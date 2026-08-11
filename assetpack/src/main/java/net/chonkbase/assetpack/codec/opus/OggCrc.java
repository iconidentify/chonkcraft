package net.chonkbase.assetpack.codec.opus;

/**
 * The checksum in an Ogg page header.
 *
 * <p>A port of {@code _os_crc}/{@code crc_lookup} in libogg's
 * {@code src/framing.c}, the algorithm named in RFC 3533 section 6: a 32-bit
 * CRC over the whole page with the checksum field itself zeroed, generator
 * polynomial {@code 0x04c11db7}, initial value zero, no final inversion, and --
 * the part that catches everybody -- <em>not reflected</em>.
 *
 * <p>Not reflected is the whole warning. {@code java.util.zip.CRC32} is
 * CRC-32/ISO-HDLC: same polynomial, but the bits of every input byte are fed in
 * least-significant first, the register starts at {@code 0xffffffff}, and the
 * result is inverted. Reach for it here -- and it is right there in the JDK,
 * which is exactly why this keeps happening -- and every page of the file
 * carries a checksum that is wrong in a way nothing about the bytes reveals.
 * The file looks perfectly well formed in a hex dump, and every player on earth
 * refuses it: {@code ffmpeg} reports "Header processing failed" and stops,
 * {@code vorbis-tools} says "CRC mismatch", browsers play silence. There is no
 * partial failure mode to notice in testing. It either matches or the file does
 * not exist as far as the world is concerned.
 *
 * <p>The table is built at class-load time rather than written out. Sixteen
 * lines of literals with one digit wrong is the same defect as above and much
 * harder to see; {@code OggTest} pins the construction against a second,
 * table-free implementation and against the published check value for this
 * polynomial.
 */
final class OggCrc {

    /**
     * The generator polynomial, RFC 3533 section 6.
     *
     * <p>Written most-significant-bit-first, which is the form that goes with
     * an unreflected CRC. The same polynomial reversed is {@code 0xedb88320},
     * the constant every reflected CRC-32 implementation uses; if that number
     * appears anywhere near this file, something has gone wrong.
     */
    private static final int POLYNOMIAL = 0x04c1_1db7;

    private static final int[] TABLE = buildTable();

    private OggCrc() {
    }

    /**
     * The checksum of {@code length} bytes from {@code offset}.
     *
     * <p>Callers hand in the complete page, header included, with the four
     * checksum bytes at offset 22 set to zero. The result is stored back into
     * those four bytes little-endian.
     */
    static int of(byte[] data, int offset, int length) {
        int crc = 0;
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            crc = (crc << 8) ^ TABLE[((crc >>> 24) ^ (data[i] & 0xff)) & 0xff];
        }
        return crc;
    }

    /**
     * The 256-entry lookup table, one entry per leading byte.
     *
     * <p>Each entry is the remainder of that byte shifted to the top of the
     * register and divided out eight times. Because the CRC is unreflected the
     * shifts go left and the test bit is the top one; a reflected table shifts
     * right and tests the bottom bit, and the two produce completely different
     * numbers from the same polynomial.
     */
    private static int[] buildTable() {
        int[] table = new int[256];
        for (int i = 0; i < 256; i++) {
            int remainder = i << 24;
            for (int bit = 0; bit < 8; bit++) {
                remainder = (remainder & 0x8000_0000) != 0
                        ? (remainder << 1) ^ POLYNOMIAL
                        : remainder << 1;
            }
            table[i] = remainder;
        }
        return table;
    }

    /** The table itself, so {@code OggTest} can check it entry by entry. */
    static int[] table() {
        return TABLE.clone();
    }
}
