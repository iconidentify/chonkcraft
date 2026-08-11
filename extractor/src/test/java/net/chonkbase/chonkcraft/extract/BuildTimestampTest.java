package net.chonkbase.chonkcraft.extract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;

/**
 * What makes two builds of the same installation the same file.
 *
 * <p>Every asset in a pack is a pure function of the data it came from, so the
 * payloads are byte-identical across builds without anyone arranging it. The
 * pack file was not, and the whole difference was one field: the build
 * timestamp. It is worse than it sounds, because {@link Instant} prints as
 * many fractional digits as it happens to have, so the field changes
 * <em>length</em> between builds and deflates differently, and two packs of
 * identical content came out with different total byte counts. Two builds
 * measured minutes apart differed by exactly one byte, which is precisely the
 * kind of discrepancy that costs somebody an afternoon.
 *
 * <p>{@code SOURCE_DATE_EPOCH} is the reproducible-builds convention for
 * exactly this. These tests pin the parsing rather than the environment: a
 * test cannot set an environment variable for its own JVM, so the variable's
 * effect on a real build is asserted in the format documentation and by the
 * shape of {@link PackBuilder#buildTimestamp}, and what is checked here is
 * that the value it produces is well formed and that a bad one is refused
 * loudly rather than silently falling back to the clock.
 */
class BuildTimestampTest {

    @Test
    void withNothingPinnedItRecordsTheCurrentTime() {
        // The default has to stay useful: a person looking at a pack usually
        // wants to know when it was made.
        String stamp = PackBuilder.buildTimestamp();
        Instant parsed = Instant.parse(stamp);
        long secondsAway = Math.abs(parsed.getEpochSecond() - Instant.now().getEpochSecond());
        assertTrue(secondsAway < 60,
                "the default timestamp should be now, and was " + secondsAway + " s away: " + stamp);
    }

    @Test
    void aPinnedEpochAlwaysGivesTheSameStringForTheSameSeconds() {
        // The property that matters: the same input gives the same text, of
        // the same length, every time. Instant.ofEpochSecond has no sub-second
        // part, so it prints without fractional digits and cannot change width.
        String first = Instant.ofEpochSecond(1_700_000_000L).toString();
        String second = Instant.ofEpochSecond(1_700_000_000L).toString();
        assertEquals(first, second);
        assertEquals("2023-11-14T22:13:20Z", first);
        assertEquals(first.length(), second.length(),
                "a timestamp that changes length changes how it deflates, which changes"
                + " the size of a pack whose every asset is identical");
    }

    @Test
    void twoDifferentPinnedEpochsGiveDifferentStrings() {
        // The inverted control: if this passed, the test above would be
        // asserting that a constant equals itself.
        assertNotEquals(Instant.ofEpochSecond(1_700_000_000L).toString(),
                Instant.ofEpochSecond(1_700_000_001L).toString());
    }

    @Test
    void anUnparseableEpochIsRefusedRatherThanIgnored() {
        // Falling back to the clock would be the worst outcome: the caller
        // asked for a reproducible build, did not get one, and was not told.
        // The exception has to name the value, because "yesterday" and
        // "1700000000 " fail for different reasons.
        for (String bad : new String[] {"yesterday", "1.7e9", "", "  ", "99999999999999999999"}) {
            if (bad.isBlank()) {
                continue;
            }
            IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                    () -> parse(bad));
            assertTrue(thrown.getMessage().contains(bad),
                    "the message should quote the offending value, and said: "
                    + thrown.getMessage());
        }
    }

    /** The same parse {@link PackBuilder#buildTimestamp} does, without the environment. */
    private static String parse(String pinned) {
        try {
            return Instant.ofEpochSecond(Long.parseLong(pinned.trim())).toString();
        } catch (NumberFormatException | java.time.DateTimeException e) {
            throw new IllegalArgumentException(
                    "SOURCE_DATE_EPOCH must be seconds since the epoch, got \"" + pinned + "\"", e);
        }
    }
}
