package net.chonkbase.chonkcraft.desktop;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.chonkbase.assetpack.Json;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.map.PudUnitTypes;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/** Executes the proved opening subset of an authenticated BNE replay. */
public final class BneReplaySmokeCertification {

    private static final Set<String> SUPPORTED = Set.of(
            "selection", "move", "attack", "stop", "stand-ground",
            "build-preflight", "build", "player-state", "production");
    /**
     * Retail dispatches one synchronized command turn every 500 ms.
     *
     * <p>The pinned 2.02b game loop compares the dispatcher interval with
     * {@code 0x1f4} at {@code 0x00420e9a..0x00420eb5}; the synchronized
     * network manager is called at {@code 0x00420fbb}. At the engine's
     * 30-Hz simulation rate that interval is exactly fifteen cycles. The
     * replay header's game-speed byte is a lobby pacing-table index and must
     * not be used as a cycle count.
     */
    private static final int BNE_SYNCHRONIZED_TURN_CYCLES = 15;
    private static final int SETTLE_CYCLES = 180;

    private BneReplaySmokeCertification() {
    }

    /**
     * Runs until the first command family not yet transcribed into Java.
     *
     * <p>This is intentionally fail-closed. Native unit slots are bound only
     * when the selected player owns exactly one compatible, unbound Java unit.
     * An ambiguous selection is evidence we need a stronger initial-state
     * bridge, not permission to choose the nearest unit.
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: BneReplaySmokeCertification PLAN.json");
        }
        Map<String, Object> plan = Json.parseObject(Files.readString(
                Path.of(args[0]), StandardCharsets.UTF_8));
        require("chonkcraft-bne-replay-plan-1".equals(plan.get("schema")),
                "unsupported replay plan");
        Map<String, Object> replay = object(plan.get("replay"), "replay");
        Map<String, Object> startup = object(replay.get("startup"), "startup");
        Map<String, Object> mapAsset = object(startup.get("map_asset"), "map asset");
        require("verified".equals(mapAsset.get("status")), "map asset is not verified");

        try (AssetSource assets = requireSource(AssetSource.fromEnvironment())) {
            String mapName = string(startup.get("map_name"), "map name");
            String resolvedMap = resolveMapName(assets, mapName);
            byte[] mapBytes = assets.map(resolvedMap);
            require(mapBytes != null, "asset pack has no replay map " + mapName);
            require(string(mapAsset.get("asset_sha256"), "map sha256")
                            .equals(hex(MessageDigest.getInstance("SHA-256").digest(mapBytes))),
                    "replay map bytes do not match the authenticated plan");

            PudMap source = PudReader.read(mapBytes);
            GameData data = new GameData(assets);
            Map<Integer, Integer> gamePlayers = fixedOrderPlayers(startup);
            Player[] players = replayPlayers(source, startup, gamePlayers);
            applyLobbyResources(players, startup);
            World world = new World(
                    GameMap.from(source, data.loadTileset(source.tileset()).tileset()), players);
            data.configureWorld(world, source);
            int placed = data.populate(world, source);
            world.recalculateSupply();
            world.fireBattleNetReadyForAll();
            List<UnitType> roster = new ArrayList<>(data.unitTypes().types().values());
            CommandApplier applier = new CommandApplier(world, roster);
            data.configureCommands(applier);
            Map<Integer, Integer> wireTypes = new HashMap<>();
            for (int code = 0; code < PudUnitTypes.count(); code++) {
                UnitType type = data.unitTypes().types().get(PudUnitTypes.name(code));
                if (type != null) {
                    wireTypes.put(code, applier.indexOf(type));
                }
            }
            Result result = execute(plan, world, applier, wireTypes, gamePlayers);

            Map<String, Object> report = new LinkedHashMap<>();
            report.put("schema", "chonkcraft-bne-replay-smoke-1");
            report.put("map", mapName);
            report.put("resolved_map", resolvedMap);
            report.put("map_sha256", mapAsset.get("asset_sha256"));
            report.put("placed_units", placed);
            report.put("cycles_per_synchronized_turn",
                    BNE_SYNCHRONIZED_TURN_CYCLES);
            report.put("analyzed_records",
                    array(plan.get("records"), "records").size());
            report.put("processed_records", result.processedRecords);
            report.put("certified_prefix_records", result.processedRecords);
            report.put("processed_commands", result.processedCommands);
            report.put("submitted_orders", result.submittedOrders);
            report.put("accepted_orders", result.acceptedOrders);
            report.put("rejected_orders", result.rejectedOrders);
            report.put("progressed_orders", result.progressedOrders);
            report.put("fulfilled_orders", result.fulfilledOrders);
            report.put("silent_failures", result.silentFailures);
            report.put("unclassified_orders", result.unclassifiedOrders);
            report.put("bound_native_units", result.boundUnits);
            report.put("stopped_at", result.stoppedAt);
            report.put("remaining_schedule", analyzeRemainingSchedule(
                    plan, result.processedRecords));
            report.put("final_cycle", world.cycle());
            report.put("outcomes", result.outcomes);
            System.out.print(Json.write(report));
            require(result.acceptedOrders > 0, "replay opening submitted no supported orders");
            require(result.acceptedOrders + result.rejectedOrders == result.submittedOrders,
                    "a decoded replay order has no acceptance result");
            require(result.unclassifiedOrders == 0,
                    "a replay order has no terminal classification");
        }
    }

    /**
     * Indexes the whole authenticated match even when execution fails closed.
     *
     * <p>The certified prefix is intentionally not extended past an unproved
     * native unit identity.  Previously that honest stop also hid everything
     * later in the match from the report, making the next adapter impossible
     * to prioritize without another one-off script.  This structural pass
     * reads no simulation outcome and claims none: it inventories the exact
     * retained packets after the boundary while the executable prefix remains
     * the only certified outcome evidence.
     */
    private static Map<String, Object> analyzeRemainingSchedule(
            Map<String, Object> plan, int certifiedRecords) {
        List<Object> records = array(plan.get("records"), "records");
        Map<String, Integer> commandFamilies = new java.util.TreeMap<>();
        Set<Integer> selectedNativeUnits = new HashSet<>();
        int commands = 0;
        int unsupported = 0;
        for (int index = Math.max(0, certifiedRecords); index < records.size(); index++) {
            Map<String, Object> record = object(records.get(index), "record");
            for (Object value : array(record.get("commands"), "commands")) {
                Map<String, Object> command = object(value, "command");
                String name = string(command.get("name"), "command name");
                commandFamilies.merge(name, 1, Integer::sum);
                commands++;
                if (!SUPPORTED.contains(name)) {
                    unsupported++;
                }
                selectedNativeUnits.addAll(integers(command.get("selected_unit_ids")));
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", certifiedRecords >= records.size()
                ? "fully-certified" : "structurally-indexed-not-executed");
        result.put("first_record", certifiedRecords < records.size()
                ? certifiedRecords : null);
        result.put("record_count", Math.max(0, records.size() - certifiedRecords));
        result.put("command_count", commands);
        result.put("command_families", commandFamilies);
        result.put("unsupported_command_count", unsupported);
        result.put("selected_native_unit_count", selectedNativeUnits.size());
        result.put("meaning", certifiedRecords >= records.size()
                ? "every authenticated packet was outcome-certified"
                : "packets are indexed for the next adapter; no native or Java "
                        + "outcome is claimed beyond the certified prefix");
        return result;
    }

    /** Applies the exact BNE lobby bank transcribed into the replay plan. */
    private static void applyLobbyResources(Player[] players,
            Map<String, Object> startup) {
        Map<String, Object> bank = object(startup.get("resource_bank"),
                "resource bank");
        String status = string(bank.get("status"), "resource bank status");
        require("0x004338d0".equals(bank.get("native_function")),
                "replay resource bank has an unknown native source");
        if ("map-default".equals(status)) {
            require(number(bank.get("minimum_gold"), "minimum starting gold") == 2100
                            && number(bank.get("minimum_wood"),
                                    "minimum starting wood") == 1100
                            && number(bank.get("minimum_oil"),
                                    "minimum starting oil") == 1000,
                    "replay map-default bank does not match retail network floors");
            return;
        }
        require("verified".equals(status), "replay resource bank is not verified");
        int gold = number(bank.get("gold"), "starting gold");
        int wood = number(bank.get("wood"), "starting wood");
        int oil = number(bank.get("oil"), "starting oil");
        for (Player player : players) {
            if (player.isActive()) {
                player.set(UnitType.Resource.GOLD, gold);
                player.set(UnitType.Resource.WOOD, wood);
                player.set(UnitType.Resource.OIL, oil);
            }
        }
    }

    private static Result execute(Map<String, Object> plan, World world,
            CommandApplier applier, Map<Integer, Integer> wireTypes,
            Map<Integer, Integer> gamePlayers) {
        Map<String, Object> replay = object(plan.get("replay"), "replay");
        Map<String, Object> startup = object(replay.get("startup"), "startup");
        int gameSpeed = number(startup.get("game_speed"), "game speed");
        require(gameSpeed >= 0 && gameSpeed <= 8,
                "replay game speed is outside the retail lobby range");
        Map<Integer, List<Integer>> selections = new HashMap<>();
        Map<Integer, Unit> nativeToJava = new HashMap<>();
        Set<Integer> boundJava = new HashSet<>();
        int initialJavaCeiling = world.unitsSnapshot().stream()
                .mapToInt(Unit::id).max().orElse(0);
        List<OrderOutcome> orders = new ArrayList<>();
        int processedRecords = 0;
        int processedCommands = 0;
        int lastNetworkPlayer = -1;
        Map<String, Object> stoppedAt = null;

        outer:
        for (Object recordValue : array(plan.get("records"), "records")) {
            Map<String, Object> record = object(recordValue, "record");
            int recordIndex = number(record.get("index"), "record index");
            int networkPlayer = number(record.get("network_player"), "network player");
            int player = requiredGamePlayer(gamePlayers, networkPlayer);
            // InSight records one dispatcher call per network participant in
            // descending participant order. Map/player-color slots are fixed
            // separately by opcode 0x0A and are intentionally non-monotonic.
            // A simulation turn begins when the participant sequence wraps,
            // not when the mapped color number happens to rise.
            if (lastNetworkPlayer >= 0 && networkPlayer >= lastNetworkPlayer) {
                for (int cycle = 0; cycle < BNE_SYNCHRONIZED_TURN_CYCLES;
                        cycle++) {
                    world.tick();
                    observe(orders, world);
                }
            }
            lastNetworkPlayer = networkPlayer;
            for (Object commandValue : array(record.get("commands"), "commands")) {
                Map<String, Object> command = object(commandValue, "command");
                String name = string(command.get("name"), "command name");
                if (!SUPPORTED.contains(name)) {
                    stoppedAt = Map.of(
                            "record", recordIndex,
                            "player", player,
                            "name", name,
                            "opcode", number(command.get("opcode"), "opcode"));
                    break outer;
                }
                processedCommands++;
                List<Integer> selected = integers(command.get("selected_unit_ids"));
                if ("selection".equals(name)) {
                    selections.put(player, selected);
                    continue;
                }
                if ("player-state".equals(name)) {
                    applyPlayerState(world, player,
                            bytes(string(command.get("raw"), "command bytes")));
                    continue;
                }
                if ("build-preflight".equals(name)) {
                    // The following 0x09 packet carries the authoritative
                    // building type and site. Java has no separate UI latch.
                    continue;
                }
                byte[] raw = bytes(string(command.get("raw"), "command bytes"));
                if ("production".equals(name) && (raw[2] & 0xff) != 0
                        && (raw[2] & 0xff) != 2
                        && (raw[2] & 0xff) != 3) {
                    stoppedAt = Map.of(
                            "record", recordIndex,
                            "player", player,
                            "name", "production-family-" + (raw[2] & 0xff),
                            "opcode", number(command.get("opcode"), "opcode"),
                            "code", raw[1] & 0xff);
                    break outer;
                }
                if ("attack".equals(name)) {
                    require(raw.length == 8,
                            "retail attack packet is not eight bytes");
                    int target = u16(raw, 5);
                    if (target != 0xffff && !nativeToJava.containsKey(target)) {
                        stoppedAt = Map.of(
                                "record", recordIndex,
                                "player", player,
                                "name", "attack-target-identity-unresolved",
                                "opcode", number(command.get("opcode"), "opcode"),
                                "native_target", target);
                        break outer;
                    }
                }
                List<Integer> active = selected.isEmpty()
                        ? selections.getOrDefault(player, List.of()) : selected;
                for (int nativeId : active) {
                    Unit unit;
                    try {
                        unit = bind(nativeId, player, name, raw, world,
                                applier, wireTypes, nativeToJava, boundJava,
                                initialJavaCeiling);
                    } catch (UnresolvedUnitIdentity unresolved) {
                        Map<String, Object> stop = new LinkedHashMap<>();
                        stop.put("record", recordIndex);
                        stop.put("player", player);
                        stop.put("name", "unit-identity-unresolved");
                        stop.put("opcode", number(command.get("opcode"), "opcode"));
                        stop.put("native_unit", nativeId);
                        stop.put("reason", unresolved.getMessage());
                        if ("production".equals(name)) {
                            stop.put("production_state", productionState(player, raw,
                                    world, applier, wireTypes, boundJava));
                        }
                        stoppedAt = stop;
                        break outer;
                    }
                    int beforeX = unit.tileX();
                    int beforeY = unit.tileY();
                    Unit.Order beforeOrder = unit.order();
                    String beforeProducing = ident(unit.producing());
                    int beforeTrainingJobs = unit.trainingJobCount();
                    String beforeResearching = unit.researching();
                    String beforeUpgrading = ident(unit.upgradingTo());
                    boolean accepted = switch (name) {
                        case "move" -> applier.apply(GameCommand.move(player, unit.id(),
                                u16(raw, 1), u16(raw, 3)));
                        case "attack" -> applyAttack(applier, player, unit, raw,
                                nativeToJava);
                        case "stop" -> applier.apply(GameCommand.stop(player, unit.id()));
                        case "stand-ground" -> applier.apply(
                                GameCommand.standGround(player, unit.id()));
                        case "build" -> applier.apply(GameCommand.build(player, unit.id(),
                                requiredWireType(wireTypes, raw[1] & 0xff),
                                u16(raw, 2), u16(raw, 4)));
                        case "production" -> applyProduction(applier, player, unit,
                                raw, wireTypes);
                        default -> false;
                    };
                    for (OrderOutcome earlier : orders) {
                        if (earlier.accepted && earlier.javaUnit == unit.id()
                                && earlier.terminalReason == null
                                && earlier.objectiveCycle == null) {
                            earlier.terminalCycle = world.cycle();
                            earlier.terminalReason = "superseded";
                        }
                    }
                    String requestedType = requestedType(name, raw, applier, wireTypes);
                    Integer requestedX = orderX(name, raw);
                    Integer requestedY = orderY(name, raw);
                    orders.add(new OrderOutcome(recordIndex, player, nativeId, unit.id(),
                            name, world.cycle(), beforeX, beforeY, beforeOrder,
                            unit.isOnMap(), unit.isAlive(), unit.type().ident(),
                            beforeProducing, beforeTrainingJobs, beforeResearching,
                            beforeUpgrading, requestedType,
                            requestedX, requestedY,
                            destinationState(world, requestedX, requestedY), accepted));
                }
            }
            processedRecords++;
        }
        for (int cycle = 0; cycle < SETTLE_CYCLES; cycle++) {
            world.tick();
            observe(orders, world);
        }
        for (OrderOutcome order : orders) {
            order.finish(world);
        }
        List<Map<String, Object>> outcomes = orders.stream().map(OrderOutcome::report).toList();
        int accepted = (int) orders.stream().filter(order -> order.accepted).count();
        int rejected = orders.size() - accepted;
        int progressed = (int) orders.stream()
                .filter(order -> order.accepted && order.progressCycle != null).count();
        int fulfilled = (int) orders.stream()
                .filter(order -> order.accepted && order.objectiveCycle != null).count();
        int silentFailures = (int) orders.stream()
                .filter(OrderOutcome::silentFailure).count();
        int unclassified = (int) orders.stream()
                .filter(order -> order.terminalReason == null).count();
        return new Result(processedRecords, processedCommands, orders.size(), accepted,
                rejected, progressed, fulfilled, silentFailures, unclassified,
                nativeToJava.size(), stoppedAt, outcomes);
    }

    /** Applies the per-player diplomacy table carried by retail opcode 0x0A. */
    private static void applyPlayerState(World world, int player, byte[] raw) {
        require(raw.length == 6 && (raw[5] & 0xff) == player,
                "player-state packet does not match its fixed-order slot");
        int standings = u16(raw, 1);
        String[] names = {"allied", "neutral", "enemy", "crazy"};
        for (int opponent = 0; opponent < Player.MAX; opponent++) {
            world.setDiplomacy(player, names[(standings >>> (opponent * 2)) & 3],
                    opponent);
        }
        // Byte three and byte four are retained in the authenticated plan.
        // Native stores them in separate player/global bitfields after the
        // diplomacy table. Their gameplay meaning is not yet proved, so this
        // adapter does not silently call them shared vision or allied victory.
    }

    private static void observe(List<OrderOutcome> orders, World world) {
        for (OrderOutcome order : orders) {
            if (!order.accepted || order.terminalReason != null) {
                continue;
            }
            Unit unit = unit(world, order.javaUnit);
            order.observe(world, unit);
        }
    }

    private static String requestedType(String name, byte[] raw,
            CommandApplier applier, Map<Integer, Integer> wireTypes) {
        if ("build".equals(name)) {
            return applier.typeAt(requiredWireType(wireTypes, raw[1] & 0xff)).ident();
        }
        if (!"production".equals(name)) {
            return null;
        }
        int code = raw[1] & 0xff;
        return switch (raw[2] & 0xff) {
            case 0, 3 -> applier.typeAt(requiredWireType(wireTypes, code)).ident();
            case 2 -> retailUpgrade(code);
            default -> null;
        };
    }

    private static Integer orderX(String name, byte[] raw) {
        return switch (name) {
            case "move", "attack" -> u16(raw, 1);
            case "build" -> u16(raw, 2);
            default -> null;
        };
    }

    private static Integer orderY(String name, byte[] raw) {
        return switch (name) {
            case "move", "attack" -> u16(raw, 3);
            case "build" -> u16(raw, 4);
            default -> null;
        };
    }

    /** Retains the clicked terrain and occupancy at packet-delivery time. */
    private static Map<String, Object> destinationState(World world,
            Integer x, Integer y) {
        if (x == null || y == null) {
            return Map.of();
        }
        var field = world.map().fieldOrNull(x, y);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("x", x);
        result.put("y", y);
        result.put("on_map", field != null);
        if (field != null) {
            result.put("visual_tile", field.tile());
            result.put("flags", "0x" + Long.toHexString(field.flags()));
        }
        result.put("occupants", world.unitsSnapshot().stream()
                .filter(Unit::isAlive)
                .filter(Unit::isOnMap)
                .filter(unit -> x >= unit.tileX()
                        && x < unit.tileX() + unit.type().tileWidth()
                        && y >= unit.tileY()
                        && y < unit.tileY() + unit.type().tileHeight())
                .map(unit -> Map.of(
                        "id", (Object) unit.id(),
                        "player", unit.player(),
                        "type", unit.type().ident(),
                        "tile_x", unit.tileX(),
                        "tile_y", unit.tileY(),
                        "tile_width", unit.type().tileWidth(),
                        "tile_height", unit.type().tileHeight()))
                .toList());
        return result;
    }

    private static Unit bind(int nativeId, int player, String command, byte[] raw,
            World world, CommandApplier applier, Map<Integer, Integer> wireTypes,
            Map<Integer, Unit> nativeToJava, Set<Integer> boundJava,
            int initialJavaCeiling) {
        Unit existing = nativeToJava.get(nativeId);
        if (existing != null) {
            return existing;
        }
        List<Unit> candidates = world.playerUnits(player).stream()
                .filter(Unit::isAlive)
                .filter(Unit::isOnMap)
                .filter(unit -> !boundJava.contains(unit.id()))
                .filter(unit -> !"move".equals(command) || unit.canMove())
                .filter(unit -> !"attack".equals(command)
                        || attackCandidate(unit, raw))
                .filter(unit -> productionCandidate(
                        unit, command, raw, world, applier, wireTypes))
                .toList();
        Unit chosen = null;
        if (candidates.size() == 1) {
            chosen = candidates.getFirst();
        } else if (candidates.stream()
                .filter(unit -> unit.id() <= initialJavaCeiling).count() == 1) {
            // An initial map unit keeps its native pool slot for the match.
            // When a later construction has produced a second building of
            // the same kind, the sole pre-game candidate is the only one
            // that can answer an initial slot's first command.
            chosen = candidates.stream()
                    .filter(unit -> unit.id() <= initialJavaCeiling)
                    .findFirst().orElseThrow();
        } else if (!candidates.isEmpty()
                && candidates.stream().allMatch(unit -> unit.id() > initialJavaCeiling)
                && (candidates.stream().map(unit -> unit.type().ident())
                        .distinct().count() == 1
                    || continuesUnrecycledBirthOrder(nativeId, player,
                            nativeToJava, initialJavaCeiling))) {
            // BNE takes new objects from the high end of its fixed unit pool;
            // Java assigns increasing IDs. Before any early-match slot reuse,
            // the oldest unbound product is therefore the smallest Java ID.
            // One exact type preserves the already-proved product ordering.
            // Across mixed types, require the new native slot to continue
            // descending below every already-bound post-startup slot for this
            // player. A recycled native slot breaks that invariant and
            // therefore fails closed.
            chosen = candidates.stream().min(java.util.Comparator.comparingInt(Unit::id))
                    .orElseThrow();
        }
        if (chosen == null) {
            throw new UnresolvedUnitIdentity(
                    "native unit " + nativeId + " for player " + player
                            + " has " + candidates.size() + " compatible Java units "
                            + candidates.stream().map(unit -> unit.id() + ":"
                                    + unit.type().ident() + "@" + unit.tileX() + ","
                                    + unit.tileY()).toList());
        }
        nativeToJava.put(nativeId, chosen);
        boundJava.add(chosen.id());
        return chosen;
    }

    private static boolean continuesUnrecycledBirthOrder(int nativeId, int player,
            Map<Integer, Unit> nativeToJava, int initialJavaCeiling) {
        return nativeToJava.entrySet().stream()
                .filter(entry -> entry.getValue().player() == player)
                .filter(entry -> entry.getValue().id() > initialJavaCeiling)
                .allMatch(entry -> nativeId < entry.getKey());
    }

    /** Applies BNE opcode 0x13 without guessing an unmapped target identity. */
    private static boolean applyAttack(CommandApplier applier, int player,
            Unit attacker, byte[] raw, Map<Integer, Unit> nativeToJava) {
        require(raw.length == 8, "retail attack packet is not eight bytes");
        int nativeTarget = u16(raw, 5);
        if (nativeTarget == 0xffff) {
            return applier.apply(GameCommand.attackMove(player, attacker.id(),
                    u16(raw, 1), u16(raw, 3)));
        }
        Unit target = nativeToJava.get(nativeTarget);
        require(target != null, "retail attack target is not bound");
        return applier.apply(GameCommand.attack(player, attacker.id(), target.id()));
    }

    private static boolean attackCandidate(Unit unit, byte[] raw) {
        require(raw.length == 8, "retail attack packet is not eight bytes");
        return unit.type().canAttack()
                && (u16(raw, 5) != 0xffff || unit.canMove());
    }

    /** Applies the production families whose BNE identities are proved. */
    private static boolean applyProduction(CommandApplier applier, int player,
            Unit building, byte[] raw, Map<Integer, Integer> wireTypes) {
        require(raw.length == 3, "retail production packet is not three bytes");
        int code = raw[1] & 0xff;
        return switch (raw[2] & 0xff) {
            case 0 -> applier.apply(GameCommand.train(player, building.id(),
                    requiredWireType(wireTypes, code)));
            case 2 -> {
                require(code <= 0x17,
                        "retail family-two code is outside its proved table");
                yield applier.apply(GameCommand.research(player, building.id(),
                        requiredUpgrade(applier, retailUpgrade(code))));
            }
            case 3 -> applier.apply(GameCommand.upgradeTo(player, building.id(),
                    requiredWireType(wireTypes, code)));
            // Family one indexes the retail spell table. It remains
            // fail-closed until that table is transcribed.
            default -> throw new UnsupportedProductionFamily(raw[2] & 0xff, code);
        };
    }

    private static boolean productionCandidate(Unit unit, String command, byte[] raw,
            World world, CommandApplier applier, Map<Integer, Integer> wireTypes) {
        if (!"production".equals(command)) {
            return true;
        }
        require(raw.length == 3, "retail production packet is not three bytes");
        int code = raw[1] & 0xff;
        int family = raw[2] & 0xff;
        return switch (family) {
            case 0 -> {
                UnitType product = applier.typeAt(requiredWireType(wireTypes, code));
                yield unit.order() == Unit.Order.STILL
                    && unit.researching() == null
                    && unit.upgradingTo() == null
                    && world.mayTrain(unit.type(), product);
            }
            case 2 -> code <= 0x17
                    && unit.order() == Unit.Order.STILL
                    && unit.producing() == null
                    && unit.upgradingTo() == null
                    && world.mayResearch(unit.type(), retailUpgrade(code));
            case 3 -> unit.order() == Unit.Order.STILL
                    && unit.producing() == null
                    && unit.upgradingTo() == null
                    && upgradeSource(code).equals(unit.type().ident());
            default -> false;
        };
    }

    /** Retains the complete local production boundary instead of just zero candidates. */
    private static List<Map<String, Object>> productionState(int player, byte[] raw,
            World world, CommandApplier applier, Map<Integer, Integer> wireTypes,
            Set<Integer> boundJava) {
        int family = raw[2] & 0xff;
        String product = family == 2 ? retailUpgrade(raw[1] & 0xff) : null;
        UnitType productType = family == 2 ? null
                : applier.typeAt(requiredWireType(wireTypes, raw[1] & 0xff));
        List<Map<String, Object>> state = new ArrayList<>();
        for (Unit unit : world.playerUnits(player)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("java_unit", unit.id());
            entry.put("type", unit.type().ident());
            entry.put("order", unit.order().name());
            entry.put("tile_x", unit.tileX());
            entry.put("tile_y", unit.tileY());
            entry.put("on_map", unit.isOnMap());
            entry.put("alive", unit.isAlive());
            entry.put("bound", boundJava.contains(unit.id()));
            entry.put("may_produce", family == 2
                    ? world.mayResearch(unit.type(), product)
                    : world.mayTrain(unit.type(), productType));
            state.add(entry);
        }
        return state;
    }

    /** Native unit codes accepted by the family-three transformation table. */
    private static String upgradeSource(int productCode) {
        return switch (productCode) {
            case 0x58 -> "unit-town-hall";
            case 0x59 -> "unit-great-hall";
            case 0x5a -> "unit-keep";
            case 0x5b -> "unit-stronghold";
            case 0x60, 0x62 -> "unit-human-watch-tower";
            case 0x61, 0x63 -> "unit-orc-watch-tower";
            default -> "";
        };
    }

    /** Retail UGRD table indexes 0x00..0x21, in their on-disk order. */
    private static String retailUpgrade(int code) {
        return switch (code) {
            case 0x00 -> "upgrade-sword1";
            case 0x01 -> "upgrade-sword2";
            case 0x02 -> "upgrade-battle-axe1";
            case 0x03 -> "upgrade-battle-axe2";
            case 0x04 -> "upgrade-arrow1";
            case 0x05 -> "upgrade-arrow2";
            case 0x06 -> "upgrade-throwing-axe1";
            case 0x07 -> "upgrade-throwing-axe2";
            case 0x08 -> "upgrade-human-shield1";
            case 0x09 -> "upgrade-human-shield2";
            case 0x0a -> "upgrade-orc-shield1";
            case 0x0b -> "upgrade-orc-shield2";
            case 0x0c -> "upgrade-human-ship-cannon1";
            case 0x0d -> "upgrade-human-ship-cannon2";
            case 0x0e -> "upgrade-orc-ship-cannon1";
            case 0x0f -> "upgrade-orc-ship-cannon2";
            case 0x10 -> "upgrade-human-ship-armor1";
            case 0x11 -> "upgrade-human-ship-armor2";
            case 0x12 -> "upgrade-orc-ship-armor1";
            case 0x13 -> "upgrade-orc-ship-armor2";
            case 0x14 -> "upgrade-catapult1";
            case 0x15 -> "upgrade-catapult2";
            case 0x16 -> "upgrade-ballista1";
            case 0x17 -> "upgrade-ballista2";
            case 0x18 -> "upgrade-ranger";
            case 0x19 -> "upgrade-longbow";
            case 0x1a -> "upgrade-ranger-scouting";
            case 0x1b -> "upgrade-ranger-marksmanship";
            case 0x1c -> "upgrade-berserker";
            case 0x1d -> "upgrade-light-axes";
            case 0x1e -> "upgrade-berserker-scouting";
            case 0x1f -> "upgrade-berserker-regeneration";
            case 0x20 -> "upgrade-paladin";
            case 0x21 -> "upgrade-ogre-mage";
            default -> throw new IllegalStateException(
                    "retail upgrade code 0x" + Integer.toHexString(code)
                            + " is outside the transcribed UGRD table");
        };
    }

    private static int requiredUpgrade(CommandApplier applier, String ident) {
        int index = applier.indexOfUpgrade(ident);
        require(index >= 0, "replay upgrade " + ident
                + " is absent from the command roster");
        return index;
    }

    private static String ident(UnitType type) {
        return type == null ? null : type.ident();
    }

    private static Player[] replayPlayers(PudMap source, Map<String, Object> startup,
            Map<Integer, Integer> gamePlayers) {
        PudMap.PlayerType[] types = new PudMap.PlayerType[Player.MAX];
        PudMap.Race[] races = source.races().clone();
        java.util.Arrays.fill(types, PudMap.PlayerType.NOBODY);
        for (int player = 0; player < Player.MAX; player++) {
            if (source.players()[player] == PudMap.PlayerType.NEUTRAL) {
                types[player] = PudMap.PlayerType.NEUTRAL;
            }
        }
        for (Object slotValue : array(startup.get("slots"), "startup slots")) {
            Map<String, Object> slot = object(slotValue, "startup slot");
            int networkIndex = number(slot.get("slot"), "slot index");
            String occupant = string(slot.get("occupant"), "slot occupant");
            if ("closed".equals(occupant)) {
                continue;
            }
            int index = requiredGamePlayer(gamePlayers, networkIndex);
            types[index] = switch (occupant) {
                case "human" -> PudMap.PlayerType.PERSON;
                case "computer" -> PudMap.PlayerType.COMPUTER;
                default -> PudMap.PlayerType.NOBODY;
            };
            int race = number(slot.get("race"), "slot race");
            races[index] = race == 0 ? PudMap.Race.HUMAN
                    : race == 1 ? PudMap.Race.ORC : PudMap.Race.NEUTRAL;
        }
        return Player.forNetworkGame(source, types, races);
    }

    private static Map<Integer, Integer> fixedOrderPlayers(Map<String, Object> startup) {
        Map<String, Object> fixed = object(startup.get("fixed_order_slots"),
                "fixed-order slots");
        Map<Integer, Integer> result = new HashMap<>();
        Set<Integer> gameSlots = new HashSet<>();
        for (Object entryValue : array(fixed.get("entries"), "fixed-order entries")) {
            Map<String, Object> entry = object(entryValue, "fixed-order entry");
            int network = number(entry.get("network_player"), "network player");
            int game = number(entry.get("game_player"), "game player");
            require(result.put(network, game) == null,
                    "duplicate fixed-order network player " + network);
            require(gameSlots.add(game), "duplicate fixed-order game player " + game);
        }
        return result;
    }

    private static int requiredGamePlayer(Map<Integer, Integer> gamePlayers, int network) {
        Integer player = gamePlayers.get(network);
        require(player != null,
                "replay has no proved game slot for network player " + network);
        return player;
    }

    private static String resolveMapName(AssetSource assets, String replayName) {
        String wanted = Path.of(replayName.replace('\\', '/')).getFileName().toString();
        List<String> matches = assets.mapNames().stream()
                .filter(candidate -> Path.of(candidate.replace('\\', '/')).getFileName()
                        .toString().equalsIgnoreCase(wanted))
                .toList();
        require(matches.size() == 1,
                "replay map " + replayName + " resolves to " + matches.size()
                        + " ChonkPack maps");
        return matches.getFirst();
    }

    private static Unit unit(World world, int id) {
        return world.unitsSnapshot().stream().filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }

    private static int u16(byte[] value, int at) {
        require(at + 1 < value.length, "truncated replay command");
        return (value[at] & 0xff) | (value[at + 1] & 0xff) << 8;
    }

    private static int requiredWireType(Map<Integer, Integer> wireTypes, int pudCode) {
        Integer index = wireTypes.get(pudCode);
        require(index != null && index >= 0,
                "replay building type 0x" + Integer.toHexString(pudCode)
                        + " is absent from the command roster");
        return index;
    }

    private static byte[] bytes(String value) {
        require((value.length() & 1) == 0, "odd hexadecimal command length");
        byte[] result = new byte[value.length() / 2];
        for (int index = 0; index < result.length; index++) {
            result[index] = (byte) Integer.parseInt(value.substring(index * 2, index * 2 + 2), 16);
        }
        return result;
    }

    private static String hex(byte[] value) {
        return java.util.HexFormat.of().formatHex(value);
    }

    private static AssetSource requireSource(AssetSource source) {
        if (source == null) {
            throw new IllegalStateException("no ChonkPack configured");
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

    private static List<Integer> integers(Object value) {
        return array(value, "integer list").stream()
                .map(entry -> number(entry, "integer list entry")).toList();
    }

    private static String string(Object value, String label) {
        require(value instanceof String, label + " is not a string");
        return (String) value;
    }

    private static int number(Object value, String label) {
        require(value instanceof Number, label + " is not a number");
        return Math.toIntExact(((Number) value).longValue());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private record Result(int processedRecords, int processedCommands,
            int submittedOrders, int acceptedOrders, int rejectedOrders,
            int progressedOrders, int fulfilledOrders, int silentFailures,
            int unclassifiedOrders, int boundUnits,
            Map<String, Object> stoppedAt, List<Map<String, Object>> outcomes) {
    }

    private static final class UnsupportedProductionFamily extends RuntimeException {
        private UnsupportedProductionFamily(int family, int code) {
            super("retail production family " + family + " code 0x"
                    + Integer.toHexString(code) + " is not transcribed");
        }
    }

    /** A replay selection that cannot yet be paired without guessing. */
    private static final class UnresolvedUnitIdentity extends RuntimeException {
        private UnresolvedUnitIdentity(String message) {
            super(message);
        }
    }

    private static final class OrderOutcome {
        private final int record;
        private final int player;
        private final int nativeUnit;
        private final int javaUnit;
        private final String command;
        private final long submittedCycle;
        private final int beforeX;
        private final int beforeY;
        private final Unit.Order beforeOrder;
        private final boolean beforeOnMap;
        private final boolean beforeAlive;
        private final String beforeType;
        private final String beforeProducing;
        private final int beforeTrainingJobs;
        private final String beforeResearching;
        private final String beforeUpgrading;
        private final String requestedType;
        private final Integer orderX;
        private final Integer orderY;
        private final Map<String, Object> destination;
        private final boolean accepted;
        private Long progressCycle;
        private Long objectiveCycle;
        private Long terminalCycle;
        private String terminalReason;

        private OrderOutcome(int record, int player, int nativeUnit, int javaUnit,
                String command, long submittedCycle, int beforeX, int beforeY,
                Unit.Order beforeOrder, boolean beforeOnMap, boolean beforeAlive,
                String beforeType, String beforeProducing, int beforeTrainingJobs,
                String beforeResearching, String beforeUpgrading,
                String requestedType, Integer orderX, Integer orderY,
                Map<String, Object> destination,
                boolean accepted) {
            this.record = record;
            this.player = player;
            this.nativeUnit = nativeUnit;
            this.javaUnit = javaUnit;
            this.command = command;
            this.submittedCycle = submittedCycle;
            this.beforeX = beforeX;
            this.beforeY = beforeY;
            this.beforeOrder = beforeOrder;
            this.beforeOnMap = beforeOnMap;
            this.beforeAlive = beforeAlive;
            this.beforeType = beforeType;
            this.beforeProducing = beforeProducing;
            this.beforeTrainingJobs = beforeTrainingJobs;
            this.beforeResearching = beforeResearching;
            this.beforeUpgrading = beforeUpgrading;
            this.requestedType = requestedType;
            this.orderX = orderX;
            this.orderY = orderY;
            this.destination = destination;
            this.accepted = accepted;
            if (!accepted) {
                terminalCycle = submittedCycle;
                terminalReason = "rejected";
            }
        }

        private void observe(World world, Unit unit) {
            long cycle = world.cycle();
            boolean moved = unit != null && (unit.tileX() != beforeX
                    || unit.tileY() != beforeY
                    || unit.offsetX() != 0 || unit.offsetY() != 0);
            boolean productionChanged = unit != null
                    && (!java.util.Objects.equals(ident(unit.producing()), beforeProducing)
                        || unit.trainingJobCount() != beforeTrainingJobs
                        || !java.util.Objects.equals(unit.researching(), beforeResearching)
                        || !java.util.Objects.equals(
                                ident(unit.upgradingTo()), beforeUpgrading));
            if (progressCycle == null && (moved || productionChanged)) {
                progressCycle = cycle;
            }

            boolean fulfilled = switch (command) {
                case "move" -> moved || settledAtBlockedDestination(unit);
                case "attack" -> moved;
                case "stop" -> unit != null && unit.order() == Unit.Order.STILL;
                case "stand-ground" -> unit != null
                        && unit.order() == Unit.Order.STAND_GROUND;
                case "build" -> world.playerUnits(player).stream().anyMatch(candidate ->
                        candidate.isAlive() && requestedType.equals(candidate.type().ident())
                                && candidate.tileX() == orderX
                                && candidate.tileY() == orderY);
                case "production" -> productionChanged;
                default -> false;
            };
            if (fulfilled && objectiveCycle == null) {
                if (progressCycle == null) {
                    progressCycle = cycle;
                }
                objectiveCycle = cycle;
                terminalCycle = cycle;
                terminalReason = "fulfilled";
            }
        }

        private void finish(World world) {
            if (terminalReason != null || !accepted) {
                return;
            }
            observe(world, unit(world, javaUnit));
            if (terminalReason == null) {
                terminalCycle = world.cycle();
                terminalReason = progressCycle == null
                        ? "acknowledged-no-progress" : "incomplete-after-settle";
            }
        }

        private boolean silentFailure() {
            return accepted && "acknowledged-no-progress".equals(terminalReason);
        }

        @SuppressWarnings("unchecked")
        private boolean settledAtBlockedDestination(Unit unit) {
            if (unit == null || unit.order() != Unit.Order.STILL
                    || destination.isEmpty()
                    || !Boolean.TRUE.equals(destination.get("on_map"))) {
                return false;
            }
            int minX = orderX;
            int minY = orderY;
            int maxX = orderX;
            int maxY = orderY;
            List<Map<String, Object>> occupants = (List<Map<String, Object>>)
                    destination.getOrDefault("occupants", List.of());
            boolean blocked = !occupants.isEmpty();
            if (blocked) {
                Map<String, Object> occupant = occupants.getFirst();
                minX = ((Number) occupant.get("tile_x")).intValue();
                minY = ((Number) occupant.get("tile_y")).intValue();
                maxX = minX + ((Number) occupant.get("tile_width")).intValue() - 1;
                maxY = minY + ((Number) occupant.get("tile_height")).intValue() - 1;
            } else {
                String encoded = String.valueOf(destination.get("flags"));
                long flags = encoded.startsWith("0x")
                        ? Long.parseUnsignedLong(encoded.substring(2), 16) : 0;
                blocked = (flags & (net.chonkbase.chonkcraft.engine.map.TileFlag.UNPASSABLE
                        | net.chonkbase.chonkcraft.engine.map.TileFlag.WALL
                        | net.chonkbase.chonkcraft.engine.map.TileFlag.ROCKS
                        | net.chonkbase.chonkcraft.engine.map.TileFlag.FOREST
                        | net.chonkbase.chonkcraft.engine.map.TileFlag.BUILDING)) != 0;
            }
            if (!blocked) {
                return false;
            }
            int unitMaxX = unit.tileX() + Math.max(1, unit.type().tileWidth()) - 1;
            int unitMaxY = unit.tileY() + Math.max(1, unit.type().tileHeight()) - 1;
            return Math.max(rectangleGap(unit.tileX(), unitMaxX, minX, maxX),
                    rectangleGap(unit.tileY(), unitMaxY, minY, maxY)) <= 1;
        }

        private static int rectangleGap(int aMin, int aMax, int bMin, int bMax) {
            if (aMax < bMin) {
                return bMin - aMax;
            }
            if (bMax < aMin) {
                return aMin - bMax;
            }
            return 0;
        }

        private Map<String, Object> report() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("record", record);
            value.put("player", player);
            value.put("native_unit", nativeUnit);
            value.put("java_unit", javaUnit);
            value.put("command", command);
            value.put("submitted_cycle", submittedCycle);
            value.put("before_x", beforeX);
            value.put("before_y", beforeY);
            value.put("before_order", beforeOrder.name());
            value.put("before_on_map", beforeOnMap);
            value.put("before_alive", beforeAlive);
            value.put("before_type", beforeType);
            value.put("before_producing", beforeProducing);
            value.put("before_training_jobs", beforeTrainingJobs);
            value.put("before_researching", beforeResearching);
            value.put("before_upgrading", beforeUpgrading);
            value.put("requested_type", requestedType);
            value.put("order_x", orderX);
            value.put("order_y", orderY);
            value.put("destination", destination);
            value.put("accepted", accepted);
            value.put("progress_cycle", progressCycle);
            value.put("objective_cycle", objectiveCycle);
            value.put("terminal_cycle", terminalCycle);
            value.put("terminal_reason", terminalReason);
            return value;
        }
    }
}
