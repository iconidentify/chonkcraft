package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * GiveOrder 27 walks a mend to the building's far corner, not the origin.
 *
 * <p>Authenticated repair-1 fixtures: a peon north of a 4-by-4 hall at
 * 22,22 stands on 26,21; one east of a hall at 41,56 stands on 45,59;
 * a grunt west of 22,22 stands on 21,23. Walking the connected origin
 * used to park those hulls on 22,21 / 45,55 / 22,21.
 */
class BattleNetRepairApproachTest {

    @Test
    @DisplayName("a peon north of a hall mends from the north-east ring")
    void aPeonNorthOfAHallMendsFromTheNorthEastRing() {
        World world = openLand(64);
        Unit hall = world.createUnit(hallType(), 0, 22, 22);
        Unit peon = world.createUnit(peonType(), 0, 25, 18);
        CommandApplier applier = new CommandApplier(
                world, List.of(peon.type(), hall.type()));
        assertTrue(applier.apply(GameCommand.repair(0, peon.id(), hall.id())),
                "GiveOrder 27 must accept a peon told to mend a hall");
        stand(world, peon, 120);
        assertEquals(26, peon.tileX(),
                "retail's peon stands on 26,21, not the origin's north tile "
                        + peon.tileX() + "," + peon.tileY());
        assertEquals(21, peon.tileY(),
                "retail's peon stands on 26,21, not "
                        + peon.tileX() + "," + peon.tileY());
    }

    @Test
    @DisplayName("a peon east of a hall mends from the south-east ring")
    void aPeonEastOfAHallMendsFromTheSouthEastRing() {
        World world = openLand(64);
        Unit hall = world.createUnit(hallType(), 0, 41, 56);
        Unit peon = world.createUnit(peonType(), 0, 50, 58);
        CommandApplier applier = new CommandApplier(
                world, List.of(peon.type(), hall.type()));
        assertTrue(applier.apply(GameCommand.repair(0, peon.id(), hall.id())),
                "GiveOrder 27 must accept a peon told to mend a hall");
        stand(world, peon, 160);
        assertEquals(45, peon.tileX(),
                "retail's peon stands on 45,59, not "
                        + peon.tileX() + "," + peon.tileY());
        assertEquals(59, peon.tileY(),
                "retail's peon stands on 45,59, not "
                        + peon.tileX() + "," + peon.tileY());
    }

    @Test
    @DisplayName("a soldier west of a hall stands on the west ring")
    void aSoldierWestOfAHallStandsOnTheWestRing() {
        World world = openLand(64);
        Unit hall = world.createUnit(hallType(), 0, 22, 22);
        Unit grunt = world.createUnit(gruntType(), 0, 18, 23);
        CommandApplier applier = new CommandApplier(
                world, List.of(grunt.type(), hall.type()));
        assertTrue(applier.apply(GameCommand.repair(0, grunt.id(), hall.id())),
                "GiveOrder 27 must walk a soldier who cannot mend");
        stand(world, grunt, 120);
        assertEquals(21, grunt.tileX(),
                "retail's grunt stands on 21,23, not the origin "
                        + grunt.tileX() + "," + grunt.tileY());
        assertEquals(23, grunt.tileY(),
                "retail's grunt stands on 21,23, not "
                        + grunt.tileX() + "," + grunt.tileY());
    }

    private static void stand(World world, Unit unit, int limit) {
        for (int i = 0; i < limit; i++) {
            world.tick();
            if (i > 8 && !unit.isMoving() && unit.pathLength() == 0
                    && unit.order() == Unit.Order.STILL) {
                return;
            }
        }
    }

    private static World openLand(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return new World(map);
    }

    private static UnitType hallType() {
        UnitType type = new UnitType("unit-great-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        return type;
    }

    private static UnitType peonType() {
        UnitType type = new UnitType("unit-peon");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setRepairRange(1);
        type.setAnimationSet(walker());
        return type;
    }

    private static UnitType gruntType() {
        UnitType type = new UnitType("unit-grunt");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setRepairRange(0);
        type.setAnimationSet(walker());
        return type;
    }

    private static AnimationSet walker() {
        AnimationSet set = new AnimationSet("walker");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move",
                List.of("frame 0", "move 16", "wait 1",
                        "frame 5", "move 16", "wait 1")));
        return set;
    }
}
