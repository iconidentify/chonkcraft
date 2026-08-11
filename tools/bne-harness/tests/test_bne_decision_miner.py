#!/usr/bin/env python3

import json
from pathlib import Path
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

import bne_branch_witness
import bne_decision_miner
import bne_triage


class DecisionMinerTest(unittest.TestCase):
    def probe(self):
        return {
            "schema": 1, "branch": 0x0044FB14,
            "flag_instruction": 0x0044FB10, "flag_operation": "compare",
            "condition": "e", "width": 8,
            "lhs": {
                "kind": "memory", "name": "unit[*].order", "width": 8,
                "gdb": "*(unsigned char*)($esi + 46)",
                "address_gdb": "$esi + 46",
                "semantic_ast": {"op": "symbol", "value": "unit[*].order"},
            },
            "rhs": {
                "kind": "immediate", "name": "12", "value": 12,
                "width": 8, "gdb": "12", "address_gdb": "0",
                "semantic_ast": {"op": "const", "value": 12},
            },
            "focus_register": "esi", "skipped_instructions": 0,
            "scope": "one focus-scoped decision-function activation",
        }

    def plan(self, *, probe=False, heldout=True):
        captures = {
            "rejected": {"cycle": 29, "expected_outcome": "rejected",
                         "before": 2, "after": 2, "changed": False},
            "accepted": {"cycle": 34, "expected_outcome": "accepted",
                         "before": 2, "after": 12, "changed": True},
        }
        if heldout:
            captures["heldout"] = {
                "cycle": 39, "expected_outcome": "accepted",
                "before": 2, "after": 12, "changed": True,
            }
        plan = {
            "schema": 1, "decision_id": "decision-a", "case": "case-a",
            "fixture_id": "fixture-a",
            "scenario": r"Campaign\Human\Human13.pud", "seed": 1,
            "focus": {"native_slot": 17, "java_id": 3},
            "decision": {"entry_address": 0x0044FB00,
                         "focus_register": "esi", "field": "order",
                         "field_offset": 46, "field_bytes": 1},
            "captures": captures,
            "capture_window": {"maximum_instructions": 65536,
                               "maximum_ranked_branches": 24},
            "policy": {"automatic_source_changes": False},
        }
        if probe:
            plan["predicate_probe"] = self.probe()
        return plan

    def capture(self, phase, *, operands=True):
        accepted = phase in {"accepted", "heldout"}
        value = 12 if accepted else 2
        event = {
            "seq": 1, "type": "branch",
            "cycle": self.plan()["captures"][phase]["cycle"],
            "address": 0x0044FB14, "target": 0x0044FB20,
            "taken": accepted, "condition": "e",
            "predicate_probe_plan": self.probe(),
        }
        if operands:
            event["operands"] = {
                "lhs": {**self.probe()["lhs"], "value": value,
                        "runtime_address": 0x10000A40},
                "rhs": {**self.probe()["rhs"], "value": 12,
                        "runtime_address": 0},
            }
            event["predicate_probe"] = {
                "flag_instruction": 0x0044FB10, "hit": 1,
                "focus_identity": {"required": True, "proved": True,
                                   "register": "esi"},
            }
        return {
            "schema": 1, "backend": "gdb-bts-decision-visit",
            "case": "case-a", "decision_id": "decision-a", "phase": phase,
            "cycle": self.plan()["captures"][phase]["cycle"], "field": "order",
            "instruction_count": 5,
            "decision": {"entry_address": 0x0044FB00,
                         "return_address": 0x00452199,
                         "focus_register": "esi", "native_slot": 17,
                         "focus_address": 0x10000A12, "before": 2,
                         "after": 12 if accepted else 2,
                         "changed": accepted,
                         "expected_outcome": "accepted" if accepted else "rejected"},
            "events": [event],
        }

    def write_capture(self, root, plan, capture):
        path = root / f"{capture['phase']}.decision-capture.json"
        path.write_text(json.dumps(capture, sort_keys=True) + "\n")
        manifest = {
            "schema": 1,
            "capture": {"name": path.name, **bne_triage.file_identity(path)},
            "oracle": {"executable": {
                "sha256": bne_branch_witness.BNE_202_SHA256,
            }},
            "runtime": {"network_disabled": True},
            "harness": {"capture_importer": {
                "bytes": 1, "sha256": "b" * 64,
            }},
            "backend": {"name": "test", "branch_history": True,
                        "decision_visit": True,
                        "raw_history": {"bytes": 1, "sha256": "a" * 64}},
            "request": {
                "case": plan["case"], "decision_id": plan["decision_id"],
                "plan_sha256": bne_triage.canonical_digest(plan),
                "phase": capture["phase"], "cycle": capture["cycle"],
                "native_slot": plan["focus"]["native_slot"],
                "field": plan["decision"]["field"],
                "scenario": plan["scenario"], "seed": plan["seed"],
            },
        }
        path.with_name(path.stem + ".manifest.json").write_text(
            json.dumps(manifest, sort_keys=True) + "\n")
        return path

    def test_first_pass_ranks_flip_and_emits_automatic_operand_plan(self):
        captures = {
            phase: self.capture(phase, operands=False)
            for phase in ("rejected", "accepted", "heldout")
        }
        result = bne_decision_miner.analyze_decisions(self.plan(), captures)
        self.assertEqual(0x0044FB14, result["top_branch"]["address"])
        self.assertTrue(result["proof"]["operand_probe_ready"])
        self.assertEqual(self.probe(),
                         result["predicate_probe_plan"]["predicate_probe"])
        self.assertFalse(result["proof"]["semantic_handoff_ready"])

    def test_operand_pass_proves_heldout_rule_and_semantic_handoff(self):
        captures = {
            phase: self.capture(phase)
            for phase in ("rejected", "accepted", "heldout")
        }
        result = bne_decision_miner.analyze_decisions(
            self.plan(probe=True), captures,
        )
        self.assertEqual("unit[*].order == 12",
                         result["predicate"]["semantic"])
        self.assertTrue(result["proof"]["heldout_prediction_passed"])
        self.assertTrue(result["proof"]["semantic_handoff_ready"])
        semantic = result["semantic_bridge_handoff"]
        self.assertEqual(2, semantic["schema"])
        self.assertTrue(semantic["proof"]["focus_identity_proven"])
        self.assertTrue(semantic["proof"]["passed"])

    def test_selected_contrast_cycles_cannot_claim_execution_cadence(self):
        captures = {
            phase: self.capture(phase)
            for phase in ("rejected", "accepted", "heldout")
        }
        result = bne_decision_miner.analyze_decisions(
            self.plan(probe=True), captures,
        )

        temporal = result["temporal_scope"]
        self.assertEqual([29, 34, 39], temporal["cycles"])
        self.assertEqual([5, 5], temporal["gaps"])
        self.assertFalse(temporal["cadence_claim_supported"])
        self.assertIn("selected outcome samples", temporal["reason"])

    def test_heldout_branch_must_classify_the_planned_outcome(self):
        captures = {
            phase: self.capture(phase)
            for phase in ("rejected", "accepted", "heldout")
        }
        heldout = captures["heldout"]["decision"]
        heldout.update({"expected_outcome": "rejected", "after": 2,
                        "changed": False})
        result = bne_decision_miner.analyze_decisions(
            self.plan(probe=True), captures,
        )
        self.assertFalse(
            result["predicate"]["proof"]["outcome_classification_correct"],
        )
        self.assertFalse(result["proof"]["semantic_handoff_ready"])

    def test_writer_instruction_derives_the_unit_pointer_register(self):
        writer = {"instruction_text": "mov %cl,0x2e(%esi)"}
        self.assertEqual(
            "esi", bne_decision_miner.focus_register_from_writer(writer, "order"),
        )

    def test_next_order_rule_is_labeled_as_a_promotion_boundary(self):
        predicate = {
            "lhs_ast": {"op": "symbol", "value": "unit[*].next_order"},
            "rhs_ast": {"op": "const", "value": 60},
        }
        self.assertEqual(
            "order-promotion-boundary",
            bne_decision_miner.predicate_role(self.plan(), predicate),
        )

    def test_prologue_derives_focus_from_the_entry_stack_pointer(self):
        instructions = [
            {"addr": 0x00452EF0, "opcode": "push esi"},
            {"addr": 0x00452EF1, "opcode": "mov esi, dword [esp + 8]"},
            {"addr": 0x00452EF5, "opcode": "test byte [esi + 0x1e], 7"},
        ]
        focus = bne_decision_miner.entry_focus_from_instructions(
            instructions, "esi",
        )
        self.assertEqual("entry-stack-pointer", focus["kind"])
        self.assertEqual(4, focus["offset"])
        self.assertEqual(0x00452EF1, focus["proved_by"]["address"])

    def test_bootstrap_history_selects_the_call_that_reaches_the_writer(self):
        with tempfile.TemporaryDirectory() as temporary:
            history = Path(temporary) / "capture.gdb-history.txt"
            history.write_text("""
1  0x0045248c: call 0x452ef0
2  0x00452ef0: push %esi
3  0x00452f54: ret
4  0x00452587: call 0x452ef0
5  0x00452ef0: push %esi
6  0x00452fa2: mov %dl,0x2e(%esi)
""")
            callsite = bne_decision_miner.accepted_callsite_from_history(
                history, writer_address=0x00452FA2,
                entry_address=0x00452EF0,
            )
        self.assertEqual(0x00452587, callsite)

    def upstream_history(self, root):
        """The order dispatch reaches its shared handler indirectly."""
        history = root / "upstream.gdb-history.txt"
        history.write_text("""
1   0x00452573: call *0x495ed8(,%edx,4)
2   0x0040b010: sub $0x14,%esp
3   0x0045324d: mov %cl,0x2f(%esi)
4   0x0040b379: ret
5   0x0045257a: add $0x4,%esp
6   0x00452587: call 0x452ef0
7   0x00452ef0: push %esi
8   0x00452fa2: mov %dl,0x2e(%esi)
9   0x0045258c: add $0x4,%esp
""")
        return history

    def test_indirect_order_dispatch_proves_the_shared_handler_caller(self):
        with tempfile.TemporaryDirectory() as temporary:
            activation = bne_decision_miner.accepted_activation_from_history(
                self.upstream_history(Path(temporary)),
                writer_address=0x00452FA2, entry_address=0x0040B010,
            )
        self.assertEqual(0x00452573, activation["callsite"])
        self.assertEqual("indirect", activation["call_kind"])
        self.assertEqual("recorded-successor-instruction",
                         activation["proved_by"])

    def test_an_upstream_activation_returns_before_the_accepted_write(self):
        with tempfile.TemporaryDirectory() as temporary:
            history = self.upstream_history(Path(temporary))
            upstream = bne_decision_miner.accepted_activation_from_history(
                history, writer_address=0x00452FA2, entry_address=0x0040B010,
            )
            self.assertFalse(bne_decision_miner.activation_contains_writer(
                history, upstream, entry_address=0x0040B010,
                entry_return_address=0x0045257A,
            ))
            containing = bne_decision_miner.accepted_activation_from_history(
                history, writer_address=0x00452FA2, entry_address=0x00452EF0,
            )
            self.assertTrue(bne_decision_miner.activation_contains_writer(
                history, containing, entry_address=0x00452EF0,
                entry_return_address=0x0045258C,
            ))

    def test_a_reentered_handler_does_not_close_the_outer_activation(self):
        with tempfile.TemporaryDirectory() as temporary:
            history = Path(temporary) / "nested.gdb-history.txt"
            history.write_text("""
1   0x00452573: call *0x495ed8(,%edx,4)
2   0x0040b010: sub $0x14,%esp
3   0x0040b010: sub $0x14,%esp
4   0x0045257a: add $0x4,%esp
5   0x00452fa2: mov %dl,0x2e(%esi)
6   0x0045257a: add $0x4,%esp
""")
            self.assertTrue(bne_decision_miner.activation_contains_writer(
                history, {"entry_index": 1, "writer_index": 4},
                entry_address=0x0040B010,
                entry_return_address=0x0045257A,
            ))

    def test_a_direct_call_immediate_must_match_the_taken_entry(self):
        with tempfile.TemporaryDirectory() as temporary:
            history = Path(temporary) / "wrong.gdb-history.txt"
            history.write_text("""
1  0x00452587: call 0x452ef0
2  0x0040b010: sub $0x14,%esp
3  0x00452fa2: mov %dl,0x2e(%esi)
""")
            with self.assertRaises(ValueError):
                bne_decision_miner.accepted_activation_from_history(
                    history, writer_address=0x00452FA2,
                    entry_address=0x0040B010,
                )

    def test_an_upstream_scope_is_labelled_and_sourced_from_the_fixture(self):
        contrast = {
            "native_slot": 17, "field": "order",
            "fixture": {"path": "case-a.bnefx", "fixture_id": "fixture-a"},
            "observations": self.plan()["captures"],
        }
        base = {"case": "case-a", "fixture_id": "fixture-a", "schema": 1}
        upstream = bne_decision_miner.build_decision_plan(
            base, contrast, entry_address=0x0040B010, focus_register="esi",
            entry_focus={"kind": "entry-stack-pointer", "offset": 4},
            entry_callsite=0x00452573, entry_return_address=0x0045257A,
            activation_scope=bne_decision_miner.ACTIVATION_UPSTREAM,
        )
        containing = bne_decision_miner.build_decision_plan(
            base, contrast, entry_address=0x00452EF0, focus_register="esi",
            entry_focus={"kind": "entry-stack-pointer", "offset": 4},
            entry_callsite=0x00452587, entry_return_address=0x0045258C,
        )
        self.assertEqual("fixture-cycle-outcome",
                         upstream["decision"]["outcome_source"])
        self.assertEqual("activation-field-delta",
                         containing["decision"]["outcome_source"])
        self.assertNotEqual(upstream["decision_id"], containing["decision_id"])

    def test_an_upstream_visit_is_judged_by_its_sealed_fixture_cycle(self):
        plan = self.plan(probe=True)
        plan["decision"]["activation_scope"] = \
            bne_decision_miner.ACTIVATION_UPSTREAM
        plan["decision"]["outcome_source"] = "fixture-cycle-outcome"
        captures = {}
        for phase in ("rejected", "accepted", "heldout"):
            capture = self.capture(phase)
            # An upstream handler returns before the promotion writes, so
            # every phase leaves the watched field exactly as it entered.
            capture["decision"]["after"] = capture["decision"]["before"]
            capture["decision"]["changed"] = False
            capture["decision"]["outcome_source"] = "fixture-cycle-outcome"
            captures[phase] = capture
        result = bne_decision_miner.analyze_decisions(plan, captures)
        self.assertTrue(result["proof"]["predicate_recovered"])
        self.assertTrue(result["predicate"]["proof"]["passed"])

    def test_an_upstream_plan_refuses_a_writer_scoped_capture(self):
        plan = self.plan(probe=True)
        plan["decision"]["outcome_source"] = "fixture-cycle-outcome"
        captures = {phase: self.capture(phase)
                    for phase in ("rejected", "accepted")}
        with self.assertRaises(ValueError):
            bne_decision_miner.analyze_decisions(plan, captures)

    def test_durable_miner_is_content_addressed_and_authenticated(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            plan = self.plan(probe=True)
            plan_path = root / "plan.json"
            plan_path.write_text(json.dumps(plan) + "\n")
            paths = [self.write_capture(root, plan, self.capture(phase))
                     for phase in ("rejected", "accepted", "heldout")]
            artifacts = root / "artifacts"
            first = bne_decision_miner.run_miner(plan_path, paths, artifacts)
            second = bne_decision_miner.run_miner(plan_path, paths, artifacts)
            self.assertEqual(first, second)
            self.assertEqual(0, first[0])
            self.assertTrue((first[1] / "semantic-slice.json").is_file())


if __name__ == "__main__":
    unittest.main()
