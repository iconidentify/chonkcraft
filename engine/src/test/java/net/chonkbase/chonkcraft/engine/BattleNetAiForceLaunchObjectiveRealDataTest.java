package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.ai.AiPlayer;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
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
                "campaigns/orc-exp/levelx11o", 6, 49, "air");

        assertEquals(1, launch.requested());
        assertEquals(1, launch.assigned());
        assertEquals(2, launch.targetX());
        assertEquals(54, launch.targetY());
    }

    @Test
    @DisplayName("xorc 8 recurring naval launch targets the person's shipyard")
    void xorc8NavalLaunchUsesTheNativeShipyardObjective() {
        AiPlayer.DecisionLaunch launch = launchAt(
                "campaigns/orc-exp/levelx08o", 2, 1499, "naval");

        assertEquals(3, launch.requested());
        assertEquals(3, launch.assigned());
        assertEquals(98, launch.targetX());
        assertEquals(122, launch.targetY());
    }

    private static AiPlayer.DecisionLaunch launchAt(
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
        return ai.battleNetDecisionLaunches().stream()
                .filter(candidate -> domain.equals(candidate.domain()))
                .findFirst().orElseThrow();
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }
}
