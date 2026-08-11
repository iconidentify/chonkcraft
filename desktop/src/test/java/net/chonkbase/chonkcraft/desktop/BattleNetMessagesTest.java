package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Regression coverage for the single player-facing notification catalog. */
class BattleNetMessagesTest {

    @Test
    @DisplayName("every recovered BNE notification has a polished offline fallback")
    void recoveredMessagesRemainUsableWithoutAStringTable() {
        for (BattleNetMessages.Key key : BattleNetMessages.Key.values()) {
            String text = BattleNetMessages.text(null, key);
            assertFalse(text.isBlank(), key + " was blank");
            assertTrue(Character.isUpperCase(text.codePointAt(0)),
                    key + " did not start with a capital: " + text);
            assertTrue(text.endsWith(".") || text.endsWith("!") || text.endsWith("?"),
                    key + " had no terminal punctuation: " + text);
        }
        assertEquals("Not enough food...build more farms.",
                BattleNetMessages.text(null, BattleNetMessages.Key.NOT_ENOUGH_FOOD));
        assertEquals("You cannot build there.",
                BattleNetMessages.text(null, BattleNetMessages.Key.CANNOT_BUILD_THERE));
    }

    @Test
    @DisplayName("local notices are sentence-cased without damaging BNE ellipses")
    void localNoticesUseOneStyle() {
        assertEquals("Moving.", BattleNetMessages.sentence("moving"));
        assertEquals("Already polished!", BattleNetMessages.sentence("Already polished!"));
        assertEquals("Not enough food...build more farms.",
                BattleNetMessages.sentence("not enough food...build more farms."));
        assertEquals("", BattleNetMessages.sentence("  "));
    }
}
