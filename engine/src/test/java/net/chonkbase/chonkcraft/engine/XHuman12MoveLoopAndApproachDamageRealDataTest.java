package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
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

    @Test
    @DisplayName("paid refusal recovery settles with one cached heading")
    void paidRefusalRecoveryWithOneCachedHeadingPaysFixture204Reseed() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit recoveryGrunt = unitById(world, 153);
        Unit nearbyFootman = unitById(world, 151);
        assertNotNull(recoveryGrunt,
                "XHuman 12 has no native-slot-1447 recovery grunt");
        assertNotNull(nearbyFootman,
                "XHuman 12 has no native-slot-1449 footman");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 203) {
            mission.tick();
        }
        assertEquals(0xd358cbee, world.randomSeed(),
                "the agreeing prefix ends before the recovery arrival debit");
        assertEquals(2534, recoveryGrunt.battleNetSequenceOffset());
        assertEquals(1, recoveryGrunt.battleNetAnimationTimer());
        assertEquals(1, recoveryGrunt.pathLength(),
                "the successful diagonal retains one cached cardinal heading");
        assertEquals(true,
                recoveryGrunt.battleNetPaidRefusalRecoveryApproach(),
                "the residual belongs to a completed paid refusal probe");

        mission.tick();
        assertEquals(204, fixtureCycle(world));
        assertEquals(0x3305888f, world.randomSeed(),
                "paid recovery settlement must debit on the native arrival");
        assertEquals(2540, recoveryGrunt.battleNetSequenceOffset(),
                "the paid residual opens past Attack OP0 on settlement");
        assertEquals(1, recoveryGrunt.battleNetAnimationTimer());
        assertEquals(0, recoveryGrunt.pathLength(),
                "native parks the retained route at cursor twenty");
        assertEquals(false, recoveryGrunt.battleNetPendingMeleeSyncRand());
        assertEquals(2539, nearbyFootman.battleNetSequenceOffset(),
                "the adjacent footman does not own the fixture-204 draw");
        assertEquals(1, nearbyFootman.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("a paid same-quarry residual retains its full move band")
    void paidSameQuarryResidualRetainsItsFullMoveBand() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 137);
        assertNotNull(grunt,
                "XHuman 12 has no native-slot-1463 recovery grunt");

        // The sealed native route consumes its southeast opening on fixture
        // 188. When those pixels settle on 204, the retained east byte is
        // still occupied by a cooperative unit. Retail advances collision
        // generation three to four and exposes the complete Move 15 band
        // without replacing the approved route. It therefore remains on the
        // formation square through the cycle-207 comparison frontier.
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 204) {
            mission.tick();
        }

        assertEquals(23, grunt.tileX());
        assertEquals(61, grunt.tileY());
        assertEquals(4, grunt.battleNetCollisionCounter());
        assertEquals(2, grunt.battleNetRefusals());
        assertEquals(2, grunt.pathLength(),
                "the paid residual keeps both cached route bytes");
        assertEquals(world.idle.battleNetSequenceStart(grunt,
                        net.chonkbase.chonkcraft.engine.animation
                                .BattleNetSequence.MOVE_ANIMATION),
                grunt.battleNetSequenceOffset());
        assertEquals(15, grunt.battleNetAnimationTimer());

        while (fixtureCycle(world) < 207) {
            mission.tick();
        }
        assertEquals(23, grunt.tileX(),
                "the full refusal band keeps the formation square");
        assertEquals(61, grunt.tileY());
        assertEquals(12, grunt.battleNetAnimationTimer(),
                "native Move construction counts 15,14,13,12");
    }

    @Test
    @DisplayName("a retained building retarget replays its approved opening")
    void retainedBuildingRetargetReplaysItsApprovedOpening() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 90);
        Unit guardTower = unitById(world, 115);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1510 grunt");
        assertNotNull(guardTower, "XHuman 12 has no paired guard tower");

        // The native target upgrade writes a twelve-byte tower route and
        // spends east on fixture 187. Its residual settles into real Attack
        // construction 3,2,1 on fixtures 203..205. Move owns a route-index-20
        // replay visit on 206, then redraws and consumes that approved east
        // opening again on 207; a free-compass south detour is not this
        // route's owner.
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 203) {
            mission.tick();
        }
        assertEquals(guardTower, grunt.target());
        assertEquals(11, grunt.pathLength());
        assertEquals(2539, grunt.battleNetSequenceOffset());
        assertEquals(3, grunt.battleNetAnimationTimer());

        mission.tick();
        assertEquals(204, fixtureCycle(world));
        assertEquals(2, grunt.battleNetAnimationTimer());
        mission.tick();
        assertEquals(205, fixtureCycle(world));
        assertEquals(1, grunt.battleNetAnimationTimer());
        mission.tick();
        assertEquals(206, fixtureCycle(world));
        assertEquals(40, grunt.tileX());
        assertEquals(38, grunt.tileY());
        assertEquals(world.idle.battleNetSequenceStart(grunt,
                        net.chonkbase.chonkcraft.engine.animation
                                .BattleNetSequence.MOVE_ANIMATION),
                grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer());
        assertEquals(0, grunt.pathLength(),
                "route index twenty parks the old tower buffer");
        assertEquals(1, grunt.battleNetCollisionCounter(),
                "the route-index-twenty replay park owns native collision one");

        mission.tick();
        assertEquals(207, fixtureCycle(world));
        assertEquals(41, grunt.tileX(),
                "the cached tower route replays east after construction");
        assertEquals(38, grunt.tileY());
        assertEquals(Direction.fromDelta(1, 0), grunt.lastStepHeading());
        assertEquals(Direction.fromDelta(1, 1),
                grunt.peekHeadingAtDepth(0),
                "the footprint redraw turns south-east after replayed east");
        assertEquals(Direction.fromDelta(0, 1),
                grunt.peekHeadingAtDepth(1),
                "the footprint redraw replaces the stale duplicate diagonal");
        assertEquals(1, grunt.battleNetCollisionCounter(),
                "the replayed opening retains its formation-wall provenance");
    }

    @Test
    @DisplayName("settled formation retargets preserve the native wall faces")
    void settledFormationRetargetsPreserveTheNativeWallFaces() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit towerRetarget = unitById(world, 100);
        Unit mobileRetarget = unitById(world, 86);
        Unit guardTower = unitById(world, 115);
        Unit footman = unitById(world, 123);
        assertNotNull(towerRetarget);
        assertNotNull(mobileRetarget);
        assertNotNull(guardTower);
        assertNotNull(footman);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 209) {
            mission.tick();
        }
        assertEquals(39, towerRetarget.tileX());
        assertEquals(37, towerRetarget.tileY());
        assertEquals(14, towerRetarget.pathLength());
        assertEquals(2, towerRetarget.battleNetPathStepsTaken());
        assertEquals(35, mobileRetarget.tileX());
        assertEquals(40, mobileRetarget.tileY());
        assertEquals(2, mobileRetarget.battleNetCollisionCounter());
        assertEquals(2, mobileRetarget.battleNetRefusals());

        mission.tick();
        assertEquals(210, fixtureCycle(world));
        assertEquals(guardTower, towerRetarget.target());
        assertEquals(40, towerRetarget.tileX(),
                "the collision-marked formation wall selects native east");
        assertEquals(37, towerRetarget.tileY());
        assertEquals(Direction.fromDelta(1, 0),
                towerRetarget.lastStepHeading());
        assertEquals(footman, mobileRetarget.target());
        assertEquals(36, mobileRetarget.tileX(),
                "the paid collision-two buffer retains northeast");
        assertEquals(39, mobileRetarget.tileY());
        assertEquals(Direction.fromDelta(1, -1),
                mobileRetarget.lastStepHeading());
    }

    @Test
    @DisplayName("a reversing paid recovery keeps collision-marked movers solid")
    void reversingPaidRecoveryKeepsCollisionMarkedMoversSolid() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit recoveryGrunt = unitById(world, 96);
        Unit formationMate = unitById(world, 105);
        Unit knight = unitById(world, 125);
        Unit returningPeon = unitById(world, 46);
        assertNotNull(recoveryGrunt,
                "XHuman 12 has no native-slot-1504 recovery grunt");
        assertNotNull(formationMate,
                "XHuman 12 has no native-slot-1495 formation mate");
        assertNotNull(knight, "XHuman 12 has no paired knight");
        assertNotNull(returningPeon,
                "XHuman 12 has no native-slot-1554 returning peon");

        // Native slot 1504 has just drained a north step away from its quarry
        // at fixture 194. The paid recovery returns to the full route writer
        // on 195. Slot 1495 is then crossing (30,40) on action-state Move with
        // packed collision two, so native keeps it solid and writes exactly
        // SE,SW. Clearing that body produces a saturated twenty-byte route
        // whose wrong second heading is not exposed until fixture 211.
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 194) {
            mission.tick();
        }
        assertEquals(knight, recoveryGrunt.target());
        assertEquals(30, recoveryGrunt.tileX());
        assertEquals(39, recoveryGrunt.tileY());
        assertEquals(0, recoveryGrunt.pathLength());
        assertEquals(1, recoveryGrunt.battleNetCollisionCounter());
        assertEquals(1, recoveryGrunt.battleNetRefusals());
        assertEquals(30, formationMate.tileX());
        assertEquals(40, formationMate.tileY());
        assertEquals(2, formationMate.battleNetCollisionCounter(),
                "the paired native mover carries packed collision two");

        mission.tick();
        assertEquals(195, fixtureCycle(world));
        assertEquals(31, recoveryGrunt.tileX());
        assertEquals(40, recoveryGrunt.tileY());
        assertEquals(Direction.fromDelta(1, 1),
                recoveryGrunt.lastStepHeading());
        assertEquals(1, recoveryGrunt.pathLength(),
                "native consumes SE and retains only the SW tail");
        assertEquals(Direction.fromDelta(-1, 1),
                recoveryGrunt.peekHeading());

        // The same paid generation later finishes a north residual at fixture
        // 245 with collision four and no hard-refusal history. Retail's shared
        // wall writer keeps its counter-clockwise opening byte and commits NW
        // on 246; choosing the opposite face moves east and is the first
        // full-fleet semantic divergence.
        while (fixtureCycle(world) < 245) {
            mission.tick();
        }
        assertEquals(31, recoveryGrunt.tileX());
        assertEquals(40, recoveryGrunt.tileY());
        assertEquals(4, recoveryGrunt.battleNetCollisionCounter());
        assertEquals(0, recoveryGrunt.battleNetRefusals());

        mission.tick();
        assertEquals(246, fixtureCycle(world));
        assertEquals(30, recoveryGrunt.tileX(),
                "the paid cardinal residual keeps native's northwest wall face");
        assertEquals(39, recoveryGrunt.tileY());
        assertEquals(Direction.fromDelta(-1, -1),
                recoveryGrunt.lastStepHeading());
        assertEquals(Direction.fromDelta(1, -1),
                recoveryGrunt.peekHeading(),
                "the retained route turns northeast after its northwest opening");

        // On the next visit, native action 24 finishes this peon's committed
        // northeast pixels just as its closest great-hall entry changes from
        // (6,23) to (8,23). The old route tail belongs to the former point:
        // retail parks it at cursor twenty and raises collision one on 247,
        // then redraws and commits north on 248. Consuming Java's cached north
        // on the settle visit is one semantic cycle early.
        assertEquals(7, returningPeon.tileX());
        assertEquals(29, returningPeon.tileY());
        assertEquals(2, returningPeon.pathLength());
        assertEquals(0, returningPeon.battleNetCollisionCounter());
        assertEquals(6, returningPeon.orderTargetX());
        assertEquals(23, returningPeon.orderTargetY());

        mission.tick();
        assertEquals(247, fixtureCycle(world));
        assertEquals(7, returningPeon.tileX());
        assertEquals(29, returningPeon.tileY(),
                "the changed depot entry parks the stale tail for one visit");
        assertEquals(0, returningPeon.pathLength());
        assertEquals(1, returningPeon.battleNetCollisionCounter());
        assertEquals(8, returningPeon.orderTargetX());
        assertEquals(23, returningPeon.orderTargetY());

        mission.tick();
        assertEquals(248, fixtureCycle(world));
        assertEquals(7, returningPeon.tileX());
        assertEquals(28, returningPeon.tileY());
        assertEquals(Direction.fromDelta(0, -1),
                returningPeon.lastStepHeading());
    }

    @Test
    @DisplayName("terrain wood wall route retains its doubled northeast turn")
    void terrainWoodWallRouteRetainsItsDoubledNortheastTurn() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit woodPeon = unitById(world, 240);
        assertNotNull(woodPeon, "XHuman 12 has no native-slot-1360 wood peon");

        // A queued patrol opens the blocked tree route on fixture 200. The
        // authenticated native buffer is W,NW,NE,NE,E,SE. Its fourth byte is
        // not consumed until fixture 248, so substituting E,NE stays invisible
        // for forty-eight cycles and then parks against the peon on (12,88).
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 200) {
            mission.tick();
        }

        assertEquals(11, woodPeon.tileX());
        assertEquals(90, woodPeon.tileY());
        assertEquals(Direction.fromDelta(-1, 0),
                woodPeon.lastStepHeading());
        assertEquals(5, woodPeon.pathLength());
        assertEquals(Direction.fromDelta(-1, -1),
                woodPeon.peekHeadingAtDepth(0));
        assertEquals(Direction.fromDelta(1, -1),
                woodPeon.peekHeadingAtDepth(1));
        assertEquals(Direction.fromDelta(1, -1),
                woodPeon.peekHeadingAtDepth(2),
                "retail keeps the second northeast before turning east");
        assertEquals(Direction.fromDelta(1, 0),
                woodPeon.peekHeadingAtDepth(3));
        assertEquals(Direction.fromDelta(1, 1),
                woodPeon.peekHeadingAtDepth(4));
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
