package net.chonkbase.chonkcraft.data.video;

import java.nio.charset.StandardCharsets;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.Palette;

/**
 * Decodes Warcraft II's Smacker cutscenes.
 *
 * <p>The videos live in {@code muddat.cud} on the CD as SMK2 files, RAD Game
 * Tools' format from 1994. ChonkCraft does not decode them: {@code ConvertVideo}
 * extracts the raw {@code .smk} and shells out to {@code ffmpeg2theora}, which
 * is why a ChonkCraft installation carries {@code .ogv} files and a Warcraft II CD
 * does not. This implementation decodes the original instead, so nothing outside the JVM
 * has to be installed for a cutscene to play.
 *
 * <p>The format is four Huffman trees and a stream of 4-by-4 blocks. The trees
 * are built once from the file header and reused for every frame, which is
 * what makes the format small and what makes it awkward: they are 16-bit
 * trees, built out of a pair of 8-bit trees, with a move-to-front cache of the
 * three most recent values held inside the tree's own value array. The cache
 * is the part that cannot be simplified away. Three leaves are reserved for
 * it, and every decoded symbol rewrites them, so a decoder that treats the
 * tree as immutable produces plausible garbage rather than an obvious failure.
 */
public final class SmackerVideo {

    /** Marks an entry in a tree array as a branch rather than a leaf. */
    private static final int NODE = 0x8000_0000;

    /** How many blocks a run of each length code covers. */
    private static final int[] BLOCK_RUNS = {
        1, 2, 3, 4, 5, 6, 7, 8,
        9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24,
        25, 26, 27, 28, 29, 30, 31, 32,
        33, 34, 35, 36, 37, 38, 39, 40,
        41, 42, 43, 44, 45, 46, 47, 48,
        49, 50, 51, 52, 53, 54, 55, 56,
        57, 58, 59, 128, 256, 512, 1024, 2048
    };

    /**
     * Six-bit palette components expanded to eight.
     *
     * <p>Not a shift. The table is the one the format defines, and it maps 63
     * to 255 rather than to 252, so white stays white.
     */
    private static final int[] PALETTE_LEVELS = {
        0x00, 0x04, 0x08, 0x0C, 0x10, 0x14, 0x18, 0x1C,
        0x20, 0x24, 0x28, 0x2C, 0x30, 0x34, 0x38, 0x3C,
        0x41, 0x45, 0x49, 0x4D, 0x51, 0x55, 0x59, 0x5D,
        0x61, 0x65, 0x69, 0x6D, 0x71, 0x75, 0x79, 0x7D,
        0x82, 0x86, 0x8A, 0x8E, 0x92, 0x96, 0x9A, 0x9E,
        0xA2, 0xA6, 0xAA, 0xAE, 0xB2, 0xB6, 0xBA, 0xBE,
        0xC3, 0xC7, 0xCB, 0xCF, 0xD3, 0xD7, 0xDB, 0xDF,
        0xE3, 0xE7, 0xEB, 0xEF, 0xF3, 0xF7, 0xFB, 0xFF
    };

    /** Thrown when the bytes are not a Smacker file this can read. */
    public static final class NotSmackerException extends RuntimeException {
        NotSmackerException(String message) {
            super(message);
        }
    }

    private final byte[] data;
    private final int width;
    private final int height;
    private final int frameCount;
    private final int frameRate;

    /** Per frame: how long its chunk is, and what it contains. */
    private final int[] frameSizes;
    private final int[] frameFlags;
    private final int[] frameOffsets;

    /** The four trees, as flat arrays with {@link #NODE} marking branches. */
    private int[] mmapTree;
    private int[] mclrTree;
    private int[] fullTree;
    private int[] typeTree;

    /** The three move-to-front slots in each tree. */
    private final int[] mmapLast = new int[3];
    private final int[] mclrLast = new int[3];
    private final int[] fullLast = new int[3];
    private final int[] typeLast = new int[3];

    /** The picture, carried between frames because most frames are deltas. */
    private final byte[] picture;

    /** The trees as built, for restoring the caches between frames. */
    private int[] mmapFresh;
    private int[] mclrFresh;
    private int[] fullFresh;
    private int[] typeFresh;

    /** The current palette, likewise. */
    private final byte[] palette = new byte[768];

    /** Whether this is SMK4, which has extra block modes. */
    private final boolean version4;

    /** How many audio tracks a Smacker file may carry. */
    private static final int AUDIO_TRACKS = 7;

    /**
     * Each track's descriptor word.
     *
     * <p>The low twenty-four bits are the sample rate; the top bits say
     * whether the track is present, compressed, sixteen bit and stereo.
     */
    private final int[] audioRate = new int[AUDIO_TRACKS];

    private static final int AUDIO_COMPRESSED = 0x8000_0000;
    private static final int AUDIO_PRESENT = 0x4000_0000;
    private static final int AUDIO_SIXTEEN_BIT = 0x2000_0000;
    private static final int AUDIO_STEREO = 0x1000_0000;
    private static final int AUDIO_BINK = 0x0800_0000;
    private static final int AUDIO_RATE_MASK = 0x00FF_FFFF;

    private SmackerVideo(byte[] data) {
        this.data = data;
        if (data.length < 104) {
            throw new NotSmackerException("too short to be a Smacker file");
        }
        String signature = new String(data, 0, 4, StandardCharsets.ISO_8859_1);
        if (!"SMK2".equals(signature) && !"SMK4".equals(signature)) {
            throw new NotSmackerException("not a Smacker file: " + signature);
        }
        version4 = "SMK4".equals(signature);

        width = readInt(data, 4);
        height = readInt(data, 8);
        frameCount = readInt(data, 12);
        frameRate = readInt(data, 16);

        if (width <= 0 || height <= 0 || width > 4096 || height > 4096) {
            throw new NotSmackerException("implausible size " + width + "x" + height);
        }
        if (frameCount <= 0 || frameCount > 100_000) {
            throw new NotSmackerException("implausible frame count " + frameCount);
        }

        // Seven audio tracks, each described by a word of flags and a rate.
        for (int track = 0; track < AUDIO_TRACKS; track++) {
            audioRate[track] = readInt(data, 72 + track * 4);
        }

        int treesSize = readInt(data, 52);
        int mmapSize = readInt(data, 56);
        int mclrSize = readInt(data, 60);
        int fullSize = readInt(data, 64);
        int typeSize = readInt(data, 68);

        frameSizes = new int[frameCount];
        frameFlags = new int[frameCount];
        frameOffsets = new int[frameCount];

        int cursor = 104;
        for (int i = 0; i < frameCount; i++) {
            frameSizes[i] = readInt(data, cursor + i * 4);
        }
        cursor += frameCount * 4;
        for (int i = 0; i < frameCount; i++) {
            frameFlags[i] = data[cursor + i] & 0xFF;
        }
        cursor += frameCount;

        // The trees come next, then the frames back to back. Built on a
        // thread with a deep stack: the trees run to tens of thousands of
        // entries and the reader follows their depth, which the default stack
        // does not have room for.
        BitReader bits = new BitReader(data, cursor, treesSize);
        Thread builder = new Thread(null, () -> {
            mmapTree = buildTree(bits, mmapSize, mmapLast);
            mclrTree = buildTree(bits, mclrSize, mclrLast);
            fullTree = buildTree(bits, fullSize, fullLast);
            typeTree = buildTree(bits, typeSize, typeLast);
        }, "smacker-trees", 256L * 1024 * 1024);
        builder.start();
        try {
            builder.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new NotSmackerException("interrupted while reading the trees");
        }

        int frameStart = cursor + treesSize;
        for (int i = 0; i < frameCount; i++) {
            frameOffsets[i] = frameStart;
            // The bottom two bits are flags, not length.
            frameStart += frameSizes[i] & ~3;
        }

        picture = new byte[width * height];
        mmapFresh = mmapTree.clone();
        mclrFresh = mclrTree.clone();
        fullFresh = fullTree.clone();
        typeFresh = typeTree.clone();
    }

    /**
     * Puts the trees back as they were built.
     *
     * <p>The trees themselves are built once from the file header and last
     * the whole video; it is only the three move-to-front slots inside them
     * that are per-frame. Getting this backwards does not fail: it decodes
     * dozens of frames exactly and then produces a frame of noise.
     */
    private void restoreTrees() {
        System.arraycopy(mmapFresh, 0, mmapTree, 0, mmapTree.length);
        System.arraycopy(mclrFresh, 0, mclrTree, 0, mclrTree.length);
        System.arraycopy(fullFresh, 0, fullTree, 0, fullTree.length);
        System.arraycopy(typeFresh, 0, typeTree, 0, typeTree.length);
    }

    /** Reads a Smacker file. */
    public static SmackerVideo read(byte[] data) {
        return new SmackerVideo(data);
    }

    /** Whether some bytes look like one, without throwing if they do not. */
    public static boolean looksLikeSmacker(byte[] data) {
        if (data == null || data.length < 104) {
            return false;
        }
        String signature = new String(data, 0, 4, StandardCharsets.ISO_8859_1);
        return "SMK2".equals(signature) || "SMK4".equals(signature);
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int frameCount() {
        return frameCount;
    }

    /**
     * How long a frame lasts, in microseconds.
     *
     * <p>Positive means milliseconds. Negative means hundred-thousandths of a
     * second, which is to say ten microseconds a unit, not one: Warcraft II's
     * cutscenes carry -8333, and reading that as microseconds gives 120 frames
     * a second where the truth is twelve. Zero means the format's default of
     * ten frames a second.
     */
    public int frameMicros() {
        if (frameRate > 0) {
            return frameRate * 1000;
        }
        if (frameRate < 0) {
            return -frameRate * 10;
        }
        return 100_000;
    }

    /** The same, rounded to milliseconds. */
    public int frameMillis() {
        return Math.max(1, (frameMicros() + 500) / 1000);
    }

    /** The palette after the most recently decoded frame. */
    public Palette palette() {
        return Palette.fromRgb(palette);
    }

    /**
     * Decodes one frame into the running picture.
     *
     * <p>Frames must be decoded in order. Most are deltas against the one
     * before, so asking for frame fifty without the forty-nine ahead of it
     * gives a picture assembled from whatever happened to be in the buffer.
     */
    public IndexedImage decodeFrame(int index) {
        if (index < 0 || index >= frameCount) {
            throw new IndexOutOfBoundsException("no frame " + index);
        }
        int offset = frameOffsets[index];
        int length = frameSizes[index] & ~3;
        int end = Math.min(data.length, offset + length);
        int cursor = offset;

        // A palette change, when the frame carries one.
        if ((frameFlags[index] & 1) != 0 && cursor < end) {
            int chunk = (data[cursor] & 0xFF) * 4;
            decodePalette(cursor + 1, Math.min(end, cursor + chunk));
            cursor += chunk;
        }
        // Audio tracks, each with its own length. Skipped here: the sound is
        // decoded separately, and the video has to step over it either way.
        for (int track = 0; track < 7; track++) {
            if ((frameFlags[index] & (2 << track)) == 0) {
                continue;
            }
            if (cursor + 4 > end) {
                break;
            }
            int chunk = readInt(data, cursor);
            cursor += Math.max(4, chunk);
        }

        if (cursor < end) {
            // The caches start again at every frame. Carried instead, the
            // first 38 frames of a cutscene still decode pixel-perfectly and
            // the 39th falls apart: the two readings agree for as long as no
            // frame ends with the cache in a state the next one reads from,
            // which is a long while and then never again.
            restoreTrees();
            decodeVideo(cursor, end);
        }
        IndexedImage image = new IndexedImage(width, height);
        System.arraycopy(picture, 0, image.pixels(), 0, picture.length);
        return image;
    }

    /**
     * A decoded soundtrack.
     *
     * @param sampleRate frames a second, as the file declares
     * @param channels   one or two
     * @param samples    interleaved signed sixteen-bit, whatever the file's
     *                   own depth was
     */
    public record Audio(int sampleRate, int channels, short[] samples) {}

    /** Whether a track carries sound this can decode. */
    public boolean hasAudio(int track) {
        if (track < 0 || track >= AUDIO_TRACKS) {
            return false;
        }
        int flags = audioRate[track];
        // Bink-compressed audio is a different codec that turned up in later
        // Smacker files; none of Warcraft II's use it.
        return (flags & AUDIO_PRESENT) != 0 && (flags & AUDIO_BINK) == 0;
    }

    /** The stored depth of a decodable audio track, or zero when it is absent. */
    public int audioBitsPerSample(int track) {
        if (!hasAudio(track)) {
            return 0;
        }
        return (audioRate[track] & AUDIO_SIXTEEN_BIT) != 0 ? 16 : 8;
    }

    /**
     * Decodes a whole soundtrack.
     *
     * <p>The sound is not a block at the end of the file: it is cut into one
     * chunk per frame and interleaved with the pictures, which is what let a
     * 1995 machine play a cutscene off a CD. So decoding it means walking every
     * frame, stepping over the palette and any earlier tracks, and stitching
     * the pieces back together.
     *
     * <p>Each chunk is differentially coded and Huffman compressed, one tree
     * per byte of each channel: eight-bit mono has one tree, sixteen-bit
     * stereo has four. The first sample of a chunk is stored outright and
     * every one after it is a delta from the one before.
     *
     * @return the track, or null if it is absent or of a kind this cannot read
     */
    public Audio decodeAudio(int track) {
        if (!hasAudio(track)) {
            return null;
        }
        int flags = audioRate[track];
        int rate = flags & AUDIO_RATE_MASK;
        int channels = (flags & AUDIO_STEREO) != 0 ? 2 : 1;
        boolean sixteenBit = (flags & AUDIO_SIXTEEN_BIT) != 0;
        if (rate <= 0) {
            return null;
        }

        java.io.ByteArrayOutputStream collected = new java.io.ByteArrayOutputStream();
        for (int index = 0; index < frameCount; index++) {
            int offset = frameOffsets[index];
            int end = Math.min(data.length, offset + (frameSizes[index] & ~3));
            int cursor = offset;

            if ((frameFlags[index] & 1) != 0 && cursor < end) {
                cursor += (data[cursor] & 0xFF) * 4;
            }
            for (int other = 0; other < AUDIO_TRACKS; other++) {
                if ((frameFlags[index] & (2 << other)) == 0) {
                    continue;
                }
                if (cursor + 4 > end) {
                    break;
                }
                int chunk = Math.max(4, readInt(data, cursor));
                if (other == track) {
                    decodeAudioChunk(cursor, Math.min(end, cursor + chunk),
                            flags, channels, sixteenBit, collected);
                }
                cursor += chunk;
            }
        }

        byte[] raw = collected.toByteArray();
        if (raw.length == 0) {
            return null;
        }
        return new Audio(rate, channels, toSigned16(raw, sixteenBit));
    }

    /**
     * One frame's worth of one track.
     *
     * <p>The chunk opens with its own length and then the unpacked length,
     * both four bytes. After that the bitstream repeats what the header
     * already said -- compressed, stereo, sixteen bit -- and those repeated
     * flags are the ones that govern, because a track can carry an
     * uncompressed chunk in an otherwise compressed stream.
     */
    private void decodeAudioChunk(int from, int to, int headerFlags, int headerChannels,
            boolean headerSixteen, java.io.ByteArrayOutputStream out) {
        if (from + 8 > to) {
            return;
        }
        int unpacked = readInt(data, from + 4);
        if (unpacked <= 0) {
            return;
        }
        if ((headerFlags & AUDIO_COMPRESSED) == 0) {
            // Stored outright.
            int available = Math.min(unpacked, to - (from + 8));
            out.write(data, from + 8, Math.max(0, available));
            return;
        }

        BitReader bits = new BitReader(data, from + 8, to - (from + 8));
        if (!bits.bit()) {
            // The chunk says it is not compressed after all; the rest is the
            // samples as they stand.
            int consumed = bits.consumedBytes() + 1;
            int available = Math.min(unpacked, to - (from + 8 + consumed));
            out.write(data, from + 8 + consumed, Math.max(0, available));
            return;
        }
        boolean stereo = bits.bit();
        boolean sixteen = bits.bit();
        int channels = stereo ? 2 : 1;

        // One tree per byte of each channel.
        int treeCount = 1 << ((sixteen ? 1 : 0) + (stereo ? 1 : 0));
        Small[] trees = new Small[treeCount];
        for (int i = 0; i < treeCount; i++) {
            trees[i] = bits.bit() ? readSmall(bits) : null;
            bits.bit();
        }

        if (sixteen) {
            int[] predictor = new int[2];
            for (int channel = channels - 1; channel >= 0; channel--) {
                // The bitstream is little-bit-first, but this one word is a
                // big-endian sample. The 1995 movies are eight-bit mono and
                // never reach this branch; Battle.net Edition's replacement
                // movies are sixteen-bit stereo, so leaving the word as read
                // turns a quiet sample such as 10 into 2560 and makes the
                // whole chunk crackle around the wrong predictor. Smacker's
                // reference implementations byte-swap this initial sample;
                // the following Huffman deltas remain low byte, high byte.
                predictor[channel] = Short.reverseBytes((short) bits.bits(16));
            }
            for (int channel = 0; channel < channels; channel++) {
                writeShort(out, (short) predictor[channel]);
            }
            int samples = unpacked / 2;
            for (int i = channels; i < samples && !bits.exhausted(); i++) {
                int base = 2 * (stereo ? (i & 1) : 0);
                int low = decodeSmall(bits, trees[base]);
                int high = decodeSmall(bits, trees[base + 1]);
                int delta = (low & 0xFF) | ((high & 0xFF) << 8);
                int channel = stereo ? (i & 1) : 0;
                predictor[channel] = (short) (predictor[channel] + (short) delta);
                writeShort(out, (short) predictor[channel]);
            }
        } else {
            int[] predictor = new int[2];
            for (int channel = channels - 1; channel >= 0; channel--) {
                predictor[channel] = bits.bits(8);
            }
            for (int channel = 0; channel < channels; channel++) {
                out.write(predictor[channel] & 0xFF);
            }
            for (int i = channels; i < unpacked && !bits.exhausted(); i++) {
                int channel = stereo ? (i & 1) : 0;
                int delta = decodeSmall(bits, trees[channel]);
                // The delta is signed and the running value is a byte that
                // wraps. It does not saturate: a passage loud enough to run
                // off the top comes back round the bottom, and that wrap is
                // audible in the original. Clamping instead pins it at the
                // rail and every sample after it in that passage is wrong.
                predictor[channel] = (predictor[channel] + (byte) delta) & 0xFF;
                out.write(predictor[channel]);
            }
        }
    }

    private static void writeShort(java.io.ByteArrayOutputStream out, short value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    /** Walks one eight-bit tree to a leaf. */
    private static int decodeSmall(BitReader bits, Small tree) {
        if (tree == null) {
            return 0;
        }
        int at = tree.root();
        int guard = 0;
        while (tree.value()[at] < 0 && ++guard < 64) {
            at = bits.bit() ? tree.right()[at] : tree.left()[at];
            if (at <= 0 || at >= tree.value().length) {
                return 0;
            }
        }
        int value = tree.value()[at];
        return value < 0 ? 0 : value;
    }

    /**
     * The collected bytes as signed sixteen-bit samples.
     *
     * <p>Eight-bit Smacker audio is unsigned, centred on 128, so it is shifted
     * and scaled rather than simply widened; left as it is, every sample sits
     * in the top half of the range and the sound is a loud buzz.
     */
    private static short[] toSigned16(byte[] raw, boolean sixteenBit) {
        if (sixteenBit) {
            short[] samples = new short[raw.length / 2];
            for (int i = 0; i < samples.length; i++) {
                samples[i] = (short) ((raw[i * 2] & 0xFF) | (raw[i * 2 + 1] << 8));
            }
            return samples;
        }
        short[] samples = new short[raw.length];
        for (int i = 0; i < raw.length; i++) {
            samples[i] = (short) (((raw[i] & 0xFF) - 128) << 8);
        }
        return samples;
    }

    /** Rewinds to the start, so a video can be played twice. */
    public void reset() {
        java.util.Arrays.fill(picture, (byte) 0);
        java.util.Arrays.fill(palette, (byte) 0);
    }

    /**
     * The palette chunk.
     *
     * <p>Three opcodes: skip this many entries and keep what was there, copy a
     * run from somewhere in the previous palette, or read a new colour. The
     * copy is why the previous palette has to be kept rather than cleared.
     */
    private void decodePalette(int from, int to) {
        byte[] previous = palette.clone();
        int cursor = from;
        int out = 0;
        while (out < 768 && cursor < to) {
            int op = data[cursor++] & 0xFF;
            if ((op & 0x80) != 0) {
                out += (((op & 0x7F) + 1) * 3);
            } else if ((op & 0x40) != 0) {
                if (cursor >= to) {
                    break;
                }
                int source = (data[cursor++] & 0xFF) * 3;
                int count = (op & 0x3F) + 1;
                for (int i = 0; i < count && out < 768; i++) {
                    for (int c = 0; c < 3 && out < 768; c++) {
                        palette[out++] = source < previous.length ? previous[source] : 0;
                        source++;
                    }
                }
            } else {
                // Two bytes still to come, so the chunk must have two left.
                // Testing for one too many drops the last colour of a full
                // palette, and index 255 is a real colour in these videos.
                if (cursor + 2 > to) {
                    break;
                }
                palette[out++] = (byte) PALETTE_LEVELS[op & 0x3F];
                palette[out++] = (byte) PALETTE_LEVELS[data[cursor++] & 0x3F];
                palette[out++] = (byte) PALETTE_LEVELS[data[cursor++] & 0x3F];
            }
        }
    }

    /**
     * How far the last frame's block stream reached, and how far it should
     * have. A frame that runs out of bits before its blocks are covered
     * leaves the rest of the picture as it was, which is how a decoding
     * fault shows up: not as an error but as a frame that is partly stale.
     */
    private int lastBlock;
    private int lastBlockTotal;

    /** The block the last decoded frame reached. */
    public int lastBlockReached() {
        return lastBlock;
    }

    /** How many blocks that frame had. */
    public int blockCount() {
        return lastBlockTotal;
    }

    /** The block stream. */
    private void decodeVideo(int from, int to) {
        BitReader bits = new BitReader(data, from, to - from);
        int blocksWide = (width + 3) / 4;
        int blocks = blocksWide * ((height + 3) / 4);
        lastBlockTotal = blocks;
        int block = 0;

        while (block < blocks && !bits.exhausted()) {
            int type = decode(bits, typeTree, typeLast);
            if (type < 0) {
                return;
            }
            int run = BLOCK_RUNS[(type >> 2) & 0x3F];
            switch (type & 3) {
                case 0 -> {
                    // Two colours and a bitmap saying which pixel takes which.
                    while (run-- > 0 && block < blocks) {
                        int colours = decode(bits, mclrTree, mclrLast);
                        int map = decode(bits, mmapTree, mmapLast);
                        if (colours < 0 || map < 0) {
                            return;
                        }
                        int high = (colours >> 8) & 0xFF;
                        int low = colours & 0xFF;
                        int base = (block / blocksWide) * width * 4 + (block % blocksWide) * 4;
                        for (int row = 0; row < 4; row++) {
                            int at = base + row * width;
                            for (int column = 0; column < 4; column++) {
                                put(at + column, (map & (1 << column)) != 0 ? high : low);
                            }
                            map >>= 4;
                        }
                        block++;
                    }
                }
                case 1 -> {
                    // Every pixel spelled out, two at a time.
                    int mode = 0;
                    if (version4) {
                        if (bits.bit()) {
                            mode = 1;
                        } else if (bits.bit()) {
                            mode = 2;
                        }
                    }
                    while (run-- > 0 && block < blocks) {
                        int base = (block / blocksWide) * width * 4 + (block % blocksWide) * 4;
                        if (mode == 0) {
                            for (int row = 0; row < 4; row++) {
                                int at = base + row * width;
                                int pair = decode(bits, fullTree, fullLast);
                                if (pair < 0) {
                                    return;
                                }
                                put(at + 2, pair & 0xFF);
                                put(at + 3, (pair >> 8) & 0xFF);
                                pair = decode(bits, fullTree, fullLast);
                                if (pair < 0) {
                                    return;
                                }
                                put(at, pair & 0xFF);
                                put(at + 1, (pair >> 8) & 0xFF);
                            }
                        } else if (mode == 1) {
                            // Two rows at a time, the pair repeated.
                            for (int row = 0; row < 4; row += 2) {
                                int pair = decode(bits, fullTree, fullLast);
                                if (pair < 0) {
                                    return;
                                }
                                int low = pair & 0xFF;
                                int high = (pair >> 8) & 0xFF;
                                for (int copy = 0; copy < 2; copy++) {
                                    int at = base + (row + copy) * width;
                                    put(at, low);
                                    put(at + 1, high);
                                    put(at + 2, low);
                                    put(at + 3, high);
                                }
                            }
                        } else {
                            // One pair down each half of the block.
                            for (int row = 0; row < 4; row++) {
                                int pair = decode(bits, fullTree, fullLast);
                                if (pair < 0) {
                                    return;
                                }
                                int at = base + row * width;
                                put(at, pair & 0xFF);
                                put(at + 1, pair & 0xFF);
                                put(at + 2, (pair >> 8) & 0xFF);
                                put(at + 3, (pair >> 8) & 0xFF);
                            }
                        }
                        block++;
                    }
                }
                case 2 -> {
                    // Unchanged from the previous frame.
                    while (run-- > 0 && block < blocks) {
                        block++;
                    }
                }
                default -> {
                    // One colour, carried in the type code itself.
                    int colour = (type >> 8) & 0xFF;
                    while (run-- > 0 && block < blocks) {
                        int base = (block / blocksWide) * width * 4 + (block % blocksWide) * 4;
                        for (int row = 0; row < 4; row++) {
                            int at = base + row * width;
                            for (int column = 0; column < 4; column++) {
                                put(at + column, colour);
                            }
                        }
                        block++;
                    }
                }
            }
        }
            lastBlock = block;
    }
    /** Writes a pixel, ignoring one that falls off a partial bottom row. */

    private void put(int at, int value) {
        if (at >= 0 && at < picture.length) {
            picture[at] = (byte) value;
        }
    }

    /**
     * Walks a tree and moves the result to the front of its cache.
     *
     * <p>The cache is three reserved leaves inside the tree's own array, and
     * updating it is not an optimisation that can be skipped: the encoder
     * assumed it, so the next symbol's meaning depends on it.
     */
    private static int decode(BitReader bits, int[] tree, int[] last) {
        if (tree == null || tree.length == 0) {
            return -1;
        }
        int at = 0;
        int guard = 0;
        while ((tree[at] & NODE) != 0) {
            if (bits.bit()) {
                at += tree[at] & ~NODE;
            }
            at++;
            if (at >= tree.length || ++guard > tree.length) {
                return -1;
            }
        }
        int value = tree[at];
        if (value != tree[last[0]]) {
            tree[last[2]] = tree[last[1]];
            tree[last[1]] = tree[last[0]];
            tree[last[0]] = value;
        }
        return value;
    }

    /**
     * Builds one 16-bit tree.
     *
     * <p>Out of two 8-bit trees, one for each byte of the value, plus three
     * escape values. A leaf whose value matches an escape becomes one of the
     * move-to-front slots and is stored as zero, which is why the escapes have
     * to be read before the tree and not after.
     */
    private static int[] buildTree(BitReader bits, int size, int[] last) {
        if (size <= 0) {
            last[0] = last[1] = last[2] = 0;
            return new int[] {0};
        }
        // Two bits precede each of the byte trees, not one. The second is the
        // presence flag; what the first is for the format does not say, and
        // both are set in every file here. Reading only one starts the tree a
        // bit early, which does not fail: it parses a valid tree with one leaf
        // too many, and the nine bits that costs put everything after it out
        // of step. The symptom is a file that decodes fully into noise.
        bits.bit();
        Small low = bits.bit() ? readSmall(bits) : null;
        bits.bit();
        Small high = bits.bit() ? readSmall(bits) : null;
        bits.bit();

        int[] escapes = {bits.bits(16), bits.bits(16), bits.bits(16)};
        last[0] = last[1] = last[2] = -1;

        int capacity = ((size + 3) >> 2) + 4;
        int[] values = new int[Math.max(capacity, 4)];
        int[] current = {0};
        int before = bits.consumedBytes();
        readBig(bits, values, current, low, high, escapes, last, 0);
        bits.bit();

        // Slots the stream never used still have to exist, or the cache update
        // would write outside the tree.
        for (int i = 0; i < 3; i++) {
            if (last[i] == -1) {
                last[i] = Math.min(current[0]++, values.length - 1);
            }
        }
        return values;
    }

    /** An 8-bit tree, as an explicit pair of arrays. */
    private record Small(int[] left, int[] right, int[] value, int root) {}

    /** Reads an 8-bit tree; leaves carry a byte. */
    private static Small readSmall(BitReader bits) {
        // 511 is the most nodes a 256-leaf binary tree can have.
        int[] left = new int[600];
        int[] right = new int[600];
        int[] value = new int[600];
        java.util.Arrays.fill(value, -1);
        int[] next = {1};
        int[] leaves = {0};
        readSmallNode(bits, left, right, value, next, leaves, 0, 0);
        return new Small(left, right, value, 0);
    }

    private static void readSmallNode(BitReader bits, int[] left, int[] right, int[] value,
            int[] next, int[] leaves, int at, int depth) {
        if (depth > 32 || at >= value.length || next[0] + 2 >= value.length) {
            return;
        }
        if (!bits.bit()) {
            value[at] = bits.bits(8);
            leaves[0]++;
            return;
        }
        left[at] = next[0]++;
        right[at] = next[0]++;
        readSmallNode(bits, left, right, value, next, leaves, left[at], depth + 1);
        readSmallNode(bits, left, right, value, next, leaves, right[at], depth + 1);
    }

    /** Reads a byte through an 8-bit tree, or zero when there is no tree. */
    private static int readSmallValue(BitReader bits, Small tree) {
        if (tree == null) {
            return 0;
        }
        int at = tree.root();
        int guard = 0;
        while (tree.value()[at] < 0 && guard++ < 64) {
            at = bits.bit() ? tree.right()[at] : tree.left()[at];
            if (at <= 0 || at >= tree.value().length) {
                return 0;
            }
        }
        return Math.max(0, tree.value()[at]);
    }

    /** Reads the 16-bit tree into the flat array the decoder walks. */
    private static int readBig(BitReader bits, int[] values, int[] current, Small low, Small high,
            int[] escapes, int[] last, int depth) {
        // Bounded by the tree's own capacity rather than by a guessed depth.
        // A move-mapping tree runs to forty thousand entries, and a limit of a
        // few dozen truncates it after eighty: the file still decodes, into
        // noise, because a truncated tree is a valid tree for the wrong code.
        if (current[0] + 1 >= values.length || depth > values.length || bits.exhausted()) {
            return 0;
        }
        if (!bits.bit()) {
            int value = readSmallValue(bits, low) | (readSmallValue(bits, high) << 8);
            for (int i = 0; i < 3; i++) {
                if (value == escapes[i]) {
                    last[i] = current[0];
                    value = 0;
                    break;
                }
            }
            values[current[0]++] = value;
            return 1;
        }
        int at = current[0]++;
        int left = readBig(bits, values, current, low, high, escapes, last, depth + 1);
        values[at] = NODE | left;
        int right = readBig(bits, values, current, low, high, escapes, last, depth + 1);
        return left + right + 1;
    }

    private static int readInt(byte[] data, int offset) {
        if (offset + 4 > data.length) {
            return 0;
        }
        return (data[offset] & 0xFF)
                | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16)
                | ((data[offset + 3] & 0xFF) << 24);
    }

    /**
     * Bits, least significant first within each byte.
     *
     * <p>The order matters and is not the one a reader written from habit
     * would use: taking the high bit first decodes a tree that is structurally
     * valid and completely wrong.
     */
    private static final class BitReader {
        private final byte[] data;
        private final int end;
        private final int start;
        private int position;
        private int bit;

        BitReader(byte[] data, int from, int length) {
            this.data = data;
            this.start = from;
            this.position = from;
            this.end = Math.min(data.length, from + Math.max(0, length));
        }

        boolean exhausted() {
            return position >= end;
        }

        int consumedBytes() {
            return position - start;
        }

        boolean bit() {
            if (position >= end) {
                return false;
            }
            boolean value = ((data[position] >> bit) & 1) != 0;
            if (++bit == 8) {
                bit = 0;
                position++;
            }
            return value;
        }

        int bits(int count) {
            int value = 0;
            for (int i = 0; i < count; i++) {
                if (bit()) {
                    value |= 1 << i;
                }
            }
            return value;
        }
    }
}
