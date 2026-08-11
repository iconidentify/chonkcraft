package net.chonkbase.assetpack.codec;

/**
 * How close two versions of the same audio are, in decibels.
 *
 * <p>The question a lossless codec never has to ask and a lossy one can never
 * avoid. {@link Flac} round trips to the bit, so "did this survive" is
 * {@code Arrays.equals}; {@link Opus} does not, so the only honest check is how
 * far the samples moved, and every part of the build that wants to know has to
 * ask it the same way. Two implementations of "close enough" would let a pack
 * pass the build's own check and fail the verifier, or worse, the other way
 * round.
 *
 * <p>This is plain waveform signal-to-noise: the power of the reference over
 * the power of the difference. It is <em>not</em> a measure of how the audio
 * sounds, and it deliberately under-rates a perceptual codec, which is free to
 * move energy around inside a band as long as the band's total is right. That
 * makes it the conservative choice for a regression gate: a number that only
 * ever understates the quality cannot pass audio that has actually broken. What
 * it does catch, loudly, is everything that goes wrong structurally -- a
 * channel swap, a sample-rate mismatch, an off-by-one alignment, a stream
 * decoded from the wrong offset -- because all of those drive it to roughly
 * 0 dB whatever the codec did.
 */
public final class SignalToNoise {

    /**
     * The floor a lossy sound effect has to clear against the audio it replaces.
     *
     * <p>Measured, not chosen. Opus at 64 kb/s reaches about 31 dB on a tone and
     * 13 to 18 dB on noise-like material at the rates a 1990s game uses, and
     * recorded speech sits between the two. Twelve decibels is comfortably under
     * everything that is working and far above everything that is not: a channel
     * swap, a rate mismatch, a stream read from the wrong offset and an
     * off-by-one alignment all land within a decibel or two of zero, because a
     * signal misaligned against itself has as much error power as signal.
     *
     * <p>A clip that cannot clear it is not shipped lossy. It goes into the pack
     * losslessly instead, which costs bytes and cannot cost quality, so the
     * failure mode of the whole lossy path is a slightly larger pack.
     */
    public static final double SOUND_FLOOR_DB = 12.0;

    /**
     * The floor a lossy music track has to clear against the disc it came from.
     *
     * <p>Lower than {@link #SOUND_FLOOR_DB}, and not because music matters less.
     * Stereo CELT spends its bits on mid-side energy and phase rather than on
     * reproducing each channel's waveform, so plain waveform SNR under-rates it
     * further than it under-rates a mono effect -- the audio is closer to the
     * disc than the number suggests, and the number is still the right shape of
     * check because everything structural still drives it to zero.
     */
    public static final double MUSIC_FLOOR_DB = 8.0;

    private SignalToNoise() {
    }

    /**
     * Compares two runs of samples, which must be the same length and on the
     * same scale.
     *
     * @return decibels; {@link Double#POSITIVE_INFINITY} when they are
     *         identical, and {@link Double#NEGATIVE_INFINITY} when the
     *         reference is silence but the measurement is not
     */
    public static double db(int[] reference, int[] measured) {
        if (reference.length != measured.length) {
            throw new IllegalArgumentException("cannot compare " + reference.length
                    + " samples against " + measured.length + "; resample one first");
        }
        double signal = 0;
        double noise = 0;
        for (int i = 0; i < reference.length; i++) {
            double r = reference[i];
            double d = r - measured[i];
            signal += r * r;
            noise += d * d;
        }
        if (noise == 0) {
            return Double.POSITIVE_INFINITY;
        }
        if (signal == 0) {
            // Silence in, something out. There is no ratio to report and
            // reporting 0 dB would read as "half the energy is error", which is
            // far too kind to a decoder that invented a sound.
            return Double.NEGATIVE_INFINITY;
        }
        return 10.0 * Math.log10(signal / noise);
    }

    /** As {@link #db(int[], int[])}, for the 16-bit form the mixer uses. */
    public static double db(short[] reference, short[] measured) {
        if (reference.length != measured.length) {
            throw new IllegalArgumentException("cannot compare " + reference.length
                    + " samples against " + measured.length + "; resample one first");
        }
        int[] a = new int[reference.length];
        int[] b = new int[measured.length];
        for (int i = 0; i < a.length; i++) {
            a[i] = reference[i];
            b[i] = measured[i];
        }
        return db(a, b);
    }

    /** The number as a report reads it, with infinity spelled out. */
    public static String describe(double db) {
        if (db == Double.POSITIVE_INFINITY) {
            return "bit-exact";
        }
        if (db == Double.NEGATIVE_INFINITY) {
            return "noise against silence";
        }
        return String.format(java.util.Locale.ROOT, "%.1f dB", db);
    }
}
