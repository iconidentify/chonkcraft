import copy
import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
SPEC = importlib.util.spec_from_file_location(
    "bne_playtest_explorer", SCRIPTS / "bne_playtest_explorer.py")
explorer = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(explorer)
ADAPTER = Path(__file__).with_name("synthetic_playtest_adapter.py")
MATRIX_TEST = Path(__file__).with_name("test_bne_command_matrix.py")
MATRIX_SPEC = importlib.util.spec_from_file_location(
    "command_matrix_test", MATRIX_TEST)
matrix_test = importlib.util.module_from_spec(MATRIX_SPEC)
assert MATRIX_SPEC.loader is not None
MATRIX_SPEC.loader.exec_module(matrix_test)


class PlaytestExplorerTest(unittest.TestCase):

    def seed(self):
        return {
            "schema": explorer.SEED_SCHEMA,
            "identity": {
                "fixture": "synthetic-combat",
                "source_sha256": "a" * 64,
                "seed": 14598366,
            },
            "start_cycle": 30,
            "settle_cycles": 120,
            "setup": {"kind": "campaign", "scenario": "synthetic.pud"},
            "actors": [
                {
                    "id": 100, "player": 0, "domain": "land",
                    "capabilities": ["move", "attack", "attack-ground", "stop"],
                    "target_ids": [200],
                },
                {
                    "id": 110, "player": 0, "domain": "land",
                    "capabilities": ["follow"], "target_ids": [100],
                },
            ],
            "targets": [
                {"id": 200, "player": 1, "domain": "land", "x": 20, "y": 20},
            ],
            "points": [
                {"x": 12, "y": 10, "kind": "open", "domains": ["land"]},
                {"x": 20, "y": 20, "kind": "target", "domains": ["land"]},
                {"x": 9, "y": 10, "kind": "blocked", "domains": ["land"]},
            ],
        }

    def result(self, scenario, side, *, delay=5):
        observations = []
        for index, command in enumerate(scenario["commands"]):
            issued = command["issue_cycle"]
            observations.append({
                "command_index": index,
                "accepted": True,
                "first_progress_cycle": issued + delay,
                "terminal_cycle": issued + delay + 10,
                "terminal_reason": "fulfilled",
                "state": {"tile_x": 12, "tile_y": 10, "order": "STILL",
                          "alive": True, "on_map": True},
            })
        return {
            "schema": explorer.RESULT_SCHEMA,
            "side": side,
            "scenario_sha256": scenario["scenario_sha256"],
            "producer": {
                "name": side,
                "build_sha256": "a" * 64 if side == "native" else "b" * 64,
                "authority_sha256": (
                    explorer.PINNED_BNE_EXECUTABLE_SHA256
                    if side == "native" else None),
            },
            "observations": observations,
            "events": [],
        }

    def adapter(self, side, fault="none"):
        return explorer.Adapter(side, [
            sys.executable, str(ADAPTER), "--side", side,
            "--scenario", "{scenario}", "--output", "{output}",
            "--fault", fault,
        ])

    def test_generates_legal_timing_repeat_and_replacement_scenarios(self):
        scenarios = explorer.generate_scenarios(self.seed(), max_scenarios=500)
        self.assertGreater(len(scenarios), 40)
        self.assertEqual(
            {"single", "refuse", "repeat", "replace", "turn-boundary"},
            {item["pattern"] for item in scenarios})
        attack_cycles = {
            item["commands"][0]["issue_cycle"]
            for item in scenarios
            if item["pattern"] in {"single", "turn-boundary"}
            and item["commands"][0]["kind"] == "attack"
        }
        self.assertEqual({30, 31, 34, 35, 39, 40, 44, 45}, attack_cycles)
        turn_cycles = {
            item["commands"][0]["issue_cycle"]
            for item in scenarios if item["pattern"] == "turn-boundary"
        }
        self.assertTrue(turn_cycles)
        self.assertTrue(all(cycle % 15 in (0, 14) for cycle in turn_cycles),
                        "turn-boundary orders sit on the retail 15-cycle edge")
        for scenario in scenarios:
            explorer.validate_scenario(scenario)
            self.assertEqual("synthetic.pud", scenario["setup"]["scenario"])
            self.assertEqual(2, len(scenario["actors"]))

    def test_capabilities_prevent_inventing_illegal_orders(self):
        seed = self.seed()
        seed["actors"] = [{
            "id": 7, "player": 0, "domain": "water",
            "capabilities": ["move"],
        }]
        seed["points"].append({
            "x": 24, "y": 24, "kind": "open", "domains": ["water"],
        })
        commands = explorer.legal_commands(seed)
        self.assertTrue(commands)
        self.assertEqual({"move"}, {item["kind"] for item in commands})
        self.assertTrue(all(item["x"] == 24 for item in commands))

    def test_authenticated_fixture_becomes_a_timing_and_replacement_seed(self):
        fixture = matrix_test.fixture()
        self.addCleanup(fixture.unlink, missing_ok=True)
        seed = explorer.seed_from_fixture(fixture)
        self.assertEqual("fixture-test", seed["identity"]["fixture_id"])
        self.assertEqual(64, len(seed["identity"]["fixture_sha256"]))
        self.assertEqual({"land", "air", "water"},
                         {actor["domain"] for actor in seed["actors"]})
        self.assertTrue(any(point["kind"] == "occupied"
                            for point in seed["points"]))
        scenarios = explorer.generate_scenarios(seed, max_scenarios=500)
        self.assertTrue(any(item["pattern"] == "repeat" for item in scenarios))
        self.assertTrue(any(item["pattern"] == "replace" for item in scenarios))
        repeated = next(item for item in scenarios if item["pattern"] == "repeat")
        script = explorer.native_command_script(repeated)
        self.assertEqual(2, sum(line.startswith("cycle ")
                                for line in script.splitlines()))
        self.assertIn(repeated["scenario_sha256"], script)

    def test_native_direct_injector_refuses_an_unproved_command_family(self):
        scenario = next(item for item in explorer.generate_scenarios(
            self.seed(), max_scenarios=500)
            if item["commands"][0]["kind"] == "follow")
        with self.assertRaisesRegex(ValueError, "does not prove"):
            explorer.native_command_script(scenario)

    def test_native_direct_injector_emits_attack(self):
        scenario = next(
            item for item in explorer.generate_scenarios(
                self.seed(), max_scenarios=500)
            if all(command["kind"] == "attack" for command in item["commands"])
            and isinstance(item["commands"][0].get("target_id"), int))
        script = explorer.native_command_script(scenario)
        self.assertIn("attack unit", script)
        self.assertIn("target", script)

    def test_legal_commands_emit_defend_for_a_friend(self):
        seed = self.seed()
        seed["actors"][0]["capabilities"] = ["defend"]
        seed["actors"][0]["target_ids"] = [110]
        commands = explorer.legal_commands(seed)
        defend = [item for item in commands if item["kind"] == "defend"]
        self.assertTrue(defend, "defend produced no legal commands")
        self.assertEqual([110], [item["target_id"] for item in defend])

    def test_native_direct_injector_still_refuses_unproved_defend(self):
        seed = self.seed()
        seed["actors"][0]["capabilities"] = ["defend"]
        seed["actors"][0]["target_ids"] = [110]
        scenario = next(
            item for item in explorer.generate_scenarios(seed, max_scenarios=40)
            if item["commands"][0]["kind"] == "defend")
        with self.assertRaisesRegex(ValueError, "does not prove"):
            explorer.native_command_script(scenario)

    def test_native_direct_injector_emits_attack_ground(self):
        seed = self.seed()
        seed["actors"][0]["capabilities"] = ["attack-ground"]
        scenario = next(
            item for item in explorer.generate_scenarios(seed, max_scenarios=80)
            if all(command["kind"] == "attack-ground"
                   for command in item["commands"]))
        script = explorer.native_command_script(scenario)
        self.assertIn("attack-ground unit", script)
        self.assertIn(" x ", script)

    def test_native_direct_injector_emits_repair(self):
        seed = self.seed()
        seed["actors"][0]["capabilities"] = ["repair"]
        seed["actors"][0]["target_ids"] = [100]
        seed["targets"] = [
            {"id": 100, "player": 0, "domain": "land", "x": 22, "y": 22},
        ]
        scenario = next(
            item for item in explorer.generate_scenarios(seed, max_scenarios=80)
            if all(command["kind"] == "repair" for command in item["commands"])
            and isinstance(item["commands"][0].get("target_id"), int))
        script = explorer.native_command_script(scenario)
        self.assertIn("repair unit", script)
        self.assertIn("target", script)

    def test_native_direct_injector_emits_stop(self):
        scenario = next(
            item for item in explorer.generate_scenarios(
                self.seed(), max_scenarios=500)
            if all(command["kind"] == "stop" for command in item["commands"]))
        script = explorer.native_command_script(scenario)
        self.assertIn("stop unit", script)
        self.assertNotIn("0x436ee0", script)

    def test_native_direct_injector_emits_return_goods(self):
        seed = self.seed()
        seed["actors"][0]["capabilities"] = ["return-goods"]
        scenario = next(
            item for item in explorer.generate_scenarios(seed, max_scenarios=80)
            if all(command["kind"] == "return-goods"
                   for command in item["commands"]))
        script = explorer.native_command_script(scenario)
        self.assertIn("return-goods unit", script)
        self.assertNotIn("target", script)

    def test_native_command_registry_stays_fail_closed_without_dual_runs(self):
        registry = explorer.native_command_registry({"rows": []})
        explorer.validate_native_command_registry(registry)
        self.assertEqual(
            explorer.PINNED_BNE_EXECUTABLE_SHA256,
            registry["authority_sha256"])
        goods = registry["families"]["return-goods"]
        self.assertEqual(24, goods["evidence_hashes"]["order_function_index"])
        self.assertEqual(0, goods["dual_adapter_executed"])
        self.assertFalse(
            goods["native_execution_works"],
            "return-goods stays fail-closed until both adapters execute it")
        self.assertFalse(
            registry["families"]["production"]["native_execution_works"],
            "production 0x15 stays fail-closed")
        self.assertEqual(27, registry["families"]["repair"]["evidence_hashes"][
            "order_function_index"])
        self.assertEqual(0, registry["families"]["repair"]["dual_adapter_executed"])
        self.assertFalse(
            registry["families"]["repair"]["native_execution_works"],
            "repair stays fail-closed until both adapters execute it")
        self.assertEqual(17, registry["families"]["attack-ground"][
            "evidence_hashes"]["order_function_index"])
        self.assertFalse(
            registry["families"]["attack-ground"]["native_execution_works"],
            "attack-ground stays fail-closed until both adapters execute it")

    def test_native_command_registry_counts_only_ledger_executions(self):
        ledger = {
            "schema": "chonkcraft-bne-playtest-execution-ledger-1",
            "rows": [{
                "qualifies": True,
                "families": ["move"],
                "source": "tools/bne-harness/work/playtest-explorer/commanded/x.bnefx",
                "native_observations": [{"accepted": True}],
            }],
        }
        registry = explorer.native_command_registry(ledger)
        explorer.validate_native_command_registry(registry)
        self.assertEqual(1, registry["families"]["move"]["dual_adapter_executed"])
        self.assertTrue(registry["families"]["move"]["native_execution_works"])
        self.assertEqual(
            0, registry["families"]["return-goods"]["dual_adapter_executed"],
            "a move ledger is not a return-goods execution")

    def test_comparison_catches_a_mutated_harvest_result(self):
        seed = self.seed()
        seed["actors"][0]["capabilities"] = ["harvest"]
        seed["actors"][0]["target_ids"] = [200]
        seed["targets"][0]["type_ident"] = "unit-gold-mine"
        scenario = next(
            item for item in explorer.generate_scenarios(seed, max_scenarios=80)
            if all(command["kind"] == "harvest" for command in item["commands"]))
        native = self.result(scenario, "native")
        java = self.result(scenario, "java")
        self.assertEqual(0, explorer.compare_results(
            native, java, scenario)["difference_count"])
        java["observations"][0]["accepted"] = not java["observations"][0]["accepted"]
        report = explorer.compare_results(native, java, scenario)
        self.assertGreater(report["difference_count"], 0)
        self.assertEqual("accepted", report["first_difference"]["fields"][0])

    def test_comparison_catches_a_mutated_attack_ground_result(self):
        seed = self.seed()
        seed["actors"][0]["capabilities"] = ["attack-ground"]
        scenario = next(
            item for item in explorer.generate_scenarios(seed, max_scenarios=80)
            if all(command["kind"] == "attack-ground"
                   for command in item["commands"]))
        native = self.result(scenario, "native")
        java = self.result(scenario, "java")
        self.assertEqual(0, explorer.compare_results(
            native, java, scenario)["difference_count"])
        java["observations"][0]["accepted"] = not java["observations"][0]["accepted"]
        report = explorer.compare_results(native, java, scenario)
        self.assertGreater(report["difference_count"], 0)
        self.assertEqual("accepted", report["first_difference"]["fields"][0])

    def test_comparison_catches_a_mutated_repair_result(self):
        seed = self.seed()
        seed["actors"][0]["capabilities"] = ["repair"]
        seed["actors"][0]["target_ids"] = [100]
        seed["targets"] = [
            {"id": 100, "player": 0, "domain": "land", "x": 22, "y": 22},
        ]
        scenario = next(
            item for item in explorer.generate_scenarios(seed, max_scenarios=80)
            if all(command["kind"] == "repair" for command in item["commands"]))
        native = self.result(scenario, "native")
        java = self.result(scenario, "java")
        self.assertEqual(0, explorer.compare_results(
            native, java, scenario)["difference_count"])
        java["observations"][0]["accepted"] = not java["observations"][0]["accepted"]
        report = explorer.compare_results(native, java, scenario)
        self.assertGreater(report["difference_count"], 0)
        self.assertEqual("accepted", report["first_difference"]["fields"][0])

    def test_comparison_catches_a_mutated_attack_result(self):
        scenario = next(
            item for item in explorer.generate_scenarios(
                self.seed(), max_scenarios=500)
            if all(command["kind"] == "attack" for command in item["commands"]))
        native = self.result(scenario, "native")
        java = self.result(scenario, "java")
        self.assertEqual(0, explorer.compare_results(
            native, java, scenario)["difference_count"])
        java["observations"][0]["accepted"] = not java["observations"][0]["accepted"]
        report = explorer.compare_results(native, java, scenario)
        self.assertGreater(report["difference_count"], 0)
        self.assertEqual("accepted", report["first_difference"]["fields"][0])

    def test_comparison_catches_a_mutated_stop_result(self):
        scenario = next(
            item for item in explorer.generate_scenarios(
                self.seed(), max_scenarios=500)
            if all(command["kind"] == "stop" for command in item["commands"]))
        native = self.result(scenario, "native")
        java = self.result(scenario, "java")
        self.assertEqual(0, explorer.compare_results(
            native, java, scenario)["difference_count"])
        java["observations"][0]["accepted"] = not java["observations"][0]["accepted"]
        report = explorer.compare_results(native, java, scenario)
        self.assertGreater(report["difference_count"], 0)
        self.assertEqual("accepted", report["first_difference"]["fields"][0])

    def test_one_hundred_move_rows_are_not_five_families(self):
        scenario = next(
            item for item in explorer.generate_scenarios(
                self.seed(), max_scenarios=500)
            if {command["kind"] for command in item["commands"]} == {"move"})
        native = self.result(scenario, "native")
        java = self.result(scenario, "java")
        rows = []
        for index in range(100):
            row = explorer.execution_ledger_row(
                scenario, native, java, source=f"synthetic-{index}")
            row["command_content_sha256"] = f"{index:064x}"
            rows.append(row)
        report = explorer.execution_ledger(rows)
        self.assertEqual(100, report["distinct_command_contents"])
        self.assertEqual(["move"], report["families"])
        self.assertFalse(
            report["complete"],
            "100 move-only executions are not the five-family requirement")

    def test_comparison_uses_relative_cadence_and_observable_state(self):
        scenario = explorer.generate_scenarios(
            self.seed(), max_scenarios=1)[0]
        native = self.result(scenario, "native", delay=5)
        java = self.result(scenario, "java", delay=5)
        self.assertEqual(0, explorer.compare_results(
            native, java, scenario)["difference_count"])
        java["observations"][0]["first_progress_cycle"] += 25
        report = explorer.compare_results(native, java, scenario)
        self.assertEqual("progress_delay",
                         report["first_difference"]["fields"][0])

    def test_result_identity_mismatch_is_refused(self):
        scenario = explorer.generate_scenarios(self.seed(), max_scenarios=1)[0]
        native = self.result(scenario, "native")
        java = self.result(scenario, "java")
        java["scenario_sha256"] = "f" * 64
        with self.assertRaisesRegex(ValueError, "different scenario"):
            explorer.compare_results(native, java, scenario)

    def test_an_unpinned_native_result_is_refused(self):
        scenario = explorer.generate_scenarios(self.seed(), max_scenarios=1)[0]
        native = self.result(scenario, "native")
        java = self.result(scenario, "java")
        native["producer"]["authority_sha256"] = "0" * 64
        with self.assertRaisesRegex(ValueError, "pinned BNE"):
            explorer.compare_results(native, java, scenario)

    def test_end_to_end_rediscovers_reduces_and_seals_seeded_fault(self):
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        report = explorer.explore(
            self.seed(), self.adapter("native"),
            self.adapter("java", "attack-cadence"), Path(temporary.name),
            max_scenarios=160, max_differences=1, minimize_tests=32)
        self.assertEqual(1, report["difference_count"])
        self.assertGreater(report["coverage_token_count"], 5)
        finding = report["differences"][0]
        packet_path = Path(finding["packet"])
        self.assertTrue(packet_path.is_file())
        packet = json.loads(packet_path.read_text(encoding="utf-8"))
        self.assertEqual(explorer.PACKET_SCHEMA, packet["schema"])
        self.assertEqual("progress_delay",
                         packet["comparison"]["first_difference"]["fields"][0])
        self.assertEqual("command-cadence", packet["handoff"]["route"])
        self.assertEqual(1, finding["minimal_command_count"])
        self.assertEqual(64, len(packet["packet_sha256"]))

    def test_reducer_removes_an_unrelated_order_and_keeps_the_fault(self):
        scenarios = explorer.generate_scenarios(self.seed(), max_scenarios=500)
        scenario = next(item for item in scenarios
                        if item["pattern"] == "replace"
                        and item["commands"][0]["kind"] == "move"
                        and item["commands"][1]["kind"] == "attack")
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        minimal, native, java, report = explorer.minimize_difference(
            scenario, self.adapter("native"),
            self.adapter("java", "attack-cadence"), Path(temporary.name),
            max_tests=32)
        self.assertEqual(1, len(minimal["commands"]))
        self.assertEqual("attack", minimal["commands"][0]["kind"])
        self.assertGreater(report["proof"]["tests"], 1)
        self.assertGreater(explorer.compare_results(
            native, java, minimal)["difference_count"], 0)

    def test_empty_or_tampered_measurements_never_pass(self):
        scenario = explorer.generate_scenarios(self.seed(), max_scenarios=1)[0]
        native = self.result(scenario, "native")
        java = self.result(scenario, "java")
        native["observations"] = []
        with self.assertRaisesRegex(ValueError, "no command observations"):
            explorer.compare_results(native, java, scenario)
        tampered = copy.deepcopy(scenario)
        tampered["commands"][0]["issue_cycle"] += 1
        with self.assertRaisesRegex(ValueError, "identity changed"):
            explorer.compare_results(self.result(scenario, "native"),
                                     self.result(scenario, "java"), tampered)

    def test_group_and_congestion_cover_shared_movers(self):
        seed = self.seed()
        seed["actors"].append({
            "id": 120, "player": 0, "domain": "land",
            "capabilities": ["move"], "target_ids": [],
        })
        scenarios = explorer.generate_scenarios(seed, max_scenarios=800)
        patterns = {item["pattern"] for item in scenarios}
        self.assertIn("group", patterns)
        self.assertIn("congestion", patterns)
        group = next(item for item in scenarios if item["pattern"] == "group")
        self.assertGreaterEqual(len(group["commands"]), 2)
        self.assertEqual(1, len({command["issue_cycle"]
                                 for command in group["commands"]}))
        congested = next(item for item in scenarios
                         if item["pattern"] == "congestion")
        self.assertEqual(congested["commands"][0]["kind"],
                         congested["commands"][1]["kind"])
        first, second = congested["commands"]
        if "x" in first:
            self.assertEqual((first["x"], first["y"]), (second["x"], second["y"]))
        else:
            self.assertEqual(first["target_id"], second["target_id"])

    def test_blocked_destinations_are_generated_as_refusals(self):
        scenarios = explorer.generate_scenarios(self.seed(), max_scenarios=80)
        refused = [item for item in scenarios if item["pattern"] == "refuse"]
        self.assertTrue(refused)
        self.assertTrue(all(command.get("point_kind") in explorer.REFUSED_POINTS
                            for item in refused for command in item["commands"]))

    def test_production_families_cover_unaffordable_and_shared_targets(self):
        seed = self.seed()
        seed["actors"] = [
            {
                "id": 300, "player": 0, "domain": "land",
                "capabilities": ["train", "research", "attack-move"],
                "type_index": 2, "afford": False, "target_ids": [],
            },
            {
                "id": 301, "player": 0, "domain": "land",
                "capabilities": ["train", "attack-move", "harvest"],
                "type_index": 2, "target_ids": [400],
            },
            {
                "id": 302, "player": 0, "domain": "land",
                "capabilities": ["harvest", "attack-move"],
                "target_ids": [400],
            },
        ]
        seed["targets"] = [
            {"id": 400, "player": 0, "domain": "land", "x": 8, "y": 8},
        ]
        scenarios = explorer.generate_scenarios(seed, max_scenarios=1200)
        kinds = {command["kind"] for item in scenarios for command in item["commands"]}
        self.assertTrue({"train", "research", "attack-move", "harvest"} <= kinds,
                        "production and harvest must appear in the generated set")
        unaffordable = [
            item for item in scenarios if item["pattern"] == "refuse"
            and item["commands"][0].get("point_kind") == "unaffordable"
        ]
        self.assertTrue(unaffordable,
                        "an unpaid train or research is a refusal, not a pass")
        self.assertTrue(any(item["commands"][0]["kind"] in {"train", "research"}
                            for item in unaffordable))
        harvest_jams = [
            item for item in scenarios if item["pattern"] == "congestion"
            and item["commands"][0]["kind"] == "harvest"
        ]
        self.assertTrue(harvest_jams,
                        "two harvesters on one depot are a congestion case")
        attack_moves = [
            item for item in scenarios if item["pattern"] == "congestion"
            and item["commands"][0]["kind"] == "attack-move"
        ]
        self.assertTrue(attack_moves,
                        "two attack-moves onto one square are a congestion case")
        train_replaces = [
            item for item in scenarios if item["pattern"] == "replace"
            and any(command["kind"] == "train" for command in item["commands"])
        ]
        self.assertTrue(train_replaces,
                        "a hall that can train and attack-move must replace")
        train_jams = [
            item for item in scenarios if item["pattern"] == "congestion"
            and item["commands"][0]["kind"] == "train"
        ]
        self.assertTrue(train_jams,
                        "two halls training the same unit are a congestion case")

    def test_typed_buttons_do_not_make_grunts_harvest_or_farms_train(self):
        caps = explorer.load_typed_command_capabilities()
        self.assertGreaterEqual(len(caps), 20,
                                "typed catalog must parse real button rows")
        self.assertIn("harvest", caps["unit-peon"])
        self.assertIn("harvest", caps["unit-peasant"])
        self.assertIn("repair", caps["unit-peon"])
        self.assertIn("repair", caps["unit-peasant"])
        self.assertNotIn("harvest", caps.get("unit-grunt", set()),
                         "a grunt has no harvest button")
        self.assertNotIn("harvest", caps.get("unit-footman", set()),
                         "a footman has no harvest button")
        self.assertNotIn("repair", caps.get("unit-grunt", set()),
                         "a grunt has no repair button")
        self.assertNotIn("repair", caps.get("unit-footman", set()),
                         "a footman has no repair button")
        self.assertNotIn("train", caps.get("unit-pig-farm", set()),
                         "a pig farm has no train button")
        self.assertNotIn("train", caps.get("unit-farm", set()),
                         "a farm has no train button")
        self.assertNotIn("research", caps.get("unit-pig-farm", set()),
                         "a pig farm has no research button")
        self.assertNotIn("research", caps.get("unit-farm", set()),
                         "a farm has no research button")
        self.assertIn("train", caps["unit-great-hall"])
        self.assertIn("research", caps["unit-orc-blacksmith"])

    def test_idle_seed_uses_typed_capabilities_not_type_ranges(self):
        corpus = (
            Path(__file__).resolve().parents[1]
            / "work/corpus/campaign-1800/cases"
        )
        orc = corpus / "retail-orc-01-idle.bnefx"
        if not orc.is_file():
            self.skipTest("authenticated Orc 1 idle fixture is missing")
        seed = explorer.seed_from_idle_fixture(orc)
        by_ident = {}
        for actor in seed["actors"]:
            ident = actor.get("type_ident")
            if ident:
                by_ident.setdefault(ident, set()).update(actor["capabilities"])
        grunt_actors = [
            actor for actor in seed["actors"]
            if actor.get("type_ident") == "unit-grunt"
        ]
        self.assertTrue(grunt_actors, "Orc 1 has opening-line grunts")
        for actor in grunt_actors:
            self.assertNotIn("harvest", actor["capabilities"],
                             "a grunt must not become a harvester")
            self.assertNotIn("repair", actor["capabilities"],
                             "a grunt must not become a repairer")
        self.assertIn("harvest", by_ident.get("unit-peon", set()),
                      "Orc 1 must declare a typed peon harvest")
        self.assertIn("repair", by_ident.get("unit-peon", set()),
                      "Orc 1 must declare a typed peon repair")
        for actor in seed["actors"]:
            if actor.get("type_ident") in {"unit-pig-farm", "unit-farm"}:
                self.assertNotIn("train", actor["capabilities"],
                                 "a farm must not become a trainer")
                self.assertNotIn("research", actor["capabilities"],
                                 "a farm must not become a researcher")
        if "unit-great-hall" in by_ident:
            self.assertIn("train", by_ident["unit-great-hall"])

    def test_generated_inventory_is_not_dual_adapter_execution(self):
        corpus = (
            Path(__file__).resolve().parents[1]
            / "work/corpus/campaign-1800/cases"
        )
        fixtures = [
            corpus / "retail-human-01-idle.bnefx",
            corpus / "retail-orc-01-idle.bnefx",
            corpus / "retail-xhuman-12-idle.bnefx",
        ]
        if not all(path.is_file() for path in fixtures):
            self.skipTest("authenticated campaign-1800 idle fixtures are missing")
        seeds = [explorer.seed_from_idle_fixture(path) for path in fixtures]
        report = explorer.coverage_inventory(seeds, max_scenarios=80)
        self.assertGreaterEqual(report["generated_scenarios"], 1)
        self.assertEqual(
            report.get("dual_adapter_executed_scenarios", 0), 0,
            "generation without adapters is not dual-adapter execution")
        self.assertFalse(
            report.get("complete", True),
            "generated inventory is not the 100 dual-adapter requirement")
        self.assertEqual(report.get("executed_families"), [],
                         "generation names no dual-adapter families")

    def test_inventory_copies_ledger_count_without_becoming_complete(self):
        corpus = (
            Path(__file__).resolve().parents[1]
            / "work/corpus/campaign-1800/cases"
        )
        ledger_path = (
            Path(__file__).resolve().parents[1]
            / "work/playtest-explorer/execution-ledger.json"
        )
        fixture = corpus / "retail-orc-01-idle.bnefx"
        if not fixture.is_file() or not ledger_path.is_file():
            self.skipTest("idle fixture or execution ledger is missing")
        ledger = json.loads(ledger_path.read_text(encoding="utf-8"))
        report = explorer.coverage_inventory(
            [explorer.seed_from_idle_fixture(fixture)],
            max_scenarios=20, ledger=ledger)
        self.assertGreaterEqual(report["generated_scenarios"], 1)
        self.assertGreaterEqual(
            report["dual_adapter_executed_scenarios"], 100,
            "the ledger's executed count must appear beside generation")
        self.assertNotEqual(
            report["generated_scenarios"],
            report["dual_adapter_executed_scenarios"],
            "generation and dual-adapter execution are different numbers")
        self.assertIn("repair", report["executed_families"])
        self.assertFalse(
            report["complete"],
            "attaching a ledger must not mark generated inventory complete")

    def test_a_commanded_move_then_stop_fixture_keeps_both_orders(self):
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/playtest-explorer/commanded/stop-1/00.bnefx"
        )
        if not fixture.is_file():
            self.skipTest("authenticated Orc 1 move-then-stop fixture is missing")
        seed = explorer.seed_from_commanded_fixture(fixture)
        scenario = explorer.scenario_from_commanded_seed(seed)
        kinds = [command["kind"] for command in scenario["commands"]]
        self.assertEqual(["move", "stop"], kinds)
        self.assertEqual(1594, scenario["commands"][1]["unit_id"])
        self.assertEqual(20, scenario["commands"][1]["issue_cycle"])

    def test_a_commanded_seed_becomes_the_exact_captured_scenario(self):
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/traces/bne-fixture-v1.1-orc-01.txt.bnefx"
        )
        if not fixture.is_file():
            self.skipTest("authenticated Orc 1 move fixture is missing")
        seed = explorer.seed_from_commanded_fixture(fixture)
        scenario = explorer.scenario_from_commanded_seed(seed)
        explorer.validate_scenario(scenario)
        command = scenario["commands"][0]
        self.assertEqual("move", command["kind"])
        self.assertEqual(1594, command["unit_id"])
        self.assertEqual((30, 18), (command["x"], command["y"]))
        self.assertEqual(5, command["issue_cycle"])
        self.assertEqual(
            explorer.command_content_identity(scenario),
            explorer.command_content_identity(scenario))

    def test_an_empty_execution_ledger_is_not_complete(self):
        report = explorer.execution_ledger([])
        self.assertEqual(0, report["dual_adapter_executed_scenarios"])
        self.assertFalse(report["complete"])

    def test_five_families_and_a_hundred_rows_are_still_not_parity(self):
        scenario = next(
            item for item in explorer.generate_scenarios(
                self.seed(), max_scenarios=500)
            if {command["kind"] for command in item["commands"]} == {"move"})
        native = self.result(scenario, "native")
        java = self.result(scenario, "java")
        rows = []
        families = ["move", "attack", "stop", "patrol", "harvest"]
        for index in range(100):
            row = explorer.execution_ledger_row(
                scenario, native, java, source=f"synthetic-{index}")
            row["command_content_sha256"] = f"{index:064x}"
            row["families"] = [families[index % len(families)]]
            rows.append(row)
        report = explorer.execution_ledger(rows)
        self.assertTrue(report["executed_threshold_met"])
        self.assertFalse(
            report["complete"],
            "both adapters executed is not complete and is not parity")
        split = explorer.split_command_report(
            report, generated_scenarios=240)
        self.assertEqual(240, split["generated"])
        self.assertEqual(split["executed_native"], split["executed_java"])
        self.assertGreaterEqual(split["comparable"], 100)
        self.assertEqual(split["exact_parity"], split["comparable"])
        self.assertEqual(0, split["materially_divergent"])
        self.assertFalse(split["complete"])
        self.assertFalse(split["parity"])

    def test_split_report_separates_an_accepted_mismatch(self):
        scenario = explorer.generate_scenarios(
            self.seed(), max_scenarios=1)[0]
        native = self.result(scenario, "native")
        java = self.result(scenario, "java")
        java["observations"][0]["accepted"] = False
        row = explorer.execution_ledger_row(
            scenario, native, java, source="mismatch.bnefx")
        split = explorer.split_command_report(
            explorer.execution_ledger([row]), generated_scenarios=10)
        self.assertEqual(1, split["comparable"])
        self.assertEqual(0, split["exact_parity"])
        self.assertEqual(1, split["materially_divergent"])
        self.assertFalse(split["parity"])

    def test_worklist_clusters_systemic_differences_and_tracks_regressions(self):
        scenario = next(
            item for item in explorer.generate_scenarios(
                self.seed(), max_scenarios=500)
            if len(item["commands"]) == 1
            and item["commands"][0]["kind"] == "move")
        later = explorer._scenario_with_commands(scenario, [{
            **scenario["commands"][0],
            "issue_cycle": scenario["commands"][0]["issue_cycle"] + 1,
        }])

        def row(item, native_delay, java_delay, source):
            native = self.result(item, "native", delay=native_delay)
            java = self.result(item, "java", delay=java_delay)
            return explorer.execution_ledger_row(
                item, native, java, source=source)

        baseline = explorer.execution_ledger([
            row(scenario, 5, 8, "fixed.bnefx"),
            row(later, 5, 5, "regressed.bnefx"),
        ])
        current = explorer.execution_ledger([
            row(scenario, 5, 5, "fixed.bnefx"),
            row(later, 5, 9, "regressed.bnefx"),
        ])
        inventory = {
            "generated_scenarios": 10,
            "families": ["move", "attack-move"],
            "patterns": ["single", "group"],
        }
        report = explorer.command_worklist(
            current, inventory=inventory, baseline=baseline,
            expected_java_sha256="b" * 64)

        self.assertEqual(1, report["fleet"]["exact"])
        self.assertEqual(1, report["fleet"]["divergent"])
        self.assertEqual(1, len(report["clusters"]))
        self.assertEqual(["progress_delay", "terminal_delay"],
                         report["clusters"][0]["fields"])
        self.assertEqual("movement-and-settle-cadence",
                         report["clusters"][0]["route"])
        self.assertEqual(1, report["baseline_delta"]["fixed"])
        self.assertEqual(1, report["baseline_delta"]["regressed"])
        self.assertFalse(report["gate"]["no_regressions"])
        self.assertTrue(report["gate"]["current_identity"])
        self.assertEqual(["attack-move"],
                         report["coverage"]["generated_not_executed"])
        self.assertEqual(0, report["coverage"]["queued_commands"])
        markdown = explorer.command_worklist_markdown(report)
        self.assertIn("1 / 2 exact", markdown)
        self.assertIn("movement-and-settle-cadence", markdown)

    def test_worklist_rejects_a_stale_java_producer_identity(self):
        scenario = explorer.generate_scenarios(
            self.seed(), max_scenarios=1)[0]
        row = explorer.execution_ledger_row(
            scenario, self.result(scenario, "native"),
            self.result(scenario, "java"), source="old-engine.bnefx")
        report = explorer.command_worklist(
            explorer.execution_ledger([row]),
            expected_java_sha256="c" * 64)
        self.assertFalse(report["gate"]["current_identity"])
        self.assertEqual(["b" * 64],
                         report["authority"]["stale_java_producer_hashes"])


if __name__ == "__main__":
    unittest.main()
