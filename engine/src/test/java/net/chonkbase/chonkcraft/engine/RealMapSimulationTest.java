package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Runs the simulation on a real Warcraft II map.
 *
 * <p>Synthetic grids prove the algorithms; this proves they survive contact
 * with real terrain, where the tileset supplies the passability flags and the
 * map places seventy-odd units that all have to fit.
 */
class RealMapSimulationTest {

    private record Loaded(World world, GameData data, PudMap source) {}

    private static Loaded load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");

        PudMap source = findForestMap(install);
        Assumptions.assumeTrue(source != null, "no forest map in this installation");

        GameData data = new GameData(install);
        GameMap map = GameMap.from(source, data.loadTileset(source.tileset()).tileset());
        World world = new World(map);
        data.populate(world, source);
        return new Loaded(world, data, source);
    }

    @Test
    @DisplayName("the map's terrain flags come through from the tileset")
    void terrainFlagsComeThroughFromTheTileset() {
        Loaded loaded = load();
        GameMap map = loaded.world().map();

        int passable = 0;
        int forest = 0;
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                if (map.field(x, y).isLandPassable()) {
                    passable++;
                }
                if (map.field(x, y).isForest()) {
                    forest++;
                }
            }
        }
        int total = map.width() * map.height();
        // A playable map is mostly walkable but far from entirely so.
        assertTrue(passable > total / 4, "only " + passable + " of " + total + " squares are walkable");
        assertTrue(passable < total, "every square is walkable, so the flags did not come through");
        assertTrue(forest > 0, "a forest map with no forest");
    }

    @Test
    @DisplayName("every placed unit fits on the map and occupies its squares")
    void everyPlacedUnitFitsAndOccupies() {
        Loaded loaded = load();
        World world = loaded.world();

        long expected = loaded.source().units().stream()
                .filter(u -> !net.chonkbase.chonkcraft.data.map.PudUnitTypes.isStartLocation(u.type()))
                .count();
        assertEquals(expected, world.units().size(),
                "not every unit the map places was created");

        for (Unit unit : world.units()) {
            assertTrue(world.map().contains(unit.tileX(), unit.tileY()),
                    unit + " is off the map");
            assertTrue(world.map().field(unit.tileX(), unit.tileY()).isOccupied(),
                    unit + " did not mark its square");
            assertEquals(unit, world.unitAt(unit.tileX(), unit.tileY()),
                    "lookup did not find " + unit);
        }
    }

    @Test
    @DisplayName("a real unit walks across real terrain")
    void aRealUnitWalksAcrossRealTerrain() {
        Loaded loaded = load();
        World world = loaded.world();

        Unit walker = world.units().stream()
                .filter(u -> !u.type().building() && u.type().speed() > 0 && u.type().landUnit())
                .findFirst()
                .orElse(null);
        Assumptions.assumeTrue(walker != null, "the map places no mobile land unit");

        int startX = walker.tileX();
        int startY = walker.tileY();

        // Find a walkable square a little way off and order the unit to it.
        int targetX = -1;
        int targetY = -1;
        for (int radius = 3; radius < 12 && targetX < 0; radius++) {
            for (int dy = -radius; dy <= radius && targetX < 0; dy++) {
                for (int dx = -radius; dx <= radius && targetX < 0; dx++) {
                    int x = startX + dx;
                    int y = startY + dy;
                    if (world.map().contains(x, y)
                            && world.map().field(x, y).isLandPassable()
                            && !world.map().field(x, y).isOccupied()
                            && world.orderMove(walker, x, y)) {
                        targetX = x;
                        targetY = y;
                    }
                }
            }
        }
        Assumptions.assumeTrue(targetX >= 0, "nowhere reachable near " + walker);

        for (int cycle = 0; cycle < 5000; cycle++) {
            world.tick();
            if (walker.order() == Unit.Order.STILL && !walker.isMoving()) {
                break;
            }
        }
        assertEquals(targetX, walker.tileX(), walker + " did not reach its destination");
        assertEquals(targetY, walker.tileY());
        assertTrue(startX != targetX || startY != targetY, "the unit did not actually have to move");
    }

    @Test
    @DisplayName("a busy map ticks without units overlapping")
    void aBusyMapTicksWithoutOverlap() {
        Loaded loaded = load();
        World world = loaded.world();

        // Send every mobile unit somewhere, then run and check nothing ends up
        // standing on top of anything else.
        int ordered = 0;
        for (Unit unit : world.units()) {
            if (unit.type().building() || unit.type().speed() <= 0) {
                continue;
            }
            if (world.orderMove(unit, unit.tileX() + 4, unit.tileY() + 4)) {
                ordered++;
            }
        }
        Assumptions.assumeTrue(ordered > 0, "no unit could be ordered anywhere");

        for (int cycle = 0; cycle < 400; cycle++) {
            world.tick();
        }

        for (Unit unit : world.units()) {
            Unit occupant = world.unitAt(unit.tileX(), unit.tileY());
            assertEquals(unit, occupant,
                    unit + " shares its square with " + occupant);
        }
    }

    /** The first forest-tileset map the source offers. */
    private static PudMap findForestMap(AssetSource source) {
        for (String name : source.mapNames()) {
            PudMap map = PudReader.read(source.map(name));
            if (map.tileset() == PudMap.Tileset.FOREST) {
                return map;
            }
        }
        return null;
    }
}
