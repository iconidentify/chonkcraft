package net.chonkbase.chonkcraft.matchmaking;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Human game codes have one forgiving interpretation on client and server. */
class RoomCodeTest {

    @Test
    @DisplayName("A spoken code tolerates case, spaces, separators, I and L")
    void aSpokenCodeIsNormalized() {
        assertEquals("A11B2C", RoomCode.normalize("a-il b2c"));
    }

    @Test
    @DisplayName("An incomplete or ambiguous-zero code is refused")
    void malformedCodesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> RoomCode.normalize("ABC"));
        assertThrows(IllegalArgumentException.class, () -> RoomCode.normalize("ABO123"));
    }
}
