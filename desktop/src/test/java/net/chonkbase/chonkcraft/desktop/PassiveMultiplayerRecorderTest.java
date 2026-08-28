package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.chonkbase.assetpack.Json;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.network.SyncHash;
import net.chonkbase.chonkcraft.engine.save.LoadGame;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The local evidence left behind by an ordinary multiplayer game.
 *
 * <p>There used to be no durable match record at all. A movement failure seen
 * during an hour with another person had to be recreated by hand, and closing
 * the game discarded the only authoritative input -- the commands both peers
 * had actually accepted. These inspect files while the game is live as well
 * as after it closes, because a final archive alone would still vanish in the
 * crash and forced-quit cases where the evidence matters most.
 */
class PassiveMultiplayerRecorderTest {

    @Test
    @DisplayName("a multiplayer game leaves a self-contained harvestable bundle")
    void aRunningGameFlushesCommandsAndFinishesItsBundle(@TempDir Path root) throws Exception {
        World world = new World(new GameMap(12, 10, new Tileset()));
        byte[] map = {0x57, 0x41, 0x52, 0x32, 0x01, 0x02};
        Instant began = Instant.parse("2026-08-26T19:20:21.123Z");
        PassiveMultiplayerRecorder recorder = PassiveMultiplayerRecorder.open(
                world, "maps/skirmish/FAMILY.PUD", map, 1, 5, 2,
                "test-build", root, began);
        Path directory = recorder.directory();
        GameCommand command = GameCommand.move(1, 42, 17, 23).withQueued(true);
        try {
            recorder.released(0, 1, List.of(command), 0x1234L);
            Path stream = directory.resolve("commands.jsonl");
            long deadline = System.currentTimeMillis() + 2_000L;
            while ((!Files.exists(stream) || Files.size(stream) == 0)
                    && System.currentTimeMillis() < deadline) {
                Thread.sleep(5L);
            }
            assertTrue(Files.exists(stream) && Files.size(stream) > 0,
                    "the live recorder did not flush a crash-recoverable command prefix");
            assertTrue(Files.readString(directory.resolve("manifest.json"))
                            .contains("\"status\": \"recording\""),
                    "the live bundle cannot be distinguished from a cleanly closed one");
            recorder.released(1, 6, List.of(), 0x5678L);
            recorder.finished(1, 6, 0x5678L);
        } finally {
            recorder.close();
        }

        assertArrayEquals(map, Files.readAllBytes(directory.resolve("map.pud")),
                "the bundle did not retain the exact map bytes agreed in the lobby");
        assertTrue(Files.isRegularFile(directory.resolve("initial.sav.gz")),
                "the bundle is missing its cycle-zero simulation state");
        String saved = LoadGame.read(directory.resolve("initial.sav.gz"));
        assertEquals("maps/skirmish/FAMILY.PUD", LoadGame.header(saved).mapPath(),
                "the initial save no longer names the map that produced the world");

        List<String> cycles = Files.readAllLines(directory.resolve("commands.jsonl"));
        assertEquals(2, cycles.size(),
                "an empty accepted batch or a commanded batch disappeared from the stream");
        ObjectMapper json = new ObjectMapper();
        JsonNode firstCycle = json.readTree(cycles.getFirst());
        JsonNode lastCycle = json.readTree(cycles.getLast());
        assertEquals("MOVE", firstCycle.path("commands").get(0).path("kind").asText(),
                "the first command line is not valid, structured JSON");
        assertEquals(0, lastCycle.path("commands").size(),
                "the empty cycle does not parse as an empty command batch");
        assertTrue(cycles.getFirst().contains("\"kind\":\"MOVE\"")
                        && cycles.getFirst().contains("\"unit_id\":42")
                        && cycles.getFirst().contains("\"queued\":true")
                        && cycles.getFirst().contains("\"wire_hex\":"),
                "the first command is not preserved in normalized and exact wire forms");
        assertTrue(cycles.getLast().contains("\"commands\":[]"),
                "a no-order lockstep cycle was omitted instead of recorded as empty");

        String manifest = Files.readString(directory.resolve("manifest.json"));
        assertEquals("complete", json.readTree(manifest).path("status").asText(),
                "the finished manifest is not valid, structured JSON");
        assertTrue(manifest.contains("\"status\": \"complete\"")
                        && manifest.contains("\"recorded_net_cycles\": 2")
                        && manifest.contains("\"recorded_commands\": 1")
                        && manifest.contains("\"final_net_cycle\": 1")
                        && manifest.contains("\"final_sync_hash\": \"0000000000005678\""),
                "the finished manifest does not delimit and authenticate the recorded stream");
        assertFalse(manifest.contains("chat"),
                "private side-band chat leaked into the deterministic match record");
        assertFalse(Files.exists(directory.resolve(".active")),
                "a cleanly finished bundle still looks live to the retention gate");

        MultiplayerRecording.Validated validated = MultiplayerRecording.validate(directory);
        assertTrue(validated.currentSchemaComplete(),
                "the finished bundle did not seal its roster and three artifacts");
        assertEquals(SyncHash.SCHEMA, validated.syncHashSchema(),
                "the bundle did not name the exact synchronization projection");
        assertEquals(16, validated.players().size(),
                "the replay boundary lost part of the simulation player table");
        assertEquals(2, validated.recordedNetCycles());
        assertEquals(1, validated.recordedCommands());
    }

    @Test
    @DisplayName("two independently recorded command schedules replay exactly")
    void sealedSchedulesReplayThroughTheOrdinaryCommandBoundary(@TempDir Path root)
            throws Exception {
        Path first = record(root.resolve("first"), List.of(
                List.of(GameCommand.ping(0, 4, 6)),
                List.of(),
                List.of(GameCommand.quit(2))));
        Path second = record(root.resolve("second"), List.of(
                List.of(GameCommand.ping(1, 8, 3)),
                List.of(GameCommand.ping(0, 2, 7)),
                List.of(), List.of(), List.of(), List.of(), List.of(), List.of()));

        for (Path directory : List.of(first, second)) {
            MultiplayerRecording.Validated validated = MultiplayerRecording.validate(directory);
            World replayWorld = new World(new GameMap(12, 10, new Tileset()));
            LoadGame.apply(replayWorld, LoadGame.read(validated.initialSave().path()), Map.of());
            MultiplayerRecording.Replay replay = MultiplayerRecording.replay(
                    validated, replayWorld, new CommandApplier(replayWorld, List.of()));
            assertTrue(replay.exact(), replay.failure());
            assertEquals(validated.finalSyncHash(), replay.finalSyncHash(),
                    "an exact replay ended on a different synchronized world");
        }
    }

    @Test
    @DisplayName("sealed recordings reject wire tampering and a wrong initial world")
    void recordingRefereeFailsClosedOnContainerAndSimulationDrift(@TempDir Path root)
            throws Exception {
        Path tampered = record(root.resolve("tampered"),
                List.of(List.of(GameCommand.ping(0, 7, 9)), List.of()));
        Path stream = tampered.resolve("commands.jsonl");
        String changed = Files.readString(stream, StandardCharsets.UTF_8)
                .replaceFirst("\\\"x\\\":7", "\\\"x\\\":8");
        Files.writeString(stream, changed, StandardCharsets.UTF_8);
        Map<String, Object> manifest = Json.parseObject(Files.readString(
                tampered.resolve("manifest.json"), StandardCharsets.UTF_8));
        @SuppressWarnings("unchecked")
        Map<String, Object> artifacts = (Map<String, Object>) manifest.get("artifacts");
        @SuppressWarnings("unchecked")
        Map<String, Object> commandStream =
                (Map<String, Object>) artifacts.get("command_stream");
        commandStream.put("bytes", Files.size(stream));
        commandStream.put("sha256", MultiplayerRecording.sha256(stream));
        Files.writeString(tampered.resolve("manifest.json"), Json.write(manifest),
                StandardCharsets.UTF_8);
        assertThrows(java.io.IOException.class,
                () -> MultiplayerRecording.validate(tampered),
                "rewriting normalized fields and resealing the file hid wire-byte tampering");

        Path wrongSchema = record(root.resolve("wrong-schema"),
                List.of(List.of(), List.of()));
        Map<String, Object> schemaManifest = Json.parseObject(Files.readString(
                wrongSchema.resolve("manifest.json"), StandardCharsets.UTF_8));
        schemaManifest.put("sync_hash_schema", SyncHash.SCHEMA + 1);
        Files.writeString(wrongSchema.resolve("manifest.json"),
                Json.write(schemaManifest), StandardCharsets.UTF_8);
        assertThrows(IllegalArgumentException.class,
                () -> MultiplayerRecording.validate(wrongSchema),
                "a recording from an unknown synchronization projection was accepted");

        Path valid = record(root.resolve("wrong-world"), List.of(List.of(), List.of()));
        MultiplayerRecording.Validated validated = MultiplayerRecording.validate(valid);
        World wrong = new World(new GameMap(12, 10, new Tileset()));
        wrong.tick();
        MultiplayerRecording.Replay replay = MultiplayerRecording.replay(
                validated, wrong, new CommandApplier(wrong, List.of()));
        assertFalse(replay.exact(), "a world already past cycle zero certified the recording");
        assertTrue(replay.failure().contains("starts at cycle"), replay.failure());
    }

    @Test
    @DisplayName("runtime source binding names only the exact installed JAR")
    void runtimeSourceBindingRejectsDetachedMetadata(@TempDir Path root) throws Exception {
        Path game = root.resolve("game.jar");
        Files.write(game, new byte[] {9, 8, 7, 6, 5});
        String hash = MultiplayerRecording.sha256(game);
        String revision = "0123456789abcdef0123456789abcdef01234567";
        Files.writeString(root.resolve("release.properties"),
                "format=chonkcraft-installed-1\n"
                        + "version=2026.0827.999\n"
                        + "game.sha256=" + hash + "\n"
                        + "game.bytes=" + Files.size(game) + "\n"
                        + "origin=remote\n"
                        + "source.revision=" + revision + "\n",
                StandardCharsets.UTF_8);

        PassiveMultiplayerRecorder.RuntimeIdentity bound =
                PassiveMultiplayerRecorder.RuntimeIdentity.from(
                        game, "2026.0827.999");
        assertEquals(hash, bound.gameJarSha256());
        assertEquals(Files.size(game), bound.gameJarBytes());
        assertEquals(revision, bound.sourceRevision());

        Files.writeString(root.resolve("release.properties"),
                "version=2026.0827.999\n"
                        + "game.sha256=" + "0".repeat(64) + "\n"
                        + "source.revision=" + revision + "\n",
                StandardCharsets.UTF_8);
        PassiveMultiplayerRecorder.RuntimeIdentity detached =
                PassiveMultiplayerRecorder.RuntimeIdentity.from(
                        game, "2026.0827.999");
        assertEquals(hash, detached.gameJarSha256(),
                "the recorder lost the independently measured JAR identity");
        assertEquals(null, detached.sourceRevision(),
                "metadata for different JAR bytes claimed this runtime's revision");
    }

    @Test
    @DisplayName("three authenticated BNE maps reconstruct and replay exactly")
    void sealedRecordingsReconstructAcrossIndependentMaps(@TempDir Path root)
            throws Exception {
        AssetSource configured = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(configured != null,
                "No asset pack/install configured. Set -Dchonkcraft.pack or wc2.install.dir.");
        try (AssetSource assets = configured) {
            List<String> maps = List.of(
                    "All You Need BNE.pud",
                    "Forsaken Isles BNE.pud",
                    "ladder/Garden of war BNE.pud");
            Assumptions.assumeTrue(maps.stream().allMatch(assets::hasMap),
                    "the authenticated source does not carry all three BNE maps");

            for (int scenario = 0; scenario < maps.size(); scenario++) {
                String map = maps.get(scenario);
                Path recording = recordMap(assets, map,
                        root.resolve("map-" + scenario), scenario);
                BneRecordingCertification.Certification certification =
                        BneRecordingCertification.certify(recording, assets);
                assertTrue(certification.recording().currentSchemaComplete(),
                        map + " did not produce a self-contained current recording");
                assertTrue(certification.replay().exact(),
                        map + ": " + certification.replay().failure());
                assertEquals(Boolean.TRUE,
                        certification.report().get("simulation_exact"), map);
            }
        }
    }

    @Test
    @DisplayName("retention never erases another local client's live game")
    void retentionSkipsLockedGamesWhileRemovingTheOldestFinishedOne(@TempDir Path root)
            throws Exception {
        Path active = recording(root, "game-1-active", 1L);
        Path old = recording(root, "game-2-old", 2L);
        Path newest = recording(root, "game-3-new", 3L);
        Path marker = active.resolve(".active");
        Files.writeString(marker, "");
        Files.setLastModifiedTime(active, FileTime.fromMillis(1L));

        try (FileChannel channel = FileChannel.open(marker, StandardOpenOption.WRITE);
                FileLock lock = channel.lock()) {
            PassiveMultiplayerRecorder.prune(root, 2, Long.MAX_VALUE);
            assertTrue(Files.isDirectory(active),
                    "retention erased a game another local client was still recording");
            assertFalse(Files.exists(old),
                    "retention kept an older finished game instead of making bounded space");
            assertTrue(Files.isDirectory(newest),
                    "retention erased the newest finished game before an older candidate");
        }

        PassiveMultiplayerRecorder.prune(root, 1, Long.MAX_VALUE);
        assertFalse(Files.exists(active),
                "an abandoned unlocked recording was never made eligible for later cleanup");
        assertTrue(Files.isDirectory(newest),
                "cleanup did not preserve the newest remaining match");
    }

    private static Path recording(Path root, String name, long modified) throws Exception {
        Path directory = Files.createDirectory(root.resolve(name));
        Files.writeString(directory.resolve("commands.jsonl"), "{}\n");
        Files.setLastModifiedTime(directory, FileTime.fromMillis(modified));
        return directory;
    }

    private static Path record(Path root, List<List<GameCommand>> batches) throws Exception {
        Files.createDirectories(root);
        World world = new World(new GameMap(12, 10, new Tileset()));
        CommandApplier applier = new CommandApplier(world, List.of());
        PassiveMultiplayerRecorder recorder = PassiveMultiplayerRecorder.open(
                world, "maps/skirmish/REPLAY.PUD",
                new byte[] {0x57, 0x41, 0x52, 0x32, 9, 8, 7},
                0, 5, 2, "test-build", root,
                Instant.parse("2026-08-27T20:00:00Z"));
        Path directory = recorder.directory();
        try {
            for (int netCycle = 0; netCycle < batches.size(); netCycle++) {
                long boundary = (long) netCycle * 5;
                while (world.cycle() < boundary) {
                    world.tick();
                }
                List<GameCommand> commands = batches.get(netCycle);
                applier.applyAll(commands);
                world.tick();
                recorder.released(netCycle, world.cycle(), commands, SyncHash.of(world));
            }
            recorder.finished(batches.size() - 1L, world.cycle(), SyncHash.of(world));
        } finally {
            recorder.close();
        }
        return directory;
    }

    private static Path recordMap(AssetSource assets, String mapName, Path root,
            int scenario) throws Exception {
        Files.createDirectories(root);
        byte[] mapBytes = assets.map(mapName);
        PudMap source = PudReader.read(mapBytes);
        PudMap.PlayerType[] types = new PudMap.PlayerType[Player.MAX];
        Arrays.fill(types, PudMap.PlayerType.NOBODY);
        List<Integer> playable = new ArrayList<>();
        for (int index = 0; index < Player.MAX; index++) {
            if (source.players()[index] == PudMap.PlayerType.NEUTRAL) {
                types[index] = PudMap.PlayerType.NEUTRAL;
            } else if (source.players()[index] != PudMap.PlayerType.NOBODY) {
                playable.add(index);
            }
        }
        Assumptions.assumeTrue(playable.size() >= 2,
                mapName + " has fewer than two playable slots");
        int localPlayer = playable.getFirst();
        types[localPlayer] = PudMap.PlayerType.PERSON;
        if (scenario == 0) {
            types[playable.get(1)] = PudMap.PlayerType.PERSON;
        } else {
            types[playable.get(1)] = PudMap.PlayerType.COMPUTER;
            if (scenario == 2 && playable.size() > 2) {
                types[playable.get(2)] = PudMap.PlayerType.PERSON;
            }
        }

        GameData data = new GameData(assets);
        World world = new World(
                GameMap.from(source, data.loadTileset(source.tileset()).tileset()),
                Player.forNetworkGame(source, types, source.races()));
        world.setPlayerSiegeBuildingTargetLockEnabled(true);
        data.configureWorld(world, source);
        data.populate(world, source);
        world.recalculateSupply();
        world.enableAiForComputerPlayers();
        data.attachRetailAi(world, source, Map.of());

        List<net.chonkbase.chonkcraft.engine.unit.UnitType> roster =
                new ArrayList<>(data.unitTypes().types().values());
        CommandApplier applier = new CommandApplier(world, roster);
        data.configureCommands(applier);
        PassiveMultiplayerRecorder recorder = PassiveMultiplayerRecorder.open(
                world, mapName, mapBytes, localPlayer, 5, 2,
                "test-build", root, Instant.parse("2026-08-27T20:00:00Z"));
        Path directory = recorder.directory();
        long initialCycle = world.cycle();
        try {
            for (int netCycle = 0; netCycle < 12; netCycle++) {
                long boundary = initialCycle + (long) netCycle * 5;
                while (world.cycle() < boundary) {
                    world.tick();
                }
                List<GameCommand> commands = netCycle == scenario * 3
                        ? List.of(GameCommand.ping(localPlayer,
                                (scenario + 2) % world.map().width(),
                                (scenario + 3) % world.map().height()))
                        : List.of();
                applier.applyAll(commands);
                world.tick();
                recorder.released(netCycle, world.cycle(), commands, SyncHash.of(world));
            }
            recorder.finished(11, world.cycle(), SyncHash.of(world));
        } finally {
            recorder.close();
        }
        return directory;
    }
}
