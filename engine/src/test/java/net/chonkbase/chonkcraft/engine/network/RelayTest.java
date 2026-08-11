package net.chonkbase.chonkcraft.engine.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The host as a message switch.
 *
 * <p>Eight players talking directly to each other is twenty-eight links, and
 * every one of them is somewhere a cycle can stall. Everyone talking to the
 * host is eight, and only the host has to know where anybody is.
 *
 * <p>These run three sessions in one process on the loopback, which is enough
 * to prove the routing: a client's batch reaches every other client, and no
 * client is sent its own batch back.
 */
class RelayTest {

    /**
     * Reads until the expected number of batches arrive, or the patience runs
     * out.
     *
     * <p>Against a wall clock rather than a count of attempts. A fixed number
     * of five millisecond sleeps is a fixed amount of *scheduler* time, not of
     * real time, and when the whole suite runs at once those sleeps stretch --
     * so this passed alone and failed in company, which is the least useful
     * way for a test to behave.
     */
    private static List<NetworkSession.Batch> drain(NetworkSession session, int expected)
            throws Exception {
        List<NetworkSession.Batch> got = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 5_000L;
        while (got.size() < expected && System.currentTimeMillis() < deadline) {
            got.addAll(session.poll());
            if (got.size() < expected) {
                Thread.sleep(5);
            }
        }
        return got;
    }

    @Test
    @DisplayName("a client's batch reaches every other client through the host")
    void theHostPassesBatchesOn() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        try (NetworkSession host = new NetworkSession(0, 0);
                NetworkSession one = new NetworkSession(1, 0);
                NetworkSession two = new NetworkSession(2, 0)) {

            // Clients know only the host; the host knows everyone.
            one.addPeer(0, local, host.localPort());
            two.addPeer(0, local, host.localPort());
            two.setTrustedRelay(new java.net.InetSocketAddress(local, host.localPort()));
            host.addPeer(1, local, one.localPort());
            host.addPeer(2, local, two.localPort());

            one.broadcast(7, 6, 0xABCDL, List.of(GameCommand.move(1, 42, 3, 4)));

            List<NetworkSession.Batch> atHost = drain(host, 1);
            assertEquals(1, atHost.size(), "the host did not receive it");
            NetworkSession.Batch fromOne = atHost.get(0);
            assertEquals(1, fromOne.player());

            host.relay(fromOne);

            List<NetworkSession.Batch> atTwo = drain(two, 1);
            assertEquals(1, atTwo.size(), "player two never saw player one's commands");
            assertEquals(7, atTwo.get(0).netCycle());
            assertEquals(1, atTwo.get(0).player());
            assertEquals(1, atTwo.get(0).commands().size());
            assertEquals(GameCommand.Kind.MOVE, atTwo.get(0).commands().get(0).kind());

            // And player one is not sent its own batch back.
            List<NetworkSession.Batch> echo = one.poll();
            assertTrue(echo.isEmpty(), "the sender got its own batch returned: " + echo);
        }
    }

    @Test
    @DisplayName("the relay forwards the bytes untouched")
    void theRelayDoesNotRewrite() throws Exception {
        // A host that decodes and re-encodes is a host that can alter what it
        // passes on, and nobody downstream could tell.
        InetAddress local = InetAddress.getLoopbackAddress();
        try (NetworkSession host = new NetworkSession(0, 0);
                NetworkSession one = new NetworkSession(1, 0);
                NetworkSession two = new NetworkSession(2, 0)) {
            one.addPeer(0, local, host.localPort());
            two.addPeer(0, local, host.localPort());
            two.setTrustedRelay(new java.net.InetSocketAddress(local, host.localPort()));
            host.addPeer(1, local, one.localPort());
            host.addPeer(2, local, two.localPort());

            List<GameCommand> sent = List.of(
                    GameCommand.attack(1, 5, 9),
                    GameCommand.harvest(1, 5, 12, 13));
            one.broadcast(11, 10, 0x1234L, sent);

            NetworkSession.Batch atHost = drain(host, 1).get(0);
            host.relay(atHost);
            NetworkSession.Batch atTwo = drain(two, 1).get(0);

            assertArrayEqualsRaw(atHost.raw(), atTwo.raw());
            assertEquals(sent.size(), atTwo.commands().size());
            assertEquals(0x1234L, atTwo.syncHash());
            assertEquals(10, atTwo.hashCycle());
        }
    }

    private static void assertArrayEqualsRaw(byte[] expected, byte[] actual) {
        assertEquals(expected.length, actual.length, "the relayed packet changed length");
        for (int i = 0; i < expected.length; i++) {
            assertEquals(expected[i], actual[i], "byte " + i + " was rewritten in transit");
        }
    }

    @Test
    @DisplayName("a departed peer stops being sent to")
    void aDepartedPeerIsForgotten() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        try (NetworkSession host = new NetworkSession(0, 0);
                NetworkSession two = new NetworkSession(2, 0)) {
            host.addPeer(2, local, two.localPort());
            assertEquals(1, host.peers().size());
            host.removePeer(2);
            assertTrue(host.peers().isEmpty(), "the host still holds a departed player");
        }
    }
}
