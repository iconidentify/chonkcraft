package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Where the AI puts a building that will store what a worker mines.
 *
 * <p>Around the mine, not around the worker. {@code AiFindBuildingPlace}
 * The game checks {@code type.CanStore[i]} before
 * anything else and hands a depot to {@code HallPlaceFinder}, which uses the
 * worker's position for one thing only -- flooding out from it to find a mine
 * that nobody has taken -- and then places the building by flooding out from
 * <em>that</em>. The ordinary finder, which
 * searches from wherever the builder is standing, is only the fallback for
 * when no usable mine can be reached.
 *
 * <p>This implementation used the ordinary finder for everything, so its first town hall
 * went up wherever its first peasant happened to be. On
 * {@code maps/skirmish/(2)x-marks-the-spot} that was 7,30 against upstream's
 * 7,25, five squares apart, and it was the whole of that map's divergence:
 * fixing it took the map from 859 findings to agreeing with the real engine
 * for nine hundred cycles.
 *
 * <p>The fixture puts the mine and the worker at opposite corners so the two
 * rules cannot give the same answer by luck.
 */
class AiHallPlacementTest {

    private static final int MINE_X = 4;
    private static final int MINE_Y = 4;
    private static final int WORKER_X = 34;
    private static final int WORKER_Y = 34;

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static Player[] onePlayer() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i == 0 ? PudMap.PlayerType.COMPUTER : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        return players;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        ResourceInfo gold = new ResourceInfo(Resource.GOLD);
        gold.setCapacity(100);
        type.gathering().put(Resource.GOLD, gold);
        return type;
    }

    private static UnitType goldMine() {
        UnitType type = new UnitType("unit-gold-mine");
        type.setTileSize(3, 3);
        type.setHitPoints(25500);
        type.setBuilding(true);
        type.setGivesResource(Resource.GOLD);
        // The hall finder only courts a mine that is a mine in law as well
        // as in name: upstream's ResourceOnMap demands CanHarvest and a
        // non-zero stock, so an empty husk gets no depot built against it.
        type.setCanHarvest(true);
        return type;
    }

    private static UnitType townHall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        type.stores().add(Resource.GOLD);
        type.stores().add(Resource.WOOD);
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 1200);
        type.costs().put(Resource.WOOD, 800);
        return type;
    }

    private static int distanceToMine(int x, int y) {
        int awayX = Math.max(0, Math.max(MINE_X - x, x - (MINE_X + 2)));
        int awayY = Math.max(0, Math.max(MINE_Y - y, y - (MINE_Y + 2)));
        return Math.max(awayX, awayY);
    }

    @Test
    @DisplayName("a town hall is sited around the gold mine, not around the peasant")
    void aDepotIsPlacedAroundTheResourceItWillServe() {
        UnitType hall = townHall();
        World world = new World(grass(40), onePlayer());
        world.setBuilders(Map.of(hall.ident(), Set.of("unit-peasant")));
        world.player(0).set(Resource.GOLD, 5000);
        world.player(0).set(Resource.WOOD, 5000);
        Unit mine = world.createUnit(goldMine(), 15, MINE_X, MINE_Y);
        mine.setResourcesHeld(25000);
        Unit worker = world.createUnit(peasant(), 0, WORKER_X, WORKER_Y);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.need(hall, 1);

        int[] site = null;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 20 && site == null; cycle++) {
            world.tick();
            if (worker.pendingBuild() == hall) {
                site = new int[] {worker.buildTileX(), worker.buildTileY()};
            }
        }

        assertNotNull(site, "the AI never sent its peasant to build the hall at all");
        int toMine = distanceToMine(site[0], site[1]);
        assertTrue(toMine <= 3,
                "the hall was sited at " + site[0] + "," + site[1] + ", which is " + toMine
                        + " squares from the mine at " + MINE_X + "," + MINE_Y + ". A depot is"
                        + " placed by flooding out from the mine it will serve, so it should"
                        + " come out against it; searching from the builder instead puts it"
                        + " thirty squares away, next to the peasant at "
                        + WORKER_X + "," + WORKER_Y);
    }
}
