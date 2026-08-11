#!/usr/bin/env python3

from pathlib import Path
import sys
import unittest


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

import bne_decision_capture


class DecisionCaptureTest(unittest.TestCase):
    def plan(self, *, probe=False):
        plan = {
            "schema": 1, "decision_id": "decision-a", "case": "case-a",
            "scenario": r"Campaign\Human\Human13.pud", "seed": 1,
            "focus": {"native_slot": 17, "java_id": 3},
            "decision": {
                "entry_address": 0x0044FB00, "focus_register": "esi",
                "entry_focus": {"kind": "entry-stack-pointer", "offset": 4},
                "entry_callsite": 0x00452194,
                "entry_return_address": 0x00452199,
                "field": "order", "field_offset": 46, "field_bytes": 1,
            },
            "captures": {
                "rejected": {"cycle": 29, "expected_outcome": "rejected",
                             "before": 2, "after": 2, "changed": False},
                "accepted": {"cycle": 34, "expected_outcome": "accepted",
                             "before": 2, "after": 12, "changed": True},
            },
            "capture_window": {"maximum_instructions": 65536},
            "policy": {},
        }
        if probe:
            instructions = [
                {"index": 1, "address": 0x0044FB10,
                 "instruction": "cmpb $0xc,0x2e(%esi)"},
                {"index": 2, "address": 0x0044FB14,
                 "instruction": "je 0x0044fb20"},
            ]
            plan["predicate_probe"] = bne_decision_capture.predicate_probe(
                instructions, 1, 0x0044FB14, "e", "esi",
            )
        return plan

    def history(self, *, accepted=False, predicate=False):
        after = 12 if accepted else 2
        next_address = 0x0044FB20 if accepted else 0x0044FB16
        marker = ""
        if predicate:
            value = 12 if accepted else 2
            marker = (
                "BNEDECISIONPRED branch=0x0044fb14 flag=0x0044fb10 "
                f"hit=1 lhs={value} rhs=12 lhs_addr=0x10000a40 "
                "rhs_addr=0x0 focus=0x10000a12 expected=0x10000a12\n"
            )
        return f"""
BNEDECISION entry=0x0044fb00 focus=0x10000a12 expected=0x10000a12 return=0x00452199 before=2
{marker}BNEDECISIONEND entry=0x0044fb00 after={after}
  1  0x0044fb00: push %ebp
  2  0x0044fb10: cmpb $0xc,0x2e(%esi)
  3  0x0044fb14: je 0x0044fb20
  4  0x{next_address:08x}: mov %eax,%ebx
  5  0x0044fb30: ret
"""

    def test_memory_compare_becomes_a_named_focus_scoped_probe(self):
        instructions = [
            {"index": 1, "address": 0x0044FB10,
             "instruction": "cmpb $0xc,0x2e(%esi)"},
            {"index": 2, "address": 0x0044FB14,
             "instruction": "je 0x0044fb20"},
        ]
        probe = bne_decision_capture.predicate_probe(
            instructions, 1, 0x0044FB14, "e", "esi",
        )
        self.assertEqual("compare", probe["flag_operation"])
        self.assertEqual(8, probe["width"])
        self.assertEqual("unit[*].order", probe["lhs"]["name"])
        self.assertEqual(12, probe["rhs"]["value"])
        self.assertEqual("symbol", probe["lhs"]["semantic_ast"]["op"])

    def test_mismatched_memory_width_is_not_mislabeled_as_a_unit_field(self):
        operand = bne_decision_capture.parse_operand(
            "0x2e(%esi)", width=32, focus_register="esi",
        )
        self.assertEqual("mem[esi+46]", operand["name"])
        self.assertEqual("unknown", operand["semantic_ast"]["op"])

    def test_indexed_memory_keeps_its_scale_without_guessing_semantics(self):
        operand = bne_decision_capture.parse_operand(
            "0x10(%eax,%ecx,4)", width=8, focus_register="esi",
        )
        self.assertEqual("mem[eax+ecx*4+16]", operand["name"])
        self.assertEqual("unknown", operand["semantic_ast"]["op"])

    def test_bounded_register_origin_recovers_a_focus_unit_field(self):
        instructions = [
            {"index": 1, "address": 0x00452EFF,
             "instruction": "mov 0x2f(%esi),%al"},
            {"index": 2, "address": 0x00452F02,
             "instruction": "cmp $0x3c,%al"},
            {"index": 3, "address": 0x00452F04,
             "instruction": "jne 0x00452f55"},
        ]
        probe = bne_decision_capture.predicate_probe(
            instructions, 2, 0x00452F04, "ne", "esi",
        )
        self.assertEqual("unit[*].next_order", probe["lhs"]["name"])
        self.assertEqual("al", probe["lhs"]["resolved_from"]["register"])
        self.assertEqual(60, probe["rhs"]["value"])

    def test_bounded_backtrack_recovers_test_flags_across_a_mov(self):
        instructions = [
            {"index": 1, "address": 0x0044FB10,
             "instruction": "test %eax,%eax"},
            {"index": 2, "address": 0x0044FB12,
             "instruction": "mov %ecx,%edx"},
            {"index": 3, "address": 0x0044FB14,
             "instruction": "jne 0x0044fb20"},
        ]
        probe = bne_decision_capture.predicate_probe(
            instructions, 2, 0x0044FB14, "ne", "esi",
        )
        self.assertEqual("test", probe["flag_operation"])
        self.assertEqual(1, probe["skipped_instructions"])

    def test_bounded_backtrack_stops_at_an_intervening_flag_writer(self):
        instructions = [
            {"index": 1, "address": 0x0044FB10,
             "instruction": "cmpb $0xc,0x2e(%esi)"},
            {"index": 2, "address": 0x0044FB12,
             "instruction": "add %ecx,%edx"},
            {"index": 3, "address": 0x0044FB14,
             "instruction": "jne 0x0044fb20"},
        ]
        probe = bne_decision_capture.predicate_probe(
            instructions, 2, 0x0044FB14, "ne", "esi",
        )
        self.assertIsNone(probe)

    def test_gdb_script_records_only_the_matching_function_activation(self):
        text = bne_decision_capture.gdb_commands(
            self.plan(), "rejected", history_log=Path("/tmp/history"),
            resume_marker=Path("/tmp/resume"),
        )
        self.assertIn("BneDecisionEntry('*0x0044fb00')", text)
        self.assertIn("*(unsigned int*)($esp + 4)", text)
        self.assertIn("return_address != 0x00452199", text)
        self.assertIn("focus != expected", text)
        self.assertIn("*(unsigned int*)$esp", text)
        self.assertIn("gdb.Breakpoint('*0x%08x' % return_address", text)
        self.assertIn("BNEDECISIONEND", text)

    def test_parser_proves_field_outcome_and_attaches_memory_operands(self):
        plan = self.plan(probe=True)
        capture = bne_decision_capture.parse_capture(
            plan, "accepted", self.history(accepted=True, predicate=True),
        )
        self.assertTrue(capture["decision"]["changed"])
        branch = capture["events"][0]
        self.assertTrue(branch["taken"])
        self.assertEqual(12, branch["operands"]["lhs"]["value"])
        self.assertEqual(0x10000A40,
                         branch["operands"]["lhs"]["runtime_address"])
        self.assertTrue(branch["predicate_probe"]["focus_identity"]["proved"])

    def test_an_upstream_visit_keeps_the_field_it_entered_on(self):
        plan = self.plan()
        plan["decision"]["outcome_source"] = "fixture-cycle-outcome"
        capture = bne_decision_capture.parse_capture(
            plan, "accepted", self.history(),
        )
        self.assertFalse(capture["decision"]["changed"])
        self.assertEqual("accepted", capture["decision"]["expected_outcome"])
        self.assertEqual("fixture-cycle-outcome",
                         capture["decision"]["outcome_source"])

    def test_an_upstream_visit_still_has_to_enter_on_the_sealed_state(self):
        plan = self.plan()
        plan["decision"]["outcome_source"] = "fixture-cycle-outcome"
        text = self.history().replace("before=2", "before=12", 1)
        with self.assertRaisesRegex(ValueError, "wrong field state"):
            bne_decision_capture.parse_capture(plan, "accepted", text)

    def test_parser_rejects_a_capture_for_the_wrong_unit(self):
        text = self.history().replace("focus=0x10000a12", "focus=0x10000a13", 1)
        with self.assertRaisesRegex(ValueError, "focus identity"):
            bne_decision_capture.parse_capture(self.plan(), "rejected", text)


if __name__ == "__main__":
    unittest.main()
