package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
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
