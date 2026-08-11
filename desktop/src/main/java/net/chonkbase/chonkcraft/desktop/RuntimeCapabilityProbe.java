package net.chonkbase.chonkcraft.desktop;

import java.net.URI;
import java.net.http.WebSocket;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingClient;

/** Package-time proof that the trimmed launcher runtime can load online play. */
public final class RuntimeCapabilityProbe {

    private RuntimeCapabilityProbe() {
    }

    public static void main(String[] args) throws Exception {
        URI loopback = URI.create("http://127.0.0.1");
        MatchmakingClient client = new MatchmakingClient(loopback);
        if (!loopback.equals(client.service())) {
            throw new IllegalStateException("matchmaking client changed its service URI");
        }
        // RelayDatagramSocket resolves this lazily after a room is allocated.
        // Naming it here makes the release gate prove that WebSocket is present.
        if (WebSocket.class.getModule() == null) {
            throw new IllegalStateException("java.net.http WebSocket is unavailable");
        }
        if (args.length == 1) {
            new MatchmakingClient(URI.create(args[0])).games("runtime-capability-probe");
        }
        System.out.println("ChonkCraft packaged runtime capabilities: OK");
    }
}
