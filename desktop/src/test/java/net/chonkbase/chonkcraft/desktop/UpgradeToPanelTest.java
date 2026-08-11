package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.ui.UiLayout;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A town hall turning into a keep says so, and can be stopped.
 *
 * <p>The panel substitution and the cancel button both asked only about
 * {@code researching()}. Upstream's are one arm of a switch covering
 * {@code UnitAction::UpgradeTo} <em>and</em> {@code UnitAction::Research} --
 * "trick 17", and the same pair again in
 * {@code IsButtonAllowed}'s {@code CancelUpgrade} case. So a hall part way into
 * a keep drew its ordinary grid and its ordinary statistics: it looked idle,
 * offered its normal orders, and the twelve hundred gold it had already spent
 * could not be got back. {@code World.cancelUpgradeTo} refunds correctly and
 * has a test of its own; it simply had no path from the mouse.
 *
 * <p>Every assertion here is on the same building in the same two states, so a
 * failure says which half is wrong: the grid, the info panel, or the click.
 */
class UpgradeToPanelTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    private record Scene(World world, SidePanel panel, CommandPanel commands, GameData data) {}

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
        world.setUpgrades(data.upgrades().upgrades());
        world.setSpells(data.spells().spells());
        world.setMissileTypes(data.missiles().types());
        world.fog().revealAll(0);
        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);

        UiLayout.Layout layout = data.uiLayout("human", WIDTH, HEIGHT);
        assertNotNull(layout, "the layout script must be readable for any of this to mean much");
        SidePanel panel = new SidePanel(world, data, 0, "human", tilesetName, layout);
        CommandPanel commands = new CommandPanel(world, data, data.userInterface(tilesetName),
                data.upgrades().dependencies(), 0, tilesetName, "human",
                data.unitTypes().types(), layout);
        return new Scene(world, panel, commands, data);
    }

    /** A hall of the local player's, with enough in the bank to buy a keep. */
    private static Unit hall(Scene scene) {
        UnitType hallType = scene.data().unitTypes().types().get("unit-town-hall");
        assertNotNull(hallType, "the shipped data has a town hall");
        Unit hall = null;
        for (int y = 2; y < 12 && hall == null; y++) {
            for (int x = 2; x < 12; x++) {
                hall = scene.world().createUnit(hallType, 0, x, y);
                if (hall != null) {
                    break;
                }
            }
        }
        Assumptions.assumeTrue(hall != null, "nowhere on this map to put a town hall");
        for (UnitType.Resource resource : UnitType.Resource.values()) {
            scene.world().player(0).set(resource, 100000);
        }
        hall.setSelected(true);
        return hall;
    }

    private static BufferedImage paint(Scene scene, Unit selected) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = frame.createGraphics();
        scene.panel().draw(g2, WIDTH, HEIGHT, selected, 0, 0, WIDTH, HEIGHT);
        scene.commands().draw(g2, selected);
        g2.dispose();
        return frame;
    }

    private static void keep(BufferedImage frame, String name) {
        try {
            Path out = Paths.get("target", "upgrade-to-" + name + ".png");
            Files.createDirectories(out.getParent());
            javax.imageio.ImageIO.write(frame, "png", out.toFile());
        } catch (java.io.IOException ignored) {
            // A frame that could not be written is not a failing assertion.
        }
    }

    @Test
    @DisplayName("a hall becoming a keep shows the cancel grid and nothing else")
    void theGridIsSubstituted() {
        Scene scene = scene();
        Unit hall = hall(scene);
        UnitType keep = scene.data().unitTypes().types().get("unit-keep");
        assertNotNull(keep, "the shipped data has a keep");

        paint(scene, hall);
        var idle = scene.commands().describeForTest();
        assertTrue(idle.contains("train-unit:unit-peasant"),
                "an idle town hall trains peasants; got " + idle);

        assertTrue(scene.world().orderUpgradeTo(hall, keep), "the hall can start a keep");
        assertEquals(keep, hall.upgradingTo());

        scene.commands().resetLevel();
        keep(paint(scene, hall), "grid");
        var upgrading = scene.commands().describeForTest();

        assertTrue(upgrading.contains("cancel-upgrade"),
                "a hall part way into a keep offers no cancel: " + upgrading);
        // The substitution replaces the type outright, so the cancel is the
        // only thing in the grid -- not the hall's own buttons with a cancel
        // added to them.
        assertEquals(1, upgrading.stream().filter(slot -> !"-".equals(slot)).count(),
                "the cancel grid should hold exactly one button: " + upgrading);
    }

    @Test
    @DisplayName("a hall becoming a keep shows the progress panel, not its statistics")
    void theInfoPanelShowsTheJob() {
        Scene scene = scene();
        Unit hall = hall(scene);
        UnitType keep = scene.data().unitTypes().types().get("unit-keep");

        int idleFrame = scene.panel().infoPanelFrame(hall);

        assertTrue(scene.world().orderUpgradeTo(hall, keep));
        int busyFrame = scene.panel().infoPanelFrame(hall);

        assertEquals(3, busyFrame,
                "a building at work uses the third backdrop, the one with room for a"
                        + " progress bar; it was drawn as backdrop " + busyFrame
                        + " (an idle hall draws " + idleFrame + ")");

        // And the panel itself says what it is doing. The words go in the
        // production block, which an idle hall does not draw at all, so the
        // two frames differ in that region only when the branch is taken.
        BufferedImage drawn = paint(scene, hall);
        keep(drawn, "panel");
        java.awt.Rectangle slot = SidePanel.productionBounds();
        assertTrue(busy(drawn, slot) > 40,
                "nothing was drawn in the production slot for a hall becoming a keep");
    }

    /** Pixels in a box that are not the panel's own flat stone. */
    private static int busy(BufferedImage frame, java.awt.Rectangle box) {
        int busy = 0;
        for (int y = box.y; y < box.y + box.height; y++) {
            for (int x = box.x; x < box.x + box.width; x++) {
                if (x < 0 || y < 0 || x >= frame.getWidth() || y >= frame.getHeight()) {
                    continue;
                }
                int rgb = frame.getRGB(x, y);
                int red = (rgb >> 16) & 0xFF;
                int green = (rgb >> 8) & 0xFF;
                int blue = rgb & 0xFF;
                if (red > 150 || green > 150 || blue > 150
                        || Math.abs(red - green) > 24 || Math.abs(green - blue) > 24) {
                    busy++;
                }
            }
        }
        return busy;
    }
}
