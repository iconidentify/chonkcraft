package net.chonkbase.runtime.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class AudioEventRingTest {
    @Test
    void boundedSnapshotDropsOnlyOverwrittenEvents() {
        AudioEventRing ring = new AudioEventRing(3);
        for (int i = 0; i < 5; i++) {
            ring.publish(AudioEventType.VOICE_STARTED, i + 1L, AudioBus.UI);
        }

        List<AudioEvent> retained = ring.snapshotSince(0L);
        assertEquals(List.of(2L, 3L, 4L), retained.stream().map(AudioEvent::sequence).toList());
        assertEquals(List.of(3L, 4L, 5L), retained.stream().map(AudioEvent::voiceId).toList());
        assertEquals(5L, ring.nextSequence());
    }

    @Test
    void sequenceCursorAvoidsRedelivery() {
        AudioEventRing ring = new AudioEventRing(8);
        ring.publish(AudioEventType.BUS_GAIN_CHANGED, 0L, AudioBus.MUSIC);
        ring.publish(AudioEventType.BUS_MUTE_CHANGED, 0L, AudioBus.AMBIENCE);

        assertEquals(2, ring.snapshotSince(0L).size());
        assertEquals(1, ring.snapshotSince(1L).size());
        assertEquals(0, ring.snapshotSince(ring.nextSequence()).size());
    }
}
