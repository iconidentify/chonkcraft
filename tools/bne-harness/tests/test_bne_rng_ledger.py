from pathlib import Path
import json
import sys
import unittest


SCRIPTS = Path(__file__).parents[1] / "scripts"
if str(SCRIPTS) not in sys.path:
    sys.path.insert(0, str(SCRIPTS))

import bne_rng_ledger


MASK = 0xffffffff
MULTIPLIER = 0x015a4e35


def advance(seed: int) -> int:
    return (seed * MULTIPLIER + 1) & MASK


def chain(seed: int, count: int) -> list[tuple[int, int, int]]:
    """A real run of BNE's asynchronous generator, seed by seed."""
    draws = []
    for _ in range(count):
        after = advance(seed)
        draws.append((seed, after, (after >> 16) & 0x7fff))
        seed = after
    return draws


def native_trace(draws, callers, *, first_cycle=1) -> str:
    """The tracer's own line shape, addresses printed the way %p prints them."""
    lines = []
    for index, (before, after, result) in enumerate(draws):
        caller = callers[index]
        if caller.startswith("0x"):
            caller = caller[2:]
        lines.append(
            f"# bne-trace event=async-random cycle={first_cycle + index} "
            f"caller={caller} before={before} after={after} "
            f"result={result}"
        )
    return "\n".join(lines) + "\n"


def java_causal(draws, callers, *, first_cycle=1) -> str:
    lines = []
    for index, (before, after, result) in enumerate(draws):
        lines.append(json.dumps({
            "schema": 1, "side": "java", "ordinal": index,
            "cycle": first_cycle + index, "kind": "rng.async.draw",
            "fields": {
                "before": before, "after": after, "result": result,
                "draw": index + 1, "caller": callers[index],
                "caller_chain": None, "caller_line": 100 + index,
                "context": None,
            },
        }, sort_keys=True))
    return "\n".join(lines) + "\n"


EVIDENCE = {
    "path": "/evidence/case.trace.txt", "sha256": "0" * 64, "bytes": 1024,
    "scenario": "Campaign\\Human\\Human01.pud", "seed": 1, "cycles": 60,
}


class BneRngLedgerTest(unittest.TestCase):

    def ledger(self, native_text, java_text, **kwargs):
        return bne_rng_ledger.ledger_from_text(
            native_text, java_text, native_evidence=EVIDENCE,
            case="synthetic", **kwargs,
        )

    def test_identical_ledgers_report_no_disagreement(self):
        draws = chain(1, 6)
        callers = ["0x00418370"] * 6
        java_callers = ["BattleNetProjectileSystem.battleNetProjectileDamage"] * 6
        report = self.ledger(native_trace(draws, callers),
                             java_causal(draws, java_callers))
        self.assertEqual("identical", report["classification"],
                         "two runs of the same generator spent on the same "
                         "consumers were reported as disagreeing")
        self.assertEqual(6, report["matched"])
        self.assertIsNone(report["first_mismatch"])
        self.assertEqual(0, bne_rng_ledger.exit_code(report))

    def test_java_map_construction_prefix_is_outside_native_capture_window(self):
        # The native async hook starts at scenario-loaded while Java causal
        # tracing also sees unit-pool construction. The shared post-load seed
        # is an evidence boundary, not hundreds of gameplay-only Java draws.
        draws = chain(1, 8)
        native = draws[2:]
        report = self.ledger(
            native_trace(native, ["0x0040ad58"] * len(native)),
            java_causal(draws,
                        ["World.initializeBattleNetUnit"] * 2
                        + ["BattleNetIdleSystem.battleNetLandIdleChoice"]
                        * len(native)),
        )
        self.assertEqual("identical", report["classification"])
        self.assertEqual({"native": 0, "java": 2},
                         report["capture_prefix_excluded"])
        self.assertEqual(6, report["matched"])
        self.assertEqual(6, report["java"]["compared_draw_count"])

    def test_two_native_callsites_may_share_one_java_consumer(self):
        # Java deliberately folds native projectile X/Y aim call sites into
        # one helper. That many-to-one implementation mapping does not mean
        # the random stream changed consumers.
        draws = chain(1, 4)
        report = self.ledger(
            native_trace(draws, ["0x0040fbf7", "0x0040fc06"] * 2),
            java_causal(draws,
                        ["BattleNetProjectileSystem.aimJitter"] * 4),
        )
        self.assertEqual("identical", report["classification"])
        self.assertIsNone(report["first_mismatch"])

    def test_native_damage_consumer_may_be_split_across_java_helpers(self):
        # Native shares one physical-damage RNG routine. Java splits melee
        # and projectile resolution without changing the stream consumer.
        draws = chain(1, 4)
        report = self.ledger(
            native_trace(draws, ["0x00418412"] * 4),
            java_causal(draws, [
                "BattleNetProjectileSystem.battleNetProjectileDamage",
                "World.battleNetMeleeDamage",
                "World.battleNetMeleeDamage",
                "BattleNetProjectileSystem.battleNetProjectileDamage",
            ]),
        )
        self.assertEqual("identical", report["classification"])
        self.assertIsNone(report["first_mismatch"])

    def test_one_extra_java_draw_is_named_where_the_consumers_shift(self):
        # Both engines take numbers out of one generator, so an extra Java
        # draw leaves every later seed exactly where it was and moves who
        # spent it. The evidence is the shift, not a changed number.
        draws = chain(1, 7)
        native_callers = ["0x00418370", "0x0040ad30"] * 3
        java_callers = [
            "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
            "BattleNetProjectileSystem.aimJitter",
            "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
            "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
        ]
        report = self.ledger(native_trace(draws[:6], native_callers),
                             java_causal(draws, java_callers))
        self.assertEqual("java-extra-draw", report["classification"],
                         "a seventh number Java took out of a stream native "
                         "spent six from was not identified as an extra draw")
        self.assertEqual(2, report["first_mismatch"]["at_match_index"],
                         "the extra draw is reported somewhere other than the "
                         "draw whose consumer first shifted")
        self.assertEqual("BattleNetProjectileSystem.aimJitter",
                         report["first_mismatch"]["observed_java_caller"])
        self.assertEqual(1, report["surplus_java_draws"])

    def test_one_missing_java_draw_is_named_where_the_consumers_shift(self):
        draws = chain(1, 6)
        native_callers = ["0x00418370", "0x0040ad30", "0x00451b50"] * 2
        # Java never takes the third draw, so its later consumers arrive one
        # seed early and the native address at that seed loses its partner.
        java_callers = [
            "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
            "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
            "World.initializeBattleNetUnit",
        ]
        report = self.ledger(native_trace(draws, native_callers),
                             java_causal(draws[:5], java_callers))
        self.assertEqual("native-draw-missing-in-java",
                         report["classification"],
                         "a number native took and Java did not was not "
                         "identified as a missing draw")
        self.assertEqual(3, report["first_mismatch"]["at_match_index"],
                         "the missing draw is reported somewhere other than "
                         "the first established native call site whose Java "
                         "consumer shifted")
        self.assertEqual(-1, report["surplus_java_draws"])

    def test_a_shorter_capture_alone_is_not_reported_as_a_missing_draw(self):
        draws = chain(1, 8)
        callers = ["0x00418370"] * 8
        java_callers = ["World.battleNetMeleeDamage"] * 5
        report = self.ledger(native_trace(draws, callers),
                             java_causal(draws[:5], java_callers))
        self.assertEqual("window-length-difference",
                         report["classification"],
                         "a Java run that simply stopped earlier was reported "
                         "as having skipped a draw, which turns a short "
                         "capture into a finding about the game")
        self.assertEqual(5, report["matched"])

    def test_the_same_seed_chain_spent_in_another_order_is_a_reordering(self):
        draws = chain(1, 6)
        native_callers = ["0x00418370", "0x0040ad30"] * 3
        # The last two consumers swap while every seed stays where it was.
        java_callers = [
            "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
            "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
            "BattleNetIdleSystem.wander", "World.battleNetMeleeDamage",
        ]
        report = self.ledger(native_trace(draws, native_callers),
                             java_causal(draws, java_callers))
        self.assertEqual("consumer-reordered", report["classification"],
                         "the same seeds spent by the same consumers in "
                         "another order were not reported as a reordering")
        self.assertEqual(4, report["first_mismatch"]["at_match_index"],
                         "the reordering is reported somewhere other than the "
                         "first draw whose consumer changed")
        self.assertTrue(report["first_mismatch"]["window_consumers_preserved"])

    def test_the_same_seed_spent_by_a_new_consumer_is_not_a_reordering(self):
        draws = chain(1, 6)
        native_callers = ["0x00418370", "0x0040ad30"] * 3
        java_callers = [
            "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
            "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
            "World.battleNetMeleeDamage",
            "BattleNetConstructionSystem.burnBattleNetConstructorStream",
        ]
        report = self.ledger(native_trace(draws, native_callers),
                             java_causal(draws, java_callers))
        self.assertEqual("same-seed-different-consumer",
                         report["classification"],
                         "a draw spent somewhere it had never been spent "
                         "before was reported as a mere reordering")
        self.assertEqual(
            "BattleNetConstructionSystem.burnBattleNetConstructorStream",
            report["first_mismatch"]["observed_java_caller"])
        self.assertEqual("BattleNetIdleSystem.wander",
                         report["first_mismatch"]["expected_java_caller"])

    def test_a_cycle_offset_does_not_stop_the_two_ledgers_aligning(self):
        draws = chain(1, 6)
        report = self.ledger(
            native_trace(draws, ["0x00418370"] * 6, first_cycle=40),
            java_causal(draws, ["World.battleNetMeleeDamage"] * 6,
                        first_cycle=38),
        )
        self.assertEqual("identical", report["classification"],
                         "the same draws counted from a different cycle were "
                         "reported as a disagreement")
        self.assertEqual(2, report["cycle_offset"]["modal"],
                         "the two-cycle presentation offset between the "
                         "engines was not measured")
        self.assertTrue(report["cycle_offset"]["stable"])

    def test_a_recorded_draw_that_is_not_the_generator_is_refused(self):
        draws = chain(1, 4)
        broken = list(draws)
        broken[2] = (broken[2][0], broken[2][1] ^ 0x00010000, broken[2][2])
        report = self.ledger(
            native_trace(broken, ["0x00418370"] * 4),
            java_causal(draws, ["World.battleNetMeleeDamage"] * 4),
        )
        self.assertEqual("malformed-lcg-transition", report["classification"],
                         "a seed step that is not the asynchronous LCG was "
                         "aligned as though it were evidence")
        self.assertEqual(2, bne_rng_ledger.exit_code(report),
                         "unbelievable evidence exits as though it were a "
                         "finding about the game")
        self.assertEqual(
            ["seed transition", "returned value"],
            report["validation"]["native_invalid"][0]["problems"])

    def test_missing_native_evidence_produces_a_capture_plan_not_a_verdict(self):
        draws = chain(1, 4)
        report = bne_rng_ledger.ledger_from_text(
            None, java_causal(draws, ["World.battleNetMeleeDamage"] * 4),
            case="synthetic",
        )
        self.assertEqual("native-evidence-missing", report["classification"],
                         "a Java ledger with nothing to compare against was "
                         "given a verdict")
        self.assertIn("bne_oracle.py run",
                      report["next_experiment"]["capture_command"],
                      "the absent native side produced no capture command")
        self.assertEqual([], report["operations"])

    def test_an_unauthenticated_native_ledger_is_not_quietly_compared(self):
        draws = chain(1, 4)
        report = bne_rng_ledger.ledger_from_text(
            native_trace(draws, ["0x00418370"] * 4),
            java_causal(draws, ["World.battleNetMeleeDamage"] * 4),
            case="synthetic",
        )
        self.assertEqual("native-evidence-missing", report["classification"],
                         "a native ledger with no oracle manifest behind it "
                         "was compared as though it were authenticated")
        self.assertIn("authenticates", report["next_experiment"]["reason"])

    def test_a_diverged_seed_says_the_disagreement_began_earlier(self):
        native = chain(1, 6)
        java = chain(1, 3) + chain(999, 3)
        report = self.ledger(
            native_trace(native, ["0x00418370"] * 6),
            java_causal(java, ["World.battleNetMeleeDamage"] * 6),
        )
        self.assertIn(report["classification"],
                      ("seed-chain-diverged", "java-extra-draw",
                       "native-draw-missing-in-java"),
                      "a Java run whose seed left the native chain was "
                      "reported as agreeing")
        self.assertEqual(3, report["matched"],
                         "the three draws taken before the seeds parted were "
                         "not credited as agreement")

    def test_the_synchronized_stream_aligns_although_the_two_engines_cut_it_differently(self):
        # Native takes its number from the advanced seed and this port takes
        # it from the seed as it stood. The step is the same either way, and
        # the same run must not read as four diverged seeds because of it.
        mask, seed = 0xffffffff, 1
        native, java = [], []
        for index in range(4):
            after = (seed * 0x41c64e6d + 0x3039) & mask
            native.append(
                f"# bne-trace event=sync-random cycle={index + 1} "
                f"caller=004534c0 before={seed} after={after} "
                f"result={(after >> 16) & 0x7fff}")
            java.append(json.dumps({
                "schema": 1, "side": "java", "ordinal": index,
                "cycle": index + 1, "kind": "rng.sync.draw",
                "fields": {"before": seed, "after": after,
                           "result": (seed >> 16) & 0xffff,
                           "draw": index + 1, "caller": "World.damageFor"},
            }, sort_keys=True))
            seed = after
        report = bne_rng_ledger.ledger_from_text(
            "\n".join(native), "\n".join(java), stream="sync",
            native_evidence=EVIDENCE, case="synthetic")
        self.assertEqual("identical", report["classification"],
                         "the synchronized stream reported a divergence "
                         "because the engines cut the returned number from "
                         "opposite ends of the same seed step")
        self.assertEqual([], report["validation"]["native_invalid"])
        self.assertEqual([], report["validation"]["java_invalid"],
                         "the port's own rule for the returned number was "
                         "judged against native's")

    def test_the_report_names_the_consumers_without_claiming_they_are_equal(self):
        draws = chain(1, 4)
        report = self.ledger(
            native_trace(draws, ["0x00418370"] * 4),
            java_causal(draws, ["World.battleNetMeleeDamage"] * 4),
        )
        correspondence = report["consumer_correspondence"]
        self.assertEqual(1, len(correspondence),
                         "one native address paired with one Java method was "
                         "not recorded as an observed correspondence")
        self.assertFalse(correspondence[0]["proved"],
                         "a correspondence merely observed in one pair of "
                         "ledgers is presented as a proved mapping")
        self.assertEqual(4, correspondence[0]["support"])

    def test_the_same_evidence_twice_produces_the_same_bytes(self):
        draws = chain(1, 6)
        java_callers = ["World.battleNetMeleeDamage",
                        "BattleNetIdleSystem.wander"] * 3
        native_callers = ["0x00418370", "0x0040ad30", "0x0040ad30"] * 2
        first = self.ledger(native_trace(draws, native_callers),
                            java_causal(draws, java_callers))
        second = self.ledger(native_trace(draws, native_callers),
                             java_causal(draws, java_callers))
        self.assertEqual(json.dumps(first, sort_keys=True),
                         json.dumps(second, sort_keys=True),
                         "the same evidence produced two different reports, "
                         "so a content-addressed run could never cache")
        self.assertEqual(bne_rng_ledger.format_ledger(first),
                         bne_rng_ledger.format_ledger(second))

    def test_the_markdown_report_names_the_first_disagreement(self):
        draws = chain(1, 7)
        native_callers = ["0x00418370", "0x0040ad30"] * 3
        java_callers = [
            "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
            "BattleNetProjectileSystem.aimJitter",
            "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
            "World.battleNetMeleeDamage", "BattleNetIdleSystem.wander",
        ]
        report = self.ledger(native_trace(draws[:6], native_callers),
                             java_causal(draws, java_callers))
        markdown = bne_rng_ledger.format_ledger(report)
        self.assertIn("First disagreement", markdown)
        self.assertIn("Java takes a draw native does not.", markdown)
        self.assertIn("not a proved mapping", markdown,
                      "the report compares a return address with a method "
                      "name without saying they are different kinds of name")


if __name__ == "__main__":
    unittest.main()
