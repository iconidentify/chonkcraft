package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.pathfinder.BattleNetPathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks the collided route-park and same-cycle refill on XHuman 12. */
class XHuman12CollisionRefillRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a paid one-heading chase enters its moving ally's vacated square")
    void paidOneHeadingChaseUsesTheNativeVisitOrderVacate() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Sealed slots 1503/1523 pair with Java 97/77. At fixture 39 the
        // grunt's already-paid E route enters the square ranged slot 1524 is
        // vacating. The later axethrower must then see the grunt there and
        // park its SE route rather than swapping their native outcomes.
        Unit grunt = unitById(world, 97);
        Unit axethrower = unitById(world, 77);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1503 grunt");
        assertNotNull(axethrower, "XHuman 12 has no native-slot-1523 axethrower");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 39) {
            mission.tick();
        }

        assertEquals(32, grunt.tileX());
        assertEquals(38, grunt.tileY(),
                "the paid melee ray must consume native's east heading");
        assertEquals(31, axethrower.tileX());
        assertEquals(37, axethrower.tileY(),
                "the later ranged route must park behind the committed grunt");
    }

    @Test
    @DisplayName("a paid help handoff routes behind the saturated front rank")
    void paidHelpHandoffKeepsSaturatedFrontRankSolid() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Native slot 1470 / Java 130 accepts a person-help target while its
        // one-byte east route settles. The following paid route draw keeps
        // the collision-four front grunt solid and stores N,N. Clearing that
        // packed wall produced N,NE and split the formation at fixture 143.
        Unit rearGrunt = unitById(world, 130);
        Unit frontGrunt = unitById(world, 121);
        assertNotNull(rearGrunt, "XHuman 12 has no native-slot-1470 grunt");
        assertNotNull(frontGrunt, "XHuman 12 has no saturated front grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 126) {
            mission.tick();
        }
        assertEquals(4, frontGrunt.battleNetCollisionCounter(),
                "the front rank owns native collision generation four");

        while (fixtureCycle(world) < 143) {
            mission.tick();
        }
        assertEquals(23, rearGrunt.tileX(),
                "the rear grunt must not cut north-east through its formation");
        assertEquals(40, rearGrunt.tileY());
        assertEquals(Direction.fromDelta(0, -1), rearGrunt.lastStepHeading());
    }

    @Test
    @DisplayName("a saturated closing diagonal terminates before redraw")
    void saturatedClosingDiagonalTerminatesBeforeRedraw() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 118);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1482 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 143) {
            mission.tick();
        }
        assertEquals(29, grunt.tileX());
        assertEquals(39, grunt.tileY());
        assertEquals(5, grunt.battleNetCollisionCounter(),
                "the route terminator advances native collision four to five");
        assertEquals(0, grunt.pathLength(),
                "the paid diagonal exposes route index twenty before redraw");

        mission.tick();
        assertEquals(144, fixtureCycle(world));
        assertEquals(30, grunt.tileX(),
                "the paid route must redraw east instead of freezing");
        assertEquals(39, grunt.tileY());
        assertEquals(Direction.fromDelta(1, 0), grunt.lastStepHeading());
    }

    @Test
    @DisplayName("a paid refill retarget keeps its incumbent compass buffer")
    void paidRefillRetargetKeepsIncumbentCompassBuffer() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 110);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1490 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 143) {
            mission.tick();
        }
        assertEquals(28, grunt.tileX());
        assertEquals(36, grunt.tileY());

        mission.tick();
        assertEquals(144, fixtureCycle(world));
        assertEquals(29, grunt.tileX(),
                "the target swap must not reverse an approved east buffer");
        assertEquals(36, grunt.tileY());
        assertEquals(Direction.fromDelta(1, 0), grunt.lastStepHeading());
    }

    @Test
    @DisplayName("a near-full retained tail leaves Attack through one-count Move")
    void nearFullRetainedTailDoesNotBorrowShortTailMoveConstruction() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 83);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1517 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 91) {
            mission.tick();
        }
        for (int fixture = 91; fixture <= 93; fixture++) {
            assertEquals(2539, grunt.battleNetSequenceOffset(),
                    "the paid east residual owns fresh Attack construction");
            assertEquals(94 - fixture, grunt.battleNetAnimationTimer(),
                    "native exposes Attack construction as 3,2,1");
            assertEquals(19, grunt.pathLength(),
                    "construction retains the near-full route buffer");
            mission.tick();
        }

        assertEquals(94, fixtureCycle(world));
        assertEquals(2482, grunt.battleNetSequenceOffset(),
                "the near-full park exits through Move construction");
        assertEquals(1, grunt.battleNetAnimationTimer(),
                "a near-full park must not borrow the short tail's Move 15 band");
        assertEquals(26, grunt.tileX());
        assertEquals(39, grunt.tileY());

        mission.tick();
        assertEquals(95, fixtureCycle(world));
        assertEquals(27, grunt.tileX(),
                "the one-byte refill must consume southeast immediately");
        assertEquals(40, grunt.tileY());
        assertEquals(Direction.fromDelta(1, 1), grunt.lastStepHeading());
    }

    @Test
    @DisplayName("a pressured cardinal tail stays live through its Move band")
    void pressuredCardinalTailStaysLiveThroughMoveBand() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 83);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1517 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 147) {
            mission.tick();
        }
        assertEquals(28, grunt.tileX(),
                "the pressured route must not cold-replan southeast");
        assertEquals(38, grunt.tileY());
        assertEquals(19, grunt.pathLength(),
                "the native cardinal tail remains live during the wait");
        assertEquals(Direction.fromDelta(0, -1), grunt.peekHeading());
        assertEquals(3, grunt.battleNetCollisionCounter());
    }

    @Test
    @DisplayName("a saturated paid building refill retains its second wall byte")
    void saturatedPaidBuildingRefillRetainsItsSecondWallByte() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 83);
        Unit formationMate = unitById(world, 110);
        Unit offeredCollisionWall = unitById(world, 88);
        Unit formationPressure = unitById(world, 96);
        Unit paidTailRetarget = unitById(world, 120);
        Unit saturatedMobileRetarget = unitById(world, 106);
        Unit woodcutter = unitById(world, 236);
        Unit knight = unitById(world, 125);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1517 grunt");
        assertNotNull(formationMate,
                "XHuman 12 has no native-slot-1490 grunt");
        assertNotNull(offeredCollisionWall,
                "XHuman 12 has no native-slot-1512 grunt");
        assertNotNull(formationPressure,
                "XHuman 12 has no native-slot-1504 grunt");
        assertNotNull(paidTailRetarget,
                "XHuman 12 has no native-slot-1480 grunt");
        assertNotNull(saturatedMobileRetarget,
                "XHuman 12 has no native-slot-1494 grunt");
        assertNotNull(woodcutter,
                "XHuman 12 has no native-slot-1364 peon");
        assertNotNull(knight, "XHuman 12 has no native-slot-1475 knight");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 210) {
            mission.tick();
        }
        assertEquals(29, grunt.tileX());
        assertEquals(36, grunt.tileY());
        assertEquals(2, grunt.pathLength(),
                "the paid RI-20 refill stores both south and southwest");
        assertEquals(Direction.fromDelta(0, 1), grunt.peekHeading());
        assertEquals(1, grunt.battleNetCollisionCounter(),
                "the replacement retains native collision generation one");

        mission.tick();
        assertEquals(211, fixtureCycle(world));
        assertEquals(29, grunt.tileX());
        assertEquals(37, grunt.tileY(),
                "the first replacement byte commits south");
        assertEquals(1, grunt.pathLength(),
                "southwest remains cached behind the committed south byte");
        assertEquals(Direction.fromDelta(-1, 1), grunt.peekHeading());

        while (fixtureCycle(world) < 226) {
            mission.tick();
        }
        assertEquals(29, grunt.tileX());
        assertEquals(37, grunt.tileY());
        assertEquals(-2, grunt.offsetY(),
                "fixture 226 still owes the final south residual pixels");
        assertEquals(1, grunt.pathLength());
        assertEquals(1, grunt.battleNetCollisionCounter());
        assertEquals(2, grunt.battleNetPathInitialLength());
        assertEquals(1, grunt.battleNetPathStepsTaken());
        assertTrue(grunt.battleNetAttackWrapDestArmPending());

        mission.tick();
        assertEquals(227, fixtureCycle(world));
        assertEquals(knight, grunt.target(),
                "the residual target scan upgrades the tower to the knight");
        assertEquals(30, grunt.tileX(),
                "the retained wall byte permits the immediate east retarget step");
        assertEquals(37, grunt.tileY());
        assertEquals(Direction.fromDelta(1, 0), grunt.lastStepHeading());
        assertEquals(4, grunt.pathLength(),
                "the east step retains native's four-byte knight tail");
        assertEquals(0, grunt.battleNetCollisionCounter());

        while (fixtureCycle(world) < 261) {
            mission.tick();
        }
        assertEquals(30, grunt.tileX(),
                "Move timer one must not spend the stale east tail");
        assertEquals(37, grunt.tileY());
        assertEquals(2482, grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer(),
                "retail retains Move timer one before parking the tail");
        assertEquals(31, formationMate.tileX());
        assertEquals(38, formationMate.tileY());
        assertEquals(30, formationPressure.tileX());
        assertEquals(39, formationPressure.tileY());
        assertEquals(10, woodcutter.tileX());
        assertEquals(89, woodcutter.tileY());

        mission.tick();
        assertEquals(262, fixtureCycle(world));
        assertEquals(30, grunt.tileX());
        assertEquals(37, grunt.tileY());
        assertEquals(0, grunt.pathLength(),
                "the completed paid band parks its old route at RI 20");
        assertEquals(31, formationMate.tileX());
        assertEquals(38, formationMate.tileY(),
                "the earlier pool slot parks its paid cursor too");
        assertEquals(30, formationPressure.tileX());
        assertEquals(39, formationPressure.tileY(),
                "the collision-four route parks at RI 20 for one visit");
        assertEquals(10, woodcutter.tileX());
        assertEquals(89, woodcutter.tileY(),
                "the forest residual finishes without spending its blocked head");

        mission.tick();
        assertEquals(263, fixtureCycle(world));
        assertEquals(31, grunt.tileX());
        assertEquals(38, grunt.tileY(),
                "the following NewPath visit must redraw and spend southeast");
        assertEquals(Direction.fromDelta(1, 1), grunt.lastStepHeading());
        assertEquals(2, grunt.pathLength(),
                "the redrawn southeast route retains its two-byte tail");
        assertEquals(32, formationMate.tileX());
        assertEquals(39, formationMate.tileY(),
                "the earlier pool slot must vacate before slot 1517 enters");
        assertEquals(Direction.fromDelta(1, 1),
                formationMate.lastStepHeading());
        assertEquals(2, formationMate.pathLength());
        assertEquals(30, formationPressure.tileX());
        assertEquals(40, formationPressure.tileY(),
                "the next paid generation must redraw and spend south");
        assertEquals(Direction.fromDelta(0, 1),
                formationPressure.lastStepHeading());
        assertEquals(1, formationPressure.pathLength());
        assertEquals(32, paidTailRetarget.tileX());
        assertEquals(36, paidTailRetarget.tileY());
        assertEquals(37, saturatedMobileRetarget.tileX());
        assertEquals(39, saturatedMobileRetarget.tileY());
        assertEquals(17, saturatedMobileRetarget.pathLength(),
                "the saturated router keeps native's long rear-rank route");
        assertEquals(Direction.fromDelta(1, -1),
                saturatedMobileRetarget.peekHeading(),
                "the second northeast remains cached while the first step settles");
        assertEquals(10, woodcutter.tileX());
        assertEquals(89, woodcutter.tileY(),
                "the full free-prefix shortcut enters wood construction instead of moving north");
        assertEquals(13, woodcutter.battleNetWoodOrderX(),
                "the reverse resource ray stores the intervening forest wall");
        assertEquals(89, woodcutter.battleNetWoodOrderY());
        assertEquals(world.idle.battleNetSequenceStart(woodcutter,
                        BattleNetSequence.ATTACK_ANIMATION),
                woodcutter.battleNetSequenceOffset());
        assertEquals(3, woodcutter.battleNetAnimationTimer());

        mission.tick();
        assertEquals(264, fixtureCycle(world));
        assertEquals(31, paidTailRetarget.tileX());
        assertEquals(37, paidTailRetarget.tileY(),
                "the long paid tail retarget must redraw and spend southwest without construction");
        assertEquals(Direction.fromDelta(-1, 1),
                paidTailRetarget.lastStepHeading());
        assertEquals(3, paidTailRetarget.pathLength());
        assertEquals(38, saturatedMobileRetarget.tileX());
        assertEquals(38, saturatedMobileRetarget.tileY(),
                "the paid redraw follows native above the collision-marked rear rank");
        assertEquals(Direction.fromDelta(1, -1),
                saturatedMobileRetarget.lastStepHeading());
        assertEquals(16, saturatedMobileRetarget.pathLength());
        assertEquals(Direction.fromDelta(1, -1),
                saturatedMobileRetarget.peekHeading(),
                "native's third northeast remains behind the committed second step");
        assertEquals(2, woodcutter.battleNetAnimationTimer());
        mission.tick();
        assertEquals(265, fixtureCycle(world));
        assertEquals(1, woodcutter.battleNetAnimationTimer());
        mission.tick();
        assertEquals(266, fixtureCycle(world));
        assertEquals(11, woodcutter.tileX());
        assertEquals(90, woodcutter.tileY(),
                "the completed construction redraws and spends southeast");
        assertEquals(Direction.fromDelta(1, 1),
                woodcutter.lastStepHeading());
        assertEquals(1, woodcutter.pathLength(),
                "east remains cached behind the committed southeast byte");
        assertEquals(Direction.fromDelta(1, 0), woodcutter.peekHeading());

        while (fixtureCycle(world) < 279) {
            mission.tick();
        }
        assertEquals(30, formationPressure.tileX());
        assertEquals(40, formationPressure.tileY());
        assertEquals(0, formationPressure.pathLength(),
                "the saturated cardinal tail parks at RI 20");
        assertTrue(formationPressure
                        .battleNetResidualEmptyApproachIdlePending(),
                "the saturated wrap queues the active-order callback");
        assertEquals(31, grunt.tileX());
        assertEquals(38, grunt.tileY());
        assertEquals(0, grunt.pathLength(),
                "the occupied paid-wrap tail parks for one Move visit");
        assertEquals(1, offeredCollisionWall.battleNetCollisionCounter(),
                "the offered cached-route wake retains native collision one");

        mission.tick();
        assertEquals(280, fixtureCycle(world));
        assertEquals(30, formationPressure.tileX(),
                "the nearer saturated grunt must enter Attack construction");
        assertEquals(40, formationPressure.tileY());
        assertEquals(world.idle.battleNetSequenceStart(formationPressure,
                        BattleNetSequence.ATTACK_ANIMATION),
                formationPressure.battleNetSequenceOffset());
        assertEquals(3, formationPressure.battleNetAnimationTimer());
        assertEquals(32, grunt.tileX(),
                "the paid-wrap refill owns the open southeast square");
        assertEquals(39, grunt.tileY());
        assertEquals(Direction.fromDelta(1, 1), grunt.lastStepHeading());
        assertEquals(19, grunt.pathLength(),
                "native retains nineteen headings after committing southeast");
    }

    @Test
    @DisplayName("a committed chase step defers target scan until pixels settle")
    void committedChaseStepDefersTargetScanUntilPixelsSettle() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 119);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1481 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 148) {
            mission.tick();
        }
        assertEquals(25, grunt.tileX());
        assertEquals(38, grunt.tileY(),
                "a post-commit scan must not erase the approved route early");
    }

    @Test
    @DisplayName("a first refusal after a long chase keeps the direct progressive face")
    void firstSaturatedChaseRefusalKeepsDirectProgressiveFace() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 119);
        Unit damagedGrunt = unitById(world, 159);
        Unit damagedFootman = unitById(world, 151);
        Unit westernGrunt = unitById(world, 105);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1481 grunt");
        assertNotNull(damagedGrunt, "XHuman 12 has no native-slot-1441 grunt");
        assertNotNull(damagedFootman,
                "XHuman 12 has no native-slot-1449 footman");
        assertNotNull(westernGrunt,
                "XHuman 12 has no native-slot-1495 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 267) {
            mission.tick();
        }

        assertEquals(31, grunt.tileX());
        assertEquals(40, grunt.tileY());
        assertEquals(1, grunt.battleNetCollisionCounter());
        assertEquals(1, grunt.battleNetRefusals());
        assertEquals(0, grunt.pathLength(),
                "the refused northeast tail is parked at route index twenty");

        mission.tick();
        assertEquals(268, fixtureCycle(world));
        assertEquals(30, grunt.tileX());
        assertEquals(41, grunt.tileY(),
                "the native refill is the one-byte southwest progressive face");
        assertEquals(Direction.fromDelta(-1, 1), grunt.lastStepHeading());
        assertEquals(0, grunt.pathLength(),
                "native stores and consumes a complete one-byte refill");

        while (fixtureCycle(world) < 283) {
            mission.tick();
        }
        assertTrue(grunt.isMoving(),
                "fixture 283 retains the progressive step's final pixels");

        mission.tick();
        assertEquals(284, fixtureCycle(world));
        assertFalse(grunt.isMoving(),
                "fixture 284 settles the paid progressive step");
        assertEquals(0, grunt.pathLength(),
                "native parks the spent one-byte cursor at route index twenty");
        assertEquals(0, grunt.battleNetCollisionCounter(),
                "active-order Still clears the retired collision generation");
        assertEquals(0, grunt.battleNetRefusals(),
                "active-order Still clears the retired refusal generation");
        assertEquals(world.idle.battleNetSequenceStart(
                        grunt, BattleNetSequence.ATTACK_ANIMATION),
                grunt.battleNetSequenceOffset(),
                "settlement opens native Attack construction");
        assertEquals(3, grunt.battleNetAnimationTimer(),
                "fixture 284 owns the first three-count construction");
        assertEquals(0x179cfada, world.battleNetRandomSeed,
                "fixture 284 includes the progressive step's idle draw");

        mission.tick();
        assertEquals(2, grunt.battleNetAnimationTimer());
        mission.tick();
        assertEquals(1, grunt.battleNetAnimationTimer());
        mission.tick();
        assertEquals(287, fixtureCycle(world));
        assertEquals(3, grunt.battleNetAnimationTimer(),
                "the boxed retry reopens construction every three visits");
        assertEquals(0xe39784ed, world.battleNetRandomSeed,
                "fixture 287 includes the second active-order idle draw");

        while (fixtureCycle(world) < 290) {
            mission.tick();
        }
        assertEquals(3, grunt.battleNetAnimationTimer(),
                "fixture 290 reopens the still-boxed constructor");
        assertEquals(34, damagedGrunt.hitPoints(),
                "native's first cycle-290 melee roll deals eight");
        assertEquals(47, damagedFootman.hitPoints(),
                "native's second cycle-290 melee roll deals eight");
        assertEquals(28, westernGrunt.hitPoints(),
                "native's third cycle-290 melee roll deals seven");
        assertEquals(0x5a765747, world.battleNetRandomSeed,
                "the third idle draw restores the complete cycle-290 ledger");
    }

    @Test
    @DisplayName("a hard-parked paid refill continues the retained wall face")
    void hardParkedPaidRefillContinuesRetainedWallFace() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 120);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1480 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 151) {
            mission.tick();
        }
        assertEquals(27, grunt.tileX(),
                "the hard park must continue clockwise from blocked northeast");
        assertEquals(38, grunt.tileY(),
                "a paid refill must not cold-replan north out of the formation");
        assertEquals(Direction.fromDelta(1, 0), grunt.lastStepHeading());
    }

    @Test
    @DisplayName("a saturated two-face buffer parks beyond its opposite face")
    void saturatedTwoFaceBufferParksRetainedOppositeFace() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 94);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1506 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 153) {
            mission.tick();
        }
        assertEquals(32, grunt.tileX(),
                "the retained wall face owns a refusal band, not a southeast escape");
        assertEquals(39, grunt.tileY());
        assertEquals(0, grunt.pathLength(),
                "the written southwest byte remains behind native route index twenty");
        assertEquals(8, grunt.battleNetCollisionCounter());
        assertEquals(15, grunt.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("a saturated retarget keeps its approved route through the band")
    void saturatedRetargetKeepsApprovedRouteThroughBand() {
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
        assertNotNull(grunt, "XHuman 12 has no native-slot-1510 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 155) {
            mission.tick();
        }
        assertEquals(38, grunt.tileX(),
                "the paid retarget route must rejoin northeast, not redraw southwest");
        assertEquals(38, grunt.tileY());
        assertEquals(Direction.fromDelta(1, -1), grunt.lastStepHeading());
    }

    @Test
    @DisplayName("a saturated refused retarget treats the formation as a wall")
    void saturatedRefusedRetargetKeepsFormationSolid() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 108);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1492 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 156) {
            mission.tick();
        }
        assertEquals(27, grunt.tileX(),
                "the saturated retarget must wall-route northwest without another band");
        assertEquals(36, grunt.tileY());
        assertEquals(Direction.fromDelta(-1, -1), grunt.lastStepHeading());
        assertEquals(6, grunt.battleNetCollisionCounter(),
                "the accepted replacement retains its paid collision generation");
        assertEquals(0, grunt.battleNetRefusals(),
                "a committed wall route starts a fresh refusal generation");
    }

    @Test
    @DisplayName("a saturated near recovery escalates after one direct retry")
    void saturatedNearRecoveryEscalatesToFullWallRoute() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 96);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1504 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 162) {
            mission.tick();
        }
        assertEquals(30, grunt.tileX(),
                "one rejected direct retry must escalate instead of looping");
        assertEquals(40, grunt.tileY());
        assertEquals(Direction.fromDelta(-1, -1), grunt.lastStepHeading());
        assertEquals(19, grunt.pathLength(),
                "the first northwest byte is spent from BNE's full wall route");
        assertEquals(0, grunt.battleNetCollisionCounter());
        assertEquals(0, grunt.battleNetRefusals());
    }

    @Test
    @DisplayName("an attack handoff clears stale formation collision debt")
    void attackHandoffClearsStaleFormationCollisionDebt() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Native slot 1504 / Java 96 has accumulated collision generation
        // four while the formation blocks its south-west attack route.  BNE
        // fixture 87 enters 0x438410, parks that route, clears the packed
        // nibble, and installs Attack 3.  Java used to preserve both of its
        // refusal projections through this boundary, leaving this moving body
        // solid to the later wall trace for slot 1476 / Java 124.
        Unit blocker = unitById(world, 96);
        assertNotNull(blocker, "XHuman 12 has no native-slot-1504 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 86) {
            mission.tick();
        }
        assertEquals(31, blocker.tileX());
        assertEquals(41, blocker.tileY());
        assertEquals(4, blocker.battleNetCollisionCounter());
        assertEquals(2, blocker.battleNetRefusals());

        mission.tick();
        assertEquals(87, fixtureCycle(world));
        assertEquals(0, blocker.battleNetCollisionCounter(),
                "Attack construction owns a fresh native collision nibble");
        assertEquals(0, blocker.battleNetRefusals(),
                "the duplicate Java refusal projection shares that lifetime");
        assertEquals(3, blocker.battleNetAnimationTimer(),
                "native opens the three-visit Attack constructor");

        Unit follower = unitById(world, 124);
        assertNotNull(follower, "XHuman 12 has no native-slot-1476 grunt");
        while (fixtureCycle(world) < 126) {
            mission.tick();
        }
        assertEquals(23, follower.tileX());
        assertEquals(48, follower.tileY());

        mission.tick();
        assertEquals(127, fixtureCycle(world));
        assertEquals(23, follower.tileX());
        assertEquals(47, follower.tileY(),
                "the follower takes BNE's north step instead of freezing");
        assertEquals(Direction.fromDelta(0, -1),
                follower.lastStepHeading());
    }

    @Test
    @DisplayName("xhuman 12 parks an allied refusal then refills before cycle 57")
    void xhuman12ParksAlliedRefusalThenRefillsBeforeCycle57() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit grunt = unitAt(world, "unit-grunt", 22, 44);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1476 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 57) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 40) {
                assertEquals(0, grunt.pathLength(),
                        "the standing allied blocker parks route index 20");
                assertEquals(2, grunt.battleNetCollisionCounter(),
                        "the route park retains native collision two");
            }
            if (fixture == 41) {
                assertEquals(23, grunt.tileX());
                assertEquals(44, grunt.tileY());
                assertEquals(19, grunt.pathLength(),
                        "the replacement twenty-heading route spends south now");
                assertEquals(Direction.fromDelta(-1, 1), grunt.peekHeading(),
                        "south-west remains cached for the next Move boundary");
            }
        }

        assertEquals(22, grunt.tileX(),
                "the replacement route must spend south-west on fixture 57");
        assertEquals(45, grunt.tileY());
        assertEquals(2, grunt.battleNetCollisionCounter());
    }

    @Test
    @DisplayName("xhuman 12 pays attack construction before parking a blocked chase")
    void xhuman12PaysAttackConstructionBeforeParkingBlockedChase() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit grunt = unitAt(world, "unit-grunt", 30, 38);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1496 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 60) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture >= 56 && fixture <= 58) {
                assertEquals(30, grunt.tileX(),
                        "Attack construction must not consume the free diagonal");
                assertEquals(39, grunt.tileY());
                assertEquals(world.idle.battleNetSequenceStart(grunt,
                                BattleNetSequence.ATTACK_ANIMATION),
                        grunt.battleNetSequenceOffset());
                assertEquals(59 - fixture, grunt.battleNetAnimationTimer());
                assertEquals(5, grunt.pathLength(),
                        "construction retains the refused south route");
            }
            if (fixture == 59) {
                assertEquals(30, grunt.tileX());
                assertEquals(39, grunt.tileY());
                assertEquals(0, grunt.pathLength(),
                        "the construction handoff parks route index 20");
                assertEquals(2, grunt.battleNetCollisionCounter());
                assertEquals(world.idle.battleNetSequenceStart(grunt,
                                BattleNetSequence.MOVE_ANIMATION),
                        grunt.battleNetSequenceOffset());
                assertEquals(1, grunt.battleNetAnimationTimer());
            }
        }

        assertEquals(30, grunt.tileX(),
                "the blocked replacement route must not step at fixture 60");
        assertEquals(39, grunt.tileY());
        assertEquals(2, grunt.pathLength());
        assertEquals(3, grunt.battleNetCollisionCounter());
    }

    @Test
    @DisplayName("a previously refused long cardinal ray parks before refill")
    void previouslyRefusedLongCardinalRayParksBeforeRefill() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Native slot 1503 / Java 97. The same twenty-byte attack ray first
        // parks and refills E at fixtures 106/107. When that new first E
        // settles into another allied blocker at 123, sticky refusal history
        // makes the long cardinal ray a hard route park, not another
        // fifteen-visit cooperative wait. BNE replans and commits NE at 124.
        Unit grunt = unitAt(world, "unit-grunt", 31, 38);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1503 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 124) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 106) {
                assertEquals(33, grunt.tileX());
                assertEquals(40, grunt.tileY());
                assertEquals(0, grunt.pathLength(),
                        "the first blocked long residual parks route index 20");
                assertEquals(1, grunt.battleNetCollisionCounter());
            }
            if (fixture == 107) {
                assertEquals(34, grunt.tileX());
                assertEquals(40, grunt.tileY());
                assertEquals(19, grunt.pathLength(),
                        "the replacement E ray spends exactly its first byte");
                assertEquals(1, grunt.battleNetCollisionCounter());
            }
            if (fixture == 123) {
                assertEquals(34, grunt.tileX());
                assertEquals(40, grunt.tileY());
                assertEquals(0, grunt.pathLength(),
                        "the second blocked cardinal face must park, not sleep");
                assertEquals(2, grunt.battleNetCollisionCounter(),
                        "the route-park visit advances native collision one to two");
            }
        }

        assertEquals(35, grunt.tileX());
        assertEquals(39, grunt.tileY(),
                "the following visit commits BNE's north-east refill");
        assertEquals(19, grunt.pathLength());
        assertEquals(Direction.fromDelta(1, 1), grunt.peekHeading(),
                "south-east is the next cached replacement heading");
        assertEquals(2, grunt.battleNetCollisionCounter(),
                "the paid formation collision remains attached to the refill");
    }

    @Test
    @DisplayName("a repeated retained-route refusal writes timer fifteen immediately")
    void repeatedRetainedRouteRefusalWritesTimerFifteenImmediately() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Sealed native slot 1503 / Java 97 reaches the same blocked NE head
        // twice. FUN_004379e0 increments the packed collision byte at
        // 0x00437a0d and unconditionally writes Move timer 15 at 0x00437a25
        // on both visits. The second visit is fixture 221: it must not expose
        // a synthetic Move/1 callback before beginning the paid band.
        Unit grunt = unitById(world, 97);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1503 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 221) {
            mission.tick();
        }

        assertEquals(38, grunt.tileX());
        assertEquals(39, grunt.tileY());
        assertEquals(2, grunt.battleNetCollisionCounter(),
                "the repeated handler visit advances collision one to two");
        assertEquals(world.idle.battleNetSequenceStart(grunt,
                        BattleNetSequence.MOVE_ANIMATION),
                grunt.battleNetSequenceOffset());
        assertEquals(15, grunt.battleNetAnimationTimer(),
                "the native refusal handler writes fifteen on this visit");
        assertEquals(14, grunt.battleNetOrderDelay(),
                "fourteen remaining quiet visits mirror native timer 15..1");

        while (fixtureCycle(world) < 235) {
            mission.tick();
        }
        assertEquals(38, grunt.tileX(),
                "timer one still owns the blocked square at fixture 235");
        assertEquals(39, grunt.tileY());

        mission.tick();
        assertEquals(236, fixtureCycle(world));
        assertEquals(39, grunt.tileX(),
                "the paid retained route consumes north-east at fixture 236");
        assertEquals(38, grunt.tileY());
        assertEquals(Direction.fromDelta(1, -1), grunt.lastStepHeading());
    }

    @Test
    @DisplayName("collision generation four refills on its residual-settle visit")
    void collisionGenerationFourRefillsOnItsResidualSettleVisit() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Native slot 1482 / Java 118 reaches the end of a diagonal route with
        // collision generation four and no hard-refusal history. That band is
        // already paid: its residual-settle callback writes the next route in
        // the cooperative formation view and commits NE on fixture 127. A
        // generic collided-route park deferred the refill and then chose N.
        Unit grunt = unitById(world, 118);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1482 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 126) {
            mission.tick();
        }
        assertEquals(28, grunt.tileX());
        assertEquals(40, grunt.tileY());
        assertEquals(4, grunt.battleNetCollisionCounter(),
                "the settling route owns native collision generation four");
        assertEquals(0, grunt.battleNetRefusals(),
                "hard-refusal history would require the later park arm");

        mission.tick();
        assertEquals(127, fixtureCycle(world));
        assertEquals(29, grunt.tileX());
        assertEquals(39, grunt.tileY(),
                "the paid band must refill and commit northeast immediately");
        assertEquals(Direction.fromDelta(1, -1), grunt.lastStepHeading());
        assertEquals(19, grunt.pathLength(),
                "one heading is spent from the full replacement buffer");
    }

    @Test
    @DisplayName("a free-compass detour remains the head of its replacement buffer")
    void freeCompassDetourRetainsItsReplacementBuffer() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Sealed native slot 1496 / Java 104 ends its old four-byte route on
        // a blocked west head. Retail parks RI 20, draws a complete replacement
        // buffer toward knight 1475, substitutes the first free compass byte E,
        // and consumes it on fixture 221. The following NE byte and eighteen
        // more headings remain cached. Treating E as a complete one-byte route
        // falsely closes Move when the quarry changes on fixture 237 and makes
        // Java pay an unrelated Attack 3,2,1 construction.
        Unit grunt = unitById(world, 104);
        Unit replacement = unitById(world, 123);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1496 grunt");
        assertNotNull(replacement, "XHuman 12 has no native-slot-1478 footman");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 221) {
            mission.tick();
        }

        assertEquals(35, grunt.tileX());
        assertEquals(40, grunt.tileY(),
                "the approved east detour commits on native's visit");
        assertEquals(BattleNetPathFinder.MAX_PATH - 1, grunt.pathLength(),
                "east is the head of a full replacement buffer, not a surrogate route");
        assertEquals(BattleNetPathFinder.MAX_PATH,
                grunt.battleNetPathInitialLength());
        assertEquals(1, grunt.battleNetPathStepsTaken());

        while (fixtureCycle(world) < 237) {
            mission.tick();
        }

        assertEquals(replacement, grunt.target(),
                "the residual-settle scan installs the better footman");
        assertEquals(36, grunt.tileX(),
                "the replacement route commits without false cold construction");
        assertEquals(39, grunt.tileY());
        assertEquals(Direction.fromDelta(1, -1), grunt.lastStepHeading());
    }

    @Test
    @DisplayName("paid melee recovery keeps both formation grunts engaged at cycle 140")
    void paidMeleeRecoveryKeepsFormationEngagedAtCycle140() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Sealed BNE slots 1501 and 1504 pair with Java units 99 and 96.
        // At fixture 140 the former is still paying its bounded recovery
        // band, while the latter consumes BNE's one-byte south refill. A cold
        // wall search makes both leave their authentic formation squares.
        Unit boundedRetry = unitById(world, 99);
        Unit directRefill = unitById(world, 96);
        assertNotNull(boundedRetry);
        assertNotNull(directRefill);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 140) {
            mission.tick();
        }

        assertEquals(30, boundedRetry.tileX(),
                "the eighth refused direct probe must not spend its retained wall route");
        assertEquals(41, boundedRetry.tileY());
        assertEquals(31, directRefill.tileX());
        assertEquals(41, directRefill.tileY(),
                "the paid residual must consume BNE's direct south refill");
        assertEquals(Direction.fromDelta(0, 1), directRefill.lastStepHeading());
    }

    @Test
    @DisplayName("parked saturated retargets preserve native refill ownership")
    void parkedSaturatedRetargetsPreserveNativeRefillOwnership() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit footprintChaser = unitById(world, 92);
        Unit saturatedRetarget = unitById(world, 104);
        assertNotNull(footprintChaser,
                "XHuman 12 has no native-slot-1508 grunt");
        assertNotNull(saturatedRetarget,
                "XHuman 12 has no native-slot-1496 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 270) {
            mission.tick();
        }
        assertEquals(39, footprintChaser.tileX());
        assertEquals(44, footprintChaser.tileY());
        assertEquals(0, footprintChaser.pathLength(),
                "the retained footprint route parks for one native visit");
        assertEquals(1, footprintChaser.battleNetCollisionCounter());
        assertEquals(2482, footprintChaser.battleNetSequenceOffset());
        assertEquals(1, footprintChaser.battleNetAnimationTimer());

        mission.tick();
        assertEquals(271, fixtureCycle(world));
        assertEquals(38, footprintChaser.tileX(),
                "the replacement route commits west after the parked visit");
        assertEquals(44, footprintChaser.tileY());
        assertEquals(2, footprintChaser.pathLength(),
                "northwest and northeast remain behind the committed west byte");
        assertEquals(Direction.fromDelta(-1, -1),
                footprintChaser.peekHeadingAtDepth(0));
        assertEquals(Direction.fromDelta(1, -1),
                footprintChaser.peekHeadingAtDepth(1));
        assertEquals(1, footprintChaser.battleNetCollisionCounter());

        assertEquals(36, saturatedRetarget.tileX());
        assertEquals(39, saturatedRetarget.tileY());
        assertEquals(0, saturatedRetarget.pathLength(),
                "the first retained refusal parks at route index twenty");
        assertEquals(2, saturatedRetarget.battleNetCollisionCounter());
        assertEquals(2482, saturatedRetarget.battleNetSequenceOffset());
        assertEquals(1, saturatedRetarget.battleNetAnimationTimer());

        mission.tick();
        assertEquals(272, fixtureCycle(world));
        assertEquals(3, saturatedRetarget.battleNetCollisionCounter(),
                "the continued occupied head raises native generation three");
        assertEquals(2482, saturatedRetarget.battleNetSequenceOffset());
        assertEquals(15, saturatedRetarget.battleNetAnimationTimer());
        int[] route = {
            3, 1, 1, 2, 3, 3, 3, 4, 4, 5, 5, 6, 6, 6, 7, 7, 7, 6, 6
        };
        assertEquals(route.length, saturatedRetarget.pathLength());
        for (int depth = 0; depth < route.length; depth++) {
            assertEquals(route[depth],
                    saturatedRetarget.peekHeadingAtDepth(depth),
                    "native fixture-272 route heading at depth " + depth);
        }

        while (fixtureCycle(world) < 275) {
            mission.tick();
        }
        assertEquals(12, saturatedRetarget.battleNetAnimationTimer(),
                "the parked replacement owns the complete Move 15..1 band");
        assertEquals(route.length, saturatedRetarget.pathLength(),
                "the paid band must not spend its retained route early");
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
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
