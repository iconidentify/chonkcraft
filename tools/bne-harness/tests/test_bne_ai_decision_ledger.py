"""Tests for the normalized BNE 2.02b AI decision ledger.

Rows are built in code from a 48-byte AIPlayerState. The ledger has to
refuse raw process pointers and report the first cycle/field of a
mutation, not merely that two JSON documents differ.
"""

from pathlib import Path
import sys
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_ai_decision_ledger as ledger


AI_BASE = 0x00B00000
AI_SIZE = 0x4000


def raw_state(*, wait: int = 4, pc: int = 0x120,
              listed: int = 0x40, threshold: int = 0x80,
              wanted_workers: int = 5, process_pc: bool = False) -> bytes:
    raw = bytearray(48)
    raw[0:4] = wait.to_bytes(4, "little")
    pc_value = (AI_BASE + pc) if process_pc else pc
    list_value = AI_BASE + listed
    threshold_value = AI_BASE + threshold
    raw[0x04:0x08] = pc_value.to_bytes(4, "little")
    raw[0x13] = wanted_workers
    raw[0x22] = 0xff
    raw[0x23:0x27] = list_value.to_bytes(4, "little")
    raw[0x27:0x2b] = threshold_value.to_bytes(4, "little")
    return bytes(raw)


def sample_row(**overrides):
    fields = dict(
        cycle=12, player=6, profile=29,
        raw_state=raw_state(process_pc=True),
        ai_base=AI_BASE, ai_size=AI_SIZE,
        predicates=[{"id": 4, "result": True}],
        writes=[{"offset": 0x13, "value": 5}],
        launches=[{"kind": "ground", "accepted": True}],
        classification="independent-choice",
    )
    fields.update(overrides)
    return ledger.row(**fields)


class AiDecisionLedgerTest(unittest.TestCase):

    def test_two_identical_native_shaped_captures_are_byte_identical(self):
        first = ledger.build_ledger([sample_row(), sample_row(
            cycle=13, raw_state=raw_state(wait=3, process_pc=True))])
        second = ledger.build_ledger([sample_row(), sample_row(
            cycle=13, raw_state=raw_state(wait=3, process_pc=True))])
        self.assertTrue(ledger.ledgers_identical(first, second),
                        "two identical native captures must compare equal "
                        "after pointer normalization")

    def test_process_pointers_become_ai_bin_offsets(self):
        item = sample_row()
        self.assertEqual(0x120, item["pc_offset"])
        self.assertEqual(0x40, item["list_offset"])
        self.assertEqual(0x80, item["threshold_offset"])
        self.assertNotIn("00b0", item["non_pointer_hex"])

    def test_raw_out_of_range_pointer_fails_closed(self):
        raw = bytearray(raw_state())
        raw[0x04:0x08] = (0x00401234).to_bytes(4, "little")
        with self.assertRaisesRegex(ValueError, "not in"):
            ledger.normalize_state(bytes(raw), AI_BASE, AI_SIZE)

    def test_missing_active_player_cycle_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "missing active-player"):
            ledger.build_ledger(
                [sample_row(cycle=12, player=6)],
                active_players=[6], cycles=[12, 13],
            )

    def test_pc_mutation_fails_at_that_cycle_and_field(self):
        baseline = ledger.build_ledger([sample_row()])
        mutated = ledger.mutate_pc(baseline, 12, 6, 8)
        difference = ledger.first_difference(baseline, mutated)
        self.assertEqual(12, difference["cycle"])
        self.assertEqual(6, difference["player"])
        self.assertEqual("pc_offset", difference["field"])
        self.assertEqual(0x120, difference["left"])
        self.assertEqual(0x128, difference["right"])

    def test_predicate_mutation_fails_at_that_cycle_and_field(self):
        baseline = ledger.build_ledger([sample_row()])
        mutated = ledger.mutate_predicate_result(baseline, 12, 6)
        difference = ledger.first_difference(baseline, mutated)
        self.assertEqual(12, difference["cycle"])
        self.assertEqual("predicates", difference["field"])
        self.assertTrue(difference["left"][0]["result"])
        self.assertFalse(difference["right"][0]["result"])

    def test_state_byte_mutation_fails_at_that_cycle_and_field(self):
        baseline = ledger.build_ledger([sample_row()])
        mutated = ledger.mutate_state_byte(baseline, 12, 6, nibble=0)
        difference = ledger.first_difference(baseline, mutated)
        self.assertEqual(12, difference["cycle"])
        self.assertEqual("non_pointer_hex", difference["field"])
        self.assertNotEqual(difference["left"], difference["right"])


if __name__ == "__main__":
    unittest.main()
