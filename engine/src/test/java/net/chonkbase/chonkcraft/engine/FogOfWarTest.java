package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.FogOfWar;
import net.chonkbase.chonkcraft.engine.map.FogOfWar.Visibility;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Test;

/** Tests for vision, memory, and the two layers of fog. */
class FogOfWarTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType scout(int sightRange) {
        UnitType type = new UnitType("unit-scout");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setSightRange(sightRange);

        AnimationSet set = new AnimationSet("walker");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move",
                List.of("frame 0", "move 16", "wait 1", "frame 5", "move 16", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    // ------------------------------------------------------------ the layers

    @Test
    void groundStartsUnexplored() {
        FogOfWar fog = new FogOfWar(20, 20, 2);
        assertEquals(Visibility.UNEXPLORED, fog.visibility(0, 10, 10));
        assertFalse(fog.isExplored(0, 10, 10));
        assertFalse(fog.isVisible(0, 10, 10));
    }

    @Test
    void sightRevealsAndMemoryPersists() {
        FogOfWar fog = new FogOfWar(20, 20, 2);
        fog.addSight(0, 10, 10, 1, 1, 3);

        assertEquals(Visibility.VISIBLE, fog.visibility(0, 10, 10));
        assertEquals(Visibility.VISIBLE, fog.visibility(0, 12, 10));

        // Walk away: the ground is remembered but no longer watched. This is
        // the distinction that lets you see terrain you have scouted without
        // seeing what the enemy is doing on it.
        fog.removeSight(0, 10, 10, 1, 1, 3);
        assertEquals(Visibility.EXPLORED, fog.visibility(0, 10, 10));
        assertTrue(fog.isExplored(0, 10, 10));
        assertFalse(fog.isVisible(0, 10, 10));
    }

    @Test
    void sightIsADiscNotABox() {
        FogOfWar fog = new FogOfWar(20, 20, 1);
        fog.addSight(0, 10, 10, 1, 1, 3);

        // Three squares straight out is inside the circle.
        assertTrue(fog.isVisible(0, 13, 10));
        assertTrue(fog.isVisible(0, 10, 13));
        // The corner of the bounding box is not: 3 by 3 is 4.24 away.
        assertFalse(fog.isVisible(0, 13, 13), "the corner of the box should be outside the disc");
        // But a shallower diagonal is.
        assertTrue(fog.isVisible(0, 12, 12), "2 by 2 is 2.83, inside a radius of 3");
    }

    @Test
    void visionIsCountedSoOneUnitLeavingDoesNotBlindTheRest() {
        FogOfWar fog = new FogOfWar(20, 20, 1);
        fog.addSight(0, 10, 10, 1, 1, 2);
        fog.addSight(0, 11, 10, 1, 1, 2);

        assertTrue(fog.isVisible(0, 10, 10));
        fog.removeSight(0, 11, 10, 1, 1, 2);
        assertTrue(fog.isVisible(0, 10, 10), "the remaining unit should still see");

        fog.removeSight(0, 10, 10, 1, 1, 2);
        assertFalse(fog.isVisible(0, 10, 10), "now nothing is watching");
    }

    @Test
    void cloakDetectionIsCountedWithoutRevealingTerrain() {
        FogOfWar fog = new FogOfWar(20, 20, 1);
        fog.addDetection(0, 10, 10, 1, 1, 2);
        fog.addDetection(0, 11, 10, 1, 1, 2);

        assertTrue(fog.isDetected(0, 10, 10));
        assertFalse(fog.isVisible(0, 10, 10), "detection is not ordinary sight");
        assertFalse(fog.isExplored(0, 10, 10), "detection does not explore terrain");

        fog.removeDetection(0, 11, 10, 1, 1, 2);
        assertTrue(fog.isDetected(0, 10, 10), "the remaining detector should still count");
        fog.removeDetection(0, 10, 10, 1, 1, 2);
        assertFalse(fog.isDetected(0, 10, 10), "coverage should end with the last detector");
    }

    @Test
    void playersSeeIndependently() {
        FogOfWar fog = new FogOfWar(20, 20, 2);
        fog.addSight(0, 5, 5, 1, 1, 3);

        assertTrue(fog.isVisible(0, 5, 5));
        assertFalse(fog.isVisible(1, 5, 5), "the other player should see nothing");
        assertFalse(fog.isExplored(1, 5, 5));
    }

    @Test
    void aBuildingSeesFromEveryEdge() {
        FogOfWar fog = new FogOfWar(30, 30, 1);
        // A 4x4 hall at 10,10 with sight 2 should see two squares past each
        // of its own edges, not two squares from its top-left corner.
        fog.addSight(0, 10, 10, 4, 4, 2);

        assertTrue(fog.isVisible(0, 8, 10), "two west of the western edge");
        assertTrue(fog.isVisible(0, 15, 13), "two east of the eastern edge");
        assertTrue(fog.isVisible(0, 11, 8), "two north of the northern edge");
        assertFalse(fog.isVisible(0, 17, 13), "well beyond the eastern edge");
    }

    @Test
    void revealAllExploresWithoutMakingEverythingVisible() {
        FogOfWar fog = new FogOfWar(10, 10, 1);
        fog.revealAll(0);

        assertEquals(100, fog.exploredCount(0));
        assertEquals(Visibility.EXPLORED, fog.visibility(0, 5, 5));
        // Revealing the map shows the terrain, not the enemy's movements.
        assertFalse(fog.isVisible(0, 5, 5));
    }

    @Test
    void offMapSquaresAreUnexplored() {
        FogOfWar fog = new FogOfWar(10, 10, 1);
        fog.revealAll(0);
        assertEquals(Visibility.UNEXPLORED, fog.visibility(0, -1, 5));
        assertEquals(Visibility.UNEXPLORED, fog.visibility(0, 5, 99));
    }

    // ------------------------------------------------------- in the world

    @Test
    void placingAUnitLightsItsSurroundings() {
        World world = new World(grass(30));
        world.createUnit(scout(4), 0, 15, 15);

        assertTrue(world.fog().isVisible(0, 15, 15));
        assertTrue(world.fog().isVisible(0, 18, 15));
        assertFalse(world.fog().isVisible(0, 25, 15), "beyond sight range");
        assertFalse(world.fog().isVisible(1, 15, 15), "the enemy sees nothing");
    }

    @Test
    void visionFollowsAMovingUnitAndLeavesMemoryBehind() {
        World world = new World(grass(40));
        Unit unit = world.createUnit(scout(3), 0, 5, 5);

        assertTrue(world.fog().isVisible(0, 5, 5));
        world.orderMove(unit, 25, 5);

        for (int cycle = 0; cycle < 4000; cycle++) {
            world.tick();
            if (unit.order() == Unit.Order.STILL && !unit.isMoving()) {
                break;
            }
        }
        assertEquals(25, unit.tileX(), "the scout never arrived");

        // It sees where it is now.
        assertTrue(world.fog().isVisible(0, 25, 5));
        // Its old ground is remembered but no longer watched.
        assertFalse(world.fog().isVisible(0, 5, 5), "the start should have gone dark");
        assertTrue(world.fog().isExplored(0, 5, 5), "but it should be remembered");
        // And it explored the whole corridor on the way.
        assertTrue(world.fog().isExplored(0, 15, 5), "the middle of the walk");
    }

    @Test
    void aDeadUnitStopsSeeing() {
        World world = new World(grass(30));
        Unit unit = world.createUnit(scout(4), 0, 15, 15);
        assertTrue(world.fog().isVisible(0, 15, 15));

        world.kill(unit);
        assertFalse(world.fog().isVisible(0, 15, 15), "a corpse does not scout");
        assertTrue(world.fog().isExplored(0, 15, 15), "but the ground stays remembered");
    }

    @Test
    void exploredGroundOnlyGrows() {
        World world = new World(grass(40));
        Unit unit = world.createUnit(scout(3), 0, 5, 5);
        int start = world.fog().exploredCount(0);

        world.orderMove(unit, 30, 30);
        int previous = start;
        for (int cycle = 0; cycle < 6000; cycle++) {
            world.tick();
            int now = world.fog().exploredCount(0);
            assertTrue(now >= previous, "explored ground shrank at cycle " + cycle);
            previous = now;
            if (unit.order() == Unit.Order.STILL && !unit.isMoving()) {
                break;
            }
        }
        assertTrue(previous > start, "the scout explored nothing");
    }
}
