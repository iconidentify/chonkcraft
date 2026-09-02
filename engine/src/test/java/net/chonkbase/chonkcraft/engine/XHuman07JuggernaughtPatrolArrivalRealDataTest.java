package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated XHuman 7 capital-ship Patrol-to-Still RNG boundary. */
class XHuman07JuggernaughtPatrolArrivalRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 7 juggernaught rearms naval idle on patrol arrival")
    void juggernaughtRearmsNavalIdleOnPatrolArrival() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx07h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        World world = mission.world();
        Unit juggernaught = unitById(world, 27);
        assertNotNull(juggernaught,
                "XHuman 7 has no native-slot-1573 juggernaught");

        while (fixtureCycle(world) < 59) {
            mission.tick();
        }

        assertEquals(24, juggernaught.tileX());
        assertEquals(26, juggernaught.tileY());
        assertEquals(Unit.Order.STILL, juggernaught.order(),
                "native exhausts the one-heading map patrol on fixture 59");
        assertEquals(14, juggernaught.battleNetFlyingIdleTimer(),
                "native immediately pays FUN_0040AE30 on Patrol-to-Still arrival");
        assertEquals(2_532_760_218L,
                Integer.toUnsignedLong(world.battleNetRandomSeed()),
                "the fixture-59 native async ledger must remain exact");

        while (fixtureCycle(world) < 103) {
            mission.tick();
        }
        assertEquals(Unit.Order.STILL, juggernaught.order(),
                "the short replacement patrol also finishes on fixture 103");
        assertEquals(5, juggernaught.battleNetFlyingIdleTimer(),
                "a live naval idle timer decrements instead of drawing again");
        assertEquals(1_138_277_617L,
                Integer.toUnsignedLong(world.battleNetRandomSeed()),
                "the fixture-103 native async ledger must remain exact");
    }

    @Test
    @DisplayName("xhuman 7 juggernaught spends one residual refusal band behind its tanker")
    void juggernaughtSpendsOneResidualRefusalBandBehindItsTanker() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx07h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        World world = mission.world();
        Unit juggernaught = unitById(world, 27);
        Unit tanker = unitById(world, 29);
        assertNotNull(juggernaught,
                "XHuman 7 has no native-slot-1573 juggernaught");
        assertNotNull(tanker, "XHuman 7 has no moving platform tanker");

        while (fixtureCycle(world) < 462) {
            mission.tick();
        }
        assertEquals(30, juggernaught.tileX());
        assertEquals(28, juggernaught.tileY());
        assertEquals(4, juggernaught.pathLength(),
                "the cached SE,E,SE,E patrol tail remains live");
        assertEquals(3, juggernaught.battleNetPathStepsTaken());
        assertEquals(4, juggernaught.battleNetCollisionCounter(),
                "the occupied southeast wake advances generation three to four");
        assertEquals(14, juggernaught.battleNetOrderDelay());
        assertEquals(15, juggernaught.battleNetAnimationTimer());

        while (fixtureCycle(world) < 476) {
            mission.tick();
        }
        assertEquals(30, tanker.tileX(),
                "the tanker has logically vacated the cached anchor");
        assertEquals(30, tanker.tileY());
        assertEquals(1, juggernaught.battleNetAnimationTimer(),
                "native serves one complete Move refusal band");

        mission.tick();
        assertEquals(477, fixtureCycle(world));
        assertEquals(32, juggernaught.tileX(),
                "the first wake consumes the retained southeast heading");
        assertEquals(30, juggernaught.tileY());
        assertEquals(3, juggernaught.pathLength());
        assertEquals(4, juggernaught.battleNetCollisionCounter(),
                "a successful wake retains the paid generation");
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
