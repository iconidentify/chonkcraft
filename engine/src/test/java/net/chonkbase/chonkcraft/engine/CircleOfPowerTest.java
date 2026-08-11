package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.engine.campaign.Campaign;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Standing on the things a player is supposed to stand on.
 *
 * <p>Three of the shipped types are buildings with no hit points at all: the
 * circle of power, the pile of the same shape, and the oil patch. Upstream
 * treats that as the definition of a building you can walk into --
 * {@code UpdateUnitStats} sets
 * {@code FieldFlags} to {@code MapFieldNoBuilding} rather than
 * {@code MapFieldBuilding} when {@code HP_INDEX} has a maximum of nought, and
 * says so in its own comment: "A little chaos, buildings without HP can be
 * entered. The oil-patch is a very special case."
 *
 * <p>This implementation marked every building the same way, so the circle of power on
 * the second human mission -- a two by two patch of ground the mission exists
 * to get a rescued archer onto -- refused the move order outright, and the
 * hundred and five oil patches spread over twenty-nine of the fifty-two
 * campaign maps were solid rock in the middle of the sea.
 *
 * <p>The move order is the entry point, not the flag: a test that asked
 * whether {@code hitPoints()} was nought would have passed throughout, and
 * so would one that called {@code markOccupancy} directly.
 */
class CircleOfPowerTest {

    /** The mission whose objective is to get a unit onto the circle. */
    private static final String MISSION = "campaigns/human/level02h";

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private static Mission mission(GameData data, String path) {
        Mission mission = data.loadMission(path);
        Assumptions.assumeTrue(mission != null, "no campaign map available");
        return mission;
    }

    private static Unit find(World world, String ident) {
        for (Unit unit : world.units()) {
            if (unit.type() != null && ident.equals(unit.type().ident())) {
                return unit;
            }
        }
        return null;
    }

    @Test
    @DisplayName("an archer walks onto the circle of power the second mission is won on")
    void anArcherReachesTheCircleOfPower() {
        GameData data = load();
        Mission mission = mission(data, MISSION);
        World world = mission.world();

        Unit circle = find(world, "unit-circle-of-power");
        assertNotNull(circle, "the second human mission has no circle of power on it");
        assertEquals(0, circle.type().hitPoints(),
                "the fixture proves nothing unless the circle is one of the no-hit-point "
                        + "buildings: HitPoints = 0 at scripts/units.legacy-declaration:350");

        // The player's own archer, not one of the six the mission asks you to
        // rescue. Those six start in the north-west corner and the pathfinder
        // answers UNREACHABLE for every one of them, circle or no circle, so a
        // failure there would be ambiguous between "cannot stand on it" and
        // "cannot get to it". Every one of the player's own units can reach it.
        Unit archer = null;
        for (Unit unit : world.units()) {
            if (unit.type() != null && "unit-archer".equals(unit.type().ident())
                    && world.player(unit.player()) != null
                    && world.player(unit.player()).type() == PudMap.PlayerType.PERSON) {
                archer = unit;
                break;
            }
        }
        assertNotNull(archer, "the mission gives the player no archer");
        assertTrue(archer.type().speed() > 0, "the archer cannot move at all");

        int toX = circle.tileX();
        int toY = circle.tileY();
        assertTrue(Math.max(Math.abs(archer.tileX() - toX), Math.abs(archer.tileY() - toY)) > 1,
                "the fixture must start off the circle or it proves nothing");

        assertTrue(world.orderMove(archer, toX, toY),
                "the order to walk onto the circle of power was refused: a building with no "
                        + "hit points marks MapFieldBuilding instead of MapFieldNoBuilding, "
                        + "so the objective of the mission is solid ground");

        // Eight simulated minutes, which is far more than the walk needs: the
        // player's archers start six or seven squares from the circle. The
        // margin is there so a fight on the way cannot turn this into a flake.
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 480; cycle++) {
            mission.tick();
            if (archer.tileX() == toX && archer.tileY() == toY) {
                return;
            }
        }
        throw new AssertionError("the archer never reached the circle of power at "
                + toX + "," + toY + "; it stopped at " + archer.tileX() + "," + archer.tileY()
                + " on order " + archer.order());
    }

    @Test
    @DisplayName("no building without hit points marks its ground as occupied")
    void noHitPointsMeansNoObstacle() {
        GameData data = load();
        Mission mission = mission(data, MISSION);
        World world = mission.world();

        int checked = 0;
        for (Unit unit : world.units()) {
            UnitType type = unit.type();
            if (type == null || !type.building() || type.hitPoints() != 0) {
                continue;
            }
            checked++;
            for (int dy = 0; dy < Math.max(1, type.tileHeight()); dy++) {
                for (int dx = 0; dx < Math.max(1, type.tileWidth()); dx++) {
                    MapField field = world.map().fieldOrNull(unit.tileX() + dx, unit.tileY() + dy);
                    if (field == null) {
                        continue;
                    }
                    assertFalse(field.hasFlag(TileFlag.BUILDING),
                            type.ident() + " blocks the square at " + (unit.tileX() + dx) + ","
                                    + (unit.tileY() + dy) + ": unittype.cpp:645 gives a building "
                                    + "with no hit points MapFieldNoBuilding, not MapFieldBuilding");
                    assertTrue(field.hasFlag(TileFlag.NO_BUILDING),
                            type.ident() + " does not reserve the square at " + (unit.tileX() + dx)
                                    + "," + (unit.tileY() + dy) + " against building on it");
                }
            }
        }
        assertTrue(checked > 0, "the mission holds no no-hit-point building, so this proves nothing");
    }

    @Test
    @DisplayName("an oil tanker sails over an oil patch instead of running aground on it")
    void aTankerSailsOverAnOilPatch() {
        GameData data = load();
        List<String> paths = new ArrayList<>();
        for (Campaign campaign : data.campaigns()) {
            for (var step : campaign.missions()) {
                paths.add(step.mapArchivePath());
            }
        }
        Assumptions.assumeTrue(!paths.isEmpty(), "no campaign scripts in this checkout");

        UnitType tanker = data.unitTypes().types().get("unit-human-oil-tanker");
        assertNotNull(tanker, "no oil tanker in the roster");
        long mask = Unit.movementMaskFor(tanker);
        long blocking = Unit.blockingFlagsFor(tanker);
        int size = Math.max(1, tanker.tileWidth());

        int patches = 0;
        int walled = 0;
        Mission sailed = null;
        int[] route = null;
        for (String path : paths) {
            Mission mission;
            try {
                mission = data.loadMission(path);
            } catch (RuntimeException broken) {
                continue;
            }
            if (mission == null) {
                continue;
            }
            World world = mission.world();
            for (Unit unit : world.units()) {
                if (unit.type() == null || !"unit-oil-patch".equals(unit.type().ident())) {
                    continue;
                }
                patches++;
                // The terrain question, asked of the flags: another ship
                // happening to sit on a patch at map load is not this bug.
                for (int dy = 0; dy < Math.max(1, unit.type().tileHeight()); dy++) {
                    for (int dx = 0; dx < Math.max(1, unit.type().tileWidth()); dx++) {
                        MapField field = world.map()
                                .fieldOrNull(unit.tileX() + dx, unit.tileY() + dy);
                        if (field != null && field.hasFlag(TileFlag.BUILDING)) {
                            walled++;
                        }
                    }
                }
                if (sailed == null) {
                    // Somewhere clear to start from, close enough to sail in.
                    for (int radius = 2; radius < 8 && route == null; radius++) {
                        for (int dx = -radius; dx <= radius && route == null; dx++) {
                            for (int dy = -radius; dy <= radius; dy++) {
                                int x = unit.tileX() + dx;
                                int y = unit.tileY() + dy;
                                if (world.map().isFootprintFree(x, y, size, size, mask, blocking)
                                        && world.map().isFootprintFree(unit.tileX(), unit.tileY(),
                                                size, size, mask, blocking)) {
                                    sailed = mission;
                                    route = new int[] {x, y, unit.tileX(), unit.tileY()};
                                    break;
                                }
                            }
                        }
                    }
                }
            }
        }
        // Counted, not merely checked: a sweep that found no patches would
        // pass while the whole oil economy sat behind a wall.
        assertTrue(patches > 50, "only " + patches + " oil patches found across the campaigns");
        assertEquals(0, walled,
                walled + " squares of the " + patches + " oil patches are marked as holding a "
                        + "building: an oil patch has no hit points, so unittype.cpp:645 makes "
                        + "it MapFieldNoBuilding and a ship crosses it");

        assertNotNull(route, "no oil patch with open water beside it, so nothing was sailed");
        World world = sailed.world();
        Unit ship = world.createUnit(tanker, 0, route[0], route[1]);
        assertNotNull(ship, "the tanker could not be put on the water at "
                + route[0] + "," + route[1]);
        world.fog().revealAll(0);
        assertTrue(world.orderMove(ship, route[2], route[3]),
                "a tanker was refused the order to sail onto an oil patch at "
                        + route[2] + "," + route[3]);
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 120; cycle++) {
            sailed.tick();
            if (ship.tileX() == route[2] && ship.tileY() == route[3]) {
                return;
            }
        }
        throw new AssertionError("the tanker never reached the oil patch at "
                + route[2] + "," + route[3] + "; it stopped at "
                + ship.tileX() + "," + ship.tileY());
    }
}
