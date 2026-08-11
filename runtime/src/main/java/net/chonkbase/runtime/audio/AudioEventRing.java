package net.chonkbase.runtime.audio;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Fixed-capacity overwrite ring for mixer diagnostics.
 *
 * <p>Readers keep the next sequence they want and call
 * {@link #snapshotSince(long)} off the audio thread. Slow readers lose the
 * oldest overwritten events rather than applying backpressure to playback.
 */
public final class AudioEventRing {
    private final int capacity;
    private final AtomicLong nextSequence = new AtomicLong();
    private final AtomicReferenceArray<AudioEvent> events;

    public AudioEventRing(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.events = new AtomicReferenceArray<>(capacity);
    }

    public void publish(AudioEventType type, long voiceId, AudioBus bus) {
        if (type == null) {
            throw new IllegalArgumentException("type must not be null");
        }
        long sequence = nextSequence.getAndIncrement();
        events.set((int) (sequence % capacity), new AudioEvent(sequence, type, voiceId, bus));
    }

    public List<AudioEvent> snapshotSince(long requestedSequence) {
        long latestExclusive = nextSequence.get();
        long start = Math.max(Math.max(0L, requestedSequence), latestExclusive - capacity);
        List<AudioEvent> snapshot = new ArrayList<>((int) Math.min(capacity, latestExclusive - start));
        for (long sequence = start; sequence < latestExclusive; sequence++) {
            AudioEvent event = events.get((int) (sequence % capacity));
            if (event != null && event.sequence() == sequence) {
                snapshot.add(event);
            }
        }
        return List.copyOf(snapshot);
    }

    public long nextSequence() {
        return nextSequence.get();
    }

    public int capacity() {
        return capacity;
    }
}
