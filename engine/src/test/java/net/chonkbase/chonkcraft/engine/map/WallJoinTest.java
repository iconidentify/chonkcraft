package net.chonkbase.chonkcraft.engine.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A wall beside a breach stops drawing a join into it.
 *
 * <p>{@code MapFixWallTile} and {@code MapFixWallNeighbors},
 * {@code :201}. When a wall tile changes,
 * upstream re-derives the four squares around it, so each neighbour picks the
 * variant matching its new surroundings.
 *
 * <p>This implementation had the three states -- whole, broken, destroyed -- and no wall
 * direction table at all, so it changed a damaged square's picture by adding a
 * fixed offset to whatever code was already there and left the neighbours
 * alone. Break the middle of a run and the two beside it went on drawing a
 * join to a square that was no longer a wall. Wall damage was new, so nothing
 * had ever changed a wall tile mid-game before and it had never shown.
 *
 * <p>The fixed offset was wrong on its own account too. Two of the shipped
 * groups carry a second variant of each picture, so their broken tile sits
 * three codes along and their destroyed one six; a stride of two and four drew
 * an empty square for those.
 */
class WallJoinTest {

    private static final int WHOLE = 40;

    /** Human wall by direction: nothing, east, west, and both. */
    private static final int ALONE = WallTileset.HUMAN[0];
    private static final int EAST_ONLY = WallTileset.HUMAN[2];
    private static final int WEST_ONLY = WallTileset.HUMAN[8];
    private static final int BOTH_WAYS = WallTileset.HUMAN[10];

    private static GameMap field(int size) {
        GameMap map = new GameMap(size, size, WallTileset.withWalls());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    /** Lays a human wall square already drawn with the join it should have. */
    private static void wall(GameMap map, int x, int y, int code) {
        MapField square = map.field(x, y);
        square.setTile(code);
        square.setFlags(TileFlag.LAND_ALLOWED | TileFlag.WALL | TileFlag.HUMAN
                | TileFlag.UNPASSABLE);
        square.setValue(WHOLE);
    }

    /** Three squares of wall in a row, each drawn joined the way it stands. */
    private static GameMap runOfThree() {
        GameMap map = field(16);
        wall(map, 5, 5, EAST_ONLY);
        wall(map, 6, 5, BOTH_WAYS);
        wall(map, 7, 5, WEST_ONLY);
        return map;
    }

    @Test
    @DisplayName("breaking the end of a run re-draws the segment that joined on to it")
    void aSurvivingWallStopsJoiningToTheHole() {
        GameMap map = runOfThree();
        map.hitWall(7, 5, WHOLE, WHOLE);

        assertFalse(map.field(7, 5).isWall(), "the fixture wants that square down");
        assertEquals(WEST_ONLY, map.field(6, 5).tile(),
                "the middle square joined east to a wall that is no longer there; it has"
                        + " to be re-derived as joining west only");
        assertNotEquals(BOTH_WAYS, map.field(6, 5).tile(),
                "which is the whole of the bug: it kept drawing the old join");
        assertEquals(EAST_ONLY, map.field(5, 5).tile(),
                "and a square two away, whose neighbours did not change, is left alone");
    }

    @Test
    @DisplayName("breaking the middle of a run re-draws both squares beside it")
    void bothNeighboursAreFixed() {
        GameMap map = runOfThree();
        map.hitWall(6, 5, WHOLE, WHOLE);

        assertFalse(map.field(6, 5).isWall());
        assertEquals(ALONE, map.field(5, 5).tile(),
                "the western segment now stands on its own");
        assertEquals(ALONE, map.field(7, 5).tile(),
                "and so does the eastern one");
    }

    @Test
    @DisplayName("the rubble is drawn with the joins the wall had, not with none")
    void theRubbleKeepsItsShape() {
        GameMap map = runOfThree();
        map.hitWall(7, 5, WHOLE, WHOLE);
        assertEquals(WEST_ONLY + WallTileset.DESTROYED, map.field(7, 5).tile(),
                "RemoveWall picks the square's own picture while it still counts as a"
                        + " wall, so the rubble matches what came down");
    }

    /**
     * A merely damaged wall is still a wall, so nothing round it changes shape
     * -- only its own picture does. Upstream fixes just the square here too.
     */
    @Test
    @DisplayName("a damaged wall changes its own picture and nobody else's")
    void damageDoesNotMoveTheJoins() {
        GameMap map = runOfThree();
        map.hitWall(6, 5, WHOLE / 2 + 1, WHOLE);

        assertEquals(BOTH_WAYS + WallTileset.BROKEN, map.field(6, 5).tile(),
                "under half its hit points it draws its broken picture, in its own group");
        assertEquals(EAST_ONLY, map.field(5, 5).tile());
        assertEquals(WEST_ONLY, map.field(7, 5).tile());
    }

    /**
     * Off the map counts as wall. A wall run to the edge is drawn joined to it
     * rather than ending in mid-air, which is {@code GetDirectionFromSurrounding}
     * setting the bit for any position that is not on the map.
     */
    @Test
    @DisplayName("a wall against the map edge is drawn joined to it")
    void theEdgeCountsAsWall() {
        GameMap map = field(16);
        wall(map, 0, 0, ALONE);
        wall(map, 1, 0, ALONE);
        // (0,0) has the edge north and west, and a wall east: bits 1, 8 and 2.
        map.hitWall(1, 0, WHOLE, WHOLE);
        // With (1,0) gone, (0,0) is joined north and west only: 1 + 8 = 9.
        assertEquals(WallTileset.HUMAN[9], map.field(0, 0).tile(),
                "two of its four neighbours are off the map, and off the map is wall");
    }

    /**
     * An orc wall is not a human one. The direction scan asks for the same
     * race, which is what stops two players' walls being drawn as one.
     */
    @Test
    @DisplayName("a wall does not join to the other race's")
    void racesDoNotJoin() {
        GameMap map = field(16);
        wall(map, 5, 5, ALONE);
        MapField orc = map.field(6, 5);
        orc.setTile(WallTileset.ORC[0]);
        orc.setFlags(TileFlag.LAND_ALLOWED | TileFlag.WALL | TileFlag.UNPASSABLE);
        orc.setValue(WHOLE);

        map.hitWall(5, 5, WHOLE / 2 + 1, WHOLE);
        assertEquals(ALONE + WallTileset.BROKEN, map.field(5, 5).tile(),
                "the orc wall beside it is not a neighbour for this purpose");
    }
}
