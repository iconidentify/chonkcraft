package net.chonkbase.chonkcraft.engine.missile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Locks retail BNE's integer projectile flight against the Human 13 tower
 * arrow and the type-13 catapult rock minimum-flight rule.
 *
 * <p>Implements the remaining-distance init in {@code 0x0040fdc0}/{@code
 * 0x0040fb10} and the point-motion action at {@code 0x004101f0} with direction
 * state from {@code 0x00429fa0}.
 */
class BattleNetMissileMotionTest {

    private static MissileType arrow() {
        return new MissileType("missile-arrow", null, MissileClass.POINT_TO_POINT,
                32, 32, 5, 9, 32, 1, 0, 1, 0, null, null, false, 0, 0, false);
    }

    private static MissileType rock() {
        return new MissileType("missile-catapult-rock", null, MissileClass.PARABOLIC,
                32, 32, 15, 9, 8, 1, 2, 2, 0, null, null, false, 0, 0, false);
    }

    private static MissileType touchOfDeath() {
        return new MissileType("missile-touch-of-death", null,
                MissileClass.POINT_TO_POINT_WITH_HIT,
                32, 32, 30, 9, 16, 1, 1, 1, 50,
                null, null, false, 0, 0, false);
    }

    @Test
    @DisplayName("a type-15 arrow steps the native 12-pixel bresenham path from human 13")
    void aType15ArrowStepsTheNativeTwelvePixelBresenhamPathFromHuman13() {
        // Schema-1.1 projectile slot 5 on retail-human-13-idle, cycles 7..13.
        // ChonkCraft Euclidean flight at speed 32 used to cover this gap in about
        // three steps and land damage on fixture cycle 14; native type 15 is
        // still airborne through cycle 13.
        Missile shot = new Missile(arrow(), null, null, 3872, 1152, 3920, 1080);
        shot.enableBattleNetMotion(12, 0);

        int[] xs = {3872, 3884, 3884, 3896, 3908, 3908, 3920};
        int[] ys = {1152, 1140, 1128, 1116, 1104, 1092, 1080};
        assertEquals(xs[0], (int) shot.x(), "muzzle X after constructor");
        assertEquals(ys[0], (int) shot.y(), "muzzle Y after constructor");
        assertEquals(72, shot.battleNetRemaining(),
                "remaining distance is max(|48|,|72|), not the Euclidean length");
        int launchFacing = shot.direction();
        assertEquals(1, launchFacing, "the constructor aims north-east");
        assertFalse(shot.hasArrived(), "constructor does not detonate");

        for (int i = 1; i < xs.length; i++) {
            shot.step();
            assertEquals(xs[i], (int) shot.x(),
                    "native X after motion step " + i);
            assertEquals(ys[i], (int) shot.y(),
                    "native Y after motion step " + i);
            assertEquals(launchFacing, shot.direction(),
                    "0x004101f0 must preserve projectile +0x0a on motion step " + i);
            assertFalse(shot.hasArrived(),
                    "remaining is still non-negative after step " + i
                            + "; native jge keeps the shot alive at zero");
        }
        assertEquals(0, shot.battleNetRemaining(),
                "after six steps of 12 the 72-pixel remaining word is exactly zero");

        shot.step();
        assertTrue(shot.battleNetPendingImpact(),
                "the seventh step drives remaining negative and arms action 6 without "
                        + "detonating (XHuman 2: remaining -4, target HP still full)");
        assertFalse(shot.hasArrived(), "action 6 is a separate timed pass");
        assertFalse(shot.consumeHit(), "no impact until the action-6 beat");

        shot.step();
        assertTrue(shot.hasArrived(),
                "the action-6 beat frees the shot one pass after remaining goes negative");
        assertTrue(shot.consumeHit(), "arrival owes one impact");
    }

    @Test
    @DisplayName("mobile aim jitter of minus one on the major axis shortens remaining by one pixel")
    void mobileAimJitterOfMinusOneOnTheMajorAxisShortensRemainingByOnePixel() {
        // XHuman 10 axe from (2896,1808) to farm centre (3040,1888) is
        // remaining 144. Native +0x20 starts at 143 after the constructor's
        // -3..4 aim nudge; 144 is divisible by speed 12 and needs one extra
        // step before remaining goes negative.
        Missile exact = new Missile(arrow(), null, null, 2896, 1808, 3040, 1888);
        exact.enableBattleNetMotion(12, 0);
        assertEquals(144, exact.battleNetRemaining(),
                "tile-centre aim is a pure max-axis of 144");

        Missile jittered = new Missile(arrow(), null, null, 2896, 1808, 3040, 1888);
        jittered.applyBattleNetAimJitter(-1, 1);
        jittered.enableBattleNetMotion(12, 0);
        assertEquals(143, jittered.battleNetRemaining(),
                "a -1 major-axis aim nudge yields native remaining 143");
        assertEquals(3039, (int) jittered.toX(),
                "aim X matches the native stored target word");
    }

    @Test
    @DisplayName("constructor-boundary re-aim shortens a walking-target arrow to native remaining")
    void constructorBoundaryReAimShortensAWalkingTargetArrowToNativeRemaining() {
        // XHuman 12 archer 150→grunt 152. Presentation allocated the missile
        // with grunt centre 782; the grunt walked east before FUN_0040fb10.
        // Stale aim + jitter (-4,+4) made rem 134 and freed fixture 36; native
        // re-aims at OP10 to 785, same jitter, rem 131, free fixture 35.
        Missile stale = new Missile(arrow(), null, null, 912, 1904, 782, 1936);
        stale.applyBattleNetAimJitter(-4, 4);
        stale.enableBattleNetMotion(12, 0);
        assertEquals(134, stale.battleNetRemaining(),
                "presentation-frame aim is three pixels long on the major axis");
        assertEquals(778, (int) stale.toX(),
                "stale aim after -4 jitter");

        Missile live = new Missile(arrow(), null, null, 912, 1904, 782, 1936);
        live.setBattleNetAim(785, 1936);
        live.applyBattleNetAimJitter(-4, 4);
        live.enableBattleNetMotion(12, 0);
        assertEquals(131, live.battleNetRemaining(),
                "OP10 re-aim yields native remaining 131");
        assertEquals(781, (int) live.toX(),
                "live aim after -4 jitter matches sealed slot-6 aim word");
    }

    @Test
    @DisplayName("a type-13 rock uses speed eight and a ninety-six pixel minimum flight")
    void aType13RockUsesSpeedEightAndANinetySixPixelMinimumFlight() {
        // Table 0x00494e0c type 13 is speed 8; 0x00494e6c factor 3 becomes
        // min flight 96 after the constructors' shl 5. A short 40-pixel throw
        // would otherwise land in five steps and is forced to thirteen.
        Missile shortThrow = new Missile(rock(), null, null, 0, 0, 40, 0);
        shortThrow.enableBattleNetMotion(8, 96);
        assertEquals(96, shortThrow.battleNetRemaining(),
                "max-axis 40 is raised to the type-13 minimum of 96");

        int steps = 0;
        while (!shortThrow.hasArrived() && steps < 40) {
            shortThrow.step();
            steps++;
        }
        // 96/8 = 12 motion steps to rem 0, 13th makes rem -8 and arms
        // action 6; parabolic wait 5 frees on the 18th pass (Human 13 rock
        // remaining -5 at fixture 30, free at 35).
        assertEquals(18, steps,
                "96 pixels at 8 per step go negative on the thirteenth update "
                        + "and parabolic action-6 frees five passes later");
        assertEquals(104, (int) shortThrow.x(),
                "the rock overshoots the aim by one speed step when remaining breaks");

        Missile longThrow = new Missile(rock(), null, null, 0, 0, 200, 0);
        longThrow.enableBattleNetMotion(8, 96);
        assertEquals(200, longThrow.battleNetRemaining(),
                "a throw already past the minimum keeps its max-axis distance");
        steps = 0;
        while (!longThrow.hasArrived() && steps < 50) {
            longThrow.step();
            steps++;
        }
        assertEquals(31, steps,
                "200 pixels at 8 per step cross on the twenty-sixth update and "
                        + "free five action-6 passes later on the thirty-first");
    }

    @Test
    @DisplayName("a parabolic rock draws retail's five visual arc phases")
    void aParabolicRockDrawsRetailsFiveVisualArcPhases() {
        // 0x00410260 indexes 0x0049067c = 0,5,10,5,0. Java stores the
        // animation row apart from the five facings, hence 0,1,2,1,0 here.
        Missile shot = new Missile(rock(), null, null, 0, 0, 96, 0);
        shot.enableBattleNetMotion(8, 96);
        int launchFacing = shot.direction();
        assertEquals(2, launchFacing, "the constructor aims east");
        int[] expected = {0, 0, 1, 1, 2, 2, 2, 1, 1, 0};
        for (int row : expected) {
            shot.step();
            assertEquals(row, shot.frame(),
                    "wrong retail parabolic animation row at remaining "
                            + shot.battleNetRemaining());
            assertEquals(0, (int) shot.y(),
                    "retail's visual arc must not move the impact coordinate");
            assertEquals(launchFacing, shot.direction(),
                    "0x00410260 changes +0x09 frame but never +0x0a facing");
        }
    }

    @Test
    @DisplayName("a catapult rock holds five action-6 visits before free")
    void aCatapultRockHoldsFiveActionSixVisitsBeforeFree() {
        // Human 13 slot 3: remaining goes negative at fixture 30 while
        // knight 1490 stays at 77 HP through 34; the slot frees and HP drops
        // only at 35. Point-to-point and other parabolic types still free
        // on the next pass (XHuman 10 small-cannon splash).
        Missile shot = new Missile(rock(), null, null, 0, 0, 40, 0);
        shot.enableBattleNetMotion(8, 96);
        int armStep = -1;
        int freeStep = -1;
        for (int step = 1; step <= 40; step++) {
            shot.step();
            if (armStep < 0 && shot.battleNetPendingImpact()) {
                armStep = step;
            }
            if (shot.hasArrived()) {
                freeStep = step;
                break;
            }
        }
        assertTrue(armStep > 0, "rock must arm action 6 when remaining goes negative");
        assertTrue(freeStep > 0, "rock must eventually free");
        assertEquals(5, freeStep - armStep,
                "catapult-rock action-6 must free five visits after arming; "
                        + "armed on step " + armStep + " freed on " + freeStep);
        assertTrue(shot.consumeHit(), "freeing owes one impact");
    }

    @Test
    @DisplayName("a parabolic small-cannon frees on the next action-6 visit")
    void aParabolicSmallCannonFreesOnTheNextActionSixVisit() {
        MissileType cannon = new MissileType("missile-small-cannon", null,
                MissileClass.PARABOLIC, 32, 32, 1, 5, 16, 1, 2, 2, 0,
                null, null, false, 0, 0, false);
        Missile shot = new Missile(cannon, null, null, 0, 0, 64, 0);
        shot.enableBattleNetMotion(16, 64);
        int armStep = -1;
        int freeStep = -1;
        for (int step = 1; step <= 20; step++) {
            shot.step();
            if (armStep < 0 && shot.battleNetPendingImpact()) {
                armStep = step;
            }
            if (shot.hasArrived()) {
                freeStep = step;
                break;
            }
        }
        assertTrue(armStep > 0, "cannon must arm action 6");
        assertEquals(1, freeStep - armStep,
                "small-cannon must keep one-pass action-6; XHuman 10 splash "
                        + "landed four cycles late under a blanket rock hold");
    }

    @Test
    @DisplayName("a point-to-point-with-hit shot animates action 6 before damage")
    void aPointToPointWithHitShotAnimatesActionSixBeforeDamage() {
        // XHuman 2 native type 10, slot 3: constructed at fixture 544 with
        // remaining 129, crosses to -3 at 555, then displays flattened frames
        // 0,5,10,15,20,25 at three-visit cadence and frees at 571. Java stores
        // those five-facing sheet positions as animation rows 0..5.
        Missile shot = new Missile(touchOfDeath(), null, null,
                2032, 1936, 1972, 2065);
        shot.enableBattleNetMotion(12, 0);

        for (int step = 1; step <= 10; step++) {
            shot.step();
            assertEquals(0, shot.frame(),
                    "point-to-point-with-hit stays on row zero during flight");
        }
        assertEquals(9, shot.battleNetRemaining());

        shot.step();
        assertEquals(-3, shot.battleNetRemaining());
        assertTrue(shot.battleNetPendingImpact(),
                "negative remaining arms the separate hit animation");
        assertEquals(16, shot.battleNetImpactWait(),
                "six rows use the immediate first beat plus five three-visit holds");
        assertFalse(shot.consumeHit(), "arming action 6 does not deal damage");

        int[] expectedRows = {1, 1, 1, 2, 2, 2, 3, 3, 3,
                4, 4, 4, 5, 5, 5};
        for (int row : expectedRows) {
            shot.step();
            assertFalse(shot.hasArrived(),
                    "the projectile stays live through its final visible row");
            assertEquals(row, shot.frame());
            assertFalse(shot.consumeHit(), "visible impact rows do not damage early");
        }

        shot.step();
        assertTrue(shot.hasArrived(), "the visit after the final row frees the shot");
        assertTrue(shot.consumeHit(), "freeing the animated impact owes one hit");
    }

    @Test
    @DisplayName("ordinary chonkcraft flight is unchanged without the battle.net motion switch")
    void ordinaryChonkCraftFlightIsUnchangedWithoutTheBattleNetMotionSwitch() {
        Missile shot = new Missile(arrow(), null, null, 0, 0, 160, 0);
        assertFalse(shot.battleNetMotion(), "fresh missiles stay on the ChonkCraft path");
        shot.step();
        assertEquals(0, shot.x(), "MissileInitMove still burns the first action");
        shot.step();
        assertEquals(32, shot.x(), "scripted arrow speed 32 still applies");
    }
}
