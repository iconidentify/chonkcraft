package net.chonkbase.chonkcraft.engine.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class TitleSequenceTest {

    @Test
    void nativeSequenceUsesRetailMediaWithoutTheChonkCraftBlackImage() {
        List<TitleSequence.Screen> screens = TitleSequence.battleNet();

        assertEquals(4, screens.size());
        assertEquals(TitleSequence.Kind.BLACK, screens.get(0).kind());
        assertNull(screens.get(0).assetPath(), "native black must not request an asset");
        assertEquals("videos/logo", screens.get(1).assetPath());
        assertEquals("videos/gameintro", screens.get(2).assetPath());
        assertEquals("ui/title", screens.get(3).assetPath());
        assertTrue(screens.get(3).menuMusic());
        assertFalse(screens.stream().anyMatch(screen ->
                screen.assetPath() != null && screen.assetPath().contains("black_title")));
    }
}
