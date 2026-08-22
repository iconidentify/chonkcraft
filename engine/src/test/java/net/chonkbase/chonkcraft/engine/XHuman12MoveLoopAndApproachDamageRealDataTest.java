package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.missile.Missile;
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
        Unit depotPeon = unitById(world, 109);
        assertNotNull(longRouteGrunt);
        assertNotNull(guardTower);
        assertNotNull(depotPeon);

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
        assertEquals(Unit.Order.STILL, depotPeon.order());
        assertEquals(75, depotPeon.tileX());
        assertEquals(40, depotPeon.tileY());
        assertEquals(2595, depotPeon.battleNetSequenceOffset(),
                "the depot exit retains the native peon Still program");
        assertEquals(25, depotPeon.battleNetAnimationTimer(),
                "computer resource dispatch waits behind native's timed Still head");

        mission.tick();
        assertEquals(177, fixtureCycle(world));
        assertEquals(2595, depotPeon.battleNetSequenceOffset());
        assertEquals(24, depotPeon.battleNetAnimationTimer(),
                "the timed depot head must not fire a phantom idle RNG marker");
        assertEquals(101, guardTower.hitPoints(),
                "the old approach hit must not suppress the native fixture-177 blow");
        assertEquals(2558, longRouteGrunt.battleNetSequenceOffset(),
                "the grunt advances through its attack body instead of freezing at OP0");
        assertEquals(5, longRouteGrunt.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("native attack OP0 releases every dying tower goal")
    void attackBytecodeBoundaryOutranksThePresentationSwing() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit southGrunt = unitById(world, 237);
        Unit northGrunt = unitById(world, 225);
        Unit axethrower = unitById(world, 199);
        assertNotNull(southGrunt);
        assertNotNull(northGrunt);
        assertNotNull(axethrower);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 180) {
            mission.tick();
        }
        for (Unit attacker : new Unit[] {
                southGrunt, northGrunt, axethrower}) {
            assertEquals(Unit.Order.ATTACK, attacker.order());
            assertEquals(1, attacker.battleNetAnimationTimer(),
                    "the committed Attack tail remains visible through fixture 180");
        }

        mission.tick();
        assertEquals(181, fixtureCycle(world));
        for (Unit attacker : new Unit[] {
                southGrunt, northGrunt, axethrower}) {
            assertEquals(Unit.Order.STILL, attacker.order(),
                    "OP0 must release the dying tower on the native visit");
            assertEquals(null, attacker.target());
            assertEquals(3, attacker.battleNetAnimationTimer(),
                    "EndActionAttack constructs a fresh native Still head");
        }
        assertEquals(2477, southGrunt.battleNetSequenceOffset());
        assertEquals(2477, northGrunt.battleNetSequenceOffset());
        assertEquals(825, axethrower.battleNetSequenceOffset());
    }

    @Test
    @DisplayName("a parked chase redraws its whole route suffix")
    void parkedChaseDoesNotConsumeAStaleSuffixAfterResidualSettle() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 93);
        assertNotNull(grunt);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 180) {
            mission.tick();
        }
        assertEquals(32, grunt.tileX());
        assertEquals(38, grunt.tileY());
        assertEquals(2534, grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer());

        mission.tick();
        assertEquals(181, fixtureCycle(world));
        assertEquals(32, grunt.tileX(),
                "the Move OP0 visit parks the blocked retained suffix");
        assertEquals(38, grunt.tileY());
        assertEquals(2482, grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer());
        assertEquals(0, grunt.pathLength(),
                "native exposes route index twenty before the refill");

        mission.tick();
        assertEquals(182, fixtureCycle(world));
        assertEquals(33, grunt.tileX(),
                "the following callback consumes the fresh east heading");
        assertEquals(38, grunt.tileY());
        assertEquals(2485, grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("a committed ranged swing fires through its dying target")
    void committedRangedSequenceStillConstructsItsProjectile() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit axethrower = unitById(world, 194);
        Unit tower = unitById(world, 230);
        assertNotNull(axethrower);
        assertNotNull(tower);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 180) {
            mission.tick();
        }
        assertEquals(Unit.Order.DYING, tower.order());
        assertEquals(tower, axethrower.target(),
                "the already-committed swing retains the dying quarry");
        assertEquals(900, axethrower.battleNetSequenceOffset());
        assertEquals(1, axethrower.battleNetAnimationTimer());

        mission.tick();
        assertEquals(181, fixtureCycle(world));
        assertEquals(906, axethrower.battleNetSequenceOffset());
        assertEquals(3, axethrower.battleNetAnimationTimer());
        Missile shot = world.missiles().stream()
                .filter(missile -> missile.source() == axethrower)
                .filter(Missile::battleNetConstructorDrawn)
                .findFirst().orElse(null);
        assertNotNull(shot,
                "retail OP10 constructs the projectile even after the target starts dying");
        assertEquals(tower, shot.target());
        assertEquals(744998177, world.battleNetRandomSeed(),
                "the committed shot must preserve native consumer order for later damage");
    }

    @Test
    @DisplayName("spent formation movers stay transparent to fresh chase routes")
    void spentFormationMoverDoesNotStrandFreshChaseRoutes() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit southGrunt = unitById(world, 144);
        Unit northGrunt = unitById(world, 147);
        assertNotNull(southGrunt);
        assertNotNull(northGrunt);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 167) {
            mission.tick();
        }
        assertEquals(2, southGrunt.pathLength(),
                "retail writes E,NE through the spent moving ally");
        assertEquals(2, southGrunt.peekHeading());
        assertEquals(2, northGrunt.pathLength(),
                "retail writes SE,NE instead of retrying a one-byte route");
        assertEquals(3, northGrunt.peekHeading());
        assertEquals(14, southGrunt.battleNetOrderDelay(),
                "the fresh route must enter the cooperative refusal band");
        assertEquals(14, northGrunt.battleNetOrderDelay());

        while (fixtureCycle(world) < 183) {
            mission.tick();
        }
        assertEquals(2539, northGrunt.battleNetSequenceOffset(),
                "the blocked attacker resumes its Attack program on retail's visit");
        assertEquals(3, northGrunt.battleNetAnimationTimer());
        assertEquals(624659339, world.battleNetRandomSeed(),
                "the resumed attacker owns retail's active-order idle draw");
    }

    @Test
    @DisplayName("paid formation provenance survives route commit and park")
    void paidFormationProvenanceSurvivesRouteCommitAndPark() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit paidMover = unitById(world, 84);
        Unit retargetingGrunt = unitById(world, 90);
        Unit compactTerminator = unitById(world, 94);
        assertNotNull(paidMover);
        assertNotNull(retargetingGrunt);
        assertNotNull(compactTerminator);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 184) {
            mission.tick();
        }
        assertEquals(1, paidMover.battleNetCollisionCounter(),
                "a stage-six cooperative route keeps BNE's packed collision generation");
        assertEquals(31, compactTerminator.tileX());
        assertEquals(38, compactTerminator.tileY());
        assertEquals(9, compactTerminator.battleNetCollisionCounter());
        assertEquals(2482, compactTerminator.battleNetSequenceOffset());
        assertEquals(15, compactTerminator.battleNetAnimationTimer(),
                "the compact shared-buffer terminator owns another complete Move band");
        assertEquals(14, compactTerminator.battleNetOrderDelay());

        while (fixtureCycle(world) < 187) {
            mission.tick();
        }
        assertEquals(40, retargetingGrunt.tileX(),
                "the hard paid mover makes the replacement route start east");
        assertEquals(38, retargetingGrunt.tileY());
        assertEquals(11, retargetingGrunt.pathLength());
        assertEquals(3, retargetingGrunt.peekHeading(),
                "retail consumed E and retained SE as the new route head");
        assertEquals(1, paidMover.battleNetCollisionCounter());

        mission.tick();
        assertEquals(188, fixtureCycle(world));
        assertEquals(31, compactTerminator.tileX(),
                "the paid compact route must not escape the formation early");
        assertEquals(38, compactTerminator.tileY());
        assertEquals(11, compactTerminator.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("paid wall faces preserve RNG and full routes replace immediately")
    void paidWallFacePreservesRngAndFullRouteRetargetsImmediately() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit wallFaceGrunt = unitById(world, 166);
        Unit fullRouteGrunt = unitById(world, 130);
        Unit refusedDiagonalGrunt = unitById(world, 111);
        Unit saturatedEscapeGrunt = unitById(world, 147);
        Unit saturatedAcceptedFaceGrunt = unitById(world, 118);
        Unit saturatedRecoveryGrunt = unitById(world, 96);
        Unit compactPaidRetargetGrunt = unitById(world, 108);
        Unit progressedLongRouteGrunt = unitById(world, 100);
        Unit knight = unitById(world, 154);
        assertNotNull(wallFaceGrunt);
        assertNotNull(fullRouteGrunt);
        assertNotNull(refusedDiagonalGrunt);
        assertNotNull(saturatedEscapeGrunt);
        assertNotNull(saturatedAcceptedFaceGrunt);
        assertNotNull(saturatedRecoveryGrunt);
        assertNotNull(compactPaidRetargetGrunt);
        assertNotNull(progressedLongRouteGrunt);
        assertNotNull(knight);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 189) {
            mission.tick();
        }
        assertEquals(2482, wallFaceGrunt.battleNetSequenceOffset());
        assertEquals(1, wallFaceGrunt.battleNetAnimationTimer());
        assertEquals(2, wallFaceGrunt.battleNetCollisionCounter());
        assertEquals(1, wallFaceGrunt.battleNetRefusals(),
                "the paid stage-six probe enters Move's refusal ladder");
        assertEquals(2,
                wallFaceGrunt.battleNetPostConstructionWallFaceHeading());
        assertEquals(-147476682, world.battleNetRandomSeed(),
                "parking the wall face must not consume active-order idle RNG");

        mission.tick();
        assertEquals(190, fixtureCycle(world));
        assertEquals(3, wallFaceGrunt.battleNetCollisionCounter());
        assertEquals(2, wallFaceGrunt.battleNetRefusals());
        assertEquals(-1724771554, world.battleNetRandomSeed());

        mission.tick();
        assertEquals(191, fixtureCycle(world));
        assertEquals(26, fullRouteGrunt.tileX());
        assertEquals(39, fullRouteGrunt.tileY());
        assertEquals(19, fullRouteGrunt.pathLength(),
                "a full native route replaces and first-steps in one callback");
        assertEquals(1, fullRouteGrunt.peekHeading());
        assertEquals(24, knight.hitPoints(),
                "the aligned async stream owns native melee damage");

        mission.tick();
        assertEquals(192, fixtureCycle(world));
        assertEquals(30, refusedDiagonalGrunt.tileX());
        assertEquals(37, refusedDiagonalGrunt.tileY());
        assertEquals(2, refusedDiagonalGrunt.lastStepHeading(),
                "a refused old diagonal must yield to the fresh east route");
        assertEquals(23, saturatedEscapeGrunt.tileX());
        assertEquals(60, saturatedEscapeGrunt.tileY());
        assertEquals(4, saturatedEscapeGrunt.lastStepHeading(),
                "behavior-two saturation must scan past retreating wall faces");
        assertEquals(2485,
                saturatedEscapeGrunt.battleNetSequenceOffset());
        assertEquals(1,
                saturatedEscapeGrunt.battleNetAnimationTimer());

        mission.tick();
        assertEquals(193, fixtureCycle(world));
        assertEquals(31, saturatedAcceptedFaceGrunt.tileX());
        assertEquals(41, saturatedAcceptedFaceGrunt.tileY(),
                "the admitted saturated face settles before active-order retirement");
        assertEquals(2539,
                saturatedAcceptedFaceGrunt.battleNetSequenceOffset());
        assertEquals(3,
                saturatedAcceptedFaceGrunt.battleNetAnimationTimer(),
                "the admitted face settles directly into Attack construction");
        assertEquals(27, compactPaidRetargetGrunt.tileX());
        assertEquals(37, compactPaidRetargetGrunt.tileY());
        assertEquals(38, progressedLongRouteGrunt.tileX());
        assertEquals(38, progressedLongRouteGrunt.tileY());

        mission.tick();
        assertEquals(194, fixtureCycle(world));
        assertEquals(31, saturatedAcceptedFaceGrunt.tileX(),
                "a settled saturated face must not draw another escape byte");
        assertEquals(41, saturatedAcceptedFaceGrunt.tileY());
        assertEquals(28, compactPaidRetargetGrunt.tileX(),
                "a compact paid retarget consumes the fresh east route head");
        assertEquals(37, compactPaidRetargetGrunt.tileY());
        assertEquals(2, compactPaidRetargetGrunt.lastStepHeading());
        assertEquals(39, progressedLongRouteGrunt.tileX(),
                "the redrawn long-route suffix retains native northeast");
        assertEquals(37, progressedLongRouteGrunt.tileY());
        assertEquals(1, progressedLongRouteGrunt.lastStepHeading());
        assertEquals(1659601165, world.battleNetRandomSeed(),
                "the saturated settle owns its active-order idle draw");

        mission.tick();
        assertEquals(195, fixtureCycle(world));
        assertEquals(31, saturatedAcceptedFaceGrunt.tileX());
        assertEquals(41, saturatedAcceptedFaceGrunt.tileY());
        assertEquals(2539,
                saturatedAcceptedFaceGrunt.battleNetSequenceOffset());
        assertEquals(1,
                saturatedAcceptedFaceGrunt.battleNetAnimationTimer(),
                "retail holds the accepted saturated face through Attack 3,2,1");
        assertEquals(31, saturatedRecoveryGrunt.tileX(),
                "the retired refusal generation permits the fresh southeast head");
        assertEquals(40, saturatedRecoveryGrunt.tileY());
        assertEquals(3, saturatedRecoveryGrunt.lastStepHeading());
        assertEquals(0, saturatedRecoveryGrunt.battleNetRefusals());
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
