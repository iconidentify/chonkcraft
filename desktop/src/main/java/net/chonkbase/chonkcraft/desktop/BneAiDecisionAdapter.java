package net.chonkbase.chonkcraft.desktop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.ai.AiDecisionLedger;
import net.chonkbase.chonkcraft.engine.campaign.Mission;

/**
 * Emits the Java half of the per-cycle {@code ai.bin} decision ledger.
 *
 * <p>This is deliberately a production adapter rather than a test helper.  The
 * parity lab can therefore execute the same current-head classes a released
 * game uses, retain the JSON as evidence, and compare it with a pinned native
 * trace.  Fixture cycles begin after the same two initialization ticks used by
 * the command adapter.
 */
public final class BneAiDecisionAdapter {

    private static final int INITIALIZATION_TICKS = 2;

    private BneAiDecisionAdapter() {
    }

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        try (AssetSource assets = require(AssetSource.fromEnvironment(),
                "Set CHONKCRAFT_ASSET_PACK to an authenticated BNE ChonkPack")) {
            require(assets.isBattleNetEdition(),
                    "AI decision evidence requires Battle.net Edition media");
            GameData data = new GameData(assets);
            var source = require(data.campaignMap(parsed.map),
                    "campaign map will not load: " + parsed.map);
            int person = GameData.personIn(source);
            Mission mission = require(data.loadMission(
                    parsed.map, person, parsed.seed),
                    "mission will not load: " + parsed.map);
            for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
                mission.tick();
            }
            List<AiDecisionLedger.Row> rows = new ArrayList<>();
            for (int fixtureCycle = 1; fixtureCycle <= parsed.cycles; fixtureCycle++) {
                mission.tick();
                rows.addAll(AiDecisionLedger.snapshot(
                        mission.world(), fixtureCycle));
            }
            require(!rows.isEmpty(),
                    "mission has no active computer-player AI state");
            Path parent = parsed.output.toAbsolutePath().getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            List<Integer> computers = mission.world().ais().keySet().stream()
                    .sorted(Comparator.naturalOrder()).toList();
            Files.writeString(parsed.output,
                    evidenceJson(rows, parsed, person, computers) + "\n",
                    StandardCharsets.UTF_8);
            System.out.println("AI decision ledger: " + rows.size()
                    + " rows -> " + parsed.output);
        }
    }

    private static <T> T require(T value, String message) {
        if (value == null) {
            throw new IllegalStateException(message);
        }
        return value;
    }

    static String evidenceJson(List<AiDecisionLedger.Row> rows, Arguments parsed,
            int person, List<Integer> computers) {
        String ledger = AiDecisionLedger.toJson(rows);
        StringBuilder out = new StringBuilder(ledger.length() + 160);
        out.append(ledger, 0, ledger.length() - 1)
                .append(",\"map\":\"").append(json(parsed.map)).append('"')
                .append(",\"seed\":").append(parsed.seed)
                .append(",\"cycles\":").append(parsed.cycles)
                .append(",\"person_player\":").append(person)
                .append(",\"computer_players\":[");
        for (int index = 0; index < computers.size(); index++) {
            if (index > 0) {
                out.append(',');
            }
            out.append(computers.get(index));
        }
        return out.append("]}").toString();
    }

    private static String json(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    record Arguments(String map, int seed, int cycles,
            Path output) {

        private static Arguments parse(String[] args) {
            String map = "campaigns/orc/level01o";
            int seed = 1;
            int cycles = 12;
            Path output = null;
            for (int index = 0; index < args.length; index++) {
                String flag = args[index];
                if (index + 1 >= args.length) {
                    throw new IllegalArgumentException("missing value for " + flag);
                }
                String value = args[++index];
                switch (flag) {
                    case "--map" -> map = value;
                    case "--seed" -> seed = Integer.parseInt(value);
                    case "--cycles" -> cycles = Integer.parseInt(value);
                    case "--output" -> output = Path.of(value);
                    default -> throw new IllegalArgumentException(
                            "unknown argument: " + flag);
                }
            }
            if (cycles < 1) {
                throw new IllegalArgumentException("--cycles must be positive");
            }
            if (output == null) {
                throw new IllegalArgumentException("--output is required");
            }
            return new Arguments(map, seed, cycles, output);
        }
    }
}
