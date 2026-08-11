package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JPanel;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.network.GameDiscovery;
import net.chonkbase.chonkcraft.engine.network.GameLobby;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every menu page, drawn at three window sizes.
 *
 * <p>The screens are laid out at 640 by 480 and blown up to whatever the window
 * is, so a layout that is right at one size is right at all of them -- in
 * theory. In practice the thing that breaks is the mapping: a page draws
 * correctly into the design buffer and then lands somewhere else on screen,
 * and only a check that goes from a design rectangle to a screen pixel catches
 * it. So each page is drawn at 1024 by 700, 1400 by 900 and 2560 by 1440, and
 * every button the page says it drew is looked for where the scaling says it
 * should be.
 *
 * <p>The pictures are left in {@code target/menu-qa} on the way past. They are
 * not what the test asserts on -- they are for a person to look at when
 * something here fails, or when the menus are being worked on.
 */
class MenuRenderSweepTest {

    /** A small window, a middling one, and one bigger than the art by four.  */
    private static final int[][] SIZES = {{1024, 700}, {1400, 900}, {2560, 1440}};

    private static final int DESIGN_WIDTH = 640;
    private static final int DESIGN_HEIGHT = 480;

    private static Path outDir() throws java.io.IOException {
        Path dir = Paths.get("target", "menu-qa");
        Files.createDirectories(dir);
        return dir;
    }

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    /**
     * The map list the menu is given, asked for the way the launcher asks.
     *
     * <p>{@code Main.findMaps} rather than a directory walk repeated here: that
     * is the call the running game makes, and {@code MapDiscoverySeamTest}
     * holds its order against the walk, so a sweep of the pages draws the list
     * a player would be looking at.
     */
    private static List<Path> maps(GameData data) {
        return Main.findMaps(data.source());
    }

    /** Draws a panel at a size and leaves the picture behind. */
    private static BufferedImage draw(String name, JPanel panel, int width, int height)
            throws java.io.IOException {
        panel.setSize(width, height);
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        panel.paint(g2);
        g2.dispose();
        javax.imageio.ImageIO.write(image, "png",
                outDir().resolve(name + "-" + width + "x" + height + ".png").toFile());
        return image;
    }

    /** Where a design point lands on a window of a given size. */
    private static java.awt.Point onScreen(int designX, int designY, int width, int height) {
        Rectangle shown = PixelScaler.fit(DESIGN_WIDTH, DESIGN_HEIGHT, width, height, false);
        return new java.awt.Point(
                shown.x + designX * shown.width / DESIGN_WIDTH,
                shown.y + designY * shown.height / DESIGN_HEIGHT);
    }

    /** Where a design rectangle's middle lands on a window of a given size. */
    private static java.awt.Point onScreen(Rectangle slot, int width, int height) {
        return onScreen(slot.x + slot.width / 2, slot.y + slot.height / 2, width, height);
    }

    @Test
    @DisplayName("every menu page lands where the scaling says, at every window size")
    void everyPageSurvivesEveryScale() throws Exception {
        GameData data = data();
        List<Path> maps = maps(data);
        MenuScreen menu = new MenuScreen(data, "human", 1024, 700, launch -> { });

        List<Path> saves = new ArrayList<>();
        for (String name : List.of("Autosave", "Tarsonis Keep", "before the dam",
                "orc-exp mission 4", "quick")) {
            saves.add(Paths.get("/saves/" + name + ".sav.gz"));
        }

        Map<String, Runnable> pages = new LinkedHashMap<>();
        pages.put("01-main", () -> menu.showMainMenu(data, maps));
        pages.put("02-campaigns", () -> menu.showCampaignsForTest(data, maps));
        pages.put("03-missions", () -> menu.showMissionsForTest(data, maps, "human", 14));
        pages.put("04-maps", () -> menu.showMapsForTest(data, maps));
        pages.put("05-saves", () -> menu.showSavesForTest(data, maps, saves));
        pages.put("06-multiplayer", () -> menu.showMultiplayerForTest(data, maps));
        pages.put("07-mpmaps", () -> menu.showMultiplayerMapsForTest(data, maps));

        for (var page : pages.entrySet()) {
            page.getValue().run();
            for (int[] size : SIZES) {
                BufferedImage drawn = draw(page.getKey(), menu, size[0], size[1]);
                List<Rectangle> slots = menu.slotBoundsForTest();
                assertTrue(!slots.isEmpty(), page.getKey() + " drew no rows");
                for (int i = 0; i < slots.size(); i++) {
                    Rectangle slot = slots.get(i);
                    // Both shoulders of the slab, well clear of the caption
                    // centred on it. The middle is where the lettering is, and
                    // gold is as red as the stone.
                    for (int inset : new int[] {24, slot.width - 24}) {
                        java.awt.Point at = onScreen(slot.x + inset,
                                slot.y + slot.height / 2, size[0], size[1]);
                        int rgb = drawn.getRGB(at.x, at.y);
                        int red = rgb >> 16 & 255;
                        int green = rgb >> 8 & 255;
                        // The slab is near-black red -- 68, 3, 0 in the middle
                        // of it. The parchment behind it is a pale warm grey
                        // and the sketches on it are a darker one, and both
                        // have far more green in them than the stone does. A
                        // row that is not where the screen says it is reads as
                        // parchment here.
                        assertTrue(green < 40 && red > green * 2,
                                page.getKey() + " at " + size[0] + "x" + size[1]
                                        + ": row " + i + " is not on its button at "
                                        + at.x + "," + at.y + " (" + red + "," + green + ")");
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the briefing, the lobby and the join screen all draw at every size")
    void theOtherScreensSurviveEveryScale() throws Exception {
        GameData data = data();
        var mission = data.loadMission("campaigns/human/level01h", 0);
        Assumptions.assumeTrue(mission != null, "the first human mission is not available");

        BriefingScreen briefing = new BriefingScreen(data, "human", 1024, 700,
                mission.background(), "Human Campaign - Mission 1", mission.briefing(),
                "Continue", () -> { });
        for (int[] size : SIZES) {
            BufferedImage drawn = draw("08-briefing", briefing, size[0], size[1]);
            Rectangle button = briefing.continueBoundsForTest();
            java.awt.Point at = onScreen(button, size[0], size[1]);
            // The way onward is the one thing on this screen that must be
            // there: a briefing with no reachable button is a dead end.
            assertTrue((drawn.getRGB(at.x, at.y) & 0xFFFFFF) != 0,
                    "the continue button is not where the scaling says at "
                            + size[0] + "x" + size[1]);
            assertTrue(briefing.proseBottomForTest() <= button.y,
                    "the prose runs into the button");
        }

        try (GameLobby lobby = GameLobby.host("Chris", "garden.pud", 8, 7599)) {
            LobbyScreen screen = new LobbyScreen(data, lobby, "Garden of War",
                    new LobbyScreen.Listener() {
                        @Override
                        public void onStart(GameLobby settled) { }

                        @Override
                        public void onCancel() { }
                    });
            for (int[] size : SIZES) {
                BufferedImage drawn = draw("09-lobby", screen, size[0], size[1]);
                java.awt.Point at = onScreen(LobbyScreen.startBounds(), size[0], size[1]);
                assertTrue((drawn.getRGB(at.x, at.y) & 0xFFFFFF) != 0,
                        "Start is not where the scaling says at " + size[0] + "x" + size[1]);
            }
        }

        JoinScreen join = new JoinScreen(data, null, new JoinScreen.Listener() {
            @Override
            public void onJoin(String host, int port) { }

            @Override
            public void onCancel() { }
        });
        for (int[] size : SIZES) {
            draw("10-join-empty", join, size[0], size[1]);
        }
        join.setFound(List.of(
                new GameDiscovery.Game("Chris's Game", "Garden of War", 2, 8,
                        "192.168.1.20", 7100, 0),
                new GameDiscovery.Game("A Very Long Player Name Here", "Battle on the Rocks",
                        8, 8, "192.168.1.44", 7100, 0)));
        for (int[] size : SIZES) {
            BufferedImage drawn = draw("11-join-found", join, size[0], size[1]);
            java.awt.Point at = onScreen(JoinScreen.connectBounds(), size[0], size[1]);
            assertTrue((drawn.getRGB(at.x, at.y) & 0xFFFFFF) != 0,
                    "Connect is not where the scaling says at " + size[0] + "x" + size[1]);
        }
    }
}
