package net.chonkbase.chonkcraft.engine.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.chonkbase.chonkcraft.data.GraphicsIndex;
import net.chonkbase.chonkcraft.data.NameTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The music playlist: what plays after the current track ends.
 *
 * <p>Covers the behaviour of {@code MusicStopped} in
 * {@code scripts/sound.legacy-declaration} and {@code StopMusic}.
 *
 * <p>These run everywhere. They need no installation, because the asset index
 * comes from the bundled conversion table rather than the archives, and no
 * audio device, because the selection is driven through the same seam the
 * player uses rather than through a synthesiser. That matters here more than
 * usual: the defect being fixed is that music stopped after one track and
 * nobody noticed for a long time, which is exactly what happens to behaviour
 * that only a human with speakers can observe.
 */
class MusicPlaylistTest {

    // ------------------------------------------------------------- selection

    @Test
    @DisplayName("a track that plays is left in the playlist, so it can come round again")
    void aSuccessfulTrackIsNotConsumed() {
        MusicPlayer music = player(1);
        music.setPlaylist(List.of("a", "b", "c"));

        List<String> played = new ArrayList<>();
        assertTrue(music.advanceUsing(track -> played.add(track)));

        assertEquals(1, played.size(), "one track should have started");
        assertEquals(List.of("a", "b", "c"), music.playlist(),
                "upstream returns as soon as PlayMusic succeeds and removes nothing; "
                        + "consuming the entry would silence the game after three tracks");
    }

    @Test
    @DisplayName("a track that will not play is dropped and another is tried")
    void aFailingTrackIsPrunedAndTheNextTried() {
        // Only one entry can play, so reaching it proves the loop keeps trying
        // rather than giving up on the first failure. The seed is not relied
        // on: whichever order the picks come in, anything attempted and failed
        // must be gone and the playable one must remain.
        MusicPlayer music = player(7);
        music.setPlaylist(List.of("broken-1", "broken-2", "broken-3", "fine"));

        List<String> attempts = new ArrayList<>();
        assertTrue(music.advanceUsing(track -> {
            attempts.add(track);
            return "fine".equals(track);
        }));

        assertEquals("fine", attempts.getLast(), "it should stop at the one that plays");
        assertTrue(music.playlist().contains("fine"), "a track that played is kept");
        for (String attempted : attempts) {
            if (!"fine".equals(attempted)) {
                assertFalse(music.playlist().contains(attempted),
                        attempted + " failed to play and should have been pruned");
            }
        }
    }

    @Test
    @DisplayName("an empty playlist plays nothing rather than looping forever")
    void anEmptyPlaylistStops() {
        MusicPlayer music = player(1);
        music.setPlaylist(List.of());
        assertFalse(music.advanceUsing(track -> true));
    }

    @Test
    @DisplayName("when every track fails the playlist empties and the game falls silent")
    void allFailingTracksTerminate() {
        MusicPlayer music = player(3);
        music.setPlaylist(List.of("a", "b", "c"));

        assertFalse(music.advanceUsing(track -> false),
                "nothing plays, and the loop must end rather than spin");
        assertTrue(music.playlist().isEmpty());
    }

    @Test
    @DisplayName("selection is random, not always the first track")
    void selectionIsRandom() {
        MusicPlayer music = player(42);
        music.setPlaylist(List.of("a", "b", "c", "d", "e"));

        List<String> chosen = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            music.advanceUsing(track -> chosen.add(track));
        }
        // The bug this guards: the shipped code took the first matching track
        // every time, so a session heard "Human Battle 1" and nothing else.
        assertTrue(chosen.stream().distinct().count() > 1,
                "a random playlist should not keep choosing the same track");
    }

    // ---------------------------------------------------------- track naming

    @Test
    @DisplayName("the situation playlists name tracks the index actually has")
    void everyNamedTrackExists() {
        MusicPlayer music = player(1);
        List<String> known = music.tracks();
        assertTrue(known.size() >= 15, "expected the shipped music table, found " + known.size());

        List<String> named = new ArrayList<>();
        named.addAll(MusicPlayer.battleTracks(false));
        named.addAll(MusicPlayer.battleTracks(true));
        named.addAll(MusicPlayer.menuTracks());
        named.addAll(MusicPlayer.briefingTracks(false));
        named.addAll(MusicPlayer.briefingTracks(true));
        named.addAll(MusicPlayer.resultTracks(false, true));
        named.addAll(MusicPlayer.resultTracks(true, true));
        named.addAll(MusicPlayer.resultTracks(false, false));
        named.addAll(MusicPlayer.resultTracks(true, false));

        for (String track : named) {
            assertTrue(known.contains(track),
                    track + " is not in the conversion table; the playlist would be silent");
        }
    }

    @Test
    @DisplayName("each race gets its own five battle tracks")
    void battleTracksAreFivePerRace() {
        assertEquals(5, MusicPlayer.battleTracks(false).size());
        assertEquals("Human Battle 1", MusicPlayer.battleTracks(false).getFirst());
        assertEquals("Orc Battle 5", MusicPlayer.battleTracks(true).getLast());
    }

    @Test
    @DisplayName("the menu and the orc briefing share a theme, as the shipped table does")
    void theMenuUsesTheOrcBriefingTheme() {
        // Not a copy-paste slip: entries 429 in the conversion table serve both
        // "Orc Briefing" and "Main Menu", and every menu in guichan.legacy-declaration names
        // music/Orc Briefing.
        assertEquals(MusicPlayer.briefingTracks(true), MusicPlayer.menuTracks());
    }

    @Test
    @DisplayName("victory and defeat differ, and differ by race")
    void resultTracksAreDistinct() {
        assertEquals(List.of("Human Victory"), MusicPlayer.resultTracks(false, true));
        assertEquals(List.of("Human Defeat"), MusicPlayer.resultTracks(false, false));
        assertEquals(List.of("Orc Victory"), MusicPlayer.resultTracks(true, true));
        assertEquals(List.of("Orc Defeat"), MusicPlayer.resultTracks(true, false));
    }

    @Test
    @DisplayName("names the installation does not have are filtered out before playing")
    void availableKeepsOnlyRealTracks() {
        MusicPlayer music = player(1);
        List<String> filtered = music.available(
                List.of("Human Battle 1", "Human Battle 6", "not a track"));
        assertEquals(List.of("Human Battle 1"), filtered,
                "Battle 6 ships only with the expansion and must not reach the playlist");
    }

    // ------------------------------------------------------------- callbacks

    @Test
    @DisplayName("a finished track does nothing when there is no sequencer")
    void finishingWithoutASequencerIsHarmless() {
        // start() was never called, so there is no advancer thread. The
        // end-of-track hook must not throw: a machine with no synthesiser
        // should play the game in silence rather than fail.
        MusicPlayer music = player(1);
        music.setPlaylist(List.of("a"));
        music.onTrackFinished();
        assertFalse(music.isPlaying());
    }

    @Test
    @DisplayName("stopping does not chain into the next track")
    void stopDoesNotAdvance() {
        // Upstream's StopMusic brackets the halt with CallbackMusicDisable and
        // CallbackMusicEnable for exactly this reason. Without it, every
        // deliberate stop -- leaving a briefing, entering a cutscene -- would
        // start something else instead of falling silent.
        MusicPlayer music = player(1);
        music.silence();
        assertTrue(music.playlist().isEmpty(),
                "silence() must clear the playlist so nothing can follow");
    }

    // ----------------------------------------------------------------- setup

    /**
     * A player over the real conversion table, with no archive and no device.
     *
     * <p>{@code GraphicsIndex.load} reads the bundled {@code graphics-index.tsv}
     * rather than the game archives, so the music rows -- and therefore the
     * track names -- are present without an installation.
     */
    private static MusicPlayer player(long seed) {
        return new MusicPlayer(null, GraphicsIndex.load(names("Footman")), new Random(seed));
    }

    /** A minimal name table; the music rows do not depend on its contents. */
    private static NameTable names(String... strings) {
        int count = strings.length + 1;
        int headerBytes = count * 2;

        ByteArrayOutputStream body = new ByteArrayOutputStream();
        int[] offsets = new int[count];
        for (int i = 0; i < strings.length; i++) {
            offsets[i + 1] = headerBytes + body.size();
            body.writeBytes(strings[i].getBytes(StandardCharsets.ISO_8859_1));
            body.write(0);
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(count & 0xFF);
        out.write((count >>> 8) & 0xFF);
        for (int i = 1; i < count; i++) {
            out.write(offsets[i] & 0xFF);
            out.write((offsets[i] >>> 8) & 0xFF);
        }
        out.writeBytes(body.toByteArray());
        return NameTable.from(out.toByteArray());
    }
}
