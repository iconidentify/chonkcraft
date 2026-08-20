package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
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
}
