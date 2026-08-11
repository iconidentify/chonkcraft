package net.chonkbase.assetpack.codec.opus;

import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Whether the range coder puts back exactly what it took in, and what it does
 * when it cannot.
 *
 * <p>This is the layer every other part of Opus stands on and it is the only
 * part that is exactly specified: RFC 6716 section 4.1 is integer arithmetic
 * throughout, so a correct decoder is bit-exact and an incorrect one is wrong
 * from the first symbol that differs. There is nothing to sound plausible about
 * -- a range coder that is off by one anywhere turns the rest of the frame into
 * noise -- with one exception, and it is the reason half of this file exists.
 *
 * <p>The exception is {@code ec_tell} and {@code ec_tell_frac}. CELT's bit
 * allocator reads them between every band to decide how many bits are left, so
 * a decoder whose count is one bit adrift from the encoder's still decodes
 * every symbol it is given, and hands the synthesis stage a different bit
 * allocation from the one the encoder chose. Nothing fails. The frame comes out
 * as music, in the right key, at the wrong resolution. So the counts are pinned
 * here against a table worked out by hand from the RFC rather than against
 * whatever this implementation happens to produce.
 *
 * <p>The other thing being proved is that no byte string, however malformed,
 * can make this read outside its buffer. A range decoder is handed
 * attacker-controlled bytes by definition, and it deliberately reads several
 * bytes past where the symbols end.
 */
class RangeCoderTest {

    private static final int OP_SYMBOL = 0;
    private static final int OP_BIT = 1;
    private static final int OP_ICDF = 2;
    private static final int OP_RAW = 3;
    private static final int OP_UNIFORM = 4;

    /** Cumulative frequency tables: {@code fl[0]..fl[n]}, so n symbols in n+1 entries. */
    static final int[][] CDFS = {
        {0, 3, 8},
        {0, 1, 5, 9, 16},
        {0, 7, 11, 13, 14, 15},
        {0, 1000, 20000, 40000, 65535},
        {0, 1, 2, 3, 4, 5, 6, 7, 8},
    };

    static final int[] CDF_TOTALS = {8, 16, 15, 65535, 8};

    /** Inverse cumulative tables, each terminated by the zero the format requires. */
    static final short[][] ICDFS = {
        {224, 96, 32, 0},
        {87, 44, 21, 8, 0},
        {24, 8, 0},
        {1, 0},
    };

    static final int[] ICDF_FTB = {8, 8, 5, 1};

    /**
     * Totals for uniform integers, chosen to straddle the eight-bit boundary.
     *
     * <p>At 256 and below the whole value goes through the range coder; above
     * it, the top eight bits are coded and the rest become raw bits at the far
     * end of the buffer. 256 and 257 sit either side of that switch, and
     * {@link Integer#MAX_VALUE} is the widest split the format allows: eight
     * coded bits and twenty-three raw ones.
     */
    static final int[] UNIFORM_COUNTS = {
        2, 3, 5, 17, 256, 257, 1000, 65536, 1000000, Integer.MAX_VALUE,
    };

    /**
     * What {@code ec_tell} must return after n raw bits, worked out from
     * RFC 6716 section 4.1.6 rather than from this code.
     *
     * <p>A decoder finishes initialising with {@code nbits_total} at 33 and
     * {@code rng} at exactly 2**31, because renormalisation ran three times
     * over the 128 it starts at. {@code ec_tell} is
     * {@code nbits_total - ilog(rng)}, and {@code ilog(2**31)} is 32, so a
     * decoder that has read nothing reports 1: the bit RFC 6716 section 4.1.6.1
     * reserves for terminating the encoder's stream. Reading a raw bit adds one
     * to {@code nbits_total} and does not touch {@code rng}, so from there the
     * count is n+1 exactly, with no rounding anywhere in it.
     */
    private static final int[] TELL_AFTER_RAW_BITS = {
        1, 2, 3, 4, 5, 6, 7, 8,
        9, 10, 11, 12, 13, 14, 15, 16,
        17, 18, 19, 20, 21, 22, 23, 24,
        25, 26, 27, 28, 29, 30, 31, 32,
        33,
    };

    /**
     * A fixed-seed linear congruential generator.
     *
     * <p>Not {@link java.util.Random}: a corpus that differs between JVMs, or
     * between runs, cannot be quoted in a bug report, and a range coder failure
     * is only useful if the exact byte sequence that produced it can be
     * reproduced.
     */
    static final class Lcg {

        private long state;

        Lcg(long seed) {
            this.state = seed;
        }

        int next(int bound) {
            state = state * 6364136223846793005L + 1442695040888963407L;
            return (int) (state >>> 33) % bound;
        }
    }

    /** One encoded sequence: an operation kind, a context, and a value per step. */
    private record Program(int[] kind, int[] context, int[] value) {

        int length() {
            return kind.length;
        }
    }

    @Test
    @DisplayName("two thousand random symbol sequences come back out of the decoder unchanged")
    void everySymbolSequenceSurvivesARoundTrip() {
        Lcg rnd = new Lcg(0x0F51_1EED_0000_0001L);
        int totalOps = 0;
        for (int trial = 0; trial < 2000; trial++) {
            Program program = randomProgram(rnd, rnd.next(41));
            byte[] out = new byte[16 + program.length() * 5];
            int[] encoderTell = new int[program.length() + 1];
            RangeEncoder enc = new RangeEncoder(out);
            encodeProgram(enc, program, encoderTell);
            int length = enc.finish();
            assertTrue(length <= out.length,
                    "the encoder claimed more bytes than it was given, trial " + trial);
            checkProgramDecodes(program, out, length, enc.finalRange(), encoderTell, trial);
            totalOps += program.length();
        }
        assertTrue(totalOps > 30000,
                "the corpus was too small to have exercised anything: only " + totalOps
                        + " coded symbols over 2000 sequences");
    }

    @Test
    @DisplayName("shrinking the buffer to the byte either fits and decodes, or says it does not fit")
    void theEncoderNeverQuietlyTruncatesAStream() {
        Lcg rnd = new Lcg(0x00C0_FFEE_0000_0002L);
        int fitted = 0;
        int refused = 0;
        for (int trial = 0; trial < 200; trial++) {
            Program program = randomProgram(rnd, 1 + rnd.next(24));
            int cap = 16 + program.length() * 5;
            int smallestThatFits = -1;
            for (int size = 0; size <= cap; size++) {
                byte[] out = new byte[size];
                int[] encoderTell = new int[program.length() + 1];
                RangeEncoder enc = new RangeEncoder(out);
                int length;
                try {
                    encodeProgram(enc, program, encoderTell);
                    length = enc.finish();
                } catch (RangeCoderException e) {
                    assertNotNull(e.getMessage(),
                            "the encoder refused a " + size + "-byte buffer without saying why");
                    refused++;
                    continue;
                }
                if (smallestThatFits < 0) {
                    smallestThatFits = size;
                }
                // The point of the sweep: a size the encoder accepted must
                // decode back to the same symbols. An encoder that dropped a
                // byte and said nothing would fail here and nowhere else.
                checkProgramDecodes(program, out, length, enc.finalRange(), encoderTell, trial);
                fitted++;
            }
            assertTrue(smallestThatFits > 0,
                    "trial " + trial + " fitted into a zero-byte buffer, so it proves nothing");
        }
        assertTrue(refused > 200, "the sweep never once ran a buffer short, so it tested only the"
                + " easy half: " + refused + " refusals over 200 sequences");
        assertTrue(fitted > 2000, "the sweep barely ever fitted: " + fitted + " successes");
    }

    @Test
    @DisplayName("a fresh decoder reports one bit used, and each raw bit adds exactly one")
    void tellCountsRawBitsExactlyAsTheRfcSays() {
        byte[] frame = new byte[8];
        for (int i = 0; i < frame.length; i++) {
            frame[i] = (byte) (0xA5 ^ i);
        }
        RangeDecoder dec = new RangeDecoder(frame);
        for (int n = 0; n < TELL_AFTER_RAW_BITS.length; n++) {
            assertEquals(TELL_AFTER_RAW_BITS[n], dec.tell(),
                    "ec_tell after " + n + " raw bits; CELT sizes every band against this"
                            + " number and an error here changes the allocation, not one symbol");
            assertEquals(8 * TELL_AFTER_RAW_BITS[n], dec.tellFrac(),
                    "ec_tell_frac after " + n + " raw bits: raw bits are whole bits, so the"
                            + " eighths must land exactly on a boundary");
            if (n < TELL_AFTER_RAW_BITS.length - 1) {
                dec.decodeRawBits(1);
            }
        }
    }

    @Test
    @DisplayName("one bit of an even context costs exactly one bit, on top of the reserved one")
    void tellCountsAnEvenlySplitSymbolExactly() {
        // rng starts at 2**31; a logp of 1 halves it either way, so ilog(rng)
        // falls to 31 and ec_tell rises from 1 to 2 with nothing rounded. This
        // is the only symbol in the format whose cost can be checked by hand,
        // and it is the anchor for the fractional count: 16 eighths, not 15 or
        // 17, which is what tells an off-by-one in ec_tell_frac's squaring loop
        // from a correct one.
        byte[] frame = {(byte) 0x80, 0x00, 0x00, 0x00};
        RangeDecoder dec = new RangeDecoder(frame);
        assertEquals(1, dec.tell(), "a decoder that has read nothing owes the termination bit");
        dec.decodeBit(1);
        assertEquals(2, dec.tell(), "one evenly split binary symbol costs exactly one bit");
        assertEquals(16, dec.tellFrac(),
                "ec_tell_frac must agree to the eighth, or CELT's last band gets the wrong"
                        + " number of pulses");
        dec.decodeBit(1);
        assertEquals(3, dec.tell(), "the second evenly split symbol costs one more bit");
        assertEquals(24, dec.tellFrac(), "and eight more eighths");
    }

    @Test
    @DisplayName("the fractional bit count always rounds up to the whole one")
    void tellFracAlwaysRoundsUpToTell() {
        Lcg rnd = new Lcg(0x0DEF_ACED_0000_0003L);
        int checks = 0;
        int fractional = 0;
        for (int trial = 0; trial < 400; trial++) {
            Program program = randomProgram(rnd, 1 + rnd.next(40));
            byte[] out = new byte[16 + program.length() * 5];
            RangeEncoder enc = new RangeEncoder(out);
            encodeProgram(enc, program, null);
            int length = enc.finish();
            RangeDecoder dec = new RangeDecoder(out, 0, length);
            for (int i = 0; i <= program.length(); i++) {
                int whole = dec.tell();
                int eighths = dec.tellFrac();
                assertEquals(whole, (eighths + 7) / 8,
                        "RFC 6716 section 4.1.6 guarantees ec_tell() == ceil(ec_tell_frac()/8);"
                                + " trial " + trial + " step " + i + " had " + whole
                                + " and " + eighths);
                assertEquals(encoderTellAfter(program, i, out.length), whole,
                        "the two ends must count the same bits after the same symbols;"
                                + " trial " + trial + " step " + i);
                if (eighths % 8 != 0) {
                    fractional++;
                }
                checks++;
                if (i < program.length()) {
                    decodeStep(dec, program, i);
                }
            }
        }
        assertTrue(checks > 5000, "only " + checks + " points were checked");
        assertTrue(fractional > 1000, "every single count landed on a whole bit, so the rounding"
                + " this test exists to check was never exercised: " + fractional + " of " + checks);
    }

    @Test
    @DisplayName("raw bits fill the frame to the last bit, and the one after it is reported")
    void theTwoCursorsMeetExactlyAtTheEndOfTheFrame() {
        for (int size = 1; size <= 24; size++) {
            byte[] out = new byte[size];
            int[] written = new int[size];
            Lcg rnd = new Lcg(0x00BA_D5EE_0000_0000L + size);
            RangeEncoder enc = new RangeEncoder(out);
            for (int i = 0; i < size; i++) {
                written[i] = rnd.next(256);
                enc.encodeRawBits(written[i], 8);
            }
            int length = enc.finish();
            assertEquals(size, length,
                    "a frame packed solid with raw bits must be transmitted whole");

            RangeDecoder dec = new RangeDecoder(out, 0, length);
            for (int i = 0; i < size; i++) {
                assertEquals(written[i], dec.decodeRawBits(8),
                        "raw byte " + i + " of " + size + " came back different, so every"
                                + " sign and pulse position after it is wrong too");
            }
            assertEquals(0, dec.rawBitsRemaining(),
                    "a " + size + "-byte frame holds exactly " + (size * 8) + " raw bits");
            assertFalse(dec.rawBitsOverread(),
                    "reading a frame's raw bits to the last one is not an overread; the reader"
                            + " fetches whole bytes ahead of what it hands out and a check that"
                            + " counted bytes touched would call this a fault");
            dec.checkRawBitsInBounds();

            dec.decodeRawBits(1);
            assertTrue(dec.rawBitsOverread(),
                    "the bit past the end of a " + size + "-byte frame was invented and must"
                            + " be reported as such");
            RangeCoderException e = assertThrows(RangeCoderException.class,
                    dec::checkRawBitsInBounds,
                    "a decoder that has run off the front of the frame must say so");
            assertTrue(e.getMessage().contains("ran off the front"),
                    "the overread must name what happened, not just fail: " + e.getMessage());
        }
    }

    @Test
    @DisplayName("an encoder asked for more bits than its buffer holds says so instead of dropping them")
    void anEncoderRefusesToOverrunItsBuffer() {
        RangeEncoder raw = new RangeEncoder(new byte[4]);
        RangeCoderException e = assertThrows(RangeCoderException.class, () -> {
            for (int i = 0; i < 64; i++) {
                raw.encodeRawBits(0xFF, 8);
            }
        }, "sixty-four bytes of raw bits cannot fit in four");
        assertTrue(e.getMessage().contains("4-byte buffer"),
                "the message must name the buffer that ran out: " + e.getMessage());

        RangeEncoder coded = new RangeEncoder(new byte[3]);
        RangeCoderException e2 = assertThrows(RangeCoderException.class, () -> {
            for (int i = 0; i < 200; i++) {
                coded.encodeSymbol(CDFS[3], CDF_TOTALS[3], 0);
            }
        }, "two hundred six-bit symbols cannot fit in three bytes");
        assertTrue(e2.getMessage().contains("3-byte buffer"),
                "the message must name the buffer that ran out: " + e2.getMessage());

        // The other way an encoder runs out, and the one upstream does not
        // notice until the stream is flushed: coding the topmost symbol of a
        // context over and over drives val to the top of the range, so every
        // byte produced is a 255 that might still be carried into and none of
        // them can be written yet. The pending run is what overflows, not the
        // buffer.
        RangeEncoder carries = new RangeEncoder(new byte[3]);
        RangeCoderException e3 = assertThrows(RangeCoderException.class, () -> {
            for (int i = 0; i < 200; i++) {
                carries.encodeSymbol(CDFS[3], CDF_TOTALS[3], 3);
            }
        }, "an unbounded run of carry-propagating bytes cannot fit in three");
        assertTrue(e3.getMessage().contains("carry-propagating"),
                "the message must name the pending carry run: " + e3.getMessage());
    }

    @Test
    @DisplayName("a range decoder handed a slice outside its array refuses it by name")
    void aSliceOutsideItsArrayIsRefused() {
        byte[] data = new byte[10];
        assertThrows(RangeCoderException.class, () -> new RangeDecoder(data, -1, 4),
                "a negative offset is not a frame");
        assertThrows(RangeCoderException.class, () -> new RangeDecoder(data, 8, 4),
                "a frame that ends past the array is not a frame");
        assertThrows(RangeCoderException.class, () -> new RangeDecoder(data, 0, 11),
                "a frame longer than the array is not a frame");
        RangeCoderException e = assertThrows(RangeCoderException.class,
                () -> new RangeDecoder(data, 0, -1), "a negative length is not a frame");
        assertNotNull(e.getMessage(), "the refusal must say what was wrong");
    }

    @Test
    @DisplayName("an empty frame decodes as all zeros rather than failing")
    void aZeroLengthFrameStillDecodes() {
        // A real one: RFC 6716 section 3.2.1 allows a frame of length zero, and
        // the range decoder must invent zeros for it rather than fault. A
        // decoder that threw here would drop every DTX packet in a stream.
        RangeDecoder dec = new RangeDecoder(new byte[0]);
        assertEquals(1, dec.tell(), "even an empty frame owes the termination bit");
        for (int i = 0; i < 40; i++) {
            assertEquals(0, dec.decodeBit(1),
                    "an empty frame is all zero bits and decodes as the zero symbol");
        }
        assertEquals(0, dec.rawBitsRead(), "no raw bits were asked for yet");
        dec.decodeRawBits(4);
        assertTrue(dec.rawBitsOverread(), "an empty frame has no raw bits to give");
    }

    @Test
    @DisplayName("every single-bit corruption of a real packet decodes or complains, never faults")
    void noSingleBitFlipCanMakeTheDecoderReadOutsideItsBuffer() {
        List<OpusTestVectors.Packet> packets = celtPackets();
        Assumptions.assumeTrue(packets != null, OpusTestVectors.skipReason());
        byte[] packet = packets.get(0).payload();
        assertTrue(packet.length >= 300,
                "the sweep needs a packet of at least 300 bytes and got " + packet.length);
        // The first 300 bytes of a real CELT packet: long enough that every one
        // of the 2048 flips lands inside a frame the decoder will actually
        // read, short enough that the sweep is seconds rather than minutes.
        byte[] original = new byte[300];
        System.arraycopy(packet, 0, original, 0, original.length);

        int sweptBits = 0;
        int cleanDecodes = 0;
        int complaints = 0;
        byte[] work = new byte[original.length];
        for (int bit = 0; bit < 256 * 8; bit++) {
            System.arraycopy(original, 0, work, 0, original.length);
            work[bit >>> 3] ^= (byte) (1 << (bit & 7));
            for (int truncation : new int[] {0, 1, 7, 64, 255}) {
                int length = original.length - truncation;
                try {
                    drive(new RangeDecoder(work, 0, length), length);
                    cleanDecodes++;
                } catch (RangeCoderException e) {
                    assertNotNull(e.getMessage(),
                            "bit " + bit + " truncated by " + truncation
                                    + " produced a bare RangeCoderException with no message");
                    complaints++;
                } catch (IndexOutOfBoundsException e) {
                    fail("flipping bit " + bit + " and cutting " + truncation
                            + " bytes made the range decoder read outside its buffer: " + e);
                }
            }
            sweptBits++;
        }
        assertEquals(2048, sweptBits, "the sweep must cover every bit of the first 256 bytes");
        assertEquals(2048 * 5, cleanDecodes + complaints,
                "every corrupted variant must have reached one outcome or the other");
        assertTrue(cleanDecodes > 0, "the sweep never once decoded, so it proves nothing about"
                + " the decoder having survived: " + cleanDecodes);
    }

    @Test
    @DisplayName("whatever bytes follow the range coder's last one, its symbols still decode")
    void theTerminationLeavesTheTrailingBytesFreeForAnythingAtAll() {
        // RFC 6716 section 5.1.5: the value the encoder terminates on is chosen
        // to have the most trailing zero bits it can, precisely so that
        // "the maximum number of trailing bits [may] be set to arbitrary values
        // while still ensuring the range coded part of the buffer can be
        // decoded correctly". That is not a nicety -- it is the whole reason
        // CELT may pack raw bits into the same bytes, and an encoder that
        // terminated on any old value inside the range would produce frames
        // that decode until the moment the raw bits are dense enough to reach
        // back into the last coded byte.
        Lcg rnd = new Lcg(0x7E27_1BA1_0000_0005L);
        int variants = 0;
        for (int trial = 0; trial < 400; trial++) {
            Program program = codedOnlyProgram(rnd, 1 + rnd.next(30));
            byte[] out = new byte[16 + program.length() * 5];
            RangeEncoder enc = new RangeEncoder(out);
            encodeProgram(enc, program, null);
            int length = enc.finish();
            int coded = enc.rangeBytes();
            assertEquals(coded, length,
                    "a stream with no raw bits in it needs only its coded bytes");
            assertTrue(coded < out.length,
                    "trial " + trial + " filled its buffer, so there is nothing trailing to"
                            + " overwrite and the test would prove nothing");
            for (int fill : new int[] {0x00, 0xFF, 0x5A, 0xA5}) {
                byte[] noisy = out.clone();
                for (int i = coded; i < noisy.length; i++) {
                    noisy[i] = (byte) fill;
                }
                checkProgramDecodes(program, noisy, noisy.length, enc.finalRange(), null,
                        trial);
                variants++;
            }
        }
        assertEquals(400 * 4, variants, "the sweep did not run to completion");
    }

    @Test
    @DisplayName("finishing a symbol that was never started is refused rather than spun on")
    void updateWithoutADecodeIsRefused() {
        // ec_dec_update finishes the division ec_decode began, and upstream
        // simply trusts the caller to pair them. Unpaired, the multiply is by
        // zero and the range collapses; a range of zero can never be shifted
        // back above 2**23, so renormalisation would not return. The CELT
        // decoder calls these two separately for its Laplace symbols, which is
        // where an ordering mistake would come from.
        RangeDecoder fresh = new RangeDecoder(new byte[] {0x11, 0x22, 0x33, 0x44});
        RangeCoderException e = assertThrows(RangeCoderException.class,
                () -> fresh.update(0, 1, 8), "no decode() has been made to finish");
        assertTrue(e.getMessage().contains("must follow a decode"),
                "the refusal must say what is missing: " + e.getMessage());

        RangeDecoder paired = new RangeDecoder(new byte[] {0x11, 0x22, 0x33, 0x44});
        int fs = paired.decode(8);
        paired.update(fs, fs + 1, 8);
        assertThrows(RangeCoderException.class, () -> paired.update(fs, fs + 1, 8),
                "a second update on one decode is the same mistake");

        // And the paired form is unaffected, so the sentinel guards the misuse
        // and not the use.
        for (int i = 0; i < 20; i++) {
            int next = paired.decode(16);
            paired.update(next, next + 1, 16);
        }
        assertTrue(paired.tell() > 1, "the paired form must still decode");
    }

    @Test
    @DisplayName("a table with a zero-width symbol is refused rather than spun on")
    void anEncoderRefusesATableWhoseSymbolHasNoWidth() {
        // Both of these collapse rng to zero, and a range of zero can never be
        // renormalised back above 2**23: the encoder would not write a bad
        // frame, it would stop returning. On an audio thread that is a silent
        // application rather than a bad second of sound, which is why the check
        // is worth the compare it costs. The decoder needs neither check and
        // has neither; the invariant val < rng rules both out there.
        short[] repeated = {200, 100, 100, 0};
        RangeCoderException e = assertThrows(RangeCoderException.class,
                () -> new RangeEncoder(new byte[16]).encodeIcdf(2, repeated, 8),
                "two equal entries give a symbol no part of the range");
        assertTrue(e.getMessage().contains("no part of the range"),
                "the refusal must name what is wrong with the table: " + e.getMessage());

        short[] emptyFirst = {256, 128, 0};
        RangeCoderException e2 = assertThrows(RangeCoderException.class,
                () -> new RangeEncoder(new byte[16]).encodeIcdf(0, emptyFirst, 8),
                "an icdf table cannot express a first symbol of zero probability, which is"
                        + " what an entry of 1<<ftb asks for");
        assertTrue(e2.getMessage().contains("no part of the range"),
                "the refusal must name what is wrong with the table: " + e2.getMessage());

        // The same table used on a symbol that does have width still works, so
        // the check rejects the symbol and not the table.
        RangeEncoder ok = new RangeEncoder(new byte[16]);
        ok.encodeIcdf(1, repeated, 8);
        assertTrue(ok.finish() > 0, "a well-formed symbol of the same table must still encode");
    }

    @Test
    @DisplayName("the shortcut decoders agree symbol for symbol with the long-hand one")
    void theAlternateDecodingMethodsAreExactlyEquivalent() {
        // RFC 6716 section 4.1.3 says ec_dec_bit_logp, ec_dec_icdf and
        // ec_decode_bin are "exactly equivalent" to ec_decode followed by
        // ec_dec_update, and gives the three-tuple each one stands for. They
        // are the shapes a from-memory implementation gets subtly wrong -- the
        // icdf table is a complement, not a CDF, and the boundary of
        // ec_dec_bit_logp is (1<<logp)-1 rather than 1<<logp -- and either
        // mistake decodes most symbols correctly, which is what makes it
        // survive a casual test. So both forms run over the same bytes here and
        // must agree on the symbol, on rng, and on the bit count.
        Lcg rnd = new Lcg(0x0A17_E27A_0000_0004L);
        int steps = 0;
        for (int trial = 0; trial < 500; trial++) {
            byte[] frame = new byte[1 + rnd.next(64)];
            for (int i = 0; i < frame.length; i++) {
                frame[i] = (byte) rnd.next(256);
            }
            RangeDecoder shortcut = new RangeDecoder(frame);
            RangeDecoder longhand = new RangeDecoder(frame);
            for (int step = 0; step < 60; step++) {
                switch (rnd.next(3)) {
                    case 0 -> {
                        int logp = 1 + rnd.next(15);
                        int ft = 1 << logp;
                        int fast = shortcut.decodeBit(logp);
                        int fs = longhand.decode(ft);
                        int slow;
                        if (fs < ft - 1) {
                            slow = 0;
                            longhand.update(0, ft - 1, ft);
                        } else {
                            slow = 1;
                            longhand.update(ft - 1, ft, ft);
                        }
                        assertEquals(slow, fast, "ec_dec_bit_logp disagreed with ec_decode at"
                                + " logp " + logp + ", trial " + trial + " step " + step);
                    }
                    case 1 -> {
                        int c = rnd.next(ICDFS.length);
                        short[] icdf = ICDFS[c];
                        int ftb = ICDF_FTB[c];
                        int ft = 1 << ftb;
                        int fast = shortcut.decodeIcdf(icdf, ftb);
                        int fs = longhand.decode(ft);
                        int k = 0;
                        while (fs >= ft - icdf[k]) {
                            k++;
                        }
                        longhand.update(k == 0 ? 0 : ft - icdf[k - 1], ft - icdf[k], ft);
                        assertEquals(k, fast, "ec_dec_icdf disagreed with ec_decode on table "
                                + c + ", trial " + trial + " step " + step);
                    }
                    default -> {
                        int ftb = 1 + rnd.next(15);
                        int ft = 1 << ftb;
                        int fast = shortcut.decodeBin(ftb);
                        int slow = longhand.decode(ft);
                        assertEquals(slow, fast, "ec_decode_bin disagreed with ec_decode at ftb "
                                + ftb + ", trial " + trial + " step " + step);
                        shortcut.update(fast, fast + 1, ft);
                        longhand.update(slow, slow + 1, ft);
                    }
                }
                assertEquals(longhand.finalRange(), shortcut.finalRange(),
                        "the two forms left different ranges, trial " + trial
                                + " step " + step + "; the conformance check compares exactly"
                                + " this number");
                assertEquals(longhand.tellFrac(), shortcut.tellFrac(),
                        "the two forms counted different bits, trial " + trial
                                + " step " + step);
                steps++;
            }
        }
        assertEquals(500 * 60, steps, "the equivalence sweep did not run to completion");
    }

    @Test
    @DisplayName("the range coder holds unsigned values that a signed int would read as negative")
    void theRangeIsExactlyTwoToThe31AfterInitialisation() {
        // The single most likely way to get this wrong in Java. rng is 128 at
        // initialisation and renormalisation shifts it left by eight three
        // times, landing on exactly 2**31 -- which as a signed int is negative,
        // so val/rng would be zero or negative from the very first symbol and
        // every context in every frame would decode symbol zero.
        RangeDecoder dec = new RangeDecoder(new byte[] {0x12, 0x34, 0x56, 0x78});
        assertEquals(1L << 31, dec.finalRange(),
                "a freshly initialised decoder holds a range of exactly 2**31");
        assertTrue(dec.finalRange() > 0, "the range must be read as an unsigned quantity");
        RangeEncoder enc = new RangeEncoder(new byte[4]);
        assertEquals(dec.finalRange(), enc.finalRange(),
                "both ends start from the same range or nothing after this agrees");
    }

    private static List<OpusTestVectors.Packet> celtPackets() {
        OpusTestVectors.Vector vector = OpusTestVectors.load(1);
        return vector == null ? null : vector.packets();
    }

    /**
     * Drives a decoder the way CELT does: coded symbols until the frame's bits
     * are gone, then raw bits from the far end until the frame has no more.
     *
     * <p>Deterministic from the frame length alone, so a failure is reproducible
     * from the packet that caused it and nothing else.
     */
    private static void drive(RangeDecoder dec, int bytes) {
        int budget = bytes * 8;
        Lcg rnd = new Lcg(0x5EED_0000_0000_0000L + bytes);
        int previous = dec.tell();
        int steps = 0;
        while (dec.tell() < budget && steps < 4 * budget + 64) {
            switch (rnd.next(4)) {
                case 0 -> dec.decodeBit(1 + rnd.next(15));
                case 1 -> {
                    int c = rnd.next(ICDFS.length);
                    dec.decodeIcdf(ICDFS[c], ICDF_FTB[c]);
                }
                case 2 -> {
                    int c = rnd.next(CDFS.length);
                    dec.decodeSymbol(CDFS[c], CDF_TOTALS[c]);
                }
                default -> dec.decodeUniform(UNIFORM_COUNTS[rnd.next(UNIFORM_COUNTS.length)]);
            }
            int now = dec.tell();
            if (now < previous) {
                throw new AssertionError("ec_tell went backwards, from " + previous
                        + " to " + now + "; a bit budget that can shrink lets CELT allocate"
                        + " bits it has already spent");
            }
            if (now != (dec.tellFrac() + 7) / 8) {
                throw new AssertionError("ec_tell " + now + " and ec_tell_frac "
                        + dec.tellFrac() + " disagree");
            }
            previous = now;
            steps++;
        }
        while (dec.rawBitsRemaining() > 0) {
            dec.decodeRawBits((int) Math.min(RangeDecoder.MAX_RAW_BITS, dec.rawBitsRemaining()));
        }
    }

    private static Program randomProgram(Lcg rnd, int length) {
        int[] kind = new int[length];
        int[] context = new int[length];
        int[] value = new int[length];
        for (int i = 0; i < length; i++) {
            kind[i] = rnd.next(5);
            switch (kind[i]) {
                case OP_SYMBOL -> {
                    context[i] = rnd.next(CDFS.length);
                    value[i] = rnd.next(CDFS[context[i]].length - 1);
                }
                case OP_BIT -> {
                    context[i] = 1 + rnd.next(15);
                    value[i] = rnd.next(2);
                }
                case OP_ICDF -> {
                    context[i] = rnd.next(ICDFS.length);
                    value[i] = rnd.next(ICDFS[context[i]].length);
                }
                case OP_RAW -> {
                    context[i] = 1 + rnd.next(RangeDecoder.MAX_RAW_BITS);
                    value[i] = rnd.next(1 << context[i]);
                }
                default -> {
                    context[i] = rnd.next(UNIFORM_COUNTS.length);
                    value[i] = rnd.next(UNIFORM_COUNTS[context[i]]);
                }
            }
        }
        return new Program(kind, context, value);
    }

    /**
     * A program using only entropy-coded symbols, so nothing is packed at the
     * far end of the buffer.
     *
     * <p>Uniform integers are left out along with raw bits: above 256 values
     * they spend raw bits themselves, so a program containing one would put
     * data in the trailing bytes the caller is about to overwrite.
     */
    private static Program codedOnlyProgram(Lcg rnd, int length) {
        int[] kind = new int[length];
        int[] context = new int[length];
        int[] value = new int[length];
        for (int i = 0; i < length; i++) {
            kind[i] = rnd.next(3);
            switch (kind[i]) {
                case OP_SYMBOL -> {
                    context[i] = rnd.next(CDFS.length);
                    value[i] = rnd.next(CDFS[context[i]].length - 1);
                }
                case OP_BIT -> {
                    context[i] = 1 + rnd.next(15);
                    value[i] = rnd.next(2);
                }
                default -> {
                    context[i] = rnd.next(ICDFS.length);
                    value[i] = rnd.next(ICDFS[context[i]].length);
                }
            }
        }
        return new Program(kind, context, value);
    }

    private static void encodeProgram(RangeEncoder enc, Program program, int[] tellOut) {
        for (int i = 0; i < program.length(); i++) {
            if (tellOut != null) {
                tellOut[i] = enc.tell();
            }
            int c = program.context()[i];
            int v = program.value()[i];
            switch (program.kind()[i]) {
                case OP_SYMBOL -> enc.encodeSymbol(CDFS[c], CDF_TOTALS[c], v);
                case OP_BIT -> enc.encodeBit(v, c);
                case OP_ICDF -> enc.encodeIcdf(v, ICDFS[c], ICDF_FTB[c]);
                case OP_RAW -> enc.encodeRawBits(v, c);
                default -> enc.encodeUniform(v, UNIFORM_COUNTS[c]);
            }
        }
        if (tellOut != null) {
            tellOut[program.length()] = enc.tell();
        }
    }

    private static int decodeStep(RangeDecoder dec, Program program, int i) {
        int c = program.context()[i];
        return switch (program.kind()[i]) {
            case OP_SYMBOL -> dec.decodeSymbol(CDFS[c], CDF_TOTALS[c]);
            case OP_BIT -> dec.decodeBit(c);
            case OP_ICDF -> dec.decodeIcdf(ICDFS[c], ICDF_FTB[c]);
            case OP_RAW -> dec.decodeRawBits(c);
            default -> dec.decodeUniform(UNIFORM_COUNTS[c]);
        };
    }

    private static void checkProgramDecodes(Program program, byte[] out, int length,
            long encoderRange, int[] encoderTell, int trial) {
        RangeDecoder dec = new RangeDecoder(out, 0, length);
        for (int i = 0; i < program.length(); i++) {
            if (encoderTell != null) {
                assertEquals(encoderTell[i], dec.tell(),
                        "trial " + trial + " step " + i + ": the two ends disagree about how"
                                + " many bits have gone by, which is how a frame decodes"
                                + " cleanly and is allocated wrongly");
            }
            int got = decodeStep(dec, program, i);
            if (got != program.value()[i]) {
                fail("trial " + trial + " step " + i + " (kind " + program.kind()[i]
                        + ", context " + program.context()[i] + ") encoded "
                        + program.value()[i] + " and decoded " + got
                        + "; from here the rest of the frame is noise");
            }
        }
        if (encoderTell != null) {
            assertEquals(encoderTell[program.length()], dec.tell(),
                    "trial " + trial + ": the two ends disagree about the finished frame");
        }
        assertEquals(encoderRange, dec.finalRange(),
                "trial " + trial + ": the range state differs at the end of the frame, which is"
                        + " exactly the check the conformance vectors make");
    }

    /**
     * The encoder's bit count after {@code steps} of {@code program}, recomputed
     * rather than remembered.
     *
     * <p>Re-encoding from scratch each time is wasteful and deliberate: a count
     * carried alongside the decode would be compared against a number this same
     * run produced, and the property under test is that two independent runs of
     * two different pieces of code arrive at the same one.
     */
    private static int encoderTellAfter(Program program, int steps, int capacity) {
        RangeEncoder enc = new RangeEncoder(new byte[capacity]);
        for (int i = 0; i < steps; i++) {
            int c = program.context()[i];
            int v = program.value()[i];
            switch (program.kind()[i]) {
                case OP_SYMBOL -> enc.encodeSymbol(CDFS[c], CDF_TOTALS[c], v);
                case OP_BIT -> enc.encodeBit(v, c);
                case OP_ICDF -> enc.encodeIcdf(v, ICDFS[c], ICDF_FTB[c]);
                case OP_RAW -> enc.encodeRawBits(v, c);
                default -> enc.encodeUniform(v, UNIFORM_COUNTS[c]);
            }
        }
        return enc.tell();
    }
}
