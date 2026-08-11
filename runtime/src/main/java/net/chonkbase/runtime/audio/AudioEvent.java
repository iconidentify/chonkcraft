package net.chonkbase.runtime.audio;

/**
 * Immutable mixer event. Sequence numbers are local to one
 * {@link AudioEventRing}.
 */
public record AudioEvent(long sequence, AudioEventType type, long voiceId, AudioBus bus) {}
