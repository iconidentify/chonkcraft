package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.missile.MissileClass;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The green cross that acknowledges an order.
 *
 * <p>{@code SetClickMissile("missile-green-cross")} in the prelude, thrown
 * down by {@code MakeLocalMissile} where a click was aimed. It is the only
 * confirmation an order gets when the units it was given to are off screen --
 * which is exactly the case when the click was on the minimap, and the reason
 * this needs a test rather than an eyeball.
 *
 * <p>The marker was there and drew nothing. {@code missile-green-cross} is
 * {@code missile-class-cycle-once}, a class that does not travel, and the
 * stepper special-cased only {@code missile-class-stay}. So the cross fell
 * through to the travel branch, was launched at the point it was aimed at,
 * found itself already there, and was collected as landed before a single
 * frame of it was ever drawn. Asserting that a missile was *created* would
 * have passed on that; what matters is how many cycles it is visible for.
 */
class ClickMarkerTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private static World plain(GameData data) {
        int size = 32;
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.setUnitTypes(data.unitTypes().types());
        world.setMissileTypes(data.missiles().types());
        return world;
    }

    @Test
    @DisplayName("An order marker is visible for long enough to be seen")
    void theMarkerIsVisible() {
        GameData data = load();
        World world = plain(data);

        world.markOrder(12, 14);
        world.tick();

        Missile marker = null;
        for (Missile missile : world.missiles()) {
            if ("missile-green-cross".equals(missile.type().ident())) {
                marker = missile;
            }
        }
        assertNotNull(marker, "clicking an order down left no marker at all");
        assertEquals(12, marker.tileX(), "the marker is not where the order was aimed");
        assertEquals(14, marker.tileY(), "the marker is not where the order was aimed");

        int visible = 1;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 5; cycle++) {
            world.tick();
            boolean stillThere = world.missiles().stream()
                    .anyMatch(m -> "missile-green-cross".equals(m.type().ident()));
            if (!stillThere) {
                break;
            }
            visible++;
        }
        // Upstream runs the four frames forwards and back with a cycle's pause
        // at each turn, so this is a couple of tenths of a second. One or two
        // cycles would be a flicker, and the old behaviour was zero.
        assertTrue(visible >= 5,
                "the marker was on screen for " + visible + " cycles, which nobody would see");
        assertTrue(visible < World.CYCLES_PER_SECOND * 2,
                "the marker never went away: " + visible + " cycles");
    }

    /**
     * The class the cross belongs to is the whole reason it was invisible, so
     * the data is worth pinning: if it were ever re-read as a travelling class
     * the marker would silently stop drawing again.
     */
    @Test
    @DisplayName("The click missile does not travel")
    void theClickMissileIsStationary() {
        GameData data = load();
        var cross = data.missiles().types().get("missile-green-cross");
        assertNotNull(cross, "the click missile is missing from the shipped data");
        assertEquals(MissileClass.CYCLE_ONCE, cross.missileClass());
        assertTrue(!cross.missileClass().travels(),
                "a marker that travels is a marker that arrives instantly and never draws");
    }

    /**
     * Nothing fired it, so nothing may be hurt by it. A marker that resolved
     * like a shell would splash the spot the player clicked.
     */
    @Test
    @DisplayName("An order marker harms nobody when it finishes")
    void theMarkerIsHarmless() {
        GameData data = load();
        World world = plain(data);
        var footman = data.unitTypes().types().get("unit-footman");
        assertNotNull(footman);

        var victim = world.createUnit(footman, 1, 12, 14);
        int before = victim.hitPoints();
        world.markOrder(12, 14);
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND; cycle++) {
            world.tick();
        }
        assertEquals(before, victim.hitPoints(), "the order marker did damage");
    }
}
