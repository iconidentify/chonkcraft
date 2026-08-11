#!/usr/bin/env python3

from pathlib import Path
import sys
import unittest


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

import bne_semantic_slice


class SemanticSliceTest(unittest.TestCase):
    def plan(self):
        return {
            "focus": {"native_slot": 1553},
        }

    def capture(self, *, cycle=23, lhs=2, rhs=1, taken=True,
                focus=False):
        probe = {"compare": 0x00437644}
        if focus:
            probe["focus_identity"] = {
                "required": True, "proved": True, "register": "esi",
                "observed": 0x10039998, "expected": 0x10039998,
                "matched": True,
            }
        return {
            "case": "retail-xhuman-12-idle", "cycle": cycle,
            "events": [{
                "type": "branch", "address": 0x00437646,
                "condition": "g", "taken": taken,
                "operands": {
                    "lhs": {"name": "ecx", "value": lhs},
                    "rhs": {"name": "eax", "value": rhs},
                },
                "predicate_probe": probe,
            }],
        }

    def history(self):
        instructions = [
            (0x004374A0, "mov    0x8(%esp),%eax"),
            (0x004374A4, "sub    $0x8,%esp"),
            (0x004374A7, "mov    %eax,%ecx"),
            (0x004374A9, "and    $0xff,%ecx"),
            (0x004374AF, "push   %ebx"),
            (0x004374B0, "push   %esi"),
            (0x004374B1, "mov    0x4962b0(%ecx),%bl"),
            (0x004374C7, "mov    0x14(%esp),%esi"),
            (0x00437538, "push   %edi"),
            (0x0043754E, "movzbw %bl,%di"),
            (0x00437605, "mov    0x84(%esi),%eax"),
            (0x0043760B, "mov    %ax,%cx"),
            (0x0043760E, "mov    %eax,0xc(%esp)"),
            (0x00437612, "sub    0x18(%esi),%cx"),
            (0x00437616, "mov    %cx,0xc(%esp)"),
            (0x0043761B, "jns    0x437623"),
            (0x0043761D, "mov    0xc(%esp),%ecx"),
            (0x00437621, "neg    %ecx"),
            (0x00437639, "mov    %edi,%eax"),
            (0x0043763B, "pop    %edi"),
            (0x0043763C, "movswl %cx,%ecx"),
            (0x0043763F, "and    $0xffff,%eax"),
            (0x00437644, "cmp    %eax,%ecx"),
            (0x00437646, "jg     0x437663"),
        ]
        return "\n".join(
            f"{index:6d}   0x{address:08x}:\t{assembly}"
            for index, (address, assembly) in enumerate(instructions, 1)
        ) + "\n"

    def history_with_direct_caller(self):
        caller = [
            (0x0043779F, "mov    0x4ab894,%esi"),
            (0x004377A5, "mov    0x2e(%esi),%dl"),
            (0x004377A8, "push   %edx"),
            (0x004377A9, "push   %esi"),
            (0x004377AA, "call   0x4374a0"),
        ]
        instructions = [*caller]
        for item in bne_semantic_slice.parse_instruction_history(self.history()):
            instructions.append((
                int(item["address"]),
                bne_semantic_slice._assembly(item["instruction"]),
            ))
        return "\n".join(
            f"{index:6d}   0x{address:08x}:\t{assembly}"
            for index, (address, assembly) in enumerate(instructions, 1)
        ) + "\n"

    def test_unscoped_capture_keeps_unit_identity_explicit(self):
        analysis = bne_semantic_slice.analyze_history(
            self.plan(), self.capture(), self.history_with_direct_caller(),
        )
        self.assertNotIn("unit[1553]", analysis["predicate"]["semantic"])
        self.assertIn("bne.global_004ab894", analysis["predicate"]["semantic"])
        self.assertFalse(analysis["focus_identity"]["proved"])
        self.assertTrue(analysis["self_check"])

    def test_held_out_control_must_recover_same_formula_and_outcome(self):
        result = bne_semantic_slice.analyze_semantic_slice(
            self.plan(), self.capture(focus=True), self.history(), [(
                self.capture(cycle=22, lhs=1, rhs=1, taken=False, focus=True),
                self.history(),
            )],
        )
        self.assertTrue(result["proof"]["passed"])
        self.assertTrue(result["held_out"][0]["same_formula"])
        self.assertTrue(result["held_out"][0]["prediction_correct"])
        self.assertEqual(
            {"lhs": 1, "rhs": 1, "prediction": False},
            result["boundary_experiment"]["flip_to_not_taken"],
        )

    def test_recovers_order_argument_from_the_executed_direct_caller(self):
        analysis = bne_semantic_slice.analyze_history(
            self.plan(), self.capture(focus=True),
            self.history_with_direct_caller(),
        )
        self.assertEqual(
            "abs((unit[1553].order_x - unit[1553].x)) > "
            "u8(bne.table_004962b0[u8(unit[1553].order)])",
            analysis["predicate"]["semantic"],
        )
        self.assertNotIn(
            "function_arg2", analysis["predicate"]["rhs"]["sources"],
        )

    def test_formula_without_focus_identity_is_not_a_semantic_proof(self):
        result = bne_semantic_slice.analyze_semantic_slice(
            self.plan(), self.capture(), self.history_with_direct_caller(), [(
                self.capture(cycle=22, lhs=1, rhs=1, taken=False),
                self.history_with_direct_caller(),
            )],
        )
        self.assertTrue(result["proof"]["predicate_recovery_passed"])
        self.assertFalse(result["proof"]["focus_identity_proven"])
        self.assertFalse(result["proof"]["passed"])
        self.assertEqual(2, result["schema"])


if __name__ == "__main__":
    unittest.main()
