import json
from pathlib import Path
import sys
import tempfile
import unittest

SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_corpus


class CorpusPlanTest(unittest.TestCase):

    def test_builds_all_52_retail_campaign_cases(self):
        cases = bne_corpus.campaign_cases(1800, 1)
        self.assertEqual(52, len(cases))
        self.assertEqual(52, len({case["id"] for case in cases}))
        self.assertEqual(52, len({case["scenario"] for case in cases}))
        self.assertEqual(r"Campaign\Human\Human01.pud", cases[0]["scenario"])
        self.assertEqual(r"Campaign\XOrc\2XOrc12.pud", cases[-1]["scenario"])
        self.assertTrue(all(case["cycles"] == 1800 for case in cases))

    def test_rejects_duplicate_case_ids(self):
        plan = bne_corpus.campaign_plan(40, 1)
        plan["cases"][1]["id"] = plan["cases"][0]["id"]
        with self.assertRaisesRegex(ValueError, "duplicate"):
            bne_corpus.validate_plan_data(plan, Path.cwd())

    def test_rejects_unknown_scenarios_and_unsafe_ids(self):
        plan = bne_corpus.campaign_plan(40, 1)
        plan["cases"] = [dict(plan["cases"][0])]
        plan["cases"][0]["scenario"] = r"Maps\Custom.pud"
        with self.assertRaisesRegex(ValueError, "built-in"):
            bne_corpus.validate_plan_data(plan, Path.cwd())
        plan = bne_corpus.campaign_plan(40, 1)
        plan["cases"] = [dict(plan["cases"][0])]
        plan["cases"][0]["id"] = "../escape"
        with self.assertRaisesRegex(ValueError, "unsafe"):
            bne_corpus.validate_plan_data(plan, Path.cwd())

    def test_writes_a_deterministic_plan_shape(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "plan.json"
            data = bne_corpus.campaign_plan(40, 7)
            bne_corpus._write_json_atomic(path, data)
            loaded, cases = bne_corpus.load_plan(path)
        self.assertEqual(data, loaded)
        self.assertEqual(52, len(cases))
        self.assertEqual(7, cases[0]["seed"])


if __name__ == "__main__":
    unittest.main()
