package net.chonkbase.assetpack.codec.opus;

import java.util.Arrays;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The range coder checked against the reference implementation the RFC carries,
 * rather than against itself.
 *
 * <p>A port of {@code ec_dec}, {@code ec_enc} and {@code ec_tell_frac} in
 * {@code celt/entdec.c}, {@code celt/entenc.c} and {@code celt/entcode.c},
 * specified by RFC 6716 sections 4.1 and 5.1. The copies live in
 * {@link Reference} below and are transcribed statement for statement from the
 * archive in RFC 6716 Appendix A, which section 1 of the RFC makes normative.
 *
 * <p>Everything else that tests this layer proves it agrees with itself: a
 * sequence encoded by {@link RangeEncoder} comes back out of
 * {@link RangeDecoder}, and the two report the same {@code rng}. That check is
 * blind to any mistake both halves make, and the mistakes worth worrying about
 * here are exactly of that kind, because both halves were written from the same
 * reading of the same page. The prose of the RFC is itself not enough of a
 * second opinion -- section 5.1.1 states the {@code fl == 0} branch of
 * {@code ec_encode()} as {@code rng - (rng/ft)*(fh - fl)} where the code, and
 * the decoder's own section 4.1.2, say {@code (ft - fh)}. An implementation
 * transcribed faithfully from that sentence encodes a stream no decoder in the
 * world can read, and a round trip against a decoder with the same slip in it
 * would pass.
 *
 * <p>So the oracle here is written in a different arithmetic from the code under
 * test on purpose. {@link RangeDecoder} carries its state in {@code long},
 * where nothing wraps; {@link Reference} carries it in {@code int} with
 * {@link Integer#divideUnsigned} and {@link Integer#compareUnsigned}, which is
 * what the C actually does. Every place the two disagree about wrapping is a
 * place this test looks at directly.
 *
 * <p>Then it is driven over the conformance vectors, because a range decoder's
 * behaviour depends on the bytes it is given: the arithmetic is exercised at
 * different truncation points by real coded data than by anything a test can
 * invent.
 */
class RangeCoderReferenceTest {

    /** How far past the frame's own bits each script keeps decoding. */
    private static final int OVERRUN_STEPS = 24;

    @Test
    @DisplayName("the decoder tracks the reference implementation symbol for symbol on every CELT frame in the suite")
    void theDecoderMatchesTheReferenceOnEveryCeltFrameInTheConformanceSuite() {
        int frames = 0;
        long steps = 0;
        long bytes = 0;
        for (int number = 1; number <= 12; number++) {
            OpusTestVectors.Vector vector = OpusTestVectors.load(number);
            Assumptions.assumeTrue(vector != null, OpusTestVectors.skipReason());
            for (OpusTestVectors.Packet packet : vector.packets()) {
                OpusPacket parsed = OpusPacket.parse(packet.payload());
                if (parsed.mode() != OpusPacket.Mode.CELT) {
                    continue;
                }
                for (int f = 0; f < parsed.frameCount(); f++) {
                    int offset = parsed.frameOffset(f);
                    int length = parsed.frameLength(f);
                    steps += compareOnOneFrame(parsed.data(), offset, length,
                            vector.name() + " packet " + packet.index() + " frame " + f);
                    bytes += length;
                    frames++;
                }
            }
        }
        System.out.println("RangeCoderReferenceTest: " + frames + " CELT frames, " + bytes
                + " bytes, " + steps + " symbols agreed with the RFC 6716 Appendix A decoder");
        assertEquals(18784, frames,
                "the suite holds 18784 CELT frames; a different number means a different vector set");
        assertTrue(steps > 500_000L,
                "only " + steps + " symbols were compared, too few to have covered the frame sizes"
                        + " CELT actually uses");
    }

    /**
     * Drives both decoders down the same script and fails at the first
     * disagreement.
     *
     * <p>The script is chosen from the generator alone and never from a decoded
     * value, so the two decoders stay on the same operation even after they
     * have diverged. Picking the next context from the last symbol would hide a
     * divergence behind a different script and report it many symbols late, or
     * not at all.
     *
     * @return how many symbols were compared
     */
    private static int compareOnOneFrame(byte[] data, int offset, int length, String where) {
        RangeDecoder mine = new RangeDecoder(data, offset, length);
        Reference ref = Reference.decoder(data, offset, length);
        assertEquals(ref.tell(), mine.tell(), where + ": tell disagrees before any symbol");
        assertEquals(Integer.toUnsignedLong(ref.rng), mine.finalRange(),
                where + ": rng disagrees before any symbol");

        RangeCoderTest.Lcg rnd = new RangeCoderTest.Lcg(0x0D1FF_0000_0001L + length);
        int budget = length * 8;
        int steps = 0;
        while ((mine.tell() < budget || steps < budget / 8 + OVERRUN_STEPS) && steps < 4096) {
            int kind = rnd.next(6);
            long theirs;
            long reference;
            switch (kind) {
                case 0 -> {
                    int logp = 1 + rnd.next(15);
                    theirs = mine.decodeBit(logp);
                    reference = ref.decBitLogp(logp);
                }
                case 1 -> {
                    int c = rnd.next(RangeCoderTest.ICDFS.length);
                    theirs = mine.decodeIcdf(RangeCoderTest.ICDFS[c], RangeCoderTest.ICDF_FTB[c]);
                    reference = ref.decIcdf(RangeCoderTest.ICDFS[c], RangeCoderTest.ICDF_FTB[c]);
                }
                case 2 -> {
                    int c = rnd.next(RangeCoderTest.CDFS.length);
                    theirs = mine.decodeSymbol(RangeCoderTest.CDFS[c], RangeCoderTest.CDF_TOTALS[c]);
                    reference = ref.decSymbol(RangeCoderTest.CDFS[c], RangeCoderTest.CDF_TOTALS[c]);
                }
                case 3 -> {
                    int count = RangeCoderTest.UNIFORM_COUNTS[
                            rnd.next(RangeCoderTest.UNIFORM_COUNTS.length)];
                    theirs = mine.decodeUniform(count);
                    reference = Integer.toUnsignedLong(ref.decUint(count));
                }
                case 4 -> {
                    int ftb = 1 + rnd.next(15);
                    int fs = mine.decodeBin(ftb);
                    mine.update(fs, fs + 1, 1 << ftb);
                    int rfs = ref.decodeBin(ftb);
                    ref.decUpdate(rfs, rfs + 1, 1 << ftb);
                    theirs = fs;
                    reference = rfs;
                }
                default -> {
                    int bits = rnd.next(RangeDecoder.MAX_RAW_BITS + 1);
                    theirs = Integer.toUnsignedLong(mine.decodeRawBits(bits));
                    reference = Integer.toUnsignedLong(ref.decBits(bits));
                }
            }
            // Compared by hand rather than through assertEquals so that the
            // message, which names a frame out of two and a half million bytes,
            // is built once on failure instead of three million times on the
            // way to a pass.
            if (theirs != reference) {
                fail(at(where, steps, kind) + ": decoded " + theirs
                        + " where the RFC 6716 Appendix A decoder decoded " + reference);
            }
            if (mine.finalRange() != Integer.toUnsignedLong(ref.rng)) {
                fail(at(where, steps, kind) + ": rng is " + mine.finalRange()
                        + " and the reference holds " + Integer.toUnsignedLong(ref.rng));
            }
            if (mine.tell() != ref.tell()) {
                fail(at(where, steps, kind) + ": ec_tell is " + mine.tell()
                        + " and the reference says " + ref.tell());
            }
            if (mine.tellFrac() != ref.tellFrac()) {
                fail(at(where, steps, kind) + ": ec_tell_frac is " + mine.tellFrac()
                        + " and the reference says " + ref.tellFrac());
            }
            steps++;
        }
        return steps;
    }

    private static String at(String where, int steps, int kind) {
        return where + " step " + steps + " (op " + kind + ")";
    }

    @Test
    @DisplayName("the encoder writes byte for byte what the reference implementation writes")
    void theEncoderProducesTheSameBytesAsTheReference() {
        RangeCoderTest.Lcg rnd = new RangeCoderTest.Lcg(0x0E_11C0_DE00_0001L);
        int compared = 0;
        long symbols = 0;
        for (int trial = 0; trial < 3000; trial++) {
            int ops = 1 + rnd.next(40);
            int[] kind = new int[ops];
            int[] context = new int[ops];
            int[] value = new int[ops];
            for (int i = 0; i < ops; i++) {
                kind[i] = rnd.next(5);
                context[i] = rnd.next(64);
                value[i] = rnd.next(1 << 20);
            }
            int cap = 32 + ops * 6;
            byte[] mineOut = new byte[cap];
            byte[] refOut = new byte[cap];
            RangeEncoder enc = new RangeEncoder(mineOut);
            Reference ref = Reference.encoder(refOut, cap);
            for (int i = 0; i < ops; i++) {
                encodeOne(enc, kind[i], context[i], value[i]);
                ref.encodeOne(kind[i], context[i], value[i]);
                assertEquals(Integer.toUnsignedLong(ref.rng), enc.finalRange(),
                        "trial " + trial + " op " + i + ": rng");
                assertEquals(ref.tell(), enc.tell(), "trial " + trial + " op " + i + ": ec_tell");
                assertEquals(ref.tellFrac(), enc.tellFrac(),
                        "trial " + trial + " op " + i + ": ec_tell_frac");
                symbols++;
            }
            enc.finish();
            ref.encDone();
            assertEquals(0, ref.error, "the reference implementation busted its own buffer");
            assertEquals(ref.offs, enc.rangeBytes(), "trial " + trial + ": ec_range_bytes");
            assertArrayEquals(refOut, mineOut, "trial " + trial + ": the encoded bytes differ");
            compared++;
        }
        System.out.println("RangeCoderReferenceTest: " + compared + " encoded streams, " + symbols
                + " symbols, byte-identical to the RFC 6716 Appendix A encoder");
        assertEquals(3000, compared);
    }

    private static void encodeOne(RangeEncoder enc, int kind, int context, int value) {
        switch (kind) {
            case 0 -> {
                int c = context % RangeCoderTest.CDFS.length;
                enc.encodeSymbol(RangeCoderTest.CDFS[c], RangeCoderTest.CDF_TOTALS[c],
                        value % (RangeCoderTest.CDFS[c].length - 1));
            }
            case 1 -> enc.encodeBit(value & 1, 1 + context % 15);
            case 2 -> {
                int c = context % RangeCoderTest.ICDFS.length;
                enc.encodeIcdf(value % RangeCoderTest.ICDFS[c].length,
                        RangeCoderTest.ICDFS[c], RangeCoderTest.ICDF_FTB[c]);
            }
            case 3 -> {
                int bits = 1 + context % RangeDecoder.MAX_RAW_BITS;
                enc.encodeRawBits(value & ((1 << bits) - 1), bits);
            }
            default -> {
                int count = RangeCoderTest.UNIFORM_COUNTS[
                        context % RangeCoderTest.UNIFORM_COUNTS.length];
                enc.encodeUniform(Integer.remainderUnsigned(value, count), count);
            }
        }
    }

    /**
     * The uniform integers at the edges of the format, against the reference.
     *
     * <p>RFC 6716 section 4.1.5 lets {@code ft} reach {@code 2**32 - 1}, which
     * is the one place in this layer where a signed Java {@code int} silently
     * means something else. CELT's pulse decoder is the caller that gets
     * there -- the number of ways to place K pulses in N samples runs right up
     * to the ceiling -- and nothing in this codebase calls it yet, so a wrong
     * split between the coded byte and the raw remainder would sit here
     * unnoticed until the first wideband stereo band decoded to noise.
     */
    @Test
    @DisplayName("uniform integers at both sides of every boundary match the reference, up to 2**32-1")
    void theWidestUniformIntegersMatchTheReference() {
        long[] counts = {
            2, 3, 255, 256, 257, 511, 512, 513, 65535, 65536, 65537,
            (1L << 24) - 1, 1L << 24, (1L << 24) + 1,
            (1L << 31) - 1, 1L << 31, (1L << 31) + 1,
            (1L << 32) - 2, (1L << 32) - 1,
        };
        RangeCoderTest.Lcg rnd = new RangeCoderTest.Lcg(0x0_01F0_0000_0001L);
        int checked = 0;
        for (long count : counts) {
            long[] values = {
                0, 1, count / 3, count / 2, count - 2, count - 1,
                Long.remainderUnsigned(rnd.next(Integer.MAX_VALUE), count),
            };
            for (long value : values) {
                if (value < 0 || value >= count) {
                    continue;
                }
                byte[] mineOut = new byte[32];
                byte[] refOut = new byte[32];
                RangeEncoder enc = new RangeEncoder(mineOut);
                Reference refEnc = Reference.encoder(refOut, 32);
                enc.encodeUniformWide(value, count);
                refEnc.encUint((int) value, (int) count);
                enc.finish();
                refEnc.encDone();
                assertArrayEquals(refOut, mineOut,
                        "encoding " + value + " of " + count + " produced different bytes");

                RangeDecoder dec = new RangeDecoder(mineOut);
                Reference refDec = Reference.decoder(refOut, 0, 32);
                long got = dec.decodeUniformWide(count);
                long want = Integer.toUnsignedLong(refDec.decUint((int) count));
                assertEquals(want, got, "decoding " + value + " of " + count);
                assertEquals(value, got, "the round trip lost " + value + " of " + count);
                assertEquals(Integer.toUnsignedLong(refDec.rng), dec.finalRange(),
                        "rng after " + value + " of " + count);
                checked++;
            }
        }
        assertTrue(checked >= 100, "only " + checked + " uniform integers were checked");
    }

    /**
     * A decoder pointed at a new frame must not remember the last one.
     *
     * <p>{@link RangeDecoder#init} exists so the CELT decoder can keep one of
     * these on the audio thread and run every frame of every packet through it
     * without allocating, so the frame before is very often a frame that threw:
     * a corrupt packet aborts between {@code ec_decode()} and
     * {@code ec_dec_update()} and leaves the saved divisor behind. If that
     * divisor survives {@code init}, the check that an {@code update()} has a
     * symbol to finish is satisfied by the previous frame's arithmetic, and the
     * multiply runs with a divisor taken from a range the new frame never had.
     * The result is a frame decoded with the wrong {@code rng}, which is not a
     * dropped frame -- it is a frame of audio that comes out plausible and
     * wrong, and every frame after it in the packet with it.
     *
     * <p>The total is deliberately the same one the stale divisor was computed
     * from, so nothing but {@code init} clearing its own state can catch this.
     */
    // The failure this guards against is a loop that never ends. A deadline on
    // the calling thread is no use against one -- JUnit's default timeout is
    // only noticed once the method returns, which it never does -- so the test
    // has to run somewhere the runner can outlive.
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("a decoder pointed at a new frame forgets the half-finished symbol of the old one")
    void reusingADecoderCannotCarryAHalfFinishedSymbolIntoTheNextFrame() {
        byte[] first = {(byte) 0x80, 0x37, (byte) 0xC1, 0x04, (byte) 0x9E, 0x2B};
        byte[] second = {0x11, 0x22, 0x33, 0x44, 0x55, 0x66};

        RangeDecoder dec = new RangeDecoder(first);
        // Move rng off the 2**31 every decoder initialises to, or the divisor
        // the old frame leaves is the same one the new frame would compute and
        // carrying it over would be harmless.
        dec.decodeBit(3);
        dec.decodeIcdf(RangeCoderTest.ICDFS[0], RangeCoderTest.ICDF_FTB[0]);
        assertTrue(dec.finalRange() != 1L << 31,
                "the setup left rng where a fresh decoder starts, so a stale divisor from it"
                        + " would decode the same and this test would prove nothing");
        dec.decode(2);
        dec.init(second, 0, second.length);

        RangeCoderException e = assertThrows(RangeCoderException.class,
                () -> dec.update(0, 1, 2),
                "update() was accepted on a freshly initialised decoder that had not decoded"
                        + " anything, so the divisor from the previous frame was still there");
        assertTrue(e.getMessage() != null && !e.getMessage().isEmpty(),
                "a range coder fault must say what happened");

        // And the decoder is still usable afterwards, which is the point of
        // failing rather than looping.
        Reference ref = Reference.decoder(second, 0, second.length);
        assertEquals(ref.decIcdf(RangeCoderTest.ICDFS[0], RangeCoderTest.ICDF_FTB[0]),
                dec.decodeIcdf(RangeCoderTest.ICDFS[0], RangeCoderTest.ICDF_FTB[0]),
                "a decoder that refused a bad update() must still decode the frame it was given");
        assertEquals(Integer.toUnsignedLong(ref.rng), dec.finalRange());
    }

    /**
     * The other way the same loop runs away, without any reuse at all.
     *
     * <p>{@code ec_dec_update()} finishes a division {@code ec_decode()}
     * started, and the reference relies on that pairing without checking it:
     * every call site in {@code celt/bands.c} and {@code celt/laplace.c} passes
     * back the same {@code ft} it decoded with. Handed a larger one, the saved
     * divisor multiplies up past {@code rng} and the range goes to zero, which
     * renormalisation can never lift back above 2**23. That is a caller's
     * mistake rather than a stream's, but it is one an audio thread cannot
     * survive, so it has to be refused rather than entered.
     */
    @Test
    @Timeout(value = 30, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    @DisplayName("update() refuses a context total the divisor waiting for it was not computed from")
    void updateRefusesATotalItsDivisorWasNotComputedFrom() {
        byte[] frame = {(byte) 0x80, 0, 0, 0, 0, 0};

        RangeDecoder dec = new RangeDecoder(frame);
        dec.decode(1);
        RangeCoderException e = assertThrows(RangeCoderException.class,
                () -> dec.update(0, 65534, 65535),
                "update() took a total of 65535 from a divisor computed for a total of 1");
        assertTrue(e.getMessage().contains("65535") && e.getMessage().contains("1"),
                "the refusal must name both totals: " + e.getMessage());

        // The pairing the format actually uses still goes through untouched.
        RangeDecoder good = new RangeDecoder(frame);
        Reference ref = Reference.decoder(frame, 0, frame.length);
        int fs = good.decodeBin(15);
        good.update(fs, fs + 1, 1 << 15);
        int refFs = ref.decodeBin(15);
        ref.decUpdate(refFs, refFs + 1, 1 << 15);
        assertEquals(refFs, fs, "ec_decode_bin");
        assertEquals(Integer.toUnsignedLong(ref.rng), good.finalRange(), "rng after ec_dec_update");
    }

    /**
     * The shortest frames the format allows, against the reference.
     *
     * <p>A zero-byte frame is not a malformed packet: RFC 6716 section 3.2.1
     * allows a frame length of zero, and the decoder must read it as an endless
     * run of zero bits rather than fault. One and two byte frames are where the
     * forward reader and the backward reader are looking at the same byte from
     * opposite ends on the very first symbol.
     */
    @Test
    @DisplayName("frames of zero, one and two bytes decode exactly as the reference does")
    void theShortestFramesMatchTheReference() {
        int checked = 0;
        for (int length = 0; length <= 2; length++) {
            int patterns = length == 0 ? 1 : 1 << (8 * length);
            for (int pattern = 0; pattern < patterns; pattern++) {
                byte[] frame = new byte[length];
                for (int i = 0; i < length; i++) {
                    frame[i] = (byte) (pattern >>> (8 * i));
                }
                RangeDecoder mine = new RangeDecoder(frame);
                Reference ref = Reference.decoder(frame, 0, length);
                for (int step = 0; step < 24; step++) {
                    int want = ref.decIcdf(RangeCoderTest.ICDFS[1], RangeCoderTest.ICDF_FTB[1]);
                    int got = mine.decodeIcdf(RangeCoderTest.ICDFS[1], RangeCoderTest.ICDF_FTB[1]);
                    int wantRaw = ref.decBits(5);
                    int gotRaw = mine.decodeRawBits(5);
                    if (want != got || mine.finalRange() != Integer.toUnsignedLong(ref.rng)
                            || mine.tellFrac() != ref.tellFrac() || wantRaw != gotRaw) {
                        fail("frame " + Arrays.toString(frame) + " step " + step + ": decoded "
                                + got + "/" + gotRaw + " with rng " + mine.finalRange()
                                + " and ec_tell_frac " + mine.tellFrac()
                                + " where the reference decoded " + want + "/" + wantRaw
                                + " with rng " + Integer.toUnsignedLong(ref.rng)
                                + " and ec_tell_frac " + ref.tellFrac());
                    }
                }
                checked++;
            }
        }
        assertEquals(1 + 256 + 65536, checked);
    }

    /**
     * The range coder of RFC 6716 Appendix A, transcribed statement for
     * statement.
     *
     * <p>A port of {@code celt/entdec.c}, {@code celt/entenc.c} and
     * {@code ec_tell_frac} in {@code celt/entcode.c}. Deliberately not tidied:
     * the names, the order of the statements and the branches are the C's, so
     * that anyone can lay the two side by side. The C snippet each method comes
     * from is quoted above it.
     *
     * <p>State is {@code int} holding an unsigned 32-bit value, with
     * {@link Integer#divideUnsigned} and {@link Integer#compareUnsigned} where C
     * relies on the type. That is the opposite choice from {@link RangeDecoder},
     * which uses {@code long} and never wraps, and the difference is the point:
     * anywhere the C is relying on wrap-around, these two disagree and the
     * comparison catches it.
     */
    private static final class Reference {

        private static final int EC_SYM_BITS = 8;
        private static final int EC_CODE_BITS = 32;
        private static final int EC_SYM_MAX = (1 << EC_SYM_BITS) - 1;
        private static final int EC_CODE_SHIFT = EC_CODE_BITS - EC_SYM_BITS - 1;
        private static final int EC_CODE_TOP = 1 << (EC_CODE_BITS - 1);
        private static final int EC_CODE_BOT = EC_CODE_TOP >>> EC_SYM_BITS;
        private static final int EC_CODE_EXTRA = (EC_CODE_BITS - 2) % EC_SYM_BITS + 1;
        private static final int EC_UINT_BITS = 8;
        private static final int EC_WINDOW_SIZE = 32;
        private static final int BITRES = 3;

        private byte[] buf;
        private int base;
        private int storage;
        private int offs;
        private int endOffs;
        private int endWindow;
        private int nendBits;
        private int nbitsTotal;
        private int rng;
        private int val;
        private int ext;
        private int rem;
        private int error;

        static Reference decoder(byte[] buf, int base, int storage) {
            Reference r = new Reference();
            r.buf = buf;
            r.base = base;
            r.storage = storage;
            r.endOffs = 0;
            r.endWindow = 0;
            r.nendBits = 0;
            r.nbitsTotal = EC_CODE_BITS + 1
                    - ((EC_CODE_BITS - EC_CODE_EXTRA) / EC_SYM_BITS) * EC_SYM_BITS;
            r.offs = 0;
            r.rng = 1 << EC_CODE_EXTRA;
            r.rem = r.readByte();
            r.val = r.rng - 1 - (r.rem >>> (EC_SYM_BITS - EC_CODE_EXTRA));
            r.error = 0;
            r.decNormalize();
            return r;
        }

        static Reference encoder(byte[] buf, int size) {
            Reference r = new Reference();
            r.buf = buf;
            r.base = 0;
            r.endOffs = 0;
            r.endWindow = 0;
            r.nendBits = 0;
            r.nbitsTotal = EC_CODE_BITS + 1;
            r.offs = 0;
            r.rng = EC_CODE_TOP;
            r.rem = -1;
            r.val = 0;
            r.ext = 0;
            r.storage = size;
            r.error = 0;
            return r;
        }

        private static int ecMini(int a, int b) {
            return Integer.compareUnsigned(a, b) < 0 ? a : b;
        }

        /** {@code return _this->offs<_this->storage?_this->buf[_this->offs++]:0;} */
        private int readByte() {
            return offs < storage ? buf[base + offs++] & 0xFF : 0;
        }

        /** {@code return _this->end_offs<_this->storage?_this->buf[_this->storage-++(_this->end_offs)]:0;} */
        private int readByteFromEnd() {
            return endOffs < storage ? buf[base + storage - ++endOffs] & 0xFF : 0;
        }

        /**
         * <pre>
         * while(_this-&gt;rng&lt;=EC_CODE_BOT){
         *   _this-&gt;nbits_total+=EC_SYM_BITS;
         *   _this-&gt;rng&lt;&lt;=EC_SYM_BITS;
         *   sym=_this-&gt;rem;
         *   _this-&gt;rem=ec_read_byte(_this);
         *   sym=(sym&lt;&lt;EC_SYM_BITS|_this-&gt;rem)&gt;&gt;(EC_SYM_BITS-EC_CODE_EXTRA);
         *   _this-&gt;val=((_this-&gt;val&lt;&lt;EC_SYM_BITS)+(EC_SYM_MAX&amp;~sym))&amp;(EC_CODE_TOP-1);
         * }
         * </pre>
         */
        private void decNormalize() {
            while (Integer.compareUnsigned(rng, EC_CODE_BOT) <= 0) {
                nbitsTotal += EC_SYM_BITS;
                rng <<= EC_SYM_BITS;
                int sym = rem;
                rem = readByte();
                sym = (sym << EC_SYM_BITS | rem) >>> (EC_SYM_BITS - EC_CODE_EXTRA);
                val = ((val << EC_SYM_BITS) + (EC_SYM_MAX & ~sym)) & (EC_CODE_TOP - 1);
            }
        }

        /** {@code _this->ext=_this->rng/_ft; s=_this->val/_this->ext; return _ft-EC_MINI(s+1,_ft);} */
        int decode(int ft) {
            ext = Integer.divideUnsigned(rng, ft);
            int s = Integer.divideUnsigned(val, ext);
            return ft - ecMini(s + 1, ft);
        }

        /** {@code _this->ext=_this->rng>>_bits; s=_this->val/_this->ext; return (1U<<_bits)-EC_MINI(s+1U,1U<<_bits);} */
        int decodeBin(int bits) {
            ext = rng >>> bits;
            int s = Integer.divideUnsigned(val, ext);
            return (1 << bits) - ecMini(s + 1, 1 << bits);
        }

        /** {@code s=IMUL32(_this->ext,_ft-_fh); _this->val-=s; _this->rng=_fl>0?IMUL32(_this->ext,_fh-_fl):_this->rng-s;} */
        void decUpdate(int fl, int fh, int ft) {
            int s = ext * (ft - fh);
            val -= s;
            rng = fl > 0 ? ext * (fh - fl) : rng - s;
            decNormalize();
        }

        int decSymbol(int[] cumulative, int total) {
            int fs = decode(total);
            int k = 0;
            while (cumulative[k + 1] <= fs) {
                k++;
            }
            decUpdate(cumulative[k], cumulative[k + 1], total);
            return k;
        }

        /** {@code r=rng; d=val; s=r>>_logp; ret=d<s; if(!ret)val=d-s; rng=ret?s:r-s;} */
        int decBitLogp(int logp) {
            int r = rng;
            int d = val;
            int s = r >>> logp;
            int ret = Integer.compareUnsigned(d, s) < 0 ? 1 : 0;
            if (ret == 0) {
                val = d - s;
            }
            rng = ret != 0 ? s : r - s;
            decNormalize();
            return ret;
        }

        /** {@code s=rng; d=val; r=s>>_ftb; ret=-1; do{t=s; s=IMUL32(r,_icdf[++ret]);}while(d<s); val=d-s; rng=t-s;} */
        int decIcdf(short[] icdf, int ftb) {
            int s = rng;
            int d = val;
            int r = s >>> ftb;
            int t;
            int ret = -1;
            do {
                t = s;
                s = r * (icdf[++ret] & 0xFFFF);
            } while (Integer.compareUnsigned(d, s) < 0);
            val = d - s;
            rng = t - s;
            decNormalize();
            return ret;
        }

        /**
         * <pre>
         * _ft--; ftb=EC_ILOG(_ft);
         * if(ftb&gt;EC_UINT_BITS){
         *   ftb-=EC_UINT_BITS; ft=(_ft&gt;&gt;ftb)+1;
         *   s=ec_decode(_this,ft); ec_dec_update(_this,s,s+1,ft);
         *   t=(opus_uint32)s&lt;&lt;ftb|ec_dec_bits(_this,ftb);
         *   if(t&lt;=_ft)return t; _this-&gt;error=1; return _ft;
         * }
         * else{_ft++; s=ec_decode(_this,_ft); ec_dec_update(_this,s,s+1,_ft); return s;}
         * </pre>
         */
        int decUint(int ft) {
            ft--;
            int ftb = ecIlog(ft);
            if (ftb > EC_UINT_BITS) {
                ftb -= EC_UINT_BITS;
                int f = (ft >>> ftb) + 1;
                int s = decode(f);
                decUpdate(s, s + 1, f);
                int t = s << ftb | decBits(ftb);
                if (Integer.compareUnsigned(t, ft) <= 0) {
                    return t;
                }
                error = 1;
                return ft;
            }
            ft++;
            int s = decode(ft);
            decUpdate(s, s + 1, ft);
            return s;
        }

        /**
         * <pre>
         * window=end_window; available=nend_bits;
         * if(available&lt;_bits){do{window|=ec_read_byte_from_end(_this)&lt;&lt;available;
         *   available+=EC_SYM_BITS;}while(available&lt;=EC_WINDOW_SIZE-EC_SYM_BITS);}
         * ret=window&amp;((1&lt;&lt;_bits)-1); window&gt;&gt;=_bits; available-=_bits;
         * </pre>
         */
        int decBits(int bits) {
            int window = endWindow;
            int available = nendBits;
            if (available < bits) {
                do {
                    window |= readByteFromEnd() << available;
                    available += EC_SYM_BITS;
                } while (available <= EC_WINDOW_SIZE - EC_SYM_BITS);
            }
            int ret = window & ((1 << bits) - 1);
            window >>>= bits;
            available -= bits;
            endWindow = window;
            nendBits = available;
            nbitsTotal += bits;
            return ret;
        }

        /** {@code if(_this->offs+_this->end_offs>=_this->storage)return -1; _this->buf[_this->offs++]=_value;} */
        private void writeByte(int value) {
            if (offs + endOffs >= storage) {
                error = -1;
                return;
            }
            buf[base + offs++] = (byte) value;
        }

        private void writeByteAtEnd(int value) {
            if (offs + endOffs >= storage) {
                error = -1;
                return;
            }
            buf[base + storage - ++endOffs] = (byte) value;
        }

        /**
         * <pre>
         * if(_c!=EC_SYM_MAX){
         *   carry=_c&gt;&gt;EC_SYM_BITS;
         *   if(_this-&gt;rem&gt;=0)ec_write_byte(_this,_this-&gt;rem+carry);
         *   if(_this-&gt;ext&gt;0){sym=(EC_SYM_MAX+carry)&amp;EC_SYM_MAX;
         *     do ec_write_byte(_this,sym); while(--(_this-&gt;ext)&gt;0);}
         *   _this-&gt;rem=_c&amp;EC_SYM_MAX;
         * }
         * else _this-&gt;ext++;
         * </pre>
         */
        private void carryOut(int c) {
            if (c != EC_SYM_MAX) {
                int carry = c >> EC_SYM_BITS;
                if (rem >= 0) {
                    writeByte(rem + carry);
                }
                if (ext > 0) {
                    int sym = (EC_SYM_MAX + carry) & EC_SYM_MAX;
                    do {
                        writeByte(sym);
                    } while (--ext > 0);
                }
                rem = c & EC_SYM_MAX;
            } else {
                ext++;
            }
        }

        private void encNormalize() {
            while (Integer.compareUnsigned(rng, EC_CODE_BOT) <= 0) {
                carryOut(val >>> EC_CODE_SHIFT);
                val = (val << EC_SYM_BITS) & (EC_CODE_TOP - 1);
                rng <<= EC_SYM_BITS;
                nbitsTotal += EC_SYM_BITS;
            }
        }

        /**
         * <pre>
         * r=_this-&gt;rng/_ft;
         * if(_fl&gt;0){_this-&gt;val+=_this-&gt;rng-IMUL32(r,(_ft-_fl)); _this-&gt;rng=IMUL32(r,(_fh-_fl));}
         * else _this-&gt;rng-=IMUL32(r,(_ft-_fh));
         * </pre>
         */
        void encode(int fl, int fh, int ft) {
            int r = Integer.divideUnsigned(rng, ft);
            if (fl > 0) {
                val += rng - r * (ft - fl);
                rng = r * (fh - fl);
            } else {
                rng -= r * (ft - fh);
            }
            encNormalize();
        }

        /** {@code r=rng; l=val; s=r>>_logp; r-=s; if(_val)val=l+r; rng=_val?s:r;} */
        void encBitLogp(int bit, int logp) {
            int r = rng;
            int l = val;
            int s = r >>> logp;
            r -= s;
            if (bit != 0) {
                val = l + r;
            }
            rng = bit != 0 ? s : r;
            encNormalize();
        }

        /**
         * <pre>
         * r=_this-&gt;rng&gt;&gt;_ftb;
         * if(_s&gt;0){_this-&gt;val+=_this-&gt;rng-IMUL32(r,_icdf[_s-1]);
         *   _this-&gt;rng=IMUL32(r,_icdf[_s-1]-_icdf[_s]);}
         * else _this-&gt;rng-=IMUL32(r,_icdf[_s]);
         * </pre>
         */
        void encIcdf(int s, short[] icdf, int ftb) {
            int r = rng >>> ftb;
            if (s > 0) {
                val += rng - r * (icdf[s - 1] & 0xFFFF);
                rng = r * ((icdf[s - 1] & 0xFFFF) - (icdf[s] & 0xFFFF));
            } else {
                rng -= r * (icdf[s] & 0xFFFF);
            }
            encNormalize();
        }

        /**
         * <pre>
         * _ft--; ftb=EC_ILOG(_ft);
         * if(ftb&gt;EC_UINT_BITS){ftb-=EC_UINT_BITS; ft=(_ft&gt;&gt;ftb)+1; fl=_fl&gt;&gt;ftb;
         *   ec_encode(_this,fl,fl+1,ft); ec_enc_bits(_this,_fl&amp;((1&lt;&lt;ftb)-1),ftb);}
         * else ec_encode(_this,_fl,_fl+1,_ft+1);
         * </pre>
         */
        void encUint(int fl, int ft) {
            ft--;
            int ftb = ecIlog(ft);
            if (ftb > EC_UINT_BITS) {
                ftb -= EC_UINT_BITS;
                int f = (ft >>> ftb) + 1;
                int l = fl >>> ftb;
                encode(l, l + 1, f);
                encBits(fl & ((1 << ftb) - 1), ftb);
            } else {
                encode(fl, fl + 1, ft + 1);
            }
        }

        /**
         * <pre>
         * window=end_window; used=nend_bits;
         * if(used+_bits&gt;EC_WINDOW_SIZE){do{ec_write_byte_at_end(_this,window&amp;EC_SYM_MAX);
         *   window&gt;&gt;=EC_SYM_BITS; used-=EC_SYM_BITS;}while(used&gt;=EC_SYM_BITS);}
         * window|=_fl&lt;&lt;used; used+=_bits;
         * </pre>
         */
        void encBits(int fl, int bits) {
            int window = endWindow;
            int used = nendBits;
            if (used + bits > EC_WINDOW_SIZE) {
                do {
                    writeByteAtEnd(window & EC_SYM_MAX);
                    window >>>= EC_SYM_BITS;
                    used -= EC_SYM_BITS;
                } while (used >= EC_SYM_BITS);
            }
            window |= fl << used;
            used += bits;
            endWindow = window;
            nendBits = used;
            nbitsTotal += bits;
        }

        /**
         * <pre>
         * l=EC_CODE_BITS-EC_ILOG(_this-&gt;rng); msk=(EC_CODE_TOP-1)&gt;&gt;l;
         * end=(_this-&gt;val+msk)&amp;~msk;
         * if((end|msk)&gt;=_this-&gt;val+_this-&gt;rng){l++; msk&gt;&gt;=1; end=(_this-&gt;val+msk)&amp;~msk;}
         * while(l&gt;0){ec_enc_carry_out(_this,end&gt;&gt;EC_CODE_SHIFT);
         *   end=(end&lt;&lt;EC_SYM_BITS)&amp;(EC_CODE_TOP-1); l-=EC_SYM_BITS;}
         * if(_this-&gt;rem&gt;=0||_this-&gt;ext&gt;0)ec_enc_carry_out(_this,0);
         * ... flush the raw-bit window, clear the gap, OR the remainder in ...
         * </pre>
         */
        void encDone() {
            int l = EC_CODE_BITS - ecIlog(rng);
            int msk = (EC_CODE_TOP - 1) >>> l;
            int end = (val + msk) & ~msk;
            if (Integer.compareUnsigned(end | msk, val + rng) >= 0) {
                l++;
                msk >>>= 1;
                end = (val + msk) & ~msk;
            }
            while (l > 0) {
                carryOut(end >>> EC_CODE_SHIFT);
                end = (end << EC_SYM_BITS) & (EC_CODE_TOP - 1);
                l -= EC_SYM_BITS;
            }
            if (rem >= 0 || ext > 0) {
                carryOut(0);
            }
            int window = endWindow;
            int used = nendBits;
            while (used >= EC_SYM_BITS) {
                writeByteAtEnd(window & EC_SYM_MAX);
                window >>>= EC_SYM_BITS;
                used -= EC_SYM_BITS;
            }
            if (error == 0) {
                Arrays.fill(buf, base + offs, base + storage - endOffs, (byte) 0);
                if (used > 0) {
                    if (endOffs >= storage) {
                        error = -1;
                    } else {
                        l = -l;
                        if (offs + endOffs >= storage && l < used) {
                            window &= (1 << l) - 1;
                            error = -1;
                        }
                        buf[base + storage - endOffs - 1] |= (byte) window;
                    }
                }
            }
        }

        void encodeOne(int kind, int context, int value) {
            switch (kind) {
                case 0 -> {
                    int c = context % RangeCoderTest.CDFS.length;
                    int[] cdf = RangeCoderTest.CDFS[c];
                    int k = value % (cdf.length - 1);
                    encode(cdf[k], cdf[k + 1], RangeCoderTest.CDF_TOTALS[c]);
                }
                case 1 -> encBitLogp(value & 1, 1 + context % 15);
                case 2 -> {
                    int c = context % RangeCoderTest.ICDFS.length;
                    encIcdf(value % RangeCoderTest.ICDFS[c].length,
                            RangeCoderTest.ICDFS[c], RangeCoderTest.ICDF_FTB[c]);
                }
                case 3 -> {
                    int bits = 1 + context % RangeDecoder.MAX_RAW_BITS;
                    encBits(value & ((1 << bits) - 1), bits);
                }
                default -> {
                    int count = RangeCoderTest.UNIFORM_COUNTS[
                            context % RangeCoderTest.UNIFORM_COUNTS.length];
                    encUint(Integer.remainderUnsigned(value, count), count);
                }
            }
        }

        /** {@code EC_ILOG}, one more than the index of the highest set bit. */
        private static int ecIlog(int v) {
            return 32 - Integer.numberOfLeadingZeros(v);
        }

        /** {@code return _this->nbits_total-EC_ILOG(_this->rng);} */
        int tell() {
            return nbitsTotal - ecIlog(rng);
        }

        /**
         * <pre>
         * nbits=_this-&gt;nbits_total&lt;&lt;BITRES; l=EC_ILOG(_this-&gt;rng); r=_this-&gt;rng&gt;&gt;(l-16);
         * for(i=BITRES;i--&gt;0;){r=r*r&gt;&gt;15; b=r&gt;&gt;16; l=l&lt;&lt;1|b; r&gt;&gt;=b;}
         * return nbits-l;
         * </pre>
         */
        int tellFrac() {
            int nbits = nbitsTotal << BITRES;
            int l = ecIlog(rng);
            int r = rng >>> (l - 16);
            for (int i = BITRES; i-- > 0;) {
                r = (int) ((Integer.toUnsignedLong(r) * Integer.toUnsignedLong(r)) >>> 15);
                int b = r >>> 16;
                l = l << 1 | b;
                r >>>= b;
            }
            return nbits - l;
        }
    }
}
