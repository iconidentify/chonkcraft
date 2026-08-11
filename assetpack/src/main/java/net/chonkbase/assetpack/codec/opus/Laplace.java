package net.chonkbase.assetpack.codec.opus;

import java.util.Objects;

/**
 * The Laplace-like symbol coder CELT writes its coarse band energies with.
 *
 * <p>A port of {@code ec_laplace_decode} and {@code ec_laplace_get_freq1} in
 * {@code celt/laplace.c}. RFC 6716 section 4.3.2.1 names it: "The decoding of
 * the Laplace-distributed values is implemented in ec_laplace_decode()
 * (laplace.c)." The RFC prose says nothing else about it, so every number and
 * every branch below comes from Appendix A, which section 1 of the same RFC
 * makes the normative part of the specification.
 *
 * <p>The distribution being coded is the coarse energy prediction error, in
 * whole 6 dB steps. It is sharply peaked at zero and falls off geometrically,
 * so instead of a table the context is built on the fly from two parameters:
 * {@code fs}, the probability of zero, and {@code decay}, the ratio between
 * consecutive magnitudes. Both are read out of {@code e_prob_model} per frame
 * size, per band, and per prediction type; see {@link CeltEnergy}.
 *
 * <p>The shape of the context is
 *
 * <pre>
 *   symbol:   ... -3   -2   -1    0   +1   +2   +3 ...
 *   width:        f3   f2   f1   fs   f1   f2   f3
 * </pre>
 *
 * <p>with {@code f1 = freq1(fs, decay)} and each {@code f(n+1)} a {@code decay}
 * fraction of {@code f(n)}, every one of them widened by {@link #MIN_PROB} so
 * that no symbol can ever have zero width. Once the geometric part has decayed
 * to nothing, every remaining magnitude gets exactly {@link #MIN_PROB} out of
 * 32768, which is what guarantees that an arbitrarily large energy step is
 * still representable. That guarantee is the reason for the constant
 * {@code LAPLACE_NMIN}: 16 magnitudes in each direction are reserved out of the
 * total before {@code freq1} is computed, so the tail always has somewhere to
 * live. Drop that reservation and the widest bands of a loud transient code an
 * energy the encoder never sent, which a listener hears as one band jumping to
 * a wrong level for one frame and then dragging the next few frames with it
 * through the inter-frame prediction.
 *
 * <p>Both directions are here. {@link #encode} is what {@code CeltEncoder}
 * writes coarse energy with, and it is not simply the decoder run backwards:
 * the magnitudes past the decaying part all share the floor probability, so a
 * value large enough to fall off the end of the context cannot be coded at all
 * and the encoder clamps it to the largest one that fits. That is why
 * {@link #encode} returns a value rather than nothing -- the caller has to
 * reconstruct its state from what was actually sent, and an encoder that
 * updated from the value it wanted rather than the value it wrote would drift
 * away from the decoder one band at a time.
 */
public final class Laplace {

    /**
     * {@code LAPLACE_LOG_MINP}: log2 of the floor probability.
     *
     * <p>Zero in RFC 6716's Appendix A, so the shifts it appears in are all
     * no-ops. They are written out anyway, because the tail arithmetic below is
     * only readable as "in units of the floor probability" and a reader who
     * meets a bare {@code >>1} has to rediscover why.
     */
    static final int LOG_MIN_PROB = 0;

    /** {@code LAPLACE_MINP}: the smallest width any symbol may have, out of 32768. */
    static final int MIN_PROB = 1 << LOG_MIN_PROB;

    /**
     * {@code LAPLACE_NMIN}: magnitudes guaranteed representable in each direction.
     *
     * <p>Reserved out of the total before the decaying part is sized, so that
     * the geometric part can never eat the whole range and leave the tail
     * without room.
     */
    static final int MIN_REPRESENTABLE = 16;

    /** The context total this coder always uses, {@code 1<<15}. */
    static final int TOTAL = 1 << 15;

    /** {@code ec_decode_bin} takes the total as a bit count, not a value. */
    static final int TOTAL_BITS = 15;

    /**
     * The largest probability of zero this coder can be given.
     *
     * <p>{@code ec_laplace_get_freq1} computes {@code 32768 - 2*NMIN - fs0}
     * as an {@code unsigned}, so upstream a larger {@code fs0} wraps to about
     * four billion and sizes the first symbol from garbage.
     * {@code e_prob_model} tops out at {@code 192<<7 == 24576} and never comes
     * near it, but a caller that computed the parameter rather than reading it
     * from the table gets told rather than silently decoding a different stream
     * from the one the encoder wrote.
     */
    static final int MAX_ZERO_FREQ = TOTAL - MIN_PROB * (2 * MIN_REPRESENTABLE);

    /**
     * The largest decay rate this coder can be given.
     *
     * <p>{@code freq1} multiplies by {@code 16384-decay}; beyond 16384 that
     * goes negative and, being multiplied into an unsigned in C, produces a
     * first symbol wider than the whole context. {@code e_prob_model} tops out
     * at {@code 179<<6 == 11456}.
     */
    static final int MAX_DECAY = 16384;

    private Laplace() {
    }

    /**
     * Width of the symbol for magnitude one, {@code ec_laplace_get_freq1}.
     *
     * <p>{@code decay} is a Q14 fraction here even though every other
     * probability on this class is out of 32768; that asymmetry is in the
     * reference and is why {@link CeltEnergy} shifts the table's decay byte
     * left by 6 and its zero-probability byte left by 7.
     */
    static int freq1(int zeroFreq, int decay) {
        int ft = TOTAL - MIN_PROB * (2 * MIN_REPRESENTABLE) - zeroFreq;
        return (ft * (16384 - decay)) >> 15;
    }

    /**
     * Encodes one Laplace-distributed integer, {@code ec_laplace_encode}.
     *
     * <p>The return value is the point of the signature. Upstream takes
     * {@code int *value} and writes back through it, because a magnitude beyond
     * what the floor-probability tail can reach is clamped to the largest one
     * the context can express. A caller that ignored that would predict the next
     * band from an energy the decoder never saw, and because coarse energy is
     * coded as a difference from the previous band and the previous frame, one
     * such band puts the whole rest of the frame -- and, through the time
     * prediction, several frames after it -- at the wrong level.
     *
     * @param enc   the frame's range encoder
     * @param value the integer to code
     * @param fs    probability of zero out of 32768, {@code e_prob_model[..][2*i]<<7}
     * @param decay Q14 ratio between consecutive magnitudes,
     *              {@code e_prob_model[..][2*i+1]<<6}
     * @return the value actually coded, which is {@code value} unless it had to
     *         be clamped to the tail the context can reach
     * @throws RangeCoderException if the parameters are outside the range the
     *                             format can express
     */
    public static int encode(RangeEncoder enc, int value, int fs, int decay) {
        Objects.requireNonNull(enc, "enc");
        if (fs < 0 || fs > MAX_ZERO_FREQ) {
            throw new RangeCoderException("a Laplace zero-probability must be 0 to "
                    + MAX_ZERO_FREQ + ", not " + fs);
        }
        if (decay < 0 || decay > MAX_DECAY) {
            throw new RangeCoderException("a Laplace decay rate must be 0 to "
                    + MAX_DECAY + ", not " + decay);
        }

        int coded = value;
        int fl = 0;
        if (value != 0) {
            // s is 0 for a positive value and -1 for a negative one, which is
            // what makes the sign fall out of the arithmetic below rather than
            // needing a branch: (val+s)^s is |val|, and (i+di+s)^s puts the sign
            // back on.
            int s = value < 0 ? -1 : 0;
            int val = (value + s) ^ s;
            fl = fs;
            fs = freq1(fs, decay);
            int i = 1;
            for (; fs > 0 && i < val; i++) {
                fs *= 2;
                fl += fs + 2 * MIN_PROB;
                fs = (fs * decay) >> 15;
            }
            if (fs == 0) {
                int ndiMax = (TOTAL - fl + MIN_PROB - 1) >> LOG_MIN_PROB;
                ndiMax = (ndiMax - s) >> 1;
                int di = Math.min(val - i, ndiMax - 1);
                fl += (2 * di + 1 + s) * MIN_PROB;
                fs = Math.min(MIN_PROB, TOTAL - fl);
                coded = (i + di + s) ^ s;
            } else {
                fs += MIN_PROB;
                fl += fs & ~s;
            }
            if (fl + fs > TOTAL || fs <= 0) {
                throw new RangeCoderException("a Laplace symbol for " + value
                        + " came out as [" + fl + "," + (fl + fs) + ") of " + TOTAL
                        + ", which is not a range the coder can carry");
            }
        }
        enc.encodeBin(fl, fl + fs, TOTAL_BITS);
        return coded;
    }

    /**
     * Decodes one Laplace-distributed integer, {@code ec_laplace_decode}.
     *
     * @param dec   the frame's range decoder, positioned at the symbol
     * @param fs    probability of zero out of 32768, {@code e_prob_model[..][2*i]<<7}
     * @param decay Q14 ratio between consecutive magnitudes,
     *              {@code e_prob_model[..][2*i+1]<<6}
     * @return the decoded value, positive or negative
     * @throws RangeCoderException if the parameters are outside the range the
     *                             format can express
     */
    public static int decode(RangeDecoder dec, int fs, int decay) {
        Objects.requireNonNull(dec, "dec");
        if (fs < 0 || fs > MAX_ZERO_FREQ) {
            throw new RangeCoderException("a Laplace zero-probability must be 0 to "
                    + MAX_ZERO_FREQ + ", not " + fs);
        }
        if (decay < 0 || decay > MAX_DECAY) {
            throw new RangeCoderException("a Laplace decay rate must be 0 to "
                    + MAX_DECAY + ", not " + decay);
        }

        int val = 0;
        int fm = dec.decodeBin(TOTAL_BITS);
        int fl = 0;
        if (fm >= fs) {
            val++;
            fl = fs;
            fs = freq1(fs, decay) + MIN_PROB;
            // Walk the geometric part one magnitude at a time. The loop
            // terminates whatever the caller's decay is: it only runs while
            // fl + 2*fs <= fm, and fm is below 32768 because decodeBin(15)
            // produced it, so fl stays below 32768 too. Each pass adds 2*fs to
            // fl, and the condition it just passed guarantees fs > MIN_PROB, so
            // fl grows by at least four and this runs at most 8191 times. That
            // bound is what stops a corrupt frame from spinning here.
            while (fs > MIN_PROB && fm >= fl + 2 * fs) {
                fs *= 2;
                fl += fs;
                fs = ((fs - 2 * MIN_PROB) * decay) >> 15;
                fs += MIN_PROB;
                val++;
            }
            // Everything past the decaying part is MIN_PROB wide, so the rest of
            // the magnitude is a division rather than a search. Without this the
            // loop above would have to run once per magnitude and a corrupt
            // frame could ask it for tens of thousands of passes.
            if (fs <= MIN_PROB) {
                int di = (fm - fl) >> (LOG_MIN_PROB + 1);
                val += di;
                fl += 2 * di * MIN_PROB;
            }
            // Sign last: each magnitude occupies two adjacent slots, the lower
            // one negative. Reading the sign from the wrong slot inverts the
            // energy step of a band, which a listener hears as a band swelling
            // where the encoder faded it.
            if (fm < fl + fs) {
                val = -val;
            } else {
                fl += fs;
            }
        }
        // The IMIN is Appendix A's and is kept because Appendix A has it, not
        // because it fires. It cannot: fl never passes fm, decodeBin holds fm
        // below 32768, and the widths the distribution can be sitting on at that
        // point sum to at most the total. LaplaceTest walks every state the
        // machine above can stop in, for every model the energy tables can ask
        // for, and finds fl+fs reaching exactly 32768 and never exceeding it.
        // Dropping it would therefore be safe and would also be a place where
        // this file stopped saying the same thing as the specification.
        dec.update(fl, Math.min(fl + fs, TOTAL), TOTAL);
        return val;
    }
}
