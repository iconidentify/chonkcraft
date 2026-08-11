package net.chonkbase.chonkcraft.data.archive;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import net.chonkbase.chonkcraft.data.source.ArchiveIds;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reads a real Warcraft II installation.
 *
 * <p>The decisive check is {@link #everySoundDecodesToAWellFormedWav()}: the
 * sound archive holds hundreds of independent RIFF files, each carrying its own
 * length in its header. If the decompressor were wrong anywhere, some file's
 * magic or declared size would disagree with what came out. Getting all of them
 * right by accident is not a thing that happens.
 *
 * <p>Skipped when no installation is configured. Point
 * {@code -Dwc2.install.dir=...} or {@code WC2_INSTALL_DIR} at the game
 * directory, the one holding {@code DATA/MAINDAT.WAR}.
 */
class WarArchiveRealDataTest {

    private static InstallSource install() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game "
                        + "(the directory containing DATA/MAINDAT.WAR) to run this test.");
        return install;
    }

    private static WarArchive open(InstallSource install, int archiveId) {
        Path path = install.archivePath(archiveId);
        Assumptions.assumeTrue(path != null,
                "This release does not ship archive " + archiveId);
        return WarArchive.open(path, archiveId);
    }

    @Test
    @DisplayName("every sound decodes to a WAV whose header agrees with its length")
    void everySoundDecodesToAWellFormedWav() {
        WarArchive sounds = open(install(), ArchiveIds.SFXDAT);

        int wavCount = 0;
        for (int i = 0; i < sounds.entryCount(); i++) {
            if (!sounds.isValid(i)) {
                continue;
            }
            byte[] entry = sounds.entry(i);
            if (entry.length < 12 || !startsWithRiffWave(entry)) {
                continue;
            }
            wavCount++;
            int declared = readLe32(entry, 4);
            assertEquals(entry.length - 8, declared,
                    "entry " + i + ": RIFF header declares " + declared + " but the entry expanded to "
                            + (entry.length - 8) + " bytes of payload");
        }
        assertTrue(wavCount > 300, "expected the full sound bank, found " + wavCount + " WAV entries");
    }

    @Test
    @DisplayName("every entry expands to exactly its declared length")
    void everyEntryExpandsToItsDeclaredLength() {
        InstallSource install = install();
        int swept = 0;
        for (int archiveId : ArchiveIds.ALL) {
            Path path = install.archivePath(archiveId);
            if (path == null) {
                // A release ships some subset of the six. The DOS install has
                // no snddat.war at all.
                continue;
            }
            WarArchive war = WarArchive.open(path, archiveId);
            for (int i = 0; i < war.entryCount(); i++) {
                if (!war.isValid(i)) {
                    continue;
                }
                assertEquals(war.declaredLength(i), war.entry(i).length,
                        path.getFileName() + " entry " + i + " expanded to the wrong length");
                swept++;
            }
        }
        // An install where every archive resolved to null would pass the loop
        // above having decompressed nothing.
        int minimum = install.isBattleNetEdition() ? 800 : 1000;
        assertTrue(swept > minimum,
                "only " + swept + " entries expanded across the whole install");
    }

    @Test
    @DisplayName("the main archive holds the expected 6-bit VGA palettes")
    void mainArchiveHoldsSixBitPalettes() {
        WarArchive main = open(install(), ArchiveIds.MAINDAT);

        int palettes = 0;
        for (int i = 0; i < main.entryCount(); i++) {
            if (!main.isValid(i)) {
                continue;
            }
            byte[] entry = main.entry(i);
            if (entry.length != 768) {
                continue;
            }
            boolean sixBit = true;
            for (byte component : entry) {
                if ((component & 0xFF) > 0x3F) {
                    sixBit = false;
                    break;
                }
            }
            if (sixBit) {
                palettes++;
            }
        }
        // A 768-byte entry whose every byte fits in six bits is a 256-colour
        // VGA palette. Random data would essentially never qualify.
        assertTrue(palettes >= 10, "expected the tileset and interface palettes, found " + palettes);
    }

    @Test
    @DisplayName("archive headers match the ids wartool expects")
    void archiveHeadersDeclareTheExpectedIds() {
        InstallSource install = install();
        int checked = 0;
        for (int archiveId : ArchiveIds.ALL) {
            Path path = install.archivePath(archiveId);
            if (path == null) {
                continue;
            }
            assertEquals(archiveId, WarArchive.open(path, archiveId).id(),
                    path.getFileName() + " declares an id the format does not give it");
            checked++;
        }
        assertTrue(checked >= 4, "only " + checked + " archives were on this install");
    }

    private static boolean startsWithRiffWave(byte[] entry) {
        return entry[0] == 'R' && entry[1] == 'I' && entry[2] == 'F' && entry[3] == 'F'
                && entry[8] == 'W' && entry[9] == 'A' && entry[10] == 'V' && entry[11] == 'E';
    }

    private static int readLe32(byte[] data, int offset) {
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }
}
