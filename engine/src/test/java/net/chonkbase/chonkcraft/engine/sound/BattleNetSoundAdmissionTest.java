package net.chonkbase.chonkcraft.engine.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicLong;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BNE 0x440792..0x4407a2 gates a sound for 80 ms after successful playback.
 * Controlled native replays checked 79/80/81 ms and unsigned clock wrap.
 * The port admitted every coincident blow; thirty-two real peasant attacks
 * drove even ten-percent volume into the master limiter's 0.98 ceiling.
 */
class BattleNetSoundAdmissionTest {
    private static SoundBank sounds() {
        AssetSource source = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(source != null, "Warcraft II audio assets are required");
        return new GameData(source).sounds();
    }

    @Test
    @DisplayName("a burst of real effects follows every volume setting and zero is exact silence")
    void repeatedEffectsCannotNormalizeAwayTheVolumeSlider() {
        SoundBank bank = sounds();
        double full = renderedPower(bank, 1f);
        assertTrue(full > 0.001, "the real attack sample must be audible");
        for (float volume : new float[] {0.8f, 0.5f, 0.2f, 0.1f, 0f}) {
            double power = renderedPower(bank, volume);
            assertEquals(full * volume, power, 0.000001,
                    "a crowded effect must follow the slider's amplitude at " + volume);
        }
    }

    private static double renderedPower(SoundBank bank, float volume) {
        try (GameAudio audio = new GameAudio(bank, () -> 1_000_000_000L)) {
            audio.startWithoutDevice();
            SoundServer server = new SoundServer(audio.mixer(), null, null, SoundServer.Backend.CD);
            server.setEffectVolume(volume);
            audio.mixer().render(new float[128], 64);
            for (int index = 0; index < 32; index++) {
                audio.playNamedAt("peasant-attack", bound -> 0, 0f);
            }
            float[] samples = new float[48_000];
            audio.mixer().render(samples, samples.length / 2);
            double power = 0;
            for (float sample : samples) {
                power += sample * sample;
            }
            return Math.sqrt(power / samples.length);
        }
    }

    @Test
    @DisplayName("sound repeats respect the native boundary and preserve sample selection")
    void nativeRepeatBoundaryDoesNotSlideOnRefusal() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        int[] choices = {0};
        try (GameAudio audio = new GameAudio(sounds(), now::get)) {
            audio.startWithoutDevice();
            for (int elapsed : new int[] {0, 40, 79, 80}) {
                now.set(1_000_000_000L + elapsed * 1_000_000L);
                audio.playNamedAt("peasant-attack", bound -> { choices[0]++; return 0; }, 0f);
                audio.mixer().render(new float[2], 1);
                assertEquals(elapsed < 80 ? 1 : 2, audio.mixer().logicalVoiceCount(),
                        "native admission at " + elapsed + " ms");
            }
            assertEquals(4, choices[0], "suppressed playback must preserve ordinary sample selection");
        }
    }
}
