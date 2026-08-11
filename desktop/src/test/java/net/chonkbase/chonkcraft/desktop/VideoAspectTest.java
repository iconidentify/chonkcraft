package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.awt.Dimension;
import java.awt.Rectangle;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The stored movie raster and the shape intended for the screen. */
class VideoAspectTest {

    @Test
    @DisplayName("Battle.net Edition's 320 by 288 movies fill a 4:3 game display")
    void battleNetMoviesAreNotShownTallAndSkinny() {
        Dimension aspect = VideoScreen.displayAspect(320, 288, true);

        assertEquals(new Dimension(1_920, 1_440), aspect);
        assertEquals(new Rectangle(0, 0, 640, 480),
                PixelScaler.fit(aspect.width, aspect.height, 640, 480, false));
    }

    @Test
    @DisplayName("Battle.net Edition's Blizzard movie gets the same wide pixels")
    void battleNetLogoIsNotShownTallAndSkinny() {
        Dimension aspect = VideoScreen.displayAspect(320, 308, true);

        assertEquals(new Dimension(1_920, 1_540), aspect);
        assertEquals(new Rectangle(21, 0, 598, 480),
                PixelScaler.fit(aspect.width, aspect.height, 640, 480, false));
    }

    @Test
    @DisplayName("letterboxed classic movies keep their stored shape")
    void classicMovieShapeIsPreserved() {
        assertEquals(new Dimension(320, 144),
                VideoScreen.displayAspect(320, 144, false));
    }
}
