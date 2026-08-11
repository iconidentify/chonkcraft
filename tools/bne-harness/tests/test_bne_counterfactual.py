from pathlib import Path
import sys
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_counterfactual
import bne_java


def unit(order="PATROL", x=10, y=20):
    return {
        "type": "unit-human-destroyer", "player": 6,
        "x": x, "y": y, "hp": 100, "order": order, "removed": False,
    }


def trace(orders):
    return {
        cycle: {"seed": "00000001", "players": {},
                "units": {1542: unit(order=order)}}
        for cycle, order in enumerate(orders, 1)
    }


class BneCounterfactualTest(unittest.TestCase):

    def test_replay_uses_the_engine_trace_property_namespace(self):
        case = bne_java.Case(
            case_id="case", fixture=Path("fixture.bnefx"),
            fixture_id="fixture-id", scenario=r"Campaign\Human\Human01.pud",
            java_map="campaigns/human/level01h", cycles=1800, seed=7,
            state_schema="1.1",
        )

        command = bne_counterfactual._java_command(
            case, Path("trace.txt"), through=40,
            asset_pack=Path("bne.chonkpack"), source_dir=Path("chonkcraft"),
            java_wrapper=None, java="java", spec=Path("plan.tsv"),
        )

        self.assertIn("-Dchonkcraft.trace.seed=7", command)
        self.assertIn("-Dchonkcraft.trace.profile=bne", command)
        self.assertIn("-Dchonkcraft.trace.counterfactual=plan.tsv", command)
        retired_prefix = "-D" + "war" + "gus.trace."
        self.assertFalse(any(retired_prefix in item for item in command))

    def test_generates_bounded_order_candidates_from_packet(self):
        packet = {
            "case": {"id": "case"},
            "divergence": {"cycle": 25, "findings": [{
                "kind": "unit", "unit": 1542, "field": "order",
                "oracle": "patrol", "java": "attack",
            }]},
            "semantic": {"25": {"focus": [{
                "native_slot": 1542, "java_id": 58,
                "oracle": unit(), "java": unit("ATTACK_MOVE"),
            }]}},
        }

        candidates = bne_counterfactual.generate_candidates(packet)

        self.assertEqual(
            ["baseline", "pre-oracle-order", "post-oracle-order",
             "post-oracle-reported-order"],
            [item["id"] for item in candidates],
        )
        operations = {intervention["operation"]
                      for candidate in candidates
                      for intervention in candidate["interventions"]}
        self.assertEqual({"set-order", "set-reported-order"}, operations)

    def test_position_plan_includes_timing_replan_and_state_clamp(self):
        packet = {
            "case": {"id": "case"},
            "divergence": {"cycle": 25, "findings": [
                {"kind": "unit", "unit": 1563, "field": "x",
                 "oracle": 85, "java": 86},
                {"kind": "unit", "unit": 1563, "field": "y",
                 "oracle": 36, "java": 35},
            ]},
            "semantic": {"25": {"focus": [{
                "native_slot": 1563, "java_id": 37,
                "oracle": {**unit(), "x": 85, "y": 36},
                "java": {**unit(), "x": 86, "y": 35},
            }]}},
        }

        plan = bne_counterfactual.plan_from_packet(packet)

        self.assertEqual(4, plan["candidate_count"])
        self.assertEqual(
            {"control", "timing", "route-choice", "state-clamp"},
            {item["family"] for item in plan["candidates"]},
        )
        self.assertFalse(plan["policy"]["production_engine_hooks"])

    def test_ranking_rewards_a_future_not_just_the_divergent_frame(self):
        oracle = trace(["PATROL", "PATROL", "PATROL", "PATROL"])
        baseline_trace = trace(
            ["PATROL", "ATTACK_MOVE", "ATTACK_MOVE", "ATTACK_MOVE"]
        )
        corrected_trace = trace(["PATROL", "PATROL", "PATROL", "PATROL"])
        baseline = {
            "candidate": {"id": "baseline", "family": "control"},
            "score": bne_counterfactual.score_traces(
                oracle, baseline_trace, divergence_cycle=2,
                native_unit=1542, java_unit=1542, focus_fields={"order"},
            ),
        }
        corrected = {
            "candidate": {"id": "post-oracle-order",
                          "family": "transition-result"},
            "score": bne_counterfactual.score_traces(
                oracle, corrected_trace, divergence_cycle=2,
                native_unit=1542, java_unit=1542, focus_fields={"order"},
            ),
        }

        ranked = bne_counterfactual.rank_results([baseline, corrected])

        self.assertEqual("post-oracle-order", ranked[0]["candidate"]["id"])
        self.assertEqual("frontier-advanced", ranked[0]["classification"])
        self.assertEqual("high", ranked[0]["confidence"])
        self.assertEqual(3, ranked[0]["score"]["future_focus_exact_cycles"])


if __name__ == "__main__":
    unittest.main()
