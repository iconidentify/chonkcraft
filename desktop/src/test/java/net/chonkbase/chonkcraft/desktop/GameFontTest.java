package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The lettering is an outline face shipped in the jar.
 *
 * <p>The point of embedding it rather than asking the machine for a family by
 * name is that the answer cannot change: the sidebar is laid out to the width
 * of the text in it, so a font that resolves differently on another machine is
 * a layout that breaks on another machine.
 */
class GameFontTest {

    @Test
    @DisplayName("the face is in the jar, not on the machine")
    void theFaceIsShipped() {
        assertNotNull(GameFont.class.getResourceAsStream("/fonts/DroidSerif-Regular.ttf"),
                "the regular face is not on the classpath");
        assertNotNull(GameFont.class.getResourceAsStream("/fonts/DroidSerif-Bold.ttf"),
                "the bold face is not on the classpath");
        assertNotNull(GameFont.class.getResourceAsStream("/fonts/README.txt"),
                "the licence notice must travel with the font");
    }

    @Test
    @DisplayName("every face loads and reports usable metrics")
    void everyFaceLoads() {
        for (GameFont.Face face : GameFont.Face.values()) {
            GameFont font = GameFont.load(null, face);
            assertNotNull(font, face.name());
            assertTrue(font.height() > 6 && font.height() < 40,
                    face + " reports an implausible line height: " + font.height());
            assertTrue(font.widthOf("Peasant") > 0, face + " measures nothing");
            assertEquals(0, font.widthOf(""), "an empty string is no wide");
        }
    }

    @Test
    @DisplayName("nothing falls back to a font off the machine")
    void theShippedFaceIsTheOneUsed() {
        // A build that shipped without its fonts still runs -- text in the
        // wrong face beats no text -- but it must never do so quietly. This is
        // the check that turns a broken jar into a failed build rather than a
        // puzzle about why the menus suddenly look cheap.
        assertTrue(!GameFont.usingFallbackFace(),
                "the shipped lettering is not on the classpath; every screen is "
                        + "being drawn in the runtime's own serif");
        for (GameFont.Face face : GameFont.Face.values()) {
            assertEquals("Droid Serif", GameFont.load(null, face).family(),
                    face + " is not cut from the shipped family");
        }
        // And a size between the three faces is still the same family: the
        // briefing sets its prose smaller until it fits, and used to reach for
        // the look and feel's font to do it.
        assertEquals("Droid Serif", GameFont.load(null, GameFont.Face.GAME).atSize(9f).family());
    }

    @Test
    @DisplayName("the faces are ordered small, game, large")
    void theFacesAreOrdered() {
        int small = GameFont.load(null, GameFont.Face.SMALL).height();
        int game = GameFont.load(null, GameFont.Face.GAME).height();
        int large = GameFont.load(null, GameFont.Face.LARGE).height();
        assertTrue(small <= game, "small is not smaller than game");
        assertTrue(game < large, "large is not larger than game");
    }

    @Test
    @DisplayName("drawing puts ink on the page in the colour asked for")
    void itDrawsInTheRightInk() {
        // The old bitmap path recoloured a sheet; this one sets a colour and
        // draws. Worth a check that it lands at all, and lands where the
        // caller said: the contract is that y is the top of the line, not the
        // baseline, because that is what every call site was written against.
        BufferedImage canvas = new BufferedImage(200, 40, BufferedImage.TYPE_INT_RGB);
        var g2 = canvas.createGraphics();
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, 200, 40);
        GameFont font = GameFont.load(null, GameFont.Face.GAME);
        font.draw(g2, "Peasant", 4, 8, GameFont.Ink.YELLOW);
        g2.dispose();

        int lit = 0;
        int aboveTheLine = 0;
        for (int y = 0; y < 40; y++) {
            for (int x = 0; x < 200; x++) {
                int pixel = canvas.getRGB(x, y);
                if ((pixel & 0xFFFFFF) == 0) {
                    continue;
                }
                lit++;
                if (y < 8) {
                    aboveTheLine++;
                }
                int red = (pixel >> 16) & 0xFF;
                int blue = pixel & 0xFF;
                assertTrue(red > blue, "the yellow ink came out some other colour");
            }
        }
        assertTrue(lit > 40, "almost nothing was drawn: " + lit + " pixels");
        assertEquals(0, aboveTheLine, "text was drawn above the top of its line");
    }

    @Test
    @DisplayName("a wider string measures wider")
    void measurementIsMonotonic() {
        GameFont font = GameFont.load(null, GameFont.Face.GAME);
        assertTrue(font.widthOf("Peasant the Elder") > font.widthOf("Peasant"),
                "measurement does not grow with the string");
    }
}
