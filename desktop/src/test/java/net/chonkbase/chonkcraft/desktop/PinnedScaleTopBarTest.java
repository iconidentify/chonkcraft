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
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A scale the player pinned gets the same forgiving top bar as one the game
 * chose.
 *
 * <p>This is the regression half of a decision. The top bar was logged as
 * crowding at a pinned interface scale of three in a narrow window, and it was
 * logged as a decision rather than a defect because upstream's
 * {@code DrawResources} does not reflow either: it draws at the declared
 * positions and lets them collide. The decision taken here is to keep this
 * port's more forgiving behaviour -- when the counts will not fit, the least
 * important is dropped, score first and gold never -- and to apply it at
 * pinned scales as well.
 *
 * <p>That turned out to need no new code. {@link SidePanel#layOutTopBar} works
 * from the interface's width in design pixels, and a pinned scale reaches it
 * by the same route a chosen one does: {@code setInterfaceScale} rebuilds the
 * layout and {@code paintComponent} passes {@code toDesign(getWidth())}. What
 * was missing was anything saying so. {@code SidePanelVisualTest} exercises the
 * layout function directly, with a width it computes itself, so it would go on
 * passing if the pinned path stopped reaching that function at all.
 *
 * <p>So this one pins the scale on a real {@link GameScreen}, paints it, and
 * asks the panel where the counts actually went.
 */
class PinnedScaleTopBarTest {

    private static final String MAP = "campaigns/human/level02h";

    /** Narrow enough that six counts cannot possibly fit at scale three. */
    private static final int WINDOW_WIDTH = 1024;
    private static final int WINDOW_HEIGHT = 768;

    private record Scene(GameScreen screen, SidePanel panel, World world,
            java.util.Map<String, UnitType> types) {}

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private static Scene scene(double pinned) {
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

        // Six-figure counts, so the bar is asked to hold the widest figures the
        // game can produce rather than the four zeroes a fresh map starts on.
        for (UnitType.Resource resource : UnitType.Resource.values()) {
            world.player(0).set(resource, 999999);
        }
        world.player(0).setScore(999999);

        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        var layout = data.uiLayout("human", WINDOW_WIDTH, WINDOW_HEIGHT);
        assertNotNull(layout);
        SidePanel panel = new SidePanel(world, data, 0, "human", tilesetName, layout);
        GameScreen screen = new GameScreen(world, data,
                new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), tileset.palette(),
                tilesetName, 0, WINDOW_WIDTH, WINDOW_HEIGHT,
                new net.chonkbase.chonkcraft.engine.sound.GameAudio(data.sounds()),
                panel, null, applier, CommandSink.local(applier),
                java.util.List.of(), "human");
        screen.setSize(WINDOW_WIDTH, WINDOW_HEIGHT);
        // The player's own choice, which is what "pinned" means: from here the
        // window no longer overrules it.
        screen.setInterfaceScale(pinned);
        return new Scene(screen, panel, world, data.unitTypes().types());
    }

    private static BufferedImage paint(GameScreen screen) {
        BufferedImage frame =
                new BufferedImage(WINDOW_WIDTH, WINDOW_HEIGHT, BufferedImage.TYPE_INT_RGB);
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
    @DisplayName("counts never overlap at any pinned interface scale")
    void thePinnedBarGivesWayRatherThanCrowding() {
        for (int pinned = 1; pinned <= 4; pinned++) {
            Scene scene = scene(pinned);
            save(paint(scene.screen()), "pinned" + pinned + "-topbar.png");

            var cells = new ArrayList<>(scene.panel().topBarForTest());
            cells.sort(java.util.Comparator.comparingInt(SidePanel.TopBarCell::iconX));

            int design = (int) Math.floor(WINDOW_WIDTH / (double) pinned);
            SidePanel.TopBarCell previous = null;
            for (var cell : cells) {
                assertTrue(cell.iconX() >= SidePanel.WIDTH,
                        "at a pinned scale of " + pinned + " slot " + cell.slot()
                                + " starts at " + cell.iconX() + ", on the sidebar");
                assertTrue(cell.iconX() + cell.width() <= design - 16,
                        "at a pinned scale of " + pinned + " slot " + cell.slot()
                                + " ends at " + (cell.iconX() + cell.width())
                                + ", off a bar " + design + " design pixels wide");
                if (previous != null) {
                    assertTrue(cell.iconX() >= previous.iconX() + previous.width(),
                            "at a pinned scale of " + pinned + " slot " + cell.slot()
                                    + " starts at " + cell.iconX() + ", inside slot "
                                    + previous.slot() + " which ends at "
                                    + (previous.iconX() + previous.width()));
                }
                previous = cell;
            }

            // Gold never gives way, and the bar is never empty: dropping
            // everything would satisfy "nothing overlaps" and tell the player
            // nothing at all.
            assertTrue(cells.stream().anyMatch(cell -> cell.slot() == 0),
                    "the gold count did not survive a pinned scale of " + pinned);
        }
    }
}
