package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Native WaitInDepot surfaces Still so 0x439280 can spend the bank.
 *
 * <p>Authenticated Human 11 player 4 banks 720 gold by the castle visit
 * around 1275, leaves as Still, and founds a farm at {@code 72,12}. That
 * farm is newer than the watch-tower at {@code 50,50}, so the 1399
 * fifty-cycle walk can finally write max-Y 58. A computer that stayed on
 * Harvest through the hall never took the ready farm branch.
 */
class BattleNetAiDepotReadyRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;
    private static final String SKIP =
            "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir";

    @Test
    @DisplayName("a human 11 peasant founds the farm south of the castle after banking")
    void aHuman11PeasantFoundsTheFarmSouthOfTheCastleAfterBanking() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, SKIP);
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level11h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 11 is not in the pack");
        World world = mission.world();
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        boolean founded = false;
        for (int cycle = 1; cycle <= 1600; cycle++) {
            mission.tick();
            if (farmAt(world, 4, 72, 12) != null) {
                founded = true;
                break;
            }
        }
        assertTrue(founded,
                "retail founds Human 11 player 4's next farm at 72,12 after the castle visit");
    }

    @Test
    @DisplayName("that new farm lets the land box reach the southern watch tower")
    void thatNewFarmLetsTheLandBoxReachTheSouthernWatchTower() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, SKIP);
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level11h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 11 is not in the pack");
        World world = mission.world();
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int cycle = 1; cycle <= 1649; cycle++) {
            mission.tick();
        }
        assertTrue(farmAt(world, 4, 72, 12) != null,
                "the 72,12 farm is standing before the 1649 land-box beat");
        AiPlayer ai = world.ais().get(4);
        assertTrue(ai != null, "Human 11 has a computer player 4");
        byte[] packed = ai.packDecisionState();
        assertTrue(packed != null, "Human 11 player 4 has an ai.bin state");
        assertEquals(58, packed[BattleNetAiBytecode.OFF_BOUND_MAX_Y] & 0xff,
                "retail max-Y becomes 58 once the 50,50 tower is no longer first");
    }

    private static Unit farmAt(World world, int player, int tileX, int tileY) {
        for (Unit unit : world.units()) {
            if (unit == null || unit.player() != player || !unit.isAlive()
                    || unit.type() == null) {
                continue;
            }
            if (unit.type().ident().contains("farm") && unit.type().building()
                    && unit.tileX() == tileX && unit.tileY() == tileY) {
                return unit;
            }
        }
        return null;
    }
}
