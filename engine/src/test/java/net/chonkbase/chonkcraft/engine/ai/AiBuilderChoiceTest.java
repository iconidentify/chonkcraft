package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * Which of its workers the AI sends to put a building up.
 *
 * <p>{@code AiHelpers.Build()} is the answer, and it is not a property of a
 * unit type: {@code InitAiHelper} builds it from the button table, where every
 * {@code "build"} button's value is the building and its {@code ForUnit} mask
 * is the set of workers allowed to raise it. The engine holds that relation
 * now, so the AI asks it.
 *
 * <p>It used to guess, with {@code canGather() && landUnit()}, because nothing
 * checked the pairing and an AI down to its last oil tanker sent it to build
 * five pig farms on dry land. The guess stopped that, and stopped rather more
 * besides: it is narrower than the real rule, and the one building a tanker is
 * on the list for is an oil platform.
 */
class AiBuilderChoiceTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    /** A gatherer that is not a land unit, which is what a tanker is. */
    private static UnitType oilTanker() {
        UnitType type = new UnitType("unit-orc-oil-tanker");
        type.setTileSize(1, 1);
        type.setHitPoints(90);
        type.setSpeed(10);
        type.setSeaUnit(true);
        type.setSightRange(4);
        ResourceInfo oil = new ResourceInfo(Resource.OIL);
        oil.setCapacity(50);
        type.gathering().put(Resource.OIL, oil);
        return type;
    }

    private static UnitType platform() {
        UnitType type = new UnitType("unit-orc-oil-platform");
        type.setTileSize(3, 3);
        type.setHitPoints(650);
        type.setBuilding(true);
        type.setSightRange(3);
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 700);
        type.costs().put(Resource.WOOD, 450);
        return type;
    }

    private static Player[] onePlayer() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i == 0 ? PudMap.PlayerType.COMPUTER : PudMap.PlayerType.NOBODY,
                    PudMap.Race.ORC);
        }
        return players;
    }

    private static int count(World world, String ident) {
        int found = 0;
        for (Unit unit : world.units()) {
            if (unit.isAlive() && unit.type() != null && unit.type().ident().equals(ident)) {
                found++;
            }
        }
        return found;
    }

    private static World world(Map<String, Set<String>> builders) {
        World world = new World(grass(30), onePlayer());
        world.setBuilders(builders);
        world.player(0).set(Resource.GOLD, 5000);
        world.player(0).set(Resource.WOOD, 5000);
        return world;
    }

    @Test
    @DisplayName("the one building an oil tanker may raise, it is sent to raise")
    void aTankerBuildsWhatTheButtonTableSaysItMay() {
        UnitType rig = platform();
        World world = world(Map.of(rig.ident(), Set.of("unit-orc-oil-tanker")));
        UnitType tanker = oilTanker();
        Unit boat = world.createUnit(tanker, 0, 5, 5);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.need(rig, 1);

        // Which worker is sent is the whole subject, and it is watched
        // directly rather than through a finished platform. This map is grass
        // from edge to edge and a tanker is a sea unit, so it cannot swim to a
        // site it has to stand on -- upstream's builder is swallowed by what it
        // raises and aims at the footprint itself, not beside it
        // The platform used to appear here
        // only because the tanker was allowed to build from wherever it
        // happened to be standing.
        boolean sent = false;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 20 && !sent; cycle++) {
            world.tick();
            sent = boat.pendingBuild() == rig;
        }

        assertTrue(sent,
                "the AI would not use its tanker, because it picked its builders by landUnit()"
                        + " rather than by asking which workers may raise this building");
    }

    /**
     * Where the building goes, and why the order the ground is searched in
     * decides it.
     *
     * <p>{@code BuildingPlaceFinder} is a flood fill
     * The game whose queue takes each square's
     * neighbours in the order north, west, east, south, then the four
     * diagonals. A ring spiral, which
     * is what this implementation had, walks a square ring at each radius and so meets
     * the diagonal corner before the orthogonal neighbour. Both find a site
     * one square away; they disagree about which one, and a base laid out
     * from a different first building diverges from there on.
     */
    @Test
    @DisplayName("the site is the first square a flood fill reaches, not the first corner")
    void theSiteIsFoundByFloodFillOrder() {
        UnitType hut = platform();
        hut.setTileSize(1, 1);
        World world = world(Map.of(hut.ident(), Set.of("unit-orc-oil-tanker")));
        UnitType peon = oilTanker();
        peon.setSeaUnit(false);
        peon.setLandUnit(true);
        world.createUnit(peon, 0, 10, 10);
        // Everything but the square due north of the worker and the square
        // north-west of it is refused, so the two orders answer differently
        // and nothing else can.
        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 30; x++) {
                boolean north = x == 10 && y == 9;
                boolean northWest = x == 9 && y == 9;
                if (!north && !northWest) {
                    world.map().field(x, y).setFlags(TileFlag.LAND_ALLOWED | TileFlag.NO_BUILDING);
                }
            }
        }

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.need(hut, 1);
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 20; cycle++) {
            world.tick();
        }

        Unit built = null;
        for (Unit unit : world.units()) {
            if (unit.type() == hut) {
                built = unit;
            }
        }
        assertEquals(1, count(world, hut.ident()), "the AI never built it at all, so the"
                + " fixture proves nothing about where it would have put it");
        assertEquals(10, built.tileX(),
                "the building went to the diagonal, which is the corner a ring spiral reaches"
                        + " before it reaches the square straight north");
        assertEquals(9, built.tileY(), "and it should be the square straight north");
    }

    /**
     * The control, and the fault the guess was standing in for.
     */
    @Test
    @DisplayName("a worker the table does not list is not sent")
    void aWorkerTheTableDoesNotListIsNotSent() {
        UnitType rig = platform();
        World world = world(Map.of(rig.ident(), Set.of("unit-peon")));
        world.createUnit(oilTanker(), 0, 5, 5);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.need(rig, 1);

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 20; cycle++) {
            world.tick();
        }

        assertEquals(0, count(world, rig.ident()),
                "only a peon may raise this, and the AI has none");
        assertEquals(5000, world.player(0).get(Resource.GOLD),
                "and nothing should have been reserved for a building it cannot start");
    }
}
