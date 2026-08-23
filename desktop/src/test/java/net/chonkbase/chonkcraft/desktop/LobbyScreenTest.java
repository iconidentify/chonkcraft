package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.net.InetAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.network.GameLobby;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The lobby screen, clicked where it draws.
 *
 * <p>Every click here goes through the screen's own hit regions, which are
 * built while it draws. A test that called the lobby's methods directly would
 * prove the protocol -- already proven elsewhere -- and nothing about whether
 * the buttons a player can see do what they say.
 */
class LobbyScreenTest {

    private static final int PORT = 7501;

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II media configured. Set CHONKCRAFT_ASSET_PACK.");
        return new GameData(assets);
    }

    /** A listener that records what the screen asked for. */
    private static final class Recording implements LobbyScreen.Listener {
        private final AtomicBoolean started = new AtomicBoolean();
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private final AtomicBoolean updateRequired = new AtomicBoolean();
        private final AtomicInteger startedCount = new AtomicInteger();

        @Override
        public void onStart(GameLobby lobby) {
            started.set(true);
            startedCount.incrementAndGet();
        }

        @Override
        public void onCancel() {
            cancelled.set(true);
        }

        @Override
        public void onUpdateRequired() {
            updateRequired.set(true);
        }
    }

    /** Clicks the middle of a region the screen says it drew something in. */
    private static boolean click(LobbyScreen screen, Rectangle where) {
        return screen.clickDesign(where.x + where.width / 2, where.y + where.height / 2);
    }

    @Test
    @DisplayName("A direct build mismatch leaves the lobby through the launcher update action")
    void aDirectMismatchOffersQuitToUpdate() throws Exception {
        String previous = System.getProperty("chonkcraft.network.build");
        InetAddress local = InetAddress.getLoopbackAddress();
        Recording heard = new Recording();
        try {
            System.setProperty("chonkcraft.network.build", "current-build");
            try (GameLobby host = GameLobby.host("Host", "garden.pud", 4, PORT + 20)) {
                System.setProperty("chonkcraft.network.build", "old-build");
                try (GameLobby client = GameLobby.join("Old", local, PORT + 20)) {
                    long deadline = System.currentTimeMillis() + 4_000L;
                    while (System.currentTimeMillis() < deadline && !client.updateRequired()) {
                        host.poll();
                        client.poll();
                        Thread.sleep(5);
                    }
                    assertTrue(client.updateRequired());
                    LobbyScreen screen = new LobbyScreen(null, client, null, heard);
                    screen.render();
                    assertTrue(click(screen, LobbyScreen.cancelBounds()));
                    assertTrue(heard.updateRequired.get());
                    assertFalse(heard.cancelled.get());
                }
            }
        } finally {
            if (previous == null) {
                System.clearProperty("chonkcraft.network.build");
            } else {
                System.setProperty("chonkcraft.network.build", previous);
            }
        }
    }

    @Test
    @DisplayName("The host cycles a slot between open, computer and closed")
    void slotsCycle() throws Exception {
        GameData data = load();
        try (GameLobby lobby = GameLobby.host("Chris", "garden.pud", 8, PORT)) {
            LobbyScreen screen = new LobbyScreen(data, lobby, "garden.pud", new Recording());
            screen.render();

            assertEquals(GameLobby.Occupant.OPEN, lobby.state().slots().get(2).occupant());
            assertTrue(click(screen, LobbyScreen.actionBounds(2)), "no button was drawn there");
            assertEquals(GameLobby.Occupant.COMPUTER, lobby.state().slots().get(2).occupant());

            screen.render();
            click(screen, LobbyScreen.actionBounds(2));
            assertEquals(GameLobby.Occupant.CLOSED, lobby.state().slots().get(2).occupant());

            screen.render();
            click(screen, LobbyScreen.actionBounds(2));
            assertEquals(GameLobby.Occupant.OPEN, lobby.state().slots().get(2).occupant());
        }
    }

    @Test
    @DisplayName("The host switches a slot's side")
    void raceToggles() throws Exception {
        GameData data = load();
        try (GameLobby lobby = GameLobby.host("Chris", "garden.pud", 8, PORT + 1)) {
            LobbyScreen screen = new LobbyScreen(data, lobby, "garden.pud", new Recording());
            screen.render();

            assertEquals("human", lobby.state().slots().get(0).race());
            assertTrue(click(screen, LobbyScreen.raceBounds(0)));
            assertEquals("orc", lobby.state().slots().get(0).race());
            screen.render();
            click(screen, LobbyScreen.raceBounds(0));
            assertEquals("human", lobby.state().slots().get(0).race());
        }
    }

    @Test
    @DisplayName("The host selects the Top vs Bottom team template")
    void gameTemplateToggles() throws Exception {
        GameData data = load();
        try (GameLobby lobby = GameLobby.host("Chris", "garden.pud", 8, PORT + 21)) {
            LobbyScreen screen = new LobbyScreen(data, lobby, "garden.pud", new Recording());
            screen.render();

            assertEquals(GameLobby.GameTemplate.MELEE, lobby.state().gameTemplate());
            assertTrue(click(screen, LobbyScreen.templateBounds()));
            assertEquals(GameLobby.GameTemplate.TOP_VS_BOTTOM,
                    lobby.state().gameTemplate());
            screen.render();
            assertTrue(click(screen, LobbyScreen.templateBounds()));
            assertEquals(GameLobby.GameTemplate.MELEE, lobby.state().gameTemplate());
        }
    }

    @Test
    @DisplayName("Top vs Bottom groups the map's real teams instead of implying row order")
    void topVsBottomShowsTheMapDefinedTeams() throws Exception {
        GameData data = load();
        byte[] map = data.source().map("All You Need BNE.pud");
        assertNotNull(map);
        try (GameLobby lobby = GameLobby.host(
                "Chris", "All You Need BNE.pud", map, 8, PORT + 23)) {
            lobby.setGameTemplate(GameLobby.GameTemplate.TOP_VS_BOTTOM);
            LobbyScreen screen = new LobbyScreen(data, lobby,
                    "All You Need BNE.pud", new Recording());
            screen.render();

            // This retail map interleaves north and south colour slots. A
            // plain colour-order table put Blue directly below Red and made
            // them look like teammates even though their starts are on
            // opposite halves of the map.
            int[] expected = {0, 3, 4, 6, 1, 2, 5, 7};
            for (int row = 0; row < expected.length; row++) {
                assertEquals(expected[row], screen.slotAtRowForTest(row),
                        "visual row " + row + " names the wrong starting area");
            }

            // Computers use the same visible move gesture as people. Moving
            // the bottom team's Yellow AI to the second Top Team row changes
            // the real colour/start slot, not merely its presentation.
            assertTrue(lobby.setOccupant(7, GameLobby.Occupant.COMPUTER));
            screen.render();
            assertTrue(click(screen, LobbyScreen.moveBounds(7)));
            screen.render();
            assertTrue(click(screen, LobbyScreen.rowBounds(1)));
            assertEquals(GameLobby.Occupant.COMPUTER,
                    lobby.state().slots().get(3).occupant());
            assertEquals(GameLobby.Occupant.OPEN,
                    lobby.state().slots().get(7).occupant());
        }
    }

    /**
     * The screen must not start a game one person can play, because the
     * lockstep scheduler would then be waiting on nobody and the player would
     * be sitting in a game that never advances.
     */
    @Test
    @DisplayName("Start is refused until there is somebody to play against")
    void aLoneHostCannotStart() throws Exception {
        GameData data = load();
        Recording heard = new Recording();
        try (GameLobby lobby = GameLobby.host("Chris", "garden.pud", 8, PORT + 2)) {
            LobbyScreen screen = new LobbyScreen(data, lobby, "garden.pud", heard);
            screen.render();

            click(screen, LobbyScreen.startBounds());
            assertFalse(heard.started.get(), "a game of one started");

            // A computer opponent is somebody to play against.
            screen.render();
            click(screen, LobbyScreen.actionBounds(1));
            assertEquals(GameLobby.Occupant.COMPUTER, lobby.state().slots().get(1).occupant());
            screen.render();
            click(screen, LobbyScreen.startBounds());
            assertTrue(heard.started.get(), "a host and a computer should be able to start");
        }
    }

    @Test
    @DisplayName("Start stays unavailable until the joiner's map is ready")
    void startWaitsForTheJoinersMap() throws Exception {
        Recording heard = new Recording();
        InetAddress local = InetAddress.getLoopbackAddress();
        byte[] map = new byte[125_000];
        for (int i = 0; i < map.length; i++) {
            map[i] = (byte) (i * 19 + 7);
        }
        try (GameLobby host = GameLobby.host(
                    "Chris", "host-only.pud", map, 8, PORT + 9);
                GameLobby client = GameLobby.join(
                    "Ann", local, PORT + 9, name -> null)) {
            long deadline = System.currentTimeMillis() + 4_000L;
            while (System.currentTimeMillis() < deadline && host.humanCount() < 2) {
                host.poll();
                client.poll();
                Thread.sleep(5);
            }
            assertEquals(2, host.humanCount(), "the joiner never reached the lobby");

            LobbyScreen screen = new LobbyScreen(null, host, "host-only.pud", heard);
            screen.render();
            assertFalse(click(screen, LobbyScreen.startBounds()),
                    "Start was clickable while the joiner still lacked the map");
            assertFalse(heard.started.get(), "the screen began an unsynchronized game");

            deadline = System.currentTimeMillis() + 4_000L;
            while (System.currentTimeMillis() < deadline
                    && !host.state().allPlayersReady()) {
                host.poll();
                client.poll();
                Thread.sleep(5);
            }
            assertTrue(host.state().allPlayersReady(),
                    "the joiner's completed transfer never unlocked Start");
            screen.render();
            assertTrue(click(screen, LobbyScreen.startBounds()),
                    "Start stayed unavailable after the map verified");
            assertTrue(heard.started.get(), "the ready lobby did not begin");
        }
    }

    @Test
    @DisplayName("A joiner enters the game once while the map loads")
    void aJoinerStartsOnce() throws Exception {
        Recording heard = new Recording();
        InetAddress local = InetAddress.getLoopbackAddress();
        try (GameLobby host = GameLobby.host("Chris", "garden.pud", 8, PORT + 10);
                GameLobby client = GameLobby.join("Ann", local, PORT + 10)) {
            long deadline = System.currentTimeMillis() + 4_000L;
            while (System.currentTimeMillis() < deadline && host.humanCount() < 2) {
                host.poll();
                client.poll();
                Thread.sleep(5);
            }
            assertEquals(2, host.humanCount(), "the joiner never reached the lobby");
            host.start();

            LobbyScreen screen = new LobbyScreen(null, client, null, heard);
            deadline = System.currentTimeMillis() + 4_000L;
            while (System.currentTimeMillis() < deadline && !client.isStarted()) {
                screen.tick();
                Thread.sleep(5);
            }
            screen.tick();
            screen.tick();
            assertEquals(1, heard.startedCount.get(),
                    "one Start packet launched the same client more than once");
        }
    }

    @Test
    @DisplayName("The host moves a player to another slot in two clicks")
    void aPlayerCanBeMoved() throws Exception {
        GameData data = load();
        InetAddress local = InetAddress.getLoopbackAddress();
        try (GameLobby host = GameLobby.host("Chris", "garden.pud", 8, PORT + 3);
                GameLobby client = GameLobby.join("Ann", local, PORT + 3)) {

            long deadline = System.currentTimeMillis() + 4_000L;
            while (System.currentTimeMillis() < deadline && host.humanCount() < 2) {
                host.poll();
                client.poll();
                Thread.sleep(5);
            }
            assertEquals(2, host.humanCount(), "the client never got in");
            int seat = 1;
            for (GameLobby.Slot slot : host.state().slots()) {
                if (slot.index() > 0 && slot.occupant() == GameLobby.Occupant.HUMAN) {
                    seat = slot.index();
                }
            }

            LobbyScreen screen = new LobbyScreen(data, host, "garden.pud", new Recording());
            screen.render();
            // Picking them up, then putting them down.
            assertTrue(click(screen, LobbyScreen.moveBounds(seat)));
            screen.render();
            assertTrue(click(screen, LobbyScreen.rowBounds(7)));
            assertEquals(GameLobby.Occupant.HUMAN, host.state().slots().get(7).occupant());
            assertEquals("Ann", host.state().slots().get(7).name());
            assertEquals(GameLobby.Occupant.OPEN, host.state().slots().get(seat).occupant());
        }
    }

    @Test
    @DisplayName("The creator can move itself to another colour and start location")
    void theHostCanChooseItsOwnColour() throws Exception {
        GameData data = load();
        try (GameLobby host = GameLobby.host("Chris", "garden.pud", 8, PORT + 22)) {
            LobbyScreen screen = new LobbyScreen(data, host, "garden.pud", new Recording());
            screen.render();
            assertTrue(click(screen, LobbyScreen.moveBounds(0)));
            screen.render();
            assertTrue(click(screen, LobbyScreen.rowBounds(6)));

            assertEquals(6, host.state().localSlot());
            assertEquals(GameLobby.Occupant.OPEN, host.state().slots().get(0).occupant());
            assertEquals("Chris", host.state().slots().get(6).name());

            screen.render();
            assertTrue(click(screen, LobbyScreen.actionBounds(0)),
                    "the creator's former colour must remain a usable lobby slot");
            assertEquals(GameLobby.Occupant.COMPUTER,
                    host.state().slots().get(0).occupant());
        }
    }

    /**
     * A client's screen must offer nothing it cannot deliver. Every control is
     * the host's, and the wire enforces that; showing buttons that quietly do
     * nothing would be worse than showing none.
     */
    @Test
    @DisplayName("A joiner's screen has no controls but Cancel")
    void aJoinerCanOnlyLeave() throws Exception {
        GameData data = load();
        InetAddress local = InetAddress.getLoopbackAddress();
        try (GameLobby client = GameLobby.join("Ann", local, PORT + 4)) {
            LobbyScreen screen = new LobbyScreen(data, client, null, new Recording());
            screen.render();
            assertFalse(click(screen, LobbyScreen.startBounds()),
                    "a joiner was offered a Start button");
            assertFalse(click(screen, LobbyScreen.actionBounds(2)),
                    "a joiner was offered a slot control");
            assertFalse(click(screen, LobbyScreen.rowBounds(1)),
                    "a joiner was able to pick a player up");
            assertFalse(click(screen, LobbyScreen.templateBounds()),
                    "a joiner was offered the game-template control");
        }
    }

    @Test
    @DisplayName("The table, the hint and the buttons all clear each other and the panel")
    void theLobbyIsSpacedEvenly() throws Exception {
        GameData data = load();
        try (GameLobby lobby = GameLobby.host("Chris", "garden.pud", 8, PORT + 6)) {
            LobbyScreen screen = new LobbyScreen(data, lobby, "garden.pud", new Recording());
            screen.render();

            Rectangle last = LobbyScreen.rowBounds(7);
            Rectangle start = LobbyScreen.startBounds();
            // Room under the table for the line of advice, and the buttons
            // clear of the panel's foot by what the heading clears its head by.
            assertTrue(start.y - (last.y + last.height) >= 40,
                    "no room between the table and the buttons for the hint");
            assertEquals(464 - (start.y + start.height), 16,
                    "the buttons should sit sixteen clear of the panel, as the heading does");

            // A row's two buttons never overlap the name beside them.
            Rectangle race = LobbyScreen.raceBounds(3);
            Rectangle action = LobbyScreen.actionBounds(3);
            assertTrue(race.x + race.width < action.x, "the race and action buttons overlap");
            assertTrue(action.x + action.width <= last.x + last.width,
                    "a row's button runs off the end of the row");
        }
    }

    @Test
    @DisplayName("A long player name is cut where the buttons begin")
    void aLongNameIsCut() throws Exception {
        GameData data = load();
        try (GameLobby lobby = GameLobby.host(
                "A Player Whose Name Goes On And On Without Any End To It",
                "garden.pud", 8, PORT + 7)) {
            LobbyScreen screen = new LobbyScreen(data, lobby, "garden.pud", new Recording());
            BufferedImage image = screen.render();

            // The gutter between the name and the race button, which nothing
            // out of the name column may reach.
            Rectangle race = LobbyScreen.raceBounds(0);
            Rectangle row = LobbyScreen.rowBounds(0);
            for (int y = row.y + 3; y < row.y + row.height - 3; y++) {
                int rgb = image.getRGB(race.x - 6, y);
                int luminance = ((rgb >> 16 & 255) * 3 + (rgb >> 8 & 255) * 6
                        + (rgb & 255)) / 10;
                assertTrue(luminance < 140,
                        "the name reaches the buttons at " + (race.x - 6) + "," + y);
            }
        }
    }

    @Test
    @DisplayName("The lobby letters in the same face as the in-game menu")
    void theLobbyUsesTheGameFace() throws Exception {
        GameData data = load();
        String inGame = GameFont.load(data, GameFont.Face.GAME).family();
        try (GameLobby lobby = GameLobby.host("Chris", "garden.pud", 8, PORT + 8)) {
            LobbyScreen screen = new LobbyScreen(data, lobby, "garden.pud", new Recording());
            for (String family : screen.faceFamiliesForTest()) {
                assertEquals(inGame, family, "the lobby letters in " + family);
            }
        }
    }

    @Test
    @DisplayName("Every slot is drawn, in the game's own colours")
    void everySlotIsDrawn() throws Exception {
        GameData data = load();
        try (GameLobby lobby = GameLobby.host("Chris", "garden.pud", 8, PORT + 5)) {
            LobbyScreen screen = new LobbyScreen(data, lobby, "garden.pud", new Recording());
            BufferedImage image = screen.render();
            assertNotNull(image);

            // The colour swatch of each row should be that player's colour.
            for (int i = 0; i < 8; i++) {
                Rectangle row = LobbyScreen.rowBounds(i);
                int rgb = image.getRGB(row.x + 12, row.y + 14) & 0xFFFFFF;
                assertEquals(PlayerColours.of(i).getRGB() & 0xFFFFFF, rgb,
                        "slot " + i + " is not drawn in " + PlayerColours.nameOf(i));
            }
        }
    }
}
