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

    def test_ai_heap_base_is_derived_from_profile_list_and_threshold(self):
        ai = bytearray(AI_SIZE)
        ai[0:2] = (0x200).to_bytes(2, "little")
        ai[0x200:0x202] = (0x400).to_bytes(2, "little")
        ai[0x202:0x204] = (0x480).to_bytes(2, "little")
        raw = raw_state(pc=0x220, listed=0x400, threshold=0x480,
                        process_pc=True)
        text = tracer_dump(cycle=1, player=6, profile=0, raw=raw)
        self.assertEqual(AI_BASE, ledger.derive_ai_base(text, bytes(ai)))

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

    def test_telemetry_debt_does_not_disguise_exact_committed_state(self):
        baseline = ledger.build_ledger([sample_row()])
        changed = ledger.build_ledger([sample_row(predicates=[])])
        self.assertIsNone(ledger.first_state_difference(baseline, changed))
        difference = ledger.first_telemetry_difference(baseline, changed)
        self.assertEqual("predicates", difference["field"])
        self.assertFalse(ledger.ledgers_identical(baseline, changed))

    def test_state_byte_mutation_fails_at_that_cycle_and_field(self):
        baseline = ledger.build_ledger([sample_row()])
        mutated = ledger.mutate_state_byte(baseline, 12, 6, nibble=0)
        difference = ledger.first_difference(baseline, mutated)
        self.assertEqual(12, difference["cycle"])
        self.assertEqual("non_pointer_hex", difference["field"])
        self.assertNotEqual(difference["left"], difference["right"])

    def test_java_file_offsets_compare_equal_to_normalized_native_pointers(self):
        native_shaped = sample_row(raw_state=raw_state(process_pc=True))
        java_shaped = sample_row(raw_state=raw_state(process_pc=False))
        left = ledger.build_ledger([native_shaped])
        right = ledger.build_ledger([java_shaped])
        self.assertTrue(ledger.ledgers_identical(left, right),
                        "Java already stores ai.bin file offsets; they must "
                        "compare equal to native process pointers after "
                        "normalization")

    def test_two_identical_native_trace_dumps_compare_equal(self):
        text = tracer_dump(cycle=12, player=6, profile=29,
                           raw=raw_state(process_pc=True))
        first = ledger.ledger_from_native_trace(
            text, ai_base=AI_BASE, ai_size=AI_SIZE)
        second = ledger.ledger_from_native_trace(
            text, ai_base=AI_BASE, ai_size=AI_SIZE)
        self.assertTrue(ledger.ledgers_identical(first, second),
                        "two identical native AIPlayerState dumps must "
                        "compare equal after pointer normalization")
        self.assertEqual(1, len(first["rows"]))
        self.assertEqual(0x120, first["rows"][0]["pc_offset"])
        self.assertEqual(0x40, first["rows"][0]["list_offset"])
        self.assertEqual(0x80, first["rows"][0]["threshold_offset"])

    def test_per_cycle_native_trace_is_complete_for_active_player(self):
        text = tracer_dump(cycle=12, player=6, profile=29,
                           raw=raw_state(process_pc=True)) + tracer_dump(
            cycle=13, player=6, profile=29,
            raw=raw_state(wait=3, process_pc=True))
        built = ledger.ledger_from_native_trace(
            text, ai_base=AI_BASE, ai_size=AI_SIZE,
            active_players=[6], cycles=[12, 13])
        report = ledger.coverage_report(
            built, active_players=[6], cycles=[12, 13])
        self.assertTrue(report["complete"])
        self.assertEqual(2, report["rows"])
        self.assertEqual([12, 13], report["cycle_span"])

    def test_per_cycle_native_trace_missing_cycle_fails_closed(self):
        text = tracer_dump(cycle=12, player=6, profile=29,
                           raw=raw_state(process_pc=True))
        with self.assertRaisesRegex(ValueError, "missing active-player"):
            ledger.ledger_from_native_trace(
                text, ai_base=AI_BASE, ai_size=AI_SIZE,
                active_players=[6], cycles=[12, 13])

    def test_native_state_writes_are_derived_from_committed_cycle_boundaries(self):
        before = raw_state(wait=0, process_pc=True)
        after = bytearray(before)
        after[0x13] = 9
        text = tracer_dump(cycle=12, player=6, profile=29, raw=before,
                           phase="game-before") + tracer_dump(
            cycle=12, player=6, profile=29, raw=bytes(after))
        built = ledger.ledger_from_native_trace(
            text, ai_base=AI_BASE, ai_size=AI_SIZE)
        item = built["rows"][0]
        self.assertEqual("independent-choice", item["classification"])
        self.assertEqual([{"offset": 0x13, "before": 5, "after": 9}],
                         item["writes"])

    def test_a_same_cycle_game_before_does_not_hide_the_previous_wait_write(self):
        # Native AI often thinks after game-after. Cycle N+1's game-before
        # already holds the new wait, so a same-cycle diff is empty. The
        # write is the previous committed after-state versus this after-state.
        warmup = raw_state(wait=1, process_pc=True)
        after = raw_state(wait=0, process_pc=True)
        text = (
            tracer_dump(cycle=2, player=1, profile=0, raw=warmup,
                        phase="warmup-after")
            + tracer_dump(cycle=1, player=1, profile=0, raw=after,
                          phase="game-before")
            + tracer_dump(cycle=1, player=1, profile=0, raw=after)
        )
        built = ledger.ledger_from_native_trace(
            text, ai_base=AI_BASE, ai_size=AI_SIZE)
        item = built["rows"][0]
        self.assertEqual("fallout", item["classification"],
                         "decrementing a leftover wait is not an independent "
                         "choice")
        self.assertEqual([{"offset": 0, "before": 1, "after": 0}],
                         item["writes"])
        self.assertEqual([], item["predicates"])

    def test_a_failed_wait_until_is_recovered_from_ai_bin_opcode_three(self):
        ai = bytearray(AI_SIZE)
        ai[0x120] = 3
        ai[0x121] = 3
        incoming = raw_state(wait=0, process_pc=True)
        after = raw_state(wait=1, process_pc=True)
        text = (
            tracer_dump(cycle=1, player=1, profile=0, raw=incoming)
            + tracer_dump(cycle=2, player=1, profile=0, raw=after,
                          phase="game-before")
            + tracer_dump(cycle=2, player=1, profile=0, raw=after)
        )
        built = ledger.ledger_from_native_trace(
            text, ai_base=AI_BASE, ai_size=AI_SIZE, ai_bin=bytes(ai))
        item = built["rows"][1]
        self.assertEqual("independent-choice", item["classification"])
        self.assertEqual([{"offset": 0, "before": 0, "after": 1}],
                         item["writes"])
        self.assertEqual([{"id": 3, "result": False}], item["predicates"],
                         "opcode 3 at the incoming PC is WAIT-UNTIL; an "
                         "unchanged PC is a failed predicate")

    def test_native_trace_pc_mutation_fails_at_that_cycle_and_field(self):
        text = tracer_dump(cycle=12, player=6, profile=29,
                           raw=raw_state(process_pc=True))
        baseline = ledger.ledger_from_native_trace(
            text, ai_base=AI_BASE, ai_size=AI_SIZE)
        mutated = ledger.mutate_pc(baseline, 12, 6, 8)
        difference = ledger.first_difference(baseline, mutated)
        self.assertEqual(12, difference["cycle"])
        self.assertEqual("pc_offset", difference["field"])
        self.assertEqual(0x120, difference["left"])
        self.assertEqual(0x128, difference["right"])

    def test_native_trace_predicate_mutation_fails_at_that_cycle_and_field(self):
        text = tracer_dump(cycle=12, player=6, profile=29,
                           raw=raw_state(process_pc=True))
        baseline = ledger.ledger_from_native_trace(
            text, ai_base=AI_BASE, ai_size=AI_SIZE)
        baseline["rows"][0]["predicates"] = [{"id": 4, "result": True}]
        mutated = ledger.mutate_predicate_result(baseline, 12, 6)
        difference = ledger.first_difference(baseline, mutated)
        self.assertEqual(12, difference["cycle"])
        self.assertEqual("predicates", difference["field"])

    def test_native_trace_state_byte_mutation_fails_at_that_cycle_and_field(self):
        text = tracer_dump(cycle=12, player=6, profile=29,
                           raw=raw_state(process_pc=True))
        baseline = ledger.ledger_from_native_trace(
            text, ai_base=AI_BASE, ai_size=AI_SIZE)
        mutated = ledger.mutate_state_byte(baseline, 12, 6, nibble=0)
        difference = ledger.first_difference(baseline, mutated)
        self.assertEqual(12, difference["cycle"])
        self.assertEqual("non_pointer_hex", difference["field"])
        self.assertNotEqual(difference["left"], difference["right"])

    def test_native_trace_without_boundary_dumps_fails_closed(self):
        with self.assertRaisesRegex(ValueError, "no ai-build-boundary"):
            ledger.ledger_from_native_trace(
                "# bne-trace event=cycle cycle=1\n",
                ai_base=AI_BASE, ai_size=AI_SIZE)


def tracer_dump(*, cycle: int, player: int, profile: int, raw: bytes,
                phase: str = "game-after") -> str:
    hex_state = ",".join(f"{byte:02x}" for byte in raw)
    return (
        f"# bne-trace event=ai-build-boundary phase={phase} "
        f"index={cycle} player={player} profile={profile} "
        f"length=12 state={hex_state}\n"
    )


if __name__ == "__main__":
    unittest.main()
