from pathlib import Path
import sys
import unittest


ROOT = Path(__file__).parents[3]
SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_combat_lifecycle as combat


REQUIREMENTS = ROOT / "tools/bne-harness/combat-lifecycle-requirements.json"


class CombatLifecycleTest(unittest.TestCase):

    def test_matrix_has_every_player_visible_combat_domain(self):
        required = combat.load_requirements(REQUIREMENTS)
        self.assertEqual(9, required["encounters"])
        self.assertGreater(required["required_cells"], 150)
        observed = {item["encounter"] for item in required["cells"]}
        self.assertEqual({
            "melee-infantry", "ranged-infantry", "siege", "tower",
            "naval", "air", "direct-spell", "persistent-spell",
            "building-destruction",
        }, observed)

    def test_generated_matrix_is_not_native_proof(self):
        required = combat.load_requirements(REQUIREMENTS)
        report = combat.coverage(required, {
            "schema": combat.PROOF_SCHEMA, "rows": [],
        })
        self.assertFalse(report["complete"])
        self.assertEqual(0, report["exact"])
        self.assertEqual(required["required_cells"], len(report["debts"]))

    def test_every_cell_needs_exact_causal_order(self):
        required = combat.load_requirements(REQUIREMENTS)
        rows = [{
            **{key: item[key] for key in ("encounter", "stance", "phase")},
            "native_observed": True,
            "java_observed": True,
            "exact": True,
            "causal_order_exact": False,
        } for item in required["cells"]]
        report = combat.coverage(required, {
            "schema": combat.PROOF_SCHEMA, "rows": rows,
        })
        self.assertFalse(report["complete"])
        self.assertTrue(all("causal-order-not-exact" in item["reasons"]
                            for item in report["debts"]))


if __name__ == "__main__":
    unittest.main()
