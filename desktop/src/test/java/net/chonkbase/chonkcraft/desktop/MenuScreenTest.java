package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The menu, built on the game's own art.
 *
 * <p>Rendered rather than merely constructed, because most of what can go
 * wrong with a menu is positional: a page that draws its buttons off the panel
 * or on top of each other still passes every test that only counts entries.
 */
class MenuScreenTest {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 800;

    private record Fixture(GameData data, List<Path> maps) {}

    private static Fixture load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");

        // The list through Main.findMaps, which is the call the launcher makes,
        // rather than a directory walk written out again here. The order is the
        // same one either way -- MapDiscoverySeamTest holds the two against
        // each other -- so the pages below are paged and captioned exactly as a
        // player would find them.
        return new Fixture(new GameData(install), Main.findMaps(install));
    }

    private static MenuScreen screen(Fixture fixture, java.util.function.Consumer<
            MenuScreen.Launch> onLaunch) {
        MenuScreen menu = new MenuScreen(fixture.data(), "human", WIDTH, HEIGHT, onLaunch);
        menu.setSize(WIDTH, HEIGHT);
        return menu;
    }

    /** Draws the current page and returns what came out. */
    private static BufferedImage render(MenuScreen menu) {
        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        menu.paint(g2);
        g2.dispose();
        return image;
    }

    @Test
    @DisplayName("the menu art comes out of the archive")
    void theArtLoads() {
        Fixture fixture = load();
        MenuScreen menu = screen(fixture, launch -> { });
        assertTrue(menu.isAvailable(), "no menu background in this installation");

        menu.showMainMenu(fixture.data(), fixture.maps());
        BufferedImage image = render(menu);

        // The panel is centred, so the middle of the window is the middle of
        // the background rather than the black around it.
        assertTrue((image.getRGB(WIDTH / 2, HEIGHT / 2) & 0xFFFFFF) != 0,
                "the background did not draw");
    }

    @Test
    @DisplayName("every button lands inside the panel the art was drawn for")
    void buttonsStayOnThePanel() {
        Fixture fixture = load();
        MenuScreen menu = screen(fixture, launch -> { });
        Assumptions.assumeTrue(menu.isAvailable(), "no menu art");

        // The longest page: a full mission page is six missions plus the two
        // navigation rows. If that fits, the shorter ones do.
        menu.showMissionsForTest(fixture.data(), fixture.maps(), "human", 14);
        render(menu);

        // In the design's own 640 by 480, not on screen: the menu is laid out
        // at the size it was drawn for and scaled to the window, so a button
        // fitting the panel is a question about the design and not about
        // whatever size the window happens to be.
        List<java.awt.Rectangle> slots = menu.slotBoundsForTest();
        assertEquals(8, slots.size(), "six missions, a next page and a way back");
        for (java.awt.Rectangle slot : slots) {
            assertTrue(slot.x >= 0 && slot.x + slot.width <= 640,
                    "a button ran off the side: " + slot);
            assertTrue(slot.y >= 0 && slot.y + slot.height <= 480,
                    "a button ran off the bottom: " + slot);
        }
    }

    @Test
    @DisplayName("buttons do not overlap each other")
    void buttonsDoNotOverlap() {
        Fixture fixture = load();
        MenuScreen menu = screen(fixture, launch -> { });
        Assumptions.assumeTrue(menu.isAvailable(), "no menu art");

        menu.showMissionsForTest(fixture.data(), fixture.maps(), "human", 14);
        render(menu);

        List<java.awt.Rectangle> slots = menu.slotBoundsForTest();
        for (int i = 0; i < slots.size(); i++) {
            for (int k = i + 1; k < slots.size(); k++) {
                assertTrue(!slots.get(i).intersects(slots.get(k)),
                        "rows " + i + " and " + k + " overlap");
            }
        }
    }

    @Test
    @DisplayName("choosing a mission reports the campaign and the number")
    void choosingAMissionReportsIt() {
        Fixture fixture = load();
        Assumptions.assumeTrue(!fixture.data().campaigns().isEmpty(), "no campaigns");

        AtomicReference<MenuScreen.Launch> chosen = new AtomicReference<>();
        MenuScreen menu = screen(fixture, chosen::set);
        menu.showMissionsForTest(fixture.data(), fixture.maps(), "human", 14);
        render(menu);

        // The third row is mission three, and pressing it must say so rather
        // than reporting the row.
        menu.pressForTest(2);
        assertNotNull(chosen.get(), "nothing was reported");
        assertEquals("human", chosen.get().campaign());
        assertEquals(3, chosen.get().mission());
        assertNull(chosen.get().map(), "a campaign choice is not a map choice");
    }

    @Test
    @DisplayName("the second page of missions carries on from the first")
    void missionPagesContinue() {
        Fixture fixture = load();
        Assumptions.assumeTrue(!fixture.data().campaigns().isEmpty(), "no campaigns");

        AtomicReference<MenuScreen.Launch> chosen = new AtomicReference<>();
        MenuScreen menu = screen(fixture, chosen::set);
        menu.showMissionsForTest(fixture.data(), fixture.maps(), "human", 14);
        render(menu);

        // Row seven on a full page is "more missions".
        menu.pressForTest(6);
        render(menu);
        menu.pressForTest(0);

        assertNotNull(chosen.get());
        assertEquals(7, chosen.get().mission(),
                "the second page starts where the first left off");
    }

    @Test
    @DisplayName("choosing a map reports the file")
    void choosingAMapReportsIt() {
        Fixture fixture = load();
        Assumptions.assumeTrue(!fixture.maps().isEmpty(), "no maps in this installation");

        AtomicReference<MenuScreen.Launch> chosen = new AtomicReference<>();
        MenuScreen menu = screen(fixture, chosen::set);
        menu.showMapsForTest(fixture.data(), fixture.maps());
        render(menu);

        menu.pressForTest(0);
        assertNotNull(chosen.get());
        assertNull(chosen.get().campaign(), "a map choice is not a campaign choice");
        assertEquals(fixture.maps().getFirst(), chosen.get().map());
    }

    @Test
    @DisplayName("Direct hosting remains a deliberate map choice and transport")
    void directHostingIsAFirstClassMenuPath() {
        Fixture fixture = load();
        Assumptions.assumeTrue(!fixture.maps().isEmpty(), "no maps in this installation");

        AtomicReference<MenuScreen.Launch> chosen = new AtomicReference<>();
        MenuScreen menu = screen(fixture, chosen::set);
        menu.showMultiplayerForTest(fixture.data(), fixture.maps());
        render(menu);
        menu.pressForTest(2);
        render(menu);
        assertTrue(menu.headingForTest().startsWith("Direct IP Game"),
                "the map page should still say which transport is being configured");

        menu.pressForTest(0);
        assertNotNull(chosen.get());
        assertEquals(MenuScreen.Launch.Multiplayer.HOST_DIRECT, chosen.get().multiplayer());
        assertEquals(fixture.maps().getFirst(), chosen.get().map());
    }

    @Test
    @DisplayName("Direct joining is separate from the online room service")
    void directJoiningIsAFirstClassMenuPath() {
        Fixture fixture = load();
        AtomicReference<MenuScreen.Launch> chosen = new AtomicReference<>();
        MenuScreen menu = screen(fixture, chosen::set);
        menu.showMultiplayerForTest(fixture.data(), fixture.maps());
        render(menu);

        menu.pressForTest(4);
        assertNotNull(chosen.get());
        assertEquals(MenuScreen.Launch.Multiplayer.JOIN_DIRECT, chosen.get().multiplayer(),
                "the direct join button must not pass through online browsing");
    }

    /** Every page the menu can put up, named, so a check can sweep them all. */
    private static java.util.Map<String, Runnable> everyPage(Fixture fixture, MenuScreen menu) {
        List<Path> saves = new ArrayList<>();
        for (String name : List.of("Autosave", "before the dam", "Tarsonis Keep",
                "orc expansion four", "quick", "hillsbrad", "a seventh")) {
            saves.add(Paths.get("/saves/" + name + ".sav.gz"));
        }
        java.util.Map<String, Runnable> pages = new java.util.LinkedHashMap<>();
        pages.put("main", () -> menu.showMainMenu(fixture.data(), fixture.maps()));
        pages.put("campaigns", () -> menu.showCampaignsForTest(fixture.data(), fixture.maps()));
        pages.put("multiplayer", () ->
                menu.showMultiplayerForTest(fixture.data(), fixture.maps()));
        pages.put("maps", () -> menu.showMapsForTest(fixture.data(), fixture.maps()));
        pages.put("host maps", () ->
                menu.showMultiplayerMapsForTest(fixture.data(), fixture.maps()));
        pages.put("saves", () -> menu.showSavesForTest(fixture.data(), fixture.maps(), saves));
        // The two that used to lift the column: a campaign long enough to page,
        // and one short enough not to.
        pages.put("missions of fourteen", () ->
                menu.showMissionsForTest(fixture.data(), fixture.maps(), "orc-exp", 14));
        pages.put("missions of four", () ->
                menu.showMissionsForTest(fixture.data(), fixture.maps(), "human", 4));
        return pages;
    }

    @Test
    @DisplayName("no page draws its heading onto its first button")
    void theHeadingNeverTouchesTheColumn() {
        Fixture fixture = load();
        MenuScreen menu = screen(fixture, launch -> { });
        Assumptions.assumeTrue(menu.isAvailable(), "no menu art");

        for (var page : everyPage(fixture, menu).entrySet()) {
            page.getValue().run();
            render(menu);
            java.awt.Rectangle heading = menu.headingBoundsForTest();
            if (heading == null) {
                continue;
            }
            java.awt.Rectangle first = menu.slotBoundsForTest().getFirst();
            // The heading's room is reserved in the column's own sum, so this
            // gap does not shrink as a page grows. It used to: the heading was
            // drawn thirty pixels above whatever the column had been pushed to.
            assertTrue(heading.y + heading.height <= first.y,
                    page.getKey() + ": the heading reaches " + (heading.y + heading.height)
                            + " and the first button starts at " + first.y);
            assertTrue(first.y - (heading.y + heading.height) >= 12,
                    page.getKey() + ": only " + (first.y - heading.y - heading.height)
                            + " pixels between the heading and the column");
        }
    }

    @Test
    @DisplayName("no page draws over the parchment's burnt edge")
    void everyPageStaysOnTheParchment() {
        Fixture fixture = load();
        MenuScreen menu = screen(fixture, launch -> { });
        Assumptions.assumeTrue(menu.isAvailable(), "no menu art");

        // Measured off the background art rather than read back from the
        // screen's own constants, so this stays a question about the picture.
        // The Tides of Darkness logo's tail ends at 152, and the burnt edge
        // painted along the foot of the parchment begins at 457 under the left
        // of the button column. Everything the menu draws lives between them.
        int logoBottom = 152;
        int scorchTop = 457;
        for (var page : everyPage(fixture, menu).entrySet()) {
            page.getValue().run();
            render(menu);
            java.awt.Rectangle heading = menu.headingBoundsForTest();
            if (heading != null) {
                assertTrue(heading.y >= logoBottom,
                        page.getKey() + ": the heading rides up onto the logo at " + heading.y);
                // Kept near the width of the column it captions. A line half
                // again as wide runs out over the sketches either side.
                assertTrue(heading.width <= 320,
                        page.getKey() + ": the heading is " + heading.width + " wide");
            }
            for (java.awt.Rectangle slot : menu.slotBoundsForTest()) {
                assertTrue(slot.y >= logoBottom,
                        page.getKey() + ": a button at " + slot.y + " is up in the logo");
                assertTrue(slot.y + slot.height <= scorchTop,
                        page.getKey() + ": a button reaches " + (slot.y + slot.height)
                                + ", into the burnt edge at " + scorchTop);
            }
        }
    }

    @Test
    @DisplayName("a page whose buttons say it themselves carries no heading")
    void redundantHeadingsAreGone() {
        Fixture fixture = load();
        MenuScreen menu = screen(fixture, launch -> { });
        Assumptions.assumeTrue(menu.isAvailable(), "no menu art");

        // Every row on these pages names itself. Saying "choose a campaign"
        // over four buttons each ending in the word Campaign tells the player
        // nothing they cannot see.
        menu.showMainMenu(fixture.data(), fixture.maps());
        assertEquals("", menu.headingForTest(), "the main menu");
        menu.showCampaignsForTest(fixture.data(), fixture.maps());
        assertEquals("", menu.headingForTest(), "the campaign list");
        menu.showMultiplayerForTest(fixture.data(), fixture.maps());
        assertEquals("", menu.headingForTest(), "host, join, back");

        // And where the rows do not say it: "Mission 3" does not say whose.
        menu.showMissionsForTest(fixture.data(), fixture.maps(), "orc-exp", 14);
        assertEquals("Orc Expansion", menu.headingForTest());
    }

    @Test
    @DisplayName("a long list says where in it the player is")
    void pagedListsSayWhereTheyAre() {
        Fixture fixture = load();
        Assumptions.assumeTrue(fixture.maps().size() > 6,
                "this installation has one page of maps");

        MenuScreen menu = screen(fixture, launch -> { });
        menu.showMapsForTest(fixture.data(), fixture.maps());
        render(menu);
        assertEquals("Maps 1 to 6 of " + fixture.maps().size(), menu.headingForTest());

        // The next page carries on, and says so.
        menu.pressForTest(6);
        assertEquals("Maps 7 to 12 of " + fixture.maps().size(), menu.headingForTest());

        // One page of maps needs no such line: they are all on screen.
        menu.showMapsForTest(fixture.data(), fixture.maps().subList(0, 3));
        assertEquals("", menu.headingForTest());
    }

    @Test
    @DisplayName("a caption too long for its button is cut, not run off it")
    void longCaptionsAreCutToTheButton() {
        Fixture fixture = load();
        MenuScreen menu = screen(fixture, launch -> { });
        Assumptions.assumeTrue(menu.isAvailable(), "no menu art");

        // A map file is named by whoever made it, and this one is named by
        // somebody who was not thinking of a 224 pixel slab.
        Path silly = Paths.get("/maps/A Map With A Preposterously Long Name Indeed.pud");
        menu.showMapsForTest(fixture.data(), List.of(silly));
        BufferedImage drawn = render(menu);

        java.awt.Rectangle slot = menu.slotBoundsForTest().getFirst();
        // Nothing gold is written on the parchment either side of the button.
        java.awt.Rectangle shown = PixelScaler.fit(640, 480, WIDTH, HEIGHT, false);
        int row = shown.y + (slot.y + slot.height / 2) * shown.height / 480;
        for (int x = shown.x + 4; x < shown.x + slot.x * shown.width / 640 - 4; x++) {
            int rgb = drawn.getRGB(x, row);
            boolean gold = (rgb >> 16 & 255) > 200 && (rgb >> 8 & 255) > 170
                    && (rgb & 255) < 140;
            assertTrue(!gold, "lettering at " + x + " is off the left of the button");
        }
    }

    @Test
    @DisplayName("a save past the sixth can still be reached")
    void everySaveIsReachable() {
        Fixture fixture = load();
        AtomicReference<MenuScreen.Launch> chosen = new AtomicReference<>();
        MenuScreen menu = screen(fixture, chosen::set);

        List<Path> saves = new ArrayList<>();
        for (int i = 1; i <= 9; i++) {
            saves.add(Paths.get("/saves/save " + i + ".sav.gz"));
        }
        menu.showSavesForTest(fixture.data(), fixture.maps(), saves);
        render(menu);
        assertEquals("Saved Games 1 to 6 of 9", menu.headingForTest());

        // Row seven is the way on; the seventh save used to be unreachable.
        menu.pressForTest(6);
        render(menu);
        assertEquals("Saved Games 7 to 9 of 9", menu.headingForTest());
        menu.pressForTest(0);
        assertNotNull(chosen.get());
        assertEquals(saves.get(6), chosen.get().save());
    }

    @Test
    @DisplayName("every screen letters in the face the in-game menu uses")
    void oneFaceThroughout() {
        Fixture fixture = load();
        // The in-game panel's face is the one the game is set in; every screen
        // a player passes through on the way to it has to be the same. The
        // briefing was not: it drew in whatever the look and feel handed out,
        // which is a sans on most machines, so going from the menu into a
        // mission changed typeface halfway.
        String inGame = GameFont.load(fixture.data(), GameFont.Face.GAME).family();
        assertEquals("Droid Serif", inGame, "the in-game face is not the shipped one");

        List<List<String>> screens = List.of(
                screen(fixture, launch -> { }).faceFamiliesForTest(),
                new BriefingScreen(fixture.data(), "human", WIDTH, HEIGHT, null,
                        "Mission 1", "Some prose.", "Continue", () -> { })
                        .faceFamiliesForTest(),
                new JoinScreen(fixture.data(), null, new JoinScreen.Listener() {
                    @Override
                    public void onJoin(String host, int port) { }

                    @Override
                    public void onCancel() { }
                }).faceFamiliesForTest());
        for (List<String> families : screens) {
            for (String family : families) {
                assertEquals(inGame, family, "a screen letters in " + family);
            }
        }
    }

    @Test
    @DisplayName("the campaign page lists what the scripts define")
    void theCampaignPageListsTheCampaigns() {
        Fixture fixture = load();
        Assumptions.assumeTrue(!fixture.data().campaigns().isEmpty(), "no campaigns");

        MenuScreen menu = screen(fixture, launch -> { });
        menu.showCampaignsForTest(fixture.data(), fixture.maps());
        render(menu);

        List<String> captions = new ArrayList<>(menu.captionsForTest());
        assertEquals(List.of("Human Campaign", "Orc Campaign", "Human Expansion",
                "Orc Expansion", "Previous Menu"), captions);
    }
}
