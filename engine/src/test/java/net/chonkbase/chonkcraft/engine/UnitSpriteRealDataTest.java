package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.Palette;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Resolves and decodes every unit sprite from the real archives.
 *
 * <p>The chain under test: a unit type names a sprite path, the conversion
 * table maps that path to an archive entry, and the decoder turns the entry
 * into pixels. Building paths take a longer route, through the per-tileset
 * table, because a farm on snow is a different picture from one on grass.
 */
class UnitSpriteRealDataTest {

    private static GameData gameData() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");

        return new GameData(install);
    }

    @Test
    @DisplayName("the string table and conversion index load from the archives")
    void theStringTableAndIndexLoad() {
        GameData data = gameData();

        // strdat.war entry 1, the same bytes wartool.h embeds as its Names blob.
        assertTrue(data.names().size() > 400, "expected the full string table, got " + data.names().size());
        assertEquals("human/units/peasant", data.names().expand("human/units/%4"));
        assertEquals("human/units/dwarven_demolition_squad", data.names().expand("human/units/%16"));

        assertTrue(data.graphics().size() > 300, "expected the full conversion table");
    }

    @Test
    @DisplayName("every unit sprite path resolves to an archive entry")
    void everyUnitSpritePathResolves() {
        GameData data = gameData();

        List<String> unresolved = new ArrayList<>();
        int resolved = 0;
        for (UnitType type : data.unitTypes().types().values()) {
            String path = type.imageFileFor("summer");
            if (path.isEmpty()) {
                continue;
            }
            if (data.graphics().find(path) == null) {
                unresolved.add(type.ident() + " -> " + path);
            } else {
                resolved++;
            }
        }
        assertTrue(resolved > 100, "expected the full sprite set, resolved " + resolved);
        assertEquals(List.of(), unresolved, unresolved.size() + " sprite paths do not resolve");
    }

    @Test
    @DisplayName("unit sprites decode to sheets a whole number of frames wide")
    void unitSpritesDecodeToWholeFrames() {
        GameData data = gameData();

        for (String ident : List.of("unit-footman", "unit-grunt", "unit-peasant",
                "unit-town-hall", "unit-great-hall", "unit-farm")) {
            UnitType type = data.unitTypes().types().get(ident);
            Assumptions.assumeTrue(type != null, ident + " not defined");

            String path = type.imageFileFor("summer");
            IndexedImage sheet = data.sprite(path);
            assertTrue(sheet != null, ident + ": no sprite at " + path);

            // The declared frame size has to tile the sheet exactly, or the
            // renderer would slice frames at the wrong offsets.
            assertEquals(0, sheet.width() % type.imageWidth(),
                    ident + ": sheet width " + sheet.width() + " is not a multiple of frame width "
                            + type.imageWidth());
            assertEquals(0, sheet.height() % type.imageHeight(),
                    ident + ": sheet height " + sheet.height() + " is not a multiple of frame height "
                            + type.imageHeight());

            // A sprite is neither blank nor fully opaque.
            int opaque = 0;
            for (byte pixel : sheet.pixels()) {
                if ((pixel & 0xFF) != Palette.TRANSPARENT_INDEX) {
                    opaque++;
                }
            }
            assertTrue(opaque > 0, ident + " decoded to nothing but transparency");
            assertTrue(opaque < sheet.pixels().length, ident + " has no transparency at all");
        }
    }

    @Test
    @DisplayName("buildings resolve a different sprite per tileset")
    void buildingsResolveADifferentSpritePerTileset() {
        GameData data = gameData();
        UnitType farm = data.unitTypes().types().get("unit-farm");
        Assumptions.assumeTrue(farm != null, "unit-farm not defined");

        String summer = farm.imageFileFor("summer");
        String winter = farm.imageFileFor("winter");
        assertTrue(!summer.equals(winter), "a farm should look different on snow");
        assertTrue(data.graphics().find(summer) != null, "no entry for " + summer);
        assertTrue(data.graphics().find(winter) != null, "no entry for " + winter);
        // Different terrain, different archive entry.
        assertTrue(data.graphics().find(summer).entry() != data.graphics().find(winter).entry());
    }

    @Test
    @DisplayName("every unit a map places is drawn onto the rendered scene")
    void everyUnitAMapPlacesIsDrawn() {
        GameData data = gameData();
        InstallSource install = InstallSource.fromEnvironment();

        PudMap map = findForestMap(install);
        Assumptions.assumeTrue(map != null, "no forest map in this installation");

        GameData.LoadedTileset tileset = data.loadTileset(map.tileset());
        IndexedImage scene = new MapRenderer(tileset.tileset(), tileset.sheet())
                .render(map.width(), map.height(), map.tiles());

        // Snapshot the terrain so we can prove the units actually changed it.
        byte[] before = scene.pixels().clone();

        int drawn = data.drawUnits(scene, map);
        assertEquals(map.units().size(), drawn,
                "only " + drawn + " of " + map.units().size() + " placed units were drawn");

        int changed = 0;
        for (int i = 0; i < before.length; i++) {
            if (before[i] != scene.pixels()[i]) {
                changed++;
            }
        }
        assertTrue(changed > 1000,
                "drawing " + drawn + " units changed only " + changed + " pixels");
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
