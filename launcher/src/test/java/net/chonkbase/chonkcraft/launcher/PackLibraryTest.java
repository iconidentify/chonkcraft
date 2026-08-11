package net.chonkbase.chonkcraft.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import net.chonkbase.assetpack.AssetKind;
import net.chonkbase.assetpack.AssetPackWriter;
import net.chonkbase.assetpack.Codec;
import net.chonkbase.assetpack.PackManifest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The managed pack directory is a library, not an unbounded file operation. */
class PackLibraryTest {

    @TempDir
    Path temporary;

    @Test
    @DisplayName("source version and provenance survive into the pack library")
    void sourceProvenanceIsVisibleAfterThePackIsReopened() {
        Path file = temporary.resolve("packs").resolve("battle-net.chonkpack");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("battleNetEdition", true);
        properties.put("expansionRelease", true);
        properties.put("sourceVersion", "Battle.net Edition 2.02b");
        properties.put("sourceFormat", "Battle.net Edition MPQ");
        properties.put("sourceOriginalName", "Warcraft II BNE.iso");
        properties.put("sourceOriginalSha256", "abcdef0123456789");
        PackManifest.Identity identity = new PackManifest.Identity(
                "wc2-battle-net-edition", "Warcraft II: Battle.net Edition",
                "test disc", "test builder", "2026-07-30T12:00:00Z",
                properties);
        writePlayablePack(file, identity);

        PackLibrary.PackInfo pack = PackLibrary.read(file);

        assertNotNull(pack);
        assertEquals("Battle.net Edition 2.02b", pack.sourceVersion(),
                "the source release disappeared from the library");
        assertEquals("Battle.net Edition 2.02b", pack.versionDetail(),
                "the retail source version disappeared");
        assertEquals("Warcraft II BNE.iso", pack.sourceOriginalName(),
                "the selected installer name disappeared from the library");
        assertEquals("abcdef012345", pack.fingerprint(),
                "the source checksum was not shortened for display");
    }

    @Test
    @DisplayName("legacy War2Combat packs are not playable choices")
    void legacyWar2CombatPackIsRejected() {
        Path file = temporary.resolve("packs").resolve("legacy.chonkpack");
        PackManifest.Identity identity = new PackManifest.Identity(
                "wc2-war2combat-4.6", "Unsupported derivative",
                "legacy installer", "test builder", "2026-07-30T12:00:00Z", Map.of());
        writePlayablePack(file, identity);

        assertNull(PackLibrary.read(file));
    }

    @Test
    @DisplayName("a pack without the retail AI program is not playable")
    void packMissingRetailAiIsRejected() {
        Path file = temporary.resolve("packs").resolve("incomplete.chonkpack");
        PackManifest.Identity identity = new PackManifest.Identity(
                "wc2-battle-net-edition", "Incomplete retail pack",
                "test", "test builder", "2026-07-30T12:00:00Z", Map.of());
        try (AssetPackWriter writer = new AssetPackWriter(file, identity)) {
            writer.finish();
        }

        assertNull(PackLibrary.read(file));
    }

    @Test
    @DisplayName("export copies a pack and delete removes only its managed copy")
    void exportAndDeleteStayWithinTheirNamedFiles() throws Exception {
        Path directory = temporary.resolve("packs");
        Files.createDirectories(directory);
        Path file = directory.resolve("original.chonkpack");
        Files.writeString(file, "pack bytes");
        PackLibrary library = new PackLibrary(directory);
        PackLibrary.PackInfo pack = new PackLibrary.PackInfo(
                file, "original", "Original", "disc", false, false, 1);
        Path exported = temporary.resolve("exports").resolve("original.chonkpack");

        library.export(pack, exported, false);
        assertEquals("pack bytes", Files.readString(exported),
                "the exported pack did not match the managed copy");

        library.delete(pack);
        assertFalse(Files.exists(file), "the managed pack remained after deletion");
        assertTrue(Files.exists(exported), "deleting the managed pack removed its export");
    }

    @Test
    @DisplayName("delete refuses a file outside the managed pack library")
    void deleteRefusesAnOutsideFile() throws Exception {
        Path directory = temporary.resolve("packs");
        Files.createDirectories(directory);
        Path outside = temporary.resolve("outside.chonkpack");
        Files.writeString(outside, "keep");
        PackLibrary library = new PackLibrary(directory);
        PackLibrary.PackInfo pack = new PackLibrary.PackInfo(
                outside, "outside", "Outside", "test", false, false, 1);

        IOException failure = assertThrows(IOException.class,
                () -> library.delete(pack),
                "the library deleted a file it does not own");
        assertTrue(failure.getMessage().contains("managed library"),
                "the refusal did not explain the library boundary");
        assertTrue(Files.exists(outside), "the outside file was removed");
    }

    private static void writePlayablePack(Path file, PackManifest.Identity identity) {
        try (AssetPackWriter writer = new AssetPackWriter(file, identity)) {
            int ai = writer.add("archives/maindat/0277", AssetKind.BINARY, Codec.STORE,
                    "assets/archives/maindat/0277.bin", new byte[] {1, 2, 3, 4},
                    4, Map.of());
            int[] slots = new int[278];
            Arrays.fill(slots, -1);
            slots[277] = ai;
            writer.archive(1000, "maindat", slots);
            writer.finish();
        }
    }
}
