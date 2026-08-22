package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks the collided route-park and same-cycle refill on XHuman 12. */
class XHuman12CollisionRefillRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a wall-blocked wood order keeps native wall-follow through cycle 200")
    void wallBlockedWoodOrderKeepsNativeWallFollowThroughCycle200() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit peon = unitById(world, 235);
        assertNotNull(peon, "XHuman 12 has no native-slot-1365 peon");
        assertEquals("unit-peon", peon.type().ident());
        assertTrue(world.map.field(13, 89).hasFlag(TileFlag.WALL),
                "the stored wood order point is a permanent wall, not a building");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 200) {
            mission.tick();
        }

        assertEquals(11, peon.tileX());
        assertEquals(89, peon.tileY(),
                "native parks the occupied north head instead of tip-stepping south-east");
        assertEquals(0, peon.pathLength(),
                "the refused wall-follow route is parked at route index twenty");
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
    @DisplayName("spent construction and paid collision refill without frozen callbacks")
    void spentConstructionAndPaidCollisionRefillWithoutFrozenCallbacks() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit spentConstruction = unitById(world, 124);
        Unit paidCollision = unitById(world, 118);
        assertNotNull(spentConstruction);
        assertNotNull(paidCollision);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 127) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture >= 124 && fixture <= 126) {
                assertEquals(127 - fixture,
                        spentConstruction.battleNetAnimationTimer(),
                        "the spent one-byte handoff drains native Attack 3,2,1");
            }
        }

        assertEquals(23, spentConstruction.tileX());
        assertEquals(47, spentConstruction.tileY(),
                "timer one must hand the replacement route north immediately");
        assertEquals(29, paidCollision.tileX());
        assertEquals(39, paidCollision.tileY(),
                "paid collision four refills and commits north-east on settle");
        assertEquals(Direction.fromDelta(1, -1),
                paidCollision.lastStepHeading());
        assertEquals(19, paidCollision.pathLength());
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
