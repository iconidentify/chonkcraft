package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.ai.AiPlayer;
import net.chonkbase.chonkcraft.engine.ai.BattleNetAiBytecode;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Native 0x416c40 / 0x439de0 refuses a farm that sits on another
 * building's body.
 *
 * <p>Authenticated Human 8 seed-1 peons leave the hall already walking
 * to a pig-farm: player 3 to {@code 62,14} (order point 63,14) and
 * player 0 to {@code 3,67} (order point 4,68). The two-tile farm
 * lattice tests {@code 66,14} before {@code 62,14}; that square is the
 * south-east tile of the existing pig-farm at {@code 65,13}. Player
 * CheckCanBuild only refuses a solid origin -- Garden of War's
 * blacksmith still founds on a hall body -- so the AI ring used to
 * take 66,14 and rewrite player 3's box at the cycle-99 beat.
 */
class BattleNetAiFarmSiteRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;
    private static final String SKIP =
            "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir";

    @Test
    @DisplayName("a human 8 peon walks past the neighbouring farm to the free site")
    void aHuman8PeonWalksPastTheNeighbouringFarmToTheFreeSite() {
        assertFarmSite("campaigns/human/level08h", 3, 69, 9, 62, 14);
    }

    @Test
    @DisplayName("a second human 8 peon founds west of his existing farm, not on it")
    void aSecondHuman8PeonFoundsWestOfHisExistingFarmNotOnIt() {
        assertFarmSite("campaigns/human/level08h", 0, 8, 75, 3, 67);
    }

    @Test
    @DisplayName("a human 8 northern peon founds on the free tile south of his farm")
    void aHuman8NorthernPeonFoundsOnTheFreeTileSouthOfHisFarm() {
        assertFarmSite("campaigns/human/level08h", 1, 11, 17, 11, 7);
    }

    @Test
    @DisplayName("a human 8 town does not rewrite the land box when the farm is still on the road")
    void aHuman8TownDoesNotRewriteTheLandBoxWhenTheFarmIsStillOnTheRoad() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, SKIP);
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level08h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is not in the pack");
        World world = mission.world();
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int cycle = 1; cycle <= 99; cycle++) {
            mission.tick();
        }
        AiPlayer ai = world.ais().get(3);
        assertTrue(ai != null, "Human 8 has a computer player 3");
        byte[] packed = ai.packDecisionState();
        assertNotNull(packed, "Human 8 player 3 has an ai.bin state");
        assertEquals(0x03, packed[BattleNetAiBytecode.OFF_BOUND_MIN_Y] & 0xff,
                "retail min-Y stays 3 while the new farm is still a walk");
        assertEquals(0x59, packed[BattleNetAiBytecode.OFF_BOUND_MAX_X] & 0xff,
                "retail max-X stays 89 while the new farm is still a walk");
        assertEquals(0x07, packed[BattleNetAiBytecode.OFF_BOUND_MAX_Y] & 0xff,
                "retail max-Y stays the unexpanded wrap until the farm is founded");
        assertEquals(0x3b, packed[BattleNetAiBytecode.OFF_BOUND_MIN_X] & 0xff,
                "retail min-X stays 59 while the new farm is still a walk");
    }

    private static void assertFarmSite(String map, int player, int peonX,
            int peonY, int siteX, int siteY) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, SKIP);
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map, 0, 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        World world = mission.world();
        Unit peon = null;
        UnitType farm = null;
        for (Unit unit : world.units()) {
            if (unit == null || unit.type() == null) {
                continue;
            }
            if (farm == null && unit.type().ident().contains("farm")
                    && unit.type().building()) {
                farm = unit.type();
            }
            if (unit.player() == player && unit.tileX() == peonX
                    && unit.tileY() == peonY
                    && unit.type().ident().contains("peon")) {
                peon = unit;
            }
        }
        assertTrue(peon != null,
                map + " player " + player + " has a peon at " + peonX + "," + peonY);
        assertTrue(farm != null, map + " has a farm type to place");
        int[] site = world.aiFindBattleNetFoodPlace(peon, farm);
        assertNotNull(site,
                map + " player " + player + " must still find a farm site");
        assertArrayEquals(new int[] {siteX, siteY}, site,
                map + " player " + player + " retail founds the opening farm at "
                        + siteX + "," + siteY);
    }
}
