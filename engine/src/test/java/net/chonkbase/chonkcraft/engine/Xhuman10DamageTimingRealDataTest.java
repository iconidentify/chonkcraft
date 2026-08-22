package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * XHuman 10's cycle-52 and cycle-54 HP drops on the orc grunt that
 * opened at 78,93.
 *
 * <p>Authenticated campaign-1800 fixture {@code retail-xhuman-10-idle}:
 * grunt 1471 starts Still at 78,93 and arrives on 81,90 at fixture 41.
 * Archer 1470 at 84,94 waits through fixture 25 and opens on its own Still
 * marker at 26; its type-15 arrow frees at fixture 52
 * (60 to 54). Footman 1479 on 82,91
 * is already on stationary Attack from the arrival and lands opcode ten
 * at fixture 54 (54 to 46). The case's coarse first divergence after the
 * route-ownership wave is this second blow at cycle 54.
 */
class Xhuman10DamageTimingRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 10's opening grunt is hurt on cycle 54")
    void xhuman10sOpeningGruntIsHurtOnCycle54() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        Unit grunt = unitAt(world, "unit-grunt", 78, 93);
        Unit footman = unitAt(world, "unit-footman", 82, 91);
        Unit archer = unitAt(world, "unit-archer", 84, 94);
        assertNotNull(grunt, "XHuman 10 has no grunt on 78,93");
        assertNotNull(footman, "XHuman 10 has no footman on 82,91");
        assertNotNull(archer, "XHuman 10 has no archer on 84,94");
        int opened = grunt.hitPoints();

        // Two HandleEachCycle warmup ticks precede fixture cycle 1 -- the
        // same boundary EngineTrace and the Human 12 playability click use.
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Integer hpAt52 = null;
        Integer hpAt54 = null;
        Unit.Order archerOrderAt25 = null;
        Unit.Order archerOrderAt26 = null;
        Unit.Order footmanOrderAt54 = null;
        while (world.cycle() < 56) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 25) {
                archerOrderAt25 = archer.order();
            }
            if (fixture == 26) {
                archerOrderAt26 = archer.order();
            }
            if (fixture == 52) {
                hpAt52 = grunt.hitPoints();
            }
            if (fixture == 54) {
                hpAt54 = grunt.hitPoints();
                footmanOrderAt54 = footman.order();
            }
        }

        assertEquals(Unit.Order.STILL, archerOrderAt25,
                "native waits for the archer's own cycle-26 Still marker");
        assertEquals(Unit.Order.ATTACK, archerOrderAt26,
                "native promotes the archer on its cycle-26 Still marker");
        assertTrue(hpAt52 != null && hpAt52 < opened,
                "retail's first blow lands on cycle 52; the grunt is still at "
                        + hpAt52 + " of " + opened);
        assertTrue(hpAt54 != null && hpAt54 < hpAt52,
                "retail's second blow lands on cycle 54; the grunt is still at "
                        + hpAt54 + " of " + hpAt52);
        assertEquals(Unit.Order.ATTACK, footmanOrderAt54,
                "retail's footman stays on Attack at cycle 54, not "
                        + footmanOrderAt54);
        assertEquals(81, grunt.tileX(),
                "the grunt must stand on 81,90 when the second blow lands");
        assertEquals(90, grunt.tileY(),
                "the grunt must stand on 81,90 when the second blow lands");
    }

    @Test
    @DisplayName("xhuman 10's splash-help knight defers SyncRand to Attack OP0")
    void xhuman10SplashHelpKnightDefersSyncRandUntilAttackOp0() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        Unit guard = unitAt(world, "unit-footman", 98, 56);
        Unit knight = unitAt(world, "unit-knight", 84, 89);
        assertNotNull(guard,
                "XHuman 10 has no stationary footman on 98,56");
        assertNotNull(knight,
                "XHuman 10 has no splash-help knight on 84,89");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Integer seedAt57 = null;
        Integer seedAt58 = null;
        Integer seedAt60 = null;
        Integer knightSequenceAt58 = null;
        Integer knightTimerAt58 = null;
        Integer knightSyncAt58 = null;
        Boolean knightPendingAt58 = null;
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 61) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 57) {
                seedAt57 = world.randomSeed();
            } else if (fixture == 58) {
                seedAt58 = world.randomSeed();
                knightSequenceAt58 = knight.battleNetSequenceOffset();
                knightTimerAt58 = knight.battleNetAnimationTimer();
                knightSyncAt58 = knight.battleNetMeleeSyncRemaining();
                knightPendingAt58 = knight.battleNetPendingMeleeSyncRand();
            } else if (fixture == 60) {
                seedAt60 = world.randomSeed();
            }
        }

        assertEquals(0x967eb0e7, seedAt57,
                "the two earlier live footmen own the first two native draws");
        assertEquals(0x2781e494, seedAt58,
                "the stationary footman owns native fixture 58's sole draw");
        assertEquals(Unit.Order.ATTACK, guard.order(),
                "the person guard must still be on native action 16");
        assertTrue(guard.battleNetStationaryAttack(),
                "the 98,56 footman is the stationary action-16 witness");
        assertEquals(1922, knightSequenceAt58,
                "the residual arrival opens the knight's Attack start");
        assertEquals(3, knightTimerAt58,
                "native keeps Attack construction 3,2,1 after the arrival");
        assertEquals(0, knightSyncAt58,
                "the knight must not arm its melee cadence on residual settle");
        assertEquals(Boolean.TRUE, knightPendingAt58,
                "the knight retains the draw until Attack OP0");
        assertEquals(0xc46b9b3d, seedAt60,
                "the next mobile grunt owns fixture 60's draw");
        assertEquals(0xf94bdf32, world.randomSeed(),
                "the knight finally debits on native Attack OP0 at fixture 61");
        assertTrue(knight.battleNetMeleeSyncRemaining() > 0,
                "Attack OP0 arms the knight's twenty-five-cycle cadence");
    }

    @Test
    @DisplayName("xhuman 10's refused knight reconstructs Attack before walking")
    void xhuman10RefusedKnightReconstructsAttackBeforeWalking() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit knight = unitAt(world, "unit-knight", 84, 88);
        assertNotNull(knight,
                "XHuman 10 has no refused knight on 84,88 after warmup");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 67) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture >= 63 && fixture <= 65) {
                assertEquals(84, knight.tileX(),
                        "fixture " + fixture + " native holds the refused chase "
                                + "through Attack 3,2,1; id=" + knight.id()
                                + " stage="
                                + knight.battleNetAttackRefusalRecoveryStage()
                                + " seq=" + knight.battleNetSequenceOffset()
                                + "/" + knight.battleNetAnimationTimer());
                assertEquals(88, knight.tileY());
                assertEquals(1922, knight.battleNetSequenceOffset(),
                        "the refusal wake reconstructs the knight Attack start");
                assertEquals(66 - fixture, knight.battleNetAnimationTimer(),
                        "Attack construction must count 3,2,1");
            } else if (fixture == 66) {
                assertEquals(84, knight.tileX(),
                        "native plans the replacement route before walking it");
                assertEquals(88, knight.tileY());
                assertEquals(1874, knight.battleNetSequenceOffset(),
                        "the timer-one boundary hands ownership back to Move");
                assertEquals(1, knight.battleNetAnimationTimer());
                assertNotNull(knight.target());
                assertEquals(82, knight.target().tileX(),
                        "the plan-only visit names the nearby grunt");
                assertEquals(88, knight.target().tileY());
            }
        }

        assertEquals(83, knight.tileX(),
                "native first-steps west only on fixture 67");
        assertEquals(88, knight.tileY());
        assertTrue(knight.isMoving(),
                "the replacement chase must remain live after the deferred step");
    }

    @Test
    @DisplayName("xhuman 10's commanded knight pays Attack construction before retarget")
    void xhuman10CommandedKnightHoldsBeforeItsAutomaticRetarget() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        Unit knight = unitAt(world, "unit-knight", 84, 91);
        assertNotNull(knight,
                "XHuman 10 has no commanded knight on 84,91");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 64) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture >= 61 && fixture <= 63) {
                assertEquals(83, knight.tileX(),
                        "native retains the settled route through Attack 3,2,1");
                assertEquals(90, knight.tileY(),
                        "native retains the settled route through Attack 3,2,1");
                assertEquals(1922, knight.battleNetSequenceOffset(),
                        "the commanded-to-auto handoff belongs to Attack start");
                assertEquals(64 - fixture, knight.battleNetAnimationTimer(),
                        "Attack construction counts 3,2,1 before retargeting");
            }
        }

        assertEquals(82, knight.tileX(),
                "native first-steps northwest only after the construction hold");
        assertEquals(89, knight.tileY(),
                "native first-steps northwest only after the construction hold");
        assertTrue(knight.isMoving(),
                "fixture 64 owns the replacement chase residual");
    }

    @Test
    @DisplayName("xhuman 10's retained-route knight opens and debits on residual settle")
    void xhuman10RetainedRouteKnightOpensAndDebitsOnResidualSettle() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slot 1480. Its splash-help handoff retains route-buffer
        // cursor one while replacing the catapult with the nearby grunt.
        Unit knight = unitAt(world, "unit-knight", 84, 91);
        assertNotNull(knight, "XHuman 10 has no retained-route knight on 84,91");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 76) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 75) {
                assertEquals(0xd9e2b600, world.randomSeed(),
                        "native has not paid slot 1480's table-0x27 draw yet");
                assertEquals(1, knight.pathLength(),
                        "the final cached heading survives through the residual");
                assertEquals(2, knight.battleNetPathStepsTaken(),
                        "the replacement route retains native cursor-two provenance");
                assertEquals(1917, knight.battleNetSequenceOffset());
                assertEquals(1, knight.battleNetAnimationTimer());
            }
        }

        assertEquals(0x9cfbae39, world.randomSeed(),
                "fixture 76 pays the retained-route knight's synchronized draw");
        assertEquals(1923, knight.battleNetSequenceOffset(),
                "native residual-opens immediately past Attack OP0");
        assertEquals(1, knight.battleNetAnimationTimer());
        assertEquals(0, knight.pathLength(),
                "native parks route cursor 20 on the settle visit");
        assertTrue(!knight.battleNetPendingMeleeSyncRand(),
                "the first melee draw must no longer be pending");
        assertTrue(knight.battleNetMeleeSyncRemaining() > 0,
                "the draw arms the recurring melee cadence");
    }

    @Test
    @DisplayName("xhuman 10's replacement refusals preserve native RNG and cavalry flow")
    void xhuman10ReplacementRefusalsPreserveNativeRngAndCavalryFlow() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native 1486 is the blocked replacement-ray witness. Native 1485
        // proves that already-paid Attack construction is not charged again
        // when its first replacement stride settles. Native 1471 receives the
        // projectile whose damage roll follows both transitions.
        Unit refusedGrunt = unitAt(world, "unit-grunt", 78, 89);
        Unit cavalry = unitAt(world, "unit-knight", 84, 90);
        Unit hurtGrunt = unitAt(world, "unit-grunt", 78, 93);
        assertNotNull(refusedGrunt);
        assertNotNull(cavalry);
        assertNotNull(hurtGrunt);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 81) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 77) {
                assertEquals(0xfed4b178, world.battleNetRandomSeed(),
                        "the blocked replacement grunt must not steal the "
                                + "following projectile's async draw");
                assertEquals(80, refusedGrunt.tileX());
                assertEquals(90, refusedGrunt.tileY());
                assertEquals(2482, refusedGrunt.battleNetSequenceOffset(),
                        "native keeps the grunt on Move during the refusal band");
                assertEquals(10, refusedGrunt.battleNetAnimationTimer(),
                        "fixture 77 is the tenth count of the retained Move band");
                assertEquals(2, refusedGrunt.pathLength(),
                        "the blocked replacement ray remains cached");
            } else if (fixture == 79) {
                assertEquals(0x9ca2a85c, world.battleNetRandomSeed(),
                        "native async ownership must remain aligned through "
                                + "the footman's damage roll");
                assertEquals(42, hurtGrunt.hitPoints(),
                        "the aligned footman roll deals native four damage");
            } else if (fixture == 80) {
                assertEquals(83, cavalry.tileX());
                assertEquals(90, cavalry.tileY());
                assertTrue(cavalry.battleNetSequenceOffset() >= 1874
                                && cavalry.battleNetSequenceOffset() < 1922,
                        "the settled route boundary must remain owned by Move");
                assertEquals(0, cavalry.battleNetOrderDelay(),
                        "already-paid Attack construction must not buy a "
                                + "second residual hold");
            }
        }

        assertEquals(82, cavalry.tileX(),
                "native redraws and first-steps west on fixture 81");
        assertEquals(90, cavalry.tileY());
        assertTrue(cavalry.isMoving(),
                "the paid-construction replacement chase remains live");
        assertTrue(cavalry.battleNetSequenceOffset() >= 1874
                        && cavalry.battleNetSequenceOffset() < 1922,
                "fixture 81 advances directly through the knight Move body");
    }

    @Test
    @DisplayName("xhuman 10's residual retarget leaves its dying quarry")
    void xhuman10ResidualRetargetLeavesItsDyingQuarry() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        Unit grunt = unitAt(world, "unit-grunt", 78, 87);
        Unit footman = unitAt(world, "unit-footman", 82, 88);
        Unit knight = unitAt(world, "unit-knight", 84, 89);
        assertNotNull(grunt, "XHuman 10 has no grunt on 78,87");
        assertNotNull(footman, "XHuman 10 has no footman on 82,88");
        assertNotNull(knight, "XHuman 10 has no knight on 84,89");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 54) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 50) {
                assertSame(footman, grunt.target(),
                        "the committed residual still owns the dying footman");
                assertEquals(Unit.Order.DYING, footman.order());
            } else if (fixture == 51) {
                assertSame(knight, grunt.target(),
                        "retail OP0 replaces the corpse with the live knight");
                assertEquals(2539, grunt.battleNetSequenceOffset(),
                        "retail parks on the grunt's Attack start");
                assertEquals(3, grunt.battleNetAnimationTimer(),
                        "the replacement pays Attack construction 3,2,1");
                assertEquals(81, grunt.tileX());
                assertEquals(89, grunt.tileY());
            } else if (fixture == 53) {
                assertEquals(81, grunt.tileX());
                assertEquals(89, grunt.tileY(),
                        "the grunt must hold through construction fixture 53");
            }
        }

        assertSame(knight, grunt.target());
        assertEquals(82, grunt.tileX());
        assertEquals(88, grunt.tileY(),
                "retail resumes the live chase NE on fixture 54");
        assertTrue(grunt.isMoving(),
                "the grunt must be walking, not swinging at the corpse");
    }

    @Test
    @DisplayName("xhuman 10's settled grunt replaces its equal offered target")
    void xhuman10SettledGruntReplacesItsEqualOfferedTarget() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slot 1495: the western grunt which first chases the footman
        // on 82,88 and then the knight which opened on 84,89.
        Unit grunt = unitAt(world, "unit-grunt", 78, 87);
        assertNotNull(grunt, "XHuman 10 has no western grunt on 78,87");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 69) {
            mission.tick();
        }
        Unit replacement = unitAt(world, "unit-knight", 83, 88);
        Unit incumbent = unitAt(world, "unit-knight", 83, 89);
        assertNotNull(replacement,
                "native slot 1493 must be adjacent on 83,88 at fixture 69");
        assertNotNull(incumbent,
                "native slot 1489 must remain on 83,89 at fixture 69");
        assertSame(incumbent, grunt.target(),
                "fixture 69 still owns the offered knight and cached heading");
        assertEquals(1, grunt.pathLength(),
                "fixture 69 retains the last heading of the old chase");

        mission.tick();

        assertSame(replacement, grunt.target(),
                "native OP0 free-scan replaces the equal offered knight at fixture 70");
        assertEquals(0, grunt.pathLength(),
                "SetAutoTarget clears the stale heading when the goal changes");
        assertEquals(0xd9e2b600, world.randomSeed(),
                "the first live in-range callback pays native FUN_004234b0");
        assertTrue(grunt.battleNetMeleeSyncRemaining() > 0,
                "the successful callback arms the repeating melee cadence");
    }

    @Test
    @DisplayName("xhuman 10's blocked replacement route pays one refusal band")
    void xhuman10BlockedReplacementRoutePaysOneRefusalBand() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slot 1475: the southern grunt which opens on 76,92.
        Unit grunt = unitAt(world, "unit-grunt", 76, 92);
        assertNotNull(grunt, "XHuman 10 has no southern grunt on 76,92");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 75) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 57) {
                assertEquals(79, grunt.tileX());
                assertEquals(90, grunt.tileY());
                Unit blocker = unitAt(world, "unit-grunt", 80, 90);
                assertNotNull(blocker,
                        "native slot 1486 is crossing the replacement ray");
                assertEquals(0, blocker.battleNetCollisionCounter(),
                        "the moving blocker remains cooperative in FUN_004379e0");
                assertNotNull(grunt.target());
                assertEquals(83, grunt.target().tileX());
                assertEquals(89, grunt.target().tileY());
                assertEquals(4, grunt.pathLength(),
                        "retail keeps the blocked E,NE,E,E replacement ray");
                assertEquals(Direction.fromDelta(1, 0), grunt.peekHeading(),
                        "the allied blocker refuses the first east heading");
                assertEquals(2482, grunt.battleNetSequenceOffset(),
                        "the refusal owns the grunt Move start");
                assertEquals(15, grunt.battleNetAnimationTimer(),
                        "a newly blocked replacement ray pays one full Move band");
            } else if (fixture == 64) {
                assertEquals(83, grunt.target().tileX(),
                        "a better scan result remains banked during the band");
                assertEquals(89, grunt.target().tileY());
                assertEquals(2482, grunt.battleNetSequenceOffset());
                assertEquals(8, grunt.battleNetAnimationTimer());
            } else if (fixture == 72) {
                assertEquals(79, grunt.tileX(),
                        "the timer-one wake opens Attack instead of walking");
                assertEquals(90, grunt.tileY());
                assertEquals(2539, grunt.battleNetSequenceOffset(),
                        "retail pays Attack construction after the refusal band");
                assertEquals(3, grunt.battleNetAnimationTimer());
            }
        }

        assertEquals(82, grunt.target().tileX(),
                "the banked replacement installs after Attack 3,2,1");
        assertEquals(89, grunt.target().tileY());
        assertEquals(3, grunt.pathLength(),
                "retail installs the replacement NE,E,E route without stepping it");
        assertEquals(Direction.fromDelta(1, -1), grunt.peekHeading());
        assertEquals(2482, grunt.battleNetSequenceOffset());
        assertEquals(15, grunt.battleNetAnimationTimer(),
                "the first replacement probe retains native Move ownership");
    }

    @Test
    @DisplayName("xhuman 10's paid refusal wake routes through departing allies")
    void xhuman10PaidRefusalWakeRoutesThroughDepartingAllies() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slot 1490: the grunt which opens on 76,88, parks its E
        // residual at fixture 41 and pays the complete cooperative-refusal
        // band through fixture 56.
        Unit grunt = unitAt(world, "unit-grunt", 76, 88);
        assertNotNull(grunt, "XHuman 10 has no western grunt on 76,88");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 57) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 42) {
                assertEquals(78, grunt.tileX());
                assertEquals(88, grunt.tileY());
                assertEquals(2, grunt.pathLength(),
                        "the parked replacement keeps its two headings");
            } else if (fixture == 56) {
                assertEquals(78, grunt.tileX(),
                        "the paid band holds the grunt through timer one");
                assertEquals(88, grunt.tileY());
                assertTrue(grunt.battleNetRefusalHold(),
                        "the wake retains refusal provenance for target routing");
            }
        }

        assertEquals(79, grunt.tileX(),
                "retail first-steps east on the refusal wake");
        assertEquals(88, grunt.tileY(),
                "the departing ally is soft for the replacement ray");
        assertTrue(grunt.isMoving());
        assertNotNull(grunt.target());
        assertEquals("unit-knight", grunt.target().type().ident());
        assertEquals(83, grunt.target().tileX());
        assertEquals(89, grunt.target().tileY());
        assertEquals(2, grunt.pathLength(),
                "E,E,SE is installed and the first east heading is spent");
        assertEquals(Direction.fromDelta(1, 0), grunt.peekHeading(),
                "the second native heading remains east");
        assertEquals(0, grunt.battleNetCollisionCounter(),
                "a successful wake clears the paid collision provenance");
        assertEquals(0, grunt.battleNetRefusals());
    }

    @Test
    @DisplayName("xhuman 10's settled replacement keeps its blocked route")
    void xhuman10SettledReplacementKeepsItsBlockedRoute() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slot 1497: the northern grunt whose east residual settles
        // as its target changes from the footman to the knight. Native draws
        // SE,SE,NE through the departing allied grunt, restores occupancy,
        // then refuses that first SE without discarding the route.
        Unit grunt = unitAt(world, "unit-grunt", 76, 86);
        assertNotNull(grunt, "XHuman 10 has no northern grunt on 76,86");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 59) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture >= 56) {
                assertEquals(79, grunt.tileX(),
                        "the blocked replacement must not sidestep at fixture "
                                + fixture);
                assertEquals(87, grunt.tileY());
                assertNotNull(grunt.target());
                assertEquals("unit-knight", grunt.target().type().ident());
                assertEquals(83, grunt.target().tileX());
                assertEquals(89, grunt.target().tileY());
                assertEquals(3, grunt.pathLength(),
                        "retail retains the SE,SE,NE replacement ray");
                assertEquals(Direction.fromDelta(1, 1), grunt.peekHeading());
                assertEquals(2482, grunt.battleNetSequenceOffset(),
                        "the cooperative refusal owns Move start");
                assertEquals(71 - fixture, grunt.battleNetAnimationTimer(),
                        "the refusal counts down from native timer fifteen");
            }
        }
    }

    @Test
    @DisplayName("xhuman 10's archer keeps Attack while the melee arrival owns SyncRand")
    void xhuman10ArcherRetargetOwnsItsAttackLoopWrap() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slot 1473 wraps and retargets on fixture 90, but bow +0xb and
        // +0x7a refresh without touching SyncRand (XHuman 9/12 prove that
        // independently). The same fixture's sole 0x4234CD draw belongs to
        // native grunt 1477 / Java 123: its one cached heading survives
        // Attack 3,2,1 and is parked when OP0 accepts the first live arrival.
        Unit archer = unitAt(world, "unit-archer", 84, 93);
        Unit arrival = unitById(world, 123);
        assertNotNull(archer, "XHuman 10 has no archer on 84,93");
        assertNotNull(arrival, "XHuman 10 has no Java-paired grunt 1477");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 89) {
            mission.tick();
        }
        assertEquals(Unit.Order.ATTACK, archer.order());
        assertEquals(2054, archer.battleNetSequenceOffset());
        assertEquals(1, archer.battleNetAnimationTimer());
        assertEquals(0, archer.battleNetMeleeSyncRemaining(),
                "bow animation floors are not table-0x27 melee cadence");
        assertTrue(arrival.battleNetPendingMeleeSyncRand());
        assertEquals(1, arrival.pathLength());
        assertEquals(2539, arrival.battleNetSequenceOffset());
        assertEquals(1, arrival.battleNetAnimationTimer());
        assertEquals(0x31dff4f5, world.randomSeed());

        mission.tick();
        assertEquals(0x237c228a, world.randomSeed(),
                "grunt 1477's path-one OP0 owns caller 0x4234CD at fixture 90");
        assertTrue(!arrival.battleNetPendingMeleeSyncRand());
        assertEquals(0, arrival.pathLength());
        assertEquals(2540, arrival.battleNetSequenceOffset());
        assertEquals(1, arrival.battleNetAnimationTimer());
        assertEquals(Unit.Order.ATTACK, archer.order(),
                "the fresh ranged Attack constructor owns the retarget visit");
        assertNotNull(archer.target());
        assertEquals(81, archer.target().tileX());
        assertEquals(90, archer.target().tileY());
        assertEquals(2039, archer.battleNetSequenceOffset());
        assertEquals(3, archer.battleNetAnimationTimer());

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 93) {
            mission.tick();
        }
        assertEquals(Unit.Order.ATTACK, archer.order());
        assertEquals(2039, archer.battleNetSequenceOffset());
        assertEquals(63, archer.battleNetAnimationTimer(),
                "the new ranged swing enters native's committed OP0 hold");

        mission.tick();
        assertEquals(Unit.Order.ATTACK, archer.order(),
                "action 16 remains Attack throughout the committed OP0 hold");
        assertEquals(2039, archer.battleNetSequenceOffset());
        assertEquals(62, archer.battleNetAnimationTimer(),
                "native owns the first quiet committed-hold visit at fixture 94");
    }

    @Test
    @DisplayName("xhuman 10's recurring ranged retarget renews the OP0 cadence")
    void xhuman10RecurringRangedRetargetRenewsOp0Cadence() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native archer 1502 / Java 98 enters its first 63-count ranged hold
        // at fixture 26. When that hold expires it changes grunts at fixture
        // 89, reconstructs Attack 3,2,1, and renews the same hold at 92.
        // Carrying the old hold-active bit across the retarget let Java enter
        // windup and create a phantom arrow at fixture 103. Its three async
        // constructor draws then changed footman 121's fixture-104 damage to
        // grunt 1471 / Java 129 from native eight to four.
        Unit archer = unitAt(world, "unit-archer", 84, 85);
        Unit grunt = unitAt(world, "unit-grunt", 78, 93);
        assertNotNull(archer, "XHuman 10 has no archer on 84,85");
        assertNotNull(grunt, "XHuman 10 has no opening grunt on 78,93");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 89) {
            mission.tick();
        }
        assertEquals(2039, archer.battleNetSequenceOffset());
        assertEquals(3, archer.battleNetAnimationTimer(),
                "the second ranged retarget restarts Attack construction");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 92) {
            mission.tick();
        }
        assertEquals(2039, archer.battleNetSequenceOffset());
        assertEquals(63, archer.battleNetAnimationTimer(),
                "the replacement swing must renew native's ranged OP0 hold");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 104) {
            mission.tick();
        }
        assertEquals(2039, archer.battleNetSequenceOffset(),
                "the phantom fixture-103 arrow must never enter windup");
        assertEquals(51, archer.battleNetAnimationTimer());
        assertEquals(34, grunt.hitPoints(),
                "fixture-104 melee must retain BNE's eight-damage async roll");
    }

    @Test
    @DisplayName("xhuman 10's paid route settles through Attack before its next step")
    void xhuman10PaidRouteResidualReopensAttackBeforeNextHeading() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native slot 1497. A collision-backed tail-wrap route has three bytes
        // left when its first residual settles. Attack owns 90..92; Move spends
        // the retained southeast heading only on fixture 93.
        Unit grunt = unitAt(world, "unit-grunt", 76, 86);
        assertNotNull(grunt, "XHuman 10 has no northern grunt on 76,86");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 89) {
            mission.tick();
        }
        assertEquals(78, grunt.tileX());
        assertEquals(88, grunt.tileY());
        assertEquals(2, grunt.offsetX());
        assertEquals(-2, grunt.offsetY());

        mission.tick();
        assertEquals(78, grunt.tileX());
        assertEquals(88, grunt.tileY());
        assertEquals(0, grunt.offsetX());
        assertEquals(0, grunt.offsetY());
        assertEquals(3, grunt.pathLength(),
                "Attack construction retains the native SE,SE,NE route");
        assertEquals(2539, grunt.battleNetSequenceOffset());
        assertEquals(3, grunt.battleNetAnimationTimer());

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 92) {
            mission.tick();
        }
        assertEquals(78, grunt.tileX());
        assertEquals(88, grunt.tileY());
        assertEquals(2539, grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer());

        mission.tick();
        assertEquals(79, grunt.tileX());
        assertEquals(89, grunt.tileY());
        assertTrue(grunt.isMoving(),
                "Move spends the retained southeast heading after 3,2,1");
        assertEquals(2, grunt.pathLength());
    }

    @Test
    @DisplayName("xhuman 10's refused melee tail opens past attack op0")
    void xhuman10RefusedMeleeTailOpensPastAttackOp0() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native 1538 / Java 62 begins at 91,58. Its crowded approach is
        // refused once, then a one-heading southeast recovery route settles
        // adjacent to native footman 1542. That paid residual enters the ogre
        // Attack program after OP0; treating it as a fresh approach installs
        // a 23-cycle hold and suppresses the fixture-92 blow.
        Unit ogre = unitAt(world, "unit-ogre", 91, 58);
        Unit footman = unitAt(world, "unit-footman", 98, 56);
        assertNotNull(ogre, "XHuman 10 has no refusal-tail ogre on 91,58");
        assertNotNull(footman, "XHuman 10 has no stationary footman on 98,56");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 85) {
            mission.tick();
        }
        assertEquals(97, ogre.tileX());
        assertEquals(57, ogre.tileY());
        assertEquals(1, ogre.battleNetRefusals(),
                "the opening is owned by the paid refusal-recovery tail");
        assertEquals(644, ogre.battleNetSequenceOffset(),
                "native enters immediately after ogre Attack OP0");
        assertEquals(1, ogre.battleNetAnimationTimer());

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 92) {
            mission.tick();
        }
        assertEquals(658, ogre.battleNetSequenceOffset(),
                "fixture 92 consumes native ogre OP10 into its frame-35 wait");
        assertEquals(5, ogre.battleNetAnimationTimer());
        assertEquals(32, footman.hitPoints(),
                "the refusal-tail ogre must land native fixture 92's nine damage");
    }

    @Test
    @DisplayName("xhuman 10's melee loop retains its offered equal-score target")
    void xhuman10MeleeLoopRetainsItsOfferedEqualScoreTarget() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native 1542 / Java 58 begins at 98,56. At fixture 83 its completed
        // swing sees three adjacent, equally scored ogres. OfferNewTarget has
        // banked native 1543 / Java 57 at 97,56, so BNE prices that incumbent
        // before its screen-Y walk, restarts Attack construction 3,2,1, and
        // enters the 23-cycle committed hold instead of hitting ogre 1548.
        Unit footman = unitAt(world, "unit-footman", 98, 56);
        assertNotNull(footman, "XHuman 10 has no footman on 98,56");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 83) {
            mission.tick();
        }
        Unit offeredOgre = unitAt(world, "unit-ogre", 97, 56);
        Unit spatialFirstOgre = unitAt(world, "unit-ogre", 97, 55);
        assertNotNull(offeredOgre, "XHuman 10 has no offered ogre on 97,56");
        assertNotNull(spatialFirstOgre,
                "XHuman 10 has no spatial-first ogre on 97,55");
        assertSame(offeredOgre, footman.target(),
                "native's banked equal-score offer wins the tail-wrap scan");
        assertEquals(2539, footman.battleNetSequenceOffset());
        assertEquals(3, footman.battleNetAnimationTimer(),
                "native restarts footman Attack construction at fixture 83");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 86) {
            mission.tick();
        }
        assertSame(offeredOgre, footman.target());
        assertEquals(2539, footman.battleNetSequenceOffset());
        assertEquals(23, footman.battleNetAnimationTimer(),
                "the offered retarget enters the committed melee body hold");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 93) {
            mission.tick();
        }
        assertEquals(73, spatialFirstOgre.hitPoints(),
                "the discarded equal-score target must not take an early hit");
    }

    @Test
    @DisplayName("xhuman 10's paid cavalry residual retarget opens attack immediately")
    void xhuman10PaidCavalryResidualRetargetOpensAttackImmediately() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Native 1485 / Java 115 begins at 84,90. Its paid westbound route
        // finishes on fixture 93 as the dying footman ceases to be a goal.
        // CheckForTargetInRange names adjacent grunt 1477 / Java 123 and, in
        // the same callback, opens knight Attack@1922/3 and calls 0x4234CD.
        Unit knight = unitAt(world, "unit-knight", 84, 90);
        assertNotNull(knight, "XHuman 10 has no paid-residual knight on 84,90");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 93) {
            mission.tick();
        }
        Unit grunt = unitAt(world, "unit-grunt", 81, 89);
        assertNotNull(grunt, "native replacement grunt must stand on 81,89");
        assertSame(grunt, knight.target(),
                "the settled route must promote its in-range replacement");
        assertEquals(82, knight.tileX());
        assertEquals(90, knight.tileY());
        assertEquals(1922, knight.battleNetSequenceOffset(),
                "native opens fresh knight Attack construction on the settle visit");
        assertEquals(3, knight.battleNetAnimationTimer());
        assertEquals(0xaf1cf0fb, world.randomSeed(),
                "the paid arrival must debit native caller 0x4234CD at fixture 93");
        assertEquals(25, knight.battleNetMeleeSyncRemaining(),
                "the first in-range debit arms the native attack-loop cadence");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 96) {
            mission.tick();
        }
        assertEquals(1922, knight.battleNetSequenceOffset());
        assertEquals(23, knight.battleNetAnimationTimer(),
                "construction 3,2,1 enters native's committed melee body hold");
    }

    @Test
    @DisplayName("xhuman 10's long gold corridor parks its occupied cardinal tail")
    void xhuman10LongGoldCorridorParksItsOccupiedCardinalTail() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        // Authenticated native slot 1438 / Java 162. Its six-heading gold
        // prefix ends at (17,114) behind peon 1433. Retail parks the stale
        // south tail on fixture 115, refills an occupied south-east head, and
        // advances collision 1..8 before serving the complete refusal band.
        // It does not free-wake south when the old cell clears on fixture 117.
        Unit peon = unitAt(world, "unit-peon", 15, 107);
        assertNotNull(peon, "XHuman 10 has no native-slot-1438 gold peon");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 122) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture >= 115 && fixture <= 122) {
                assertEquals(17, peon.tileX());
                assertEquals(114, peon.tileY(),
                        "the occupied-prefix refusal must not free-wake south on "
                                + "fixture " + fixture);
                assertEquals(fixture - 114,
                        peon.battleNetCollisionCounter(),
                        "native increments the collision nibble on every refused "
                                + "route generation");
            }
        }

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 137) {
            mission.tick();
        }
        assertEquals(17, peon.tileX());
        assertEquals(115, peon.tileY(),
                "native wakes after the bounded band and replans south");
        assertEquals(8, peon.battleNetCollisionCounter(),
                "the paid refusal generation remains attached to the route");
    }

    private static Unit unitAt(World world, String ident, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.type() != null
                    && ident.equals(unit.type().ident())
                    && unit.tileX() == x && unit.tileY() == y) {
                return unit;
            }
        }
        return null;
    }

    private static Unit unitById(World world, int id) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }
}
