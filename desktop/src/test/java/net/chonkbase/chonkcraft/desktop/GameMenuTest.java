package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The in-game menu, driven the way a player drives it.
 *
 * <p>Every check here starts from {@code open()} and reaches its page by
 * clicking, because that is the path that broke: the pages were right and the
 * clicks landed somewhere else. A test that called {@code showSound()} directly
 * would have passed throughout.
 */
class GameMenuTest {

    private static final int WIDTH = 800;
    private static final int HEIGHT = 640;

    /** The panel's own size, which the menu centres in whatever it is given. */
    private static final int PANEL_WIDTH = 256;
    private static final int PANEL_HEIGHT = 288;

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");
        return new GameData(assets);
    }

    /** A session that records what the menu asks of it. */
    private static final class Recording implements GameMenu.Session {
        private boolean paused;
        private int speed = 30;
        private double scale = 1;
        private double game = 1;
        private boolean wheel = true;
        private float effects = 1f;
        private float music = 0.5f;
        private boolean synthesised;
        private List<String> objectives = List.of();

        @Override
        public void setPaused(boolean value) {
            paused = value;
        }

        @Override
        public boolean isPaused() {
            return paused;
        }

        @Override
        public int speed() {
            return speed;
        }

        @Override
        public void setSpeed(int cyclesPerSecond) {
            speed = cyclesPerSecond;
        }

        @Override
        public double interfaceScale() {
            return scale;
        }

        @Override
        public void setInterfaceScale(double value) {
            scale = Math.max(1, Math.min(4, value));
        }

        @Override
        public double gameScale() {
            return game;
        }

        @Override
        public void setGameScale(double value) {
            game = Math.max(1, Math.min(4, value));
        }

        @Override
        public boolean wheelZoom() {
            return wheel;
        }

        @Override
        public void setWheelZoom(boolean enabled) {
            wheel = enabled;
        }

        @Override
        public float effectVolume() {
            return effects;
        }

        @Override
        public void setEffectVolume(float volume) {
            effects = Math.max(0f, Math.min(1f, volume));
        }

        @Override
        public float musicVolume() {
            return music;
        }

        @Override
        public void setMusicVolume(float volume) {
            music = Math.max(0f, Math.min(1f, volume));
        }

        @Override
        public String save() {
            return "saved";
        }

        @Override
        public String load() {
            return "loaded";
        }

        @Override
        public void endScenario() {
        }

        @Override
        public List<String> objectives() {
            return objectives;
        }

        @Override
        public boolean isNetworked() {
            return false;
        }

        @Override
        public boolean synthesisedMusic() {
            return synthesised;
        }

        @Override
        public void setSynthesisedMusic(boolean value) {
            synthesised = value;
        }
    }

    private static BufferedImage render(GameMenu menu) {
        return render(menu, WIDTH, HEIGHT);
    }

    private static BufferedImage render(GameMenu menu, int width, int height) {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        menu.draw(g2, width, height);
        g2.dispose();
        return image;
    }

    /**
     * Keeps a frame in {@code target/} to be looked at.
     *
     * <p>Following {@code InfoPanelRenderTest}: a pixel assertion says the
     * lettering starts where it was asked to and says nothing whatever about
     * whether the page looks like anything.
     */
    private static void keep(BufferedImage frame, String name) {
        try {
            Path out = Paths.get("target", "menu-" + name + ".png");
            Files.createDirectories(out.getParent());
            javax.imageio.ImageIO.write(frame, "png", out.toFile());
        } catch (java.io.IOException ignored) {
            // A frame that could not be written is not a failing assertion.
        }
    }

    private static int panelLeft() {
        return (WIDTH - PANEL_WIDTH) / 2;
    }

    private static int panelTop() {
        return (HEIGHT - PANEL_HEIGHT) / 2;
    }

    /** Where the panel a menu is showing now sits, which the help page widens. */
    private static int panelLeft(GameMenu menu) {
        return (WIDTH - menu.panelWidth(WIDTH, HEIGHT)) / 2;
    }

    private static int panelTop(GameMenu menu) {
        return (HEIGHT - menu.panelHeight(WIDTH, HEIGHT)) / 2;
    }

    /** Clicks the button whose row this is, the way the layout places them. */
    private static void clickRow(GameMenu menu, int row) {
        menu.click(panelLeft() + 16 + 100, panelTop() + 40 + 36 * row + 14);
    }

    /** Clicks the button at the foot of the page, which is always "Previous". */
    private static void clickPrevious(GameMenu menu) {
        int left = panelLeft(menu) + (menu.panelWidth(WIDTH, HEIGHT) - 224) / 2;
        menu.click(left + 50, panelTop(menu) + menu.panelHeight(WIDTH, HEIGHT) - 40 + 14);
    }

    @Test
    @DisplayName("Every retail objective stays inside the menu's text column")
    void retailObjectivesAreMeasuredAndWrapped() {
        GameData data = load();
        Recording session = new Recording();
        GameMenu menu = new GameMenu(data, "human", session);
        int logicalLines = 0;
        int displayLines = 0;
        int missions = 0;

        for (var campaign : data.campaigns()) {
            for (var step : campaign.missions()) {
                var mission = data.missionTextFor(step.mapArchivePath());
                if (mission == null || mission.objectives().isEmpty()) {
                    continue;
                }
                missions++;
                GameMenu.BodyLayout layout = menu.layoutBody(
                        mission.objectives(), 224, 210);
                logicalLines += mission.objectives().size();
                displayLines += layout.lines().size();
                for (String line : layout.lines()) {
                    assertTrue(layout.face().widthOf(line) <= 224,
                            () -> "retail objective escaped the 224-pixel BNE column: " + line);
                }
            }
        }

        assertEquals(52, missions,
                "the regression did not resolve objectives for the whole campaign set");
        assertTrue(displayLines > logicalLines,
                "none of the retail objectives exercised wrapping, so this proves nothing");
    }

    @Test
    @DisplayName("The objectives page draws its wrapped retail prose")
    void objectivesPageDrawsWrappedProse() {
        GameData data = load();
        Recording session = new Recording();
        session.objectives = List.of(
                "Destroy every enemy shipyard, oil platform, and vessel guarding the channel.");
        GameMenu menu = new GameMenu(data, "human", session);
        menu.open();
        render(menu);
        clickRow(menu, 3);

        GameMenu.BodyLayout layout = menu.layoutBody(session.objectives, 224, 210);
        assertTrue(layout.lines().size() > 1, "the objective was not wrapped");
        BufferedImage image = render(menu);
        keep(image, "objectives-wrapped");
        assertEquals("objectives", menu.page());
    }

    /** Opens the menu and walks to the sound page, drawing as a player would. */
    private static GameMenu atSound(GameData data, Recording session) {
        GameMenu menu = new GameMenu(data, "human", session);
        menu.open();
        render(menu);
        clickRow(menu, 1);   // Options
        render(menu);
        clickRow(menu, 2);   // Sound
        render(menu);
        return menu;
    }

    @Test
    @DisplayName("A volume is set by dragging, not by pressing plus ten times")
    void volumeIsASlider() {
        GameData data = load();
        Recording session = new Recording();
        GameMenu menu = atSound(data, session);

        // The effects groove: full button width at the first row, its track at
        // the foot of a thirty pixel row.
        int trackY = panelTop() + 40 + 30 - 5;
        int left = panelLeft() + 16;

        menu.click(left, trackY);
        assertEquals(0f, session.effectVolume(), 0.01f,
                "clicking the left end should silence it");

        menu.drag(left + 224, trackY);
        assertEquals(1f, session.effectVolume(), 0.01f,
                "dragging to the right end should be full");

        menu.drag(left + 112, trackY);
        assertEquals(0.5f, session.effectVolume(), 0.05f,
                "the middle of the groove should be half");

        // Letting go and moving on must not keep adjusting.
        menu.release();
        assertFalse(menu.drag(left, trackY), "a released slider should not move");
        assertEquals(0.5f, session.effectVolume(), 0.05f);
    }

    @Test
    @DisplayName("The music slider is its own, not the effects one")
    void slidersAreIndependent() {
        GameData data = load();
        Recording session = new Recording();
        GameMenu menu = atSound(data, session);

        int left = panelLeft() + 16;
        menu.click(left + 224, panelTop() + 40 + 36 + 30 - 5);
        assertEquals(1f, session.musicVolume(), 0.01f);
        assertEquals(1f, session.effectVolume(), 0.01f, "effects should be untouched");

        menu.release();
        menu.click(left, panelTop() + 40 + 30 - 5);
        assertEquals(0f, session.effectVolume(), 0.01f);
        assertEquals(1f, session.musicVolume(), 0.01f, "music should be untouched");
    }

    @Test
    @DisplayName("The sound page offers a choice of soundtrack, and it is not a slider")
    void theSoundPageOffersBothSoundtracks() {
        // Warcraft II ships its score twice, as eighteen XMI tracks for a
        // synthesiser and as the same music recorded on the discs, and until
        // now the discs won whenever there were any with nothing a player could
        // say about it. Reached by clicking rather than by calling showSound,
        // because the row has to be where the click lands.
        GameData data = load();
        Recording session = new Recording();
        GameMenu menu = atSound(data, session);

        assertTrue(menu.captions().contains("Music: CD Audio"),
                "the sound page does not offer the choice at all: " + menu.captions());
        float wasMusic = session.musicVolume();
        float wasEffects = session.effectVolume();

        clickRow(menu, 2);
        render(menu);

        assertTrue(session.synthesised,
                "pressing the row did not ask the game to change soundtrack");
        assertTrue(menu.captions().contains("Music: Synthesised"),
                "the caption still says what was playing before: " + menu.captions());
        assertEquals("sound", menu.page(),
                "changing the soundtrack must leave the player on the sound page");
        assertEquals(wasMusic, session.musicVolume(), 0.001f,
                "changing the soundtrack moved the music volume");
        assertEquals(wasEffects, session.effectVolume(), 0.001f,
                "changing the soundtrack moved the effects volume");

        clickRow(menu, 2);
        render(menu);
        assertFalse(session.synthesised, "the row does not go back the other way");
    }

    @Test
    @DisplayName("Speed is a range and reaches both ends of it")
    void speedIsASlider() {
        GameData data = load();
        Recording session = new Recording();
        GameMenu menu = new GameMenu(data, "human", session);
        menu.open();
        render(menu);
        clickRow(menu, 1);   // Options
        render(menu);
        clickRow(menu, 0);   // Game Speed
        render(menu);

        int left = panelLeft() + 16;
        int trackY = panelTop() + 40 + 30 - 5;
        menu.click(left, trackY);
        assertEquals(5, session.speed(), "the slow end");
        menu.drag(left + 224, trackY);
        assertEquals(60, session.speed(), "the fast end");
    }

    /**
     * The fault that made the menu useless: the pages were laid out in design
     * pixels and clicked in screen ones, so every page but the first agreed
     * with the pointer only at scale one.
     */
    @Test
    @DisplayName("Every page can be reached and left again by clicking")
    void everyPageIsReachable() {
        GameData data = load();
        Recording session = new Recording();
        GameMenu menu = new GameMenu(data, "human", session);
        menu.open();
        assertTrue(menu.isOpen());
        render(menu);

        // Options, then each of its pages, back out of each, and out again.
        clickRow(menu, 1);
        render(menu);
        for (int page = 0; page < 3; page++) {
            clickRow(menu, page);
            render(menu);
            // "Previous" sits at the foot of the panel, not on the row grid.
            menu.click(panelLeft() + 16 + 100, panelTop() + PANEL_HEIGHT - 40 + 14);
            render(menu);
            assertTrue(menu.isOpen(), "leaving a page should not close the menu");
        }
        menu.click(panelLeft() + 16 + 100, panelTop() + PANEL_HEIGHT - 40 + 14);
        render(menu);
        assertTrue(menu.isOpen(), "the options page returns to the root, it does not close");

        // Escape from the root closes, and unpauses.
        menu.key(java.awt.event.KeyEvent.VK_ESCAPE);
        assertFalse(menu.isOpen(), "Escape at the root should close the menu");
        assertFalse(session.isPaused(), "closing should let the game run again");
    }

    /**
     * Body text used to be drawn before the buttons and at the top of the
     * panel, so the sound page printed its own levels underneath its own
     * controls. Nothing may sit in the rows the widgets occupy.
     */
    @Test
    @DisplayName("No page writes its text underneath its own controls")
    void textClearsTheControls() {
        GameData data = load();
        Recording session = new Recording();
        GameMenu menu = new GameMenu(data, "human", session);
        menu.open();
        render(menu);
        clickRow(menu, 1);

        // Speed, Scale and Sound, which all put a control in the first row.
        // Keystrokes is the fourth and is deliberately not here: it has no
        // control there and its text belongs at the top of the panel.
        for (int page = 0; page < 3; page++) {
            clickRow(menu, page);
            BufferedImage image = render(menu);
            assertFalse(hasLetteringAcross(image, panelTop() + 40 + 4),
                    "page " + page + " draws text across its first control row");
            menu.click(panelLeft() + 16 + 100, panelTop() + PANEL_HEIGHT - 40 + 14);
        }
    }

    /**
     * The interface and the world scale separately, which is the whole point
     * of there being two of them: a player who wants a large readable sidebar
     * does not necessarily want to be looking at a quarter of the map.
     */
    @Test
    @DisplayName("Interface and game scale move independently")
    void twoScalesNotOne() {
        GameData data = load();
        Recording session = new Recording();
        GameMenu menu = new GameMenu(data, "human", session);
        menu.open();
        render(menu);
        clickRow(menu, 1);   // Options
        render(menu);
        clickRow(menu, 1);   // Scale
        render(menu);

        int left = panelLeft() + 16;
        menu.click(left + 224, panelTop() + 40 + 30 - 5);
        assertEquals(4, Math.round(session.interfaceScale()), "interface to the top");
        assertEquals(1, Math.round(session.gameScale()), "the world should not have moved");

        menu.release();
        menu.click(left + 224, panelTop() + 40 + 36 + 30 - 5);
        assertEquals(4, Math.round(session.gameScale()), "world to the top");
        assertEquals(4, Math.round(session.interfaceScale()), "interface should not have moved");

        menu.release();
        menu.click(left, panelTop() + 40 + 36 + 30 - 5);
        assertEquals(1, Math.round(session.gameScale()), "world back to the bottom");
        assertEquals(4, Math.round(session.interfaceScale()));
    }

    @Test
    @DisplayName("The zoom can be locked against the wheel")
    void zoomLocks() {
        GameData data = load();
        Recording session = new Recording();
        GameMenu menu = new GameMenu(data, "human", session);
        menu.open();
        render(menu);
        clickRow(menu, 1);
        render(menu);
        clickRow(menu, 1);
        render(menu);

        assertTrue(session.wheelZoom(), "the wheel zooms by default");
        clickRow(menu, 2);
        assertFalse(session.wheelZoom(), "the toggle should have locked it");
        render(menu);
        clickRow(menu, 2);
        assertTrue(session.wheelZoom(), "and unlocked it again");
    }

    /**
     * The keystroke list is longer than any panel it is drawn in. It used to
     * draw all of it anyway, and the button at the foot of the page was drawn
     * over the last entry.
     */
    @Test
    @DisplayName("No page runs its text under the button at the foot of it")
    void textClearsTheReturnButton() {
        GameData data = load();
        Recording session = new Recording();
        GameMenu menu = new GameMenu(data, "human", session);
        menu.open();
        render(menu);

        // The root, then Help, and every page of it.
        clickRow(menu, 2);
        for (int page = 0; page < menu.layOutHelp(menu.panelWidth(WIDTH, HEIGHT),
                menu.panelHeight(WIDTH, HEIGHT), 0).pages(); page++) {
            BufferedImage image = render(menu);
            int left = panelLeft(menu);
            int top = panelTop(menu);
            int buttonTop = top + menu.panelHeight(WIDTH, HEIGHT) - 40;
            for (int y = buttonTop - 6; y < buttonTop; y++) {
                assertFalse(hasLetteringAlong(image, left, menu.panelWidth(WIDTH, HEIGHT), y),
                        "page " + page + " of the keystroke list reaches y " + (y - top)
                                + ", under the button at " + (buttonTop - top));
            }
            menu.key(java.awt.event.KeyEvent.VK_SPACE);
        }
    }

    /** Whether a whole row across the panel has any lettering on it. */
    private static boolean hasLetteringAlong(BufferedImage image, int y) {
        return hasLetteringAlong(image, panelLeft(), PANEL_WIDTH, y);
    }

    private static boolean hasLetteringAlong(BufferedImage image, int left, int width, int y) {
        for (int x = left + 4; x < left + width - 4; x++) {
            int rgb = image.getRGB(x, y);
            if (((rgb >> 16) & 0xFF) > 150 && ((rgb >> 8) & 0xFF) > 150
                    && (rgb & 0xFF) > 130) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a row of pixels has lettering in the gap between the two
     * half-width buttons, where only stray body text can be.
     */
    private static boolean hasLetteringAcross(BufferedImage image, int y) {
        int gapLeft = panelLeft() + 16 + 106;
        int gapRight = gapLeft + 12;
        for (int x = gapLeft; x < gapRight; x++) {
            int rgb = image.getRGB(x, y);
            int red = (rgb >> 16) & 0xFF;
            int green = (rgb >> 8) & 0xFF;
            int blue = rgb & 0xFF;
            // The lettering is far brighter than the stone it sits on, which
            // measures in the forties.
            if (red > 150 && green > 150 && blue > 130) {
                return true;
            }
        }
        return false;
    }

    // ---- the keystroke page --------------------------------------------

    /** Opens the menu and walks to the keystroke list the way a player does. */
    private static GameMenu atHelp(GameData data, Recording session) {
        GameMenu menu = new GameMenu(data, "human", session);
        menu.open();
        render(menu);
        clickRow(menu, 2);   // Help
        render(menu);
        return menu;
    }

    /**
     * The list used to be reachable by two names, and the name it had in
     * Options advertised F1 -- a shortcut that belongs to Help. One thing, one
     * place; the shortcut stays.
     */
    @Test
    @DisplayName("Options offers no keystroke entry, and F1 still opens the list")
    void keystrokesLiveUnderHelpAlone() {
        GameData data = load();
        Recording session = new Recording();
        GameMenu menu = new GameMenu(data, "human", session);
        menu.open();
        render(menu);
        clickRow(menu, 1);   // Options
        render(menu);

        assertEquals("options", menu.page());
        for (String caption : menu.captions()) {
            String lower = caption.toLowerCase(java.util.Locale.ROOT);
            assertFalse(lower.contains("keystroke"),
                    "Options still offers \"" + caption + "\"");
            assertFalse(lower.contains("f1"),
                    "Options still advertises F1 on \"" + caption + "\"");
        }

        // F1 from Options opens the list and Escape puts the reader back on
        // the page they left, not at the root.
        menu.key(java.awt.event.KeyEvent.VK_F1);
        assertEquals("help", menu.page(), "F1 should open the keystroke list");
        render(menu);
        menu.key(java.awt.event.KeyEvent.VK_ESCAPE);
        assertEquals("options", menu.page(), "Escape should return where F1 was pressed");

        // And from the root, which is the other place it is reached from.
        // Left by the button this time, which is on a wider panel than every
        // other page's and has to be found where that panel puts it.
        menu.key(java.awt.event.KeyEvent.VK_ESCAPE);
        menu.key(java.awt.event.KeyEvent.VK_F1);
        assertEquals("help", menu.page());
        render(menu);
        clickPrevious(menu);
        assertEquals("root", menu.page());
    }

    /**
     * The fault the page was rebuilt for.
     *
     * <p>It used to pad a key name out to a fixed number of characters and draw
     * the result as one string. The face is proportional, so "F10, Backspace"
     * and "^" padded to the same count are nowhere near the same width and the
     * descriptions staggered all over the panel. Two columns at measured
     * positions, or it is not a column.
     */
    @Test
    @DisplayName("Every description begins at the same x, clear of the key caps")
    void theTwoColumnsAreColumns() {
        GameData data = load();
        Recording session = new Recording();
        GameMenu menu = atHelp(data, session);

        for (int[] panel : new int[][] {{352, 400}, {304, 300}, {256, 200}}) {
            GameMenu.HelpLayout first = menu.layOutHelp(panel[0], panel[1], 0);
            for (int page = 0; page < first.pages(); page++) {
                GameMenu.HelpLayout laid = menu.layOutHelp(panel[0], panel[1], page);
                assertEquals(first.descriptionX(), laid.descriptionX(),
                        "the column moved between pages, so it is not a column");
                for (GameMenu.HelpLine line : laid.lines()) {
                    if (line.heading() != null) {
                        continue;
                    }
                    for (GameMenu.Cap cap : line.caps()) {
                        assertTrue(cap.box().x + cap.box().width <= laid.descriptionX(),
                                "the cap \"" + cap.label() + "\" runs into the descriptions");
                    }
                }
            }
        }
    }

    /**
     * The scales the interface offers, drawn into the room each one leaves.
     *
     * <p>The menu is laid out in design pixels and the whole of it is then
     * scaled up, so a bigger interface is a smaller page: at fourfold on a 1280
     * by 720 window the panel has 320 by 180 to live in. A page sized for the
     * design resolution and drawn into that is a page with keys off the bottom
     * of it, which is the failure a screenshot at one scale never shows.
     */
    @Test
    @DisplayName("Nothing overflows the panel at any interface scale")
    void nothingOverflowsAtAnyScale() {
        GameData data = load();
        Recording session = new Recording();
        GameMenu menu = atHelp(data, session);

        int[][] windows = {{1280, 720}, {1600, 900}, {1920, 1080}, {2560, 1440}};
        for (int[] window : windows) {
            for (double scale = 1; scale <= 4; scale++) {
                int room = (int) Math.floor(window[0] / scale);
                int tall = (int) Math.floor(window[1] / scale);
                int panelW = menu.panelWidth(room, tall);
                int panelH = menu.panelHeight(room, tall);
                String where = window[0] + "x" + window[1] + " at " + (int) scale + "x";
                assertTrue(panelW <= room && panelH <= tall,
                        "the panel is bigger than the screen at " + where);

                GameMenu.HelpLayout laid = menu.layOutHelp(panelW, panelH, 0);
                int listed = 0;
                for (int page = 0; page < laid.pages(); page++) {
                    GameMenu.HelpLayout shown = menu.layOutHelp(panelW, panelH, page);
                    // A section broken across a page turn carries its heading
                    // with it. Keys under no heading are keys in no section.
                    assertNotNull(shown.lines().get(0).heading(),
                            "at " + where + ", page " + page + " starts with a key and no "
                                    + "heading to say what it belongs to");
                    for (GameMenu.HelpLine line : shown.lines()) {
                        assertTrue(shown.body().contains(line.extent()),
                                "at " + where + ", page " + page + " draws "
                                        + (line.heading() == null
                                                ? line.description() : line.heading())
                                        + " at " + line.extent() + ", outside "
                                        + shown.body());
                        if (line.heading() == null) {
                            listed++;
                        }
                    }
                }
                assertEquals(entryCount(), listed,
                        "at " + where + " the pages between them do not hold the whole list");
            }
        }
    }

    private static int entryCount() {
        int total = 0;
        for (GameMenu.Section section : GameMenu.KEYSTROKES) {
            total += section.keys().size();
        }
        return total;
    }

    /**
     * Help that has drifted out of date is worse than no help.
     *
     * <p>Both directions, against the switch that actually decides what a key
     * does. Crude -- it reads source text, as {@code CommandCoverageTest} does
     * for the command buttons -- but the alternative is a list of key names
     * checked against a list of key names, which certifies the list.
     */
    @Test
    @DisplayName("The list names every key the game handles, and no others")
    void theListMatchesWhatTheGameAnswers() {
        String source;
        try {
            source = Files.readString(Paths.get(
                    "src/main/java/net/chonkbase/chonkcraft/desktop/GameScreen.java"));
        } catch (java.io.IOException unreadable) {
            throw new AssertionError("cannot read GameScreen to check the help against it",
                    unreadable);
        }

        java.util.Set<String> handled = new java.util.TreeSet<>();
        handled.addAll(keysNamedIn(source, "boolean keyPressed(KeyEvent event)"));
        // The arrows are claimed by name a line before the switch, through the
        // method that says which they are.
        handled.addAll(keysNamedIn(source, "boolean isArrowKey("));

        java.util.Set<String> listed = new java.util.TreeSet<>();
        for (GameMenu.Section section : GameMenu.KEYSTROKES) {
            for (GameMenu.Keystroke key : section.keys()) {
                for (GameMenu.CapText cap : GameMenu.capsOf(key.keys())) {
                    java.util.List<String> codes = CODES.get(cap.label());
                    assertNotNull(codes, "the list shows a cap, \"" + cap.label()
                            + "\", that this test does not know how to check");
                    listed.addAll(codes);
                }
            }
        }
        // The caret has no key code: the game reads it off the character,
        // because where "^" is depends on the keyboard.
        assertTrue(source.contains("getKeyChar() == '^'"),
                "the list offers ^ for select-nothing and nothing answers it");

        assertEquals(handled, listed,
                "the keystroke list and GameScreen.keyPressed disagree: "
                        + "listed but not handled " + minus(listed, handled)
                        + ", handled but not listed " + minus(handled, listed));
    }

    /** Every {@code KeyEvent.VK_} named inside one method of the source. */
    private static java.util.Set<String> keysNamedIn(String source, String signature) {
        int start = source.indexOf(signature);
        assertTrue(start >= 0, "GameScreen no longer has " + signature);
        int open = source.indexOf('{', start);
        int depth = 0;
        int end = open;
        for (int i = open; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                end = i;
                break;
            }
        }
        java.util.Set<String> found = new java.util.TreeSet<>();
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("KeyEvent\\.(VK_\\w+)").matcher(source.substring(open, end));
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    private static java.util.Set<String> minus(java.util.Set<String> from,
            java.util.Set<String> take) {
        java.util.Set<String> left = new java.util.TreeSet<>(from);
        left.removeAll(take);
        return left;
    }

    /**
     * What each cap on the list means in key codes.
     *
     * <p>Modifiers stand for no code at all -- the game asks the event whether
     * shift was down, not which shift. A range stands for the codes the game
     * names to bound it: {@code F2-F4} is a comparison against {@code VK_F2}
     * and {@code VK_F4}, and {@code 0-9} an offset from {@code VK_0}.
     */
    private static final java.util.Map<String, java.util.List<String>> CODES =
            java.util.Map.ofEntries(
                    java.util.Map.entry("Ctrl", List.of()),
                    java.util.Map.entry("Shift", List.of()),
                    java.util.Map.entry("Alt", List.of()),
                    java.util.Map.entry("^", List.of()),
                    java.util.Map.entry("F1", List.of("VK_F1")),
                    java.util.Map.entry("F5", List.of("VK_F5")),
                    java.util.Map.entry("F6", List.of("VK_F6")),
                    java.util.Map.entry("F7", List.of("VK_F7")),
                    java.util.Map.entry("F8", List.of("VK_F8")),
                    java.util.Map.entry("F10", List.of("VK_F10")),
                    java.util.Map.entry("F11", List.of("VK_F11")),
                    java.util.Map.entry("F12", List.of("VK_F12")),
                    java.util.Map.entry("F2-F4", List.of("VK_F2", "VK_F4")),
                    java.util.Map.entry("0-9", List.of("VK_0")),
                    java.util.Map.entry("M", List.of("VK_M")),
                    java.util.Map.entry("P", List.of("VK_P")),
                    java.util.Map.entry("I", List.of("VK_I")),
                    java.util.Map.entry("C", List.of("VK_C")),
                    java.util.Map.entry("E", List.of("VK_E")),
                    java.util.Map.entry("H", List.of("VK_H")),
                    java.util.Map.entry("L", List.of("VK_L")),
                    java.util.Map.entry("Q", List.of("VK_Q")),
                    java.util.Map.entry("S", List.of("VK_S")),
                    java.util.Map.entry("T", List.of("VK_T")),
                    java.util.Map.entry("X", List.of("VK_X")),
                    java.util.Map.entry(".", List.of("VK_PERIOD")),
                    java.util.Map.entry("Tab", List.of("VK_TAB")),
                    java.util.Map.entry("Print", List.of("VK_PRINTSCREEN")),
                    java.util.Map.entry("Esc", List.of("VK_ESCAPE")),
                    java.util.Map.entry("Space", List.of("VK_SPACE")),
                    java.util.Map.entry("Pause", List.of("VK_PAUSE")),
                    java.util.Map.entry("Backspace", List.of("VK_BACK_SPACE")),
                    java.util.Map.entry("Arrows",
                            List.of("VK_LEFT", "VK_RIGHT", "VK_UP", "VK_DOWN")),
                    java.util.Map.entry("+", List.of("VK_EQUALS", "VK_PLUS", "VK_ADD")),
                    java.util.Map.entry("-", List.of("VK_MINUS", "VK_SUBTRACT")));

    /**
     * The pages turn, and turning them comes back round to the first.
     *
     * <p>A list that pages and cannot be got back to the top of is a list with
     * a trapdoor in it.
     */
    @Test
    @DisplayName("The list pages, and pages round")
    void thePagesTurn() {
        GameData data = load();
        Recording session = new Recording();
        GameMenu menu = atHelp(data, session);
        render(menu);

        int pages = menu.layOutHelp(menu.panelWidth(WIDTH, HEIGHT), menu.panelHeight(WIDTH, HEIGHT), 0).pages();
        assertTrue(pages > 1, "the list is expected to need more than one page at " + WIDTH
                + "x" + HEIGHT + "; if it stopped needing one, this test is stale");

        // The button beside "Previous" turns the page; it says which page of
        // how many, so a reader knows there is another.
        java.util.List<String> captions = menu.captions();
        assertEquals(2, captions.size(), "a paged list needs a button to page it");
        assertTrue(captions.get(1).contains("1/" + pages), "the button should say where "
                + "in the list the reader is, not just \"more\": " + captions.get(1));

        for (int turn = 1; turn < pages; turn++) {
            menu.key(java.awt.event.KeyEvent.VK_SPACE);
            render(menu);
            assertTrue(menu.captions().get(1).contains((turn + 1) + "/" + pages));
        }
        menu.key(java.awt.event.KeyEvent.VK_SPACE);
        render(menu);
        assertTrue(menu.captions().get(1).contains("1/" + pages),
                "the last page should come back round to the first");
    }

    /**
     * Draws every page of the list, to be looked at.
     *
     * <p>Nothing here can tell whether it looks right. The assertions are that
     * the ink is inside the panel and that each key cap has a border round it,
     * which is what distinguishes the redrawn page from the ragged two columns
     * of padded text it replaces; the frames in {@code target/} are for the
     * only judgement that matters.
     */
    @Test
    @DisplayName("The keystroke page draws its caps and stays inside its panel")
    void theListDrawsAsKeys() {
        GameData data = load();
        Recording session = new Recording();
        GameMenu menu = atHelp(data, session);

        int panelW = menu.panelWidth(WIDTH, HEIGHT);
        int panelH = menu.panelHeight(WIDTH, HEIGHT);
        int pages = menu.layOutHelp(panelW, panelH, 0).pages();
        for (int page = 0; page < pages; page++) {
            GameMenu.HelpLayout laid = menu.layOutHelp(panelW, panelH, page);
            BufferedImage image = render(menu);
            keep(image, "help-" + (page + 1));

            int left = panelLeft(menu);
            int top = panelTop(menu);
            for (GameMenu.HelpLine line : laid.lines()) {
                if (line.heading() != null) {
                    continue;
                }
                for (GameMenu.Cap cap : line.caps()) {
                    java.awt.Rectangle box = cap.box();
                    // A cap is a shape: the border is darker than the panel it
                    // sits on and the face inside is lighter than the border.
                    int face = image.getRGB(left + box.x + box.width / 2,
                            top + box.y + box.height / 2);
                    int outside = image.getRGB(left + box.x - 2,
                            top + box.y + box.height / 2);
                    assertTrue(blue(face) > blue(outside),
                            "the cap \"" + cap.label() + "\" does not stand off the panel");
                }
            }
            menu.key(java.awt.event.KeyEvent.VK_SPACE);
        }

        // And every page at the least room the interface leaves it -- fourfold
        // on a 1920 by 1080 display -- where the list breaks into more pages
        // and a section has to carry its heading across the break.
        int cramped = menu.layOutHelp(menu.panelWidth(480, 270),
                menu.panelHeight(480, 270), 0).pages();
        for (int page = 0; page < cramped; page++) {
            keep(render(menu, 480, 270), "help-small-" + (page + 1));
            menu.key(java.awt.event.KeyEvent.VK_SPACE);
        }
        // And through the transform the game actually draws the menu with, so
        // that the caps can be judged at the size a player at threefold sees
        // them rather than at the size they are laid out in.
        keep(scaled(menu, 480, 270, 3), "help-scaled");
    }

    /** Draws the menu the way {@code GameScreen} does: design pixels, blown up. */
    private static BufferedImage scaled(GameMenu menu, int design, int tall, int by) {
        BufferedImage image = new BufferedImage(design * by, tall * by,
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = image.createGraphics();
        g2.scale(by, by);
        menu.draw(g2, design, tall);
        g2.dispose();
        return image;
    }

    private static int blue(int rgb) {
        return rgb & 0xFF;
    }
}
