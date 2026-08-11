package net.chonkbase.chonkcraft.engine.sound;

import javax.sound.midi.MidiMessage;
import javax.sound.midi.Receiver;
import javax.sound.midi.ShortMessage;

/**
 * The music slider, for the half of the soundtrack that is played by a
 * synthesiser rather than mixed as samples.
 *
 * <p>Implements the MIDI half of {@code SetMusicVolume}. Upstream needs only the one
 * line, {@code Mix_VolumeMusic(MusicVolume)}, because SDL_mixer sits under both
 * of its music backends; on Windows, where MIDI is handed to a separate
 * process, it forwards the same number down a pipe as a value clamped to 127 --
 * {@code External_Volume} at {@code :225-235}, fed from
 * {@code std::min(MusicVolume, 127)} at {@code :176-179}. That clamp is the
 * tell: what goes to a MIDI player is a controller value.
 *
 * <p>This implementation has no such shared layer. Red book music is samples and goes
 * through {@code AudioMixer}'s music bus; the eighteen XMI tracks are MIDI and
 * go to {@code MidiSystem}'s sequencer and the JDK's own synthesiser, which the
 * mixer never sees. So the music slider reached one backend and not the other,
 * and a player who turned the music down heard nothing change -- which is
 * exactly what was reported: "the music volume control has no effect".
 *
 * <p>This sits between the sequencer and the synthesiser and scales channel
 * volume, controller 7, on its way past. Interception rather than a single
 * controller message sent once, because the tracks set their own volume
 * constantly: the human briefing theme sends 140 volume changes in fifty-two
 * seconds and Human Battle 1 sends 514, so anything set at the start is
 * overwritten within a bar. What the track asked for is remembered per channel
 * and the slider multiplies it.
 *
 * <p>The multiplication is a square root, and that is not arbitrary. The
 * sample mixer's bus gain is stated in decibels and this implementation converts a
 * slider at {@code v} to {@code 20*log10(v)}, so amplitude follows the slider
 * exactly. A MIDI channel volume does not: the MMA's curve, which the JDK's
 * Gervill implements as a concave transform of -960 centibels
 * ({@code SoftPerformer}'s default connection block for {@code midi_cc 7}),
 * attenuates by {@code 40*log10(cc/127)} decibels, so amplitude follows the
 * <em>square</em> of the controller. Scaling the controller by the square root
 * of the slider makes the two agree: the change in level is
 * {@code 20*log10(v)} decibels on both paths, so a player at half volume hears
 * the same six decibels down whichever backend is playing and hears no jump
 * when the setting is switched under them.
 */
public final class MidiVolume implements Receiver {

    /** Controller 7, channel volume. What a slider moves. */
    public static final int CHANNEL_VOLUME = 7;

    /** Controller 121, reset all controllers. */
    private static final int RESET_ALL_CONTROLLERS = 121;

    /** How many channels a MIDI stream has. */
    public static final int CHANNELS = 16;

    /** The largest a controller value can be. */
    public static final int MAX_CONTROLLER = 127;

    /**
     * What a channel's volume is before anything sets it.
     *
     * <p>100, from General MIDI's own reset state, and the value Gervill puts
     * back at {@code SoftChannel:1458}. It matters because a track that never
     * sends a volume of its own must still follow the slider, and eight of the
     * eighteen tracks send fewer than one volume change per channel.
     */
    public static final int DEFAULT_VOLUME = 100;

    private final Receiver downstream;

    /** What the track last asked for on each channel, before scaling. */
    private final int[] asked = new int[CHANNELS];

    private volatile float volume = 1f;

    public MidiVolume(Receiver downstream) {
        this.downstream = downstream;
        java.util.Arrays.fill(asked, DEFAULT_VOLUME);
    }

    /**
     * The controller value a channel would be sent, given what the track asked
     * for and where the slider is.
     *
     * <p>Rounded rather than truncated, so a slider fully up is a pass-through:
     * {@code 117 * sqrt(1)} has to come back as 117 and not 116, or every track
     * would lose a fraction of a decibel for nothing.
     */
    public static int scaled(int askedFor, float volume) {
        if (volume <= 0f) {
            return 0;
        }
        int clamped = Math.max(0, Math.min(MAX_CONTROLLER, askedFor));
        float scale = (float) Math.sqrt(Math.min(1f, volume));
        return Math.max(0, Math.min(MAX_CONTROLLER, Math.round(clamped * scale)));
    }

    /**
     * Moves the slider, and tells the synthesiser at once.
     *
     * <p>At once rather than at the next volume event in the track, because
     * the next one can be a minute away and a player who has just dragged the
     * slider is listening for the change now.
     *
     * <p>Called from the interface thread while {@link #send} is being called
     * from the sequencer's, so the two write {@code asked} at the same time.
     * That is left unsynchronised on purpose: the worst a lost write can do is
     * leave one channel a fraction of a decibel out until the track's next
     * volume event, and the tracks send hundreds of those. Taking a lock here
     * would put the interface thread in the path of the sequencer's.
     */
    public void setVolume(float wanted) {
        volume = Math.max(0f, Math.min(1f, wanted));
        for (int channel = 0; channel < CHANNELS; channel++) {
            sendVolume(channel);
        }
    }

    /** What a channel is actually sent: what the track asked for, scaled. */
    private int controllerValue(int channel) {
        return scaled(asked[channel], volume);
    }

    /**
     * Puts every channel back to the default and re-applies the slider.
     *
     * <p>Called when a new track starts. Without it the volume a channel was
     * left at by the previous track carries into the next one, which sounds
     * like a track that begins in the wrong balance and then rights itself.
     */
    public void reset() {
        java.util.Arrays.fill(asked, DEFAULT_VOLUME);
        setVolume(volume);
    }

    @Override
    public void send(MidiMessage message, long timeStamp) {
        if (message instanceof ShortMessage shortMessage
                && shortMessage.getCommand() == ShortMessage.CONTROL_CHANGE) {
            int channel = shortMessage.getChannel();
            if (channel >= 0 && channel < CHANNELS) {
                if (shortMessage.getData1() == CHANNEL_VOLUME) {
                    asked[channel] = shortMessage.getData2();
                    sendVolume(channel);
                    return;
                }
                if (shortMessage.getData1() == RESET_ALL_CONTROLLERS) {
                    // The sequencer sends this on every stop. A synthesiser
                    // that obeys it to the letter puts channel volume back to
                    // 100 and the slider is lost; Gervill happens not to, which
                    // would have made this a bug that only bit on somebody
                    // else's machine.
                    forward(message, timeStamp);
                    asked[channel] = DEFAULT_VOLUME;
                    sendVolume(channel);
                    return;
                }
            }
        }
        forward(message, timeStamp);
    }

    private void sendVolume(int channel) {
        try {
            ShortMessage message = new ShortMessage(ShortMessage.CONTROL_CHANGE, channel,
                    CHANNEL_VOLUME, controllerValue(channel));
            forward(message, -1L);
        } catch (javax.sound.midi.InvalidMidiDataException e) {
            // The arguments are all clamped above, so this cannot happen; a
            // synthesiser that refuses one message is not worth failing over.
        }
    }

    private void forward(MidiMessage message, long timeStamp) {
        if (downstream != null) {
            downstream.send(message, timeStamp);
        }
    }

    @Override
    public void close() {
        if (downstream != null) {
            downstream.close();
        }
    }
}
