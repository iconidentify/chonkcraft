#!/usr/bin/env python3

import json
from pathlib import Path
import sys
import tempfile
import unittest


SCRIPTS = Path(__file__).resolve().parents[1] / "scripts"
sys.path.insert(0, str(SCRIPTS))

import bne_semantic_bridge


class SemanticBridgeTest(unittest.TestCase):
    def semantic_slice(self):
        lhs = {
            "op": "abs", "args": [{
                "op": "sub", "args": [
                    {"op": "symbol", "value": "unit[1553].order_x"},
                    {"op": "symbol", "value": "unit[1553].x"},
                ],
            }],
        }
        rhs = {
            "op": "u8", "args": [{
                "op": "table", "args": [4809392, {
                    "op": "u8", "args": [
                        {"op": "symbol", "value": "function_arg2"},
                    ],
                }],
            }],
        }
        return {
            "schema": 2, "case": "retail-xhuman-12-idle",
            "anchor": {
                "cycle": 23,
                "branch": {"address": 0x00437646, "operator": ">"},
                "observed": {"lhs": 2, "rhs": 1, "taken": True},
                "predicate": {
                    "semantic": "abs(order_x-x) > table[arg2]",
                    "lhs": {"ast": lhs}, "rhs": {"ast": rhs},
                },
            },
            "boundary_experiment": {
                "flip_to_not_taken": {"lhs": 1, "rhs": 1,
                                      "prediction": False},
                "flip_to_taken": {"lhs": 2, "rhs": 1,
                                  "prediction": True},
            },
            "proof": {"passed": True, "focus_identity_proven": True},
        }

    def atlas(self):
        return {
            "schema": 1,
            "symbol_families": [
                {"canonical": "unit.x", "native": ["unit[*].x"],
                 "java": ["unit.tileX", "unit.x"]},
                {"canonical": "unit.y", "native": ["unit[*].y"],
                 "java": ["unit.tileY", "unit.y"]},
                {"canonical": "unit.order_x",
                 "native": ["unit[*].order_x"],
                 "java": ["unit.orderTargetX", "unit.order_x"]},
                {"canonical": "unit.order_y",
                 "native": ["unit[*].order_y"],
                 "java": ["unit.orderTargetY", "unit.order_y"]},
            ],
        }

    def trace_lines(self):
        expression = (
            "max(abs(sub(unit.orderTargetX,unit.tileX)),"
            "abs(sub(unit.orderTargetY,unit.tileY)))"
        )
        lines = []
        for ordinal, cycle in enumerate((5, 20)):
            lines.append(json.dumps({
                "schema": 1, "side": "java", "ordinal": ordinal,
                "cycle": cycle, "kind": "semantic.predicate",
                "subject": "unit:47", "fields": {
                    "predicate_id": "gold.refuse.near-approach",
                    "lhs_expression": expression, "lhs": 1,
                    "operator": "<=", "rhs_expression": "2", "rhs": 2,
                    "result": True, "decision": "delay 6 or 14",
                    "source": "World.java:21814",
                },
            }))
        return "\n".join(lines) + "\n"

    def test_normalizes_coordinate_aliases_and_absolute_subtraction_order(self):
        native = bne_semantic_bridge.normalize(
            bne_semantic_bridge.parse_expression(
                "abs(sub(unit[1553].order_x,unit[1553].x))"),
            "native", self.atlas(),
        )
        java = bne_semantic_bridge.normalize(
            bne_semantic_bridge.parse_expression(
                "abs(sub(unit.tileX,unit.orderTargetX))"),
            "java", self.atlas(),
        )
        self.assertEqual(native, java)

    def test_classifies_native_axis_as_component_without_guessing_table(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            source = root / "World.java"
            source.write_text("""
class World {
  boolean near(int goalX, int x) {
    return Math.abs(goalX - x) <= 2;
  }
}
""", encoding="utf-8")
            trace = root / "java.jsonl"
            trace.write_text(self.trace_lines(), encoding="utf-8")
            events = bne_semantic_bridge.parse_java_trace(trace)
            result = bne_semantic_bridge.analyze_bridge(
                self.semantic_slice(), events, self.atlas(), root,
            )

        self.assertEqual("related-boundary",
                         result["diagnosis"]["classification"])
        self.assertTrue(result["proof"]["usefulness_gate_passed"])
        self.assertFalse(result["proof"]["semantic_equivalence_proved"])
        self.assertEqual("native-component-of-java",
                         result["dynamic_candidates"][0]["relation"])
        self.assertIn("function_arg2",
                      result["dynamic_candidates"][0]["unmatched_native_symbols"])
        self.assertEqual(2,
                         result["diagnosis"]["held_out_java_observations"])
        self.assertEqual([5, 20],
                         result["temporal_signature"]["java"]["cycles"])
        self.assertEqual([15],
                         result["temporal_signature"]["java"]["gaps"])
        self.assertEqual("single-gap",
                         result["temporal_signature"]["java"]["classification"])

    def test_cadence_requires_two_equal_gaps_before_claiming_a_period(self):
        tentative = bne_semantic_bridge.cadence_signature([9, 29])
        stable = bne_semantic_bridge.cadence_signature([9, 29, 49, 69])
        irregular = bne_semantic_bridge.cadence_signature([9, 39, 59, 79])

        self.assertIsNone(tentative["stable_period"])
        self.assertEqual(20, stable["stable_period"])
        self.assertEqual("irregular", irregular["classification"])

    def test_durable_run_is_an_authenticated_cache_hit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            semantic = root / "semantic.json"
            semantic.write_text(json.dumps(self.semantic_slice()),
                                encoding="utf-8")
            trace = root / "java.jsonl"
            trace.write_text(self.trace_lines(), encoding="utf-8")
            atlas = root / "atlas.json"
            atlas.write_text(json.dumps(self.atlas()), encoding="utf-8")
            source = root / "source"
            source.mkdir()
            (source / "World.java").write_text(
                "class World { boolean near(int x) { return Math.abs(x) <= 2; } }\n",
                encoding="utf-8",
            )
            artifacts = root / "artifacts"

            first_status, first_run = bne_semantic_bridge.run_bridge(
                semantic, trace, source, atlas, artifacts,
            )
            second_status, second_run = bne_semantic_bridge.run_bridge(
                semantic, trace, source, atlas, artifacts,
            )

            self.assertEqual(0, first_status)
            self.assertEqual(first_run, second_run)
            self.assertEqual(first_status, second_status)
            pointer = json.loads((artifacts / "latest.json").read_text())
            self.assertTrue(pointer["usefulness_gate_passed"])

    def test_rejects_legacy_slice_without_focus_identity(self):
        semantic = self.semantic_slice()
        semantic["schema"] = 1
        semantic["proof"].pop("focus_identity_proven")
        with self.assertRaisesRegex(ValueError, "focus identity"):
            bne_semantic_bridge.analyze_bridge(
                semantic, [], self.atlas(), Path("."),
            )


if __name__ == "__main__":
    unittest.main()
