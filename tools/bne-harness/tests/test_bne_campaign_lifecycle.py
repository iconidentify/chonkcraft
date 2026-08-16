from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).parents[3]
SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_campaign_lifecycle as lifecycle


CATALOG = ROOT / "engine/src/main/resources/chonkcraft/missions.tsv"


class CampaignLifecycleTest(unittest.TestCase):

    def test_sealed_catalog_has_every_action_path(self):
        result = lifecycle.inventory(CATALOG)
        self.assertEqual(52, result["missions"])
        self.assertEqual(137, result["triggers"])
        self.assertEqual({
            "DEFEAT": 79,
            "DELAYED_VICTORY": 1,
            "DIPLOMACY": 1,
            "SET_FLAG": 1,
            "VICTORY": 55,
        }, result["action_counts"])

    def test_generated_inventory_is_not_proof(self):
        compiled = lifecycle.inventory(CATALOG)
        proof = {"schema": lifecycle.PROOF_SCHEMA, "rows": []}
        report = lifecycle.coverage(compiled, proof)
        self.assertFalse(report["complete"])
        self.assertEqual(0, report["exact"])
        self.assertEqual(137, len(report["debts"]))

    def test_mutable_action_requires_save_resume_evidence(self):
        compiled = lifecycle.inventory(CATALOG)
        rows = []
        for expected in compiled["rows"]:
            rows.append({
                "mission": expected["mission"],
                "trigger": expected["trigger"],
                "native_observed": True,
                "java_observed": True,
                "exact": True,
                "save_resume_exact": not expected["requires_save_resume"],
            })
        report = lifecycle.coverage(
            compiled, {"schema": lifecycle.PROOF_SCHEMA, "rows": rows})
        self.assertFalse(report["complete"])
        self.assertEqual(3, len(report["debts"]))
        self.assertTrue(all("save-resume-not-exact" in debt["reasons"]
                            for debt in report["debts"]))


if __name__ == "__main__":
    unittest.main()
