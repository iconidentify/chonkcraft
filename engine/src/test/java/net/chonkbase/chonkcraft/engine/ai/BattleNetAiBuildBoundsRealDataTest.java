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
 * A computer player with no first building keeps the inverted 0x4273e0 box.
 *
 * <p>Authenticated Orc 1 and Human 1 game-before dumps (BNE 2.02b, seed 1,
 * {@code CHONK_BNE_TRACE_AI_BUILD_STATE=1}) store {@code 20,ff,ff,20} at
 * {@code +0x2b..+0x2e}. Native 0x4273e0 writes map size and -1 when
 * {@code 0x4be264[player]} is null. Java left those bytes zero, so the
 * compared AIPlayerState disagreed after pointer normalization. Human 4
 * expands the box around its hall and is held out.
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
}
