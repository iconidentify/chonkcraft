package net.chonkbase.chonkcraft.engine.sound;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.sound.midi.Sequence;
import javax.sound.midi.Sequencer;
import javax.sound.midi.ShortMessage;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.runtime.audio.AudioMixer;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Numbered recordings played the same battle for either race and silenced menus.
 * The choice was based on the existence of any CD audio, not the requested
 * musical role. These play real decoded recordings through the mixer and real
 * archive XMI through the sequencer boundary, including the numbered catalog
 * shape of older imports. They do not impersonate the reporter's German media.
 *
 * <p>The recording oracle is authenticated BNE 2.02b: 0x440f2c resolves scene
 * rows at 0x4a1898 through filenames at 0x4a184c. Rows 0..11 are the six battles
 * per race; 12..15 are defeat/victory, and 20..22 are briefing/menu. The tests
 * below use those roles to request music, then check both the actual recording
 * read and non-silent PCM, rather than merely asserting that a name was parsed.
 */
class SoundtrackRealDataTest {
    private static AssetSource assets() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No Warcraft II assets; set CHONKCRAFT_ASSET_PACK or WC2_INSTALL_DIR.");
        Assumptions.assumeTrue(assets.isBattleNetEdition(),
                "The soundtrack referee requires an authenticated Battle.net asset pack.");
        return assets;
    }

    @Test
    @DisplayName("every battle and front-end role emits its own real recorded music")
    void everyMusicalRoleReachesTheRecordedSamples() {
        AssetSource assets = assets();
        List<String> roles = new ArrayList<>();
        for (String race : List.of("Human", "Orc")) {
            for (int number = 1; number <= 6; number++) {
                roles.add(race + " Battle " + number);
            }
            roles.add(race + " Briefing");
            roles.add(race + " Victory");
            roles.add(race + " Defeat");
        }
        roles.add("Main Menu");
        assertEquals(19, roles.size(), "the native scene-family sweep lost a role");
        for (String role : roles) {
            int index = indexOf(assets, role);
            List<Integer> reads = new ArrayList<>();
            AssetSource onlyThisRecording = view(assets,
                    List.of(assets.musicTracks().get(index)), List.of(index), reads);
            AudioMixer mixer = new AudioMixer();
            CdMusic disc = new CdMusic(onlyThisRecording, mixer);
            try (SoundServer server = new SoundServer(mixer, disc, null, SoundServer.Backend.CD)) {
                assertTrue(play(server, role), role + " did not start");
                assertEquals(List.of(index), reads, role + " read another recording");
                assertEquals(role, disc.playing(), role + " was replaced by a different theme");
                assertTrue(assets.musicTracks().get(index).sourceOrigin()
                                .endsWith("Music\\" + nativeFile(role)),
                        role + " does not come from its native scene-table file");
                assertTrue(renderEnergy(mixer) > 0.001,
                        role + " selected metadata but emitted no music");
            }
        }
    }

    @Test
    @DisplayName("numbered imports use the real race and menu themes instead of an arbitrary track")
    void numberedRecordingsFallBackByMusicalRole() throws Exception {
        AssetSource assets = assets();
        List<AssetSource.MusicTrack> numbered = new ArrayList<>();
        List<Integer> indices = new ArrayList<>();
        for (int i = 0; i < assets.musicTracks().size(); i++) {
            var original = assets.musicTracks().get(i);
            numbered.add(new AssetSource.MusicTrack("disc track " + (i + 2),
                    original.sampleRate(), original.channels(), original.frames()));
            indices.add(i);
        }
        List<Integer> reads = new ArrayList<>();
        AudioMixer mixer = new AudioMixer();
        CdMusic disc = new CdMusic(view(assets, numbered, indices, reads), mixer);
        MusicPlayer synth = new GameData(assets).music();
        MidiOutput output = new MidiOutput(synth);
        try (SoundServer server = new SoundServer(mixer, disc, synth, SoundServer.Backend.CD)) {
            for (String role : List.of("Main Menu", "Human Battle 1", "Orc Battle 1",
                    "Human Briefing", "Orc Briefing", "Human Victory", "Orc Defeat")) {
                assertTrue(play(server, role), role + " did not reach the sequencer");
                assertEquals(SoundServer.Backend.XMI, server.backend(),
                        role + " chose unidentified recorded music");
                assertNull(disc.playing(), "a numbered track is still audible under " + role);
                assertNotNull(output.sequence, role + " delivered no MIDI score");
                assertTrue(output.running, role + " was loaded without starting playback");
                List<String> expected = role.contains("Battle")
                        ? MusicPlayer.battleTracks(role.startsWith("Orc"))
                        : List.of("Main Menu".equals(role) ? "Orc Briefing" : role);
                List<String> scores = new ArrayList<>();
                for (String name : expected) {
                    Sequence score = synth.sequence(name);
                    if (score != null) {
                        scores.add(notes(score));
                    }
                }
                assertFalse(scores.isEmpty(), role + " has no authentic score to compare");
                assertTrue(scores.contains(notes(output.sequence)),
                        role + " emitted the other race's MIDI notes");
            }
            assertTrue(reads.isEmpty(), "a numbered recording was guessed from its position");
        }
    }

    @Test
    @DisplayName("changing soundtrack source keeps the current scene and closing an old owner is harmless")
    void sourceChangesAndOldCleanupPreserveTheCurrentScene() throws Exception {
        AssetSource assets = assets();
        MusicPlayer synth = new GameData(assets).music();
        MidiOutput output = new MidiOutput(synth);
        AudioMixer firstMixer = new AudioMixer();
        SoundServer old = new SoundServer(firstMixer, new CdMusic(assets, firstMixer), synth,
                SoundServer.Backend.XMI);
        AudioMixer secondMixer = new AudioMixer();
        CdMusic disc = new CdMusic(assets, secondMixer);
        try (SoundServer next = new SoundServer(secondMixer, disc, synth, SoundServer.Backend.CD)) {
            old.playBattleMusic(true);
            assertTrue(next.playMenuMusic(), "the replacement menu did not start");
            assertTrue(next.setBackend(SoundServer.Backend.XMI), "the menu's MIDI did not start");
            assertEquals(notes(synth.sequence("Orc Briefing")), notes(output.sequence),
                    "changing source replaced the menu with battle music");
            old.close();
            assertTrue(output.running, "old session cleanup stopped the new soundtrack");
            assertTrue(next.setBackend(SoundServer.Backend.CD), "the recorded menu did not return");
            assertEquals("Main Menu", disc.playing(), "the source switch forgot the menu");
            assertTrue(next.playResultMusic(false, true), "the victory theme did not start");
            assertTrue(next.setBackend(SoundServer.Backend.XMI), "the victory score did not switch");
            assertEquals(notes(synth.sequence("Human Victory")), notes(output.sequence),
                    "the result source switch started a battle");
            assertTrue(synth.playlist().isEmpty(), "a result must not restart after finishing");
        } finally {
            old.close();
        }
    }

    private static boolean play(SoundServer server, String role) {
        boolean orc = role.startsWith("Orc");
        if (role.contains("Battle")) {
            return server.playBattleMusic(orc);
        }
        if (role.endsWith("Briefing")) {
            return server.playBriefingMusic(orc);
        }
        if (role.endsWith("Victory") || role.endsWith("Defeat")) {
            return server.playResultMusic(orc, role.endsWith("Victory"));
        }
        return server.playMenuMusic();
    }

    private static String nativeFile(String role) {
        String prefix = role.startsWith("Orc") ? "O" : "H";
        if (role.contains("Battle")) {
            return (role.startsWith("Orc") ? "ORC" : "HUMAN")
                    + role.charAt(role.length() - 1) + ".WAV";
        }
        if ("Main Menu".equals(role)) {
            return "OWARROOM.WAV";
        }
        return prefix + (role.endsWith("Briefing") ? "WARROOM"
                : role.endsWith("Victory") ? "VICTORY" : "DEFEAT") + ".WAV";
    }

    private static int indexOf(AssetSource assets, String role) {
        for (int i = 0; i < assets.musicTracks().size(); i++) {
            if (role.equals(assets.musicTracks().get(i).name())) {
                return i;
            }
        }
        throw new AssertionError("authenticated music role missing: " + role);
    }

    private static AssetSource view(AssetSource source, List<AssetSource.MusicTrack> tracks,
            List<Integer> indices, List<Integer> reads) {
        return (AssetSource) Proxy.newProxyInstance(AssetSource.class.getClassLoader(),
                new Class<?>[] {AssetSource.class}, (proxy, method, args) -> {
                    if ("musicTracks".equals(method.getName())) {
                        return tracks;
                    }
                    if ("musicSamples".equals(method.getName())) {
                        int index = indices.get((Integer) args[0]);
                        reads.add(index);
                        return source.musicSamples(index);
                    }
                    return method.invoke(source, args);
                });
    }

    private static double renderEnergy(AudioMixer mixer) {
        float[] samples = new float[2048];
        double energy = 0;
        for (int block = 0; block < 200; block++) {
            mixer.render(samples, 1024);
            for (float sample : samples) {
                energy += sample * sample;
            }
        }
        return energy;
    }

    private static String notes(Sequence sequence) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        int notes = 0;
        for (var track : sequence.getTracks()) {
            for (int i = 0; i < track.size(); i++) {
                var event = track.get(i);
                if (event.getMessage() instanceof ShortMessage message
                        && message.getCommand() == ShortMessage.NOTE_ON
                        && message.getData2() > 0) {
                    digest.update(ByteBuffer.allocate(8).putLong(event.getTick()).array());
                    digest.update(message.getMessage());
                    notes++;
                }
            }
        }
        assertTrue(notes > 100, "the authentic score must contain music, not only metadata");
        return HexFormat.of().formatHex(digest.digest());
    }

    /** Captures the real MIDI score delivered to the device boundary without a speaker. */
    private static final class MidiOutput {
        private Sequence sequence;
        private boolean running;
        private boolean open = true;

        private MidiOutput(MusicPlayer player) throws Exception {
            Sequencer device = (Sequencer) Proxy.newProxyInstance(Sequencer.class.getClassLoader(),
                    new Class<?>[] {Sequencer.class}, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "isOpen": return open;
                            case "isRunning": return running;
                            case "setSequence": sequence = (Sequence) args[0]; break;
                            case "start": running = true; break;
                            case "stop": running = false; break;
                            case "close": open = false; running = false; break;
                            default: break;
                        }
                        return null;
                    });
            var field = MusicPlayer.class.getDeclaredField("sequencer");
            field.setAccessible(true);
            field.set(player, device);
        }
    }
}
