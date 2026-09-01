package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.ai.AiPlayer;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated cross-domain witnesses for recurring native force targets. */
class BattleNetAiForceLaunchObjectiveRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xorc 11 recurring air launch targets the person's closest mine")
    void xorc11AirLaunchUsesTheNativeMineObjective() {
        AiPlayer.DecisionLaunch launch = launchAt(
                "campaigns/orc-exp/levelx11o", 6, 49, "air").launch();

        assertEquals(1, launch.requested());
        assertEquals(1, launch.assigned());
        assertEquals(2, launch.targetX());
        assertEquals(54, launch.targetY());
    }

    @Test
    @DisplayName("xorc 8 recurring naval launch targets a person's naval unit")
    void xorc8NavalLaunchUsesTheNativeShipyardObjective() {
        LaunchObservation observation = launchAt(
                "campaigns/orc-exp/levelx08o", 2, 1499, "naval");
        AiPlayer.DecisionLaunch launch = observation.launch();

        assertEquals(3, launch.requested());
        assertEquals(3, launch.assigned());
        assertNotNull(navalObjectiveAt(observation.world(),
                        launch.targetX(), launch.targetY()),
                "selector one must target a person-owned oil platform,"
                        + " shipyard, or mobile naval fallback; an exact late"
                        + " coordinate is not stable once the replay has"
                        + " already diverged from the native world");
    }

    private static LaunchObservation launchAt(
            String map, int player, int targetCycle, String domain) {
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
        while (fixtureCycle(mission.world()) < targetCycle) {
            mission.tick();
        }
        AiPlayer ai = mission.world().enableAi(player);
        AiPlayer.DecisionLaunch launch = ai.battleNetDecisionLaunches().stream()
                .filter(candidate -> domain.equals(candidate.domain()))
                .findFirst().orElseThrow();
        return new LaunchObservation(launch, mission.world());
    }

    private static Unit navalObjectiveAt(World world, int tileX, int tileY) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.tileX() == tileX && unit.tileY() == tileY)
                .filter(unit -> unit.type() != null)
                .filter(unit -> world.player(unit.player()) != null
                        && world.player(unit.player()).type()
                                == PudMap.PlayerType.PERSON)
                .filter(unit -> unit.type().seaUnit()
                        || "unit-human-shipyard".equals(unit.type().ident())
                        || "unit-orc-shipyard".equals(unit.type().ident())
                        || unit.type().building()
                        && unit.type().givesResource()
                                == UnitType.Resource.OIL)
                .findFirst().orElse(null);
    }

    private record LaunchObservation(AiPlayer.DecisionLaunch launch,
            World world) { }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }
}
