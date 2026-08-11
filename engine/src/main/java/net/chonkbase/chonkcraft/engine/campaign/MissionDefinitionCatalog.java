package net.chonkbase.chonkcraft.engine.campaign;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.ai.AiAssignment;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;

/** Loads the sealed declarations in {@code chonkcraft/missions.tsv}. */
public final class MissionDefinitionCatalog {

    private static final String RESOURCE = "/chonkcraft/missions.tsv";
    private static final Map<String, MissionDefinition> DEFINITIONS = load();

    private MissionDefinitionCatalog() {
    }

    public static MissionDefinition find(String path) {
        return DEFINITIONS.get(path);
    }

    public static List<MissionDefinition> all() {
        return List.copyOf(DEFINITIONS.values());
    }

    private static Map<String, MissionDefinition> load() {
        InputStream stream = MissionDefinitionCatalog.class.getResourceAsStream(RESOURCE);
        if (stream == null) {
            throw new IllegalStateException("missing native mission catalog " + RESOURCE);
        }
        Map<String, MissionDefinition> definitions = new LinkedHashMap<>();
        Builder current = null;
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (!"CHONKCRAFT-MISSIONS\t1".equals(header)) {
                throw new IllegalStateException("unsupported native mission catalog: " + header);
            }
            String line;
            int number = 1;
            while ((line = reader.readLine()) != null) {
                number++;
                if (line.isBlank()) {
                    continue;
                }
                String[] fields = line.split("\t", -1);
                switch (fields[0]) {
                    case "M" -> {
                        require(current == null && fields.length >= 4, number, line);
                        current = new Builder(decode(fields[1]), decode(fields[2]));
                        int voices = Integer.parseInt(fields[3]);
                        require(fields.length == 4 + voices, number, line);
                        for (int i = 0; i < voices; i++) {
                            current.voices.add(decode(fields[4 + i]));
                        }
                    }
                    case "A" -> {
                        require(current != null && fields.length == 3, number, line);
                        current.allow.put(decode(fields[1]), fields[2]);
                    }
                    case "I" -> {
                        require(current != null && fields.length == 4, number, line);
                        int player = Integer.parseInt(fields[1]);
                        current.ai.put(player, new MissionDefinition.AiLabel(
                                emptyToNull(decode(fields[2])),
                                AiAssignment.Origin.valueOf(fields[3])));
                    }
                    case "T" -> {
                        require(current != null && fields.length == 3, number, line);
                        current.triggers.add(new TriggerSystem.ProgramSpec(
                                decode(fields[1]), decode(fields[2])));
                    }
                    case "E" -> {
                        require(current != null && fields.length == 1, number, line);
                        MissionDefinition definition = current.build();
                        require(definitions.put(definition.path(), definition) == null,
                                number, "duplicate " + definition.path());
                        current = null;
                    }
                    default -> throw new IllegalStateException(
                            "unknown native mission row " + number + ": " + line);
                }
            }
        } catch (IOException | RuntimeException error) {
            throw new IllegalStateException("cannot load native mission catalog", error);
        }
        if (current != null || definitions.size() != 52) {
            throw new IllegalStateException("native mission catalog has "
                    + definitions.size() + " complete missions");
        }
        int triggers = definitions.values().stream().mapToInt(d -> d.triggers().size()).sum();
        if (triggers != 137) {
            throw new IllegalStateException("native mission catalog has " + triggers
                    + " triggers, expected 137");
        }
        return Map.copyOf(definitions);
    }

    private static String decode(String encoded) {
        if (encoded.isEmpty()) {
            return "";
        }
        return new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8);
    }

    private static String emptyToNull(String value) {
        return value.isEmpty() ? null : value;
    }

    private static void require(boolean condition, int line, String text) {
        if (!condition) {
            throw new IllegalStateException("invalid native mission row " + line + ": " + text);
        }
    }

    private static final class Builder {
        private final String path;
        private final String background;
        private final List<String> voices = new ArrayList<>();
        private final Map<String, String> allow = new LinkedHashMap<>();
        private final Map<Integer, MissionDefinition.AiLabel> ai = new LinkedHashMap<>();
        private final List<TriggerSystem.ProgramSpec> triggers = new ArrayList<>();

        private Builder(String path, String background) {
            this.path = path;
            this.background = background;
        }

        private MissionDefinition build() {
            return new MissionDefinition(path, emptyToNull(background), voices,
                    allow, ai, triggers);
        }
    }
}
