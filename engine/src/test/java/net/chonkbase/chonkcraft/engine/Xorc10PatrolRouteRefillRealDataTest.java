package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated small-warship Patrol refill at XOrc 10 fixture 244. */
class Xorc10PatrolRouteRefillRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xorc 10 destroyer parks its off-lattice Patrol overshoot")
    void xorc10DestroyerParksItsOffLatticePatrolOvershoot() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc-exp/levelx10o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 10 is not in the pack");
        World world = mission.world();
        Unit destroyer = unitById(world, 116);
        assertNotNull(destroyer,
                "XOrc 10 has no native-slot-1484 human destroyer");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 324) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, destroyer.order());
        assertEquals(100, destroyer.tileX());
        assertEquals(78, destroyer.tileY());
        assertEquals(99, destroyer.orderTargetX());
        assertEquals(79, destroyer.orderTargetY());
        assertEquals(1, destroyer.pathLength(),
                "the eleven-heading route retains its final west byte");

        mission.tick();
        assertEquals(325, fixtureCycle(world));
        assertEquals(100, destroyer.tileX(),
                "native parks west on the closest doubled-lattice anchor");
        assertEquals(78, destroyer.tileY());
        assertEquals(120, destroyer.orderTargetX(),
                "the settled Patrol turns back toward the near endpoint");
        assertEquals(72, destroyer.orderTargetY());
        assertEquals(0, destroyer.pathLength());
        assertEquals(3129, destroyer.battleNetSequenceOffset());
        assertEquals(3, destroyer.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("xorc 10 destroyer refills Patrol on the residual-settle visit")
    void xorc10DestroyerRefillsPatrolWithoutTheGenericEmptyRouteWait() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc-exp/levelx10o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 10 is not in the pack");
        World world = mission.world();
        Unit destroyer = unitById(world, 117);
        assertNotNull(destroyer,
                "XOrc 10 has no native-slot-1483 human destroyer");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 243) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, destroyer.order());
        assertEquals(110, destroyer.tileX());
        assertEquals(76, destroyer.tileY());
        assertEquals(0, destroyer.pathLength(),
                "the previous seven-byte patrol prefix is exhausted");

        mission.tick();
        assertEquals(244, fixtureCycle(world));
        assertEquals(Unit.Order.PATROL, destroyer.order());
        assertEquals(108, destroyer.tileX(),
                "retail redraws and commits west on the settle visit");
        assertEquals(76, destroyer.tileY());
        assertEquals(5, destroyer.pathLength(),
                "one heading is consumed from the fresh six-byte route");
        assertEquals(0, destroyer.waitCycles(),
                "small-warship Patrol must not inherit Move's PF_WAIT ten");
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }
}
