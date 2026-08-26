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
 * XOrc 11's walled axethrower against the sealed native capture.
 *
 * <p>Axethrower 1517 (Java 83) stands in a shore compound whose land gate no
 * route crosses to the enemy archer row it acquires at fixture 248. Retail
 * winds the Attack construction 3,2,1 across fixtures 250..252 and on 253
 * clears the target and returns to Still without a single step -- the
 * GiveOrder epilogue at 0x00453097 proved by capture. This implementation
 * used to promote the chase once the order delay ran out and then loop the
 * windup timer against the wall forever.
 */
class Xorc11UnreachableQuarryDropRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an axethrower that cannot route to its quarry drops it when the windup ends")
    void anAxethrowerDropsAnUnroutableQuarryWhenItsAttackConstructionEnds() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc-exp/levelx11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 11 is not in the pack");
        World world = mission.world();
        Unit thrower = unitById(world, 83);
        assertNotNull(thrower,
                "XOrc 11 has no Java unit 83 / native axethrower 1517");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 252) {
            mission.tick();
        }
        assertEquals(4, thrower.tileX(),
                "the axethrower holds its pocket square through the windup");
        assertEquals(37, thrower.tileY(),
                "the axethrower holds its pocket square through the windup");
        assertNotNull(thrower.target(),
                "the axethrower has acquired the archer behind the wall");

        mission.tick();
        assertEquals(253, fixtureCycle(world));
        assertEquals(null, thrower.target(),
                "retail clears the unroutable quarry on fixture 253");
        assertEquals(Unit.Order.STILL, thrower.order(),
                "the axethrower returns to Still when the windup ends");

        mission.tick();
        mission.tick();
        assertEquals(255, fixtureCycle(world));
        assertEquals(Unit.Order.STILL, thrower.order(),
                "the axethrower stays Still instead of chasing");
        assertEquals(4, thrower.tileX(),
                "no chase step ever leaves the pocket");
        assertEquals(37, thrower.tileY(),
                "no chase step ever leaves the pocket");
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
