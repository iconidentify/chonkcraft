package net.chonkbase.chonkcraft.desktop;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import net.chonkbase.assetpack.Json;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.network.SyncHash;
import net.chonkbase.chonkcraft.engine.save.LoadGame;

/** Strict reader and deterministic referee for passive multiplayer recordings. */
final class MultiplayerRecording {

    private static final String MANIFEST = "manifest.json";
    private static final String HASH_PATTERN = "[0-9a-f]{64}";
    private static final String SYNC_PATTERN = "[0-9a-f]{16}";

    record Artifact(Path path, long bytes, String sha256, boolean sealed) {}

    record PlayerSpec(int index, PudMap.PlayerType type, PudMap.Race race) {}

    record RuntimeIdentity(String build, String gameJarSha256, Long gameJarBytes,
            String sourceRevision) {
        boolean sourceBound() {
            return gameJarSha256 != null && sourceRevision != null;
        }
    }

    record Validated(Path directory, int schema, Integer syncHashSchema,
            boolean rosterAuthenticated,
            String build, String mapName, String mapSha256, int localPlayer,
            int cyclesPerSecond, int cyclesPerUpdate, int lag,
            long initialWorldCycle, long initialSyncHash, long recordedNetCycles,
            long recordedCommands, long finalNetCycle, long finalWorldCycle,
            long finalSyncHash, Artifact map, Artifact initialSave, Artifact commandStream,
            List<PlayerSpec> players, RuntimeIdentity runtime,
            Map<GameCommand.Kind, Long> commandFamilies, String manifestSha256) {

        boolean artifactSealed() {
            return map.sealed() && initialSave.sealed() && commandStream.sealed();
        }

        boolean currentSchemaComplete() {
            return schema == 2 && syncHashSchema != null
                    && syncHashSchema == SyncHash.SCHEMA
                    && rosterAuthenticated && artifactSealed();
        }
    }

    record Cycle(long netCycle, long worldCycle, long syncHash,
            List<GameCommand> commands) {}

    record Replay(boolean exact, long replayedNetCycles, long replayedCommands,
            long finalWorldCycle, long finalSyncHash, Long firstMismatchNetCycle,
            Long expectedSyncHash, Long actualSyncHash, String failure) {}

    private MultiplayerRecording() {
    }

    /** Authenticates the container, stream framing, wire bytes and every count. */
    static Validated validate(Path directory) throws IOException {
        Path root = directory.toAbsolutePath().normalize();
        require(Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS),
                "recording is not a real directory: " + root);
        require(!Files.isSymbolicLink(root), "recording directory is a symbolic link");
        require(!Files.exists(root.resolve(".active")),
                "recording is still active and cannot be certified");

        Path manifestPath = member(root, MANIFEST);
        Map<String, Object> manifest = parseObject(manifestPath, "recording manifest");
        int schema = integer(manifest.get("schema"), "manifest schema");
        require(schema == 1 || schema == 2, "unsupported recording schema " + schema);
        Integer syncHashSchema = schema == 2
                ? positiveInteger(manifest.get("sync_hash_schema"), "sync hash schema")
                : null;
        if (syncHashSchema != null) {
            require(syncHashSchema == SyncHash.SCHEMA,
                    "recording sync hash schema " + syncHashSchema
                            + " is not supported by referee schema " + SyncHash.SCHEMA);
        }
        require("complete".equals(string(manifest.get("status"), "recording status")),
                "recording did not finish cleanly");
        require(manifest.get("failure") == null, "complete recording carries a failure");

        String mapName = string(manifest.get("map_name"), "map name");
        String mapHash = hash(manifest.get("map_sha256"), "map SHA-256");
        String build = string(manifest.get("build"), "game build");
        int localPlayer = boundedInteger(manifest.get("local_player"), "local player", 0,
                PudMap.PLAYER_MAX - 1);
        int cyclesPerSecond = positiveInteger(manifest.get("cycles_per_second"),
                "cycles per second");
        int cyclesPerUpdate = positiveInteger(manifest.get("cycles_per_update"),
                "cycles per update");
        int lag = boundedInteger(manifest.get("lag"), "network lag", 0, 1_000_000);
        long initialWorldCycle = nonNegative(manifest.get("initial_world_cycle"),
                "initial world cycle");
        long initialSyncHash = syncHash(manifest.get("initial_sync_hash"),
                "initial sync hash");
        long recordedCycles = nonNegative(manifest.get("recorded_net_cycles"),
                "recorded net cycles");
        long recordedCommands = nonNegative(manifest.get("recorded_commands"),
                "recorded commands");
        long finalNetCycle = number(manifest.get("final_net_cycle"), "final net cycle");
        long finalWorldCycle = nonNegative(manifest.get("final_world_cycle"),
                "final world cycle");
        long finalSyncHash = syncHash(manifest.get("final_sync_hash"), "final sync hash");

        Map<String, Object> artifacts = object(manifest.get("artifacts"), "artifacts");
        Artifact map = artifact(root, artifacts.get("map"), schema, "map");
        Artifact save = artifact(root, artifacts.get("initial_save"), schema, "initial save");
        Artifact stream = artifact(root, artifacts.get("command_stream"), schema,
                "command stream");
        require(map.sha256().equals(mapHash),
                "map artifact does not match the manifest map SHA-256");

        String saved = LoadGame.read(save.path());
        LoadGame.Header header;
        try {
            header = LoadGame.header(saved);
        } catch (RuntimeException malformed) {
            throw new IOException("initial save is not a supported ChonkCraft save", malformed);
        }
        require(header != null && mapName.equals(header.mapPath()),
                "initial save names a different map");
        require(header.cycle() == initialWorldCycle,
                "initial save cycle does not match the manifest");

        List<PlayerSpec> players = schema == 2
                ? players(manifest.get("players")) : List.of();
        RuntimeIdentity runtime = runtime(manifest, schema, build);

        Map<GameCommand.Kind, Long> families = new EnumMap<>(GameCommand.Kind.class);
        long rows = 0;
        long commands = 0;
        long lastNet = -1;
        long lastWorld = initialWorldCycle;
        long lastHash = initialSyncHash;
        try (BufferedReader in = Files.newBufferedReader(stream.path(), StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                require(!line.isEmpty(), "command stream contains a blank row at " + rows);
                Cycle cycle = decode(line, rows, initialWorldCycle, cyclesPerUpdate);
                rows++;
                commands += cycle.commands().size();
                lastNet = cycle.netCycle();
                lastWorld = cycle.worldCycle();
                lastHash = cycle.syncHash();
                for (GameCommand command : cycle.commands()) {
                    families.merge(command.kind(), 1L, Long::sum);
                }
            }
        } catch (Json.SyntaxException | IllegalArgumentException malformed) {
            throw new IOException("command stream is malformed: " + malformed.getMessage(),
                    malformed);
        }
        require(rows == recordedCycles,
                "recorded row count " + recordedCycles + " does not match " + rows);
        require(commands == recordedCommands,
                "recorded command count " + recordedCommands + " does not match " + commands);
        require(lastNet == finalNetCycle, "final net cycle does not match the stream");
        require(lastWorld == finalWorldCycle, "final world cycle does not match the stream");
        require(lastHash == finalSyncHash, "final sync hash does not match the stream");
        if (rows == 0) {
            require(finalNetCycle == -1, "empty recording has a final network cycle");
            require(finalWorldCycle == initialWorldCycle,
                    "empty recording moved its final world cycle");
            require(finalSyncHash == initialSyncHash,
                    "empty recording changed its final sync hash");
        }

        return new Validated(root, schema, syncHashSchema, schema == 2,
                build, mapName, mapHash,
                localPlayer, cyclesPerSecond, cyclesPerUpdate, lag, initialWorldCycle,
                initialSyncHash, rows, commands, finalNetCycle, finalWorldCycle,
                finalSyncHash, map, save, stream, players, runtime,
                Map.copyOf(families), sha256(manifestPath));
    }

    /** Replays the validated stream through the same command applier and world tick. */
    static Replay replay(Validated recording, World world, CommandApplier applier)
            throws IOException {
        long initial = SyncHash.of(world);
        if (world.cycle() != recording.initialWorldCycle()) {
            return failed(0, 0, world, null, null, null,
                    "reconstructed world starts at cycle " + world.cycle()
                            + " instead of " + recording.initialWorldCycle());
        }
        if (initial != recording.initialSyncHash()) {
            return failed(0, 0, world, null, recording.initialSyncHash(), initial,
                    "reconstructed initial world hash differs");
        }

        long rows = 0;
        long commands = 0;
        try (BufferedReader in = Files.newBufferedReader(recording.commandStream().path(),
                StandardCharsets.UTF_8)) {
            String line;
            while ((line = in.readLine()) != null) {
                Cycle cycle = decode(line, rows, recording.initialWorldCycle(),
                        recording.cyclesPerUpdate());
                long beforeBoundary = cycle.worldCycle() - 1;
                while (world.cycle() < beforeBoundary) {
                    world.tick();
                }
                if (world.cycle() != beforeBoundary) {
                    return failed(rows, commands, world, cycle.netCycle(), cycle.syncHash(),
                            SyncHash.of(world), "world passed the recorded command boundary");
                }
                applier.applyAll(cycle.commands());
                world.tick();
                long actual = SyncHash.of(world);
                rows++;
                commands += cycle.commands().size();
                if (world.cycle() != cycle.worldCycle() || actual != cycle.syncHash()) {
                    return failed(rows, commands, world, cycle.netCycle(), cycle.syncHash(),
                            actual, world.cycle() != cycle.worldCycle()
                                    ? "world cycle differs after the recorded batch"
                                    : "sync hash differs after the recorded batch");
                }
            }
        }
        return new Replay(true, rows, commands, world.cycle(), SyncHash.of(world),
                null, null, null, null);
    }

    private static Replay failed(long rows, long commands, World world, Long cycle,
            Long expected, Long actual, String failure) {
        return new Replay(false, rows, commands, world.cycle(), SyncHash.of(world),
                cycle, expected, actual, failure);
    }

    static Cycle decode(String line, long row, long initialWorldCycle,
            int cyclesPerUpdate) {
        Map<String, Object> value = Json.parseObject(line);
        long netCycle = nonNegative(value.get("net_cycle"), "net cycle at row " + row);
        require(netCycle == row, "net cycle " + netCycle + " is not contiguous row " + row);
        long worldCycle = nonNegative(value.get("world_cycle"),
                "world cycle at row " + row);
        long expectedWorld;
        try {
            expectedWorld = Math.addExact(initialWorldCycle,
                    Math.addExact(Math.multiplyExact(netCycle, cyclesPerUpdate), 1));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("world cycle overflows at row " + row, overflow);
        }
        require(worldCycle == expectedWorld,
                "world cycle " + worldCycle + " at row " + row
                        + " should be " + expectedWorld);
        long syncHash = syncHash(value.get("sync_hash"), "sync hash at row " + row);
        List<Object> encoded = array(value.get("commands"), "commands at row " + row);
        List<GameCommand> commands = new ArrayList<>(encoded.size());
        for (int index = 0; index < encoded.size(); index++) {
            commands.add(command(object(encoded.get(index),
                    "command " + index + " at row " + row)));
        }
        return new Cycle(netCycle, worldCycle, syncHash, List.copyOf(commands));
    }

    private static GameCommand command(Map<String, Object> value) {
        String wire = string(value.get("wire_hex"), "command wire");
        require(wire.matches("[0-9a-f]{" + (GameCommand.WIRE_BYTES * 2) + "}"),
                "command wire is not canonical lowercase hex");
        byte[] bytes = HexFormat.of().parseHex(wire);
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        GameCommand decoded = GameCommand.readFrom(buffer);
        require(!buffer.hasRemaining(), "command wire has trailing bytes");
        require(decoded.kind().name().equals(string(value.get("kind"), "command kind")),
                "command kind disagrees with its wire");
        require(decoded.player() == integer(value.get("player"), "command player"),
                "command player disagrees with its wire");
        require(decoded.unitId() == integer(value.get("unit_id"), "command unit"),
                "command unit disagrees with its wire");
        require(decoded.x() == integer(value.get("x"), "command x"),
                "command x disagrees with its wire");
        require(decoded.y() == integer(value.get("y"), "command y"),
                "command y disagrees with its wire");
        require(decoded.targetId() == integer(value.get("target_id"), "command target"),
                "command target disagrees with its wire");
        require(decoded.typeIndex() == integer(value.get("type_index"), "command type"),
                "command type disagrees with its wire");
        require(value.get("queued") instanceof Boolean queued
                        && decoded.queued() == queued.booleanValue(),
                "command queue flag disagrees with its wire");
        require(decoded.player() >= 0 && decoded.player() < PudMap.PLAYER_MAX,
                "command player is outside the Warcraft II roster");
        return decoded;
    }

    private static List<PlayerSpec> players(Object value) {
        List<Object> encoded = array(value, "recorded players");
        require(encoded.size() == PudMap.PLAYER_MAX,
                "recorded player table must contain all " + PudMap.PLAYER_MAX + " slots");
        List<PlayerSpec> result = new ArrayList<>(encoded.size());
        boolean[] seen = new boolean[PudMap.PLAYER_MAX];
        for (Object entry : encoded) {
            Map<String, Object> player = object(entry, "recorded player");
            int index = boundedInteger(player.get("index"), "player index", 0,
                    PudMap.PLAYER_MAX - 1);
            require(!seen[index], "duplicate recorded player " + index);
            seen[index] = true;
            PudMap.PlayerType type;
            PudMap.Race race;
            try {
                type = PudMap.PlayerType.valueOf(string(player.get("type"), "player type"));
                race = PudMap.Race.valueOf(string(player.get("race"), "player race"));
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("unknown recorded player type or race", invalid);
            }
            result.add(new PlayerSpec(index, type, race));
        }
        result.sort(java.util.Comparator.comparingInt(PlayerSpec::index));
        return List.copyOf(result);
    }

    private static RuntimeIdentity runtime(Map<String, Object> manifest, int schema,
            String build) {
        if (schema < 2) {
            return new RuntimeIdentity(build, null, null, null);
        }
        Map<String, Object> value = object(manifest.get("runtime"), "runtime identity");
        require(build.equals(string(value.get("build"), "runtime build")),
                "runtime build disagrees with the manifest build");
        String jarHash = nullableHash(value.get("game_jar_sha256"), "game JAR SHA-256");
        Long jarBytes = value.get("game_jar_bytes") == null ? null
                : nonNegative(value.get("game_jar_bytes"), "game JAR bytes");
        String revision = nullableString(value.get("source_revision"), "source revision");
        if (revision != null) {
            require(revision.matches("[0-9a-f]{40}"), "source revision is not a commit SHA");
            require(jarHash != null && jarBytes != null,
                    "source-bound runtime has no game JAR identity");
        }
        require((jarHash == null) == (jarBytes == null),
                "partial game JAR identity in runtime manifest");
        return new RuntimeIdentity(build, jarHash, jarBytes, revision);
    }

    private static Artifact artifact(Path root, Object value, int schema, String label)
            throws IOException {
        String name;
        Long declaredBytes = null;
        String declaredHash = null;
        if (schema == 1) {
            name = string(value, label + " artifact");
        } else {
            Map<String, Object> entry = object(value, label + " artifact");
            name = string(entry.get("name"), label + " artifact name");
            declaredBytes = nonNegative(entry.get("bytes"), label + " artifact bytes");
            declaredHash = hash(entry.get("sha256"), label + " artifact SHA-256");
        }
        Path path = member(root, name);
        long bytes = Files.size(path);
        String actualHash = sha256(path);
        if (declaredBytes != null) {
            require(declaredBytes.longValue() == bytes,
                    label + " artifact byte count does not match");
            require(declaredHash.equals(actualHash), label + " artifact SHA-256 does not match");
        }
        return new Artifact(path, bytes, actualHash, schema == 2);
    }

    private static Path member(Path root, String name) throws IOException {
        Path relative;
        try {
            relative = Path.of(name);
        } catch (RuntimeException malformed) {
            throw new IOException("invalid recording artifact name " + name, malformed);
        }
        require(!relative.isAbsolute() && relative.getNameCount() == 1
                        && !".".equals(name) && !"..".equals(name),
                "recording artifact is not a direct member: " + name);
        Path resolved = root.resolve(relative).normalize();
        require(root.equals(resolved.getParent()), "recording artifact escapes its bundle");
        require(Files.isRegularFile(resolved, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isSymbolicLink(resolved),
                "recording artifact is missing or unsafe: " + name);
        return resolved;
    }

    private static Map<String, Object> parseObject(Path path, String label) throws IOException {
        try {
            return Json.parseObject(Files.readString(path, StandardCharsets.UTF_8));
        } catch (Json.SyntaxException malformed) {
            throw new IOException(label + " is malformed", malformed);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String label) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(label + " is not an object");
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Object value, String label) {
        if (!(value instanceof List<?>)) {
            throw new IllegalArgumentException(label + " is not an array");
        }
        return (List<Object>) value;
    }

    private static String string(Object value, String label) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException(label + " is not a non-empty string");
        }
        return text;
    }

    private static String nullableString(Object value, String label) {
        if (value == null) {
            return null;
        }
        return string(value, label);
    }

    private static int integer(Object value, String label) {
        try {
            return Math.toIntExact(number(value, label));
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException(label + " is outside the integer range", overflow);
        }
    }

    private static int positiveInteger(Object value, String label) {
        int result = integer(value, label);
        require(result > 0, label + " is not positive");
        return result;
    }

    private static int boundedInteger(Object value, String label, int minimum, int maximum) {
        int result = integer(value, label);
        require(result >= minimum && result <= maximum, label + " is outside its range");
        return result;
    }

    private static long nonNegative(Object value, String label) {
        long result = number(value, label);
        require(result >= 0, label + " is negative");
        return result;
    }

    private static long number(Object value, String label) {
        if (!(value instanceof Long number)) {
            throw new IllegalArgumentException(label + " is not an integer");
        }
        return number.longValue();
    }

    private static String hash(Object value, String label) {
        String result = string(value, label);
        require(result.matches(HASH_PATTERN), label + " is not canonical lowercase SHA-256");
        return result;
    }

    private static String nullableHash(Object value, String label) {
        return value == null ? null : hash(value, label);
    }

    private static long syncHash(Object value, String label) {
        String text = string(value, label);
        require(text.matches(SYNC_PATTERN), label + " is not canonical lowercase sync hash");
        return Long.parseUnsignedLong(text, 16);
    }

    static String syncHex(long value) {
        return String.format("%016x", value);
    }

    static String sha256(Path path) throws IOException {
        MessageDigest digest = digest();
        try (var in = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("this Java runtime has no SHA-256", impossible);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }
}
