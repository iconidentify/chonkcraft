#!/usr/bin/env python3

import json
from pathlib import Path
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

import bne_decision_remote


class DecisionRemoteTest(unittest.TestCase):
    def plan(self):
        return {
            "schema": 1, "decision_id": "decision-a", "case": "case-a",
            "scenario": r"Campaign\Human\Human13.pud", "seed": 1,
            "focus": {"native_slot": 17},
            "decision": {"entry_address": 0x0044FB00,
                         "focus_register": "esi", "field": "order"},
            "captures": {
                "rejected": {"cycle": 29, "expected_outcome": "rejected"},
                "accepted": {"cycle": 34, "expected_outcome": "accepted"},
            },
        }

    def test_decision_capture_uses_a_content_addressed_isolated_harness(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "plan.json"
            path.write_text(json.dumps(self.plan()) + "\n")
            remote = bne_decision_remote.build_remote_plan(
                path, phases=["rejected", "accepted"],
            )
        deployment = remote["deployment"]
        self.assertRegex(deployment["harness_name"],
                         r"^harness-decision-miner-[0-9a-f]{12}$")
        self.assertEqual(2, len(remote["commands"]))
        self.assertIn("decision-capture", remote["commands"][0]["argv"])
        outputs = [item["remote_files"]["capture"]
                   for item in remote["commands"]]
        self.assertNotEqual(outputs[0], outputs[1])
        self.assertTrue(all(deployment["sha256"][:12] in path
                            for path in outputs))

    def test_bootstrap_also_uses_the_content_addressed_isolated_harness(self):
        bootstrap = {
            "schema": 1, "case": "case-a",
            "scenario": r"Campaign\Human\Human13.pud", "seed": 1,
            "divergence_cycle": 34,
            "focus": {"native_slot": 17, "fields": ["order"]},
        }
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "bootstrap.json"
            path.write_text(json.dumps(bootstrap) + "\n")
            remote = bne_decision_remote.build_remote_plan(
                path, bootstrap=True,
            )
        deployment = remote["deployment"]
        self.assertRegex(deployment["harness_name"],
                         r"^harness-decision-miner-[0-9a-f]{12}$")
        command = remote["commands"][0]["argv"]
        self.assertIn("branch-capture", command)
        self.assertIn(deployment["harness_name"], command[1])
        self.assertEqual(deployment["harness_name"],
                         command[command.index("--harness-name") + 1])
        self.assertIn(deployment["sha256"][:12],
                      remote["commands"][0]["remote_files"]["capture"])

    def test_remote_host_and_root_are_strictly_validated(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "plan.json"
            path.write_text(json.dumps(self.plan()) + "\n")
            with self.assertRaisesRegex(ValueError, "host"):
                bne_decision_remote.build_remote_plan(path, host="bad;host")
            with self.assertRaisesRegex(ValueError, "home-relative"):
                bne_decision_remote.build_remote_plan(path, remote_root="../oracle")


if __name__ == "__main__":
    unittest.main()
