package net.chonkbase.runtime.audio;

final class AudioMath {
    static final float SILENT_DB = -80.0f;
    static final float MAX_GAIN_DB = 12.0f;

    private AudioMath() {}

    static void requireGainDb(float gainDb) {
        if (!Float.isFinite(gainDb) || gainDb < SILENT_DB || gainDb > MAX_GAIN_DB) {
            throw new IllegalArgumentException(
                    "gainDb must be finite and within [" + SILENT_DB + ", " + MAX_GAIN_DB + "]");
        }
    }

    static float dbToLinear(float gainDb) {
        if (gainDb <= SILENT_DB) {
            return 0.0f;
        }
        return (float) Math.pow(10.0, gainDb / 20.0);
    }
}
