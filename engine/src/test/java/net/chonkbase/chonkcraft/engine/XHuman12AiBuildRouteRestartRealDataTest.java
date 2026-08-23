package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated AI ready handoff after a compact build route ends. */
class XHuman12AiBuildRouteRestartRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a terminal paid build route re-enters the native ready callback")
    void terminalPaidBuildRouteReentersNativeReadyCallback() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit builder = unitById(world, 236); // Native pool slot 1364.
        assertNotNull(builder);

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 84) {
            mission.tick();
        }

        assertEquals(10, builder.tileX());
        assertEquals(88, builder.tileY());
        assertEquals(Unit.Order.BUILD, builder.order());
        assertEquals(3700, world.player(6).get(Resource.GOLD));
        assertEquals(3650, world.player(6).get(Resource.WOOD),
                "the spent route must hand back through ready and debit the replacement");
        assertEquals(2595, builder.battleNetSequenceOffset());
        assertEquals(3, builder.battleNetAnimationTimer(),
                "the replacement retains native constructor cadence");

        while (fixtureCycle(world) < 102) {
            mission.tick();
        }
        assertEquals(100, world.player(6).get(Resource.GOLD));
        assertEquals(950, world.player(6).get(Resource.WOOD),
                "the authenticated three-cycle retry cadence must remain stable");

        while (fixtureCycle(world) < 105) {
            mission.tick();
        }
        assertEquals(Unit.Order.HARVEST, builder.order(),
                "an unaffordable retry must fall through to normal worker assignment");
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
}
