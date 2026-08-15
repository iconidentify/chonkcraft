#!/usr/bin/env python3
"""Regression coverage for the determinism trace differ."""

from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


DIFFER = Path(__file__).with_name("diff-determinism.py")


class DiffDeterminismTest(unittest.TestCase):

    def test_reused_upstream_slot_starts_a_new_identity(self):
        upstream = """\
cycle 1 seed 00000001
u 0 unit-footman p0 1 1 hp 60 o 1
cycle 2 seed 00000001
cycle 3 seed 00000001
u 0 unit-dead-vision-2-6 p0 2 2 hp 1 o 1
"""
        java = """\
cycle 1 seed 00000001
u 1 unit-footman p0 1 1 hp 60 o STILL
cycle 2 seed 00000001
cycle 3 seed 00000001
u 2 unit-dead-vision-2-6 p0 2 2 hp 1 o STILL
"""
        with tempfile.TemporaryDirectory() as directory:
            left = Path(directory, "upstream.txt")
            right = Path(directory, "java.txt")
            left.write_text(upstream)
            right.write_text(java)
            result = subprocess.run(
                [sys.executable, str(DIFFER), str(left), str(right)],
                check=False, capture_output=True, text=True)

        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("traces agree over 3 shared cycles", result.stdout)

    def test_action_order_can_be_disabled_for_a_non_action_table_trace(self):
        left = """\
cycle 1 seed 00000001
u 9 unit-footman p0 1 1 hp 60 o STILL
u 8 unit-grunt p1 2 2 hp 60 o STILL
"""
        right = """\
cycle 1 seed 00000001
u 1 unit-grunt p1 2 2 hp 60 o STILL
u 2 unit-footman p0 1 1 hp 60 o STILL
"""
        with tempfile.TemporaryDirectory() as directory:
            left_path = Path(directory, "bne.txt")
            right_path = Path(directory, "java.txt")
            left_path.write_text(left)
            right_path.write_text(right)
            strict = subprocess.run(
                [sys.executable, str(DIFFER), str(left_path), str(right_path)],
                check=False, capture_output=True, text=True)
            relaxed = subprocess.run(
                [sys.executable, str(DIFFER), "--ignore-action-order",
                 str(left_path), str(right_path)],
                check=False, capture_output=True, text=True)

        self.assertEqual(1, strict.returncode)
        self.assertIn("action-table order", strict.stdout)
        self.assertEqual(0, relaxed.returncode, relaxed.stdout + relaxed.stderr)

    def test_cycle_one_pairs_by_owner_and_tile_not_type_name(self):
        native = """\
cycle 1 seed 00000001
u 1550 unit-skeleton p6 10 10 hp 40 o STILL
u 1551 unit-footman p0 1 1 hp 60 o STILL
"""
        java = """\
cycle 1 seed 00000001
u 20 unit-attack-peasant p6 10 10 hp 40 o STILL
u 2 unit-footman p0 1 1 hp 60 o STILL
"""
        with tempfile.TemporaryDirectory() as directory:
            left = Path(directory, "native.txt")
            right = Path(directory, "java.txt")
            left.write_text(native)
            right.write_text(java)
            result = subprocess.run(
                [sys.executable, str(DIFFER), str(left), str(right),
                 "--ignore-action-order"],
                check=False, capture_output=True, text=True)

        self.assertEqual(1, result.returncode, result.stdout + result.stderr)
        self.assertIn("paired 2 of 2 units", result.stdout)
        self.assertIn("type", result.stdout)
        self.assertNotIn("unmatched", result.stdout)


if __name__ == "__main__":
    unittest.main()
