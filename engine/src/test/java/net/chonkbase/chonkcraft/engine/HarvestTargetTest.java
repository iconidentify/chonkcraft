package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A woodcutter has to know where the trees still are.
 *
 * <p>Written from a player's report, made once a separate fix got felled ground
 * drawing correctly and the behaviour became visible: "trees are being removed
 * as they are harvested but our tree cutting peons don't know where the new
 * edge of the forest is or something. they stand offset quite a bit ... this
 * guy looks like he's chopping nothing." The screenshots showed a peasant
 * standing in the middle of a field of stumps it had cleared itself, several
 * squares from the wood, swinging at bare ground.
 *
 * <p>The square a woodcutter remembers is the one it just felled -- a forest
 * square holds a hundred wood and a peasant carries a hundred, so a full load
 * is exactly one tree. {@code COrder_Resource::WaitInDepot} searches out from
 * that square for one that still has a tree on it and comes out of the hall
 * facing what it found; this implementation went straight back to the stump and only
 * looked for another tree once it was standing in it. On three peasants
 * working one wood on {@code campaigns/human/level04h}, 77 return trips out of
 * 77 came out of the hall aimed at bare ground.
 *
 * <p>The other half is the same missing search from the other end. Upstream's
 * {@code MoveToResource_Terrain} answers an unreachable tree by looking out
 * from where the worker is standing and going to the nearest one it can get
 * at; this implementation dropped the order to STILL. A click on any tree more than one
 * square inside a stand was accepted and then did nothing for the rest of the
 * game, and so was a click on a tree whose approaches other peasants happened
 * to be standing on.
 *
 * <p>So nothing here asserts on a coordinate. Each test asks whether the
 * square the worker is aimed at still has a tree on it, and whether the wood
 * arrives.
 */
class HarvestTargetTest {

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No authenticated BNE asset source configured.");
        return new GameData(assets);
    }

    /** A hall on clear ground, a stand of trees a short walk from it. */
    private record Camp(World world, GameMap map, Unit hall, int treeX, int treeY) {}

    private static Camp camp() {
        GameData data = load();
        PudMap pud = data.campaignMap("campaigns/human/level01h");
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        GameMap map = GameMap.from(pud, data.loadTileset(pud.tileset()).tileset());
        World world = new World(map, Player.from(pud));
        data.configureWorld(world, pud.tileset());
        UnitType hallType = data.unitTypes().types().get("unit-town-hall");
        assertNotNull(hallType, "no town hall in the shipped unit types");

        int[] spot = clearing(map, hallType.tileWidth(), hallType.tileHeight());
        Assumptions.assumeTrue(spot != null, "no wood with room for a hall beside it");
        Unit hall = world.createUnit(hallType, 0, spot[0], spot[1]);
        assertNotNull(hall, "the hall would not go down");
        // The whole map lit, so that nothing here is also a test of what a
        // player has scouted.
        world.fog().addSight(0, 0, 0, map.width(), map.height(), 0);
        return new Camp(world, map, hall, spot[2], spot[3]);
    }

    /** A peasant on the first free square it can find beside the hall. */
    private static Unit peasant(Camp camp) {
        GameData data = load();
        UnitType type = data.unitTypes().types().get("unit-peasant");
        assertNotNull(type, "no peasant in the shipped unit types");
        Unit hall = camp.hall();
        for (int radius = 1; radius <= 8; radius++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int x = hall.tileX() + hall.type().tileWidth() / 2 + dx;
                    int y = hall.tileY() + hall.type().tileHeight() / 2 + dy;
                    if (!isClear(camp.map(), x, y, 1, 1) || camp.world().unitAt(x, y) != null) {
                        continue;
                    }
                    Unit made = camp.world().createUnit(type, 0, x, y);
                    if (made != null) {
                        return made;
                    }
                }
            }
        }
        Assumptions.abort("nowhere to stand a peasant beside the hall");
        return null;
    }

    @Test
    @DisplayName("a peasant back out of the hall is sent to a tree, not to the stump it left")
    void aReturningWoodcutterIsAimedAtATreeThatIsStillThere() {
        Camp camp = camp();
        World world = camp.world();
        Unit worker = peasant(camp);
        assertTrue(camp.map().field(camp.treeX(), camp.treeY()).isForest(),
                "the fixture must start on a tree or it proves nothing");
        assertTrue(world.orderHarvest(worker, camp.treeX(), camp.treeY()),
                "the peasant would not take a chop order on a tree at the edge of the wood");

        int exits = 0;
        int exitsAimedAtAStump = 0;
        int firstBadExit = -1;
        boolean wasInside = false;
        for (int cycle = 1; cycle <= World.CYCLES_PER_SECOND * 300; cycle++) {
            world.tick();
            boolean inside = !worker.isOnMap();
            if (wasInside && !inside) {
                exits++;
                boolean stillATree = camp.map().contains(
                                worker.resourceTileX(), worker.resourceTileY())
                        && camp.map().field(worker.resourceTileX(), worker.resourceTileY())
                                .isForest();
                if (!stillATree) {
                    exitsAimedAtAStump++;
                    if (firstBadExit < 0) {
                        firstBadExit = cycle;
                    }
                }
            }
            wasInside = inside;
        }

        // An empty sweep would pass this vacuously, and a peasant that never
        // reached the wood at all is the interesting failure.
        assertTrue(exits >= 2,
                "the peasant only came out of the hall " + exits + " times in five minutes, so "
                        + "this proves nothing about where it was sent");
        assertEquals(0, exitsAimedAtAStump,
                exitsAimedAtAStump + " of " + exits + " trips out of the hall sent the peasant "
                        + "back to a square with no tree on it, the first at cycle " + firstBadExit
                        + ": WaitInDepot searches out from the felled square for one that still "
                        + "has wood before it picks which face of the hall to come out of");
    }

    @Test
    @DisplayName("a chop order in the middle of a wood fells the nearest tree that can be reached")
    void aTreeDeepInsideAStandSendsTheWorkerToTheEdgeOfIt() {
        Camp camp = camp();
        World world = camp.world();
        GameMap map = camp.map();
        Unit worker = peasant(camp);

        int deepX = -1;
        int deepY = -1;
        for (int y = 1; y < map.height() - 1 && deepX < 0; y++) {
            for (int x = 1; x < map.width() - 1; x++) {
                if (allEightForest(map, x, y)) {
                    deepX = x;
                    deepY = y;
                    break;
                }
            }
        }
        Assumptions.assumeTrue(deepX >= 0, "no stand on this map is two squares deep");
        // The control the whole test rests on: this square cannot be worked
        // from anywhere, because everything touching it is also trees.
        assertTrue(allEightForest(map, deepX, deepY),
                "the fixture must aim at a tree with no walkable square beside it");

        assertTrue(world.orderHarvest(worker, deepX, deepY),
                "the peasant refused a chop order the player is allowed to give");
        for (int cycle = 1; cycle <= World.CYCLES_PER_SECOND * 120; cycle++) {
            world.tick();
        }

        assertTrue(worker.carried() > 0 || world.players()[0].get(UnitType.Resource.WOOD) > 1000,
                "two minutes after being told to chop the middle of the wood the peasant is at "
                        + worker.tileX() + "," + worker.tileY() + " in order " + worker.order()
                        + " carrying nothing: MoveToResource_Terrain answers an unreachable tree "
                        + "by searching out from the worker for one it can reach");
        assertTrue(map.contains(worker.resourceTileX(), worker.resourceTileY()),
                "the peasant is working a square that is not on the map");
        assertTrue(hasWalkableNeighbour(map, worker.resourceTileX(), worker.resourceTileY()),
                "the peasant ended up working " + worker.resourceTileX() + ","
                        + worker.resourceTileY() + ", which has no square beside it to stand on");
    }

    @Test
    @DisplayName("four peasants sent to one tree all end up cutting wood")
    void everyPeasantSentToTheSameTreeFindsOneOfItsOwn() {
        Camp camp = camp();
        World world = camp.world();
        List<Unit> crew = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            crew.add(peasant(camp));
        }
        assertEquals(4, crew.size(), "the fixture needs four peasants to say anything");
        for (Unit worker : crew) {
            assertTrue(world.orderHarvest(worker, camp.treeX(), camp.treeY()),
                    "a peasant refused the chop order the other three took");
        }

        // Only three of the tree's eight neighbours can be stood on at once,
        // so at least one of the four has to be sent somewhere else. That is
        // the case that used to stand a peasant down for good: the route came
        // back empty and the order fell to STILL.
        int[] delivered = new int[crew.size()];
        int[] carried = new int[crew.size()];
        for (int cycle = 1; cycle <= World.CYCLES_PER_SECOND * 600; cycle++) {
            world.tick();
            for (int i = 0; i < crew.size(); i++) {
                Unit worker = crew.get(i);
                if (carried[i] > 0 && worker.carried() == 0) {
                    delivered[i]++;
                }
                carried[i] = worker.carried();
            }
        }

        StringBuilder idle = new StringBuilder();
        int working = 0;
        for (int i = 0; i < crew.size(); i++) {
            if (delivered[i] > 0) {
                working++;
            } else {
                Unit worker = crew.get(i);
                idle.append(" peasant ").append(i).append(" stood at ").append(worker.tileX())
                        .append(",").append(worker.tileY()).append(" in order ")
                        .append(worker.order()).append(";");
            }
        }
        assertEquals(crew.size(), working,
                working + " of the four peasants ever banked a load in ten minutes:" + idle
                        + " a chop order that cannot be routed sends the worker to the nearest "
                        + "tree it can reach, it does not stand it down");
        assertTrue(world.players()[0].get(UnitType.Resource.WOOD) >= 3000,
                "four peasants brought in only "
                        + (world.players()[0].get(UnitType.Resource.WOOD) - 1000)
                        + " wood in ten minutes, which is not four peasants working");
    }

    @Test
    @DisplayName("a peasant boxed in when it is told to chop starts as soon as the way clears")
    void aWorkerPennedInWhenTheOrderArrivesStartsOnceItCanGetOut() {
        Camp camp = camp();
        World world = camp.world();
        // Out of arm's reach of the wood, on purpose. A peasant that happens
        // to be standing next to some other tree never has to plan a route at
        // all -- it is already beside a resource and simply starts chopping --
        // and a fixture like that passes whatever the code does. The first
        // draft of this test did exactly that and passed against the defect.
        UnitType peasantType = load().unitTypes().types().get("unit-peasant");
        assertNotNull(peasantType, "no peasant in the shipped unit types");
        Unit worker = null;
        for (int radius = 2; radius <= 10 && worker == null; radius++) {
            for (int dy = -radius; dy <= radius && worker == null; dy++) {
                for (int dx = -radius; dx <= radius && worker == null; dx++) {
                    int x = camp.hall().tileX() + camp.hall().type().tileWidth() / 2 + dx;
                    int y = camp.hall().tileY() + camp.hall().type().tileHeight() / 2 + dy;
                    if (!isClear(camp.map(), x, y, 1, 1) || world.unitAt(x, y) != null
                            || touchesForest(camp.map(), x, y)) {
                        continue;
                    }
                    worker = world.createUnit(peasantType, 0, x, y);
                }
            }
        }
        Assumptions.assumeTrue(worker != null, "nowhere near the hall to stand a peasant clear "
                + "of the trees");
        assertTrue(!touchesForest(camp.map(), worker.tileX(), worker.tileY()),
                "the peasant is already beside a tree, so it never has to plan a route and "
                        + "the fixture cannot tell the two behaviours apart");

        // Pen it in: every square it could step to is filled with somebody
        // else. This is not a contrived arrangement. It is what happens on
        // campaigns/human/level04h, where the open ground beside the hall is
        // one square wide, four peasants come out into it, and all four are
        // told to chop at once: three of them are shoulder to shoulder behind
        // the one at the open end for the two or three seconds it takes that
        // one to walk away.
        UnitType blockerType = load().unitTypes().types().get("unit-footman");
        assertNotNull(blockerType, "no footman in the shipped unit types");
        List<Unit> pen = new ArrayList<>();
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                int x = worker.tileX() + dx;
                int y = worker.tileY() + dy;
                if (!isClear(camp.map(), x, y, 1, 1) || world.unitAt(x, y) != null) {
                    continue;
                }
                Unit blocker = world.createUnit(blockerType, 0, x, y);
                if (blocker != null) {
                    pen.add(blocker);
                }
            }
        }
        // The fixture proves nothing unless it really is penned in, and
        // nothing unless it could get out afterwards.
        assertTrue(pen.size() >= 1, "nothing was standing in the peasant's way, so this fixture "
                + "cannot tell the two behaviours apart");
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                int x = worker.tileX() + dx;
                int y = worker.tileY() + dy;
                assertTrue(!isClear(camp.map(), x, y, 1, 1) || world.unitAt(x, y) != null,
                        "the peasant can still step to " + x + "," + y + ", so it was never "
                                + "penned in and the fixture proves nothing");
            }
        }

        assertTrue(world.orderHarvest(worker, camp.treeX(), camp.treeY()),
                "the peasant would not take the chop order");
        // Long enough that a worker which gives up has given up.
        for (int cycle = 1; cycle <= World.CYCLES_PER_SECOND * 5; cycle++) {
            world.tick();
        }
        // And now the way is clear. Upstream waits ten cycles and plans again
        // -- "unit.Wait = 10" on the PF_UNREACHABLE arm of
        // MoveToResource_Terrain, which then re-aims and returns "still on the
        // way". A worker stood down instead has no way back: on level04h three
        // of four peasants dropped to STILL on the first cycle and had not
        // taken one step half an hour later, while the same two peasants from
        // the same squares, told to chop after the way was clear, brought in
        // 43 and 45 loads.
        for (Unit blocker : pen) {
            world.remove(blocker);
        }
        for (int cycle = 1; cycle <= World.CYCLES_PER_SECOND * 300; cycle++) {
            world.tick();
        }

        assertTrue(world.players()[0].get(UnitType.Resource.WOOD) > 1000,
                "five minutes after the way out cleared the peasant is at " + worker.tileX() + ","
                        + worker.tileY() + " in order " + worker.order() + " and has banked "
                        + "nothing: a route that cannot be planned this cycle is somebody "
                        + "standing in the way, not a reason to stand the worker down for good");
    }

    /** Whether a square has a tree next to it, so that standing on it is chopping. */
    private static boolean touchesForest(GameMap map, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (map.contains(x + dx, y + dy) && map.field(x + dx, y + dy).isForest()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean allEightForest(GameMap map, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (!map.contains(x + dx, y + dy) || !map.field(x + dx, y + dy).isForest()) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean hasWalkableNeighbour(GameMap map, int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dy != 0) && isClear(map, x + dx, y + dy, 1, 1)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean isClear(GameMap map, int x, int y, int width, int height) {
        for (int dy = 0; dy < height; dy++) {
            for (int dx = 0; dx < width; dx++) {
                if (!map.contains(x + dx, y + dy)
                        || !map.field(x + dx, y + dy).isLandPassable()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * A tree at the edge of a thick stand with room for a hall a few squares
     * away, so that banking a load is a walk rather than a step.
     *
     * @return the hall's corner and the tree, or {@code null}
     */
    private static int[] clearing(GameMap map, int hallWidth, int hallHeight) {
        for (int y = 2; y < map.height() - 2; y++) {
            for (int x = 2; x < map.width() - 2; x++) {
                if (!map.field(x, y).isForest() || !hasWalkableNeighbour(map, x, y)) {
                    continue;
                }
                if (forestWithin(map, x, y, 6) < 45) {
                    continue;
                }
                for (int dy = -12; dy <= 12; dy++) {
                    for (int dx = -12; dx <= 12; dx++) {
                        int distance = Math.max(Math.abs(dx), Math.abs(dy));
                        if (distance < 4 || distance > 11) {
                            continue;
                        }
                        if (isClear(map, x + dx, y + dy, hallWidth, hallHeight)) {
                            return new int[] {x + dx, y + dy, x, y};
                        }
                    }
                }
            }
        }
        return null;
    }

    private static int forestWithin(GameMap map, int x, int y, int range) {
        int count = 0;
        for (int dy = -range; dy <= range; dy++) {
            for (int dx = -range; dx <= range; dx++) {
                if (map.contains(x + dx, y + dy) && map.field(x + dx, y + dy).isForest()) {
                    count++;
                }
            }
        }
        return count;
    }
}
