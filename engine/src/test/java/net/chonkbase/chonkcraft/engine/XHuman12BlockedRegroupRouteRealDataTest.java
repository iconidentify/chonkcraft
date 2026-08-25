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

/** Authenticated blocked recurring-regroup route in XHuman 12. */
class XHuman12BlockedRegroupRouteRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("XHuman 12's regrouping axethrower retains its worker-blocked route")
    void regroupingAxethrowerRetainsItsWorkerBlockedRoute() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        World world = mission.world();
        Unit axethrower = unitById(world, 241);
        assertNotNull(axethrower,
                "XHuman 12 has no Java unit 241 / native axethrower 1359");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 251) {
            mission.tick();
        }

        assertEquals(Unit.Order.MOVE, axethrower.order());
        assertEquals(0, axethrower.pathLength(),
                "the recurring regroup constructor has not drawn its route yet");

        mission.tick();
        assertEquals(252, fixtureCycle(world));
        assertEquals(Unit.Order.MOVE, axethrower.order(),
                "the worker-blocked route remains Move instead of promoting Still");
        assertEquals(12, axethrower.tileX());
        assertEquals(89, axethrower.tileY());
        assertFalse(axethrower.isMoving());
        assertEquals(5, axethrower.pathLength());
        assertEquals(Direction.fromDelta(0, -1), axethrower.peekHeading(),
                "native retains N,NW,SE,E,E behind route index zero");
        assertEquals(1, axethrower.battleNetCollisionCounter(),
                "the occupied north head raises native unit+0x1d to 0x10");
        assertEquals(14, axethrower.battleNetOrderDelay());
        assertEquals(830, axethrower.battleNetSequenceOffset());
        assertEquals(15, axethrower.battleNetAnimationTimer());
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
