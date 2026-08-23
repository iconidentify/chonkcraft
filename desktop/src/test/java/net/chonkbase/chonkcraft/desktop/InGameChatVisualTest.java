package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.net.DatagramSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.swing.JPanel;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.LockstepScheduler;
import net.chonkbase.chonkcraft.engine.network.NetworkGame;
import net.chonkbase.chonkcraft.engine.network.NetworkSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Visual and interaction proof for the in-game message surface. */
class InGameChatVisualTest {

    @Test
    @DisplayName("the BNE message line and connected roster render together")
    void rosterAndMessageLineRender() throws Exception {
        try (Scene scene = scene()) {
            InGameChat chat = scene.chat();
            chat.accept(new NetworkGame.ChatEvent(1, "Alex", 1, "Trolls at the north bridge.",
                    false));
            chat.accept(new NetworkGame.ChatEvent(0, "Chris", 2, "I will bring the destroyers.",
                    true));

            BufferedImage first = render(chat);
            chat.click(610, 28); // Messages
            chat.keyPressed(key(KeyEvent.VK_ENTER, '\n'));
            for (char character : "Ready when you are".toCharArray()) {
                chat.keyPressed(key(KeyEvent.VK_UNDEFINED, character));
            }
            BufferedImage complete = render(chat);

            Path output = Path.of("target/visual-chat/in-game-chat-1280x960.png");
            Files.createDirectories(output.getParent());
            assertTrue(ImageIO.write(complete, "png", output.toFile()));
            assertTrue(differentPixels(first, complete) > 20_000,
                    "opening the roster and message line made no visible surface");

            chat.keyPressed(key(KeyEvent.VK_ENTER, '\n'));
            assertEquals("Ready when you are",
                    scene.game().drainChatEvents().getFirst().text(),
                    "Enter did not hand the completed line to the network session");
        }
    }

    @Test
    @DisplayName("muting a connected player suppresses only their later chat")
    void muteIsLocalAndImmediate() throws Exception {
        try (Scene scene = scene()) {
            InGameChat chat = scene.chat();
            render(chat);
            chat.click(610, 28);
            render(chat);
            chat.click(580, 149); // Alex's MUTE control in the second roster row.
            assertTrue(chat.isMuted(1));
            int before = chat.lineCount();
            chat.accept(new NetworkGame.ChatEvent(1, "Alex", 1, "not shown", false));
            assertEquals(before, chat.lineCount());
            chat.accept(new NetworkGame.ChatEvent(2, "Morgan", 1, "still shown", false));
            assertEquals(before + 1, chat.lineCount());
        }
    }

    @Test
    @DisplayName("the battlefield names the ally whose sight is shared")
    void theRunningAllianceIsConfirmedOnEntry() throws Exception {
        try (Scene scene = scene()) {
            assertEquals("Allied with Morgan. Shared sight is on.",
                    GameScreen.alliedOpeningStatus(scene.game()),
                    "the map opened without identifying the alliance actually in force");
        }
    }

    private static Scene scene() throws Exception {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i, i < 3 ? PudMap.PlayerType.PERSON
                    : PudMap.PlayerType.NOBODY, PudMap.Race.HUMAN);
        }
        World world = new World(new GameMap(32, 24, new Tileset()), players);
        world.setAllied(0, 2, true);
        world.setAllied(2, 0, true);
        NetworkSession session = new NetworkSession(0, new DatagramSocket(0));
        NetworkGame game = new NetworkGame(world, session, new LockstepScheduler(3),
                new CommandApplier(world, List.of()), 0);
        game.setHostPlayer(0);
        game.setPlayerNames(Map.of(0, "Chris", 1, "Alex", 2, "Morgan"));
        InGameChat chat = new InGameChat(game, GameFont.load(null, GameFont.Face.GAME), null);
        return new Scene(session, game, chat);
    }

    private static BufferedImage render(InGameChat chat) {
        BufferedImage image = new BufferedImage(1280, 960, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        g2.setColor(new java.awt.Color(0x101820));
        g2.fillRect(0, 0, image.getWidth(), image.getHeight());
        g2.scale(2, 2);
        chat.draw(g2, 176, 16, 448, 448);
        g2.dispose();
        return image;
    }

    private static KeyEvent key(int code, char character) {
        return new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 1L, 0, code, character);
    }

    private static int differentPixels(BufferedImage first, BufferedImage second) {
        int count = 0;
        for (int y = 0; y < first.getHeight(); y++) {
            for (int x = 0; x < first.getWidth(); x++) {
                if (first.getRGB(x, y) != second.getRGB(x, y)) {
                    count++;
                }
            }
        }
        return count;
    }

    private record Scene(NetworkSession session, NetworkGame game, InGameChat chat)
            implements AutoCloseable {
        @Override
        public void close() {
            session.close();
        }
    }
}
