package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** BNE's terrain harvester turns to the resource point before its first swing. */
class HarvesterFacingTest {

    @Test
    @DisplayName("a woodcutter faces the tree from every adjacent approach")
    void facesTheTreeFromAllEightDirections() {
        int[][] neighbours = {
            {-1, -1}, {0, -1}, {1, -1}, {1, 0},
            {1, 1}, {0, 1}, {-1, 1}, {-1, 0}
        };
        for (int[] delta : neighbours) {
            GameMap map = grass(20);
            int workerX = 10;
            int workerY = 10;
            int treeX = workerX + delta[0];
            int treeY = workerY + delta[1];
            map.field(treeX, treeY).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
            map.field(treeX, treeY).setValue(100);

            World world = new World(map);
            Unit worker = world.createUnit(woodcutter(), 0, workerX, workerY);
            worker.setDirection(Missile.directionToHeading(-delta[0], -delta[1]));
            assertTrue(world.orderHarvest(worker, treeX, treeY),
                    "the adjacent tree order was refused for " + delta[0] + "," + delta[1]);
            for (int cycle = 0; cycle < 12 && !worker.gatherClockStarted(); cycle++) {
                world.tick();
            }

            assertTrue(worker.gatherClockStarted(),
                    "the worker never began chopping toward " + delta[0] + "," + delta[1]);
            assertEquals(Missile.directionToHeading(delta[0], delta[1]), worker.direction(),
                    "the worker swung away from its tree at delta "
                            + delta[0] + "," + delta[1]);
        }
    }

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType woodcutter() {
        UnitType type = new UnitType("unit-peon");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanHarvest(true);
        ResourceInfo wood = new ResourceInfo(UnitType.Resource.WOOD);
        wood.setTerrainHarvester(true);
        wood.setCapacity(100);
        wood.setWaitAtResource(24);
        wood.setStep(1);
        type.gathering().put(UnitType.Resource.WOOD, wood);
        AnimationSet animations = new AnimationSet(type.ident());
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.HARVEST,
                Animation.parse("harvest", List.of("frame 0", "wait 1")));
        type.setAnimationSet(animations);
        return type;
    }
}
