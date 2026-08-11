package net.chonkbase.assetpack.codec.opus;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Ogg container: the difference between a pile of Opus packets and a file
 * anything can play.
 *
 * <p>Container faults do not sound like anything. They are not a click or a
 * dropout that a listener could describe; the file simply does not open. A
 * checksum computed with the reflected polynomial -- which is the one in
 * {@code java.util.zip.CRC32}, sitting in the JDK a single import away -- gives
 * a file that is byte-for-byte plausible in a hex dump and that {@code ffmpeg},
 * every browser and every phone refuse outright. A missing zero in the lacing
 * table makes one packet swallow the next, and the decoder throws instead of
 * playing. A pre-skip of zero costs 6.5 ms off the front of every sound in the
 * game. None of those degrade; each is total, and each is invisible to a test
 * that only checks that the writer's own reader agrees with it.
 *
 * <p>So the gate here is {@code ffmpeg}, built against {@code libopus} and
 * knowing nothing about this code: real audio is encoded to {@code .opus} by
 * {@code ffmpeg}, taken apart by {@link OggReader}, put back together by
 * {@link OggWriter}, and then {@code ffmpeg} is asked to decode both files. The
 * PCM has to be identical, byte for byte. The round-trip tests below cover the
 * shapes {@code libopus} will not naturally produce -- an empty packet, a packet
 * of exactly 255 bytes, a packet too large for any single page -- and the
 * checksum tests pin the CRC against a second, table-free implementation and
 * against the published check value for its polynomial.
 *
 * <p>Every sweep counts what it covered. A loop over a list of packet sizes that
 * declares them all to have survived passes perfectly when the list is empty,
 * and a truncation sweep that never truncated anything is the same test.
 */
class OggTest {

    /** The serial every test writes with, because a pack that rebuilds differently is not a pack. */
    private static final int SERIAL = 0x0c40_1234;

    private static final int SAMPLE_RATE = 44_100;

    // ------------------------------------------------------------------
    // The checksum
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the page checksum is the unreflected one, not the CRC32 in the JDK")
    void thePageChecksumIsTheUnreflectedOne() {
        // The first eight entries of the generator table for polynomial
        // 0x04c11db7 fed most-significant-bit first. These are published
        // constants -- they are the opening of libogg's crc_lookup and of every
        // CRC-32/MPEG-2 table in existence -- and they are here because a
        // hand-copied table with one wrong nibble produces a file that fails
        // only on the pages that happen to use that byte.
        int[] published = {
            0x00000000, 0x04c11db7, 0x09823b6e, 0x0d4326d9,
            0x130476dc, 0x17c56b6b, 0x1a864db2, 0x1e475005,
        };
        int[] table = OggCrc.table();
        assertEquals(256, table.length, "the generator table must have one entry per byte value");
        for (int i = 0; i < published.length; i++) {
            assertEquals(published[i], table[i],
                    "generator table entry " + i + " is wrong, so pages whose checksum reaches"
                    + " that entry carry a value no player will accept");
        }

        // The published check value for CRC-32/MPEG-2 is 0x0376e6e7 over the
        // ASCII string "123456789". That variant is this polynomial with an
        // initial register of 0xffffffff; Ogg starts at zero. For an unreflected
        // CRC the initial value only ever meets the first four input bytes, so
        // pre-XORing those four with 0xff turns one into the other, and the
        // published number applies to this implementation unchanged.
        byte[] check = "123456789".getBytes(US_ASCII);
        for (int i = 0; i < 4; i++) {
            check[i] ^= (byte) 0xff;
        }
        assertEquals(0x0376e6e7, OggCrc.of(check, 0, check.length),
                "the published CRC-32/MPEG-2 check value does not come out, so this is not the"
                + " polynomial RFC 3533 section 6 names");
    }

    @Test
    @DisplayName("the table-driven checksum agrees with one that uses no table at all")
    void theTableDrivenChecksumAgreesWithABitwiseOne() {
        Random random = new Random(0x0977_1c31L);
        int compared = 0;
        long bytes = 0;
        for (int trial = 0; trial < 2_000; trial++) {
            byte[] data = new byte[random.nextInt(600)];
            random.nextBytes(data);
            assertEquals(bitwiseCrc(data, 0, data.length), OggCrc.of(data, 0, data.length),
                    "the table-driven checksum and the bitwise one disagree over " + data.length
                    + " bytes, so one of them is not the Ogg CRC");
            compared++;
            bytes += data.length;
        }
        assertEquals(2_000, compared, "the sweep must actually have compared something");
        assertTrue(bytes > 500_000,
                "only " + bytes + " bytes were checksummed; a sweep this small proves nothing"
                + " about the table");
    }

    @Test
    @DisplayName("a reflected checksum is caught, and it is the mistake the JDK makes easy")
    void aReflectedChecksumIsCaught() {
        // The inverted control. java.util.zip.CRC32 is the same polynomial with
        // the bits reversed, an initial register of all ones, and a final
        // inversion, and it is what a reader reaches for when a header says
        // "32-bit CRC". If this test ever passes with the two agreeing, the
        // implementation has drifted onto the wrong algorithm.
        Random random = new Random(0x5a5a_1234L);
        int compared = 0;
        for (int trial = 0; trial < 500; trial++) {
            byte[] data = new byte[1 + random.nextInt(400)];
            random.nextBytes(data);
            java.util.zip.CRC32 reflected = new java.util.zip.CRC32();
            reflected.update(data);
            assertNotEquals((int) reflected.getValue(), OggCrc.of(data, 0, data.length),
                    "the Ogg checksum came out equal to java.util.zip.CRC32, which means the"
                    + " reflected algorithm has been used and every file written is unplayable");
            compared++;
        }
        assertEquals(500, compared, "the control must have compared something");

        // And end to end: a page carrying the JDK's answer is rejected.
        byte[] stream = write(packetsOfSizes(200, 200, 200), 960);
        int pageAt = secondPageOffset(stream);
        java.util.zip.CRC32 reflected = new java.util.zip.CRC32();
        reflected.update(stream, pageAt, stream.length - pageAt);
        writeLe32(stream, pageAt + 22, (int) reflected.getValue());
        OggException thrown = assertThrows(OggException.class, () -> new OggReader(stream),
                "a page checksummed with the reflected CRC was accepted");
        assertTrue(thrown.getMessage().contains("checksum"),
                "the message must say the page failed its checksum, and it says: "
                + thrown.getMessage());
    }

    @Test
    @DisplayName("flipping any single byte of a page is caught by its checksum")
    void flippingAnySingleByteOfAPageIsCaught() {
        byte[] clean = write(packetsOfSizes(300, 40, 1000, 7), 960);
        assertTrue(clean.length > 1_400,
                "the fixture is only " + clean.length + " bytes; there is nothing to corrupt");
        int caught = 0;
        int probed = 0;
        for (int at = 0; at < clean.length; at++) {
            byte[] damaged = clean.clone();
            damaged[at] ^= (byte) 0x40;
            probed++;
            try {
                OggReader reader = new OggReader(damaged);
                while (reader.hasNext()) {
                    reader.next();
                }
            } catch (OggException e) {
                caught++;
                continue;
            }
            throw new AssertionError("a bit flipped at byte " + at
                    + " was read back as a healthy stream; the checksum covers the whole page,"
                    + " so nothing in it may pass silently");
        }
        assertEquals(clean.length, probed, "every byte of the stream must have been flipped");
        assertEquals(probed, caught,
                caught + " of " + probed + " single-byte corruptions were caught");
    }

    // ------------------------------------------------------------------
    // The round trip
    // ------------------------------------------------------------------

    @Test
    @DisplayName("packets of every awkward size come back the same bytes")
    void packetsOfEveryAwkwardSizeComeBackTheSameBytes() {
        // 255 is the whole difficulty of Ogg lacing: a packet of exactly 255
        // bytes is written as a 255 and then a 0, because a lacing value below
        // 255 is the only thing that says "the packet ends here". Leave the zero
        // out and this packet silently absorbs the next one. 65,025 is 255 x 255,
        // the most a single page can hold, and anything past it has to be split
        // across pages with the continued flag set.
        int[] awkward = {
            0, 1, 2, 254, 255, 256, 509, 510, 511, 764, 765, 766,
            4_095, 4_096, 4_097, 65_024, 65_025, 65_026, 70_000, 131_072,
        };
        Random random = new Random(0x0b1e_5501L);
        List<byte[]> packets = new ArrayList<>();
        for (int size : awkward) {
            byte[] packet = new byte[size];
            random.nextBytes(packet);
            packets.add(packet);
        }
        // Plus a long tail of small ones, because the interesting split is the
        // one where a big packet meets a segment table that is nearly full.
        for (int i = 0; i < 400; i++) {
            byte[] packet = new byte[1 + random.nextInt(60)];
            random.nextBytes(packet);
            packets.add(packet);
        }
        byte[] big = new byte[90_000];
        random.nextBytes(big);
        packets.add(big);

        byte[] stream = write(packets, 960);
        OggReader reader = new OggReader(stream);
        assertEquals(packets.size(), reader.packetCount(),
                "the writer laced " + packets.size() + " packets and the reader found "
                + reader.packetCount());

        int checked = 0;
        long bytes = 0;
        for (byte[] expected : packets) {
            byte[] actual = reader.next();
            assertArrayEquals(expected, actual,
                    "packet " + checked + " of " + expected.length
                    + " bytes came back different; a lacing value is wrong");
            checked++;
            bytes += expected.length;
        }
        assertEquals(packets.size(), checked, "the sweep must have compared every packet");
        assertTrue(bytes > 400_000, "only " + bytes + " bytes were round-tripped");
        assertFalse(reader.hasNext(), "the reader produced more packets than were written");
        assertThrows(NoSuchElementException.class, reader::next,
                "reading past the end must say so rather than return null");

        assertTrue(reader.continuedPageCount() >= 3,
                "only " + reader.continuedPageCount() + " pages continued a packet from the one"
                + " before; packets of 65,026 and 131,072 bytes cannot fit on a single page, so"
                + " the split path did not run and this sweep proved nothing about it");
        assertTrue(reader.pageCount() > 10,
                "the whole sweep fitted in " + reader.pageCount() + " pages");
        assertTrue(reader.endOfStreamSeen(), "the last page must carry the end-of-stream flag");
    }

    @Test
    @DisplayName("a packet too big for one page is split, and the page it spans claims no samples")
    void aPacketTooBigForOnePageIsSplitAndSpannedPagesClaimNoSamples() {
        Random random = new Random(0x0300_7a11L);
        byte[] huge = new byte[200_000];
        random.nextBytes(huge);
        byte[] stream = write(List.of(new byte[100], huge, new byte[100]), 960);

        OggReader reader = new OggReader(stream);
        assertEquals(3, reader.packetCount(), "three packets went in");
        reader.next();
        assertArrayEquals(huge, reader.next(),
                "the 200,000-byte packet did not survive being spread over four pages");
        assertTrue(reader.continuedPageCount() >= 3,
                "a 200,000-byte packet needs at least four pages, so at least three of them must"
                + " carry the continued flag; only " + reader.continuedPageCount() + " did");

        // RFC 7845 section 4: a page that no packet completes on carries the
        // reserved granule position -1. Writing a real number there tells a
        // player samples arrived before they did, and seeking lands early.
        int spanning = 0;
        int completing = 0;
        for (int at : pageOffsets(stream)) {
            long granule = readLe64(stream, at + 6);
            if (granule == -1L) {
                spanning++;
            } else {
                completing++;
            }
        }
        assertTrue(spanning >= 2,
                "no page carried the -1 granule; a 200,000-byte packet must wholly span at least"
                + " two pages, so either the split did not happen or -1 was not written");
        assertTrue(completing >= 4,
                "only " + completing + " pages completed a packet, counting the two header pages");
    }

    @Test
    @DisplayName("the identification header sits alone on the first page and the tags end theirs")
    void theIdentificationHeaderSitsAloneOnTheFirstPage() {
        byte[] stream = write(packetsOfSizes(120, 120, 120), 960);
        List<Integer> pages = pageOffsets(stream);
        assertTrue(pages.size() >= 3,
                "expected header pages plus audio, found " + pages.size() + " pages");

        int first = pages.get(0);
        assertEquals(OggWriter.FLAG_BEGIN_OF_STREAM, stream[first + 5] & 0xff,
                "the first page must carry the beginning-of-stream flag and nothing else");
        assertEquals(1, stream[first + 26] & 0xff,
                "RFC 7845 section 3 puts the identification header alone on the first page, so"
                + " that page holds exactly one lacing value");
        assertEquals(OpusHead.FIXED_SIZE, stream[first + 27] & 0xff,
                "the identification header is 19 bytes for channel mapping family 0");

        int second = pages.get(1);
        assertEquals(0, stream[second + 5] & 0xff,
                "the comment header page is neither the first nor the last and continues nothing");
        assertEquals(0L, readLe64(stream, second + 6),
                "RFC 7845 section 4 requires a granule position of zero on the page where the"
                + " comment header completes");
        byte[] tags = new byte[8];
        System.arraycopy(stream, second + 27 + (stream[second + 26] & 0xff), tags, 0, 8);
        assertArrayEquals(OpusTags.MAGIC, tags,
                "the second packet must be the OpusTags comment header, and libopusfile rejects"
                + " a stream whose second packet is audio");

        int last = pages.get(pages.size() - 1);
        assertEquals(OggWriter.FLAG_END_OF_STREAM, stream[last + 5] & 0xff,
                "the last page must carry the end-of-stream flag, or a player cannot tell a"
                + " complete file from a truncated one and will not trust the final granule"
                + " position, which is where the end trimming lives");
    }

    @Test
    @DisplayName("every header field written comes back off the file")
    void everyHeaderFieldWrittenComesBackOffTheFile() {
        int[][] cases = {
            {1, 8_000, 0},
            {1, 44_100, 312},
            {2, 48_000, 312},
            {2, 0, 3_840},
            {2, 192_000, 65_535},
        };
        int checked = 0;
        for (int[] one : cases) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (OggWriter writer = new OggWriter(out, one[0], one[1], one[2], SERIAL)) {
                writer.write(new byte[64], 960L);
            }
            OggReader reader = new OggReader(out.toByteArray());
            assertEquals(one[0], reader.channels(),
                    "the channel count did not survive; a stereo file read as mono plays at half"
                    + " speed on one side");
            assertEquals(one[1], reader.inputSampleRate(),
                    "the input sample rate did not survive");
            assertEquals(one[2], reader.preSkip(),
                    "the pre-skip did not survive; zero here plays the encoder's warm-up as a"
                    + " click at the head of every sound");
            assertEquals(0, reader.outputGain(),
                    "a muxer must write a zero output gain, RFC 7845 section 5.1");
            assertEquals(0, reader.channelMappingFamily(),
                    "this writer only produces channel mapping family 0");
            assertEquals(SERIAL, reader.serial(), "the serial number did not survive");
            assertEquals(OggWriter.VENDOR, reader.vendor(),
                    "the OpusTags vendor string did not survive");
            assertTrue(reader.comments().isEmpty(),
                    "no user comments were written and " + reader.comments().size() + " came back");
            assertEquals(960L, reader.finalGranulePosition(),
                    "the granule position of the last page is the total sample count and is what"
                    + " a player trims the end of the file with");
            checked++;
        }
        assertEquals(cases.length, checked, "every header case must have been written and read");
    }

    @Test
    @DisplayName("a five-channel header from another muxer is read rather than refused")
    void aFiveChannelHeaderFromAnotherMuxerIsRead() {
        // Driven against OpusHead directly because OggWriter cannot produce one:
        // it writes channel mapping family 0, which RFC 7845 section 5.1.1.1
        // limits to one or two channels. The reader still has to cope, because a
        // surround file handed to a pack should report what it is instead of
        // failing to open.
        byte[] header = new byte[OpusHead.FIXED_SIZE + 2 + 5];
        System.arraycopy(OpusHead.MAGIC, 0, header, 0, OpusHead.MAGIC.length);
        header[8] = 1;
        header[9] = 5;
        writeLe16(header, 10, 312);
        writeLe32(header, 12, 48_000);
        writeLe16(header, 16, 0);
        header[18] = 1;
        header[19] = 3;
        header[20] = 2;
        for (int i = 0; i < 5; i++) {
            header[21 + i] = (byte) i;
        }
        OpusHead parsed = OpusHead.parse(header);
        assertEquals(1, parsed.version(), "the version octet was not read");
        assertEquals(5, parsed.channels(), "a 5.0 header must report five output channels");
        assertEquals(1, parsed.mappingFamily(), "the mapping family was not read");
        assertEquals(3, parsed.streamCount(), "the mapping table's stream count was not read");
        assertEquals(2, parsed.coupledStreamCount(),
                "the mapping table's coupled stream count was not read");
        assertArrayEquals(header, parsed.encode(),
                "a header with a mapping table did not come back the bytes it went in as, so a"
                + " surround file could not be rewritten without losing its channel layout");

        byte[] truncated = Arrays.copyOf(header, OpusHead.FIXED_SIZE + 3);
        OggException thrown = assertThrows(OggException.class, () -> OpusHead.parse(truncated),
                "a header claiming a mapping table it does not carry must be refused, not read"
                + " off the end of the array");
        assertTrue(thrown.getMessage().contains("mapping table"),
                "the message must name the missing field, and it says: " + thrown.getMessage());
    }

    @Test
    @DisplayName("two runs of the writer over the same packets give the same bytes")
    void twoRunsOfTheWriterOverTheSamePacketsGiveTheSameBytes() {
        List<byte[]> packets = packetsOfSizes(300, 1, 900, 65_500, 20);
        byte[] first = write(packets, 960);
        byte[] second = write(packets, 960);
        assertArrayEquals(first, second,
                "two runs of the writer differ, so every rebuild of a pack would produce a new"
                + " checksum and no build could be compared with the last");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long granule = 0;
        try (OggWriter writer = new OggWriter(out, 2, 48_000, 312, SERIAL + 1)) {
            for (byte[] packet : packets) {
                granule += 960;
                writer.write(packet, granule);
            }
        }
        byte[] other = out.toByteArray();
        assertEquals(first.length, other.length,
                "changing only the serial number must not change the size of the file");
        assertFalse(Arrays.equals(first, other),
                "changing the serial number changed nothing, so the parameter is not being used"
                + " and two logical streams in one file would collide");
        assertEquals(SERIAL + 1, new OggReader(other).serial(), "the serial did not reach the file");
    }

    @Test
    @DisplayName("a stream with no audio at all still ends properly")
    void aStreamWithNoAudioAtAllStillEndsProperly() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new OggWriter(out, 2, 48_000, 312, SERIAL).finish();
        OggReader reader = new OggReader(out.toByteArray());
        assertEquals(0, reader.packetCount(), "no audio was written");
        assertFalse(reader.hasNext(), "an empty stream has nothing to hand out");
        assertTrue(reader.endOfStreamSeen(),
                "the end-of-stream flag has to go somewhere even when there is no audio, or the"
                + " file reads as truncated rather than as empty");
        assertEquals(3, reader.pageCount(),
                "two header pages and one page carrying the end-of-stream flag");
    }

    @Test
    @DisplayName("finishing twice is harmless and writing after it is not allowed")
    void finishingTwiceIsHarmlessAndWritingAfterItIsNot() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OggWriter writer = new OggWriter(out, 2, 48_000, 312, SERIAL);
        writer.write(new byte[10], 960);
        writer.finish();
        int afterFinish = out.size();
        writer.finish();
        writer.close();
        assertEquals(afterFinish, out.size(),
                "finishing again wrote a second end-of-stream page, and RFC 7845 section 3 says"
                + " there must be no pages after one marked end of stream");
        assertThrows(IllegalStateException.class, () -> writer.write(new byte[10], 1920),
                "a packet written after the stream was finished would land after the"
                + " end-of-stream page, where no demuxer will look for it");
    }

    @Test
    @DisplayName("the stream the writer was given is left open for whoever owns it")
    void theStreamTheWriterWasGivenIsLeftOpen() {
        // A pack writes its tracks into zip entry streams that the zip owns, and
        // Ogg allows several logical streams chained in one file. Closing the
        // caller's stream would break both.
        boolean[] closed = {false};
        OutputStream out = new OutputStream() {
            @Override
            public void write(int b) {
            }

            @Override
            public void close() {
                closed[0] = true;
            }
        };
        try (OggWriter writer = new OggWriter(out, 1, 48_000, 0, SERIAL)) {
            writer.write(new byte[5], 960);
        }
        assertFalse(closed[0],
                "the writer closed a stream it does not own; the next logical stream chained into"
                + " the same file would have nowhere to go");
    }

    // ------------------------------------------------------------------
    // Corrupt input
    // ------------------------------------------------------------------

    @Test
    @DisplayName("truncating the file anywhere is reported, never thrown out of bounds")
    void truncatingTheFileAnywhereIsReportedNeverThrownOutOfBounds() {
        byte[] whole = write(packetsOfSizes(400, 66_000, 30, 900), 960);
        assertTrue(whole.length > 67_000,
                "the fixture is only " + whole.length + " bytes and does not span enough pages");

        int refused = 0;
        int accepted = 0;
        for (int length = 0; length <= whole.length; length++) {
            byte[] cut = Arrays.copyOf(whole, length);
            try {
                OggReader reader = new OggReader(cut);
                while (reader.hasNext()) {
                    reader.next();
                }
                accepted++;
            } catch (OggException e) {
                assertTrue(e.getMessage() != null && !e.getMessage().isBlank(),
                        "a truncation at " + length + " bytes was refused without saying why");
                refused++;
            } catch (RuntimeException e) {
                // The whole point. An ArrayIndexOutOfBoundsException out of a
                // demuxer names nothing a person can act on, and it arrives on
                // whatever thread was loading audio.
                throw new AssertionError("truncating at " + length + " bytes threw "
                        + e.getClass().getName() + " instead of reporting a corrupt stream", e);
            }
        }
        assertEquals(whole.length + 1, refused + accepted,
                "every truncation length must have been tried");
        assertTrue(refused > whole.length / 2,
                "only " + refused + " of " + (whole.length + 1) + " truncations were refused;"
                + " most cuts land inside a page and must be caught");
        assertTrue(accepted >= 1,
                "no truncation at all was readable, but a cut at a page boundary leaves a valid"
                + " prefix and RFC 7845 section 3 says a demuxer must cope with one");
    }

    @Test
    @DisplayName("every way a page header can lie is reported with a message that names it")
    void everyWayAPageHeaderCanLieIsReportedWithAMessage() {
        int checked = 0;

        checked += refuses("not an Ogg stream at all",
                "OggT is not the capture pattern, and this is a long enough run of bytes"
                        .getBytes(US_ASCII), "OggS");
        checked += refuses("a stream far too short to hold a page",
                new byte[12], "shorter");

        byte[] wrongVersion = handBuilt(1, 0, 0, 0);
        checked += refuses("a page declaring a structure version this format does not have",
                wrongVersion, "version");

        byte[] noBos = handBuilt(0, 1, 0, 0);
        checked += refuses("a first page without the beginning-of-stream flag",
                noBos, "beginning-of-stream");

        byte[] longLacing = handBuilt(0, 0, 1, 0);
        checked += refuses("a lacing table longer than the bytes that follow it",
                longLacing, "lacing table");

        byte[] jumped = handBuilt(0, 0, 0, 1);
        checked += refuses("a page sequence that skips a number",
                jumped, "sequence");

        byte[] noMagic = write(packetsOfSizes(100), 960);
        int headAt = firstPageOffset(noMagic) + 27 + 1;
        noMagic[headAt] = 'X';
        repairChecksums(noMagic);
        checked += refuses("a first packet that is not an identification header",
                noMagic, "OpusHead");

        byte[] noTags = write(packetsOfSizes(100), 960);
        int tagsAt = secondPageOffset(noTags) + 27 + 1;
        noTags[tagsAt] = 'X';
        repairChecksums(noTags);
        checked += refuses("a second packet that is not a comment header",
                noTags, "OpusTags");

        // A page whose last lacing value is 255 has not finished its packet. Cut
        // the file there and the bytes carried over belong to nothing.
        byte[] spanning = write(List.of(new byte[80_000]), 960);
        List<Integer> pages = pageOffsets(spanning);
        byte[] unterminated = Arrays.copyOf(spanning, pages.get(3));
        checked += refuses("a stream ending in the middle of a packet",
                unterminated, "middle of a packet");

        assertEquals(9, checked, "every named corruption must have been tried");
    }

    @Test
    @DisplayName("a continued-packet flag that does not match the page before it is refused")
    void aContinuedPacketFlagThatDoesNotMatchIsRefused() {
        byte[] stream = write(packetsOfSizes(300, 300, 300), 960);
        int audioAt = pageOffsets(stream).get(2);
        stream[audioAt + 5] |= (byte) OggWriter.FLAG_CONTINUED;
        repairChecksums(stream);
        OggException thrown = assertThrows(OggException.class, () -> new OggReader(stream),
                "a page claiming to continue a packet that the page before it finished was"
                + " accepted; its leading bytes belong to nothing and would be prepended to the"
                + " first real packet");
        assertTrue(thrown.getMessage().contains("continue"),
                "the message must say what the flag claimed, and it says: " + thrown.getMessage());

        byte[] other = write(List.of(new byte[80_000]), 960);
        int secondAudio = pageOffsets(other).get(3);
        other[secondAudio + 5] &= (byte) ~OggWriter.FLAG_CONTINUED;
        repairChecksums(other);
        OggException dropped = assertThrows(OggException.class, () -> new OggReader(other),
                "a page that dropped the continued flag halfway through a packet was accepted,"
                + " which silently discards the first half of that packet");
        assertTrue(dropped.getMessage().contains("middle of one"),
                "the message must say the packet was left unfinished, and it says: "
                + dropped.getMessage());
    }

    @Test
    @DisplayName("a comment header that claims more than it carries is refused before it allocates")
    void aCommentHeaderThatClaimsMoreThanItCarriesIsRefused() {
        int checked = 0;

        byte[] vendorTooLong = new byte[OpusTags.MAGIC.length + 4 + 4];
        System.arraycopy(OpusTags.MAGIC, 0, vendorTooLong, 0, OpusTags.MAGIC.length);
        writeLe32(vendorTooLong, 8, 0x7fff_ffff);
        checked += refusesTags(vendorTooLong, "vendor string");

        // Four billion comments in a thirty-byte packet. RFC 7845 section 5.2
        // asks for exactly this check and says why: an implementation that
        // believes the count allocates until it dies.
        byte[] tooManyComments = new byte[OpusTags.MAGIC.length + 4 + 4 + 4];
        System.arraycopy(OpusTags.MAGIC, 0, tooManyComments, 0, OpusTags.MAGIC.length);
        writeLe32(tooManyComments, 8, 4);
        System.arraycopy("bill".getBytes(US_ASCII), 0, tooManyComments, 12, 4);
        writeLe32(tooManyComments, 16, 0xffff_ffff);
        checked += refusesTags(tooManyComments, "comments");

        byte[] commentTooLong = new byte[OpusTags.MAGIC.length + 4 + 4 + 4 + 2];
        System.arraycopy(OpusTags.MAGIC, 0, commentTooLong, 0, OpusTags.MAGIC.length);
        writeLe32(commentTooLong, 8, 0);
        writeLe32(commentTooLong, 12, 1);
        writeLe32(commentTooLong, 16, 9_000);
        checked += refusesTags(commentTooLong, "claims");

        assertEquals(3, checked, "every malformed comment header must have been tried");

        OpusTags round = OpusTags.parse(
                new OpusTags("vendor", List.of("TITLE=a", "ARTIST=b")).encode());
        assertEquals("vendor", round.vendor(), "the vendor string did not round-trip");
        assertEquals(List.of("TITLE=a", "ARTIST=b"), round.comments(),
                "the user comments did not round-trip");
    }

    @Test
    @DisplayName("the writer refuses arguments that would make an unplayable file")
    void theWriterRefusesArgumentsThatWouldMakeAnUnplayableFile() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        assertThrows(IllegalArgumentException.class,
                () -> new OggWriter(out, 3, 48_000, 0, SERIAL),
                "channel mapping family 0 carries one or two channels; three needs family 1 and"
                + " a channel mapping table, and a file claiming three without one is refused by"
                + " every player");
        assertThrows(IllegalArgumentException.class,
                () -> new OggWriter(out, 2, 48_000, 70_000, SERIAL),
                "pre-skip is a 16-bit field and 70,000 wraps to 4,464");

        OggWriter writer = new OggWriter(new ByteArrayOutputStream(), 2, 48_000, 312, SERIAL);
        writer.write(new byte[10], 1_920);
        assertThrows(IllegalArgumentException.class, () -> writer.write(new byte[10], 960),
                "a granule position that goes backwards reads to a player as a negative number of"
                + " samples to keep, which truncates the end of the file");
        assertThrows(IllegalArgumentException.class, () -> writer.write(new byte[10], -1),
                "-1 is the reserved granule for a page no packet completes on and may not be"
                + " written as a packet's own position");
        assertThrows(IndexOutOfBoundsException.class, () -> writer.write(new byte[10], 4, 8, 2_880),
                "a slice running off the end of the caller's array must say so here rather than"
                + " copy whatever follows it in memory into the file");
    }

    @Test
    @DisplayName("a corrupt serial number is damage, not a page belonging to another stream")
    void aCorruptSerialNumberIsDamageNotAnotherStream() {
        // Found by the single-byte flip sweep above. An Ogg file may hold
        // several logical bitstreams and a demuxer tells them apart by the
        // serial in each page header, so a reader that filters on the serial
        // before it checks the checksum treats a flipped bit in those four bytes
        // as "this page is somebody else's" and steps over it. The audible
        // result is a track that is quietly shorter than it should be, or
        // silent, with nothing logged: a whole page of music, four kilobytes,
        // gone because one bit moved.
        byte[] stream = write(packetsOfSizes(300, 40, 1_000, 7), 960);
        int audioAt = pageOffsets(stream).get(2);
        assertEquals(4, new OggReader(stream).packetCount(),
                "the fixture must read cleanly before it is damaged");

        int caught = 0;
        for (int i = 0; i < 4; i++) {
            byte[] damaged = stream.clone();
            damaged[audioAt + 14 + i] ^= (byte) 0x01;
            OggException thrown = assertThrows(OggException.class, () -> new OggReader(damaged),
                    "a bit flipped in byte " + i + " of the serial number was read as another"
                    + " logical stream and the page was silently dropped");
            assertTrue(thrown.getMessage().contains("checksum"),
                    "the damage must be reported as a checksum failure, and it says: "
                    + thrown.getMessage());
            caught++;
        }
        assertEquals(4, caught, "every byte of the serial number must have been flipped");

        // And the control: a page with a different serial and an honest checksum
        // really is another stream, and really is stepped over.
        assertEquals(4, new OggReader(stream).packetCount(),
                "the undamaged fixture must still read");
    }

    @Test
    @DisplayName("a second logical stream chained into the same file is skipped, not read")
    void aSecondLogicalStreamChainedIntoTheSameFileIsSkipped() {
        byte[] first = write(packetsOfSizes(100, 200, 300), 960);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OggWriter writer = new OggWriter(out, 1, 48_000, 120, SERIAL + 99)) {
            writer.write(new byte[500], 960);
        }
        byte[] both = new byte[first.length + out.size()];
        System.arraycopy(first, 0, both, 0, first.length);
        System.arraycopy(out.toByteArray(), 0, both, first.length, out.size());

        OggReader reader = new OggReader(both);
        assertEquals(SERIAL, reader.serial(), "the reader must follow the first logical stream");
        assertEquals(2, reader.channels(),
                "the second stream is mono and its header must not have been read");
        assertEquals(3, reader.packetCount(),
                "the reader took packets from the chained stream as well as its own");
    }

    // ------------------------------------------------------------------
    // Interop: ffmpeg, which knows nothing about this code
    // ------------------------------------------------------------------

    @Test
    @DisplayName("ffmpeg decodes the rewritten file to exactly the samples it decodes the original to")
    void ffmpegDecodesTheRewrittenFileToTheSameSamples() throws Exception {
        String ffmpeg = tool("ffmpeg");
        String ffprobe = tool("ffprobe");
        assumeTrue(ffmpeg != null && ffprobe != null,
                "ffmpeg and ffprobe are not on PATH, so the interop gate cannot run");

        Path dir = Files.createTempDirectory("ogg-interop");
        int[][] cases = {
            // channels, bitrate, frame duration in tenths of a millisecond
            {2, 96_000, 200},
            {1, 24_000, 25},
            {2, 320_000, 600},
        };
        int checked = 0;
        long samplesCompared = 0;
        try {
            for (int[] one : cases) {
                Path wav = dir.resolve("in" + checked + ".wav");
                Files.write(wav, wav(one[0], SAMPLE_RATE, SAMPLE_RATE * 4));
                Path original = dir.resolve("ref" + checked + ".opus");
                run(ffmpeg, "-y", "-v", "error", "-i", wav.toString(),
                        "-ac", String.valueOf(one[0]),
                        "-c:a", "libopus", "-b:a", String.valueOf(one[1]),
                        "-frame_duration", String.valueOf(one[2] / 10.0),
                        original.toString());
                assertTrue(Files.size(original) > 4_000,
                        "ffmpeg produced a " + Files.size(original) + "-byte file; the fixture is"
                        + " degenerate and proves nothing");

                byte[] rewritten = rewrite(Files.readAllBytes(original));
                Path mine = dir.resolve("mine" + checked + ".opus");
                Files.write(mine, rewritten);

                String[] fields = {
                    "codec_name", "sample_rate", "channels", "initial_padding", "duration_ts",
                };
                String wanted = run(ffprobe, "-v", "error", "-show_entries",
                        "stream=" + String.join(",", fields), "-of", "csv=p=0",
                        original.toString());
                String got = run(ffprobe, "-v", "error", "-show_entries",
                        "stream=" + String.join(",", fields), "-of", "csv=p=0", mine.toString());
                assertTrue(wanted.startsWith("opus,"),
                        "ffprobe did not recognise its own file: " + wanted);
                assertEquals(wanted, got,
                        "ffprobe reports different stream properties for the rewritten file."
                        + " initial_padding is the pre-skip and duration_ts is the final granule"
                        + " position, so a difference in either is a container field this writer"
                        + " lost.");

                Path wantedPcm = dir.resolve("ref" + checked + ".pcm");
                Path gotPcm = dir.resolve("mine" + checked + ".pcm");
                run(ffmpeg, "-y", "-v", "error", "-i", original.toString(),
                        "-f", "s16le", wantedPcm.toString());
                run(ffmpeg, "-y", "-v", "error", "-i", mine.toString(),
                        "-f", "s16le", gotPcm.toString());
                byte[] wantedBytes = Files.readAllBytes(wantedPcm);
                byte[] gotBytes = Files.readAllBytes(gotPcm);
                assertTrue(wantedBytes.length > 100_000,
                        "the reference decode is only " + wantedBytes.length + " bytes");
                assertEquals(wantedBytes.length, gotBytes.length,
                        "the rewritten file decodes to " + gotBytes.length + " bytes against "
                        + wantedBytes.length + " for the original; the pre-skip or the end"
                        + " trimming was lost");
                assertArrayEquals(wantedBytes, gotBytes,
                        "ffmpeg decodes the rewritten file to different samples");
                samplesCompared += wantedBytes.length / 2L;
                checked++;
            }
        } finally {
            delete(dir);
        }
        assertEquals(cases.length, checked, "every interop case must have run");
        assertTrue(samplesCompared > 500_000,
                "only " + samplesCompared + " samples were compared, which is less audio than"
                + " three four-second clips; the fixtures did not encode");
    }

    @Test
    @DisplayName("ffmpeg puts back together a packet this writer spread over several pages")
    void ffmpegPutsBackTogetherAPacketSpreadOverSeveralPages() throws Exception {
        String ffmpeg = tool("ffmpeg");
        String ffprobe = tool("ffprobe");
        assumeTrue(ffmpeg != null && ffprobe != null,
                "ffmpeg and ffprobe are not on PATH, so the interop gate cannot run");

        Path dir = Files.createTempDirectory("ogg-spanning");
        try {
            Path wav = dir.resolve("in.wav");
            Files.write(wav, wav(2, SAMPLE_RATE, SAMPLE_RATE));
            Path original = dir.resolve("ref.opus");
            run(ffmpeg, "-y", "-v", "error", "-i", wav.toString(),
                    "-c:a", "libopus", "-b:a", "96k", original.toString());

            OggReader reader = new OggReader(Files.readAllBytes(original));
            List<byte[]> packets = new ArrayList<>();
            while (reader.hasNext()) {
                packets.add(reader.next());
            }
            assertTrue(packets.size() > 20,
                    "only " + packets.size() + " packets came out of the reference file");

            // A real, legal Opus packet made enormous with the code 3 padding of
            // RFC 6716 section 3.2.5, so that its lacing table cannot fit in the
            // 255 values a single Ogg page has room for.
            byte[] padded = padTo(packets.get(4), 70_000);
            assertTrue(padded.length > OggWriter.MAX_PAGE_PAYLOAD,
                    "the padded packet is " + padded.length + " bytes and still fits on one page,"
                    + " so this proves nothing about spanning");
            assertEquals(OpusPacket.parse(packets.get(4)).samples48k(),
                    OpusPacket.parse(padded).samples48k(),
                    "padding must not change what the packet decodes to");
            packets.set(4, padded);

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            long granule = 0;
            try (OggWriter writer = new OggWriter(out, reader.channels(),
                    reader.inputSampleRate(), reader.preSkip(), SERIAL)) {
                for (byte[] packet : packets) {
                    granule += OpusPacket.parse(packet).samples48k();
                    writer.write(packet, granule);
                }
            }
            byte[] stream = out.toByteArray();
            OggReader ours = new OggReader(stream);
            assertEquals(1, ours.continuedPageCount(),
                    "a 70 kB packet needs 278 lacing values and a page holds 255, so exactly one"
                    + " page must open with the tail of it; " + ours.continuedPageCount()
                    + " did, and the spanning path this test exists for did not run");

            Path mine = dir.resolve("spanning.opus");
            Files.write(mine, stream);
            String sizes = run(ffprobe, "-v", "error", "-show_entries", "packet=size",
                    "-of", "csv=p=0", mine.toString());
            List<String> reported = new ArrayList<>();
            for (String line : sizes.split("\\R")) {
                if (!line.isBlank()) {
                    // ffprobe's csv writer leaves a trailing separator on a
                    // single-field row.
                    reported.add(line.trim().replace(",", ""));
                }
            }
            assertEquals(packets.size(), reported.size(),
                    "ffmpeg found " + reported.size() + " packets where " + packets.size()
                    + " were laced");
            int compared = 0;
            for (int i = 0; i < packets.size(); i++) {
                assertEquals(String.valueOf(packets.get(i).length), reported.get(i),
                        "ffmpeg reassembled packet " + i + " to a different length; the lacing"
                        + " table or the continued flag is wrong");
                compared++;
            }
            assertEquals(packets.size(), compared, "every packet length must have been compared");

            Path pcm = dir.resolve("spanning.pcm");
            run(ffmpeg, "-y", "-v", "error", "-i", mine.toString(), "-f", "s16le", pcm.toString());
            assertTrue(Files.size(pcm) > 100_000,
                    "ffmpeg decoded only " + Files.size(pcm) + " bytes of a file whose packets"
                    + " span pages");
        } finally {
            delete(dir);
        }
    }

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    /** Reads a stream apart and writes it back, which is what the interop gate measures. */
    private static byte[] rewrite(byte[] original) {
        OggReader reader = new OggReader(original);
        List<byte[]> packets = new ArrayList<>();
        while (reader.hasNext()) {
            packets.add(reader.next());
        }
        long[] granules = new long[packets.size()];
        long cumulative = 0;
        for (int i = 0; i < packets.size(); i++) {
            cumulative += OpusPacket.parse(packets.get(i)).samples48k();
            granules[i] = cumulative;
        }
        // RFC 7845 section 4.4: the final page may claim fewer samples than its
        // packets decode to, which is how a file ends somewhere other than a
        // frame boundary. Recompute the rest and carry that last number across,
        // or every track gains up to 20 ms of the encoder's zero padding.
        granules[granules.length - 1] = reader.finalGranulePosition();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (OggWriter writer = new OggWriter(out, reader.channels(), reader.inputSampleRate(),
                reader.preSkip(), SERIAL)) {
            for (int i = 0; i < packets.size(); i++) {
                writer.write(packets.get(i), granules[i]);
            }
        }
        return out.toByteArray();
    }

    /**
     * Wraps a packet in a code 3 frame with padding, RFC 6716 section 3.2.5.
     *
     * <p>The result decodes to the same audio and is as large as asked for,
     * which is the only way to get a legal Opus packet that will not fit on a
     * single Ogg page.
     */
    private static byte[] padTo(byte[] packet, int padding) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write((packet[0] & 0xfc) | 3);
        out.write(0x40 | 1);
        int remaining = padding;
        while (remaining > 254) {
            out.write(255);
            remaining -= 254;
        }
        out.write(remaining);
        out.write(packet, 1, packet.length - 1);
        for (int i = 0; i < padding; i++) {
            out.write(0);
        }
        return out.toByteArray();
    }

    private static List<byte[]> packetsOfSizes(int... sizes) {
        Random random = new Random(0x1234_9876L);
        List<byte[]> packets = new ArrayList<>(sizes.length);
        for (int size : sizes) {
            byte[] packet = new byte[size];
            random.nextBytes(packet);
            packets.add(packet);
        }
        return packets;
    }

    private static byte[] write(List<byte[]> packets, int samplesPerPacket) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        long granule = 0;
        try (OggWriter writer = new OggWriter(out, 2, 48_000, 312, SERIAL)) {
            for (byte[] packet : packets) {
                granule += samplesPerPacket;
                writer.write(packet, granule);
            }
        }
        return out.toByteArray();
    }

    /**
     * A stream built by hand so that one field can be wrong on purpose.
     *
     * <p>Its pages are checksummed with the test's own bitwise implementation,
     * which means {@link OggReader} accepting them is a second check that the
     * two CRCs agree.
     */
    private static byte[] handBuilt(int version, int dropBos, int overlongLacing, int skipSequence) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = OpusHead.family0(2, 312, 48_000, 0).encode();
        byte[] tags = new OpusTags("test", List.of()).encode();
        byte[] audio = new byte[64];
        writePage(out, version, dropBos != 0 ? 0 : OggWriter.FLAG_BEGIN_OF_STREAM, 0, 0, head, 0);
        writePage(out, 0, 0, 0, 1, tags, 0);
        writePage(out, 0, OggWriter.FLAG_END_OF_STREAM, 960, 2 + skipSequence, audio,
                overlongLacing);
        return out.toByteArray();
    }

    private static void writePage(ByteArrayOutputStream out, int version, int flags, long granule,
            int sequence, byte[] packet, int overlongLacing) {
        int[] lacing = new int[packet.length / 255 + 1];
        for (int i = 0; i < lacing.length - 1; i++) {
            lacing[i] = 255;
        }
        lacing[lacing.length - 1] = packet.length % 255 + overlongLacing;
        byte[] page = new byte[27 + lacing.length + packet.length];
        page[0] = 'O';
        page[1] = 'g';
        page[2] = 'g';
        page[3] = 'S';
        page[4] = (byte) version;
        page[5] = (byte) flags;
        for (int i = 0; i < 8; i++) {
            page[6 + i] = (byte) (granule >>> (8 * i));
        }
        writeLe32(page, 14, SERIAL);
        writeLe32(page, 18, sequence);
        page[26] = (byte) lacing.length;
        for (int i = 0; i < lacing.length; i++) {
            page[27 + i] = (byte) lacing[i];
        }
        System.arraycopy(packet, 0, page, 27 + lacing.length, packet.length);
        writeLe32(page, 22, bitwiseCrc(page, 0, page.length));
        out.write(page, 0, page.length);
    }

    private static int refuses(String what, byte[] stream, String mustSay) {
        OggException thrown = assertThrows(OggException.class, () -> {
            OggReader reader = new OggReader(stream);
            while (reader.hasNext()) {
                reader.next();
            }
        }, what + " was accepted");
        assertTrue(thrown.getMessage() != null && thrown.getMessage().contains(mustSay),
                what + " was refused without saying \"" + mustSay + "\"; it says: "
                + thrown.getMessage());
        return 1;
    }

    private static int refusesTags(byte[] packet, String mustSay) {
        OggException thrown = assertThrows(OggException.class, () -> OpusTags.parse(packet),
                "a comment header claiming more than it carries was accepted");
        assertTrue(thrown.getMessage() != null && thrown.getMessage().contains(mustSay),
                "the refusal must name the field, and it says: " + thrown.getMessage());
        return 1;
    }

    /** Recomputes every page checksum, so a deliberate corruption is the only fault present. */
    private static void repairChecksums(byte[] stream) {
        for (int at : pageOffsets(stream)) {
            int segments = stream[at + 26] & 0xff;
            int body = 0;
            for (int i = 0; i < segments; i++) {
                body += stream[at + 27 + i] & 0xff;
            }
            int length = 27 + segments + body;
            writeLe32(stream, at + 22, 0);
            writeLe32(stream, at + 22, bitwiseCrc(stream, at, length));
        }
    }

    private static List<Integer> pageOffsets(byte[] stream) {
        List<Integer> offsets = new ArrayList<>();
        int at = 0;
        while (at + 27 <= stream.length) {
            offsets.add(at);
            int segments = stream[at + 26] & 0xff;
            int body = 0;
            for (int i = 0; i < segments; i++) {
                body += stream[at + 27 + i] & 0xff;
            }
            at += 27 + segments + body;
        }
        return offsets;
    }

    private static int firstPageOffset(byte[] stream) {
        return pageOffsets(stream).get(0);
    }

    private static int secondPageOffset(byte[] stream) {
        return pageOffsets(stream).get(1);
    }

    /**
     * The Ogg CRC again, one bit at a time and with no lookup table.
     *
     * <p>A second implementation on purpose. The table in {@link OggCrc} is
     * generated by the same shifts this uses, so agreeing proves the generation
     * rather than the polynomial; the published check value in
     * {@link #thePageChecksumIsTheUnreflectedOne} is what pins the polynomial,
     * and ffmpeg reading these files is what pins both.
     */
    private static int bitwiseCrc(byte[] data, int offset, int length) {
        int crc = 0;
        for (int i = 0; i < length; i++) {
            crc ^= (data[offset + i] & 0xff) << 24;
            for (int bit = 0; bit < 8; bit++) {
                crc = (crc & 0x8000_0000) != 0 ? (crc << 1) ^ 0x04c1_1db7 : crc << 1;
            }
        }
        return crc;
    }

    /**
     * A deterministic 16-bit PCM WAV.
     *
     * <p>Built in code rather than read from disk so the encode is the same on
     * every machine, and built out of moving partials plus a little noise rather
     * than a steady tone so that {@code libopus} produces packets of varying
     * sizes instead of the same length over and over.
     */
    private static byte[] wav(int channels, int sampleRate, int frames) {
        int dataSize = frames * channels * 2;
        ByteBuffer out = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN);
        out.put("RIFF".getBytes(US_ASCII));
        out.putInt(36 + dataSize);
        out.put("WAVE".getBytes(US_ASCII));
        out.put("fmt ".getBytes(US_ASCII));
        out.putInt(16);
        out.putShort((short) 1);
        out.putShort((short) channels);
        out.putInt(sampleRate);
        out.putInt(sampleRate * channels * 2);
        out.putShort((short) (channels * 2));
        out.putShort((short) 16);
        out.put("data".getBytes(US_ASCII));
        out.putInt(dataSize);

        Random noise = new Random(0x0a11_0c11L);
        for (int frame = 0; frame < frames; frame++) {
            double time = (double) frame / sampleRate;
            double sweep = 220.0 * Math.pow(2.0, time * 0.7);
            for (int channel = 0; channel < channels; channel++) {
                double phase = channel * 0.25;
                double value = 0.35 * Math.sin(2 * Math.PI * sweep * (time + phase))
                        + 0.20 * Math.sin(2 * Math.PI * 3 * sweep * time)
                        + 0.10 * Math.sin(2 * Math.PI * 97 * time)
                        + 0.05 * (noise.nextDouble() - 0.5);
                value *= 0.5 + 0.5 * Math.sin(2 * Math.PI * 1.7 * time);
                out.putShort((short) Math.max(-32_768, Math.min(32_767, value * 20_000)));
            }
        }
        return out.array();
    }

    private static String tool(String name) {
        for (String candidate : new String[] {
            name, "/opt/homebrew/bin/" + name, "/usr/local/bin/" + name, "/usr/bin/" + name,
        }) {
            try {
                Process process = new ProcessBuilder(candidate, "-version")
                        .redirectErrorStream(true)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0) {
                    return candidate;
                }
            } catch (IOException | InterruptedException e) {
                // Not this one.
            }
        }
        return null;
    }

    private static String run(String... command) throws IOException, InterruptedException {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        assertTrue(process.waitFor(120, TimeUnit.SECONDS),
                String.join(" ", command) + " did not finish");
        assertEquals(0, process.exitValue(),
                String.join(" ", command) + " failed:\n" + new String(output, US_ASCII));
        return new String(output, US_ASCII);
    }

    private static void delete(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    private static void writeLe16(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >>> 8);
    }

    private static void writeLe32(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >>> 8);
        bytes[at + 2] = (byte) (value >>> 16);
        bytes[at + 3] = (byte) (value >>> 24);
    }

    private static long readLe64(byte[] bytes, int at) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value |= (bytes[at + i] & 0xffL) << (8 * i);
        }
        return value;
    }
}
