package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * What a computer player does when it can find nothing to gather.
 *
 * <p>It asks to see more of the map. {@code AiAssignHarvester}'s two arms both
 * end the same way -- no forest within a thousand squares, or no mine -- with
 * {@code AiExplore(unit.tilePos, mask)}
 * which files a request. Then, at most
 * once every five seconds, {@code AiSendExplorers}
 * The game picks one of those requests with a number
 * off the shared stream, looks for an unexplored square near it, finds the
 * nearest idle unit that could go, and sends it.
 *
 * <p>This implementation had none of it, and the cost is not only that its computer
 * players never went looking. {@code AiSendExplorers} is a heavy spender:
 * fifty-one numbers in one cycle on {@code maps/demo/demo02}, three request
 * picks and three rounds of eight two-draw attempts at an unexplored square.
 * A draw either engine makes and the other does not puts every roll afterwards
 * out of step, and that map's first divergence sat on cycle 158 -- the cycle
 * those fifty-one are spent -- until this was written.
 *
 * <p>The other half is what made the request possible: this implementation offered every
 * idle worker wood or gold, whatever it was. A human oil tanker was being sent
 * at a forest twenty squares away that it could neither reach nor cut, so it
 * was never idle and never asked for anything.
 */
class AiExplorationTest {

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

    private static UnitType woodcutter() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        ResourceInfo wood = new ResourceInfo(Resource.WOOD);
        wood.setCapacity(100);
        type.gathering().put(Resource.WOOD, wood);
        return type;
    }

    /** Gathers oil and nothing else, which is the whole point of it. */
    private static UnitType tanker() {
        UnitType type = new UnitType("unit-human-oil-tanker");
        type.setTileSize(1, 1);
        type.setHitPoints(90);
        type.setSpeed(10);
        type.setSeaUnit(true);
        ResourceInfo oil = new ResourceInfo(Resource.OIL);
        oil.setCapacity(100);
        type.gathering().put(Resource.OIL, oil);
        return type;
    }

    private static UnitType goldMine() {
        UnitType type = new UnitType("unit-gold-mine");
        type.setTileSize(3, 3);
        type.setHitPoints(25500);
        type.setBuilding(true);
        type.setGivesResource(Resource.GOLD);
        return type;
    }

    @Test
    @DisplayName("a worker is offered only what it can carry")
    void aTankerIsNotSentAtAForest() {
        GameMap map = grass(40);
        // One tree, a long way off, and a mine: everything a peasant would
        // want and nothing a tanker can use.
        map.field(30, 30).setFlags(TileFlag.LAND_ALLOWED | TileFlag.FOREST);
        World world = new World(map, onePlayer());
        world.createUnit(goldMine(), 15, 34, 34);
        Unit oiler = world.createUnit(tanker(), 0, 5, 5);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        int before = world.randomSeed();
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 8; cycle++) {
            world.tick();
        }

        assertNotEquals(Unit.Order.HARVEST, oiler.order(),
                "the tanker was sent to gather. It carries oil and there is none on this map;"
                        + " AiAssignHarvester opens with unit.Type->ResInfo[resource] and never"
                        + " offers a type a resource it cannot hold");
        // And the proof that it was left idle rather than quietly given a job
        // it could not do: an idle worker with nothing to gather is what files
        // an exploration request, and answering one spends numbers.
        assertNotEquals(before, world.randomSeed(),
                "the player never went looking, which means its tanker was not counted as a"
                        + " worker with nothing to do -- it had been handed the forest at"
                        + " 30,30 or the mine at 34,34, neither of which it can carry");
    }

    @Test
    @DisplayName("a worker that can find nothing makes the player go and look")
    void nothingToGatherSendsAnExplorer() {
        GameMap map = grass(40);
        World world = new World(map, onePlayer());
        // Nothing to cut and nothing to dig anywhere on it.
        Unit worker = world.createUnit(woodcutter(), 0, 5, 5);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);

        // Nothing happens for the first five seconds: AiSendExplorers is gated
        // on GameCycle > LastExplorationGameCycle + 5 * CYCLES_PER_SECOND, and
        // that counter starts at nought.
        int early = world.randomSeed();
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 5; cycle++) {
            world.tick();
        }
        assertEquals(early, world.randomSeed(),
                "the player went exploring inside its own five-second gate");

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
        }

        assertNotEquals(early, world.randomSeed(),
                "the player never went looking at all. A worker that can reach no resource"
                        + " files an exploration request, and the request is answered with a"
                        + " unit sent at an unexplored square -- which is fifty-one numbers"
                        + " off the shared stream on maps/demo/demo02 and was none here");
        assertTrue(worker.order() == Unit.Order.MOVE
                        || worker.tileX() != 5 || worker.tileY() != 5,
                "the only unit this player has never went anywhere, so whatever was drawn"
                        + " above was drawn for something else");
    }
}
