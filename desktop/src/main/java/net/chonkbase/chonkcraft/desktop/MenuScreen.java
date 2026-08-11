package net.chonkbase.chonkcraft.desktop;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.JPanel;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.Palette;
import net.chonkbase.chonkcraft.engine.GameData;

/**
 * The menus, drawn on Warcraft II's own art.
 *
 * <p>Implements the menu screens in {@code scripts/guichan.legacy-declaration}, reduced to
 * what a game needs to be started: choose a campaign and a mission, or choose
 * a skirmish map.
 *
 * <p>The background and the button are the game's, out of the archive. The
 * layout follows {@code BuildProgramStartMenu}: a 640 by 480 panel centred in
 * whatever window it is given, with the buttons in a column. That centring is
 * upstream's own idea and it is why the menu still looks deliberate at a
 * resolution the artists never saw.
 */
final class MenuScreen extends JPanel {

    /** The size the menu art was drawn for. */
    private static final int DESIGN_WIDTH = 640;
    private static final int DESIGN_HEIGHT = 480;

    /**
     * How far apart the rows sit.
     *
     * <p>Thirty, which is the button's own height plus a two pixel gap -- the
     * original stacks them almost touching, and spacing them out reads as a
     * web page rather than as the game.
     */
    private static final int ROW_HEIGHT = 30;

    /**
     * Where the first row sits.
     *
     * <p>Lower than upstream's 104. ChonkCraft draws over its own menu background,
     * whose logo is small and out of the way; this draws over the one the DOS
     * game shipped, where the Tides of Darkness logo fills the top left corner
     * and a button at 104 lands squarely across it. Eight rows from here still
     * clear the bottom of the 480-pixel panel.
     */
    private static final int FIRST_ROW_Y = 255;

    /**
     * The foot of the parchment, which the last row must not pass.
     *
     * <p>Not the foot of the panel. The background is a sheet of parchment with
     * a burnt edge painted on it, and that edge begins at 457 at the left of
     * the button column and 461 down the middle: a row ending at 462 is drawn
     * half on the scorch. Seven pixels of clean parchment below the last button
     * is what makes the column look laid on the page rather than run off it.
     */
    private static final int LAST_ROW_BOTTOM = 450;

    /**
     * The highest anything may be drawn.
     *
     * <p>The Tides of Darkness logo fills the top left down to about 152, and a
     * centred heading is wider than the gap its right edge leaves. Nothing --
     * heading or button -- goes above this.
     */
    private static final int CONTENT_TOP = 155;

    /** Clear parchment between the foot of the heading and the first button. */
    private static final int HEADING_GAP = 16;

    /** One choice on a page. */
    private record Entry(String caption, String hotkey, Runnable action) {}

    /** Where an entry was last drawn, for hit testing. */
    private final List<Rectangle> bounds = new ArrayList<>();

    /** Where the heading's ink landed, or null when there is no heading. */
    private Rectangle headingBounds;

    /** The menu drawn at its own size, and the scaler's working copy. */
    private BufferedImage design;
    private BufferedImage scaleCache;

    private final BufferedImage background;

    /** The button in its two states, from the archive's widget sheet. */
    private final BufferedImage button;

    private final BufferedImage buttonPressed;

    /** The game's own lettering, rather than whatever the JVM offers. */
    private final GameFont headingFont;

    /**
     * What the buttons are lettered in.
     *
     * <p>The in-game panel's face, at the in-game panel's size, on the same
     * widget slab the in-game panel uses -- so a button here and a button
     * behind the F10 key are the same object seen twice. The menu used to set
     * these in the heading's bold nineteen, which filled the slab corner to
     * corner and read as a different program's idea of a button.
     */
    private final GameFont captionFont;

    /** Which entry the pointer is over, or -1. */
    private int hovered = -1;

    private List<Entry> entries = List.of();
    private String heading = "";

    /** Notified with a description once the player has chosen a game. */
    private final Consumer<Launch> onLaunch;

    /**
     * What the player picked.
     *
     * <p>Exactly one kind is set: a campaign mission, a skirmish map, a save
     * to resume, or a multiplayer game. They are kept as one record rather
     * than four callbacks because the caller does the same thing with all of
     * them, which is load a world and start the game.
     */
    record Launch(String campaign, int mission, java.nio.file.Path map,
            java.nio.file.Path save, LobbySetup lobby, Multiplayer multiplayer) {

        /** Which multiplayer screen to put up, when that is what was chosen. */
        enum Multiplayer {
            /** Publish a lobby in the online browser. */
            HOST_PUBLIC,
            /** Create a lobby reachable only by code or invite. */
            HOST_PRIVATE,
            /** Listen on a user-selected UDP port without using the online service. */
            HOST_DIRECT,
            /** Browse the online room service or enter a room code. */
            JOIN,
            /** Join by address without contacting the room service. */
            JOIN_DIRECT
        }

        static Launch campaignMission(String campaign, int mission) {
            return new Launch(campaign, mission, null, null, null, null);
        }

        static Launch skirmish(java.nio.file.Path map) {
            return new Launch(null, 0, map, null, null, null);
        }

        static Launch saved(java.nio.file.Path save) {
            return new Launch(null, 0, null, save, null, null);
        }

        /** Host a game on a chosen map, which opens the lobby. */
        static Launch host(java.nio.file.Path map, boolean privateGame) {
            return host(map, privateGame ? Multiplayer.HOST_PRIVATE : Multiplayer.HOST_PUBLIC);
        }

        /** Host through the selected online or direct transport. */
        static Launch host(java.nio.file.Path map, Multiplayer kind) {
            return new Launch(null, 0, map, null, null,
                    kind);
        }

        /** Go looking for a game to join. The map comes from whoever is hosting. */
        static Launch join() {
            return new Launch(null, 0, null, null, null, Multiplayer.JOIN);
        }

        /** Join by IP address or LAN discovery without using the online service. */
        static Launch joinDirect() {
            return new Launch(null, 0, null, null, null, Multiplayer.JOIN_DIRECT);
        }

        /** A lobby that has settled and is ready to become a game. */
        static Launch multiplayer(LobbySetup lobby) {
            return new Launch(null, 0, lobby.map(), null, lobby, null);
        }
    }

    MenuScreen(GameData data, String race, int width, int height, Consumer<Launch> onLaunch) {
        this.onLaunch = onLaunch;
        this.background = load(data, "ui/Menu_background_with_title");
        // The archive's own large widget button, 224 by 28, which is the size
        // the original menu uses. The red set, not the blue: the DOS menu is
        // red whichever side you go on to play, and the sheet the red one
        // lives in happens to be the orc widgets.
        //
        // Not "ui/<race>/menubutton" -- that is the 176 by 24 sidebar button
        // for in-game use, and drawing the menu with it was why these came out
        // narrow and grey against a parchment that wanted dark red stone.
        this.button = widget(data, "ui/orc/widgets/button-large-normal");
        this.buttonPressed = widget(data, "ui/orc/widgets/button-large-pressed");
        this.headingFont = GameFont.load(data, GameFont.Face.LARGE);
        this.captionFont = GameFont.load(data, GameFont.Face.GAME);

        setPreferredSize(new Dimension(width, height));
        setBackground(Color.BLACK);
        setDoubleBuffered(true);
        setFocusable(true);
        installInput();
    }

    /** A piece of the widget sheet, which has its own palette. */
    private static BufferedImage widget(GameData data, String path) {
        IndexedImage image = data.widget(path);
        if (image == null) {
            return null;
        }
        Palette palette = data.widgetPalette(path);
        return palette == null ? null : image.toBufferedImage(palette);
    }

    private static BufferedImage load(GameData data, String path) {
        IndexedImage image = data.image(path);
        if (image == null) {
            return null;
        }
        Palette palette = data.paletteFor(path);
        return palette == null ? null : image.toBufferedImage(palette);
    }

    /** Whether the art was found; without it there is nothing to show. */
    boolean isAvailable() {
        return background != null;
    }

    /** Replaces the page being shown. */
    private void show(String heading, List<Entry> entries) {
        this.heading = heading;
        this.entries = entries;
        repaint();
    }

    /** Builds the opening page. */
    void showMainMenu(GameData data, java.util.List<java.nio.file.Path> maps) {
        List<Entry> page = new ArrayList<>();
        if (!data.campaigns().isEmpty()) {
            page.add(new Entry("Campaign Game", "c", () -> showCampaigns(data, maps)));
        }
        if (!maps.isEmpty()) {
            page.add(new Entry("Single Player Game", "s", () -> showMaps(data, maps)));
        }
        // Only offered when there is something to load. An entry that leads to
        // an empty page is a worse answer than no entry.
        if (!savedGames().isEmpty()) {
            page.add(new Entry("Load Game", "l", () -> showSaves(data, maps)));
        }
        if (!maps.isEmpty()) {
            page.add(new Entry("Multi Player Game", "m", () -> showMultiplayer(data, maps)));
        }
        page.add(new Entry("Exit Program", "x", () -> System.exit(0)));
        show("", page);
    }

    /** Opens the map page directly, for rendering checks. */
    void showMapsForTest(GameData data, java.util.List<java.nio.file.Path> maps) {
        showMaps(data, maps);
    }

    /** Where the rows landed when the page was last drawn. */
    List<Rectangle> slotBoundsForTest() {
        return List.copyOf(bounds);
    }

    /** The captions on the current page. */
    List<String> captionsForTest() {
        return entries.stream().map(Entry::caption).toList();
    }

    /** Activates a row, as clicking it would. */
    void pressForTest(int row) {
        entries.get(row).action().run();
    }

    /** Opens the campaign page directly, for rendering checks. */
    void showCampaignsForTest(GameData data, java.util.List<java.nio.file.Path> maps) {
        showCampaigns(data, maps);
    }

    /** Opens a mission page directly, for rendering checks. */
    void showMissionsForTest(GameData data, java.util.List<java.nio.file.Path> maps,
            String campaign, int count) {
        showMissions(data, maps, campaign, count);
    }

    /** Opens the load page on a given set of saves, for rendering checks. */
    void showSavesForTest(GameData data, java.util.List<java.nio.file.Path> maps,
            java.util.List<java.nio.file.Path> saves) {
        showSaves(data, maps, saves);
    }

    /** Opens the multiplayer page directly, for rendering checks. */
    void showMultiplayerForTest(GameData data, java.util.List<java.nio.file.Path> maps) {
        showMultiplayer(data, maps);
    }

    /** Opens the host's map page directly, for rendering checks. */
    void showMultiplayerMapsForTest(GameData data, java.util.List<java.nio.file.Path> maps) {
        showMultiplayerMaps(data, maps);
    }

    /** The families this screen letters with, so a test can prove they match. */
    java.util.List<String> faceFamiliesForTest() {
        return java.util.List.of(headingFont.family(), captionFont.family());
    }

    /** The heading the current page is showing, for rendering checks. */
    String headingForTest() {
        return heading;
    }

    /** Where the heading was drawn, or null if the page has none. */
    Rectangle headingBoundsForTest() {
        return headingBounds;
    }

    private void showCampaigns(GameData data, java.util.List<java.nio.file.Path> maps) {
        List<Entry> page = new ArrayList<>();
        for (var campaign : data.campaigns()) {
            String name = campaign.name();
            page.add(new Entry(campaignTitle(name), hotkeyFor(page.size()),
                    () -> showMissions(data, maps, name, campaign.missions().size())));
        }
        page.add(new Entry("Previous Menu", "escape", () -> showMainMenu(data, maps)));
        // No heading. Every row says the word Campaign; a line above them
        // saying to choose one tells the player what the buttons already tell
        // them, and costs the column thirty pixels of parchment to do it.
        show("", page);
    }

    /**
     * The missions of one campaign, in a scrolling column.
     *
     * <p>Fourteen missions do not fit in the eight rows the original leaves
     * room for, so this pages rather than shrinking the buttons: the art is a
     * fixed size and stretching it would be the one thing that looks wrong.
     */
    private void showMissions(GameData data, java.util.List<java.nio.file.Path> maps,
            String campaign, int count) {
        showMissionPage(data, maps, campaign, count, 0);
    }

    private static final int PAGE_SIZE = 6;

    private void showMissionPage(GameData data, java.util.List<java.nio.file.Path> maps,
            String campaign, int count, int first) {
        List<Entry> page = new ArrayList<>();
        int last = Math.min(count, first + PAGE_SIZE);
        for (int number = first + 1; number <= last; number++) {
            int chosen = number;
            page.add(new Entry("Mission " + number, hotkeyFor(page.size()),
                    () -> onLaunch.accept(Launch.campaignMission(campaign, chosen))));
        }
        if (last < count) {
            page.add(new Entry("More Missions", "n",
                    () -> showMissionPage(data, maps, campaign, count, last)));
        }
        page.add(new Entry("Previous Menu", "escape", () -> showCampaigns(data, maps)));
        // The campaign's name, and nothing else. "Mission 3" on a button does
        // not say whose third mission it is, so the heading earns its room;
        // which six of the fourteen these are is on the buttons already.
        show(campaignTitle(campaign), page);
    }

    private void showMaps(GameData data, java.util.List<java.nio.file.Path> maps) {
        showMapPage(data, maps, 0);
    }

    private void showMapPage(GameData data, java.util.List<java.nio.file.Path> maps, int first) {
        List<Entry> page = new ArrayList<>();
        int last = Math.min(maps.size(), first + PAGE_SIZE);
        for (int i = first; i < last; i++) {
            java.nio.file.Path map = maps.get(i);
            String name = map.getFileName().toString();
            page.add(new Entry(name.replaceFirst("(?i)\\.pud$", ""), hotkeyFor(page.size()),
                    () -> onLaunch.accept(Launch.skirmish(map))));
        }
        if (last < maps.size()) {
            page.add(new Entry("More Maps", "n", () -> showMapPage(data, maps, last)));
        }
        page.add(new Entry("Previous Menu", "escape", () -> showMainMenu(data, maps)));
        show(place("Maps", first, last, maps.size()), page);
    }

    /**
     * Where in a long list the player has got to, or nothing.
     *
     * <p>A page of map names says nothing about how far through them it is or
     * how many there are, and that is the one thing a player paging through
     * thirty-four of them wants to know. When they all fit on one page there is
     * no "where", so there is no heading: naming a list whose every member is
     * on screen is the redundancy this is meant to avoid.
     */
    private static String place(String what, int first, int last, int total) {
        return total <= PAGE_SIZE
                ? ""
                : what + " " + (first + 1) + " to " + last + " of " + total;
    }

    /** The saves on disk, newest first: the last one written is the wanted one. */
    static List<java.nio.file.Path> savedGames() {
        java.nio.file.Path directory = GameScreen.saveDirectory();
        if (!java.nio.file.Files.isDirectory(directory)) {
            return List.of();
        }
        try (var listing = java.nio.file.Files.list(directory)) {
            return listing
                    .filter(path -> path.getFileName().toString()
                            .endsWith(net.chonkbase.chonkcraft.engine.save.SaveGame.SUFFIX))
                    .sorted((a, b) -> Long.compare(modified(b), modified(a)))
                    .toList();
        } catch (java.io.IOException e) {
            return List.of();
        }
    }

    private static long modified(java.nio.file.Path path) {
        try {
            return java.nio.file.Files.getLastModifiedTime(path).toMillis();
        } catch (java.io.IOException e) {
            return 0;
        }
    }

    private void showSaves(GameData data, java.util.List<java.nio.file.Path> maps) {
        showSaves(data, maps, savedGames());
    }

    private void showSaves(GameData data, java.util.List<java.nio.file.Path> maps,
            List<java.nio.file.Path> saves) {
        showSavePage(data, maps, saves, 0);
    }

    /**
     * The saves, newest first, a page at a time.
     *
     * <p>Paged like the maps rather than cut off at six. A player with seven
     * saves is not a player with six: the seventh was simply unreachable, and a
     * menu that quietly loses a save is worse than one that makes you press on.
     */
    private void showSavePage(GameData data, java.util.List<java.nio.file.Path> maps,
            List<java.nio.file.Path> saves, int first) {
        List<Entry> page = new ArrayList<>();
        int last = Math.min(saves.size(), first + PAGE_SIZE);
        for (int i = first; i < last; i++) {
            java.nio.file.Path save = saves.get(i);
            page.add(new Entry(net.chonkbase.chonkcraft.engine.save.LoadGame.nameOf(save),
                    hotkeyFor(page.size()), () -> onLaunch.accept(Launch.saved(save))));
        }
        if (last < saves.size()) {
            page.add(new Entry("More Saved Games", "n",
                    () -> showSavePage(data, maps, saves, last)));
        }
        page.add(new Entry("Previous Menu", "escape", () -> showMainMenu(data, maps)));
        // The rows are whatever the player called their saves, which does not
        // say they are saves at all -- so this page keeps a heading where the
        // campaign list loses one. Paged, it says which of them these are.
        String where = place("Saved Games", first, last, saves.size());
        show(where.isEmpty() ? "Saved Games" : where, page);
    }

    /**
     * Hosting or joining.
     *
     * <p>Online hosting uses the relay. Direct hosting asks for a UDP port and
     * joins by address; LAN discovery is a convenience over that same direct
     * transport rather than a different multiplayer protocol.
     */
    private void showMultiplayer(GameData data, java.util.List<java.nio.file.Path> maps) {
        List<Entry> page = new ArrayList<>();
        page.add(new Entry("Host Public Game", "h",
                () -> showMultiplayerMaps(data, maps, false)));
        page.add(new Entry("Host Private Game", "p",
                () -> showMultiplayerMaps(data, maps, Launch.Multiplayer.HOST_PRIVATE)));
        page.add(new Entry("Host Direct IP Game", "d",
                () -> showMultiplayerMaps(data, maps, Launch.Multiplayer.HOST_DIRECT)));
        // A joiner picks no map: the host's is the one being played, and
        // asking a joiner to choose one is asking them to guess.
        page.add(new Entry("Join Online Game", "j", () -> onLaunch.accept(Launch.join())));
        page.add(new Entry("Join Direct IP Game", "i",
                () -> onLaunch.accept(Launch.joinDirect())));
        page.add(new Entry("Previous Menu", "escape", () -> showMainMenu(data, maps)));
        // The choices name both discovery and transport, so a heading would
        // only repeat the multiplayer button the player used to reach them.
        show("", page);
    }

    private void showMultiplayerMaps(GameData data, java.util.List<java.nio.file.Path> maps) {
        showMultiplayerMaps(data, maps, Launch.Multiplayer.HOST_PUBLIC);
    }

    private void showMultiplayerMaps(GameData data, java.util.List<java.nio.file.Path> maps,
            boolean privateGame) {
        showMultiplayerMaps(data, maps,
                privateGame ? Launch.Multiplayer.HOST_PRIVATE : Launch.Multiplayer.HOST_PUBLIC);
    }

    private void showMultiplayerMaps(GameData data, java.util.List<java.nio.file.Path> maps,
            Launch.Multiplayer kind) {
        showMultiplayerMapPage(data, maps, 0, kind);
    }

    /**
     * Only the host chooses a map.
     *
     * <p>A joiner plays whatever the host is playing, so asking them to choose
     * one would be asking them to guess -- and a wrong guess is not a wrong
     * map, it is a desync on the first cycle.
     */
    private void showMultiplayerMapPage(GameData data, java.util.List<java.nio.file.Path> maps,
            int first, Launch.Multiplayer kind) {
        List<Entry> page = new ArrayList<>();
        int last = Math.min(maps.size(), first + PAGE_SIZE);
        for (int i = first; i < last; i++) {
            java.nio.file.Path map = maps.get(i);
            page.add(new Entry(map.getFileName().toString().replaceFirst("(?i)\\.pud$", ""),
                    hotkeyFor(page.size()), () -> onLaunch.accept(Launch.host(map, kind))));
        }
        if (last < maps.size()) {
            page.add(new Entry("More Maps", "n",
                    () -> showMultiplayerMapPage(data, maps, last, kind)));
        }
        page.add(new Entry("Previous Menu", "escape", () -> showMultiplayer(data, maps)));
        // The one map list where the heading has something of its own to say:
        // these names look exactly like the single player list, and picking one
        // here opens a lobby other people are about to join.
        //
        // "Hosting:" rather than "Hosting a Game -", which with the count after
        // it made a line half again as wide as the column it captioned and ran
        // out over the sketches either side of it.
        String where = place("Maps", first, last, maps.size());
        String title = switch (kind) {
            case HOST_PRIVATE -> "Private Game";
            case HOST_DIRECT -> "Direct IP Game";
            default -> "Public Game";
        };
        show(where.isEmpty() ? "Hosting " + title : title + ": " + where, page);
    }

    private static String campaignTitle(String name) {
        return switch (name) {
            case "human" -> "Human Campaign";
            case "orc" -> "Orc Campaign";
            case "human-exp" -> "Human Expansion";
            case "orc-exp" -> "Orc Expansion";
            default -> name;
        };
    }

    /** A digit for each row, which is easier to reach than a guessed letter. */
    private static String hotkeyFor(int row) {
        return String.valueOf((char) ('1' + row));
    }

    private void installInput() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                for (int i = 0; i < bounds.size() && i < entries.size(); i++) {
                    if (bounds.get(i).contains(toDesign(event.getPoint()))) {
                        entries.get(i).action().run();
                        return;
                    }
                }
            }
        });
        addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                java.awt.Point at = toDesign(event.getPoint());
                int was = hovered;
                hovered = -1;
                for (int i = 0; i < bounds.size(); i++) {
                    if (bounds.get(i).contains(at)) {
                        hovered = i;
                        break;
                    }
                }
                if (hovered != was) {
                    repaint();
                }
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                if (event.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    for (Entry entry : entries) {
                        if ("escape".equals(entry.hotkey())) {
                            entry.action().run();
                            return;
                        }
                    }
                    return;
                }
                char typed = Character.toLowerCase(event.getKeyChar());
                for (Entry entry : entries) {
                    if (entry.hotkey().length() == 1
                            && entry.hotkey().charAt(0) == typed) {
                        entry.action().run();
                        return;
                    }
                }
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        // Drawn once at the size it was designed for and then blown up to the
        // window, so it fills a modern screen instead of sitting as a postage
        // stamp in the middle of one. PixelScaler does the enlarging: whole
        // steps as far as they go and one interpolated pass over the
        // remainder, which keeps the lettering crisp without leaving most of
        // the window empty the way whole steps alone would.
        if (design == null) {
            design = new BufferedImage(DESIGN_WIDTH, DESIGN_HEIGHT,
                    BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D g2 = design.createGraphics();
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, DESIGN_WIDTH, DESIGN_HEIGHT);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        paintDesign(g2);
        g2.dispose();

        scaleCache = PixelScaler.draw((Graphics2D) g, design,
                getWidth(), getHeight(), false, scaleCache);
    }

    /** The menu at its own size; everything here is in design pixels. */
    private void paintDesign(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        int offsetX = 0;
        int offsetY = 0;
        if (background != null) {
            g2.drawImage(background, offsetX, offsetY, null);
        }

        bounds.clear();
        int buttonWidth = button != null ? button.getWidth() : 224;
        int buttonHeight = button != null ? button.getHeight() : 28;
        int x = offsetX + (DESIGN_WIDTH - buttonWidth) / 2;

        // The column sits low, under the logo and among the sketches, as the
        // original does. A long page -- the campaign missions, the map list --
        // is lifted just enough to keep its last row on the parchment rather
        // than being allowed to run off the bottom.
        //
        // The heading's room is part of that sum rather than a constant taken
        // off the top of it. Subtracting a constant was the bug: a page long
        // enough to lift the column lifted it into its own heading, and how
        // long that was depended on how many missions a campaign had. Reserved
        // here, the two cannot meet at any row count -- the column is pushed
        // down by exactly what the heading occupies, and the floor it is never
        // allowed above already includes that reservation.
        int rows = Math.max(1, entries.size());
        int headingSpace = heading.isEmpty() || headingFont == null
                ? 0
                : headingFont.height() + HEADING_GAP;
        // What the column actually inks: the last row ends at its button, not
        // at the two-pixel gap that would follow it.
        int columnHeight = rows * ROW_HEIGHT - (ROW_HEIGHT - buttonHeight);
        int top = Math.min(FIRST_ROW_Y, LAST_ROW_BOTTOM - columnHeight);
        top = Math.max(top, CONTENT_TOP + headingSpace);

        headingBounds = null;
        if (headingSpace > 0) {
            int headingWidth = headingFont.widthOf(heading);
            int headingY = offsetY + top - headingSpace;
            headingBounds = new Rectangle(offsetX + (DESIGN_WIDTH - headingWidth) / 2,
                    headingY, headingWidth, headingFont.height());
            headingFont.drawCentred(g2, heading, offsetX + DESIGN_WIDTH / 2, headingY,
                    GameFont.Ink.YELLOW);
        }

        for (int i = 0; i < entries.size(); i++) {
            int y = offsetY + top + ROW_HEIGHT * i;
            Rectangle slot = new Rectangle(x, y, buttonWidth, buttonHeight);
            bounds.add(slot);

            BufferedImage art = i == hovered && buttonPressed != null
                    ? buttonPressed
                    : button;
            if (art != null) {
                g2.drawImage(art, x, y, null);
            } else {
                g2.setColor(new Color(70, 18, 14));
                g2.fill(slot);
            }
            if (i == hovered) {
                // The yellow outline the original puts round the entry under
                // the pointer. It is the only thing that says a menu of stone
                // slabs is answering at all.
                g2.setColor(new Color(0xFFDC50));
                g2.drawRect(x, y, buttonWidth - 1, buttonHeight - 1);
                g2.drawRect(x + 1, y + 1, buttonWidth - 3, buttonHeight - 3);
            }

            if (captionFont != null) {
                // Cut to the slab it is written on. Most captions are this
                // program's own and fit; a map file or a save the player named
                // is neither, and one long enough would otherwise be lettered
                // out over the parchment on both sides of the button.
                String caption = captionFont.fitted(entries.get(i).caption(),
                        buttonWidth - 24);
                captionFont.drawCentred(g2, caption, x + buttonWidth / 2,
                        y + (buttonHeight - captionFont.height()) / 2, GameFont.Ink.YELLOW);
            }
        }
    }

    /** A click, in the design's own coordinates. */
    private java.awt.Point toDesign(java.awt.Point at) {
        Rectangle shown = PixelScaler.fit(DESIGN_WIDTH, DESIGN_HEIGHT,
                getWidth(), getHeight(), false);
        if (shown.width <= 0 || shown.height <= 0) {
            return at;
        }
        return new java.awt.Point(
                (at.x - shown.x) * DESIGN_WIDTH / shown.width,
                (at.y - shown.y) * DESIGN_HEIGHT / shown.height);
    }
}
