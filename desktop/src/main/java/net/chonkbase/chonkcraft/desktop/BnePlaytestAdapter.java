package net.chonkbase.chonkcraft.desktop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import net.chonkbase.assetpack.Json;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.data.map.PudUnitTypes;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.missile.Missile;

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
            // Retail 0x15 byte 1 is the PUD type. GameCommand.train still
            // carries a roster index, which is why a generated type 0 must
            // become the footman and not roster[0] (the alchemist).
            Map<Integer, Integer> pudToRoster = new HashMap<>();
            for (int code = 0; code < PudUnitTypes.count(); code++) {
                UnitType type = data.unitTypes().types().get(PudUnitTypes.name(code));
                if (type != null) {
                    pudToRoster.put(code, applier.indexOf(type));
                }
            }
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
            if (scenario.get("pair_units") instanceof List<?> pairing
                    && !pairing.isEmpty()) {
                nativeToJava.putAll(pairRoster(
                        array(scenario.get("pair_units"), "pair_units"),
                        world, nativeToJava));
            }
            List<Integer> lifecycleUnits = lifecycleUnits(scenario, nativeToJava);
            List<Map<String, Object>> lifecycleEvents = new ArrayList<>();
            Map<Integer, Integer> javaToNative = new HashMap<>();
            nativeToJava.forEach((nativeId, javaId) ->
                    javaToNative.put(javaId, nativeId));
            Map<Missile, Integer> projectileIds = new IdentityHashMap<>();
            Map<Missile, Map<String, Object>> previousProjectiles =
                    new IdentityHashMap<>();
            int nextProjectileId = 0;

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
                    issue(sink, command, nativeToJava, scenario, pudToRoster,
                            world);
                    List<PlayerIntentJournal.Outcome> after = journal.outcomeSnapshot();
                    if (after.size() > before) {
                        issuedIntents.put(index, after.getLast().intentId());
                    } else {
                        issuedIntents.put(index, -1L - index);
                    }
                }
                mission.tick();
                journal.observe(cycle, world);
                for (int nativeId : lifecycleUnits) {
                    lifecycleEvents.add(lifecycleState(cycle, nativeId,
                            unit(world, nativeToJava.get(nativeId)), world));
                }
                if (!lifecycleUnits.isEmpty()) {
                    for (Missile missile : world.missiles().stream()
                            .filter(world::battleNetProjectileConstructed).toList()) {
                        if (!projectileIds.containsKey(missile)) {
                            projectileIds.put(missile, nextProjectileId++);
                        }
                    }
                    Map<Missile, Map<String, Object>> current =
                            new IdentityHashMap<>();
                    for (Missile missile : world.missiles().stream()
                            .filter(world::battleNetProjectileConstructed).toList()) {
                        Map<String, Object> event = projectileState(cycle,
                                projectileIds.get(missile), missile,
                                javaToNative, true);
                        lifecycleEvents.add(event);
                        current.put(missile, event);
                    }
                    for (Map.Entry<Missile, Map<String, Object>> entry
                            : previousProjectiles.entrySet()) {
                        if (current.containsKey(entry.getKey())) {
                            continue;
                        }
                        Map<String, Object> event = new LinkedHashMap<>(entry.getValue());
                        event.put("cycle", cycle);
                        event.put("present", false);
                        lifecycleEvents.add(event);
                    }
                    previousProjectiles = current;
                }
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
            result.put("events", lifecycleEvents);
            Files.createDirectories(parsed.output.getParent());
            Files.writeString(parsed.output, Json.write(result), StandardCharsets.UTF_8);
        }
    }

    private static boolean issue(CommandSink sink, Map<String, Object> command,
            Map<Integer, Integer> nativeToJava, Map<String, Object> scenario,
            Map<Integer, Integer> pudToRoster,
            net.chonkbase.chonkcraft.engine.World world) {
        Integer javaId = nativeToJava.get(number(command.get("unit_id"), "unit id"));
        if (javaId == null) {
            return false;
        }
        // This adapter twins the native fixture injector, not the campaign UI.
        // The injector guards GiveOrder with BNE_202_LOCAL_PLAYER, which stays
        // slot 0 even when BNE_202_UI_PLAYER (the visible campaign person) is
        // another slot. Using the actor's owner made an enemy Orc 1 archer's
        // patrol/stop command pass on Java after retail rejected it; using the
        // campaign person made retail-accepted slot-0 commands fail on Human
        // maps. Slot 0 is therefore part of this evidence protocol.
        int nativeLocalPlayer = 0;
        Unit actor = unit(world, javaId);
        if (actor == null || !world.canControl(nativeLocalPlayer, actor.player())) {
            return false;
        }
        GameCommand order = toGameCommand(command, javaId, nativeToJava, scenario,
                pudToRoster, nativeLocalPlayer);
        if (order == null) {
            return false;
        }
        return sink.issueAccepted(order);
    }

    private static GameCommand toGameCommand(Map<String, Object> command, int javaId,
            Map<Integer, Integer> nativeToJava, Map<String, Object> scenario,
            Map<Integer, Integer> pudToRoster, int player) {
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
            case "train" -> GameCommand.train(player, javaId,
                    pudToRoster.getOrDefault(typeIndex, -1));
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
        // Native snapshots the projectile pool on the terminal cycle.
        // Reading the live list after extra settle ticks dropped the
        // landed rock and left one later axe, so batch-1/24 counted 1
        // while the Still visit still had the two live shots native
        // holds at 40.
        state.put("missile_count", outcome.missileCount());
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
            state.put("missile_count", constructedMissileCount(world));
            state.put("cargo_count", 0);
            return state;
        }
        state.put("tile_x", unit.tileX());
        state.put("tile_y", unit.tileY());
        state.put("offset_x", Math.floorMod(unit.offsetX(), Unit.TILE_PIXELS));
        state.put("offset_y", Math.floorMod(unit.offsetY(), Unit.TILE_PIXELS));
        state.put("order", unit.order() == null ? null : unit.order().name());
        if (unit.target() != null) {
            state.put("target_id", unit.target().id());
        }
        state.put("hit_points", unit.hitPoints());
        state.put("carried", unit.carried());
        state.put("alive", unit.isAlive());
        state.put("on_map", unit.isOnMap());
        state.put("missile_count", constructedMissileCount(world));
        state.put("cargo_count", unit.cargo().size());
        return state;
    }

    /**
     * Native AUXL only counts shots that have crossed the projectile
     * constructor. Presentation placeholders used to sit in the Java list
     * first, so Human 13's two catapults made cycle 4 {@code missile_count}
     * 2 while retail still reported 0 -- which is why the ranged causal
     * prefix failed before the commanded axe existed.
     */
    private static int constructedMissileCount(
            net.chonkbase.chonkcraft.engine.World world) {
        int count = 0;
        for (Missile missile : world.missiles()) {
            if (world.battleNetProjectileConstructed(missile)) {
                count++;
            }
        }
        return count;
    }

    private static List<Integer> lifecycleUnits(Map<String, Object> scenario,
            Map<Integer, Integer> nativeToJava) {
        Object requested = scenario.get("combat_observation");
        if (!(requested instanceof Map<?, ?> raw)) {
            return List.of();
        }
        Object ids = raw.get("unit_ids");
        require(ids instanceof List<?>, "combat observation unit_ids is not an array");
        List<Integer> result = new ArrayList<>();
        for (Object value : (List<?>) ids) {
            int nativeId = number(value, "combat observation unit id");
            require(nativeToJava.containsKey(nativeId),
                    "combat observation unit " + nativeId + " is not paired");
            require(!result.contains(nativeId),
                    "combat observation repeats native unit " + nativeId);
            result.add(nativeId);
        }
        require(!result.isEmpty(), "combat observation has no units");
        return List.copyOf(result);
    }

    private static Map<String, Object> lifecycleState(int cycle, int nativeId,
            Unit unit, net.chonkbase.chonkcraft.engine.World world) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("cycle", cycle);
        event.put("kind", "combat-state");
        event.put("unit_id", nativeId);
        event.put("present", unit != null);
        event.put("alive", unit != null && unit.isAlive());
        event.put("on_map", unit != null && unit.isOnMap());
        event.put("x", unit == null ? -1 : unit.tileX());
        event.put("y", unit == null ? -1 : unit.tileY());
        event.put("offset_x", unit == null ? 0
                : unit.tileX() * Unit.TILE_PIXELS + unit.offsetX());
        event.put("offset_y", unit == null ? 0
                : unit.tileY() * Unit.TILE_PIXELS + unit.offsetY());
        event.put("order", unit == null || unit.order() == null
                ? null : unit.order().name());
        event.put("hit_points", unit == null ? 0 : unit.hitPoints());
        event.put("sequence", unit == null ? -1 : unit.battleNetSequenceOffset());
        event.put("animation_timer", unit == null ? 0
                : unit.battleNetAnimationTimer());
        event.put("animation_state", animationState(unit));
        // The native state stream stores a process pointer here. A stable slot
        // mapping needs a separately authenticated pointer-table observation;
        // do not pretend a Java id and a retail pointer are comparable.
        event.put("target_id", null);
        event.put("missile_count", constructedMissileCount(world));
        return event;
    }

    private static String animationState(Unit unit) {
        if (unit == null || unit.type() == null
                || unit.type().animationSet() == null) {
            return null;
        }
        AnimationSet animations = unit.type().animationSet();
        for (AnimationSet.State state : AnimationSet.State.values()) {
            if (animations.get(state) == unit.animation().current()) {
                return state.name();
            }
        }
        return null;
    }

    private static Map<String, Object> projectileState(int cycle, int id,
            Missile missile, Map<Integer, Integer> javaToNative,
            boolean present) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("cycle", cycle);
        event.put("kind", "combat-projectile");
        event.put("projectile_id", id);
        event.put("present", present);
        event.put("source_id", nativeUnitId(missile.source(), javaToNative));
        event.put("target_id", nativeUnitId(missile.target(), javaToNative));
        event.put("type", missile.type() == null ? null : missile.type().ident());
        event.put("type_code", projectileTypeCode(missile));
        event.put("x", (int) Math.round(missile.x()));
        event.put("y", (int) Math.round(missile.y()));
        event.put("frame", missile.frame());
        event.put("remaining", missile.battleNetRemaining());
        event.put("pool_slot", missile.battleNetPoolSlot());
        return event;
    }

    private static Integer nativeUnitId(Unit unit,
            Map<Integer, Integer> javaToNative) {
        return unit == null ? null : javaToNative.get(unit.id());
    }

    private static int projectileTypeCode(Missile missile) {
        if (missile.type() == null || missile.type().ident() == null) {
            return -1;
        }
        return switch (missile.type().ident()) {
            case "missile-catapult-rock" -> 13;
            case "missile-ballista-bolt" -> 14;
            case "missile-arrow", "missile-arrow-super" -> 15;
            case "missile-axe" -> 16;
            case "missile-impact" -> 21;
            case "missile-small-cannon", "missile-small-cannon-super" -> 24;
            default -> -1;
        };
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

    /**
     * Best-effort native-slot map for every first-frame unit. Combat
     * projectile events speak native slot numbers; an ambient catapult
     * that is not an actor used to become {@code source_id=null} and
     * broke the Human 13 causal prefix while the shot itself matched.
     */
    private static Map<Integer, Integer> pairRoster(List<Object> roster,
            net.chonkbase.chonkcraft.engine.World world,
            Map<Integer, Integer> already) {
        Map<Integer, Integer> paired = new LinkedHashMap<>();
        for (Object value : roster) {
            Map<String, Object> item = object(value, "pair unit");
            int nativeId = number(item.get("id"), "pair unit id");
            if (already.containsKey(nativeId) || paired.containsKey(nativeId)) {
                continue;
            }
            if (!(item.get("x") instanceof Number) || !(item.get("y") instanceof Number)) {
                continue;
            }
            int x = number(item.get("x"), "pair unit x");
            int y = number(item.get("y"), "pair unit y");
            int player = optionalNumber(item.get("player"), 0);
            List<Unit> matches = world.unitsSnapshot().stream()
                    .filter(Unit::isAlive)
                    .filter(Unit::isOnMap)
                    .filter(unit -> unit.player() == player)
                    .filter(unit -> unit.tileX() == x && unit.tileY() == y)
                    .toList();
            if (matches.size() != 1) {
                continue;
            }
            paired.put(nativeId, matches.getFirst().id());
        }
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
