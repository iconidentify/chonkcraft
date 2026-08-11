#!/usr/bin/env python3

from pathlib import Path
import sys
import unittest


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

import bne_experiments


class ExperimentPlanningTest(unittest.TestCase):
    def test_movement_investigation_starts_with_transition_cadence(self):
        plan = bne_experiments.default_investigation_plan(
            "retail-human-05-idle", 29, [{
                "kind": "unit", "field": "y", "unit_type": "unit-zeppelin",
                "oracle": 56, "java": 58,
            }],
        )
        self.assertEqual("transition-cadence", plan["recommended"]["id"])

    def test_cadence_evidence_increases_tied_blocker_tractability(self):
        cases = [{
            "id": "movement", "first_divergence_cycle": 29,
            "findings": [{"kind": "unit", "field": "y",
                          "unit_type": "unit-zeppelin"}],
        }, {
            "id": "order", "first_divergence_cycle": 29,
            "findings": [{"kind": "unit", "field": "order",
                          "unit_type": "unit-ogre"}],
        }]
        ranked = bne_experiments.rank_tied_blockers(cases, {
            "movement": {"phase": {"classification": "one-time-delay"}},
        })
        self.assertEqual("movement", ranked[0]["case"])
        self.assertEqual("semantic-bridge", ranked[0]["recommended_tool"])
        self.assertTrue(ranked[0]["does_not_change_acceptance_priority"])


if __name__ == "__main__":
    unittest.main()
