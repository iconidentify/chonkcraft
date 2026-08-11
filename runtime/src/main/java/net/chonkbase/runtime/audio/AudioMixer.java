package net.chonkbase.runtime.audio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Deterministic 48 kHz stereo mixer core.
 *
 * <p>Producers submit immutable commands without waiting for queue space. One
 * owner thread calls {@link #render(float[], int)}. Device ownership, decode,
 * file I/O, and real-time scheduling deliberately remain outside this class.
 */
public final class AudioMixer {
    public static final int SAMPLE_RATE = PcmFormat.GAME_SAMPLE_RATE;
    public static final int OUTPUT_CHANNELS = 2;
    public static final int DEFAULT_COMMAND_CAPACITY = 256;
    public static final int DEFAULT_EVENT_CAPACITY = 256;
    public static final int DEFAULT_MAX_VOICES = 32;
    public static final float SILENT_DB = AudioMath.SILENT_DB;
    public static final float MAX_GAIN_DB = AudioMath.MAX_GAIN_DB;
    public static final float DEFAULT_LIMITER_CEILING = 0.98f;
    public static final int DEFAULT_LIMITER_RELEASE_FRAMES = SAMPLE_RATE / 20;
    public static final int MAX_VOICE_FADE_FRAMES = SAMPLE_RATE * 2;
    public static final long NO_VOICE = 0L;

    private static final float SQRT_TWO = (float) Math.sqrt(2.0);

    private final int commandCapacity;
    private final int maxVoices;
    private final int maxRetiringVoices;
    private final int physicalVoiceLimit;
    private final ArrayBlockingQueue<AudioCommand> commands;
    private final AudioEventRing events;
    private final AtomicLong voiceIds = new AtomicLong(1L);
    private final List<Voice> voices = new ArrayList<>();
    private final BusState[] buses = new BusState[AudioBus.values().length];
    private final float[] busLeft = new float[AudioBus.values().length];
    private final float[] busRight = new float[AudioBus.values().length];
    private final MasterLimiter limiter;

    private long startSequence;
    private volatile int ownedVoiceCount;
    private volatile int activeVoiceCount;

    public AudioMixer() {
        this(DEFAULT_COMMAND_CAPACITY, DEFAULT_EVENT_CAPACITY, DEFAULT_MAX_VOICES);
    }

    public AudioMixer(int commandCapacity, int eventCapacity, int maxVoices) {
        if (commandCapacity <= 0) {
            throw new IllegalArgumentException("commandCapacity must be positive");
        }
        if (maxVoices <= 0) {
            throw new IllegalArgumentException("maxVoices must be positive");
        }
        this.commandCapacity = commandCapacity;
        this.maxVoices = maxVoices;
        this.maxRetiringVoices = maxVoices;
        this.physicalVoiceLimit =
                maxVoices > Integer.MAX_VALUE / 2
                        ? Integer.MAX_VALUE
                        : maxVoices * 2;
        this.commands = new ArrayBlockingQueue<>(commandCapacity);
        this.events = new AudioEventRing(eventCapacity);
        this.limiter = new MasterLimiter(DEFAULT_LIMITER_CEILING, DEFAULT_LIMITER_RELEASE_FRAMES);
        for (int i = 0; i < buses.length; i++) {
            buses[i] = new BusState();
        }
    }

    public AudioEventRing events() {
        return events;
    }

    /**
     * Current live voice objects, including retiring transition tails and an
     * incoming voice at its exact-silence transition endpoint.
     */
    public int activeVoiceCount() {
        return activeVoiceCount;
    }

    /** Current non-retiring voices that own logical mixer slots. */
    public int logicalVoiceCount() {
        return ownedVoiceCount;
    }

    /**
     * Maximum simultaneously owned voices. Atomic replacements retain this
     * logical bound while permitting bounded outgoing fade tails.
     */
    public int maxVoices() {
        return maxVoices;
    }

    /**
     * Maximum live voice objects, including retiring tails. The mixer permits
     * at most one retiring tail per logical voice slot.
     */
    public int physicalVoiceLimit() {
        return physicalVoiceLimit;
    }

    public int queuedCommandCount() {
        return commands.size();
    }

    public boolean submit(AudioCommand command) {
        if (command == null) {
            throw new IllegalArgumentException("command must not be null");
        }
        boolean accepted = commands.offer(command);
        if (!accepted) {
            long voiceId = switch (command) {
                case AudioCommand.Play play -> play.voiceId();
                case AudioCommand.StreamPlay play ->
                    play.voiceId();
                case AudioCommand.Replace replace ->
                    replace.incoming().voiceId();
                default -> NO_VOICE;
            };
            AudioBus bus = busOf(command);
            events.publish(AudioEventType.COMMAND_REJECTED, voiceId, bus);
            releaseUnappliedStream(command);
        }
        return accepted;
    }

    public long play(
            PcmClip clip, AudioBus bus, boolean looping, float gainDb, float pan, int priority) {
        return play(
                clip,
                bus,
                looping && clip != null
                        ? PcmLoopRegion.fullClip(clip)
                        : null,
                gainDb,
                pan,
                priority);
    }

    /**
     * Starts a one-shot when {@code loopRegion} is {@code null}, or a
     * sample-accurate loop with the supplied prevalidated region.
     */
    public long play(
            PcmClip clip,
            AudioBus bus,
            PcmLoopRegion loopRegion,
            float gainDb,
            float pan,
            int priority) {
        long voiceId = nextVoiceId();
        AudioCommand.Play play =
                new AudioCommand.Play(
                        voiceId,
                        clip,
                        bus,
                        loopRegion,
                        gainDb,
                        pan,
                        priority);
        return submit(play) ? voiceId : NO_VOICE;
    }

    /**
     * Starts one pre-primed, single-use background stream. Loop behavior is
     * owned by the stream producer; the render thread sees a monotonically
     * increasing source timeline.
     */
    public long play(
            PcmStream stream,
            AudioBus bus,
            float gainDb,
            float pan,
            int priority) {
        long voiceId = nextVoiceId();
        AudioCommand.StreamPlay play =
                new AudioCommand.StreamPlay(
                        voiceId,
                        stream,
                        bus,
                        gainDb,
                        pan,
                        priority);
        return submit(play) ? voiceId : NO_VOICE;
    }

    /**
     * Atomically replaces one voice with another through complementary linear
     * envelopes. The outgoing and incoming timelines both advance during the
     * transition; one bounded queue slot therefore cannot accept only half of
     * a state change.
     *
     * <p>If the outgoing voice has already disappeared when the command is
     * applied, the incoming voice fades up from silence instead.
     */
    public long replace(
            long outgoingVoiceId,
            PcmClip clip,
            AudioBus bus,
            PcmLoopRegion loopRegion,
            float gainDb,
            float pan,
            int priority,
            int transitionFrames) {
        long voiceId = nextVoiceId();
        AudioCommand.Play incoming =
                new AudioCommand.Play(
                        voiceId,
                        clip,
                        bus,
                        loopRegion,
                        gainDb,
                        pan,
                        priority);
        AudioCommand.Replace replace =
                new AudioCommand.Replace(
                        outgoingVoiceId, incoming, transitionFrames);
        return submit(replace) ? voiceId : NO_VOICE;
    }

    /**
     * Atomically replaces one voice with a pre-primed stream through the same
     * bounded, endpoint-exact tail policy as resident clips.
     */
    public long replace(
            long outgoingVoiceId,
            PcmStream stream,
            AudioBus bus,
            float gainDb,
            float pan,
            int priority,
            int transitionFrames) {
        long voiceId = nextVoiceId();
        AudioCommand.StreamPlay incoming =
                new AudioCommand.StreamPlay(
                        voiceId,
                        stream,
                        bus,
                        gainDb,
                        pan,
                        priority);
        AudioCommand.Replace replace =
                new AudioCommand.Replace(
                        outgoingVoiceId,
                        incoming,
                        transitionFrames);
        return submit(replace) ? voiceId : NO_VOICE;
    }

    public boolean stop(long voiceId) {
        return submit(new AudioCommand.Stop(voiceId));
    }

    public boolean fadeOut(long voiceId, int fadeFrames) {
        return submit(new AudioCommand.FadeOut(voiceId, fadeFrames));
    }

    public boolean stopAll() {
        return submit(new AudioCommand.StopAll());
    }

    public boolean setBusGainDb(AudioBus bus, float gainDb, int rampFrames) {
        return submit(new AudioCommand.SetBusGain(bus, gainDb, rampFrames));
    }

    public boolean setBusMuted(AudioBus bus, boolean muted, int rampFrames) {
        return submit(new AudioCommand.SetBusMuted(bus, muted, rampFrames));
    }

    /**
     * Renders exactly {@code frameCount} stereo frames into the beginning of
     * {@code output}. This method performs no file or device I/O.
     */
    public void render(float[] output, int frameCount) {
        if (frameCount < 0) {
            throw new IllegalArgumentException("frameCount must not be negative");
        }
        if (output == null || output.length < frameCount * OUTPUT_CHANNELS) {
            throw new IllegalArgumentException("output is too small for requested frames");
        }
        drainCommands();
        Arrays.fill(output, 0, frameCount * OUTPUT_CHANNELS, 0.0f);

        for (int frame = 0; frame < frameCount; frame++) {
            Arrays.fill(busLeft, 0.0f);
            Arrays.fill(busRight, 0.0f);
            mixVoicesForFrame();

            float left = 0.0f;
            float right = 0.0f;
            for (AudioBus bus : AudioBus.values()) {
                if (!bus.acceptsVoices()) {
                    continue;
                }
                float busGain = buses[bus.ordinal()].nextGain();
                left += busLeft[bus.ordinal()] * busGain;
                right += busRight[bus.ordinal()] * busGain;
            }

            float masterGain = buses[AudioBus.MASTER.ordinal()].nextGain();
            long packed = limiter.limit(left * masterGain, right * masterGain);
            output[frame * 2] = Float.intBitsToFloat((int) (packed >>> 32));
            output[(frame * 2) + 1] = Float.intBitsToFloat((int) packed);
        }
    }

    private long nextVoiceId() {
        long voiceId = voiceIds.getAndIncrement();
        if (voiceId <= 0L) {
            throw new IllegalStateException("audio voice id space exhausted");
        }
        return voiceId;
    }

    private void drainCommands() {
        // Bound work even if producers continue to submit during this render.
        for (int i = 0; i < commandCapacity; i++) {
            AudioCommand command = commands.poll();
            if (command == null) {
                return;
            }
            apply(command);
        }
    }

    private void apply(AudioCommand command) {
        switch (command) {
            case AudioCommand.Play play -> startVoice(play);
            case AudioCommand.StreamPlay play ->
                startVoice(play);
            case AudioCommand.Replace replace -> replaceVoice(replace);
            case AudioCommand.Stop stop -> stopVoice(stop.voiceId());
            case AudioCommand.FadeOut fade ->
                fadeOutVoice(fade.voiceId(), fade.fadeFrames());
            case AudioCommand.StopAll ignored -> stopEveryVoice();
            case AudioCommand.SetBusGain gain -> {
                buses[gain.bus().ordinal()].gain.rampTo(AudioMath.dbToLinear(gain.gainDb()), gain.rampFrames());
                events.publish(AudioEventType.BUS_GAIN_CHANGED, NO_VOICE, gain.bus());
            }
            case AudioCommand.SetBusMuted mute -> {
                buses[mute.bus().ordinal()].mute.rampTo(mute.muted() ? 0.0f : 1.0f, mute.rampFrames());
                events.publish(AudioEventType.BUS_MUTE_CHANGED, NO_VOICE, mute.bus());
            }
        }
    }

    private void startVoice(AudioCommand.VoiceStart play) {
        int duplicate = indexOfVoice(play.voiceId());
        int victimIndex = -1;
        if (duplicate >= 0) {
            victimIndex = duplicate;
        }

        if (duplicate < 0 && ownedVoiceCount >= maxVoices) {
            victimIndex = lowestPriorityOldestVoice();
            Voice victim = voices.get(victimIndex);
            if (play.priority() < victim.priority) {
                events.publish(
                        AudioEventType.VOICE_REJECTED,
                        play.voiceId(),
                        play.bus());
                releaseUnstartedStream(play);
                return;
            }
        }

        if (!beginStreamPlayback(play)) {
            events.publish(
                    AudioEventType.VOICE_REJECTED,
                    play.voiceId(),
                    play.bus());
            return;
        }

        if (victimIndex >= 0) {
            Voice victim = voices.remove(victimIndex);
            ownedVoiceCount--;
            activeVoiceCount--;
            releaseVoiceSource(victim);
            events.publish(
                    duplicate >= 0
                            ? AudioEventType.VOICE_STOPPED
                            : AudioEventType.VOICE_STOLEN,
                    victim.id,
                    victim.bus);
        }

        voices.add(new Voice(play, startSequence++));
        ownedVoiceCount++;
        activeVoiceCount++;
        events.publish(AudioEventType.VOICE_STARTED, play.voiceId(), play.bus());
    }

    private void replaceVoice(AudioCommand.Replace replace) {
        int outgoingIndex = indexOfVoice(replace.outgoingVoiceId());
        if (outgoingIndex < 0) {
            startVoiceFadedIn(
                    replace.incoming(), replace.transitionFrames());
            return;
        }

        if (!beginStreamPlayback(replace.incoming())) {
            events.publish(
                    AudioEventType.VOICE_REJECTED,
                    replace.incoming().voiceId(),
                    replace.incoming().bus());
            return;
        }

        Voice outgoing = voices.get(outgoingIndex);
        outgoing.retire(replace.transitionFrames());
        ownedVoiceCount--;
        if (outgoing.envelope.isSilent()) {
            voices.remove(outgoingIndex);
            activeVoiceCount--;
            releaseVoiceSource(outgoing);
            events.publish(
                    AudioEventType.VOICE_STOPPED,
                    outgoing.id,
                    outgoing.bus);
        } else {
            enforceRetiringVoiceLimit();
        }

        Voice incoming = new Voice(
                replace.incoming(),
                startSequence++,
                VoiceEnvelope.fadeIn(replace.transitionFrames()));
        voices.add(incoming);
        ownedVoiceCount++;
        activeVoiceCount++;
        events.publish(
                AudioEventType.VOICE_STARTED,
                replace.incoming().voiceId(),
                replace.incoming().bus());
    }

    private void startVoiceFadedIn(
            AudioCommand.VoiceStart play, int fadeFrames) {
        int victimIndex = -1;
        if (ownedVoiceCount >= maxVoices) {
            victimIndex = lowestPriorityOldestVoice();
            Voice victim = voices.get(victimIndex);
            if (play.priority() < victim.priority) {
                events.publish(
                        AudioEventType.VOICE_REJECTED,
                        play.voiceId(),
                        play.bus());
                releaseUnstartedStream(play);
                return;
            }
        }

        if (!beginStreamPlayback(play)) {
            events.publish(
                    AudioEventType.VOICE_REJECTED,
                    play.voiceId(),
                    play.bus());
            return;
        }

        if (victimIndex >= 0) {
            Voice victim = voices.remove(victimIndex);
            ownedVoiceCount--;
            activeVoiceCount--;
            releaseVoiceSource(victim);
            events.publish(
                    AudioEventType.VOICE_STOLEN, victim.id, victim.bus);
        }

        voices.add(new Voice(
                play, startSequence++, VoiceEnvelope.fadeIn(fadeFrames)));
        ownedVoiceCount++;
        activeVoiceCount++;
        events.publish(
                AudioEventType.VOICE_STARTED, play.voiceId(), play.bus());
    }

    private int lowestPriorityOldestVoice() {
        int victimIndex = -1;
        Voice victim = null;
        for (int i = 0; i < voices.size(); i++) {
            Voice candidate = voices.get(i);
            if (candidate.retiring) {
                continue;
            }
            if (victim == null
                    || candidate.priority < victim.priority
                    || (candidate.priority == victim.priority
                            && candidate.startSequence
                                    < victim.startSequence)) {
                victim = candidate;
                victimIndex = i;
            }
        }
        return victimIndex;
    }

    /**
     * Bounds physical work when state churn creates fades faster than they can
     * finish. This is command-boundary work, never per-frame work. The
     * quietest tail by current effective channel gain is discarded first;
     * exact ties discard the oldest tail.
     */
    private void enforceRetiringVoiceLimit() {
        int retiringCount = 0;
        for (Voice voice : voices) {
            if (voice.retiring) {
                retiringCount++;
            }
        }
        while (retiringCount > maxRetiringVoices) {
            int victimIndex = quietestOldestRetiringVoice();
            Voice victim = voices.remove(victimIndex);
            activeVoiceCount--;
            retiringCount--;
            releaseVoiceSource(victim);
            events.publish(
                    AudioEventType.VOICE_STOPPED,
                    victim.id,
                    victim.bus);
        }
    }

    private int quietestOldestRetiringVoice() {
        int victimIndex = -1;
        Voice victim = null;
        float victimGain = Float.POSITIVE_INFINITY;
        for (int i = 0; i < voices.size(); i++) {
            Voice candidate = voices.get(i);
            if (!candidate.retiring) {
                continue;
            }
            float candidateGain = candidate.currentEffectiveGain();
            if (victim == null
                    || candidateGain < victimGain
                    || (candidateGain == victimGain
                            && candidate.startSequence
                                    < victim.startSequence)) {
                victim = candidate;
                victimGain = candidateGain;
                victimIndex = i;
            }
        }
        if (victimIndex < 0) {
            throw new IllegalStateException(
                    "retiring voice count exceeded its limit without a victim");
        }
        return victimIndex;
    }

    private void stopVoice(long voiceId) {
        int index = indexOfVoice(voiceId);
        if (index < 0) {
            return;
        }
        Voice stopped = voices.remove(index);
        if (!stopped.retiring) {
            ownedVoiceCount--;
            activeVoiceCount--;
        }
        releaseVoiceSource(stopped);
        events.publish(AudioEventType.VOICE_STOPPED, stopped.id, stopped.bus);
    }

    private void fadeOutVoice(long voiceId, int fadeFrames) {
        int index = indexOfVoice(voiceId);
        if (index < 0) {
            return;
        }
        Voice voice = voices.get(index);
        voice.retire(fadeFrames);
        ownedVoiceCount--;
        if (voice.envelope.isSilent()) {
            voices.remove(index);
            activeVoiceCount--;
            releaseVoiceSource(voice);
            events.publish(
                    AudioEventType.VOICE_STOPPED, voice.id, voice.bus);
        } else {
            enforceRetiringVoiceLimit();
        }
    }

    private void stopEveryVoice() {
        for (Voice voice : voices) {
            releaseVoiceSource(voice);
        }
        voices.clear();
        ownedVoiceCount = 0;
        activeVoiceCount = 0;
        events.publish(AudioEventType.ALL_VOICES_STOPPED, NO_VOICE, AudioBus.MASTER);
    }

    private int indexOfVoice(long voiceId) {
        for (int i = 0; i < voices.size(); i++) {
            Voice voice = voices.get(i);
            if (!voice.retiring && voice.id == voiceId) {
                return i;
            }
        }
        return -1;
    }

    private void mixVoicesForFrame() {
        for (int i = 0; i < voices.size(); ) {
            Voice voice = voices.get(i);
            long packedFrame = voice.packedFrame();
            boolean sourceAvailable =
                    packedFrame != PcmStream.FRAME_UNAVAILABLE;
            float sourceLeft =
                    sourceAvailable
                            ? Float.intBitsToFloat(
                                    (int) (packedFrame >>> 32))
                            : 0.0f;
            float sourceRight =
                    sourceAvailable
                            ? Float.intBitsToFloat((int) packedFrame)
                            : 0.0f;
            if (voice.stream != null) {
                if (!sourceAvailable
                        && voice.stream.terminallyUnavailable(
                                voice.frame)) {
                    voices.remove(i);
                    activeVoiceCount--;
                    if (!voice.retiring) {
                        ownedVoiceCount--;
                    }
                    releaseVoiceSource(voice);
                    events.publish(
                            AudioEventType.STREAM_FAILED,
                            voice.id,
                            voice.bus);
                    events.publish(
                            AudioEventType.VOICE_STOPPED,
                            voice.id,
                            voice.bus);
                    continue;
                }
                if (!sourceAvailable && !voice.streamUnderrunning) {
                    voice.streamUnderrunning = true;
                    events.publish(
                            AudioEventType.STREAM_UNDERRUN,
                            voice.id,
                            voice.bus);
                } else if (sourceAvailable
                        && voice.streamUnderrunning) {
                    voice.streamUnderrunning = false;
                    events.publish(
                            AudioEventType.STREAM_RECOVERED,
                            voice.id,
                            voice.bus);
                }
                voice.stream.consumeThrough(voice.frame + 1L);
            }
            float envelopeGain = voice.envelope.next();
            int busIndex = voice.bus.ordinal();
            busLeft[busIndex] +=
                    sourceLeft * voice.leftGain * envelopeGain;
            busRight[busIndex] +=
                    sourceRight * voice.rightGain * envelopeGain;

            voice.frame++;
            if (voice.retiring && voice.envelope.finishedAtSilence()) {
                voices.remove(i);
                activeVoiceCount--;
                releaseVoiceSource(voice);
                events.publish(
                        AudioEventType.VOICE_STOPPED, voice.id, voice.bus);
                continue;
            }
            if (voice.clip != null
                    && voice.loopRegion != null
                    && voice.frame >= voice.loopRegion.endFrameExclusive()) {
                // The loop head was already heard inside the overlap. Continue
                // immediately after it so no samples are duplicated.
                voice.frame =
                        voice.loopRegion.startFrame()
                                + voice.loopRegion.crossfadeFrames();
                i++;
                continue;
            }
            if (voice.frame < voice.sourceFrameCount()) {
                i++;
                continue;
            }
            voices.remove(i);
            activeVoiceCount--;
            if (!voice.retiring) {
                ownedVoiceCount--;
            }
            releaseVoiceSource(voice);
            events.publish(
                    voice.retiring
                            ? AudioEventType.VOICE_STOPPED
                            : AudioEventType.VOICE_COMPLETED,
                    voice.id,
                    voice.bus);
        }
    }

    private static AudioBus busOf(AudioCommand command) {
        return switch (command) {
            case AudioCommand.Play play -> play.bus();
            case AudioCommand.StreamPlay play -> play.bus();
            case AudioCommand.Replace replace ->
                replace.incoming().bus();
            case AudioCommand.SetBusGain gain -> gain.bus();
            case AudioCommand.SetBusMuted mute -> mute.bus();
            case AudioCommand.Stop ignored -> null;
            case AudioCommand.FadeOut ignored -> null;
            case AudioCommand.StopAll ignored -> AudioBus.MASTER;
        };
    }

    private static boolean beginStreamPlayback(
            AudioCommand.VoiceStart start) {
        return !(start instanceof AudioCommand.StreamPlay streamPlay)
                || streamPlay.stream().beginPlayback();
    }

    private static void releaseUnstartedStream(
            AudioCommand.VoiceStart start) {
        if (start instanceof AudioCommand.StreamPlay streamPlay) {
            streamPlay.stream().releaseAsync();
        }
    }

    private static void releaseUnappliedStream(AudioCommand command) {
        switch (command) {
            case AudioCommand.StreamPlay play ->
                play.stream().releaseAsync();
            case AudioCommand.Replace replace ->
                releaseUnstartedStream(replace.incoming());
            default -> {
            }
        }
    }

    private static void releaseVoiceSource(Voice voice) {
        if (voice.stream != null) {
            voice.stream.releaseAsync();
        }
    }

    private static final class Voice {
        final long id;
        final PcmClip clip;
        final PcmStream stream;
        final AudioBus bus;
        final PcmLoopRegion loopRegion;
        final int priority;
        final long startSequence;
        final float leftGain;
        final float rightGain;
        final VoiceEnvelope envelope;
        boolean retiring;
        boolean streamUnderrunning;
        long frame;

        Voice(
                AudioCommand.VoiceStart play,
                long startSequence) {
            this(play, startSequence, VoiceEnvelope.fullGain());
        }

        Voice(
                AudioCommand.VoiceStart play,
                long startSequence,
                VoiceEnvelope envelope) {
            this.id = play.voiceId();
            this.clip =
                    play instanceof AudioCommand.Play resident
                            ? resident.clip()
                            : null;
            this.stream =
                    play instanceof AudioCommand.StreamPlay streamed
                            ? streamed.stream()
                            : null;
            this.bus = play.bus();
            this.loopRegion =
                    play instanceof AudioCommand.Play resident
                            ? resident.loopRegion()
                            : null;
            this.priority = play.priority();
            this.startSequence = startSequence;
            this.envelope = envelope;

            float angle = (play.pan() + 1.0f) * ((float) Math.PI / 4.0f);
            float sourceGain = AudioMath.dbToLinear(play.gainDb());
            int channels =
                    clip != null ? clip.channels() : stream.channels();
            float stereoCompensation =
                    channels == 1 ? 1.0f : SQRT_TWO;
            this.leftGain = sourceGain * (float) Math.cos(angle) * stereoCompensation;
            this.rightGain = sourceGain * (float) Math.sin(angle) * stereoCompensation;
        }

        void retire(int fadeFrames) {
            retiring = true;
            envelope.rampTo(0.0f, fadeFrames);
        }

        float currentEffectiveGain() {
            return envelope.currentGain()
                    * Math.max(Math.abs(leftGain), Math.abs(rightGain));
        }

        long sourceFrameCount() {
            return clip != null
                    ? clip.frameCount()
                    : stream.playbackFrameCount();
        }

        long packedFrame() {
            if (stream != null) {
                return stream.frameAt(frame);
            }
            int sourceFrame = Math.toIntExact(frame);
            int rightChannel = clip.channels() == 1 ? 0 : 1;
            float left = sample(sourceFrame, 0);
            float right = sample(sourceFrame, rightChannel);
            return ((long) Float.floatToRawIntBits(left) << 32)
                    | (Float.floatToRawIntBits(right)
                            & 0xffff_ffffL);
        }

        float sample(int sourceFrame, int channel) {
            if (loopRegion == null || !loopRegion.crossfades()) {
                return clip.sampleAt(sourceFrame, channel);
            }
            int crossfadeStart =
                    loopRegion.endFrameExclusive()
                            - loopRegion.crossfadeFrames();
            if (sourceFrame < crossfadeStart) {
                return clip.sampleAt(sourceFrame, channel);
            }

            int crossfadeIndex = sourceFrame - crossfadeStart;
            float mix = (float) crossfadeIndex
                    / (float) (loopRegion.crossfadeFrames() - 1);
            float tail = clip.sampleAt(sourceFrame, channel);
            float head = clip.sampleAt(
                    loopRegion.startFrame() + crossfadeIndex, channel);
            // Linear complementary gains keep correlated material from
            // overshooting while making both splice boundaries continuous.
            return tail + ((head - tail) * mix);
        }
    }

    /**
     * Endpoint-exact linear voice envelope. Unlike the bus smoother, the
     * current value is rendered before advancing so an N-frame transition
     * audibly includes both its zero and unity endpoints.
     */
    private static final class VoiceEnvelope {
        private float current;
        private float target;
        private float step;
        private int remainingFrames;

        private VoiceEnvelope(float initial) {
            current = initial;
            target = initial;
        }

        static VoiceEnvelope fullGain() {
            return new VoiceEnvelope(1.0f);
        }

        static VoiceEnvelope fadeIn(int frames) {
            VoiceEnvelope envelope = new VoiceEnvelope(0.0f);
            envelope.rampTo(1.0f, frames);
            return envelope;
        }

        void rampTo(float nextTarget, int frames) {
            target = nextTarget;
            remainingFrames = frames;
            step = (target - current) / (frames - 1);
        }

        float next() {
            float value = current;
            if (remainingFrames > 0) {
                if (remainingFrames > 1) {
                    current += step;
                } else {
                    current = target;
                }
                remainingFrames--;
            }
            return value;
        }

        boolean finishedAtSilence() {
            return remainingFrames == 0 && current == 0.0f;
        }

        boolean isSilent() {
            return current == 0.0f;
        }

        float currentGain() {
            return current;
        }
    }

    private static final class BusState {
        final SmoothedValue gain = new SmoothedValue(1.0f);
        final SmoothedValue mute = new SmoothedValue(1.0f);

        float nextGain() {
            return gain.next() * mute.next();
        }
    }

    private static final class SmoothedValue {
        private float current;
        private float target;
        private float step;
        private int remainingFrames;

        SmoothedValue(float initial) {
            current = initial;
            target = initial;
        }

        void rampTo(float nextTarget, int frames) {
            target = nextTarget;
            if (frames == 0) {
                current = target;
                step = 0.0f;
                remainingFrames = 0;
                return;
            }
            remainingFrames = frames;
            step = (target - current) / frames;
        }

        float next() {
            if (remainingFrames > 0) {
                current += step;
                remainingFrames--;
                if (remainingFrames == 0) {
                    current = target;
                }
            }
            return current;
        }
    }

    /**
     * Linked-stereo peak limiter. It is exactly unity below the ceiling until
     * an overload occurs, attacks in the same sample, and releases smoothly.
     */
    private static final class MasterLimiter {
        private final float ceiling;
        private final float releaseCoefficient;
        private float gain = 1.0f;

        MasterLimiter(float ceiling, int releaseFrames) {
            if (!(ceiling > 0.0f && ceiling <= 1.0f) || releaseFrames <= 0) {
                throw new IllegalArgumentException("invalid limiter configuration");
            }
            this.ceiling = ceiling;
            this.releaseCoefficient = 1.0f - (float) Math.exp(-1.0 / releaseFrames);
        }

        long limit(float left, float right) {
            float peak = Math.max(Math.abs(left), Math.abs(right));
            float requiredGain = peak > ceiling ? ceiling / peak : 1.0f;
            if (requiredGain < gain) {
                gain = requiredGain;
            } else if (gain < 1.0f) {
                gain += (1.0f - gain) * releaseCoefficient;
                gain = Math.min(1.0f, gain);
            }
            float limitedLeft = left * gain;
            float limitedRight = right * gain;
            return ((long) Float.floatToRawIntBits(limitedLeft) << 32)
                    | (Float.floatToRawIntBits(limitedRight) & 0xffff_ffffL);
        }
    }
}
