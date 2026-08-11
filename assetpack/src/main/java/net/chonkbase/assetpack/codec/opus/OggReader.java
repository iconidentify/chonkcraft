package net.chonkbase.assetpack.codec.opus;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Reads the Opus packets back out of an Ogg bitstream.
 *
 * <p>The inverse of {@link OggWriter}: RFC 3533 section 6 page framing, RFC 7845
 * for what the first two packets have to be. Pages are verified before they are
 * believed -- capture pattern, structure version, declared lengths against the
 * bytes that are actually there, sequence numbers in order, and the CRC of every
 * page -- and then the lacing tables are walked to put the packets back together
 * across page boundaries.
 *
 * <p><b>Every length in this format is a number the file supplies about itself.</b>
 * A page header declares how many lacing values follow it, and those lacing
 * values declare how many payload bytes follow them. Truncate a file, flip a
 * byte, or hand it a JPEG, and all of those still parse as integers -- they
 * simply point past the end of the array. The failure that produces is an
 * {@link ArrayIndexOutOfBoundsException} thrown from inside a demuxer, on
 * whatever thread was loading audio, with a stack trace that names none of the
 * things a person could act on. Every read here is bounds-checked first and
 * throws {@link OggException} naming the page and the byte offset it wanted, so
 * that "track 12 of the pack is truncated" is what reaches the log.
 *
 * <p>It reads the first logical bitstream and only that one. Pages belonging to
 * other serial numbers -- a multiplexed video stream, or a second Opus stream
 * chained on the end -- are skipped over, and reading stops at the
 * end-of-stream page. That is the whole of what a game pack needs and it is what
 * keeps the packet numbering meaningful.
 */
public final class OggReader {

    private final int serial;
    private final OpusHead head;
    private final OpusTags tags;
    private final List<byte[]> packets;
    private final long finalGranulePosition;
    private final int pageCount;
    private final int continuedPageCount;
    private final boolean endOfStreamSeen;

    private int next;

    /**
     * Parses the whole stream up front.
     *
     * <p>Eagerly rather than lazily, because the alternative is a corrupt page
     * discovered halfway through playback: an asset pack is opened once and a
     * file that is not going to work should say so at load, not in the middle of
     * a battle.
     */
    public OggReader(byte[] ogg) {
        Objects.requireNonNull(ogg, "ogg");
        if (ogg.length < OggWriter.HEADER_SIZE) {
            throw new OggException("not an Ogg stream: " + ogg.length
                    + " bytes is shorter than a single " + OggWriter.HEADER_SIZE + "-byte page header");
        }

        List<byte[]> found = new ArrayList<>();
        ByteArrayOutputStream partial = new ByteArrayOutputStream();
        boolean havePartial = false;
        boolean sawEnd = false;
        int streamSerial = 0;
        boolean serialKnown = false;
        int expectedSequence = 0;
        int pages = 0;
        int continued = 0;
        long lastGranule = 0;
        int firstPagePacketCount = -1;

        int at = 0;
        while (at < ogg.length && !sawEnd) {
            if (ogg.length - at < OggWriter.HEADER_SIZE) {
                throw new OggException("the stream ends mid-page-header at byte " + at
                        + ": " + (ogg.length - at) + " bytes left, "
                        + OggWriter.HEADER_SIZE + " needed");
            }
            if (ogg[at] != 'O' || ogg[at + 1] != 'g' || ogg[at + 2] != 'g' || ogg[at + 3] != 'S') {
                throw new OggException("no \"OggS\" capture pattern at byte " + at
                        + "; found 0x" + hex(ogg, at, 4));
            }
            int version = ogg[at + 4] & 0xff;
            if (version != OggWriter.VERSION) {
                throw new OggException("page at byte " + at + " declares Ogg structure version "
                        + version + "; RFC 3533 defines only version " + OggWriter.VERSION);
            }
            int flags = ogg[at + 5] & 0xff;
            long granule = readLe64(ogg, at + 6);
            int pageSerial = readLe32(ogg, at + 14);
            int sequence = readLe32(ogg, at + 18);
            int storedCrc = readLe32(ogg, at + 22);
            int segments = ogg[at + 26] & 0xff;

            int lacingAt = at + OggWriter.HEADER_SIZE;
            if (ogg.length - lacingAt < segments) {
                throw new OggException("page at byte " + at + " declares a " + segments
                        + "-value lacing table with only " + (ogg.length - lacingAt) + " bytes left");
            }
            int bodyLength = 0;
            for (int i = 0; i < segments; i++) {
                bodyLength += ogg[lacingAt + i] & 0xff;
            }
            int bodyAt = lacingAt + segments;
            if (ogg.length - bodyAt < bodyLength) {
                throw new OggException("page at byte " + at + " has a lacing table summing to "
                        + bodyLength + " bytes of payload with only " + (ogg.length - bodyAt)
                        + " bytes left in the stream");
            }
            int pageLength = OggWriter.HEADER_SIZE + segments + bodyLength;

            // The checksum is verified before the serial number is looked at,
            // and the order matters. A single flipped bit in the four serial
            // bytes of an audio page makes that page belong to some other
            // logical bitstream as far as the format is concerned, and the
            // serial-first reader below simply skipped it: a corrupt byte turned
            // a track into a shorter track, or into silence, and reported
            // nothing at all. Checking the CRC first means a page either belongs
            // to a real second stream -- in which case its checksum is fine and
            // it is skipped -- or it is damage, and damage is named.
            //
            // The page is copied rather than checksummed in place because the
            // CRC covers the whole page with its own four bytes zeroed, and the
            // caller's array is an input that has to come back unmodified.
            byte[] page = new byte[pageLength];
            System.arraycopy(ogg, at, page, 0, pageLength);
            page[22] = 0;
            page[23] = 0;
            page[24] = 0;
            page[25] = 0;
            int computedCrc = OggCrc.of(page, 0, pageLength);
            if (computedCrc != storedCrc) {
                throw new OggException("page " + sequence + " at byte " + at
                        + " fails its checksum: the page says 0x"
                        + Integer.toHexString(storedCrc) + " and its " + pageLength
                        + " bytes give 0x" + Integer.toHexString(computedCrc));
            }

            if (!serialKnown) {
                if ((flags & OggWriter.FLAG_BEGIN_OF_STREAM) == 0) {
                    throw new OggException("the first page does not carry the beginning-of-stream"
                            + " flag, so this is not the start of a logical bitstream");
                }
                streamSerial = pageSerial;
                serialKnown = true;
            }
            if (pageSerial != streamSerial) {
                // Another logical bitstream sharing the file, and intact.
                at += pageLength;
                continue;
            }

            if (sequence != expectedSequence) {
                throw new OggException("page sequence jumps from " + expectedSequence + " to "
                        + sequence + " at byte " + at + "; a page is missing or duplicated");
            }
            expectedSequence++;

            boolean continues = (flags & OggWriter.FLAG_CONTINUED) != 0;
            if (continues) {
                continued++;
            }
            if (continues != havePartial) {
                // RFC 7845 section 3 forbids decoding across either mismatch.
                // A pack is not a live stream joined mid-broadcast, so this is
                // corruption rather than a condition to work around.
                throw new OggException(continues
                        ? "page " + sequence + " at byte " + at + " claims to continue a packet,"
                          + " but the page before it ended one"
                        : "page " + sequence + " at byte " + at + " starts a new packet,"
                          + " but the page before it ended in the middle of one");
            }

            int payloadAt = bodyAt;
            int i = 0;
            int completedHere = 0;
            while (i < segments) {
                int length = 0;
                boolean terminated = false;
                while (i < segments) {
                    int value = ogg[lacingAt + i] & 0xff;
                    i++;
                    length += value;
                    if (value < 255) {
                        terminated = true;
                        break;
                    }
                }
                if (terminated) {
                    byte[] packet;
                    if (havePartial) {
                        partial.write(ogg, payloadAt, length);
                        packet = partial.toByteArray();
                        partial.reset();
                        havePartial = false;
                    } else {
                        packet = new byte[length];
                        System.arraycopy(ogg, payloadAt, packet, 0, length);
                    }
                    found.add(packet);
                    completedHere++;
                } else {
                    partial.write(ogg, payloadAt, length);
                    havePartial = true;
                }
                payloadAt += length;
            }

            if (pages == 0) {
                firstPagePacketCount = completedHere;
            }
            if (granule != -1L) {
                lastGranule = granule;
            }
            pages++;
            sawEnd = (flags & OggWriter.FLAG_END_OF_STREAM) != 0;
            at += pageLength;
        }

        if (havePartial) {
            throw new OggException("the stream ends in the middle of a packet: "
                    + partial.size() + " bytes were carried over from the last page and never"
                    + " terminated");
        }
        if (found.size() < 2) {
            throw new OggException("an Ogg Opus stream needs an identification header and a"
                    + " comment header; this one has " + found.size() + " packet"
                    + (found.size() == 1 ? "" : "s"));
        }
        if (firstPagePacketCount != 1) {
            throw new OggException("RFC 7845 section 3 puts the identification header alone on"
                    + " the first page, and this one carries " + firstPagePacketCount + " packets");
        }
        if (found.get(1).length > OpusTags.MAX_SIZE) {
            throw new OggException("the comment header is " + found.get(1).length
                    + " bytes; RFC 7845 section 5.2 allows treating anything over "
                    + OpusTags.MAX_SIZE + " as invalid");
        }

        this.serial = streamSerial;
        this.head = OpusHead.parse(found.get(0));
        this.tags = OpusTags.parse(found.get(1));
        this.packets = List.copyOf(found.subList(2, found.size()));
        this.finalGranulePosition = lastGranule;
        this.pageCount = pages;
        this.continuedPageCount = continued;
        this.endOfStreamSeen = sawEnd;
    }

    /** Output channels, from {@code OpusHead}. RFC 7845 section 5.1. */
    public int channels() {
        return head.channels();
    }

    /**
     * 48 kHz samples to decode and discard before the audio starts.
     *
     * <p>The encoder's own latency, RFC 7845 section 4.2. A player that ignores
     * it starts every file a few milliseconds early, on the encoder's warm-up
     * rather than on the recording, which is heard as a click.
     */
    public int preSkip() {
        return head.preSkip();
    }

    /**
     * The rate of the audio before it was encoded, as metadata only.
     *
     * <p>RFC 7845 section 5.1 is explicit that this "is _not_ the sample rate to
     * use for playback"; Opus decodes at 48 kHz whatever this says, and zero
     * means the muxer did not record it. Configure an audio device from this
     * field and a 44.1 kHz source plays back about nine percent slow.
     */
    public int inputSampleRate() {
        return head.inputSampleRate();
    }

    /**
     * The playback gain in Q7.8 dB, RFC 7845 section 5.1.
     *
     * <p>Zero in anything this project writes, because a muxer should bake gain
     * into the samples. Reported so that a file produced elsewhere is not played
     * at a volume its author did not intend.
     */
    public int outputGain() {
        return head.outputGain();
    }

    /** The channel mapping family: 0 for the mono and stereo files a pack holds. */
    public int channelMappingFamily() {
        return head.mappingFamily();
    }

    /** The logical bitstream serial number this reader followed. */
    public int serial() {
        return serial;
    }

    /** The {@code OpusTags} vendor string, RFC 7845 section 5.2. */
    public String vendor() {
        return tags.vendor();
    }

    /** The {@code NAME=value} user comments, in the order the file lists them. */
    public List<String> comments() {
        return tags.comments();
    }

    /**
     * The granule position of the last page that completed a packet.
     *
     * <p>The total 48 kHz sample count of the stream, pre-skip included, and the
     * number a player needs in order to trim the end: RFC 7845 section 4.4 lets
     * the final page declare fewer samples than its packets decode to, so that a
     * file can end somewhere other than a frame boundary. Rewrite a stream
     * without carrying this across and every track gains up to 20 ms of the
     * encoder's zero padding.
     */
    public long finalGranulePosition() {
        return finalGranulePosition;
    }

    /** Whether another audio packet is available. */
    public boolean hasNext() {
        return next < packets.size();
    }

    /** The next audio packet, headers already consumed. */
    public byte[] next() {
        if (!hasNext()) {
            throw new NoSuchElementException("the stream holds " + packets.size()
                    + " audio packets and all of them have been read");
        }
        return packets.get(next++);
    }

    /** How many audio packets the stream holds, the two headers excluded. */
    public int packetCount() {
        return packets.size();
    }

    /** Pages read from this logical bitstream, so a test can say how the writer paged. */
    int pageCount() {
        return pageCount;
    }

    /** Pages that opened with the tail of a packet, so a test can prove the split path ran. */
    int continuedPageCount() {
        return continuedPageCount;
    }

    /** Whether the end-of-stream flag was found; false means the file was truncated. */
    boolean endOfStreamSeen() {
        return endOfStreamSeen;
    }

    private static String hex(byte[] bytes, int at, int count) {
        StringBuilder text = new StringBuilder(count * 2);
        for (int i = 0; i < count && at + i < bytes.length; i++) {
            text.append(String.format("%02x", bytes[at + i]));
        }
        return text.toString();
    }

    private static int readLe32(byte[] bytes, int at) {
        return (bytes[at] & 0xff)
                | ((bytes[at + 1] & 0xff) << 8)
                | ((bytes[at + 2] & 0xff) << 16)
                | ((bytes[at + 3] & 0xff) << 24);
    }

    private static long readLe64(byte[] bytes, int at) {
        long value = 0;
        for (int i = 0; i < 8; i++) {
            value |= (bytes[at + i] & 0xffL) << (8 * i);
        }
        return value;
    }
}
