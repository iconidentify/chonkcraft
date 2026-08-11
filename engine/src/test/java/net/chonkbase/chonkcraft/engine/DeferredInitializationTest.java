package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Settings ChonkCraft defers through {@code InitGameVariables}. */
class DeferredInitializationTest {

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    void theRealPreludesDeferredSettingsAreApplied() {
        GameData data = data();
        data.upgrades();

        assertTrue(data.deferredSettingsApplied());
        assertEquals(0, data.forestRegeneration());
        assertEquals(1, data.speedFactor());
        assertTrue(data.trainingQueueEnabled());
        assertNull(data.damageMissile(), "ShowDamage defaults off in the shipped preferences");
    }
}
