package net.chonkbase.chonkcraft.engine.ai;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.chonkbase.chonkcraft.engine.World;

/**
 * Normalized {@code ai.bin} decision rows from a live Java computer player.
 *
 * <p>Same schema as {@code bne_ai_decision_ledger.py}. Pointers are already
 * {@code ai.bin} file offsets. Dual identical ticks must compare equal; a
 * shifted PC must fail at that cycle and field.
 */
public final class AiDecisionLedger {

    public static final String SCHEMA = "chonkcraft-bne-ai-decision-ledger-2";
    public static final String AUTHORITY_SHA256 =
            "b0e914a9cb7dcc81a205e700a9bb0a1d0649df19d459388051ba170783d2c807";

    public record Row(int cycle, int player, int profile, int waitCount,
            int pcOffset, int listOffset, int thresholdOffset,
            String nonPointerHex,
            List<AiPlayer.DecisionPredicate> predicates,
            List<AiPlayer.DecisionWrite> writes,
            List<AiPlayer.DecisionLaunch> launches,
            String classification) {
    }

    private AiDecisionLedger() {
    }

    /** One row for a live retail program, or {@code null} if none is armed. */
    public static Row fromPlayer(int cycle, AiPlayer ai) {
        if (ai == null || cycle < 1) {
            return null;
        }
        byte[] packed = ai.packDecisionState();
        if (packed == null) {
            return null;
        }
        int aiSize = ai.battleNetAiProfileData() == null
                ? Integer.MAX_VALUE : ai.battleNetAiProfileData().length;
        int pc = readU32(packed, 0x04);
        int list = readU32(packed, 0x23);
        int threshold = readU32(packed, 0x27);
        if (pc >= aiSize || list >= aiSize || threshold >= aiSize) {
            throw new IllegalStateException(
                    "AI.BIN pointer is not a file offset for player "
                            + ai.playerIndex());
        }
        return new Row(cycle, ai.playerIndex(), ai.battleNetBuildProfileId(),
                BattleNetAiBytecode.waitCounter(packed), pc, list, threshold,
                nonPointerHex(packed), ai.battleNetDecisionPredicates(),
                ai.battleNetDecisionWrites(), ai.battleNetDecisionLaunches(),
                ai.battleNetLastTickIndependent()
                        ? "independent-choice" : "fallout");
    }

    /** One row per armed computer player, sorted by player. */
    public static List<Row> snapshot(World world, int cycle) {
        List<Row> rows = new ArrayList<>();
        if (world == null) {
            return rows;
        }
        for (AiPlayer ai : world.ais().values()) {
            Row row = fromPlayer(cycle, ai);
            if (row != null) {
                rows.add(row);
            }
        }
        rows.sort(Comparator.comparingInt(Row::player));
        return rows;
    }

    public static String toJson(List<Row> rows) {
        StringBuilder out = new StringBuilder();
        out.append("{\"schema\":\"").append(SCHEMA)
                .append("\",\"authority_sha256\":\"").append(AUTHORITY_SHA256)
                .append("\",\"rows\":[");
        for (int index = 0; index < rows.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            Row row = rows.get(index);
            out.append("{\"cycle\":").append(row.cycle())
                    .append(",\"player\":").append(row.player())
                    .append(",\"profile\":").append(row.profile())
                    .append(",\"wait\":").append(row.waitCount())
                    .append(",\"pc_offset\":").append(row.pcOffset())
                    .append(",\"list_offset\":").append(row.listOffset())
                    .append(",\"threshold_offset\":").append(row.thresholdOffset())
                    .append(",\"non_pointer_hex\":\"").append(row.nonPointerHex())
                    .append("\",\"predicates\":[");
            for (int event = 0; event < row.predicates().size(); event++) {
                if (event > 0) {
                    out.append(',');
                }
                var predicate = row.predicates().get(event);
                out.append("{\"id\":").append(predicate.id())
                        .append(",\"result\":").append(predicate.result()).append('}');
            }
            out.append("],\"writes\":[");
            for (int event = 0; event < row.writes().size(); event++) {
                if (event > 0) {
                    out.append(',');
                }
                var write = row.writes().get(event);
                out.append("{\"offset\":").append(write.offset())
                        .append(",\"before\":").append(write.before())
                        .append(",\"after\":").append(write.after()).append('}');
            }
            out.append("],\"launches\":[");
            for (int event = 0; event < row.launches().size(); event++) {
                if (event > 0) {
                    out.append(',');
                }
                var launch = row.launches().get(event);
                out.append("{\"domain\":\"").append(launch.domain())
                        .append("\",\"requested\":").append(launch.requested())
                        .append(",\"assigned\":").append(launch.assigned())
                        .append(",\"target\":");
                if (launch.targetX() == null || launch.targetY() == null) {
                    out.append("null");
                } else {
                    out.append('[').append(launch.targetX()).append(',')
                            .append(launch.targetY()).append(']');
                }
                out.append('}');
            }
            out.append(']')
                    .append(",\"classification\":\"")
                    .append(row.classification()).append("\"}");
        }
        out.append("]}");
        return out.toString();
    }

    static String nonPointerHex(byte[] raw) {
        StringBuilder hex = new StringBuilder();
        for (int index = 0; index < raw.length; index++) {
            if (index >= 0x04 && index < 0x08
                    || index >= 0x23 && index < 0x2b) {
                continue;
            }
            hex.append(String.format("%02x", raw[index] & 0xff));
        }
        return hex.toString();
    }

    private static int readU32(byte[] raw, int offset) {
        return (raw[offset] & 0xff)
                | ((raw[offset + 1] & 0xff) << 8)
                | ((raw[offset + 2] & 0xff) << 16)
                | ((raw[offset + 3] & 0xff) << 24);
    }
}
