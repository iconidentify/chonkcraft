package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
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
 * A computer player's next instruction is a compared {@code ai.bin} offset.
 *
 * <p>The comparison program already refuses raw process pointers. Java used
 * to keep the PC, list, and threshold as loose fields and emit nothing, so
 * there was no Java side to compare.
 */
class BattleNetAiDecisionLedgerEmitRealDataTest {

    private static final int INIT = 2;

    @Test
    @DisplayName("an orc 1 computer player's next instruction is an ai.bin offset")
    void anOrc1ComputerPlayersNextInstructionIsAnAiBinOffset() {
        List<AiDecisionLedger.Row> rows = emit("campaigns/orc/level01o", 8);
        assertFalse(rows.isEmpty(),
                "Orc 1 has computer players that must appear in the ledger");
        for (AiDecisionLedger.Row row : rows) {
            assertTrue(row.pcOffset() >= 0,
                    "the next instruction must be an ai.bin offset, not "
                            + row.pcOffset());
            assertTrue(row.listOffset() >= 0,
                    "the ordered list must be an ai.bin offset, not "
                            + row.listOffset());
            assertTrue(row.thresholdOffset() >= 0,
                    "the threshold table must be an ai.bin offset, not "
                            + row.thresholdOffset());
            assertEquals(72, row.nonPointerHex().length(),
                    "the compared state is the 36 non-pointer bytes");
            assertTrue("independent-choice".equals(row.classification())
                            || "fallout".equals(row.classification()),
                    "a row must say whether this cycle chose or followed");
        }
        assertTrue(rows.stream().anyMatch(row -> !row.writes().isEmpty()),
                "the live ledger must report real AI state mutations, not only snapshots");
    }

    @Test
    @DisplayName("two identical orc 1 ticks write the same compared offsets")
    void twoIdenticalOrc1TicksWriteTheSameComparedOffsets() {
        String first = AiDecisionLedger.toJson(emit("campaigns/orc/level01o", 8));
        String second = AiDecisionLedger.toJson(emit("campaigns/orc/level01o", 8));
        assertFalse(first.contains("\"rows\":[]"),
                "Orc 1 must emit compared computer-player rows");
        assertEquals(first, second,
                "two identical ticks must compare equal after packing");
    }

    @Test
    @DisplayName("a shifted program counter fails at that cycle and field")
    void aShiftedProgramCounterFailsAtThatCycleAndField() {
        List<AiDecisionLedger.Row> rows = emit("campaigns/orc/level01o", 8);
        assertFalse(rows.isEmpty(),
                "Orc 1 must emit compared computer-player rows");
        AiDecisionLedger.Row first = rows.getFirst();
        AiDecisionLedger.Row shifted = new AiDecisionLedger.Row(
                first.cycle(), first.player(), first.profile(), first.waitCount(),
                first.pcOffset() + 8, first.listOffset(), first.thresholdOffset(),
                first.nonPointerHex(), first.predicates(), first.writes(),
                first.launches(), first.classification());
        List<AiDecisionLedger.Row> mutated = new ArrayList<>(rows);
        mutated.set(0, shifted);
        String left = AiDecisionLedger.toJson(rows);
        String right = AiDecisionLedger.toJson(mutated);
        assertFalse(left.equals(right),
                "a shifted ai.bin program counter must not compare equal");
        assertTrue(right.contains("\"pc_offset\":" + (first.pcOffset() + 8)),
                "the first difference must be the shifted program counter");
    }

    @Test
    @DisplayName("a long install wait is still 65532 at game-before cycle 1")
    void aLongInstallWaitIsStill65532AtGameBeforeCycle1() {
        // Native Human 1 p0 and Orc 2 p1 warmup-1 is a no-op on WAIT 65534.
        // Game-before 1 is 65532. Ticking world.cycle 1 used to report 65531.
        // Orc 1's bootstrap wait 1 must still tick (game-before 1 is 0).
        assertEquals(65532, firstWait("campaigns/human/level01h", 0),
                "Human 1 player 0 is wait 65532 at game-before 1");
        assertEquals(65532, firstWait("campaigns/orc/level02o", 1),
                "Orc 2 player 1 is wait 65532 at game-before 1");
        assertEquals(0, firstWait("campaigns/orc/level01o", 1),
                "Orc 1 player 1 is wait 0 at game-before 1");
    }

    @Test
    @DisplayName("a human 5 computer still holds the wait-until yield at cycle 1")
    void aHuman5ComputerStillHoldsTheWaitUntilYieldAtCycle1() {
        List<AiDecisionLedger.Row> rows = emit("campaigns/human/level05h", 2);
        List<AiDecisionLedger.Row> player5 = rowsFor(rows, 5);
        assertTrue(player5.size() >= 2,
                "Human 5 player 5 must emit the first two gameplay cycles");
        assertEquals(0, player5.get(0).waitCount(),
                "Human 5 player 5 game-after 1 is wait 0 after the yield");
        assertEquals(561, player5.get(0).pcOffset(),
                "Human 5 player 5 is still on the SETs that follow predicate 3");
        assertEquals(6000, player5.get(1).waitCount(),
                "Human 5 player 5 writes WAIT 6000 on game-after 2");
        assertEquals(575, player5.get(1).pcOffset(),
                "Human 5 player 5 is past WAIT 6000 on game-after 2");
        assertEquals(65532, rowsFor(rows, 0).get(0).waitCount(),
                "Human 5 player 0 still keeps its long opening WAIT");
    }

    @Test
    @DisplayName("an orc 5 land computer stays on the force-size wait-until")
    void anOrc5LandComputerStaysOnTheForceSizeWaitUntil() {
        List<AiDecisionLedger.Row> rows = emit("campaigns/orc/level05o", 4);
        List<AiDecisionLedger.Row> player1 = rowsFor(rows, 1);
        assertTrue(player1.size() >= 4,
                "Orc 5 player 1 must emit the first four gameplay cycles");
        assertEquals(7080, player1.get(0).pcOffset(),
                "Orc 5 player 1 game-after 1 is still on the force-want SETs");
        assertEquals(7089, player1.get(1).pcOffset(),
                "Orc 5 player 1 stays on WAIT-UNTIL 4 after the SETs");
        assertEquals(1, player1.get(1).waitCount(),
                "Orc 5 player 1 yields one tick on the failed force gate");
        assertEquals(7089, player1.get(3).pcOffset(),
                "Orc 5 player 1 is still on WAIT-UNTIL 4 at game-after 4");
    }

    @Test
    @DisplayName("an orc 5 computer still holds the wait-until yield at cycle 1")
    void anOrc5ComputerStillHoldsTheWaitUntilYieldAtCycle1() {
        List<AiDecisionLedger.Row> rows = emit("campaigns/orc/level05o", 2);
        List<AiDecisionLedger.Row> player0 = rowsFor(rows, 0);
        assertTrue(player0.size() >= 2,
                "Orc 5 player 0 must emit the first two gameplay cycles");
        assertEquals(0, player0.get(0).waitCount(),
                "Orc 5 player 0 game-after 1 is wait 0 after the yield");
        assertEquals(3059, player0.get(0).pcOffset(),
                "Orc 5 player 0 is still on the SETs that follow predicate 3");
        assertEquals(6000, player0.get(1).waitCount(),
                "Orc 5 player 0 writes WAIT 6000 on game-after 2");
        assertEquals(3073, player0.get(1).pcOffset(),
                "Orc 5 player 0 is past WAIT 6000 on game-after 2");
    }

    @Test
    @DisplayName("program-counter writes are recorded across three campaign profiles")
    void programCounterWritesAreRecordedAcrossThreeCampaignProfiles() {
        assertWrite("campaigns/orc/level05o", 0, 2, 0x04, 0xf3, 0x01);
        assertWrite("campaigns/orc/level07o", 0, 2, 0x04, 0xf8, 0xfd);
        assertWrite("campaigns/orc/level10o", 1, 2, 0x04, 0x2e, 0x45);
    }

    @Test
    @DisplayName("periodic launch-byte writes are recorded across force domains")
    void periodicLaunchByteWritesAreRecordedAcrossForceDomains() {
        assertWrite("campaigns/orc/level11o", 1, 49,
                BattleNetAiBytecode.OFF_LAUNCH_GROUND, 1, 0);
        assertWrite("campaigns/human-exp/levelx12h", 2, 49,
                BattleNetAiBytecode.OFF_LAUNCH_GROUND, 1, 0);
        assertWrite("campaigns/orc-exp/levelx11o", 6, 49,
                BattleNetAiBytecode.OFF_LAUNCH_AIR, 1, 0);

        List<AiDecisionLedger.Row> sameCycle = rowsFor(
                emit("campaigns/orc-exp/levelx08o", 1499), 2);
        assertTrue(sameCycle.size() >= 1499,
                "XOrc 8 player 2 has no cycle-1499 AI row");
        assertFalse(sameCycle.get(1498).writes().stream().anyMatch(
                        write -> write.offset()
                                == BattleNetAiBytecode.OFF_LAUNCH_NAVAL),
                "arming and consuming one launch byte in the same cycle has "
                        + "no committed net write");
    }

    @Test
    @DisplayName("a computer whose install stored zero still arms the builder-scan latch")
    void aComputerWhoseInstallStoredZeroStillArmsTheBuilderScanLatch() {
        // Human 1 profile 1 and Human 4 profile 3 both SET +0x0c=0. Native
        // 0x428160 already sees 1 at warmup-before. Orc 1 SET 1 and must stay 1.
        assertArmed("campaigns/human/level01h");
        assertArmed("campaigns/human/level04h");
        assertArmed("campaigns/orc/level01o");
    }

    @Test
    @DisplayName("a person-only map does not invent a computer instruction")
    void aPersonOnlyMapDoesNotInventAComputerInstruction() {
        World world = new World(new GameMap(16, 16, new Tileset()));
        List<AiDecisionLedger.Row> rows = AiDecisionLedger.snapshot(world, 1);
        assertTrue(rows.isEmpty(),
                "a map with no computer player has no ai.bin instruction");
    }

    private static void assertArmed(String map) {
        List<AiDecisionLedger.Row> rows = emit(map, 1);
        assertFalse(rows.isEmpty(), map + " must emit a computer-player row");
        for (AiDecisionLedger.Row row : rows) {
            String hex = row.nonPointerHex();
            assertEquals(72, hex.length(),
                    "the compared state is the 36 non-pointer bytes");
            assertEquals("01", hex.substring(16, 18),
                    map + " player " + row.player()
                            + " must keep +0x0c armed for the 0x428160 builder scan");
        }
    }

    private static List<AiDecisionLedger.Row> rowsFor(
            List<AiDecisionLedger.Row> rows, int player) {
        List<AiDecisionLedger.Row> found = new ArrayList<>();
        for (AiDecisionLedger.Row row : rows) {
            if (row.player() == player) {
                found.add(row);
            }
        }
        return found;
    }

    private static int firstWait(String map, int player) {
        List<AiDecisionLedger.Row> rows = emit(map, 1);
        for (AiDecisionLedger.Row row : rows) {
            if (row.player() == player) {
                return row.waitCount();
            }
        }
        throw new AssertionError(map + " player " + player
                + " has no cycle-1 AI row");
    }

    private static void assertWrite(String map, int player, int cycle,
            int offset, int before, int after) {
        List<AiDecisionLedger.Row> playerRows = rowsFor(emit(map, cycle), player);
        assertTrue(playerRows.size() >= cycle,
                map + " player " + player + " has no cycle-" + cycle + " AI row");
        assertTrue(playerRows.get(cycle - 1).writes().contains(
                        new AiPlayer.DecisionWrite(offset, before, after)),
                map + " player " + player + " cycle " + cycle
                        + " must record the authenticated packed-state write at +0x"
                        + Integer.toHexString(offset));
    }

    private static List<AiDecisionLedger.Row> emit(String map, int last) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map, 0);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        World world = mission.world();
        for (int tick = 0; tick < INIT; tick++) {
            mission.tick();
        }
        List<AiDecisionLedger.Row> rows = new ArrayList<>();
        for (int cycle = 1; cycle <= last; cycle++) {
            mission.tick();
            rows.addAll(AiDecisionLedger.snapshot(world, (int) world.cycle()));
        }
        return rows;
    }
}
