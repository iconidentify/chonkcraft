package net.chonkbase.chonkcraft.engine.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Rock uses the same removable edge transitions as forest. */
class RockClearingTest {

    private static Tileset summer() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install).loadTileset(PudMap.Tileset.FOREST).tileset();
    }

    private static int solidCode(Tileset tileset, long flag) {
        for (int code = 0; code < tileset.tileCount(); code++) {
            Tileset.Tile tile = tileset.tile(code);
            if (tile.isDefined() && tile.baseTerrain() != 0 && tile.mixTerrain() == 0
                    && (tile.flags() & flag) != 0) {
                return code;
            }
        }
        throw new AssertionError("no solid slot carrying flag " + flag);
    }

    private static int grassCode(Tileset tileset) {
        for (int code = 0; code < tileset.tileCount(); code++) {
            Tileset.Tile tile = tileset.tile(code);
            if (tile.isDefined() && tile.baseTerrain() != 0 && tile.mixTerrain() == 0
                    && (tile.flags() & TileFlag.LAND_ALLOWED) != 0
                    && (tile.flags() & (TileFlag.ROCKS | TileFlag.UNPASSABLE)) == 0) {
                return code;
            }
        }
        throw new AssertionError("no open land slot");
    }

    @Test
    void theThreeSingleRockTilesDescribeALoneColumn() {
        Tileset tileset = summer();
        int rock = tileset.graphicFor(solidCode(tileset, TileFlag.ROCKS));
        int open = tileset.graphicFor(grassCode(tileset));

        assertEquals(tileset.midOneRockTile(),
                tileset.graphicFor(tileset.rockTileCodeFor(rock, open, rock, open)));
        assertEquals(tileset.topOneRockTile(),
                tileset.graphicFor(tileset.rockTileCodeFor(open, open, rock, open)));
        assertEquals(tileset.botOneRockTile(),
                tileset.graphicFor(tileset.rockTileCodeFor(rock, open, open, open)));
    }

    @Test
    void clearedRockBecomesTheDeclaredWalkableGround() {
        Tileset tileset = summer();
        int grass = grassCode(tileset);
        int rock = solidCode(tileset, TileFlag.ROCKS);
        GameMap map = new GameMap(9, 9, tileset);
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                int code = x >= 3 && x <= 5 && y >= 3 && y <= 5 ? rock : grass;
                map.field(x, y).setTile(code);
                map.field(x, y).setFlags(tileset.flagsFor(code));
            }
        }

        map.clearRockTile(4, 4);

        MapField cleared = map.field(4, 4);
        assertFalse(cleared.hasFlag(TileFlag.ROCKS));
        assertTrue(cleared.isLandPassable());
        assertEquals(tileset.removedRockTile(), tileset.graphicFor(cleared.tile()));
    }
}
