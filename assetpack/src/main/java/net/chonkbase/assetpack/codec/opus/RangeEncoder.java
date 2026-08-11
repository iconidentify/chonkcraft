package net.chonkbase.assetpack.codec.opus;

import java.util.Arrays;
import java.util.Objects;

/**
 * Packs entropy-coded symbols into one Opus frame.
 *
 * <p>A port of {@code ec_enc} and its operations in {@code celt/entenc.c},
 * specified normatively by RFC 6716 section 5.1. The mirror image of
 * {@link RangeDecoder}, and the reason it exists here now, before there is
 * anything to encode, is that it is the only way to prove the decoder: a
 * round trip through both is an exact test of every path in either.
 *
 * <p>The encoder carries two pieces of state the decoder has no analogue for,
 * {@code rem} and {@code ext}. Each pass of renormalisation produces nine bits
 * -- eight of data and one of carry -- and the carry can propagate backwards
 * through any run of 255 bytes already emitted. So the last non-255 byte is
 * held back in {@code rem}, the run of 255s behind it is counted in
 * {@code ext}, and both are resolved the moment a byte arrives that cannot
 * carry. Nothing is written until it can no longer change.
 *
 * <p>Raw bits are packed from the far end of the same buffer, backwards, and
 * {@link #finish()} is where the two meet: it emits the fewest range coder bits
 * that still decode correctly whatever follows them, then ORs the last raw-bit
 * byte into the last range coder byte if they land on the same one. RFC 6716
 * section 5.1.5 chooses that terminating value precisely so this overlap cannot
 * corrupt the range coded data.
 *
 * <p>No floating point, for the same reason as the decoder: this is exact
 * integer arithmetic and a conforming encoder is bit-exact. {@code val} and
 * {@code rng} are held in {@code long} carrying unsigned 32-bit values, because
 * {@code rng} starts at exactly 2**31 and a signed {@code int} is negative
 * there.
 *
 * <p>Departs from upstream in one place, deliberately.
 * {@code ec_write_byte} silently drops a byte that will not fit and leaves
 * {@code error} unset unless there happen to be raw bits left over, so an
 * encoder handed too small a buffer can return a truncated stream that decodes
 * to something else entirely with nothing said. Here that throws
 * {@link RangeCoderException} at the byte that would not fit. The bytes
 * produced are identical in every case that fits; the difference is only that
 * a case that does not fit stops instead of lying. Whether it can fit is the
 * caller's arithmetic to do -- {@link #tell()} against the buffer size is what
 * CELT's allocator checks -- so reaching this is a bug in the caller.
 *
 * <p>{@code ec_enc_shrink} and {@code ec_enc_patch_initial_bits} are not ported.
 * Both belong to the CELT encoder, which does not exist yet, and an untested
 * port of either would be a worse starting point than an absent one.
 */
public final class RangeEncoder {

    /** Bits in an output symbol, {@code EC_SYM_BITS}. */
    private static final int SYM_BITS = 8;

    /** {@code EC_SYM_MAX}. */
    private static final int SYM_MAX = 255;

    /** {@code EC_CODE_BITS}. */
    private static final int CODE_BITS = 32;

    /** {@code EC_CODE_SHIFT}: where the nine carry-bearing bits of val start. */
    private static final int CODE_SHIFT = CODE_BITS - SYM_BITS - 1;

    /** {@code EC_CODE_TOP}. */
    private static final long CODE_TOP = 1L << (CODE_BITS - 1);

    /** {@code EC_CODE_BOT}: renormalisation runs until rng is above this. */
    private static final long CODE_BOT = CODE_TOP >> SYM_BITS;

    /** {@code EC_UINT_BITS}. */
    private static final int UINT_BITS = 8;

    /** {@code EC_WINDOW_SIZE}. */
    private static final int WINDOW_SIZE = 32;

    /** The most raw bits one call may add; see {@link RangeDecoder#MAX_RAW_BITS}. */
    public static final int MAX_RAW_BITS = RangeDecoder.MAX_RAW_BITS;

    private byte[] buf;
    private int base;
    private int storage;

    private int offs;
    private int endOffs;
    private int endWindow;
    private int nendBits;
    private int nbitsTotal;

    /** The low end of the current range, unsigned 32-bit. */
    private long val;

    /** The size of the current range, unsigned 32-bit. */
    private long rng;

    /** How many 255 bytes are waiting behind {@link #rem} for a carry. */
    private int ext;

    /** The one output byte held back, or -1 before the first is produced. */
    private int rem;

    /** Whether any raw bits were written, which fixes the transmitted length. */
    private boolean rawBitsUsed;

    private boolean finished;

    /** Writes a frame into the whole of {@code out}. */
    public RangeEncoder(byte[] out) {
        this(out, 0, out.length);
    }

    /** Writes a frame into {@code length} bytes of {@code out} at {@code offset}. */
    public RangeEncoder(byte[] out, int offset, int length) {
        init(out, offset, length);
    }

    /**
     * Points this encoder at another buffer, discarding all state.
     *
     * @throws RangeCoderException if the slice is not inside {@code out}
     */
    public void init(byte[] out, int offset, int length) {
        Objects.requireNonNull(out, "out");
        if (offset < 0 || length < 0 || offset > out.length - length) {
            throw new RangeCoderException("range encoder given " + length
                    + " bytes at offset " + offset + " of a " + out.length + "-byte array");
        }
        this.buf = out;
        this.base = offset;
        this.storage = length;
        this.offs = 0;
        this.endOffs = 0;
        this.endWindow = 0;
        this.nendBits = 0;
        this.rawBitsUsed = false;
        this.finished = false;
        // 33, where the decoder starts at 9 and renormalises its way here. Both
        // must agree from the first symbol or ec_tell() differs between the two
        // ends and CELT allocates differently at each.
        this.nbitsTotal = CODE_BITS + 1;
        this.val = 0;
        this.rng = CODE_TOP;
        this.ext = 0;
        this.rem = -1;
    }

    private void writeByte(int value) {
        if (offs + endOffs >= storage) {
            throw new RangeCoderException("range encoder ran out of its " + storage
                    + "-byte buffer with " + offs + " bytes of range coder data and "
                    + endOffs + " bytes of raw bits already in it");
        }
        buf[base + offs++] = (byte) value;
    }

    private void writeByteAtEnd(int value) {
        if (offs + endOffs >= storage) {
            throw new RangeCoderException("range encoder ran out of its " + storage
                    + "-byte buffer packing raw bits, with " + offs
                    + " bytes of range coder data and " + endOffs
                    + " bytes of raw bits already in it");
        }
        buf[base + storage - ++endOffs] = (byte) value;
    }

    /**
     * Resolves one nine-bit output, {@code ec_enc_carry_out}.
     *
     * <p>A 255 cannot be written yet because a later carry would turn it into a
     * 0 and push the carry one byte further back, so it is only counted. Any
     * other value settles everything behind it: the held byte takes the carry,
     * the counted run becomes all 0s or all 255s depending on it, and this byte
     * becomes the new held one.
     */
    private void carryOut(int c) {
        if (c != SYM_MAX) {
            int carry = c >>> SYM_BITS;
            if (rem >= 0) {
                writeByte(rem + carry);
            }
            if (ext > 0) {
                int sym = (SYM_MAX + carry) & SYM_MAX;
                do {
                    writeByte(sym);
                } while (--ext > 0);
            }
            rem = c & SYM_MAX;
        } else {
            ext++;
            // Every one of these becomes a byte the moment a value arrives that
            // cannot carry, so a run longer than the buffer is a stream that has
            // already busted and simply has not noticed. Upstream lets the
            // counter run and finds out at ec_enc_done; catching it here means
            // the exception names the symbol that overran rather than the flush.
            if (ext > storage) {
                throw new RangeCoderException("range encoder has " + ext
                        + " carry-propagating bytes pending and only a " + storage
                        + "-byte buffer to put them in");
            }
        }
    }

    /** {@code ec_enc_normalize}: pushes bits out until rng is back above 2**23. */
    private void normalize() {
        while (rng <= CODE_BOT) {
            carryOut((int) (val >>> CODE_SHIFT));
            val = (val << SYM_BITS) & (CODE_TOP - 1);
            rng <<= SYM_BITS;
            nbitsTotal += SYM_BITS;
        }
    }

    /**
     * Encodes the symbol whose three-tuple is {@code (fl, fh, ft)},
     * {@code ec_encode}.
     */
    public void encode(int fl, int fh, int ft) {
        if (fl < 0 || fl >= fh || fh > ft || ft > 65535) {
            throw new RangeCoderException("symbol range [" + fl + "," + fh + ") of " + ft
                    + " is not a valid range coder three-tuple");
        }
        long r = rng / ft;
        if (fl > 0) {
            // Masked to 32 bits because carryOut reads exactly nine bits out of
            // val and a value that had grown past 2**32 would hand it a tenth.
            // The invariant val+rng <= 2**32 means this never actually fires;
            // it is here so that if it ever did, the stream would break loudly
            // rather than emit a byte with a stray carry in it.
            val = (val + rng - r * (ft - fl)) & 0xFFFFFFFFL;
            rng = r * (fh - fl);
        } else {
            rng -= r * (ft - fh);
        }
        normalize();
    }

    /** {@code ec_encode_bin}: {@link #encode} where {@code ft} is {@code 1<<ftb}. */
    public void encodeBin(int fl, int fh, int ftb) {
        if (ftb < 1 || ftb > 15) {
            throw new RangeCoderException("a binary context may be 1 to 15 bits, not " + ftb);
        }
        int ft = 1 << ftb;
        if (fl < 0 || fl >= fh || fh > ft) {
            throw new RangeCoderException("symbol range [" + fl + "," + fh + ") of " + ft
                    + " is not a valid range coder three-tuple");
        }
        long r = rng >>> ftb;
        if (fl > 0) {
            val = (val + rng - r * (ft - fl)) & 0xFFFFFFFFL;
            rng = r * (fh - fl);
        } else {
            rng -= r * (ft - fh);
        }
        normalize();
    }

    /** Encodes one symbol of a cumulative frequency table; see {@link RangeDecoder#decodeSymbol}. */
    public void encodeSymbol(int[] cumulativeFrequencies, int total, int k) {
        Objects.requireNonNull(cumulativeFrequencies, "cumulativeFrequencies");
        int n = cumulativeFrequencies.length - 1;
        if (n < 1 || k < 0 || k >= n) {
            throw new RangeCoderException("symbol " + k + " is outside a context of " + n);
        }
        if (cumulativeFrequencies[0] != 0 || cumulativeFrequencies[n] != total) {
            throw new RangeCoderException("a cumulative frequency table must run from 0 to "
                    + total + ", not from " + cumulativeFrequencies[0]
                    + " to " + cumulativeFrequencies[n]);
        }
        encode(cumulativeFrequencies[k], cumulativeFrequencies[k + 1], total);
    }

    /** {@code ec_enc_bit_logp}: encodes a bit whose probability of being set is 2**-logp. */
    public void encodeBit(int bit, int logp) {
        if (logp < 1 || logp > 23) {
            throw new RangeCoderException("a bit probability exponent must be 1 to 23, not " + logp);
        }
        long r = rng;
        long l = val;
        long s = r >>> logp;
        r -= s;
        if (bit != 0) {
            val = (l + r) & 0xFFFFFFFFL;
        }
        rng = bit != 0 ? s : r;
        normalize();
    }

    /**
     * {@code ec_enc_icdf}: encodes symbol {@code s} of an inverse cumulative
     * table.
     *
     * <p>Checks that the symbol occupies some part of the range, which the
     * decoder does not have to: there the invariant {@code val < rng} makes a
     * collapsed range impossible whatever the table says, and here nothing
     * stops it. A range of zero can never be renormalised back above 2**23, so
     * the loop that tries would not encode a bad frame -- it would not return.
     */
    public void encodeIcdf(int s, short[] icdf, int ftb) {
        Objects.requireNonNull(icdf, "icdf");
        if (ftb < 1 || ftb > 15) {
            throw new RangeCoderException("an icdf context may be 1 to 15 bits, not " + ftb);
        }
        if (s < 0 || s >= icdf.length) {
            throw new RangeCoderException("symbol " + s + " is outside an icdf table of "
                    + icdf.length);
        }
        long r = rng >>> ftb;
        if (s > 0) {
            int hi = icdf[s - 1] & 0xFFFF;
            int lo = icdf[s] & 0xFFFF;
            if (hi <= lo) {
                throw new RangeCoderException("icdf symbol " + s + " has entries " + hi
                        + " and " + lo + ", so it occupies no part of the range");
            }
            val = (val + rng - r * hi) & 0xFFFFFFFFL;
            rng = r * (hi - lo);
        } else {
            int first = icdf[0] & 0xFFFF;
            if (first >= (1 << ftb)) {
                throw new RangeCoderException("icdf symbol 0 has entry " + first
                        + " in a " + ftb + "-bit table, so it occupies no part of the range");
            }
            rng -= r * first;
        }
        normalize();
    }

    /** {@code ec_enc_bits}: packs {@code bits} raw bits at the far end of the buffer. */
    public void encodeRawBits(int value, int bits) {
        if (bits < 0 || bits > MAX_RAW_BITS) {
            throw new RangeCoderException("a raw field may be 0 to " + MAX_RAW_BITS
                    + " bits, not " + bits);
        }
        if (bits < 32 && (value >>> bits) != 0) {
            throw new RangeCoderException("raw value " + Integer.toUnsignedString(value)
                    + " does not fit in " + bits + " bits");
        }
        int window = endWindow;
        int used = nendBits;
        if (used + bits > WINDOW_SIZE) {
            do {
                writeByteAtEnd(window & SYM_MAX);
                window >>>= SYM_BITS;
                used -= SYM_BITS;
            } while (used >= SYM_BITS);
        }
        window |= value << used;
        used += bits;
        endWindow = window;
        nendBits = used;
        nbitsTotal += bits;
        // Only a field that actually took a bit pins the transmitted length to
        // the whole buffer; a zero-bit request must not turn a four-byte frame
        // into whatever size the caller happened to allocate.
        rawBitsUsed |= bits > 0;
    }

    /** {@code ec_enc_uint}: encodes one of {@code count} equally likely values. */
    public void encodeUniform(int value, int count) {
        if (count < 1) {
            throw new RangeCoderException("a uniform integer needs at least one value, not " + count);
        }
        encodeUniformWide(value, count);
    }

    /**
     * {@link #encodeUniform} for totals that do not fit a signed int; see
     * {@link RangeDecoder#decodeUniformWide}.
     */
    public void encodeUniformWide(long value, long count) {
        if (count < 1 || count > 0xFFFFFFFFL) {
            throw new RangeCoderException("a uniform integer total must be 1 to 2**32-1, not " + count);
        }
        if (value < 0 || value >= count) {
            throw new RangeCoderException("uniform value " + value + " is outside [0," + count + ")");
        }
        long ftMinus1 = count - 1;
        int ftb = EntropyCode.ilog(ftMinus1);
        if (ftb > UINT_BITS) {
            ftb -= UINT_BITS;
            int ft = (int) (ftMinus1 >>> ftb) + 1;
            int fl = (int) (value >>> ftb);
            encode(fl, fl + 1, ft);
            encodeRawBits((int) (value & ((1L << ftb) - 1)), ftb);
        } else {
            int v = (int) value;
            encode(v, v + 1, (int) count);
        }
    }

    /**
     * Terminates the stream, {@code ec_enc_done}.
     *
     * <p>Emits the value inside the current range with the most trailing zero
     * bits, so that the bits after it can be anything at all and the range coded
     * data still decodes -- which is exactly what makes the overlap with the raw
     * bits safe. Then the raw-bit window is flushed and, if its last byte is
     * also the range coder's last byte, ORed into it.
     *
     * @return how many bytes of the buffer must be transmitted: the whole of it
     *         once any raw bits have been packed at the far end, otherwise just
     *         the range coder's own bytes, since everything after them is zero
     *         and a decoder invents zeros there anyway
     * @throws RangeCoderException if the symbols given do not fit the buffer
     */
    public int finish() {
        if (finished) {
            throw new RangeCoderException("this range encoder has already been finished");
        }
        int l = CODE_BITS - EntropyCode.ilog(rng);
        long msk = (CODE_TOP - 1) >>> l;
        long end = (val + msk) & ~msk & 0xFFFFFFFFL;
        if ((end | msk) >= val + rng) {
            l++;
            msk >>>= 1;
            end = (val + msk) & ~msk & 0xFFFFFFFFL;
        }
        while (l > 0) {
            carryOut((int) (end >>> CODE_SHIFT));
            end = (end << SYM_BITS) & (CODE_TOP - 1);
            l -= SYM_BITS;
        }
        if (rem >= 0 || ext > 0) {
            carryOut(0);
        }
        int window = endWindow;
        int used = nendBits;
        while (used >= SYM_BITS) {
            writeByteAtEnd(window & SYM_MAX);
            window >>>= SYM_BITS;
            used -= SYM_BITS;
        }
        // Everything between the two cursors is zero, so that a decoder reading
        // range coder bytes it never wrote gets the same zeros it would have
        // invented past the end of the frame.
        Arrays.fill(buf, base + offs, base + storage - endOffs, (byte) 0);
        if (used > 0) {
            if (endOffs >= storage) {
                throw new RangeCoderException("range encoder has " + used
                        + " raw bits left and no byte of its " + storage
                        + "-byte buffer to put them in");
            }
            // l went negative in the loop above by however many bits of the
            // last range coder byte were not needed. Those are the only bits
            // the raw data may take back.
            l = -l;
            if (offs + endOffs >= storage && l < used) {
                throw new RangeCoderException("range encoder cannot fit " + used
                        + " raw bits into the " + l + " spare bits of the last of its "
                        + storage + " bytes without corrupting the range coded data");
            }
            buf[base + storage - endOffs - 1] |= (byte) window;
        }
        finished = true;
        return rawBitsUsed ? storage : offs;
    }

    /**
     * Pretends the rest of the frame has already been spent.
     *
     * <p>{@code enc->nbits_total += tell-ec_tell(enc)} in
     * {@code celt_encode_with_ec} ({@code celt/celt.c}), whose comment is
     * "Pretend we've filled all the remaining bits with zeros". The mirror of
     * {@link RangeDecoder#chargeRemainingBits()}, and it has to exist for the
     * same reason: a CELT frame whose silence flag is set writes nothing after
     * that flag, and the way both ends agree on that is that every subsequent
     * "is there room for this symbol" test answers no. An encoder that skipped
     * this would go on writing the transient flag, the coarse energy and the
     * band shapes into a frame the decoder has already stopped reading, and the
     * two range coders would part company for the rest of the packet.
     *
     * <p>Touches only the bit counter, so it cannot change what any symbol
     * already written encodes to.
     */
    public void chargeRemainingBits() {
        if (finished) {
            throw new RangeCoderException("this range encoder has already been finished");
        }
        nbitsTotal += storage * 8 - tell();
    }

    /**
     * Everything an encoder needs to be put back where it was.
     *
     * <p>Reusable on purpose. {@code quant_coarse_energy} rewinds and replays
     * once per frame, and a snapshot that allocated would put a garbage
     * collection on the encode path of every frame.
     */
    public static final class Bookmark {
        private byte[] bytes = new byte[0];
        private int base;
        private int storage;
        private int offs;
        private int endOffs;
        private int endWindow;
        private int nendBits;
        private int nbitsTotal;
        private long val;
        private long rng;
        private int ext;
        private int rem;
        private boolean rawBitsUsed;
        private boolean taken;
    }

    /**
     * Records where this encoder is, so {@link #restore} can put it back.
     *
     * <p>{@code quant_coarse_energy} needs this. It codes the band energies
     * twice, once predicting from the previous frame and once not, and keeps
     * whichever came out smaller; upstream does it by copying the {@code ec_enc}
     * struct and the bytes it has written, which is what this is. The buffer
     * contents are copied as well as the arithmetic state, because the second
     * pass overwrites the bytes the first one wrote and the winner may be the
     * first.
     *
     * <p>The alternative -- encoding both passes into separate buffers and
     * splicing -- does not work: a range coder's output depends on the state it
     * started from, so the two passes cannot be produced independently and
     * chosen between afterwards.
     */
    public void save(Bookmark into) {
        Objects.requireNonNull(into, "into");
        if (finished) {
            throw new RangeCoderException("this range encoder has already been finished");
        }
        if (into.bytes.length < storage) {
            into.bytes = new byte[storage];
        }
        System.arraycopy(buf, base, into.bytes, 0, storage);
        into.base = base;
        into.storage = storage;
        into.offs = offs;
        into.endOffs = endOffs;
        into.endWindow = endWindow;
        into.nendBits = nendBits;
        into.nbitsTotal = nbitsTotal;
        into.val = val;
        into.rng = rng;
        into.ext = ext;
        into.rem = rem;
        into.rawBitsUsed = rawBitsUsed;
        into.taken = true;
    }

    /**
     * Puts this encoder back where {@link #save} found it, output bytes and all.
     *
     * @throws RangeCoderException if the bookmark was never taken, or was taken
     *                             from a different buffer
     */
    public void restore(Bookmark from) {
        Objects.requireNonNull(from, "from");
        if (!from.taken) {
            throw new RangeCoderException("this bookmark has never been saved into");
        }
        if (from.base != base || from.storage != storage) {
            throw new RangeCoderException("this bookmark was taken from a " + from.storage
                    + "-byte frame at offset " + from.base + " and this encoder is writing a "
                    + storage + "-byte frame at offset " + base);
        }
        System.arraycopy(from.bytes, 0, buf, base, storage);
        this.offs = from.offs;
        this.endOffs = from.endOffs;
        this.endWindow = from.endWindow;
        this.nendBits = from.nendBits;
        this.nbitsTotal = from.nbitsTotal;
        this.val = from.val;
        this.rng = from.rng;
        this.ext = from.ext;
        this.rem = from.rem;
        this.rawBitsUsed = from.rawBitsUsed;
        this.finished = false;
    }

    /** Whole bits used so far, {@code ec_tell}, matching the decoder exactly. */
    public int tell() {
        return EntropyCode.tell(nbitsTotal, rng);
    }

    /** Bits used so far in eighths, {@code ec_tell_frac}. */
    public int tellFrac() {
        return EntropyCode.tellFrac(nbitsTotal, rng);
    }

    /**
     * The size of the range at this point, {@code rng}.
     *
     * <p>Equal to {@link RangeDecoder#finalRange()} after the decoder has read
     * back the same symbols. This is the check the conformance vectors are
     * built around.
     */
    public long finalRange() {
        return rng;
    }

    /** How many bytes of range coder data have been written, {@code ec_range_bytes}. */
    public int rangeBytes() {
        return offs;
    }

    @Override
    public String toString() {
        return "RangeEncoder[" + storage + " bytes, tell=" + tell()
                + " of " + (storage * 8) + ", rng=" + rng + "]";
    }
}
