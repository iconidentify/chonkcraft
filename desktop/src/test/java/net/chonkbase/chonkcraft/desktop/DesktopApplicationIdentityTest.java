package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The launched game is a ChonkCraft application, not a generic Java process. */
class DesktopApplicationIdentityTest {

    @Test
    @DisplayName("the game names itself before AWT starts and packages its crest")
    void gameHasItsOwnDesktopIdentity() {
        DesktopApplicationIdentity.initialize();

        assertEquals("ChonkCraft", System.getProperty("apple.awt.application.name"));
        assertEquals("ChonkCraft",
                System.getProperty("com.apple.mrj.application.apple.menu.about.name"));
        assertNotNull(DesktopApplicationIdentity.class
                        .getResource("/icons/chonkcraft.png"),
                "the game would fall back to the Java taskbar icon");
    }
}
