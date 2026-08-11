package net.chonkbase.chonkcraft.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The launcher begins at the source a player selects, not at an extracted dir.
 *
 * <p>A directory hidden inside an ordinary ZIP is the most common download
 * shape, and unsafe parent paths are the failure mode of accepting arbitrary
 * archives. Both tests enter through source inspection, the same discovery
 * path the Create Graphics Pack button uses.
 */
class SourceImporterTest {

    @TempDir
    Path temporary;

    @Test
    @DisplayName("a zipped game directory is found without asking where DATA is")
    void aPlainZipIsDiscovered() throws Exception {
        Path archive = temporary.resolve("warcraft.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("Warcraft II/DATA/MAINDAT.WAR"));
            zip.write(warArchiveWithRetailAi(1000));
            zip.closeEntry();
        }
        LauncherHome home = new LauncherHome(temporary.resolve("home"));
        SourceImporter importer = new SourceImporter(home, new PackLibrary(home.packs()));

        List<SourceImporter.ProgressUpdate> progress = new ArrayList<>();
        SourceImporter.Inspection found = importer.inspect(archive, progress::add);
        assertTrue(found.installation().toString().contains("Warcraft II"),
                "the nested game directory was not selected");
        assertFalse(found.expansion(), "a base archive became the expansion");
        assertEquals(0, found.maps(), "the empty fixture invented loose maps");
        assertEquals(100, progress.getLast().percent(),
                "source inspection never completed its progress journey");
        for (int i = 1; i < progress.size(); i++) {
            assertTrue(progress.get(i).percent() >= progress.get(i - 1).percent(),
                    "import progress moved backwards");
        }
        SourceImporter.ProgressUpdate unpacked = progress.stream()
                .filter(update -> update.unit() == SourceImporter.Unit.BYTES)
                .reduce((first, second) -> second)
                .orElseThrow();
        assertEquals(unpacked.total(), unpacked.completed(),
                "ZIP extraction reported a made-up rather than exact byte count");
    }

    @Test
    @DisplayName("a zip cannot write outside its import directory")
    void anEscapingZipIsRefused() throws Exception {
        Path archive = temporary.resolve("unsafe.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("../../outside"));
            zip.write(1);
            zip.closeEntry();
        }
        LauncherHome home = new LauncherHome(temporary.resolve("home"));
        SourceImporter importer = new SourceImporter(home, new PackLibrary(home.packs()));

        IOException failure = assertThrows(IOException.class,
                () -> importer.inspect(archive, null),
                "an escaping archive path was extracted");
        assertTrue(failure.getMessage().contains("unsafe path"),
                "the refusal did not name the unsafe archive path");
        assertFalse(Files.exists(temporary.resolve("outside")),
                "the archive wrote beyond its staging directory");
    }

    @Test
    @DisplayName("an installation without the retail AI program is refused")
    void sourceMissingRetailAiIsRefused() throws Exception {
        Path archive = temporary.resolve("incomplete.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            zip.putNextEntry(new ZipEntry("Warcraft II/DATA/MAINDAT.WAR"));
            zip.write(oneEntryWarArchive(1000));
            zip.closeEntry();
        }
        LauncherHome home = new LauncherHome(temporary.resolve("home"));
        SourceImporter importer = new SourceImporter(home, new PackLibrary(home.packs()));

        IOException failure = assertThrows(IOException.class,
                () -> importer.inspect(archive, null));
        assertTrue(failure.getMessage().contains("ai.bin"),
                "the incomplete media refusal did not explain the missing AI program");
    }

    @Test
    @DisplayName("community installer executables are not accepted media")
    void executableInstallerIsRefused() throws Exception {
        Path installer = temporary.resolve("community-installer.exe");
        Files.write(installer, new byte[] {1, 2, 3, 4});
        LauncherHome home = new LauncherHome(temporary.resolve("home"));
        SourceImporter importer = new SourceImporter(home, new PackLibrary(home.packs()));

        IOException failure = assertThrows(IOException.class,
                () -> importer.inspect(installer, null));
        assertTrue(failure.getMessage().contains("unsupported source type"));
    }

    private static byte[] warArchiveWithRetailAi(int id) {
        int entries = 278;
        int payload = 8 + entries * 4;
        byte[] bytes = new byte[payload + 8];
        little32(bytes, 0, 0x19);
        little16(bytes, 4, entries);
        little16(bytes, 6, id);
        for (int entry = 0; entry < entries; entry++) {
            little32(bytes, 8 + entry * 4, payload);
        }
        little32(bytes, payload, 4);
        bytes[payload + 4] = 1;
        bytes[payload + 5] = 2;
        bytes[payload + 6] = 3;
        bytes[payload + 7] = 4;
        return bytes;
    }

    private static byte[] oneEntryWarArchive(int id) {
        byte[] bytes = new byte[17];
        little32(bytes, 0, 0x19);
        little16(bytes, 4, 1);
        little16(bytes, 6, id);
        little32(bytes, 8, 12);
        little32(bytes, 12, 1);
        bytes[16] = 1;
        return bytes;
    }

    private static void little16(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >>> 8);
    }

    private static void little32(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >>> 8);
        bytes[at + 2] = (byte) (value >>> 16);
        bytes[at + 3] = (byte) (value >>> 24);
    }
}
