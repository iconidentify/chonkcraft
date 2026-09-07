package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import net.chonkbase.chonkcraft.engine.map.FogOfWar.Visibility;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Test;

class VisibilitySnapshotTest {
    @Test
    void movingSightNeverBlinksInTheSharedInterior() throws Exception {
        GameMap map = new GameMap(32, 32, new Tileset());
        for (int y = 0; y < 32; y++) {
            for (int x = 0; x < 32; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        UnitType type = new UnitType("walker");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setSightRange(8);
        var unit = world.createUnit(type, 0, 12, 12);
        assertNotNull(unit);
        assertTrue(world.orderPatrol(unit, 14, 12));
        var before = world.visibilitySnapshot(0, 11, 11, 5, 3);
        assertTrue(java.util.Arrays.stream(before).allMatch(v -> v == Visibility.VISIBLE));
        CountDownLatch start = new CountDownLatch(1);
        try (var threads = Executors.newFixedThreadPool(2)) {
            var simulation = threads.submit(() -> {
                start.await();
                for (int tick = 0; tick < 10000; tick++) {
                    world.tick();
                }
                return null;
            });
            var rendering = threads.submit(() -> {
                start.await();
                for (int frame = 0; frame < 10000; frame++) {
                    for (var visibility : world.visibilitySnapshot(0, 11, 11, 5, 3)) {
                        assertEquals(Visibility.VISIBLE, visibility,
                                "the common interior of both sight discs must stay lit");
                    }
                }
                return null;
            });
            start.countDown();
            simulation.get(20, TimeUnit.SECONDS);
            rendering.get(20, TimeUnit.SECONDS);
        }
        // A captured frame must also remain immutable after later fog changes.
        world.fog().removeSight(0, unit.tileX(), unit.tileY(), 1, 1, 8);
        assertTrue(java.util.Arrays.stream(before).allMatch(v -> v == Visibility.VISIBLE));
        assertEquals(Visibility.EXPLORED, world.visibilitySnapshot(0, 12, 12, 1, 1)[0]);
    }
}
