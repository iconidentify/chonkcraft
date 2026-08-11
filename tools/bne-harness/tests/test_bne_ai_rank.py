from pathlib import Path
import sys
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

from bne_ai_rank import rank


def case(name, mismatches):
    return {"id": name, "semantic_v2": {
        "families": ["player"], "mismatches": mismatches}}


class AiRankTest(unittest.TestCase):

    def test_separates_policy_from_prior_combat_fallout(self):
        result = rank({"cases": [
            case("combat", [
                {"cycle": 40, "family": "player", "field": "kills",
                 "retail": 1, "java": 0},
                {"cycle": 80, "family": "player", "field": "supply",
                 "retail": 9, "java": 13},
            ]),
            case("research", [
                {"cycle": 60, "family": "player", "field": "arrows",
                 "retail": 0, "java": 1},
            ]),
        ]})
        self.assertEqual("research", result["next"]["id"])
        by_id = {row["id"]: row for row in result["cases"]}
        self.assertEqual("casualty-cascade", by_id["combat"]["category"])
        self.assertEqual("research-policy", by_id["research"]["category"])

    def test_refuses_a_non_player_only_receipt(self):
        with self.assertRaisesRegex(ValueError, "player-only"):
            rank({"cases": [case("mixed", [])
                            | {"semantic_v2": {"families": ["player", "unit"],
                                                "mismatches": []}}]})

    def test_same_cycle_economy_is_not_ranked_ahead_of_a_casualty(self):
        result = rank({"cases": [case("same-cycle", [
            {"cycle": 40, "family": "player", "field": "kills",
             "retail": 1, "java": 0},
            {"cycle": 40, "family": "player", "field": "supply",
             "retail": 9, "java": 13},
        ])]})
        self.assertEqual("casualty-cascade", result["cases"][0]["category"])


if __name__ == "__main__":
    unittest.main()
