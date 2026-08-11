package net.chonkbase.chonkcraft.matchmaker;

import java.net.URI;

/** Bootable HTTP room directory and WebSocket packet relay. */
public final class Main {

    private Main() {
    }

    public static void main(String[] args) throws Exception {
        int port = integerEnvironment("MATCHMAKER_PORT", 9092);
        URI relayUri = URI.create(environment("PUBLIC_RELAY_URI",
                "ws://127.0.0.1:" + port + "/relay"));
        URI inviteBase = withSlash(URI.create(environment("PUBLIC_INVITE_BASE",
                "https://chonkbase.net/chonkcraft/join/")));
        try (MatchmakerServer server = new MatchmakerServer(port, relayUri, inviteBase)) {
            server.start();
            System.out.println("ChonkCraft matchmaker listening on " + port);
            server.await();
        }
    }

    private static URI withSlash(URI uri) {
        String text = uri.toString();
        return text.endsWith("/") ? uri : URI.create(text + "/");
    }

    private static String environment(String key, String fallback) {
        String value = System.getenv(key);
        return value == null || value.isBlank() ? fallback : value.strip();
    }

    private static int integerEnvironment(String key, int fallback) {
        try {
            return Integer.parseInt(environment(key, Integer.toString(fallback)));
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException(key + " must be a TCP port", invalid);
        }
    }
}
