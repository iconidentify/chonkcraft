package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Counterexample to XHuman 2's usable-route refill before target acquisition. */
class XHuman12HomeRouteAttackRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 12's boxed home route acquires the tower on cycle 38")
    void xhuman12sBoxedHomeRouteAcquiresTheTowerOnCycle38() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx12h",
                GameData.personIn(data.campaignMap(
                        "campaigns/human-exp/levelx12h")), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit grunt = unitAt(world, "unit-grunt", 10, 90);
        Unit tower = unitAt(world, "unit-human-guard-tower", 13, 86);
        assertNotNull(grunt, "XHuman 12 has no native-slot-1358 grunt");
        assertNotNull(tower, "XHuman 12 has no native-slot-1370 tower");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 38) {
            mission.tick();
        }

        assertEquals(12, grunt.tileX());
        assertEquals(90, grunt.tileY());
        assertEquals(Unit.Order.ATTACK, grunt.order(),
                "an empty home-route refill must still dispatch Still acquisition");
        assertSame(tower, grunt.target(),
                "native fixture 38 promotes Attack against the guard tower");
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
