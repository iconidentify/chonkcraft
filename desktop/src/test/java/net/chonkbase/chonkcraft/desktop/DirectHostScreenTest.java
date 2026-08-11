package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.util.concurrent.atomic.AtomicInteger;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The direct-host screen opens exactly the UDP port it shows the player. */
class DirectHostScreenTest {

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "no asset source");
        return new GameData(assets);
    }

    @Test
    @DisplayName("Direct hosting starts on the visible default UDP port")
    void theVisibleDefaultIsThePortThatOpens() {
        AtomicInteger opened = new AtomicInteger();
        DirectHostScreen screen = new DirectHostScreen(load(), "Garden of War",
                listener(opened));

        assertEquals(7100, screen.selectedPort());
        screen.type(KeyEvent.VK_ENTER, '\n');
        assertEquals(7100, opened.get(),
                "pressing Enter should bind the same default shown on the screen");
    }

    @Test
    @DisplayName("Typing replaces the default and opens the chosen UDP port")
    void aHostCanChooseAnotherPort() {
        AtomicInteger opened = new AtomicInteger();
        DirectHostScreen screen = new DirectHostScreen(load(), "Garden of War",
                listener(opened));

        screen.render();
        java.awt.Rectangle field = DirectHostScreen.portFieldBounds();
        assertTrue(screen.clickDesign(field.x + field.width / 2, field.y + field.height / 2),
                "the visible UDP field should accept focus when clicked");
        for (char digit : "8123".toCharArray()) {
            screen.type(0, digit);
        }
        assertEquals(8123, screen.selectedPort());
        screen.type(KeyEvent.VK_ENTER, '\n');
        assertEquals(8123, opened.get());
    }

    @Test
    @DisplayName("A privileged UDP port is explained and never opened")
    void aPrivilegedPortIsRefused() {
        AtomicInteger opened = new AtomicInteger();
        DirectHostScreen screen = new DirectHostScreen(load(), "Garden of War",
                listener(opened));

        for (char digit : "80".toCharArray()) {
            screen.type(0, digit);
        }
        screen.type(KeyEvent.VK_ENTER, '\n');
        assertEquals(0, opened.get(), "invalid ports must stop before binding a socket");
    }

    private static DirectHostScreen.Listener listener(AtomicInteger opened) {
        return new DirectHostScreen.Listener() {
            @Override
            public void onHost(int port) {
                opened.set(port);
            }

            @Override
            public void onCancel() {
            }
        };
    }
}
