from pathlib import Path
import json
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_experiments
import bne_function_lab
import bne_java
import bne_lab
import bne_rng_ledger
import bne_triage

from test_bne_rng_ledger import chain, java_causal, native_trace


HP_FINDING = {
    "kind": "unit", "unit": 1448, "unit_type": "unit-grunt", "field": "hp",
    "oracle": 35, "java": 38,
}


def hp_packet(native_series, java_series, *, findings=None):
    """A packet whose focus pair carries one hit-point series per side."""
    semantic = {}
    for cycle, native_hp in native_series:
        semantic[str(cycle)] = {"cycle": cycle, "focus": [{
            "native_slot": 1448, "java_id": 152,
            "oracle": {"x": 25, "y": 60, "hp": native_hp},
            "java": {"x": 25, "y": 60,
                     "hp": dict(java_series)[cycle]},
        }]}
    return {
        "schema": 1,
        "case": {"id": "case-a", "scenario": "Campaign\\Human\\Human12.pud",
                 "seed": 1},
        "divergence": {"cycle": max(cycle for cycle, _ in native_series),
                       "findings": findings or [HP_FINDING]},
        "semantic": semantic,
        "native_state": {}, "native_diagnostic_events": [],
        "java_diagnostic_highlights": [], "java_process_output": {},
    }


class BneRngEscalationTest(unittest.TestCase):

    def test_blows_landing_together_for_different_damage_escalate_to_the_ledger(self):
        # Both engines lose hit points on the same three cycles, the same
        # number of times, by different amounts. Nothing but the roll is left.
        packet = hp_packet(
            [(35, 53), (36, 47), (37, 47), (38, 35)],
            [(35, 53), (36, 47), (37, 47), (38, 38)],
        )
        evidence = bne_experiments.hp_evidence(packet)
        self.assertTrue(evidence["randomized_damage_suspected"],
                        "hit points falling on the same cycles for different "
                        "amounts were not recognized as a differently rolled "
                        "blow")
        plan = bne_experiments.default_investigation_plan(
            "case-a", 38, packet["divergence"]["findings"], evidence=evidence)
        self.assertEqual("async-rng-ledger", plan["recommended"]["id"],
                         "the highest-value next experiment for a damage "
                         "mismatch is still one that cannot distinguish a "
                         "differently rolled blow")
        self.assertIn("rounding", [item["id"] for item in plan["hypotheses"]],
                      "the non-random explanations were dropped rather than "
                      "outranked")

    def test_a_building_coming_up_does_not_escalate_to_the_ledger(self):
        # Hit points rising is a building under construction or a heal, and
        # neither takes a damage roll.
        packet = hp_packet(
            [(35, 100), (36, 140), (37, 180), (38, 220)],
            [(35, 100), (36, 140), (37, 180), (38, 218)],
        )
        evidence = bne_experiments.hp_evidence(packet)
        self.assertEqual("rising", evidence["direction"])
        self.assertFalse(evidence["randomized_damage_suspected"],
                         "a building coming up was treated as a blow whose "
                         "damage was rolled")
        plan = bne_experiments.default_investigation_plan(
            "case-a", 38, packet["divergence"]["findings"], evidence=evidence)
        self.assertNotEqual("async-rng-ledger", plan["recommended"]["id"])
        self.assertEqual({"rounding", "event-order", "rate"},
                         {item["id"] for item in plan["hypotheses"]},
                         "construction lost its rounding, order and rate "
                         "hypotheses to an RNG one that cannot apply")

    def test_a_different_per_tick_rate_does_not_escalate_to_the_ledger(self):
        # The engines change the same unit's hit points on different cycles,
        # which is a rate or an ordering difference, not a roll.
        packet = hp_packet(
            [(35, 60), (36, 54), (37, 48), (38, 42)],
            [(35, 60), (36, 60), (37, 54), (38, 48)],
        )
        evidence = bne_experiments.hp_evidence(packet)
        self.assertFalse(evidence["cadence_agrees"],
                         "hit points changing on different cycles were "
                         "reported as a matching cadence")
        self.assertFalse(evidence["randomized_damage_suspected"])
        plan = bne_experiments.default_investigation_plan(
            "case-a", 38, packet["divergence"]["findings"], evidence=evidence)
        self.assertNotEqual("async-rng-ledger", plan["recommended"]["id"],
                            "a cadence mismatch was escalated to an RNG "
                            "ledger that would report the streams agreeing")

    def test_a_lab_run_without_native_evidence_emits_the_capture_command(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            triage_root = directory / "triage"
            packet_dir = triage_root / "packets" / "case-a"
            packet_dir.mkdir(parents=True)
            packet = hp_packet(
                [(35, 53), (36, 47), (37, 47), (38, 35)],
                [(35, 53), (36, 47), (37, 47), (38, 38)],
            )
            packet_path = packet_dir / "packet.json"
            packet_path.write_text(json.dumps(packet) + "\n")
            survey = triage_root / "survey.json"
            survey.write_text(json.dumps({"cases": [{
                "id": "case-a", "state": "divergent",
                "first_divergence_cycle": 38,
                "findings": packet["divergence"]["findings"],
            }]}) + "\n")
            request = {"engine": {"head": "abc"}}
            request_id = bne_triage.canonical_digest(request)
            (triage_root / "manifest.json").write_text(json.dumps({
                "schema": 1, "request_sha256": request_id,
                "created_at": "2026-08-03T00:00:00+00:00", "request": request,
                "candidate": {"survey": "survey.json", "counts": {
                    "clean": 51, "divergent": 1, "failed": 0}},
                "gate": {"passed": True},
                "frontier": {"common_clean_through": 37,
                             "earliest_divergence_cycle": 38},
                "clusters": [],
                "packets": [{"case": "case-a", "cycle": 38,
                             "packet": "packets/case-a/packet.json"}],
                "artifacts": bne_triage.inventory_files(
                    triage_root, [packet_path, survey]),
            }, indent=2, sort_keys=True) + "\n")

            status, run_root = bne_lab.build_lab(triage_root, directory / "lab")

            self.assertEqual(0, status)
            manifest = json.loads((run_root / "manifest.json").read_text())
            case = manifest["cases"][0]
            self.assertEqual("native-evidence-missing",
                             case["rng_ledger_classification"],
                             "a damage mismatch with no native ledger behind "
                             "it was given a verdict instead of a capture plan")
            report = (run_root / manifest["attempt"]
                      / case["rng_ledger_report"]).read_text()
            self.assertIn("bne_oracle.py run", report,
                          "the lab escalated to the RNG ledger and then said "
                          "nothing about how to capture the missing side")
            self.assertIn("Human12.pud", report,
                          "the capture command names some other scenario than "
                          "the case's own")

    def test_a_lab_run_with_native_evidence_names_the_first_shifted_draw(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            triage_root = directory / "triage"
            packet_dir = triage_root / "packets" / "case-a"
            packet_dir.mkdir(parents=True)
            packet = hp_packet(
                [(35, 53), (36, 47), (37, 47), (38, 35)],
                [(35, 53), (36, 47), (37, 47), (38, 38)],
            )
            packet_path = packet_dir / "packet.json"
            packet_path.write_text(json.dumps(packet) + "\n")
            survey = triage_root / "survey.json"
            survey.write_text(json.dumps({"cases": [{
                "id": "case-a", "state": "divergent",
                "first_divergence_cycle": 38,
                "findings": packet["divergence"]["findings"],
            }]}) + "\n")
            draws = chain(1, 7)
            native_callers = ["0x00418370", "0x0040ad30"] * 3
            java_callers = [
                "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
                "BattleNetProjectileSystem.aimJitter",
                "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
                "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
            ]
            causal = triage_root / "case-a.causal.jsonl"
            causal.write_text(java_causal(draws, java_callers))
            trace = self.authenticated_native_trace(
                directory, native_trace(draws[:6], native_callers))
            request = {"engine": {"head": "abc"}}
            request_id = bne_triage.canonical_digest(request)
            (triage_root / "manifest.json").write_text(json.dumps({
                "schema": 1, "request_sha256": request_id,
                "created_at": "2026-08-03T00:00:00+00:00", "request": request,
                "candidate": {"survey": "survey.json", "counts": {
                    "clean": 51, "divergent": 1, "failed": 0}},
                "gate": {"passed": True},
                "frontier": {"common_clean_through": 37,
                             "earliest_divergence_cycle": 38},
                "clusters": [],
                "packets": [{"case": "case-a", "cycle": 38,
                             "packet": "packets/case-a/packet.json",
                             "java_causal_trace": "case-a.causal.jsonl"}],
                "artifacts": bne_triage.inventory_files(
                    triage_root, [packet_path, survey, causal]),
            }, indent=2, sort_keys=True) + "\n")

            status, run_root = bne_lab.build_lab(
                triage_root, directory / "lab",
                native_traces={"case-a": trace})

            manifest = json.loads((run_root / "manifest.json").read_text())
            case = manifest["cases"][0]
            self.assertEqual("java-extra-draw",
                             case["rng_ledger_classification"],
                             "an HP finding did not follow its damage upstream "
                             "into the draw that was spent differently")
            report = json.loads((run_root / manifest["attempt"]
                                 / case["rng_ledger"]).read_text())
            self.assertEqual(2, report["first_mismatch"]["at_match_index"])
            self.assertEqual("BattleNetProjectileSystem.aimJitter",
                             report["first_mismatch"]["observed_java_caller"])
            self.assertEqual(0, status)

    def authenticated_native_trace(self, directory: Path, body: str) -> Path:
        trace = directory / "case-a.trace.txt"
        trace.write_text(body)
        identity = bne_triage.file_identity(trace)
        (directory / "case-a.manifest.json").write_text(json.dumps({
            "schema": 2,
            "run": {"trace": {"name": trace.name, **identity},
                    "requested_scenario": "Campaign\\Human\\Human12.pud",
                    "cycle_limit": 60, "initialization_seed": 1},
            "oracle": {"executable": {
                "sha256": bne_function_lab.BNE_202_SHA256}},
            "runtime": {"network_disabled": True},
            "fixture": {"id": "fixture-case-a"},
            "harness": {"tracer": {"sha256": "0" * 64}},
        }, indent=2, sort_keys=True) + "\n")
        return trace

    def test_the_command_line_ledger_is_content_addressed_and_repeatable(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            draws = chain(1, 7)
            native_callers = ["0x00418370", "0x0040ad30"] * 3
            java_callers = [
                "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
                "BattleNetProjectileSystem.aimJitter",
                "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
                "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
            ]
            trace = self.authenticated_native_trace(
                directory, native_trace(draws[:6], native_callers))
            causal = directory / "case-a.causal.jsonl"
            causal.write_text(java_causal(draws, java_callers))

            status, run_root = bne_rng_ledger.run_rng_ledger(
                trace, causal, directory / "artifacts", case="case-a")

            self.assertEqual(1, status,
                             "an extra Java draw exited as though the two "
                             "ledgers agreed")
            report = json.loads((run_root / "RNG-DIFF.json").read_text())
            self.assertEqual("java-extra-draw", report["classification"])
            manifest = json.loads((run_root / "manifest.json").read_text())
            for relative, expected in manifest["artifacts"].items():
                self.assertEqual(expected,
                                 bne_triage.file_identity(run_root / relative),
                                 f"the manifest does not describe {relative}")
            repeated_status, repeated_root = bne_rng_ledger.run_rng_ledger(
                trace, causal, directory / "artifacts", case="case-a")
            self.assertEqual((status, run_root),
                             (repeated_status, repeated_root),
                             "the same evidence produced a second run rather "
                             "than the same content-addressed one")

    def test_an_unauthenticated_native_trace_is_refused_by_the_command(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            draws = chain(1, 4)
            trace = directory / "case-a.trace.txt"
            trace.write_text(native_trace(draws, ["0x00418370"] * 4))
            causal = directory / "case-a.causal.jsonl"
            causal.write_text(
                java_causal(draws, ["World.battleNetMeleeDamage"] * 4))

            with self.assertRaises(ValueError,
                    msg="a native trace with no oracle manifest beside it was "
                        "accepted as evidence"):
                bne_rng_ledger.run_rng_ledger(
                    trace, causal, directory / "artifacts", case="case-a")

    def test_the_parser_offers_the_ledger_as_a_supported_command(self):
        arguments = bne_java.parser().parse_args([
            "rng-ledger", "--java-causal", "case.causal.jsonl",
            "--native-trace", "case.trace.txt",
        ])
        self.assertEqual(bne_java.rng_ledger_command, arguments.func)
        self.assertEqual("async", arguments.stream)


if __name__ == "__main__":
    unittest.main()
