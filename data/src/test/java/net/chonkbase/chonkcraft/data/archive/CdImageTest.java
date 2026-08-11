package net.chonkbase.chonkcraft.data.archive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The two sector layouts a launcher meets before it can see Warcraft II.
 *
 * <p>CloneCD and BIN/CUE store 2,352-byte raw sectors while ISO and Toast
 * commonly store the same filesystem as 2,048-byte cooked sectors. Both tests
 * start from a volume descriptor and take a file all the way back out, because
 * recognizing a signature without walking the directory is not a usable disc.
 */
class CdImageTest {

    @TempDir
    Path temporary;

    @Test
    @DisplayName("a cooked ISO data track yields its files")
    void aCookedImageIsRead() throws Exception {
        Path image = temporary.resolve("disc.iso");
        Files.write(image, cookedIso());

        try (CdImage disc = CdImage.open(image)) {
            assertNotNull(disc, "the cooked data track was rejected");
            CdImage.Entry entry = disc.findByName("MAINDAT.WAR");
            assertNotNull(entry, "the data directory vanished from the ISO");
            assertArrayEquals("game".getBytes(StandardCharsets.US_ASCII), disc.read(entry),
                    "the archive bytes changed while reading cooked sectors");
        }
    }

    @Test
    @DisplayName("a raw BIN data track yields the same files")
    void aRawImageIsRead() throws Exception {
        byte[] cooked = cookedIso();
        byte[] raw = new byte[cooked.length / 2048 * 2352];
        for (int sector = 0; sector < cooked.length / 2048; sector++) {
            int at = sector * 2352;
            raw[at] = 0;
            java.util.Arrays.fill(raw, at + 1, at + 11, (byte) 0xFF);
            raw[at + 11] = 0;
            raw[at + 15] = 1;
            System.arraycopy(cooked, sector * 2048, raw, at + 16, 2048);
        }
        Path image = temporary.resolve("disc.bin");
        Files.write(image, raw);

        try (CdImage disc = CdImage.open(image)) {
            assertNotNull(disc, "the raw data track was rejected");
            CdImage.Entry entry = disc.findByName("MAINDAT.WAR");
            assertNotNull(entry, "the archive vanished from the raw data track");
            assertArrayEquals("game".getBytes(StandardCharsets.US_ASCII), disc.read(entry),
                    "the archive bytes changed while removing raw-sector framing");
        }

        Path recooked = temporary.resolve("recooked.iso");
        assertTrue(CdImage.cookDataTrack(image, recooked, 24),
                "the raw framing was not recognized");
        assertArrayEquals(cooked, Files.readAllBytes(recooked),
                "cooking did not recover the original filesystem bytes");
    }

    private static byte[] cookedIso() {
        int sectorSize = 2048;
        byte[] image = new byte[24 * sectorSize];
        int descriptor = 16 * sectorSize;
        image[descriptor] = 1;
        put(image, descriptor + 1, "CD001");
        image[descriptor + 6] = 1;
        put(image, descriptor + 40, "CHONKCRAFT TEST");
        directoryRecord(image, descriptor + 156, 20, sectorSize, true, new byte[] {0});

        int root = 20 * sectorSize;
        int at = root;
        at += directoryRecord(image, at, 20, sectorSize, true, new byte[] {0});
        at += directoryRecord(image, at, 20, sectorSize, true, new byte[] {1});
        directoryRecord(image, at, 21, 4, false,
                "DATA/MAINDAT.WAR;1".getBytes(StandardCharsets.US_ASCII));
        put(image, 21 * sectorSize, "game");
        return image;
    }

    private static int directoryRecord(byte[] image, int at, int sector, int length,
            boolean directory, byte[] name) {
        int size = 33 + name.length + (name.length % 2 == 0 ? 1 : 0);
        image[at] = (byte) size;
        image[at + 1] = 0;
        le32(image, at + 2, sector);
        be32(image, at + 6, sector);
        le32(image, at + 10, length);
        be32(image, at + 14, length);
        image[at + 25] = (byte) (directory ? 2 : 0);
        image[at + 28] = 1;
        image[at + 31] = 1;
        image[at + 32] = (byte) name.length;
        System.arraycopy(name, 0, image, at + 33, name.length);
        return size;
    }

    private static void put(byte[] bytes, int at, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(encoded, 0, bytes, at, encoded.length);
    }

    private static void le32(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >>> 8);
        bytes[at + 2] = (byte) (value >>> 16);
        bytes[at + 3] = (byte) (value >>> 24);
    }

    private static void be32(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >>> 24);
        bytes[at + 1] = (byte) (value >>> 16);
        bytes[at + 2] = (byte) (value >>> 8);
        bytes[at + 3] = (byte) value;
    }
}
