package net.chonkbase.runtime.audio;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class AudioMixerTest {
    private static final float EPSILON = 0.000_1f;

    @Test
    void monoPanUsesEqualPowerLaw() {
        float[] hardLeft = renderSingleMono(-1.0f);
        float[] center = renderSingleMono(0.0f);
        float[] hardRight = renderSingleMono(1.0f);

        assertEquals(0.5f, hardLeft[0], EPSILON);
        assertEquals(0.0f, hardLeft[1], EPSILON);
        assertEquals(0.5f / Math.sqrt(2.0), center[0], EPSILON);
        assertEquals(0.5f / Math.sqrt(2.0), center[1], EPSILON);
        assertEquals(0.0f, hardRight[0], EPSILON);
        assertEquals(0.5f, hardRight[1], EPSILON);
    }

    @Test
    void centeredStereoRetainsItsOriginalChannelLevels() {
        AudioMixer mixer = new AudioMixer();
        PcmClip clip = PcmClip.fromFloats("stereo", 2, new float[] {0.25f, -0.5f});
        mixer.play(clip, AudioBus.MUSIC, false, 0.0f, 0.0f, 1);

        float[] output = new float[2];
        mixer.render(output, 1);

        assertEquals(0.25f, output[0], EPSILON);
        assertEquals(-0.5f, output[1], EPSILON);
    }

    @Test
    void busGainAndMuteRampsAreClickFreeAndComposableWithMaster() {
        AudioMixer mixer = new AudioMixer();
        PcmClip constant = monoConstant("constant", 0.5f, 8);
        float minusSixDb = (float) (20.0 * Math.log10(0.5));

        mixer.setBusGainDb(AudioBus.MUSIC, minusSixDb, 4);
        mixer.play(constant, AudioBus.MUSIC, true, 0.0f, -1.0f, 1);
        float[] ramp = new float[8];
        mixer.render(ramp, 4);

        assertArrayEquals(new float[] {0.4375f, 0.0f, 0.375f, 0.0f, 0.3125f, 0.0f, 0.25f, 0.0f}, ramp, EPSILON);

        mixer.setBusMuted(AudioBus.MUSIC, true, 2);
        float[] mute = new float[6];
        mixer.render(mute, 3);
        assertArrayEquals(new float[] {0.125f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f}, mute, EPSILON);

        mixer.setBusMuted(AudioBus.MUSIC, false, 0);
        mixer.setBusGainDb(AudioBus.MASTER, minusSixDb, 0);
        float[] master = new float[2];
        mixer.render(master, 1);
        assertEquals(0.125f, master[0], EPSILON);
    }

    @Test
    void voiceCapRejectsLowerPriorityAndStealsOldestEqualPriority() {
        AudioMixer mixer = new AudioMixer(16, 32, 2);
        PcmClip looping = monoConstant("loop", 0.1f, 2);
        long first = mixer.play(looping, AudioBus.WORLD, true, -20.0f, 0.0f, 1);
        long second = mixer.play(looping, AudioBus.WORLD, true, -20.0f, 0.0f, 1);
        mixer.render(new float[2], 1);

        long rejected = mixer.play(looping, AudioBus.UI, true, -20.0f, 0.0f, 0);
        mixer.render(new float[2], 1);
        assertEquals(2, mixer.activeVoiceCount());
        assertEvent(mixer, AudioEventType.VOICE_REJECTED, rejected);

        long replacement = mixer.play(looping, AudioBus.UI, true, -20.0f, 0.0f, 1);
        mixer.render(new float[2], 1);
        assertEquals(2, mixer.activeVoiceCount());
        assertEvent(mixer, AudioEventType.VOICE_STOLEN, first);
        assertEvent(mixer, AudioEventType.VOICE_STARTED, replacement);
        assertFalse(hasEvent(mixer, AudioEventType.VOICE_STOLEN, second));
    }

    @Test
    void limiterIsTransparentBelowCeilingAndLinkedAboveIt() {
        AudioMixer quietMixer = new AudioMixer();
        quietMixer.play(
                PcmClip.fromFloats("quiet", 2, new float[] {0.25f, -0.5f}),
                AudioBus.MUSIC,
                false,
                0.0f,
                0.0f,
                1);
        float[] quiet = new float[2];
        quietMixer.render(quiet, 1);
        assertEquals(0.25f, quiet[0], EPSILON);
        assertEquals(-0.5f, quiet[1], EPSILON);

        AudioMixer loudMixer = new AudioMixer();
        PcmClip loud = PcmClip.fromFloats("loud", 2, new float[] {0.8f, 0.4f});
        loudMixer.play(loud, AudioBus.MUSIC, false, 0.0f, 0.0f, 1);
        loudMixer.play(loud, AudioBus.WORLD, false, 0.0f, 0.0f, 1);
        float[] limited = new float[2];
        loudMixer.render(limited, 1);

        assertEquals(AudioMixer.DEFAULT_LIMITER_CEILING, limited[0], EPSILON);
        assertEquals(AudioMixer.DEFAULT_LIMITER_CEILING / 2.0f, limited[1], EPSILON);
    }

    @Test
    void oneShotCompletesAndLoopingVoiceWrapsExactly() {
        PcmClip clip = PcmClip.fromFloats("two-frame", 1, new float[] {0.25f, 0.5f});

        AudioMixer oneShot = new AudioMixer();
        long oneShotId = oneShot.play(clip, AudioBus.UI, false, 0.0f, -1.0f, 1);
        float[] completed = new float[6];
        oneShot.render(completed, 3);
        assertArrayEquals(new float[] {0.25f, 0.0f, 0.5f, 0.0f, 0.0f, 0.0f}, completed, EPSILON);
        assertEquals(0, oneShot.activeVoiceCount());
        assertEvent(oneShot, AudioEventType.VOICE_COMPLETED, oneShotId);

        AudioMixer loop = new AudioMixer();
        loop.play(clip, AudioBus.AMBIENCE, true, 0.0f, -1.0f, 1);
        float[] wrapped = new float[10];
        loop.render(wrapped, 5);
        assertArrayEquals(
                new float[] {0.25f, 0.0f, 0.5f, 0.0f, 0.25f, 0.0f, 0.5f, 0.0f, 0.25f, 0.0f},
                wrapped,
                EPSILON);
        assertEquals(1, loop.activeVoiceCount());
    }

    @Test
    void loopRegionCrossfadesTheSeamAndSkipsTheAlreadyMixedHead() {
        PcmClip clip = PcmClip.fromFloats(
                "region",
                1,
                new float[] {
                    0.125f,
                    0.25f,
                    -0.5f,
                    -0.375f,
                    -0.25f,
                    -0.125f,
                    0.0f,
                    0.125f,
                    0.25f,
                    0.375f,
                    0.5f,
                    0.5f,
                    -0.45f // authored tail after loopEnd is never reached
                });
        AudioMixer mixer = new AudioMixer();
        mixer.play(
                clip,
                AudioBus.AMBIENCE,
                new PcmLoopRegion(2, 12, 3),
                0.0f,
                -1.0f,
                1);

        float[] stereo = new float[32];
        mixer.render(stereo, 16);

        assertArrayEquals(
                new float[] {
                    0.125f,
                    0.25f,
                    -0.5f,
                    -0.375f,
                    -0.25f,
                    -0.125f,
                    0.0f,
                    0.125f,
                    0.25f,
                    0.375f,
                    0.0625f,
                    -0.25f,
                    -0.125f,
                    0.0f,
                    0.125f,
                    0.25f
                },
                leftChannel(stereo),
                EPSILON);
        assertTrue(maxAdjacentJump(leftChannel(stereo), 8, 13) < 0.35f);
        assertEquals(1, mixer.activeVoiceCount());
    }

    @Test
    void loopCrossfadeIsBitIdenticalAcrossRenderBlockBoundaries() {
        PcmClip clip = PcmClip.fromFloats(
                "block-invariant",
                2,
                new float[] {
                    0.1f, -0.1f,
                    0.2f, -0.2f,
                    0.3f, -0.3f,
                    0.4f, -0.4f,
                    0.5f, -0.5f,
                    0.6f, -0.6f,
                    0.7f, -0.7f,
                    0.8f, -0.8f,
                    -0.8f, 0.8f,
                    -0.7f, 0.7f,
                    -0.6f, 0.6f,
                    -0.5f, 0.5f
                });
        PcmLoopRegion loop = new PcmLoopRegion(2, 12, 3);

        float[] contiguous = renderRegion(clip, loop, 41);
        float[] divided = new float[contiguous.length];
        AudioMixer dividedMixer = new AudioMixer();
        dividedMixer.play(
                clip, AudioBus.MUSIC, loop, -9.0f, 0.25f, 4);
        int destinationFrame = 0;
        for (int blockFrames : new int[] {1, 7, 2, 13, 18}) {
            float[] block = new float[blockFrames * 2];
            dividedMixer.render(block, blockFrames);
            System.arraycopy(
                    block,
                    0,
                    divided,
                    destinationFrame * 2,
                    block.length);
            destinationFrame += blockFrames;
        }

        assertArrayEquals(contiguous, divided);
    }

    @Test
    void stoppingCrossfadedLoopAtLifecycleBoundaryIsOrderedAndIdempotent() {
        AudioMixer mixer = new AudioMixer();
        PcmClip clip = monoConstant("lifecycle-loop", 0.2f, 12);
        long voice = mixer.play(
                clip,
                AudioBus.MUSIC,
                new PcmLoopRegion(1, 11, 3),
                -6.0f,
                0.0f,
                3);
        mixer.render(new float[22], 11);

        assertTrue(mixer.stop(voice));
        assertTrue(mixer.stop(voice));
        float[] afterStop = new float[8];
        mixer.render(afterStop, 4);

        assertArrayEquals(new float[8], afterStop);
        assertEquals(0, mixer.activeVoiceCount());
        assertEquals(
                1L,
                mixer.events().snapshotSince(0).stream()
                        .filter(event ->
                                event.type() == AudioEventType.VOICE_STOPPED
                                        && event.voiceId() == voice)
                        .count());
    }

    @Test
    void atomicReplacementUsesComplementaryEndpointExactVoiceEnvelopes() {
        AudioMixer mixer = new AudioMixer();
        PcmClip outgoing = monoConstant("outgoing", 0.4f, 8);
        PcmClip incoming = monoConstant("incoming", -0.4f, 8);
        long outgoingVoice =
                mixer.play(
                        outgoing,
                        AudioBus.MUSIC,
                        true,
                        0.0f,
                        -1.0f,
                        4);
        mixer.render(new float[2], 1);
        long eventStart = mixer.events().nextSequence();

        long incomingVoice =
                mixer.replace(
                        outgoingVoice,
                        incoming,
                        AudioBus.MUSIC,
                        PcmLoopRegion.fullClip(incoming),
                        0.0f,
                        -1.0f,
                        4,
                        5);
        float[] transition = new float[12];
        float[] firstFrame = new float[2];
        mixer.render(firstFrame, 1);
        assertEquals(2, mixer.activeVoiceCount());
        assertEquals(1, mixer.logicalVoiceCount());
        System.arraycopy(firstFrame, 0, transition, 0, 2);
        float[] remainder = new float[10];
        mixer.render(remainder, 5);
        System.arraycopy(remainder, 0, transition, 2, 10);

        assertArrayEquals(
                new float[] {0.4f, 0.2f, 0.0f, -0.2f, -0.4f, -0.4f},
                leftChannel(transition),
                EPSILON);
        assertEquals(1, mixer.activeVoiceCount());
        assertEquals(
                List.of(
                        AudioEventType.VOICE_STARTED,
                        AudioEventType.VOICE_STOPPED),
                mixer.events().snapshotSince(eventStart).stream()
                        .map(AudioEvent::type)
                        .toList());
        assertEvent(
                mixer, AudioEventType.VOICE_STARTED, incomingVoice);
        assertEvent(
                mixer, AudioEventType.VOICE_STOPPED, outgoingVoice);
    }

    @Test
    void atomicReplacementIsBitIdenticalAcrossRenderBlockBoundaries() {
        PcmClip outgoing = PcmClip.fromFloats(
                "replace-out",
                1,
                new float[] {0.1f, 0.3f, -0.2f, 0.4f});
        PcmClip incoming = PcmClip.fromFloats(
                "replace-in",
                1,
                new float[] {-0.4f, 0.2f, 0.35f, -0.1f});

        float[] contiguous =
                renderReplacement(outgoing, incoming, new int[] {37});
        float[] divided = renderReplacement(
                outgoing, incoming, new int[] {1, 5, 2, 11, 18});

        assertArrayEquals(contiguous, divided);
    }

    @Test
    void missingReplacementTargetFadesIncomingUpWithoutExceedingVoiceCap() {
        AudioMixer mixer = new AudioMixer(8, 32, 1);
        PcmClip incoming = monoConstant("fallback", 0.4f, 8);

        long voice = mixer.replace(
                99L,
                incoming,
                AudioBus.AMBIENCE,
                PcmLoopRegion.fullClip(incoming),
                0.0f,
                -1.0f,
                5,
                3);
        float[] faded = new float[8];
        mixer.render(faded, 4);

        assertArrayEquals(
                new float[] {0.0f, 0.2f, 0.4f, 0.4f},
                leftChannel(faded),
                EPSILON);
        assertEquals(1, mixer.activeVoiceCount());
        assertEvent(mixer, AudioEventType.VOICE_STARTED, voice);
    }

    @Test
    void longReplacementCommandBurstsBoundLogicalOwnersAndPhysicalTails() {
        AudioMixer mixer = new AudioMixer(16, 1024, 1);
        PcmClip loop = monoConstant("burst-loop", 0.2f, 8);
        long initialOwner = mixer.play(
                loop,
                AudioBus.MUSIC,
                PcmLoopRegion.fullClip(loop),
                -12.0f,
                -1.0f,
                4);
        mixer.render(new float[2], 1);

        long owner = initialOwner;
        List<Long> quietSupersededOwners = new ArrayList<>();
        for (int block = 0; block < 20; block++) {
            long ownerAtBlockStart = owner;
            for (int command = 0; command < 8; command++) {
                owner = mixer.replace(
                        owner,
                        loop,
                        AudioBus.MUSIC,
                        PcmLoopRegion.fullClip(loop),
                        -12.0f,
                        -1.0f,
                        4,
                        AudioMixer.MAX_VOICE_FADE_FRAMES);
                assertTrue(owner != AudioMixer.NO_VOICE);
            }
            if (block > 0) {
                quietSupersededOwners.add(ownerAtBlockStart);
            }

            mixer.render(new float[2], 1);
            assertEquals(1, mixer.logicalVoiceCount());
            assertEquals(2, mixer.activeVoiceCount());
            assertEquals(2, mixer.physicalVoiceLimit());
        }

        for (long quietOwner : quietSupersededOwners) {
            assertEvent(
                    mixer,
                    AudioEventType.VOICE_STOPPED,
                    quietOwner);
        }
        assertFalse(hasEvent(
                mixer, AudioEventType.VOICE_STOPPED, initialOwner));

        mixer.render(
                new float[AudioMixer.MAX_VOICE_FADE_FRAMES * 2],
                AudioMixer.MAX_VOICE_FADE_FRAMES);
        assertEquals(1, mixer.logicalVoiceCount());
        assertEquals(1, mixer.activeVoiceCount());
        assertEvent(
                mixer, AudioEventType.VOICE_STOPPED, initialOwner);
    }

    @Test
    void equalGainTailOverflowStopsTheOldestTailDeterministically() {
        AudioMixer mixer = new AudioMixer(8, 32, 1);
        PcmClip loop = monoConstant("equal-tail", 0.4f, 8);
        long oldest = mixer.play(
                loop,
                AudioBus.AMBIENCE,
                PcmLoopRegion.fullClip(loop),
                0.0f,
                -1.0f,
                3);
        mixer.render(new float[2], 1);

        long newer = mixer.replace(
                oldest,
                loop,
                AudioBus.AMBIENCE,
                PcmLoopRegion.fullClip(loop),
                0.0f,
                -1.0f,
                3,
                3);
        mixer.render(new float[2], 1);
        mixer.replace(
                newer,
                loop,
                AudioBus.AMBIENCE,
                PcmLoopRegion.fullClip(loop),
                0.0f,
                -1.0f,
                3,
                3);
        mixer.render(new float[2], 1);

        assertEquals(1, mixer.logicalVoiceCount());
        assertEquals(2, mixer.activeVoiceCount());
        assertEvent(
                mixer, AudioEventType.VOICE_STOPPED, oldest);
        assertFalse(hasEvent(
                mixer, AudioEventType.VOICE_STOPPED, newer));
    }

    @Test
    void cappedReplacementBurstIsBitIdenticalAcrossRenderBlockBoundaries() {
        float[] contiguous =
                renderCappedReplacementBurst(false);
        float[] divided =
                renderCappedReplacementBurst(true);

        assertArrayEquals(contiguous, divided);
    }

    @Test
    void rejectedAtomicReplacementLeavesTheLogicalOwnerAndTailBudgetUntouched() {
        AudioMixer mixer = new AudioMixer(1, 32, 1);
        PcmClip current = monoConstant("queue-owner", 0.25f, 8);
        PcmClip rejected = monoConstant("queue-rejected", -0.25f, 8);
        long owner = mixer.play(
                current,
                AudioBus.MUSIC,
                PcmLoopRegion.fullClip(current),
                0.0f,
                -1.0f,
                3);
        mixer.render(new float[2], 1);

        assertTrue(mixer.setBusMuted(AudioBus.UI, true, 0));
        assertEquals(
                AudioMixer.NO_VOICE,
                mixer.replace(
                        owner,
                        rejected,
                        AudioBus.MUSIC,
                        PcmLoopRegion.fullClip(rejected),
                        0.0f,
                        -1.0f,
                        3,
                        AudioMixer.MAX_VOICE_FADE_FRAMES));
        float[] output = new float[2];
        mixer.render(output, 1);

        assertArrayEquals(new float[] {0.25f, 0.0f}, output);
        assertEquals(1, mixer.logicalVoiceCount());
        assertEquals(1, mixer.activeVoiceCount());
        assertFalse(hasEvent(
                mixer, AudioEventType.VOICE_STOPPED, owner));
        assertTrue(mixer.events().snapshotSince(0L).stream()
                .anyMatch(event ->
                        event.type()
                                == AudioEventType.COMMAND_REJECTED));
    }

    @Test
    void explicitVoiceFadeOutStopsAfterItsExactSilentEndpoint() {
        AudioMixer mixer = new AudioMixer();
        PcmClip loop = monoConstant("fade-out", 0.4f, 8);
        long voice = mixer.play(
                loop, AudioBus.AMBIENCE, true, 0.0f, -1.0f, 2);
        mixer.render(new float[2], 1);

        assertTrue(mixer.fadeOut(voice, 3));
        float[] faded = new float[8];
        mixer.render(faded, 4);

        assertArrayEquals(
                new float[] {0.4f, 0.2f, 0.0f, 0.0f},
                leftChannel(faded),
                EPSILON);
        assertEquals(0, mixer.activeVoiceCount());
        assertEvent(mixer, AudioEventType.VOICE_STOPPED, voice);
    }

    @Test
    void commandQueueIsBoundedAndReportsDrops() {
        AudioMixer mixer = new AudioMixer(1, 8, 2);
        assertTrue(mixer.setBusMuted(AudioBus.UI, true, 0));
        long rejected = mixer.play(monoConstant("drop", 0.25f, 1), AudioBus.UI, false, 0.0f, 0.0f, 1);

        assertEquals(AudioMixer.NO_VOICE, rejected);
        assertEquals(1, mixer.queuedCommandCount());
        assertTrue(mixer.events().snapshotSince(0L).stream()
                .anyMatch(event -> event.type() == AudioEventType.COMMAND_REJECTED));
    }

    @Test
    void identicalCommandsProduceBitIdenticalMixes() {
        float[] first = deterministicScenario();
        float[] second = deterministicScenario();
        assertArrayEquals(first, second);
    }

    @Test
    void fakeSinkReceivesMixerFramesWithoutRetainingTheRenderBuffer() throws Exception {
        AudioMixer mixer = new AudioMixer();
        mixer.play(monoConstant("sink", 0.25f, 2), AudioBus.UI, false, 0.0f, -1.0f, 1);
        float[] block = new float[4];
        mixer.render(block, 2);

        FakeSink sink = new FakeSink();
        sink.open(PcmFormat.GAME_STEREO, 512);
        sink.start();
        assertEquals(2, sink.write(block, 0, 2));
        block[0] = 0.9f;
        sink.stop();
        sink.close();

        assertEquals(PcmFormat.GAME_STEREO, sink.format);
        assertArrayEquals(new float[] {0.25f, 0.0f, 0.25f, 0.0f}, sink.written, EPSILON);
        assertTrue(sink.started);
        assertTrue(sink.stopped);
        assertTrue(sink.closed);
    }

    private static float[] renderSingleMono(float pan) {
        AudioMixer mixer = new AudioMixer();
        mixer.play(monoConstant("pan", 0.5f, 1), AudioBus.UI, false, 0.0f, pan, 1);
        float[] output = new float[2];
        mixer.render(output, 1);
        return output;
    }

    private static PcmClip monoConstant(String name, float value, int frames) {
        float[] samples = new float[frames];
        java.util.Arrays.fill(samples, value);
        return PcmClip.fromFloats(name, 1, samples);
    }

    private static float[] deterministicScenario() {
        AudioMixer mixer = new AudioMixer();
        PcmClip clip = PcmClip.fromFloats("deterministic", 1, new float[] {0.1f, 0.2f, -0.1f});
        mixer.setBusGainDb(AudioBus.AMBIENCE, -3.0f, 3);
        mixer.play(clip, AudioBus.AMBIENCE, true, -6.0f, -0.25f, 2);
        mixer.play(clip, AudioBus.WORLD, true, -9.0f, 0.5f, 1);
        float[] output = new float[24];
        mixer.render(output, 12);
        return output;
    }

    private static float[] renderRegion(
            PcmClip clip, PcmLoopRegion loop, int frames) {
        AudioMixer mixer = new AudioMixer();
        mixer.play(clip, AudioBus.MUSIC, loop, -9.0f, 0.25f, 4);
        float[] output = new float[frames * 2];
        mixer.render(output, frames);
        return output;
    }

    private static float[] renderReplacement(
            PcmClip outgoing, PcmClip incoming, int[] blocks) {
        AudioMixer mixer = new AudioMixer();
        long outgoingVoice = mixer.play(
                outgoing,
                AudioBus.MUSIC,
                PcmLoopRegion.fullClip(outgoing),
                -12.0f,
                -0.25f,
                4);
        mixer.render(new float[6], 3);
        mixer.replace(
                outgoingVoice,
                incoming,
                AudioBus.MUSIC,
                PcmLoopRegion.fullClip(incoming),
                -9.0f,
                0.35f,
                4,
                13);

        int totalFrames = java.util.Arrays.stream(blocks).sum();
        float[] output = new float[totalFrames * 2];
        int destination = 0;
        for (int blockFrames : blocks) {
            float[] block = new float[blockFrames * 2];
            mixer.render(block, blockFrames);
            System.arraycopy(
                    block, 0, output, destination * 2, block.length);
            destination += blockFrames;
        }
        return output;
    }

    private static float[] renderCappedReplacementBurst(
            boolean dividedBlocks) {
        AudioMixer mixer = new AudioMixer(16, 128, 1);
        PcmClip[] states = {
            monoConstant("burst-a", 0.1f, 8),
            monoConstant("burst-b", -0.2f, 8),
            monoConstant("burst-c", 0.3f, 8),
            monoConstant("burst-d", -0.15f, 8),
            monoConstant("burst-e", 0.25f, 8)
        };
        long owner = mixer.play(
                states[0],
                AudioBus.MUSIC,
                PcmLoopRegion.fullClip(states[0]),
                -12.0f,
                -1.0f,
                4);
        mixer.render(new float[2], 1);

        float[] output = new float[(states.length - 1) * 6];
        int destinationFrame = 0;
        for (int state = 1; state < states.length; state++) {
            owner = mixer.replace(
                    owner,
                    states[state],
                    AudioBus.MUSIC,
                    PcmLoopRegion.fullClip(states[state]),
                    -12.0f,
                    -1.0f,
                    4,
                    97);
            int[] blocks = dividedBlocks
                    ? new int[] {1, 2}
                    : new int[] {3};
            for (int blockFrames : blocks) {
                float[] block = new float[blockFrames * 2];
                mixer.render(block, blockFrames);
                System.arraycopy(
                        block,
                        0,
                        output,
                        destinationFrame * 2,
                        block.length);
                destinationFrame += blockFrames;
                assertEquals(1, mixer.logicalVoiceCount());
                assertTrue(
                        mixer.activeVoiceCount()
                                <= mixer.physicalVoiceLimit());
            }
        }
        assertEquals(2, mixer.activeVoiceCount());
        return output;
    }

    private static float[] leftChannel(float[] stereo) {
        float[] left = new float[stereo.length / 2];
        for (int frame = 0; frame < left.length; frame++) {
            left[frame] = stereo[frame * 2];
        }
        return left;
    }

    private static float maxAdjacentJump(
            float[] values, int fromInclusive, int toExclusive) {
        float maximum = 0.0f;
        for (int index = Math.max(1, fromInclusive);
                index < Math.min(values.length, toExclusive);
                index++) {
            maximum = Math.max(
                    maximum, Math.abs(values[index] - values[index - 1]));
        }
        return maximum;
    }

    private static boolean hasEvent(AudioMixer mixer, AudioEventType type, long voiceId) {
        return mixer.events().snapshotSince(0L).stream()
                .anyMatch(event -> event.type() == type && event.voiceId() == voiceId);
    }

    private static void assertEvent(AudioMixer mixer, AudioEventType type, long voiceId) {
        assertTrue(hasEvent(mixer, type, voiceId), () -> "missing " + type + " for voice " + voiceId);
    }

    private static final class FakeSink implements AudioSink {
        PcmFormat format;
        boolean started;
        boolean stopped;
        boolean closed;
        float[] written = new float[0];

        @Override
        public void open(PcmFormat format, int bufferFrames) {
            this.format = format;
        }

        @Override
        public void start() {
            started = true;
        }

        @Override
        public int write(float[] interleavedSamples, int frameOffset, int frameCount) {
            int from = frameOffset * PcmFormat.GAME_STEREO.channels();
            int to = from + frameCount * PcmFormat.GAME_STEREO.channels();
            List<Float> copy = new ArrayList<>(to - from);
            for (int i = from; i < to; i++) {
                copy.add(interleavedSamples[i]);
            }
            written = new float[copy.size()];
            for (int i = 0; i < copy.size(); i++) {
                written[i] = copy.get(i);
            }
            return frameCount;
        }

        @Override
        public void stop() {
            stopped = true;
        }

        @Override
        public void close() {
            closed = true;
        }
    }
}
