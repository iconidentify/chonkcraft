package net.chonkbase.chonkcraft.data.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Reads every {@code .PUD} shipped with a real Warcraft II installation.
 *
 * <p>Skipped when no installation is configured; see
 * {@code WarArchiveRealDataTest} for how to point at one.
 */
class PudReaderRealDataTest {

    private static InstallSource install() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return install;
    }

    @Test
    @DisplayName("every shipped map parses and is internally consistent")
    void everyShippedMapParses() {
        try (InstallSource install = install()) {
            List<String> maps = install.mapNames();
            assertTrue(maps.size() >= 20, "expected the shipped map set, found " + maps.size());

            List<String> failures = new ArrayList<>();
            for (String name : maps) {
                try {
                    PudMap map = PudReader.read(install.map(name));

                    // Warcraft II maps are square and one of four fixed sizes.
                    assertEquals(map.width(), map.height(), name + ": maps are square");
                    assertTrue(List.of(32, 64, 96, 128).contains(map.width()),
                            name + ": unexpected map size " + map.width());
                    assertEquals(map.width() * map.height(), map.tiles().length,
                            name + ": terrain does not cover the map");
                    assertEquals(PudMap.PLAYER_MAX, map.players().length);

                    for (PudMap.PudUnit unit : map.units()) {
                        assertTrue(unit.x() < map.width() && unit.y() < map.height(),
                                name + ": unit at " + unit.x() + "," + unit.y()
                                        + " is off the map");
                        assertTrue(unit.player() < PudMap.PLAYER_MAX,
                                name + ": unit owned by slot " + unit.player());
                        assertTrue(!unit.typeName().isEmpty(),
                                name + ": unit type " + unit.type() + " has no name");
                    }
                } catch (RuntimeException | AssertionError e) {
                    failures.add(name + ": " + e.getMessage());
                }
            }
            if (!failures.isEmpty()) {
                fail("failed on " + failures.size() + " of " + maps.size() + " maps:\n  "
                        + String.join("\n  ", failures));
            }
        }
    }

    @Test
    @DisplayName("shipped maps place start locations and resources")
    void shippedMapsPlaceStartLocationsAndResources() {
        try (InstallSource install = install()) {
            List<String> maps = install.mapNames();

            int withStarts = 0;
            int withResources = 0;
            for (String name : maps) {
                PudMap map = PudReader.read(install.map(name));
                boolean hasStart = map.units().stream()
                        .anyMatch(u -> PudUnitTypes.isStartLocation(u.type()));
                boolean hasResource = map.units().stream()
                        .anyMatch(u -> PudUnitTypes.holdsResources(u.type())
                                && u.resourcesHeld() > 0);
                if (hasStart) {
                    withStarts++;
                }
                if (hasResource) {
                    withResources++;
                }
            }

            // A playable map needs somewhere to start and something to mine.
            assertTrue(withStarts > maps.size() / 2,
                    "only " + withStarts + " of " + maps.size()
                            + " maps have start locations");
            assertTrue(withResources > maps.size() / 2,
                    "only " + withResources + " of " + maps.size()
                            + " maps have resources");
        }
    }
}
