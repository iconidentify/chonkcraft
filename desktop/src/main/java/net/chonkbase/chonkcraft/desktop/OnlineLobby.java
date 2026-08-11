package net.chonkbase.chonkcraft.desktop;

import java.util.concurrent.atomic.AtomicBoolean;
import net.chonkbase.chonkcraft.engine.network.GameLobby;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingClient;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.HostStateRequest;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.Phase;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.Seat;
import net.chonkbase.chonkcraft.matchmaking.RelayDatagramSocket;

/** Host credentials and heartbeat kept out of the deterministic lobby state. */
final class OnlineLobby {

    private static final long HEARTBEAT_MILLIS = 3_000;

    private final MatchmakingClient client;
    private final Seat seat;
    private final RelayDatagramSocket relay;
    private final AtomicBoolean requestInFlight = new AtomicBoolean();
    private volatile long lastHeartbeat;
    private volatile String serviceProblem = "";

    OnlineLobby(MatchmakingClient client, Seat seat) {
        this(client, seat, null);
    }

    OnlineLobby(MatchmakingClient client, Seat seat, RelayDatagramSocket relay) {
        this.client = client;
        this.seat = seat;
        this.relay = relay;
    }

    String code() {
        return seat.game().code();
    }

    String inviteUrl() {
        return seat.inviteUrl();
    }

    boolean privateGame() {
        return seat.visibility()
                == net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.Visibility.PRIVATE;
    }

    String serviceProblem() {
        return serviceProblem;
    }

    void tick(GameLobby lobby) {
        if (seat.hostToken() == null || System.currentTimeMillis() - lastHeartbeat < HEARTBEAT_MILLIS
                || !requestInFlight.compareAndSet(false, true)) {
            return;
        }
        lastHeartbeat = System.currentTimeMillis();
        Thread.startVirtualThread(() -> {
            try {
                client.update(code(), seat.hostToken(), new HostStateRequest(lobby.humanCount(),
                        lobby.capacity(), lobby.isStarted() ? Phase.PLAYING : Phase.WAITING));
                serviceProblem = "";
            } catch (Exception failure) {
                serviceProblem = "Invite service reconnecting; the lobby remains active.";
            } finally {
                requestInFlight.set(false);
            }
        });
    }

    void starting(GameLobby lobby) {
        updateOnce(lobby, Phase.STARTING);
    }

    void close() {
        if (seat.hostToken() == null) {
            return;
        }
        if (relay != null) {
            relay.closeRoom();
        }
        Thread.startVirtualThread(() -> {
            try {
                client.close(code(), seat.hostToken());
            } catch (Exception ignored) {
                // Relay silence expires the room if the explicit close cannot reach the service.
            }
        });
    }

    private void updateOnce(GameLobby lobby, Phase phase) {
        if (seat.hostToken() == null) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                client.update(code(), seat.hostToken(), new HostStateRequest(lobby.humanCount(),
                        lobby.capacity(), phase));
            } catch (Exception ignored) {
                // The authenticated relay traffic itself keeps an active room alive.
            }
        });
    }
}
