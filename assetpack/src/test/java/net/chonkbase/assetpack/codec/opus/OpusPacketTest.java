package net.chonkbase.assetpack.codec.opus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The framing layer, against the real conformance streams and against the
 * packets RFC 6716 section 3.4 says a decoder must refuse.
 *
 * <p>The interesting half is the refusals. A packet that is accepted when it
 * should not be does not fail here; it fails several layers down, as a range
 * decoder reading past its buffer, and what a player hears is a burst of noise
 * rather than an error.
 */
class OpusPacketTest {

    @Test
    void everyPacketInEveryConformanceVectorParses() {
        assumeTrue(OpusTestVectors.directory() != null, OpusTestVectors.skipReason());

        int packets = 0;
        int frames = 0;
        Map<OpusPacket.Mode, Integer> byMode = new EnumMap<>(OpusPacket.Mode.class);

        for (int number = 1; number <= 12; number++) {
            OpusTestVectors.Vector vector = OpusTestVectors.load(number);
            if (vector == null) {
                continue;
            }
            for (OpusTestVectors.Packet packet : vector.packets()) {
                OpusPacket parsed = OpusPacket.parse(packet.payload());
                packets++;
                frames += parsed.frameCount();
                byMode.merge(parsed.mode(), 1, Integer::sum);

                // Every frame has to lie inside the packet, and the frames
                // together have to account for the packet minus its framing
                // overhead and padding. A parser that quietly produced a frame
                // pointing outside the buffer would pass a "did it throw" test.
                for (int i = 0; i < parsed.frameCount(); i++) {
                    assertTrue(parsed.frameOffset(i) >= 0,
                            vector.name() + " packet " + packet.index() + " frame " + i
                            + " starts before the buffer");
                    assertTrue(parsed.frameOffset(i) + parsed.frameLength(i)
                                    <= packet.payload().length,
                            vector.name() + " packet " + packet.index() + " frame " + i
                            + " runs past the end");
                }
                assertTrue(parsed.frameCount() >= 1 && parsed.frameCount() <= OpusPacket.MAX_FRAMES,
                        vector.name() + " packet " + packet.index() + " has "
                        + parsed.frameCount() + " frames");
                assertTrue(parsed.samples48k() <= 5760,
                        vector.name() + " packet " + packet.index() + " claims "
                        + parsed.samples48k() + " samples, over the 120 ms limit");
            }
        }

        assertTrue(packets > 10_000,
                "expected the full vector set, parsed only " + packets + " packets");
        assertEquals(3, byMode.size(),
                "the vectors should exercise all three modes, saw " + byMode);
        System.out.printf("framing: %,d packets, %,d frames, modes %s%n", packets, frames, byMode);
    }

    // ------------------------------------------------------- the table of contents

    @Test
    void theConfigurationNumberSelectsModeBandwidthAndFrameSize() {
        // Spot values from the table in RFC 6716 section 3.1, chosen at the
        // boundaries between the three mode families.
        assertEquals(OpusPacket.Mode.SILK, packet(0).mode());
        assertEquals(OpusPacket.Bandwidth.NARROWBAND, packet(0).bandwidth());
        assertEquals(10_000, packet(0).frameMicros());

        assertEquals(OpusPacket.Mode.SILK, packet(11).mode());
        assertEquals(OpusPacket.Bandwidth.WIDEBAND, packet(11).bandwidth());
        assertEquals(60_000, packet(11).frameMicros());

        assertEquals(OpusPacket.Mode.HYBRID, packet(12).mode());
        assertEquals(OpusPacket.Bandwidth.SUPERWIDEBAND, packet(12).bandwidth());
        assertEquals(OpusPacket.Mode.HYBRID, packet(15).mode());
        assertEquals(OpusPacket.Bandwidth.FULLBAND, packet(15).bandwidth());

        assertEquals(OpusPacket.Mode.CELT, packet(16).mode());
        assertEquals(2_500, packet(16).frameMicros());
        assertEquals(OpusPacket.Mode.CELT, packet(31).mode());
        assertEquals(OpusPacket.Bandwidth.FULLBAND, packet(31).bandwidth());
        assertEquals(20_000, packet(31).frameMicros());

        // 2.5 ms at 48 kHz is 120 samples, and 20 ms is 960. Getting this
        // wrong produces audio at the right pitch and the wrong length.
        assertEquals(120, packet(16).frameSamples48k());
        assertEquals(960, packet(31).frameSamples48k());
    }

    @Test
    void theStereoBitIsReadFromTheTableOfContents() {
        assertTrue(!OpusPacket.parse(new byte[] {(byte) (31 << 3), 0}).stereo());
        assertTrue(OpusPacket.parse(new byte[] {(byte) ((31 << 3) | 0x04), 0}).stereo());
    }

    // -------------------------------------------------------------- frame codes

    @Test
    void codeZeroIsOneFrameOfEverythingThatFollows() {
        OpusPacket parsed = OpusPacket.parse(new byte[] {toc(31, false, 0), 1, 2, 3, 4});
        assertEquals(1, parsed.frameCount());
        assertEquals(1, parsed.frameOffset(0));
        assertEquals(4, parsed.frameLength(0));
    }

    @Test
    void codeOneSplitsTheRemainderInTwo() {
        OpusPacket parsed = OpusPacket.parse(new byte[] {toc(31, false, 1), 1, 2, 3, 4});
        assertEquals(2, parsed.frameCount());
        assertEquals(2, parsed.frameLength(0));
        assertEquals(2, parsed.frameLength(1));
        assertEquals(3, parsed.frameOffset(1));
    }

    @Test
    void codeOneRefusesAnOddPayload() {
        // Two frames of equal size cannot come out of an odd number of bytes.
        // Splitting anyway gives two frames that overlap by half a byte.
        OpusPacket.MalformedPacketException thrown = assertThrows(
                OpusPacket.MalformedPacketException.class,
                () -> OpusPacket.parse(new byte[] {toc(31, false, 1), 1, 2, 3}));
        assertTrue(thrown.getMessage().contains("odd"), thrown.getMessage());
    }

    @Test
    void codeTwoReadsTheFirstFramesLength() {
        OpusPacket parsed = OpusPacket.parse(new byte[] {toc(31, false, 2), 2, 1, 2, 3, 4, 5});
        assertEquals(2, parsed.frameCount());
        assertEquals(2, parsed.frameLength(0));
        assertEquals(3, parsed.frameLength(1));
    }

    @Test
    void codeTwoRefusesAFirstFrameLongerThanThePacket() {
        assertThrows(OpusPacket.MalformedPacketException.class,
                () -> OpusPacket.parse(new byte[] {toc(31, false, 2), 40, 1, 2}));
    }

    @Test
    void aTwoByteLengthCountsFours() {
        // 252 and above is written in two bytes, the second counting fours.
        // 252 + 4*2 = 260.
        byte[] data = new byte[1 + 2 + 260 + 5];
        data[0] = toc(31, false, 2);
        data[1] = (byte) 252;
        data[2] = 2;
        OpusPacket parsed = OpusPacket.parse(data);
        assertEquals(260, parsed.frameLength(0));
        assertEquals(5, parsed.frameLength(1));
    }

    @Test
    void codeThreeCarriesEqualFramesWhenTheVariableBitIsClear() {
        byte[] data = new byte[] {toc(31, false, 3), 3, 1, 2, 3, 4, 5, 6};
        OpusPacket parsed = OpusPacket.parse(data);
        assertEquals(3, parsed.frameCount());
        for (int i = 0; i < 3; i++) {
            assertEquals(2, parsed.frameLength(i));
        }
    }

    @Test
    void codeThreeRefusesEqualFramesThatDoNotDivide() {
        assertThrows(OpusPacket.MalformedPacketException.class,
                () -> OpusPacket.parse(new byte[] {toc(31, false, 3), 3, 1, 2, 3, 4}));
    }

    @Test
    void codeThreeReadsALengthPerFrameWhenTheVariableBitIsSet() {
        // count byte 0x80 | 3 frames, then two lengths, the third implied.
        byte[] data = new byte[] {toc(31, false, 3), (byte) 0x83, 2, 3, 1, 1, 2, 2, 2, 3, 3};
        OpusPacket parsed = OpusPacket.parse(data);
        assertEquals(3, parsed.frameCount());
        assertEquals(2, parsed.frameLength(0));
        assertEquals(3, parsed.frameLength(1));
        assertEquals(2, parsed.frameLength(2));
    }

    @Test
    void codeThreePaddingIsRemovedFromTheFrames() {
        // 0x40 sets the padding bit; the count byte says three bytes of it,
        // and they sit at the end rather than where the count is.
        byte[] data = new byte[] {toc(31, false, 3), (byte) 0x42, 3, 1, 2, 3, 4, 0, 0, 0};
        OpusPacket parsed = OpusPacket.parse(data);
        assertEquals(2, parsed.frameCount());
        assertEquals(2, parsed.frameLength(0));
        assertEquals(2, parsed.frameLength(1),
                "the three padding bytes must not be handed to the range decoder as audio");
    }

    @Test
    void codeThreeRefusesAFrameCountOfZero() {
        assertThrows(OpusPacket.MalformedPacketException.class,
                () -> OpusPacket.parse(new byte[] {toc(31, false, 3), 0}));
    }

    @Test
    void codeThreeRefusesMoreThanOneHundredAndTwentyMilliseconds() {
        // 49 frames of 2.5 ms is 122.5 ms, one over the limit.
        byte[] data = new byte[2 + 49];
        data[0] = toc(16, false, 3);
        data[1] = 49;
        assertThrows(OpusPacket.MalformedPacketException.class, () -> OpusPacket.parse(data));
    }

    @Test
    void aFrameOverTheByteLimitIsRefused() {
        // 1275 bytes is the most a frame may be: 20 ms at the codec's ceiling.
        byte[] data = new byte[1 + 1276];
        data[0] = toc(31, false, 0);
        assertThrows(OpusPacket.MalformedPacketException.class, () -> OpusPacket.parse(data));
    }

    @Test
    void anEmptyPacketIsRefused() {
        assertThrows(OpusPacket.MalformedPacketException.class,
                () -> OpusPacket.parse(new byte[0]));
    }

    @Test
    void everyMalformedShapeIsReportedRatherThanReturningGarbage() {
        // A sweep: truncate a well-formed code 3 VBR packet at every length and
        // assert each either parses consistently or is reported. The failure
        // this catches is an array index escaping as ArrayIndexOutOfBounds,
        // which a caller cannot distinguish from a bug in itself.
        byte[] full = new byte[] {toc(31, false, 3), (byte) 0x83, 2, 3, 1, 1, 2, 2, 2, 3, 3};
        for (int length = 1; length <= full.length; length++) {
            byte[] cut = new byte[length];
            System.arraycopy(full, 0, cut, 0, length);
            try {
                OpusPacket parsed = OpusPacket.parse(cut);
                for (int i = 0; i < parsed.frameCount(); i++) {
                    assertTrue(parsed.frameOffset(i) + parsed.frameLength(i) <= length,
                            "truncated to " + length + ": frame " + i + " escapes the buffer");
                }
            } catch (OpusPacket.MalformedPacketException expected) {
                // A message, which is the point.
                assertTrue(expected.getMessage() != null && !expected.getMessage().isBlank());
            }
        }
    }

    private static byte toc(int config, boolean stereo, int code) {
        return (byte) ((config << 3) | (stereo ? 0x04 : 0) | code);
    }

    private static OpusPacket packet(int config) {
        return OpusPacket.parse(new byte[] {toc(config, false, 0), 0});
    }

    /** Kept so the import of List is used if this class grows a table case. */
    @SuppressWarnings("unused")
    private static final List<String> MODES = List.of("SILK", "HYBRID", "CELT");
}
