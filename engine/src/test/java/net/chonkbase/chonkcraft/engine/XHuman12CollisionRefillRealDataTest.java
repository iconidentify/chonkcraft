package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    @DisplayName("a full collision rewrite remains solid to the next chase refill")
    void fullCollisionRewriteRetainsOccupancyOwnership() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit rewrittenGrunt = unitById(world, 130);
        Unit followingGrunt = unitById(world, 119);
        assertNotNull(rewrittenGrunt,
                "XHuman 12 has no native-slot-1470 grunt");
        assertNotNull(followingGrunt,
                "XHuman 12 has no native-slot-1481 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 243) {
            mission.tick();
        }
        assertEquals(28, rewrittenGrunt.tileX());
        assertEquals(38, rewrittenGrunt.tileY());
        assertEquals(Direction.fromDelta(0, 1),
                rewrittenGrunt.lastStepHeading());
        assertTrue(rewrittenGrunt.battleNetRetainedRewriteOccupancy(),
                "the native collision-one rewrite remains an occupied wall");

        while (fixtureCycle(world) < 259) {
            mission.tick();
        }
        assertEquals(29, rewrittenGrunt.tileX());
        assertEquals(39, rewrittenGrunt.tileY());
        assertEquals(Direction.fromDelta(1, 1),
                rewrittenGrunt.lastStepHeading());
        assertTrue(rewrittenGrunt.battleNetRetainedRewriteOccupancy());

        while (fixtureCycle(world) < 267) {
            mission.tick();
        }
        assertEquals(31, followingGrunt.tileX());
        assertEquals(40, followingGrunt.tileY());

        mission.tick();
        assertEquals(268, fixtureCycle(world));
        assertEquals(30, followingGrunt.tileX());
        assertEquals(41, followingGrunt.tileY(),
                "the collided moving body forces native's one-byte southwest refill");
        assertEquals(Direction.fromDelta(-1, 1),
                followingGrunt.lastStepHeading());
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
    @DisplayName("an empty collided melee refill retries only its direct ray")
    void emptyCollidedMeleeRefillRetriesOnlyItsDirectRay() {
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
        Unit knight = unitById(world, 125);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1490 grunt");
        assertNotNull(knight, "XHuman 12 has no native-slot-1475 knight");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 295) {
            mission.tick();
        }
        int moveStart = world.idle.battleNetSequenceStart(grunt,
                BattleNetSequence.MOVE_ANIMATION);
        int attackStart = world.idle.battleNetSequenceStart(grunt,
                BattleNetSequence.ATTACK_ANIMATION);
        assertEquals(31, grunt.tileX());
        assertEquals(40, grunt.tileY());
        assertEquals(moveStart, grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer());
        assertEquals(2, grunt.battleNetCollisionCounter(),
                "the empty replacement retains collided-route provenance");
        assertEquals(0, grunt.battleNetRefusals());

        for (int fixture = 296; fixture <= 298; fixture++) {
            mission.tick();
            assertEquals(fixture, fixtureCycle(world));
            assertEquals(31, grunt.tileX());
            assertEquals(40, grunt.tileY());
            assertEquals(attackStart, grunt.battleNetSequenceOffset());
            assertEquals(299 - fixture,
                    grunt.battleNetAnimationTimer(),
                    "native exposes the first Attack constructor as 3,2,1");
        }
        assertEquals(16, knight.hitPoints(),
                "the blocked direct wake has not reassigned the melee roll");

        mission.tick();
        assertEquals(299, fixtureCycle(world));
        assertEquals(31, grunt.tileX(),
                "a free closer side square must not bypass the blocked direct ray");
        assertEquals(40, grunt.tileY());
        assertEquals(attackStart, grunt.battleNetSequenceOffset());
        assertEquals(3, grunt.battleNetAnimationTimer(),
                "the blocked direct ray opens the next Attack constructor");
        assertEquals(13, knight.hitPoints(),
                "the constructor retry must retain native async consumer order");
    }

    @Test
    @DisplayName("a non-empty collided refill keeps its full wall route")
    void nonEmptyCollidedRefillDoesNotEnterTheDirectRayLoop() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 104);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1496 grunt");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 106) {
            mission.tick();
        }
        assertEquals(31, grunt.tileX());
        assertEquals(40, grunt.tileY());
        assertEquals(0, grunt.pathLength());
        assertEquals(5, grunt.battleNetCollisionCounter());

        mission.tick();
        assertEquals(107, fixtureCycle(world));
        assertEquals(32, grunt.tileX(),
                "the successful replacement route commits north-east");
        assertEquals(39, grunt.tileY());
        assertEquals(Direction.fromDelta(1, -1),
                grunt.lastStepHeading());
        assertEquals(BattleNetPathFinder.MAX_PATH - 1,
                grunt.pathLength(),
                "the non-empty replacement retains its full route tail");

        // The same unit later pays a nearly-full nineteen-byte wall route.
        // Its accepted SE head must retain collision three; when the cached NE
        // tail meets the still-occupied formation square, fixture 303 advances
        // that generation to four and parks RI20. The paid writer keeps that
        // refused square hard and commits a fresh north head on fixture 304.
        while (fixtureCycle(world) < 303) {
            mission.tick();
        }
        assertEquals(37, grunt.tileX());
        assertEquals(40, grunt.tileY());
        assertEquals(0, grunt.pathLength());
        assertEquals(4, grunt.battleNetCollisionCounter());
        assertEquals(0, grunt.battleNetRefusals());
        assertEquals(world.idle.battleNetSequenceStart(grunt,
                        BattleNetSequence.MOVE_ANIMATION),
                grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer());

        mission.tick();
        assertEquals(304, fixtureCycle(world));
        assertEquals(37, grunt.tileX());
        assertEquals(39, grunt.tileY(),
                "the paid hard-square writer must commit north immediately");
        assertEquals(Direction.fromDelta(0, -1), grunt.lastStepHeading());
        assertEquals(4, grunt.battleNetCollisionCounter());
        assertTrue(grunt.pathLength() > 0,
                "the north commit retains the replacement wall tail");
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
        Unit knight = unitById(world, 125);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1517 grunt");
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
    @DisplayName("a capacity retarget tail keeps its paid cooperative Move band")
    void capacityRetargetTailKeepsItsPaidCooperativeMoveBand() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Sealed native slot 1496 / Java 104 changes to footman 1477 while
        // consuming the first NE byte of a capacity route. Once that leg
        // settles, Attack construction counts 3,2,1 on fixtures 253..255.
        // The retained east head is occupied by mid-stride allied slot 1494,
        // so fixture 256 keeps all eighteen bytes and opens Move 15 instead
        // of parking the cursor and taking a fresh south-east route on 257.
        Unit grunt = unitById(world, 104);
        Unit footman = unitById(world, 123);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1496 grunt");
        assertNotNull(footman, "XHuman 12 has no native-slot-1477 footman");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 253) {
            mission.tick();
        }

        assertEquals(36, grunt.tileX());
        assertEquals(39, grunt.tileY());
        assertEquals(footman, grunt.target());
        assertEquals(BattleNetPathFinder.MAX_PATH - 2, grunt.pathLength());
        assertEquals(BattleNetPathFinder.MAX_PATH - 1,
                grunt.battleNetPathInitialLength());
        assertEquals(1, grunt.battleNetPathStepsTaken());
        assertEquals(world.idle.battleNetSequenceStart(grunt,
                        BattleNetSequence.ATTACK_ANIMATION),
                grunt.battleNetSequenceOffset());
        assertEquals(3, grunt.battleNetAnimationTimer());

        mission.tick();
        assertEquals(254, fixtureCycle(world));
        assertEquals(2, grunt.battleNetAnimationTimer());
        mission.tick();
        assertEquals(255, fixtureCycle(world));
        assertEquals(1, grunt.battleNetAnimationTimer());
        mission.tick();
        assertEquals(256, fixtureCycle(world));
        assertEquals(36, grunt.tileX());
        assertEquals(39, grunt.tileY());
        assertEquals(BattleNetPathFinder.MAX_PATH - 2, grunt.pathLength(),
                "the paid band retains the cached east-led tail");
        assertEquals(1, grunt.battleNetCollisionCounter());
        assertEquals(world.idle.battleNetSequenceStart(grunt,
                        BattleNetSequence.MOVE_ANIMATION),
                grunt.battleNetSequenceOffset());
        assertEquals(15, grunt.battleNetAnimationTimer());

        mission.tick();
        assertEquals(257, fixtureCycle(world));
        assertEquals(36, grunt.tileX(),
                "the first paid quiet visit must not redraw south-east");
        assertEquals(39, grunt.tileY());
        assertEquals(14, grunt.battleNetAnimationTimer());
        assertEquals(BattleNetPathFinder.MAX_PATH - 2, grunt.pathLength());

        while (fixtureCycle(world) < 270) {
            mission.tick();
        }
        assertEquals(36, grunt.tileX());
        assertEquals(39, grunt.tileY());
        assertEquals(BattleNetPathFinder.MAX_PATH - 2, grunt.pathLength());
        assertEquals(1, grunt.battleNetCollisionCounter());
        assertEquals(1, grunt.battleNetAnimationTimer());

        mission.tick();
        assertEquals(271, fixtureCycle(world));
        assertEquals(36, grunt.tileX());
        assertEquals(39, grunt.tileY());
        assertEquals(0, grunt.pathLength(),
                "the paid cursor parks at native route index twenty");
        assertEquals(2, grunt.battleNetCollisionCounter());
        assertTrue(grunt.battleNetRetargetResidualParkRefill());
        assertEquals(1, grunt.battleNetRetargetResidualParkSteps());
        assertEquals(world.idle.battleNetSequenceStart(grunt,
                        BattleNetSequence.MOVE_ANIMATION),
                grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer());

        mission.tick();
        assertEquals(272, fixtureCycle(world));
        assertEquals(36, grunt.tileX(),
                "the replacement's occupied south-east head opens a paid band");
        assertEquals(39, grunt.tileY());
        assertEquals(BattleNetPathFinder.MAX_PATH - 1, grunt.pathLength());
        assertEquals(3, grunt.battleNetCollisionCounter());
        assertEquals(14, grunt.battleNetOrderDelay());
        assertEquals(world.idle.battleNetSequenceStart(grunt,
                        BattleNetSequence.MOVE_ANIMATION),
                grunt.battleNetSequenceOffset());
        assertEquals(15, grunt.battleNetAnimationTimer());
        int[] replacement = {
                3, 1, 1, 2, 3, 3, 3, 4, 4, 5,
                5, 6, 6, 6, 7, 7, 7, 6, 6
        };
        for (int depth = 0; depth < replacement.length; depth++) {
            assertEquals(replacement[depth],
                    grunt.peekHeadingAtDepth(depth),
                    "native retained-face route differs at depth " + depth);
        }
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
    @DisplayName("a pressured capacity tail redraws before its immediate retarget step")
    void pressuredCapacityTailRedrawsBeforeItsImmediateRetargetStep() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 99);
        Unit footman = unitById(world, 123);
        Unit knight = unitById(world, 125);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1501 grunt");
        assertNotNull(footman, "XHuman 12 has no native-slot-1477 footman");
        assertNotNull(knight, "XHuman 12 has no native-slot-1475 knight");

        // Native slot 1501 finishes the pixels of its fifth east-led route
        // byte with one collision/refusal generation attached. On fixture
        // 258 AutoSelectTarget replaces knight 1475 with footman 1477, NewPath
        // writes 00,01,03,01,02,02,03,03,03,04,05,05,06,06,06,07,07,06,06,06,
        // and MoveToTarget consumes north in that same callback. Treating the
        // old capacity tail as a newly completed refusal band softens the
        // formation, draws an occupied east head, and parks the fighter.
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 257) {
            mission.tick();
        }

        assertEquals(knight, grunt.target());
        assertEquals(35, grunt.tileX());
        assertEquals(40, grunt.tileY());
        assertEquals(BattleNetPathFinder.MAX_PATH - 5, grunt.pathLength());
        assertEquals(BattleNetPathFinder.MAX_PATH,
                grunt.battleNetPathInitialLength());
        assertEquals(5, grunt.battleNetPathStepsTaken());
        assertEquals(1, grunt.battleNetCollisionCounter());
        assertEquals(1, grunt.battleNetRefusals());

        mission.tick();
        assertEquals(258, fixtureCycle(world));
        assertEquals(footman, grunt.target(),
                "the settled scan must publish native's replacement quarry");
        assertEquals(35, grunt.tileX());
        assertEquals(39, grunt.tileY(),
                "the replacement route must consume north immediately");
        assertEquals(Direction.fromDelta(0, -1), grunt.lastStepHeading());
        assertEquals(BattleNetPathFinder.MAX_PATH - 1, grunt.pathLength());
        assertEquals(BattleNetPathFinder.MAX_PATH,
                grunt.battleNetPathInitialLength());
        assertEquals(1, grunt.battleNetPathStepsTaken());
        int[] retained = {
                1, 3, 1, 2, 2, 3, 3, 3, 4, 5,
                5, 6, 6, 6, 7, 7, 6, 6, 6
        };
        for (int depth = 0; depth < retained.length; depth++) {
            assertEquals(retained[depth], grunt.peekHeadingAtDepth(depth),
                    "native replacement route differs at retained depth " + depth);
        }
        assertEquals(0, grunt.battleNetCollisionCounter());
        assertEquals(0, grunt.battleNetRefusals());
        assertEquals(world.idle.battleNetSequenceStart(grunt,
                        BattleNetSequence.MOVE_ANIMATION) + 3,
                grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer());

        while (fixtureCycle(world) < 273) {
            mission.tick();
        }
        assertEquals(footman, grunt.target());
        assertEquals(35, grunt.tileX());
        assertEquals(39, grunt.tileY());
        assertEquals(BattleNetPathFinder.MAX_PATH - 1, grunt.pathLength());
        assertEquals(0x6e32f013L, Integer.toUnsignedLong(world.randomSeed()),
                "the settled north residual must not debit sync RNG");

        mission.tick();
        assertEquals(274, fixtureCycle(world));
        assertEquals(footman, grunt.target());
        assertEquals(world.idle.battleNetSequenceStart(grunt,
                        BattleNetSequence.ATTACK_ANIMATION),
                grunt.battleNetSequenceOffset());
        assertEquals(3, grunt.battleNetAnimationTimer());
        assertEquals(BattleNetPathFinder.MAX_PATH - 1, grunt.pathLength(),
                "Attack construction retains the paid old route bytes");
        assertEquals(0x6e32f013L, Integer.toUnsignedLong(world.randomSeed()),
                "opening Attack construction owns no sync draw");

        while (fixtureCycle(world) < 276) {
            mission.tick();
        }
        assertEquals(footman, grunt.target());
        assertEquals(1, grunt.battleNetAnimationTimer());
        assertEquals(0xf2883250L, Integer.toUnsignedLong(world.randomSeed()));

        mission.tick();
        assertEquals(277, fixtureCycle(world));
        assertEquals(knight, grunt.target(),
                "timer one must publish the fresh native quarry");
        assertEquals(36, grunt.tileX());
        assertEquals(40, grunt.tileY(),
                "the fresh route must consume southeast, not stale northeast");
        assertEquals(Direction.fromDelta(1, 1), grunt.lastStepHeading());
        assertEquals(BattleNetPathFinder.MAX_PATH - 1, grunt.pathLength());
        assertEquals(BattleNetPathFinder.MAX_PATH,
                grunt.battleNetPathInitialLength());
        assertEquals(1, grunt.battleNetPathStepsTaken());
        int[] returnedKnightTail = {
                2, 1, 1, 1, 3, 3, 3, 4, 4, 4,
                5, 5, 6, 6, 6, 7, 7, 6, 6
        };
        for (int depth = 0; depth < returnedKnightTail.length; depth++) {
            assertEquals(returnedKnightTail[depth],
                    grunt.peekHeadingAtDepth(depth),
                    "native returned-knight route differs at retained depth "
                            + depth);
        }
        assertEquals(0, grunt.battleNetCollisionCounter());
        assertEquals(0, grunt.battleNetRefusals());
        assertEquals(world.idle.battleNetSequenceStart(grunt,
                        BattleNetSequence.MOVE_ANIMATION) + 3,
                grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer());
        assertEquals(0xf2883250L, Integer.toUnsignedLong(world.randomSeed()),
                "the replacement route/step boundary owns no sync draw");
    }

    @Test
    @DisplayName("an unoffered four-byte melee ray parks before its compact wall pair")
    void unofferedFourByteMeleeRayParksBeforeCompactWallPair() {
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
        Unit saturatedGrunt = unitById(world, 96);
        Unit coldDiagonalGrunt = unitById(world, 119);
        Unit knight = unitById(world, 125);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1517 grunt");
        assertNotNull(saturatedGrunt,
                "XHuman 12 has no native-slot-1504 grunt");
        assertNotNull(coldDiagonalGrunt,
                "XHuman 12 has no native-slot-1481 grunt");
        assertNotNull(knight, "XHuman 12 has no native-slot-1475 knight");

        // Native slot 1517 finishes Attack construction with a cached
        // E,E,SE,S,SW tail and no live hit offer. Fixture 246 parks that old
        // route at RI 20. Fixture 247 writes SE,SE,SW,W, refuses the first
        // SE, and owns a complete Move 15..1 band. The timer-one wake on 262
        // parks that same face again; 263 writes the compact SE,SW wall pair
        // and commits its first byte immediately.
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 245) {
            mission.tick();
        }

        assertEquals(knight, grunt.target());
        assertNull(grunt.offeredTarget());
        assertEquals(30, grunt.tileX());
        assertEquals(37, grunt.tileY());
        assertEquals(4, grunt.pathLength());
        assertEquals(2539, grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer());

        mission.tick();
        assertEquals(246, fixtureCycle(world));
        assertEquals(0, grunt.pathLength(),
                "the unoffered old buffer is parked before replacement planning");
        assertEquals(1, grunt.battleNetCollisionCounter());
        assertEquals(2482, grunt.battleNetSequenceOffset());
        assertEquals(1, grunt.battleNetAnimationTimer());

        mission.tick();
        assertEquals(247, fixtureCycle(world));
        assertEquals(4, grunt.pathLength());
        assertEquals(4, grunt.battleNetPathInitialLength());
        assertEquals(0, grunt.battleNetPathStepsTaken());
        assertEquals(3, grunt.peekHeadingAtDepth(0));
        assertEquals(3, grunt.peekHeadingAtDepth(1));
        assertEquals(5, grunt.peekHeadingAtDepth(2));
        assertEquals(6, grunt.peekHeadingAtDepth(3));
        assertEquals(2, grunt.battleNetCollisionCounter());
        assertEquals(0, grunt.battleNetRefusals());
        assertEquals(2482, grunt.battleNetSequenceOffset());
        assertEquals(15, grunt.battleNetAnimationTimer());

        while (fixtureCycle(world) < 261) {
            mission.tick();
        }
        assertEquals(30, grunt.tileX());
        assertEquals(37, grunt.tileY());
        assertEquals(4, grunt.pathLength());
        assertEquals(4, grunt.battleNetPathInitialLength());
        assertEquals(0, grunt.battleNetPathStepsTaken());
        assertEquals(2, grunt.battleNetCollisionCounter());
        assertEquals(0, grunt.battleNetRefusals());
        assertTrue(grunt.stepDrained());
        assertEquals(2, grunt.lastStepHeading());
        assertEquals(3, grunt.peekHeading());
        assertEquals(1, grunt.battleNetAnimationTimer());
        assertEquals(30, saturatedGrunt.tileX());
        assertEquals(39, saturatedGrunt.tileY());
        assertEquals(BattleNetPathFinder.MAX_PATH - 1,
                saturatedGrunt.pathLength());
        assertEquals(BattleNetPathFinder.MAX_PATH,
                saturatedGrunt.battleNetPathInitialLength());
        assertEquals(1, saturatedGrunt.battleNetPathStepsTaken());
        assertEquals(4, saturatedGrunt.battleNetCollisionCounter());
        assertEquals(0, saturatedGrunt.battleNetRefusals());
        assertEquals(2534, saturatedGrunt.battleNetSequenceOffset());
        assertEquals(1, saturatedGrunt.battleNetAnimationTimer());

        mission.tick();
        assertEquals(262, fixtureCycle(world));
        assertEquals(0, grunt.pathLength());
        assertEquals(3, grunt.battleNetCollisionCounter());
        assertEquals(1, grunt.battleNetRefusals());
        assertEquals(1, grunt.battleNetAnimationTimer());
        assertEquals(30, saturatedGrunt.tileX());
        assertEquals(39, saturatedGrunt.tileY());
        assertEquals(0, saturatedGrunt.pathLength(),
                "the saturated cached tail parks before its paid redraw");
        assertEquals(5, saturatedGrunt.battleNetCollisionCounter());
        assertEquals(0, saturatedGrunt.battleNetRefusals());
        assertEquals(2482, saturatedGrunt.battleNetSequenceOffset());
        assertEquals(1, saturatedGrunt.battleNetAnimationTimer());

        mission.tick();
        assertEquals(263, fixtureCycle(world));
        assertEquals(31, grunt.tileX());
        assertEquals(38, grunt.tileY(),
                "the compact replacement pair commits southeast immediately");
        assertEquals(Direction.fromDelta(1, 1), grunt.lastStepHeading());
        assertEquals(1, grunt.pathLength());
        assertEquals(Direction.fromDelta(-1, 1), grunt.peekHeading());
        assertEquals(3, grunt.battleNetCollisionCounter());
        assertEquals(30, saturatedGrunt.tileX());
        assertEquals(40, saturatedGrunt.tileY(),
                "the saturated replacement ray commits south immediately");
        assertEquals(Direction.fromDelta(0, 1),
                saturatedGrunt.lastStepHeading());
        assertEquals(1, saturatedGrunt.pathLength());
        assertEquals(Direction.fromDelta(0, 1),
                saturatedGrunt.peekHeading());
        assertEquals(5, saturatedGrunt.battleNetCollisionCounter());

        // Slot 1504 drains the first south byte through fixture 279, where
        // the second south is refused and the compact buffer is parked. The
        // next visit clears collision six and enters a cold Attack constructor
        // loop. A free south-east neighbour is deliberately not a route: the
        // loop re-faces toward the moving knight but keeps RI 20 and spends no
        // additional sync draw.
        while (fixtureCycle(world) < 279) {
            mission.tick();
        }
        assertEquals(knight, saturatedGrunt.target());
        assertEquals(30, saturatedGrunt.tileX());
        assertEquals(40, saturatedGrunt.tileY());
        assertEquals(0, saturatedGrunt.pathLength());
        assertEquals(6, saturatedGrunt.battleNetCollisionCounter());
        assertEquals(0, saturatedGrunt.battleNetRefusals());
        assertEquals(world.idle.battleNetSequenceStart(saturatedGrunt,
                        BattleNetSequence.MOVE_ANIMATION),
                saturatedGrunt.battleNetSequenceOffset());
        assertEquals(1, saturatedGrunt.battleNetAnimationTimer());
        assertEquals(0xfd31fc49L, Integer.toUnsignedLong(world.randomSeed()));

        mission.tick();
        assertEquals(280, fixtureCycle(world));
        assertEquals(30, saturatedGrunt.tileX());
        assertEquals(40, saturatedGrunt.tileY(),
                "the saturated cardinal park must not invent a diagonal escape");
        assertEquals(0, saturatedGrunt.pathLength());
        assertEquals(0, saturatedGrunt.battleNetCollisionCounter());
        assertEquals(world.idle.battleNetSequenceStart(saturatedGrunt,
                        BattleNetSequence.ATTACK_ANIMATION),
                saturatedGrunt.battleNetSequenceOffset());
        assertEquals(3, saturatedGrunt.battleNetAnimationTimer());
        assertEquals(0x1046237cL, Integer.toUnsignedLong(world.randomSeed()),
                "the wrapped cardinal constructor owns no sync draw");
        assertEquals(0x0957ada5, world.battleNetRandomSeed(),
                "fixture 280 owns native's active-order idle draw");

        while (fixtureCycle(world) < 282) {
            mission.tick();
        }
        assertEquals(1, saturatedGrunt.battleNetAnimationTimer());
        mission.tick();
        assertEquals(283, fixtureCycle(world));
        assertEquals(30, saturatedGrunt.tileX());
        assertEquals(40, saturatedGrunt.tileY());
        assertEquals(3, saturatedGrunt.battleNetAnimationTimer(),
                "a still-blocked direct face reopens Attack construction");
        assertEquals(0x3a951405L, Integer.toUnsignedLong(world.randomSeed()),
                "the recurring constructor also owns no sync draw");

        mission.tick();
        assertEquals(284, fixtureCycle(world));
        assertEquals(knight, coldDiagonalGrunt.target());
        assertEquals(30, coldDiagonalGrunt.tileX());
        assertEquals(41, coldDiagonalGrunt.tileY());
        assertEquals(0, coldDiagonalGrunt.pathLength());
        assertEquals(world.idle.battleNetSequenceStart(coldDiagonalGrunt,
                        BattleNetSequence.ATTACK_ANIMATION),
                coldDiagonalGrunt.battleNetSequenceOffset());
        assertEquals(3, coldDiagonalGrunt.battleNetAnimationTimer(),
                "the unqueued paid diagonal enters cold Attack construction");
        assertTrue(coldDiagonalGrunt.battleNetColdNoProgressRefusalLoop());
        assertEquals(0x179cfada, world.battleNetRandomSeed(),
                "fixture 284 debits the diagonal Still promotion");

        while (fixtureCycle(world) < 287) {
            mission.tick();
        }
        assertEquals(3, coldDiagonalGrunt.battleNetAnimationTimer(),
                "the boxed diagonal promotion repeats every three visits");
        assertEquals(0xe39784ed, world.battleNetRandomSeed(),
                "fixture 287 retains the authenticated async ledger");

        while (fixtureCycle(world) < 290) {
            mission.tick();
        }
        assertEquals(3, coldDiagonalGrunt.battleNetAnimationTimer());
        assertEquals(0x5a765747, world.battleNetRandomSeed(),
                "all cycle-290 melee rolls receive their native draw values");

        while (fixtureCycle(world) < 300) {
            mission.tick();
        }
        assertEquals(30, saturatedGrunt.tileX());
        assertEquals(40, saturatedGrunt.tileY());
        assertEquals(1, saturatedGrunt.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("cycle-264 paid retargets keep their authenticated formation routes")
    void unofferedCollisionFourRetargetKeepsOuterFormationRoute() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 106);
        Unit buildingRetargetGrunt = unitById(world, 120);
        Unit cleanBuildingRetargetGrunt = unitById(world, 121);
        Unit knight = unitById(world, 125);
        Unit footman = unitById(world, 123);
        Unit guardTower = unitById(world, 117);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1494 grunt");
        assertNotNull(buildingRetargetGrunt,
                "XHuman 12 has no native-slot-1480 grunt");
        assertNotNull(cleanBuildingRetargetGrunt,
                "XHuman 12 has no native-slot-1479 grunt");
        assertNotNull(knight, "XHuman 12 has no native-slot-1475 knight");
        assertNotNull(footman, "XHuman 12 has no native-slot-1477 footman");
        assertNotNull(guardTower,
                "XHuman 12 has no native-slot-1483 guard tower");

        // Native slot 1494 settles the eleventh byte of its collision-four
        // knight route on fixture 245. With no live hit offer, target scan
        // selects footman 1477 and NewPath keeps unrefused collision-marked
        // formation peers solid. The resulting outer route begins with three
        // north-east bytes. One commits immediately, Attack construction owns
        // fixtures 261..263, and the second commits on fixture 264. Repeating
        // the last heading after construction can mimic the final coordinate,
        // but loses the authenticated eighteen-byte route and is explicitly
        // excluded by the retained-buffer assertions below.
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 244) {
            mission.tick();
        }
        assertEquals(knight, grunt.target());
        assertEquals(36, grunt.tileX());
        assertEquals(40, grunt.tileY());
        assertEquals(4, grunt.battleNetCollisionCounter());

        mission.tick();
        assertEquals(245, fixtureCycle(world));
        assertEquals(footman, grunt.target());
        assertEquals(37, grunt.tileX());
        assertEquals(39, grunt.tileY());
        assertEquals(Direction.fromDelta(1, -1), grunt.lastStepHeading());
        assertEquals(18, grunt.battleNetPathInitialLength());
        assertEquals(1, grunt.battleNetPathStepsTaken());
        int[] retained = {
                1, 1, 3, 3, 2, 3, 4, 5, 4,
                5, 6, 6, 6, 7, 7, 6, 6
        };
        assertEquals(retained.length, grunt.pathLength());
        for (int depth = 0; depth < retained.length; depth++) {
            assertEquals(retained[depth], grunt.peekHeadingAtDepth(depth),
                    "native outer route differs at retained depth " + depth);
        }
        assertEquals(0, grunt.battleNetCollisionCounter());
        assertEquals(0, grunt.battleNetRefusals());

        while (fixtureCycle(world) < 261) {
            mission.tick();
        }
        for (int fixture = 261; fixture <= 263; fixture++) {
            assertEquals(world.idle.battleNetSequenceStart(grunt,
                            BattleNetSequence.ATTACK_ANIMATION),
                    grunt.battleNetSequenceOffset());
            assertEquals(264 - fixture, grunt.battleNetAnimationTimer(),
                    "native exposes the retained-route constructor as 3,2,1");
            assertEquals(retained.length, grunt.pathLength(),
                    "Attack construction must retain every cached heading");
            if (fixture < 263) {
                mission.tick();
            }
        }

        // Native slot 1480 reaches the same fixture through a different paid
        // seam: fourteen bytes of a collision-three knight route remain when
        // target scan selects guard tower 1483. NewPath softens the collided
        // formation rank, but keeps the long uncollided grunt which is moving
        // on the direct south-west approach diagonal solid. That exact view
        // writes SW,W,W,SW,S,S. Broadly softening the rank writes SW,S,SW,W;
        // treating every mover as solid writes SW,W,W,SW,SE.
        assertEquals(knight, buildingRetargetGrunt.target());
        assertEquals(32, buildingRetargetGrunt.tileX());
        assertEquals(36, buildingRetargetGrunt.tileY());
        assertEquals(14, buildingRetargetGrunt.pathLength());
        assertEquals(3,
                buildingRetargetGrunt.battleNetCollisionCounter());
        assertEquals(knight, cleanBuildingRetargetGrunt.target());
        assertEquals(30, cleanBuildingRetargetGrunt.tileX());
        assertEquals(36, cleanBuildingRetargetGrunt.tileY());
        assertEquals(16, cleanBuildingRetargetGrunt.pathLength());
        assertEquals(3,
                cleanBuildingRetargetGrunt.battleNetCollisionCounter());

        mission.tick();
        assertEquals(264, fixtureCycle(world));
        assertEquals(38, grunt.tileX());
        assertEquals(38, grunt.tileY(),
                "the constructor wake commits the cached north-east byte");
        assertEquals(Direction.fromDelta(1, -1), grunt.lastStepHeading());
        assertEquals(retained.length - 1, grunt.pathLength());

        assertEquals(guardTower, buildingRetargetGrunt.target(),
                "the residual scan must publish native's building replacement");
        assertEquals(31, buildingRetargetGrunt.tileX());
        assertEquals(37, buildingRetargetGrunt.tileY(),
                "the paid building route commits south-west immediately");
        assertEquals(Direction.fromDelta(-1, 1),
                buildingRetargetGrunt.lastStepHeading());
        assertEquals(6,
                buildingRetargetGrunt.battleNetPathInitialLength());
        assertEquals(1,
                buildingRetargetGrunt.battleNetPathStepsTaken());
        int[] buildingRetained = {6, 6, 5, 4, 4};
        assertEquals(buildingRetained.length,
                buildingRetargetGrunt.pathLength());
        for (int depth = 0; depth < buildingRetained.length; depth++) {
            assertEquals(buildingRetained[depth],
                    buildingRetargetGrunt.peekHeadingAtDepth(depth),
                    "native building route differs at retained depth " + depth);
        }
        assertEquals(0,
                buildingRetargetGrunt.battleNetCollisionCounter());
        assertEquals(world.idle.battleNetSequenceStart(buildingRetargetGrunt,
                        BattleNetSequence.MOVE_ANIMATION) + 3,
                buildingRetargetGrunt.battleNetSequenceOffset());
        assertEquals(1,
                buildingRetargetGrunt.battleNetAnimationTimer());

        mission.tick();
        assertEquals(265, fixtureCycle(world));
        assertEquals(guardTower, cleanBuildingRetargetGrunt.target(),
                "the next paid residual publishes the same tower");
        assertEquals(29, cleanBuildingRetargetGrunt.tileX());
        assertEquals(37, cleanBuildingRetargetGrunt.tileY(),
                "the one-square approach corridor keeps native's south-west head");
        assertEquals(Direction.fromDelta(-1, 1),
                cleanBuildingRetargetGrunt.lastStepHeading());
        assertEquals(3,
                cleanBuildingRetargetGrunt.battleNetPathInitialLength());
        assertEquals(1,
                cleanBuildingRetargetGrunt.battleNetPathStepsTaken());
        assertEquals(2, cleanBuildingRetargetGrunt.pathLength());
        assertEquals(Direction.fromDelta(-1, 1),
                cleanBuildingRetargetGrunt.peekHeadingAtDepth(0));
        assertEquals(Direction.fromDelta(0, 1),
                cleanBuildingRetargetGrunt.peekHeadingAtDepth(1));
        assertEquals(0,
                cleanBuildingRetargetGrunt.battleNetCollisionCounter());
        assertEquals(world.idle.battleNetSequenceStart(
                        cleanBuildingRetargetGrunt,
                        BattleNetSequence.MOVE_ANIMATION) + 3,
                cleanBuildingRetargetGrunt.battleNetSequenceOffset());
        assertEquals(1,
                cleanBuildingRetargetGrunt.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("an unspent paid formation route executes its opened head after construction")
    void unspentPaidFormationRouteExecutesItsOpenedHeadAfterConstruction() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit grunt = unitById(world, 99);
        Unit footman = unitById(world, 123);
        assertNotNull(grunt,
                "XHuman 12 has no native-slot-1501 grunt");
        assertNotNull(footman,
                "XHuman 12 has no native-slot-1477 footman");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 296) {
            mission.tick();
        }

        // Native changes quarry, writes a full N-led formation route, and
        // pays Move 15 while the north square is occupied. No heading has
        // been consumed from this route yet.
        assertEquals(footman, grunt.target());
        assertEquals(36, grunt.tileX());
        assertEquals(40, grunt.tileY());
        assertEquals(BattleNetPathFinder.MAX_PATH,
                grunt.battleNetPathInitialLength());
        assertEquals(0, grunt.battleNetPathStepsTaken());
        assertEquals(BattleNetPathFinder.MAX_PATH, grunt.pathLength());
        assertEquals(Direction.fromDelta(0, -1), grunt.peekHeading());
        assertEquals(world.idle.battleNetSequenceStart(grunt,
                        BattleNetSequence.MOVE_ANIMATION),
                grunt.battleNetSequenceOffset());
        assertEquals(15, grunt.battleNetAnimationTimer());
        assertEquals(0xba394867, world.randomSeed());

        while (fixtureCycle(world) < 311) {
            mission.tick();
        }
        for (int fixture = 311; fixture <= 313; fixture++) {
            assertEquals(world.idle.battleNetSequenceStart(grunt,
                            BattleNetSequence.ATTACK_ANIMATION),
                    grunt.battleNetSequenceOffset());
            assertEquals(314 - fixture, grunt.battleNetAnimationTimer(),
                    "native exposes the paid constructor as 3,2,1");
            assertEquals(BattleNetPathFinder.MAX_PATH, grunt.pathLength(),
                    "construction must retain the unspent route");
            if (fixture < 313) {
                mission.tick();
            }
        }

        mission.tick();
        assertEquals(314, fixtureCycle(world));
        assertEquals(36, grunt.tileX());
        assertEquals(39, grunt.tileY(),
                "the opened north head must execute instead of parking again");
        assertEquals(Direction.fromDelta(0, -1), grunt.lastStepHeading());
        assertEquals(BattleNetPathFinder.MAX_PATH - 1, grunt.pathLength());
        assertEquals(Direction.fromDelta(1, -1), grunt.peekHeading(),
                "the authenticated north-east tail must remain cached");
        assertEquals(0x7e7cdd5f, world.randomSeed(),
                "the constructor wake owns no sync draw");
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
