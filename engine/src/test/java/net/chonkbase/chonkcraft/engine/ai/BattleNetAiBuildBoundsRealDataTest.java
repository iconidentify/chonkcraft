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
 * have no land building and keep {@code 20,ff,ff,20}. Orc 2 / Human 2
 * computers have only a watch tower, so {@code 0x439ce0} returns null
 * and the 64-tile box stays {@code 40,ff,ff,40}. Human 4 expands
 * to {@code 45,5f,5f,38} and Orc 4 to {@code 00,1a,07,00} after the
 * newest-first land walk and pad -5/+8. A 128-tile computer whose
 * signed min never moves pads {@code 0x80-5} to 123, so Human 5 is
 * {@code 7b,36,75,7b} and Orc 12 is {@code 7b,4d,3b,7b}. Native
 * 0x44c260 re-runs that walk on the fifty-cycle beat, so Human 8
 * becomes {@code 3e,16,56,00} at 199 and Human 5 becomes
 * {@code 7b,31,75,7b} at 1649. Sea and shore buildings stay out. A
 * computer with no gold depot must not receive a building rectangle.
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
    @DisplayName("an orc 2 computer keeps the inverted 64-tile build box")
    void anOrc2ComputerKeepsTheInverted64TileBuildBox() {
        assertBuildBox("campaigns/orc/level02o", 1, 0x40, 0xff, 0xff, 0x40);
    }

    @Test
    @DisplayName("a human 2 computer keeps the inverted 64-tile build box")
    void aHuman2ComputerKeepsTheInverted64TileBuildBox() {
        assertBuildBox("campaigns/human/level02h", 0, 0x40, 0xff, 0xff, 0x40);
    }

    @Test
    @DisplayName("a human 3 computer with farms and no hall keeps the inverted box")
    void aHuman3ComputerWithFarmsAndNoHallKeepsTheInvertedBox() {
        assertBuildBox("campaigns/human/level03h", 0, 0x40, 0xff, 0xff, 0x40);
    }

    @Test
    @DisplayName("a human 7 passive computer keeps the inverted box when its hall is on another island")
    void aHuman7PassiveComputerKeepsTheInvertedBoxWhenItsHallIsOnAnotherIsland() {
        assertBuildBox("campaigns/human/level07h", 5, 0x60, 0xff, 0xff, 0x60);
    }

    @Test
    @DisplayName("a human 9 computer keeps the inverted box when its newest unit cannot reach the hall")
    void aHuman9ComputerKeepsTheInvertedBoxWhenItsNewestUnitCannotReachTheHall() {
        assertBuildBox("campaigns/human/level09h", 1, 0x60, 0xff, 0xff, 0x60);
    }

    @Test
    @DisplayName("a human 7 computer whose newest farm shares the hall's island still expands")
    void aHuman7ComputerWhoseNewestFarmSharesTheHallsIslandStillExpands() {
        assertBuildBox("campaigns/human/level07h", 2, 0x1d, 0x5f, 0x5f, 0x28);
    }

    @Test
    @DisplayName("a human 5 computer pads the unused 128-tile min to 123")
    void aHuman5ComputerPadsTheUnused128TileMinTo123() {
        assertBuildBox("campaigns/human/level05h", 0, 0x7b, 0x36, 0x75, 0x7b);
    }

    @Test
    @DisplayName("an orc 12 computer pads the unused 128-tile min to 123")
    void anOrc12ComputerPadsTheUnused128TileMinTo123() {
        assertBuildBox("campaigns/orc/level12o", 1, 0x7b, 0x4d, 0x3b, 0x7b);
    }

    @Test
    @DisplayName("a human 13 computer keeps a wrapped 128-tile max of 134")
    void aHuman13ComputerKeepsAWrapped128TileMaxOf134() {
        assertBuildBox("campaigns/human/level13h", 0, 0x7b, 0x86, 0x3c, 0x7b);
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
    @DisplayName("a human 8 computer rewrites the land-building box on the fifty-cycle beat")
    void aHuman8ComputerRewritesTheLandBuildingBoxOnTheFiftyCycleBeat() {
        // Native writes 3e165600 at fixture 199. Java's extra hall is
        // already up at the 149 beat, so the same rectangle lands there.
        // 148 is the cycle before that beat and must keep the install box.
        assertBuildBox("campaigns/human/level08h", 0, 148, 0x3e, 0x16, 0x4f, 0x00);
        assertBuildBox("campaigns/human/level08h", 0, 149, 0x3e, 0x16, 0x56, 0x00);
    }

    @Test
    @DisplayName("an expansion computer keeps the land box while its newest tanker is inside a platform")
    void anExpansionComputerKeepsTheLandBoxWhileItsNewestTankerIsInsideAPlatform() {
        assertBuildBox("campaigns/human-exp/levelx03h", 3, 699,
                0x7b, 0x7e, 0x32, 0x7b);
    }

    @Test
    @DisplayName("an expansion computer keeps the land box while its newest peon is inside a mine")
    void anExpansionComputerKeepsTheLandBoxWhileItsNewestPeonIsInsideAMine() {
        assertBuildBox("campaigns/human-exp/levelx03h", 0, 1049,
                0x7b, 0x77, 0x57, 0x7b);
    }

    @Test
    @DisplayName("an expansion computer keeps the land box when its newest unit is a dead-vision leftover")
    void anExpansionComputerKeepsTheLandBoxWhenItsNewestUnitIsADeadVisionLeftover() {
        assertBuildBox("campaigns/human-exp/levelx07h", 6, 399,
                0x7b, 0x1e, 0x37, 0x7b);
    }

    @Test
    @DisplayName("a second expansion computer keeps the land box when a destroyer leaves a dead-vision head")
    void aSecondExpansionComputerKeepsTheLandBoxWhenADestroyerLeavesADeadVisionHead() {
        assertBuildBox("campaigns/orc-exp/levelx08o", 2, 1499,
                0x7b, 0x5f, 0x4d, 0x7b);
    }

    @Test
    @DisplayName("an expansion computer whose newest unit shares the hall island still expands")
    void anExpansionComputerWhoseNewestUnitSharesTheHallIslandStillExpands() {
        assertBuildBox("campaigns/human-exp/levelx03h", 4, 699,
                0x7b, 0x82, 0x7a, 0x7b);
    }

    @Test
    @DisplayName("a human 5 computer rewrites the land-building box on a later fifty-cycle beat")
    void aHuman5ComputerRewritesTheLandBuildingBoxOnALaterFiftyCycleBeat() {
        // Native seed-1 shrinks at 1649. Java's counted building leaves a
        // beat later, so the same 7b31757b rectangle lands at 1699.
        assertBuildBox("campaigns/human/level05h", 0, 1698, 0x7b, 0x36, 0x75, 0x7b);
        assertBuildBox("campaigns/human/level05h", 0, 1699, 0x7b, 0x31, 0x75, 0x7b);
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
        assertBuildBox(map, player, 1, minY, maxX, maxY, minX);
    }

    private static void assertBuildBox(String map, int player, int fixtureCycle,
            int minY, int maxX, int maxY, int minX) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map, 0, 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        World world = mission.world();
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int cycle = 1; cycle <= fixtureCycle; cycle++) {
            mission.tick();
        }
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
