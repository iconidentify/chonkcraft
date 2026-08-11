package net.chonkbase.chonkcraft.data.music;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Converts Warcraft II's XMI music into standard MIDI.
 *
 * <p>Implements {@code TranscodeXmiToMid}, which ChonkCraft vendors from Peter
 * Cawley's CorsixTH under the MIT licence. The algorithm is his; the Java is
 * this implementation's.
 *
 * <p>XMI is AIL's format and differs from MIDI in three ways that matter:
 *
 * <ul>
 *   <li>Delays are stored as a run of bytes below 0x80, each counting one
 *       tick, rather than as a single variable-length quantity. Three MIDI
 *       ticks to one XMI tick is the ratio the format assumes.
 *   <li>A note-on carries its own duration, so there are no note-off events
 *       in the stream. The converter has to synthesise one for every note and
 *       place it at the right time, which is why events are collected and
 *       sorted rather than streamed straight through.
 *   <li>Tempo is expressed against a fixed 120 beats per minute, so the
 *       division has to be recomputed from whatever tempo event turns up.
 * </ul>
 */
public final class XmiToMidi {

    /** MIDI ticks per XMI tick. */
    private static final int TICK_RATIO = 3;

    /** The tempo XMI assumes, in microseconds per quarter note. */
    private static final int DEFAULT_TEMPO = 500_000;

    private XmiToMidi() {
    }

    /** Thrown when the bytes are not an XMI this can read. */
    public static final class NotXmiException extends RuntimeException {
        NotXmiException(String message) {
            super(message);
        }
    }

    /** One MIDI event, with the time it happens. */
    private static final class Event {
        int time;
        int status;
        int data;
        byte[] extra = new byte[0];
        /** Preserves the order two events at the same time were read in. */
        int sequence;
    }

    /**
     * Converts one XMI file to a single-track MIDI file.
     *
     * @param xmi the whole XMI, as it comes out of the archive
     */
    public static byte[] convert(byte[] xmi) {
        int cursor = scanTo(xmi, "EVNT");
        if (cursor < 0) {
            throw new NotXmiException("no EVNT chunk: not an XMI file");
        }
        // Past the tag and its length.
        cursor += 8;

        List<Event> events = new ArrayList<>();
        int time = 0;
        int tempo = DEFAULT_TEMPO;
        boolean tempoSet = false;
        int sequence = 0;

        while (cursor < xmi.length) {
            // A run of bytes below 0x80 is the delay before the next event.
            int status;
            while (true) {
                if (cursor >= xmi.length) {
                    return write(events, tempo);
                }
                status = xmi[cursor++] & 0xFF;
                if ((status & 0x80) != 0) {
                    break;
                }
                time += status * TICK_RATIO;
            }

            Event event = new Event();
            event.time = time;
            event.status = status;
            event.sequence = sequence++;

            switch (status & 0xF0) {
                case 0xC0, 0xD0 -> {
                    // Program change and channel pressure: one data byte.
                    if (cursor >= xmi.length) {
                        return write(events, tempo);
                    }
                    event.data = xmi[cursor++] & 0xFF;
                    events.add(event);
                }
                case 0x80, 0xA0, 0xB0, 0xE0 -> {
                    if (cursor + 1 >= xmi.length) {
                        return write(events, tempo);
                    }
                    event.data = xmi[cursor++] & 0xFF;
                    event.extra = new byte[] {xmi[cursor++]};
                    events.add(event);
                }
                case 0x90 -> {
                    // A note-on carries its duration, so the matching note-off
                    // has to be invented and placed. This is the whole reason
                    // events are collected and sorted rather than streamed.
                    if (cursor + 1 >= xmi.length) {
                        return write(events, tempo);
                    }
                    int note = xmi[cursor++] & 0xFF;
                    int velocity = xmi[cursor++] & 0xFF;
                    event.data = note;
                    event.extra = new byte[] {(byte) velocity};
                    events.add(event);

                    int[] read = readVariableLength(xmi, cursor);
                    cursor = read[1];

                    Event off = new Event();
                    off.time = time + read[0] * TICK_RATIO;
                    off.status = status;
                    off.data = note;
                    // Velocity zero is a note-off, which every sequencer
                    // understands and which keeps the running status intact.
                    off.extra = new byte[] {0};
                    off.sequence = sequence++;
                    events.add(off);
                }
                case 0xF0 -> {
                    if (status == 0xFF) {
                        if (cursor >= xmi.length) {
                            return write(events, tempo);
                        }
                        int meta = xmi[cursor++] & 0xFF;
                        int[] read = readVariableLength(xmi, cursor);
                        int length = read[0];
                        cursor = read[1];
                        if (cursor + length > xmi.length) {
                            return write(events, tempo);
                        }

                        if (meta == 0x2F) {
                            // End of track.
                            return write(events, tempo);
                        }
                        if (meta == 0x51 && length >= 3 && !tempoSet) {
                            // Kept as written. The scaling that makes XMI's
                            // clock line up with MIDI's goes into the header's
                            // division, not into the tempo: applying it to
                            // both counts it twice and every track plays at a
                            // third of its speed.
                            tempo = ((xmi[cursor] & 0xFF) << 16)
                                    | ((xmi[cursor + 1] & 0xFF) << 8)
                                    | (xmi[cursor + 2] & 0xFF);
                            tempoSet = true;
                        }
                        cursor += length;
                        // Meta events other than tempo and end are dropped:
                        // they carry text and cue points the game never reads.
                    } else {
                        // System exclusive: skip its declared length.
                        int[] read = readVariableLength(xmi, cursor);
                        cursor = read[1] + read[0];
                    }
                }
                default -> {
                    // An unknown status byte means the stream has desynced;
                    // stop rather than emit noise.
                    return write(events, tempo);
                }
            }
        }
        return write(events, tempo);
    }

    /** Assembles the collected events into a MIDI file. */
    private static byte[] write(List<Event> events, int tempo) {
        // Stable by time, then by the order read, so a note-off never lands
        // before the note-on it belongs to.
        events.sort(Comparator.<Event>comparingInt(e -> e.time).thenComparingInt(e -> e.sequence));

        ByteArrayOutputStream track = new ByteArrayOutputStream();
        writeVariableLength(track, 0);
        track.write(0xFF);
        track.write(0x51);
        track.write(0x03);
        track.write((tempo >> 16) & 0xFF);
        track.write((tempo >> 8) & 0xFF);
        track.write(tempo & 0xFF);

        int previous = 0;
        for (Event event : events) {
            writeVariableLength(track, event.time - previous);
            previous = event.time;
            track.write(event.status);
            track.write(event.data);
            track.writeBytes(event.extra);
        }
        // End of track.
        writeVariableLength(track, 0);
        track.write(0xFF);
        track.write(0x2F);
        track.write(0x00);

        byte[] trackBytes = track.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes("MThd".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeInt(out, 6);
        writeShort(out, 0);   // format 0: one track
        writeShort(out, 1);   // one track
        // The division carries the whole of the XMI-to-MIDI clock conversion:
        // the file's own tempo, times the tick ratio squared, over the
        // constant AIL's clock implies. The tempo event below stays as the
        // file wrote it, because scaling both would count the conversion twice
        // and play every track at a third speed.
        writeShort(out, (tempo * TICK_RATIO * TICK_RATIO) / 25_000);
        out.writeBytes("MTrk".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        writeInt(out, trackBytes.length);
        out.writeBytes(trackBytes);
        return out.toByteArray();
    }

    /** Finds a four-character chunk tag, or {@code -1}. */
    private static int scanTo(byte[] data, String tag) {
        byte[] wanted = tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + wanted.length <= data.length; i++) {
            for (int k = 0; k < wanted.length; k++) {
                if (data[i + k] != wanted[k]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    /**
     * Reads a MIDI variable-length quantity.
     *
     * @return the value and the position after it
     */
    private static int[] readVariableLength(byte[] data, int offset) {
        int value = 0;
        int cursor = offset;
        while (cursor < data.length) {
            int b = data[cursor++] & 0xFF;
            value = (value << 7) | (b & 0x7F);
            if ((b & 0x80) == 0) {
                break;
            }
        }
        return new int[] {value, cursor};
    }

    private static void writeVariableLength(ByteArrayOutputStream out, int value) {
        int amount = Math.max(0, value);
        int buffer = amount & 0x7F;
        int shifted = amount >> 7;
        // Build the continuation chain back to front.
        java.util.Deque<Integer> bytes = new java.util.ArrayDeque<>();
        bytes.push(buffer);
        while (shifted > 0) {
            bytes.push((shifted & 0x7F) | 0x80);
            shifted >>= 7;
        }
        while (!bytes.isEmpty()) {
            out.write(bytes.pop());
        }
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static void writeShort(ByteArrayOutputStream out, int value) {
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
