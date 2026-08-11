package net.chonkbase.chonkcraft.engine.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashSet;
import java.util.Set;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a wood looks like while it is being cut down.
 *
 * <p>A forest tile is an edge between trees and grass and which edge it is
 * depends on what stands beside it, so taking one square out of the middle of
 * a wood changes how four of its neighbours have to be drawn.
 * {@code CMap::ClearWoodTile} therefore does the square and its eight
 * neighbours together, re-deriving each from its own four
 * ({@code CMap::FixTile} and {@code CTileset::getTileBySurrounding}).
 *
 * <p>The three tiles the tileset declares as {@code top-one-tree},
 * {@code mid-one-tree} and {@code bot-one-tree} are the ones that only that
 * derivation ever reaches: a square with no valid edge left is a single tree,
 * and which single tree depends on whether the wood carries on above it, below
 * it, or both. The implementation cut straight from a full forest tile to bare ground,
 * so a half-felled wood was drawn as though it were whole up to the squares
 * that had actually been cut, and the three tiles were parsed and never used.
 */
class ForestFellingTest {

    private static Tileset summer() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install).loadTileset(PudMap.Tileset.FOREST).tileset();
    }

    /** The first code of the tileset's solid forest slot. */
    private static int solidForestCode(Tileset tileset) {
        for (int code = 0; code < tileset.tileCount(); code++) {
            Tileset.Tile tile = tileset.tile(code);
            if (tile.isDefined() && tile.mixTerrain() == 0 && tile.baseTerrain() != 0
                    && (tile.flags() & TileFlag.FOREST) != 0) {
                return code;
            }
        }
        throw new AssertionError("the summer tileset has no solid forest slot");
    }

    /** A solid land code that is not forest, for the ground around a wood. */
    private static int grassCode(Tileset tileset) {
        for (int code = 0; code < tileset.tileCount(); code++) {
            Tileset.Tile tile = tileset.tile(code);
            if (tile.isDefined() && tile.mixTerrain() == 0 && tile.baseTerrain() != 0
                    && (tile.flags() & TileFlag.FOREST) == 0
                    && (tile.flags() & TileFlag.LAND_ALLOWED) != 0
                    && (tile.flags() & TileFlag.UNPASSABLE) == 0) {
                return code;
            }
        }
        throw new AssertionError("the summer tileset has no plain land slot");
    }

    // --------------------------------------------------- the derivation alone

    /**
     * The three transitional tiles, straight out of
     * {@code getTileBySurrounding}. Trees above and below and open ground
     * either side is the middle of a lone column; trees only below is its top;
     * trees only above is its foot.
     */
    @Test
    @DisplayName("A lone column of trees resolves to top, middle and bottom")
    void aLoneColumnResolvesToTheThreeSingleTreeTiles() {
        Tileset tileset = summer();
        int trees = tileset.graphicFor(solidForestCode(tileset));
        int open = tileset.graphicFor(grassCode(tileset));

        assertEquals(tileset.midOneTreeTile(),
                tileset.graphicFor(tileset.woodTileCodeFor(trees, open, trees, open)),
                "trees above and below, nothing either side");
        assertEquals(tileset.topOneTreeTile(),
                tileset.graphicFor(tileset.woodTileCodeFor(open, open, trees, open)),
                "trees only below: the top of the column");
        assertEquals(tileset.botOneTreeTile(),
                tileset.graphicFor(tileset.woodTileCodeFor(trees, open, open, open)),
                "trees only above: the foot of the column");
    }

    /** Trees on all four sides is the solid tile, not a transition. */
    @Test
    @DisplayName("The middle of a wood stays the solid tile")
    void theMiddleOfAWoodIsSolid() {
        Tileset tileset = summer();
        int trees = tileset.graphicFor(solidForestCode(tileset));
        assertEquals(trees,
                tileset.graphicFor(tileset.woodTileCodeFor(trees, trees, trees, trees)));
    }

    /** Nothing round it at all: no trees can stand here. */
    @Test
    @DisplayName("A square with nothing beside it has no tile left")
    void anIsolatedSquareClears() {
        Tileset tileset = summer();
        int open = tileset.graphicFor(grassCode(tileset));
        assertEquals(-1, tileset.woodTileCodeFor(open, open, open, open));
    }

    // ------------------------------------------------------ felling on a map

    private static GameMap wood(Tileset tileset, int size, int fromX, int toX, int fromY, int toY) {
        GameMap map = new GameMap(size, size, tileset);
        int grass = grassCode(tileset);
        int forest = solidForestCode(tileset);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                boolean trees = x >= fromX && x <= toX && y >= fromY && y <= toY;
                int code = trees ? forest : grass;
                map.field(x, y).setTile(code);
                map.field(x, y).setFlags(tileset.flagsFor(code));
                map.field(x, y).setValue(trees ? GameMap.WOOD_PER_FOREST_TILE : 0);
            }
        }
        return map;
    }

    /**
     * Cutting a lane down both sides of a three-wide wood leaves a single
     * column standing, and that column has to be drawn with the tiles the
     * tileset keeps for exactly that.
     */
    @Test
    @DisplayName("Cutting round a column of trees draws it as a column")
    void cuttingRoundAColumnDrawsIt() {
        Tileset tileset = summer();
        GameMap map = wood(tileset, 12, 4, 6, 2, 6);

        for (int y = 2; y <= 6; y++) {
            map.clearWoodTile(4, y);
        }
        for (int y = 2; y <= 6; y++) {
            map.clearWoodTile(6, y);
        }

        Set<Integer> drawn = new LinkedHashSet<>();
        for (int y = 2; y <= 6; y++) {
            drawn.add(tileset.graphicFor(map.field(5, y).tile()));
        }
        Set<Integer> single = Set.of(tileset.topOneTreeTile(),
                tileset.midOneTreeTile(), tileset.botOneTreeTile());
        assertTrue(drawn.stream().anyMatch(single::contains),
                "the standing column should be drawn with the single-tree tiles, and is drawn "
                        + drawn + " against " + single);
        assertTrue(drawn.contains(tileset.midOneTreeTile()),
                "the middle of the column is mid-one-tree, and is " + drawn);
    }

    /**
     * The other half, and a bug of its own. The cleared tile is one of the four
     * graphics the tileset names outside the map's tile-code space, so looking
     * its flags up by code found an undefined slot -- and the old code
     * <em>replaced</em> the square's flags with what it found. Every square
     * felled on a real map ended up with a flag word of zero: no land, and so
     * nothing could walk across it. A worker could chop a hole in the world.
     */
    @Test
    @DisplayName("Felled ground is walkable and draws the cleared tile")
    void felledGroundIsWalkable() {
        Tileset tileset = summer();
        GameMap map = wood(tileset, 12, 4, 6, 2, 6);

        // The evidence for the paragraph above, so it cannot quietly come
        // back: the cleared graphic is not a tile code, and reading it as one
        // finds an empty slot with no flags at all.
        assertEquals(0, tileset.graphicFor(tileset.removedTreeTile()),
                "removed-tree is a graphic index, not a tile code");
        assertEquals(0L, tileset.flagsFor(tileset.removedTreeTile()),
                "so its flags read as a code are nothing, and were being assigned wholesale");

        map.clearWoodTile(5, 4);
        MapField cleared = map.field(5, 4);

        assertTrue(cleared.isLandPassable(), "a felled square has to be walkable");
        assertTrue(!cleared.isForest(), "the trees are gone");
        assertEquals(0, cleared.value(), "and so is the wood in it");
        assertEquals(tileset.removedTreeTile(), tileset.graphicFor(cleared.tile()),
                "it should draw the tileset's cleared-ground graphic");
        assertNotEquals(0, tileset.graphicFor(cleared.tile()),
                "graphic zero is the undefined slot, which is what the code used to find");
    }

    /**
     * Only the picture of a neighbour changes. It still holds its trees and the
     * wood that is left in them, which is what stops felling one square from
     * emptying the ones round it.
     */
    @Test
    @DisplayName("A mended neighbour keeps its trees and its wood")
    void aMendedNeighbourKeepsWhatItHas() {
        Tileset tileset = summer();
        GameMap map = wood(tileset, 12, 4, 6, 2, 6);
        int before = tileset.graphicFor(map.field(5, 3).tile());

        map.clearWoodTile(4, 3);

        MapField neighbour = map.field(5, 3);
        assertTrue(neighbour.isForest(), "it still has trees");
        assertEquals(GameMap.WOOD_PER_FOREST_TILE, neighbour.value(), "and all of its wood");
        assertNotEquals(before, tileset.graphicFor(neighbour.tile()),
                "but it is not drawn the way it was, because the wood beside it is gone");
    }
}
