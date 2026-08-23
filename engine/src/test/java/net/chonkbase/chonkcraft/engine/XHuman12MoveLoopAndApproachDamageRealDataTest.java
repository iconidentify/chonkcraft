package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks XHuman 12's first post-cycle-52 native movement/combat boundary. */
class XHuman12MoveLoopAndApproachDamageRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 12 pays builder, move-loop, and approach-damage cadence")
    void xhuman12PaysMoveLoopOp0AndApproachDamageHold() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx12h",
                GameData.personIn(data.campaignMap(
                        "campaigns/human-exp/levelx12h")), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit loopGrunt = unitAt(world, "unit-grunt", 26, 39);
        Unit retargetGrunt = unitAt(world, "unit-grunt", 24, 39);
        Unit approachGrunt = unitAt(world, "unit-grunt", 23, 60);
        Unit footman = unitAt(world, "unit-footman", 26, 59);
        Unit builder = unitAt(world, "unit-peon", 4, 85);
        assertNotNull(loopGrunt, "XHuman 12 has no native-slot-1494 grunt");
        assertNotNull(retargetGrunt, "XHuman 12 has no native-slot-1492 grunt");
        assertNotNull(approachGrunt, "XHuman 12 has no native-slot-1448 grunt");
        assertNotNull(footman, "XHuman 12 has no native-slot-1449 footman");
        assertNotNull(builder, "XHuman 12 has no native-slot-1376 builder");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        int loopX53 = -1;
        int loopY53 = -1;
        int loopSequence53 = -1;
        int loopX54 = -1;
        int loopY54 = -1;
        int approachTimer43 = -1;
        int footmanHp57 = -1;
        int footmanHp58 = -1;
        int builderX55 = -1;
        int loopCollision37 = -1;
        int retargetPath41 = -1;
        int retargetCollision41 = -1;
        int retargetCollision42 = -1;
        int retargetHeading42 = -1;
        int retargetDelay42 = -1;
        int retargetX56 = -1;
        int retargetY56 = -1;
        while (world.cycle() < 61) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 43) {
                approachTimer43 = approachGrunt.battleNetAnimationTimer();
            }
            if (fixture == 37) {
                loopCollision37 = loopGrunt.battleNetCollisionCounter();
            }
            if (fixture == 41) {
                retargetPath41 = retargetGrunt.pathLength();
                retargetCollision41 =
                        retargetGrunt.battleNetCollisionCounter();
            }
            if (fixture == 42) {
                retargetCollision42 =
                        retargetGrunt.battleNetCollisionCounter();
                retargetHeading42 = retargetGrunt.peekHeading();
                retargetDelay42 = retargetGrunt.battleNetOrderDelay();
            }
            if (fixture == 53) {
                loopX53 = loopGrunt.tileX();
                loopY53 = loopGrunt.tileY();
                loopSequence53 = loopGrunt.battleNetSequenceOffset();
            }
            if (fixture == 54) {
                loopX54 = loopGrunt.tileX();
                loopY54 = loopGrunt.tileY();
            }
            if (fixture == 57) {
                footmanHp57 = footman.hitPoints();
            }
            if (fixture == 58) {
                footmanHp58 = footman.hitPoints();
            }
            if (fixture == 55) {
                builderX55 = builder.tileX();
            }
            if (fixture == 56) {
                retargetX56 = retargetGrunt.tileX();
                retargetY56 = retargetGrunt.tileY();
            }
        }

        assertEquals(7, builderX55,
                "ordinary build traffic waits fourteen visits; the combat "
                        + "nearly-full exception must not delay its third tile");
        assertEquals(23, approachTimer43,
                "damaged approach must arm native's Attack body hold");
        assertEquals(2, loopCollision37,
                "the nearly-full replan must preserve native collision two");
        assertEquals(0, retargetPath41,
                "the first retarget residual must park its stale route");
        assertEquals(1, retargetCollision41,
                "the route-park visit records native collision one");
        assertEquals(2, retargetCollision42,
                "the replacement route's blocked first square records two");
        assertEquals(5, retargetHeading42,
                "collision-aware replacement routing starts southwest");
        assertEquals(14, retargetDelay42,
                "the replacement route owns retail's fifteen-count wait");
        assertEquals(26, retargetX56,
                "the stale east route must not move before fixture 56");
        assertEquals(39, retargetY56,
                "retarget recovery holds the native battle square");
        assertEquals(27, loopX53, "Move-loop goto must not step on fixture 53");
        assertEquals(39, loopY53, "Move-loop goto holds the old logical tile");
        assertEquals(2482, loopSequence53,
                "native yields on the grunt Move opening OP0");
        assertEquals(28, loopX54, "the following OP0 visit takes the next step");
        assertEquals(40, loopY54,
                "the delayed replan observes traffic and chooses southeast");
        assertEquals(60, footmanHp57,
                "the held grunt must not land Java's early fixture-53 blow");
        assertEquals(55, footmanHp58,
                "the first native damage at fixture 58 comes from the axe");
    }

    @Test
    @DisplayName("approach damage expires when a live route tail advances")
    void oldApproachDamageDoesNotFreezeAMuchLaterAttack() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit longRouteGrunt = unitById(world, 91);
        Unit guardTower = unitById(world, 115);
        assertNotNull(longRouteGrunt);
        assertNotNull(guardTower);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 20) {
            mission.tick();
        }
        assertEquals(47, longRouteGrunt.hitPoints());
        assertEquals(true, longRouteGrunt.battleNetAttackOp0Damaged(),
                "the direct hit initially belongs to this approach element");

        while (fixtureCycle(world) < 23) {
            mission.tick();
        }
        assertEquals(false, longRouteGrunt.battleNetAttackOp0Damaged(),
                "committing a step with a live tail starts a new damage generation");

        while (fixtureCycle(world) < 176) {
            mission.tick();
        }

        mission.tick();
        assertEquals(177, fixtureCycle(world));
        assertEquals(101, guardTower.hitPoints(),
                "the old approach hit must not suppress the native fixture-177 blow");
        assertEquals(2558, longRouteGrunt.battleNetSequenceOffset(),
                "the grunt advances through its attack body instead of freezing at OP0");
        assertEquals(5, longRouteGrunt.battleNetAnimationTimer());
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit unitById(World world, int id) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
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
