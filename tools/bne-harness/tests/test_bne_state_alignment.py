from pathlib import Path
import sys
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_state_machine as miner

from test_bne_state_machine import record


def java_window(rows, *, first_cycle=30):
    """A followed unit's per-cycle causal record, cycle by cycle."""
    return {first_cycle + index: dict(row) for index, row in enumerate(rows)}


class BneStateAlignmentTest(unittest.TestCase):

    def test_a_java_field_that_moves_with_a_native_byte_is_a_correlation(self):
        native = miner.mine([
            miner.Sample(cycle=30 + index, raw=record(o4=value), written=True)
            for index, value in enumerate([0, 1, 2, 3, 4])
        ])
        java = miner.series_trajectories(java_window([
            {"collision": 0, "x": 5}, {"collision": 1, "x": 5},
            {"collision": 2, "x": 5}, {"collision": 3, "x": 5},
            {"collision": 4, "x": 5},
        ]))
        pairing = next(item for item in
                       miner.correlate(native["trajectories"], java, 0)
                       if item["native"] == "byte:0x04")
        self.assertEqual("strong-temporal-correlation", pairing["confidence"],
                         "a Java field that changed on exactly the same "
                         "cycles carrying exactly the same values was not "
                         "reported as correlated")
        self.assertEqual("collision", pairing["java_counterpart"])

    def test_two_java_fields_moving_together_are_reported_as_ambiguous(self):
        native = miner.mine([
            miner.Sample(cycle=30 + index, raw=record(o4=value), written=True)
            for index, value in enumerate([0, 1, 2, 3])
        ])
        java = miner.series_trajectories(java_window([
            {"collision": 0, "order_delay": 0}, {"collision": 1, "order_delay": 1},
            {"collision": 2, "order_delay": 2}, {"collision": 3, "order_delay": 3},
        ]))
        pairing = next(item for item in
                       miner.correlate(native["trajectories"], java, 0)
                       if item["native"] == "byte:0x04")
        self.assertEqual("speculative-candidate", pairing["confidence"],
                         "two Java fields that move identically were resolved "
                         "to one of them, which timing cannot do")
        self.assertIsNone(pairing["java_counterpart"])
        self.assertIn("cannot choose", pairing["reason"])

    def test_a_native_byte_with_no_java_field_behind_it_says_so(self):
        native = miner.mine([
            miner.Sample(cycle=30 + index, raw=record(o4=value), written=True)
            for index, value in enumerate([0, 1, 2, 3, 3, 3])
        ])
        java = miner.series_trajectories(java_window([
            {"hp": 60}, {"hp": 60}, {"hp": 60}, {"hp": 60},
            {"hp": 54}, {"hp": 48},
        ]))
        pairing = next(item for item in
                       miner.correlate(native["trajectories"], java, 0)
                       if item["native"] == "byte:0x04")
        self.assertEqual("no-java-counterpart", pairing["confidence"],
                         "a native counter that nothing in the port moves "
                         "with was given a Java counterpart anyway, which is "
                         "how an invented flag gets evidence it never had")
        self.assertIsNone(pairing["java_counterpart"])

    def test_the_same_cycles_with_different_values_is_only_a_candidate(self):
        native = miner.mine([
            miner.Sample(cycle=30 + index, raw=record(o4=value), written=True)
            for index, value in enumerate([0, 1, 2, 3])
        ])
        java = miner.series_trajectories(java_window([
            {"x": 5}, {"x": 6}, {"x": 7}, {"x": 8},
        ]))
        pairing = next(item for item in
                       miner.correlate(native["trajectories"], java, 0)
                       if item["native"] == "byte:0x04")
        self.assertEqual("speculative-candidate", pairing["confidence"],
                         "a field that merely moves on the same cycles was "
                         "promoted to a correlation without carrying the same "
                         "values")
        self.assertEqual("x", pairing["java_counterpart"])

    def test_nothing_is_ever_graded_proved_without_being_told(self):
        native = miner.mine([
            miner.Sample(cycle=30 + index, raw=record(o4=value), written=True)
            for index, value in enumerate([0, 1, 2, 3, 4, 5])
        ])
        java = miner.series_trajectories(java_window([
            {"collision": value} for value in [0, 1, 2, 3, 4, 5]
        ]))
        graded = miner.correlate(native["trajectories"], java, 0)
        self.assertNotIn("proved", {item["confidence"] for item in graded},
                         "a perfect timing and value correlation was written "
                         "down as a proved mapping, which is the one thing "
                         "this tool must never do by itself")
        told = miner.correlate(native["trajectories"], java, 0,
                               proved={"byte:0x04": "collision"})
        self.assertEqual("proved", next(
            item["confidence"] for item in told
            if item["native"] == "byte:0x04"))

    def test_a_java_field_that_only_shares_some_cycles_is_speculative(self):
        native = miner.mine([
            miner.Sample(cycle=30 + index, raw=record(o4=value), written=True)
            for index, value in enumerate([0, 1, 2, 2, 3])
        ])
        java = miner.series_trajectories(java_window([
            {"wait": 0}, {"wait": 1}, {"wait": 1}, {"wait": 2}, {"wait": 3},
        ]))
        pairing = next(item for item in
                       miner.correlate(native["trajectories"], java, 0)
                       if item["native"] == "byte:0x04")
        self.assertEqual("speculative-candidate", pairing["confidence"])
        self.assertEqual([31, 34], pairing["partial_matches"][0][
            "shared_change_cycles"])

    def test_two_port_fields_that_together_fit_are_offered_as_a_combination(self):
        native = miner.mine([
            miner.Sample(cycle=30 + index, raw=record(o4=value), written=True)
            for index, value in enumerate([0, 1, 2, 2, 3])
        ])
        # Neither field alone changes on 31, 32 and 34; between them they do.
        java = miner.series_trajectories(java_window([
            {"a": 0, "b": 0}, {"a": 1, "b": 0}, {"a": 2, "b": 0},
            {"a": 2, "b": 0}, {"a": 2, "b": 1},
        ]))
        pairing = next(item for item in
                       miner.correlate(native["trajectories"], java, 0)
                       if item["native"] == "byte:0x04")
        self.assertEqual(["a", "b"], pairing["combination_candidate"],
                         "a native counter that no single port field fits, "
                         "but two together do, was reported as having no "
                         "counterpart rather than as a possible combination")
        self.assertEqual("speculative-candidate", pairing["confidence"],
                         "a combination of two fields was graded as strongly "
                         "as one field carrying the same values")

    def test_the_offset_between_the_two_cycle_numberings_is_measured(self):
        native = {30 + index: {"x": 10 + index, "y": 4}
                  for index in range(6)}
        java = {28 + index: {"x": 10 + index, "y": 4} for index in range(6)}
        estimate = miner.estimate_cycle_offset(native, java)
        self.assertEqual(2, estimate["offset"],
                         "the two-cycle difference between the fixture's "
                         "numbering and the engine's was not measured, so "
                         "every later comparison is off by it")
        self.assertTrue(estimate["unambiguous"])

    def test_an_unaligned_pair_reports_its_offset_as_ambiguous(self):
        native = {30 + index: {"x": 4, "y": 4} for index in range(4)}
        java = {30 + index: {"x": 9, "y": 9} for index in range(4)}
        estimate = miner.estimate_cycle_offset(native, java)
        self.assertFalse(estimate["unambiguous"],
                         "two windows that agree about nothing produced a "
                         "confident offset anyway")

    def test_alignment_names_the_last_agreed_and_first_differing_cycle(self):
        native = {30 + index: {"x": 10 + index, "y": 4, "hp": 60}
                  for index in range(5)}
        java = dict(native)
        java[33] = {"x": 13, "y": 4, "hp": 47}
        result = miner.first_divergence(native, java, 0)
        self.assertEqual(32, result["last_aligned_cycle"])
        self.assertEqual(33, result["first_divergent_cycle"])
        self.assertEqual({"native": 60, "java": 47}, result["differences"]["hp"])

    def test_the_observables_that_moved_at_the_divergence_are_listed(self):
        window = [
            miner.Sample(cycle=40 + index,
                         raw=record(o4=counter, o8=route), written=True)
            for index, (counter, route) in enumerate(
                [(1, 22), (2, 22), (3, 22), (4, 0), (4, 0)])
        ]
        report = miner.mine(window)
        moved = miner.changed_at(report["trajectories"], 43)
        keys = [item["key"] for item in moved]
        self.assertIn("byte:0x08", keys,
                      "the field cleared on the divergent cycle was not among "
                      "the candidates offered for it")
        cleared = next(item for item in moved if item["key"] == "byte:0x08")
        self.assertEqual((22, 0), (cleared["previous"], cleared["value"]))

    def test_an_order_name_is_followed_without_being_read_as_a_number(self):
        java = miner.series_trajectories(java_window([
            {"order": "MOVE"}, {"order": "MOVE"},
            {"order": "STILL"}, {"order": "ATTACK"},
        ]))
        item = next(entry for entry in java if entry["field"] == "order")
        self.assertEqual({"MOVE": 0, "STILL": 1, "ATTACK": 2}, item["encoding"],
                         "an order name was compared as a number without "
                         "saying which number stood for which order")
        self.assertEqual(["MOVE", "MOVE", "STILL", "ATTACK"],
                         item["encoded_values"])

    def test_a_java_field_that_never_moves_is_not_offered_as_a_counterpart(self):
        java = miner.series_trajectories(java_window([
            {"order": "MOVE", "hp": 60}, {"order": "MOVE", "hp": 60},
            {"order": "MOVE", "hp": 54},
        ]))
        self.assertEqual(["hp"], [item["field"] for item in java],
                         "a field that held one value for the whole window "
                         "was offered as something a native byte could "
                         "correspond to")


if __name__ == "__main__":
    unittest.main()
