package net.chonkbase.chonkcraft.launcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Player-visible process identity must never fall back to Java's Main class. */
class ApplicationIdentityTest {

    @Test
    @DisplayName("desktop identity is branded before AWT starts")
    void desktopIdentityIsChonkCraft() {
        ApplicationIdentity.initialize();

        assertEquals("ChonkCraft", System.getProperty("apple.awt.application.name"));
        assertEquals("ChonkCraft",
                System.getProperty("com.apple.mrj.application.apple.menu.about.name"));
        assertFalse(ApplicationIdentity.icons().isEmpty(),
                "window and taskbar icons fell back to Java");
        assertNotNull(ApplicationIdentity.iconImage(),
                "the branded About/dialog icon is missing");
    }

    @Test
    @DisplayName("confirmations name the action instead of offering Yes and No")
    void confirmationsUseMeaningfulNativeActions() {
        List<String> options = LauncherDialogs.confirmationOptions("Remove Pack");

        assertTrue(options.contains("Cancel"));
        assertTrue(options.contains("Remove Pack"));
        assertFalse(options.contains("Yes"));
        assertFalse(options.contains("No"));
    }

    @Test
    @DisplayName("release version feeds the About panel")
    void configuredVersionFeedsAbout() {
        String previous = System.getProperty("chonkcraft.version");
        try {
            System.setProperty("chonkcraft.version", "0.1.1-beta1");
            assertEquals("0.1.1-beta1", ApplicationIdentity.version());
        } finally {
            if (previous == null) {
                System.clearProperty("chonkcraft.version");
            } else {
                System.setProperty("chonkcraft.version", previous);
            }
        }
    }
}
