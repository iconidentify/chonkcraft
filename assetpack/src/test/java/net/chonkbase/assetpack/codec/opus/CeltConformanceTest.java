package net.chonkbase.assetpack.codec.opus;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The conformance gate for the CELT decoder, run against RFC 6716's own vectors.
 *
 * <p>Two things are checked, and the first is worth far more than it looks.
 *
 * <p><b>The range state.</b> {@code opus_demo} stores, beside every packet, the
 * value the reference <em>encoder's</em> range coder held when it finished that
 * packet. A decoder that has read every symbol of the packet correctly ends
 * holding exactly the same integer, and one that has misread a single symbol
 * anywhere does not. This is integer arithmetic, so unlike the audio it cannot
 * be excused by floating-point rounding: it is a bit-exact test of the entropy
 * layer, of the allocator, of the energy models, of the split angles, of the
 * pulse codebooks and of every table underneath them, all at once. It also
 * localises a fault to one packet rather than to a stream.
 *
 * <p><b>The audio.</b> RFC 6716 section 6 does not require bit-exact output,
 * because the codec is specified in floating point and two conforming decoders
 * may differ in the last bit. So the audio is compared rather than asserted
 * equal -- but on two figures, not one. A whole-stream signal-to-noise ratio
 * averages over thousands of packets and cannot see a defect confined to a few
 * frames; the worst single sample can, and cannot see a small error spread over
 * everything. Both are checked, against bounds close to what this decoder
 * actually reaches, because the loose floor the RFC implies passed a 50 per
 * cent error in the anti-collapse noise amplitude. See {@link Expected}.
 *
 * <p>Vectors 1 and 11 are used because they are the two that are pure CELT:
 * every packet in them selects one of configurations 28 to 31, fullband stereo
 * at 2.5, 5, 10 or 20 ms. The other ten vectors need SILK.
 */
@DisplayName("CELT conformance against the RFC 6716 test vectors")
class CeltConformanceTest {

    /**
     * The three pure-CELT vectors, with what each one must contain and what this
     * decoder must achieve on it.
     *
     * <p>The packet count is asserted before anything is measured, because a
     * sweep that finds nothing passes every check downstream of it: a directory
     * of empty {@code .bit} files once made this suite report "GATE A: 0 of 0
     * packets bit-exact" and "SNR Infinity dB" and go green.
     *
     * <p>{@code minSnrDb} and {@code maxDeviation} are regression bounds, not
     * the RFC's conformance threshold, and they are deliberately close to what
     * this decoder actually achieves. The 60 dB floor RFC 6716 section 6 implies
     * is far too loose to police the synthesis path: a whole-stream ratio
     * averages over two thousand packets, so a defect confined to a handful of
     * frames disappears into it. Measured against these vectors, a 50 per cent
     * error in the anti-collapse noise amplitude, a doubled folding dither, a
     * mid weighting of 0.55 instead of 0.5 in the intensity-stereo fold and a
     * de-emphasis coefficient wrong in its fourth digit all passed a 60 dB
     * floor. Every one of them moves the worst-sample deviation or the ratio
     * past the bounds below.
     *
     * <p>They are safe to assert this tightly because the decode is
     * reproducible: Java float arithmetic is IEEE-754 with no extended
     * precision, {@code StrictMath} is bit-defined, and {@code Math.sqrt} is
     * required to be correctly rounded. The same bytes give the same samples on
     * any JVM, so these are constants rather than measurements. Moving one is a
     * decision to be made deliberately, which is the point.
     */
    private record Expected(int number, int packets, double minSnrDb, int maxDeviation) {}

    /** The two vectors whose every packet is CELT-only, and their gate figures. */
    private static final Expected[] CELT_VECTORS = {
        new Expected(1, 2147, 92.0, 2),
        new Expected(11, 553, 100.0, 2)
    };

    /** The third pure-CELT vector, the one that changes channel count mid-stream. */
    private static final Expected CHANNEL_SWITCHING_VECTOR = new Expected(7, 4186, 78.0, 4);

    /** The floor RFC 6716 section 6 implies, kept alongside the regression bounds. */
    private static final double CONFORMANCE_FLOOR_DB = 60.0;

    /** What a decode of one vector produced. */
    private record Decoded(String name, short[] pcm, int packets, int bitExact,
            List<String> failures) {}

    @Test
    @DisplayName("every packet of testvector01 and testvector11 ends on the encoder's range state")
    void rangeStateMatchesOnEveryCeltPacket() {
        Assumptions.assumeTrue(OpusTestVectors.directory() != null, OpusTestVectors.skipReason());

        int packets = 0;
        int bitExact = 0;
        List<String> failures = new ArrayList<>();
        for (Expected expected : CELT_VECTORS) {
            OpusTestVectors.Vector vector = OpusTestVectors.load(expected.number());
            Assumptions.assumeTrue(vector != null, OpusTestVectors.skipReason());
            // Before anything is measured: a truncated or empty vector file
            // would otherwise let every check below pass on nothing at all.
            assertEquals(expected.packets(), vector.packets().size(),
                    vector.name() + " holds the wrong number of packets, so this gate would"
                    + " be measuring something other than the published vector");
            Decoded decoded = decode(vector);
            packets += decoded.packets();
            bitExact += decoded.bitExact();
            failures.addAll(decoded.failures());
        }

        System.out.println("GATE A: " + bitExact + " of " + packets + " packets bit-exact");
        for (int i = 0; i < Math.min(10, failures.size()); i++) {
            System.out.println("  " + failures.get(i));
        }

        int matched = bitExact;
        int total = packets;
        assertEquals(total, matched, () -> matched + " of " + total
                + " packets ended on the encoder's range state; the first mismatches were "
                + failures.subList(0, Math.min(5, failures.size())));
    }

    @Test
    @DisplayName("the decoded audio matches the reference decoder to better than 60 dB")
    void audioMatchesTheReferenceDecode() {
        Assumptions.assumeTrue(OpusTestVectors.directory() != null, OpusTestVectors.skipReason());

        for (Expected expected : CELT_VECTORS) {
            OpusTestVectors.Vector vector = OpusTestVectors.load(expected.number());
            Assumptions.assumeTrue(vector != null, OpusTestVectors.skipReason());
            assertEquals(expected.packets(), vector.packets().size(),
                    vector.name() + " holds the wrong number of packets");
            checkAudio(vector, expected, decode(vector), "GATE B: ");
        }
    }

    /**
     * Compares one decode against the reference on both the ratio and the worst
     * single sample, and reports the figures either way.
     *
     * <p>Both, because they fail on different faults. The ratio catches a small
     * error spread over the whole stream, which is what a wrong coefficient in
     * the de-emphasis integrator or the folding dither looks like. The worst
     * sample catches a large error confined to a few frames, which is what a
     * wrong anti-collapse amplitude looks like -- that one fires on eight frames
     * of the seven thousand in vectors 1 and 11, and a whole-stream ratio does
     * not move at all when it is wrong by half.
     */
    private static void checkAudio(OpusTestVectors.Vector vector, Expected expected,
            Decoded decoded, String label) {
        short[] reference = vector.reference();
        assertTrue(reference.length > 0,
                vector.name() + ".dec is empty, so there is nothing to compare against");
        assertEquals(reference.length, decoded.pcm().length,
                vector.name() + " decoded to a different number of samples than the reference");

        double snr = OpusTestVectors.snrDb(reference, decoded.pcm());
        int worst = OpusTestVectors.maxDeviation(reference, decoded.pcm());
        System.out.println(label + vector.name() + " SNR " + String.format("%.2f", snr)
                + " dB, worst sample deviation " + worst + " of 32768");

        assertTrue(snr > CONFORMANCE_FLOOR_DB, () -> vector.name() + " decoded at " + snr
                + " dB against the reference, which is below the " + CONFORMANCE_FLOOR_DB
                + " dB conformance floor; the worst sample was out by " + worst);
        assertTrue(snr > expected.minSnrDb(), () -> vector.name() + " decoded at " + snr
                + " dB, below the " + expected.minSnrDb() + " dB this decoder reaches;"
                + " something in the synthesis path has changed");
        assertTrue(worst <= expected.maxDeviation(), () -> vector.name()
                + " has a sample out by " + worst + " counts against the reference, past the "
                + expected.maxDeviation() + " this decoder reaches; a defect confined to a few"
                + " frames shows up here long before it shows up in the ratio");
    }

    @Test
    @DisplayName("testvector07 decodes too, and it changes channel count on half its packets")
    void theChannelSwitchingVectorAlsoDecodes() {
        Assumptions.assumeTrue(OpusTestVectors.directory() != null, OpusTestVectors.skipReason());
        OpusTestVectors.Vector vector = OpusTestVectors.load(CHANNEL_SWITCHING_VECTOR.number());
        Assumptions.assumeTrue(vector != null, OpusTestVectors.skipReason());
        assertEquals(CHANNEL_SWITCHING_VECTOR.packets(), vector.packets().size(),
                vector.name() + " holds the wrong number of packets");

        // Not one of the two gates, and worth having anyway: vector 7 is the
        // third pure-CELT stream, and 2128 of its 4186 packets are mono while
        // 2058 are stereo. It is the only vector that exercises the energy
        // history being folded across a channel change, which is the one piece
        // of decoder state the reference keeps at two channels whatever the
        // packet carries.
        Decoded decoded = decode(vector);
        System.out.println("extra: " + vector.name() + " " + decoded.bitExact() + " of "
                + decoded.packets() + " packets bit-exact");
        for (int i = 0; i < Math.min(5, decoded.failures().size()); i++) {
            System.out.println("  " + decoded.failures().get(i));
        }

        assertEquals(decoded.packets(), decoded.bitExact(),
                "testvector07 did not decode bit-exactly");
        // This vector carries nearly all of the anti-collapse coverage there is:
        // the pass fires on 133 of its frames against 1 in vector 1 and 7 in
        // vector 11, so its bounds are the ones guarding that path.
        checkAudio(vector, CHANNEL_SWITCHING_VECTOR, decoded, "extra: ");
    }

    @Test
    @DisplayName("the range gate catches a single flipped bit, so passing it means something")
    void theRangeGateIsLoadBearing() {
        Assumptions.assumeTrue(OpusTestVectors.directory() != null, OpusTestVectors.skipReason());
        OpusTestVectors.Vector vector = OpusTestVectors.load(1);
        Assumptions.assumeTrue(vector != null, OpusTestVectors.skipReason());

        // A gate that 2700 packets pass is only worth having if it can fail.
        // One flipped bit changes some symbol, and every symbol after it, so
        // the range state must change too -- or the decode must refuse the
        // packet outright.
        //
        // Only single-frame packets are corrupted here, and that restriction is
        // itself a finding. Each frame of a packet gets its own range coder, so
        // the value stored beside a packet is the state of its LAST frame; a bit
        // flipped in an earlier frame is genuinely invisible to it. That is what
        // the reference reports too, and it is the reason gate B matters: the
        // audio comparison covers all 7025 frames, where the range check covers
        // the 2700 that end their packets.
        OpusDecoder decoder = new OpusDecoder(48_000, 2);
        short[] pcm = new short[2 * OpusDecoder.MAX_PACKET_SAMPLES];

        int tried = 0;
        int caught = 0;
        int singleFrame = 0;
        for (OpusTestVectors.Packet packet : vector.packets()) {
            byte[] original = packet.payload();
            OpusPacket parsed = OpusPacket.parse(original);
            if (parsed.frameCount() != 1) {
                continue;
            }
            if (++singleFrame > 200) {
                break;
            }
            long expected = packet.expectedFinalRange();
            // Anchored on the frame, not on the packet. These vectors use code 3
            // framing even for a single frame, so the bytes between the table of
            // contents and the frame are a frame count and a padding count --
            // flipping those changes the framing rather than the audio, and the
            // decoder is right to produce the same samples for some of them.
            //
            // The first bytes of the frame are the front of the range coder's
            // own stream, so every bit in them is a range-coded bit. Bits deeper
            // in are increasingly likely to be raw bits, which RFC 6716 section
            // 4.1.4 places outside the range coder on purpose: they change the
            // audio but by design cannot change rng, so corrupting them would
            // prove nothing about this gate.
            int first = parsed.frameOffset(0) * 8;
            int limit = Math.min(original.length * 8, first + 24);
            for (int bit = first; bit < limit; bit++) {
                byte[] corrupt = original.clone();
                corrupt[bit >> 3] ^= (byte) (1 << (bit & 7));
                decoder.reset();
                tried++;
                try {
                    decoder.decode(corrupt, 0, corrupt.length, pcm, 0);
                    if (decoder.finalRange() != expected) {
                        caught++;
                    }
                } catch (RuntimeException refused) {
                    caught++;
                }
            }
        }
        decoder.reset();

        System.out.println("gate check: " + caught + " of " + tried
                + " single-bit corruptions of the range-coded region changed the range state"
                + " or were refused");
        assertTrue(tried > 500, "the corruption sweep only tried " + tried + " flips");
        int detected = caught;
        int attempts = tried;
        assertEquals(attempts, detected, () -> "only " + detected + " of " + attempts
                + " flipped range-coded bits changed the decoder's range state, so the"
                + " conformance gate is not testing what it claims to test");
    }

    @Test
    @DisplayName("the same stream decodes to the same samples every time")
    void decodingIsDeterministic() {
        Assumptions.assumeTrue(OpusTestVectors.directory() != null, OpusTestVectors.skipReason());
        OpusTestVectors.Vector vector = OpusTestVectors.load(11);
        Assumptions.assumeTrue(vector != null, OpusTestVectors.skipReason());

        short[] first = decode(vector).pcm();
        short[] second = decode(vector).pcm();
        // Two empty arrays are equal to each other, which would make this pass
        // against a vector file that had not loaded.
        assertTrue(first.length > 0, "the decode produced no samples to compare");
        org.junit.jupiter.api.Assertions.assertArrayEquals(first, second,
                "two decodes of the same stream disagreed, so something in the decode path"
                + " depends on state it should not");
    }

    @Test
    @DisplayName("a frame whose silence flag is set reads nothing else and outputs nothing")
    void theSilenceFlagStopsTheFrameDead() {
        // Not one symbol of this path appears in any of the three CELT vectors:
        // an instrumented decode of all 11,211 of their frames found the silence
        // flag set on none of them. It still has to be right, because a frame
        // with the flag set contains nothing at all after it, and a decoder that
        // carried on reading would take symbols the encoder never wrote and
        // desynchronise its range coder for the rest of the packet.
        //
        // The flag decodes as set when the first two bytes of the frame are
        // 0xFF, which is what makes the range decoder's value zero and so puts
        // it inside the one-in-32768 sub-range the set flag occupies. That is
        // the same construction the reference relies on for its own silent
        // frame, spelled `unsigned char silence[2] = {0xFF, 0xFF}` in
        // opus_decode_frame.
        //
        // The rest of the frame is deliberately NOT 0xFF. A frame of nothing but
        // 0xFF is degenerate: the decoder's value stays at zero, every symbol it
        // reads is the last one of its context, and the range walks back to the
        // same number it would have held had it stopped. A first attempt at this
        // test used one, and it passed with the whole silence mechanism ripped
        // out. Varying the tail is what makes reading one symbol too many show
        // up in the range state.
        byte[] packet = new byte[40];
        // Configuration 31: CELT, fullband, 20 ms, stereo, code 0, one frame.
        packet[0] = (byte) ((31 << 3) | 0x04);
        packet[1] = (byte) 0xFF;
        packet[2] = (byte) 0xFF;
        for (int i = 3; i < packet.length; i++) {
            packet[i] = (byte) (0x5A * i + 0x37);
        }

        OpusDecoder decoder = new OpusDecoder(48_000, 2);
        short[] pcm = new short[2 * 960];
        int produced = decoder.decode(packet, 0, packet.length, pcm, 0);
        assertEquals(960, produced, "a 20 ms frame is 960 samples at 48 kHz");

        for (int i = 0; i < pcm.length; i++) {
            assertEquals(0, pcm[i], "sample " + i + " of a silent frame was not silent");
        }

        // The exact range state after reading one bit at probability 2**-15 and
        // renormalising, and nothing else. Asserting the value rather than just
        // "quiet output" is what proves no further symbol was read: a decoder
        // that went on to read the post-filter flag, the transient flag and
        // twenty-one bands of coarse energy would also produce silence here,
        // because the envelope is overwritten either way. With this payload and
        // the silence handling removed it reads 102559488 instead.
        assertEquals(1L << 24, decoder.finalRange(),
                "the decoder did not stop at the silence flag");
    }

    @Test
    @DisplayName("every SILK and Hybrid configuration is refused by name, never silenced")
    void silkAndHybridAreRefusedRatherThanSilenced() {
        OpusDecoder decoder = new OpusDecoder(48_000, 2);
        short[] pcm = new short[2 * OpusDecoder.MAX_PACKET_SAMPLES];

        // All sixteen non-CELT configurations rather than one of each kind. The
        // failure this guards against is a decoder that returns silence for a
        // mode it cannot handle, which turns a missing feature into a bug report
        // about audio that "sometimes does not play", months later and in
        // someone else's component -- and it only takes one configuration
        // falling through to do that.
        int refused = 0;
        for (int config = 0; config < 16; config++) {
            String mode = config < 12 ? "SILK" : "HYBRID";
            for (int stereo = 0; stereo < 2; stereo++) {
                byte[] packet = new byte[60];
                // Code 0, one frame, so the framing never refuses it first and
                // the refusal that arrives is the mode's.
                packet[0] = (byte) ((config << 3) | (stereo << 2));
                for (int i = 1; i < packet.length; i++) {
                    packet[i] = (byte) (0x39 * i + 7);
                }
                UnsupportedOperationException failure = org.junit.jupiter.api.Assertions
                        .assertThrows(UnsupportedOperationException.class,
                                () -> decoder.decode(packet, 0, packet.length, pcm, 0),
                                "configuration " + config + " was decoded rather than refused");
                assertTrue(failure.getMessage().contains(mode),
                        "the refusal for configuration " + config + " must name " + mode
                        + ", and said: " + failure.getMessage());
                refused++;
                decoder.reset();
            }
        }
        assertEquals(32, refused, "the sweep did not reach every non-CELT configuration");
    }

    @Test
    @DisplayName("a corrupt or truncated packet gives a message, never an array index fault")
    void malformedPacketsAreReportedNotFaulted() {
        OpusDecoder decoder = new OpusDecoder(48_000, 2);
        short[] pcm = new short[2 * OpusDecoder.MAX_PACKET_SAMPLES];

        Assumptions.assumeTrue(OpusTestVectors.directory() != null, OpusTestVectors.skipReason());
        OpusTestVectors.Vector vector = OpusTestVectors.load(1);
        Assumptions.assumeTrue(vector != null, OpusTestVectors.skipReason());
        assertEquals(CELT_VECTORS[0].packets(), vector.packets().size(),
                vector.name() + " holds the wrong number of packets, so this sweep would run"
                + " on something other than the published vector");

        // Three shapes of damage, and none of them may produce an
        // ArrayIndexOutOfBoundsException, a NullPointerException, an
        // ArithmeticException or a hang. A decoder that faults on a bad packet
        // takes the audio thread down with it, and a game that streams music
        // over a network will meet every one of these eventually.
        int checked = 0;
        java.util.Random noise = new java.util.Random(20240727L);

        // Every prefix of a real packet, which is what a truncated stream is.
        for (int p = 0; p < 40; p++) {
            byte[] payload = vector.packets().get(p).payload();
            for (int cut = 1; cut <= payload.length; cut++) {
                checked += attempt(decoder, payload, cut, pcm);
            }
        }

        // Real packets with bytes rewritten, which is what a bit error is.
        for (int p = 0; p < 300; p++) {
            byte[] payload = vector.packets().get(p).payload().clone();
            for (int round = 0; round < 8; round++) {
                payload[noise.nextInt(payload.length)] = (byte) noise.nextInt(256);
                checked += attempt(decoder, payload, payload.length, pcm);
            }
        }

        // Pure noise, which is what a resynchronisation on the wrong byte is.
        for (int p = 0; p < 400; p++) {
            byte[] payload = new byte[1 + noise.nextInt(600)];
            noise.nextBytes(payload);
            checked += attempt(decoder, payload, payload.length, pcm);
        }

        assertTrue(checked > 3000, "the damage sweep only covered " + checked + " packets");
    }

    /** Decodes one damaged packet, failing only on an exception a caller cannot act on. */
    private static int attempt(OpusDecoder decoder, byte[] payload, int length, short[] pcm) {
        try {
            decoder.decode(payload, 0, length, pcm, 0);
        } catch (OpusPacket.MalformedPacketException | RangeCoderException
                | IllegalArgumentException | IllegalStateException
                | UnsupportedOperationException expected) {
            // A sentence about the stream is the correct outcome.
        } catch (RuntimeException wrong) {
            org.junit.jupiter.api.Assertions.fail("a damaged " + length + "-byte packet threw "
                    + wrong.getClass().getName() + ": " + wrong.getMessage());
        }
        decoder.reset();
        return 1;
    }

    /** Decodes a whole vector, checking each packet's range state as it goes. */
    private static Decoded decode(OpusTestVectors.Vector vector) {
        OpusDecoder decoder = new OpusDecoder(48_000, 2);
        List<OpusTestVectors.Packet> packets = vector.packets();

        assertEquals(48_000, decoder.sampleRate(), "Opus always decodes at 48 kHz");
        int samples = 0;
        for (OpusTestVectors.Packet packet : packets) {
            samples += OpusPacket.parse(packet.payload()).samples48k();
        }
        // Sized from the decoder's own channel count, not the packets': vector 7
        // switches between mono and stereo packets and every one of them still
        // produces two output channels.
        short[] pcm = new short[decoder.channels() * samples];

        int at = 0;
        int bitExact = 0;
        List<String> failures = new ArrayList<>();
        for (OpusTestVectors.Packet packet : packets) {
            byte[] payload = packet.payload();
            int produced = decoder.decode(payload, 0, payload.length, pcm, at);
            at += produced * 2;
            if (decoder.finalRange() == packet.expectedFinalRange()) {
                bitExact++;
            } else if (failures.size() < 64) {
                OpusPacket parsed = OpusPacket.parse(payload);
                failures.add(vector.name() + " packet " + packet.index() + " (" + parsed
                        + ", " + payload.length + " bytes): range " + decoder.finalRange()
                        + ", expected " + packet.expectedFinalRange());
            }
        }
        assertEquals(pcm.length, at, vector.name() + " produced the wrong number of samples");
        return new Decoded(vector.name(), pcm, packets.size(), bitExact, failures);
    }
}
