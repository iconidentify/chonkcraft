package net.chonkbase.chonkcraft.data.source;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class BneMusicContractTest {

    @Test
    void completeInstallExeCatalogIsAccepted() {
        assertDoesNotThrow(() -> BneMusicContract.validate(complete()));
    }

    @Test
    void missingSilentWrongRateAndWrongContainerTracksAreRefused() {
        List<AssetSource.MusicTrack> missing = new ArrayList<>(complete());
        missing.removeLast();
        assertThrows(IllegalStateException.class, () -> BneMusicContract.validate(missing));

        List<AssetSource.MusicTrack> silent = new ArrayList<>(complete());
        silent.set(0, track(0, 0, BneMusicContract.SAMPLE_RATE,
                "INSTALL.EXE:Music\\HUMAN1.WAV"));
        assertThrows(IllegalStateException.class, () -> BneMusicContract.validate(silent));

        List<AssetSource.MusicTrack> wrongRate = new ArrayList<>(complete());
        wrongRate.set(0, track(0, 100, 44_100, "INSTALL.EXE:Music\\HUMAN1.WAV"));
        assertThrows(IllegalStateException.class, () -> BneMusicContract.validate(wrongRate));

        List<AssetSource.MusicTrack> wrongContainer = new ArrayList<>(complete());
        wrongContainer.set(0, track(0, 100, BneMusicContract.SAMPLE_RATE,
                "disc.bin:track-02"));
        assertThrows(IllegalStateException.class,
                () -> BneMusicContract.validate(wrongContainer));
    }

    private static List<AssetSource.MusicTrack> complete() {
        List<AssetSource.MusicTrack> tracks = new ArrayList<>();
        for (int i = 0; i < BneMusicContract.NAMES.size(); i++) {
            tracks.add(track(i, 100 + i, BneMusicContract.SAMPLE_RATE,
                    "INSTALL.EXE:Music\\TRACK" + i + ".WAV"));
        }
        return List.copyOf(tracks);
    }

    private static AssetSource.MusicTrack track(int index, long frames, int rate,
            String origin) {
        return new AssetSource.MusicTrack(BneMusicContract.NAMES.get(index), rate,
                BneMusicContract.CHANNELS, frames, origin);
    }
}
