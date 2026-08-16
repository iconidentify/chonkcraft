"""Tests for physical-input-to-outcome transaction receipts."""

from pathlib import Path
import sys
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_player_transaction as transaction


def evidence(*, gesture: bool = True, terminal: bool = True,
             second_unit: bool = True):
    intents = []
    if gesture:
        intents.append({
            "intent_id": 1,
            "transaction_id": 1,
            "cycle": 10,
            "event": "gesture",
            "selected_unit_ids": [7, 9] if second_unit else [7],
            "gesture": {
                "origin": "field",
                "detail": "right-click",
                "screen_x": 320,
                "screen_y": 240,
                "tile_x": 12,
                "tile_y": 13,
                "modifiers": "shift",
                "target_id": None,
                "target_shape": "open-ground",
            },
        })
    ids = [7, 9] if second_unit else [7]
    for offset, unit_id in enumerate(ids, 2):
        intents.append({
            "intent_id": offset,
            "transaction_id": 1,
            "cycle": 10,
            "event": "order",
            "selected_unit_ids": ids,
            "accepted": True,
            "command": {
                "kind": "MOVE",
                "player": 0,
                "unit_id": unit_id,
                "x": 12,
                "y": 13,
                "target_id": 0,
                "type_index": 0,
                "queued": True,
                "wire_hex": "0100",
            },
        })
    outcomes = []
    for offset, unit_id in enumerate(ids, 2):
        outcomes.append({
            "intent_id": offset,
            "transaction_id": 1,
            "submitted_cycle": 10,
            "unit_id": unit_id,
            "command": "MOVE",
            "accepted": True,
            "first_progress_cycle": 12,
            "terminal_cycle": 30 if terminal else None,
            "terminal_reason": "settled" if terminal else None,
            "tile_x": 12,
            "tile_y": 13,
            "offset_x": 0,
            "offset_y": 0,
            "order": "STILL",
            "target_id": None,
            "hit_points": 60,
            "carried": 0,
            "alive": True,
            "on_map": True,
            "missile_count": 0,
        })
    return {
        "map_path": "maps/test.pud",
        "campaign": None,
        "mission": 0,
        "player_intents": intents,
        "player_outcomes": outcomes,
    }


class PlayerTransactionTest(unittest.TestCase):

    def test_group_fanout_stays_one_transaction(self):
        compiled = transaction.compile_evidence(evidence(), source="test")
        self.assertEqual(1, len(compiled["transactions"]))
        item = compiled["transactions"][0]
        self.assertTrue(item["coverage"]["physical_gesture"])
        self.assertTrue(item["coverage"]["group_fanout"])
        self.assertTrue(item["coverage"]["queued"])
        self.assertTrue(item["coverage"]["terminal"])
        self.assertEqual([7, 9],
                         [command["unit_id"] for command in item["commands"]])

    def test_gesture_difference_is_the_first_causal_difference(self):
        left = transaction.compile_evidence(evidence(), source="left")
        changed = evidence()
        changed["player_intents"][0]["gesture"]["tile_x"] = 14
        right = transaction.compile_evidence(changed, source="right")
        difference = transaction.first_difference(left, right)
        self.assertEqual(0, difference["transaction"])
        self.assertEqual("gesture", difference["field"])

    def test_gestureless_command_is_explicit_coverage_debt(self):
        compiled = transaction.compile_evidence(
            evidence(gesture=False, second_unit=False), source="test")
        requirements = {
            "minimum_transactions": 1,
            "minimum_group_transactions": 0,
            "origins": ["field"],
            "modifiers": [],
            "selection_sizes": [],
            "target_shapes": [],
            "families": ["move"],
            "queueable_families": [],
            "rejection_families": [],
        }
        report = transaction.coverage([compiled], requirements)
        self.assertFalse(report["complete"])
        self.assertEqual([1], report["gestureless_transactions"])
        self.assertEqual(["field"], report["missing"]["origins"])

    def test_unfinished_command_prevents_certification(self):
        compiled = transaction.compile_evidence(
            evidence(terminal=False, second_unit=False), source="test")
        requirements = {
            "minimum_transactions": 1,
            "minimum_group_transactions": 0,
            "origins": ["field"],
            "modifiers": ["shift"],
            "selection_sizes": [1],
            "target_shapes": ["open-ground"],
            "families": ["move"],
            "queueable_families": ["move"],
            "rejection_families": [],
        }
        report = transaction.coverage([compiled], requirements)
        self.assertFalse(report["complete"])
        self.assertEqual([1], report["incomplete_transactions"])

    def test_native_do_right_button_trace_is_one_group_transaction(self):
        compiled = transaction.compile_ui_trace(
            "\n".join([
                "# bne-trace event=ui-right-click cycle=5 x=25 y=28 "
                "ui-player=1 selected=1598,1597",
                "# bne-trace event=ui-fanout cycle=5 unit=1598 "
                "order=3 next-order=60 order-x=25 order-y=28",
                "# bne-trace event=ui-fanout cycle=5 unit=1597 "
                "order=3 next-order=60 order-x=25 order-y=28",
                "# bne-trace event=command-unit-state cycle=80 unit=1598 "
                "sequence=0 sequence-flags=0 animation-timer=0 animation=0 "
                "frame=0 face=0 order=1 next-order=60 order-x=25 order-y=28 "
                "path-head=0",
                "# bne-trace event=command-unit-state cycle=80 unit=1597 "
                "sequence=0 sequence-flags=0 animation-timer=0 animation=0 "
                "frame=0 face=0 order=1 next-order=60 order-x=25 order-y=28 "
                "path-head=0",
            ]),
            source="test-trace")
        self.assertEqual(1, len(compiled["transactions"]))
        item = compiled["transactions"][0]
        self.assertTrue(item["coverage"]["physical_gesture"],
                        "DoRightButton must keep the field click as the gesture")
        self.assertTrue(item["coverage"]["group_fanout"],
                        "two selected footmen must stay one transaction")
        self.assertEqual([1598, 1597],
                         [command["unit_id"] for command in item["commands"]])
        self.assertEqual([(25, 28), (25, 28)],
                         [(command["x"], command["y"]) for command in item["commands"]],
                         "fan-out dests belong on the commands, not only the click")
        self.assertEqual("field", item["gesture"]["origin"])
        self.assertEqual(25, item["gesture"]["tile_x"])
        self.assertEqual(28, item["gesture"]["tile_y"])


if __name__ == "__main__":
    unittest.main()
