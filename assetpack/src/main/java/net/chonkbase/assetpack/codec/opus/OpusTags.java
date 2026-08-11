package net.chonkbase.assetpack.codec.opus;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The comment header, the second packet of every Ogg Opus stream.
 *
 * <p>RFC 7845 section 5.2: the magic {@code "OpusTags"} followed by a
 * Vorbis-comment block -- a length-prefixed vendor string, a comment count, and
 * that many length-prefixed {@code NAME=value} strings, every length a 32-bit
 * little-endian unsigned. The one difference from Vorbis, shared with Theora
 * and Speex, is that the trailing framing bit is not present.
 *
 * <p>It is mandatory even when there is nothing to say. A stream whose second
 * packet is audio rather than {@code "OpusTags"} is rejected by every demuxer
 * that follows the specification, so the empty header is written unconditionally
 * -- eight bytes of magic, a vendor string, and a zero.
 *
 * <p>The lengths are read as unsigned 64-bit values and bounds-checked against
 * what is actually in the packet before anything is allocated. RFC 7845 section
 * 5.2 asks for exactly that, and says why: a 30-byte comment header can claim
 * four billion comments, and an implementation that believes it allocates until
 * it dies. A game loading an untrusted pack should get an exception naming the
 * field, not an {@link OutOfMemoryError}.
 */
final class OpusTags {

    /** {@code "OpusTags"}, chosen like {@code "OpusHead"} to be an invalid Opus TOC byte. */
    static final byte[] MAGIC = {'O', 'p', 'u', 's', 'T', 'a', 'g', 's'};

    /**
     * The largest comment header this reader will look at, RFC 7845 section 5.2.
     *
     * <p>The specification permits treating anything above 120 MB as invalid.
     * Taking that permission is the difference between rejecting a hostile pack
     * and trying to hold it in memory.
     */
    static final int MAX_SIZE = 125_829_120;

    private final String vendor;
    private final List<String> comments;

    OpusTags(String vendor, List<String> comments) {
        this.vendor = vendor;
        this.comments = List.copyOf(comments);
    }

    /** The packet bytes exactly as they go on the wire. */
    byte[] encode() {
        byte[] vendorBytes = vendor.getBytes(StandardCharsets.UTF_8);
        byte[][] commentBytes = new byte[comments.size()][];
        int size = MAGIC.length + 4 + vendorBytes.length + 4;
        for (int i = 0; i < commentBytes.length; i++) {
            commentBytes[i] = comments.get(i).getBytes(StandardCharsets.UTF_8);
            size += 4 + commentBytes[i].length;
        }
        byte[] bytes = new byte[size];
        System.arraycopy(MAGIC, 0, bytes, 0, MAGIC.length);
        int at = MAGIC.length;
        at = writeString(bytes, at, vendorBytes);
        writeLe32(bytes, at, commentBytes.length);
        at += 4;
        for (byte[] comment : commentBytes) {
            at = writeString(bytes, at, comment);
        }
        return bytes;
    }

    /** Reads a comment header, checking every declared length against what is there. */
    static OpusTags parse(byte[] packet) {
        if (packet.length < MAGIC.length + 4) {
            throw new OggException("the comment header is " + packet.length
                    + " bytes; RFC 7845 section 5.2 needs at least " + (MAGIC.length + 4));
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (packet[i] != MAGIC[i]) {
                throw new OggException("the second packet is not an Opus comment header:"
                        + " expected the magic \"OpusTags\", found "
                        + new String(packet, 0, Math.min(8, packet.length), StandardCharsets.ISO_8859_1));
            }
        }
        int at = MAGIC.length;
        long vendorLength = readLe32(packet, at);
        at += 4;
        if (vendorLength > packet.length - at) {
            throw new OggException("the comment header declares a " + vendorLength
                    + "-byte vendor string with only " + (packet.length - at) + " bytes left");
        }
        String vendor = new String(packet, at, (int) vendorLength, StandardCharsets.UTF_8);
        at += (int) vendorLength;

        if (packet.length - at < 4) {
            throw new OggException("the comment header ends before its comment count");
        }
        long count = readLe32(packet, at);
        at += 4;
        // Four bytes of length per comment is the floor, so a count that cannot
        // even fit its own length fields is a lie and is caught before the list
        // is sized. This is the allocation RFC 7845 section 5.2 warns about.
        if (count > (packet.length - at) / 4L) {
            throw new OggException("the comment header declares " + count
                    + " comments with only " + (packet.length - at) + " bytes left");
        }
        List<String> comments = new ArrayList<>((int) count);
        for (long i = 0; i < count; i++) {
            if (packet.length - at < 4) {
                throw new OggException("the comment header ends inside the length of comment " + i);
            }
            long length = readLe32(packet, at);
            at += 4;
            if (length > packet.length - at) {
                throw new OggException("comment " + i + " claims " + length
                        + " bytes with only " + (packet.length - at) + " left");
            }
            comments.add(new String(packet, at, (int) length, StandardCharsets.UTF_8));
            at += (int) length;
        }
        return new OpusTags(vendor, comments);
    }

    String vendor() {
        return vendor;
    }

    List<String> comments() {
        return Collections.unmodifiableList(comments);
    }

    private static int writeString(byte[] bytes, int at, byte[] value) {
        writeLe32(bytes, at, value.length);
        System.arraycopy(value, 0, bytes, at + 4, value.length);
        return at + 4 + value.length;
    }

    private static void writeLe32(byte[] bytes, int at, int value) {
        bytes[at] = (byte) value;
        bytes[at + 1] = (byte) (value >>> 8);
        bytes[at + 2] = (byte) (value >>> 16);
        bytes[at + 3] = (byte) (value >>> 24);
    }

    /** Unsigned, because a length near 4 billion read as a negative int passes every bounds check. */
    private static long readLe32(byte[] bytes, int at) {
        return (bytes[at] & 0xffL)
                | ((bytes[at + 1] & 0xffL) << 8)
                | ((bytes[at + 2] & 0xffL) << 16)
                | ((bytes[at + 3] & 0xffL) << 24);
    }
}
