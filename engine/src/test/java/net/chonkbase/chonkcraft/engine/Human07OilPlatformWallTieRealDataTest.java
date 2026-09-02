package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated marked-platform wall tie on retail Human 7. */
class Human07OilPlatformWallTieRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a doubled tanker keeps the marked-platform wall route on a free-prefix tie")
    void doubledTankerKeepsTheMarkedPlatformWallRouteOnTie() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level07h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        World world = mission.world();
        Unit tanker = unitById(world, 98);
        Unit platform = unitById(world, 106);
        assertNotNull(tanker,
                "Human 7 has no Java unit 98 / native tanker 1502");
        assertNotNull(platform,
                "Human 7 has no Java unit 106 / native platform 1494");

        tickThrough(mission, 461);
        assertSame(platform, tanker.resourceUnit());
        assertEquals(Unit.Order.HARVEST, tanker.order());
        assertEquals(58, tanker.tileX());
        assertEquals(82, tanker.tileY());
        assertEquals(3, tanker.pathLength(),
                "native keeps SW,W,W after consuming the wall route's west head");
        assertEquals(Direction.fromDelta(-1, 1), tanker.peekHeading(),
                "the equal-gain W,W,W free prefix must not replace native W,SW,W,W");

        tickThrough(mission, 492);
        assertEquals(58, tanker.tileX());
        assertEquals(82, tanker.tileY());
        mission.tick();
        assertEquals(493, fixtureCycle(world));
        assertEquals(56, tanker.tileX());
        assertEquals(84, tanker.tileY(),
                "the second marked-wall heading commits southwest on fixture 493");
    }

    private static void tickThrough(Mission mission, int fixtureCycle) {
        while (fixtureCycle(mission.world()) < fixtureCycle) {
            mission.tick();
        }
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
