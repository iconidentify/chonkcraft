package net.chonkbase.chonkcraft.desktop;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.Palette;
import net.chonkbase.chonkcraft.engine.GameData;

/**
 * The menu that F10 opens over a running game.
 *
 * <p>Implements {@code RunGameMenu} and the pages it leads to, in
 * {@code scripts/menus/game.legacy-declaration}, {@code help.legacy-declaration} and {@code options.legacy-declaration}.
 *
 * <p>Every number here is the script's. The panel is 256 by 288 because
 * {@code menu:resize(256, 288)} says so and because {@code ui/human/panel_1}
 * is exactly that size; the heading sits at x 128 y 11; full buttons are 224
 * wide at x 16 on a 36 pixel pitch from y 40; the pair of half buttons are 106
 * wide at x 16 and x 134; and "Return to Game" is at {@code 288 - 40}. Laying
 * it out by eye instead gets something that looks close and lines up with
 * nothing.
 *
 * <p>The keystroke page is the one exception, and it is upstream's exception
 * too: {@code RunKeystrokeHelpMenu} resizes the panel before it lays anything
 * out, because a list of keys does not fit in the panel a menu fits in. It is
 * laid out here rather than in the script's coordinates because the script's
 * answer is a scroll bar, and this one pages.
 *
 * <p>The menu pauses the game while it is up, which is what
 * {@code HandleIngameCommandKey} does before opening any of these: every branch
 * of it calls {@code SetGamePaused(true)} first, except in a network game where
 * one player cannot stop the others.
 */
final class GameMenu {

    /** The panel art's own size, and the coordinate space the script uses. */
    private static final int WIDTH = 256;

    private static final int HEIGHT = 288;

    /**
     * The keystroke page's own panel, which is larger than the rest.
     *
     * <p>The script does the same thing: every other page runs on
     * {@code panel(1)} at 256 by 288, and {@code RunKeystrokeHelpMenu} calls
     * {@code menu:resize(352, 352)} before it lays a single label out. A list
     * of forty keys does not go in the panel a five button menu goes in, and
     * upstream's answer -- a wider panel with a scroll bar in it -- says so.
     *
     * <p>Taller than the script's 352 because this page pages rather than
     * scrolls: 400 is what puts two of the three sections on the first page
     * instead of one, and it still leaves a margin inside the 480 pixel design
     * height the interface is drawn for.
     */
    private static final int HELP_WIDTH = 352;

    private static final int HELP_HEIGHT = 400;

    /**
     * How much of the screen the panel leaves round itself.
     *
     * <p>The menu is drawn in design pixels and the whole of it is then scaled
     * up, so the room it has is the window divided by the interface scale: at
     * fourfold on a 1920 by 1080 display the panel is being laid out in 480 by
     * 270. A page sized for 640 by 480 and drawn into that is a page with its
     * bottom third off the screen, so the keystroke panel takes what is there
     * rather than what it would like, and the list pages itself to fit.
     */
    private static final int SCREEN_MARGIN = 8;

    /**
     * As small as the keystroke panel is allowed to get.
     *
     * <p>The ordinary panel's own width, because the two columns are laid out
     * to fit that and there is no point pretending anything narrower works;
     * and a height that still holds the heading, a couple of rows and the
     * button at the foot. Below that the screen is too small for any page of
     * this menu, which is not a keystroke problem.
     */
    private static final int HELP_MIN_WIDTH = 256;

    private static final int HELP_MIN_HEIGHT = 176;

    /**
     * The margin down the side of an ordinary page, where its text and its
     * sliders begin. The buttons are centred on the panel instead, which comes
     * to the same thing on a 256 wide one and keeps them centred on the wider
     * keystroke panel.
     */
    private static final int BUTTON_X = 16;

    /** The width of a full-width button. */
    private static final int FULL_WIDTH = 224;

    /** The half-width pair, and the gap between them. */
    private static final int HALF_WIDTH = 106;

    private static final int HALF_GAP = 12;

    /** Where the first row sits and how far apart the rows are. */
    private static final int FIRST_ROW_Y = 40;

    private static final int ROW_PITCH = 36;

    /** {@code addFullButton} and {@code addHalfButton} both call setSize with 28. */
    private static final int BUTTON_HEIGHT = 28;

    /** How far above the foot of the panel the last row of buttons sits. */
    private static final int RETURN_INSET = 40;

    /**
     * What a page shows.
     *
     * @param caption  asked for rather than held, because the keystroke page's
     *                 second button says which page of the list is up and that
     *                 is not known until the panel has been measured against
     *                 the screen it is being drawn on
     * @param x        inset from the left of the button block, which is centred
     *                 in the panel so that a wider panel keeps its buttons the
     *                 width the script gives them
     * @param y        from the top of the panel, or up from the bottom of it
     *                 when {@code fromBottom}
     */
    private record Item(java.util.function.Supplier<String> caption, int x, int y,
            int width, boolean fromBottom, Runnable action) {}

    /**
     * A continuous setting, shown as a groove with a knob in it.
     *
     * <p>A pair of plus and minus buttons for a volume is a fair sign that
     * nobody looked at the thing being adjusted. Loudness is a position along a
     * range, and the control for a position along a range is a slider: it shows
     * where the setting sits without being read, and it is one gesture from any
     * value to any other instead of ten presses.
     *
     * @param value    where the knob is, nought to one
     * @param set      where to put it
     * @param readout  the setting in its own units, for the line above
     */
    private record Slider(String caption, int y,
            java.util.function.DoubleSupplier value,
            java.util.function.DoubleConsumer set,
            java.util.function.Supplier<String> readout) {}

    /** How tall a slider row is: a line of text with the groove beneath it. */
    private static final int SLIDER_HEIGHT = 30;

    /** The groove, and the knob that runs along it. */
    private static final int TRACK_HEIGHT = 10;

    private static final int KNOB_WIDTH = 12;

    /** Which page is up, or null when the menu is closed. */
    private String page;

    /**
     * How to build the page that is up.
     *
     * <p>Kept so that F1 can put the keystroke list over whatever the player
     * was looking at and Escape can put them back where they were. Opening
     * help from the sound page and being returned to the root is the sort of
     * small wrongness that makes a menu feel like it is not listening.
     */
    private Runnable current = this::showRoot;

    private final List<Item> items = new ArrayList<>();

    private final List<Slider> sliders = new ArrayList<>();

    /**
     * Free text above the buttons, for the objectives and the question the
     * end-scenario page asks. The keystroke list is not free text and has a
     * layout of its own.
     */
    private List<String> lines = List.of();

    private String heading = "";

    /** Where each item was last drawn, in screen coordinates. */
    private final List<Rectangle> bounds = new ArrayList<>();

    /** Where each slider's groove was last drawn, likewise. */
    private final List<Rectangle> tracks = new ArrayList<>();

    /**
     * Which slider the pointer is holding, or -1.
     *
     * <p>Held across the drag rather than looked up each time, so running the
     * pointer off the groove while adjusting does not drop the setting. That is
     * how every slider on the machine behaves and the exception would be felt.
     */
    private int grabbed = -1;

    private final BufferedImage panel;

    /** The full and half width button art, which are two separate pieces. */
    private final BufferedImage fullButton;

    private final BufferedImage halfButton;

    private final GameFont font;

    private final GameFont small;

    /** What the menu can ask of the game around it. */
    interface Session {
        void setPaused(boolean paused);

        boolean isPaused();

        /** Cycles a second, which is what the speed control moves. */
        int speed();

        void setSpeed(int cyclesPerSecond);

        /** How much bigger than its design size the interface is drawn. */
        double interfaceScale();

        void setInterfaceScale(double scale);

        /** How much bigger the world itself -- map, units, buildings -- is drawn. */
        double gameScale();

        void setGameScale(double scale);

        /** Whether the wheel changes the zoom. */
        boolean wheelZoom();

        void setWheelZoom(boolean enabled);

        /** Effect and music loudness, nought to one. */
        float effectVolume();

        void setEffectVolume(float volume);

        float musicVolume();

        void setMusicVolume(float volume);

        /**
         * Whether the synthesised soundtrack is playing rather than the
         * recorded one.
         *
         * <p>Warcraft II ships its score twice: eighteen XMI tracks for a
         * synthesiser and the same music recorded on the discs. The recordings
         * used to win whenever there were any, on one line in the launcher with
         * nothing a player could say about it.
         */
        boolean synthesisedMusic();

        void setSynthesisedMusic(boolean synthesised);

        /** Saves, returning what to put in the status line. */
        String save();

        /** Loads the most recent save, returning what to put in the status line. */
        String load();

        /** Gives up the scenario and returns to the main menu. */
        void endScenario();

        /** The mission's objectives, or an empty list on a skirmish map. */
        List<String> objectives();

        /** Whether this is a network game, where one player cannot pause. */
        boolean isNetworked();
    }

    private final Session session;

    GameMenu(GameData data, String race, Session session) {
        this.session = session;
        String side = "orc".equalsIgnoreCase(race) ? "orc" : "human";
        this.panel = load(data, "ui/" + side + "/panel_1");
        // The widget sheet's own pieces, at the sizes addFullButton and
        // addHalfButton ask for. Stretching one graphic to both widths is what
        // it looked like before, and it looked like a stretched graphic.
        this.fullButton = widget(data, "ui/" + side + "/widgets/button-large-normal");
        this.halfButton = widget(data, "ui/" + side + "/widgets/button-small-normal");
        this.font = GameFont.load(data, GameFont.Face.GAME);
        this.small = GameFont.load(data, GameFont.Face.SMALL);
    }

    private static BufferedImage load(GameData data, String path) {
        IndexedImage image = data.image(path);
        Palette palette = image == null ? null : data.paletteFor(path);
        return palette == null ? null : image.toBufferedImage(palette);
    }

    private static BufferedImage widget(GameData data, String path) {
        IndexedImage image = data.widget(path);
        Palette palette = image == null ? null : data.widgetPalette(path);
        return palette == null ? null : image.toBufferedImage(palette);
    }

    boolean isOpen() {
        return page != null;
    }

    /** Which page is up: "root", "options", "help" and so on, or null. */
    String page() {
        return page;
    }

    /**
     * What is written on the buttons of the page that is up.
     *
     * <p>For the test that says Options no longer offers the keystroke list.
     * Asking the page what it offers is the only way to check that from
     * outside; reading it off the drawn pixels would be checking the font.
     */
    List<String> captions() {
        List<String> captions = new ArrayList<>();
        for (Item item : items) {
            captions.add(item.caption().get());
        }
        return captions;
    }

    /**
     * Opens the root page.
     *
     * <p>Pausing here rather than in each caller matches
     * {@code HandleIngameCommandKey}, which pauses before every menu it opens.
     */
    void open() {
        if (!session.isNetworked()) {
            session.setPaused(true);
        }
        showRoot();
    }

    /** Closes the menu and lets the game run on. */
    void close() {
        page = null;
        clearWidgets();
        lines = List.of();
        if (!session.isNetworked()) {
            session.setPaused(false);
        }
    }

    /** Empties both widget lists, which every page rebuild starts with. */
    private void clearWidgets() {
        items.clear();
        sliders.clear();
        grabbed = -1;
    }

    private void addSlider(String caption, int row,
            java.util.function.DoubleSupplier value,
            java.util.function.DoubleConsumer set,
            java.util.function.Supplier<String> readout) {
        sliders.add(new Slider(caption, FIRST_ROW_Y + ROW_PITCH * row, value, set, readout));
    }

    private void add(String caption, int row, Runnable action) {
        items.add(new Item(() -> caption, 0, FIRST_ROW_Y + ROW_PITCH * row,
                FULL_WIDTH, false, action));
    }

    private void addPair(String left, Runnable leftAction, String right, Runnable rightAction) {
        items.add(new Item(() -> left, 0, FIRST_ROW_Y, HALF_WIDTH, false, leftAction));
        items.add(new Item(() -> right, HALF_WIDTH + HALF_GAP, FIRST_ROW_Y,
                HALF_WIDTH, false, rightAction));
    }

    private void addReturn(String caption, Runnable action) {
        items.add(new Item(() -> caption, 0, RETURN_INSET, FULL_WIDTH, true, action));
    }

    private void showRoot() {
        page = "root";
        current = this::showRoot;
        heading = "Game Menu";
        lines = List.of();
        clearWidgets();
        addPair("Save (F11)", session::save, "Load (F12)", session::load);
        add("Options (F5)", 1, this::showOptions);
        add("Help (F1)", 2, () -> showHelp(this::showRoot));
        add("Scenario Objectives", 3, this::showObjectives);
        add("End Scenario", 4, this::showEndScenario);
        addReturn("Return to Game (Esc)", this::close);
    }


    /**
     * The settings pages.
     *
     * <p>No keystroke entry here. The list used to be on this page as well as
     * behind Help on the root, which meant the same page was reachable by two
     * names -- and the name it had here, "Keystrokes (F1)", advertised a
     * shortcut that belongs to Help. One thing, one place: the list is what
     * Help is, and Options is where settings are changed. F1 still opens it
     * from anywhere, which is the part of the old entry that was worth
     * keeping.
     */
    private void showOptions() {
        page = "options";
        current = this::showOptions;
        heading = "Options";
        clearWidgets();
        lines = List.of();
        add("Game Speed", 0, this::showSpeed);
        add("Scale", 1, this::showScale);
        add("Sound", 2, this::showSound);
        addReturn("Previous (Esc)", this::showRoot);
    }

    private void showSpeed() {
        page = "speed";
        current = this::showSpeed;
        heading = "Game Speed";
        clearWidgets();
        lines = List.of();
        addSlider("Speed", 0,
                () -> (session.speed() - SLOWEST) / (double) (FASTEST - SLOWEST),
                fraction -> session.setSpeed(
                        (int) Math.round(SLOWEST + fraction * (FASTEST - SLOWEST))),
                () -> session.speed() + " ticks");
        add("Normal", 1, () -> {
            session.setSpeed(net.chonkbase.chonkcraft.engine.World.CYCLES_PER_SECOND);
            showSpeed();
        });
        addReturn("Previous (Esc)", this::showOptions);
    }

    /** The ends of the speed slider's travel, in cycles a second. */
    private static final int SLOWEST = 5;

    private static final int FASTEST = 60;

    /** The ends of both scale sliders' travel. */
    private static final int SMALLEST = 1;

    private static final int LARGEST = 4;

    /**
     * How large to draw things.
     *
     * <p>Two settings, because they answer different questions. The interface
     * scale is about whether the lettering can be read; the game scale is about
     * how much of the battle is on the screen at once. A player who wants a
     * large readable sidebar does not necessarily want to be looking at a
     * quarter of the map.
     *
     * <p>Whole steps for both: a fractional scale puts the pixel grid out of
     * register, and the lettering goes ragged and the sprites go soft.
     */
    private void showScale() {
        page = "scale";
        current = this::showScale;
        heading = "Scale";
        clearWidgets();
        lines = List.of();
        addSlider("Interface", 0,
                () -> (session.interfaceScale() - SMALLEST) / (double) (LARGEST - SMALLEST),
                fraction -> session.setInterfaceScale(
                        Math.round(SMALLEST + fraction * (LARGEST - SMALLEST))),
                () -> times(session.interfaceScale()));
        addSlider("Game", 1,
                () -> (session.gameScale() - SMALLEST) / (double) (LARGEST - SMALLEST),
                fraction -> session.setGameScale(
                        Math.round(SMALLEST + fraction * (LARGEST - SMALLEST))),
                () -> times(session.gameScale()));
        add(wheelCaption(), 2, () -> {
            session.setWheelZoom(!session.wheelZoom());
            showScale();
        });
        addReturn("Previous (Esc)", this::showOptions);
    }

    private String wheelCaption() {
        return session.wheelZoom() ? "Wheel Zoom: On" : "Wheel Zoom: Locked";
    }

    private static String times(double scale) {
        return Math.round(scale) + "x";
    }

    /**
     * The two volumes, and which recording of the score they act on.
     *
     * <p>The third row is the setting Warcraft II never needed. Its score is
     * on the discs as red book audio and in {@code snddat.war} as eighteen XMI
     * tracks, and until now the discs won whenever there were any. The two do
     * not sound alike -- one is a recorded orchestra and the other is whatever
     * synthesiser the machine has -- and which of them a player prefers is not
     * a question this implementation can answer for them.
     *
     * <p>Row two rather than a page of its own: the page had rows two and three
     * free and the toggle-caption shape is already used three lines away by the
     * wheel zoom on the scale page, so this needs no redesign to fit.
     */
    private void showSound() {
        page = "sound";
        current = this::showSound;
        heading = "Sound";
        clearWidgets();
        lines = List.of();
        addSlider("Effects", 0, session::effectVolume,
                volume -> session.setEffectVolume((float) volume),
                () -> percent(session.effectVolume()));
        addSlider("Music", 1, session::musicVolume,
                volume -> session.setMusicVolume((float) volume),
                () -> percent(session.musicVolume()));
        add(musicSourceCaption(), 2, () -> {
            session.setSynthesisedMusic(!session.synthesisedMusic());
            showSound();
        });
        addReturn("Previous (Esc)", this::showOptions);
    }

    /**
     * Which soundtrack the button offers, read back from the game rather than
     * from what was last pressed.
     *
     * <p>An installation with no discs cannot play the recordings and one with
     * no {@code snddat.war} cannot play the XMI, so the caption says what is
     * actually happening: pressing it on a machine that has only one of the two
     * leaves the caption where it was, which is the honest answer.
     */
    private String musicSourceCaption() {
        return session.synthesisedMusic() ? "Music: Synthesised" : "Music: CD Audio";
    }

    private static String percent(float volume) {
        return Math.round(Math.max(0f, Math.min(1f, volume)) * 100) + "%";
    }

    /**
     * One key and what it does.
     *
     * @param keys        the caps to draw, written the way a keyboard shortcut
     *                    is written: alternatives divided by {@code " / "} and
     *                    a modifier from the key it modifies by {@code " + "}.
     *                    Held as one string rather than a list so that the
     *                    table below reads as the list a player sees, and
     *                    parsed by {@link #capsOf} in one place.
     * @param description what the key does, in the fewest words that say it
     */
    record Keystroke(String keys, String description) {}

    /** A heading and the keys under it. */
    record Section(String title, List<Keystroke> keys) {}

    /**
     * The keystroke list.
     *
     * <p>Trimmed from the {@code keystrokes} table in {@code menus/help.legacy-declaration} to
     * the keys this implementation actually answers, and grouped. Upstream's list is
     * forty rows in the order they were thought of -- full screen next to mouse
     * grab next to mute next to game speed -- which is a list to search rather
     * than a list to read. Three sections instead: what the game is doing, what
     * is selected, and where the camera is looking. A player who wants to know
     * how to find an idle worker knows before reading a word that it is not
     * under Game.
     *
     * <p>Listing a key the implementation does not answer would be worse than listing
     * none, so {@code GameMenuTest} checks this table against the switch in
     * {@code GameScreen.keyPressed} in both directions. That is the failure
     * that matters here: help does not stop being true loudly.
     */
    static final List<Section> KEYSTROKES = List.of(
            new Section("Game", List.of(
                    // All three open it. Two rows reading "game menu" looked
                    // like the duplicate this redesign existed to remove.
                    new Keystroke("F10 / Backspace / Alt + M", "game menu"),
                    new Keystroke("F5", "options"),
                    new Keystroke("F6 / F7 / F8", "speed / sound / scale"),
                    new Keystroke("F1 / Alt + H", "this list"),
                    new Keystroke("F11 / F12", "save / load"),
                    new Keystroke("Alt + S / Alt + L", "save / load"),
                    new Keystroke("Alt + Q / Alt + X", "end the scenario"),
                    new Keystroke("Ctrl + P / Pause", "pause"),
                    new Keystroke("+ / -", "faster / slower"),
                    new Keystroke("Ctrl + S / Ctrl + M", "mute sound / music"),
                    new Keystroke("Print", "screenshot"),
                    new Keystroke("Ctrl + Shift + E", "playtest evidence (Command works on Mac)"),
                    new Keystroke("Esc", "cancel / close"))),
            new Section("Selection", List.of(
                    new Keystroke("0-9", "select group"),
                    new Keystroke("Ctrl + 0-9", "define group"),
                    new Keystroke("Shift + 0-9", "add to group"),
                    new Keystroke("Alt + I / .", "find idle worker"),
                    new Keystroke("^", "select nothing"))),
            new Section("View", List.of(
                    new Keystroke("Arrows", "scroll the map"),
                    new Keystroke("Alt + C", "centre on selection"),
                    new Keystroke("Ctrl + T", "follow the selected unit"),
                    new Keystroke("Tab", "hide minimap terrain"),
                    new Keystroke("Space", "go to last event"),
                    new Keystroke("F2-F4", "recall map position"),
                    new Keystroke("Shift + F2-F4", "save map position"))));

    /** A cap and the separator drawn before it; the first cap has none. */
    record CapText(String join, String label) {}

    /**
     * Splits a key group into the caps it draws as.
     *
     * <p>The one place the notation in {@link #KEYSTROKES} is understood, so
     * the layout, the drawing and the test that checks the list against the
     * game's key handling all agree about what {@code "Ctrl + P / Pause"}
     * means: two caps joined by a plus, then a third alternative.
     *
     * <p>Split on the spaced separators rather than the bare characters, which
     * is what lets {@code "+ / -"} name the two keys that change the game speed
     * without the plus being read as a join.
     */
    static List<CapText> capsOf(String keys) {
        List<CapText> caps = new ArrayList<>();
        for (String alternative : keys.split(" / ")) {
            String join = caps.isEmpty() ? "" : "/";
            for (String part : alternative.split(" \\+ ")) {
                caps.add(new CapText(join, part));
                join = "+";
            }
        }
        return caps;
    }

    /**
     * The keystroke page, which is reached from the root and from F1.
     *
     * <p>Told where it came from rather than assuming. It used to return to the
     * root whichever way it was entered, so opening it from Options dropped a
     * level on the way out -- a small thing, and the sort of small thing that
     * makes a menu feel like it is not listening.
     */
    private void showHelp(Runnable back) {
        page = "help";
        current = () -> showHelp(back);
        heading = "Keystrokes";
        clearWidgets();
        lines = List.of();
        helpPage = 0;
        helpBack = back;
        // One page until the panel has been measured against the screen; the
        // draw fixes this the moment it knows how much room there is.
        buildHelpButtons(1);
    }

    /** Where the keystroke page returns to, and which page of it is up. */
    private Runnable helpBack = this::showRoot;

    private int helpPage;

    /** How many pages the last draw found the list needed. */
    private int helpPages = 1;

    /** What the buttons at the foot of the keystroke page were built for. */
    private int helpButtonPages;

    /**
     * Puts the right buttons at the foot of the keystroke page.
     *
     * <p>A list that fits gets the one button every other page has. A list that
     * does not gets a second beside it, captioned with where in the list the
     * reader is, because a button that says "Next" and nothing else leaves them
     * to guess whether there is a third page.
     */
    private void buildHelpButtons(int pageCount) {
        items.clear();
        helpButtonPages = pageCount;
        if (pageCount <= 1) {
            addReturn("Previous (Esc)", helpBack);
            return;
        }
        items.add(new Item(() -> "Previous (Esc)", 0, RETURN_INSET, HALF_WIDTH, true, helpBack));
        items.add(new Item(
                () -> "More (" + (helpPage + 1) + "/" + helpPages + ")",
                HALF_WIDTH + HALF_GAP, RETURN_INSET, HALF_WIDTH, true, this::nextHelpPage));
    }

    /** Turns to the next page of the list, and round to the first from the last. */
    private void nextHelpPage() {
        helpPage = helpPages <= 0 ? 0 : (helpPage + 1) % helpPages;
    }

    private void showObjectives() {
        page = "objectives";
        current = this::showObjectives;
        heading = "Objectives";
        clearWidgets();
        List<String> objectives = session.objectives();
        lines = objectives.isEmpty()
                ? List.of("Defeat your enemies.")
                : objectives;
        addReturn("Previous (Esc)", this::showRoot);
    }

    /**
     * Opens the menu straight at the give-up confirmation.
     *
     * <p>For Alt-Q and Alt-X, which upstream sends to
     * {@code RunQuitToMenuConfirmMenu} and {@code RunExitConfirmMenu}. This
     * port has one confirmation rather than two, and the keys share it.
     */
    void openEndScenario() {
        open();
        showEndScenario();
    }

    private void showEndScenario() {
        page = "end";
        current = this::showEndScenario;
        heading = "End Scenario";
        clearWidgets();
        lines = List.of("Give up and return", "to the main menu?");
        // Rows one and two, leaving the first row's worth of height for the
        // question. The answers go under what they answer.
        add("Yes", 1, () -> {
            close();
            session.endScenario();
        });
        add("No", 2, this::showRoot);
        addReturn("Previous (Esc)", this::showRoot);
    }

    /** How much air goes between lines of body text. */
    private static final int LINE_GAP = 2;

    /** A face, its line spacing, and the text wrapped for that exact face. */
    record BodyLayout(GameFont face, int step, List<String> lines) {}

    /**
     * The largest face that fits this many lines in this much room.
     *
     * <p>Tried in order: the reading face with air between the lines, the
     * small face with air, and the small face set solid. When even that does
     * not fit, the spacing is tightened to whatever does rather than the text
     * being allowed to run off the panel -- which is what happened to the
     * keystroke list before it was given a layout of its own: its last entry
     * was drawn underneath the button at the foot of the page and could not be
     * read. A page that quietly loses the line at the bottom of it is worse
     * than no page, and the version of this that claimed the solid setting
     * fitted exactly had never been looked at.
     */
    BodyLayout layoutBody(List<String> source, int width, int room) {
        if (source.isEmpty()) {
            return new BodyLayout(font, 0, List.of());
        }
        for (BodyLayout candidate : new BodyLayout[] {
                bodyCandidate(source, font, width,
                        font == null ? 0 : font.height() + LINE_GAP),
                bodyCandidate(source, small, width,
                        small == null ? 0 : small.height() + LINE_GAP),
                bodyCandidate(source, small, width,
                        small == null ? 0 : small.height())}) {
            if (candidate.face() != null
                    && candidate.lines().size() * candidate.step() <= room) {
                return candidate;
            }
        }
        GameFont last = small != null ? small : font;
        if (last == null) {
            return new BodyLayout(null, 0, List.of());
        }
        List<String> wrapped = wrapBody(source, last, width);
        return new BodyLayout(last,
                wrapped.isEmpty() ? 0 : Math.max(1, room / wrapped.size()), wrapped);
    }

    private static BodyLayout bodyCandidate(List<String> source, GameFont face,
            int width, int step) {
        return new BodyLayout(face, step,
                face == null ? List.of() : wrapBody(source, face, width));
    }

    /**
     * Wraps menu prose into BNE's 224-pixel content column.
     *
     * <p>The retail menu gives free text the same inset and width as its
     * controls.  The old renderer honoured only the vertical boundary, so a
     * perfectly valid objective from the retail text table could be painted
     * through the right edge of the modal.  Wrapping is measured in the
     * bitmap face that will actually draw the line; character counts are not
     * widths in this proportional font.
     */
    private static List<String> wrapBody(List<String> source, GameFont face, int width) {
        List<String> wrapped = new ArrayList<>();
        for (String logical : source) {
            if (logical == null || logical.isBlank()) {
                wrapped.add("");
                continue;
            }
            String line = "";
            for (String originalWord : logical.trim().split("\\s+")) {
                String word = originalWord;
                String candidate = line.isEmpty() ? word : line + " " + word;
                if (face.widthOf(candidate) <= width) {
                    line = candidate;
                    continue;
                }
                if (!line.isEmpty()) {
                    wrapped.add(line);
                    line = "";
                }
                while (!word.isEmpty() && face.widthOf(word) > width) {
                    int fit = 1;
                    while (fit < word.length()
                            && face.widthOf(word.substring(0, fit + 1)) <= width) {
                        fit++;
                    }
                    wrapped.add(word.substring(0, fit));
                    word = word.substring(fit);
                }
                line = word;
            }
            if (!line.isEmpty()) {
                wrapped.add(line);
            }
        }
        return List.copyOf(wrapped);
    }

    /** The margin the body keeps inside the panel, left and right. */
    private static final int BODY_MARGIN = 16;

    /** The top of the body, clear of the heading that sits at y 11. */
    private static final int BODY_TOP = 34;

    /** Air between the foot of the body and the buttons below it. */
    private static final int BODY_FOOT_GAP = 8;

    /** How much wider and taller a key cap is than the label inside it. */
    private static final int CAP_PAD_X = 5;

    private static final int CAP_PAD_Y = 1;

    /** Air either side of the "+" or "/" between two caps. */
    private static final int JOIN_PAD = 3;

    /** Between the key column and the description column. */
    private static final int COLUMN_GAP = 12;

    /** Between one key and the next. */
    private static final int ROW_GAP = 3;

    /** Between a section's rule and the first key under it. */
    private static final int RULE_GAP = 4;

    /** Extra air above a section heading that is not first on its page. */
    private static final int SECTION_LEAD = 7;

    /** As little as the description column is ever given. */
    private static final int MIN_DESCRIPTION = 40;

    /** A key cap as laid out: where the box goes, in panel coordinates. */
    record Cap(String join, String label, Rectangle box) {}

    /**
     * One laid-out line of the keystroke page.
     *
     * <p>A heading when {@code heading} is set and a key otherwise, rather than
     * two lists, because the thing the layout has to get right is the order and
     * the spacing of the two together.
     *
     * @param extent what the line actually marks, so that a caller -- the test,
     *               chiefly -- can ask whether the page fits without measuring
     *               strings itself
     */
    record HelpLine(String heading, String description, List<Cap> caps, int y,
            int height, Rectangle extent) {}

    /**
     * The keystroke page laid out for a panel of a given size.
     *
     * @param descriptionX where every description begins, on every page: one
     *                     number, so the second column is a column
     * @param tallest      the height of the fullest page, which is how tall the
     *                     panel needs to be
     */
    record HelpLayout(int page, int pages, List<HelpLine> lines, int descriptionX,
            GameFont descriptionFont, Rectangle body, int tallest) {}

    /**
     * Lays the keystroke list out.
     *
     * <p>Two columns at fixed positions, not one string with spaces in it. The
     * list used to be built by padding a key name out to sixteen characters and
     * drawing the result as a single line, which lines up in a text editor and
     * in nothing else: the face is proportional, so "F10, Backspace" and "^"
     * padded to the same character count are nowhere near the same width, and
     * the descriptions staggered across half the panel. Here the key column is
     * measured -- the widest key group in the whole table, cap borders and
     * separators included -- and every description starts at the far side of it.
     * That holds at any size the face is asked for, and needs no hand-tuned
     * spaces.
     *
     * <p>The width is measured over the whole table rather than over the page
     * being shown, so the column does not jump when the reader turns a page.
     *
     * <p>Pages rather than scrolls, because the room is whatever the screen has
     * left after the interface scale has taken its share, and a list that
     * cannot be scrolled and does not fit is a list with keys missing off the
     * bottom of it. Headings are never left stranded at the foot of a page, and
     * a section split across a break carries its heading onto the next one.
     *
     * @param wanted which page to return, clamped to what there is
     */
    HelpLayout layOutHelp(int panelW, int panelH, int wanted) {
        Rectangle body = new Rectangle(BODY_MARGIN, BODY_TOP,
                Math.max(1, panelW - 2 * BODY_MARGIN),
                Math.max(1, panelH - RETURN_INSET - BODY_FOOT_GAP - BODY_TOP));
        if (font == null || small == null) {
            return new HelpLayout(0, 1, List.of(), body.x, font, body, 0);
        }

        int capHeight = small.height() + 2 * CAP_PAD_Y;
        int keyColumn = 0;
        int longest = 0;
        for (Section section : KEYSTROKES) {
            for (Keystroke key : section.keys()) {
                keyColumn = Math.max(keyColumn, keyExtent(key.keys(), capHeight));
                longest = Math.max(longest, font.widthOf(key.description()));
            }
        }
        // A panel too narrow for both columns gives the description what is
        // left rather than letting the key column push it off the edge.
        keyColumn = Math.min(keyColumn, Math.max(1, body.width - COLUMN_GAP - MIN_DESCRIPTION));
        int descriptionX = body.x + keyColumn + COLUMN_GAP;
        int room = body.x + body.width - descriptionX;

        // The reading face where it fits, the small one where it does not.
        // Descriptions are the part being read; they lose size last.
        GameFont descriptionFont = longest <= room ? font : small;
        int rowHeight = Math.max(capHeight, descriptionFont.height());
        int headingBlock = font.height() + 3 + RULE_GAP;

        // Where the reader would rather the breaks fell, worked out before a
        // line is placed; the placing below still breaks wherever it has to.
        java.util.Set<Integer> wantedBreaks = pageBreaks(rowHeight, headingBlock, body.height);

        List<List<HelpLine>> pages = new ArrayList<>();
        List<HelpLine> lines = new ArrayList<>();
        int y = 0;
        for (int index = 0; index < KEYSTROKES.size(); index++) {
            Section section = KEYSTROKES.get(index);
            boolean headed = false;
            for (Keystroke key : section.keys()) {
                int lead = lines.isEmpty() ? 0 : SECTION_LEAD;
                int needed = (headed ? 0 : lead + headingBlock) + rowHeight;
                boolean turn = y + needed > body.height
                        || (!headed && wantedBreaks.contains(index));
                if (turn && !lines.isEmpty()) {
                    pages.add(lines);
                    lines = new ArrayList<>();
                    y = 0;
                    headed = false;
                }
                if (!headed) {
                    int headingY = y + (lines.isEmpty() ? 0 : SECTION_LEAD);
                    lines.add(new HelpLine(section.title(), null, List.of(), headingY,
                            font.height(),
                            new Rectangle(body.x, headingY, body.width, font.height())));
                    y = headingY + headingBlock;
                    headed = true;
                }
                String shown = elide(descriptionFont, key.description(), room);
                List<Cap> caps = layOutCaps(key.keys(), body.x, y, rowHeight, capHeight);
                int right = descriptionX + descriptionFont.widthOf(shown);
                lines.add(new HelpLine(null, shown, caps, y, rowHeight,
                        new Rectangle(body.x, y, right - body.x, rowHeight)));
                y += rowHeight + ROW_GAP;
            }
        }
        if (!lines.isEmpty()) {
            pages.add(lines);
        }
        if (pages.isEmpty()) {
            pages.add(List.of());
        }

        // Each page is centred in the body rather than hung from the top of
        // it. A short page that starts at the top has all its air in one lump
        // at the bottom, which reads as a page that ran out rather than a page
        // that ended.
        int tallest = 0;
        for (int index = 0; index < pages.size(); index++) {
            int height = heightOf(pages.get(index));
            tallest = Math.max(tallest, height);
            pages.set(index, shifted(pages.get(index),
                    body.y + Math.max(0, (body.height - height) / 2)));
        }
        int shown = Math.max(0, Math.min(wanted, pages.size() - 1));
        return new HelpLayout(shown, pages.size(), pages.get(shown), descriptionX,
                descriptionFont, body, tallest);
    }

    /** How tall a page's lines come to, measured from the first line's top. */
    private static int heightOf(List<HelpLine> lines) {
        int bottom = 0;
        for (HelpLine line : lines) {
            bottom = Math.max(bottom, line.y() + line.height());
        }
        return bottom;
    }

    /** The same lines, moved down the panel. */
    private static List<HelpLine> shifted(List<HelpLine> lines, int by) {
        List<HelpLine> moved = new ArrayList<>(lines.size());
        for (HelpLine line : lines) {
            List<Cap> caps = new ArrayList<>(line.caps().size());
            for (Cap cap : line.caps()) {
                Rectangle box = cap.box();
                caps.add(new Cap(cap.join(), cap.label(),
                        new Rectangle(box.x, box.y + by, box.width, box.height)));
            }
            Rectangle extent = line.extent();
            moved.add(new HelpLine(line.heading(), line.description(), caps,
                    line.y() + by, line.height(),
                    new Rectangle(extent.x, extent.y + by, extent.width, extent.height)));
        }
        return moved;
    }

    /**
     * Which sections should start a page.
     *
     * <p>The obvious way to break a list into pages is to fill each one until
     * it will not take another line, and it is the wrong way here: the list
     * comes to thirteen rows and then five, so the reader turns from a full
     * page to a nearly empty one and wonders what went wrong. This tries every
     * way of dividing the list at its section boundaries, takes the fewest
     * pages, and among those the one whose fullest page is emptiest -- which
     * is the arrangement that looks as though somebody chose it.
     *
     * <p>Only whole sections, and only when every page of the arrangement fits.
     * When a single section is taller than the panel -- a small screen at a
     * large interface scale -- there is no such arrangement and this says
     * nothing, leaving the placing to break the section wherever it must.
     */
    private java.util.Set<Integer> pageBreaks(int rowHeight, int headingBlock, int room) {
        int count = KEYSTROKES.size();
        int[] heights = new int[count];
        for (int i = 0; i < count; i++) {
            int keys = KEYSTROKES.get(i).keys().size();
            heights[i] = headingBlock + keys * rowHeight + Math.max(0, keys - 1) * ROW_GAP;
            if (heights[i] > room) {
                return java.util.Set.of();
            }
        }
        // Between two sections on one page: the gap after the last key of the
        // one above, and the air a heading takes above it.
        int between = ROW_GAP + SECTION_LEAD;

        java.util.Set<Integer> best = null;
        int bestPages = Integer.MAX_VALUE;
        int bestTallest = Integer.MAX_VALUE;
        for (int mask = 0; mask < (1 << Math.max(0, count - 1)); mask++) {
            java.util.Set<Integer> breaks = new java.util.TreeSet<>();
            for (int i = 1; i < count; i++) {
                if ((mask & (1 << (i - 1))) != 0) {
                    breaks.add(i);
                }
            }
            int pages = 1;
            int height = heights[0];
            int tallest = heights[0];
            boolean fits = true;
            for (int i = 1; i < count; i++) {
                if (breaks.contains(i)) {
                    pages++;
                    height = heights[i];
                } else {
                    height += between + heights[i];
                }
                if (height > room) {
                    fits = false;
                    break;
                }
                tallest = Math.max(tallest, height);
            }
            if (!fits) {
                continue;
            }
            if (pages < bestPages || (pages == bestPages && tallest < bestTallest)) {
                best = breaks;
                bestPages = pages;
                bestTallest = tallest;
            }
        }
        return best == null ? java.util.Set.of() : best;
    }

    /** Where each cap of one key group sits, and how wide the group is. */
    private List<Cap> layOutCaps(String keys, int left, int rowY, int rowHeight, int capHeight) {
        List<Cap> caps = new ArrayList<>();
        int x = left;
        int top = rowY + (rowHeight - capHeight) / 2;
        for (CapText cap : capsOf(keys)) {
            if (!cap.join().isEmpty()) {
                x += small.widthOf(cap.join()) + 2 * JOIN_PAD;
            }
            int width = capWidth(cap.label(), capHeight);
            caps.add(new Cap(cap.join(), cap.label(),
                    new Rectangle(x, top, width, capHeight)));
            x += width;
        }
        return caps;
    }

    /** How wide a whole key group draws, caps and separators together. */
    private int keyExtent(String keys, int capHeight) {
        int width = 0;
        for (CapText cap : capsOf(keys)) {
            if (!cap.join().isEmpty()) {
                width += small.widthOf(cap.join()) + 2 * JOIN_PAD;
            }
            width += capWidth(cap.label(), capHeight);
        }
        return width;
    }

    /**
     * How wide one cap is.
     *
     * <p>Never narrower than it is tall, so that a lone character -- "^" -- is
     * a square key rather than a sliver, which is what it is on the keyboard.
     */
    private int capWidth(String label, int capHeight) {
        return Math.max(capHeight, small.widthOf(label) + 2 * CAP_PAD_X);
    }

    /**
     * Cuts a description to the room there is.
     *
     * <p>A last resort for a panel narrow enough that even the small face
     * overruns it. Text that stops with an ellipsis is text the reader knows is
     * cut; text drawn over the edge of the panel is a bug.
     */
    private static String elide(GameFont face, String text, int room) {
        if (face.widthOf(text) <= room) {
            return text;
        }
        for (int cut = text.length() - 1; cut > 0; cut--) {
            String candidate = text.substring(0, cut) + "...";
            if (face.widthOf(candidate) <= room) {
                return candidate;
            }
        }
        return "...";
    }

    /**
     * How big the panel is on a screen of a given size.
     *
     * <p>Fixed at the script's size for every page but the keystroke list. That
     * one takes as much room as the screen has to offer, up to the size it
     * wants, and then gives back whatever the list did not need: a panel with a
     * hand's breadth of empty stone under the last key is a panel that was
     * measured against nothing. The screen here is in design pixels, so it
     * shrinks as the interface scale grows.
     *
     * <p>Cached, because the trimming means measuring the whole list and the
     * size is asked for several times in the course of one frame.
     */
    private java.awt.Dimension panelSize(int screenWidth, int screenHeight) {
        if (!"help".equals(page)) {
            return new java.awt.Dimension(WIDTH, HEIGHT);
        }
        String key = screenWidth + "x" + screenHeight;
        if (key.equals(sizedFor) && panelSize != null) {
            return panelSize;
        }
        int width = Math.max(HELP_MIN_WIDTH,
                Math.min(HELP_WIDTH, screenWidth - 2 * SCREEN_MARGIN));
        int most = Math.max(HELP_MIN_HEIGHT,
                Math.min(HELP_HEIGHT, screenHeight - 2 * SCREEN_MARGIN));
        int wanted = layOutHelp(width, most, 0).tallest()
                + BODY_TOP + BODY_FOOT_GAP + RETURN_INSET;
        panelSize = new java.awt.Dimension(width,
                Math.max(HELP_MIN_HEIGHT, Math.min(most, wanted)));
        sizedFor = key;
        return panelSize;
    }

    /** The last panel size worked out, and the screen it was worked out for. */
    private java.awt.Dimension panelSize;

    private String sizedFor;

    int panelWidth(int screenWidth, int screenHeight) {
        return panelSize(screenWidth, screenHeight).width;
    }

    int panelHeight(int screenWidth, int screenHeight) {
        return panelSize(screenWidth, screenHeight).height;
    }

    /** Where the panel sits on a screen of a given size. */
    private int originX(int screenWidth, int screenHeight) {
        return (screenWidth - panelWidth(screenWidth, screenHeight)) / 2;
    }

    private int originY(int screenWidth, int screenHeight) {
        return (screenHeight - panelHeight(screenWidth, screenHeight)) / 2;
    }

    /** Handles a click; returns whether the menu took it. */
    boolean click(int x, int y) {
        if (page == null) {
            return false;
        }
        // Sliders first, and with room above and below the groove: a ten pixel
        // bar is a small thing to hit, and a press that lands a pixel outside
        // it should still take hold rather than do nothing.
        for (int i = 0; i < tracks.size() && i < sliders.size(); i++) {
            Rectangle track = tracks.get(i);
            if (x >= track.x - KNOB_WIDTH && x <= track.x + track.width + KNOB_WIDTH
                    && y >= track.y - 8 && y <= track.y + track.height + 8) {
                grabbed = i;
                sliders.get(i).set().accept(fractionAt(track, x));
                return true;
            }
        }
        for (int i = 0; i < bounds.size() && i < items.size(); i++) {
            if (bounds.get(i).contains(x, y)) {
                items.get(i).action().run();
                return true;
            }
        }
        // A click anywhere on the panel is swallowed, so the menu never lets an
        // order through to the map underneath it.
        return true;
    }

    /**
     * Continues a slider drag.
     *
     * @return whether anything moved, so the caller knows to redraw
     */
    boolean drag(int x, int y) {
        if (page == null || grabbed < 0 || grabbed >= sliders.size()
                || grabbed >= tracks.size()) {
            return false;
        }
        sliders.get(grabbed).set().accept(fractionAt(tracks.get(grabbed), x));
        return true;
    }

    /** Lets go of whatever slider was being dragged. */
    void release() {
        grabbed = -1;
    }

    /**
     * Where along a groove a pointer is, nought to one.
     *
     * <p>The travel is inset by half a knob at each end so the knob stays
     * inside the groove it runs in; without that, both extremes hang over the
     * edge and neither end quite reaches.
     */
    private static double fractionAt(Rectangle track, int x) {
        int travel = track.width - KNOB_WIDTH;
        if (travel <= 0) {
            return 0;
        }
        double fraction = (x - track.x - KNOB_WIDTH / 2.0) / travel;
        return Math.max(0, Math.min(1, fraction));
    }

    /**
     * Handles a key; returns whether the menu took it.
     *
     * <p>Escape steps back a page rather than closing outright, which is what
     * the script's "Previous" button does on every page but the first.
     */
    boolean key(int keyCode) {
        if (page == null) {
            return false;
        }
        if (keyCode == java.awt.event.KeyEvent.VK_ESCAPE) {
            if ("root".equals(page)) {
                close();
            } else if ("help".equals(page)) {
                // Back where the list was opened from, which F1 makes
                // anywhere: a reader sent to the root for having glanced at
                // the keys has lost their place.
                helpBack.run();
            } else {
                showRoot();
            }
            return true;
        }
        // Space and Tab turn the page of a list that has more than one, which
        // is what a reader reaches for before finding the button.
        if ("help".equals(page)
                && (keyCode == java.awt.event.KeyEvent.VK_SPACE
                        || keyCode == java.awt.event.KeyEvent.VK_TAB)) {
            nextHelpPage();
            return true;
        }
        switch (keyCode) {
            case java.awt.event.KeyEvent.VK_F11 -> session.save();
            case java.awt.event.KeyEvent.VK_F12 -> session.load();
            case java.awt.event.KeyEvent.VK_F5 -> showOptions();
            case java.awt.event.KeyEvent.VK_F6 -> showSpeed();
            // commands.legacy-declaration:11-17 sends F7 to the sound page and F8 to the
            // preferences one. This implementation's preferences are the scale page.
            case java.awt.event.KeyEvent.VK_F7 -> showSound();
            case java.awt.event.KeyEvent.VK_F8 -> showScale();
            // F1 lays the list over whatever page is up and comes back to it,
            // rather than dropping the reader at the root for having asked.
            case java.awt.event.KeyEvent.VK_F1 -> {
                if (!"help".equals(page)) {
                    showHelp(current);
                }
            }
            default -> { }
        }
        return true;
    }

    /** Draws the menu over the game. */
    void draw(Graphics2D g2, int screenWidth, int screenHeight) {
        if (page == null) {
            return;
        }
        int panelW = panelWidth(screenWidth, screenHeight);
        int panelH = panelHeight(screenWidth, screenHeight);
        int ox = originX(screenWidth, screenHeight);
        int oy = originY(screenWidth, screenHeight);

        // The game is still visible behind the panel, dimmed, the way
        // setDrawMenusUnder leaves it.
        g2.setColor(new Color(0, 0, 0, 120));
        g2.fillRect(0, 0, screenWidth, screenHeight);

        // Generated at the panel's own size rather than blitted from a 176
        // pixel photograph, so it is as sharp at three times the size as at
        // one and has no tile to repeat.
        PanelArt.panel(g2, ox, oy, panelW, panelH, StoneTexture.Tint.STONE);

        if (font != null) {
            font.drawCentred(g2, heading, ox + panelW / 2, oy + 11, GameFont.Ink.YELLOW);
        }

        if ("help".equals(page)) {
            HelpLayout laid = layOutHelp(panelW, panelH, helpPage);
            helpPages = laid.pages();
            helpPage = laid.page();
            // The buttons cannot be built until the list has been measured
            // against the screen, because how many there are depends on how
            // many pages the list came to.
            if (helpButtonPages != laid.pages()) {
                buildHelpButtons(laid.pages());
            }
            drawHelp(g2, ox, oy, laid);
        }

        // Free text: the objectives, and the question the end-scenario page
        // asks. The reading font, not the small one, because these are to be
        // read rather than glanced at; the small face only when the reading
        // one will not fit in the room there is, rather than letting the text
        // run off the panel and under the button at the bottom of it.
        //
        // Under the heading, above the controls. A page that asks a question
        // and offers Yes and No has to ask it before the answers: this used to
        // put the body below the lowest button, so "Give up and return to the
        // main menu?" appeared underneath the Yes and No it was asking about.
        //
        // The rule it replaces was written for a sound page that printed its
        // levels across its own controls. That page has sliders now and no
        // body text at all, so the rule was solving a problem that had
        // stopped existing and causing one that had not.
        //
        // The room is whatever lies between the heading and the topmost
        // control, so a long page still cannot run into anything.
        int bodyTop = oy + 32;
        int firstControl = oy + panelH - RETURN_INSET;
        for (Item item : items) {
            // The return button sits at the foot of the panel; the body may
            // run down to it.
            if (!item.fromBottom()) {
                firstControl = Math.min(firstControl, oy + item.y());
            }
        }
        for (Slider slider : sliders) {
            firstControl = Math.min(firstControl, oy + slider.y());
        }
        int bodyRoom = firstControl - bodyTop - 6;
        BodyLayout body = layoutBody(lines, panelW - 2 * BODY_MARGIN, bodyRoom);
        if (body.face() != null) {
            int lineY = bodyTop;
            for (String line : body.lines()) {
                body.face().draw(g2, line, ox + BUTTON_X, lineY, GameFont.Ink.WHITE);
                lineY += body.step();
            }
        }

        tracks.clear();
        for (Slider slider : sliders) {
            int x = ox + BUTTON_X;
            int y = oy + slider.y();
            if (font != null) {
                font.draw(g2, slider.caption(), x, y, GameFont.Ink.WHITE);
                String readout = slider.readout().get();
                font.draw(g2, readout, x + FULL_WIDTH - font.widthOf(readout), y,
                        GameFont.Ink.YELLOW);
            }
            int trackY = y + SLIDER_HEIGHT - TRACK_HEIGHT;
            Rectangle track = new Rectangle(x, trackY, FULL_WIDTH, TRACK_HEIGHT);
            tracks.add(track);
            PanelArt.sunken(g2, track.x, track.y, track.width, track.height,
                    StoneTexture.Tint.SLATE);

            double fraction = Math.max(0, Math.min(1, slider.value().getAsDouble()));
            int travel = track.width - KNOB_WIDTH;
            int knobX = track.x + (int) Math.round(fraction * travel);
            // The part already travelled, filled, so the setting can be read
            // from across the room without finding the knob first.
            if (knobX > track.x + 2) {
                g2.setColor(new Color(196, 164, 72, 190));
                g2.fillRect(track.x + 2, track.y + 3, knobX - track.x, track.height - 6);
            }
            PanelArt.panel(g2, knobX, trackY - 3, KNOB_WIDTH, TRACK_HEIGHT + 6,
                    StoneTexture.Tint.STONE);
        }

        bounds.clear();
        // The button block keeps the width the script gives it and is centred
        // in whatever panel it is on, so the wider keystroke panel does not get
        // buttons stranded against its left edge.
        int blockLeft = ox + (panelW - FULL_WIDTH) / 2;
        for (Item item : items) {
            int x = blockLeft + item.x();
            int y = item.fromBottom() ? oy + panelH - item.y() : oy + item.y();
            Rectangle slot = new Rectangle(x, y, item.width(), BUTTON_HEIGHT);
            bounds.add(slot);
            PanelArt.panel(g2, x, y, item.width(), BUTTON_HEIGHT, StoneTexture.Tint.SLATE);
            if (font != null) {
                font.drawCentred(g2, item.caption().get(), x + item.width() / 2,
                        y + (BUTTON_HEIGHT - font.height()) / 2, GameFont.Ink.WHITE);
            }
        }
    }

    /**
     * Draws the keystroke list.
     *
     * <p>Key names are drawn as keys. A key name set in the same face as the
     * sentence beside it is a word in a sentence, and the eye has to read it to
     * find out it is not: the list reads as prose broken into columns. A cap
     * with a border round it is a shape, and a page of them can be scanned
     * without being read, which is the only thing anybody ever does to a list
     * of keystrokes.
     */
    private void drawHelp(Graphics2D g2, int ox, int oy, HelpLayout laid) {
        if (font == null || small == null) {
            return;
        }
        Rectangle body = laid.body();
        for (HelpLine line : laid.lines()) {
            if (line.heading() != null) {
                font.draw(g2, line.heading(), ox + body.x, oy + line.y(),
                        GameFont.Ink.YELLOW);
                // A rule under the heading, gold at the left where the words
                // are and fading out to the right. A line of even weight all
                // the way across is a second thing on the page competing with
                // the words above it; one that fades is the underline of the
                // heading and nothing else.
                int ruleY = oy + line.y() + font.height() + 2;
                java.awt.Paint savedPaint = g2.getPaint();
                g2.setPaint(new java.awt.GradientPaint(
                        ox + body.x, ruleY, new Color(255, 220, 80, 130),
                        ox + body.x + body.width, ruleY, new Color(255, 220, 80, 0)));
                g2.fillRect(ox + body.x, ruleY, body.width, 1);
                g2.setPaint(savedPaint);
                continue;
            }
            for (Cap cap : line.caps()) {
                Rectangle box = cap.box();
                if (!cap.join().isEmpty()) {
                    // Centred in the gap the layout left for it, and dimmer
                    // than the caps either side: it is punctuation.
                    int gap = small.widthOf(cap.join()) + 2 * JOIN_PAD;
                    small.drawCentred(g2, cap.join(), ox + box.x - gap / 2,
                            oy + box.y + (box.height - small.height()) / 2,
                            GameFont.Ink.GREY);
                }
                drawCap(g2, cap.label(), ox + box.x, oy + box.y, box.width, box.height);
            }
            GameFont face = laid.descriptionFont();
            face.draw(g2, line.description(), ox + laid.descriptionX(),
                    oy + line.y() + (line.height() - face.height()) / 2,
                    GameFont.Ink.WHITE);
        }
    }

    /**
     * Draws one key cap.
     *
     * <p>Rounded, lit from the top, and dark enough for the parchment lettering
     * to read against -- the same light the panels and buttons are drawn with,
     * so a cap looks like part of this interface rather than a web page's idea
     * of a key. Flat rather than textured: the stone's grain is generated at 26
     * pixels to the unit and a cap is sixteen tall, so a slab of it in here is
     * noise rather than marble.
     */
    private void drawCap(Graphics2D g2, String label, int x, int y, int width, int height) {
        Object savedHint = g2.getRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING);
        java.awt.Stroke savedStroke = g2.getStroke();
        java.awt.Paint savedPaint = g2.getPaint();
        g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setStroke(new java.awt.BasicStroke(1f));

        java.awt.geom.RoundRectangle2D shape = new java.awt.geom.RoundRectangle2D.Float(
                x + 0.5f, y + 0.5f, width - 1f, height - 1f, CAP_RADIUS, CAP_RADIUS);
        g2.setPaint(new java.awt.GradientPaint(x, y, new Color(0x4C5769),
                x, y + height, new Color(0x232A38)));
        g2.fill(shape);
        // The lit top edge, inside the border: what makes it a key and not a
        // box. One line, because two is a bevel and a bevel this small reads
        // as blur.
        g2.setColor(new Color(255, 255, 255, 58));
        g2.drawLine(x + 2, y + 1, x + width - 3, y + 1);
        g2.setColor(new Color(0, 0, 0, 175));
        g2.draw(shape);
        g2.setPaint(savedPaint);
        g2.setStroke(savedStroke);
        if (savedHint != null) {
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, savedHint);
        }

        small.drawCentred(g2, label, x + width / 2, y + (height - small.height()) / 2,
                GameFont.Ink.WHITE);
    }

    /** How round the corner of a cap is. Enough to read as rounded, no more. */
    private static final float CAP_RADIUS = 4f;
}
