package net.chonkbase.assetpack.codec;

import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import net.chonkbase.assetpack.PackFormatException;

/**
 * Writes and reads 8-bit palette-indexed PNGs, by hand.
 *
 * <p>Implements {@code SavePNG}, with a reader that
 * upstream has no equivalent of because upstream never loads its own output
 * back. The writer produces the same shape of file: colour type 3, bit depth 8,
 * a full 256-entry {@code PLTE}, an optional {@code tRNS}, and zlib at best
 * compression, which is what {@code png_set_compression_level(png_ptr,
 * Z_BEST_COMPRESSION)} asks libpng for.
 *
 * <p>Hand-rolled rather than handed to {@code javax.imageio} because the
 * palette index of a pixel is the thing being stored, not a detail of how a
 * colour got written down. Warcraft II's team colours are a remap of indices
 * 208 to 211, and its water, fire and the flashing minimap are index bands
 * cycled at draw time; an encoder that is free to quantise, reorder or drop
 * unused palette entries turns a red player blue and freezes every river,
 * silently, at build time, with a file that still looks right in a viewer.
 * ImageIO offers no way to forbid that, so the format is written out here where
 * the guarantee can be stated: what comes out of {@link #decode} is what went
 * into {@link #encode}, index for index.
 *
 * <p>This departs from upstream in one place, deliberately. When told an image
 * is transparent, {@code SavePNG} walks the pixels rewriting every index 0 to
 * index 255 and then declares 255 transparent, because LegacyEngine's loader wants
 * the transparent colour last. That is a lossy remap: a sprite that uses both
 * index 0 and index 255 comes out of upstream with the two merged into one
 * colour, and nothing reports it. Here the caller names the index that is
 * transparent and no pixel is touched, so index 0 stays 0 and index 255 stays
 * 255 whatever {@code tRNS} says about them.
 */
public final class IndexedPng {

    private IndexedPng() {
    }

    /** The eight bytes every PNG starts with. */
    private static final byte[] SIGNATURE = {
        (byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'
    };

    /** Colour type 3: every pixel is an index into {@code PLTE}. */
    private static final int COLOUR_TYPE_INDEXED = 3;

    /** 256 entries of red, green, blue. */
    private static final int PALETTE_BYTES = 768;

    /**
     * At eight bits or fewer per pixel a PNG filter's "previous pixel" is the
     * previous byte, so every depth this class reads filters the same way.
     */
    private static final int FILTER_UNIT_BYTES = 1;

    // ---------------------------------------------------------------- encode

    /**
     * Renders an indexed image as a PNG file.
     *
     * @param width            pixels across, at least one
     * @param height           pixels down, at least one
     * @param indices          row-major, one palette index per pixel
     * @param palette768       256 red, green, blue triples
     * @param transparentIndex the one index to declare fully transparent, or
     *                         -1 for an image with no transparency at all
     */
    public static byte[] encode(int width, int height, byte[] indices, byte[] palette768,
            int transparentIndex) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("bad image size " + width + "x" + height);
        }
        int pixels = Math.multiplyExact(width, height);
        if (indices.length != pixels) {
            throw new IllegalArgumentException("pixel count " + indices.length
                    + " does not match " + width + "x" + height);
        }
        if (palette768.length != PALETTE_BYTES) {
            throw new IllegalArgumentException("palette is " + palette768.length
                    + " bytes, not " + PALETTE_BYTES);
        }
        if (transparentIndex < -1 || transparentIndex > 255) {
            throw new IllegalArgumentException(
                    "transparent index " + transparentIndex + " is not an 8-bit index or -1");
        }

        byte[] compressed = smallerOf(deflate(filter(width, height, indices)),
                deflate(unfiltered(width, height, indices)));

        ByteArrayOutputStream out = new ByteArrayOutputStream(compressed.length + 1024);
        out.writeBytes(SIGNATURE);

        byte[] header = new byte[13];
        putInt(header, 0, width);
        putInt(header, 4, height);
        header[8] = 8;
        header[9] = COLOUR_TYPE_INDEXED;
        header[10] = 0;
        header[11] = 0;
        header[12] = 0;
        chunk(out, "IHDR", header);

        // All 256 entries, always, even when the picture uses four of them. A
        // paint program that reads a 4-entry PLTE renumbers the sprite to
        // 0..3 the moment an artist saves it, and the indices are the asset.
        chunk(out, "PLTE", palette768.clone());

        if (transparentIndex >= 0) {
            // tRNS runs from entry zero, so declaring index 200 transparent
            // costs 201 bytes of which 200 say "opaque". Writing only the
            // entries up to the transparent one is what leaves the rest
            // opaque: an entry PNG does not mention is fully opaque by
            // definition, which is exactly the one-transparent-index rule.
            byte[] alpha = new byte[transparentIndex + 1];
            Arrays.fill(alpha, (byte) 0xFF);
            alpha[transparentIndex] = 0;
            chunk(out, "tRNS", alpha);
        }

        chunk(out, "IDAT", compressed);
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    /**
     * Keeps whichever of the two candidate streams deflated smaller.
     *
     * <p>Filtering is not free on a palette image, and the file this returns is
     * what an artist downloads. Measured on the fixtures in the test suite: on
     * sprite art the unfiltered stream wins by 24 to 28 per cent (a 360x720
     * sheet of fifty frames is 17334 bytes unfiltered against 22678 filtered),
     * and on tiled terrain it wins by 2. On anything smooth the filter search
     * wins by far more (a 256x256 gradient is 349 bytes filtered against 1138,
     * and sixteen rows holding all 256 indices are 32 against 283). Neither
     * choice is right for both, and a pack holds both, so both are compressed
     * and the smaller kept.
     *
     * <p>The cost is one extra deflate per image at build time and nothing at
     * run time, which is the right way round: a pack is written once by a tool
     * nobody is waiting on and downloaded by everybody who plays.
     */
    private static byte[] smallerOf(byte[] filtered, byte[] plain) {
        return plain.length <= filtered.length ? plain : filtered;
    }

    /**
     * Filters the scanlines, choosing per row.
     *
     * <p>All five filters are tried and the one with the smallest sum of
     * absolute differences is kept, which is libpng's own heuristic. It is the
     * right one for a photograph and a guess for this data: the heuristic
     * assumes a small difference between two bytes means two similar pixels,
     * and in a palette image the bytes are names, not brightnesses. Index 208
     * next to index 12 is a team-coloured pixel beside a dark one and the
     * difference between them means nothing at all. That is why the caller
     * weighs the result against the unfiltered stream rather than trusting it.
     */
    private static byte[] filter(int width, int height, byte[] indices) {
        byte[] out = new byte[Math.multiplyExact(height, width + 1)];
        byte[] prior = new byte[width];
        byte[][] candidates = new byte[5][width];
        int at = 0;
        for (int y = 0; y < height; y++) {
            int row = y * width;
            for (int x = 0; x < width; x++) {
                int raw = indices[row + x] & 0xFF;
                int left = x > 0 ? indices[row + x - 1] & 0xFF : 0;
                int above = prior[x] & 0xFF;
                int aboveLeft = x > 0 ? prior[x - 1] & 0xFF : 0;
                candidates[0][x] = (byte) raw;
                candidates[1][x] = (byte) (raw - left);
                candidates[2][x] = (byte) (raw - above);
                candidates[3][x] = (byte) (raw - ((left + above) >> 1));
                candidates[4][x] = (byte) (raw - paeth(left, above, aboveLeft));
            }
            int best = 0;
            long bestScore = Long.MAX_VALUE;
            for (int type = 0; type < candidates.length; type++) {
                long score = 0;
                for (int x = 0; x < width; x++) {
                    score += Math.abs(candidates[type][x]);
                }
                if (score < bestScore) {
                    bestScore = score;
                    best = type;
                }
            }
            out[at++] = (byte) best;
            System.arraycopy(candidates[best], 0, out, at, width);
            at += width;
            System.arraycopy(indices, row, prior, 0, width);
        }
        return out;
    }

    /** The same scanlines with filter type 0 in front of each. */
    private static byte[] unfiltered(int width, int height, byte[] indices) {
        byte[] out = new byte[Math.multiplyExact(height, width + 1)];
        for (int y = 0; y < height; y++) {
            System.arraycopy(indices, y * width, out, y * (width + 1) + 1, width);
        }
        return out;
    }

    private static byte[] deflate(byte[] raw) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(raw);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, raw.length / 4));
            byte[] buffer = new byte[16384];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] length = new byte[4];
        putInt(length, 0, data.length);
        out.writeBytes(length);

        byte[] name = new byte[4];
        for (int i = 0; i < 4; i++) {
            name[i] = (byte) type.charAt(i);
        }
        out.writeBytes(name);
        out.writeBytes(data);

        CRC32 crc = new CRC32();
        crc.update(name);
        crc.update(data);
        byte[] checksum = new byte[4];
        putInt(checksum, 0, (int) crc.getValue());
        out.writeBytes(checksum);
    }

    // ---------------------------------------------------------------- decode

    /**
     * An indexed picture as it came off disc.
     *
     * <p>Carries no transparent index. {@code tRNS} in an incoming file is an
     * alpha table with one entry per palette entry, and a tool that writes a
     * soft edge writes several partial alphas into it; there is no single index
     * to hand back. What round-trips is what the game draws with, the indices
     * and the palette, and no value of {@code tRNS} can change either.
     */
    public record Image(int width, int height, byte[] indices, byte[] palette768) {

        public Image {
            if (indices.length != Math.multiplyExact(width, height)) {
                throw new IllegalArgumentException("pixel count " + indices.length
                        + " does not match " + width + "x" + height);
            }
            if (palette768.length != PALETTE_BYTES) {
                throw new IllegalArgumentException(
                        "palette is " + palette768.length + " bytes, not " + PALETTE_BYTES);
            }
        }
    }

    /**
     * Reads an indexed PNG, whoever wrote it.
     *
     * <p>Accepts what any paint program produces for an indexed image and not
     * only what {@link #encode} writes: several {@code IDAT} chunks, a
     * different filter on every scanline, a {@code PLTE} with fewer than 256
     * entries, a bit depth of 1, 2 or 4 where the palette is small, ancillary
     * chunks anywhere, and {@code tRNS} present or absent.
     */
    public static Image decode(byte[] png) {
        requireSignature(png);

        int width = 0;
        int height = 0;
        int bitDepth = 0;
        boolean header = false;
        byte[] palette = null;
        ByteArrayOutputStream data = new ByteArrayOutputStream();

        int at = SIGNATURE.length;
        while (true) {
            if (at + 12 > png.length) {
                throw new PackFormatException("this PNG ends after " + png.length
                        + " bytes with no IEND chunk, so it is truncated");
            }
            int length = readInt(png, at);
            String type = typeAt(png, at + 4);
            if (length < 0 || at + 12L + length > png.length) {
                throw new PackFormatException("chunk " + type + " at byte " + at + " claims "
                        + Integer.toUnsignedString(length) + " bytes, past the end of the file");
            }
            verifyChecksum(png, at, length, type);

            int body = at + 8;
            switch (type) {
                case "IHDR" -> {
                    if (length != 13) {
                        throw new PackFormatException(
                                "chunk IHDR is " + length + " bytes, not the 13 PNG defines");
                    }
                    width = readInt(png, body);
                    height = readInt(png, body + 4);
                    bitDepth = png[body + 8] & 0xFF;
                    int colourType = png[body + 9] & 0xFF;
                    requireIndexed(colourType, bitDepth);
                    requireUninterlaced(png[body + 10] & 0xFF, png[body + 11] & 0xFF,
                            png[body + 12] & 0xFF);
                    if (width <= 0 || height <= 0) {
                        throw new PackFormatException(
                                "this PNG declares a size of " + width + "x" + height);
                    }
                    header = true;
                }
                case "PLTE" -> {
                    if (length % 3 != 0 || length > PALETTE_BYTES) {
                        throw new PackFormatException("chunk PLTE is " + length
                                + " bytes, which is not up to 256 red-green-blue triples");
                    }
                    // Short palettes are padded with black rather than
                    // rejected. Paint programs trim PLTE to the colours in use,
                    // so a 12-colour sprite handed back has 12 entries and its
                    // indices are still 0 to 11: padding keeps the array the
                    // fixed 768 the rest of the pack assumes.
                    palette = new byte[PALETTE_BYTES];
                    System.arraycopy(png, body, palette, 0, length);
                }
                case "IDAT" -> data.write(png, body, length);
                case "IEND" -> {
                    return assemble(width, height, bitDepth, header, palette, data.toByteArray());
                }
                default -> {
                    // tRNS lands here with everything else this reader has no
                    // use for. Its alpha table cannot move a pixel from one
                    // palette entry to another, so dropping it cannot change
                    // what the game draws.
                }
            }
            at += 12 + length;
        }
    }

    private static void requireSignature(byte[] png) {
        if (png.length < SIGNATURE.length) {
            throw new PackFormatException(
                    "this file is " + png.length + " bytes, too short to be a PNG");
        }
        for (int i = 0; i < SIGNATURE.length; i++) {
            if (png[i] != SIGNATURE[i]) {
                throw new PackFormatException(
                        "this file does not start with the eight-byte PNG signature");
            }
        }
    }

    /**
     * Rejects anything that is not colour type 3, saying which type it is.
     *
     * <p>The expected mistake, by a wide margin, is an artist opening a sprite,
     * editing it and exporting 32-bit RGBA, because that is what every paint
     * program does by default. The file is a perfectly good PNG and every pixel
     * of it is wrong for this pack, so the message has to say what happened
     * rather than let a converter guess indices back out of the colours.
     */
    private static void requireIndexed(int colourType, int bitDepth) {
        if (colourType != COLOUR_TYPE_INDEXED) {
            throw new PackFormatException("this PNG is colour type " + colourType + " ("
                    + colourTypeName(colourType) + "); an asset pack picture must be colour type "
                    + COLOUR_TYPE_INDEXED + " (palette-indexed), because the game draws from the"
                    + " palette index of each pixel and not from its colour. Re-export it as an"
                    + " indexed PNG against the pack's palette.");
        }
        if (bitDepth != 1 && bitDepth != 2 && bitDepth != 4 && bitDepth != 8) {
            throw new PackFormatException("this PNG is " + bitDepth
                    + " bits per pixel; a palette-indexed PNG is 1, 2, 4 or 8");
        }
    }

    private static void requireUninterlaced(int compression, int filterMethod, int interlace) {
        if (compression != 0) {
            throw new PackFormatException("this PNG uses compression method " + compression
                    + "; PNG defines only 0, deflate");
        }
        if (filterMethod != 0) {
            throw new PackFormatException("this PNG uses filter method " + filterMethod
                    + "; PNG defines only 0, adaptive filtering");
        }
        if (interlace != 0) {
            throw new PackFormatException("this PNG is interlaced (Adam7); save it without"
                    + " interlacing, which is the default in every editor");
        }
    }

    private static String colourTypeName(int colourType) {
        return switch (colourType) {
            case 0 -> "greyscale";
            case 2 -> "truecolour";
            case 3 -> "palette-indexed";
            case 4 -> "greyscale with alpha";
            case 6 -> "truecolour with alpha";
            default -> "not a colour type PNG defines";
        };
    }

    /**
     * Checks a chunk against its stored CRC, naming it if it fails.
     *
     * <p>Every chunk, on every read, before its contents are looked at. A pack
     * is a zip an artist unpacks, edits inside and zips again, and a truncated
     * copy or a file mangled by a text-mode transfer decompresses into noise
     * that reads as a valid picture of garbage. Naming the chunk turns "the
     * pack is broken" into a sentence someone can act on.
     */
    private static void verifyChecksum(byte[] png, int at, int length, String type) {
        CRC32 crc = new CRC32();
        crc.update(png, at + 4, 4 + length);
        long computed = crc.getValue();
        long stored = readInt(png, at + 8 + length) & 0xFFFFFFFFL;
        if (computed != stored) {
            throw new PackFormatException("chunk " + type + " at byte " + at + " is corrupt: its"
                    + " CRC is " + hex(stored) + " and its contents check to " + hex(computed));
        }
    }

    private static String hex(long value) {
        return "0x" + Long.toHexString(0x100000000L | value & 0xFFFFFFFFL).substring(1);
    }

    private static Image assemble(int width, int height, int bitDepth, boolean header,
            byte[] palette, byte[] compressed) {
        if (!header) {
            throw new PackFormatException("this PNG has no IHDR chunk");
        }
        if (palette == null) {
            throw new PackFormatException(
                    "this PNG is colour type 3 but carries no PLTE chunk, so it has no palette");
        }
        if (compressed.length == 0) {
            throw new PackFormatException("this PNG has no IDAT chunk, so it has no pixels");
        }
        int bytesPerRow = (int) (((long) width * bitDepth + 7) / 8);
        // Both totals, in long, before either is used as a length. IHDR is four
        // bytes of width and four of height and nothing else in the file has to
        // agree with them, so a header saying 65535 by 65535 asks for four
        // gigabytes before a single pixel has been read.
        //
        // Checking only the scanline total is what this used to do, and it is
        // not the same check. Below eight bits per pixel a row is a fraction of
        // its width: a 65536x32769 file at one bit per pixel declares 268476417
        // bytes of scanline, which fits in an int, and 2147549184 pixels, which
        // does not. Such a file is 261 kilobytes with every checksum in it
        // correct, so the CRC gate never fires, and it reached the widening
        // step and came out of this class as a bare ArithmeticException with a
        // stack trace where the pack layer was promised a sentence.
        long pixels = (long) width * height;
        long expected = (long) height * (bytesPerRow + 1);
        if (pixels > Integer.MAX_VALUE || expected > Integer.MAX_VALUE) {
            throw new PackFormatException("this PNG declares a size of " + width + "x" + height
                    + ", which is " + pixels + " pixels in " + expected + " bytes of scanline,"
                    + " and larger than anything a pack holds");
        }
        byte[] rows = unfilter(width, height, bytesPerRow,
                inflate(compressed, (int) expected, width, height));
        return new Image(width, height, expand(width, height, bitDepth, bytesPerRow, rows),
                palette);
    }

    /**
     * Unpacks the pixel data, growing the buffer as the pixels actually arrive.
     *
     * <p>Deliberately not {@code new byte[expected]}. That number came out of
     * IHDR and the file does not have to live up to it: an 848-byte file whose
     * header claims 20000x20000, with every checksum in it correct so the CRC
     * gate never fires, asks for 400 megabytes before a byte of pixel data has
     * been read, and on a 256 megabyte heap that is an OutOfMemoryError and a
     * stack trace where the pack layer was promised a sentence. A pack is a zip
     * downloaded from somewhere, so the header is not evidence.
     *
     * <p>Growing from 64 kilobytes costs array copies, and on the pictures a
     * pack actually holds it costs almost nothing: the largest sheet measured
     * for this codec, a 360x720 sprite sheet, is 259920 bytes of scanline and
     * reaches full size in two copies of 64 and 128 kilobytes. Every picture
     * smaller than 64 kilobytes is allocated once at its exact size and copied
     * never, because the first allocation is capped at what the header claims.
     */
    private static byte[] inflate(byte[] compressed, int expected, int width, int height) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(compressed);
            byte[] out = new byte[Math.min(expected, 1 << 16)];
            int at = 0;
            while (at < expected) {
                if (at == out.length) {
                    out = Arrays.copyOf(out, (int) Math.min(expected, out.length * 2L));
                }
                int written = inflater.inflate(out, at, out.length - at);
                if (written == 0) {
                    break;
                }
                at += written;
            }
            if (at != expected) {
                throw new PackFormatException("the image data unpacked to " + at + " bytes"
                        + " where a " + width + "x" + height + " image needs " + expected
                        + ", so it is truncated");
            }
            return out;
        } catch (DataFormatException e) {
            throw new PackFormatException(
                    "the image data will not decompress: " + e.getMessage(), e);
        } finally {
            inflater.end();
        }
    }

    /**
     * Undoes the per-scanline filter, whichever one each row chose.
     *
     * <p>A row's filter is chosen by whoever wrote the file and nothing says
     * the choice is consistent, so all five are handled on every row. A reader
     * that only understood the filters this encoder emits would open its own
     * files and fail on the artist's.
     */
    private static byte[] unfilter(int width, int height, int bytesPerRow, byte[] raw) {
        byte[] rows = new byte[Math.multiplyExact(height, bytesPerRow)];
        byte[] prior = new byte[bytesPerRow];
        int at = 0;
        for (int y = 0; y < height; y++) {
            int filterType = raw[at++] & 0xFF;
            int row = y * bytesPerRow;
            System.arraycopy(raw, at, rows, row, bytesPerRow);
            at += bytesPerRow;
            switch (filterType) {
                case 0 -> {
                }
                case 1 -> {
                    for (int x = FILTER_UNIT_BYTES; x < bytesPerRow; x++) {
                        rows[row + x] += rows[row + x - FILTER_UNIT_BYTES];
                    }
                }
                case 2 -> {
                    for (int x = 0; x < bytesPerRow; x++) {
                        rows[row + x] += prior[x];
                    }
                }
                case 3 -> {
                    for (int x = 0; x < bytesPerRow; x++) {
                        int left = x >= FILTER_UNIT_BYTES
                                ? rows[row + x - FILTER_UNIT_BYTES] & 0xFF : 0;
                        rows[row + x] += (byte) ((left + (prior[x] & 0xFF)) >> 1);
                    }
                }
                case 4 -> {
                    for (int x = 0; x < bytesPerRow; x++) {
                        int left = x >= FILTER_UNIT_BYTES
                                ? rows[row + x - FILTER_UNIT_BYTES] & 0xFF : 0;
                        int aboveLeft = x >= FILTER_UNIT_BYTES
                                ? prior[x - FILTER_UNIT_BYTES] & 0xFF : 0;
                        rows[row + x] += (byte) paeth(left, prior[x] & 0xFF, aboveLeft);
                    }
                }
                default -> throw new PackFormatException("scanline " + y + " of this PNG uses"
                        + " filter type " + filterType + ", and PNG defines only 0 to 4");
            }
            System.arraycopy(rows, row, prior, 0, bytesPerRow);
        }
        return rows;
    }

    /**
     * Spreads packed sub-byte pixels out to one index per byte.
     *
     * <p>An editor handed a sprite with sixteen colours in it will often write
     * it back at four bits a pixel, which is a smaller file holding the same
     * indices. Widening them here means a small-palette sprite survives that
     * round trip instead of being turned away at the door.
     */
    private static byte[] expand(int width, int height, int bitDepth, int bytesPerRow,
            byte[] rows) {
        if (bitDepth == 8) {
            return rows;
        }
        byte[] indices = new byte[Math.multiplyExact(width, height)];
        int perByte = 8 / bitDepth;
        int mask = (1 << bitDepth) - 1;
        for (int y = 0; y < height; y++) {
            int row = y * bytesPerRow;
            int out = y * width;
            for (int x = 0; x < width; x++) {
                int packed = rows[row + x / perByte] & 0xFF;
                int shift = 8 - bitDepth * (x % perByte + 1);
                indices[out + x] = (byte) (packed >> shift & mask);
            }
        }
        return indices;
    }

    // ----------------------------------------------------------------- bytes

    private static int paeth(int left, int above, int aboveLeft) {
        int estimate = left + above - aboveLeft;
        int fromLeft = Math.abs(estimate - left);
        int fromAbove = Math.abs(estimate - above);
        int fromAboveLeft = Math.abs(estimate - aboveLeft);
        if (fromLeft <= fromAbove && fromLeft <= fromAboveLeft) {
            return left;
        }
        return fromAbove <= fromAboveLeft ? above : aboveLeft;
    }

    private static void putInt(byte[] out, int at, int value) {
        out[at] = (byte) (value >>> 24);
        out[at + 1] = (byte) (value >>> 16);
        out[at + 2] = (byte) (value >>> 8);
        out[at + 3] = (byte) value;
    }

    private static int readInt(byte[] in, int at) {
        return (in[at] & 0xFF) << 24 | (in[at + 1] & 0xFF) << 16
                | (in[at + 2] & 0xFF) << 8 | in[at + 3] & 0xFF;
    }

    private static String typeAt(byte[] in, int at) {
        char[] name = new char[4];
        for (int i = 0; i < 4; i++) {
            int c = in[at + i] & 0xFF;
            // A chunk type is four ASCII letters. Anything else is a file that
            // has gone wrong, and printing it raw puts control bytes in the
            // message that names the chunk.
            name[i] = c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' ? (char) c : '?';
        }
        return new String(name);
    }
}
