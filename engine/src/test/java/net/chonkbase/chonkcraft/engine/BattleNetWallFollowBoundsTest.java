package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Wall-follow fatal cells from native {@code 0x4500f0}.
 *
 * <p>Out-of-bounds candidate steps and free cells carrying map flag
 * {@code 0x2000} (air-unit occupancy) fail the whole face instead of rotating
 * to another heading. Tests drive {@link World#findBattleNetPointPath} so they
 * compile against both the pre-fix Passability surface and the candidate.</p>
 */
class BattleNetWallFollowBoundsTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("footman");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    @DisplayName("wall-follow at the north edge does not rotate past out of bounds")
    void wallFollowAtTheNorthEdgeDoesNotRotatePastOutOfBounds() {
        // Free ray east from (2,0) hits unpassable (3,0). turn=-1 first
        // heading is NE onto (3,-1) OOB. Native fails that face; rotating
        // would invent a longer north-edge skirt. turn=+1 SE remains.
        GameMap map = grass(8);
        map.field(3, 0).setFlags(TileFlag.LAND_ALLOWED | TileFlag.UNPASSABLE);
        World world = new World(map);
        world.fog().revealAll(0);
        Unit walker = world.createUnit(footman(), 0, 2, 0);
        assertTrue(walker != null, "walker places");

        PathFinder.Path path = world.findBattleNetPointPath(walker, 6, 0, null);

        assertEquals(PathFinder.Result.FOUND, path.result());
        assertTrue(path.length() > 0, "south-east face must still rejoin");
        assertEquals(3, path.headings()[path.length() - 1],
                "OOB must kill the NE face so the first step is SE, not a "
                        + "rotated north-edge skirt");
        // Rotating past OOB produced longer skirts (5 headings) in the pure
        // pathfinder probe; the surviving SE face is shorter.
        assertTrue(path.length() <= 3,
                "only the SE face remains; was length " + path.length());
    }
}
