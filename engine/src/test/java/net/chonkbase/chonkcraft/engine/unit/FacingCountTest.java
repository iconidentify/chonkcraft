package net.chonkbase.chonkcraft.engine.unit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How many ways a sprite can face.
 *
 * <p>A type that does not say gets a guess, and upstream's guess is not one
 * number: is
 * {@code type->NumDirections = type->Building ? 1 : 8}. This implementation defaulted
 * everything to eight.
 *
 * <p>The consequence is only visible on some buildings, which is why it
 * survived. A frame is chosen as the animation's frame plus the unit's
 * heading, and a building is finished facing south -- heading four. With two
 * frames in the sheet, four modulo two is zero and the right frame is drawn by
 * accident. With three it is not: the animation asks for frame zero and the
 * renderer draws frame one. Both oil platforms have three.
 */
class FacingCountTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("A building faces one way and a unit eight, unless it says otherwise")
    void theGuessMatchesUpstream() {
        GameData data = load();
        int buildings = 0;
        int mobile = 0;
        StringBuilder wrong = new StringBuilder();
        for (UnitType type : data.unitTypes().types().values()) {
            if (type.rawProperties().containsKey("NumDirections")) {
                // Declared outright; the guess does not apply.
                continue;
            }
            if (type.building()) {
                buildings++;
                if (type.numDirections() != 1) {
                    wrong.append("\n  ").append(type.ident()).append(" is a building facing ")
                            .append(type.numDirections()).append(" ways");
                }
            } else {
                mobile++;
                if (type.numDirections() != 8) {
                    wrong.append("\n  ").append(type.ident()).append(" is mobile facing ")
                            .append(type.numDirections()).append(" ways");
                }
            }
        }
        assertTrue(buildings > 20, "only " + buildings + " buildings, so this proves little");
        assertTrue(mobile > 20, "only " + mobile + " mobile types, so this proves little");
        assertEquals(0, wrong.length(), "types guessed the wrong number of facings:" + wrong);
    }

    /**
     * The case that is actually wrong on screen. A three-frame building drawn
     * as though it had eight facings picks its frame by adding the heading,
     * and south is four: it draws frame one where the animation asked for
     * frame zero.
     */
    @Test
    @DisplayName("An oil platform's frames are not shifted by its heading")
    void anOilPlatformDrawsItsOwnFrame() {
        GameData data = load();
        int checked = 0;
        for (UnitType type : data.unitTypes().types().values()) {
            if (!type.building() || type.ident() == null
                    || !type.ident().contains("oil-platform")) {
                continue;
            }
            checked++;
            assertEquals(1, type.numDirections(),
                    type.ident() + " faces " + type.numDirections() + " ways, so its frame is"
                            + " its animation's frame plus its heading rather than the frame"
                            + " the animation asked for");
        }
        Assumptions.assumeTrue(checked > 0, "no oil platform in this installation");
    }

    /** A type that names a count keeps it. */
    @Test
    @DisplayName("A declared facing count is not overridden by the guess")
    void aDeclaredCountWins() {
        GameData data = load();
        int declared = 0;
        for (UnitType type : data.unitTypes().types().values()) {
            Object named = type.rawProperties().get("NumDirections");
            if (!(named instanceof Number count)) {
                continue;
            }
            declared++;
            assertEquals(count.intValue(), type.numDirections(),
                    type.ident() + " declares NumDirections and the guess overrode it");
        }
        Assumptions.assumeTrue(declared > 0, "nothing declares NumDirections");
    }
}
