package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Native 0x4273e0 writes the computer's land-building box.
 *
 * <p>Authenticated game-before dumps (BNE 2.02b, seed 1,
 * {@code CHONK_BNE_TRACE_AI_BUILD_STATE=1}): Orc 1 / Human 1 computers
 * have no land building and keep {@code 20,ff,ff,20}. Human 4 expands
 * to {@code 45,5f,5f,38} and Orc 4 to {@code 00,1a,07,00} after the
 * newest-first land walk and pad -5/+8. Sea and shore buildings stay
 * out. A computer with no land building must not receive a hall
 * rectangle.
 */
class BattleNetAiBuildBoundsRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;
    private static final String ORC1_GAME_BEFORE_NON_POINTER =
            "0000000000000000010001000100010100000000000000000000000000000320ffff2000";

    @Test
    @DisplayName("an orc 1 computer player keeps the inverted 32-tile build box")
    void anOrc1ComputerPlayerKeepsTheInverted32TileBuildBox() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc/level01o", 0);
        Assumptions.assumeTrue(mission != null, "Orc 1 is not in the pack");
        World world = mission.world();
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        mission.tick();
        List<AiDecisionLedger.Row> rows = AiDecisionLedger.snapshot(
                world, (int) world.cycle());
        assertFalse(rows.isEmpty(),
                "Orc 1 has a computer player that must appear in the ledger");
        AiDecisionLedger.Row computer = null;
        for (AiDecisionLedger.Row row : rows) {
            if (row.player() == 1) {
                computer = row;
                break;
            }
        }
        assertTrue(computer != null, "Orc 1 computer player 1 is missing");
        assertEquals(32, world.map().width(),
                "Orc 1 is a 32-tile map, so the inverted box uses 32");
        assertEquals(ORC1_GAME_BEFORE_NON_POINTER, computer.nonPointerHex(),
                "retail's Orc 1 game-before box is 32,-1,-1,32 after the wants");
    }

    @Test
    @DisplayName("a human 4 computer expands the land-building box")
    void aHuman4ComputerExpandsTheLandBuildingBox() {
        assertBuildBox("campaigns/human/level04h", 0, 69, 95, 95, 56);
    }

    @Test
    @DisplayName("an orc 4 computer expands the land-building box")
    void anOrc4ComputerExpandsTheLandBuildingBox() {
        assertBuildBox("campaigns/orc/level04o", 1, 0, 26, 7, 0);
    }

    @Test
    @DisplayName("a 32-tile computer with no building keeps the inverted box")
    void a32TileComputerWithNoBuildingKeepsTheInvertedBox() {
        World world = new World(new GameMap(32, 32, new Tileset()));
        byte[] state = new byte[BattleNetAiBytecode.STATE_BYTES];
        BattleNetAiBytecode.installEmptyBuildBounds(state, world.map().width());
        assertEquals(0x20, state[BattleNetAiBytecode.OFF_BOUND_MIN_Y] & 0xff,
                "native stores map size as min-Y when the building list is empty");
        assertEquals(0xff, state[BattleNetAiBytecode.OFF_BOUND_MAX_X] & 0xff,
                "native stores -1 as max-X when the building list is empty");
        assertEquals(0xff, state[BattleNetAiBytecode.OFF_BOUND_MAX_Y] & 0xff,
                "native stores -1 as max-Y when the building list is empty");
        assertEquals(0x20, state[BattleNetAiBytecode.OFF_BOUND_MIN_X] & 0xff,
                "native stores map size as min-X when the building list is empty");
    }

    @Test
    @DisplayName("a person-only map does not invent a build box")
    void aPersonOnlyMapDoesNotInventABuildBox() {
        World world = new World(new GameMap(32, 32, new Tileset()));
        List<AiDecisionLedger.Row> rows = AiDecisionLedger.snapshot(world, 1);
        assertTrue(rows.isEmpty(),
                "a map with no computer player has no ai.bin build box");
    }

    private static void assertBuildBox(String map, int player,
            int minY, int maxX, int maxY, int minX) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map, 0);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        World world = mission.world();
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        mission.tick();
        AiPlayer ai = world.ais().get(player);
        assertTrue(ai != null, map + " has no computer player " + player);
        byte[] packed = ai.packDecisionState();
        assertTrue(packed != null, map + " computer " + player + " has no state");
        assertEquals(minY, packed[BattleNetAiBytecode.OFF_BOUND_MIN_Y] & 0xff,
                map + " retail min-Y is " + minY + " after the land-building walk");
        assertEquals(maxX, packed[BattleNetAiBytecode.OFF_BOUND_MAX_X] & 0xff,
                map + " retail max-X is " + maxX + " after the land-building walk");
        assertEquals(maxY, packed[BattleNetAiBytecode.OFF_BOUND_MAX_Y] & 0xff,
                map + " retail max-Y is " + maxY + " after the land-building walk");
        assertEquals(minX, packed[BattleNetAiBytecode.OFF_BOUND_MIN_X] & 0xff,
                map + " retail min-X is " + minX + " after the land-building walk");
    }
}
