package net.chonkbase.assetpack.codec.opus;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The range decoder driven over every CELT payload in the official conformance
 * suite, to prove it cannot be made to fault by real data.
 *
 * <p>This is not yet the conformance gate. The gate is
 * {@code finalRange()}: RFC 6716's vectors store, beside every packet, the value
 * the reference encoder's range coder held when it finished that packet, and a
 * decoder that has read every symbol of the frame correctly ends holding the
 * same integer. Reaching that requires decoding CELT's actual symbol sequence,
 * which needs the CELT decoder, so it lands with that.
 *
 * <p>What can be proved today is the property underneath it. A range decoder is
 * handed bytes chosen by whoever made the file, and it deliberately reads
 * several bytes past where the symbols stop -- RFC 6716 section 4.1.2.1
 * requires it to -- while a second reader walks backwards from the far end of
 * the same buffer towards it. Those two cursors are where this layer goes
 * wrong, and the failure is not subtle audio: it is an index off the end of an
 * array in the middle of an audio callback. So each frame here is driven three
 * ways -- the forward reader alone until the frame's bits are gone, the
 * backward reader alone until the frame's bits are gone, and the two
 * interleaved -- and none of them may fault.
 *
 * <p>The symbol sequence used is not CELT's. It cannot be yet. It is a fixed
 * pseudo-random mixture of every decoding method in RFC 6716 section 4.1,
 * seeded from the frame's own length so that a failure is reproducible from the
 * packet that caused it, and it is deliberately driven past the end of the
 * frame, which is the region a real decoder only reaches on a corrupt packet.
 */
class RangeCoderVectorTest {

    /**
     * The two streams that are CELT from the first packet to the last.
     *
     * <p>Vectors 7 through 10 also carry CELT, mixed with other modes and with
     * mode switches partway through; these two are unmixed, so a failure in
     * them is a failure on CELT data and nothing else.
     */
    private static final int[] CELT_ONLY_VECTORS = {1, 11};

    /** Every packet of the twelve vectors whose configuration selects CELT. */
    private static final int EXPECTED_CELT_PACKETS = 11058;

    /** Those same packets split into frames, which is what a decoder sees. */
    private static final int EXPECTED_CELT_FRAMES = 18784;

    @Test
    @DisplayName("every frame of the two all-CELT streams drives the range decoder without faulting")
    void theRangeDecoderNeverFaultsOnTheCeltOnlyStreams() {
        int packets = 0;
        int frames = 0;
        long bytes = 0;
        for (int number : CELT_ONLY_VECTORS) {
            OpusTestVectors.Vector vector = OpusTestVectors.load(number);
            Assumptions.assumeTrue(vector != null, OpusTestVectors.skipReason());
            for (OpusTestVectors.Packet packet : vector.packets()) {
                OpusPacket parsed = OpusPacket.parse(packet.payload());
                assertEquals(OpusPacket.Mode.CELT, parsed.mode(),
                        vector.name() + " packet " + packet.index()
                                + " is not CELT, so this test is not measuring what it says");
                for (int f = 0; f < parsed.frameCount(); f++) {
                    driveFrame(parsed, f, vector.name(), packet.index());
                    bytes += parsed.frameLength(f);
                    frames++;
                }
                packets++;
            }
        }
        System.out.println("RangeCoderVectorTest: " + packets + " CELT-only packets, "
                + frames + " frames, " + bytes + " bytes driven without a fault");
        assertEquals(2700, packets,
                "testvector01 and testvector11 hold 2700 packets between them");
        assertEquals(7025, frames, "and 7025 frames");
    }

    @Test
    @DisplayName("all 11,058 CELT packets in the suite drive the range decoder without faulting")
    void theRangeDecoderNeverFaultsOnAnyCeltPacketInTheSuite() {
        List<OpusTestVectors.Vector> vectors = new ArrayList<>();
        for (int number = 1; number <= 12; number++) {
            OpusTestVectors.Vector vector = OpusTestVectors.load(number);
            Assumptions.assumeTrue(vector != null, OpusTestVectors.skipReason());
            vectors.add(vector);
        }

        int packets = 0;
        int frames = 0;
        long bytes = 0;
        for (OpusTestVectors.Vector vector : vectors) {
            for (OpusTestVectors.Packet packet : vector.packets()) {
                OpusPacket parsed = OpusPacket.parse(packet.payload());
                if (parsed.mode() != OpusPacket.Mode.CELT) {
                    continue;
                }
                for (int f = 0; f < parsed.frameCount(); f++) {
                    driveFrame(parsed, f, vector.name(), packet.index());
                    bytes += parsed.frameLength(f);
                    frames++;
                }
                packets++;
            }
        }
        System.out.println("RangeCoderVectorTest: " + packets + " CELT packets across all twelve"
                + " vectors, " + frames + " frames, " + bytes
                + " bytes driven without a fault");
        // Counted, not merely walked. A sweep that found no packets would
        // otherwise pass, declaring a decoder proven against nothing.
        assertEquals(EXPECTED_CELT_PACKETS, packets,
                "the twelve conformance vectors hold " + EXPECTED_CELT_PACKETS
                        + " CELT packets; a different number means a different vector set");
        assertEquals(EXPECTED_CELT_FRAMES, frames,
                "those packets split into " + EXPECTED_CELT_FRAMES + " frames");
        // 2,490,012 bytes over 18,784 frames, a mean of 133 and a longest of
        // 1275. A floor rather than an equality because the count of bytes is
        // not the thing under test; it is here so that a sweep that quietly
        // stopped reading frames cannot pass on the packet count alone.
        assertTrue(bytes > 2_400_000L,
                "only " + bytes + " bytes were driven, which is too little to have covered"
                        + " the frame sizes CELT actually uses");
    }

    /**
     * Drives one frame three ways and lets any fault out.
     *
     * <p>Reads the frame in place, at its offset inside the packet, rather than
     * from a copy. That is how the CELT decoder will do it and it is the only
     * way this test covers the offset arithmetic: a decoder that computed its
     * backward cursor from the array length instead of from the frame length
     * would pass every test built on a byte array that holds one frame and
     * nothing else, and would read another frame's bytes here.
     */
    private static void driveFrame(OpusPacket packet, int frame, String vector, int index) {
        byte[] data = packet.data();
        int offset = packet.frameOffset(frame);
        int length = packet.frameLength(frame);
        String where = vector + " packet " + index + " frame " + frame
                + " (" + length + " bytes at " + offset + ")";
        try {
            forwardToExhaustion(new RangeDecoder(data, offset, length), length);
            backwardToExhaustion(new RangeDecoder(data, offset, length), length);
            bothAtOnce(new RangeDecoder(data, offset, length), length);
        } catch (IndexOutOfBoundsException e) {
            fail("the range decoder read outside its buffer on " + where + ": " + e);
        } catch (RangeCoderException e) {
            fail("the range decoder faulted on " + where + ": " + e.getMessage());
        }
    }

    /** Coded symbols until the frame's bits are spent, checking the bit counts as it goes. */
    private static void forwardToExhaustion(RangeDecoder dec, int bytes) {
        int budget = bytes * 8;
        RangeCoderTest.Lcg rnd = new RangeCoderTest.Lcg(0x0C_E17_0000_0001L + bytes);
        int previous = dec.tell();
        assertEquals(1, previous,
                "a decoder that has read nothing owes exactly the termination bit");
        int steps = 0;
        while (dec.tell() < budget && steps < 8 * budget + 64) {
            decodeOne(dec, rnd);
            int now = dec.tell();
            if (now < previous) {
                fail("ec_tell fell from " + previous + " to " + now + " after " + steps
                        + " symbols; a budget that can shrink lets CELT spend bits twice");
            }
            if (now != (dec.tellFrac() + 7) / 8) {
                fail("ec_tell " + now + " and ec_tell_frac " + dec.tellFrac()
                        + " disagree after " + steps + " symbols");
            }
            previous = now;
            steps++;
        }
        if (bytes > 0 && steps == 0) {
            fail("a " + bytes + "-byte frame was never asked for a single symbol");
        }
    }

    /**
     * Raw bits from the far end until the frame has exactly none left, then one
     * more.
     *
     * <p>The boundary is the point of it. Reading a frame's last raw bit is not
     * an overread even though the byte cursor reached the front of the frame
     * several bytes ago -- the reader fetches up to four bytes ahead of what it
     * hands out -- and the very next bit is one the decoder invented.
     */
    private static void backwardToExhaustion(RangeDecoder dec, int bytes) {
        long expected = (long) bytes * 8;
        assertEquals(expected, dec.rawBitsRemaining(),
                "a " + bytes + "-byte frame holds " + expected + " raw bits");
        while (dec.rawBitsRemaining() > 0) {
            int take = (int) Math.min(RangeDecoder.MAX_RAW_BITS, dec.rawBitsRemaining());
            dec.decodeRawBits(take);
        }
        assertEquals(expected, dec.rawBitsRead(),
                "draining a " + bytes + "-byte frame must take exactly its bits");
        if (dec.rawBitsOverread()) {
            fail("a " + bytes + "-byte frame called its own last raw bit an overread");
        }
        dec.checkRawBitsInBounds();
        dec.decodeRawBits(1);
        if (!dec.rawBitsOverread()) {
            fail("the bit past the end of a " + bytes + "-byte frame was invented and not"
                    + " reported");
        }
    }

    /** The two cursors advancing together, which is what a real CELT frame does. */
    private static void bothAtOnce(RangeDecoder dec, int bytes) {
        int budget = bytes * 8;
        RangeCoderTest.Lcg rnd = new RangeCoderTest.Lcg(0x0C_E17_0000_0002L + bytes);
        int steps = 0;
        while (dec.tell() < budget && steps < 8 * budget + 64) {
            decodeOne(dec, rnd);
            if (dec.rawBitsRemaining() > 0) {
                dec.decodeRawBits((int) Math.min(4, dec.rawBitsRemaining()));
            }
            steps++;
        }
        // Both readers have now been past the end of the frame and neither may
        // have taken the other's word for where that was.
        dec.decodeRawBits(RangeDecoder.MAX_RAW_BITS);
        dec.decodeBit(1);
    }

    /**
     * One symbol from a fixed rotation of every decoding method in section 4.1.
     *
     * <p>Shares its probability contexts with {@link RangeCoderTest} on purpose:
     * the round trip there proves those contexts encode and decode to the same
     * values, and this proves the same contexts cannot be made to fault on real
     * bytes. Two private copies would eventually differ and each half would be
     * proving something about a context the other never saw.
     */
    private static void decodeOne(RangeDecoder dec, RangeCoderTest.Lcg rnd) {
        switch (rnd.next(5)) {
            case 0 -> dec.decodeBit(1 + rnd.next(15));
            case 1 -> {
                int c = rnd.next(RangeCoderTest.ICDFS.length);
                dec.decodeIcdf(RangeCoderTest.ICDFS[c], RangeCoderTest.ICDF_FTB[c]);
            }
            case 2 -> {
                int c = rnd.next(RangeCoderTest.CDFS.length);
                dec.decodeSymbol(RangeCoderTest.CDFS[c], RangeCoderTest.CDF_TOTALS[c]);
            }
            case 3 -> dec.decodeUniform(
                    RangeCoderTest.UNIFORM_COUNTS[rnd.next(RangeCoderTest.UNIFORM_COUNTS.length)]);
            default -> {
                int ftb = 1 + rnd.next(15);
                int fs = dec.decodeBin(ftb);
                dec.update(fs, fs + 1, 1 << ftb);
            }
        }
    }
}
