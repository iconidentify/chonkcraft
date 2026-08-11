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
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Walking to a boat that is not touching the shore with its top-left corner.
 *
 * <p>Reported from play: a footman ordered onto a transport that was plainly
 * beached said "boarding Transport" in the status line and then never moved.
 *
 * <p>A transport is two squares by two. The approach routed through the tile
 * form of {@code walkTowards}, which asks {@code findRouteToOrBeside} for the
 * transport's own square or one of the eight around it -- around <em>one</em>
 * square, the footprint's top-left. A boat sits on water, so those eight are
 * usually water too. Only when the shore happened to meet the corner the
 * top-left tile belongs to did any of them turn out to be land.
 *
 * <p>So the answer was "unreachable", the passenger dropped to {@code STILL}
 * without taking a step, and because the order had been accepted and the
 * status line written before any of that, it read as the boat being in a bad
 * spot rather than as a defect.
 *
 * <p>This is the third time the same mistake has been found in this engine: a
 * worker routing to a Great Hall's origin square rather than its footprint,
 * which left peons cycling in and out of gold mines; and a chase aimed at the
 * square its target occupies, which left attackers jogging on the spot. A goal
 * is a place the mover could stand, not the place the thing it wants happens
 * to begin.
 */
class BoardingApproachTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    /**
     * A coast running east to west: water in the north, land in the south. A
     * boat placed on rows 10 and 11 has its <em>lower</em> edge against the
     * shore and its top-left tile out in open water, which is the arrangement
     * in the report and the one the old approach could not solve.
     */
    private static World coast(GameData data) {
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                map.field(x, y).setFlags(y < 12 ? TileFlag.WATER_ALLOWED : TileFlag.LAND_ALLOWED);
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
    @DisplayName("A footman boards a beached transport whose corner is out at sea")
    void aFootmanReachesABoatBeachedOnItsLowerEdge() {
        GameData data = load();
        World world = coast(data);
        UnitType footman = data.unitTypes().types().get("unit-footman");
        UnitType boat = data.unitTypes().types().get("unit-human-transport");
        assertNotNull(footman);
        assertNotNull(boat);
        Assumptions.assumeTrue(boat.tileWidth() > 1 || boat.tileHeight() > 1,
                "a one-square transport cannot show this; the bug is about footprints");

        Unit transport = world.createUnit(boat, 0, 20, 10);
        Unit passenger = world.createUnit(footman, 0, 14, 20);
        assertNotNull(transport);
        assertNotNull(passenger);

        // The fixture must actually be the awkward case, or this passes for
        // the wrong reason: no square around the boat's top-left may be land.
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                var field = world.map().fieldOrNull(
                        transport.tileX() + dx, transport.tileY() + dy);
                Assumptions.assumeTrue(field == null
                                || !field.hasFlag(TileFlag.LAND_ALLOWED),
                        "the fixture must keep the boat's top-left corner away from land");
            }
        }

        assertTrue(world.orderBoard(passenger, transport), "the order was refused");
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 30; cycle++) {
            world.tick();
            if (passenger.isAboard()) {
                break;
            }
        }

        assertTrue(passenger.isAboard(),
                "the footman never got aboard: it ended at " + passenger.tileX() + ","
                        + passenger.tileY() + ", " + passenger.distanceTo(transport)
                        + " squares from a boat whose lower edge is on the beach. The"
                        + " approach only ever offered the eight squares around the"
                        + " transport's top-left tile, and on water those are water.");
        assertEquals(1, transport.cargo().size(), "the transport is not carrying it");
    }

    /**
     * The case the widening exists for: ordering troops onto a boat that has
     * not arrived yet.
     *
     * <p>The passenger cannot reach a transport still out at sea, so a single
     * attempt answers unreachable and, before this, dropped it to
     * {@code STILL} where it stood. Upstream widens what it will settle for
     * and tries again, so the unit walks to the shoreline and waits there --
     * and that is what makes "select the army, right-click the incoming
     * transport" work at all.
     */
    @Test
    @DisplayName("Troops ordered at a boat still sailing in walk to the shore and wait")
    void troopsMeetAnIncomingTransport() {
        GameData data = load();
        World world = coast(data);
        UnitType boat = data.unitTypes().types().get("unit-human-transport");
        UnitType footman = data.unitTypes().types().get("unit-footman");

        // Far out at sea, well beyond anything a land unit could stand near.
        Unit transport = world.createUnit(boat, 0, 20, 2);
        Unit passenger = world.createUnit(footman, 0, 14, 20);
        assertNotNull(transport);
        assertNotNull(passenger);

        assertTrue(world.orderBoard(passenger, transport), "the order was refused");

        // Four seconds of sailing nowhere: the passenger should already be
        // closing on the coast rather than standing where it was ordered.
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 4; cycle++) {
            world.tick();
        }
        assertTrue(passenger.tileY() < 20,
                "the passenger never set off towards a boat it could not yet reach; it is"
                        + " still at " + passenger.tileX() + "," + passenger.tileY());

        // Now the boat comes in to the beach.
        world.orderMove(transport, 20, 10);
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 30; cycle++) {
            world.tick();
            if (passenger.isAboard()) {
                break;
            }
        }
        assertTrue(passenger.isAboard(),
                "the passenger never boarded the transport once it beached; it ended at "
                        + passenger.tileX() + "," + passenger.tileY() + ", "
                        + passenger.distanceTo(transport) + " squares away");
    }

    /**
     * The control. With the shore against the corner the top-left tile belongs
     * to, even the old approach worked -- which is why this went unnoticed, and
     * why the test above deliberately arranges the other case.
     */
    @Test
    @DisplayName("A boat beached against its top-left corner was always boardable")
    void theEasyArrangementAlsoWorks() {
        GameData data = load();
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                map.field(x, y).setFlags(x < 20 ? TileFlag.LAND_ALLOWED : TileFlag.WATER_ALLOWED);
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

        Unit transport = world.createUnit(
                data.unitTypes().types().get("unit-human-transport"), 0, 20, 10);
        Unit passenger = world.createUnit(
                data.unitTypes().types().get("unit-footman"), 0, 10, 10);
        assertNotNull(transport);
        assertNotNull(passenger);

        assertTrue(world.orderBoard(passenger, transport));
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 30; cycle++) {
            world.tick();
            if (passenger.isAboard()) {
                break;
            }
        }
        assertTrue(passenger.isAboard(), "even the easy arrangement stopped working");
    }
}
