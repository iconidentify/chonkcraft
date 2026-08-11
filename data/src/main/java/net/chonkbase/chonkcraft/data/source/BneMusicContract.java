package net.chonkbase.chonkcraft.data.source;

import java.util.List;

/** Fail-closed identity and PCM contract for Battle.net Edition's soundtrack. */
public final class BneMusicContract {

    public static final int TRACKS = 20;
    public static final int SAMPLE_RATE = 22_050;
    public static final int CHANNELS = 2;
    public static final int BITS_PER_SAMPLE = 16;
    public static final int OPUS_BITRATE_BPS = 144_000;
    public static final int OPUS_DECODE_RATE = 48_000;

    public static final List<String> NAMES = List.of(
            "Human Battle 1", "Human Battle 2", "Human Battle 3",
            "Human Battle 4", "Human Battle 5", "Human Battle 6",
            "Orc Battle 1", "Orc Battle 2", "Orc Battle 3",
            "Orc Battle 4", "Orc Battle 5", "Orc Battle 6",
            "Human Briefing", "Orc Briefing", "Human Victory", "Orc Victory",
            "Human Defeat", "Orc Defeat", "Main Menu", "I'm a Medieval Man");

    private BneMusicContract() {
    }

    /** Refuses a partial, reordered, empty or non-BNE logical soundtrack. */
    public static void validate(List<AssetSource.MusicTrack> tracks) {
        if (tracks == null || tracks.size() != TRACKS) {
            throw new IllegalStateException("Battle.net Edition requires exactly " + TRACKS
                    + " logical music tracks, found " + (tracks == null ? 0 : tracks.size()));
        }
        for (int i = 0; i < TRACKS; i++) {
            AssetSource.MusicTrack track = tracks.get(i);
            String expected = NAMES.get(i);
            if (!expected.equals(track.name())) {
                throw new IllegalStateException("Battle.net music track " + (i + 1)
                        + " is " + track.name() + ", expected " + expected);
            }
            if (track.sampleRate() != SAMPLE_RATE || track.channels() != CHANNELS) {
                throw new IllegalStateException(expected + " is " + track.sampleRate() + " Hz / "
                        + track.channels() + " channels, expected " + SAMPLE_RATE
                        + " Hz stereo");
            }
            if (track.frames() <= 0) {
                throw new IllegalStateException(expected + " is empty");
            }
            if (!track.sourceOrigin().startsWith("INSTALL.EXE:Music\\")) {
                throw new IllegalStateException(expected + " came from " + track.sourceOrigin()
                        + ", expected INSTALL.EXE:Music\\*.WAV");
            }
        }
    }
}
