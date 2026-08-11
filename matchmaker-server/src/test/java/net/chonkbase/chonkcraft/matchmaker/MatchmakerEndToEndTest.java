package net.chonkbase.chonkcraft.matchmaker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.net.DatagramPacket;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import net.chonkbase.chonkcraft.engine.network.GameLobby;
import net.chonkbase.chonkcraft.engine.network.NetworkSession;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingClient;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingException;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.CreateGameRequest;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.JoinGameRequest;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.Visibility;
import net.chonkbase.chonkcraft.matchmaking.RelayDatagramSocket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A real HTTP directory, two WebSockets, map transfer, and final lobby agreement. */
class MatchmakerEndToEndTest {

    @Test
    @DisplayName("A browsed game can be joined by code and started through the relay")
    void aPublicGameTravelsThroughTheWholeService() throws Exception {
        int port = freePort();
        URI service = URI.create("http://127.0.0.1:" + port);
        URI relay = URI.create("ws://127.0.0.1:" + port + "/relay");
        try (MatchmakerServer server = new MatchmakerServer(port, relay,
                URI.create("https://chonkbase.net/chonkcraft/join/"))) {
            server.start();
            MatchmakingClient client = new MatchmakingClient(service);
            byte[] map = new byte[2_503];
            for (int i = 0; i < map.length; i++) {
                map[i] = (byte) (i * 31);
            }

            var hostSeat = client.create(new CreateGameRequest("Chris's Game", "Garden.pud",
                    "map-sha", 4, Visibility.PUBLIC, "test-build"));
            assertEquals(hostSeat.game(), client.games("test-build").games().getFirst());
            assertTrue(hostSeat.inviteUrl().endsWith(hostSeat.game().code()));

            var clientSeat = client.join(hostSeat.game().code().toLowerCase(),
                    new JoinGameRequest("Alex", "test-build"));
            try (RelayDatagramSocket hostSocket = socket(hostSeat);
                    RelayDatagramSocket clientSocket = socket(clientSeat)) {
                GameLobby host = GameLobby.hostRelayed("Chris", "Garden.pud", map, 4, hostSocket);
                GameLobby joiner = GameLobby.joinRelayed("Alex", clientSocket,
                        clientSeat.hostEndpointId(), ignored -> null);

                await(Duration.ofSeconds(4), () -> {
                    host.poll();
                    joiner.poll();
                    return joiner.state().localSlot() >= 0 && joiner.state().mapReady()
                            && host.state().allPlayersReady();
                });
                assertArrayEquals(map, joiner.mapBytes(),
                        "the relayed lobby must still authenticate and transfer the host's bytes");
                assertEquals(2, host.humanCount());

                hostSocket.markRoomStarted();
                await(Duration.ofSeconds(2),
                        () -> client.games("test-build").games().isEmpty());
                host.start();
                await(Duration.ofSeconds(2), () -> {
                    joiner.poll();
                    return joiner.isStarted();
                });
                assertTrue(joiner.isStarted(), "the client's final roster commit never arrived");

                assertTrue(client.games("test-build").games().isEmpty(),
                        "a started game must disappear from public browsing immediately");

                try (NetworkSession hostGame = new NetworkSession(0, host.releaseSocket());
                        NetworkSession clientGame = new NetworkSession(
                                joiner.state().localSlot(), joiner.releaseSocket())) {
                    host.peers().forEach(hostGame::addPeer);
                    joiner.peers().forEach(clientGame::addPeer);
                    clientGame.setTrustedRelay(joiner.relayAddress());
                    hostGame.broadcast(7, 6, 0x1234L, List.of());
                    final NetworkSession.Batch[] batch = new NetworkSession.Batch[1];
                    await(Duration.ofSeconds(2), () -> {
                        var received = clientGame.poll();
                        if (!received.isEmpty()) {
                            batch[0] = received.getFirst();
                        }
                        return batch[0] != null;
                    });
                    assertEquals(7, batch[0].netCycle());
                    assertEquals(0, batch[0].player());
                    assertTrue(batch[0].commands().isEmpty());
                }
            }
        }
    }

    @Test
    @DisplayName("Multiple clients receive the host map when theirs is absent or wrong")
    void multipleClientsReceiveTheAuthenticatedMapThroughTheRelay() throws Exception {
        int port = freePort();
        URI service = URI.create("http://127.0.0.1:" + port);
        URI relay = URI.create("ws://127.0.0.1:" + port + "/relay");
        try (MatchmakerServer server = new MatchmakerServer(port, relay,
                URI.create("https://chonkbase.net/chonkcraft/join/"))) {
            server.start();
            MatchmakingClient client = new MatchmakingClient(service);
            byte[] expected = new byte[125_000];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = (byte) (i * 31 + 7);
            }
            byte[] wrong = expected.clone();
            wrong[12_345] ^= 0x55;

            var hostSeat = client.create(new CreateGameRequest("Map Proof", "host-only.pud",
                    "map-sha", 4, Visibility.PRIVATE, "map-build"));
            var missingSeat = client.join(hostSeat.game().code(),
                    new JoinGameRequest("Missing Map", "map-build"));
            var wrongSeat = client.join(hostSeat.game().code(),
                    new JoinGameRequest("Wrong Map", "map-build"));

            try (RelayDatagramSocket hostSocket = socket(hostSeat);
                    RelayDatagramSocket missingSocket = socket(missingSeat);
                    RelayDatagramSocket wrongSocket = socket(wrongSeat);
                    GameLobby host = GameLobby.hostRelayed("Host", "host-only.pud", expected, 4,
                            hostSocket);
                    GameLobby missing = GameLobby.joinRelayed("Missing Map", missingSocket,
                            missingSeat.hostEndpointId(), ignored -> null);
                    GameLobby mismatched = GameLobby.joinRelayed("Wrong Map", wrongSocket,
                            wrongSeat.hostEndpointId(), ignored -> wrong)) {
                await(Duration.ofSeconds(8), () -> {
                    host.poll();
                    missing.poll();
                    mismatched.poll();
                    return missing.state().mapReady() && mismatched.state().mapReady()
                            && host.state().allPlayersReady();
                });

                assertArrayEquals(expected, missing.mapBytes());
                assertArrayEquals(expected, mismatched.mapBytes());
                assertTrue(missing.mapWasTransferred());
                assertTrue(mismatched.mapWasTransferred());
                assertEquals(100, missing.state().mapPercent());
                assertEquals(100, mismatched.state().mapPercent());
                assertEquals(3, host.humanCount());

                host.start();
                await(Duration.ofSeconds(2), () -> {
                    missing.poll();
                    mismatched.poll();
                    return missing.isStarted() && mismatched.isStarted();
                });
                hostSocket.closeRoom();
            }
        }
    }

    @Test
    @DisplayName("Private games stay out of browsing and reject a different game build")
    void privateRoomsAreCodeOnlyAndCompatible() throws Exception {
        int port = freePort();
        URI service = URI.create("http://127.0.0.1:" + port);
        try (MatchmakerServer server = new MatchmakerServer(port,
                URI.create("ws://127.0.0.1:" + port + "/relay"),
                URI.create("https://chonkbase.net/chonkcraft/join/"))) {
            server.start();
            MatchmakingClient client = new MatchmakingClient(service);
            var room = client.create(new CreateGameRequest("Invite Only", "Garden.pud", "sha",
                    2, Visibility.PRIVATE, "one-build"));

            assertTrue(client.games("one-build").games().isEmpty());
            MatchmakingException mismatch = assertThrows(MatchmakingException.class,
                    () -> client.join(room.game().code(),
                            new JoinGameRequest("Alex", "another-build")));
            assertEquals(426, mismatch.status());
            assertTrue(mismatch.getMessage().contains("one-build"));
            assertTrue(mismatch.getMessage().contains("another-build"));
            assertTrue(mismatch.getMessage().contains("Quit to the launcher"));
            assertEquals(room.game().code(), client.join(room.game().code(),
                    new JoinGameRequest("Alex", "one-build")).game().code());
        }
    }

    @Test
    @DisplayName("The same relay ticket can replace a dropped connection without changing seats")
    void aRelaySeatCanReconnect() throws Exception {
        int port = freePort();
        URI service = URI.create("http://127.0.0.1:" + port);
        try (MatchmakerServer server = new MatchmakerServer(port,
                URI.create("ws://127.0.0.1:" + port + "/relay"),
                URI.create("https://chonkbase.net/chonkcraft/join/"))) {
            server.start();
            MatchmakingClient client = new MatchmakingClient(service);
            var hostSeat = client.create(new CreateGameRequest("Reconnect", "Garden.pud", "sha",
                    2, Visibility.PUBLIC, "build"));
            var clientSeat = client.join(hostSeat.game().code(),
                    new JoinGameRequest("Alex", "build"));
            assertEquals(1, client.games("build").games().size());
            try (RelayDatagramSocket host = socket(hostSeat);
                    RelayDatagramSocket first = socket(clientSeat);
                    RelayDatagramSocket replacement = socket(clientSeat)) {
                first.close();
                host.setSoTimeout(2_000);
                replacement.setSoTimeout(2_000);
                byte[] bytes = {9, 8, 7, 6};
                host.send(new DatagramPacket(bytes, bytes.length,
                        RelayDatagramSocket.addressOf(clientSeat.endpointId())));
                byte[] received = new byte[32];
                DatagramPacket packet = new DatagramPacket(received, received.length);
                replacement.receive(packet);
                assertArrayEquals(bytes, Arrays.copyOf(received, packet.getLength()));
                assertEquals(RelayDatagramSocket.addressOf(hostSeat.endpointId()),
                        packet.getSocketAddress());
                host.closeRoom();
                await(Duration.ofSeconds(2), () -> client.games("build").games().isEmpty());
            }
        }
    }

    private static RelayDatagramSocket socket(
            net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.Seat seat) throws Exception {
        return new RelayDatagramSocket(URI.create(seat.relayUri()), seat.relayTicket(),
                seat.endpointId());
    }

    private static int freePort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void await(Duration timeout, CheckedBoolean condition) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(5);
        }
        assertTrue(condition.get(), "condition did not become true within " + timeout);
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean get() throws Exception;
    }
}
