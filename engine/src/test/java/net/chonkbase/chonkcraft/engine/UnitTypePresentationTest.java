package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Three fields that were parsed for a long time and read by nothing.
 *
 * <p>Each governs something the player sees or does, and each was being
 * substituted for by a single rule applied to every unit alike. The values here
 * come from the real data, so a regression in the parsing shows up as well as a
 * regression in the use.
 */
class UnitTypePresentationTest {

    private static Map<String, UnitType> types() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install).unitTypes().types();
    }

    @Test
    @DisplayName("a right click means different things to different units")
    void rightClickIsPerType() {
        Map<String, UnitType> types = types();
        // One rule for all of these is what the implementation had, and it is why a
        // worker sent at a tree and a soldier sent at an orc took the same
        // path through the code.
        assertEquals("harvest", types.get("unit-peasant").rightMouseAction());
        assertEquals("attack", types.get("unit-footman").rightMouseAction());
        assertEquals("sail", types.get("unit-human-transport").rightMouseAction());
        // A building takes no order from a right click at all.
        assertTrue(types.get("unit-town-hall").rightMouseAction().isEmpty());
    }

    @Test
    @DisplayName("a band drag does not gather buildings")
    void buildingsAreNotBandSelectable() {
        Map<String, UnitType> types = types();
        assertTrue(types.get("unit-footman").selectableByRectangle());
        assertTrue(types.get("unit-peasant").selectableByRectangle());
        assertFalse(types.get("unit-town-hall").selectableByRectangle(),
                "a drag across your own base would come back holding it");
        assertFalse(types.get("unit-farm").selectableByRectangle());
        assertFalse(types.get("unit-gold-mine").selectableByRectangle());
    }

    @Test
    @DisplayName("units declare what they are drawn over")
    void drawLevelsAreLayered() {
        Map<String, UnitType> types = types();
        int building = types.get("unit-town-hall").drawLevel();
        int ground = types.get("unit-footman").drawLevel();
        int flying = types.get("unit-gryphon-rider").drawLevel();

        assertTrue(building < ground, "a soldier should be drawn over a building");
        assertTrue(ground < flying, "a gryphon should be drawn over a soldier");
        assertNotEquals(0, flying, "the draw level was not parsed at all");
    }
}
