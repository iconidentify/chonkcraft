package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exact coverage for retail BNE's mutable map-square {@code 0x400}. */
class BattleNetNoBuildTest {

    private static GameMap land(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static Player[] computer() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i == 0 ? PudMap.PlayerType.COMPUTER
                            : PudMap.PlayerType.NOBODY,
                    PudMap.Race.ORC);
        }
        return players;
    }

    private static UnitType type(String ident, int size, boolean building) {
        UnitType type = new UnitType(ident);
        type.setTileSize(size, size);
        type.setHitPoints(building ? 800 : 60);
        type.setBuilding(building);
        return type;
    }

    private static boolean marked(World world, int size, int x, int y)
            throws ReflectiveOperationException {
        Field field = World.class.getDeclaredField("battleNetNoBuild");
        field.setAccessible(true);
        return ((boolean[]) field.get(world))[x + y * size];
    }

    @Test
    @DisplayName("BNE skews a clipped no-build square left on every row")
    void rightEdgeReservationKeepsTheNativeRowStrideBug() throws Exception {
        int size = 16;
        World world = new World(land(size), computer());

        world.createUnit(type("unit-great-hall", 4, true), 0, 11, 6);

        assertTrue(marked(world, size, 10, 5));
        assertTrue(marked(world, size, 14, 5));
        assertTrue(marked(world, size, 9, 6));
        assertTrue(marked(world, size, 13, 6));
        assertFalse(marked(world, size, 14, 6),
                "the clipped native pointer advances one tile short of a row");
        assertTrue(marked(world, size, 5, 10));
        assertFalse(marked(world, size, 13, 10),
                "the sixth painted row has shifted five tiles left");
    }

    @Test
    @DisplayName("BNE does not create static clearance for deposits or farms")
    void noOpBuildingCallbacksLeaveTheBitmapClear() throws Exception {
        int size = 24;
        World world = new World(land(size), computer());

        world.createUnit(type("unit-gold-mine", 3, true), 0, 4, 4);
        world.createUnit(type("unit-pig-farm", 2, true), 0, 12, 12);

        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                assertFalse(marked(world, size, x, y));
            }
        }
    }

    @Test
    @DisplayName("Cancelling an AI build clears only its exact footprint")
    void cancellationLeavesTheExpandedSkirtPainted() throws Exception {
        int size = 24;
        World world = new World(land(size), computer());
        Unit worker = world.createUnit(type("unit-peon", 1, false), 0, 2, 2);
        UnitType barracks = type("unit-orc-barracks", 3, true);

        assertTrue(world.orderBattleNetAiBuild(worker, barracks, 10, 10));
        assertTrue(marked(world, size, 9, 9));
        assertTrue(marked(world, size, 10, 10));
        assertTrue(marked(world, size, 13, 13));

        world.orderStop(worker);

        assertTrue(marked(world, size, 9, 9));
        assertFalse(marked(world, size, 10, 10));
        assertFalse(marked(world, size, 12, 12));
        assertTrue(marked(world, size, 13, 13));
    }

    @Test
    @DisplayName("BNE farm lattice leaves the peasant's own tile unbuilt")
    void farmLatticeDoesNotFoundUnderTheStandingPeasant() {
        // XOrc 10: peasant at 110,5 with hall anchor 113,5. Soft-clearing the
        // builder used to accept 109,5 (already underfoot) so the farm went
        // down immediately. Native keeps order 28 and walks to 110,4 for site
        // 109,3. The lattice must still see the peasant as occupancy.
        int size = 24;
        World world = new World(land(size), computer());
        UnitType hall = type("unit-town-hall", 3, true);
        hall.stores().add(UnitType.Resource.GOLD);
        world.createUnit(hall, 0, 16, 8);
        Unit peasant = world.createUnit(type("unit-peasant", 1, false), 0, 13, 8);
        UnitType farm = type("unit-farm", 2, true);

        int[] site = world.aiFindBattleNetFoodPlace(peasant, farm);

        assertTrue(site != null, "the farm lattice must still find a site");
        boolean underfoot = site[0] <= peasant.tileX()
                && peasant.tileX() < site[0] + 2
                && site[1] <= peasant.tileY()
                && peasant.tileY() < site[1] + 2;
        assertFalse(underfoot,
                "BNE must not place a farm on the tile the peasant already stands on");
    }
}
