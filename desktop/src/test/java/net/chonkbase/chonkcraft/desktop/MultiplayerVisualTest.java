package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import javax.imageio.ImageIO;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.network.GameDiscovery;
import net.chonkbase.chonkcraft.engine.network.GameLobby;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingClient;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.GameListing;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.Phase;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.Seat;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Rendered review surfaces for online, direct, LAN, recovery, and invite flows. */
class MultiplayerVisualTest {

    @Test
    @DisplayName("Multiplayer screens remain deliberate at design, laptop, and widescreen sizes")
    void multiplayerScreensRenderAtShippingSizes() throws Exception {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "no asset source");
        GameData data = new GameData(assets);
        Path output = Path.of(System.getProperty("chonkcraft.visual.output",
                "desktop/target/visual-multiplayer"));
        Files.createDirectories(output);

        JoinScreen online = new JoinScreen(data, null, new Noop());
        online.setOnlineGames(List.of(
                listing("QD7K3M", "Chris's Game", "Garden of War", 2, 4),
                listing("9TX4JP", "Friday Night BNE", "High Seas Combat", 3, 6),
                listing("M3RK7A", "New Players Welcome", "Hillsbrad", 1, 4)));
        write(online.render(), output.resolve("online-browser-640x480.png"));

        JoinScreen local = new JoinScreen(data, null, new Noop());
        local.showLocalForTest();
        local.setFound(List.of(
                new GameDiscovery.Game("Living Room", "Garden of War", 2, 4,
                        "192.168.1.12", 7100, 1),
                new GameDiscovery.Game("Office", "High Seas Combat", 1, 6,
                        "192.168.1.18", 7100, 1)));
        write(paint(local, 1280, 800), output.resolve("local-network-1280x800.png"));

        JoinScreen mismatch = new JoinScreen(data, null, new Noop());
        mismatch.showLocalForTest();
        mismatch.setFound(List.of(new GameDiscovery.Game("Old Living Room",
                "Garden of War", 1, 4, "192.168.1.22", 7100, 1,
                "2026.0809.14")));
        mismatch.render();
        var mismatchRow = JoinScreen.rowBounds(0);
        mismatch.clickDesign(mismatchRow.x + 20, mismatchRow.y + mismatchRow.height / 2);
        write(paint(mismatch, 1280, 800), output.resolve("update-required-1280x800.png"));

        JoinScreen directJoin = new JoinScreen(data, null, null, true, new Noop());
        write(paint(directJoin, 1280, 800), output.resolve("direct-join-1280x800.png"));

        DirectHostScreen direct = new DirectHostScreen(data, "Garden of War",
                new DirectHostScreen.Listener() {
                    @Override
                    public void onHost(int port) {
                    }

                    @Override
                    public void onCancel() {
                    }
                });
        write(paint(direct, 1280, 800), output.resolve("direct-host-1280x800.png"));

        OnlineHostScreen unavailable = new OnlineHostScreen(data, "Garden of War",
                new OnlineHostScreen.Listener() {
                    @Override
                    public void onRetry() {
                    }

                    @Override
                    public void onLocal() {
                    }

                    @Override
                    public void onCancel() {
                    }
                });
        unavailable.failed("The online service could not be reached.");
        write(paint(unavailable, 1280, 800), output.resolve("online-recovery-1280x800.png"));

        String teamMapName = assets.hasMap("All You Need BNE.pud")
                ? "All You Need BNE.pud" : assets.mapNames().getFirst();
        byte[] teamMap = assets.map(teamMapName);
        int teamCapacity = Math.max(2, Math.min(8,
                net.chonkbase.chonkcraft.data.map.PudReader.read(teamMap).playableSlots()));
        GameLobby hosted = GameLobby.host(
                "Chris", teamMapName, teamMap, teamCapacity, 0);
        for (int slot = 1; slot < Math.min(4, teamCapacity); slot++) {
            hosted.setOccupant(slot, GameLobby.Occupant.COMPUTER);
        }
        hosted.setGameTemplate(GameLobby.GameTemplate.TOP_VS_BOTTOM);
        Seat seat = new Seat(listing(
                "QD7K3M", "Chris's Game", teamMapName, 1, teamCapacity),
                "host", "ws://127.0.0.1/relay", "ticket", 0, 0,
                "https://chonkbase.net/chonkcraft/join/QD7K3M",
                net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.Visibility.PUBLIC);
        LobbyScreen lobby = new LobbyScreen(data, hosted, teamMapName,
                new OnlineLobby(new MatchmakingClient(URI.create("http://127.0.0.1:1")), seat),
                new LobbyScreen.Listener() {
                    @Override
                    public void onStart(GameLobby ignored) {
                    }

                    @Override
                    public void onCancel() {
                    }
                });
        write(paint(lobby, 1920, 1080),
                output.resolve("invite-lobby-1920x1080.png"));
        hosted.close();

        try (var files = Files.list(output)) {
            assertEquals(7, files.filter(path -> path.toString().endsWith(".png")).count());
        }
        assertTrue(Files.size(output.resolve("online-browser-640x480.png")) > 10_000);
    }

    private static GameListing listing(String code, String name, String map, int players,
            int capacity) {
        return new GameListing(code, name, map, players, capacity, "visual", Phase.WAITING, 1);
    }

    private static BufferedImage paint(javax.swing.JComponent component, int width, int height) {
        component.setSize(width, height);
        component.doLayout();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        component.paint(graphics);
        graphics.dispose();
        return image;
    }

    private static void write(BufferedImage image, Path path) throws Exception {
        assertTrue(ImageIO.write(image, "png", path.toFile()));
    }

    private static final class Noop implements JoinScreen.Listener {
        @Override
        public void onJoin(String host, int port) {
        }

        @Override
        public void onCancel() {
        }
    }
}
