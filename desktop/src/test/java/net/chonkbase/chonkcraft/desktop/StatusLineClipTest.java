package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.ui.UiLayout;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A long status message stops where its strip of art does.
 *
 * <p>{@code UI.StatusLine.Width} is declared by every interface script and was
 * parsed and never read. Upstream's {@code CStatusLine::Draw} pushes a clip of
 * {@code TextX} to {@code TextX + Width - 1} round the one line and draws
 * inside it, so a message longer than the strip loses its tail. Nothing here
 * clipped, so a long refusal ran across whatever came after the status line
 * and, in a small window, off the edge of the screen.
 *
 * <p>The measurement is a difference of two frames -- one with the line empty,
 * one with it long -- so every pixel it looks at is lettering rather than
 * panel art. The test asserts both halves: that the text really is longer than
 * the declared width, which is what makes the clip do anything at all, and
 * that nothing is drawn past it.
 */
class StatusLineClipTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    /** Long enough to overrun any declared width at this size. */
    private static final String LONG_MESSAGE =
            "cannot build there because the ground is occupied by something else entirely";

    private record Scene(GameScreen screen, UiLayout.Layout layout, GameFont font) {}

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private static Scene scene() {
        GameData data = data();
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.fog().revealAll(0);
        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);

        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        UiLayout.Layout layout = data.uiLayout("human", WIDTH, HEIGHT);
        assertNotNull(layout, "the layout script must be readable for any of this to mean much");

        // The status line is part of the chrome, so the screen needs a side
        // panel before it draws one at all.
        SidePanel panel = new SidePanel(world, data, 0, "human", tilesetName, layout);
        GameScreen screen = new GameScreen(world, data,
                new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), tileset.palette(),
                tilesetName, 0, WIDTH, HEIGHT,
                new net.chonkbase.chonkcraft.engine.sound.GameAudio(data.sounds()),
                panel, null, applier, CommandSink.local(applier),
                java.util.List.of(), "human");
        screen.setSize(WIDTH, HEIGHT);
        screen.setLayout(layout);
        screen.setGameScale(1);
        return new Scene(screen, layout, GameFont.load(data, GameFont.Face.GAME));
    }

    private static BufferedImage paint(GameScreen screen) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var g = frame.createGraphics();
        screen.paint(g);
        g.dispose();
        return frame;
    }

    private static void save(BufferedImage frame, String name) {
        try {
            Path out = Paths.get("target");
            Files.createDirectories(out);
            javax.imageio.ImageIO.write(frame, "png", out.resolve(name).toFile());
        } catch (java.io.IOException ignored) {
            // A frame that could not be written is not a failing assertion.
        }
    }

    @Test
    @DisplayName("a status message longer than the declared width is cut off at it")
    void theStatusLineIsClippedToItsDeclaredWidth() {
        Scene scene = scene();
        int width = scene.layout().statusLineWidth();
        Assumptions.assumeTrue(width > 0, "this layout declares no status line width");
        int left = scene.layout().statusLineX();

        // The premise: the message really is too long for its strip. Without
        // this the clip could be doing nothing and the test would still pass.
        assertTrue(scene.font().widthOf(LONG_MESSAGE) > width,
                "the test message is only " + scene.font().widthOf(LONG_MESSAGE)
                        + " wide against a declared " + width + ", so it cannot overrun");

        scene.screen().setStatus("");
        BufferedImage blank = paint(scene.screen());
        scene.screen().setStatus(LONG_MESSAGE);
        BufferedImage written = paint(scene.screen());
        save(written, "status-line-clipped.png");

        int rightmost = -1;
        int drawn = 0;
        for (int y = 0; y < HEIGHT; y++) {
            for (int x = 0; x < WIDTH; x++) {
                if (blank.getRGB(x, y) != written.getRGB(x, y)) {
                    drawn++;
                    rightmost = Math.max(rightmost, x);
                }
            }
        }

        assertTrue(drawn > 50, "the status line was not drawn at all: " + drawn + " pixels");
        assertTrue(rightmost < left + width,
                "the status line reaches column " + rightmost + ", past the end of its declared "
                        + width + "-pixel strip at " + (left + width)
                        + ": UI.StatusLine.Width is not being applied");
    }
}
