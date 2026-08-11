package net.chonkbase.assetpack.codec;

/**
 * Reads fields of any width out of a byte array, most significant bit first.
 *
 * <p>New infrastructure, with no upstream analogue, and the mirror image of
 * {@link BitWriter}.
 *
 * <p>Two things here are deliberate. Signed fields are sign-extended on the way
 * out rather than by the caller: FLAC stores warm-up samples and escaped
 * residuals as two's complement in a field narrower than an int, and a reader
 * that returned them unsigned would turn every negative sample into a large
 * positive one, which is a decoder that produces a loud rectified version of
 * the music instead of the music. Second, {@link #readUnary} finds the
 * terminating one bit with {@link Long#numberOfLeadingZeros} over everything
 * currently buffered, rather than testing a bit at a time. A Rice quotient is
 * read once per residual and there are 44100 of them per channel per second,
 * which is what keeps a 238-second stereo track decoding in 0.2 seconds
 * instead of in a noticeable pause.
 */
final class BitReader {

    private final byte[] bytes;
    private final int limit;
    private int position;

    /** Bits read from the array but not yet consumed, in the low {@link #cacheBits} bits. */
    private long cache;
    private int cacheBits;

    BitReader(byte[] bytes) {
        this(bytes, 0, bytes.length);
    }

    BitReader(byte[] bytes, int offset, int limit) {
        this.bytes = bytes;
        this.position = offset;
        this.limit = limit;
    }

    /** Reads {@code count} bits as an unsigned value. {@code count} is 0 to 32. */
    int readBits(int count) {
        if (count == 0) {
            return 0;
        }
        if (count < 0 || count > 32) {
            throw new IllegalArgumentException("field width out of range: " + count);
        }
        fill(count);
        cacheBits -= count;
        int value = (int) (cache >>> cacheBits);
        return count == 32 ? value : value & ((1 << count) - 1);
    }

    /** Reads {@code count} bits as an unsigned value, for the 36-bit sample count. */
    long readBitsLong(int count) {
        if (count > 32) {
            long high = readBits(count - 32) & 0xFFFFFFFFL;
            return (high << 32) | (readBits(32) & 0xFFFFFFFFL);
        }
        return readBits(count) & 0xFFFFFFFFL;
    }

    /** Reads {@code count} bits as a two's complement value, sign extended. */
    int readSigned(int count) {
        if (count == 0) {
            return 0;
        }
        int raw = readBits(count);
        if (count == 32) {
            return raw;
        }
        int signBit = 1 << (count - 1);
        return (raw ^ signBit) - signBit;
    }

    /** Reads one bit. */
    int readBit() {
        return readBits(1);
    }

    /** Counts zeroes up to and including the terminating one bit. */
    int readUnary() {
        int zeroes = 0;
        while (true) {
            if (cacheBits == 0) {
                if (position >= limit) {
                    throw new Flac.FlacFormatException(
                            "the stream ends inside a Rice quotient after " + zeroes + " zeroes");
                }
                cache = bytes[position++] & 0xFFL;
                cacheBits = 8;
            }
            long window = cache & ((1L << cacheBits) - 1);
            if (window == 0) {
                zeroes += cacheBits;
                cacheBits = 0;
            } else {
                int highestSetBit = 63 - Long.numberOfLeadingZeros(window);
                zeroes += cacheBits - 1 - highestSetBit;
                cacheBits = highestSetBit;
                return zeroes;
            }
        }
    }

    /** Discards bits up to the next byte boundary, as a frame's trailing padding requires. */
    void alignToByte() {
        cacheBits -= cacheBits & 7;
    }

    /** True when nothing is buffered, which every CRC boundary in a frame requires. */
    boolean isAligned() {
        return (cacheBits & 7) == 0;
    }

    /**
     * The offset of the next unread byte. Only meaningful when byte aligned,
     * and it is what the frame CRC is measured against.
     */
    int bytePosition() {
        if ((cacheBits & 7) != 0) {
            throw new IllegalStateException("reader is not byte aligned");
        }
        return position - (cacheBits >>> 3);
    }

    /** Moves to an absolute byte offset, discarding anything buffered. */
    void seekToByte(int offset) {
        position = offset;
        cache = 0;
        cacheBits = 0;
    }

    /** Bytes between the next unread byte and the end of the input. */
    int bytesRemaining() {
        return limit - bytePosition();
    }

    /** The array being read, so a CRC can be taken over a range without copying. */
    byte[] array() {
        return bytes;
    }

    private void fill(int count) {
        while (cacheBits < count) {
            if (position >= limit) {
                throw new Flac.FlacFormatException(
                        "the stream ends " + (count - cacheBits) + " bits short of a complete field");
            }
            cache = (cache << 8) | (bytes[position++] & 0xFFL);
            cacheBits += 8;
        }
    }
}
