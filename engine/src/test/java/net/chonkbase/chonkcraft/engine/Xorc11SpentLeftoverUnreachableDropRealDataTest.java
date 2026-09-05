package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A spent leftover whose quarry is terrain-unreachable ends Attack.
 *
 * <p>Authenticated campaign-1800 fixture {@code retail-xorc-11-idle}:
 * axethrower 1508 / Java 92 drains its last NE residual onto (6,40) while
 * chasing the archer at (14,36). Native returns to Still@825/1 on fixture
 * 576 and clears the target. Java used to PF_WAIT two cycles and keep
 * Attack forever. Occupancy-empty but terrain-reachable chases still retry.</p>
 */
class Xorc11SpentLeftoverUnreachableDropRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a spent leftover drops an unreachable quarry instead of waiting")
    void aSpentLeftoverDropsAnUnreachableQuarryInsteadOfWaiting() {
        Mission mission = mission("campaigns/orc-exp/levelx11o");
        Unit thrower = byId(mission.world(), 92);
        assertNotNull(thrower,
                "XOrc 11 has no Java twin for native axethrower 1508");

        tickThrough(mission, 575);
        assertEquals(6, thrower.tileX(),
                "the axethrower is still on its shore square");
        assertEquals(40, thrower.tileY());
        assertEquals(Unit.Order.ATTACK, thrower.order(),
                "the leftover residual is still an Attack chase");
        assertNotNull(thrower.target(),
                "the leftover still names the far archer");

        tickThrough(mission, 576);
        assertEquals(Unit.Order.STILL, thrower.order(),
                "native ends Attack when the leftover lands on unreachable ground");
        assertNull(thrower.target(),
                "GiveOrder clears the unroutable quarry");
        assertEquals(825, thrower.battleNetSequenceOffset(),
                "retail returns at the fresh Still marker");
        assertEquals(6, thrower.tileX(),
                "no chase step leaves the shore");
        assertEquals(40, thrower.tileY());
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
