package net.chonkbase.chonkcraft.engine.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Tests for the UDP transport, over the loopback interface. */
class NetworkSessionTest {

    /** Waits for a batch to arrive, or gives up. */
    private static List<NetworkSession.Batch> awaitBatches(NetworkSession session, int atLeast)
            throws InterruptedException {
        for (int attempt = 0; attempt < 200; attempt++) {
            List<NetworkSession.Batch> batches = session.poll();
            if (batches.size() >= atLeast) {
                return batches;
            }
            Thread.sleep(5);
        }
        return List.of();
    }

    @Test
    void aBatchCrossesBetweenTwoSessions() throws Exception {
        try (NetworkSession host = new NetworkSession(0, 0);
                NetworkSession guest = new NetworkSession(1, 0)) {

            InetAddress loopback = InetAddress.getLoopbackAddress();
            host.addPeer(1, loopback, guest.localPort());

            List<GameCommand> sent = List.of(
                    GameCommand.move(0, 7, 12, 34),
                    GameCommand.attack(0, 8, 9));
            host.broadcast(42, 41L, 0xDEADBEEFL, sent);

            List<NetworkSession.Batch> received = awaitBatches(guest, 1);
            assertEquals(1, received.size(), "nothing arrived");

            NetworkSession.Batch batch = received.getFirst();
            assertEquals(42, batch.netCycle());
            assertEquals(0, batch.player());
            assertEquals(41L, batch.hashCycle(), "the hash's own cycle travels with it");
            assertEquals(0xDEADBEEFL, batch.syncHash());
            assertEquals(sent, batch.commands());
        }
    }

    @Test
    void anEmptyBatchStillArrives() throws Exception {
        // Silence and a lost packet look identical, so a player with nothing
        // to order still has to say so.
        try (NetworkSession host = new NetworkSession(0, 0);
                NetworkSession guest = new NetworkSession(1, 0)) {
            host.addPeer(1, InetAddress.getLoopbackAddress(), guest.localPort());
            host.broadcast(7, 6L, 123L, List.of());

            List<NetworkSession.Batch> received = awaitBatches(guest, 1);
            assertEquals(1, received.size(), "an empty batch is not the same as no batch");
            assertEquals(7, received.getFirst().netCycle());
            assertEquals(List.of(), received.getFirst().commands());
        }
    }

    @Test
    void everyPeerGetsTheBatch() throws Exception {
        try (NetworkSession host = new NetworkSession(0, 0);
                NetworkSession first = new NetworkSession(1, 0);
                NetworkSession second = new NetworkSession(2, 0)) {

            InetAddress loopback = InetAddress.getLoopbackAddress();
            host.addPeer(1, loopback, first.localPort());
            host.addPeer(2, loopback, second.localPort());
            host.broadcast(1, 0L, 0L, List.of(GameCommand.stop(0, 5)));

            assertEquals(1, awaitBatches(first, 1).size());
            assertEquals(1, awaitBatches(second, 1).size());
        }
    }

    @Test
    void rubbishOnThePortIsIgnored() throws Exception {
        // Anything can arrive on a UDP port; the game must not fall over.
        try (NetworkSession guest = new NetworkSession(1, 0);
                DatagramSocket stranger = new DatagramSocket()) {

            byte[] noise = "hello, wrong port".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            stranger.send(new DatagramPacket(noise, noise.length,
                    InetAddress.getLoopbackAddress(), guest.localPort()));

            Thread.sleep(50);
            assertEquals(List.of(), guest.poll(), "foreign traffic should be dropped");
        }
    }

    @Test
    void aTruncatedPacketIsDroppedRatherThanHalfApplied() throws Exception {
        try (NetworkSession guest = new NetworkSession(1, 0);
                DatagramSocket stranger = new DatagramSocket()) {

            // A well-formed header claiming five commands, with none attached.
            java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(34);
            buffer.putInt(0x57475553);
            buffer.putShort((short) 1);
            buffer.put((byte) 0);
            buffer.put((byte) 0);
            buffer.putLong(1);
            buffer.putLong(0);
            buffer.putLong(0);
            buffer.putShort((short) 5);

            stranger.send(new DatagramPacket(buffer.array(), buffer.position(),
                    InetAddress.getLoopbackAddress(), guest.localPort()));

            Thread.sleep(50);
            assertEquals(List.of(), guest.poll(),
                    "half a batch applied is worse than none: it desyncs");
        }
    }

    @Test
    void pollDoesNotBlockWhenNothingHasArrived() throws Exception {
        try (NetworkSession session = new NetworkSession(0, 0)) {
            long start = System.nanoTime();
            assertEquals(List.of(), session.poll());
            long elapsedMillis = (System.nanoTime() - start) / 1_000_000;
            // It runs on the simulation thread, so it must return promptly.
            assertTrue(elapsedMillis < 500, "poll took " + elapsedMillis + "ms");
        }
    }

    @Test
    void anOversizedBatchIsRefusedRatherThanSilentlyTruncated() throws Exception {
        try (NetworkSession session = new NetworkSession(0, 0)) {
            List<GameCommand> huge = new java.util.ArrayList<>();
            for (int i = 0; i < 500; i++) {
                huge.add(GameCommand.move(0, i, 1, 1));
            }
            assertThrows(IllegalArgumentException.class,
                    () -> session.broadcast(1, 0L, 0L, huge));
        }
    }

    @Test
    void aSenderCannotClaimAnotherPlayersSlot() throws Exception {
        try (NetworkSession legitimate = new NetworkSession(0, 0);
                NetworkSession impostor = new NetworkSession(0, 0);
                NetworkSession guest = new NetworkSession(1, 0)) {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            guest.addPeer(0, loopback, legitimate.localPort());
            impostor.addPeer(1, loopback, guest.localPort());

            impostor.broadcast(3, 2, 0, List.of(GameCommand.quit(0, 0,
                    GameCommand.DepartureReason.LEFT)));

            Thread.sleep(25);
            assertEquals(List.of(), guest.poll(),
                    "an arbitrary UDP sender manufactured another player's departure");
        }
    }

    @Test
    void aClientAcceptsAHostAdjudicatedBatchForASilentPlayer() throws Exception {
        try (NetworkSession host = new NetworkSession(0, 0);
                NetworkSession guest = new NetworkSession(1, 0)) {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            host.addPeer(1, loopback, guest.localPort());
            guest.addPeer(0, loopback, host.localPort());
            guest.setTrustedRelay(new java.net.InetSocketAddress(loopback, host.localPort()));

            GameCommand quit = GameCommand.quit(2, 1 << 1,
                    GameCommand.DepartureReason.TIMEOUT);
            host.broadcastAs(2, 9, 8, 123, List.of(quit));

            List<NetworkSession.Batch> received = awaitBatches(guest, 1);
            assertEquals(2, received.getFirst().player());
            assertEquals(quit, received.getFirst().commands().getFirst());
        }
    }

    @Test
    void aPacketCannotContainCommandsIssuedAsAnotherPlayer() throws Exception {
        try (NetworkSession sender = new NetworkSession(0, 0);
                NetworkSession guest = new NetworkSession(1, 0)) {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            sender.addPeer(1, loopback, guest.localPort());
            guest.addPeer(0, loopback, sender.localPort());

            sender.broadcast(4, 3, 0, List.of(GameCommand.move(1, 7, 8, 9)));

            Thread.sleep(25);
            assertEquals(List.of(), guest.poll(),
                    "packet owner zero smuggled in an order claiming to be player one");
        }
    }
}
