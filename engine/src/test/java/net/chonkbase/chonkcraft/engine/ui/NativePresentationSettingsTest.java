package net.chonkbase.chonkcraft.engine.ui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NativePresentationSettingsTest {
    @Test
    void battleNetPlayerRampsAreCompleteAndRepeatForSixteenSlots() {
        PlayerColours colours = PlayerColours.battleNet();
        assertTrue(colours.isDefined());
        assertEquals(208, colours.firstIndex());
        assertEquals(4, colours.count());
        assertEquals(16, colours.ramps().size());
        for (int i = 0; i < 8; i++) {
            assertEquals(colours.ramps().get(i).name(), colours.ramps().get(i + 8).name());
            assertArrayEquals(colours.ramps().get(i).colours(), colours.ramps().get(i + 8).colours());
        }
    }

    @Test
    void battleNetFogLevelsAreValidAndDistinctForTheMinimap() {
        FogOfWarSettings fog = FogOfWarSettings.battleNet();
        assertTrue(fog.levels().isValid());
        assertTrue(fog.minimapLevels().isValid());
        assertEquals(new FogOfWarSettings.Levels(0x7F, 0xBE, 0xFE), fog.levels());
        assertEquals(new FogOfWarSettings.Levels(0x55, 0xAA, 0xFF), fog.minimapLevels());
    }
}
