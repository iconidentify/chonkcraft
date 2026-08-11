package net.chonkbase.chonkcraft.engine.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * How many command buttons the game has, and that a farm is not one of the
 * things with any.
 *
 * <p>Nothing counted them until now, and something needed to. ChonkCraft ships
 * {@code chonkcraft.extensions = true} at {@code scripts/legacyEngine.legacy-declaration:162} -- a
 * deliberate fork of its own, commented "Currently enables some additional
 * buttons" -- and this implementation ran that file and inherited the answer without
 * ever choosing it. The result was 232 buttons where Warcraft II has 214, and
 * the clearest symptom was that {@code unit-farm}, which in Warcraft II has no
 * command buttons whatsoever, had exactly one: "train critter"
 * ({@code scripts/human/buttons.legacy-declaration:356}).
 *
 * <p>The implementation now writes the flag itself and writes it false, because the
 * Battle.net Edition is the oracle. See {@code GameData.EXTENDED_FEATURES} for
 * the fork and its bounded consequence.
 *
 * <p>These count rather than sample. A sweep that walks the buttons and finds
 * nothing objectionable passes perfectly against a roster that failed to load,
 * and the roster loading at all is the part that has broken before -- the
 * prelude used to stop at line 142 of 623 and take four hundred and eighty
 * lines of definitions with it. So the total is asserted first, and only then
 * what is in it.
 *
 * <p>The measurement is stated as a number of buttons on named units, never by
 * asking whether extensions are on. A test phrased on the flag would agree
 * with the flag whichever way it was set, which is a tautology with test
 * scaffolding around it.
 */
class ButtonRosterRealDataTest {

    /** Warcraft II's own count. ChonkCraft with its extras on holds 232. */
    private static final int RETAIL_BUTTONS = 214;

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");
        return new GameData(assets);
    }

    private static List<UnitButton> buttons() {
        return List.copyOf(load().userInterface("summer").buttons().all());
    }

    @Test
    @DisplayName("the game has as many command buttons as Warcraft II, not as many as ChonkCraft")
    void theRosterIsTheRetailOne() {
        List<UnitButton> all = buttons();
        assertEquals(RETAIL_BUTTONS, all.size(),
                "the command panel is drawn from " + all.size() + " buttons;"
                        + " Warcraft II has " + RETAIL_BUTTONS + " and ChonkCraft"
                        + " with its extra features switched on has 232");
    }

    @Test
    @DisplayName("a farm has nothing on its command panel, and a barracks does")
    void aFarmHasNoCommands() {
        List<UnitButton> all = buttons();

        // The fixture guard first: a roster that failed to load gives every
        // building no buttons and would pass the farm's assertion outright.
        List<String> barracks = forUnit(all, "unit-human-barracks");
        assertTrue(barracks.size() >= 4,
                "a barracks should train several units and offers " + barracks
                        + "; with a roster this thin nothing below is measuring"
                        + " anything");

        assertEquals(List.of(), forUnit(all, "unit-farm"),
                "a Warcraft II farm has no command panel at all, and this one"
                        + " offers " + forUnit(all, "unit-farm"));
        assertEquals(List.of(), forUnit(all, "unit-pig-farm"),
                "a pig farm has no command panel either, and this one offers "
                        + forUnit(all, "unit-pig-farm"));
    }

    @Test
    @DisplayName("nothing anywhere in the game offers to train livestock")
    void noBuildingTrainsACritter() {
        List<String> livestock = new ArrayList<>();
        for (UnitButton button : buttons()) {
            if ("unit-critter".equals(button.value())) {
                livestock.add(button.action() + " on " + button.forUnits());
            }
        }
        assertEquals(List.of(), livestock,
                "the critter wanders the map and is not produced by anything in"
                        + " Warcraft II; these buttons say otherwise: " + livestock);
    }

    @Test
    @DisplayName("the two workshops still train the four units they always did")
    void theWorkshopsAreUntouched() {
        List<UnitButton> all = buttons();

        // Named because the extensions arm also reaches these two, so turning
        // it off had to be checked for taking something real with it. Both are
        // symmetrical in Warcraft II: each workshop makes one flyer and one
        // demolition unit.
        assertEquals(List.of("train-unit:unit-balloon", "train-unit:unit-dwarves"),
                forUnit(all, "unit-inventor"),
                "the gnomish inventor builds the flying machine and the dwarven"
                        + " demolition squad");
        assertEquals(List.of("train-unit:unit-zeppelin", "train-unit:unit-goblin-sappers"),
                forUnit(all, "unit-alchemist"),
                "the goblin alchemist builds the zeppelin and the sappers");
    }

    private static List<String> forUnit(List<UnitButton> all, String ident) {
        List<String> found = new ArrayList<>();
        for (UnitButton button : all) {
            if (button.forUnits().contains(ident)) {
                found.add(button.action() + ":" + button.value());
            }
        }
        return found;
    }
}
