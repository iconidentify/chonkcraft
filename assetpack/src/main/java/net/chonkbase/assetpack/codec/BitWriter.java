package net.chonkbase.assetpack.codec;

import java.util.Arrays;

/**
 * A growable buffer that accepts fields of any width, most significant bit first.
 *
 * <p>New infrastructure, with no upstream analogue. LegacyEngine hands audio to a
 * native library and never touches a bitstream, so there is nothing to cite.
 *
 * <p>Most significant bit first is the whole contract, and it is the part that
 * is easy to get wrong. FLAC packs a four-bit Rice parameter, a variable-length
 * unary quotient and a k-bit remainder against each other with no padding
 * between them, so a writer that filled bytes from the low end would produce a
 * stream that still decodes -- every field would come back a mirror image of
 * itself, residuals would be plausible-looking numbers, and the music would
 * play as noise. Nothing about the container would be malformed. That is the
 * failure this class exists to make impossible: bits leave through the top of
 * the accumulator, in the order they were written, and the accumulator is a
 * {@code long} so that a 32-bit field on top of seven pending bits has room.
 */
final class BitWriter {

    private byte[] bytes;
    private int length;

    /** Bits written but not yet whole bytes, held in the low {@link #pendingBits} bits. */
    private long pending;
    private int pendingBits;

    BitWriter() {
        this(1024);
    }

    BitWriter(int initialCapacity) {
        this.bytes = new byte[Math.max(16, initialCapacity)];
    }

    /**
     * Appends the low {@code count} bits of {@code value}, most significant first.
     *
     * @param count 0 to 32; a count of zero writes nothing, which is what an
     *              escaped residual partition of zero raw bits needs
     */
    void writeBits(int value, int count) {
        if (count == 0) {
            return;
        }
        if (count < 0 || count > 32) {
            throw new IllegalArgumentException("field width out of range: " + count);
        }
        long masked = count == 32
                ? (value & 0xFFFFFFFFL)
                : (value & ((1L << count) - 1));
        pending = (pending << count) | masked;
        pendingBits += count;
        while (pendingBits >= 8) {
            pendingBits -= 8;
            append((byte) (pending >>> pendingBits));
        }
    }

    /** Appends the low {@code count} bits of a value wider than an int. */
    void writeBitsLong(long value, int count) {
        if (count > 32) {
            writeBits((int) (value >>> 32), count - 32);
            writeBits((int) value, 32);
        } else {
            writeBits((int) value, count);
        }
    }

    /**
     * Appends {@code quotient} zeroes and a terminating one, which is how a
     * Rice-coded quotient is spelled.
     *
     * <p>Written in runs of at most 32 because {@link #writeBits} takes an int.
     * A quotient large enough to need that is a residual the encoder should
     * have escaped rather than Rice coded, but a stream that arrives here is
     * still written correctly rather than silently truncated.
     */
    void writeUnary(int quotient) {
        int remaining = quotient;
        while (remaining >= 32) {
            writeBits(0, 32);
            remaining -= 32;
        }
        writeBits(1, remaining + 1);
    }

    /**
     * Writes a frame or sample number in FLAC's extended UTF-8 form, which
     * carries up to 36 bits rather than Unicode's 21.
     */
    void writeUtf8(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("frame number cannot be negative: " + value);
        }
        if (value < 0x80L) {
            writeBits((int) value, 8);
        } else if (value < 0x800L) {
            writeBits((int) (0xC0 | (value >>> 6)), 8);
            continuation(value, 0);
        } else if (value < 0x10000L) {
            writeBits((int) (0xE0 | (value >>> 12)), 8);
            continuation(value, 6);
            continuation(value, 0);
        } else if (value < 0x200000L) {
            writeBits((int) (0xF0 | (value >>> 18)), 8);
            continuation(value, 12);
            continuation(value, 6);
            continuation(value, 0);
        } else if (value < 0x4000000L) {
            writeBits((int) (0xF8 | (value >>> 24)), 8);
            continuation(value, 18);
            continuation(value, 12);
            continuation(value, 6);
            continuation(value, 0);
        } else if (value < 0x80000000L) {
            writeBits((int) (0xFC | (value >>> 30)), 8);
            continuation(value, 24);
            continuation(value, 18);
            continuation(value, 12);
            continuation(value, 6);
            continuation(value, 0);
        } else if (value < 0x1000000000L) {
            writeBits(0xFE, 8);
            continuation(value, 30);
            continuation(value, 24);
            continuation(value, 18);
            continuation(value, 12);
            continuation(value, 6);
            continuation(value, 0);
        } else {
            throw new IllegalArgumentException("frame number exceeds 36 bits: " + value);
        }
    }

    private void continuation(long value, int shift) {
        writeBits((int) (0x80 | ((value >>> shift) & 0x3F)), 8);
    }

    /** Pads with zero bits up to the next byte boundary. */
    void alignToByte() {
        if (pendingBits > 0) {
            writeBits(0, 8 - pendingBits);
        }
    }

    /** Whole bytes written so far. Only meaningful when the writer is byte aligned. */
    int size() {
        return length;
    }

    /** True when nothing is pending, which every CRC boundary in a frame requires. */
    boolean isAligned() {
        return pendingBits == 0;
    }

    /** The backing array, for computing a CRC over a range without copying it out. */
    byte[] array() {
        return bytes;
    }

    /** Appends the bytes of another writer, which must be byte aligned. */
    void writeAll(BitWriter other) {
        if (!other.isAligned() || !isAligned()) {
            throw new IllegalStateException("both writers must be byte aligned to splice");
        }
        ensure(length + other.length);
        System.arraycopy(other.bytes, 0, bytes, length, other.length);
        length += other.length;
    }

    /** A copy of everything written. The writer must be byte aligned. */
    byte[] toByteArray() {
        if (pendingBits != 0) {
            throw new IllegalStateException("stream ends mid-byte with " + pendingBits + " bits");
        }
        return Arrays.copyOf(bytes, length);
    }

    private void append(byte b) {
        ensure(length + 1);
        bytes[length++] = b;
    }

    private void ensure(int capacity) {
        if (capacity > bytes.length) {
            int grown = bytes.length;
            while (grown < capacity) {
                grown += grown >> 1;
            }
            bytes = Arrays.copyOf(bytes, grown);
        }
    }
}
