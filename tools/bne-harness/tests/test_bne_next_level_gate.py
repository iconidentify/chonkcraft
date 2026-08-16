from pathlib import Path
import argparse
import sys
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_next_level_gate as gate


class NextLevelGateTest(unittest.TestCase):

    def test_next_work_names_all_open_lanes(self):
        player = {"complete": False, "physical_transactions": {"complete": False}}
        ai = {"complete": False}
        campaign = {"complete": False}
        work = gate.next_work(player, ai, campaign)
        self.assertEqual(4, len(work))
        self.assertTrue(any("physical transaction" in item for item in work))
        self.assertTrue(any("AI or combat-lifecycle" in item for item in work))
        self.assertTrue(any("mission trigger" in item for item in work))


if __name__ == "__main__":
    unittest.main()
