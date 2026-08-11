package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A unit seen once is not forgotten the moment its watcher walks away.
 *
 * <p>Goal visibility is the unit's own watcher count -- {@code
 * CUnit::VisCount}, read by {@code CUnit::IsVisible}
 * and not the fog under its feet, and the
 * difference is a deliberate bug kept bug for bug: {@code UnitCountSeen}'s
 * went-out-of-fog arm adds one beyond what the tiles say
 * so the
 * count a unit carries is its real watchers plus one phantom. When the last
 * real watcher leaves, the count falls to the phantom and the unit stays a
 * valid goal; only the unit's own next step -- an honest recount -- lets the
 * fog have it.
 *
 * <p>Found on campaigns/human/level13h at cycle 2, where every one of the @2
 * family's first divergences lived: knight 106 walks off the only square
 * that watched the ogre at 120,33, and upstream's knight 109 still bills
 * that ogre as a target -- SEENDBG showed the count fall two to one, not to
 * nought -- while this implementation's honest tile-read said dark and sent its knight
 * east after the other ogre.
 */
class SeenUnitLingersTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType walker(String ident) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setNumDirections(8);
        type.setSightRange(4);
        return type;
    }

    @Test
    @DisplayName("the watcher leaves, the ground goes dark, and the grunt is still a mark")
    void aSeenUnitOutlivesItsWatcher() {
        World world = new World(grass(32));
        Unit watcher = world.createUnit(walker("unit-footman"), 0, 5, 5);
        Unit grunt = world.createUnit(walker("unit-grunt"), 1, 9, 5);
        assertTrue(world.isVisibleTo(0, grunt),
                "the fixture's grunt starts inside the footman's sight");

        // The footman marches away until his sight no longer covers the
        // grunt's square.
        assertTrue(world.orderMove(watcher, 25, 5), "the fixture could not even begin");
        int walked = 0;
        while (walked++ < 400 && world.fog().isVisible(0, grunt.tileX(), grunt.tileY())) {
            world.tick();
        }
        assertFalse(world.fog().isVisible(0, grunt.tileX(), grunt.tileY()),
                "the fixture needs the ground under the grunt gone dark");

        assertTrue(world.isVisibleTo(0, grunt),
                "the grunt vanished the moment its watcher left. Upstream's count"
                        + " falls to the phantom watcher UnitCountSeen added when the"
                        + " grunt first came out of the fog, and the grunt stays a"
                        + " valid goal -- level13h's knight still swings at the ogre"
                        + " nobody is watching");

        // Only the grunt's own step lets the fog have it: a real move
        // recounts honestly and the going-under-fog transition drains the
        // phantom.
        assertTrue(world.orderMove(grunt, 10, 5), "the grunt's step could not begin");
        for (int cycle = 0; cycle < 40; cycle++) {
            world.tick();
        }
        assertFalse(world.isVisibleTo(0, grunt),
                "a step in the dark should have drained the phantom: the recount is"
                        + " honest and the transition takes the extra watcher back");
    }
}
