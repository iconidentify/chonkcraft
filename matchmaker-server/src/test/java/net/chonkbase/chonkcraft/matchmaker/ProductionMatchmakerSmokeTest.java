package net.chonkbase.chonkcraft.matchmaker;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import net.chonkbase.chonkcraft.engine.network.GameLobby;
import net.chonkbase.chonkcraft.engine.network.NetworkSession;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingClient;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.CreateGameRequest;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.JoinGameRequest;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.Visibility;
import net.chonkbase.chonkcraft.matchmaking.RelayDatagramSocket;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Opt-in proof against the public service players actually use.
 *
 * <p>The ordinary end-to-end test owns an in-process server. This one is run
 * after the production Kubernetes rollout and closes its private room in a
 * {@code finally} block. It proves HTTPS discovery, two authenticated WSS
 * seats, wrong-map replacement, final lobby agreement, and game datagrams in
 * both directions through the public ingress.</p>
 */
class ProductionMatchmakerSmokeTest {

    @Test
    @DisplayName("the public service transfers the host map and carries game packets both ways")
    void publicServiceTransfersMapAndCarriesGamePackets() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(
                        "chonkcraft.production.matchmaker.smoke"),
                "Production matchmaking smoke is opt-in");

        URI service = URI.create(System.getProperty(
                "chonkcraft.production.matchmaker.url",
                "https://match.chonkbase.net"));
        String build = MatchmakingProtocol.gameBuild();
        MatchmakingClient client = new MatchmakingClient(service);
        byte[] expected = new byte[125_003];
        for (int index = 0; index < expected.length; index++) {
            expected[index] = (byte) (index * 31 + 7);
        }
        byte[] wrong = expected.clone();
        wrong[61_001] ^= 0x55;

        MatchmakingProtocol.Seat hostSeat = null;
        RelayDatagramSocket hostSocket = null;
        try {
            hostSeat = client.create(new CreateGameRequest(
                    "Deployment smoke", "map-sync-proof.pud", "sha256-proof",
                    2, Visibility.PRIVATE, build));
            String roomCode = hostSeat.game().code();
            var joinerSeat = client.join(roomCode,
                    new JoinGameRequest("Deployment joiner", build));
            assertEquals(roomCode, joinerSeat.game().code());
            assertTrue(client.games(build).games().stream().noneMatch(
                            game -> game.code().equals(roomCode)),
                    "the deployment proof room must never enter public browsing");

            hostSocket = socket(hostSeat);
            try (RelayDatagramSocket joinerSocket = socket(joinerSeat);
                    GameLobby host = GameLobby.hostRelayed(
                            "Deployment host", "map-sync-proof.pud", expected,
                            2, hostSocket);
                    GameLobby joiner = GameLobby.joinRelayed(
                            "Deployment joiner", joinerSocket,
                            joinerSeat.hostEndpointId(), ignored -> wrong)) {
                await(Duration.ofSeconds(15), () -> {
                    host.poll();
                    joiner.poll();
                    return joiner.state().localSlot() >= 0
                            && joiner.state().mapReady()
                            && host.state().allPlayersReady();
                });
                assertArrayEquals(expected, joiner.mapBytes(),
                        "the public relay did not replace the joiner's wrong map bytes");
                assertTrue(joiner.mapWasTransferred(),
                        "the joiner reported ready without receiving the host map");
                assertEquals(100, joiner.state().mapPercent());

                hostSocket.markRoomStarted();
                host.start();
                await(Duration.ofSeconds(5), () -> {
                    joiner.poll();
                    return joiner.isStarted();
                });

                try (NetworkSession hostGame = new NetworkSession(
                                host.state().localSlot(), host.releaseSocket());
                        NetworkSession joinerGame = new NetworkSession(
                                joiner.state().localSlot(), joiner.releaseSocket())) {
                    host.peers().forEach(hostGame::addPeer);
                    joiner.peers().forEach(joinerGame::addPeer);
                    joinerGame.setTrustedRelay(joiner.relayAddress());

                    hostGame.broadcast(7, 6, 0x1234L, List.of());
                    NetworkSession.Batch atJoiner = receive(
                            Duration.ofSeconds(5), joinerGame);
                    assertNotNull(atJoiner, "host-to-joiner game packet was lost");
                    assertEquals(7, atJoiner.netCycle());
                    assertEquals(host.state().localSlot(), atJoiner.player());

                    joinerGame.broadcast(8, 6, 0x5678L, List.of());
                    NetworkSession.Batch atHost = receive(
                            Duration.ofSeconds(5), hostGame);
                    assertNotNull(atHost, "joiner-to-host game packet was lost");
                    assertEquals(8, atHost.netCycle());
                    assertEquals(joiner.state().localSlot(), atHost.player());
                }
            }
        } finally {
            if (hostSocket != null) {
                hostSocket.closeRoom();
                hostSocket.close();
            }
            if (hostSeat != null && hostSeat.hostToken() != null) {
                try {
                    client.close(hostSeat.game().code(), hostSeat.hostToken());
                } catch (Exception alreadyClosed) {
                    // The relay lifecycle message normally removes it first.
                }
            }
        }
    }

    private static RelayDatagramSocket socket(MatchmakingProtocol.Seat seat)
            throws Exception {
        return new RelayDatagramSocket(URI.create(seat.relayUri()),
                seat.relayTicket(), seat.endpointId());
    }

    private static NetworkSession.Batch receive(Duration timeout,
            NetworkSession session) throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            List<NetworkSession.Batch> batches = session.poll();
            if (!batches.isEmpty()) {
                return batches.getFirst();
            }
            Thread.sleep(5);
        }
        List<NetworkSession.Batch> batches = session.poll();
        return batches.isEmpty() ? null : batches.getFirst();
    }

    private static void await(Duration timeout, CheckedBoolean condition)
            throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.get()) {
                return;
            }
            Thread.sleep(5);
        }
        assertTrue(condition.get(),
                "condition did not become true within " + timeout);
    }

    @FunctionalInterface
    private interface CheckedBoolean {
        boolean get() throws Exception;
    }
}
