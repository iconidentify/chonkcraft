from pathlib import Path
import json
import sys
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_state_machine as miner


RECORD_BYTES = 24


def record(**offsets) -> bytes:
    """One synthetic unit record: a quiet field of bytes with a few written."""
    raw = bytearray(RECORD_BYTES)
    for offset, value in offsets.items():
        position = int(offset.lstrip("o"))
        if value > 0xff:
            width = 4 if value > 0xffff else 2
            raw[position:position + width] = value.to_bytes(width, "little")
        else:
            raw[position] = value
    return bytes(raw)


def samples(values, *, first_cycle=30, written=True, key="o4"):
    return [miner.Sample(cycle=first_cycle + index,
                         raw=record(**{key: value}), written=written)
            for index, value in enumerate(values)]


def find(report, key):
    for item in report["trajectories"]:
        if item["key"] == key:
            return item
    raise AssertionError(f"{key} is not in the report: "
                         + ", ".join(item["key"] for item in report["trajectories"]))


class BneStateMachineTest(unittest.TestCase):

    def test_a_byte_that_climbs_by_one_is_a_counter(self):
        report = miner.mine(samples([0, 1, 2, 3, 4, 5]))
        item = find(report, "byte:0x04")
        self.assertIn("unit-counter", item["shapes"],
                      "a byte adding one per cycle was not recognized as a "
                      "counter, which is the shape the whole miner exists for")
        self.assertIn("monotonic-increase", item["shapes"])
        self.assertEqual([31, 32, 33, 34, 35], item["change_cycles"])
        self.assertEqual(5, item["maximum"])

    def test_a_wide_counter_is_reported_once_it_carries(self):
        # A word counter crossing 0x00ff -> 0x0100: the low byte wraps to zero
        # on the cycle the high byte moves, which is what a carry looks like
        # and what says the value is really two bytes wide.
        values = [253, 254, 255, 256, 257, 258]
        report = miner.mine(samples(values))
        word = find(report, "word:0x04")
        self.assertEqual(values, word["values"],
                         "the two-byte value was not read across its carry")
        self.assertIn("unit-counter", word["shapes"])
        low = find(report, "byte:0x04")
        self.assertNotIn("unit-counter", low["shapes"],
                         "the low byte alone was still described as a clean "
                         "counter although it wrapped")

    def test_a_dword_counter_is_read_at_its_full_width(self):
        values = [0xffff, 0x10000, 0x10001, 0x10002]
        report = miner.mine(samples(values))
        self.assertEqual(values, find(report, "dword:0x04")["values"],
                         "a four-byte value that carried past its second byte "
                         "was not read at full width")

    def test_single_bits_are_followed_when_a_byte_is_used_as_flags(self):
        report = miner.mine(samples([0b0000, 0b0100, 0b0100, 0b0000, 0b0100]))
        item = find(report, "bit:0x04.2")
        self.assertEqual([0, 1, 1, 0, 1], item["values"],
                         "a byte used as flags was not followed bit by bit")
        self.assertIn("toggle", item["shapes"])

    def test_a_byte_used_as_a_number_is_not_split_into_bits(self):
        report = miner.mine(samples([1, 2, 3, 4, 5, 6, 7, 8]))
        keys = [item["key"] for item in report["trajectories"]]
        self.assertNotIn("bit:0x04.0", keys,
                         "a small integer was reported as eight independent "
                         "flags, which buries the counter it actually is")

    def test_a_counter_that_stops_at_its_ceiling_is_saturating(self):
        report = miner.mine(samples([4, 5, 6, 7, 8, 8, 8, 8]))
        item = find(report, "byte:0x04")
        self.assertIn("saturating", item["shapes"],
                      "a counter that climbed and then held was not reported "
                      "as saturating")
        self.assertEqual(8, item["saturation_value"])
        self.assertEqual(4, item["saturated_cycles"])

    def test_a_counter_that_falls_back_reports_where_it_reset(self):
        report = miner.mine(samples([0, 1, 2, 3, 4, 5, 6, 7, 8, 0, 1]))
        item = find(report, "byte:0x04")
        self.assertIn("reset", item["shapes"])
        self.assertEqual(8, item["reset_threshold"],
                         "the value the counter reached before falling back "
                         "was not reported, which is the threshold the whole "
                         "sequence turns on")
        self.assertEqual([{"cycle": 39, "from": 8, "to": 0}], item["resets"])

    def test_a_timer_armed_after_a_jump_is_recognized_as_a_countdown(self):
        report = miner.mine(samples([0, 0, 15, 14, 13, 12, 11]))
        item = find(report, "byte:0x04")
        self.assertIn("timer-armed", item["shapes"],
                      "a value that jumped to fifteen and counted down was "
                      "not recognized as an armed timer")
        self.assertEqual(15, item["armed"]["armed_value"])
        self.assertEqual(32, item["armed"]["armed_cycle"])
        self.assertEqual(4, item["armed"]["counted_down"])

    def test_a_repeating_state_reports_its_period(self):
        report = miner.mine(samples([1, 2, 3, 1, 2, 3, 1, 2, 3]))
        item = find(report, "byte:0x04")
        self.assertIn("periodic", item["shapes"])
        self.assertEqual(3, item["period"],
                         "a three-cycle repeat was not measured")

    def test_a_field_that_never_changes_is_not_reported_at_all(self):
        report = miner.mine(samples([7, 7, 7, 7]))
        self.assertEqual([], report["trajectories"],
                         "a record where nothing moved still produced "
                         "observables, so a real window would be full of the "
                         "hundred bytes that did nothing")
        self.assertEqual(0, report["changing_observables"])

    def test_a_value_rewritten_without_changing_is_reported_as_persisting(self):
        window = [
            miner.Sample(cycle=30, raw=record(o4=5, o8=1), written=True),
            miner.Sample(cycle=31, raw=record(o4=5, o8=2), written=True),
            miner.Sample(cycle=32, raw=record(o4=5, o8=2), written=False),
            miner.Sample(cycle=33, raw=record(o4=6, o8=2), written=True),
        ]
        item = find(miner.mine(window), "byte:0x04")
        self.assertEqual([30, 31, 33], item["record_written_cycles"])
        self.assertEqual([30, 31], item["rewritten_unchanged_cycles"],
                         "a value that sat inside a record the engine rewrote "
                         "was not distinguished from one nobody touched")
        self.assertEqual("record", item["write_granularity"],
                         "the report claims byte-level write evidence that "
                         "the fixture does not carry")

    def test_a_missing_cycle_is_named_rather_than_silently_bridged(self):
        window = [
            miner.Sample(cycle=30, raw=record(o4=1), written=True),
            miner.Sample(cycle=31, raw=record(o4=2), written=True),
            miner.Sample(cycle=34, raw=record(o4=5), written=True),
        ]
        report = miner.mine(window)
        self.assertFalse(report["contiguous"],
                         "a window with a hole in it was reported as though "
                         "every cycle had been seen")
        self.assertEqual([32, 33], report["missing_cycles"])

    def test_a_window_of_one_cycle_cannot_be_mined(self):
        with self.assertRaises(ValueError,
                msg="a single cycle produced a state machine, which is a "
                    "trajectory of one point"):
            miner.mine(samples([1]))

    def test_the_ramp_is_paired_with_what_changed_at_the_top_of_it(self):
        # The motivating shape, in miniature: a counter climbs, and on the
        # cycle it reaches four another field is cleared and a timer arms.
        window = []
        for index, (counter, route, timer) in enumerate([
                (1, 22, 0), (2, 22, 0), (3, 22, 0), (4, 0, 15),
                (4, 0, 14), (4, 0, 13)]):
            window.append(miner.Sample(
                cycle=40 + index,
                raw=record(o4=counter, o8=route, o12=timer), written=True))
        report = miner.mine(window)
        pairing = next(item for item in report["thresholds"]
                       if item["ramp"] == "byte:0x04"
                       and item["consequence"] == "byte:0x08")
        self.assertEqual(4, pairing["ramp_value_at_consequence"],
                         "the value the counter held when the other field "
                         "changed was not reported")
        self.assertEqual(3, pairing["ramp_value_before"])
        self.assertEqual(43, pairing["consequence_cycle"])
        self.assertTrue(pairing["at_ramp_peak"])
        timer = find(report, "byte:0x0c")
        self.assertIn("timer-armed", timer["shapes"])

    def test_the_most_informative_field_is_reported_first(self):
        window = []
        for index in range(8):
            window.append(miner.Sample(
                cycle=40 + index,
                # A counter worth reading, and an animation frame that changes
                # every cycle and means nothing.
                raw=record(o4=index // 2, o8=index % 4 + 1), written=True))
        report = miner.mine(window)
        self.assertEqual("byte:0x04", report["trajectories"][0]["key"],
                         "the report leads with the field that changes on "
                         "every cycle rather than the counter, so an agent "
                         "reading only the top of it learns nothing")

    def test_the_same_window_twice_produces_the_same_report(self):
        window = samples([0, 1, 2, 3, 4, 4, 4])
        self.assertEqual(json.dumps(miner.mine(window), sort_keys=True),
                         json.dumps(miner.mine(window), sort_keys=True),
                         "the same evidence produced two different reports, "
                         "so a content-addressed run could never cache")

    def test_the_cadence_of_the_transitions_is_reported_not_only_their_count(self):
        # Two counters with the same shape and different mechanisms: one moves
        # every cycle, the other every third.
        every_third = find(miner.mine(samples([1, 1, 1, 2, 2, 2, 3, 3])),
                           "byte:0x04")
        every_cycle = find(miner.mine(samples([1, 2, 3, 4])), "byte:0x04")
        self.assertEqual([3], every_third["change_gaps"],
                         "how many cycles apart the transitions fall was not "
                         "reported, so a counter that moves every third cycle "
                         "reads the same as one that moves every cycle")
        self.assertEqual([1, 1], every_cycle["change_gaps"])
        self.assertEqual(["monotonic-increase", "unit-counter"],
                         [shape for shape in every_third["shapes"]
                          if shape in ("monotonic-increase", "unit-counter")],
                         "both are counters that add one, and only the "
                         "cadence tells them apart")

    def test_a_byte_with_no_name_in_the_layout_is_still_followed(self):
        # The whole point: the interesting offset is usually one the pinned
        # unit layout has no name for.
        window = [
            miner.Sample(cycle=30 + index, raw=record(o21=value), written=True)
            for index, value in enumerate([0, 1, 2, 3])
        ]
        item = find(miner.mine(window), "byte:0x15")
        self.assertIsNone(item["field"],
                          "the synthetic offset was given a name it does not "
                          "have, so this proves nothing about unnamed bytes")
        self.assertIn("unit-counter", item["shapes"],
                      "an offset the layout cannot name was not followed at "
                      "all, which is most of the record")

    def test_a_packet_window_is_read_straight_out_of_the_packet(self):
        packet = {"native_state": {
            "30": {"units": {"1448": {"raw_hex": record(o4=1).hex(),
                                      "changed_this_cycle": True,
                                      "generation": 9}}},
            "31": {"units": {"1448": {"raw_hex": record(o4=2).hex(),
                                      "changed_this_cycle": True,
                                      "generation": 10}}},
            "32": {"units": {"1448": None}},
        }}
        window = miner.samples_from_packet(packet, 1448)
        self.assertEqual([30, 31], [sample.cycle for sample in window],
                         "a cycle where the slot was absent was invented "
                         "rather than skipped")
        self.assertEqual(10, window[-1].generation)


if __name__ == "__main__":
    unittest.main()
