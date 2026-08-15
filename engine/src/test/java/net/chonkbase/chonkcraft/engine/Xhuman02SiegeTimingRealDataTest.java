package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * XHuman 2's cycle-82 HP drop on the orc catapult that stands at 56,63.
 *
 * <p>Authenticated campaign-1800 fixture {@code retail-xhuman-02-idle}:
 * catapult 1555 stays on 56,63 under Attack, and a type-15 tower arrow
 * frees at fixture 82 (110 to 94). The case's coarse first divergence
 * after the farm-replacement wave is this siege blow.
 */
class Xhuman02SiegeTimingRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 2's opening catapult is hurt on cycle 82")
    void xhuman2sOpeningCatapultIsHurtOnCycle82() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx02h", 0);
        Assumptions.assumeTrue(mission != null, "XHuman 2 is not in the pack");
        World world = mission.world();

        Unit catapult = unitAt(world, "unit-catapult", 56, 63);
        assertNotNull(catapult, "XHuman 2 has no catapult on 56,63");
        int opened = catapult.hitPoints();

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Integer hpAt82 = null;
        while (world.cycle() < 84) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 82) {
                hpAt82 = catapult.hitPoints();
            }
        }

        assertTrue(hpAt82 != null && hpAt82 < opened,
                "retail's tower arrow lands on cycle 82; the catapult is still at "
                        + hpAt82 + " of " + opened);
        assertEquals(56, catapult.tileX(),
                "the catapult must still stand on 56,63 when the arrow lands");
        assertEquals(63, catapult.tileY(),
                "the catapult must still stand on 56,63 when the arrow lands");
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
