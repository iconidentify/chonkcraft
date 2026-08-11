package net.chonkbase.chonkcraft.extract;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntConsumer;
import net.chonkbase.chonkcraft.data.source.AssetSource;

/**
 * Finds the music tracks that are the same recording twice.
 *
 * <p>A Warcraft II installation with both discs has thirty-three red book
 * tracks and only nineteen recordings. Fourteen of Beyond the Dark Portal's
 * tracks are Tides of Darkness's, pressed a fifth of a second apart -- the
 * audio is identical to the sample, the discs simply put the track boundary in
 * a different place. Stored twice that is 358 MB of duplicated PCM, which is
 * more than half the pack.
 *
 * <p>They are not byte-identical, so a hash finds nothing. What finds them is
 * that one is the other shifted by a few hundred samples, and that shift is
 * small: every pair measured here is between 214 and 227 frames. So this
 * looks for an alignment rather than an equality, and having found one, proves
 * it by comparing every overlapping sample before anything is deduplicated.
 *
 * <p>Nothing here is approximate. A group's master holds the union of its
 * members, and each member is a window into it that reproduces its original
 * samples exactly. If the proof fails at any point the track stays on its own,
 * which costs space and cannot cost correctness.
 */
public final class MusicGrouping {

    /**
     * Where the fingerprint is taken from, in samples.
     *
     * <p>Past the start, because tracks begin with silence and every silent
     * window matches every other. The shortest track on either disc runs to
     * over three million samples, so a million in is comfortably inside all of
     * them.
     */
    private static final int ANCHOR_AT = 1_000_000;

    /** How much of each track to remember while looking for pairs. */
    private static final int ANCHOR_LENGTH = 16_384;

    /** The needle taken from one anchor and looked for in another. */
    private static final int NEEDLE = 512;

    /**
     * The largest shift worth looking for, in samples.
     *
     * <p>Two pressings of the same track differ by where the boundary was cut,
     * which is a fraction of a second. A window wide enough for a second of
     * audio would start matching genuinely different tracks that happen to
     * share a passage.
     */
    private static final int MAX_SHIFT = 4_096;

    /** One track's place in a group. */
    public record Member(int track, int frameOffset) {}

    /** A recording, and the tracks that are windows into it. */
    public record Group(List<Member> members, int totalSamples, int channels, int sampleRate) {

        /** Whether this group holds more than one track. */
        public boolean shared() {
            return members.size() > 1;
        }
    }

    private MusicGrouping() {
    }

    /**
     * Groups the source's tracks by recording.
     *
     * <p>Reads every track once to fingerprint it. That is a second pass over
     * a gigabyte of disc image, and it buys back a third of the pack.
     */
    public static List<Group> of(AssetSource source) {
        return of(source, ignored -> { });
    }

    /** Groups tracks while reporting how many source recordings were scanned. */
    public static List<Group> of(AssetSource source, IntConsumer progress) {
        List<AssetSource.MusicTrack> tracks = source.musicTracks();
        int count = tracks.size();
        short[][] anchors = new short[count][];
        int[] lengths = new int[count];

        for (int i = 0; i < count; i++) {
            short[] samples = source.musicSamples(i);
            lengths[i] = samples.length;
            if (samples.length >= ANCHOR_AT + ANCHOR_LENGTH) {
                anchors[i] = Arrays.copyOfRange(samples, ANCHOR_AT, ANCHOR_AT + ANCHOR_LENGTH);
            }
            progress.accept(i + 1);
        }

        List<List<Member>> groups = new ArrayList<>();
        List<Integer> bases = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int joined = -1;
            for (int g = 0; g < groups.size() && joined < 0; g++) {
                int base = bases.get(g);
                if (anchors[i] == null || anchors[base] == null) {
                    continue;
                }
                if (Math.abs(lengths[i] - lengths[base]) > MAX_SHIFT) {
                    continue;
                }
                int shift = shiftBetween(anchors[base], anchors[i]);
                if (shift == Integer.MIN_VALUE) {
                    continue;
                }
                int channels = Math.max(1, tracks.get(i).channels());
                if (shift % channels != 0) {
                    // A shift that is not a whole number of frames would swap
                    // the channels over. Real pressings never do this, and a
                    // track with its stereo reversed is exactly the kind of
                    // fault nobody reports and everybody hears.
                    continue;
                }
                if (!provenIdentical(source, base, i, shift)) {
                    continue;
                }
                groups.get(g).add(new Member(i, shift / channels));
                joined = g;
            }
            if (joined < 0) {
                List<Member> fresh = new ArrayList<>();
                fresh.add(new Member(i, 0));
                groups.add(fresh);
                bases.add(i);
            }
        }

        List<Group> out = new ArrayList<>(groups.size());
        for (int g = 0; g < groups.size(); g++) {
            List<Member> members = groups.get(g);
            int channels = Math.max(1, tracks.get(bases.get(g)).channels());
            int least = 0;
            for (Member member : members) {
                least = Math.min(least, member.frameOffset());
            }
            List<Member> shifted = new ArrayList<>(members.size());
            int total = 0;
            for (Member member : members) {
                int offset = member.frameOffset() - least;
                shifted.add(new Member(member.track(), offset));
                total = Math.max(total, offset * channels + lengths[member.track()]);
            }
            out.add(new Group(List.copyOf(shifted), total, channels,
                    tracks.get(bases.get(g)).sampleRate()));
        }
        return out;
    }

    /**
     * How far {@code other} is shifted relative to {@code base}, or
     * {@link Integer#MIN_VALUE} when they do not line up.
     *
     * <p>Positive means {@code other} starts later in the recording: sample
     * {@code k} of other is sample {@code k + shift} of base.
     */
    private static int shiftBetween(short[] base, short[] other) {
        int forward = find(base, other, 0);
        if (forward != Integer.MIN_VALUE) {
            return forward;
        }
        int backward = find(other, base, 0);
        return backward == Integer.MIN_VALUE ? Integer.MIN_VALUE : -backward;
    }

    /** Where {@code needle}'s opening run appears inside {@code haystack}. */
    private static int find(short[] haystack, short[] needle, int from) {
        outer:
        for (int at = from; at + NEEDLE <= haystack.length && at <= MAX_SHIFT; at++) {
            for (int k = 0; k < NEEDLE; k++) {
                if (haystack[at + k] != needle[k]) {
                    continue outer;
                }
            }
            return at;
        }
        return Integer.MIN_VALUE;
    }

    /**
     * Reads both tracks in full and checks every overlapping sample.
     *
     * <p>The alignment above is found from sixteen thousand samples out of
     * nineteen million, which is enough to be confident and nowhere near
     * enough to be sure. This is the part that makes the deduplication
     * lossless rather than probably lossless.
     */
    private static boolean provenIdentical(AssetSource source, int base, int other, int shift) {
        short[] a = source.musicSamples(base);
        short[] b = source.musicSamples(other);
        int from = Math.max(0, shift);
        int to = Math.min(a.length, b.length + shift);
        if (to - from < a.length / 2) {
            // Less than half in common is two tracks that share a passage, not
            // one recording pressed twice.
            return false;
        }
        for (int i = from; i < to; i++) {
            if (a[i] != b[i - shift]) {
                return false;
            }
        }
        return true;
    }
}
