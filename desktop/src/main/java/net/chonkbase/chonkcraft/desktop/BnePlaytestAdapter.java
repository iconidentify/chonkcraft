package net.chonkbase.chonkcraft.desktop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.chonkbase.assetpack.Json;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * Executes a playtest scenario the way a player order enters the game:
 * through {@link CommandApplier}, observed by {@link PlayerIntentJournal}.
 *
 * <p>Native slot numbers stay on the scenario. They are paired to the unique
 * Java unit standing on the sealed first-frame square so a fixture-backed
 * command names the same worker both engines saw, not the nearest lookalike.
 */
public final class BnePlaytestAdapter {

    static final String RESULT_SCHEMA = "chonkcraft-bne-playtest-result-1";
    private static final int BNE_INITIALIZATION_TICKS = 2;
    private static final int DEFAULT_SETTLE = 600;

    private BnePlaytestAdapter() {
    }

    public static void main(String[] args) throws Exception {
        Arguments parsed = Arguments.parse(args);
        Map<String, Object> scenario = Json.parseObject(Files.readString(
                parsed.scenario, StandardCharsets.UTF_8));
        require("chonkcraft-bne-playtest-scenario-1".equals(scenario.get("schema")),
                "adapter scenario has the wrong schema");
        String scenarioSha = string(scenario.get("scenario_sha256"), "scenario sha256");
        require(scenarioSha.length() == 64, "adapter scenario identity is missing");

        try (AssetSource assets = requireSource(AssetSource.fromEnvironment())) {
            GameData data = new GameData(assets);
            Map<String, Object> setup = object(scenario.get("setup"), "setup");
            String javaMap = javaMap(setup, scenario);
            int seed = optionalNumber(setup.get("seed"), 1);
            var source = data.campaignMap(javaMap);
            require(source != null, "campaign " + javaMap + " will not load");
            Mission mission = data.loadMission(javaMap, GameData.personIn(source), seed);
            require(mission != null, "campaign " + javaMap + " will not load");
            var world = mission.world();
            List<UnitType> roster = new ArrayList<>(data.unitTypes().types().values());
            CommandApplier applier = new CommandApplier(world, roster);
            data.configureCommands(applier);
            PlayerIntentJournal journal = new PlayerIntentJournal();
            AtomicInteger fixtureCycle = new AtomicInteger(0);
            CommandSink sink = journal.wrap(CommandSink.local(applier),
                    fixtureCycle::get, List::of, world);

            for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
                mission.tick();
            }
            Map<Integer, Integer> nativeToJava = pairActors(
                    array(scenario.get("actors"), "actors"), world);
            if (scenario.get("targets") instanceof List<?> listed && !listed.isEmpty()) {
                // Attack, harvest and repair name a native slot that is not
                // an actor. Pairing only the clicker used to send target id 0
                // and the journal then looked like a rejected attack.
                nativeToJava.putAll(pairActors(
                        array(scenario.get("targets"), "targets"), world));
            }

            List<Object> commands = array(scenario.get("commands"), "commands");
            int settle = optionalNumber(scenario.get("settle_cycles"), DEFAULT_SETTLE);
            int last = lastCycle(commands, settle);
            Map<Integer, Long> issuedIntents = new LinkedHashMap<>();
            for (int cycle = 1; cycle <= last; cycle++) {
                fixtureCycle.set(cycle);
                for (int index = 0; index < commands.size(); index++) {
                    Map<String, Object> command = object(commands.get(index), "command");
                    if (number(command.get("issue_cycle"), "issue cycle") != cycle) {
                        continue;
                    }
                    long before = journal.outcomeSnapshot().size();
                    issue(sink, command, nativeToJava, scenario);
                    List<PlayerIntentJournal.Outcome> after = journal.outcomeSnapshot();
                    if (after.size() > before) {
                        issuedIntents.put(index, after.getLast().intentId());
                    } else {
                        issuedIntents.put(index, -1L - index);
                    }
                }
                mission.tick();
                journal.observe(cycle, world);
            }

            Map<Long, PlayerIntentJournal.Outcome> byIntent = new HashMap<>();
            for (PlayerIntentJournal.Outcome outcome : journal.outcomeSnapshot()) {
                byIntent.put(outcome.intentId(), outcome);
            }
            List<Map<String, Object>> observations = new ArrayList<>();
            for (int index = 0; index < commands.size(); index++) {
                Map<String, Object> command = object(commands.get(index), "command");
                Long intent = issuedIntents.get(index);
                PlayerIntentJournal.Outcome outcome = intent == null
                        ? null : byIntent.get(intent);
                observations.add(observation(index, command, outcome, nativeToJava,
                        world, cycleOf(command), last));
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("schema", RESULT_SCHEMA);
            result.put("side", "java");
            result.put("scenario_sha256", scenarioSha);
            Map<String, Object> producer = new LinkedHashMap<>();
            producer.put("name", "bne-playtest-java-command-applier");
            producer.put("build_sha256", parsed.buildSha256);
            producer.put("authority_sha256", null);
            producer.put("map", javaMap);
            result.put("producer", producer);
            result.put("observations", observations);
            result.put("events", List.of());
            Files.createDirectories(parsed.output.getParent());
            Files.writeString(parsed.output, Json.write(result), StandardCharsets.UTF_8);
        }
    }

    private static boolean issue(CommandSink sink, Map<String, Object> command,
            Map<Integer, Integer> nativeToJava, Map<String, Object> scenario) {
        Integer javaId = nativeToJava.get(number(command.get("unit_id"), "unit id"));
        if (javaId == null) {
            return false;
        }
        // The campaign injector only GiveOrders the local player. Issuing as
        // the enemy made a native unit-not-local refusal look like Java
        // accepted a patrol or stop.
        if (actorPlayer(scenario, number(command.get("unit_id"), "unit id")) != 0) {
            return false;
        }
        GameCommand order = toGameCommand(command, javaId, nativeToJava, scenario);
        if (order == null) {
            return false;
        }
        return sink.issueAccepted(order);
    }

    private static GameCommand toGameCommand(Map<String, Object> command, int javaId,
            Map<Integer, Integer> nativeToJava, Map<String, Object> scenario) {
        int player = actorPlayer(scenario, number(command.get("unit_id"), "unit id"));
        boolean queued = Boolean.TRUE.equals(command.get("queued"));
        String kind = string(command.get("kind"), "command kind");
        int x = optionalNumber(command.get("x"), 0);
        int y = optionalNumber(command.get("y"), 0);
        int targetNative = optionalNumber(command.get("target_id"), 0);
        int targetJava = targetNative == 0 ? 0
                : nativeToJava.getOrDefault(targetNative, 0);
        int typeIndex = optionalNumber(command.get("type_index"),
                actorTypeIndex(scenario, number(command.get("unit_id"), "unit id")));
        if (("harvest".equals(kind) || "build".equals(kind) || "cast".equals(kind))
                && !command.containsKey("x")) {
            int[] point = targetPoint(scenario, targetNative);
            x = point[0];
            y = point[1];
        }
        GameCommand built = switch (kind) {
            case "move" -> GameCommand.move(player, javaId, x, y);
            case "attack" -> GameCommand.attack(player, javaId, targetJava);
            case "attack-ground" -> GameCommand.attackGround(player, javaId, x, y);
            case "attack-move" -> GameCommand.attackMove(player, javaId, x, y);
            case "stop" -> GameCommand.stop(player, javaId);
            case "stand-ground" -> GameCommand.standGround(player, javaId);
            case "patrol" -> GameCommand.patrol(player, javaId, x, y);
            case "follow" -> GameCommand.follow(player, javaId, targetJava);
            case "defend" -> GameCommand.defend(player, javaId, targetJava);
            case "harvest" -> GameCommand.harvest(player, javaId, x, y);
            case "return-goods" -> GameCommand.returnGoods(player, javaId);
            case "board" -> GameCommand.board(player, javaId, targetJava);
            case "unload" -> GameCommand.unload(player, javaId, x, y);
            case "repair" -> GameCommand.repair(player, javaId, targetJava);
            case "build" -> GameCommand.build(player, javaId, typeIndex, x, y);
            case "train" -> GameCommand.train(player, javaId, typeIndex);
            case "research" -> GameCommand.research(player, javaId, typeIndex);
            case "cast" -> targetJava == 0
                    ? GameCommand.castAt(player, javaId, x, y, typeIndex)
                    : GameCommand.cast(player, javaId, targetJava, typeIndex);
            default -> null;
        };
        if (built == null || !queued) {
            return built;
        }
        return new GameCommand(built.kind(), built.player(), built.unitId(),
                built.x(), built.y(), built.targetId(), built.typeIndex(), true);
    }

    private static Map<String, Object> observation(int index, Map<String, Object> command,
            PlayerIntentJournal.Outcome outcome, Map<Integer, Integer> nativeToJava,
            net.chonkbase.chonkcraft.engine.World world, int issued, int lastCycle) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("command_index", index);
        if (outcome == null) {
            Integer javaId = nativeToJava.get(number(command.get("unit_id"), "unit id"));
            Unit unit = javaId == null ? null : unit(world, javaId);
            row.put("accepted", false);
            row.put("first_progress_cycle", null);
            row.put("terminal_cycle", issued);
            row.put("terminal_reason", "rejected");
            row.put("state", stateOf(unit, world));
            return row;
        }
        row.put("accepted", Boolean.TRUE.equals(outcome.accepted()));
        row.put("first_progress_cycle", outcome.firstProgressCycle());
        if (outcome.terminalReason() != null) {
            row.put("terminal_cycle", outcome.terminalCycle());
            row.put("terminal_reason", outcome.terminalReason());
        } else {
            row.put("terminal_cycle", lastCycle);
            row.put("terminal_reason", outcome.firstProgressCycle() == null
                    ? "acknowledged-no-progress" : "window-complete");
        }
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("tile_x", outcome.tileX());
        state.put("tile_y", outcome.tileY());
        state.put("order", outcome.order());
        if (outcome.targetId() != null) {
            state.put("target_id", outcome.targetId());
        }
        state.put("hit_points", outcome.hitPoints());
        state.put("carried", outcome.carried());
        state.put("alive", outcome.alive());
        state.put("on_map", outcome.onMap());
        Integer javaId = nativeToJava.get(number(command.get("unit_id"), "unit id"));
        Unit unit = javaId == null ? null : unit(world, javaId);
        // Native snapshots displacement on the terminal cycle. Reading the
        // live unit after extra settle ticks picked up Still-animation bob
        // (Human 1 footman leftover 5, Human 12 gryphon leftover 20) and
        // made five compass rows look like material drift.
        state.put("offset_x", Math.floorMod(outcome.offsetX(), Unit.TILE_PIXELS));
        state.put("offset_y", Math.floorMod(outcome.offsetY(), Unit.TILE_PIXELS));
        if (unit != null) {
            state.put("cargo_count", unit.cargo().size());
        }
        state.put("missile_count", world.missiles().size());
        row.put("state", state);
        return row;
    }

    private static Map<String, Object> stateOf(Unit unit,
            net.chonkbase.chonkcraft.engine.World world) {
        Map<String, Object> state = new LinkedHashMap<>();
        if (unit == null) {
            state.put("tile_x", -1);
            state.put("tile_y", -1);
            state.put("order", null);
            state.put("alive", false);
            state.put("on_map", false);
            state.put("hit_points", 0);
            state.put("carried", 0);
            state.put("missile_count", world.missiles().size());
            state.put("cargo_count", 0);
            return state;
        }
        state.put("tile_x", unit.tileX());
        state.put("tile_y", unit.tileY());
        state.put("offset_x", Math.floorMod(unit.offsetX(), Unit.TILE_PIXELS));
        state.put("offset_y", Math.floorMod(unit.offsetY(), Unit.TILE_PIXELS));
        state.put("order", unit.order() == null ? null : unit.order().name());
        state.put("target_id", unit.target() == null ? null : unit.target().id());
        state.put("hit_points", unit.hitPoints());
        state.put("carried", unit.carried());
        state.put("alive", unit.isAlive());
        state.put("on_map", unit.isOnMap());
        state.put("missile_count", world.missiles().size());
        state.put("cargo_count", unit.cargo().size());
        return state;
    }

    private static Map<Integer, Integer> pairActors(List<Object> actors,
            net.chonkbase.chonkcraft.engine.World world) {
        Map<Integer, Integer> paired = new LinkedHashMap<>();
        for (Object value : actors) {
            Map<String, Object> actor = object(value, "actor");
            int nativeId = number(actor.get("id"), "actor id");
            if (actor.get("x") instanceof Number && actor.get("y") instanceof Number) {
                int x = number(actor.get("x"), "actor x");
                int y = number(actor.get("y"), "actor y");
                int player = optionalNumber(actor.get("player"), 0);
                List<Unit> matches = world.unitsSnapshot().stream()
                        .filter(Unit::isAlive)
                        .filter(Unit::isOnMap)
                        .filter(unit -> unit.player() == player)
                        .filter(unit -> unit.tileX() == x && unit.tileY() == y)
                        .toList();
                require(matches.size() == 1,
                        "native unit " + nativeId + " has " + matches.size()
                                + " Java units at " + x + "," + y);
                paired.put(nativeId, matches.getFirst().id());
                continue;
            }
            Unit existing = unit(world, nativeId);
            require(existing != null, "scenario unit " + nativeId + " is absent");
            paired.put(nativeId, existing.id());
        }
        require(!paired.isEmpty(), "playtest scenario paired no actors");
        return paired;
    }

    private static String javaMap(Map<String, Object> setup, Map<String, Object> scenario) {
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
        java.util.regex.Matcher orc = java.util.regex.Pattern
                .compile("Campaign\\\\Orc\\\\Orc(\\d{2})\\.pud")
                .matcher(normalized);
        if (orc.matches()) {
            return "campaigns/orc/level" + orc.group(1) + "o";
        }
        java.util.regex.Matcher xhuman = java.util.regex.Pattern
                .compile("Campaign\\\\XHuman\\\\2XHum(\\d{2})\\.pud")
                .matcher(normalized);
        if (xhuman.matches()) {
            return "campaigns/human-exp/levelx" + xhuman.group(1) + "h";
        }
        java.util.regex.Matcher xorc = java.util.regex.Pattern
                .compile("Campaign\\\\XOrc\\\\2XOrc(\\d{2})\\.pud")
                .matcher(normalized);
        if (xorc.matches()) {
            return "campaigns/orc-exp/levelx" + xorc.group(1) + "o";
        }
        throw new IllegalStateException("unsupported campaign scenario " + pud);
    }

    private static int lastCycle(List<Object> commands, int settle) {
        int lastIssue = 0;
        for (Object value : commands) {
            Map<String, Object> command = object(value, "command");
            lastIssue = Math.max(lastIssue, number(command.get("issue_cycle"), "issue cycle"));
        }
        return lastIssue + Math.max(1, settle);
    }

    private static int cycleOf(Map<String, Object> command) {
        return number(command.get("issue_cycle"), "issue cycle");
    }

    private static int actorPlayer(Map<String, Object> scenario, int nativeId) {
        for (Object value : array(scenario.get("actors"), "actors")) {
            Map<String, Object> actor = object(value, "actor");
            if (number(actor.get("id"), "actor id") == nativeId) {
                return optionalNumber(actor.get("player"), 0);
            }
        }
        return 0;
    }

    private static int actorTypeIndex(Map<String, Object> scenario, int nativeId) {
        for (Object value : array(scenario.get("actors"), "actors")) {
            Map<String, Object> actor = object(value, "actor");
            if (number(actor.get("id"), "actor id") == nativeId) {
                return optionalNumber(actor.get("type_index"), 0);
            }
        }
        return 0;
    }

    private static int[] targetPoint(Map<String, Object> scenario, int targetId) {
        for (String key : List.of("targets", "actors")) {
            Object value = scenario.get(key);
            if (!(value instanceof List<?>)) {
                continue;
            }
            for (Object entry : array(value, key)) {
                Map<String, Object> item = object(entry, key);
                if (optionalNumber(item.get("id"), -1) == targetId
                        && item.get("x") instanceof Number
                        && item.get("y") instanceof Number) {
                    return new int[] {number(item.get("x"), "x"), number(item.get("y"), "y")};
                }
            }
        }
        return new int[] {0, 0};
    }

    private static Unit unit(net.chonkbase.chonkcraft.engine.World world, int id) {
        return world.unitsSnapshot().stream().filter(candidate -> candidate.id() == id)
                .findFirst().orElse(null);
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

    private static String string(Object value, String label) {
        require(value instanceof String, label + " is not a string");
        return (String) value;
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
        private final String buildSha256;

        private Arguments(Path scenario, Path output, String buildSha256) {
            this.scenario = scenario;
            this.output = output;
            this.buildSha256 = buildSha256;
        }

        static Arguments parse(String[] args) {
            Path scenario = null;
            Path output = null;
            String build = null;
            for (int index = 0; index < args.length; index++) {
                switch (args[index]) {
                    case "--scenario" -> scenario = Path.of(args[++index]);
                    case "--output" -> output = Path.of(args[++index]);
                    case "--build-sha256" -> build = args[++index];
                    default -> throw new IllegalArgumentException(
                            "unknown argument " + args[index]);
                }
            }
            require(scenario != null && output != null && build != null
                            && build.length() == 64,
                    "usage: BnePlaytestAdapter --scenario FILE --output FILE "
                            + "--build-sha256 SHA256");
            return new Arguments(scenario, output, build);
        }
    }
}
