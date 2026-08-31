package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated moving-worker refusal for XHuman 12's ground patrol. */
class XHuman12PatrolWorkerRefusalRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a Patrol attack refill owns collision until its residual handback")
    void patrolAttackRefillOwnsCollisionUntilResidualHandback() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        World world = mission.world();
        Unit ogre = unitById(world, 244);
        assertNotNull(ogre,
                "XHuman 12 has no Java unit 244 / native ogre 1356");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 75) {
            mission.tick();
        }
        assertEquals(10, ogre.tileX());
        assertEquals(89, ogre.tileY());
        assertEquals(0, ogre.pathLength(),
                "the occupied first chase byte parks at route index twenty");
        assertEquals(1, ogre.battleNetCollisionCounter(),
                "the Patrol owner survives the one-byte Attack route writer");
        assertEquals(586, ogre.battleNetSequenceOffset());
        assertEquals(1, ogre.battleNetAnimationTimer());

        mission.tick();
        assertEquals(76, fixtureCycle(world));
        assertEquals(11, ogre.tileX());
        assertEquals(88, ogre.tileY(),
                "the one-byte northeast refill commits on the next visit");
        assertEquals(Direction.fromDelta(1, -1), ogre.lastStepHeading());
        assertEquals(1, ogre.battleNetCollisionCounter(),
                "the committed residual retains Patrol's generation one");

        while (fixtureCycle(world) < 88) {
            mission.tick();
        }
        assertEquals(0, ogre.battleNetCollisionCounter(),
                "the settled residual hands a fresh collision generation to Attack");
        assertEquals(643, ogre.battleNetSequenceOffset());
        assertEquals(3, ogre.battleNetAnimationTimer(),
                "the handback opens native's Attack 3,2,1 constructor");
    }

    @Test
    @DisplayName("XHuman 12's assault patrol retains a worker-blocked route")
    void assaultPatrolRetainsWorkerBlockedRoute() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        World world = mission.world();
        Unit ogre = unitById(world, 244);
        assertNotNull(ogre,
                "XHuman 12 has no Java unit 244 / native ogre 1356");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 254) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, ogre.order());
        assertEquals(11, ogre.tileX());
        assertEquals(86, ogre.tileY());
        assertFalse(ogre.isMoving());
        assertEquals(0, ogre.pathLength());
        assertEquals(0, ogre.battleNetCollisionCounter());

        mission.tick();
        assertEquals(255, fixtureCycle(world));
        assertEquals(Unit.Order.PATROL, ogre.order());
        assertEquals(2, ogre.pathLength(),
                "native retains NW,NE behind route index zero");
        assertEquals(Direction.fromDelta(-1, -1), ogre.peekHeading());
        assertEquals(Direction.fromDelta(1, -1),
                ogre.peekHeadingAtDepth(1));
        assertEquals(1, ogre.battleNetCollisionCounter(),
                "the occupied northwest head raises native unit+0x1d to 0x10");
        assertEquals(0, ogre.battleNetRefusals());
        assertEquals(14, ogre.battleNetOrderDelay());
        assertEquals(586, ogre.battleNetSequenceOffset());
        assertEquals(15, ogre.battleNetAnimationTimer());

        while (fixtureCycle(world) < 269) {
            mission.tick();
        }
        assertEquals(11, ogre.tileX());
        assertEquals(86, ogre.tileY());
        assertEquals(2, ogre.pathLength());
        assertEquals(1, ogre.battleNetCollisionCounter());
        assertEquals(0, ogre.battleNetOrderDelay());
        assertEquals(1, ogre.battleNetAnimationTimer());

        mission.tick();
        assertEquals(270, fixtureCycle(world));
        assertEquals(10, ogre.tileX(),
                "the retained patrol route consumes northwest when the worker drains");
        assertEquals(85, ogre.tileY());
        assertEquals(1, ogre.pathLength(),
                "northeast remains cached behind the committed northwest step");
        assertEquals(Direction.fromDelta(1, -1), ogre.peekHeading());
        assertEquals(1, ogre.battleNetCollisionCounter());
    }

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }
}
