package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated first refinery return of Human 7's tanker 1524. */
class Human07LoadedTankerReturnRouteRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("Human 7's loaded tanker replaces its spread point with the refinery route")
    void human7LoadedTankerUsesTheOrdinaryRefineryRoute() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level07h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 7 is not in the pack");
        World world = mission.world();
        Unit tanker = unitById(world, 76);
        assertNotNull(tanker,
                "Human 7 has no Java unit 76 / native tanker 1524");

        tickThrough(mission, 180);
        assertEquals(62, tanker.tileX());
        assertEquals(62, tanker.tileY());
        assertEquals(Unit.Order.STILL, tanker.order());
        assertEquals(69, tanker.orderTargetX(),
                "SpreadUnit stores the occupied point preceding the first free anchor");
        assertEquals(69, tanker.orderTargetY());

        tickThrough(mission, 183);
        assertEquals(Unit.Order.RETURN_GOODS, tanker.order());
        assertEquals(69, tanker.orderTargetX());
        assertEquals(69, tanker.orderTargetY());

        tickThrough(mission, 186);
        assertEquals(64, tanker.tileX());
        assertEquals(64, tanker.tileY());
        assertEquals(72, tanker.orderTargetX(),
                "MoveToDepot replaces the spread point with the refinery edge");
        assertEquals(72, tanker.orderTargetY());
        assertEquals(4, tanker.pathLength(),
                "native retains SE,SE,S,SE after consuming its first SE");
        assertEquals(Direction.fromDelta(1, 1), tanker.peekHeading());

        tickThrough(mission, 281);
        assertEquals(68, tanker.tileX());
        assertEquals(68, tanker.tileY());
        mission.tick();
        assertEquals(282, fixtureCycle(world));
        assertEquals(68, tanker.tileX());
        assertEquals(70, tanker.tileY(),
                "the fourth authenticated heading is south, not an early arrival");
        assertEquals(Direction.fromDelta(1, 1), tanker.peekHeading(),
                "the final southeast heading remains buffered after the south stride");
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static void tickThrough(Mission mission, int fixtureCycle) {
        while (fixtureCycle(mission.world()) < fixtureCycle) {
            mission.tick();
        }
    }

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }
}
