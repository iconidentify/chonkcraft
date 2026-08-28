package net.chonkbase.chonkcraft.desktop;

import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.chonkbase.assetpack.Json;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * Drives {@link GameScreen}'s field right-click path and writes a player
 * transaction receipt plus a cycle-level unit log.
 *
 * <p>CommandApplier injection is not a physical transaction. This adapter
 * selects the same first-frame units the sealed native click named, then
 * runs {@code fieldRightClickForTest}, which is the mouse handler's gesture
 * plus {@code commandSelected}.
 */
public final class BnePhysicalAdapter {

    static final String SCENARIO_SCHEMA = "chonkcraft-bne-physical-scenario-1";
    static final String EVIDENCE_SCHEMA = "chonkcraft-bne-physical-evidence-1";
    private static final int BNE_INITIALIZATION_TICKS = 2;

    private BnePhysicalAdapter() {
    }

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        Map<String, Object> scenario = Json.parseObject(Files.readString(
                parsed.scenario, StandardCharsets.UTF_8));
        require(SCENARIO_SCHEMA.equals(scenario.get("schema")),
                "physical scenario has the wrong schema");

        try (AssetSource assets = requireSource(AssetSource.fromEnvironment())) {
            GameData data = new GameData(assets);
            Map<String, Object> setup = object(scenario.get("setup"), "setup");
            String javaMap = javaMap(setup);
            int seed = optionalNumber(setup.get("seed"), 1);
            var source = data.campaignMap(javaMap);
            require(source != null, "campaign " + javaMap + " will not load");
            int person = GameData.personIn(source);
            Mission mission = data.loadMission(javaMap, person, seed);
            require(mission != null, "campaign " + javaMap + " will not load");
            var world = mission.world();
            List<UnitType> roster = new ArrayList<>(data.unitTypes().types().values());
            CommandApplier applier = new CommandApplier(world, roster);
            data.configureCommands(applier);

            BufferedImage terrain = new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB);
            GameScreen screen = new GameScreen(world, data, terrain,
                    data.loadTileset(PudMap.Tileset.FOREST).palette(), "summer",
                    person, 800, 600,
                    new net.chonkbase.chonkcraft.engine.sound.GameAudio(data.sounds()),
                    null, null, applier, CommandSink.local(applier), List.of(),
                    person % 2 == 0 ? "orc" : "human");
            screen.setSize(800, 600);
            screen.setLayout((net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout) null);
            screen.setGameScale(1);

            AtomicInteger fixtureCycle = new AtomicInteger(0);
            screen.setIntentCycleForTest(fixtureCycle::get);

            for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
                mission.tick();
            }

            List<Object> select = array(scenario.get("select"), "select");
            List<Unit> chosen = new ArrayList<>();
            List<Map<String, Object>> identities = new ArrayList<>();
            Map<Integer, Integer> javaToNative = new LinkedHashMap<>();
            for (Object row : select) {
                Map<String, Object> actor = object(row, "select");
                int nativeId = number(actor.get("native_id"), "native id");
                int player = optionalNumber(actor.get("player"), person);
                int x = number(actor.get("x"), "x");
                int y = number(actor.get("y"), "y");
                Unit match = uniqueAt(world, player, x, y);
                require(match != null, "no Java unit at " + x + "," + y
                        + " for native " + nativeId);
                chosen.add(match);
                javaToNative.put(match.id(), nativeId);
                identities.add(identityOf(match, nativeId));
            }
            screen.selectForTest(chosen);

            Map<String, Object> gesture = object(scenario.get("gesture"), "gesture");
            int tileX = number(gesture.get("tile_x"), "tile_x");
            int tileY = number(gesture.get("tile_y"), "tile_y");
            int issueCycle = optionalNumber(scenario.get("issue_cycle"), 5);
            int last = optionalNumber(scenario.get("cycles"), 500);
            Path cycleLog = parsed.cycleLog;
            StringBuilder cycles = new StringBuilder();

            for (int cycle = 1; cycle <= last; cycle++) {
                fixtureCycle.set(cycle);
                if (cycle == issueCycle) {
                    Unit under;
                    if (gesture.containsKey("target_native_id")) {
                        require(gesture.get("target_native_id") == null,
                                "explicit native target identities are not supported");
                        under = null;
                    } else {
                        under = world.unitAt(tileX, tileY);
                    }
                    screen.fieldRightClickForTest(tileX, tileY, under);
                }
                mission.tick();
                screen.observePlayerIntents();
                if (cycleLog != null) {
                    cycles.append(cycleLine(cycle, chosen, javaToNative, world))
                            .append('\n');
                }
            }

            Map<String, Object> evidence = evidence(scenario, parsed, javaMap,
                    identities, screen);
            Files.createDirectories(parsed.output.getParent());
            Files.writeString(parsed.output, Json.write(evidence), StandardCharsets.UTF_8);
            if (cycleLog != null) {
                Files.createDirectories(cycleLog.getParent());
                Files.writeString(cycleLog, cycles.toString(), StandardCharsets.UTF_8);
            }
        }
    }

    private static Map<String, Object> evidence(Map<String, Object> scenario,
            Arguments parsed, String javaMap, List<Map<String, Object>> identities,
            GameScreen screen) {
        Map<String, Object> authority = new LinkedHashMap<>();
        authority.put("side", "java");
        authority.put("producer", "bne-physical-gamescreen");
        authority.put("authenticated", true);
        authority.put("build_sha256", parsed.buildSha256);
        authority.put("engine_input_sha256", parsed.buildSha256);
        if (parsed.programSha256 != null) {
            authority.put("program_input_sha256", parsed.programSha256);
        }
        String scenarioId = parsed.scenarioId != null
                ? parsed.scenarioId : parsed.buildSha256;
        authority.put("fixture_id", scenarioId);
        authority.put("scenario_id", scenarioId);

        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schema", EVIDENCE_SCHEMA);
        evidence.put("authority", authority);
        evidence.put("map_path", javaMap);
        evidence.put("campaign", javaMap.contains("orc") ? "orc" : "human");
        evidence.put("mission", 1);
        evidence.put("player_intents", intents(screen.intentEntriesForTest()));
        evidence.put("player_decisions", decisions(screen.intentDecisionsForTest()));
        evidence.put("player_feedback", feedback(screen.intentFeedbackForTest()));
        evidence.put("player_outcomes", outcomes(screen.intentOutcomesForTest()));
        Map<String, Object> table = new LinkedHashMap<>();
        table.put("schema", "chonkcraft-bne-player-unit-identities-1");
        table.put("units", identities);
        evidence.put("unit_identities", table);
        evidence.put("setup", scenario.get("setup"));
        return evidence;
    }

    private static List<Map<String, Object>> intents(
            List<PlayerIntentJournal.Entry> entries) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PlayerIntentJournal.Entry entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("intent_id", entry.id());
            row.put("transaction_id", entry.transactionId());
            row.put("cycle", entry.cycle());
            row.put("event", entry.event());
            row.put("selected_unit_ids", entry.selectedUnitIds());
            if (entry.gesture() != null) {
                var gesture = entry.gesture();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("origin", gesture.origin());
                body.put("detail", gesture.detail());
                body.put("screen_x", gesture.screenX() < 0 ? null : gesture.screenX());
                body.put("screen_y", gesture.screenY() < 0 ? null : gesture.screenY());
                body.put("tile_x", gesture.tileX());
                body.put("tile_y", gesture.tileY());
                body.put("modifiers", gesture.modifiers());
                body.put("target_id", gesture.targetId());
                body.put("target_shape", gesture.targetShape());
                row.put("gesture", body);
            }
            if (entry.command() != null) {
                var command = entry.command();
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("kind", command.kind().name());
                body.put("player", command.player());
                body.put("unit_id", command.unitId());
                body.put("x", command.x());
                body.put("y", command.y());
                body.put("target_id", command.targetId());
                body.put("type_index", command.typeIndex());
                body.put("queued", command.queued());
                body.put("wire_hex", wireHex(command));
                body.put("fanout_ordinal", entry.fanoutOrdinal());
                row.put("command", body);
            }
            if (entry.accepted() != null) {
                row.put("accepted", entry.accepted());
            }
            result.add(row);
        }
        return result;
    }

    private static List<Map<String, Object>> decisions(
            List<PlayerIntentJournal.Decision> items) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PlayerIntentJournal.Decision item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("transaction_id", item.transactionId());
            row.put("cycle", item.cycle());
            row.put("accepted", item.accepted());
            row.put("family", item.family());
            row.put("queued", item.queued());
            row.put("reason", item.reason());
            result.add(row);
        }
        return result;
    }

    private static List<Map<String, Object>> feedback(
            List<PlayerIntentJournal.Feedback> items) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PlayerIntentJournal.Feedback item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("intent_id", item.intentId());
            row.put("transaction_id", item.transactionId());
            row.put("cycle", item.cycle());
            row.put("acknowledged", item.acknowledged());
            row.put("mode", item.mode());
            row.put("detail", item.detail());
            result.add(row);
        }
        return result;
    }

    private static List<Map<String, Object>> outcomes(
            List<PlayerIntentJournal.Outcome> items) {
        List<Map<String, Object>> result = new ArrayList<>();
        for (PlayerIntentJournal.Outcome item : items) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("intent_id", item.intentId());
            row.put("transaction_id", item.transactionId());
            row.put("submitted_cycle", item.submittedCycle());
            row.put("unit_id", item.unitId());
            row.put("command", item.command());
            row.put("accepted", item.accepted());
            row.put("first_progress_cycle", item.firstProgressCycle());
            row.put("terminal_cycle", item.terminalCycle());
            row.put("terminal_reason", item.terminalReason());
            row.put("tile_x", item.tileX());
            row.put("tile_y", item.tileY());
            row.put("offset_x", item.offsetX());
            row.put("offset_y", item.offsetY());
            row.put("order", item.order());
            row.put("target_id", item.targetId());
            row.put("hit_points", item.hitPoints());
            row.put("carried", item.carried());
            row.put("alive", item.alive());
            row.put("on_map", item.onMap());
            row.put("missile_count", item.missileCount());
            result.add(row);
        }
        return result;
    }

    private static Map<String, Object> identityOf(Unit unit, int nativeId) {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("origin", "initial");
        identity.put("owner", unit.player());
        identity.put("type", unit.type() == null ? "unknown" : unit.type().ident());
        identity.put("x", unit.tileX());
        identity.put("y", unit.tileY());
        identity.put("ordinal", 0);
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("local_id", unit.id());
        row.put("generation", 0);
        row.put("identity", identity);
        row.put("native_id", nativeId);
        return row;
    }

    private static String cycleLine(int cycle, List<Unit> chosen,
            Map<Integer, Integer> javaToNative,
            net.chonkbase.chonkcraft.engine.World world) {
        StringBuilder line = new StringBuilder(128);
        line.append("{\"cycle\":").append(cycle).append(",\"units\":[");
        boolean first = true;
        for (Unit unit : chosen) {
            Unit live = world.unitsSnapshot().stream()
                    .filter(candidate -> candidate.id() == unit.id())
                    .findFirst().orElse(unit);
            if (!first) {
                line.append(',');
            }
            first = false;
            line.append("{\"id\":").append(live.id())
                    .append(",\"native_slot\":")
                    .append(javaToNative.getOrDefault(live.id(), -1))
                    .append(",\"tile_x\":").append(live.tileX())
                    .append(",\"tile_y\":").append(live.tileY())
                    .append(",\"offset_x\":").append(live.offsetX())
                    .append(",\"offset_y\":").append(live.offsetY())
                    .append(",\"pixel_x\":").append(live.pixelX())
                    .append(",\"pixel_y\":").append(live.pixelY())
                    .append(",\"order\":")
                    .append(jsonString(live.order() == null ? null : live.order().name()))
                    .append(",\"hp\":").append(live.hitPoints())
                    .append('}');
        }
        line.append("]}");
        return line.toString();
    }

    private static String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String wireHex(
            net.chonkbase.chonkcraft.engine.network.GameCommand command) {
        java.nio.ByteBuffer bytes = java.nio.ByteBuffer.allocate(
                net.chonkbase.chonkcraft.engine.network.GameCommand.WIRE_BYTES);
        command.writeTo(bytes);
        StringBuilder hex = new StringBuilder(bytes.position() * 2);
        for (int index = 0; index < bytes.position(); index++) {
            hex.append(String.format("%02x", bytes.array()[index] & 0xff));
        }
        return hex.toString();
    }

    private static Unit uniqueAt(net.chonkbase.chonkcraft.engine.World world,
            int player, int x, int y) {
        List<Unit> matches = world.unitsSnapshot().stream()
                .filter(Unit::isAlive)
                .filter(Unit::isOnMap)
                .filter(unit -> unit.player() == player)
                .filter(unit -> unit.tileX() == x && unit.tileY() == y)
                .toList();
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static String javaMap(Map<String, Object> setup) {
        if (setup.get("java_map") instanceof String named) {
            return named;
        }
        String pud = setup.get("scenario") instanceof String text ? text : null;
        require(pud != null, "scenario has no campaign map");
        String normalized = pud.replace('/', '\\');
        java.util.regex.Matcher human = java.util.regex.Pattern
                .compile("Campaign\\\\Human\\\\Human(\\d{2})\\.pud")
                .matcher(normalized);
        if (human.matches()) {
            return "campaigns/human/level" + human.group(1) + "h";
        }
        throw new IllegalStateException("unsupported campaign scenario " + pud);
    }

    private static AssetSource requireSource(AssetSource source) {
        if (source == null) {
            throw new IllegalStateException("no authenticated BNE pack is configured");
        }
        return source;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String label) {
        require(value instanceof Map<?, ?>, label + " is not an object");
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String label) {
        require(value instanceof List<?>, label + " is not an array");
        return (List<Object>) value;
    }

    private static int number(Object value, String label) {
        require(value instanceof Number, label + " is not a number");
        return Math.toIntExact(((Number) value).longValue());
    }

    private static int optionalNumber(Object value, int fallback) {
        return value instanceof Number number
                ? Math.toIntExact(number.longValue()) : fallback;
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static final class Arguments {
        private final Path scenario;
        private final Path output;
        private final Path cycleLog;
        private final String buildSha256;
        private final String programSha256;
        private final String scenarioId;

        private Arguments(Path scenario, Path output, Path cycleLog,
                String buildSha256, String programSha256, String scenarioId) {
            this.scenario = scenario;
            this.output = output;
            this.cycleLog = cycleLog;
            this.buildSha256 = buildSha256;
            this.programSha256 = programSha256;
            this.scenarioId = scenarioId;
        }

        static Arguments parse(String[] args) {
            Path scenario = null;
            Path output = null;
            Path cycleLog = null;
            String build = null;
            String program = null;
            String scenarioId = null;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--scenario" -> scenario = Path.of(args[++index]);
                    case "--output" -> output = Path.of(args[++index]);
                    case "--cycle-log" -> cycleLog = Path.of(args[++index]);
                    case "--build-sha256" -> build = args[++index];
                    case "--program-sha256" -> program = args[++index];
                    case "--scenario-id" -> scenarioId = args[++index];
                    default -> throw new IllegalArgumentException(
                            "unknown argument " + args[index]);
                }
            }
            require(scenario != null && output != null && build != null
                            && build.length() == 64,
                    "usage: BnePhysicalAdapter --scenario FILE --output FILE "
                            + "--build-sha256 SHA256");
            return new Arguments(scenario, output, cycleLog, build, program,
                    scenarioId);
        }
    }
}
