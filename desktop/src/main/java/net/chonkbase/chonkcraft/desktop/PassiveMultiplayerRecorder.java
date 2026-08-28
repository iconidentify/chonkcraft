package net.chonkbase.chonkcraft.desktop;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.network.NetworkGame;
import net.chonkbase.chonkcraft.engine.network.SyncHash;
import net.chonkbase.chonkcraft.engine.save.SaveGame;

/**
 * Keeps a bounded local flight record of every multiplayer game.
 *
 * <p>The bundle contains the lobby's exact map bytes, a cycle-zero native
 * save, and the ordered command batches and post-cycle sync hashes that every
 * peer accepted. It deliberately records no chat, screenshots, account data,
 * or presentation state. Nothing is uploaded and there is no playback UI;
 * the files exist so a troublesome match can be harvested after play rather
 * than reproduced from memory.
 *
 * <p>Cycle records are appended and flushed by a daemon writer. Disk latency
 * therefore cannot stall lockstep, while a crash still leaves a complete
 * newline-framed prefix. Twenty-four bundles or 512 MiB are retained, oldest
 * first, and a file lock prevents two local clients from pruning each other's
 * live games.
 */
final class PassiveMultiplayerRecorder implements NetworkGame.CycleSink, AutoCloseable {

    private static final int MAX_RECORDINGS = 24;
    private static final long MAX_BYTES = 512L * 1024L * 1024L;
    private static final int QUEUE_CAPACITY = 4_096;
    private static final int CHECKPOINT_INTERVAL = 180;
    private static final String ACTIVE_FILE = ".active";
    private static final String MANIFEST_FILE = "manifest.json";
    private static final String STREAM_FILE = "commands.jsonl";
    private static final String MAP_FILE = "map.pud";
    private static final String SAVE_FILE = "initial" + SaveGame.SUFFIX;
    private static final DateTimeFormatter STAMP = DateTimeFormatter
            .ofPattern("uuuuMMdd-HHmmss-SSS").withZone(ZoneOffset.UTC);

    private sealed interface Event permits Cycle, Stop {}

    private record Cycle(long netCycle, long worldCycle, List<GameCommand> commands,
            long syncHash) implements Event {}

    private record Stop(boolean complete, String failure, long netCycle, long worldCycle,
            long syncHash, Instant finishedAt) implements Event {}

    private record Stored(Path path, long modified, long bytes) {}

    /** The controller and race table the lobby actually installed. */
    private record RecordedPlayer(int index, String type, String race) {}

    /** The exact installed game artifact, when this is an OTA-launched build. */
    record RuntimeIdentity(String gameJarSha256, Long gameJarBytes,
            String sourceRevision) {

        static RuntimeIdentity current(String build) {
            try {
                var source = PassiveMultiplayerRecorder.class.getProtectionDomain()
                        .getCodeSource();
                if (source == null || source.getLocation() == null) {
                    return new RuntimeIdentity(null, null, null);
                }
                Path path = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
                return from(path, build);
            } catch (Exception unavailable) {
                // Recording must never make an otherwise playable match fail to start.
                return new RuntimeIdentity(null, null, null);
            }
        }

        static RuntimeIdentity from(Path path, String build) {
            try {
                path = path.toAbsolutePath().normalize();
                if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                    // A development classes directory has no single artifact identity.
                    return new RuntimeIdentity(null, null, null);
                }
                String jarHash = sha256(path);
                long jarBytes = Files.size(path);
                String revision = null;
                Path metadata = path.resolveSibling("release.properties");
                if (Files.isRegularFile(metadata) && !Files.isSymbolicLink(metadata)) {
                    Properties values = new Properties();
                    try (var in = Files.newInputStream(metadata)) {
                        values.load(in);
                    }
                    // Trust the revision only when the launcher's verified installed
                    // metadata names this exact byte artifact and advertised build.
                    if (jarHash.equalsIgnoreCase(values.getProperty("game.sha256", ""))
                            && build.equals(values.getProperty("version", ""))) {
                        String candidate = values.getProperty("source.revision", "").trim();
                        if (candidate.matches("[0-9a-fA-F]{40}")) {
                            revision = candidate.toLowerCase(java.util.Locale.ROOT);
                        }
                    }
                }
                return new RuntimeIdentity(jarHash, jarBytes, revision);
            } catch (Exception unavailable) {
                return new RuntimeIdentity(null, null, null);
            }
        }
    }

    private final Path root;
    private final Path directory;
    private final String mapName;
    private final String mapHash;
    private final long mapBytes;
    private final String build;
    private final RuntimeIdentity runtimeIdentity;
    private final long initialSaveBytes;
    private final String initialSaveHash;
    private final List<RecordedPlayer> players;
    private final int localPlayer;
    private final int cyclesPerUpdate;
    private final int lag;
    private final Instant createdAt;
    private final long initialWorldCycle;
    private final long initialSyncHash;
    private final long initialSyncSeed;
    private final long initialSyncDraws;
    private final long initialAsyncSeed;
    private final long initialAsyncDraws;
    private final ArrayBlockingQueue<Event> pending = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
    private final AtomicBoolean accepting = new AtomicBoolean(true);
    private final CountDownLatch stopped = new CountDownLatch(1);
    private final FileChannel activityChannel;
    private final FileLock activityLock;
    private final Thread writerThread;
    private final Thread shutdownHook;

    private volatile long lastNetCycle = -1;
    private volatile long lastWorldCycle;
    private volatile long lastSyncHash;

    private PassiveMultiplayerRecorder(Path root, Path directory, String mapName,
            String mapHash, long mapBytes, String build, int localPlayer,
            int cyclesPerUpdate, int lag, Instant createdAt, World world,
            long initialSaveBytes, String initialSaveHash, FileChannel activityChannel,
            FileLock activityLock) {
        this.root = root;
        this.directory = directory;
        this.mapName = mapName;
        this.mapHash = mapHash;
        this.mapBytes = mapBytes;
        this.build = build;
        runtimeIdentity = RuntimeIdentity.current(build);
        this.initialSaveBytes = initialSaveBytes;
        this.initialSaveHash = initialSaveHash;
        List<RecordedPlayer> roster = new ArrayList<>(world.players().length);
        for (var player : world.players()) {
            roster.add(new RecordedPlayer(player.index(), player.type().name(),
                    player.race().name()));
        }
        players = List.copyOf(roster);
        this.localPlayer = localPlayer;
        this.cyclesPerUpdate = cyclesPerUpdate;
        this.lag = lag;
        this.createdAt = createdAt;
        initialWorldCycle = world.cycle();
        initialSyncHash = SyncHash.of(world);
        initialSyncSeed = Integer.toUnsignedLong(world.randomSeed());
        initialSyncDraws = world.randomDraws();
        initialAsyncSeed = Integer.toUnsignedLong(world.battleNetRandomSeed());
        initialAsyncDraws = world.battleNetRandomDraws();
        lastWorldCycle = initialWorldCycle;
        lastSyncHash = initialSyncHash;
        this.activityChannel = activityChannel;
        this.activityLock = activityLock;
        writerThread = new Thread(this::writeLoop,
                "chonkcraft-multiplayer-recorder-" + directory.getFileName());
        writerThread.setDaemon(true);
        shutdownHook = new Thread(this::close,
                "chonkcraft-multiplayer-recorder-shutdown");
    }

    /** Starts a recorder in the player's ordinary per-user recordings directory. */
    static PassiveMultiplayerRecorder open(World world, String mapName, byte[] mapBytes,
            int localPlayer, int cyclesPerUpdate, int lag, String build) throws IOException {
        return open(world, mapName, mapBytes, localPlayer, cyclesPerUpdate, lag, build,
                defaultDirectory(), Instant.now());
    }

    /** The same operation with a caller-owned directory and clock for tests. */
    static PassiveMultiplayerRecorder open(World world, String mapName, byte[] mapBytes,
            int localPlayer, int cyclesPerUpdate, int lag, String build, Path root,
            Instant createdAt) throws IOException {
        if (world == null || mapBytes == null || mapBytes.length == 0) {
            throw new IllegalArgumentException("a recording needs a world and its map bytes");
        }
        Files.createDirectories(root);
        prune(root, MAX_RECORDINGS - 1, MAX_BYTES);
        String safeMap = safeName(mapName);
        Path directory = uniqueDirectory(root,
                "game-" + STAMP.format(createdAt) + "-" + safeMap);
        Path active = directory.resolve(ACTIVE_FILE);
        FileChannel channel = FileChannel.open(active, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE);
        FileLock lock = null;
        try {
            lock = channel.lock();
            Files.write(directory.resolve(MAP_FILE), mapBytes,
                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            Path initialSave = directory.resolve(SAVE_FILE);
            SaveGame.write(world, mapName, null, 0, initialSave);
            PassiveMultiplayerRecorder recorder = new PassiveMultiplayerRecorder(
                    root, directory, mapName, sha256(mapBytes), mapBytes.length, build,
                    localPlayer, cyclesPerUpdate, lag, createdAt, world,
                    Files.size(initialSave), sha256(initialSave), channel, lock);
            recorder.writeManifest("recording", null, null, 0, 0, -1,
                    world.cycle(), SyncHash.of(world), 0, null);
            Runtime.getRuntime().addShutdownHook(recorder.shutdownHook);
            recorder.writerThread.start();
            return recorder;
        } catch (IOException | RuntimeException failed) {
            release(lock, channel);
            throw failed;
        }
    }

    Path directory() {
        return directory;
    }

    @Override
    public void released(long netCycle, long worldCycle, List<GameCommand> commands,
            long syncHash) {
        if (!accepting.get()) {
            return;
        }
        Cycle cycle = new Cycle(netCycle, worldCycle, List.copyOf(commands), syncHash);
        if (!pending.offer(cycle)) {
            stopAfterOverflow(worldCycle, syncHash);
            return;
        }
        lastNetCycle = netCycle;
        lastWorldCycle = worldCycle;
        lastSyncHash = syncHash;
    }

    @Override
    public void finished(long netCycle, long worldCycle, long syncHash) {
        finish(netCycle, worldCycle, syncHash);
    }

    @Override
    public void close() {
        finish(lastNetCycle, lastWorldCycle, lastSyncHash);
    }

    private void finish(long netCycle, long worldCycle, long syncHash) {
        if (accepting.getAndSet(false)) {
            Stop stop = new Stop(true, null, netCycle, worldCycle, syncHash, Instant.now());
            if (!pending.offer(stop)) {
                pending.clear();
                pending.offer(new Stop(false, "the recorder queue was full while closing",
                        netCycle, worldCycle, syncHash, Instant.now()));
            }
        }
        try {
            stopped.await(2, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        try {
            Runtime.getRuntime().removeShutdownHook(shutdownHook);
        } catch (IllegalStateException shuttingDown) {
            // This close is the shutdown hook itself.
        }
    }

    private void stopAfterOverflow(long worldCycle, long syncHash) {
        if (!accepting.getAndSet(false)) {
            return;
        }
        pending.clear();
        pending.offer(new Stop(false, "the recorder could not keep up with the game",
                lastNetCycle, worldCycle, syncHash, Instant.now()));
    }

    private void writeLoop() {
        long cycles = 0;
        long commands = 0;
        long netCycle = -1;
        long worldCycle = initialWorldCycle;
        long syncHash = initialSyncHash;
        long streamBytes = 0;
        MessageDigest streamDigest = sha256Digest();
        try (BufferedWriter out = Files.newBufferedWriter(directory.resolve(STREAM_FILE),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE)) {
            while (true) {
                Event event = pending.take();
                if (event instanceof Cycle cycle) {
                    String line = cycleJson(cycle);
                    out.write(line);
                    // The stream is a platform-independent evidence format, not a
                    // presentation file. A literal LF makes its digest portable.
                    out.write('\n');
                    out.flush();
                    byte[] frame = (line + "\n").getBytes(StandardCharsets.UTF_8);
                    streamDigest.update(frame);
                    streamBytes += frame.length;
                    cycles++;
                    commands += cycle.commands().size();
                    netCycle = cycle.netCycle();
                    worldCycle = cycle.worldCycle();
                    syncHash = cycle.syncHash();
                    if (cycles % CHECKPOINT_INTERVAL == 0) {
                        writeManifest("recording", null, null, cycles, commands,
                                netCycle, worldCycle, syncHash, streamBytes, null);
                    }
                    continue;
                }
                Stop stop = (Stop) event;
                out.flush();
                String streamHash = HexFormat.of().formatHex(streamDigest.digest());
                writeManifest(stop.complete() ? "complete" : "failed", stop.finishedAt(),
                        stop.failure(), cycles, commands, stop.netCycle(), stop.worldCycle(),
                        stop.syncHash(), streamBytes, streamHash);
                return;
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            writeFailureManifest("the recorder writer was interrupted", cycles, commands,
                    worldCycle, syncHash, streamBytes);
        } catch (IOException | RuntimeException failed) {
            writeFailureManifest(failed.getMessage(), cycles, commands, worldCycle, syncHash);
            System.err.println("multiplayer recording failed in " + directory + ": " + failed);
        } finally {
            accepting.set(false);
            release(activityLock, activityChannel);
            try {
                Files.deleteIfExists(directory.resolve(ACTIVE_FILE));
            } catch (IOException ignored) {
                // The lock still protected the live game; a stale marker is harmless.
            }
            try {
                prune(root, MAX_RECORDINGS, MAX_BYTES);
            } catch (IOException ignored) {
                // Retention is best effort and may not erase a recording in use.
            }
            stopped.countDown();
        }
    }

    private void writeFailureManifest(String failure, long cycles, long commands,
            long worldCycle, long syncHash) {
        writeFailureManifest(failure, cycles, commands, worldCycle, syncHash, -1);
    }

    private void writeFailureManifest(String failure, long cycles, long commands,
            long worldCycle, long syncHash, long streamBytes) {
        try {
            writeManifest("failed", Instant.now(), failure, cycles, commands,
                    lastNetCycle, worldCycle, syncHash, streamBytes, null);
        } catch (IOException manifestFailed) {
            System.err.println("could not finish multiplayer recording manifest in "
                    + directory + ": " + manifestFailed);
        }
    }

    private void writeManifest(String status, Instant finishedAt, String failure,
            long recordedCycles, long recordedCommands, long finalNetCycle,
            long finalWorldCycle, long finalSyncHash, long streamBytes,
            String streamHash) throws IOException {
        StringBuilder out = new StringBuilder(3_072);
        out.append("{\n");
        out.append("  \"schema\": 2,\n");
        out.append("  \"sync_hash_schema\": ").append(SyncHash.SCHEMA).append(",\n");
        field(out, "status", status, true);
        field(out, "created_at", createdAt.toString(), true);
        field(out, "finished_at", finishedAt == null ? null : finishedAt.toString(), true);
        field(out, "build", build, true);
        out.append("  \"runtime\": {\"build\": ").append(quote(build))
                .append(", \"game_jar_sha256\": ")
                .append(quote(runtimeIdentity.gameJarSha256()))
                .append(", \"game_jar_bytes\": ")
                .append(runtimeIdentity.gameJarBytes() == null
                        ? "null" : runtimeIdentity.gameJarBytes())
                .append(", \"source_revision\": ")
                .append(quote(runtimeIdentity.sourceRevision())).append("},\n");
        field(out, "map_name", mapName, true);
        field(out, "map_sha256", mapHash, true);
        out.append("  \"players\": [\n");
        for (int index = 0; index < players.size(); index++) {
            RecordedPlayer player = players.get(index);
            out.append("    {\"index\": ").append(player.index())
                    .append(", \"type\": ").append(quote(player.type()))
                    .append(", \"race\": ").append(quote(player.race())).append('}');
            out.append(index + 1 < players.size() ? ",\n" : "\n");
        }
        out.append("  ],\n");
        out.append("  \"local_player\": ").append(localPlayer).append(",\n");
        out.append("  \"cycles_per_second\": ").append(World.CYCLES_PER_SECOND).append(",\n");
        out.append("  \"cycles_per_update\": ").append(cyclesPerUpdate).append(",\n");
        out.append("  \"lag\": ").append(lag).append(",\n");
        out.append("  \"initial_world_cycle\": ").append(initialWorldCycle).append(",\n");
        field(out, "initial_sync_hash", hash(initialSyncHash), true);
        out.append("  \"initial_sync_rng\": {\"seed\": ").append(initialSyncSeed)
                .append(", \"draws\": ").append(initialSyncDraws).append("},\n");
        out.append("  \"initial_async_rng\": {\"seed\": ").append(initialAsyncSeed)
                .append(", \"draws\": ").append(initialAsyncDraws).append("},\n");
        out.append("  \"recorded_net_cycles\": ").append(recordedCycles).append(",\n");
        out.append("  \"recorded_commands\": ").append(recordedCommands).append(",\n");
        out.append("  \"final_net_cycle\": ").append(finalNetCycle).append(",\n");
        out.append("  \"final_world_cycle\": ").append(finalWorldCycle).append(",\n");
        field(out, "final_sync_hash", hash(finalSyncHash), true);
        field(out, "failure", failure, true);
        out.append("  \"artifacts\": {\n");
        artifact(out, "map", MAP_FILE, mapBytes, mapHash, true);
        artifact(out, "initial_save", SAVE_FILE, initialSaveBytes, initialSaveHash, true);
        artifact(out, "command_stream", STREAM_FILE, streamBytes, streamHash, false);
        out.append("  }\n");
        out.append("}\n");
        Path manifest = directory.resolve(MANIFEST_FILE);
        Path temporary = directory.resolve("." + MANIFEST_FILE + ".tmp");
        Files.writeString(temporary, out, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
        try {
            Files.move(temporary, manifest, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String cycleJson(Cycle cycle) {
        StringBuilder out = new StringBuilder(256 + cycle.commands().size() * 160);
        out.append("{\"net_cycle\":").append(cycle.netCycle())
                .append(",\"world_cycle\":").append(cycle.worldCycle())
                .append(",\"sync_hash\":").append(quote(hash(cycle.syncHash())))
                .append(",\"commands\":[");
        for (int index = 0; index < cycle.commands().size(); index++) {
            GameCommand command = cycle.commands().get(index);
            if (index > 0) {
                out.append(',');
            }
            out.append("{\"kind\":").append(quote(command.kind().name()))
                    .append(",\"player\":").append(command.player())
                    .append(",\"unit_id\":").append(command.unitId())
                    .append(",\"x\":").append(command.x())
                    .append(",\"y\":").append(command.y())
                    .append(",\"target_id\":").append(command.targetId())
                    .append(",\"type_index\":").append(command.typeIndex())
                    .append(",\"queued\":").append(command.queued())
                    .append(",\"wire_hex\":").append(quote(wireHex(command)))
                    .append('}');
        }
        return out.append("]}").toString();
    }

    private static String wireHex(GameCommand command) {
        ByteBuffer bytes = ByteBuffer.allocate(GameCommand.WIRE_BYTES);
        command.writeTo(bytes);
        return HexFormat.of().formatHex(bytes.array(), 0, bytes.position());
    }

    private static String hash(long hash) {
        return String.format("%016x", hash);
    }

    private static void artifact(StringBuilder out, String key, String name,
            long bytes, String sha256, boolean comma) {
        out.append("    ").append(quote(key)).append(": {\"name\": ")
                .append(quote(name)).append(", \"bytes\": ").append(bytes)
                .append(", \"sha256\": ").append(quote(sha256)).append('}')
                .append(comma ? ",\n" : "\n");
    }

    private static void field(StringBuilder out, String name, String value, boolean comma) {
        out.append("  ").append(quote(name)).append(": ").append(quote(value))
                .append(comma ? ",\n" : "\n");
    }

    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder escaped = new StringBuilder(value.length() + 2).append('"');
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '"' -> escaped.append("\\\"");
                case '\\' -> escaped.append("\\\\");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
                }
            }
        }
        return escaped.append('"').toString();
    }

    static Path defaultDirectory() {
        return Path.of(System.getProperty("user.home"), ".chonkcraft", "recordings");
    }

    private static String safeName(String mapName) {
        String name;
        try {
            Path file = Path.of(mapName == null ? "" : mapName).getFileName();
            name = file == null ? "multiplayer" : file.toString();
        } catch (RuntimeException malformed) {
            name = "multiplayer";
        }
        name = name.replaceFirst("(?i)\\.pud$", "")
                .replaceAll("[^A-Za-z0-9._-]", "-");
        if (name.isBlank()) {
            return "multiplayer";
        }
        return name.length() <= 48 ? name : name.substring(0, 48);
    }

    private static Path uniqueDirectory(Path root, String base) throws IOException {
        for (int suffix = 0; suffix < 1_000; suffix++) {
            Path candidate = root.resolve(suffix == 0 ? base : base + "-" + suffix);
            try {
                return Files.createDirectory(candidate);
            } catch (java.nio.file.FileAlreadyExistsException collision) {
                // Two local clients can begin the same map in one millisecond.
            }
        }
        throw new IOException("could not allocate a multiplayer recording directory");
    }

    private static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    private static String sha256(Path path) throws IOException {
        MessageDigest digest = sha256Digest();
        try (var in = Files.newInputStream(path)) {
            byte[] buffer = new byte[64 * 1024];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("this Java runtime has no SHA-256", impossible);
        }
    }

    /** Removes only unlocked bundles, oldest first, until both limits hold. */
    static void prune(Path root, int maxRecordings, long maxBytes) throws IOException {
        if (!Files.isDirectory(root)) {
            return;
        }
        List<Stored> stored = new ArrayList<>();
        try (var entries = Files.list(root)) {
            for (Path path : entries.filter(Files::isDirectory).toList()) {
                long modified;
                try {
                    modified = Files.getLastModifiedTime(path).toMillis();
                } catch (IOException unreadable) {
                    modified = Long.MAX_VALUE;
                }
                stored.add(new Stored(path, modified, size(path)));
            }
        }
        stored.sort(Comparator.comparingLong(Stored::modified)
                .thenComparing(item -> item.path().getFileName().toString()));
        long bytes = stored.stream().mapToLong(Stored::bytes).sum();
        int count = stored.size();
        for (Stored candidate : stored) {
            if (count <= maxRecordings && bytes <= maxBytes) {
                break;
            }
            if (deleteIfInactive(root, candidate.path())) {
                count--;
                bytes -= candidate.bytes();
            }
        }
    }

    private static long size(Path directory) {
        try (var files = Files.walk(directory)) {
            return files.filter(Files::isRegularFile).mapToLong(path -> {
                try {
                    return Files.size(path);
                } catch (IOException unreadable) {
                    return 0L;
                }
            }).sum();
        } catch (IOException unreadable) {
            return 0L;
        }
    }

    private static boolean deleteIfInactive(Path root, Path directory) throws IOException {
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path normalized = directory.toAbsolutePath().normalize();
        if (!normalizedRoot.equals(normalized.getParent())) {
            throw new IOException("refusing to prune outside the recording directory");
        }
        Path active = normalized.resolve(ACTIVE_FILE);
        if (!Files.exists(active)) {
            deleteDirectory(normalized);
            return true;
        }
        try (FileChannel channel = FileChannel.open(active, StandardOpenOption.WRITE)) {
            FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException inThisProcess) {
                return false;
            }
            if (lock == null) {
                return false;
            }
            try (lock) {
                deleteDirectory(normalized);
                return true;
            }
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            List<Path> descending = paths.sorted(Comparator.reverseOrder()).toList();
            for (Path path : descending) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static void release(FileLock lock, FileChannel channel) {
        try {
            if (lock != null && lock.isValid()) {
                lock.release();
            }
        } catch (IOException ignored) {
            // The process releasing the channel releases the lock as well.
        }
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (IOException ignored) {
            // Nothing else depends on the evidence file staying open.
        }
    }
}
