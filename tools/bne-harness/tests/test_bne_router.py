from pathlib import Path
import sys
import unittest

SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_router


def finding(kind, field, **extra):
    return {"cycle": 44, "kind": kind, "field": field, "unit": 1, **extra}


def blocker(case, findings, rank=1, state="retained"):
    return {"case": case, "cycle": 44, "first_divergence_cycle": 44,
            "rank": rank, "findings": findings, "state": state}


def lanes(route):
    return {step["lane"]: step["state"] for step in route["steps"]}


class RoutingTest(unittest.TestCase):
    """A blocker goes where its finding says, not where its name says."""

    def test_a_submarine_that_is_two_tiles_out_goes_to_cadence(self):
        route = bne_router.route_blocker(
            blocker("retail-xorc-08-idle",
                    [finding("unit", "x", unit_type="unit-human-submarine",
                             oracle=102, java=100),
                     finding("unit", "y", unit_type="unit-human-submarine",
                             oracle=88, java=86),
                     finding("unit", "order", unit_type="unit-gryphon-rider",
                             oracle="still", java="patrol")]),
            packet_path="blockers/retail-xorc-08-idle/packet",
        )
        self.assertEqual("position-movement", route["family"])
        self.assertEqual("cadence", route["next_lane"],
                         "a position mismatch did not start at the profiler")
        self.assertEqual(bne_router.QUEUED, lanes(route)["state-machine"],
                         "the temporal state machine was not queued behind cadence")

    def test_an_order_finding_also_asks_for_the_native_write(self):
        route = bne_router.route_blocker(
            blocker("orc-08", [finding("unit", "x"), finding("unit", "order")]),
            packet_path="p",
        )
        self.assertIn("branch-witness", route["blocked_lanes"],
                      "a differing order did not raise a field-write question")

    def test_an_order_only_finding_still_starts_cheaply(self):
        route = bne_router.route_blocker(
            blocker("orc-08", [finding("unit", "order")]), packet_path="p")
        self.assertEqual("order-acquisition", route["family"])
        self.assertEqual("cadence", route["next_lane"],
                         "an order mismatch skipped the evidence already on disk")

    def test_an_ogre_losing_five_hit_points_is_not_an_rng_question_by_default(self):
        """Escalating on the word hp produces a ledger that says nothing."""
        route = bne_router.route_blocker(
            blocker("retail-human-13-idle",
                    [finding("unit", "hp", unit_type="unit-ogre",
                             oracle=90, java=85)]),
            packet_path="p",
            hp_evidence={"applicable": True, "direction": "falling",
                         "cadence_agrees": False, "change_count_agrees": False,
                         "values_differ": True,
                         "randomized_damage_suspected": False},
        )
        self.assertEqual("hit-points", route["family"])
        self.assertEqual(bne_router.BLOCKED, lanes(route)["rng"],
                         "the draw ledger was run without its precondition")
        self.assertEqual("causal", route["next_lane"],
                         "hit points with a disagreeing shape did not go to "
                         "event-order analysis")
        withheld = next(step for step in route["steps"] if step["lane"] == "rng")
        self.assertTrue(withheld["withheld"])

    def test_the_one_documented_damage_shape_does_escalate_to_the_ledger(self):
        route = bne_router.route_blocker(
            blocker("human-13", [finding("unit", "hp")]),
            packet_path="p",
            hp_evidence={"applicable": True, "direction": "falling",
                         "cadence_agrees": True, "change_count_agrees": True,
                         "values_differ": True,
                         "randomized_damage_suspected": True},
        )
        rng = next(step for step in route["steps"] if step["lane"] == "rng")
        self.assertNotEqual(
            True, rng.get("withheld"),
            "the shape the ledger documents did not reach the ledger")
        self.assertIn("precondition", rng["reason"])

    def test_an_available_native_trace_unblocks_the_ledger(self):
        route = bne_router.route_blocker(
            blocker("human-13", [finding("unit", "hp")]),
            packet_path="p",
            hp_evidence={"applicable": True, "direction": "falling",
                         "cadence_agrees": True, "change_count_agrees": True,
                         "values_differ": True,
                         "randomized_damage_suspected": True},
            capabilities={"native_traces": {"human-13": "/traces/human-13.txt"}},
        )
        self.assertEqual(bne_router.QUEUED, lanes(route)["rng"],
                         "an authenticated native trace did not unblock the ledger")

    def test_a_synchronized_seed_mismatch_goes_straight_to_the_ledger(self):
        route = bne_router.route_blocker(
            blocker("orc-01", [{"cycle": 44, "kind": "sync_rng"}]),
            packet_path="p")
        self.assertEqual("synchronized-rng", route["family"])
        self.assertIn("rng", route["blocked_lanes"])

    def test_a_blocked_lane_always_carries_the_command_that_unblocks_it(self):
        route = bne_router.route_blocker(
            blocker("orc-08", [finding("unit", "order")]), packet_path="p")
        for step in route["steps"]:
            if step["state"] == bne_router.BLOCKED and step["lane"] != "rng":
                self.assertTrue(
                    step.get("command") or step.get("recovery"),
                    f"lane {step['lane']} is blocked with no way forward",
                )

    def test_nothing_in_a_route_modifies_engine_source(self):
        route = bne_router.route_blocker(
            blocker("orc-08", [finding("unit", "x")]), packet_path="p")
        counterfactual = next(step for step in route["steps"]
                              if step["lane"] == "counterfactual")
        self.assertTrue(counterfactual["never_modifies_source"])
        # This asserted `--plan-only`, a flag `counterfactual` does not have,
        # so it proved the wording of a command that could not run rather than
        # the promise the command makes.
        self.assertIn("counterfactual", counterfactual["command"])
        self.assertIn(bne_router.PLACEHOLDERS["triage_run"],
                      counterfactual["requires_input"])

    def test_a_blocker_without_a_frame_cannot_report_a_complete_packet(self):
        route = bne_router.route_blocker(
            blocker("orc-08", [finding("unit", "x")], state="unavailable"))
        self.assertEqual(bne_router.BLOCKED, lanes(route)["packet"])


class RoutingOrderTest(unittest.TestCase):
    """Cheapest first, and acceptance priority untouched."""

    def test_tied_blockers_are_routed_in_estimated_cost_order(self):
        routing = bne_router.route_all([
            blocker("expensive", [finding("unit", "hp")], rank=2),
            blocker("cheap", [finding("unit", "x")], rank=1),
        ])
        self.assertEqual(["cheap", "expensive"], routing["order"],
                         "tied blockers were not routed cheapest first")

    def test_routing_never_claims_to_change_acceptance(self):
        routing = bne_router.route_all(
            [blocker("a", [finding("unit", "x")])])
        self.assertTrue(routing["acceptance_order_unchanged"])
        self.assertTrue(routing["parallel_diagnostics_permitted"])

    def test_every_reported_lane_has_a_state(self):
        routing = bne_router.route_all([
            blocker("a", [finding("unit", "x")], rank=1),
            blocker("b", [finding("unit", "hp")], rank=2),
        ])
        for lane in bne_router.LANES:
            self.assertIn(lane, routing["lanes"])
            self.assertIn(routing["lanes"][lane],
                          {"complete", "queued", "running", "blocked", "absent"})

    def test_one_complete_lane_is_reported_complete_across_blockers(self):
        routing = bne_router.route_all([
            blocker("a", [finding("unit", "x")], rank=1),
            blocker("b", [finding("unit", "x")], rank=2, state="unavailable"),
        ], packets={"a": "blockers/a/packet"})
        self.assertEqual("complete", routing["lanes"]["packet"])


if __name__ == "__main__":
    unittest.main()
