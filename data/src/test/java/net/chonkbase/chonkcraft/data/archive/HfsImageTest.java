package net.chonkbase.chonkcraft.data.archive;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A Macintosh Warcraft II data track reaches the same installation files.
 *
 * <p>The fixture contains a real partition map, master directory block,
 * catalog B-tree, folder and file data fork. Checking only the HFS signature
 * would have passed while the Japanese disc still opened as an empty folder.
 */
class HfsImageTest {

    @TempDir
    Path temporary;

    @Test
    @DisplayName("a classic HFS catalog yields the game archive data fork")
    void aClassicCatalogIsRead() throws Exception {
        Path image = temporary.resolve("mac.iso");
        Files.write(image, hfsImage());
        Path output = temporary.resolve("out");

        try (HfsImage hfs = HfsImage.open(image)) {
            assertNotNull(hfs, "the classic HFS volume was rejected");
            assertEquals("Warcraft II", hfs.volumeName(), "the disc name was misread");
            hfs.extractAll(output);
        }

        assertArrayEquals("game".getBytes(StandardCharsets.US_ASCII),
                Files.readAllBytes(output.resolve("DATA/MAINDAT.WAR")),
                "the archive data fork changed while traversing the catalog");
    }

    @Test
    @DisplayName("Japanese HFS names are decoded without MacRoman mojibake")
    void aJapaneseVolumeNameIsDecoded() {
        byte[] encoded = java.util.HexFormat.of().parseHex(
                "83458348815b834e8389837483674949204344");

        assertEquals("ウォークラフトII CD",
                HfsImage.macName(encoded, 0, encoded.length));
    }

    private static byte[] hfsImage() {
        byte[] image = new byte[100 * 512];
        int partition = 512;
        be16(image, partition, 0x504D);
        be32(image, partition + 4, 1);
        be32(image, partition + 8, 4);
        be32(image, partition + 12, 96);
        cString(image, partition + 16, "Warcraft II", 32);
        cString(image, partition + 48, "Apple_HFS", 32);

        int volume = 4 * 512;
        int mdb = volume + 1024;
        be16(image, mdb, 0x4244);
        be32(image, mdb + 20, 512);
        be16(image, mdb + 28, 4);
        pascal(image, mdb + 36, "Warcraft II");
        be32(image, mdb + 146, 1024);
        be16(image, mdb + 150, 0);
        be16(image, mdb + 152, 2);

        int allocation = volume + 4 * 512;
        int header = allocation;
        be32(image, header + 24, 1);
        be16(image, header + 32, 512);

        int leaf = allocation + 512;
        image[leaf + 8] = (byte) 0xFF;
        be16(image, leaf + 10, 2);
        int folder = leaf + 14;
        int folderLength = catalogKey(image, folder, 2, "DATA");
        int folderData = folder + folderLength;
        image[folderData] = 1;
        be32(image, folderData + 6, 10);

        int file = folderData + 16;
        int fileLength = catalogKey(image, file, 10, "MAINDAT.WAR");
        int fileData = file + fileLength;
        image[fileData] = 2;
        be32(image, fileData + 26, 4);
        be16(image, fileData + 74, 2);
        be16(image, fileData + 76, 1);
        be16(image, leaf + 510, folder - leaf);
        be16(image, leaf + 508, file - leaf);

        put(image, allocation + 2 * 512, "game");
        return image;
    }

    private static int catalogKey(byte[] image, int at, int parent, String name) {
        byte[] encoded = name.getBytes(StandardCharsets.US_ASCII);
        int keyLength = 6 + encoded.length;
        image[at] = (byte) keyLength;
        image[at + 1] = 0;
        be32(image, at + 2, parent);
        image[at + 6] = (byte) encoded.length;
        System.arraycopy(encoded, 0, image, at + 7, encoded.length);
        return (keyLength + 2) & ~1;
    }

    private static void pascal(byte[] bytes, int at, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        bytes[at] = (byte) encoded.length;
        System.arraycopy(encoded, 0, bytes, at + 1, encoded.length);
    }

    private static void cString(byte[] bytes, int at, String value, int length) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(encoded, 0, bytes, at, Math.min(encoded.length, length));
    }

    private static void put(byte[] bytes, int at, String value) {
        byte[] encoded = value.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(encoded, 0, bytes, at, encoded.length);
    }

    private static void be16(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >>> 8);
        bytes[at + 1] = (byte) value;
    }

    private static void be32(byte[] bytes, int at, int value) {
        bytes[at] = (byte) (value >>> 24);
        bytes[at + 1] = (byte) (value >>> 16);
        bytes[at + 2] = (byte) (value >>> 8);
        bytes[at + 3] = (byte) value;
    }
}
