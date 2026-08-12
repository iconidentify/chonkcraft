package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A shipyard stands on the shore.
 *
 * <p>{@code SHOREBUILDING} was read out of the scripts and then consulted by
 * nothing. Two upstream rules depend on it and neither existed here.
 *
 * <p> rebuilds such a type's movement mask to block
 * {@code MapFieldLandAllowed}, leaving coast and water as the ground it may
 * stand on. And {@code CanBuildHere} additionally
 * demands that the footprint cover at least one coast square --
 * {@code HasAtLeastOneCoastTile} -- which is what keeps it against the beach
 * rather than out in open water, where the terrain rule alone would allow it.
 *
 * <p>Instead every building, shore or not, was asked for {@code LAND_ALLOWED},
 * so a shipyard could be founded well inland with no route to the sea it exists
 * to launch ships into.
 */
class ShoreBuildingTest {

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");
        return new GameData(assets);
    }

    /**
     * Water in the north, a one-square coast ribbon at row 10, land south of
     * it -- the arrangement every Warcraft II shoreline has.
     */
    private static World shore(GameData data) {
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 40; x++) {
                long flag = y < 10 ? TileFlag.WATER_ALLOWED
                        : y == 10 ? TileFlag.COAST_ALLOWED
                        : TileFlag.LAND_ALLOWED;
                map.field(x, y).setFlags(flag);
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
        return world;
    }

    @Test
    @DisplayName("A shipyard goes on the coast, not inland and not out at sea")
    void aShipyardBelongsOnTheShore() {
        GameData data = load();
        World world = shore(data);
        UnitType yard = data.unitTypes().types().get("unit-human-shipyard");
        assertNotNull(yard);
        Assumptions.assumeTrue(yard.shoreBuilding(),
                "the fixture needs a type the scripts mark as a shore building");
        int height = Math.max(1, yard.tileHeight());

        // Straddling the ribbon: the bottom row of the footprint is the coast.
        assertTrue(world.canPlaceBuilding(yard, 5, 10 - (height - 1)),
                "a shipyard could not be built against the shore, which is the only place"
                        + " it belongs");

        // Well inland. Before the fix this was the only place it could go.
        assertFalse(world.canPlaceBuilding(yard, 5, 20),
                "a shipyard was founded inland, with no water to launch a ship into");

        // Out at sea. The terrain rule alone permits this, which is why
        // CanBuildHere demands a coast square as well.
        assertFalse(world.canPlaceBuilding(yard, 5, 2),
                "a shipyard was founded in open water: HasAtLeastOneCoastTile is what"
                        + " stops that, and the terrain mask on its own does not");
    }

    /** An ordinary building is unaffected: it still wants dry land. */
    @Test
    @DisplayName("A barracks still goes on land and not on the coast")
    void anOrdinaryBuildingIsUnchanged() {
        GameData data = load();
        World world = shore(data);
        UnitType barracks = data.unitTypes().types().get("unit-human-barracks");
        assertNotNull(barracks);
        assertFalse(barracks.shoreBuilding(), "the control must not be a shore building");

        assertTrue(world.canPlaceBuilding(barracks, 5, 20), "a barracks cannot be built on land");
        assertFalse(world.canPlaceBuilding(barracks, 5, 2), "a barracks was built on water");
        assertFalse(world.canPlaceBuilding(barracks, 5, 10),
                "a barracks was built on the coast ribbon, which upstream blocks for"
                        + " everything that is not a shore building");
    }

    @Test
    @DisplayName("an accepted shipyard order reaches a shoreline foundation")
    void anAcceptedShipyardOrderReachesAFoundation() {
        GameData data = load();
        World world = shore(data);
        UnitType workerType = data.unitTypes().types().get("unit-peasant");
        UnitType yard = data.unitTypes().types().get("unit-human-shipyard");
        assertNotNull(workerType);
        assertNotNull(yard);
        world.player(0).set(UnitType.Resource.GOLD, 10_000);
        world.player(0).set(UnitType.Resource.WOOD, 10_000);
        Unit worker = world.createUnit(workerType, 0, 5, 14);
        int siteY = 10 - (Math.max(1, yard.tileHeight()) - 1);

        assertTrue(world.orderBuild(worker, yard, 5, siteY),
                "the green shoreline site was not accepted");
        Unit foundation = null;
        for (int cycle = 0; cycle < 4_000 && foundation == null; cycle++) {
            world.tick();
            for (Unit unit : world.units()) {
                if (unit.type() == yard && unit.tileX() == 5 && unit.tileY() == siteY) {
                    foundation = unit;
                    break;
                }
            }
        }
        assertNotNull(foundation,
                "the accepted shipyard order never laid its shoreline foundation");
    }
}
