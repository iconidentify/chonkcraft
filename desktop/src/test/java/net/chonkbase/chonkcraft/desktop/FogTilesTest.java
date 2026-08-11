package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The sixteen masks that give the fog its shape.
 *
 * <p>Fog used to be drawn as filled 32 by 32 squares, which is why its edges
 * looked like a staircase. The masks that fix that are not something this implementation
 * draws -- they ship in the game data, in the first sixteen tiles of every
 * tileset sheet, put there by before it decodes a single
 * piece of terrain. So the thing worth testing is that they are found and read
 * correctly, because if they are not the fog silently falls back to squares
 * and looks exactly like the bug.
 */
class FogTilesTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("Every tileset carries sixteen fog masks")
    void theMasksAreThere() {
        GameData data = load();
        for (PudMap.Tileset which : PudMap.Tileset.values()) {
            FogTiles tiles = FogTiles.from(data.loadTileset(which).sheet());
            for (int frame = 0; frame < FogTiles.FRAMES; frame++) {
                assertNotNull(tiles.explored(frame), which + " frame " + frame);
                assertNotNull(tiles.unseen(frame), which + " frame " + frame);
                assertEquals(32, tiles.explored(frame).getWidth());
                assertEquals(32, tiles.explored(frame).getHeight());
            }
        }
    }

    /**
     * Frame zero is the empty one -- it is what the lookup table returns for
     * both a fully clear square and a fully covered one, and the renderer
     * reads that zero as "draw nothing". Every other frame must actually cover
     * something, or the fringe it is supposed to draw is missing.
     */
    @Test
    @DisplayName("Frame zero is empty and the other fifteen are not")
    void frameZeroIsTheEmptyOne() {
        GameData data = load();
        FogTiles tiles = FogTiles.from(data.loadTileset(PudMap.Tileset.FOREST).sheet());

        assertEquals(0, tiles.coverage(0), "frame 0 should be clear");
        for (int frame = 1; frame < FogTiles.FRAMES; frame++) {
            assertTrue(tiles.coverage(frame) > 0,
                    "frame " + frame + " covers nothing, so the fog edge it draws is missing");
            assertTrue(tiles.coverage(frame) < 32 * 32,
                    "frame " + frame + " covers the whole square, which is the staircase"
                            + " this exists to avoid");
        }
    }

    /**
     * Upstream names the summer sheet unconditionally in
     * {@code SetFogOfWarGraphics}, and then plays winter maps with it. That is
     * only correct because every tileset ships the same masks. Reading them
     * from whichever tileset is loaded relies on the same fact, so it is
     * checked rather than assumed.
     */
    @Test
    @DisplayName("All four tilesets ship identical masks")
    void theMasksAreTheSameEverywhere() {
        GameData data = load();
        int[] reference = null;
        for (PudMap.Tileset which : PudMap.Tileset.values()) {
            FogTiles tiles = FogTiles.from(data.loadTileset(which).sheet());
            int[] coverage = new int[FogTiles.FRAMES];
            for (int frame = 0; frame < FogTiles.FRAMES; frame++) {
                coverage[frame] = tiles.coverage(frame);
            }
            if (reference == null) {
                reference = coverage;
            } else {
                org.junit.jupiter.api.Assertions.assertArrayEquals(reference, coverage,
                        which + " has different fog masks from the first tileset, so drawing"
                                + " fog from the loaded tileset's own sheet is not safe");
            }
        }
    }

    /**
     * The opacities are the prelude's, not invented ones:
     * {@code SetFogOfWarOpacityLevels(0x7F, 0xBE, 0xFE)}.
     *
     * <p>Taken from the script through {@link GameData#fogOfWar()} rather than
     * from a constant in this file. The numbers used to be written out here and
     * in {@link FogTiles}, which made this a check that two copies of a fact
     * agreed with each other rather than that either agreed with the game.
     */
    @Test
    @DisplayName("The masks are baked at the game's own opacities")
    void theOpacitiesAreUpstreams() {
        GameData data = load();
        var levels = data.fogOfWar().levels();
        FogTiles tiles = FogTiles.from(data.loadTileset(PudMap.Tileset.FOREST).sheet(),
                levels);
        assertEquals(0x7F, levels.explored(), "the prelude's first argument");
        assertEquals(0xFE, levels.unseen(), "the prelude's third argument");

        int exploredAlpha = -1;
        int unseenAlpha = -1;
        for (int y = 0; y < 32 && exploredAlpha < 0; y++) {
            for (int x = 0; x < 32; x++) {
                int alpha = tiles.explored(1).getRGB(x, y) >>> 24;
                if (alpha != 0) {
                    exploredAlpha = alpha;
                    unseenAlpha = tiles.unseen(1).getRGB(x, y) >>> 24;
                    break;
                }
            }
        }
        assertEquals(levels.explored(), exploredAlpha);
        assertEquals(levels.unseen(), unseenAlpha);
    }
}
