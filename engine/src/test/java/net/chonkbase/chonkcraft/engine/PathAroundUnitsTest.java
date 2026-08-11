package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Walking past people who are standing in the way.
 *
 * <p>The planner charged a stationary unit five points on a ten point step, so
 * it routed straight through a crowd. The mover then refused the step -- it
 * asks a stricter question than the planner did -- waited ten cycles,
 * re-planned, and got the identical route back, forever. That loop is what
 * "obtuse movement and hesitation" was.
 *
 * <p>These are behavioural tests: they order a unit somewhere and ask whether
 * it arrives. A test of the planner alone would not have caught it, because
 * the planner was internally consistent; the disagreement was between the
 * planner and the mover.
 */
class PathAroundUnitsTest {

    private static World open(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        return new World(map, players);
    }

    private static UnitType soldier(String ident) {
        UnitType type = new UnitType(ident);
        type.setName(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        type.setSightRange(9);
        type.setReactRangePerson(6);
        type.setReactRangeComputer(6);
        type.setMissile("missile-none");
        return type;
    }

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    /**
     * A wall of standing units with one gap, and somebody told to walk past
     * it.
     */
    @Test
    @DisplayName("A unit walks around a crowd instead of stopping at it")
    void aUnitGoesRound() {
        GameData data = load();
        var types = data.unitTypes().types();
        UnitType footman = types.get("unit-footman");

        World world = open(32);
        world.setUnitTypes(types);

        // A line of your own standing footmen across the middle of the map,
        // with a gap at the very top. Yours, deliberately: a route through an
        // enemy is legitimate -- you fight your way past, and upstream charges
        // twice the moving cost for it rather than refusing -- whereas your
        // own men are simply a wall, and it was your own men jamming each
        // other that made this look broken.
        for (int y = 3; y < 30; y++) {
            world.createUnit(footman, 0, 16, y);
        }
        Unit walker = world.createUnit(footman, 0, 8, 16);
        assertTrue(world.orderMove(walker, 24, 16), "the order was refused outright");

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 120; cycle++) {
            world.tick();
            if (walker.tileX() >= 24) {
                break;
            }
        }
        assertTrue(walker.tileX() >= 24,
                "the walker stopped at " + walker.tileX() + "," + walker.tileY()
                        + "; it should have gone round the line rather than into it");
    }

    /**
     * A search that runs out of budget answers with the best it reached, not
     * with nothing.
     *
     * <p>Upstream draws a careful line here and so does this: an exhausted
     * *open set* means there is genuinely no route and the answer is
     * unreachable, while an exhausted *iteration budget* only means the search
     * was taking a while, and the unit should still set off. Blurring the two
     * would send a land unit trudging to the shoreline when told to walk
     * across the sea.
     */
    @Test
    @DisplayName("A search that runs out of budget still returns the way it got")
    void anExhaustedSearchStillGoesSomewhere() {
        World world = open(64);
        // A budget far too small for the distance: the search cannot finish,
        // but a route plainly exists.
        var thrifty = new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder(world.map(), 12);
        var path = thrifty.find(2, 2, 60, 60, TileFlag.LAND_ALLOWED, 1, 1);
        assertEquals(net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                path.result(),
                "an unfinished search should hand back the best it reached");
        assertTrue(path.headings().length > 0, "and that should be somewhere to walk");

        // Whereas a goal that genuinely cannot be reached is still refused.
        World sealed = open(32);
        for (int i = 0; i < 32; i++) {
            sealed.map().field(16, i).setFlags(TileFlag.UNPASSABLE);
        }
        var honest = new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder(sealed.map());
        assertEquals(net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.UNREACHABLE,
                honest.find(2, 2, 30, 30, TileFlag.LAND_ALLOWED, 1, 1).result(),
                "a walled-off goal is unreachable, not merely slow");
    }

    @Test
    @DisplayName("a dying unit remains the pathfinder's first cached occupant")
    void aDyingUnitAheadOfAMovingOccupantStillRefusesTheTile() {
        World world = open(15);
        world.setAllied(0, 1, false);
        for (int y = 0; y < 15; y++) {
            for (int x = 0; x < 15; x++) {
                world.map().field(x, y).setFlags(TileFlag.UNPASSABLE);
            }
        }
        for (int x = 2; x <= 9; x++) {
            world.map().field(x, 5).setFlags(TileFlag.LAND_ALLOWED);
        }

        UnitType footman = soldier("unit-footman");
        Unit mover = world.createUnit(footman, 0, 5, 5);
        Unit moving = world.createUnit(footman, 0, 7, 5);
        UnitType doomedType = soldier("unit-doomed-footman");
        AnimationSet doomedAnimations = new AnimationSet("doomed");
        doomedAnimations.put(AnimationSet.State.DEATH, Animation.parse("death",
                java.util.List.of("unbreakable begin", "wait 100",
                        "unbreakable end", "wait 1")));
        doomedType.setAnimationSet(doomedAnimations);
        Unit dying = world.createUnit(doomedType, 0, 8, 5);
        world.kill(dying, null);
        assertTrue(world.orderMove(moving, 8, 5));
        world.tick();
        moving.setWalkHolding(true);
        Unit target = world.createUnit(footman, 1, 9, 5);
        world.fog().revealAll(0);

        assertTrue(world.orderAttack(mover, target), "the attack was refused");
        world.tick();

        assertEquals(5, mover.tileX(),
                "AStar ignored the dying first occupant and routed through the moving"
                        + " unit behind it");
    }

    @Test
    @DisplayName("a vanishing cache entry is skipped when a moving unit shares its tile")
    void aVanishingUnitAheadOfAMovingOccupantDoesNotRefuseTheTile() {
        World world = open(15);
        world.setAllied(0, 1, false);
        for (int y = 0; y < 15; y++) {
            for (int x = 0; x < 15; x++) {
                world.map().field(x, y).setFlags(TileFlag.UNPASSABLE);
            }
        }
        for (int x = 2; x <= 9; x++) {
            world.map().field(x, 5).setFlags(TileFlag.LAND_ALLOWED);
        }

        UnitType footman = soldier("unit-footman");
        Unit mover = world.createUnit(footman, 0, 5, 5);
        UnitType bodyType = soldier("unit-orc-dead-body");
        bodyType.setVanishes(true);
        world.createUnit(bodyType, 0, 8, 5);
        Unit moving = world.createUnit(footman, 0, 7, 5);
        assertTrue(world.orderMove(moving, 8, 5));
        world.tick();
        moving.setWalkHolding(true);
        Unit target = world.createUnit(footman, 1, 9, 5);
        world.fog().revealAll(0);

        assertTrue(world.orderAttack(mover, target), "the attack was refused");
        world.tick();

        assertEquals(6, mover.tileX(),
                "CUnitTypeFinder should skip the vanishing first cache entry and"
                        + " charge the moving unit behind it");
    }

    /**
     * A worker sent to a tree with a building against it chops it anyway.
     *
     * <p>The planner lets a route end on a square that cannot be stood on --
     * a move order aimed at a building or at somebody's head should still set
     * off, and the walk stops short on arrival. That allowance is wrong for a
     * destination the engine invented for itself. Asked for a square beside a
     * tree, it offered the corner of a town hall that happened to touch it;
     * the walker was then refused the last step, waited, re-planned, and was
     * handed the same square again for as long as the game ran.
     */
    @Test
    @DisplayName("A worker chops a tree that a building is standing against")
    void aWorkerGetsToATreeBesideABuilding() {
        GameData data = load();
        var types = data.unitTypes().types();
        UnitType peasant = types.get("unit-peasant");
        UnitType hall = types.get("unit-town-hall");

        World world = open(32);
        world.setUnitTypes(types);

        // A tree at 20,10, and a town hall four squares across at 16,10, so
        // it covers 16..19 by 10..13. Two of the tree's eight neighbours --
        // 19,10 and 19,11 -- are hall, and 19,10 is the one a walker coming
        // from the west reaches first. It is the same shape as the corner of
        // the town hall on the first human mission, which is where this was
        // found.
        var tree = world.map().field(20, 10);
        tree.setFlags(TileFlag.LAND_ALLOWED | TileFlag.FOREST | TileFlag.UNPASSABLE);
        tree.setValue(GameMap.WOOD_PER_FOREST_TILE);
        world.createUnit(hall, 0, 16, 10);

        Unit worker = world.createUnit(peasant, 0, 10, 9);
        assertTrue(world.orderHarvest(worker, 20, 10), "the order was refused outright");

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 60; cycle++) {
            world.tick();
            if (worker.carried() > 0) {
                break;
            }
        }
        assertTrue(worker.carried() > 0,
                "the worker never chopped; it stopped at "
                        + worker.tileX() + "," + worker.tileY());
    }

    /**
     * An order aimed at a square that cannot be entered stops beside it.
     *
     * <p>This is the other half of the planner's goal allowance, and it had
     * never been written: the walk simply jammed against the last square,
     * waited ten cycles, re-planned, and jammed again.
     */
    @Test
    @DisplayName("A move onto a building stops against it rather than jamming")
    void aMoveOntoABuildingStopsShort() {
        GameData data = load();
        var types = data.unitTypes().types();
        UnitType footman = types.get("unit-footman");
        UnitType hall = types.get("unit-town-hall");

        World world = open(32);
        world.setUnitTypes(types);
        world.createUnit(hall, 0, 20, 15);
        Unit walker = world.createUnit(footman, 0, 10, 16);

        assertTrue(world.orderMove(walker, 20, 16), "the order was refused outright");
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 60; cycle++) {
            world.tick();
            if (walker.order() == Unit.Order.STILL) {
                break;
            }
        }
        assertEquals(Unit.Order.STILL, walker.order(),
                "the walker is still trying to get inside the town hall");
        assertTrue(walker.tileX() >= 18,
                "it should have walked up to the hall, not given up at " + walker.tileX());
    }

    /**
     * A flyer crosses what stops a walker.
     *
     * <p>The planner used to decide for itself that a building is a wall and
     * a body is a nuisance. That is a footman's view and the reverse of a
     * gryphon's, which crosses buildings and ground troops without noticing
     * and is stopped only by another flyer. The mover had always asked the
     * unit properly, through {@code blockingFlags}, so the two disagreed about
     * every air unit on the map: routed the long way round a keep, and
     * straight through each other.
     */
    @Test
    @DisplayName("A flyer crosses forest and buildings that stop a footman")
    void aFlyerGoesOverWhatStopsAWalker() {
        GameData data = load();
        var types = data.unitTypes().types();
        UnitType gryphon = types.get("unit-gryphon-rider");
        UnitType footman = types.get("unit-footman");
        UnitType hall = types.get("unit-town-hall");

        World world = open(32);
        world.setUnitTypes(types);

        // A forest wall right across the map, with a town hall set into it.
        for (int y = 0; y < 32; y++) {
            world.map().field(16, y)
                    .setFlags(TileFlag.LAND_ALLOWED | TileFlag.FOREST | TileFlag.UNPASSABLE);
            world.map().field(17, y)
                    .setFlags(TileFlag.LAND_ALLOWED | TileFlag.FOREST | TileFlag.UNPASSABLE);
        }
        world.createUnit(hall, 0, 12, 14);

        // The walker cannot get there at all, which is what makes the flyer's
        // crossing mean something.
        // The walker takes the order and never crosses, which is what makes
        // the flyer's crossing mean something. Upstream's CommandMove takes
        // every move order and leaves the walk to discover the wall.
        Unit walker = world.createUnit(footman, 0, 8, 20);
        assertTrue(world.orderMove(walker, 24, 20), "the order is taken, as upstream takes it");

        Unit flyer = world.createUnit(gryphon, 0, 8, 16);
        assertTrue(world.orderMove(flyer, 24, 16), "the flyer was refused outright");
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 120; cycle++) {
            world.tick();
            if (flyer.tileX() >= 24) {
                break;
            }
            assertEquals(16, flyer.tileY(),
                    "nothing up there to fly round; it should have gone straight");
        }
        assertTrue(flyer.tileX() >= 24,
                "the flyer stopped at " + flyer.tileX() + "," + flyer.tileY());
    }

    /** What does stop a flyer is another flyer. */
    @Test
    @DisplayName("A flyer goes round other flyers")
    void aFlyerGoesRoundOtherFlyers() {
        GameData data = load();
        var types = data.unitTypes().types();
        UnitType gryphon = types.get("unit-gryphon-rider");

        World world = open(32);
        world.setUnitTypes(types);

        // Gryphons are two tiles by two, so a solid wall of them is one every
        // second row. It runs from 4 down to 29, leaving a gap at the top.
        for (int y = 4; y < 30; y += 2) {
            world.createUnit(gryphon, 0, 16, y);
        }
        Unit flyer = world.createUnit(gryphon, 0, 8, 16);
        assertTrue(world.orderMove(flyer, 24, 16), "the order was refused outright");

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 120; cycle++) {
            world.tick();
            if (flyer.tileX() >= 24) {
                break;
            }
        }
        assertTrue(flyer.tileX() >= 24,
                "the flyer stopped at " + flyer.tileX() + "," + flyer.tileY()
                        + "; it should have gone round the flock rather than into it");
    }

    /**
     * A unit two tiles across does not count itself as in its own way.
     *
     * <p>Every step a 2x2 unit takes overlaps the squares it is already
     * standing on. Now that a standing unit is a wall to the planner rather
     * than a small charge, failing to exclude the searcher would wall every
     * such unit into place.
     */
    @Test
    @DisplayName("A 2x2 unit is not blocked by the squares it is standing on")
    void aTwoByTwoUnitDoesNotBlockItself() {
        GameData data = load();
        var types = data.unitTypes().types();
        UnitType gryphon = types.get("unit-gryphon-rider");

        World world = open(32);
        world.setUnitTypes(types);
        Unit flyer = world.createUnit(gryphon, 0, 10, 10);

        // One step east, whose footprint overlaps its own by a whole column.
        assertTrue(world.orderMove(flyer, 11, 10), "a single step east was refused");
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 30; cycle++) {
            world.tick();
            if (flyer.tileX() == 11) {
                break;
            }
        }
        assertEquals(11, flyer.tileX(), "it never took the step east");

        // And one step south, which overlaps by a whole row.
        assertTrue(world.orderMove(flyer, 11, 11), "a single step south was refused");
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 30; cycle++) {
            world.tick();
            if (flyer.tileY() == 11) {
                break;
            }
        }
        assertEquals(11, flyer.tileY(), "it never took the step south");
    }

    /**
     * A chaser follows its quarry rather than the ground it stood on.
     *
     * <p>The route was planned once and then followed to the end, so a unit
     * sent after a moving enemy walked the whole way to where it had been and
     * only looked again on arrival. Upstream re-plans the moment the goal tile
     * changes -- {@code PathFinderInput::SetGoal} raises
     * {@code isRecalculatePathNeeded}, and {@code NextPathElement} acts on it
     * before every step.
     */
    @Test
    @DisplayName("A unit chasing a moving enemy turns after it on the way")
    void aChaserFollowsAMovingTarget() {
        GameData data = load();
        var types = data.unitTypes().types();
        UnitType footman = types.get("unit-footman");
        UnitType peasant = types.get("unit-peasant");

        World world = open(32);
        world.setUnitTypes(types);

        // The quarry starts due east of the hunter and immediately runs north.
        Unit hunter = world.createUnit(footman, 0, 4, 16);
        Unit quarry = world.createUnit(peasant, 1, 20, 16);
        assertTrue(world.orderMove(quarry, 20, 2), "the quarry would not run");
        world.orderAttack(hunter, quarry);

        // By the time the hunter is halfway it should already have turned
        // north. Following the original route would keep it on row 16 all the
        // way to column 19, because that is where the quarry was standing when
        // the order was given.
        int turnedAt = -1;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 60; cycle++) {
            world.tick();
            if (turnedAt < 0 && hunter.tileY() < 16) {
                turnedAt = hunter.tileX();
            }
            if (hunter.tileX() >= 12) {
                break;
            }
        }
        assertTrue(turnedAt > 0 && turnedAt < 12,
                "the hunter was still walking due east at column " + hunter.tileX()
                        + ", towards where its target used to be");
    }

    /** Two units swapping places must not deadlock against each other. */
    @Test
    @DisplayName("Two units passing each other both get where they are going")
    void twoUnitsPass() {
        GameData data = load();
        var types = data.unitTypes().types();
        UnitType footman = types.get("unit-footman");

        World world = open(32);
        world.setUnitTypes(types);
        Unit left = world.createUnit(footman, 0, 10, 16);
        Unit right = world.createUnit(footman, 0, 20, 16);

        world.orderMove(left, 20, 16);
        world.orderMove(right, 10, 16);

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 120; cycle++) {
            world.tick();
            if (left.tileX() >= 19 && right.tileX() <= 11) {
                break;
            }
        }
        assertTrue(left.tileX() > 14, "the left unit barely moved: " + left.tileX());
        assertTrue(right.tileX() < 16, "the right unit barely moved: " + right.tileX());
    }
}
