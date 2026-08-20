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

/** Authenticated recurring land-assault order timing from retail BNE. */
class Orc11RecurringLandPatrolPassRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("orc 11 refreshes its moving land-assault patrol on fixture 99")
    void orc11RefreshesItsMovingLandAssaultPatrolOnFixture99() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc/level11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Orc 11 is not in the pack");
        World world = mission.world();

        // Fixture pairing: native 1558/1559 are Java 42/41. Retail gives all
        // four behavior-two land-assault members a replacement Patrol on its
        // fifty-cycle beat. The replacement is next_order at fixture 99,
        // promotes through Still construction at 101, and first-steps at 104.
        Unit knight = unitById(world, 42);
        Unit archer = unitById(world, 41);
        assertNotNull(knight, "Orc 11 has no Java unit 42 / native knight 1558");
        assertNotNull(archer, "Orc 11 has no Java unit 41 / native archer 1559");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 104) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 99) {
                assertTrue(knight.hasBattleNetPendingPatrol(),
                        "the fifty-cycle pass queues the knight's replacement Patrol");
                assertTrue(archer.hasBattleNetPendingPatrol(),
                        "the same pass queues the archer's replacement Patrol");
            }
            if (fixture == 101) {
                assertEquals(Unit.Order.PATROL, knight.order());
                assertEquals(Unit.Order.PATROL, archer.order());
                assertEquals(114, knight.tileX(),
                        "Still construction holds native knight 1558 on fixture 101");
                assertEquals(40, knight.tileY());
                assertEquals(118, archer.tileX(),
                        "Still construction holds native archer 1559 on fixture 101");
                assertEquals(39, archer.tileY());
            }
        }

        assertEquals(115, knight.tileX(),
                "the refreshed knight Patrol first-steps on fixture 104");
        assertEquals(40, knight.tileY());
        assertEquals(119, archer.tileX(),
                "the refreshed archer Patrol first-steps on fixture 104");
        assertEquals(39, archer.tileY());
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
