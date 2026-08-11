package net.chonkbase.assetpack.codec;

import java.util.Arrays;

/**
 * Turns interleaved PCM into a native FLAC stream.
 *
 * <p>New infrastructure, with no upstream analogue. See {@link Flac} for why
 * the pack carries FLAC at all and why this encoder is as narrow as it is.
 *
 * <p>The shape of the search, in the order it happens per block of 4096 sample
 * frames. Stereo is decorrelated four ways -- left/right, mid/side, left/side
 * and right/side -- and the smallest wins; mono skips that entirely. Each
 * resulting channel is offered as CONSTANT, VERBATIM and a fixed predictor of
 * order 0 to 4, and again the smallest wins. CONSTANT and VERBATIM are not
 * decoration: silence and the 487 short effects are exactly where they pay, and
 * without VERBATIM an incompressible effect encodes larger than it started
 * instead of at the raw size plus a frame header. The predictor's residual is
 * then Rice coded, with the partition order tried from 0 to 6 and any partition
 * Rice loses on escaped to unencoded bits.
 *
 * <p>Every one of those choices is made on the exact encoded bit count rather
 * than an estimate, except the fixed predictor's order, which is chosen on the
 * smallest sum of absolute residuals. That one is an estimate on purpose: it
 * costs one pass over the block instead of five Rice partition searches, and
 * it is what libFLAC's own default does. It ignores the cost of the order's
 * warm-up samples, which is at worst four samples out of 4096 and disappears
 * against a block that size.
 */
final class FlacEncoder {

    /** libFLAC's default, and the size every measurement quoted in {@link Flac} was taken at. */
    static final int BLOCK_SIZE = 4096;

    private static final int MAX_FIXED_ORDER = 4;
    private static final int MAX_PARTITION_ORDER = 6;
    private static final int MAX_PARTITIONS = 1 << MAX_PARTITION_ORDER;

    /** The widest Rice parameter the five-bit form can name; the four-bit form stops at 14. */
    private static final int MAX_RICE_PARAMETER = 30;

    private static final int RICE_METHOD_4BIT = 0;
    private static final int RICE_METHOD_5BIT = 1;

    private static final int STREAMINFO_BYTES = 34;

    /** Where {@code minFrameSize} sits once the marker and the block header are down. */
    private static final int MIN_FRAME_SIZE_OFFSET = 12;

    private final int sampleRate;
    private final int channels;
    private final int bitsPerSample;
    private final int[] samples;

    /** One block of each channel, de-interleaved. */
    private final int[][] channelBlock;

    private final int[] midBlock;
    private final int[] sideBlock;

    private final Subframe[] plan;
    private final Subframe midPlan;
    private final Subframe sidePlan;

    private final int[] differences = new int[BLOCK_SIZE];
    private final long[][] partitionSums = new long[MAX_PARTITIONS][MAX_RICE_PARAMETER + 1];
    private final int[] partitionCounts = new int[MAX_PARTITIONS];
    private final int[] partitionRawBits = new int[MAX_PARTITIONS];
    private final int[] scratchParameters = new int[MAX_PARTITIONS];
    private final boolean[] scratchEscaped = new boolean[MAX_PARTITIONS];

    private FlacEncoder(Flac.Pcm pcm) {
        this.sampleRate = pcm.sampleRate();
        this.channels = pcm.channels();
        this.bitsPerSample = pcm.bitsPerSample();
        this.samples = pcm.samples();
        this.channelBlock = new int[channels][BLOCK_SIZE];
        this.midBlock = new int[BLOCK_SIZE];
        this.sideBlock = new int[BLOCK_SIZE];
        this.plan = new Subframe[channels];
        for (int c = 0; c < channels; c++) {
            this.plan[c] = new Subframe();
        }
        this.midPlan = new Subframe();
        this.sidePlan = new Subframe();
    }

    static byte[] encode(Flac.Pcm pcm) {
        validate(pcm);
        return new FlacEncoder(pcm).run();
    }

    /**
     * Refuses input this encoder cannot represent, naming the sample at fault.
     *
     * <p>The range check is the one worth having. Eight-bit WAV is unsigned on
     * disc, so a caller that hands the bytes over as they were read passes
     * values from 0 to 255 where FLAC wants -128 to 127. Every one of them
     * would encode without complaint and come back as a sample that was never
     * recorded, so the conversion is demanded here rather than guessed at.
     */
    private static void validate(Flac.Pcm pcm) {
        int bits = pcm.bitsPerSample();
        if (bits != 8 && bits != 16) {
            throw new IllegalArgumentException(
                    "this encoder writes 8 and 16 bit audio, not " + bits + " bit");
        }
        if (pcm.sampleRate() > 0xFFFFF) {
            throw new IllegalArgumentException("STREAMINFO carries the sample rate in 20 bits, so "
                    + pcm.sampleRate() + " Hz cannot be written");
        }
        int low = -(1 << (bits - 1));
        int high = (1 << (bits - 1)) - 1;
        int[] samples = pcm.samples();
        for (int i = 0; i < samples.length; i++) {
            if (samples[i] < low || samples[i] > high) {
                throw new IllegalArgumentException("sample " + i + " is " + samples[i]
                        + ", outside the " + bits + " bit range " + low + " to " + high
                        + "; eight bit WAV is unsigned on disc and has to be centred first");
            }
        }
    }

    private byte[] run() {
        int totalFrames = samples.length / channels;

        BitWriter out = new BitWriter(Math.max(1024, samples.length));
        out.writeBits(Flac.STREAM_MARKER, 32);
        writeStreamInfo(out, totalFrames);

        int minFrameSize = Integer.MAX_VALUE;
        int maxFrameSize = 0;
        long frameNumber = 0;
        for (int start = 0; start < totalFrames; start += BLOCK_SIZE) {
            int blockFrames = Math.min(BLOCK_SIZE, totalFrames - start);
            int frameStart = out.size();
            writeFrame(out, start, blockFrames, frameNumber++);
            int frameSize = out.size() - frameStart;
            minFrameSize = Math.min(minFrameSize, frameSize);
            maxFrameSize = Math.max(maxFrameSize, frameSize);
        }

        byte[] stream = out.toByteArray();
        if (maxFrameSize > 0) {
            writeUint24(stream, MIN_FRAME_SIZE_OFFSET, minFrameSize);
            writeUint24(stream, MIN_FRAME_SIZE_OFFSET + 3, maxFrameSize);
        }
        return stream;
    }

    /**
     * Writes the one metadata block this format carries, with the last-block
     * flag set so a decoder starts on frames immediately afterwards.
     *
     * <p>The block sizes go out as 4096 both ways even when the whole stream is
     * shorter than one block, which is what libFLAC writes and therefore what
     * every decoder in the world has been tested against. Reporting the real
     * size of a short single frame instead would put a value below 16 into a
     * field the specification says is at least 16.
     */
    private void writeStreamInfo(BitWriter out, int totalFrames) {
        out.writeBits(0x80 | Flac.BLOCK_TYPE_STREAMINFO, 8);
        out.writeBits(STREAMINFO_BYTES, 24);
        out.writeBits(BLOCK_SIZE, 16);
        out.writeBits(BLOCK_SIZE, 16);
        out.writeBits(0, 24);
        out.writeBits(0, 24);
        out.writeBits(sampleRate, 20);
        out.writeBits(channels - 1, 3);
        out.writeBits(bitsPerSample - 1, 5);
        out.writeBitsLong(totalFrames, 36);
        for (byte b : Flac.md5OfSamples(samples, bitsPerSample)) {
            out.writeBits(b, 8);
        }
    }

    private static void writeUint24(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value >>> 16);
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) value;
    }

    // ----------------------------------------------------------------- frames

    private void writeFrame(BitWriter out, int firstFrame, int blockFrames, long frameNumber) {
        for (int c = 0; c < channels; c++) {
            int[] target = channelBlock[c];
            int source = firstFrame * channels + c;
            for (int i = 0; i < blockFrames; i++) {
                target[i] = samples[source];
                source += channels;
            }
        }

        int assignment;
        Subframe firstChannel = null;
        Subframe secondChannel = null;
        if (channels == 2) {
            assignment = chooseStereo(blockFrames);
            switch (assignment) {
                case Flac.CHANNELS_LEFT_SIDE -> {
                    firstChannel = plan[0];
                    secondChannel = sidePlan;
                }
                case Flac.CHANNELS_RIGHT_SIDE -> {
                    firstChannel = sidePlan;
                    secondChannel = plan[1];
                }
                case Flac.CHANNELS_MID_SIDE -> {
                    firstChannel = midPlan;
                    secondChannel = sidePlan;
                }
                default -> {
                    firstChannel = plan[0];
                    secondChannel = plan[1];
                }
            }
        } else {
            assignment = channels - 1;
            for (int c = 0; c < channels; c++) {
                planSubframe(plan[c], channelBlock[c], blockFrames, bitsPerSample);
            }
        }

        int frameStart = out.size();
        writeFrameHeader(out, blockFrames, assignment, frameNumber);
        out.writeBits(Flac.crc8(out.array(), frameStart, out.size()), 8);

        if (channels == 2) {
            writeSubframe(out, firstChannel, blockFrames);
            writeSubframe(out, secondChannel, blockFrames);
        } else {
            for (int c = 0; c < channels; c++) {
                writeSubframe(out, plan[c], blockFrames);
            }
        }

        out.alignToByte();
        out.writeBits(Flac.crc16(out.array(), frameStart, out.size()), 16);
    }

    /**
     * Plans all four stereo decorrelations and returns the cheapest.
     *
     * <p>The side channel is the difference of two samples and so needs one bit
     * more than either of them, which is why it is planned once at
     * {@code bitsPerSample + 1} and shared by all three assignments that
     * contain it. A side subframe planned at the input's own depth would wrap
     * every sample where the two channels differ by more than half scale --
     * audible as a crack on wide stereo and on nothing else, which is a defect
     * that survives casual listening.
     */
    private int chooseStereo(int blockFrames) {
        int[] left = channelBlock[0];
        int[] right = channelBlock[1];
        for (int i = 0; i < blockFrames; i++) {
            int l = left[i];
            int r = right[i];
            midBlock[i] = (l + r) >> 1;
            sideBlock[i] = l - r;
        }

        planSubframe(plan[0], left, blockFrames, bitsPerSample);
        planSubframe(plan[1], right, blockFrames, bitsPerSample);
        planSubframe(midPlan, midBlock, blockFrames, bitsPerSample);
        planSubframe(sidePlan, sideBlock, blockFrames, bitsPerSample + 1);

        long independent = plan[0].totalBits + plan[1].totalBits;
        long leftSide = plan[0].totalBits + sidePlan.totalBits;
        long rightSide = sidePlan.totalBits + plan[1].totalBits;
        long midSide = midPlan.totalBits + sidePlan.totalBits;

        long best = independent;
        int assignment = Flac.CHANNELS_INDEPENDENT_STEREO;
        if (leftSide < best) {
            best = leftSide;
            assignment = Flac.CHANNELS_LEFT_SIDE;
        }
        if (rightSide < best) {
            best = rightSide;
            assignment = Flac.CHANNELS_RIGHT_SIDE;
        }
        if (midSide < best) {
            assignment = Flac.CHANNELS_MID_SIDE;
        }
        return assignment;
    }

    private void writeFrameHeader(BitWriter out, int blockFrames, int assignment, long frameNumber) {
        int blockSizeCode = blockSizeCode(blockFrames);
        int rateCode = sampleRateCode(sampleRate);

        out.writeBits(0x3FFE, 14);
        out.writeBits(0, 1);
        out.writeBits(0, 1);
        out.writeBits(blockSizeCode, 4);
        out.writeBits(rateCode, 4);
        out.writeBits(assignment, 4);
        out.writeBits(sampleSizeCode(bitsPerSample), 3);
        out.writeBits(0, 1);
        out.writeUtf8(frameNumber);
        if (blockSizeCode == 6) {
            out.writeBits(blockFrames - 1, 8);
        } else if (blockSizeCode == 7) {
            out.writeBits(blockFrames - 1, 16);
        }
        switch (rateCode) {
            case 12 -> out.writeBits(sampleRate / 1000, 8);
            case 13 -> out.writeBits(sampleRate, 16);
            case 14 -> out.writeBits(sampleRate / 10, 16);
            default -> {
            }
        }
    }

    private static int blockSizeCode(int blockFrames) {
        if (blockFrames == 192) {
            return 1;
        }
        for (int code = 2; code <= 5; code++) {
            if (blockFrames == 576 << (code - 2)) {
                return code;
            }
        }
        for (int code = 8; code <= 15; code++) {
            if (blockFrames == 256 << (code - 8)) {
                return code;
            }
        }
        return blockFrames <= 256 ? 6 : 7;
    }

    /**
     * Names the sample rate in the frame header wherever FLAC has a code for it.
     *
     * <p>11025, which is what most of the sound effects run at, is not one of
     * the eleven rates FLAC can spell in four bits, and it is neither a whole
     * number of kilohertz nor a multiple of ten, so it goes out in the
     * sixteen-bit literal form. Falling back to "read it from STREAMINFO" would
     * also work and would cost two bytes less per frame, but a frame that names
     * its own rate is one a decoder can resynchronise onto mid-stream.
     */
    private static int sampleRateCode(int sampleRate) {
        for (int code = 1; code <= 11; code++) {
            if (Flac.SAMPLE_RATE_CODES[code] == sampleRate) {
                return code;
            }
        }
        if (sampleRate % 1000 == 0 && sampleRate / 1000 <= 255) {
            return 12;
        }
        if (sampleRate <= 0xFFFF) {
            return 13;
        }
        if (sampleRate % 10 == 0 && sampleRate / 10 <= 0xFFFF) {
            return 14;
        }
        return 0;
    }

    private static int sampleSizeCode(int bitsPerSample) {
        return switch (bitsPerSample) {
            case 8 -> 1;
            case 12 -> 2;
            case 16 -> 4;
            case 20 -> 5;
            case 24 -> 6;
            case 32 -> 7;
            default -> 0;
        };
    }

    // -------------------------------------------------------------- subframes

    /** One channel's worth of a block, and the cheapest way found to spell it. */
    private static final class Subframe {
        int type;
        int order;
        int wastedBits;
        int effectiveBits;
        final int[] shifted = new int[BLOCK_SIZE];
        final int[] residual = new int[BLOCK_SIZE];
        int riceMethod;
        int ricePartitionOrder;
        final int[] riceParameters = new int[MAX_PARTITIONS];
        final boolean[] riceEscaped = new boolean[MAX_PARTITIONS];
        long totalBits;
    }

    private void planSubframe(Subframe out, int[] block, int count, int bits) {
        int union = 0;
        boolean allEqual = true;
        int firstSample = block[0];
        for (int i = 0; i < count; i++) {
            union |= block[i];
            if (block[i] != firstSample) {
                allEqual = false;
            }
        }

        int wasted = union == 0 ? 0 : Integer.numberOfTrailingZeros(union);
        if (wasted >= bits) {
            wasted = bits - 1;
        }
        out.wastedBits = wasted;
        out.effectiveBits = bits - wasted;
        for (int i = 0; i < count; i++) {
            out.shifted[i] = block[i] >> wasted;
        }

        long headerBits = 8 + wasted;

        if (allEqual) {
            out.type = Flac.SUBFRAME_CONSTANT;
            out.order = 0;
            out.totalBits = headerBits + out.effectiveBits;
            return;
        }

        long verbatimBits = headerBits + (long) count * out.effectiveBits;

        int order = chooseFixedOrder(out.shifted, count);
        computeResidual(out.shifted, count, order, out.residual);
        long riceBits = planRice(out, count, order);
        long fixedBits = headerBits + (long) order * out.effectiveBits + riceBits;

        if (fixedBits <= verbatimBits) {
            out.type = Flac.SUBFRAME_FIXED;
            out.order = order;
            out.totalBits = fixedBits;
        } else {
            out.type = Flac.SUBFRAME_VERBATIM;
            out.order = 0;
            out.totalBits = verbatimBits;
        }
    }

    /**
     * Picks the fixed predictor whose residuals are smallest in absolute total.
     *
     * <p>Successive orders are successive finite differences, so all five come
     * out of one running pass rather than five independent ones. Ties go to the
     * lower order because it costs fewer warm-up samples, which is the case on
     * a run of silence bordered by anything at all.
     */
    private int chooseFixedOrder(int[] block, int count) {
        int maxOrder = Math.min(MAX_FIXED_ORDER, count);
        System.arraycopy(block, 0, differences, 0, count);
        long best = Long.MAX_VALUE;
        int bestOrder = 0;
        for (int order = 0; order <= maxOrder; order++) {
            if (order > 0) {
                for (int i = count - 1; i >= order; i--) {
                    differences[i] -= differences[i - 1];
                }
            }
            long sum = 0;
            for (int i = order; i < count; i++) {
                sum += Math.abs((long) differences[i]);
            }
            if (sum < best) {
                best = sum;
                bestOrder = order;
            }
        }
        return bestOrder;
    }

    private void computeResidual(int[] block, int count, int order, int[] out) {
        System.arraycopy(block, 0, differences, 0, count);
        for (int k = 1; k <= order; k++) {
            for (int i = count - 1; i >= k; i--) {
                differences[i] -= differences[i - 1];
            }
        }
        System.arraycopy(differences, order, out, 0, count - order);
    }

    // --------------------------------------------------------- rice partitions

    /**
     * Chooses the partition order, the parameter width and every partition's
     * Rice parameter, on the exact bit count rather than on an estimate.
     *
     * <p>For each partition at the deepest order that fits, this accumulates
     * the sum of {@code folded >>> k} for every k that could be a parameter. A
     * partition at a shallower order is the union of two at the next deeper
     * one, so those sums simply add, and one pass over the residuals answers
     * all seven partition orders exactly. The usual shortcut, taking
     * {@code (sum of folded) >>> k} instead, is not the same number: it is high
     * by up to one bit per residual, which is enough to choose a parameter one
     * off the best across a quiet passage and pay a few percent for nothing.
     */
    private long planRice(Subframe out, int count, int order) {
        int maxPartitionOrder = 0;
        for (int p = 1; p <= MAX_PARTITION_ORDER; p++) {
            if ((count & ((1 << p) - 1)) != 0 || (count >> p) < order) {
                break;
            }
            maxPartitionOrder = p;
        }

        accumulateDeepestPartitions(out.residual, count, order, maxPartitionOrder);

        long bestBits = Long.MAX_VALUE;
        for (int p = maxPartitionOrder; p >= 0; p--) {
            if (p < maxPartitionOrder) {
                mergePartitions(p);
            }
            for (int method = RICE_METHOD_4BIT; method <= RICE_METHOD_5BIT; method++) {
                long bits = costPartitions(p, method);
                if (bits < bestBits) {
                    bestBits = bits;
                    out.riceMethod = method;
                    out.ricePartitionOrder = p;
                    System.arraycopy(scratchParameters, 0, out.riceParameters, 0, 1 << p);
                    System.arraycopy(scratchEscaped, 0, out.riceEscaped, 0, 1 << p);
                }
            }
        }
        return bestBits;
    }

    private void accumulateDeepestPartitions(int[] residual, int count, int order, int deepest) {
        int partitions = 1 << deepest;
        int perPartition = count >> deepest;
        for (int j = 0; j < partitions; j++) {
            long[] sums = partitionSums[j];
            Arrays.fill(sums, 0L);
            int from = Math.max(j * perPartition, order) - order;
            int to = (j + 1) * perPartition - order;
            int rawBits = 0;
            for (int i = from; i < to; i++) {
                int value = residual[i];
                long folded = ((value << 1) ^ (value >> 31)) & 0xFFFFFFFFL;
                for (int k = 0; k <= MAX_RICE_PARAMETER && folded != 0; k++, folded >>>= 1) {
                    sums[k] += folded;
                }
                int needed = signedBitsNeeded(value);
                if (needed > rawBits) {
                    rawBits = needed;
                }
            }
            partitionCounts[j] = to - from;
            partitionRawBits[j] = rawBits;
        }
    }

    private void mergePartitions(int partitionOrder) {
        int partitions = 1 << partitionOrder;
        for (int j = 0; j < partitions; j++) {
            long[] target = partitionSums[j];
            long[] lower = partitionSums[2 * j];
            long[] upper = partitionSums[2 * j + 1];
            for (int k = 0; k <= MAX_RICE_PARAMETER; k++) {
                target[k] = lower[k] + upper[k];
            }
            partitionCounts[j] = partitionCounts[2 * j] + partitionCounts[2 * j + 1];
            partitionRawBits[j] = Math.max(partitionRawBits[2 * j], partitionRawBits[2 * j + 1]);
        }
    }

    private long costPartitions(int partitionOrder, int method) {
        int partitions = 1 << partitionOrder;
        int parameterBits = method == RICE_METHOD_4BIT ? 4 : 5;
        int maxParameter = method == RICE_METHOD_4BIT ? 14 : MAX_RICE_PARAMETER;
        long total = 2 + 4;
        for (int j = 0; j < partitions; j++) {
            long[] sums = partitionSums[j];
            long count = partitionCounts[j];
            long bestBits = Long.MAX_VALUE;
            int bestParameter = 0;
            for (int k = 0; k <= maxParameter; k++) {
                long bits = parameterBits + count * (k + 1) + sums[k];
                if (bits < bestBits) {
                    bestBits = bits;
                    bestParameter = k;
                }
            }
            boolean escaped = false;
            int rawBits = partitionRawBits[j];
            if (rawBits <= 31) {
                long bits = parameterBits + 5 + count * rawBits;
                if (bits < bestBits) {
                    bestBits = bits;
                    escaped = true;
                }
            }
            scratchParameters[j] = escaped ? rawBits : bestParameter;
            scratchEscaped[j] = escaped;
            total += bestBits;
        }
        return total;
    }

    /** Bits needed to hold {@code value} as two's complement; zero needs none. */
    static int signedBitsNeeded(int value) {
        if (value == 0) {
            return 0;
        }
        int magnitude = value >= 0 ? value : ~value;
        return 33 - Integer.numberOfLeadingZeros(magnitude);
    }

    // ----------------------------------------------------------------- writing

    private void writeSubframe(BitWriter out, Subframe subframe, int count) {
        int typeCode = switch (subframe.type) {
            case Flac.SUBFRAME_CONSTANT -> 0;
            case Flac.SUBFRAME_VERBATIM -> 1;
            case Flac.SUBFRAME_FIXED -> 8 | subframe.order;
            default -> throw new IllegalStateException("no such subframe type " + subframe.type);
        };
        out.writeBits(0, 1);
        out.writeBits(typeCode, 6);
        if (subframe.wastedBits > 0) {
            out.writeBits(1, 1);
            out.writeUnary(subframe.wastedBits - 1);
        } else {
            out.writeBits(0, 1);
        }

        int bits = subframe.effectiveBits;
        switch (subframe.type) {
            case Flac.SUBFRAME_CONSTANT -> out.writeBits(subframe.shifted[0], bits);
            case Flac.SUBFRAME_VERBATIM -> {
                for (int i = 0; i < count; i++) {
                    out.writeBits(subframe.shifted[i], bits);
                }
            }
            default -> {
                for (int i = 0; i < subframe.order; i++) {
                    out.writeBits(subframe.shifted[i], bits);
                }
                writeResidual(out, subframe, count);
            }
        }
    }

    private void writeResidual(BitWriter out, Subframe subframe, int count) {
        int method = subframe.riceMethod;
        int partitionOrder = subframe.ricePartitionOrder;
        int parameterBits = method == RICE_METHOD_4BIT ? 4 : 5;
        int escapeCode = method == RICE_METHOD_4BIT ? 15 : 31;

        out.writeBits(method, 2);
        out.writeBits(partitionOrder, 4);

        int partitions = 1 << partitionOrder;
        int perPartition = count >> partitionOrder;
        int[] residual = subframe.residual;
        int index = 0;
        for (int j = 0; j < partitions; j++) {
            int inPartition = perPartition - (j == 0 ? subframe.order : 0);
            if (subframe.riceEscaped[j]) {
                int rawBits = subframe.riceParameters[j];
                out.writeBits(escapeCode, parameterBits);
                out.writeBits(rawBits, 5);
                for (int i = 0; i < inPartition; i++) {
                    out.writeBits(residual[index++], rawBits);
                }
            } else {
                int parameter = subframe.riceParameters[j];
                out.writeBits(parameter, parameterBits);
                for (int i = 0; i < inPartition; i++) {
                    int value = residual[index++];
                    int folded = (value << 1) ^ (value >> 31);
                    out.writeUnary(folded >>> parameter);
                    out.writeBits(folded, parameter);
                }
            }
        }
    }
}
