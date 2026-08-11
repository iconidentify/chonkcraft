#!/usr/bin/env python3

import json
from pathlib import Path
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

import bne_branch_witness
import bne_triage


class BranchWitnessTest(unittest.TestCase):
    def packet(self):
        return {
            "schema": 1,
            "case": {
                "id": "case-a", "fixture_id": "fixture-a",
                "scenario": r"Campaign\Human\Human12.pud", "seed": 1,
            },
            "divergence": {"cycle": 24, "findings": [{
                "kind": "unit", "unit": 17, "unit_type": "unit-footman",
                "field": "y", "oracle": 8, "java": 9,
            }]},
            "semantic": {"24": {"focus": [{
                "native_slot": 17, "java_id": 3,
                "oracle": {"x": 40, "y": 8},
                "java": {"x": 40, "y": 9},
            }]}},
        }

    def capture(self, *, control=False, cycle=24):
        if control:
            return {
                "schema": 1,
                "case": "case-a", "cycle": cycle, "field": "y",
                "events": [{
                    "seq": 1, "type": "branch", "cycle": cycle,
                    "address": 0x0044FC10, "target": 0x0044FC40,
                    "taken": False, "conditional": True,
                    "condition": ">=", "controls_writer": True,
                    "function": 0x0044FBD0,
                    "operands": {
                        "lhs": {"name": "wait", "value": 13},
                        "rhs": {"name": "threshold", "value": 14},
                    },
                }],
            }
        return {
            "schema": 1,
            "case": "case-a", "cycle": cycle, "field": "y",
            "events": [
                {
                    "seq": 1, "type": "branch", "cycle": cycle,
                    "address": 0x0044FBE0, "target": 0x0044FBF0,
                    "taken": True, "conditional": True,
                    "function": 0x0044FBD0,
                },
                {
                    "seq": 2, "type": "branch", "cycle": cycle,
                    "address": 0x0044FC10, "target": 0x0044FC40,
                    "taken": True, "conditional": True,
                    "condition": ">=", "controls_writer": True,
                    "function": 0x0044FBD0,
                    "operands": {
                        "lhs": {"name": "wait", "value": 14},
                        "rhs": {"name": "threshold", "value": 14},
                    },
                },
                {
                    "seq": 3, "type": "branch", "cycle": cycle,
                    "address": 0x0044FC30, "target": 0x0044FC34,
                    "taken": True, "conditional": True,
                    "function": 0x0044FBD0,
                },
                {
                    "seq": 4, "type": "write", "cycle": cycle,
                    "address": 0x10000000 + 17 * 152 + 26,
                    "instruction": 0x0044FC55, "function": 0x0044FBD0,
                    "native_slot": 17, "field": "y", "offset": 26,
                    "before": 9, "after": 8,
                },
            ],
        }

    def write_capture(self, root, name, capture, *, request=None,
                      network_disabled=True):
        path = root / f"{name}.json"
        path.write_text(json.dumps(capture, indent=2, sort_keys=True) + "\n")
        manifest = {
            "schema": 1,
            "capture": {"name": path.name, **bne_triage.file_identity(path)},
            "oracle": {"executable": {
                "sha256": bne_branch_witness.BNE_202_SHA256,
            }, "run_manifest": {
                "bytes": 1, "sha256": "b" * 64,
                "scenario": (request or {}).get(
                    "scenario", r"Campaign\Human\Human12.pud"),
                "seed": (request or {}).get("seed", 1),
                "cycles": (request or {}).get("cycle", 24),
                "branch_pause_cycle": (request or {}).get("cycle", 24),
            }},
            "runtime": {"network_disabled": network_disabled},
            "backend": {
                "name": "test-bts", "branch_history": True,
                "writer_watchpoint": True,
            },
            "harness": {"capture_importer": {
                "bytes": 1, "sha256": "a" * 64,
            }},
            "request": request or {
                "case": "case-a", "cycle": 24, "native_slot": 17,
                "fields": ["y"],
                "scenario": r"Campaign\Human\Human12.pud",
                "seed": 1, "fixture_id": "fixture-a",
                "plan_sha256": bne_triage.canonical_digest(
                    bne_branch_witness.plan_from_packet(self.packet())),
            },
        }
        path.with_name(path.stem + ".manifest.json").write_text(
            json.dumps(manifest, indent=2, sort_keys=True) + "\n")
        return path

    def test_plan_resolves_exact_native_byte_and_capture_window(self):
        plan = bne_branch_witness.plan_from_packet(self.packet())
        self.assertEqual(17, plan["focus"]["native_slot"])
        self.assertEqual("movement.step", plan["focus"]["event_kind"])
        self.assertEqual(23, plan["capture_window"]["start_cycle"])
        self.assertEqual(26, plan["native_layout"]["watches"][0]["offset"])
        self.assertIn("17 * 152 + 26",
                      plan["native_layout"]["watches"][0]
                          ["runtime_address"]["expression"])

    def test_localizes_writer_ranks_contrasted_branch_and_infers_predicate(self):
        plan = bne_branch_witness.plan_from_packet(self.packet())
        result = bne_branch_witness.analyze_capture(
            plan, self.capture(), controls=[self.capture(control=True)],
            source_root=Path(__file__).resolve().parents[3],
        )
        self.assertEqual(0x0044FC55, result["writer"]["instruction"])
        self.assertEqual(0x0044FC10, result["top_branch"]["address"])
        self.assertEqual(6.0,
                         result["top_branch"]["score_breakdown"]["contrast"])
        self.assertTrue(result["proof"]["predicate_inferred"])
        self.assertTrue(result["proof"]["semantic_slice_ready"])
        self.assertEqual("wait >= threshold",
                         result["top_predicate"]["expression"])
        expressions = {
            candidate["expression"]
            for candidate in result["top_branch"]["predicate_candidates"]
        }
        self.assertIn("wait >= threshold", expressions)
        self.assertEqual("engine/src/main/java/net/chonkbase/chonkcraft/engine/World.java",
                         result["java_source_candidates"][0]["path"])

    def test_missing_position_write_follows_native_timer_precursor(self):
        packet = self.packet()
        packet["native_state"] = {"24": {"units": {"17": {
            "raw_changes_from_previous_packet_cycle": [{
                "field": "animation_timer", "before": 3, "after": 2,
            }],
        }}}}
        plan = bne_branch_witness.plan_from_packet(packet)
        self.assertEqual(["animation_timer"], plan["focus"]["causal_fields"])
        timer = next(item for item in plan["native_layout"]["watches"]
                     if item["field"] == "animation_timer")
        self.assertEqual("native-causal-precursor", timer["purpose"])
        self.assertEqual(7, timer["offset"])

        capture = self.capture()
        capture["events"][-1].update({
            "field": "animation_timer", "offset": 7,
            "before": 3, "after": 2,
        })
        result = bne_branch_witness.analyze_capture(
            plan, capture, source_root=None,
        )
        self.assertEqual("native-causal-precursor", result["writer_role"])
        self.assertFalse(result["proof"]["exact_native_writer"])
        self.assertTrue(result["proof"]["exact_native_precursor_writer"])

    def test_authentication_rejects_online_capture(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_capture(
                Path(directory), "capture", self.capture(),
                network_disabled=False,
            )
            with self.assertRaisesRegex(ValueError, "offline"):
                bne_branch_witness.load_verified_capture(path)

    def test_control_capture_must_match_case_scenario_seed_and_slot(self):
        with tempfile.TemporaryDirectory() as directory:
            path = self.write_capture(
                Path(directory), "control", self.capture(control=True, cycle=23),
                request={
                    "case": "case-a", "cycle": 23, "native_slot": 17,
                    "fields": ["y"], "scenario": r"Campaign\Orc\Orc01.pud",
                    "seed": 1, "fixture_id": "fixture-a",
                },
            )
            with self.assertRaisesRegex(ValueError, "not compatible"):
                bne_branch_witness.load_verified_capture(
                    path, plan=bne_branch_witness.plan_from_packet(self.packet()),
                    require_plan_match=False,
                )

    def test_durable_run_is_content_addressed_and_idempotent(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            triage = root / "triage"
            packet_dir = triage / "packets" / "case-a"
            packet_dir.mkdir(parents=True)
            packet_path = packet_dir / "packet.json"
            packet_path.write_text(json.dumps(self.packet()) + "\n")
            request = {"engine": {"head": "abc"}}
            request_sha = bne_triage.canonical_digest(request)
            manifest = {
                "schema": 1, "request_sha256": request_sha,
                "request": request,
                "packets": [{
                    "case": "case-a", "cycle": 24,
                    "packet": "packets/case-a/packet.json",
                }],
                "artifacts": bne_triage.inventory_files(triage, [packet_path]),
            }
            (triage / "manifest.json").write_text(
                json.dumps(manifest, indent=2, sort_keys=True) + "\n")
            capture = self.write_capture(root, "capture", self.capture())
            control = self.write_capture(
                root, "control", self.capture(control=True, cycle=23),
                request={
                    "case": "case-a", "cycle": 23, "native_slot": 17,
                    "fields": ["y"], "scenario": r"Campaign\Human\Human12.pud",
                    "seed": 1, "fixture_id": "fixture-a",
                },
            )
            artifact_root = root / "witness"
            status, run_root = bne_branch_witness.run_branch_witness(
                triage, "case-a", capture, artifact_root,
                control_paths=[control], source_root=None,
            )
            self.assertEqual(0, status)
            first_manifest = json.loads((run_root / "manifest.json").read_text())
            bne_branch_witness.verify_witness_manifest(
                run_root, first_manifest, first_manifest["request_sha256"],
            )
            again_status, again_root = bne_branch_witness.run_branch_witness(
                triage, "case-a", capture, artifact_root,
                control_paths=[control], source_root=None,
            )
            self.assertEqual((0, run_root), (again_status, again_root))
            self.assertTrue((artifact_root / "latest-case-a.json").is_file())


if __name__ == "__main__":
    unittest.main()
