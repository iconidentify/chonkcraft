package net.chonkbase.assetpack.codec.opus;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;

/**
 * Writes Opus packets into an Ogg bitstream, the format a {@code .opus} file is.
 *
 * <p>The page framing is RFC 3533 section 6 and the Opus mapping on top of it is
 * RFC 7845: an {@code OpusHead} identification header alone on the first page,
 * an {@code OpusTags} comment header finishing the page it completes on, and
 * then the audio packets, laced into pages carrying a granule position counted
 * in 48 kHz samples.
 *
 * <p>Opus packets on their own are not a file. Nothing in a bare packet says how
 * many channels it decodes to, how many samples to discard at the start, where
 * one packet ends and the next begins, or whether the bytes arrived intact. All
 * four of those live in the container, and getting any of them wrong produces a
 * file that is not slightly wrong but wholly unplayable -- see {@link OggCrc}
 * for the one that catches everybody.
 *
 * <p><b>Pages are filled greedily to {@link #PAGE_TARGET_BYTES} and no
 * further.</b> The Ogg segment table holds 255 lacing values of up to 255 bytes,
 * so the hard ceiling on a page is 65,025 bytes of payload; a packet larger than
 * that -- or a packet that simply does not fit in what is left of the table --
 * is split across pages with the continued flag set on the one that carries the
 * rest. That path is not exotic and it is not dead code: a 120 ms packet of
 * eight coupled streams can reach 61 kB on its own, and the RFC devotes a whole
 * section to how large a packet may legally get. It is exercised deliberately in
 * {@code OggTest}.
 *
 * <p><b>The serial number is a parameter.</b> Everything else in this writer is
 * a function of its input, so two builds of the same asset pack produce
 * byte-identical files, which is what makes a pack diffable and its checksum
 * meaningful. A randomly chosen serial -- which is what the Ogg specification
 * suggests, because it is thinking about multiplexing independent streams into
 * one physical file -- would make every rebuild of a 400 MB pack differ from the
 * last for no reason a human could see.
 */
public final class OggWriter implements AutoCloseable {

    /** {@code "OggS"}, the four bytes every page starts with. */
    private static final byte[] CAPTURE_PATTERN = {'O', 'g', 'g', 'S'};

    /** The page header before the lacing table: capture, version, flags, granule, serial, sequence, CRC, segment count. */
    static final int HEADER_SIZE = 27;

    /** RFC 3533 section 6: the only structure version defined. */
    static final int VERSION = 0;

    /** Header type flag: this page opens with the tail of a packet begun on the previous one. */
    static final int FLAG_CONTINUED = 0x01;

    /** Header type flag: first page of the logical bitstream. */
    static final int FLAG_BEGIN_OF_STREAM = 0x02;

    /** Header type flag: last page of the logical bitstream. */
    static final int FLAG_END_OF_STREAM = 0x04;

    /** The segment table is one byte long, so a page carries at most 255 lacing values. */
    static final int MAX_SEGMENTS = 255;

    /** 255 lacing values of 255 bytes: the largest payload any single page can hold. */
    static final int MAX_PAGE_PAYLOAD = MAX_SEGMENTS * 255;

    /**
     * How much payload a page collects before the next packet starts a new one.
     *
     * <p>A page is the unit of loss and the unit of seeking, so the choice is a
     * trade. Fill every page to its 65,025-byte ceiling and a single corrupt
     * page takes out four seconds of music, and a player seeking has to decode
     * from four seconds back. Flush every packet onto its own page and the
     * 27-byte header plus lacing costs about 14% of a 200-byte packet, which on
     * a pack of game audio is real space. Four kilobytes is roughly a third of a
     * second of stereo music at 96 kbit/s and about six percent overhead, and it
     * is what libogg's own {@code ogg_stream_pageout} settled on.
     *
     * <p>It is a constant rather than a parameter on purpose: it is part of what
     * makes the output a function of the input.
     */
    static final int PAGE_TARGET_BYTES = 4096;

    /**
     * The vendor string in the comment header.
     *
     * <p>Fixed, and deliberately carries no version number. RFC 7845 section 5.2
     * says this field is for identifying the encoder "for tracing differences in
     * technical behavior", and a version in it would change the bytes of every
     * audio file in the pack on every release, which is the determinism rule
     * broken by a string constant.
     */
    static final String VENDOR = "chonk-assetpack";

    private final OutputStream out;
    private final int serial;

    private final byte[] lacing = new byte[MAX_SEGMENTS];
    private final ByteArrayOutputStream payload = new ByteArrayOutputStream(MAX_PAGE_PAYLOAD);
    private int lacingCount;

    private int sequence;
    private boolean nextPageContinues;
    private boolean pageCompletesAPacket;
    private long pageGranule;
    private long lastGranule;
    private boolean finished;

    /**
     * Opens a logical stream and writes both header pages immediately.
     *
     * @param out          where the pages go. It stays the caller's: {@link #close()}
     *                     finishes the stream and flushes, and does not close it.
     * @param channels     1 or 2. RFC 7845 section 5.1.1.1 allows no more in channel
     *                     mapping family 0, which is the only family this writes.
     * @param sampleRate   the rate of the audio <em>before</em> encoding, recorded as
     *                     metadata. Opus always decodes at 48 kHz; this field never
     *                     selects a playback rate. Zero means "unspecified".
     * @param preSkip      48 kHz samples to decode and discard at the start, the
     *                     encoder's own latency. Zero here plays the encoder's warm-up
     *                     as a click at the head of every sound.
     * @param serial       the logical bitstream serial. A parameter, not a random
     *                     number, so a rebuilt pack is byte-identical.
     */
    public OggWriter(OutputStream out, int channels, int sampleRate, int preSkip, int serial) {
        this.out = Objects.requireNonNull(out, "out");
        this.serial = serial;
        OpusHead head = OpusHead.family0(channels, preSkip, sampleRate, 0);
        writeHeaderPacket(head.encode());
        writeHeaderPacket(new OpusTags(VENDOR, List.of()).encode());
    }

    /**
     * Adds one Opus packet to the stream.
     *
     * @param granulePosition the total count of 48 kHz samples decodable from the
     *                        stream up to and including this packet, RFC 7845 section
     *                        4. It is a running total, not a duration, and it must
     *                        never go backwards: the difference between the last two
     *                        page granules is how a player decides how much of the
     *                        final packet to keep, so a granule that dips reads as a
     *                        negative number of samples to keep and truncates the end
     *                        of the file.
     */
    public void write(byte[] packet, int offset, int length, long granulePosition) {
        Objects.requireNonNull(packet, "packet");
        if (finished) {
            throw new IllegalStateException("the logical stream was already finished");
        }
        if (offset < 0 || length < 0 || offset > packet.length - length) {
            throw new IndexOutOfBoundsException("packet slice " + offset + "+" + length
                    + " is outside a " + packet.length + "-byte array");
        }
        if (granulePosition < 0) {
            throw new IllegalArgumentException("granule position " + granulePosition
                    + " is negative; -1 is the reserved value for a page no packet completes on");
        }
        if (granulePosition < lastGranule) {
            throw new IllegalArgumentException("granule position went backwards, from "
                    + lastGranule + " to " + granulePosition
                    + "; it is a running sample count, not a per-packet duration");
        }
        lastGranule = granulePosition;

        // Start a new page before this packet rather than after the last one, so
        // that a page is always pending when finish() runs and the end-of-stream
        // flag has somewhere to go. Setting it needs a page that has not been
        // written yet, and the last audio page is exactly the one a naive
        // flush-when-full writer has already handed to the OutputStream.
        if (lacingCount == MAX_SEGMENTS || payload.size() >= PAGE_TARGET_BYTES) {
            flushPage(false);
        }
        appendPacket(packet, offset, length, granulePosition);
    }

    /** Adds a whole array as one packet. */
    public void write(byte[] packet, long granulePosition) {
        write(packet, 0, packet.length, granulePosition);
    }

    /**
     * Writes the last page with the end-of-stream flag and flushes.
     *
     * <p>Without that flag a player cannot tell a complete file from a truncated
     * one, so it refuses to trust the final granule position, which is where the
     * end trimming lives -- RFC 7845 section 4.4. The audible result is the last
     * partial frame of every track played out in full: up to 20 ms of the
     * encoder's zero padding at the end of every sound effect.
     *
     * <p>Calling it twice does nothing the second time.
     */
    public void finish() {
        if (finished) {
            return;
        }
        finished = true;
        if (lacingCount == 0) {
            // Nothing was written but the two headers, so the end-of-stream flag
            // needs a page of its own. It gets the granule it would have had
            // rather than the reserved -1: -1 means "no packet finished on this
            // page", which is true and reads to a player as a stream that was
            // cut off mid-packet rather than one that simply has no audio.
            pageCompletesAPacket = true;
            pageGranule = lastGranule;
        }
        flushPage(true);
        try {
            out.flush();
        } catch (IOException e) {
            throw new UncheckedIOException("could not flush the Ogg stream", e);
        }
    }

    /**
     * Finishes the stream. The {@link OutputStream} is left open on purpose.
     *
     * <p>It belongs to the caller: a pack writes its tracks into zip entry
     * streams that the zip owns, and Ogg itself allows several logical streams
     * chained one after another in a single file. Closing here would break both.
     */
    @Override
    public void close() {
        finish();
    }

    /**
     * Lays a header packet out and forces the page to end.
     *
     * <p>RFC 7845 section 3 requires the identification header to be alone on the
     * first page and the comment header to finish the page it completes on. A
     * writer that let the first audio packet share the tags page produces a file
     * {@code libopusfile} rejects outright.
     */
    private void writeHeaderPacket(byte[] packet) {
        appendPacket(packet, 0, packet.length, 0L);
        flushPage(false);
    }

    /**
     * Writes the lacing values and bytes for one packet, spilling pages as needed.
     *
     * <p>The lacing rule, RFC 3533 section 6: a packet of {@code n} bytes is
     * written as {@code n/255} values of 255 followed by one value of
     * {@code n%255}. The terminating value below 255 is what says "the packet
     * ends here", which is why a packet whose length is an exact multiple of 255
     * has to spend a whole extra lacing value on a zero. Leave that zero out and
     * the packet silently swallows the next one.
     */
    private void appendPacket(byte[] packet, int offset, int length, long granulePosition) {
        int at = offset;
        int remaining = length;
        while (true) {
            int needed = remaining / 255 + 1;
            int room = MAX_SEGMENTS - lacingCount;
            if (needed <= room) {
                while (remaining >= 255) {
                    lacing[lacingCount++] = (byte) 255;
                    payload.write(packet, at, 255);
                    at += 255;
                    remaining -= 255;
                }
                lacing[lacingCount++] = (byte) remaining;
                payload.write(packet, at, remaining);
                pageCompletesAPacket = true;
                pageGranule = granulePosition;
                return;
            }
            // The table cannot hold the rest of this packet. Fill it with whole
            // 255-byte segments, none of which can terminate the packet, and
            // carry on the next page with the continued flag set. Because
            // needed > room implies remaining >= room*255, this never writes
            // past the end of the slice.
            for (int i = 0; i < room; i++) {
                lacing[lacingCount++] = (byte) 255;
                payload.write(packet, at, 255);
                at += 255;
                remaining -= 255;
            }
            flushPage(false);
        }
    }

    /**
     * Emits the pending page.
     *
     * <p>The granule position is that of the last packet that <em>completed</em>
     * on this page. A page wholly spanned by one long packet completes nothing
     * and gets the reserved value -1, RFC 7845 section 4; writing a real granule
     * there tells a player that samples finished arriving before they did, and
     * seeking lands in the wrong place.
     */
    private void flushPage(boolean endOfStream) {
        byte[] body = payload.toByteArray();
        byte[] page = new byte[HEADER_SIZE + lacingCount + body.length];
        System.arraycopy(CAPTURE_PATTERN, 0, page, 0, CAPTURE_PATTERN.length);
        page[4] = VERSION;
        page[5] = (byte) ((nextPageContinues ? FLAG_CONTINUED : 0)
                | (sequence == 0 ? FLAG_BEGIN_OF_STREAM : 0)
                | (endOfStream ? FLAG_END_OF_STREAM : 0));
        writeLe64(page, 6, pageCompletesAPacket ? pageGranule : -1L);
        writeLe32(page, 14, serial);
        writeLe32(page, 18, sequence);
        // Offset 22 is the CRC and stays zero while the CRC is computed over the
        // page that contains it, RFC 3533 section 6.
        page[26] = (byte) lacingCount;
        System.arraycopy(lacing, 0, page, HEADER_SIZE, lacingCount);
        System.arraycopy(body, 0, page, HEADER_SIZE + lacingCount, body.length);
        writeLe32(page, 22, OggCrc.of(page, 0, page.length));

        try {
            out.write(page);
        } catch (IOException e) {
            throw new UncheckedIOException("could not write Ogg page " + sequence, e);
        }

        // A page whose last lacing value is 255 has not ended its packet, so the
        // next page must say so. This is read back by OggReader and by every
        // other demuxer to decide whether the leading bytes of a page belong to
        // the packet before it.
        nextPageContinues = lacingCount > 0 && (lacing[lacingCount - 1] & 0xff) == 255;
        sequence++;
        lacingCount = 0;
        payload.reset();
        pageCompletesAPacket = false;
    }

    private static void writeLe32(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >>> 8);
        bytes[at + 2] = (byte) (value >>> 16);
        bytes[at + 3] = (byte) (value >>> 24);
    }

    private static void writeLe64(byte[] bytes, int at, long value) {
        for (int i = 0; i < 8; i++) {
            bytes[at + i] = (byte) (value >>> (8 * i));
        }
    }
}
