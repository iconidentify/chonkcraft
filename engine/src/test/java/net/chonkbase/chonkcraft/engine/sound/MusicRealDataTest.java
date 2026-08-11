package net.chonkbase.chonkcraft.engine.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Converts the real music out of a Warcraft II installation. */
class MusicRealDataTest {

    private static GameData gameData() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("the game's music tracks are indexed")
    void theTracksAreIndexed() {
        MusicPlayer music = gameData().music();
        assertTrue(music.tracks().size() >= 15,
                "expected the full score, got " + music.tracks().size());
        assertTrue(music.tracks().stream().anyMatch(t -> t.startsWith("Human Battle")));
        assertTrue(music.tracks().stream().anyMatch(t -> t.startsWith("Orc Battle")));
    }

    @Test
    @DisplayName("every track converts to a MIDI sequence Java can read")
    void everyTrackConverts() {
        MusicPlayer music = gameData().music();

        int converted = 0;
        for (String track : music.tracks()) {
            Sequence sequence = music.sequence(track);
            assertTrue(sequence != null, track + " did not convert");
            assertTrue(sequence.getTracks().length > 0, track + " has no track data");
            converted++;
        }
        assertEquals(music.tracks().size(), converted, "some tracks failed to convert");
    }

    @Test
    @DisplayName("the converted music contains real notes")
    void theMusicContainsRealNotes() {
        MusicPlayer music = gameData().music();
        Sequence sequence = music.sequence(music.tracks().getFirst());
        Assumptions.assumeTrue(sequence != null, "no music in this installation");

        int noteOns = 0;
        int noteOffs = 0;
        for (Track track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                if (track.get(i).getMessage() instanceof ShortMessage message
                        && message.getCommand() == ShortMessage.NOTE_ON) {
                    if (message.getData2() > 0) {
                        noteOns++;
                    } else {
                        noteOffs++;
                    }
                }
            }
        }
        assertTrue(noteOns > 500, "a battle theme should have plenty of notes, got " + noteOns);
        // XMI has no note-offs, so every one of these was synthesised. A
        // mismatch means notes are being left hanging.
        assertEquals(noteOns, noteOffs, "every note should be ended exactly once");
    }

    @Test
    @DisplayName("tracks play for the length Warcraft II's music actually runs")
    void tracksAreTheRightLength() {
        MusicPlayer music = gameData().music();

        for (String track : music.tracks().subList(0, Math.min(6, music.tracks().size()))) {
            Sequence sequence = music.sequence(track);
            long seconds = sequence.getMicrosecondLength() / 1_000_000;
            // Warcraft II's themes run two to four minutes. This is the check
            // that catches the tempo scaling being applied twice, which makes
            // everything three times too long and still parses cleanly.
            assertTrue(seconds > 60 && seconds < 360,
                    track + " runs " + seconds + " seconds, which is not a Warcraft II track");
        }
    }
}
