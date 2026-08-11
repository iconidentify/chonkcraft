package net.chonkbase.assetpack.codec;

import java.util.Arrays;

/**
 * Turns a native FLAC stream back into the samples that went into it.
 *
 * <p>New infrastructure, with no upstream analogue. See {@link Flac} for why
 * the pack carries FLAC at all.
 *
 * <p>Wider than {@link FlacEncoder} on purpose. This reads LPC subframes,
 * which the encoder never writes, so a pack whose audio came out of
 * {@code flac} or ffmpeg loads here without a conversion step. An asset
 * pipeline that could only read its own output would turn every future change
 * to the encoder into a migration of everything already built.
 *
 * <p>Wasted bits are not part of that width, and this paragraph used to say
 * they were. {@link FlacEncoder} writes them whenever a block's samples share
 * low zero bits, which is what eight-bit material widened to sixteen looks
 * like. None of the 383 effects in {@code SFXDAT.SUD}, the 104 in
 * {@code SNDDAT.WAR} or the red book tracks are shaped that way, so the path
 * never runs on the shipped data and, while the comment said it did not exist,
 * nothing tested it either -- an encoder and a decoder that shifted the same
 * wrong way would have round-tripped forever and broken the first time
 * anything else opened the pack. The reference decoder checks it now, in
 * {@code FlacInteropTest}.
 *
 * <p>Every frame's CRC-16 is checked and the frame number is named when it
 * fails. That is not ceremony. A FLAC stream has no field that says how long a
 * frame is, so a decoder that mis-reads one bit does not stop -- it reads the
 * next frame from the wrong offset, or reads plausible numbers from the right
 * one, and returns audio that plays. Music that plays and is wrong is the
 * failure this project keeps finding, and the checksum is the only thing in
 * the format that distinguishes it from music that is right.
 */
final class FlacDecoder {

    private static final int MAX_CHANNELS = 8;

    private final byte[] stream;
    private final BitReader reader;
    private final Flac.StreamInfo info;

    private final int[][] channels;
    private int[] residual;

    private int[] output;
    private int outputLength;

    private FlacDecoder(byte[] stream, Flac.StreamInfo info, int firstFrameOffset) {
        this.stream = stream;
        this.info = info;
        this.reader = new BitReader(stream);
        this.reader.seekToByte(firstFrameOffset);
        this.channels = new int[MAX_CHANNELS][];
        this.residual = new int[info.maxBlockSize() > 0 ? info.maxBlockSize() : 4096];
        long expected = info.totalFrames() * (long) info.channels();
        this.output = new int[(int) Math.min(Math.max(expected, 1024L), 1 << 24)];
    }

    // ------------------------------------------------------------- metadata

    static Flac.StreamInfo readStreamInfo(byte[] flac) {
        return parseMetadata(flac).info();
    }

    static Flac.Pcm decode(byte[] flac) {
        Metadata metadata = parseMetadata(flac);
        FlacDecoder decoder = new FlacDecoder(flac, metadata.info(), metadata.firstFrameOffset());
        return decoder.run();
    }

    private record Metadata(Flac.StreamInfo info, int firstFrameOffset) {
    }

    private static Metadata parseMetadata(byte[] flac) {
        if (flac == null || flac.length < 8) {
            throw new Flac.FlacFormatException("not a FLAC stream: only "
                    + (flac == null ? 0 : flac.length) + " bytes, and the marker alone is four");
        }
        if ((flac[0] & 0xFF) != 'f' || (flac[1] & 0xFF) != 'L'
                || (flac[2] & 0xFF) != 'a' || (flac[3] & 0xFF) != 'C') {
            throw new Flac.FlacFormatException("not a FLAC stream: the first four bytes are "
                    + String.format("%02x %02x %02x %02x",
                    flac[0] & 0xFF, flac[1] & 0xFF, flac[2] & 0xFF, flac[3] & 0xFF)
                    + ", not the fLaC marker");
        }

        int position = 4;
        Flac.StreamInfo info = null;
        boolean last = false;
        while (!last) {
            if (position + 4 > flac.length) {
                throw new Flac.FlacFormatException(
                        "the stream ends inside a metadata block header at byte " + position);
            }
            int header = flac[position] & 0xFF;
            last = (header & 0x80) != 0;
            int type = header & 0x7F;
            int length = ((flac[position + 1] & 0xFF) << 16)
                    | ((flac[position + 2] & 0xFF) << 8)
                    | (flac[position + 3] & 0xFF);
            int body = position + 4;
            if (body + length > flac.length) {
                throw new Flac.FlacFormatException("metadata block of type " + type + " claims "
                        + length + " bytes but only " + (flac.length - body) + " remain");
            }
            if (type == Flac.BLOCK_TYPE_STREAMINFO) {
                if (info != null) {
                    throw new Flac.FlacFormatException("two STREAMINFO blocks in one stream");
                }
                info = parseStreamInfo(flac, body, length);
            }
            position = body + length;
        }
        if (info == null) {
            throw new Flac.FlacFormatException("the stream has no STREAMINFO block");
        }
        return new Metadata(info, position);
    }

    private static Flac.StreamInfo parseStreamInfo(byte[] flac, int offset, int length) {
        if (length < 34) {
            throw new Flac.FlacFormatException(
                    "STREAMINFO is " + length + " bytes, and it is defined as 34");
        }
        BitReader in = new BitReader(flac, offset, offset + length);
        int minBlockSize = in.readBits(16);
        int maxBlockSize = in.readBits(16);
        int minFrameSize = in.readBits(24);
        int maxFrameSize = in.readBits(24);
        int sampleRate = in.readBits(20);
        int channels = in.readBits(3) + 1;
        int bitsPerSample = in.readBits(5) + 1;
        long totalFrames = in.readBitsLong(36);
        byte[] md5 = new byte[16];
        for (int i = 0; i < 16; i++) {
            md5[i] = (byte) in.readBits(8);
        }
        if (sampleRate == 0) {
            throw new Flac.FlacFormatException(
                    "STREAMINFO gives a sample rate of zero, which no decoder can resolve");
        }
        return new Flac.StreamInfo(minBlockSize, maxBlockSize, minFrameSize, maxFrameSize,
                sampleRate, channels, bitsPerSample, totalFrames, md5);
    }

    // --------------------------------------------------------------- frames

    private Flac.Pcm run() {
        long frameNumber = 0;
        while (reader.bytesRemaining() >= 2) {
            int at = reader.bytePosition();
            if ((stream[at] & 0xFF) != 0xFF || (stream[at + 1] & 0xFC) != 0xF8) {
                break;
            }
            decodeFrame(frameNumber++);
        }

        long expectedFrames = info.totalFrames();
        long decodedFrames = outputLength / (long) info.channels();
        if (expectedFrames > 0 && decodedFrames != expectedFrames) {
            throw new Flac.FlacFormatException("the stream ends after " + decodedFrames
                    + " sample frames but STREAMINFO promised " + expectedFrames
                    + "; the audio is truncated");
        }
        return new Flac.Pcm(info.sampleRate(), info.channels(), info.bitsPerSample(),
                Arrays.copyOf(output, outputLength));
    }

    private void decodeFrame(long frameNumber) {
        int frameStart = reader.bytePosition();

        int sync = reader.readBits(14);
        if (sync != 0x3FFE) {
            throw new Flac.FlacFormatException("frame " + frameNumber
                    + " does not begin with a sync code");
        }
        if (reader.readBit() != 0) {
            throw new Flac.FlacFormatException("frame " + frameNumber
                    + " sets the reserved bit after the sync code");
        }
        reader.readBit();
        int blockSizeCode = reader.readBits(4);
        int sampleRateCode = reader.readBits(4);
        int assignment = reader.readBits(4);
        int sampleSizeCode = reader.readBits(3);
        if (reader.readBit() != 0) {
            throw new Flac.FlacFormatException("frame " + frameNumber
                    + " sets the reserved bit at the end of the header");
        }

        readUtf8(reader, frameNumber);
        int blockFrames = readBlockSize(blockSizeCode, frameNumber);
        int sampleRate = readSampleRate(sampleRateCode, frameNumber);
        int bitsPerSample = readSampleSize(sampleSizeCode, frameNumber);
        if (sampleRate != info.sampleRate()) {
            throw new Flac.FlacFormatException("frame " + frameNumber + " says " + sampleRate
                    + " Hz and STREAMINFO says " + info.sampleRate()
                    + "; the mixer would play this at the wrong speed");
        }
        if (bitsPerSample != info.bitsPerSample()) {
            throw new Flac.FlacFormatException("frame " + frameNumber + " says " + bitsPerSample
                    + " bits per sample and STREAMINFO says " + info.bitsPerSample());
        }

        int headerEnd = reader.bytePosition();
        int storedCrc8 = reader.readBits(8);
        int computedCrc8 = Flac.crc8(stream, frameStart, headerEnd);
        if (storedCrc8 != computedCrc8) {
            throw new Flac.FlacFormatException(String.format(
                    "frame %d has a corrupt header: CRC-8 is %02x and the header computes %02x",
                    frameNumber, storedCrc8, computedCrc8));
        }

        int channelCount = assignment < 8 ? assignment + 1 : 2;
        if (channelCount != info.channels()) {
            throw new Flac.FlacFormatException("frame " + frameNumber + " carries " + channelCount
                    + " channels but STREAMINFO says " + info.channels());
        }
        if (assignment > 10) {
            throw new Flac.FlacFormatException("frame " + frameNumber
                    + " uses reserved channel assignment " + assignment);
        }

        ensureChannelCapacity(channelCount, blockFrames);
        for (int c = 0; c < channelCount; c++) {
            int channelBits = bitsPerSample + sideChannelExtraBit(assignment, c);
            decodeSubframe(channels[c], blockFrames, channelBits, frameNumber);
        }

        reader.alignToByte();
        int frameEnd = reader.bytePosition();
        int storedCrc16 = reader.readBits(16);
        int computedCrc16 = Flac.crc16(stream, frameStart, frameEnd);
        if (storedCrc16 != computedCrc16) {
            throw new Flac.FlacFormatException(String.format(
                    "frame %d fails its CRC-16: the stream says %04x and the frame computes %04x. "
                            + "The audio it decodes to is not the audio that was encoded.",
                    frameNumber, storedCrc16, computedCrc16));
        }

        undoStereoDecorrelation(assignment, blockFrames);
        interleave(channelCount, blockFrames);
    }

    /**
     * One extra bit for whichever of the two channels is a difference.
     *
     * <p>The side channel holds {@code left - right}, which needs a bit more
     * than either of them does. Reading it at the frame's own depth instead
     * decodes every wide-stereo passage as a wrapped, buzzing version of
     * itself while narrow passages come back perfect, which is a defect that
     * hides on anything close to mono.
     */
    private static int sideChannelExtraBit(int assignment, int channel) {
        return switch (assignment) {
            case Flac.CHANNELS_LEFT_SIDE, Flac.CHANNELS_MID_SIDE -> channel == 1 ? 1 : 0;
            case Flac.CHANNELS_RIGHT_SIDE -> channel == 0 ? 1 : 0;
            default -> 0;
        };
    }

    private void ensureChannelCapacity(int channelCount, int blockFrames) {
        for (int c = 0; c < channelCount; c++) {
            if (channels[c] == null || channels[c].length < blockFrames) {
                channels[c] = new int[blockFrames];
            }
        }
        if (residual.length < blockFrames) {
            residual = new int[blockFrames];
        }
    }

    private static long readUtf8(BitReader in, long frameNumber) {
        int first = in.readBits(8);
        int continuations;
        long value;
        if ((first & 0x80) == 0) {
            return first;
        } else if ((first & 0xE0) == 0xC0) {
            continuations = 1;
            value = first & 0x1F;
        } else if ((first & 0xF0) == 0xE0) {
            continuations = 2;
            value = first & 0x0F;
        } else if ((first & 0xF8) == 0xF0) {
            continuations = 3;
            value = first & 0x07;
        } else if ((first & 0xFC) == 0xF8) {
            continuations = 4;
            value = first & 0x03;
        } else if ((first & 0xFE) == 0xFC) {
            continuations = 5;
            value = first & 0x01;
        } else if (first == 0xFE) {
            continuations = 6;
            value = 0;
        } else {
            throw new Flac.FlacFormatException("frame " + frameNumber
                    + " has a malformed frame number");
        }
        for (int i = 0; i < continuations; i++) {
            int next = in.readBits(8);
            if ((next & 0xC0) != 0x80) {
                throw new Flac.FlacFormatException("frame " + frameNumber
                        + " has a malformed frame number");
            }
            value = (value << 6) | (next & 0x3F);
        }
        return value;
    }

    private int readBlockSize(int code, long frameNumber) {
        return switch (code) {
            case 0 -> throw new Flac.FlacFormatException(
                    "frame " + frameNumber + " uses the reserved block size code 0");
            case 1 -> 192;
            case 2, 3, 4, 5 -> 576 << (code - 2);
            case 6 -> reader.readBits(8) + 1;
            case 7 -> reader.readBits(16) + 1;
            default -> 256 << (code - 8);
        };
    }

    private int readSampleRate(int code, long frameNumber) {
        return switch (code) {
            case 0 -> info.sampleRate();
            case 12 -> reader.readBits(8) * 1000;
            case 13 -> reader.readBits(16);
            case 14 -> reader.readBits(16) * 10;
            case 15 -> throw new Flac.FlacFormatException(
                    "frame " + frameNumber + " uses the invalid sample rate code 15");
            default -> Flac.SAMPLE_RATE_CODES[code];
        };
    }

    private int readSampleSize(int code, long frameNumber) {
        return switch (code) {
            case 0 -> info.bitsPerSample();
            case 1 -> 8;
            case 2 -> 12;
            case 3 -> throw new Flac.FlacFormatException(
                    "frame " + frameNumber + " uses the reserved sample size code 3");
            case 4 -> 16;
            case 5 -> 20;
            case 6 -> 24;
            default -> 32;
        };
    }

    // ------------------------------------------------------------ subframes

    private void decodeSubframe(int[] out, int blockFrames, int channelBits, long frameNumber) {
        if (reader.readBit() != 0) {
            throw new Flac.FlacFormatException("frame " + frameNumber
                    + " has a subframe whose leading padding bit is set");
        }
        int type = reader.readBits(6);
        int wastedBits = 0;
        if (reader.readBit() != 0) {
            wastedBits = reader.readUnary() + 1;
        }
        int bits = channelBits - wastedBits;
        if (bits < 1 || bits > 32) {
            throw new Flac.FlacFormatException("frame " + frameNumber + " has a subframe with "
                    + wastedBits + " wasted bits out of " + channelBits + ", leaving " + bits);
        }

        if (type == 0) {
            int value = reader.readSigned(bits);
            Arrays.fill(out, 0, blockFrames, value);
        } else if (type == 1) {
            for (int i = 0; i < blockFrames; i++) {
                out[i] = reader.readSigned(bits);
            }
        } else if (type >= 8 && type <= 12) {
            decodeFixed(out, blockFrames, bits, type - 8, frameNumber);
        } else if (type >= 32) {
            decodeLpc(out, blockFrames, bits, type - 31, frameNumber);
        } else {
            throw new Flac.FlacFormatException("frame " + frameNumber
                    + " uses reserved subframe type " + type);
        }

        if (wastedBits > 0) {
            for (int i = 0; i < blockFrames; i++) {
                out[i] <<= wastedBits;
            }
        }
    }

    private void decodeFixed(int[] out, int blockFrames, int bits, int order, long frameNumber) {
        if (order > blockFrames) {
            throw new Flac.FlacFormatException("frame " + frameNumber + " has a fixed predictor of "
                    + "order " + order + " in a block of " + blockFrames + " samples");
        }
        for (int i = 0; i < order; i++) {
            out[i] = reader.readSigned(bits);
        }
        decodeResidual(blockFrames, order, frameNumber);
        switch (order) {
            case 0 -> System.arraycopy(residual, 0, out, 0, blockFrames);
            case 1 -> {
                for (int i = 1; i < blockFrames; i++) {
                    out[i] = residual[i - 1] + out[i - 1];
                }
            }
            case 2 -> {
                for (int i = 2; i < blockFrames; i++) {
                    out[i] = residual[i - 2] + 2 * out[i - 1] - out[i - 2];
                }
            }
            case 3 -> {
                for (int i = 3; i < blockFrames; i++) {
                    out[i] = residual[i - 3] + 3 * out[i - 1] - 3 * out[i - 2] + out[i - 3];
                }
            }
            default -> {
                for (int i = 4; i < blockFrames; i++) {
                    out[i] = residual[i - 4]
                            + 4 * out[i - 1] - 6 * out[i - 2] + 4 * out[i - 3] - out[i - 4];
                }
            }
        }
    }

    /**
     * Reads an LPC subframe, which this encoder never writes and every other
     * one does.
     *
     * <p>The accumulator is a {@code long} and the shift is applied to it
     * before the residual is added, in that order. Both details are load
     * bearing: 32 coefficients of up to 15 bits against 32-bit samples
     * overflows an {@code int} outright, and rounding the prediction before
     * adding the residual instead of after gives a stream that decodes to
     * something within a bit or two of the music, which is the kind of wrong
     * that sounds fine and fails a checksum.
     */
    private void decodeLpc(int[] out, int blockFrames, int bits, int order, long frameNumber) {
        if (order > blockFrames) {
            throw new Flac.FlacFormatException("frame " + frameNumber + " has an LPC predictor of "
                    + "order " + order + " in a block of " + blockFrames + " samples");
        }
        for (int i = 0; i < order; i++) {
            out[i] = reader.readSigned(bits);
        }
        int precision = reader.readBits(4) + 1;
        if (precision == 16) {
            throw new Flac.FlacFormatException("frame " + frameNumber
                    + " uses the invalid LPC coefficient precision of 16");
        }
        int shift = reader.readSigned(5);
        if (shift < 0) {
            throw new Flac.FlacFormatException("frame " + frameNumber
                    + " uses a negative LPC shift of " + shift);
        }
        int[] coefficients = new int[order];
        for (int i = 0; i < order; i++) {
            coefficients[i] = reader.readSigned(precision);
        }
        decodeResidual(blockFrames, order, frameNumber);
        for (int i = order; i < blockFrames; i++) {
            long prediction = 0;
            for (int j = 0; j < order; j++) {
                prediction += (long) coefficients[j] * out[i - 1 - j];
            }
            out[i] = residual[i - order] + (int) (prediction >> shift);
        }
    }

    private void decodeResidual(int blockFrames, int order, long frameNumber) {
        int method = reader.readBits(2);
        if (method > 1) {
            throw new Flac.FlacFormatException("frame " + frameNumber
                    + " uses reserved residual coding method " + method);
        }
        int parameterBits = method == 0 ? 4 : 5;
        int escapeCode = method == 0 ? 15 : 31;
        int partitionOrder = reader.readBits(4);
        int partitions = 1 << partitionOrder;
        if ((blockFrames & (partitions - 1)) != 0) {
            throw new Flac.FlacFormatException("frame " + frameNumber + " splits a block of "
                    + blockFrames + " samples into " + partitions + " partitions, which do not "
                    + "divide it evenly");
        }
        int perPartition = blockFrames >> partitionOrder;
        if (perPartition < order) {
            throw new Flac.FlacFormatException("frame " + frameNumber + " has a first partition of "
                    + perPartition + " samples but a predictor of order " + order);
        }

        int index = 0;
        for (int partition = 0; partition < partitions; partition++) {
            int parameter = reader.readBits(parameterBits);
            int count = perPartition - (partition == 0 ? order : 0);
            if (parameter == escapeCode) {
                int rawBits = reader.readBits(5);
                if (rawBits == 0) {
                    Arrays.fill(residual, index, index + count, 0);
                    index += count;
                } else {
                    for (int i = 0; i < count; i++) {
                        residual[index++] = reader.readSigned(rawBits);
                    }
                }
            } else {
                for (int i = 0; i < count; i++) {
                    int quotient = reader.readUnary();
                    int folded = (quotient << parameter) | reader.readBits(parameter);
                    residual[index++] = (folded >>> 1) ^ -(folded & 1);
                }
            }
        }
    }

    // ------------------------------------------------------------- assembly

    private void undoStereoDecorrelation(int assignment, int blockFrames) {
        switch (assignment) {
            case Flac.CHANNELS_LEFT_SIDE -> {
                int[] left = channels[0];
                int[] side = channels[1];
                for (int i = 0; i < blockFrames; i++) {
                    side[i] = left[i] - side[i];
                }
            }
            case Flac.CHANNELS_RIGHT_SIDE -> {
                int[] side = channels[0];
                int[] right = channels[1];
                for (int i = 0; i < blockFrames; i++) {
                    side[i] = side[i] + right[i];
                }
            }
            case Flac.CHANNELS_MID_SIDE -> {
                int[] mid = channels[0];
                int[] side = channels[1];
                for (int i = 0; i < blockFrames; i++) {
                    int difference = side[i];
                    int sum = (mid[i] << 1) | (difference & 1);
                    mid[i] = (sum + difference) >> 1;
                    side[i] = (sum - difference) >> 1;
                }
            }
            default -> {
            }
        }
    }

    private void interleave(int channelCount, int blockFrames) {
        int needed = outputLength + channelCount * blockFrames;
        if (needed > output.length) {
            int grown = output.length;
            while (grown < needed) {
                grown += grown >> 1;
            }
            output = Arrays.copyOf(output, grown);
        }
        for (int c = 0; c < channelCount; c++) {
            int[] source = channels[c];
            int target = outputLength + c;
            for (int i = 0; i < blockFrames; i++) {
                output[target] = source[i];
                target += channelCount;
            }
        }
        outputLength = needed;
    }
}
