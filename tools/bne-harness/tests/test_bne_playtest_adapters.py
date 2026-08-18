import importlib.util
import json
from pathlib import Path
import sys
import tempfile
import unittest
import zipfile


SCRIPTS = Path(__file__).parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))
EXPLORER_SPEC = importlib.util.spec_from_file_location(
    "bne_playtest_explorer", SCRIPTS / "bne_playtest_explorer.py")
explorer = importlib.util.module_from_spec(EXPLORER_SPEC)
assert EXPLORER_SPEC.loader is not None
EXPLORER_SPEC.loader.exec_module(explorer)
NATIVE_SPEC = importlib.util.spec_from_file_location(
    "bne_playtest_native_adapter", SCRIPTS / "bne_playtest_native_adapter.py")
native = importlib.util.module_from_spec(NATIVE_SPEC)
assert NATIVE_SPEC.loader is not None
NATIVE_SPEC.loader.exec_module(native)
JAVA_SPEC = importlib.util.spec_from_file_location(
    "bne_playtest_java_adapter", SCRIPTS / "bne_playtest_java_adapter.py")
java = importlib.util.module_from_spec(JAVA_SPEC)
assert JAVA_SPEC.loader is not None
JAVA_SPEC.loader.exec_module(java)
MATRIX_TEST = Path(__file__).with_name("test_bne_command_matrix.py")
MATRIX_SPEC = importlib.util.spec_from_file_location(
    "command_matrix_test", MATRIX_TEST)
matrix_test = importlib.util.module_from_spec(MATRIX_SPEC)
assert MATRIX_SPEC.loader is not None
MATRIX_SPEC.loader.exec_module(matrix_test)

COMMANDED = (
    Path(__file__).resolve().parents[1]
    / "work/traces/bne-fixture-v1.1-orc-01.txt.bnefx"
)
PINNED = explorer.PINNED_BNE_EXECUTABLE_SHA256


def _slot(*, typ: int, remaining: int, flags: int) -> bytes:
    raw = bytearray(native.BULLET_BYTES)
    raw[0x20:0x22] = remaining.to_bytes(2, "little", signed=True)
    raw[0x34] = typ
    raw[native.BULLET_FLAGS] = flags
    return bytes(raw)


class PlaytestAdapterTest(unittest.TestCase):

    def test_commanded_fixture_becomes_an_exact_move_seed(self):
        self.assertTrue(COMMANDED.is_file(), "authenticated Orc 1 move fixture")
        seed = explorer.seed_from_commanded_fixture(COMMANDED)
        self.assertEqual(PINNED, seed["identity"]["authority_sha256"])
        self.assertEqual(1, len(seed["actors"]))
        self.assertEqual(1594, seed["actors"][0]["id"])
        self.assertEqual((25, 18), (seed["actors"][0]["x"], seed["actors"][0]["y"]))
        self.assertEqual({"move"}, set(seed["actors"][0]["capabilities"]))
        scenarios = explorer.generate_scenarios(seed, max_scenarios=1)
        self.assertEqual(1, len(scenarios))
        command = scenarios[0]["commands"][0]
        self.assertEqual("move", command["kind"])
        self.assertEqual(1594, command["unit_id"])
        self.assertEqual((30, 18), (command["x"], command["y"]))
        self.assertEqual(5, command["issue_cycle"])

    def test_native_adapter_reports_physical_progress_from_sealed_fixture(self):
        self.assertTrue(COMMANDED.is_file(), "authenticated Orc 1 move fixture")
        seed = explorer.seed_from_commanded_fixture(COMMANDED)
        scenario = explorer.generate_scenarios(seed, max_scenarios=1)[0]
        result = native.run_from_fixture(
            scenario, COMMANDED, PINNED, "a" * 64)
        explorer.validate_result(result, scenario, "native")
        self.assertEqual(PINNED, result["producer"]["authority_sha256"])
        observation = result["observations"][0]
        self.assertTrue(observation["accepted"],
                        "the peon accepted the commanded move")
        self.assertEqual(9, observation["first_progress_cycle"],
                         "the peon's cycle-8 route reservation is not a visible "
                         "step; its first physical pixel moves on retail cycle 9")
        self.assertEqual("settled", observation["terminal_reason"])
        self.assertEqual(27, observation["state"]["tile_x"])
        self.assertEqual(17, observation["state"]["tile_y"])
        self.assertEqual("STILL", observation["state"]["order"])

    def test_native_progress_ignores_a_tile_reservation_without_a_pixel_step(self):
        before = {
            "alive": True, "tile_x": 20, "tile_y": 31,
            "px": 640, "py": 992, "order": "MOVE", "hit_points": 60,
        }
        reserved = {
            **before, "tile_x": 21, "tile_y": 30,
        }
        stepped = {
            **reserved, "px": 643, "py": 989,
        }

        self.assertFalse(native.progressed(before, reserved, "move"),
                         "a route reservation with unchanged IX/IY is telemetry, "
                         "not player-visible progress")
        self.assertTrue(native.progressed(before, stepped, "move"),
                        "the first physical pixel is visible progress")

    def test_native_sequence_observes_each_command_through_the_sealed_horizon(self):
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/playtest-explorer/commanded/stand-ground-1/01.bnefx"
        )
        if not fixture.is_file():
            self.skipTest("authenticated move-then-hold fixture is missing")
        seed = explorer.seed_from_commanded_fixture(fixture)
        scenario = explorer.scenario_from_commanded_seed(seed)
        result = native.run_from_fixture(scenario, fixture, PINNED, "a" * 64)

        self.assertEqual(80, result["observations"][0]["terminal_cycle"],
                         "the first command must retain the sequence's sealed horizon")
        self.assertEqual(24, result["observations"][1]["terminal_cycle"],
                         "the replacement itself still fulfills when order 15 pops")

    def test_human13_constructor_without_slot_still_names_the_commanded_axe(self):
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/combat-lifecycle/native/combat-ranged-human13-1800.bnefx"
        )
        self.assertTrue(fixture.is_file(), "authenticated Human 13 ranged fixture")
        created = [
            item for item in native.projectile_states_by_cycle(fixture).get(18, [])
            if item.get("source_id") == 1494 and item.get("present")
        ]
        self.assertEqual(1, len(created),
                         "retail's cycle-18 constructor is axethrower 1494; "
                         "the older projectile-created line omits the slot, "
                         "but AUXL still records that birth")
        self.assertEqual(16, created[0]["type_code"],
                         "unit 1494 fires a type-16 axe")
        self.assertEqual(1493, created[0]["target_id"],
                         "the commanded axe is aimed at knight 1493")
        arrows = [
            item for item in native.projectile_states_by_cycle(fixture).get(7, [])
            if item.get("type_code") == 15 and item.get("present")
        ]
        self.assertEqual(1, len(arrows),
                         "fixture cycle 7 has one tower arrow")
        self.assertEqual(1481, arrows[0]["source_id"],
                         "the fixed constructor is not hooked; +0x30 still "
                         "names guard tower 1481 once a hooked birth has "
                         "established the unit-pool base")
        self.assertEqual(1490, arrows[0]["target_id"],
                         "the tower arrow is aimed at knight 1490")

    def test_native_adapter_counts_in_flight_shots_on_the_terminal_cycle(self):
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/playtest-explorer/commanded/batch-1/24.bnefx"
        )
        self.assertTrue(fixture.is_file(), "authenticated Human 13 north click")
        seed = explorer.seed_from_commanded_fixture(fixture)
        scenario = explorer.scenario_from_commanded_seed(seed)
        result = native.run_from_fixture(
            scenario, fixture, PINNED, "a" * 64)
        explorer.validate_result(result, scenario, "native")
        observation = result["observations"][0]
        self.assertEqual(40, observation["terminal_cycle"],
                         "the axethrower is Still on 98,55 at retail 40")
        self.assertEqual(2, observation["state"]["missile_count"],
                         "retail still has the landed rock and its impact "
                         "sprite at that Still visit, not an empty pool")

    def test_type_21_is_live_until_free_even_without_flag_04(self):
        # Human 7's impact sprite is rem=0, flags 0x02 then 0x00, never
        # 0x04. Human 13's three persistent occupants use the same rem
        # and flag pattern on types 19/20/28 and are not live shots.
        self.assertTrue(native.active_projectile_count(
            _slot(typ=21, remaining=0, flags=0x02)),
            "type 21 is live from occupancy, including the 0x02 birth visit")
        self.assertTrue(native.active_projectile_count(
            _slot(typ=21, remaining=0, flags=0x00)),
            "type 21 stays live after the birth flag drops")
        self.assertTrue(native.active_projectile_count(
            _slot(typ=21, remaining=0, flags=0x04)),
            "type 21 is still live when Human 13 later sets 0x04")
        self.assertFalse(native.active_projectile_count(
            _slot(typ=21, remaining=0, flags=native.BULLET_FREE)),
            "FREE ends the impact sprite")
        self.assertFalse(native.active_projectile_count(
            _slot(typ=19, remaining=0, flags=0x00)),
            "type 19 is a persistent occupant, not a live shot")
        self.assertFalse(native.active_projectile_count(
            _slot(typ=20, remaining=0, flags=0x00)),
            "type 20 is a persistent occupant, not a live shot")
        self.assertFalse(native.active_projectile_count(
            _slot(typ=28, remaining=0, flags=0x02)),
            "type 28 is a persistent occupant, not a live shot")
        self.assertTrue(native.active_projectile_count(
            _slot(typ=13, remaining=-2, flags=0x00)),
            "a landed rock with remaining distance is still live")
        self.assertTrue(native.active_projectile_count(
            _slot(typ=13, remaining=0, flags=0x04)),
            "a detonating rock is live by flag 0x04")

    def test_human07_impact_sprite_is_live_from_birth(self):
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/playtest-explorer/commanded/attack-ground-1/00.bnefx"
        )
        self.assertTrue(fixture.is_file(),
                        "authenticated Human 7 attack-ground fixture")
        counts = native.projectile_counts_by_cycle(fixture)
        self.assertGreater(len(counts), 0, "the sealed fixture has AUXL cycles")
        self.assertEqual(1, counts[33],
                         "the eastern rock is still occupying the pool")
        self.assertEqual(1, counts[34],
                         "retail replaces that rock with a type-21 impact "
                         "sprite on the same cycle")
        self.assertEqual(1, counts[47],
                         "the impact sprite stays allocated through its "
                         "final visible hold")
        self.assertEqual(0, counts[48],
                         "FREE on the next cycle empties the live pool")
        born = [
            item for item in native.projectile_states_by_cycle(fixture).get(34, [])
            if item.get("type_code") == 21 and item.get("present")
        ]
        self.assertEqual(1, len(born),
                         "cycle 34's live occupant is the impact sprite")
        self.assertIsNone(born[0]["source_id"],
                          "Human 7's impact has no source pointer")
        self.assertEqual((432, 2096), (born[0]["x"], born[0]["y"]),
                         "the impact sits on the rock's last pixel")
        self.assertEqual(0, born[0]["remaining"],
                         "type 21 remaining is 0 from birth")

    def test_human13_impact_is_live_before_flag_04(self):
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/combat-lifecycle/native/combat-ranged-human13-1800.bnefx"
        )
        self.assertTrue(fixture.is_file(), "authenticated Human 13 ranged fixture")
        counts = native.projectile_counts_by_cycle(fixture)
        self.assertEqual(0, counts[1],
                         "types 19/20/28 occupy the pool from cycle 1 "
                         "and are not live shots")
        born = [
            item for item in native.projectile_states_by_cycle(fixture).get(35, [])
            if item.get("type_code") == 21 and item.get("present")
        ]
        self.assertEqual(1, len(born),
                         "Human 13's type-21 impact is live at birth, "
                         "two cycles before flag 0x04")
        self.assertEqual((3984, 968), (born[0]["x"], born[0]["y"]),
                         "the impact sits on catapult 1479's last rock pixel")
        self.assertEqual(0, born[0]["remaining"],
                         "type 21 remaining is 0 from birth")

    def test_native_adapter_refuses_a_mismatched_command_stream(self):
        self.assertTrue(COMMANDED.is_file(), "authenticated Orc 1 move fixture")
        seed = explorer.seed_from_commanded_fixture(COMMANDED)
        scenario = explorer.generate_scenarios(seed, max_scenarios=1)[0]
        scenario["commands"][0]["x"] = 12
        scenario["scenario_sha256"] = explorer.digest({
            key: value for key, value in scenario.items()
            if key != "scenario_sha256"
        })
        with self.assertRaisesRegex(ValueError, "do not match"):
            native.run_from_fixture(scenario, COMMANDED, PINNED, "a" * 64)

    def test_native_adapter_refuses_an_unpinned_fixture(self):
        fixture = matrix_test.fixture()
        self.addCleanup(fixture.unlink, missing_ok=True)
        with zipfile.ZipFile(fixture, "a") as archive:
            archive.writestr(
                "commands.txt",
                "cycle 5 move unit 0 x 12 y 8\n")
        seed = {
            "schema": explorer.SEED_SCHEMA,
            "identity": {"fixture": "unpinned"},
            "setup": {"kind": "sealed-fixture", "fixture": str(fixture),
                      "scenario": r"Campaign\Orc\Orc01.pud"},
            "start_cycle": 5,
            "settle_cycles": 10,
            "actors": [{"id": 0, "player": 0, "domain": "land",
                        "capabilities": ["move"], "x": 8, "y": 8}],
            "targets": [],
            "points": [{"x": 12, "y": 8, "kind": "open", "domains": ["land"]}],
        }
        scenario = explorer.generate_scenarios(seed, max_scenarios=1)[0]
        with self.assertRaisesRegex(ValueError, "pinned BNE"):
            native.run_from_fixture(scenario, fixture, PINNED, "a" * 64)

    def test_native_adapter_refuses_an_unauthenticated_local_executable(self):
        with tempfile.TemporaryDirectory() as directory:
            fake = Path(directory) / "Warcraft II BNE.exe"
            fake.write_bytes(b"not-bne")
            with self.assertRaisesRegex(ValueError, "pinned BNE"):
                native.authenticate_executable(fake)

    def test_empty_native_state_is_refused(self):
        self.assertTrue(COMMANDED.is_file(), "authenticated Orc 1 move fixture")
        seed = explorer.seed_from_commanded_fixture(COMMANDED)
        scenario = explorer.generate_scenarios(seed, max_scenarios=1)[0]
        with self.assertRaisesRegex(ValueError, "empty"):
            native.observe_commands(scenario, [])

    def test_java_adapter_source_issues_through_command_applier(self):
        source = Path(__file__).resolve().parents[3] / (
            "desktop/src/main/java/net/chonkbase/chonkcraft/desktop/"
            "BnePlaytestAdapter.java")
        text = source.read_text(encoding="utf-8")
        self.assertIn("CommandApplier", text)
        self.assertIn("PlayerIntentJournal", text)
        self.assertIn("issueAccepted", text)
        self.assertNotIn("world.orderCommandMove", text)

    def test_java_adapter_refuses_a_missing_pack(self):
        with tempfile.TemporaryDirectory() as directory:
            missing = Path(directory) / "missing.chonkpack"
            with self.assertRaisesRegex(ValueError, "missing"):
                java.resolve_pack(missing)

    def test_both_production_adapters_execute_the_sealed_orc_one_move(self):
        pack = Path.home() / (
            ".chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack")
        if not COMMANDED.is_file() or not pack.is_file():
            self.skipTest("commanded Orc 1 fixture or BNE pack missing")
        seed = explorer.seed_from_commanded_fixture(COMMANDED)
        scenario = explorer.generate_scenarios(seed, max_scenarios=1)[0]
        native_result = native.run_from_fixture(
            scenario, COMMANDED, PINNED, "a" * 64)
        explorer.validate_result(native_result, scenario, "native")
        script = Path(__file__).parents[1] / "scripts" / "bne_playtest_java_adapter.py"
        with tempfile.TemporaryDirectory() as directory:
            adapter = explorer.Adapter("java", [
                sys.executable, str(script),
                "--scenario", "{scenario}", "--output", "{output}",
                "--asset-pack", str(pack), "--skip-build",
            ], timeout=180.0)
            java_result = adapter.run(scenario, Path(directory))
        explorer.validate_result(java_result, scenario, "java")
        self.assertTrue(native_result["observations"][0]["accepted"])
        self.assertTrue(java_result["observations"][0]["accepted"])
        row = explorer.execution_ledger_row(
            scenario, native_result, java_result, source=str(COMMANDED))
        ledger = explorer.execution_ledger([row])
        self.assertEqual(1, ledger["dual_adapter_executed_scenarios"])
        self.assertEqual(["move"], ledger["families"])
        self.assertFalse(ledger["complete"],
                         "one MOVE is not the 100-scenario threshold")

    def test_native_treats_an_applied_occupied_move_as_accepted(self):
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/playtest-explorer/commanded"
            / "command-campaign-human-human02-pud-ground-occupied.bnefx"
        )
        if not fixture.is_file():
            self.skipTest("occupied Human 2 move fixture is missing")
        seed = explorer.seed_from_commanded_fixture(fixture)
        scenario = explorer.scenario_from_commanded_seed(seed)
        result = native.run_from_fixture(scenario, fixture, PINNED, "a" * 64)
        explorer.validate_result(result, scenario, "native")
        self.assertTrue(result["observations"][0]["accepted"],
                        "GiveOrder applied the occupied click even though "
                        "the hull never left its square")

    def test_java_refuses_an_enemy_patrol_the_injector_rejected(self):
        pack = Path.home() / (
            ".chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack")
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/playtest-explorer/commanded/patrol-1/02.bnefx"
        )
        if not fixture.is_file() or not pack.is_file():
            self.skipTest("enemy patrol fixture or BNE pack missing")
        seed = explorer.seed_from_commanded_fixture(fixture)
        scenario = explorer.scenario_from_commanded_seed(seed)
        native_result = native.run_from_fixture(
            scenario, fixture, PINNED, "a" * 64)
        script = Path(__file__).parents[1] / "scripts" / "bne_playtest_java_adapter.py"
        with tempfile.TemporaryDirectory() as directory:
            adapter = explorer.Adapter("java", [
                sys.executable, str(script),
                "--scenario", "{scenario}", "--output", "{output}",
                "--asset-pack", str(pack), "--skip-build",
            ], timeout=180.0)
            java_result = adapter.run(scenario, Path(directory))
        explorer.validate_result(native_result, scenario, "native")
        explorer.validate_result(java_result, scenario, "java")
        self.assertFalse(native_result["observations"][0]["accepted"],
                         "native GiveOrder refuses a unit that is not local")
        self.assertEqual("rejected", native_result["observations"][0]["terminal_reason"],
                         "a refused GiveOrder is rejected, not acknowledged-no-progress")
        self.assertFalse(java_result["observations"][0]["accepted"],
                         "Java must not issue the enemy's patrol")
        self.assertEqual("rejected", java_result["observations"][0]["terminal_reason"],
                         "Java must label the enemy patrol rejected")

    def test_both_adapters_accept_a_commanded_orc_one_attack(self):
        pack = Path.home() / (
            ".chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack")
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/playtest-explorer/commanded/attack-1/00.bnefx"
        )
        if not fixture.is_file() or not pack.is_file():
            self.skipTest("commanded Orc 1 attack fixture or BNE pack missing")
        seed = explorer.seed_from_commanded_fixture(fixture)
        scenario = explorer.scenario_from_commanded_seed(seed)
        native_result = native.run_from_fixture(
            scenario, fixture, PINNED, "a" * 64)
        explorer.validate_result(native_result, scenario, "native")
        script = Path(__file__).parents[1] / "scripts" / "bne_playtest_java_adapter.py"
        with tempfile.TemporaryDirectory() as directory:
            adapter = explorer.Adapter("java", [
                sys.executable, str(script),
                "--scenario", "{scenario}", "--output", "{output}",
                "--asset-pack", str(pack), "--skip-build",
            ], timeout=180.0)
            java_result = adapter.run(scenario, Path(directory))
        explorer.validate_result(java_result, scenario, "java")
        self.assertTrue(native_result["observations"][0]["accepted"],
                        "the grunt accepted the commanded attack")
        self.assertTrue(java_result["observations"][0]["accepted"],
                        "Java must attack the paired enemy, not target id 0")
        self.assertEqual("ATTACK", java_result["observations"][0]["state"]["order"])

    def test_both_adapters_accept_a_laden_return_goods(self):
        pack = Path.home() / (
            ".chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack")
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/playtest-explorer/commanded/return-goods-1/02.bnefx"
        )
        if not fixture.is_file() or not pack.is_file():
            self.skipTest("laden return-goods fixture or BNE pack missing")
        seed = explorer.seed_from_commanded_fixture(fixture)
        scenario = explorer.scenario_from_commanded_seed(seed)
        native_result = native.run_from_fixture(
            scenario, fixture, PINNED, "a" * 64)
        script = Path(__file__).parents[1] / "scripts" / "bne_playtest_java_adapter.py"
        with tempfile.TemporaryDirectory() as directory:
            adapter = explorer.Adapter("java", [
                sys.executable, str(script),
                "--scenario", "{scenario}", "--output", "{output}",
                "--asset-pack", str(pack), "--skip-build",
            ], timeout=180.0)
            java_result = adapter.run(scenario, Path(directory))
        explorer.validate_result(native_result, scenario, "native")
        explorer.validate_result(java_result, scenario, "java")
        kinds = [command["kind"] for command in scenario["commands"]]
        self.assertEqual(["harvest", "return-goods"], kinds)
        self.assertTrue(native_result["observations"][1]["accepted"],
                        "GiveOrder table 24 applied after the peon mined")
        self.assertTrue(java_result["observations"][1]["accepted"],
                        "Java must send the laden peon home")
        self.assertEqual("fulfilled",
                         native_result["observations"][1]["terminal_reason"])
        self.assertEqual(native_result["observations"][1]["terminal_cycle"],
                         java_result["observations"][1]["terminal_cycle"])
        self.assertEqual((22, 22), (
            java_result["observations"][1]["state"]["tile_x"],
            java_result["observations"][1]["state"]["tile_y"]),
            "both engines bank at the great hall")

    def test_both_adapters_send_an_empty_peon_to_the_hall(self):
        pack = Path.home() / (
            ".chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack")
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/playtest-explorer/commanded/return-goods-1/00.bnefx"
        )
        if not fixture.is_file() or not pack.is_file():
            self.skipTest("empty Orc 1 return-goods fixture or BNE pack missing")
        seed = explorer.seed_from_commanded_fixture(fixture)
        scenario = explorer.scenario_from_commanded_seed(seed)
        native_result = native.run_from_fixture(
            scenario, fixture, PINNED, "a" * 64)
        script = Path(__file__).parents[1] / "scripts" / "bne_playtest_java_adapter.py"
        with tempfile.TemporaryDirectory() as directory:
            adapter = explorer.Adapter("java", [
                sys.executable, str(script),
                "--scenario", "{scenario}", "--output", "{output}",
                "--asset-pack", str(pack), "--skip-build",
            ], timeout=180.0)
            java_result = adapter.run(scenario, Path(directory))
        explorer.validate_result(native_result, scenario, "native")
        explorer.validate_result(java_result, scenario, "java")
        self.assertTrue(native_result["observations"][0]["accepted"],
                        "GiveOrder table 24 applies an empty send-home")
        self.assertTrue(java_result["observations"][0]["accepted"],
                        "Java must not refuse an empty send-home")
        self.assertEqual((22, 22), (
            java_result["observations"][0]["state"]["tile_x"],
            java_result["observations"][0]["state"]["tile_y"]),
            "the empty peon walks to the great hall")

    def test_both_adapters_leave_a_send_home_still_when_no_depot_exists(self):
        pack = Path.home() / (
            ".chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack")
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/playtest-explorer/commanded/return-goods-2/01.bnefx"
        )
        if not fixture.is_file() or not pack.is_file():
            self.skipTest("no-depot return-goods fixture or BNE pack missing")
        seed = explorer.seed_from_commanded_fixture(fixture)
        scenario = explorer.scenario_from_commanded_seed(seed)
        native_result = native.run_from_fixture(
            scenario, fixture, PINNED, "a" * 64)
        script = Path(__file__).parents[1] / "scripts" / "bne_playtest_java_adapter.py"
        with tempfile.TemporaryDirectory() as directory:
            adapter = explorer.Adapter("java", [
                sys.executable, str(script),
                "--scenario", "{scenario}", "--output", "{output}",
                "--asset-pack", str(pack), "--skip-build",
            ], timeout=180.0)
            java_result = adapter.run(scenario, Path(directory))
        explorer.validate_result(native_result, scenario, "native")
        explorer.validate_result(java_result, scenario, "java")
        self.assertTrue(native_result["observations"][0]["accepted"],
                        "GiveOrder table 24 still applies when FindDeposit fails")
        self.assertTrue(java_result["observations"][0]["accepted"],
                        "Java must apply the click even when there is no hall")
        self.assertEqual((20, 31), (
            java_result["observations"][0]["state"]["tile_x"],
            java_result["observations"][0]["state"]["tile_y"]),
            "no friendly depot means the hull stays put")

    def test_both_adapters_accept_a_commanded_orc_one_repair(self):
        pack = Path.home() / (
            ".chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack")
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/playtest-explorer/commanded/repair-1/00.bnefx"
        )
        if not fixture.is_file() or not pack.is_file():
            self.skipTest("commanded Orc 1 repair fixture or BNE pack missing")
        seed = explorer.seed_from_commanded_fixture(fixture)
        scenario = explorer.scenario_from_commanded_seed(seed)
        native_result = native.run_from_fixture(
            scenario, fixture, PINNED, "a" * 64)
        explorer.validate_result(native_result, scenario, "native")
        script = Path(__file__).parents[1] / "scripts" / "bne_playtest_java_adapter.py"
        with tempfile.TemporaryDirectory() as directory:
            adapter = explorer.Adapter("java", [
                sys.executable, str(script),
                "--scenario", "{scenario}", "--output", "{output}",
                "--asset-pack", str(pack), "--skip-build",
            ], timeout=180.0)
            java_result = adapter.run(scenario, Path(directory))
        explorer.validate_result(java_result, scenario, "java")
        self.assertEqual(5, scenario["commands"][0]["issue_cycle"],
                         "the mend is issued on the commanded cycle")
        self.assertTrue(native_result["observations"][0]["accepted"],
                        "GiveOrder table 27 applied the peon mend")
        self.assertTrue(java_result["observations"][0]["accepted"],
                        "Java must mend the paired hall, not refuse a standing building")
        self.assertIsNotNone(
            java_result["observations"][0]["first_progress_cycle"],
            "the peon must leave the square toward the hall")
        self.assertNotEqual((25, 18), (
            java_result["observations"][0]["state"]["tile_x"],
            java_result["observations"][0]["state"]["tile_y"]),
            "the peon must walk off 25,18 toward the great hall")

    def test_both_adapters_turn_a_soldier_mend_into_a_walk(self):
        pack = Path.home() / (
            ".chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack")
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/playtest-explorer/commanded/repair-1/02.bnefx"
        )
        if not fixture.is_file() or not pack.is_file():
            self.skipTest("commanded peon-mends-grunt fixture or BNE pack missing")
        seed = explorer.seed_from_commanded_fixture(fixture)
        scenario = explorer.scenario_from_commanded_seed(seed)
        native_result = native.run_from_fixture(
            scenario, fixture, PINNED, "a" * 64)
        script = Path(__file__).parents[1] / "scripts" / "bne_playtest_java_adapter.py"
        with tempfile.TemporaryDirectory() as directory:
            adapter = explorer.Adapter("java", [
                sys.executable, str(script),
                "--scenario", "{scenario}", "--output", "{output}",
                "--asset-pack", str(pack), "--skip-build",
            ], timeout=180.0)
            java_result = adapter.run(scenario, Path(directory))
        explorer.validate_result(native_result, scenario, "native")
        explorer.validate_result(java_result, scenario, "java")
        self.assertTrue(native_result["observations"][0]["accepted"],
                        "GiveOrder table 27 still applies when the target is a grunt")
        self.assertTrue(java_result["observations"][0]["accepted"],
                        "Java must not refuse the click; the constructor walks")
        self.assertIsNotNone(
            native_result["observations"][0]["first_progress_cycle"],
            "the peon must walk toward the grunt")
        self.assertIsNotNone(
            java_result["observations"][0]["first_progress_cycle"],
            "Java must walk, not stay put on a refused repair")

    def test_both_adapters_walk_a_grunt_told_to_mend_a_hall(self):
        pack = Path.home() / (
            ".chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack")
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/playtest-explorer/commanded/repair-1/03.bnefx"
        )
        if not fixture.is_file() or not pack.is_file():
            self.skipTest("commanded grunt-repairs-hall fixture or BNE pack missing")
        seed = explorer.seed_from_commanded_fixture(fixture)
        scenario = explorer.scenario_from_commanded_seed(seed)
        native_result = native.run_from_fixture(
            scenario, fixture, PINNED, "a" * 64)
        script = Path(__file__).parents[1] / "scripts" / "bne_playtest_java_adapter.py"
        with tempfile.TemporaryDirectory() as directory:
            adapter = explorer.Adapter("java", [
                sys.executable, str(script),
                "--scenario", "{scenario}", "--output", "{output}",
                "--asset-pack", str(pack), "--skip-build",
            ], timeout=180.0)
            java_result = adapter.run(scenario, Path(directory))
        explorer.validate_result(native_result, scenario, "native")
        explorer.validate_result(java_result, scenario, "java")
        self.assertTrue(native_result["observations"][0]["accepted"],
                        "GiveOrder table 27 does not test the actor's worker flags")
        self.assertTrue(java_result["observations"][0]["accepted"],
                        "Java must walk the grunt, not refuse a button the injector still sends")
        self.assertNotEqual((18, 23), (
            java_result["observations"][0]["state"]["tile_x"],
            java_result["observations"][0]["state"]["tile_y"]),
            "the grunt must leave 18,23 toward the hall")

    def test_both_adapters_accept_a_commanded_catapult_attack_ground(self):
        pack = Path.home() / (
            ".chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack")
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/playtest-explorer/commanded/attack-ground-1/00.bnefx"
        )
        if not fixture.is_file() or not pack.is_file():
            self.skipTest("commanded catapult attack-ground fixture or BNE pack missing")
        seed = explorer.seed_from_commanded_fixture(fixture)
        scenario = explorer.scenario_from_commanded_seed(seed)
        native_result = native.run_from_fixture(
            scenario, fixture, PINNED, "a" * 64)
        explorer.validate_result(native_result, scenario, "native")
        script = Path(__file__).parents[1] / "scripts" / "bne_playtest_java_adapter.py"
        with tempfile.TemporaryDirectory() as directory:
            adapter = explorer.Adapter("java", [
                sys.executable, str(script),
                "--scenario", "{scenario}", "--output", "{output}",
                "--asset-pack", str(pack), "--skip-build",
            ], timeout=180.0)
            java_result = adapter.run(scenario, Path(directory))
        explorer.validate_result(java_result, scenario, "java")
        self.assertEqual(5, scenario["commands"][0]["issue_cycle"],
                         "the ground volley is issued on the commanded cycle")
        self.assertTrue(native_result["observations"][0]["accepted"],
                        "GiveOrder table 17 applied the catapult ground click")
        self.assertTrue(java_result["observations"][0]["accepted"],
                        "Java must take the paired catapult ground click")
        self.assertEqual(
            "ATTACK_GROUND", java_result["observations"][0]["state"]["order"],
            "the catapult must stay on the ground volley")

    def test_a_peon_ground_click_is_not_a_catapult_volley(self):
        pack = Path.home() / (
            ".chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack")
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/playtest-explorer/commanded/attack-ground-1/02.bnefx"
        )
        if not fixture.is_file() or not pack.is_file():
            self.skipTest("commanded peon attack-ground fixture or BNE pack missing")
        seed = explorer.seed_from_commanded_fixture(fixture)
        scenario = explorer.scenario_from_commanded_seed(seed)
        native_result = native.run_from_fixture(
            scenario, fixture, PINNED, "a" * 64)
        script = Path(__file__).parents[1] / "scripts" / "bne_playtest_java_adapter.py"
        with tempfile.TemporaryDirectory() as directory:
            adapter = explorer.Adapter("java", [
                sys.executable, str(script),
                "--scenario", "{scenario}", "--output", "{output}",
                "--asset-pack", str(pack), "--skip-build",
            ], timeout=180.0)
            java_result = adapter.run(scenario, Path(directory))
        explorer.validate_result(native_result, scenario, "native")
        explorer.validate_result(java_result, scenario, "java")
        self.assertTrue(native_result["observations"][0]["accepted"],
                        "GiveOrder table 17 still applies on a peon")
        self.assertTrue(java_result["observations"][0]["accepted"],
                         "GiveOrder 17 walks a peon toward the clicked grass")

    def test_both_adapters_accept_a_commanded_grunt_attack_move(self):
        pack = Path.home() / (
            ".chonkcraft/packs/warcraft-ii-battle-net-edition-usa.chonkpack")
        fixture = (
            Path(__file__).resolve().parents[1]
            / "work/playtest-explorer/commanded/attack-move-1/00.bnefx"
        )
        if not fixture.is_file() or not pack.is_file():
            self.skipTest("commanded grunt attack-move fixture or BNE pack missing")
        seed = explorer.seed_from_commanded_fixture(fixture)
        scenario = explorer.scenario_from_commanded_seed(seed)
        native_result = native.run_from_fixture(
            scenario, fixture, PINNED, "a" * 64)
        explorer.validate_result(native_result, scenario, "native")
        script = Path(__file__).parents[1] / "scripts" / "bne_playtest_java_adapter.py"
        with tempfile.TemporaryDirectory() as directory:
            adapter = explorer.Adapter("java", [
                sys.executable, str(script),
                "--scenario", "{scenario}", "--output", "{output}",
                "--asset-pack", str(pack), "--skip-build",
            ], timeout=180.0)
            java_result = adapter.run(scenario, Path(directory))
        explorer.validate_result(java_result, scenario, "java")
        self.assertEqual(5, scenario["commands"][0]["issue_cycle"],
                         "the ground attack is issued on the commanded cycle")
        self.assertTrue(native_result["observations"][0]["accepted"],
                        "GiveOrder table 8 dest path applied the grunt ground click")
        self.assertTrue(java_result["observations"][0]["accepted"],
                        "Java must take the paired grunt ground click")
        self.assertEqual(
            (22, 18),
            (native_result["observations"][0]["state"]["tile_x"],
             native_result["observations"][0]["state"]["tile_y"]),
            "the grunt must settle on the clicked grass")
        self.assertEqual(
            (22, 18),
            (java_result["observations"][0]["state"]["tile_x"],
             java_result["observations"][0]["state"]["tile_y"]),
            "Java must settle the grunt on the same clicked grass")


if __name__ == "__main__":
    unittest.main()
