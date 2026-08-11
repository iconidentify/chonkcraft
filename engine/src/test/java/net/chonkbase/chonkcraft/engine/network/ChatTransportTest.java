package net.chonkbase.chonkcraft.engine.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Player conversation uses the game topology without entering lockstep. */
class ChatTransportTest {

    @Test
    @DisplayName("a client message crosses the host only to selected recipients")
    void selectedRecipientsReceiveTheMessage() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        try (NetworkSession host = new NetworkSession(0, 0);
                NetworkSession one = new NetworkSession(1, 0);
                NetworkSession two = new NetworkSession(2, 0);
                NetworkSession three = new NetworkSession(3, 0)) {
            one.addPeer(0, local, host.localPort());
            host.addPeer(1, local, one.localPort());
            host.addPeer(2, local, two.localPort());
            host.addPeer(3, local, three.localPort());
            two.setTrustedRelay(new java.net.InetSocketAddress(local, host.localPort()));
            three.setTrustedRelay(new java.net.InetSocketAddress(local, host.localPort()));

            one.broadcastChat(1, 1 << 2, "Meet at the oil patch");
            NetworkSession.ChatPacket accepted = awaitChats(host).getFirst();
            assertEquals(1, accepted.player());
            assertEquals("Meet at the oil patch", accepted.text());
            host.relay(accepted);

            assertEquals("Meet at the oil patch", awaitChats(two).getFirst().text());
            Thread.sleep(25);
            three.poll();
            assertTrue(three.drainChats().isEmpty(), "an unselected opponent received the text");
        }
    }

    @Test
    @DisplayName("chat text is bounded, normalised, and remains valid UTF-8")
    void textIsSafeForTheWireAndTheMessageLine() {
        String noisy = "  hello\n\tthere\u0000  " + "⚔".repeat(300);
        String safe = NetworkSession.sanitizeChat(noisy);
        assertTrue(safe.startsWith("hello there ⚔"));
        assertFalse(safe.contains("\n"));
        assertTrue(safe.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= NetworkSession.MAX_CHAT_UTF8_BYTES);
        assertEquals(safe, NetworkSession.sanitizeChat(safe));
    }

    private static List<NetworkSession.ChatPacket> awaitChats(NetworkSession session)
            throws Exception {
        List<NetworkSession.ChatPacket> chats = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 2_000L;
        while (chats.isEmpty() && System.currentTimeMillis() < deadline) {
            session.poll();
            chats.addAll(session.drainChats());
            if (chats.isEmpty()) {
                Thread.sleep(5);
            }
        }
        assertFalse(chats.isEmpty(), "the message never arrived");
        return chats;
    }
}
