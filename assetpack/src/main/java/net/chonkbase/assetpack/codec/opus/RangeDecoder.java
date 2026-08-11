package net.chonkbase.assetpack.codec.opus;

import java.util.Objects;

/**
 * Reads the entropy-coded symbols out of one Opus frame.
 *
 * <p>A port of {@code ec_dec} and its operations in {@code celt/entdec.c},
 * specified normatively by RFC 6716 section 4.1. The bit-usage half,
 * {@code ec_tell} and {@code ec_tell_frac}, is in {@link EntropyCode} because
 * the encoder must compute it identically.
 *
 * <p>Two readers share one buffer and travel towards each other. The range
 * coder proper runs forward from the first byte. The CELT layer's raw bits run
 * backward from the last byte, least significant bit first. They are allowed to
 * overlap and in a well-packed frame they do: RFC 6716 section 4.1.2.1 says the
 * range decoder will normally have buffered several bytes of raw-bit data by
 * the time the frame is finished, and requires those same bytes to be handed
 * back unchanged when the raw bits are asked for. That is why neither cursor
 * takes the other into account, and why running off either end yields zero
 * instead of failing -- the RFC demands it of the forward reader and permits it
 * of the backward one. The count of raw bits actually taken is kept anyway, so
 * a caller that wants the check section 4.1.4 says it "may wish" to make can
 * have it from {@link #rawBitsOverread()} without paying for it per symbol.
 *
 * <p>There is no floating point here and there cannot be. Every operation in
 * the range coder is integer, so a correct decoder is bit-exact, and the value
 * of {@link #finalRange()} after a frame equals the encoder's own -- which is
 * the gate the conformance vectors give us. The state is carried in
 * {@code long} rather than {@code int} because {@code rng} and {@code val} are
 * unsigned 32-bit quantities and Java has no unsigned int: {@code rng} is
 * exactly 2**31 the instant initialisation finishes, which as a signed
 * {@code int} is negative, and the very first {@code val/rng} would then pick
 * the wrong symbol out of the very first context.
 *
 * <p>{@link #init} exists so a decoder can be reused across the frames of a
 * packet and across packets. Nothing on the decode path allocates; the only
 * allocation in this class is the message of an exception that is not thrown
 * during normal decoding.
 */
public final class RangeDecoder {

    /** Bits in an output symbol, {@code EC_SYM_BITS}. Opus codes in bytes. */
    private static final int SYM_BITS = 8;

    /** {@code EC_SYM_MAX}, the largest value one coded symbol can hold. */
    private static final int SYM_MAX = 255;

    /** {@code EC_CODE_BITS}, the width of {@code val} and {@code rng}. */
    private static final int CODE_BITS = 32;

    /** {@code EC_CODE_SHIFT}: where the nine carry-bearing bits of val start. */
    private static final int CODE_SHIFT = CODE_BITS - SYM_BITS - 1;

    /** {@code EC_CODE_TOP}, one past the largest normalised range. */
    private static final long CODE_TOP = 1L << (CODE_BITS - 1);

    /** {@code EC_CODE_BOT}: renormalisation runs until rng is above this. */
    private static final long CODE_BOT = CODE_TOP >> SYM_BITS;

    /** {@code EC_CODE_EXTRA}, the seven bits of the first byte that seed val. */
    private static final int CODE_EXTRA = (CODE_BITS - 2) % SYM_BITS + 1;

    /** {@code EC_UINT_BITS}: how much of a uniform integer is entropy coded. */
    private static final int UINT_BITS = 8;

    /** {@code EC_WINDOW_SIZE}, the width of the raw-bit staging window. */
    private static final int WINDOW_SIZE = 32;

    /**
     * The most raw bits one call may take.
     *
     * <p>The staging window is 32 bits wide and is refilled a byte at a time
     * until more than 24 bits are in it, so 25 is what it can always satisfy.
     * That is enough for every use in the format: the widest is
     * {@link #decodeUniform}'s remainder, at most 24 bits.
     */
    public static final int MAX_RAW_BITS = 25;

    private byte[] buf;
    private int base;
    private int storage;

    /** How far the forward reader has got, {@code offs}. */
    private int offs;

    /** How many bytes the backward reader has taken, {@code end_offs}. */
    private int endOffs;

    /** Raw bits already pulled from the back of the frame but not yet returned. */
    private int endWindow;

    /** How many bits of {@link #endWindow} are valid, {@code nend_bits}. */
    private int nendBits;

    /** Whole bits consumed including those still buffered, {@code nbits_total}. */
    private int nbitsTotal;

    /** The size of the current range, unsigned 32-bit. */
    private long rng;

    /** The distance from the top of the range to the coded value, less one. */
    private long val;

    /** {@code rng/ft} from the last {@link #decode}, saved for {@link #update}. */
    private long ext;

    /**
     * The total {@link #ext} was divided by, so {@link #update} can refuse any
     * other one.
     *
     * <p>Zero when no symbol is half decoded. Holding it is what makes the
     * unbounded loop in {@link #normalize} unreachable rather than merely
     * unlikely: given the same {@code ft} the division was done with,
     * {@code ext * (ft - fh)} cannot exceed {@code rng} and the range cannot
     * collapse. Given a different one it can, and does.
     */
    private int extTotal;

    /** The byte the forward reader has read but only partly used. */
    private int rem;

    /** Raw bits handed out, counted so an overread can be reported. */
    private long rawBitsRead;

    /** Set when {@link #decodeUniform} had to saturate a corrupt value. */
    private boolean uniformOutOfRange;

    /** Reads a frame that occupies the whole of {@code data}. */
    public RangeDecoder(byte[] data) {
        this(data, 0, data.length);
    }

    /** Reads the frame held in {@code length} bytes of {@code data} at {@code offset}. */
    public RangeDecoder(byte[] data, int offset, int length) {
        init(data, offset, length);
    }

    /**
     * Points this decoder at another frame, discarding all state.
     *
     * <p>Here so the CELT decoder can keep one of these on an audio thread and
     * decode every frame of every packet through it without allocating.
     *
     * @throws RangeCoderException if the slice is not inside {@code data}
     */
    public void init(byte[] data, int offset, int length) {
        Objects.requireNonNull(data, "data");
        if (offset < 0 || length < 0 || offset > data.length - length) {
            throw new RangeCoderException("range decoder given " + length
                    + " bytes at offset " + offset + " of a " + data.length + "-byte array");
        }
        this.buf = data;
        this.base = offset;
        this.storage = length;
        this.offs = 0;
        this.endOffs = 0;
        this.endWindow = 0;
        this.nendBits = 0;
        this.rawBitsRead = 0;
        this.uniformOutOfRange = false;
        // The frame before this one very often threw, and a frame that threw
        // between decode() and update() left its divisor behind. Carried into
        // the next frame it satisfies update()'s check that there is a symbol
        // to finish, and the multiply then runs with a divisor taken from a
        // range this frame never had. Nothing faults: the frame decodes, with
        // the wrong rng, and so does every frame after it. That is a packet of
        // audio that comes out plausible and wrong, which is the failure this
        // codebase is hardest to notice.
        this.ext = 0;
        this.extTotal = 0;
        // Nine, not zero: RFC 6716 section 4.1.6 counts the bit reserved for
        // terminating the encoder's stream plus one more of slack, and then
        // the renormalisation below carries it to 33. Start it anywhere else
        // and every ec_tell() in the frame is off by the same amount, which
        // does not corrupt a symbol -- it hands CELT a different allocation
        // from the one the encoder used.
        this.nbitsTotal = CODE_BITS + 1 - ((CODE_BITS - CODE_EXTRA) / SYM_BITS) * SYM_BITS;
        this.rng = 1L << CODE_EXTRA;
        this.rem = readByte();
        this.val = rng - 1 - (rem >>> (SYM_BITS - CODE_EXTRA));
        normalize();
    }

    /** How many bytes the frame occupies. */
    public int frameBytes() {
        return storage;
    }

    private int readByte() {
        return offs < storage ? buf[base + offs++] & 0xFF : 0;
    }

    private int readByteFromEnd() {
        // endOffs is only advanced on a real read, so the index is always
        // within [base, base+storage) and never needs a second bounds check.
        return endOffs < storage ? buf[base + storage - ++endOffs] & 0xFF : 0;
    }

    /**
     * Brings {@code rng} back above 2**23, {@code ec_dec_normalize}.
     *
     * <p>Each pass takes eight more bits of the stream: the bit left over from
     * the previous byte becomes the high bit of the symbol and the top seven
     * bits of the byte just read become the rest. Running past the end of the
     * frame reads zero, which RFC 6716 section 4.1.2.1 requires rather than
     * merely allows -- a decoder that stopped there would misdecode the last
     * symbols of every frame short enough for the coder to read ahead of.
     */
    private void normalize() {
        while (rng <= CODE_BOT) {
            nbitsTotal += SYM_BITS;
            rng <<= SYM_BITS;
            int sym = rem;
            rem = readByte();
            sym = ((sym << SYM_BITS) | rem) >>> (SYM_BITS - CODE_EXTRA);
            val = ((val << SYM_BITS) + (SYM_MAX & ~sym)) & (CODE_TOP - 1);
        }
    }

    /**
     * The first half of decoding a symbol, {@code ec_decode}.
     *
     * <p>Returns a value in {@code [0, ft)} that lies inside the range of
     * exactly one symbol of the context. The caller finds which, and must then
     * call {@link #update} with that symbol's three-tuple before decoding
     * anything else: the divisor computed here is kept in the coder's state
     * between the two calls, exactly as upstream keeps it in {@code ext}.
     */
    public int decode(int ft) {
        if (ft < 1 || ft > 65535) {
            throw new RangeCoderException("a range coder context total must be 1 to 65535, not " + ft);
        }
        ext = rng / ft;
        extTotal = ft;
        long s = val / ext;
        return (int) (ft - Math.min(s + 1, ft));
    }

    /**
     * {@code ec_decode_bin}: {@link #decode} where {@code ft} is {@code 1<<ftb}.
     *
     * <p>Saves the division by the total. CELT's Laplace coder calls this and
     * then {@link #update} directly rather than going through a table.
     */
    public int decodeBin(int ftb) {
        if (ftb < 1 || ftb > 15) {
            throw new RangeCoderException("a binary context may be 1 to 15 bits, not " + ftb);
        }
        ext = rng >>> ftb;
        extTotal = 1 << ftb;
        long s = val / ext;
        long ft = 1L << ftb;
        return (int) (ft - Math.min(s + 1, ft));
    }

    /**
     * The second half of decoding a symbol, {@code ec_dec_update}.
     *
     * <p>Symbol zero is the special case rather than the last symbol, which is
     * unusual among arithmetic coders and deliberate: it piles all the
     * truncation error of the integer division onto symbol zero, and Opus's
     * contexts are written so that zero is the most probable symbol. Swapping
     * the branch would still decode, and would cost a fraction of a bit on
     * every symbol in the stream.
     *
     * <p>Must follow a {@link #decode} or {@link #decodeBin} of the same
     * {@code ft} immediately, because it finishes the division that one
     * started, and both halves of that check are load-bearing. With no
     * preceding call the divisor is zero and the range becomes zero; with a
     * divisor computed from some other total, {@code ext * (ft - fh)} can
     * exceed {@code rng} and the range goes past zero. Either way a range of
     * zero can never be renormalised back above 2**23, so the loop that tried
     * would not return -- an ordering mistake would hang an audio thread rather
     * than decode a bad frame. Given the matching total it cannot happen:
     * {@code ext} is {@code rng/ft}, so {@code ext * (ft - fh)} is at most
     * {@code rng} and the subtraction leaves at least one. The two compares
     * cost nothing on the icdf path that SILK spends its time in.
     */
    public void update(int fl, int fh, int ft) {
        if (fl < 0 || fl >= fh || fh > ft || ft > 65535) {
            throw new RangeCoderException("symbol range [" + fl + "," + fh + ") of " + ft
                    + " is not a valid range coder three-tuple");
        }
        if (ext == 0) {
            throw new RangeCoderException(
                    "update() must follow a decode() or decodeBin() of the same symbol");
        }
        if (ft != extTotal) {
            throw new RangeCoderException("update() was given a context total of " + ft
                    + " but the divisor waiting for it was computed from " + extTotal);
        }
        long s = ext * (ft - fh);
        val -= s;
        rng = fl > 0 ? ext * (fh - fl) : rng - s;
        ext = 0;
        extTotal = 0;
        normalize();
    }

    /**
     * Decodes one symbol from a cumulative frequency table.
     *
     * <p>{@code cumulativeFrequencies} holds {@code fl[0]..fl[n]} with
     * {@code fl[0] == 0} and {@code fl[n] == total}, so a table of {@code n}
     * symbols has {@code n+1} entries. The RFC describes contexts as frequency
     * counts and this is the form nearest to that description; the CELT and
     * SILK tables are stored inverted instead and go through
     * {@link #decodeIcdf}.
     */
    public int decodeSymbol(int[] cumulativeFrequencies, int total) {
        Objects.requireNonNull(cumulativeFrequencies, "cumulativeFrequencies");
        int n = cumulativeFrequencies.length - 1;
        if (n < 1) {
            throw new RangeCoderException("a cumulative frequency table needs at least two entries");
        }
        if (cumulativeFrequencies[0] != 0 || cumulativeFrequencies[n] != total) {
            throw new RangeCoderException("a cumulative frequency table must run from 0 to "
                    + total + ", not from " + cumulativeFrequencies[0]
                    + " to " + cumulativeFrequencies[n]);
        }
        int fs = decode(total);
        int k = 0;
        while (k < n && cumulativeFrequencies[k + 1] <= fs) {
            k++;
        }
        if (k >= n) {
            throw new RangeCoderException("no symbol of this context covers " + fs
                    + "; the table is not monotonic");
        }
        update(cumulativeFrequencies[k], cumulativeFrequencies[k + 1], total);
        return k;
    }

    /**
     * Decodes one bit whose probability of being set is 2**-logp,
     * {@code ec_dec_bit_logp}.
     *
     * <p>Needs neither a multiply nor a divide, which is why CELT uses it for
     * the flags it codes dozens of times per frame.
     */
    public int decodeBit(int logp) {
        // Above 23 the shift could reach zero, because renormalisation only
        // guarantees rng > 2**23, and a zero-width sub-range would decode the
        // same bit forever.
        if (logp < 1 || logp > 23) {
            throw new RangeCoderException("a bit probability exponent must be 1 to 23, not " + logp);
        }
        long r = rng;
        long d = val;
        long s = r >>> logp;
        int ret = d < s ? 1 : 0;
        if (ret == 0) {
            val = d - s;
        }
        rng = ret != 0 ? s : r - s;
        normalize();
        return ret;
    }

    /**
     * Decodes one symbol from an inverse cumulative table, {@code ec_dec_icdf}.
     *
     * <p>{@code icdf[k]} holds {@code (1<<ftb) - fh[k]} and the table ends with
     * a zero. Storing the complement is what lets an eight-bit table carry an
     * eight-bit total without a special case, and folding the search into the
     * update turns the division into a short run of multiplies. This is the
     * primary interface to the range coder in the SILK layer.
     *
     * <p>Values are held in {@code short} because they run to 255 and a Java
     * {@code byte} is signed; nothing here is ever negative.
     *
     * <p>Needs no check that the symbol it lands on has any width, even though
     * a range of zero could never be renormalised back above 2**23 and the
     * loop that tried would not return. It cannot happen: the decoder holds
     * {@code val < rng} from initialisation onwards, and the search here only
     * stops at an entry strictly below the one before it, so the width it
     * subtracts is always at least one. The encoder has no such protection and
     * does check.
     */
    public int decodeIcdf(short[] icdf, int ftb) {
        Objects.requireNonNull(icdf, "icdf");
        if (ftb < 1 || ftb > 15) {
            throw new RangeCoderException("an icdf context may be 1 to 15 bits, not " + ftb);
        }
        long s = rng;
        long d = val;
        long r = s >>> ftb;
        long t;
        int ret = -1;
        do {
            t = s;
            ret++;
            // Upstream walks off the end of a table that does not end in zero.
            // Here that would be a read past the array; a table built wrong is
            // a bug in this codebase, so say so rather than decode from
            // whatever follows it in memory.
            if (ret >= icdf.length) {
                throw new RangeCoderException("icdf table of " + icdf.length
                        + " entries is not terminated by a zero");
            }
            s = r * (icdf[ret] & 0xFFFF);
        } while (d < s);
        val = d - s;
        rng = t - s;
        normalize();
        return ret;
    }

    /**
     * Takes {@code bits} raw bits from the back of the frame,
     * {@code ec_dec_bits}.
     *
     * <p>These bypass the range coder entirely. RFC 6716 section 4.1 gives two
     * reasons: they are cheaper, and a bit error in them corrupts only the
     * value they carry instead of desynchronising every symbol after it. Only
     * CELT uses them.
     *
     * <p>Reading past the front of the frame yields zeros rather than throwing,
     * which is what upstream does and what keeps a corrupt packet concealable;
     * {@link #rawBitsOverread()} reports whether it happened.
     */
    public int decodeRawBits(int bits) {
        if (bits < 0 || bits > MAX_RAW_BITS) {
            throw new RangeCoderException("a raw field may be 0 to " + MAX_RAW_BITS
                    + " bits, not " + bits);
        }
        int window = endWindow;
        int available = nendBits;
        if (available < bits) {
            do {
                window |= readByteFromEnd() << available;
                available += SYM_BITS;
            } while (available <= WINDOW_SIZE - SYM_BITS);
        }
        int ret = window & ((1 << bits) - 1);
        window >>>= bits;
        available -= bits;
        endWindow = window;
        nendBits = available;
        nbitsTotal += bits;
        rawBitsRead += bits;
        return ret;
    }

    /**
     * Decodes one of {@code count} equally likely values, {@code ec_dec_uint}.
     *
     * @throws RangeCoderException if {@code count} is not positive
     */
    public int decodeUniform(int count) {
        if (count < 1) {
            throw new RangeCoderException("a uniform integer needs at least one value, not " + count);
        }
        return (int) decodeUniformWide(count);
    }

    /**
     * {@link #decodeUniform} for totals that do not fit a signed int.
     *
     * <p>CELT's pulse decoder needs this: the number of ways to spread K pulses
     * over N samples runs to nearly 2**32, and a signed int would wrap to a
     * negative count somewhere in the middle of the largest bands and decode
     * the wrong pulse vector.
     *
     * <p>Only the top eight bits go through the range coder; the rest are raw
     * bits. That bound is a deliberate trade in the format, and it is why a
     * corrupt frame can produce a value outside {@code [0, count)}. RFC 6716
     * section 4.1.5 says to conceal rather than fail, so the value saturates
     * and {@link #uniformOutOfRange()} records it.
     */
    public long decodeUniformWide(long count) {
        if (count < 1 || count > 0xFFFFFFFFL) {
            throw new RangeCoderException("a uniform integer total must be 1 to 2**32-1, not " + count);
        }
        long ftMinus1 = count - 1;
        int ftb = EntropyCode.ilog(ftMinus1);
        if (ftb > UINT_BITS) {
            ftb -= UINT_BITS;
            int ft = (int) (ftMinus1 >>> ftb) + 1;
            int s = decode(ft);
            update(s, s + 1, ft);
            long t = ((long) s << ftb) | decodeRawBits(ftb);
            if (t <= ftMinus1) {
                return t;
            }
            uniformOutOfRange = true;
            return ftMinus1;
        }
        int ft = (int) count;
        int s = decode(ft);
        update(s, s + 1, ft);
        return s;
    }

    /** Whole bits consumed so far, {@code ec_tell}. See {@link EntropyCode#tell}. */
    public int tell() {
        return EntropyCode.tell(nbitsTotal, rng);
    }

    /**
     * Charges the frame for every bit it still holds, so that {@link #tell} and
     * {@link #tellFrac} report it as full.
     *
     * <p>{@code dec->nbits_total += tell-ec_tell(dec)} in
     * {@code celt_decode_with_ec} ({@code celt/celt.c}), whose comment is
     * "Pretend we've read all the remaining bits". A CELT frame whose silence
     * flag is set carries nothing after that flag, and the reference makes the
     * rest of the frame unaffordable rather than testing a flag at each of the
     * dozen places that ask whether there is room for the next symbol.
     *
     * <p>This is not cosmetic. Every one of those places -- the post-filter
     * parameters, the transient flag, the coarse energy models, the
     * time-frequency decisions, the band boosts -- reads a symbol when it
     * believes there is room. On a silent frame the encoder wrote none of them,
     * so a decoder that still believed it had a full frame to spend would read
     * symbols that were never written, and its range coder would part company
     * with the encoder's for the rest of the packet.
     *
     * <p>Touches only the bit counter. The range, the value and both read
     * cursors are left exactly as they are, so this cannot change what any
     * symbol decodes to; it changes only what the budget arithmetic believes is
     * affordable.
     */
    public void chargeRemainingBits() {
        nbitsTotal += storage * 8 - tell();
    }

    /** Bits consumed so far in eighths, {@code ec_tell_frac}. */
    public int tellFrac() {
        return EntropyCode.tellFrac(nbitsTotal, rng);
    }

    /**
     * The size of the range at this point, {@code rng}.
     *
     * <p>The conformance gate. An encoder and a decoder that have processed the
     * same symbols hold the same value here, and one that has misread a single
     * symbol anywhere in the frame does not, so comparing this against the
     * figure stored beside each conformance packet is a bit-exact test of every
     * probability model in the codec at once.
     */
    public long finalRange() {
        return rng;
    }

    /** How many raw bits have been handed out. */
    public long rawBitsRead() {
        return rawBitsRead;
    }

    /** How many raw bits the frame still holds; zero once it has been overread. */
    public long rawBitsRemaining() {
        return Math.max(0, (long) storage * 8 - rawBitsRead);
    }

    /**
     * Whether more raw bits have been taken than the frame contains.
     *
     * <p>The check RFC 6716 section 4.1.4 says a decoder "may wish" to make.
     * The bits handed out past that point were zeros this decoder invented, so
     * whatever CELT built from them is not what the encoder wrote.
     *
     * <p>Counted in bits taken rather than in bytes touched, and that
     * distinction is the whole of the bug it avoids: the reader refills its
     * window up to four bytes at a time, so the byte cursor routinely reaches
     * the front of the frame while the bits it fetched are still unread and
     * entirely legitimate.
     */
    public boolean rawBitsOverread() {
        return rawBitsRead > (long) storage * 8;
    }

    /**
     * Fails if the frame has been asked for more raw bits than it holds.
     *
     * @throws RangeCoderException naming the shortfall
     */
    public void checkRawBitsInBounds() {
        if (rawBitsOverread()) {
            throw new RangeCoderException("raw bits ran off the front of the frame: "
                    + rawBitsRead + " bits taken from " + storage + " bytes, "
                    + (rawBitsRead - (long) storage * 8) + " of them invented");
        }
    }

    /** Whether a {@link #decodeUniform} had to saturate a value the frame could not hold. */
    public boolean uniformOutOfRange() {
        return uniformOutOfRange;
    }

    @Override
    public String toString() {
        return "RangeDecoder[" + storage + " bytes, tell=" + tell()
                + " of " + (storage * 8) + ", rng=" + rng + "]";
    }
}
