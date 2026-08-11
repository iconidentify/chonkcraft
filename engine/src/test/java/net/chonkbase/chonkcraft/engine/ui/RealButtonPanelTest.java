package net.chonkbase.chonkcraft.engine.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.upgrade.DependencyRules;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Builds the command panel from the game's own button definitions.
 *
 * <p>The point of these is that nothing about Warcraft II's tech tree is
 * written in this project's Java. A barracks offers footmen and archers at the
 * start of a game and gains ballistae and knights when the buildings that
 * unlock them appear, and the reason is 214 {@code DefineButton} calls and 66
 * {@code DefineDependency} calls read out of the shipped scripts. If the
 * loading is wrong, these fail rather than the panel quietly offering the
 * whole roster from the first minute.
 */
class RealButtonPanelTest {

    private record Loaded(World world, GameData data, GameData.Interface ui,
            DependencyRules dependencies, Map<String, UnitType> types) {}

    private static Loaded load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");

        byte[] alamo = install.map("ALAMO.PUD");
        Assumptions.assumeTrue(alamo != null, "ALAMO.PUD not in this installation");

        GameData data = new GameData(install);
        PudMap source = PudReader.read(alamo);
        GameMap map = GameMap.from(source, data.loadTileset(source.tileset()).tileset());
        World world = new World(map, Player.from(source));
        world.setUpgrades(data.upgrades().upgrades());
        data.populate(world, source);
        return new Loaded(world, data, data.userInterface("summer"),
                data.upgrades().dependencies(), data.unitTypes().types());
    }

    /** The slots a unit shows, as action or value names, with dots for gaps. */
    private static List<String> panel(Loaded loaded, Unit unit) {
        UnitButton[] page = loaded.ui().buttons().page(unit.type().ident(), 0,
                new ButtonAvailability(loaded.world(), unit, loaded.dependencies(), false));
        List<String> names = new ArrayList<>();
        for (UnitButton button : page) {
            names.add(button == null ? "."
                    : button.value() != null ? button.value() : button.action());
        }
        return names;
    }

    private static Unit find(Loaded loaded, String ident, int player) {
        for (Unit unit : loaded.world().units()) {
            if (unit.player() == player && unit.type() != null
                    && ident.equals(unit.type().ident())) {
                return unit;
            }
        }
        return null;
    }

    @Test
    @DisplayName("the shipped scripts define the whole panel and every icon it names")
    void theButtonsAndIconsLoad() {
        Loaded loaded = load();
        // 214, and it has been both numbers for different reasons, so the
        // history is worth keeping straight. It read 214 by accident while the
        // prelude stopped at line 142 of scripts/legacyEngine.legacy-declaration and never
        // reached the chonkcraft.extensions assignment on line 162. Fixing the
        // prelude made it 232, which is what upstream ChonkCraft loads: both race
        // button files branch on that flag and declare nine buttons each only
        // when it is true.
        //
        // It is 214 again now, and this time on purpose. GameData
        // .EXTENDED_FEATURES is false because the Battle.net Edition is this
        // port's oracle, and the eighteen buttons the flag adds are plainly
        // not retail -- a farm that trains livestock, and LegacyEngine's
        // set-default-order commands on a town hall and a shipyard. See that
        // field for the fork; ButtonRosterRealDataTest names what went.
        assertEquals(214, loaded.ui().buttons().size(), "buttons from the two race scripts");
        assertEquals(198, loaded.ui().icons().frames().size(), "icons with frames from icons.legacy-declaration");
        assertEquals(66, loaded.dependencies().size(), "dependency rules from the upgrade scripts");

        // A button naming an icon that has no frame would draw as whatever
        // happens to sit at frame zero, which is a peasant.
        for (UnitButton button : loaded.ui().buttons().all()) {
            assertTrue(loaded.ui().icons().frame(button.icon()) >= 0,
                    "no frame for " + button.icon() + ", named by " + button.action());
        }
    }

    @Test
    @DisplayName("the panel reaches the button scripts, and not their extended arms")
    void theExtensionButtonsAreNotDeclared() {
        // This test used to assert the opposite, and the reversal is the
        // record of a decision rather than a fix. It was written to catch the
        // prelude stopping before scripts/legacyEngine.legacy-declaration:162, because that
        // failure is silent -- the panel simply offers less -- and it named
        // these four rather than counting so that a change had to say which
        // buttons moved.
        //
        // The implementation now sets that flag itself, and sets it false: see
        // GameData.EXTENDED_FEATURES. So these four must be absent, and the
        // same four are still the ones worth naming.
        List<UnitButton> all = load().ui().buttons().all();
        for (String[] extended : List.of(
                new String[] {"train-unit", "unit-critter", "unit-farm"},
                new String[] {"train-unit", "unit-critter", "unit-pig-farm"},
                new String[] {"harvest", null, "unit-town-hall"},
                new String[] {"harvest", null, "unit-great-hall"})) {
            assertFalse(all.stream().anyMatch(button ->
                            extended[0].equals(button.action())
                                    && java.util.Objects.equals(extended[1], button.value())
                                    && button.appliesTo(extended[2])),
                    "a " + extended[0] + " button reached " + extended[2]
                            + ", which Warcraft II does not have; is"
                            + " chonkcraft.extensions being set true again?");
        }

        // And the guard that keeps the four above from passing on an empty
        // panel: the ordinary arms of the same two files must still be here.
        assertTrue(all.stream().anyMatch(button ->
                        "train-unit".equals(button.action())
                                && "unit-footman".equals(button.value())
                                && button.appliesTo("unit-human-barracks")),
                "a barracks cannot train a footman, so the button scripts did"
                        + " not load and nothing above was measured");
    }

    @Test
    @DisplayName("no button is declared twice")
    void everyButtonIsItsOwn() {
        // Nine (pos, level, action, value, icon) combinations occur more than
        // once -- "harvest at slot five" four times over. They are not repeats:
        // each names a different set of units, and upstream's AddButton in
        // The game appends every declaration without looking at what
        // is already in UnitButtonTable, so a genuine repeat would be kept
        // rather than merged. What makes them distinct is ForUnit, so that is
        // what this compares. A collapse here means something started
        // de-duplicating, and the panel would lose commands a unit relies on.
        List<UnitButton> all = load().ui().buttons().all();
        assertEquals(all.size(), Set.copyOf(all).size(), "a button was declared twice over");
    }

    @Test
    @DisplayName("a barracks trains only what the player's buildings unlock")
    void theTechTreeGatesTraining() {
        Loaded loaded = load();
        Unit townHall = null;
        for (Unit unit : loaded.world().units()) {
            if (unit.type() != null && "unit-town-hall".equals(unit.type().ident())) {
                townHall = unit;
                break;
            }
        }
        Assumptions.assumeTrue(townHall != null, "the map places no town hall");
        int player = townHall.player();

        Unit barracks = find(loaded, "unit-human-barracks", player);
        Assumptions.assumeTrue(barracks != null, "that player has no barracks");

        List<String> start = panel(loaded, barracks);
        assertEquals("unit-footman", start.get(0));
        assertEquals("unit-archer", start.get(1));
        // Not the minuteman, which shares slot one and is declared first.
        assertFalse(start.contains("unit-attack-peasant"), "the minuteman shadows the footman");
        assertFalse(start.contains("unit-ballista"), "a ballista needs a blacksmith and lumber mill");
        assertFalse(start.contains("unit-knight"), "a knight needs stables");

        for (String ident : List.of("unit-human-blacksmith", "unit-elven-lumber-mill",
                "unit-stables", "unit-keep")) {
            UnitType type = loaded.types().get(ident);
            assertNotNull(type, ident + " is missing from the roster");
            assertNotNull(loaded.world().createUnit(type, player, 2, 2 + ident.length() % 5),
                    "could not place " + ident);
        }

        List<String> unlocked = panel(loaded, barracks);
        assertTrue(unlocked.contains("unit-ballista"),
                "the blacksmith and lumber mill unlock the ballista");
        assertTrue(unlocked.contains("unit-knight"), "the stables unlock the knight");
    }

    @Test
    @DisplayName("a blacksmith sells the first level of an upgrade, not the second")
    void upgradesAreOfferedInOrder() {
        Loaded loaded = load();
        Unit townHall = null;
        for (Unit unit : loaded.world().units()) {
            if (unit.type() != null && "unit-town-hall".equals(unit.type().ident())) {
                townHall = unit;
                break;
            }
        }
        Assumptions.assumeTrue(townHall != null, "the map places no town hall");

        UnitType type = loaded.types().get("unit-human-blacksmith");
        Assumptions.assumeTrue(type != null, "no blacksmith in the roster");
        Unit blacksmith = loaded.world().createUnit(type, townHall.player(), 2, 2);
        Assumptions.assumeTrue(blacksmith != null, "could not place a blacksmith");

        List<String> page = panel(loaded, blacksmith);
        assertTrue(page.contains("upgrade-sword1"), "swords start at the first level");
        assertFalse(page.contains("upgrade-sword2"),
                "the second level must wait for the first to be researched");
    }

    @Test
    @DisplayName("a peasant offers the commands it can actually carry out")
    void aWorkerShowsWorkerCommands() {
        Loaded loaded = load();
        Unit peasant = null;
        for (Unit unit : loaded.world().units()) {
            if (unit.type() != null && "unit-peasant".equals(unit.type().ident())) {
                peasant = unit;
                break;
            }
        }
        Assumptions.assumeTrue(peasant != null, "the map places no peasant");

        List<String> page = panel(loaded, peasant);
        assertTrue(page.contains("repair"), "a peasant has a repair range and so repairs");
        assertTrue(page.contains("harvest"), "an empty peasant may be sent to harvest");
        // Nothing carried, so there is nothing to take home.
        assertFalse(page.contains("return-goods"), "an empty peasant has nothing to return");
        // The two build pages, which is how more buildings fit than slots.
        assertTrue(page.contains("1") && page.contains("2"), "both build pages are reachable");
    }

    @Test
    @DisplayName("the build pages list the buildings a peasant can put up")
    void theBuildPagesAreReadFromTheScripts() {
        Loaded loaded = load();
        Unit peasant = null;
        for (Unit unit : loaded.world().units()) {
            if (unit.type() != null && "unit-peasant".equals(unit.type().ident())) {
                peasant = unit;
                break;
            }
        }
        Assumptions.assumeTrue(peasant != null, "the map places no peasant");

        UnitButton[] page = loaded.ui().buttons().page("unit-peasant", 1,
                new ButtonAvailability(loaded.world(), peasant, loaded.dependencies(), false));
        List<String> values = new ArrayList<>();
        for (UnitButton button : page) {
            if (button != null && button.value() != null) {
                values.add(button.value());
            }
        }
        assertTrue(values.contains("unit-farm"), "a farm is always buildable");
        assertTrue(values.contains("unit-human-barracks"), "so is a barracks");
        assertTrue(values.contains("unit-town-hall"), "and a town hall");
    }
}
