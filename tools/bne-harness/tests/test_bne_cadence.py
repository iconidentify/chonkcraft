#!/usr/bin/env python3

import io
from pathlib import Path
import sys
import unittest


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

import bne_cadence
from bne_packet import parse_trace


def trace(unit, changes, through=80, pixel_changes=None):
    position = [94, 60]
    pixel = [94 * 32, 60 * 32]
    lines = []
    changes = dict(changes)
    pixel_changes = dict(pixel_changes or {})
    for cycle in range(1, through + 1):
        if cycle in changes:
            position = list(changes[cycle])
        if cycle in pixel_changes:
            pixel = list(pixel_changes[cycle])
        lines.extend([
            f"cycle {cycle} seed 1",
            f"u {unit} unit-zeppelin p0 {position[0]} {position[1]} "
            f"hp 150 o Patrol px {pixel[0]} {pixel[1]}",
        ])
    return parse_trace(io.StringIO("\n".join(lines) + "\n"))


class CadenceTest(unittest.TestCase):
    def test_detects_one_time_wait_and_infers_the_java_pair(self):
        native = trace(1500, {
            9: (94, 58), 29: (94, 56), 49: (94, 54), 69: (94, 52),
        })
        java = trace(100, {
            9: (94, 58), 39: (94, 56), 59: (94, 54), 79: (94, 52),
        })

        result = bne_cadence.analyze_cadence(
            native, java, native_unit=1500,
        )

        self.assertEqual(100, result["java_unit"])
        self.assertEqual([9, 29, 49, 69],
                         result["native"]["cadence"]["cycles"])
        self.assertEqual([9, 39, 59, 79],
                         result["java"]["cadence"]["cycles"])
        self.assertEqual([0, 10, 10, 10],
                         result["phase"]["phase_offsets"])
        self.assertEqual("one-time-delay",
                         result["phase"]["classification"])
        self.assertEqual(10, result["phase"]["estimated_extra_wait"])

    def test_distinguishes_stable_period_from_a_settled_tail(self):
        stable = bne_cadence.cadence_signature([9, 29, 49, 69])
        delayed = bne_cadence.cadence_signature([9, 39, 59, 79])

        self.assertEqual(20, stable["stable_period"])
        self.assertEqual("stable-period", stable["classification"])
        self.assertIsNone(delayed["stable_period"])
        self.assertEqual(20, delayed["settled_tail_period"])
        self.assertEqual("settled-tail", delayed["classification"])

    def test_reports_a_different_transition_sequence_honestly(self):
        native = trace(17, {9: (94, 58), 29: (94, 56)}, through=40)
        java = trace(3, {9: (93, 59), 29: (93, 57)}, through=40)

        result = bne_cadence.analyze_cadence(
            native, java, native_unit=17, java_unit=3,
        )

        self.assertEqual("different-transition-sequence",
                         result["phase"]["classification"])
        self.assertFalse(result["phase"]["transition_values_match"])

    def test_finds_pixel_drift_while_tiles_still_match(self):
        native = trace(17, {}, through=8, pixel_changes={
            3: (3008, 1917), 6: (3008, 1914),
        })
        java = trace(3, {}, through=8, pixel_changes={
            3: (3008, 1914), 6: (3008, 1911),
        })
        for cycle in range(6, 9):
            java[cycle]["units"][3]["order"] = "Attack"

        result = bne_cadence.analyze_cadence(
            native, java, native_unit=17, java_unit=3,
            field="pixel-position",
        )

        precursor = result["subtile_precursor"]
        self.assertTrue(precursor["hidden_mismatch"])
        self.assertEqual(3, precursor["earliest_hidden_mismatch_cycle"])
        self.assertEqual(6, precursor["first_coarse_mismatch_cycle"])
        self.assertEqual(3, precursor["lead_cycles"])
        self.assertEqual([0, -3], precursor["first_observations"][0]["delta"])
        self.assertTrue(result["useful"])

    def test_pixel_cadence_refuses_an_old_trace_without_pixel_metadata(self):
        native = trace(17, {}, through=3)
        old_java = parse_trace(io.StringIO("""\
cycle 1 seed 1
u 3 unit-zeppelin p0 94 60 hp 150 o Patrol
cycle 2 seed 1
u 3 unit-zeppelin p0 94 60 hp 150 o Patrol
cycle 3 seed 1
u 3 unit-zeppelin p0 94 60 hp 150 o Patrol
"""))

        with self.assertRaisesRegex(ValueError, "lacks pixel coordinates"):
            bne_cadence.analyze_cadence(
                native, old_java, native_unit=17, java_unit=3,
                field="pixel-position",
            )

    def test_one_gap_never_becomes_a_frequency_claim(self):
        signature = bne_cadence.cadence_signature([24, 29])

        self.assertFalse(signature["frequency_claim_supported"])
        self.assertEqual("single-gap", signature["classification"])
        self.assertIn("cannot establish", signature["frequency_evidence"])


if __name__ == "__main__":
    unittest.main()
