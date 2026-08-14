package net.chonkbase.chonkcraft.engine.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Hosts announcing themselves, and joiners hearing them.
 *
 * <p>These run a host and a listener in one process. That proves the format
 * and the ageing; it cannot prove that broadcast crosses a real network, which
 * is why typing an address by hand stays available.
 */
class GameDiscoveryTest {

    /**
     * Not the real port.
     *
     * <p>Discovery uses a fixed one because both sides must agree on it, which
     * means a game actually running on this machine announces on it -- and a
     * test that listened there would collect that game and fail on it.
     *
     * <p>One per test rather than one for the class, because these tests
     * broadcast: a host announcing in one test is still heard by a listener
     * that binds the same port in the next, and the packet arrives after the
     * test that sent it has finished. Sharing a port made the last test in the
     * file fail only when the whole file ran.
     */
    private static final int FOUND_PORT = 7191;

    private static final int STALE_PORT = 7192;

    private static final int RUBBISH_PORT = 7193;

    /** Announces until the listener hears it, or gives up. */
    private static List<GameDiscovery.Game> hear(GameDiscovery host, GameDiscovery listener)
            throws Exception {
        for (int attempt = 0; attempt < 40; attempt++) {
            host.announce("Chris", "ALAMO.PUD", 2, 8, 7100);
            List<GameDiscovery.Game> games = listener.games();
            if (!games.isEmpty()) {
                return games;
            }
            Thread.sleep(50);
            // announce() rate limits itself, so let its interval pass.
            java.lang.reflect.Field field = GameDiscovery.class.getDeclaredField("lastAnnounced");
            field.setAccessible(true);
            field.setLong(host, 0L);
        }
        return List.of();
    }

    @Test
    @DisplayName("a listener hears an announced game with its details intact")
    void aGameIsFound() throws Exception {
        try (GameDiscovery listener = new GameDiscovery(true, FOUND_PORT);
                GameDiscovery host = new GameDiscovery(false, FOUND_PORT)) {
            List<GameDiscovery.Game> games = hear(host, listener);
            Assumptions.assumeFalse(games.isEmpty(),
                    "no broadcast reached the loopback on this machine");

            GameDiscovery.Game game = games.get(0);
            assertEquals("Chris", game.name());
            assertEquals("ALAMO.PUD", game.map());
            assertEquals(2, game.players());
            assertEquals(8, game.capacity());
            assertEquals(7100, game.port());
            assertNotNull(game.host());
            assertTrue(game.hasRoom(), "two of eight should have room");
            assertTrue(game.isCompatible(), "this process did not recognize its own build");
        }
    }

    @Test
    @DisplayName("A LAN announcement from another build is visibly incompatible")
    void anotherBuildIsNotJoinable() {
        GameDiscovery.Game stale = new GameDiscovery.Game(
                "Old host", "ALAMO.PUD", 1, 8, "192.168.1.4", 7100,
                System.currentTimeMillis(), "definitely-another-build");
        assertFalse(stale.isCompatible());
    }

    @Test
    @DisplayName("a full game says it is full")
    void aFullGameHasNoRoom() {
        GameDiscovery.Game full = new GameDiscovery.Game(
                "Chris", "ALAMO.PUD", 8, 8, "192.168.1.4", 7100, System.currentTimeMillis());
        assertFalse(full.hasRoom());
    }

    @Test
    @DisplayName("a game that stops announcing drops off the list")
    void silenceAgesAGameOut() throws Exception {
        try (GameDiscovery listener = new GameDiscovery(true, STALE_PORT);
                GameDiscovery host = new GameDiscovery(false, STALE_PORT)) {
            List<GameDiscovery.Game> games = hear(host, listener);
            Assumptions.assumeFalse(games.isEmpty(), "no broadcast on this machine");

            // Stop the host and drain the listener before ageing. A packet
            // still sitting on the socket used to resurrect the listing after
            // the timestamps were pushed past STALE -- the test failed only
            // when the rest of the class had already been shouting.
            host.close();
            listener.games();

            // Age the heard announcement past its welcome rather than waiting
            // several seconds for real.
            java.lang.reflect.Field field = GameDiscovery.class.getDeclaredField("found");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            var found = (java.util.Map<String, GameDiscovery.Game>) field.get(listener);
            found.replaceAll((key, game) -> new GameDiscovery.Game(game.name(), game.map(),
                    game.players(), game.capacity(), game.host(), game.port(),
                    System.currentTimeMillis() - GameDiscovery.STALE_AFTER_MILLIS - 1));

            assertTrue(listener.games().isEmpty(),
                    "a host that has gone quiet is still being listed");
        }
    }

    @Test
    @DisplayName("rubbish on the port is ignored")
    void foreignTrafficIsIgnored() throws Exception {
        // Anything at all can arrive on an open UDP port, and the game should
        // not fall over because something else on the network found it.
        try (GameDiscovery listener = new GameDiscovery(true, RUBBISH_PORT);
                java.net.DatagramSocket noise = new java.net.DatagramSocket()) {
            byte[] rubbish = "not a game announcement at all".getBytes();
            noise.send(new java.net.DatagramPacket(rubbish, rubbish.length,
                    java.net.InetAddress.getLoopbackAddress(), RUBBISH_PORT));
            Thread.sleep(60);
            assertTrue(listener.games().isEmpty(), "rubbish was accepted as a game");
        }
    }

    @Test
    @DisplayName("there is somewhere to broadcast to")
    void thereIsAlwaysATarget() {
        assertFalse(GameDiscovery.broadcastAddresses().isEmpty(),
                "no broadcast address at all, not even the loopback fallback");
    }
}
