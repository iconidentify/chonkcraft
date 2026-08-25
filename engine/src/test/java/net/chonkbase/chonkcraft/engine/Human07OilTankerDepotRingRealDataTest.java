package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated doubled-tanker arrival at Human 7's eastern refinery. */
class Human07OilTankerDepotRingRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("Human 7's loaded tanker parks its leftover west heading on the refinery ring")
    void loadedTankerParksItsLeftoverWestHeadingOnTheRefineryRing() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level07h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        World world = mission.world();
        Unit tanker = unitById(world, 109);
        assertNotNull(tanker,
                "Human 7 has no Java unit 109 / native tanker 1491");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 251) {
            mission.tick();
        }

        assertEquals(76, tanker.tileX());
        assertEquals(76, tanker.tileY());
        assertTrue(tanker.isMoving(),
                "fixture 251 still owes the last two diagonal pixels");
        assertEquals(1, tanker.pathLength(),
                "the cached west heading remains behind the native cursor");

        mission.tick();
        assertEquals(252, fixtureCycle(world));
        assertEquals(76, tanker.tileX(),
                "action 25 parks on the ring instead of consuming west");
        assertEquals(76, tanker.tileY());
        assertFalse(tanker.isMoving());
        assertEquals(0, tanker.pathLength());
        assertTrue(tanker.battleNetResourceApproachStaged(),
                "the settle visit must promote MoveToDepot to action 25");
        assertEquals(3, tanker.battleNetAnimationTimer(),
                "native starts action 25 at timer three");
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
