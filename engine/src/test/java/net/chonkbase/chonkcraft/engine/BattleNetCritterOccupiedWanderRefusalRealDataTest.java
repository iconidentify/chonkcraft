package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated critter collision-band witnesses from retail BNE. */
class BattleNetCritterOccupiedWanderRefusalRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("Orc 13's blocked critter keeps Move through collision ten")
    void orcThirteenBlockedCritterKeepsMoveThroughItsPaidCollisionBand() {
        Mission mission = mission("campaigns/orc/level13o");
        Unit critter = unitById(mission.world(), 144);
        assertNotNull(critter, "Orc 13 has no Java twin for native critter 1456");

        tickThrough(mission, 236);
        assertEquals(Unit.Order.STILL, critter.order(),
                "an ordinary completed one-tile wander remains the held-out control");
        assertEquals(16, critter.tileX());
        assertEquals(126, critter.tileY());

        tickThrough(mission, 293);
        assertEquals(Unit.Order.MOVE, critter.order(),
                "retail keeps the occupied northeast wander on Move at collision ten");
        assertEquals(16, critter.tileX());
        assertEquals(126, critter.tileY());
    }

    @Test
    @DisplayName("Human 14 distinguishes a completed wander from an occupied one")
    void humanFourteenOnlyParksTheOccupiedCritterWander() {
        Mission mission = mission("campaigns/human/level14h");
        Unit critter = unitById(mission.world(), 76);
        assertNotNull(critter, "Human 14 has no Java twin for native critter 1524");

        tickThrough(mission, 359);
        assertEquals(Unit.Order.STILL, critter.order(),
                "the southeast wander has arrived and is the negative control");
        assertEquals(119, critter.tileX());
        assertEquals(74, critter.tileY());

        tickThrough(mission, 399);
        assertEquals(Unit.Order.MOVE, critter.order(),
                "the later occupied northwest wander remains in its collision band");
        assertEquals(119, critter.tileX());
        assertEquals(74, critter.tileY());
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
        while (mission.world().cycle() - BNE_INITIALIZATION_TICKS < fixtureCycle) {
            mission.tick();
        }
    }

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }
}
