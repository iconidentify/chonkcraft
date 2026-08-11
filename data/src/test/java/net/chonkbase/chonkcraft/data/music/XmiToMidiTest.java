package net.chonkbase.chonkcraft.data.music;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.sound.midi.MidiSystem;
import javax.sound.midi.Sequence;
import javax.sound.midi.ShortMessage;
import javax.sound.midi.Track;
import org.junit.jupiter.api.Test;

/** Tests for the XMI to MIDI converter, over hand-assembled files. */
class XmiToMidiTest {

    /** Wraps an event stream in the EVNT chunk the converter looks for. */
    private static byte[] xmi(byte... events) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("FORM".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[] {0, 0, 0, 0});
        out.writeBytes("XMID".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes("EVNT".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[] {0, 0, 0, 0});
        out.writeBytes(events);
        return out.toByteArray();
    }

    private static Sequence parse(byte[] midi) throws Exception {
        // Java's own parser is an independent judge of whether the output is
        // a real MIDI file rather than merely plausible bytes.
        return MidiSystem.getSequence(new ByteArrayInputStream(midi));
    }

    @Test
    void producesAMidiFileJavaCanRead() throws Exception {
        // One note-on lasting four ticks, then end of track.
        byte[] midi = XmiToMidi.convert(xmi(
                (byte) 0x90, (byte) 60, (byte) 100, (byte) 4,
                (byte) 0xFF, (byte) 0x2F, (byte) 0x00));

        assertEquals('M', midi[0]);
        assertEquals('T', midi[1]);
        assertEquals('h', midi[2]);
        assertEquals('d', midi[3]);
        assertTrue(parse(midi).getTracks().length > 0);
    }

    @Test
    void synthesisesANoteOffForEveryNote() throws Exception {
        // XMI has no note-off events: a note carries its own duration, so the
        // converter has to invent the end and place it.
        Sequence sequence = parse(XmiToMidi.convert(xmi(
                (byte) 0x90, (byte) 60, (byte) 100, (byte) 8,
                (byte) 0xFF, (byte) 0x2F, (byte) 0x00)));

        int noteOns = 0;
        int noteOffs = 0;
        Track track = sequence.getTracks()[0];
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
        assertEquals(1, noteOns);
        assertEquals(1, noteOffs, "every note needs an end or it sounds forever");
    }

    @Test
    void aNoteEndsAfterItsDeclaredDuration() throws Exception {
        Sequence sequence = parse(XmiToMidi.convert(xmi(
                (byte) 0x90, (byte) 60, (byte) 100, (byte) 10,
                (byte) 0xFF, (byte) 0x2F, (byte) 0x00)));

        Track track = sequence.getTracks()[0];
        long onTick = -1;
        long offTick = -1;
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof ShortMessage message
                    && message.getCommand() == ShortMessage.NOTE_ON) {
                if (message.getData2() > 0) {
                    onTick = track.get(i).getTick();
                } else {
                    offTick = track.get(i).getTick();
                }
            }
        }
        // Ten XMI ticks at three MIDI ticks each.
        assertEquals(30, offTick - onTick);
    }

    @Test
    void aRunOfSmallBytesIsADelay() throws Exception {
        // XMI counts time in bytes below 0x80, one tick each, rather than as
        // a variable-length quantity.
        Sequence sequence = parse(XmiToMidi.convert(xmi(
                (byte) 10, (byte) 20,
                (byte) 0x90, (byte) 60, (byte) 100, (byte) 1,
                (byte) 0xFF, (byte) 0x2F, (byte) 0x00)));

        Track track = sequence.getTracks()[0];
        long firstNote = -1;
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof ShortMessage message
                    && message.getCommand() == ShortMessage.NOTE_ON
                    && message.getData2() > 0) {
                firstNote = track.get(i).getTick();
                break;
            }
        }
        // Thirty XMI ticks of delay, times three.
        assertEquals(90, firstNote);
    }

    @Test
    void handlesTheOneByteEventKinds() throws Exception {
        // Program change and channel pressure take a single data byte, unlike
        // everything else, and reading two would desync the whole stream.
        Sequence sequence = parse(XmiToMidi.convert(xmi(
                (byte) 0xC0, (byte) 42,
                (byte) 0x90, (byte) 60, (byte) 100, (byte) 4,
                (byte) 0xFF, (byte) 0x2F, (byte) 0x00)));

        boolean sawProgramChange = false;
        Track track = sequence.getTracks()[0];
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof ShortMessage message
                    && message.getCommand() == ShortMessage.PROGRAM_CHANGE) {
                sawProgramChange = true;
                assertEquals(42, message.getData1());
            }
        }
        assertTrue(sawProgramChange, "the program change was lost");
    }

    @Test
    void aTempoEventSetsThePlaybackRate() throws Exception {
        // 0x07A120 is 500000 microseconds per quarter note, the MIDI default.
        Sequence withTempo = parse(XmiToMidi.convert(xmi(
                (byte) 0xFF, (byte) 0x51, (byte) 0x03, (byte) 0x07, (byte) 0xA1, (byte) 0x20,
                (byte) 0x90, (byte) 60, (byte) 100, (byte) 120,
                (byte) 0xFF, (byte) 0x2F, (byte) 0x00)));

        // 120 XMI ticks at 360 MIDI ticks a second is a third of a second.
        // Getting the tempo scaling wrong shows up here as a factor of three.
        assertEquals(1_000_000L, withTempo.getMicrosecondLength(), 60_000L);
    }

    @Test
    void stopsAtEndOfTrack() throws Exception {
        // Anything after the end marker is not music.
        Sequence sequence = parse(XmiToMidi.convert(xmi(
                (byte) 0x90, (byte) 60, (byte) 100, (byte) 4,
                (byte) 0xFF, (byte) 0x2F, (byte) 0x00,
                (byte) 0x90, (byte) 72, (byte) 100, (byte) 4)));

        int notes = 0;
        Track track = sequence.getTracks()[0];
        for (int i = 0; i < track.size(); i++) {
            if (track.get(i).getMessage() instanceof ShortMessage message
                    && message.getCommand() == ShortMessage.NOTE_ON
                    && message.getData2() > 0) {
                notes++;
            }
        }
        assertEquals(1, notes, "events after the end marker should be ignored");
    }

    @Test
    void rejectsSomethingThatIsNotAnXmi() {
        assertThrows(XmiToMidi.NotXmiException.class,
                () -> XmiToMidi.convert("this is not music".getBytes(StandardCharsets.US_ASCII)));
    }

    @Test
    void aTruncatedFileStillProducesWhatItHad() throws Exception {
        // These come out of a 1995 archive; losing the tail beats losing the
        // track.
        byte[] midi = XmiToMidi.convert(xmi(
                (byte) 0x90, (byte) 60, (byte) 100, (byte) 4,
                (byte) 0x90, (byte) 62));
        assertTrue(parse(midi).getTracks().length > 0);
    }
}
