import argparse
import io
import json
from pathlib import Path
import struct
import sys
import tempfile
import unittest
from unittest import mock

SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_compare
import bne_java


def survey_report(through, states, *, head="abc", dirty=False):
    cases = []
    for case_id, state in states.items():
        if isinstance(state, int):
            cases.append({
                "id": case_id,
                "fixture_id": f"fixture-{case_id}",
                "state": "divergent",
                "compared_cycles": through,
                "first_divergence_cycle": state,
                "comparison_output": (
                    f"first divergence at cycle {state} (1 finding(s)):\n"
                    f"  cycle {state}: unit 7 (unit-grunt) x 12 vs 11"
                ),
            })
        else:
            cases.append({
                "id": case_id,
                "fixture_id": f"fixture-{case_id}",
                "state": state,
                "compared_cycles": through,
                "first_divergence_cycle": None,
                "comparison_output": "traces agree",
            })
    return {
        "schema": 1,
        "comparison_tier": "semantic-v1",
        "engine": {"head": head, "dirty": dirty},
        "asset_source": {"kind": "test", "sha256": "asset"},
        "cases": cases,
    }


class BneJavaAdapterTest(unittest.TestCase):

    @staticmethod
    def _campaign_report(through, *, divergent_at=None, head="candidate"):
        states = {
            f"case-{index:02d}": (
                divergent_at if index == 0 and divergent_at is not None
                else "clean"
            )
            for index in range(52)
        }
        return survey_report(through, states, head=head)

    def test_direct_full_gate_seals_and_promotes_an_accepted_receipt(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            baseline_path = root / "baseline.json"
            candidate_path = root / "candidate.json"
            artifacts = root / "artifacts"
            baseline_path.write_text(json.dumps(
                self._campaign_report(21, head="baseline")))
            candidate_path.write_text(json.dumps(
                self._campaign_report(22)))
            args = bne_java.parser().parse_args([
                "gate", str(candidate_path), "--baseline", str(baseline_path),
                "--artifact-root", str(artifacts),
            ])

            with mock.patch("sys.stdout", new=io.StringIO()):
                self.assertEqual(0, args.func(args))

            pointer = json.loads(
                (artifacts / "latest-accepted.json").read_text())
            manifest_path = artifacts / pointer["manifest"]
            manifest = json.loads(manifest_path.read_text())
            self.assertEqual("gate-acceptance", pointer["kind"])
            self.assertEqual(22, pointer["common_clean_through"])
            self.assertTrue(manifest["acceptance"]["eligible"])
            self.assertEqual(
                bne_java.file_identity(manifest_path),
                pointer["manifest_identity"],
            )

    def test_direct_gate_cannot_roll_back_the_accepted_pointer(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            artifacts = root / "artifacts"

            def run_gate(name, baseline_through, candidate_through):
                baseline_path = root / f"{name}-baseline.json"
                candidate_path = root / f"{name}-candidate.json"
                baseline_path.write_text(json.dumps(self._campaign_report(
                    baseline_through, head=f"{name}-baseline")))
                candidate_path.write_text(json.dumps(self._campaign_report(
                    candidate_through, head=name)))
                args = bne_java.parser().parse_args([
                    "gate", str(candidate_path),
                    "--baseline", str(baseline_path),
                    "--artifact-root", str(artifacts),
                ])
                with mock.patch("sys.stdout", new=io.StringIO()):
                    self.assertEqual(0, args.func(args))

            run_gate("newer", 21, 22)
            run_gate("older", 20, 21)

            pointer = json.loads(
                (artifacts / "latest-accepted.json").read_text())
            self.assertEqual(22, pointer["common_clean_through"])

    def test_cli_triage_composes_the_lab_handoff_by_default(self):
        args = bne_java.parser().parse_args([
            "triage", "corpus-index.json", "--baseline-survey", "proof.json",
        ])
        self.assertTrue(args.compose_lab)
        self.assertEqual(
            bne_java.ROOT / ".bne-lab", args.lab_artifact_root,
        )

    def test_cli_triage_cannot_skip_lab_pointer_promotion(self):
        args = argparse.Namespace(compose_lab=True)
        with mock.patch.object(
                bne_java, "_triage_command_core", return_value=0) as core, \
             mock.patch.object(
                bne_java, "_compose_lab_handoff", return_value=0) as compose:
            self.assertEqual(0, bne_java.triage_command(args))
        core.assert_called_once_with(args)
        compose.assert_called_once_with(args, 0)

    def test_cli_exposes_offline_semantic_slice_inputs(self):
        args = bne_java.parser().parse_args([
            "semantic-slice", "plan.json",
            "--capture", "anchor.json", "--history", "anchor.txt",
            "--control-capture", "control.json",
            "--control-history", "control.txt",
        ])
        self.assertEqual(Path("plan.json"), args.plan)
        self.assertEqual([Path("control.json")], args.control_capture)
        self.assertEqual([Path("control.txt")], args.control_history)

    def test_cli_exposes_cross_engine_semantic_bridge_inputs(self):
        args = bne_java.parser().parse_args([
            "semantic-bridge", "semantic-slice.json",
            "--java-trace", "java-semantic.jsonl",
        ])
        self.assertEqual(Path("semantic-slice.json"), args.semantic_slice)
        self.assertEqual(Path("java-semantic.jsonl"), args.java_trace)
        self.assertEqual(
            bne_java.ROOT / "tools/bne-harness/semantic-bridge-atlas.json",
            args.atlas,
        )

    def test_cli_exposes_paired_cadence_inputs(self):
        args = bne_java.parser().parse_args([
            "cadence", "case.bnefx", "--java-trace", "case.java.trace.txt",
            "--native-unit", "1500",
        ])
        self.assertEqual(Path("case.bnefx"), args.native_source)
        self.assertEqual(1500, args.native_unit)
        self.assertIsNone(args.java_unit)
        self.assertEqual("position", args.field)
        pixel = bne_java.parser().parse_args([
            "cadence", "case.bnefx", "--java-trace", "case.java.trace.txt",
            "--native-unit", "1500", "--field", "pixel-position",
        ])
        self.assertEqual("pixel-position", pixel.field)

    def test_cli_exposes_test_efficacy_gate(self):
        args = bne_java.parser().parse_args([
            "test-efficacy", "--baseline", "abc123",
            "--test", "FlyerBobPrimeTest",
        ])
        self.assertEqual("abc123", args.baseline)
        self.assertEqual("FlyerBobPrimeTest", args.test)
        self.assertEqual("engine", args.module)

    def test_cli_exposes_capability_doctor(self):
        args = bne_java.parser().parse_args([
            "doctor", "--no-remote", "--need", "capture",
        ])
        self.assertTrue(args.no_remote)
        self.assertEqual("oracle-host", args.remote_host)
        self.assertEqual("capture", args.need)

    def test_cli_exposes_contrastive_decision_pipeline(self):
        planned = bne_java.parser().parse_args([
            "decision-plan", "witness.json", "--fixture", "case.bnefx",
            "--native-unit", "1519", "--field", "order",
            "--rejected-cycle", "29", "--accepted-cycle", "34",
        ])
        self.assertEqual(29, planned.rejected_cycle)
        self.assertEqual(34, planned.accepted_cycle)
        self.assertEqual("order", planned.field)
        mined = bne_java.parser().parse_args([
            "decision-mine", "decision-plan.json",
            "--capture", "rejected.json", "--capture", "accepted.json",
        ])
        self.assertEqual(2, len(mined.capture))
        remote = bne_java.parser().parse_args([
            "decision-remote", "decision-plan.json", "--phase", "rejected",
        ])
        self.assertFalse(remote.execute)
        self.assertEqual(["rejected"], remote.phase)

    def test_structures_first_divergence_findings(self):
        findings = bne_java.parse_comparison_findings("""\
first divergence at cycle 22 (3 finding(s)):
  cycle 22: unit 1358 (unit-grunt) x 12 vs 11
  cycle 22: p6 bank (2000, 1000, 0) vs (1400, 1000, 0)
  cycle 22: seed 41c67ea6 vs 00000001 -- one engine drew
""")
        self.assertEqual(3, len(findings))
        self.assertEqual(
            {
                "cycle": 22,
                "message": "unit 1358 (unit-grunt) x 12 vs 11",
                "kind": "unit",
                "unit": 1358,
                "unit_type": "unit-grunt",
                "field": "x",
                "oracle": 12,
                "java": 11,
            },
            findings[0],
        )
        self.assertEqual([2000, 1000, 0], findings[1]["oracle"])
        self.assertEqual("sync_rng", findings[2]["kind"])

    def test_builds_per_case_frontiers_from_same_engine_surveys(self):
        h21 = survey_report(21, {"case-a": "clean", "case-b": "clean"})
        h22 = survey_report(22, {"case-a": "clean", "case-b": 22})
        frontier = bne_java.build_frontier([h21, h22])
        self.assertEqual(21, frontier["common_clean_through"])
        self.assertEqual(22, frontier["earliest_divergence_cycle"])
        self.assertEqual(
            {
                "case-a": (22, None),
                "case-b": (21, 22),
            },
            {case["id"]: (case["clean_through"],
                          case["first_divergence_cycle"])
             for case in frontier["cases"]},
        )
        self.assertIn("unit 7 (unit-grunt) x 12 vs 11",
                      bne_java.format_frontier(frontier))

    def test_frontier_ranks_a_trace_observable_tied_blocker_first(self):
        survey = survey_report(40, {"order-case": 29, "flyer-case": 29})
        records = {item["id"]: item for item in survey["cases"]}
        records["order-case"]["comparison_output"] = (
            "first divergence at cycle 29 (1 finding(s)):\n"
            "  cycle 29: unit 1500 (unit-ogre) order Still vs Attack"
        )
        records["flyer-case"]["comparison_output"] = (
            "first divergence at cycle 29 (1 finding(s)):\n"
            "  cycle 29: unit 1519 (unit-zeppelin) y 56 vs 58"
        )

        frontier = bne_java.build_frontier([survey])

        self.assertEqual("flyer-case", frontier["tied_blockers"][0]["case"])
        self.assertEqual("cadence",
                         frontier["tied_blockers"][0]["recommended_tool"])
        self.assertIn("tied-blocker investigation order",
                      bne_java.format_frontier(frontier))

    def test_frontier_rejects_mixed_engine_workspaces(self):
        first = survey_report(21, {"case-a": "clean"}, head="abc")
        second = survey_report(22, {"case-a": "clean"}, head="def")
        with self.assertRaisesRegex(ValueError, "different engine workspaces"):
            bne_java.build_frontier([first, second])

    def test_gate_accepts_next_cycle_divergence_but_rejects_regression(self):
        baseline = survey_report(21, {"case-a": "clean", "case-b": "clean"})
        candidate = survey_report(22, {"case-a": "clean", "case-b": 22},
                                  head="candidate")
        self.assertTrue(bne_java.evaluate_gate([baseline], candidate)["passed"])

        regressed = survey_report(22, {"case-a": "clean", "case-b": 21},
                                  head="candidate")
        gate = bne_java.evaluate_gate([baseline], regressed)
        self.assertFalse(gate["passed"])
        self.assertEqual("regression", gate["issues"][0]["kind"])

    def test_gate_rejects_insufficient_candidate_coverage(self):
        baseline = survey_report(21, {"case-a": "clean"})
        candidate = survey_report(20, {"case-a": "clean"}, head="candidate")
        gate = bne_java.evaluate_gate([baseline], candidate)
        self.assertFalse(gate["passed"])
        self.assertEqual("coverage_gap", gate["issues"][0]["kind"])

    def test_gate_rejects_a_different_sealed_fixture(self):
        baseline = survey_report(21, {"case-a": "clean"})
        candidate = survey_report(21, {"case-a": "clean"}, head="candidate")
        candidate["cases"][0]["fixture_id"] = "different-fixture"
        gate = bne_java.evaluate_gate([baseline], candidate)
        self.assertFalse(gate["passed"])
        self.assertEqual("fixture_mismatch", gate["issues"][0]["kind"])

    def test_gate_authenticates_a_byte_identical_full_pack_migration(self):
        baseline = self._campaign_report(80, head="baseline")
        candidate = json.loads(json.dumps(baseline))
        candidate["engine"] = {"head": "candidate", "dirty": True}
        candidate["asset_source"] = {"kind": "chonkpack", "sha256": "full"}
        for index, (before, after) in enumerate(zip(
                baseline["cases"], candidate["cases"], strict=True)):
            identity = {"bytes": 1000 + index, "sha256": f"{index:064x}"}
            before["java_trace"] = {"path": f"/old/{index}", **identity}
            after["java_trace"] = {"path": f"/new/{index}", **identity}

        gate = bne_java.evaluate_gate(
            [baseline], candidate, allow_asset_migration=True,
        )

        self.assertTrue(gate["passed"])
        self.assertEqual(52, gate["asset_migration"]["case_count"])
        self.assertEqual(
            "byte-identical-52-case-traces",
            gate["asset_migration"]["method"],
        )
        candidate["cases"][17]["java_trace"]["sha256"] = "f" * 64
        with self.assertRaisesRegex(ValueError, "changed trace bytes"):
            bne_java.evaluate_gate(
                [baseline], candidate, allow_asset_migration=True,
            )

    def test_maps_every_retail_campaign_family(self):
        self.assertEqual(
            "campaigns/human/level01h",
            bne_java.scenario_to_java_map(r"Campaign\Human\Human01.pud"),
        )
        self.assertEqual(
            "campaigns/orc/level14o",
            bne_java.scenario_to_java_map(r"Campaign\Orc\Orc14.pud"),
        )
        self.assertEqual(
            "campaigns/human-exp/levelx12h",
            bne_java.scenario_to_java_map(r"Campaign\XHuman\2XHum12.pud"),
        )
        self.assertEqual(
            "campaigns/orc-exp/levelx09o",
            bne_java.scenario_to_java_map(r"Campaign\XOrc\2XOrc09.pud"),
        )
        with self.assertRaisesRegex(ValueError, "unsupported"):
            bne_java.scenario_to_java_map(r"Maps\Custom.pud")

    def test_java_command_pins_fixture_seed_and_bne_profile(self):
        case = bne_java.Case(
            case_id="retail-human-01-idle",
            fixture=Path("fixture.bnefx"),
            fixture_id="fixture-id",
            scenario=r"Campaign\Human\Human01.pud",
            java_map="campaigns/human/level01h",
            cycles=1800,
            seed=1,
            state_schema="1.1",
        )
        args = argparse.Namespace(
            java_wrapper=Path("/tool/java-wrapper"),
            java="java",
            asset_pack=Path("/packs/bne.chonkpack"),
            install_dir=None,
            source_dir=Path("/chonkcraft"),
        )
        command = bne_java.java_command(args, case, Path("/out/trace.txt"))
        self.assertIn("-Dchonkcraft.pack=/packs/bne.chonkpack", command)
        self.assertIn("-Dchonkcraft.trace.seed=1", command)
        self.assertIn("-Dchonkcraft.trace.profile=bne", command)
        retired_prefix = "-D" + "war" + "gus.trace."
        self.assertFalse(any(retired_prefix in item for item in command),
                         "the harness used a property EngineTrace no longer reads")
        self.assertIn("campaigns/human/level01h", command)
        self.assertIn("1800", command)

    def test_requires_a_complete_52_case_index_by_default(self):
        with tempfile.TemporaryDirectory() as directory:
            index = Path(directory) / "corpus-index.json"
            index.write_text(json.dumps({"schema": 1, "cases": []}))
            with self.assertRaisesRegex(ValueError, "expected all 52"):
                bne_java.load_index(index)
            self.assertEqual([], bne_java.load_index(index, allow_partial=True))

    def test_rejects_a_truncated_or_duplicate_java_trace(self):
        with tempfile.TemporaryDirectory() as directory:
            trace = Path(directory) / "java.trace.txt"
            trace.write_text("cycle 1 seed 00000001\ncycle 2 seed 00000001\n")
            bne_compare.validate_java_trace_cycles(trace, 2)
            trace.write_text("cycle 1 seed 00000001\ncycle 1 seed 00000001\n")
            with self.assertRaisesRegex(ValueError, "non-contiguous"):
                bne_compare.validate_java_trace_cycles(trace, 2)

    def test_normalizes_only_raw_14_tower_idle_as_still(self):
        raw_idle = bytearray(152)
        raw_idle[46] = 14
        raw_attack = bytearray(152)
        raw_attack[46] = 8
        cycle_payload = b"".join((
            bne_compare.CYCLE_HEADER.pack(1, 1, 8, 2),
            bytes(16 * bne_compare.PLAYER_RECORD.size),
            bne_compare.UNIT_DELTA_HEADER.pack(3, 1),
            raw_idle,
            bne_compare.UNIT_DELTA_HEADER.pack(4, 1),
            raw_attack,
        ))
        state = io.BytesIO(b"".join((
            bne_compare.STATE_HEADER.pack(
                b"BNESTATE", 1, 1, bne_compare.STATE_HEADER.size,
                152, 1600, 16, 15,
            ),
            bne_compare.CHUNK_HEADER.pack(b"CYCL", len(cycle_payload)),
            cycle_payload,
        )))
        trace = io.BytesIO(
            b"cycle 1 seed 00000001\n"
            b"u 3 unit-human-guard-tower p1 1 1 hp 130 o ATTACK\n"
            b"u 4 unit-human-guard-tower p1 2 2 hp 130 o ATTACK\n"
        )
        output = io.BytesIO()
        bne_compare.normalize_fixture_trace(trace, state, output, 1)
        self.assertEqual(
            b"cycle 1 seed 00000001\n"
            b"u 3 unit-human-guard-tower p1 1 1 hp 130 o STILL\n"
            b"u 4 unit-human-guard-tower p1 2 2 hp 130 o ATTACK\n",
            output.getvalue(),
        )

    def test_normalizes_raw_16_stationary_attack_as_attack(self):
        raw_attack = bytearray(152)
        raw_attack[46] = 16
        raw_attack[136:140] = (0x05065970).to_bytes(4, "little")
        cycle_payload = b"".join((
            bne_compare.CYCLE_HEADER.pack(1, 1, 8, 1),
            bytes(16 * bne_compare.PLAYER_RECORD.size),
            bne_compare.UNIT_DELTA_HEADER.pack(3, 1),
            raw_attack,
        ))
        state = io.BytesIO(b"".join((
            bne_compare.STATE_HEADER.pack(
                b"BNESTATE", 1, 1, bne_compare.STATE_HEADER.size,
                152, 1600, 16, 15,
            ),
            bne_compare.CHUNK_HEADER.pack(b"CYCL", len(cycle_payload)),
            cycle_payload,
        )))
        trace = io.BytesIO(
            b"cycle 1 seed 00000001\n"
            b"u 3 unit-axethrower p6 79 114 hp 40 o STILL\n"
        )
        output = io.BytesIO()
        bne_compare.normalize_fixture_trace(trace, state, output, 1)
        self.assertEqual(
            b"cycle 1 seed 00000001\n"
            b"u 3 unit-axethrower p6 79 114 hp 40 o ATTACK\n",
            output.getvalue(),
        )

    def test_normalizes_inert_circle_raw_move_as_still(self):
        raw_circle = bytearray(152)
        raw_circle[46] = 59
        raw_footman = bytearray(152)
        raw_footman[46] = 3
        cycle_payload = b"".join((
            bne_compare.CYCLE_HEADER.pack(1, 1, 8, 2),
            bytes(16 * bne_compare.PLAYER_RECORD.size),
            bne_compare.UNIT_DELTA_HEADER.pack(3, 1),
            raw_circle,
            bne_compare.UNIT_DELTA_HEADER.pack(4, 1),
            raw_footman,
        ))
        state = io.BytesIO(b"".join((
            bne_compare.STATE_HEADER.pack(
                b"BNESTATE", 1, 1, bne_compare.STATE_HEADER.size,
                152, 1600, 16, 15,
            ),
            bne_compare.CHUNK_HEADER.pack(b"CYCL", len(cycle_payload)),
            cycle_payload,
        )))
        trace = io.BytesIO(
            b"cycle 1 seed 00000001\n"
            b"u 3 unit-circle-of-power p1 1 1 hp 255 o MOVE\n"
            b"u 4 unit-footman p1 2 2 hp 60 o MOVE\n"
        )
        output = io.BytesIO()
        bne_compare.normalize_fixture_trace(trace, state, output, 1)
        self.assertEqual(
            b"cycle 1 seed 00000001\n"
            b"u 3 unit-circle-of-power p1 1 1 hp 255 o STILL\n"
            b"u 4 unit-footman p1 2 2 hp 60 o MOVE\n",
            output.getvalue(),
        )

    def test_normalizes_resource_approach_and_inside_substates(self):
        raw_approach = bytearray(152)
        raw_approach[46] = 25
        raw_inside = bytearray(152)
        raw_inside[46] = 26
        cycle_payload = b"".join((
            bne_compare.CYCLE_HEADER.pack(1, 1, 8, 2),
            bytes(16 * bne_compare.PLAYER_RECORD.size),
            bne_compare.UNIT_DELTA_HEADER.pack(3, 1),
            raw_approach,
            bne_compare.UNIT_DELTA_HEADER.pack(4, 1),
            raw_inside,
        ))
        state = io.BytesIO(b"".join((
            bne_compare.STATE_HEADER.pack(
                b"BNESTATE", 1, 1, bne_compare.STATE_HEADER.size,
                152, 1600, 16, 15,
            ),
            bne_compare.CHUNK_HEADER.pack(b"CYCL", len(cycle_payload)),
            cycle_payload,
        )))
        trace = io.BytesIO(
            b"cycle 1 seed 00000001\n"
            b"u 3 unit-peasant p1 1 1 hp 30 o BOARD\n"
            b"u 4 unit-human-oil-tanker p1 2 2 hp 90 o UNLOAD removed\n"
        )
        output = io.BytesIO()
        bne_compare.normalize_fixture_trace(trace, state, output, 1)
        self.assertEqual(
            b"cycle 1 seed 00000001\n"
            b"u 3 unit-peasant p1 1 1 hp 30 o HARVEST\n"
            b"u 4 unit-human-oil-tanker p1 2 2 hp 90 o HARVEST removed\n",
            output.getvalue(),
        )


if __name__ == "__main__":
    unittest.main()
