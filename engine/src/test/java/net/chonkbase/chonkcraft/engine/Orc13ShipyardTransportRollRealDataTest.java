package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Orc 13's shore critter wanders when computer shipyards pay the
 * transport-deficit roll.
 *
 * <p>Authenticated campaign-1800 fixture {@code retail-orc-13-idle}: native
 * shipyard 1509 at (42,65) consumes {@code FUN_00479820} from {@code 0x40eef0}
 * at fixture 572 (return {@code 0x0040f094}). Java used to skip that draw, so
 * knight 34 stole the seed and critter 136 missed native's wander choice 48
 * at fixture 576.</p>
 */
class Orc13ShipyardTransportRollRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("orc 13's idle critter wanders south after shipyards pay the transport roll")
    void orc13IdleCritterWandersSouthAfterShipyardsPayTheTransportRoll() {
        Mission mission = mission("campaigns/orc/level13o");
        assertNotNull(byId(mission.world(), 91),
                "Orc 13 has no Java twin for native shipyard 1509");
        Unit critter = byId(mission.world(), 136);
        assertNotNull(critter, "Orc 13 has no Java twin for native critter 1464");

        tickThrough(mission, 576);
        assertEquals(Unit.Order.MOVE, critter.order(),
                "native critter 1464 wanders on the shared-loop OP0");
        assertEquals(86, critter.orderTargetX(),
                "the wander target stays on the same file");
        assertEquals(120, critter.orderTargetY(),
                "native walks one square south from 86,119");
    }

    private static Mission mission(String map) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        return mission;
    }

    private static void tickThrough(Mission mission, int fixtureCycle) {
        while (mission.world().cycle() - BNE_INITIALIZATION_TICKS
                < fixtureCycle) {
            mission.tick();
        }
    }

    private static Unit byId(World world, int id) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }
}
