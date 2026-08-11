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
 * An orc mission's HUD is lettered in yellow.
 *
 * <p>{@code UI.NormalFontColor} is declared per race -- "white" in
 * {@code scripts/human/ui_pandora.legacy-declaration} and "yellow" in
 * {@code scripts/orc/ui_pandora.legacy-declaration} -- and handed to
 * {@code SetDefaultTextColors}, which is what upstream's text drawing falls
 * back to wherever a colour is not given explicitly. Nothing read it. Every
 * call site named {@code Ink.WHITE} outright, so the side panel picked up its
 * race's artwork and drew that artwork's lettering in the other race's colour.
 *
 * <p>Nothing looked broken -- white text is legible -- which is why it survived
 * until somebody played an orc mission beside the original.
 *
 * <p>This paints the same building on the same panel twice, once as a human
 * player and once as an orc, and counts pixels of each exact ink inside the
 * info panel. The two frames must disagree, and disagree in the direction the
 * scripts say.
 */
class HudInkTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    /** {@code GameFont.colourOf(Ink.WHITE)} and {@code (Ink.YELLOW)}. */
    private static final int WHITE = 0xFFF0F0E8;
    private static final int YELLOW = 0xFFFFDC50;

    private record Scene(World world, SidePanel panel, UiLayout.Layout layout,
            Unit hall) {}

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    private static Scene scene(GameData data, String race) {
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.fog().revealAll(0);
        String tilesetName = pud.tileset() == PudMap.Tileset.FOREST
                ? "summer"
                : pud.tileset().name().toLowerCase(java.util.Locale.ROOT);

        UiLayout.Layout layout = data.uiLayout(race, WIDTH, HEIGHT);
        assertNotNull(layout, "the " + race + " layout must be readable");
        SidePanel panel = new SidePanel(world, data, 0, race, tilesetName, layout);

        UnitType hallType = data.unitTypes().types().get("unit-town-hall");
        assertNotNull(hallType);
        Unit hall = null;
        for (int y = 2; y < 12 && hall == null; y++) {
            for (int x = 2; x < 12; x++) {
                hall = world.createUnit(hallType, 0, x, y);
                if (hall != null) {
                    break;
                }
            }
        }
        Assumptions.assumeTrue(hall != null, "nowhere on this map to put a town hall");
        // Hurt, so the panel prints a figure that is not its own maximum and
        // there is plenty of lettering to count.
        hall.setHitPoints(hallType.hitPoints() / 2);
        hall.setSelected(true);
        return new Scene(world, panel, layout, hall);
    }

    private static BufferedImage paint(Scene scene) {
        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = frame.createGraphics();
        scene.panel().draw(g2, WIDTH, HEIGHT, scene.hall(), 0, 0, WIDTH, HEIGHT);
        g2.dispose();
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

    /**
     * How near a pixel must be to an ink, per channel, to count as that ink.
     *
     * <p>Nought would be exact equality, and exact equality is a measurement
     * of the platform's glyph rasteriser rather than of the colour the implementation
     * chose. {@code GameFont} draws with {@code TEXT_ANTIALIAS_ON}, so a
     * glyph's edge pixels are blends between the ink and the stone behind it
     * and only fully covered pixels land on the ink exactly -- and how many
     * pixels a 13-point glyph covers fully is a question about hinting.
     * Measured: this test counted 16 exact-white pixels on Linux against the
     * more than fifty it wants, and passed on macOS, which is a false report
     * about the implementation either way.
     *
     * <p>Twenty-four cannot confuse the two inks this test tells apart. White
     * is {@code F0F0E8} and yellow {@code FFDC50}; their blue channels are 232
     * and 80, which is 152 apart, so no tolerance near this size can call one
     * the other.
     */
    private static final int INK_TOLERANCE = 24;

    /** How many pixels of one ink sit inside a box, edge blends excluded. */
    private static int count(BufferedImage frame, java.awt.Rectangle box, int rgb) {
        int wantRed = rgb >> 16 & 0xFF;
        int wantGreen = rgb >> 8 & 0xFF;
        int wantBlue = rgb & 0xFF;
        int found = 0;
        for (int y = Math.max(0, box.y); y < Math.min(frame.getHeight(), box.y + box.height); y++) {
            for (int x = Math.max(0, box.x);
                    x < Math.min(frame.getWidth(), box.x + box.width); x++) {
                int pixel = frame.getRGB(x, y);
                if (Math.abs((pixel >> 16 & 0xFF) - wantRed) <= INK_TOLERANCE
                        && Math.abs((pixel >> 8 & 0xFF) - wantGreen) <= INK_TOLERANCE
                        && Math.abs((pixel & 0xFF) - wantBlue) <= INK_TOLERANCE) {
                    found++;
                }
            }
        }
        return found;
    }

    @Test
    @DisplayName("the info panel is lettered white for a human and yellow for an orc")
    void theDefaultInkFollowsTheRace() {
        GameData data = data();

        Scene human = scene(data, "human");
        BufferedImage humanFrame = paint(human);
        save(humanFrame, "hud-ink-human.png");

        Scene orc = scene(data, "orc");
        BufferedImage orcFrame = paint(orc);
        save(orcFrame, "hud-ink-orc.png");

        var box = human.layout().infoPanel();
        java.awt.Rectangle region = new java.awt.Rectangle(
                box.x(), box.y(), Math.max(1, box.width()), Math.max(1, box.height()));

        int humanWhite = count(humanFrame, region, WHITE);
        int orcWhite = count(orcFrame, region, WHITE);
        int orcYellow = count(orcFrame, region, YELLOW);

        // The control: a human panel really is lettered in the white ink, so
        // "no white on the orc panel" means the ink changed rather than that
        // nothing was drawn.
        assertTrue(humanWhite > 50,
                "only " + humanWhite + " white pixels on the human info panel: the measurement"
                        + " is not finding the lettering at all");
        assertEquals(0, orcWhite,
                orcWhite + " pixels of the human ink on an orc panel: UI.NormalFontColor is"
                        + " declared \"yellow\" for the orcs and is not being applied");
        assertTrue(orcYellow > humanWhite / 2,
                "the orc panel has " + orcYellow + " yellow pixels against the human panel's "
                        + humanWhite + " white: the lettering did not move to the other ink");
    }
}
