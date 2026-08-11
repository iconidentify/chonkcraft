package net.chonkbase.chonkcraft.engine.map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.TilesetDecoder;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.ArchiveIds;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.data.source.EntryArchive;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Renders a real Warcraft II map end to end.
 *
 * <p>This joins every layer built so far: the archive reader pulls the tile
 * graphics out of {@code maindat.war}, the tileset decoder assembles them into
 * a sheet, the native catalog supplies the sealed tile-code table, and the PUD
 * reader supplies the map. If any one of them is
 * wrong the output stops being terrain.
 *
 * <p>Needs only the authenticated Warcraft II assets.
 */
class MapRenderRealDataTest {

    /** Entries in maindat.war for the forest tileset, from {@code wartool.h:280}. */
    private static final int FOREST_PALETTE = 2;
    private static final int FOREST_MEGATILES = 3;
    private static final int FOREST_MINITILES = 4;

    private static InstallSource install() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return install;
    }

    private static Tileset loadForestTileset() {
        return TilesetCatalog.create(PudMap.Tileset.FOREST).tileset();
    }

    @Test
    @DisplayName("the native forest tileset builds a full tile-code table")
    void forestTilesetScriptBuildsItsTable() {
        Tileset tileset = loadForestTileset();

        assertEquals("Forest", tileset.name());
        assertEquals("tilesets/summer/terrain/summer.png", tileset.imageFile());
        assertEquals(32, tileset.tileWidth());

        // Ten solid slots and nine mixed ones, so the table runs well past the
        // 0x0Cx codes the base game uses.
        assertTrue(tileset.tileCount() >= 0x900,
                "expected the full slot table, got " + tileset.tileCount() + " codes");
        assertTrue(tileset.terrainNames().size() >= 10,
                "expected the named terrains, got " + tileset.terrainNames());

        // The slot layout documented at the top of the script: 0x05x is light
        // grass, 0x07x forest, 0x08x rock. Each must resolve to a graphic and
        // carry the right movement flags.
        assertTrue(tileset.graphicFor(0x050) > 0, "light grass has no graphic");
        assertTrue((tileset.flagsFor(0x050) & TileFlag.LAND_ALLOWED) != 0, "grass should allow land units");
        assertTrue((tileset.flagsFor(0x070) & TileFlag.FOREST) != 0, "0x070 should be forest");
        assertTrue((tileset.flagsFor(0x070) & TileFlag.UNPASSABLE) != 0, "forest should block movement");
        assertTrue((tileset.flagsFor(0x080) & TileFlag.ROCKS) != 0, "0x080 should be rock");
        assertTrue((tileset.flagsFor(0x010) & TileFlag.WATER_ALLOWED) != 0, "0x010 should be water");

        // Chopping a forest reveals a specific tile; the engine needs it by name.
        assertTrue(tileset.removedTreeTile() > 0, "no removed-tree tile defined");
    }

    @Test
    @DisplayName("a shipped map renders to real terrain")
    void aShippedMapRendersToRealTerrain() {
        InstallSource install = install();
        Tileset tileset = loadForestTileset();

        EntryArchive main = install.archive(ArchiveIds.MAINDAT);
        IndexedImage sheet = TilesetDecoder.decode(main.entry(FOREST_MINITILES), main.entry(FOREST_MEGATILES));
        assertTrue(main.entry(FOREST_PALETTE).length == 768, "entry 2 should be the forest palette");

        PudMap map = findForestMap(install);
        Assumptions.assumeTrue(map != null, "no forest-tileset map found in this installation");

        IndexedImage rendered = new MapRenderer(tileset, sheet).render(map.width(), map.height(), map.tiles());
        assertEquals(map.width() * TilesetDecoder.TILE_SIZE, rendered.width());
        assertEquals(map.height() * TilesetDecoder.TILE_SIZE, rendered.height());

        // Terrain is opaque and varied. A broken tile-code lookup shows up as
        // a single repeated tile, so require a real spread of palette indices.
        Set<Integer> colours = new HashSet<>();
        for (byte pixel : rendered.pixels()) {
            colours.add(pixel & 0xFF);
        }
        assertTrue(colours.size() > 20,
                "rendered map uses only " + colours.size() + " colours, which is not terrain");

        // Every tile code in a shipped map must resolve to a defined slot.
        int unresolved = 0;
        for (int code : map.tiles()) {
            if (!tileset.tile(code).isDefined()) {
                unresolved++;
            }
        }
        assertEquals(0, unresolved,
                unresolved + " of " + map.tiles().length + " tile codes have no tileset entry");
    }

    /** The first forest-tileset map the source offers. */
    private static PudMap findForestMap(AssetSource source) {
        for (String name : source.mapNames()) {
            PudMap map = PudReader.read(source.map(name));
            if (map.tileset() == PudMap.Tileset.FOREST) {
                return map;
            }
        }
        return null;
    }
}
