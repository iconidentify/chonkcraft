package net.chonkbase.chonkcraft.data.graphic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.GraphicsIndex;
import net.chonkbase.chonkcraft.data.NameTable;
import net.chonkbase.chonkcraft.data.archive.WarArchive;
import net.chonkbase.chonkcraft.data.source.ArchiveIds;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Warcraft II's bitmap fonts. */
class FontDecoderTest {

    private static GraphicsIndex index(InstallSource install) {
        java.nio.file.Path strings = install.archivePath(ArchiveIds.STRDAT);
        Assumptions.assumeTrue(strings != null, "no strdat.war");
        return GraphicsIndex.load(NameTable.from(
                WarArchive.open(strings, WarArchive.ID_STRDAT).entry(1)));
    }

    private static InstallSource install() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return install;
    }

    private static WarArchive main() {
        java.nio.file.Path file = install().archivePath(ArchiveIds.MAINDAT);
        Assumptions.assumeTrue(file != null, "no maindat.war");
        return WarArchive.open(file, WarArchive.ID_MAINDAT);
    }

    @Test
    @DisplayName("the shipped fonts decode with plausible glyph cells")
    void theFontsDecode() {
        WarArchive archive = main();
        GraphicsIndex index = index(install());
        int found = 0;
        for (GraphicsIndex.Asset asset : index.assets()) {
            if (asset.kind() != GraphicsIndex.Kind.FONT) {
                continue;
            }
            byte[] raw = archive.entry(asset.contentEntry());
            assertTrue(FontDecoder.looksLikeFont(raw), asset.path() + " is not a font");
            FontDecoder.Font font = FontDecoder.decode(raw);
            assertTrue(font.glyphWidth() > 0 && font.glyphHeight() > 0,
                    asset.path() + " has no glyph cell");
            assertTrue(font.count() > 90, asset.path() + " has only " + font.count() + " glyphs");
            // The sheet is fifteen cells wide, which is the layout everything
            // downstream indexes into.
            assertEquals(font.glyphWidth() * FontDecoder.PER_ROW, font.sheet().width());
            found++;
        }
        assertEquals(5, found, "the game ships five fonts");
    }

    @Test
    @DisplayName("letters have their own widths, so text is spaced not tabulated")
    void glyphsCarryTheirWidth() {
        WarArchive archive = main();
        GraphicsIndex index = index(install());
        GraphicsIndex.Asset game = index.find("game");
        Assumptions.assumeTrue(game != null, "no game font in the table");

        FontDecoder.Font font = FontDecoder.decode(archive.entry(game.contentEntry()));
        // An i is narrower than an m in any font worth the name. If every
        // glyph reported the cell width, text would come out as a grid.
        assertTrue(font.widthOf('i') < font.widthOf('m'),
                "i is " + font.widthOf('i') + " and m is " + font.widthOf('m'));
        assertTrue(font.widthOf("Gold: 3000") > font.widthOf("Gold"));
    }

    @Test
    @DisplayName("a glyph draws something rather than staying blank")
    void glyphsHavePixels() {
        WarArchive archive = main();
        GraphicsIndex index = index(install());
        GraphicsIndex.Asset game = index.find("game");
        Assumptions.assumeTrue(game != null, "no game font in the table");

        FontDecoder.Font font = FontDecoder.decode(archive.entry(game.contentEntry()));
        int glyph = font.glyphOf('A');
        assertTrue(glyph >= 0, "no glyph for A");

        int cellX = (glyph % FontDecoder.PER_ROW) * font.glyphWidth();
        int cellY = (glyph / FontDecoder.PER_ROW) * font.glyphHeight();
        int drawn = 0;
        for (int y = 0; y < font.glyphHeight(); y++) {
            for (int x = 0; x < font.glyphWidth(); x++) {
                int shade = font.sheet().pixels()[(cellY + y) * font.sheet().width() + cellX + x]
                        & 0xFF;
                if (shade != FontDecoder.TRANSPARENT) {
                    drawn++;
                }
            }
        }
        assertTrue(drawn > 10, "the letter A drew " + drawn + " pixels");
    }

    @Test
    @DisplayName("bytes that are not a font are refused")
    void rubbishIsRefused() {
        assertFalse(FontDecoder.looksLikeFont(null));
        assertFalse(FontDecoder.looksLikeFont(new byte[4]));
        assertFalse(FontDecoder.looksLikeFont(new byte[64]));
    }
}
