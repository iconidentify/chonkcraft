package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.event.KeyEvent;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.DatagramSocket;
import java.util.concurrent.atomic.AtomicReference;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.network.GameLobby;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** The visible direct-host and direct-join controls drive the real UDP lobby. */
class DirectConnectionEndToEndTest {

    @Test
    @DisplayName("Two players use the direct screens to transfer a map and start")
    void twoPlayersConnectWithoutTheRoomService() throws Exception {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "no asset source");
        GameData data = new GameData(assets);
        byte[] map = new byte[148_000];
        for (int i = 0; i < map.length; i++) {
            map[i] = (byte) (i * 19 + 7);
        }
        int port = freeUserPort();
        AtomicReference<GameLobby> hosted = new AtomicReference<>();
        DirectHostScreen hostScreen = new DirectHostScreen(data, "direct-test.pud",
                new DirectHostScreen.Listener() {
                    @Override
                    public void onHost(int chosen) {
                        try {
                            hosted.set(GameLobby.host("Host", "direct-test.pud", map, 4, chosen));
                        } catch (IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                    }

                    @Override
                    public void onCancel() {
                    }
                });
        typePort(hostScreen, port);
        hostScreen.type(KeyEvent.VK_ENTER, '\n');
        assertEquals(port, hosted.get().connectionPort(),
                "the setup screen must bind the same UDP port it displayed");

        AtomicReference<GameLobby> joined = new AtomicReference<>();
        JoinScreen joinScreen = new JoinScreen(data, null, null, true,
                new JoinScreen.Listener() {
                    @Override
                    public void onJoin(String host, int chosen) {
                        try {
                            joined.set(GameLobby.join("Joiner",
                                    java.net.InetAddress.getByName(host), chosen,
                                    ignored -> null));
                        } catch (IOException failure) {
                            throw new UncheckedIOException(failure);
                        }
                    }

                    @Override
                    public void onCancel() {
                    }
                });
        assertTrue(joinScreen.isDirectOnlyForTest(),
                "the direct menu path must not construct or contact the room service");
        for (char character : ("127.0.0.1:" + port).toCharArray()) {
            joinScreen.type(0, character);
        }
        joinScreen.type(KeyEvent.VK_ENTER, '\n');

        try (GameLobby host = hosted.get(); GameLobby client = joined.get()) {
            pollUntil("the joiner received the exact host map",
                    () -> client.state().mapReady() && host.state().allPlayersReady(),
                    host, client);
            assertArrayEquals(map, client.mapBytes(),
                    "direct mode must transfer the host's authenticated map bytes");
            host.start();
            pollUntil("the joiner heard the start", client::isStarted, host, client);
            assertTrue(client.isStarted(), "the direct lobby stopped before the game began");
        }
    }

    private static int freeUserPort() throws IOException {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private static void typePort(DirectHostScreen screen, int port) {
        for (char digit : Integer.toString(port).toCharArray()) {
            screen.type(0, digit);
        }
    }

    private static void pollUntil(String reason, java.util.function.BooleanSupplier finished,
            GameLobby... lobbies) throws Exception {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            for (GameLobby lobby : lobbies) {
                lobby.poll();
            }
            if (finished.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("timed out waiting for " + reason);
    }
}
