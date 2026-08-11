#!/usr/bin/env python3

from pathlib import Path
import sys
import unittest


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

import bne_branch_capture
import bne_branch_witness


class BranchCaptureTest(unittest.TestCase):
    def plan(self):
        packet = {
            "case": {"id": "case-a"},
            "divergence": {"cycle": 24, "findings": [{
                "kind": "unit", "unit": 17, "field": "y",
                "oracle": 8, "java": 9,
            }]},
            "semantic": {"24": {"focus": [{
                "native_slot": 17, "java_id": 3,
                "oracle": {"y": 8}, "java": {"y": 9},
            }]}},
        }
        return bne_branch_witness.plan_from_packet(packet)

    def history(self):
        return """
BNEWITNESS watch=0x10000a40 before=9 after=8
     1  0x0044fc00 <move+48>: cmp    $0xe,%eax
     2  0x0044fc04 <move+52>: jge    0x0044fc20 <move+80>
     3  0x0044fc20 <move+80>: add    $0x1,%edx
     4  0x0044fc23 <move+83>: jne    0x0044fc30 <move+96>
     5  0x0044fc25 <move+85>: mov    %dx,0x1a(%ecx)
"""

    def test_generates_bounded_watchpoint_and_bts_script(self):
        text = bne_branch_capture.gdb_commands(
            self.plan(), field="y", history_log=Path("/tmp/history.log"),
            resume_marker=Path("/tmp/resume"),
        )
        self.assertIn("$bne_pool + 17 * 152 + 26", text)
        self.assertIn("watch -l *(unsigned short*)$bne_watch", text)
        self.assertIn("record btrace bts", text)
        self.assertLess(text.index("record btrace bts"),
                        text.index("shell touch /tmp/resume"))

    def test_reconstructs_taken_and_not_taken_branches_and_writer(self):
        capture = bne_branch_capture.capture_from_gdb_log(
            self.plan(), "y", self.history(),
        )
        branches = [event for event in capture["events"]
                    if event["type"] == "branch"]
        self.assertEqual([True, False],
                         [event["taken"] for event in branches])
        writer = capture["events"][-1]
        self.assertEqual("write", writer["type"])
        self.assertEqual(0x0044FC25, writer["instruction"])
        self.assertEqual((9, 8), (writer["before"], writer["after"]))

    def test_parser_rejects_incomplete_watchpoint_log(self):
        with self.assertRaisesRegex(ValueError, "watchpoint marker"):
            bne_branch_capture.capture_from_gdb_log(
                self.plan(), "y", self.history().split("BNEWITNESS", 1)[0]
                + "\n 1 0x1 nop\n 2 0x2 nop\n",
            )

    def test_predicate_probe_records_last_dynamic_operands(self):
        probe = bne_branch_capture.predicate_probe(
            0x0044FC04, 0x0044FC00, "eax", "ecx", "ge",
        )
        script = bne_branch_capture.gdb_commands(
            self.plan(), field="y", history_log=Path("/tmp/history.log"),
            resume_marker=Path("/tmp/resume"), predicate=probe,
        )
        self.assertIn("break *0x0044fc00", script)
        self.assertIn("BNEPREDICATE branch=0x0044fc04", script)
        history = self.history() + (
            "\nBNEPREDICATE branch=0x0044fc04 compare=0x0044fc00 "
            "hit=1 lhs=15 rhs=14\n"
        )
        capture = bne_branch_capture.capture_from_gdb_log(
            self.plan(), "y", history, predicate=probe,
        )
        branch = next(event for event in capture["events"]
                      if event.get("address") == 0x0044FC04)
        self.assertEqual(15, branch["operands"]["lhs"]["value"])
        self.assertEqual(14, branch["operands"]["rhs"]["value"])
        self.assertEqual(1, branch["predicate_probe"]["hit"])
        self.assertFalse(
            branch["predicate_probe"]["focus_identity"]["proved"]
        )

    def test_focus_scoped_probe_authenticates_the_watched_unit(self):
        probe = bne_branch_capture.predicate_probe(
            0x0044FC04, 0x0044FC00, "eax", "ecx", "ge", "esi",
        )
        script = bne_branch_capture.gdb_commands(
            self.plan(), field="y", history_log=Path("/tmp/history.log"),
            resume_marker=Path("/tmp/resume"), predicate=probe,
        )
        self.assertIn("if (unsigned int)$esi == (unsigned int)$bne_focus", script)
        self.assertIn("focus_match=1", script)
        history = self.history() + (
            "\nBNEPREDICATE branch=0x0044fc04 compare=0x0044fc00 "
            "hit=1 lhs=15 rhs=14 focus=0x10039998 "
            "expected=0x10039998 focus_match=1\n"
        )
        capture = bne_branch_capture.capture_from_gdb_log(
            self.plan(), "y", history, predicate=probe,
        )
        branch = next(event for event in capture["events"]
                      if event.get("address") == 0x0044FC04)
        identity = branch["predicate_probe"]["focus_identity"]
        self.assertTrue(identity["proved"])
        self.assertEqual("esi", identity["register"])

    def test_predicate_hit_is_attached_to_its_exact_dynamic_occurrence(self):
        probe = bne_branch_capture.predicate_probe(
            0x0044FC04, 0x0044FC00, "eax", "ecx", "ge",
        )
        history = """
BNEWITNESS watch=0x10000a40 before=9 after=8
BNEPREDICATE branch=0x0044fc04 compare=0x0044fc00 hit=1 lhs=15 rhs=14
     1  0x0044fc00: cmp    %ecx,%eax
     2  0x0044fc04: jge    0x0044fc20
     3  0x0044fc06: add    $0x1,%edx
     4  0x0044fc00: cmp    %ecx,%eax
     5  0x0044fc04: jge    0x0044fc20
     6  0x0044fc20: add    $0x1,%edx
     7  0x0044fc25: mov    %dx,0x1a(%ecx)
"""
        capture = bne_branch_capture.capture_from_gdb_log(
            self.plan(), "y", history, predicate=probe,
        )
        occurrences = [event for event in capture["events"]
                       if event.get("address") == 0x0044FC04]
        self.assertIn("operands", occurrences[0])
        self.assertNotIn("operands", occurrences[1])

    def test_register_compare_generates_second_pass_probe_automatically(self):
        history = """
BNEWITNESS watch=0x10000a40 before=9 after=8
     1  0x00437644: cmp    %eax,%ecx
     2  0x00437646: jg     0x00437663
     3  0x00437663: add    $0x1,%edx
     4  0x00437666: mov    %dx,0x1a(%esi)
"""
        capture = bne_branch_capture.capture_from_gdb_log(
            self.plan(), "y", history,
        )
        branch = capture["events"][0]
        probe = branch["predicate_probe_plan"]
        self.assertEqual(0x00437644, probe["compare"])
        self.assertEqual("ecx", probe["lhs"]["name"])
        self.assertEqual("eax", probe["rhs"]["name"])
        self.assertEqual("signed-int32", probe["encoding"])

    def test_derives_earlier_cycle_control_without_mutating_anchor(self):
        anchor = self.plan()
        control = bne_branch_capture.control_plan(
            anchor, cycle=23, field="y", before=10, after=9,
        )
        self.assertEqual(24, anchor["divergence_cycle"])
        self.assertEqual(23, control["divergence_cycle"])
        self.assertEqual("clean-earlier-cycle-control", control["role"])
        watch = next(item for item in control["native_layout"]["watches"]
                     if item["field"] == "y")
        self.assertEqual((10, 9), (watch["before"], watch["oracle"]))


if __name__ == "__main__":
    unittest.main()


class RegisterProbeTest(unittest.TestCase):
    """Printing the machine at a loop nobody has read yet."""

    def plan(self):
        packet = {
            "case": {"id": "case-a"},
            "divergence": {"cycle": 24, "findings": [{
                "kind": "unit", "unit": 17, "field": "y",
                "oracle": 8, "java": 9,
            }]},
            "semantic": {"24": {"focus": [{
                "native_slot": 17, "java_id": 3,
                "oracle": {"y": 8}, "java": {"y": 9},
            }]}},
        }
        return bne_branch_witness.plan_from_packet(packet)

    def test_a_probe_outside_the_pinned_text_is_refused(self):
        with self.assertRaises(ValueError) as raised:
            bne_branch_capture.register_probe([0x00100000])
        self.assertIn("outside the pinned BNE text", str(raised.exception),
                      "a breakpoint was accepted at an address the game's "
                      "code never occupies")

    def test_the_same_address_twice_is_refused(self):
        with self.assertRaises(ValueError) as raised:
            bne_branch_capture.register_probe([0x0045037c, 0x0045037c])
        self.assertIn("repeated", str(raised.exception),
                      "one address was broken on twice, so every pass would "
                      "be reported twice and a loop counted double")

    def test_the_script_prints_every_register_at_each_pass(self):
        text = bne_branch_capture.gdb_commands(
            self.plan(), field="y", history_log=Path("/tmp/history.log"),
            resume_marker=Path("/tmp/resume"),
            registers_at=[0x0045037c],
        )
        self.assertIn("break *0x0045037c", text,
                      "the loop the probe names is never stopped at")
        for name in ("eax", "ecx", "edx", "esi", "edi"):
            self.assertIn(f"{name}=0x%08x", text,
                          f"{name} is not printed, and which register walks "
                          f"the map is the thing being looked for")
        self.assertLess(text.index("break *0x0045037c"),
                        text.index("record btrace bts"),
                        "the probe was armed after recording began, so the "
                        "early passes of the loop are lost")
