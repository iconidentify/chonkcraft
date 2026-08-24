package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.WallTileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A constructed wall becomes connected, destructible map terrain. */
class WallConstructionTest {

    private static GameMap field(int size) {
        GameMap map = new GameMap(size, size, WallTileset.withWalls());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static AnimationSet workerAnimation() {
        AnimationSet set = new AnimationSet("wall-worker");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of(
                        "frame 0", "move 16", "wait 1",
                        "frame 5", "move 16", "wait 1")));
        return set;
    }

    private static AnimationSet wallAnimation() {
        AnimationSet set = new AnimationSet("wall-site");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        return set;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setAnimationSet(workerAnimation());
        return type;
    }

    private static UnitType humanWall() {
        return wall("unit-human-wall");
    }

    private static UnitType wall(String ident) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(GameMap.WALL_HIT_POINTS);
        type.setBuilding(true);
        type.setAnimationSet(wallAnimation());
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 20);
        type.costs().put(Resource.WOOD, 10);
        return type;
    }

    private static UnitType orcWall() {
        return wall("unit-orc-wall");
    }

    private static void buildWall(World world, CommandApplier commands,
            Unit worker, UnitType wall, int x, int y) {
        int typeIndex = commands.indexOf(wall);
        assertTrue(typeIndex >= 0, "the synchronized roster omitted " + wall.ident());
        assertTrue(commands.apply(GameCommand.build(
                worker.player(), worker.id(), typeIndex, x, y)),
                "the worker refused a wall on open ground at " + x + "," + y);
        for (int cycle = 0; cycle < 1_000 && !world.map().field(x, y).isWall(); cycle++) {
            world.tick();
        }
        assertTrue(world.map().field(x, y).isWall(),
                "the completed wall remained an ordinary building at " + x + "," + y);
        world.tick();
    }

    @Test
    @DisplayName("completed BNE wall sites become joined terrain and release their worker")
    void completedWallsBecomeTerrainAndJoinTheirNeighbours() {
        World world = new World(field(24));
        world.player(0).set(Resource.GOLD, 5_000);
        world.player(0).set(Resource.WOOD, 5_000);
        Unit worker = world.createUnit(peasant(), 0, 6, 10);
        UnitType wall = humanWall();
        UnitType orcWall = orcWall();
        CommandApplier commands = new CommandApplier(world,
                List.of(worker.type(), wall, orcWall));
        assertNotNull(worker, "the worker fixture must fit on its open field");

        buildWall(world, commands, worker, wall, 10, 10);
        assertTrue(worker.isOnMap(), "the completed wall did not release its builder");
        assertFalse(world.units().stream().anyMatch(unit -> unit.type() == wall),
                "a completed wall stayed in the unit roster instead of becoming terrain");
        assertEquals(WallTileset.HUMAN[0], world.map().field(10, 10).tile(),
                "an isolated human wall drew the wrong terrain group");
        assertEquals(GameMap.WALL_HIT_POINTS, world.map().field(10, 10).value(),
                "a fresh wall did not retain its BNE hit points");
        assertTrue(world.map().field(10, 10).hasFlag(TileFlag.UNPASSABLE),
                "armies could walk through the completed wall");

        buildWall(world, commands, worker, wall, 11, 10);

        assertEquals(WallTileset.HUMAN[2], world.map().field(10, 10).tile(),
                "the first segment did not redraw with its eastern join");
        assertEquals(WallTileset.HUMAN[8], world.map().field(11, 10).tile(),
                "the second segment did not draw with its western join");
        assertEquals(4_960, world.player(0).get(Resource.GOLD),
                "the two wall segments did not each cost twenty gold");
        assertEquals(4_980, world.player(0).get(Resource.WOOD),
                "the two wall segments did not each cost ten lumber");

        world.map().hitWall(11, 10, GameMap.WALL_HIT_POINTS,
                GameMap.WALL_HIT_POINTS);
        assertFalse(world.map().field(11, 10).isWall(),
                "the completed segment did not enter the terrain damage path");
        assertEquals(WallTileset.HUMAN[0], world.map().field(10, 10).tile(),
                "the surviving segment kept joining to the destroyed wall");

        Unit orcWorker = world.createUnit(peasant(), 0, 6, 12);
        assertNotNull(orcWorker, "the orc-wall worker fixture must fit on open ground");
        buildWall(world, commands, orcWorker, orcWall, 10, 12);
        assertEquals(WallTileset.ORC[0], world.map().field(10, 12).tile(),
                "a completed orc wall drew the human terrain group");
        assertFalse(world.map().field(10, 12).hasFlag(TileFlag.HUMAN),
                "the orc wall was marked as human terrain");
    }
}
