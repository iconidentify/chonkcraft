from pathlib import Path
import sys
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_state_machine as miner

from test_bne_state_machine import record


def window(rows, *, first_cycle=40):
    """A ramp, a field it may clear, and a timer it may arm."""
    return miner.mine([
        miner.Sample(cycle=first_cycle + index,
                     raw=record(o4=ramp, o8=route, o12=timer), written=True)
        for index, (ramp, route, timer) in enumerate(rows)
    ], ranges=())


FOCUS = window([(1, 22, 0), (2, 22, 0), (3, 22, 0), (4, 0, 15),
                (4, 0, 14), (4, 0, 13)])


class BneStateCounterexampleTest(unittest.TestCase):

    def test_the_focused_finding_becomes_a_rule_another_case_can_test(self):
        rule = miner.rule_from_threshold(FOCUS)
        self.assertEqual("byte:0x04", rule["observable"])
        self.assertEqual(4, rule["threshold"],
                         "the value the ramp had reached when the other field "
                         "changed is not what the rule was written down as")
        self.assertEqual("byte:0x08", rule["consequence"])

    def test_a_case_that_behaves_the_same_way_supports_the_rule(self):
        rule = miner.rule_from_threshold(FOCUS)
        same = window([(1, 30, 0), (2, 30, 0), (3, 30, 0), (4, 0, 15),
                       (4, 0, 14), (4, 0, 13)])
        result = miner.test_rule(rule, same)
        self.assertEqual("supports", result["verdict"])
        self.assertEqual(4, result["observed_threshold"])

    def test_a_case_that_clears_at_another_value_breaks_the_threshold(self):
        rule = miner.rule_from_threshold(FOCUS)
        earlier = window([(1, 22, 0), (2, 0, 15), (2, 0, 14), (2, 0, 13),
                          (2, 0, 12), (2, 0, 11)])
        result = miner.test_rule(rule, earlier)
        self.assertEqual("contradicts", result["verdict"],
                         "a case that cleared the route at two was counted as "
                         "confirming a rule that says eight")
        self.assertEqual(2, result["observed_threshold"])

    def test_a_ramp_whose_consequence_never_fires_breaks_an_overbroad_rule(self):
        rule = miner.rule_from_threshold(FOCUS)
        never = window([(1, 22, 0), (2, 22, 0), (3, 22, 0), (4, 22, 0),
                        (5, 22, 0), (6, 22, 0)])
        result = miner.test_rule(rule, never)
        self.assertEqual("contradicts", result["verdict"],
                         "a case where the counter climbed past the threshold "
                         "and nothing happened was not treated as breaking "
                         "the rule, which is exactly how a rule stays "
                         "overbroad")
        self.assertEqual(6, result["observed_peak"])

    def test_a_case_where_the_counter_never_moves_says_nothing_either_way(self):
        rule = miner.rule_from_threshold(FOCUS)
        quiet = window([(0, 22, 0), (0, 22, 0), (0, 21, 0), (0, 21, 0),
                        (0, 21, 0), (0, 21, 0)])
        result = miner.test_rule(rule, quiet)
        self.assertEqual("not-applicable", result["verdict"],
                         "a window that never tested the rule was counted as "
                         "confirming it")

    def test_the_nearest_counterexample_is_ranked_first(self):
        rule = miner.rule_from_threshold(FOCUS)
        near = window([(1, 22, 0), (2, 22, 0), (3, 0, 15), (3, 0, 14),
                       (3, 0, 13), (3, 0, 12)])
        far = miner.mine([
            miner.Sample(cycle=40 + index,
                         raw=record(o4=ramp, o8=route, o12=timer, o16=noise),
                         written=True)
            for index, (ramp, route, timer, noise) in enumerate([
                (1, 22, 0, 1), (2, 21, 3, 9), (1, 20, 6, 2), (2, 0, 9, 8),
                (1, 0, 12, 3), (2, 0, 15, 7)])
        ])
        ranked = miner.counterexamples(rule, FOCUS, [
            {"case": "far-case", "report": far},
            {"case": "near-case", "report": near},
        ])
        self.assertEqual("near-case",
                         ranked["nearest_counterexample"]["case"],
                         "the counterexample from the case that behaves least "
                         "like the focused one was offered first, which names "
                         "no missing condition")
        self.assertEqual(2, ranked["contradicts"])
        self.assertFalse(ranked["threshold_stable"],
                         "two cases cleared at different values and the "
                         "threshold was still reported as stable")

    def test_a_stable_threshold_across_supporting_cases_is_reported_as_stable(self):
        rule = miner.rule_from_threshold(FOCUS)
        same = window([(1, 30, 0), (2, 30, 0), (3, 30, 0), (4, 0, 15),
                       (4, 0, 14), (4, 0, 13)])
        ranked = miner.counterexamples(rule, FOCUS, [
            {"case": "twin", "report": same}])
        self.assertTrue(ranked["threshold_stable"])
        self.assertEqual(0, ranked["contradicts"])


class BneProposedStateAuditTest(unittest.TestCase):

    def findings(self, **overrides):
        java = miner.series_trajectories({
            40 + index: {"collisionHold": value}
            for index, value in enumerate([1, 2, 3, 4, 4, 4])
        })
        base = {
            "trajectories": FOCUS["trajectories"],
            "correlations": miner.correlate(
                FOCUS["trajectories"], java, 0),
            "counterexamples": {"results": []},
        }
        base.update(overrides)
        return base

    def test_a_proposal_with_a_native_trajectory_behind_it_is_supported(self):
        audit = miner.audit_proposed_state(
            {"name": "collisionHold", "java_field": "collisionHold",
             "lifetime_cycles": 3, "reset_when": "route cleared"},
            self.findings())
        self.assertEqual("evidence-supports", audit["verdict"],
                         f"a field that moves with a native byte was still "
                         f"warned about: {audit['warnings']}")
        self.assertTrue(audit["changes_no_source"])

    def test_a_java_only_flag_with_no_native_correlate_is_named_as_such(self):
        audit = miner.audit_proposed_state(
            {"name": "keepChasing", "java_field": "keepChasing"},
            self.findings())
        codes = [warning["code"] for warning in audit["warnings"]]
        self.assertIn("no-native-correlate", codes,
                      "a flag nothing in the native record moves with was "
                      "accepted without comment")
        self.assertEqual("evidence-contradicts", audit["verdict"])

    def test_a_state_held_longer_than_the_native_one_is_warned_about(self):
        audit = miner.audit_proposed_state(
            {"name": "collisionHold", "java_field": "collisionHold",
             "lifetime_cycles": 40, "reset_when": "route cleared"},
            self.findings())
        codes = [warning["code"] for warning in audit["warnings"]]
        self.assertIn("outlives-native-state", codes,
                      "a flag held for forty cycles against a native state "
                      "that lasts five was not questioned")

    def test_a_flag_named_after_its_fixture_is_questioned_but_not_rejected(self):
        audit = miner.audit_proposed_state(
            {"name": "xhuman12ResidualHold", "java_field": "collisionHold",
             "lifetime_cycles": 3, "reset_when": "route cleared"},
            self.findings())
        codes = [warning["code"] for warning in audit["warnings"]]
        self.assertIn("named-after-a-fixture", codes,
                      "a flag named after the mission it was found in passed "
                      "without comment")
        self.assertEqual("evidence-incomplete", audit["verdict"],
                         "a naming complaint was treated as a contradiction "
                         "of the evidence")

    def test_a_different_name_alone_is_never_a_warning(self):
        audit = miner.audit_proposed_state(
            {"name": "battleNetSettleCounter", "java_field": "collisionHold",
             "lifetime_cycles": 3, "reset_when": "route cleared"},
            self.findings())
        self.assertEqual([], audit["warnings"],
                         "a Java field whose name differs from anything "
                         "native was warned about for the name alone, and a "
                         "port matches behaviour rather than names")

    def test_a_counterexample_in_scope_contradicts_the_proposal(self):
        audit = miner.audit_proposed_state(
            {"name": "collisionHold", "java_field": "collisionHold",
             "lifetime_cycles": 3, "reset_when": "route cleared"},
            self.findings(counterexamples={"results": [{
                "case": "near-case", "verdict": "contradicts",
                "reason": "byte:0x08 changes at byte:0x04 = 3, not 4",
            }]}))
        codes = [warning["code"] for warning in audit["warnings"]]
        self.assertIn("contradicted-by-counterexample", codes)
        self.assertEqual("evidence-contradicts", audit["verdict"])

    def test_an_unstated_reset_is_reported_when_the_native_state_resets(self):
        resetting = window([(1, 22, 0), (2, 22, 0), (3, 22, 0), (4, 0, 15),
                            (0, 0, 14), (1, 0, 13)])
        java = miner.series_trajectories({
            40 + index: {"collisionHold": value}
            for index, value in enumerate([1, 2, 3, 4, 0, 1])
        })
        audit = miner.audit_proposed_state(
            {"name": "collisionHold", "java_field": "collisionHold"},
            {"trajectories": resetting["trajectories"],
             "correlations": miner.correlate(
                 resetting["trajectories"], java, 0),
             "counterexamples": {"results": []}})
        codes = [warning["code"] for warning in audit["warnings"]]
        self.assertIn("reset-condition-unstated", codes,
                      "the native counter falls back to zero in this window "
                      "and a proposal that never resets was not questioned")


if __name__ == "__main__":
    unittest.main()
