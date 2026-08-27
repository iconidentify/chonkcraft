package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.save.LoadGame;
import org.junit.jupiter.api.DisplayName;
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
}
