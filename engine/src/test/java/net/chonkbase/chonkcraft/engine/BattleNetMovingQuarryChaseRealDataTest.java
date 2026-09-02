package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class BattleNetMovingQuarryChaseRealDataTest {

    private static final int INITIALIZATION_TICKS = 2;

    @Test
    void aHuman8AttackPeasantKeepsPaceWithItsMovingQuarry() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit attacker = unitAt(mission.world(), 4,
                "unit-attack-peasant", 68, 67);
        Unit quarry = unitAt(mission.world(), 2,
                "unit-peasant", 69, 68);
        assertNotNull(attacker);
        assertNotNull(quarry);

        while (fixtureCycle(mission.world()) < 21) {
            mission.tick();
        }
        assertChaser(attacker, 70, 67, -32, 0, true);
        assertEquals(30, quarry.hitPoints(),
                "the moving quarry cannot be struck from its former tile");

        while (fixtureCycle(mission.world()) < 37) {
            mission.tick();
        }
        assertChaser(attacker, 71, 66, -32, 32, true);

        while (fixtureCycle(mission.world()) < 114) {
            mission.tick();
        }
        assertChaser(attacker, 75, 62, -7, 7, true);
        assertFalse(quarry.isMoving(),
                "retail's quarry has just settled at fixture 114");
        assertEquals(30, quarry.hitPoints());

        while (fixtureCycle(mission.world()) < 117) {
            mission.tick();
        }
        assertChaser(attacker, 75, 62, 0, 0, false);
        assertEquals(30, quarry.hitPoints(),
                "the first swing begins only after the pursuer settles");

        while (fixtureCycle(mission.world()) < 127) {
            mission.tick();
        }
        assertEquals(25, quarry.hitPoints(),
                "retail's first legal blow lands at fixture 127");
    }

    @Test
    void aHuman8AttackPeasantFinishesRecoveryBeforeChasing() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit attacker = unitAt(mission.world(), 4,
                "unit-attack-peasant", 70, 72);
        assertNotNull(attacker);

        while (fixtureCycle(mission.world()) < 57) {
            mission.tick();
        }
        assertChaser(attacker, 70, 72, 0, 0, false);

        mission.tick();
        assertChaser(attacker, 71, 71, -32, 32, true);
        assertEquals(2, attacker.pathLength(),
                "native keeps the NE,E approach tail after its first step");
    }

    @Test
    void aHuman8AttackPeasantLetsMoveTimerOneOwnItsQuietVisit() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit attacker = unitAt(mission.world(), 4,
                "unit-attack-peasant", 77, 69);
        assertNotNull(attacker);

        while (fixtureCycle(mission.world()) < 87) {
            mission.tick();
        }
        assertChaser(attacker, 74, 65, 0, 0, false);
        assertEquals(1, attacker.pathLength());

        mission.tick();
        assertChaser(attacker, 74, 65, 0, 0, false);
        assertEquals(1, attacker.pathLength(),
                "the timer-one Move visit retains the cached NW heading");

        mission.tick();
        assertChaser(attacker, 73, 64, 32, 32, true);
        assertEquals(0, attacker.pathLength());

        while (fixtureCycle(mission.world()) < 104) {
            mission.tick();
        }
        assertChaser(attacker, 73, 64, 0, 0, false);

        mission.tick();
        assertChaser(attacker, 72, 65, 32, -32, true);
        assertEquals(1, attacker.pathLength(),
                "the exhausted chase must retain the second replacement heading");
        assertNotNull(attacker.target());
        assertEquals(72, attacker.target().tileX(),
                "the fixture-105 Move OP0 retargets to the nearer peasant");
        assertEquals(66, attacker.target().tileY(),
                "the replacement quarry is the peasant on 72,66");

        while (fixtureCycle(mission.world()) < 121) {
            mission.tick();
        }
        assertChaser(attacker, 72, 65, 0, 0, false);
        assertEquals(1, attacker.pathLength(),
                "the settled chase keeps its final route byte through Attack construction");
        assertEquals(2657, attacker.battleNetSequenceOffset(),
                "the final pixel yields to BNE's Attack program before another tile step");
        assertEquals(3, attacker.battleNetAnimationTimer());
    }

    @Test
    void aHuman8HiddenMinerRetargetPaysFreshArrivalConstruction() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (fixtureCycle(mission.world()) < 129) {
            mission.tick();
        }
        Unit attacker = unitAt(mission.world(), 4,
                "unit-attack-peasant", 78, 61);
        Unit quarry = unitAt(mission.world(), 2,
                "unit-peasant", 76, 61);
        assertNotNull(attacker,
                "native slot 1538 has not finished its prior attack loop");
        assertNotNull(quarry, "native slot 1525 is the replacement quarry");

        mission.tick();
        assertChaser(attacker, 77, 61, 32, 0, true);
        assertEquals(quarry, attacker.target(),
                "the mine-contained worker is replaced before the west chase");

        while (fixtureCycle(mission.world()) < 145) {
            mission.tick();
        }
        assertChaser(attacker, 77, 61, 2, 0, true);
        assertEquals(2652, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());

        mission.tick();
        assertChaser(attacker, 77, 61, 0, 0, false);
        assertEquals(2657, attacker.battleNetSequenceOffset(),
                "the replacement quarry owns fresh Attack construction");
        assertEquals(3, attacker.battleNetAnimationTimer(),
                "native counts construction 3,2,1 after the chase settles");

        while (fixtureCycle(mission.world()) < 156) {
            mission.tick();
        }
        assertEquals(19, quarry.hitPoints(),
                "the second attacker must not strike three fixtures early");

        while (fixtureCycle(mission.world()) < 159) {
            mission.tick();
        }
        assertEquals(14, quarry.hitPoints(),
                "native slot 1538 lands its first replacement blow at 159");
    }

    @Test
    void aMovingQuarryKeepsOwnershipOfItsCollidedChaseHold() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit attacker = unitAt(mission.world(), 4,
                "unit-attack-peasant", 77, 69);
        assertNotNull(attacker);

        while (fixtureCycle(mission.world()) < 179) {
            mission.tick();
        }
        Unit quarry = attacker.target();
        assertNotNull(quarry);
        assertChaser(attacker, 73, 65, 0, 0, false);

        while (fixtureCycle(mission.world()) < 183) {
            mission.tick();
        }
        assertChaser(attacker, 73, 65, 0, 0, false);
        assertSame(quarry, attacker.target(),
                "a vacating ally must not release a hold drawn for the quarry's old tile");

        while (fixtureCycle(mission.world()) < 189) {
            mission.tick();
        }
        assertChaser(attacker, 73, 65, 0, 0, false);
        assertSame(quarry, attacker.target(),
                "a free stale heading must not release the quarry's paid Move countdown");

        while (fixtureCycle(mission.world()) < 194) {
            mission.tick();
        }
        assertChaser(attacker, 73, 65, 0, 0, false);

        mission.tick();
        assertChaser(attacker, 74, 64, -32, 32, true);
        assertTrue(attacker.target() != quarry,
                "the paid Move OP0 must select the nearer live quarry");
        assertEquals(76, attacker.target().tileX());
        assertEquals(61, attacker.target().tileY());
        assertEquals(2603, attacker.battleNetSequenceOffset(),
                "fixture 195 spends the fresh route's north-east head");
        assertEquals(1, attacker.battleNetAnimationTimer());
    }

    @Test
    void anAdjacentMovingQuarryStartsWithTheCommittedAttackHold() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit attacker = unitAt(mission.world(), 4,
                "unit-attack-peasant", 87, 77);
        assertNotNull(attacker);
        assertFalse(attacker.type().firesMissile());

        while (fixtureCycle(mission.world()) < 172) {
            mission.tick();
        }
        Unit quarry = attacker.target();
        assertNotNull(quarry);
        assertTrue(quarry.isMoving());
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(3, attacker.battleNetAnimationTimer());
        assertTrue(attacker.battleNetAttackResumeFromMove(),
                "the Attack-tail replacement owns a committed OP0 hold");

        while (fixtureCycle(mission.world()) < 175) {
            mission.tick();
        }
        assertSame(quarry, attacker.target());
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(23, attacker.battleNetAnimationTimer(),
                "native parks at Attack start instead of swinging at a moving quarry");
    }

    @Test
    void aHuman8MeleeOp0RetargetEntersTheCommittedAttackHold() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        while (fixtureCycle(mission.world()) < 197) {
            mission.tick();
        }
        Unit attacker = unitAt(mission.world(), 4,
                "unit-attack-peasant", 86, 81);
        assertNotNull(attacker,
                "native slot 1501 has not completed its prior attack body");

        mission.tick();
        Unit quarry = unitAt(mission.world(), 2,
                "unit-peasant", 86, 82);
        assertNotNull(quarry, "native slot 1499 must still be chopping at 86,82");
        assertSame(quarry, attacker.target(),
                "the completed OP0 scan selects the adjacent chopping peasant");
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(3, attacker.battleNetAnimationTimer(),
                "the replacement quarry opens fresh Attack construction");

        while (fixtureCycle(mission.world()) < 201) {
            mission.tick();
        }
        assertEquals(2657, attacker.battleNetSequenceOffset(),
                "the paid constructor must remain at Attack start");
        assertEquals(23, attacker.battleNetAnimationTimer(),
                "native enters bodyWaitSum-1 after the OP0 retarget constructor");
        assertEquals(11, quarry.hitPoints());

        while (fixtureCycle(mission.world()) < 211) {
            mission.tick();
        }
        assertEquals(13, attacker.battleNetAnimationTimer(),
                "the native body hold is still draining at the old damage cycle");
        assertEquals(11, quarry.hitPoints(),
                "the chopping peasant must not take Java's premature fixture-211 blow");
        Unit critter = unitAt(mission.world(), 15,
                "unit-critter", 19, 54);
        assertNotNull(critter);
        assertEquals(Unit.Order.MOVE, critter.order(),
                "removing the phantom melee draw restores the native critter wander");
        assertEquals(20, critter.orderTargetX());
        assertEquals(55, critter.orderTargetY());
    }

    @Test
    void anExpiredQuarryHandsTimerOneConstructionToItsReplacementRoute() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit attacker = unitAt(mission.world(), 4,
                "unit-attack-peasant", 77, 69);
        assertNotNull(attacker);

        while (fixtureCycle(mission.world()) < 213) {
            mission.tick();
        }
        assertChaser(attacker, 74, 64, 0, 0, false);
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer(),
                "the expired quarry is validated on Attack construction timer one");

        Unit expired = attacker.target();
        mission.tick();
        assertEquals(214, fixtureCycle(mission.world()));
        assertChaser(attacker, 75, 63, -32, 32, true);
        assertTrue(attacker.target() != expired,
                "timer-one validation must name the live replacement quarry");
        assertEquals(2603, attacker.battleNetSequenceOffset(),
                "the already-paid construction hands directly to Move body");
    }

    @Test
    void adjacentExpiredQuarryHandoffsKeepTheirNativeSchedulerOrder() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        World world = mission.world();
        Unit native1505 = unitById(world, 95);
        Unit native1513 = unitById(world, 87);
        assertNotNull(native1505);
        assertNotNull(native1513);

        while (fixtureCycle(world) < 223) {
            mission.tick();
        }
        assertChaser(native1505, 76, 62, 0, 0, false);
        assertEquals(2657, native1505.battleNetSequenceOffset());
        assertEquals(3, native1505.battleNetAnimationTimer(),
                "slot 1505's replacement quarry opens fresh construction");
        assertChaser(native1513, 76, 63, 0, 0, false);
        assertEquals(2657, native1513.battleNetSequenceOffset());
        assertEquals(1, native1513.battleNetAnimationTimer());

        mission.tick();
        assertChaser(native1505, 76, 62, 0, 0, false);
        assertEquals(2657, native1505.battleNetSequenceOffset());
        assertEquals(2, native1505.battleNetAnimationTimer());
        assertChaser(native1513, 77, 62, -32, 32, true);
        assertEquals(2603, native1513.battleNetSequenceOffset());
        assertEquals(1, native1513.battleNetAnimationTimer());
        assertEquals(2, native1513.pathLength(),
                "Java removes the committed NE head from native's NE,E,E buffer");
    }

    @Test
    void offeredHarvestQuarryRouteKeepsItsCompletePaidWait() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        World world = mission.world();
        Unit attacker = unitById(world, 95);
        Unit quarry = unitById(world, 81);
        assertNotNull(attacker,
                "Human 8 has no Java twin for native attack-peasant 1505");
        assertNotNull(quarry,
                "Human 8 has no offered harvesting quarry for slot 1505");

        while (fixtureCycle(world) < 226) {
            mission.tick();
        }
        assertEquals(quarry, attacker.target());
        assertEquals(quarry, attacker.offeredTarget());
        assertEquals(3, attacker.pathLength());
        assertEquals(Direction.fromDelta(1, 0),
                attacker.peekHeadingAtDepth(0));
        assertEquals(Direction.fromDelta(1, 0),
                attacker.peekHeadingAtDepth(1));
        assertEquals(Direction.fromDelta(1, 0),
                attacker.peekHeadingAtDepth(2));
        assertEquals(1, attacker.battleNetCollisionCounter());
        assertEquals(15, attacker.battleNetAnimationTimer());

        mission.tick();
        assertEquals(227, fixtureCycle(world));
        assertEquals(3, attacker.pathLength(),
                "the paid wait retains the complete offered-quarry route");
        assertEquals(Direction.fromDelta(1, 0), attacker.peekHeading(),
                "a free diagonal must not cancel the offered-quarry band");
        assertEquals(1, attacker.battleNetCollisionCounter());
        assertEquals(14, attacker.battleNetAnimationTimer());

        while (fixtureCycle(world) < 240) {
            mission.tick();
        }
        assertChaser(attacker, 76, 62, 0, 0, false);
        assertEquals(1, attacker.battleNetAnimationTimer(),
                "native exposes the last paid Move visit at fixture 240");
        mission.tick();
        assertEquals(241, fixtureCycle(world));
        assertChaser(attacker, 76, 62, 0, 0, false);
        assertEquals(0, attacker.pathLength(),
                "the moved quarry parks the stale east route at index twenty");
        mission.tick();
        assertEquals(242, fixtureCycle(world));
        assertEquals(77, attacker.tileX());
        assertEquals(61, attacker.tileY(),
                "the fresh route begins north-east, not south-east");
    }

    @Test
    void aSurfacedQuarryKeepsItsConstructorBeforeTheAdjacentRetarget() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        World world = mission.world();
        Unit attacker = unitById(world, 95);
        Unit surfaced = unitById(world, 64);
        Unit replacement = unitById(world, 67);
        assertNotNull(attacker,
                "Human 8 has no Java twin for native attack-peasant 1505");
        assertNotNull(surfaced,
                "Human 8 has no Java twin for native laden peasant 1536");
        assertNotNull(replacement,
                "Human 8 has no Java twin for native peasant 1533");

        while (fixtureCycle(world) < 274) {
            mission.tick();
        }
        assertEquals(surfaced, attacker.target(),
                "the residual arrival retains the quarry which just surfaced");
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(3, attacker.battleNetAnimationTimer());
        assertEquals(13, attacker.battleNetAttackRefusalRecoveryStage());

        mission.tick();
        assertEquals(surfaced, attacker.target());
        assertEquals(2, attacker.battleNetAnimationTimer());
        mission.tick();
        assertEquals(surfaced, attacker.target());
        assertEquals(1, attacker.battleNetAnimationTimer());

        mission.tick();
        assertEquals(277, fixtureCycle(world));
        assertEquals(replacement, attacker.target(),
                "the OP0 after the retained constructor owns the fresh spatial target");
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(3, attacker.battleNetAnimationTimer(),
                "the replacement begins a distinct Attack constructor");
        assertEquals(0, attacker.battleNetAttackRefusalRecoveryStage());

        mission.tick();
        assertEquals(2, attacker.battleNetAnimationTimer());
        mission.tick();
        assertEquals(1, attacker.battleNetAnimationTimer());
        mission.tick();
        assertEquals(280, fixtureCycle(world));
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(23, attacker.battleNetAnimationTimer(),
                "the replacement constructor enters the committed melee body hold");
        assertEquals(24, replacement.hitPoints(),
                "the body hold must not deal the replacement's blow early");

        while (fixtureCycle(world) < 285) {
            mission.tick();
        }
        assertEquals(24, replacement.hitPoints(),
                "native has not damaged peasant 1533 by fixture 285");
    }

    @Test
    void anExpiredTwoByteQuarryRoutePaysItsCommittedMeleeBodyHold() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        World world = mission.world();
        Unit attacker = unitById(world, 74);
        Unit farm = unitById(world, 60);
        assertNotNull(attacker,
                "Human 8 has no Java twin for native attack-peasant 1526");
        assertNotNull(farm, "Human 8 has no Java twin for native farm 1540");

        while (fixtureCycle(world) < 235) {
            mission.tick();
        }
        assertChaser(attacker, 76, 61, -2, 2, true);
        assertEquals(1, attacker.pathLength());
        assertEquals(2652, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());

        mission.tick();
        assertEquals(236, fixtureCycle(world));
        assertChaser(attacker, 76, 61, 0, 0, false);
        assertEquals(2600, attacker.battleNetSequenceOffset());
        assertEquals(15, attacker.battleNetAnimationTimer(),
                "the expired two-byte route owns one complete Move band");

        while (fixtureCycle(world) < 250) {
            mission.tick();
        }
        assertEquals(2600, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());
        assertEquals(0x2c67412e, world.randomSeed());

        mission.tick();
        assertEquals(251, fixtureCycle(world));
        assertEquals(farm, attacker.target(),
                "the band wake publishes the adjacent farm footprint");
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(3, attacker.battleNetAnimationTimer());
        assertEquals(0, attacker.pathLength(),
                "native parks the expired residual at route index twenty");
        assertEquals(0, attacker.battleNetCollisionCounter());
        assertEquals(0x2c67412e, world.randomSeed(),
                "the target-and-constructor handoff owns no synchronized draw");

        while (fixtureCycle(world) < 253) {
            mission.tick();
        }
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());
        assertEquals(400, farm.hitPoints());

        mission.tick();
        assertEquals(254, fixtureCycle(world));
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(23, attacker.battleNetAnimationTimer(),
                "the paid constructor opens retail's complete melee body hold");
        assertEquals(400, farm.hitPoints(),
                "the constructor must not become an immediate farm strike");
        assertEquals(0x2c67412e, world.randomSeed());

        while (fixtureCycle(world) < 276) {
            mission.tick();
        }
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());
        assertEquals(400, farm.hitPoints());

        mission.tick();
        assertEquals(277, fixtureCycle(world));
        assertEquals(67, attacker.target().id(),
                "the completed body hold publishes the fresh live quarry");
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(3, attacker.battleNetAnimationTimer());
        assertEquals(0xb3590565, world.randomSeed());

        while (fixtureCycle(world) < 279) {
            mission.tick();
        }
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());

        mission.tick();
        assertEquals(280, fixtureCycle(world));
        assertChaser(attacker, 77, 62, -32, -32, true);
        assertEquals(Direction.fromDelta(1, 1), attacker.lastStepHeading());
        assertEquals(2, attacker.pathLength(),
                "the south-east step retains native's east,north-east tail");
        assertEquals(Direction.fromDelta(1, 0), attacker.peekHeading());
        assertEquals(2603, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());
        assertEquals(400, farm.hitPoints());
        assertEquals(0xb3590565, world.randomSeed(),
                "the fresh step owns no synchronized random draw");
    }

    @Test
    void aMovingReplacementConsumesOneColdRetryBeforeItsWallDetour() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        World world = mission.world();
        Unit attacker = unitById(world, 74);
        Unit movingReplacement = unitById(world, 64);
        Unit dyingQuarry = unitById(world, 67);
        assertNotNull(attacker,
                "Human 8 has no Java twin for native attack-peasant 1526");
        assertNotNull(movingReplacement,
                "Human 8 has no Java twin for native returner 1536");
        assertNotNull(dyingQuarry,
                "Human 8 has no Java twin for native dying quarry 1533");

        while (fixtureCycle(world) < 384) {
            mission.tick();
        }
        assertSame(movingReplacement, attacker.target(),
                "the timer-one scan replaces the dying quarry");
        assertSame(dyingQuarry, attacker.offeredTarget(),
                "the cold retry retains the quarry which owned the offer");
        assertTrue(movingReplacement.isMoving(),
                "the replacement is still draining its return residual");
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(3, attacker.battleNetAnimationTimer());
        assertTrue(attacker.battleNetColdNoProgressRefusalLoop(),
                "the replacement starts one fresh cold constructor");

        while (fixtureCycle(world) < 387) {
            mission.tick();
        }
        assertEquals(77, attacker.tileX());
        assertEquals(62, attacker.tileY());
        assertEquals(3, attacker.battleNetAnimationTimer(),
                "the moving quarry owns one additional Attack constructor");
        assertFalse(attacker.battleNetColdNoProgressRefusalLoop(),
                "that paid retry consumes the moving-replacement cold latch");

        while (fixtureCycle(world) < 389) {
            mission.tick();
        }
        assertEquals(1, attacker.battleNetAnimationTimer());
        mission.tick();
        assertEquals(390, fixtureCycle(world));
        assertChaser(attacker, 78, 63, -32, -32, true);
        assertEquals(Direction.fromDelta(1, 1), attacker.lastStepHeading(),
                "retail releases the non-progressing southeast wall face");
        assertEquals(2, attacker.pathLength());
        assertEquals(Direction.fromDelta(1, -1), attacker.peekHeading(),
                "the committed step retains northeast next");
        assertEquals(Direction.fromDelta(0, -1),
                attacker.peekHeadingAtDepth(1),
                "the native three-byte wall route ends north");
    }

    @Test
    void aPaidMovingReplacementContinuesWhenItsFirstChaseStrideSettles() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        World world = mission.world();
        Unit attacker = unitById(world, 74);
        Unit movingReplacement = unitById(world, 81);
        Unit oldQuarry = unitById(world, 67);
        assertNotNull(attacker,
                "Human 8 has no Java twin for native attack-peasant 1526");
        assertNotNull(movingReplacement,
                "Human 8 has no Java twin for native returner 1519");
        assertNotNull(oldQuarry,
                "Human 8 has no Java twin for native peasant 1533");

        while (fixtureCycle(world) < 409) {
            mission.tick();
        }
        assertFalse(attacker.battleNetChaseReplanResidualHold(),
                "the completed replacement constructor consumes its queued Attack owner");

        while (fixtureCycle(world) < 456) {
            mission.tick();
        }
        assertFalse(attacker.battleNetChaseReplanResidualHold(),
                "the open body stays consumed until the quarry runs");
        mission.tick();
        assertEquals(457, fixtureCycle(world));
        assertFalse(attacker.battleNetChaseReplanResidualHold(),
                "the first stride of a later ordinary chase is not a retarget residual");

        while (fixtureCycle(world) < 472) {
            mission.tick();
        }
        assertChaser(attacker, 79, 64, -2, -2, true);
        assertSame(movingReplacement, attacker.target());
        assertSame(oldQuarry, attacker.offeredTarget());
        assertTrue(movingReplacement.isMoving(),
                "the quarry still owns its southbound return residual");
        assertEquals(0xde652f78, world.randomSeed());

        mission.tick();
        assertEquals(473, fixtureCycle(world));
        assertEquals(0, attacker.battleNetAttackRefusalRecoveryStage(),
                "the already-paid body must not buy another Attack constructor");
        assertEquals(2603, attacker.battleNetSequenceOffset());
        assertChaser(attacker, 78, 65, 32, -32, true);
        assertEquals(Direction.fromDelta(-1, 1),
                attacker.lastStepHeading(),
                "retail immediately spends southwest toward the moving quarry");
        assertEquals(1, attacker.pathLength());
        assertEquals(Direction.fromDelta(0, 1), attacker.peekHeading(),
                "the fresh two-byte route retains south");
        assertEquals(1, attacker.battleNetAnimationTimer());
        assertFalse(attacker.battleNetRetargetResidualRoutePark(),
                "the consumed replacement handoff no longer owns a route park");
        assertEquals(0xde652f78, world.randomSeed(),
                "the immediate chase continuation owns no synchronized draw");
    }

    @Test
    void aColdPaidWrapRetargetOwnsFreshConstructionAndBodyHold() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        World world = mission.world();
        Unit attacker = unitById(world, 87);
        Unit escapingReturner = unitById(world, 81);
        Unit oldQuarry = unitById(world, 64);
        Unit asynchronousControl = unitById(world, 56);
        assertNotNull(attacker,
                "Human 8 has no Java twin for native attack-peasant 1513");
        assertNotNull(escapingReturner,
                "Human 8 has no Java twin for native peasant 1519");
        assertNotNull(oldQuarry,
                "Human 8 has no Java twin for native peasant 1536");
        assertNotNull(asynchronousControl,
                "Human 8 has no Java twin for native critter 1544");

        while (fixtureCycle(world) < 414) {
            mission.tick();
        }
        assertSame(oldQuarry, attacker.target());
        assertEquals(2600, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());
        assertEquals(25, escapingReturner.hitPoints());

        mission.tick();
        assertEquals(415, fixtureCycle(world));
        assertSame(escapingReturner, attacker.target(),
                "timer-one wake installs the fresh adjacent quarry");
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(3, attacker.battleNetAnimationTimer(),
                "the replacement owns a fresh Attack constructor");
        assertEquals(0, attacker.battleNetCollisionCounter(),
                "parking the stale route retires its collision generation");

        while (fixtureCycle(world) < 418) {
            mission.tick();
        }
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(23, attacker.battleNetAnimationTimer(),
                "the completed constructor opens the committed body hold");

        while (fixtureCycle(world) < 427) {
            mission.tick();
        }
        assertEquals(25, escapingReturner.hitPoints(),
                "the returner leaves before the held swing can land");
        assertEquals(78, attacker.tileX());
        assertEquals(62, attacker.tileY());

        while (fixtureCycle(world) < 468) {
            mission.tick();
        }
        assertEquals(79, attacker.tileX());
        assertEquals(63, attacker.tileY(),
                "the later replacement chase releases on the native fixture");
        assertSame(escapingReturner, attacker.target());
        assertEquals(1, attacker.pathLength(),
                "the paid southeast probe retains its south tail");
        assertEquals(Direction.fromDelta(0, 1), attacker.peekHeading());
        assertTrue(attacker.battleNetChaseReplanResidualHold(),
                "the replacement Attack stays queued behind its first residual");

        while (fixtureCycle(world) < 470) {
            mission.tick();
        }
        assertEquals(Unit.Order.MOVE, asynchronousControl.order(),
                "the paid probe must not steal the critter's async ordinal");

        while (fixtureCycle(world) < 484) {
            mission.tick();
        }
        assertEquals(79, attacker.tileX());
        assertEquals(63, attacker.tileY(),
                "residual settlement promotes Attack before the cached south byte");
        assertEquals(1, attacker.pathLength(),
                "Attack construction preserves the replacement route tail");
        assertEquals(Direction.fromDelta(0, 1), attacker.peekHeading());
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(3, attacker.battleNetAnimationTimer());

        while (fixtureCycle(world) < 486) {
            mission.tick();
        }
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());

        mission.tick();
        assertEquals(487, fixtureCycle(world));
        assertEquals(79, attacker.tileX());
        assertEquals(64, attacker.tileY(),
                "timer one returns ownership to the cached south byte");
    }

    @Test
    void aMovingFormationMateOnTheMarkedQuarrySkirtStaysSoft() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        World world = mission.world();
        Unit attacker = unitById(world, 95);
        Unit skirtAlly = unitById(world, 74);
        Unit quarry = unitById(world, 81);
        assertNotNull(attacker,
                "Human 8 has no Java twin for native attack-peasant 1505");
        assertNotNull(skirtAlly,
                "Human 8 has no Java twin for native attack-peasant 1526");
        assertNotNull(quarry,
                "Human 8 has no Java twin for native peasant 1519");

        while (fixtureCycle(world) < 481) {
            mission.tick();
        }
        assertSame(quarry, attacker.target());
        assertEquals(78, attacker.tileX());
        assertEquals(61, attacker.tileY());
        assertEquals(78, skirtAlly.tileX());
        assertEquals(65, skirtAlly.tileY());
        assertTrue(skirtAlly.isMoving());
        assertEquals(1, skirtAlly.pathLength());
        assertEquals(0, skirtAlly.battleNetCollisionCounter(),
                "native 0x4501bc soft-clears only a zero-collision Move body");

        mission.tick();
        assertEquals(482, fixtureCycle(world));
        assertEquals(78, attacker.tileX());
        assertEquals(62, attacker.tileY());
        assertEquals(3, attacker.pathLength(),
                "the first south byte has been consumed from native S,S,SE,SW");
        assertEquals(Direction.fromDelta(0, 1),
                attacker.peekHeadingAtDepth(0));
        assertEquals(Direction.fromDelta(1, 1),
                attacker.peekHeadingAtDepth(1));
        assertEquals(Direction.fromDelta(-1, 1),
                attacker.peekHeadingAtDepth(2),
                "the wall writer may end southwest on the occupied marked skirt");

        while (fixtureCycle(world) < 530) {
            mission.tick();
        }
        assertEquals(78, attacker.tileX(),
                "the marked-skirt southwest tail owns the cycle-530 x position");
        assertEquals(65, attacker.tileY());
    }

    @Test
    void aPaidMovingQuarryResidualRetainsRoutePressureBeforeRearmingAttack() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        World world = mission.world();
        Unit attacker = unitById(world, 87);
        Unit quarry = unitById(world, 81);
        Unit critter = unitById(world, 105);
        assertNotNull(attacker,
                "Human 8 has no Java twin for native attack-peasant 1513");
        assertNotNull(quarry,
                "Human 8 has no Java twin for native peasant 1519");
        assertNotNull(critter,
                "Human 8 has no Java twin for native critter 1495");

        while (fixtureCycle(world) < 518) {
            mission.tick();
        }
        assertSame(quarry, attacker.target());
        assertChaser(attacker, 80, 65, -2, -2, true);
        assertEquals(2652, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());
        assertEquals(2, attacker.pathLength(),
                "the granted southeast probe retains a two-byte tail");
        assertEquals(Direction.fromDelta(1, 1),
                attacker.lastStepHeading());
        assertEquals(Direction.fromDelta(-1, 1), attacker.peekHeading());
        assertTrue(attacker.battleNetPaidRefusalRecoveryApproach());

        mission.tick();
        assertEquals(519, fixtureCycle(world));
        assertChaser(attacker, 80, 65, 0, 0, false);
        assertSame(quarry, attacker.target());
        assertEquals(2600, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());
        assertEquals(1, attacker.battleNetCollisionCounter(),
                "retail parks the paid residual as collision generation one");

        while (fixtureCycle(world) < 526) {
            mission.tick();
        }
        assertChaser(attacker, 80, 65, 0, 0, false);
        assertSame(quarry, attacker.target());
        assertEquals(2600, attacker.battleNetSequenceOffset());
        assertEquals(8, attacker.battleNetCollisionCounter(),
                "each empty-route visit advances the retained pressure");
        assertEquals(0, attacker.battleNetRefusals(),
                "the paid tail does not start a separate hard-refusal ladder");
        assertEquals(15, attacker.battleNetAnimationTimer());
        assertEquals(0x95b043b5, world.battleNetRandomSeed(),
                "the pressure loop owns no active-order idle draw");

        mission.tick();
        assertEquals(527, fixtureCycle(world));
        assertEquals(Unit.Order.STILL, critter.order(),
                "the pursuer must not steal the critter's no-wander draw");
        assertEquals(0x44155061, world.battleNetRandomSeed());

        while (fixtureCycle(world) < 537) {
            mission.tick();
        }
        assertEquals(Unit.Order.MOVE, critter.order(),
                "the critter wanders on retail's later draw");
        assertEquals(80, critter.orderTargetX());
        assertEquals(82, critter.orderTargetY());
    }

    @Test
    void anExpiredMovingQuarryPaysTwoBandsBeforeOneFreshStep() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        World world = mission.world();
        Unit attacker = unitById(world, 87);
        assertNotNull(attacker,
                "Human 8 has no Java twin for native attack-peasant 1513");

        while (fixtureCycle(world) < 188) {
            mission.tick();
        }
        assertChaser(attacker, 74, 65, -2, 2, true);
        assertEquals(2652, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());

        mission.tick();
        assertEquals(189, fixtureCycle(world));
        assertChaser(attacker, 75, 64, -32, 32, true);
        assertEquals(2603, attacker.battleNetSequenceOffset(),
                "the earlier open march must spend its retained north-east head");
        assertEquals(1, attacker.pathLength(),
                "the open march retains its final north-east heading");

        while (fixtureCycle(world) < 254) {
            mission.tick();
        }
        assertChaser(attacker, 77, 62, 0, 0, false);
        assertEquals(2, attacker.pathLength());
        assertEquals(1, attacker.battleNetPathStepsTaken());
        assertEquals(1, attacker.battleNetCollisionCounter());
        assertEquals(0, attacker.battleNetRefusals());
        assertTrue(attacker.stepDrained());
        assertNotNull(attacker.target());
        assertEquals(Unit.Order.HARVEST, attacker.target().order());
        assertFalse(mission.world().targets.validAttackTarget(
                attacker, attacker.target()),
                "the paid ladder retains the quarry after it leaves the map");

        while (fixtureCycle(world) < 257) {
            mission.tick();
        }
        assertChaser(attacker, 77, 62, 0, 0, false);
        assertEquals(0x7566f4cf, world.randomSeed());

        mission.tick();
        assertEquals(258, fixtureCycle(world));
        assertChaser(attacker, 77, 62, 0, 0, false);
        assertEquals(2600, attacker.battleNetSequenceOffset(),
                "the first refusal band rolls directly into another Move band");
        assertEquals(15, attacker.battleNetAnimationTimer());
        assertEquals(64, attacker.target().id(),
                "the second band publishes the first replacement quarry");
        assertEquals(0x7566f4cf, world.randomSeed(),
                "the band handoff owns no synchronized random draw");

        while (fixtureCycle(world) < 272) {
            mission.tick();
        }
        assertChaser(attacker, 77, 62, 0, 0, false);
        assertEquals(2600, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());

        mission.tick();
        assertEquals(273, fixtureCycle(world));
        assertChaser(attacker, 77, 62, 0, 0, false);
        assertEquals(2657, attacker.battleNetSequenceOffset(),
                "the second Move band hands the chase to Attack construction");
        assertEquals(3, attacker.battleNetAnimationTimer());
        assertEquals(0xb3590565, world.randomSeed(),
                "Attack construction opens without an idle-facing draw");

        while (fixtureCycle(world) < 275) {
            mission.tick();
        }
        assertChaser(attacker, 77, 62, 0, 0, false);
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());

        mission.tick();
        assertEquals(276, fixtureCycle(world));
        assertChaser(attacker, 78, 62, -32, 0, true);
        assertEquals(Direction.fromDelta(1, 0), attacker.lastStepHeading(),
                "the post-construction visit makes one fresh east decision");
        assertEquals(67, attacker.target().id(),
                "the fresh route decision publishes the nearer live quarry");
        assertEquals(1, attacker.pathLength(),
                "the fresh east step retains native's north-east tail");
        assertEquals(Direction.fromDelta(1, -1), attacker.peekHeading());
        assertEquals(0xb3590565, world.randomSeed(),
                "the fresh route decision owns no synchronized random draw");
    }

    @Test
    void aFirstCollisionTailKeepsItsQuarryThroughRetargetConstruction() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        World world = mission.world();
        Unit attacker = unitById(world, 87);
        Unit oldQuarry = unitById(world, 67);
        Unit replacement = unitById(world, 64);
        assertNotNull(attacker,
                "Human 8 has no Java twin for native attack-peasant 1513");
        assertNotNull(oldQuarry,
                "Human 8 has no Java twin for native peasant 1533");
        assertNotNull(replacement,
                "Human 8 has no Java twin for native peasant 1536");

        while (fixtureCycle(world) < 291) {
            mission.tick();
        }
        assertChaser(attacker, 78, 62, -2, 0, true);
        assertSame(oldQuarry, attacker.target());
        assertEquals(1, attacker.pathLength());
        assertEquals(Direction.fromDelta(1, -1), attacker.peekHeading());
        assertEquals(1, attacker.battleNetCollisionCounter(),
                "the settled tail still owns its first collision generation");
        assertEquals(0, attacker.battleNetRefusals());
        assertEquals(2652, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());

        mission.tick();
        assertEquals(292, fixtureCycle(world));
        assertChaser(attacker, 78, 62, 0, 0, false);
        assertSame(oldQuarry, attacker.target(),
                "native prices the cached north-east tail before replacement");
        assertEquals(1, attacker.pathLength(),
                "the final cached heading survives the first constructor");
        assertEquals(Direction.fromDelta(1, -1), attacker.peekHeading());
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(3, attacker.battleNetAnimationTimer());

        mission.tick();
        assertSame(oldQuarry, attacker.target());
        assertEquals(2, attacker.battleNetAnimationTimer());
        mission.tick();
        assertEquals(294, fixtureCycle(world));
        assertSame(oldQuarry, attacker.target());
        assertEquals(1, attacker.battleNetAnimationTimer());

        mission.tick();
        assertEquals(295, fixtureCycle(world));
        assertSame(replacement, attacker.target(),
                "timer one owns the fresh adjacent-target scan");
        assertEquals(0, attacker.pathLength(),
                "the replacement scan parks the old terminal heading");
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(3, attacker.battleNetAnimationTimer(),
                "the replacement starts its own Attack constructor");

        while (fixtureCycle(world) < 298) {
            mission.tick();
        }
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(23, attacker.battleNetAnimationTimer(),
                "the replacement constructor enters the committed body hold");

        while (fixtureCycle(world) < 328) {
            mission.tick();
        }
        assertEquals(23, replacement.hitPoints(),
                "the delayed replacement must not strike on fixture 328");

        while (fixtureCycle(world) < 331) {
            mission.tick();
        }
        assertEquals(18, replacement.hitPoints(),
                "native attack-peasant 1513 lands its blow on fixture 331");
    }

    @Test
    void aMovingQuarryResidualReopensAttackBeforeTheCachedEastStep() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit attacker = unitById(mission.world(), 80);
        assertNotNull(attacker,
                "Human 8 has no Java twin for native attack-peasant 1520");

        while (fixtureCycle(mission.world()) < 229) {
            mission.tick();
        }
        assertChaser(attacker, 75, 63, -2, 2, true);
        assertEquals(3, attacker.pathLength());
        assertEquals(2652, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());

        mission.tick();
        assertEquals(230, fixtureCycle(mission.world()));
        assertChaser(attacker, 75, 63, 0, 0, false);
        assertEquals(3, attacker.pathLength(),
                "Attack construction retains the east-led quarry route");
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(3, attacker.battleNetAnimationTimer());

        while (fixtureCycle(mission.world()) < 232) {
            mission.tick();
        }
        assertChaser(attacker, 75, 63, 0, 0, false);
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());

        mission.tick();
        assertChaser(attacker, 76, 63, -32, 0, true);
        assertEquals(2, attacker.pathLength());
        assertEquals(2603, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());
        assertEquals(0, attacker.battleNetCollisionCounter(),
                "the accepted cached east leg clears its collision generation");

        while (fixtureCycle(mission.world()) < 249) {
            mission.tick();
        }
        assertChaser(attacker, 76, 63, 0, 0, false);
        assertEquals(2600, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());
        assertEquals(1, attacker.battleNetCollisionCounter());

        mission.tick();
        assertEquals(250, fixtureCycle(mission.world()));
        assertChaser(attacker, 75, 62, 32, 32, true);
        assertEquals(60, attacker.target().id(),
                "the parked expired quarry is replaced by the farm footprint");
        assertEquals(1, attacker.pathLength());
        assertEquals(Direction.fromDelta(-1, -1), attacker.peekHeading());
        assertEquals(0, attacker.battleNetCollisionCounter(),
                "the accepted replacement route clears the parked collision");

        while (fixtureCycle(mission.world()) < 265) {
            mission.tick();
        }
        assertChaser(attacker, 75, 62, 2, 2, true);
        assertEquals(2652, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());

        mission.tick();
        assertEquals(266, fixtureCycle(mission.world()));
        assertChaser(attacker, 75, 62, 0, 0, false);
        assertEquals(2657, attacker.battleNetSequenceOffset(),
                "the replacement route's first residual reopens Attack");
        assertEquals(3, attacker.battleNetAnimationTimer());
        assertEquals(1, attacker.pathLength(),
                "the constructor retains the second north-west byte");
        assertEquals(60, attacker.target().id());

        while (fixtureCycle(mission.world()) < 268) {
            mission.tick();
        }
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());

        mission.tick();
        assertEquals(269, fixtureCycle(mission.world()));
        assertChaser(attacker, 76, 62, -32, 0, true);
        assertEquals(64, attacker.target().id(),
                "timer one replaces the farm before the fresh east route");
        assertEquals(3, attacker.pathLength());
        assertEquals(Direction.fromDelta(1, -1), attacker.peekHeading());
        assertEquals(2603, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());

        while (fixtureCycle(mission.world()) < 303) {
            mission.tick();
        }
        assertChaser(attacker, 77, 63, -2, -2, true);
        assertEquals(71, attacker.target().id());
        assertEquals(1, attacker.pathLength());
        assertEquals(2652, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());

        mission.tick();
        assertEquals(304, fixtureCycle(mission.world()));
        assertChaser(attacker, 77, 63, 0, 0, false);
        assertEquals(71, attacker.target().id());
        assertEquals(1, attacker.pathLength(),
                "the uncollided quarry leg retains its final south-east byte");
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(3, attacker.battleNetAnimationTimer());

        while (fixtureCycle(mission.world()) < 306) {
            mission.tick();
        }
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());
        assertEquals(0x2eda08b5,
                mission.world().battleNetRandomSeed());

        mission.tick();
        assertEquals(307, fixtureCycle(mission.world()));
        assertChaser(attacker, 77, 63, 0, 0, false);
        assertEquals(64, attacker.target().id(),
                "timer one free-scans the next moving quarry");
        assertEquals(0, attacker.pathLength(),
                "the replacement scan parks the old last byte at native RI20");
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(3, attacker.battleNetAnimationTimer());
        assertEquals(0x27d01f8f,
                mission.world().battleNetRandomSeed(),
                "the replacement rearm owns native slot 1520's idle draw");

        while (fixtureCycle(mission.world()) < 309) {
            mission.tick();
        }
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());
        mission.tick();
        assertEquals(310, fixtureCycle(mission.world()));
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(3, attacker.battleNetAnimationTimer(),
                "the boxed replacement repeats the three-call Attack band");
        assertEquals(0x1d05557c,
                mission.world().battleNetRandomSeed());

        while (fixtureCycle(mission.world()) < 490) {
            mission.tick();
        }
        assertChaser(attacker, 78, 64, 0, 0, false);
        assertEquals(81, attacker.target().id());
        assertEquals(2686, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());
        assertEquals(2, attacker.battleNetCollisionCounter());
        assertTrue(attacker.battleNetAttackWrapDestArmPending());
        assertTrue(attacker.battleNetRetargetResidualParkRefill());

        mission.tick();
        assertEquals(491, fixtureCycle(mission.world()));
        assertChaser(attacker, 79, 65, -32, -32, true);
        assertEquals(81, attacker.target().id(),
                "the paid tail keeps the moving harvesting quarry");
        assertEquals(0, attacker.pathLength(),
                "the tail marker consumes its freshly drawn southeast byte");
        assertEquals(2603, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());
    }

    @Test
    void anOrdinaryMovingQuarryAttackTailHandsDirectlyToItsFreshRoute() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        World world = mission.world();
        Unit attacker = unitById(world, 74);
        Unit quarry = unitById(world, 81);
        assertNotNull(attacker,
                "Human 8 has no Java twin for native attack-peasant 1526");
        assertNotNull(quarry,
                "Human 8 has no Java twin for native peasant 1519");

        while (fixtureCycle(world) < 513) {
            mission.tick();
        }
        assertChaser(attacker, 78, 65, 0, 0, false);
        assertSame(quarry, attacker.target());
        assertEquals(78, attacker.pathGoalX());
        assertEquals(66, attacker.pathGoalY());
        assertEquals(2686, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());
        assertEquals(0, attacker.pathLength(),
                "native route index twenty exposes no executable byte");
        assertFalse(attacker.battleNetAttackWrapDestArmPending(),
                "the completed body has consumed its destination arm");
        assertFalse(attacker.battleNetRetargetResidualParkRefill(),
                "the completed body has consumed its route-park provenance");
        assertEquals(0, attacker.battleNetCollisionCounter(),
                "this tail has consumed its collision generation");
        assertEquals(0, attacker.battleNetRefusals());

        mission.tick();
        assertEquals(514, fixtureCycle(world));
        assertChaser(attacker, 79, 66, -32, -32, true);
        assertSame(quarry, attacker.target(),
                "the completed tail keeps its live moving harvesting quarry");
        assertEquals(78, attacker.pathGoalX());
        assertEquals(67, attacker.pathGoalY(),
                "the OP0 visit refreshes the quarry before pathing");
        assertEquals(0, attacker.pathLength(),
                "the fresh southeast route is consumed on the same visit");
        assertEquals(Direction.fromDelta(1, 1), attacker.lastStepHeading());
        assertEquals(2603, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());
    }

    @Test
    void aPaidWrapArrivalEntersTheAttackBodyOnItsSettlementVisit() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        World world = mission.world();
        Unit attacker = unitById(world, 99);
        Unit quarry = unitById(world, 93);
        assertNotNull(attacker,
                "Human 8 has no Java twin for native ogre 1501");
        assertNotNull(quarry,
                "Human 8 has no Java twin for native peasant 1507");

        while (fixtureCycle(world) < 283) {
            mission.tick();
        }
        assertChaser(attacker, 87, 82, -2, -2, true);
        assertEquals(2652, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());
        assertEquals(25, quarry.hitPoints());

        mission.tick();
        assertEquals(284, fixtureCycle(world));
        assertChaser(attacker, 87, 82, 0, 0, false);
        assertEquals(2660, attacker.battleNetSequenceOffset(),
                "the paid final residual returns through Attack OP0 now");
        assertEquals(1, attacker.battleNetAnimationTimer());

        while (fixtureCycle(world) < 293) {
            mission.tick();
        }
        assertEquals(25, quarry.hitPoints());
        mission.tick();
        assertEquals(294, fixtureCycle(world));
        assertEquals(21, quarry.hitPoints(),
                "native ogre 1501 lands its four-damage blow at fixture 294");
    }

    @Test
    void aSettledPaidRouteFreeScansBeforeItsCommittedAttackHold() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        World world = mission.world();
        Unit attacker = unitById(world, 62);
        Unit quarry = unitById(world, 64);
        Unit secondQuarry = unitById(world, 67);
        Unit critter = unitById(world, 61);
        assertNotNull(attacker,
                "Human 8 has no Java twin for native ogre 1538");
        assertNotNull(quarry,
                "Human 8 has no Java twin for native peasant 1536");
        assertNotNull(secondQuarry,
                "Human 8 has no Java twin for native peasant 1533");
        assertNotNull(critter,
                "Human 8 has no Java twin for native critter 1539");

        while (fixtureCycle(world) < 258) {
            mission.tick();
        }
        assertChaser(attacker, 79, 62, -2, 0, true);
        assertEquals(81, attacker.target().id(),
                "the final east residual still names native quarry 1519");
        assertEquals(0x7566f4cf, world.randomSeed());

        mission.tick();
        assertEquals(259, fixtureCycle(world));
        assertChaser(attacker, 79, 62, 0, 0, false);
        assertSame(quarry, attacker.target(),
                "the settlement callback free-scans to native quarry 1536");
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(3, attacker.battleNetAnimationTimer(),
                "the replacement owns fresh Attack construction");
        assertEquals(0x7566f4cf, world.randomSeed(),
                "the settlement scan and constructor own no synchronized draw");

        while (fixtureCycle(world) < 262) {
            mission.tick();
        }
        assertEquals(2657, attacker.battleNetSequenceOffset());
        assertEquals(23, attacker.battleNetAnimationTimer(),
                "the completed constructor enters the committed body hold");

        while (fixtureCycle(world) < 294) {
            mission.tick();
        }
        assertEquals(30, quarry.hitPoints());
        assertEquals(2672, attacker.battleNetSequenceOffset());
        assertEquals(1, attacker.battleNetAnimationTimer());
        mission.tick();
        assertEquals(295, fixtureCycle(world));
        assertEquals(26, quarry.hitPoints(),
                "native ogre 1538 lands its four-damage blow at fixture 295");
        assertSame(attacker, quarry.offeredTarget(),
                "the resource order retains its live aggressor at native +0x54");

        while (fixtureCycle(world) < 297) {
            mission.tick();
        }
        assertEquals(Unit.Order.HARVEST, quarry.order());
        assertEquals(1, quarry.battleNetAnimationTimer());
        assertEquals(0x5174c6d0, world.battleNetRandomSeed());

        mission.tick();
        assertEquals(298, fixtureCycle(world));
        assertEquals(Unit.Order.MOVE, quarry.order(),
                "the struck action-24 worker temporarily exposes action 3");
        assertEquals(79, quarry.tileX());
        assertEquals(61, quarry.tileY());
        assertFalse(quarry.isMoving(),
                "the flee order first serves its native Still constructor");
        assertEquals(83, quarry.orderTargetX());
        assertEquals(62, quarry.orderTargetY());
        assertEquals(Unit.Order.HARVEST, quarry.savedOrder());
        assertNull(quarry.offeredTarget(),
                "the native flee writer consumes the retained aggressor");
        assertEquals(2595, quarry.battleNetSequenceOffset());
        assertEquals(3, quarry.battleNetAnimationTimer());
        assertEquals(Unit.Order.STILL, critter.order(),
                "the worker owns both point draws before the critter callback");
        assertEquals(0xb468d988, world.battleNetRandomSeed());

        mission.tick();
        assertEquals(299, fixtureCycle(world));
        assertEquals(Unit.Order.MOVE, quarry.order());
        assertEquals(2, quarry.battleNetAnimationTimer());
        mission.tick();
        assertEquals(300, fixtureCycle(world));
        assertEquals(Unit.Order.MOVE, quarry.order());
        assertEquals(1, quarry.battleNetAnimationTimer());

        mission.tick();
        assertEquals(301, fixtureCycle(world));
        assertEquals(Unit.Order.HARVEST, quarry.order(),
                "the temporary action 3 restores the stranded resource order");
        assertNull(quarry.savedOrder());
        assertEquals(2595, quarry.battleNetSequenceOffset());
        assertEquals(3, quarry.battleNetAnimationTimer(),
                "the resumed depot retry starts its next three-call idle band");
        assertEquals(Unit.Order.STILL, critter.order());
        assertEquals(0x535014dc, world.battleNetRandomSeed(),
                "the restored resource retry recovers native draw ownership");

        while (fixtureCycle(world) < 313) {
            mission.tick();
        }
        assertEquals(Unit.Order.MOVE, secondQuarry.order());
        assertEquals(82, secondQuarry.orderTargetX());
        assertEquals(54, secondQuarry.orderTargetY(),
                "the unobstructed native escape point remains unchanged");

        while (fixtureCycle(world) < 322) {
            mission.tick();
        }
        assertEquals(Unit.Order.MOVE, quarry.order());
        assertEquals(Unit.Order.HARVEST, quarry.savedOrder());
        assertEquals(79, quarry.orderTargetX());
        assertEquals(59, quarry.orderTargetY(),
                "GiveOrder normalizes the blocked raw point 78,58");
        assertEquals(0xe4e79a5d, world.battleNetRandomSeed(),
                "point normalization owns no asynchronous draw");

        while (fixtureCycle(world) < 324) {
            mission.tick();
        }
        assertEquals(Unit.Order.MOVE, quarry.order());
        assertEquals(1, quarry.battleNetAnimationTimer());
        assertEquals(0xc344c127, world.battleNetRandomSeed());

        mission.tick();
        assertEquals(325, fixtureCycle(world));
        assertEquals(Unit.Order.MOVE, quarry.order());
        assertEquals(80, quarry.tileX());
        assertEquals(60, quarry.tileY(),
                "the normalized point admits native's northeast step");
        assertTrue(quarry.isMoving());
        assertEquals(-32, quarry.offsetX());
        assertEquals(32, quarry.offsetY());
        assertEquals(2603, quarry.battleNetSequenceOffset());
        assertEquals(1, quarry.battleNetAnimationTimer());
        assertEquals(0x454b560b, world.battleNetRandomSeed());
    }

    private static Unit unitById(World world, int id) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }

    private static Unit unitAt(World world, int player, String ident,
            int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.player() == player && unit.tileX() == x && unit.tileY() == y
                    && unit.type() != null && ident.equals(unit.type().ident())) {
                return unit;
            }
        }
        return null;
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - INITIALIZATION_TICKS;
    }

    private static void assertChaser(Unit attacker, int x, int y,
            int offsetX, int offsetY, boolean moving) {
        assertEquals(x, attacker.tileX());
        assertEquals(y, attacker.tileY());
        assertEquals(offsetX, attacker.offsetX());
        assertEquals(offsetY, attacker.offsetY());
        assertEquals(moving, attacker.isMoving());
        assertTrue(attacker.order() == Unit.Order.ATTACK,
                "the native chase remains an Attack order");
    }
}
