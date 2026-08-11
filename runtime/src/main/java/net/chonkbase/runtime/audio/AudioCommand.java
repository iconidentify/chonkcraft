package net.chonkbase.runtime.audio;

/**
 * Commands accepted by {@link AudioMixer}. All commands are immutable and
 * applied in queue order at the beginning of a render block.
 */
public sealed interface AudioCommand
        permits AudioCommand.Play,
                AudioCommand.StreamPlay,
                AudioCommand.Replace,
                AudioCommand.Stop,
                AudioCommand.FadeOut,
                AudioCommand.StopAll,
                AudioCommand.SetBusGain,
                AudioCommand.SetBusMuted {

    sealed interface VoiceStart
            permits Play, StreamPlay {
        long voiceId();

        AudioBus bus();

        float gainDb();

        float pan();

        int priority();
    }

    record Play(
            long voiceId,
            PcmClip clip,
            AudioBus bus,
            PcmLoopRegion loopRegion,
            float gainDb,
            float pan,
            int priority)
            implements AudioCommand, VoiceStart {
        public Play(
                long voiceId,
                PcmClip clip,
                AudioBus bus,
                boolean looping,
                float gainDb,
                float pan,
                int priority) {
            this(
                    voiceId,
                    clip,
                    bus,
                    looping && clip != null
                            ? PcmLoopRegion.fullClip(clip)
                            : null,
                    gainDb,
                    pan,
                    priority);
        }

        public Play {
            if (voiceId <= 0L) {
                throw new IllegalArgumentException("voiceId must be positive");
            }
            if (clip == null) {
                throw new IllegalArgumentException("clip must not be null");
            }
            if (bus == null || !bus.acceptsVoices()) {
                throw new IllegalArgumentException("voice bus must be a non-master bus");
            }
            if (loopRegion != null) {
                loopRegion.requireWithin(clip);
            }
            AudioMath.requireGainDb(gainDb);
            if (!Float.isFinite(pan) || pan < -1.0f || pan > 1.0f) {
                throw new IllegalArgumentException("pan must be finite and within [-1, 1]");
            }
        }

        public boolean looping() {
            return loopRegion != null;
        }
    }

    /**
     * Starts one pre-primed background stream. Stream loops are resolved by
     * the producer so the mixer consumes one monotonically increasing
     * presentation timeline.
     */
    record StreamPlay(
            long voiceId,
            PcmStream stream,
            AudioBus bus,
            float gainDb,
            float pan,
            int priority)
            implements AudioCommand, VoiceStart {
        public StreamPlay {
            if (voiceId <= 0L) {
                throw new IllegalArgumentException(
                        "voiceId must be positive");
            }
            if (stream == null) {
                throw new IllegalArgumentException(
                        "stream must not be null");
            }
            if (!stream.claimForPlayback()) {
                throw new IllegalStateException(
                        "stream must be ready and may be played only once");
            }
            if (bus == null || !bus.acceptsVoices()) {
                stream.releaseAsync();
                throw new IllegalArgumentException(
                        "voice bus must be a non-master bus");
            }
            try {
                AudioMath.requireGainDb(gainDb);
                if (!Float.isFinite(pan)
                        || pan < -1.0f
                        || pan > 1.0f) {
                    throw new IllegalArgumentException(
                            "pan must be finite and within [-1, 1]");
                }
            } catch (RuntimeException invalid) {
                stream.releaseAsync();
                throw invalid;
            }
        }
    }

    /**
     * Atomically starts {@code incoming} while retiring
     * {@code outgoingVoiceId}. Both envelopes use the same exact frame count.
     */
    record Replace(
            long outgoingVoiceId,
            VoiceStart incoming,
            int transitionFrames)
            implements AudioCommand {
        public Replace {
            if (incoming == null) {
                throw new IllegalArgumentException("incoming must not be null");
            }
            try {
                if (outgoingVoiceId <= 0L) {
                    throw new IllegalArgumentException(
                            "outgoingVoiceId must be positive");
                }
                if (incoming.voiceId() == outgoingVoiceId) {
                    throw new IllegalArgumentException(
                            "incoming voice must differ from outgoing voice");
                }
                requireFadeFrames(
                        transitionFrames, "transitionFrames");
                if (transitionFrames == 0) {
                    throw new IllegalArgumentException(
                            "transitionFrames must be at least two");
                }
            } catch (RuntimeException invalid) {
                if (incoming
                        instanceof StreamPlay streamPlay) {
                    streamPlay.stream().releaseAsync();
                }
                throw invalid;
            }
        }
    }

    record Stop(long voiceId) implements AudioCommand {
        public Stop {
            if (voiceId <= 0L) {
                throw new IllegalArgumentException("voiceId must be positive");
            }
        }
    }

    /** Retires a voice through an exact linear envelope. */
    record FadeOut(long voiceId, int fadeFrames) implements AudioCommand {
        public FadeOut {
            if (voiceId <= 0L) {
                throw new IllegalArgumentException("voiceId must be positive");
            }
            requireFadeFrames(fadeFrames, "fadeFrames");
            if (fadeFrames == 0) {
                throw new IllegalArgumentException(
                        "fadeFrames must be at least two");
            }
        }
    }

    record StopAll() implements AudioCommand {}

    record SetBusGain(AudioBus bus, float gainDb, int rampFrames) implements AudioCommand {
        public SetBusGain {
            if (bus == null) {
                throw new IllegalArgumentException("bus must not be null");
            }
            AudioMath.requireGainDb(gainDb);
            if (rampFrames < 0) {
                throw new IllegalArgumentException("rampFrames must not be negative");
            }
        }
    }

    record SetBusMuted(AudioBus bus, boolean muted, int rampFrames) implements AudioCommand {
        public SetBusMuted {
            if (bus == null) {
                throw new IllegalArgumentException("bus must not be null");
            }
            if (rampFrames < 0) {
                throw new IllegalArgumentException("rampFrames must not be negative");
            }
        }
    }

    private static void requireFadeFrames(int frames, String name) {
        if (frames < 0) {
            throw new IllegalArgumentException(name + " must not be negative");
        }
        if (frames == 1) {
            throw new IllegalArgumentException(
                    name + " must be zero or at least two");
        }
        if (frames > AudioMixer.MAX_VOICE_FADE_FRAMES) {
            throw new IllegalArgumentException(
                    name + " exceeds the two-second runtime limit");
        }
    }
}
