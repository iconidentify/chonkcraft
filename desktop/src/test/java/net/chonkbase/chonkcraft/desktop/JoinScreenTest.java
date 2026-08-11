package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.network.GameDiscovery;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.GameListing;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.Phase;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Finding a game, both ways.
 *
 * <p>The typed address matters more than it looks. Discovery covers one house
 * on one network and nothing else: over the internet, through a tunnel, or on
 * a network where broadcast is turned off, typing an address is the only way
 * in. So it is tested as the primary path rather than the fallback.
 */
class JoinScreenTest {

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No Warcraft II assets configured.");
        return new GameData(assets);
    }

    /** Records where the screen said to connect. */
    private static final class Recording implements JoinScreen.Listener {
        private String host;
        private String code;
        private int port = -1;
        private boolean cancelled;
        private boolean updateRequired;

        @Override
        public void onJoinOnline(String code) {
            this.code = code;
        }

        @Override
        public void onJoin(String host, int port) {
            this.host = host;
            this.port = port;
        }

        @Override
        public void onCancel() {
            cancelled = true;
        }

        @Override
        public void onUpdateRequired() {
            updateRequired = true;
        }
    }

    private static void type(JoinScreen screen, String text) {
        for (char c : text.toCharArray()) {
            screen.type(0, c);
        }
    }

    @Test
    @DisplayName("An address can be typed, corrected, and connected to")
    void anAddressIsTyped() {
        GameData data = load();
        Recording heard = new Recording();
        JoinScreen screen = new JoinScreen(data, null, heard);
        screen.showLocalForTest();

        type(screen, "192.168.1.44");
        assertEquals("192.168.1.44", screen.address());

        // A typo is taken back rather than starting again.
        screen.type(KeyEvent.VK_BACK_SPACE, '\b');
        assertEquals("192.168.1.4", screen.address());
        type(screen, "4");

        screen.type(KeyEvent.VK_ENTER, '\n');
        assertEquals("192.168.1.44", heard.host);
        assertEquals(JoinScreen.DEFAULT_PORT, heard.port,
                "leaving the port out should mean the usual one, not a failure");
    }

    @Test
    @DisplayName("A port can be given, and rubbish in its place is refused")
    void aPortCanBeGiven() {
        GameData data = load();
        Recording heard = new Recording();
        JoinScreen screen = new JoinScreen(data, null, heard);
        screen.showLocalForTest();

        type(screen, "gamehost.local:7205");
        screen.type(KeyEvent.VK_ENTER, '\n');
        assertEquals("gamehost.local", heard.host);
        assertEquals(7205, heard.port);

        Recording second = new Recording();
        JoinScreen wrong = new JoinScreen(data, null, second);
        wrong.showLocalForTest();
        type(wrong, "gamehost.local:nope");
        wrong.type(KeyEvent.VK_ENTER, '\n');
        assertNull(second.host, "a port that is not a number should be refused, not guessed");
    }

    @Test
    @DisplayName("A bracketed IPv6 host keeps its address and explicit UDP port")
    void ipv6CanBeJoinedDirectly() {
        GameData data = load();
        Recording heard = new Recording();
        JoinScreen screen = new JoinScreen(data, null, heard);
        screen.showLocalForTest();

        type(screen, "[2001:db8::44]:7205");
        screen.type(KeyEvent.VK_ENTER, '\n');
        assertEquals("2001:db8::44", heard.host);
        assertEquals(7205, heard.port);
    }

    @Test
    @DisplayName("Only what can be in an address gets into the field")
    void strayKeystrokesAreIgnored() {
        GameData data = load();
        JoinScreen screen = new JoinScreen(data, null, new Recording());
        screen.showLocalForTest();
        type(screen, "10.0.0.1");
        // Arrow keys and modifiers arrive as characters that are not addresses.
        screen.type(KeyEvent.VK_LEFT, KeyEvent.CHAR_UNDEFINED);
        screen.type(0, ' ');
        screen.type(0, '/');
        assertEquals("10.0.0.1", screen.address(),
                "a field that looks right and is not is worse than one that refuses");
    }

    @Test
    @DisplayName("Nothing typed is refused rather than connecting to nowhere")
    void anEmptyAddressGoesNowhere() {
        GameData data = load();
        Recording heard = new Recording();
        JoinScreen screen = new JoinScreen(data, null, heard);
        screen.showLocalForTest();
        screen.type(KeyEvent.VK_ENTER, '\n');
        assertNull(heard.host);
    }

    @Test
    @DisplayName("A discovered game is joined by clicking it")
    void aFoundGameIsClickable() {
        GameData data = load();
        Recording heard = new Recording();
        JoinScreen screen = new JoinScreen(data, null, heard);
        screen.showLocalForTest();
        screen.setFound(List.of(
                new GameDiscovery.Game("Chris", "garden.pud", 2, 8, "10.0.0.5", 7100,
                        System.currentTimeMillis()),
                new GameDiscovery.Game("Ann", "hills.pud", 4, 4, "10.0.0.6", 7100,
                        System.currentTimeMillis())));
        screen.render();

        Rectangle first = JoinScreen.rowBounds(0);
        assertTrue(screen.clickDesign(first.x + 20, first.y + first.height / 2));
        assertEquals("10.0.0.5", heard.host);
        assertEquals(7100, heard.port);

        // The second is full, so clicking it does nothing: a lobby that lets
        // somebody join a game with no room only tells them so afterwards.
        Recording second = new Recording();
        JoinScreen full = new JoinScreen(data, null, second);
        full.showLocalForTest();
        full.setFound(List.of(new GameDiscovery.Game("Ann", "hills.pud", 4, 4,
                "10.0.0.6", 7100, System.currentTimeMillis())));
        full.render();
        Rectangle row = JoinScreen.rowBounds(0);
        assertFalse(full.clickDesign(row.x + 20, row.y + row.height / 2),
                "a full game should not be joinable");
        assertNull(second.host);
    }

    @Test
    @DisplayName("A LAN game on another build offers an update instead of connecting")
    void anIncompatibleLanGameCannotBeJoined() {
        GameData data = load();
        Recording heard = new Recording();
        JoinScreen screen = new JoinScreen(data, null, heard);
        screen.showLocalForTest();
        screen.setFound(List.of(new GameDiscovery.Game("Old host", "garden.pud", 1, 8,
                "10.0.0.5", 7100, System.currentTimeMillis(), "another-build")));
        screen.render();

        Rectangle row = JoinScreen.rowBounds(0);
        assertTrue(screen.clickDesign(row.x + 20, row.y + row.height / 2));
        assertNull(heard.host, "the incompatible LAN game was contacted");
        assertTrue(screen.updateRequiredForTest());

        screen.render();
        Rectangle update = JoinScreen.updateBounds();
        assertTrue(screen.clickDesign(update.x + update.width / 2,
                update.y + update.height / 2));
        assertTrue(heard.updateRequired);
    }

    @Test
    @DisplayName("A game with a long name does not write across the map beside it")
    void longNamesAreCutToTheirColumn() {
        GameData data = load();
        JoinScreen screen = new JoinScreen(data, null, new Recording());
        screen.showLocalForTest();
        screen.setFound(List.of(new GameDiscovery.Game(
                "A Very Long Player Name Indeed That Nobody Would Choose",
                "Battle on the Rocks", 2, 8, "10.0.0.5", 7100, System.currentTimeMillis())));
        java.awt.image.BufferedImage drawn = screen.render();

        // The map's column starts here, and nothing from the name column may
        // reach it. Drawn whole, that name ran straight through the map.
        Rectangle row = JoinScreen.rowBounds(0);
        int gutter = row.x + 200;
        for (int y = row.y + 2; y < row.y + row.height - 2; y++) {
            int rgb = drawn.getRGB(gutter, y);
            int luminance = ((rgb >> 16 & 255) * 3 + (rgb >> 8 & 255) * 6 + (rgb & 255)) / 10;
            assertTrue(luminance < 110,
                    "lettering at " + gutter + "," + y + " is in the gutter between columns");
        }
    }

    @Test
    @DisplayName("A pasted spoken game code is normalized before joining")
    void aGameCodeIsJoined() {
        GameData data = load();
        Recording heard = new Recording();
        JoinScreen screen = new JoinScreen(data, null, heard);
        type(screen, "a-il b2c");
        screen.type(KeyEvent.VK_ENTER, '\n');
        assertEquals("A11B2C", heard.code);
    }

    @Test
    @DisplayName("A waiting public game is one click from the browser")
    void anOnlineGameIsClickable() {
        GameData data = load();
        Recording heard = new Recording();
        JoinScreen screen = new JoinScreen(data, null, heard);
        screen.setOnlineGames(List.of(new GameListing("ABC123", "Chris's Game", "Garden",
                2, 4, "test", Phase.WAITING, 1)));
        screen.render();

        Rectangle row = JoinScreen.rowBounds(0);
        assertTrue(screen.clickDesign(row.x + 20, row.y + row.height / 2));
        assertEquals("ABC123", heard.code);
    }

    @Test
    @DisplayName("Everything the join screen draws stays inside its panel")
    void nothingRunsOffThePanel() {
        GameData data = load();
        JoinScreen screen = new JoinScreen(data, null, new Recording());
        screen.render();

        // The panel is inset sixteen from the page on every side, and the
        // buttons at the foot have to clear it by as much as the heading does
        // at the head.
        Rectangle last = JoinScreen.rowBounds(JoinScreen.visibleRows() - 1);
        Rectangle field = JoinScreen.fieldBounds();
        Rectangle connect = JoinScreen.connectBounds();
        Rectangle cancel = JoinScreen.cancelBounds();
        assertTrue(last.y + last.height <= field.y - 20,
                "the list runs into the address field");
        assertEquals(field.y, connect.y, "Connect should sit on the field's own line");
        assertTrue(field.x + field.width < connect.x, "the field runs under Connect");
        assertTrue(cancel.y > field.y + field.height, "Cancel sits on the field");
        assertTrue(cancel.y + cancel.height <= 464,
                "Cancel reaches " + (cancel.y + cancel.height) + ", past the panel's 464");
    }

    @Test
    @DisplayName("Escape backs out")
    void escapeCancels() {
        GameData data = load();
        Recording heard = new Recording();
        JoinScreen screen = new JoinScreen(data, null, heard);
        screen.type(KeyEvent.VK_ESCAPE, (char) 27);
        assertTrue(heard.cancelled);
    }
}
