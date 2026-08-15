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
                first.nonPointerHex(), first.classification());
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
    @DisplayName("a person-only map does not invent a computer instruction")
    void aPersonOnlyMapDoesNotInventAComputerInstruction() {
        World world = new World(new GameMap(16, 16, new Tileset()));
        List<AiDecisionLedger.Row> rows = AiDecisionLedger.snapshot(world, 1);
        assertTrue(rows.isEmpty(),
                "a map with no computer player has no ai.bin instruction");
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
