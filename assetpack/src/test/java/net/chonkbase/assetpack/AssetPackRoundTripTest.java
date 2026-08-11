package net.chonkbase.assetpack;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * What a pack promises a consumer, checked by writing one and reading it back.
 *
 * <p>These are behaviours rather than fields. A pack that stores an asset and
 * hands back different bytes is broken in a way no field check would catch,
 * and the two that matter most here -- a hole in an archive still answering,
 * and the entry count staying what the original declared -- are exactly the
 * cases a tidier implementation would optimise away.
 */
class AssetPackRoundTripTest {

    private static PackManifest.Identity identity() {
        return new PackManifest.Identity("test", "Test Pack", "a test",
                "the test suite", "1970-01-01T00:00:00Z",
                Map.of("expansionEntries", Boolean.TRUE, "campaignTextOffset", 236L));
    }

    @Test
    void anAssetComesBackAsItWentIn(@TempDir Path dir) {
        Path file = dir.resolve("round-trip.chonkpack");
        byte[] payload = "the quick brown fox".getBytes(StandardCharsets.UTF_8);

        try (AssetPackWriter writer = new AssetPackWriter(file, identity())) {
            writer.add("text/greeting", AssetKind.TEXT, Codec.STORE,
                    "assets/text/greeting.txt", payload, payload.length, Map.of());
            writer.finish();
        }

        try (AssetPack pack = AssetPack.open(file)) {
            PackAsset asset = pack.find("text/greeting");
            assertNotNull(asset, "the asset should be findable by the name it was added under");
            assertArrayEquals(payload, pack.bytes(asset));
            assertTrue(pack.verify(asset), "the recorded hash should match the stored bytes");
        }
    }

    @Test
    void aHoleInAnArchiveStillAnswers(@TempDir Path dir) {
        // The behaviour under test: Warcraft II's maindat.war has five junk
        // entries in the DOS build and the port relies on reading them without
        // an exception. A pack that dropped them would renumber everything
        // after them; a pack that threw would crash the font loader, which
        // sniffs the result and falls through.
        Path file = dir.resolve("holes.chonkpack");
        try (AssetPackWriter writer = new AssetPackWriter(file, identity())) {
            int first = writer.add("a", AssetKind.BINARY, Codec.STORE, "assets/a.bin",
                    new byte[] {1, 2, 3}, 3, Map.of());
            int second = writer.add("b", AssetKind.BINARY, Codec.STORE, "assets/b.bin",
                    new byte[] {4, 5, 6}, 3, Map.of());
            writer.archive(1000, "maindat", new int[] {first, -1, -1, second});
            writer.finish();
        }

        try (AssetPack pack = AssetPack.open(file)) {
            PackManifest.Archive archive = pack.manifest().archive(1000);
            assertNotNull(archive);
            assertEquals(4, archive.entryCount(),
                    "the entry count is what the original declared, holes included");
            assertTrue(archive.isValid(0));
            assertTrue(!archive.isValid(1), "entry 1 was a hole and stays one");
            assertTrue(!archive.isValid(2));
            assertTrue(archive.isValid(3));
            assertEquals(1, archive.slots()[3],
                    "entry 3 still points at the second asset, and the holes did not"
                    + " renumber the entries after them");
        }
    }

    @Test
    void discOrderIsPreserved(@TempDir Path dir) {
        // Order is the only identity recorded music has: the game asks for
        // "track three of whichever disc is in the drive".
        Path file = dir.resolve("music.chonkpack");
        try (AssetPackWriter writer = new AssetPackWriter(file, identity())) {
            int third = writer.add("music/c", AssetKind.MUSIC, Codec.STORE, "assets/c.pcm",
                    new byte[] {3}, 1, Map.of("name", "WC2TOD track 4"));
            int first = writer.add("music/a", AssetKind.MUSIC, Codec.STORE, "assets/a.pcm",
                    new byte[] {1}, 1, Map.of("name", "WC2TOD track 2"));
            int second = writer.add("music/b", AssetKind.MUSIC, Codec.STORE, "assets/b.pcm",
                    new byte[] {2}, 1, Map.of("name", "WC2TOD track 3"));
            writer.disc("WC2TOD", List.of(first, second, third));
            writer.finish();
        }

        try (AssetPack pack = AssetPack.open(file)) {
            PackManifest.Disc disc = pack.manifest().discs().get(0);
            assertEquals(List.of("WC2TOD track 2", "WC2TOD track 3", "WC2TOD track 4"),
                    disc.tracks().stream()
                            .map(index -> pack.manifest().at(index).string("name", ""))
                            .toList(),
                    "tracks play in the order the disc lists them, not the order they were added");
        }
    }

    @Test
    void aPackFromTheFutureIsRefused() {
        String manifest = """
                {
                  "format": "chonkpack",
                  "formatVersion": 99,
                  "pack": {"id": "x"},
                  "archives": [],
                  "assets": []
                }
                """;
        PackFormatException thrown = assertThrows(PackFormatException.class,
                () -> PackManifest.fromJson(manifest));
        assertTrue(thrown.getMessage().contains("99"),
                "the message should say which version it found: " + thrown.getMessage());
    }

    @Test
    void aDuplicateNameIsRefusedRatherThanLosingAnAsset(@TempDir Path dir) {
        Path file = dir.resolve("clash.chonkpack");
        try (AssetPackWriter writer = new AssetPackWriter(file, identity())) {
            writer.add("same", AssetKind.BINARY, Codec.STORE, "assets/one.bin",
                    new byte[] {1}, 1, Map.of());
            assertThrows(IllegalArgumentException.class, () ->
                    writer.add("same", AssetKind.BINARY, Codec.STORE, "assets/two.bin",
                            new byte[] {2}, 1, Map.of()));
        }
    }

    @Test
    void anAbandonedPackDoesNotSurvive(@TempDir Path dir) {
        // A zip with assets and no manifest reads as "not a pack", and someone
        // would spend an afternoon on it.
        Path file = dir.resolve("abandoned.chonkpack");
        try (AssetPackWriter writer = new AssetPackWriter(file, identity())) {
            writer.add("a", AssetKind.BINARY, Codec.STORE, "assets/a.bin",
                    new byte[] {1}, 1, Map.of());
        }
        assertTrue(!Files.exists(file), "a half-written pack is removed rather than left behind");
        assertNull(AssetPack.tryOpen(file));
    }

    @Test
    void buildingTheSamePackTwiceProducesTheSameBytes(@TempDir Path dir) throws Exception {
        // Otherwise every build is a different artefact and nothing downstream
        // can be cached, compared or verified.
        Path first = dir.resolve("one.chonkpack");
        Path second = dir.resolve("two.chonkpack");
        for (Path file : List.of(first, second)) {
            try (AssetPackWriter writer = new AssetPackWriter(file, identity())) {
                writer.add("a", AssetKind.BINARY, Codec.STORE, "assets/a.bin",
                        new byte[] {1, 2, 3}, 3, Map.of("width", 4L));
                writer.archive(1000, "maindat", new int[] {0});
                writer.finish();
            }
        }
        assertArrayEquals(Files.readAllBytes(first), Files.readAllBytes(second));
    }

    @Test
    void theManifestSurvivesJson() {
        try {
            Path file = Files.createTempFile("manifest", ".chonkpack");
            Files.delete(file);
            PackManifest written;
            try (AssetPackWriter writer = new AssetPackWriter(file, identity())) {
                writer.add("graphics/footman", AssetKind.SPRITE, Codec.PNG_INDEXED,
                        "assets/graphics/footman.png", new byte[] {9}, 100,
                        Map.of("width", 360L, "height", 720L, "transparentIndex", 255L,
                                "frames", List.of(List.of(0L, 0L, 45L, 40L))));
                writer.archive(1000, "maindat", new int[] {0, -1});
                written = writer.finish();
            }
            PackManifest reread = PackManifest.fromJson(written.toJson());
            assertEquals(written.toJson(), reread.toJson(),
                    "a manifest written, read and written again should be identical");
            PackAsset asset = reread.find("graphics/footman");
            assertEquals(360, asset.width());
            assertEquals(255, asset.transparentIndex());
            assertEquals(1, asset.frames().size());
            assertEquals(new PackAsset.Frame(0, 0, 45, 40), asset.frames().get(0));
            assertEquals(236, Json.integer(reread.identity().properties(),
                    "campaignTextOffset", 0));
            Files.deleteIfExists(file);
        } catch (java.io.IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }
}
