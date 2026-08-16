package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Native 0x424ce0 opens WAIT-UNTIL 4 on the assigned-force word.
 *
 * <p>Retail adds a fighter to {@code 0x4ae04c} only when 0x4175e0 sees
 * {@code +0x5f} bit 2 clear. Orc 11 player 1 keeps four unmarked
 * archers at the home town, so the 3x1 land-force product passes and
 * the program leaves file offset 4275. Orc 5 player 1's seven
 * fighters are all PUD-marked map guards, so the same product fails
 * and retail stays on 7089.
 */
class BattleNetAiForcePredicateRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;
    private static final String SKIP =
            "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir";

    @Test
    @DisplayName("an orc 11 computer walks past the land-force wait on unmarked home archers")
    void anOrc11ComputerWalksPastTheLandForceWaitOnUnmarkedHomeArchers() {
        assertPcAfter("campaigns/orc/level11o", 1, 2, 4277);
    }

    @Test
    @DisplayName("an orc 5 computer stays on the land-force wait while every fighter is a map guard")
    void anOrc5ComputerStaysOnTheLandForceWaitWhileEveryFighterIsAMapGuard() {
        assertPcAfter("campaigns/orc/level05o", 1, 2, 7089);
        assertPcAfter("campaigns/orc/level05o", 1, 4, 7089);
    }

    private static void assertPcAfter(String map, int player, int fixtureCycle,
            int pc) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, SKIP);
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map, 0, 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int cycle = 1; cycle <= fixtureCycle; cycle++) {
            mission.tick();
        }
        AiPlayer ai = mission.world().ais().get(player);
        assertTrue(ai != null, map + " has no computer player " + player);
        AiDecisionLedger.Row row = AiDecisionLedger.fromPlayer(
                fixtureCycle, ai);
        assertTrue(row != null, map + " player " + player + " has no ai.bin row");
        assertEquals(pc, row.pcOffset(),
                map + " retail file PC after the land-force gate is " + pc);
    }
}
