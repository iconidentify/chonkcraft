package net.chonkbase.chonkcraft.desktop;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.Palette;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.FogOfWar;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * Warcraft II's left-hand sidebar, drawn from the game's own interface art.
 *
 * <p>The layout is the original's and the sizes come from the data rather than
 * from guesses: the minimap frame is 176 by 136, the button panel 176 by 144,
 * and the resource and status bars 448 by 16. Those add up to the 640 by 480
 * the game was designed for, which is why the sidebar is 176 wide here too.
 *
 * <p>The minimap is redrawn each frame from the live map, the player's fog,
 * and unit positions, so it shows what the player actually knows.
 */
final class SidePanel {

    /** Width of the sidebar, from the interface art. */
    static final int WIDTH = 176;

    /** Height of the bar along the top. */
    static final int TOP_BAR_HEIGHT = 16;

    private static final int MINIMAP_FRAME_HEIGHT = 136;

    /** One icon cell in the sheet. */
    private static final int ICON_WIDTH = 46;
    private static final int ICON_HEIGHT = 38;

    /** The icon sheet and the frame table, for drawing portraits. */
    private final java.awt.image.BufferedImage iconSheet;
    private final net.chonkbase.chonkcraft.engine.GameData.Interface icons;

    /**
     * Where the minimap picture sits, and how big it is.
     *
     * <p>These are the fallback for an installation whose interface scripts
     * cannot be read. Everywhere else the numbers come from
     * {@code UI.Minimap} in the race's own layout script, which puts the
     * picture two pixels lower than an eye would.
     */
    static final int INSET_X = 24;
    static final int INSET_Y = 26;
    static final int SIZE = 128;

    /** Where the minimap actually is, from the layout script. */
    private static int minimapX = INSET_X;
    private static int minimapY = INSET_Y;
    private static int minimapSize = SIZE;

    private final World world;
    private final int localPlayer;

    private final BufferedImage minimapFrame;
    private final BufferedImage buttonPanel;
    private final BufferedImage resourceBar;
    private final BufferedImage statusLine;
    private final BufferedImage fillerRight;

    /** The interface art the layout names, by the file it names it with. */
    private final java.util.Map<String, BufferedImage> fillerArt =
            new java.util.HashMap<>();

    /**
     * The info panel's own art.
     *
     * <p>Not one of the fillers: the script assigns it to
     * {@code UI.InfoPanel.G} rather than pushing it into {@code UI.Fillers},
     * so a port that draws only the fillers leaves a black hole between the
     * minimap and the command grid.
     */
    private final BufferedImage infoPanel;

    /** Where each piece goes, or null when the layout could not be read. */
    private java.util.List<
            net.chonkbase.chonkcraft.engine.ui.UiLayout.Filler> fillers;

    /** Reused each frame rather than reallocated. */
    private final BufferedImage minimap =
            new BufferedImage(SIZE, SIZE, BufferedImage.TYPE_INT_RGB);

    /** The layout the game's own scripts describe, or null if unreadable. */
    private net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout layout;

    /** The game's own lettering, or null when the archive has no font. */
    private final GameFont font;

    /**
     * The smaller face, for the two figures the script asks for it by name.
     *
     * <p>{@code Font = "small"} on the hit point line, and the same face on
     * the mana figure written across its bar. Both sit inside something fifty
     * or sixty pixels wide, which the game face does not fit in.
     */
    private final GameFont smallFont;

    /**
     * The ink for anything the scripts do not colour explicitly.
     *
     * <p>{@code UI.NormalFontColor}: white on a human mission, yellow on an
     * orc one. Every one of the call sites below used to say {@code WHITE},
     * so the panel obeyed the player's race in its artwork and ignored it in
     * its lettering.
     *
     * @see GameFont#normalInkFor(String)
     */
    private final GameFont.Ink normalInk;

    SidePanel(World world, GameData data, int localPlayer, String race,
            String tilesetName) {
        this(world, data, localPlayer, race, tilesetName, null);
    }

    SidePanel(World world, GameData data, int localPlayer, String race,
            String tilesetName,
            net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout layout) {
        this.world = world;
        this.localPlayer = localPlayer;
        this.layout = layout;
        if (layout != null && layout.minimap().width() > 0) {
            // The interface scripts are the authority on where the minimap is;
            // the constants above are only for an installation that has none.
            minimapX = layout.minimap().x();
            minimapY = layout.minimap().y();
            minimapSize = layout.minimap().width();
        }
        this.font = GameFont.load(data, GameFont.Face.GAME);
        this.smallFont = GameFont.load(data, GameFont.Face.SMALL);
        this.normalInk = GameFont.normalInkFor(race);
        this.icons = data.userInterface(tilesetName);
        IndexedImage sheet = data.sprite("tilesets/" + tilesetName + "/icons");
        Palette iconPalette = sheet == null
                ? null
                : data.paletteFor("tilesets/" + tilesetName + "/icons");
        this.iconSheet = sheet == null || iconPalette == null
                ? null
                : sheet.toBufferedImage(iconPalette);

        minimapFrame = load(data, "ui/" + race + "/minimap");
        buttonPanel = load(data, "ui/" + race + "/buttonpanel");
        resourceBar = load(data, "ui/" + race + "/resource");
        statusLine = load(data, "ui/" + race + "/statusline");
        fillerRight = load(data, "ui/" + race + "/filler-right");
        // A U row, not an I row: the info panel is stored as an uncompressed
        // sprite, so asking for it as a flat image gets nothing back.
        infoPanel = loadSprite(data, "ui/" + race + "/infopanel");

        this.fillers = layout == null || layout.fillers().isEmpty()
                ? null
                : layout.fillers();
        if (fillers != null) {
            for (var filler : fillers) {
                // The script names files; the archive is indexed by path.
                String path = filler.file().endsWith(".png")
                        ? filler.file().substring(0, filler.file().length() - 4)
                        : filler.file();
                BufferedImage art = load(data, path);
                if (art != null) {
                    fillerArt.put(filler.file(), art);
                }
            }
        }

        // The count cards: one short sentence each and the thing itself in
        // the game's own art, because the original explains none of these
        // anywhere and a paragraph under the pointer is a manual, not a
        // tooltip. Worded for the race at the controls: an orc has peons.
        boolean orc = "orc".equalsIgnoreCase(race);
        String workers = orc ? "Peons" : "Peasants";
        countMeaning[GOLD_SLOT] = new String[] {"Gold",
            workers + " mine it, a hundred a trip. Nearly everything costs gold."};
        countMeaning[LUMBER_SLOT] = new String[] {"Lumber",
            workers + " chop it from the trees. Buildings and siege engines want it."};
        countMeaning[OIL_SLOT] = new String[] {"Oil",
            "A platform on a slick, tankers to a shipyard. It buys the fleet."};
        countMeaning[FOOD_SLOT] = new String[] {"Food",
            "Eaten against provided. Farms feed four; in the red, nothing trains."};
        countMeaning[SCORE_SLOT] = new String[] {"Score",
            "Counted at the end of the mission. Settles nothing before it."};
        countMeaning[WORKERS_SLOT] = new String[] {"Idle workers",
            workers + " with nothing to do. Click the figure to visit the next one."};

        var roster = data.unitTypes().types();
        countThumbs[GOLD_SLOT] = spriteFrame(data,
                roster.get("unit-gold-mine"), tilesetName);
        countThumbs[FOOD_SLOT] = spriteFrame(data,
                roster.get(orc ? "unit-pig-farm" : "unit-farm"), tilesetName);
        countThumbs[WORKERS_SLOT] = spriteFrame(data,
                roster.get(orc ? "unit-peon" : "unit-peasant"), tilesetName);
        oilPatchArt = spriteFrame(data, roster.get("unit-oil-patch"), tilesetName);
    }

    /**
     * The first frame of a type's sprite as this tileset draws it, or null.
     *
     * <p>Frame nought, which for a building is the building and for a worker
     * is it standing looking south -- the recognisable picture in both cases.
     */
    private static BufferedImage spriteFrame(GameData data,
            net.chonkbase.chonkcraft.engine.unit.UnitType type, String tilesetName) {
        if (type == null) {
            return null;
        }
        String file = type.imageFileFor(tilesetName);
        IndexedImage sheet = data.sprite(file);
        Palette palette = sheet == null ? null : data.paletteFor(file);
        if (sheet == null || palette == null) {
            return null;
        }
        BufferedImage whole = sheet.toBufferedImage(palette);
        int width = Math.min(whole.getWidth(), Math.max(1, type.imageWidth()));
        int height = Math.min(whole.getHeight(), Math.max(1, type.imageHeight()));
        return whole.getSubimage(0, 0, width, height);
    }

    /** The info panel art is four stacked frames, each this tall. */
    private static final int INFO_PANEL_FRAME_HEIGHT = 176;

    /**
     * Draws the info panel's own backdrop.
     *
     * <p>Implements {@code DrawInfoPanelBackground} and the choice its callers
     * make. The art is not one picture: {@code ui/human/infopanel} comes back
     * 176 by 704, four frames of 176 stacked, and which one shows depends on
     * what is selected. Frame nought is an empty or foreign selection, one a
     * plain unit of your own, two a spellcaster -- the frame with the mana bar
     * cut into it -- and three a building at work.
     *
     * <p>Drawing the whole sheet instead paints four panels down the sidebar,
     * covers the button panel underneath, and leaves the command grid sitting
     * on the second one.
     */
    private void drawInfoPanelBackground(Graphics2D g2, Unit selected) {
        int x = layout != null ? layout.infoPanel().x() : 0;
        int y = layout != null ? layout.infoPanel().y() : MINIMAP_FRAME_HEIGHT;
        int width = infoPanel != null ? infoPanel.getWidth() : WIDTH;

        // Generated, like the rest of the column. Left as the original art
        // this was the one bright, streaky patch of 1995 marble between two
        // panels of clean stone, which drew the eye straight to it.
        PanelArt.panel(g2, x, y, width, INFO_PANEL_FRAME_HEIGHT, StoneTexture.Tint.STONE);

        // A well for the unit's details to sit in. Which frame the original
        // would have used still decides how much of the panel it covers: a
        // building at work needs the lower half for its progress bar, and an
        // empty selection needs no well at all.
        int frame = infoPanelFrame(selected);
        if (frame == 0) {
            return;
        }
        int inset = 6;
        // A plain unit needs down to the last row of statistics and no more; a
        // caster needs the mana bar under them, and a building at work needs
        // the whole of the panel for its progress bar. This was three quarters
        // of the panel to start with, which ends at 129: the sight figure sits
        // at 118 and a line is thirteen tall, and a peasant carrying gold has a
        // fifth line at 133, so the well cut through both of them.
        int wellHeight = frame == 1
                ? PLAIN_WELL_BOTTOM - inset
                : INFO_PANEL_FRAME_HEIGHT - inset * 2;
        PanelArt.sunken(g2, x + inset, y + inset,
                width - inset * 2, wellHeight, StoneTexture.Tint.STONE);
    }

    /**
     * Where the well ends for a plain unit.
     *
     * <p>The last row a plain unit can put anything on is 133 -- the fifth of
     * {@link #STAT_ROWS}, which is where a laden peasant's cargo goes -- and a
     * line is thirteen tall, so the well has to reach 146 before it has room
     * for a margin under it.
     */
    private static final int PLAIN_WELL_BOTTOM = 153;

    /** Which backdrop a selection calls for. Package-private so tests can ask. */
    int infoPanelFrame(Unit selected) {
        if (selected == null || selected.type() == null
                || !world.canControl(localPlayer, selected.player())
                || selectedUnits().size() > 1) {
            return 0;
        }
        if (selected.order() == Unit.Order.UNDER_CONSTRUCTION
                || selected.researching() != null
                || selected.upgradingTo() != null
                || selected.producing() != null) {
            return 3;
        }
        return selected.type().mana() > 0 ? 2 : 1;
    }

    /** Replaces the layout, as a resize does. */
    void setLayout(net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout layout) {
        this.layout = layout;
        if (layout != null && layout.minimap().width() > 0) {
            minimapX = layout.minimap().x();
            minimapY = layout.minimap().y();
            minimapSize = layout.minimap().width();
        }
        fillers = layout == null || layout.fillers().isEmpty() ? null : layout.fillers();
        if (layout != null && layout.production().width() > 0) {
            productionX = layout.production().x();
            productionY = layout.production().y();
        }
    }

    /** Decodes one uncompressed sprite, or {@code null} if it is missing. */
    private static BufferedImage loadSprite(GameData data, String path) {
        IndexedImage image = data.sprite(path);
        if (image == null) {
            return null;
        }
        Palette palette = data.paletteFor(path);
        return palette == null ? null : image.toBufferedImage(palette);
    }

    /** Decodes one interface image, or {@code null} if it is missing. */
    private static BufferedImage load(GameData data, String path) {
        IndexedImage image = data.image(path);
        if (image == null) {
            return null;
        }
        Palette palette = data.paletteFor(path);
        return palette == null ? null : image.toBufferedImage(palette);
    }

    /**
     * Whether a point is inside the minimap picture.
     *
     * <p>The frame is larger than the map it holds, and clicking the frame
     * should do nothing rather than jump the view to a corner.
     */
    static boolean isOnMinimap(int x, int y) {
        return x >= minimapX && x < minimapX + minimapSize
                && y >= minimapY && y < minimapY + minimapSize;
    }

    /** The map tile a minimap point stands for. */
    static int[] tileAt(int x, int y, int mapWidth, int mapHeight) {
        int tileX = (x - minimapX) * mapWidth / minimapSize;
        int tileY = (y - minimapY) * mapHeight / minimapSize;
        return new int[] {Math.max(0, Math.min(mapWidth - 1, tileX)),
                Math.max(0, Math.min(mapHeight - 1, tileY))};
    }

    /** Whether enough art was found to draw the panel at all. */
    boolean isAvailable() {
        return minimapFrame != null;
    }

    /**
     * One thing left off the next minimap, for {@link RenderingTruth}.
     *
     * <p>The same seam {@code GameScreen.withhold} carries and for the same
     * reason: the minimap is a second surface, a thing can be drawn on the
     * field and missing here, and the only way to ask whether a dot reached a
     * pixel is to paint the panel twice.
     */
    private GameScreen.Withheld withheld = GameScreen.Withheld.NOTHING;

    void withhold(GameScreen.Withheld what) {
        this.withheld = what == null ? GameScreen.Withheld.NOTHING : what;
    }

    /** Where the minimap sits inside the panel, so a harness can look at it. */
    static java.awt.Rectangle minimapArea() {
        return new java.awt.Rectangle(minimapX, minimapY, minimapSize, minimapSize);
    }

    /**
     * Draws the whole chrome.
     *
     * @param selected the unit shown in the info area, or {@code null}
     */
    void draw(Graphics2D g2, int viewWidth, int viewHeight, Unit selected,
            int cameraX, int cameraY, int viewportWidth, int viewportHeight) {

        // The sidebar column.
        g2.setColor(Color.BLACK);
        g2.fillRect(0, 0, WIDTH, viewHeight);

        if (fillers != null) {
            drawFillers(g2);
        } else {
            drawFillersWithoutLayout(g2, viewWidth, viewHeight);
        }
        drawInfoPanelBackground(g2, selected);
        drawMenuButton(g2);
        drawMinimap(g2, cameraX, cameraY, viewportWidth, viewportHeight);
        drawSelected(g2, selected);
        drawResources(g2, viewWidth);
        drawCountTooltip(g2, viewWidth, viewHeight);
    }

    /**
     * Draws the interface art where the layout script puts it.
     *
     * <p>The positions are not decoration. The minimap frame goes at y 24, not
     * at the top of the column: the menu button's own strip sits above it, and
     * drawing the frame at zero leaves a black band where that strip belongs.
     * Four of the six are resized as they are placed, which is how a 640 by 480
     * interface fills a larger window.
     */
    private void drawFillers(Graphics2D g2) {
        for (var filler : fillers) {
            BufferedImage art = fillerArt.get(filler.file());
            if (art == null) {
                continue;
            }
            int width = filler.width() > 0 ? filler.width() : art.getWidth();
            int height = filler.height() > 0 ? filler.height() : art.getHeight();
            if (filler.file().contains("minimap")) {
                // The surround is a slab with a well cut into it. Generating
                // the slab and drawing the well keeps the corner the same
                // stone as everything beside it; left as the original art it
                // was a lighter patch of 1995 marble in the one place the eye
                // goes first.
                PanelArt.panel(g2, filler.x(), filler.y(), width, height,
                        StoneTexture.Tint.STONE);
                PanelArt.sunken(g2, minimapX - 2, minimapY - 2,
                        minimapSize + 4, minimapSize + 4, StoneTexture.Tint.STONE);
                g2.setColor(MINIMAP_EDGE);
                g2.drawRect(minimapX - 1, minimapY - 1, minimapSize + 1, minimapSize + 1);
                continue;
            }
            if (isStoneBacking(filler.file())) {
                // The plain stone backings are generated at the size they are
                // drawn, which is the only way they can be sharp at every
                // interface size and free of a repeat at any of them. The
                // pieces that carry actual artwork -- the minimap surround,
                // the menu strip -- are still the game's own.
                PanelArt.panel(g2, filler.x(), filler.y(), width, height,
                        StoneTexture.Tint.STONE);
                continue;
            }
            tile(g2, art, filler.x(), filler.y(), width, height);
        }
    }

    /**
     * Whether a filler is a plain slab rather than a piece of artwork.
     *
     * <p>The button panel, the resource bar, the status line, the right hand
     * strip and the strip above the minimap are all texture and nothing else,
     * so nothing is lost by generating them and a good deal is gained. The
     * minimap surround has a well in it and is handled separately.
     */
    private static boolean isStoneBacking(String file) {
        return file.contains("buttonpanel") || file.contains("resource")
                || file.contains("statusline") || file.contains("filler-right")
                || file.contains("menubutton");
    }

    /**
     * Fills a rectangle by repeating the art rather than stretching it.
     *
     * <p>{@code AddResizedFiller} resizes, which at anything above the 640 by
     * 480 the art was drawn for smears the marble into vertical streaks. The
     * texture is a tileable stone pattern, so repeating it reads as stone at
     * any size, which stretching does not. This is a deliberate departure from
     * upstream, and the only one in this file.
     */
    private static void tile(Graphics2D g2, BufferedImage art, int x, int y,
            int width, int height) {
        java.awt.Shape saved = g2.getClip();
        g2.clipRect(x, y, width, height);
        for (int atY = y; atY < y + height; atY += art.getHeight()) {
            for (int atX = x; atX < x + width; atX += art.getWidth()) {
                g2.drawImage(art, atX, atY, null);
            }
        }
        g2.setClip(saved);
    }

    /** What the panel looked like before the layout was readable. */
    private void drawFillersWithoutLayout(Graphics2D g2, int viewWidth, int viewHeight) {
        if (minimapFrame != null) {
            g2.drawImage(minimapFrame, 0, 0, null);
        }
        if (buttonPanel != null) {
            g2.drawImage(buttonPanel, 0, viewHeight - buttonPanel.getHeight(), null);
        }
        if (resourceBar != null) {
            for (int x = WIDTH; x < viewWidth; x += resourceBar.getWidth()) {
                g2.drawImage(resourceBar, x, 0, null);
            }
        }
        if (statusLine != null) {
            for (int x = WIDTH; x < viewWidth; x += statusLine.getWidth()) {
                g2.drawImage(statusLine, x, viewHeight - statusLine.getHeight(), null);
            }
        }
        if (fillerRight != null) {
            g2.drawImage(fillerRight, viewWidth - fillerRight.getWidth(), 0, null);
        }
    }

    /**
     * Paints the minimap.
     *
     * <p>Terrain colour is taken from the tile's own flags rather than from
     * its graphic, because the minimap is meant to read as terrain type at a
     * glance, not as a shrunken picture. Fog is applied on top, so a player
     * sees the shape of what they have explored and the units only where they
     * can currently see.
     */
    /**
     * Whether the minimap paints the ground under the dots.
     *
     * <p>{@code UiToggleTerrain}, bound to Tab. On a map with a lot going on,
     * turning the terrain off is how a player reads where the armies are.
     */
    private boolean minimapTerrain = true;

    boolean minimapTerrain() {
        return minimapTerrain;
    }

    /** @return the state it is now in */
    boolean toggleMinimapTerrain() {
        minimapTerrain = !minimapTerrain;
        return minimapTerrain;
    }

    private void drawMinimap(Graphics2D g2, int cameraX, int cameraY,
            int viewportWidth, int viewportHeight) {
        int mapWidth = world.map().width();
        int mapHeight = world.map().height();

        for (int y = 0; y < minimapSize; y++) {
            int tileY = y * mapHeight / minimapSize;
            for (int x = 0; x < minimapSize; x++) {
                int tileX = x * mapWidth / minimapSize;
                FogOfWar.Visibility seen = world.visibilityTo(localPlayer, tileX, tileY);
                // Ground never seen is veiled at the unseen level rather than
                // painted black outright, which is upstream's shape and is the
                // same picture at the 0xFF the script asks for.
                if (!minimapTerrain) {
                    // Tab hides the ground so the dots can be read against
                    // nothing, which is what UiToggleTerrain
                    // The game is for on a crowded
                    // map.
                    minimap.setRGB(x, y, 0);
                    continue;
                }
                minimap.setRGB(x, y, seen == FogOfWar.Visibility.UNEXPLORED
                        ? veil(terrainColour(tileX, tileY, false), fogOpacity.unseen())
                        : terrainColour(tileX, tileY, seen == FogOfWar.Visibility.EXPLORED));
            }
        }

        // Units, only where they can be seen.
        //
        // Implements {@code CUnit::IsVisibleOnMinimap} in
        // The game lit ground shows anything alive on
        // it, and ground that is not lit shows what the player remembers
        // standing there, if the type is VisibleUnderFog.
        //
        // This used to ask {@code fog.isVisible(localPlayer, unit.tileX(),
        // unit.tileY())} -- the one square a unit's top-left corner sits on,
        // with no shared vision, no cloak detection and no memory. Two things
        // a player saw. A four by four keep whose top-left corner had gone
        // dark dropped off the minimap entirely while its lit body was still
        // drawn on the field beside it, which on a big base makes buildings
        // flicker off the minimap one at a time as the fog breathes. And a
        // scouted enemy town, which the map view remembers and draws, was on
        // the minimap while you watched it and gone the moment you looked
        // away -- so the one surface a player uses to keep track of where the
        // enemy is was the one surface that forgot.
        for (Unit unit : world.unitsSnapshot()) {
            // IsAliveOnMap: on the map, not destroyed, and not in the middle
            // of dying. Deliberately not Unit.isAlive, which also demands hit
            // points above zero -- upstream's CUnit::IsAlive is
            // "!Destroyed && CurrentAction() != Die" and never looks at
            // health. Three scenery types declare HitPoints = 0 with
            // Indestructible = 1 -- the oil patch, the circle of power and
            // the start location -- so asking for health dropped every oil
            // patch on every naval map off the minimap, which is the one
            // thing on those maps a player is looking for.
            if (!unit.isOnMap() || unit.isDying()) {
                continue;
            }
            boolean seen = unit.isAlive()
                    ? world.isVisibleTo(localPlayer, unit)
                    : GameScreen.seenByFog(world, localPlayer, unit);
            if (!seen || unit == withheld.unit()) {
                continue;
            }
            plotOnMinimap(minimap, minimapSize, mapWidth, mapHeight,
                    unit.tileX(), unit.tileY(), unit.type(), minimapColour(unit));
        }
        // What the player remembers. The map view draws these and the minimap
        // did not, which is exactly backwards: the minimap is the surface a
        // memory is for.
        for (var memory : world.seenBuildings().forPlayer(localPlayer)) {
            if (!memory.type().visibleUnderFog() || memory.equals(withheld.memory())) {
                continue;
            }
            plotOnMinimap(minimap, minimapSize, mapWidth, mapHeight,
                    memory.tileX(), memory.tileY(), memory.type(),
                    minimapColour(memory.owner(), memory.type(), false));
        }

        g2.drawImage(minimap, minimapX, minimapY, null);

        // The viewport rectangle, so the player can see where they are.
        // The rectangle showing what the screen is looking at, worked out in
        // tiles and then clamped to the minimap.
        //
        // Clamped, because on a large screen the view is often wider than the
        // whole map: a 32 tile map on a full-screen window shows every tile of
        // it, and the unclamped rectangle came out twice the width of the
        // minimap and hung out over the panels below and to the right of it.
        int visibleAcross = Math.min(mapWidth, Math.max(1, viewportWidth / 32));
        int visibleDown = Math.min(mapHeight, Math.max(1, viewportHeight / 32));
        int firstX = Math.max(0, Math.min(mapWidth - visibleAcross, cameraX / 32));
        int firstY = Math.max(0, Math.min(mapHeight - visibleDown, cameraY / 32));

        int boxX = minimapX + firstX * minimapSize / mapWidth;
        int boxY = minimapY + firstY * minimapSize / mapHeight;
        int boxW = Math.max(3, visibleAcross * minimapSize / mapWidth);
        int boxH = Math.max(3, visibleDown * minimapSize / mapHeight);
        // One pixel in, so the outline sits on the picture rather than on the
        // frame around it.
        boxW = Math.min(boxW, minimapX + minimapSize - boxX - 1);
        boxH = Math.min(boxH, minimapY + minimapSize - boxY - 1);
        g2.setColor(Color.WHITE);
        g2.drawRect(boxX, boxY, boxW, boxH);

        // Pings, at the minimap's scale. This is where a ping earns its keep:
        // it is how you point at somewhere off the edge of what the other
        // player is looking at.
        for (var ping : world.pings()) {
            double age = (world.cycle() - ping.cycle()) / (double) World.PING_CYCLES;
            int px = minimapX + ping.tileX() * minimapSize / mapWidth;
            int py = minimapY + ping.tileY() * minimapSize / mapHeight;
            PingArt.draw(g2, px, py, minimapSize * 0.12, age,
                    PlayerColours.of(ping.player()));
        }
    }

    /**
     * How dark the minimap's own fog is.
     *
     * <p>A separate setting from the main view's, and the game says so itself:
     * {@code SetMMFogOfWarOpacityLevels(0x55, 0xAA, 0xFF)} two lines below the
     * other call in {@code scripts/legacyEngine.legacy-declaration}. Remembered ground is veiled
     * far more lightly here than on the field, because at this scale a corner
     * of the map is a few dozen pixels and the main view's 0x7F leaves it
     * unreadable. This used to halve the colour instead, which is 0x80.
     */
    private net.chonkbase.chonkcraft.engine.ui.FogOfWarSettings.Levels fogOpacity =
            net.chonkbase.chonkcraft.engine.ui.FogOfWarSettings.MINIMAP_DEFAULT;

    /** Sets the minimap's fog opacities from what the prelude declared. */
    void setFogOpacity(net.chonkbase.chonkcraft.engine.ui.FogOfWarSettings.Levels levels) {
        if (levels != null) {
            fogOpacity = levels;
        }
    }

    /**
     * What colour a unit's dot on the minimap is.
     *
     * <p>{@code DrawUnitOn}, in its own order:
     * a neutral unit takes the colour its own type declares, your own units
     * are white when selected and green otherwise, and everybody else --
     * ally and enemy alike -- is drawn in their own player colour.
     *
     * <p>This used to be two colours: green for you and red for everything
     * else, so an ally, a gold mine, a sheep and an enemy tower were all the
     * same red. Three of those four are things you must not attack.
     *
     * <p>The attack blink is the arm that waited longest: a unit of yours
     * struck in the last second is solid red, and for six seconds after that
     * it blinks red once a second -- {@code ATTACK_RED_DURATION} and
     * {@code ATTACK_BLINK_DURATION}, applied at
     * {@code :327-328}. The minimap is the surface that tells a player about
     * a fight they are not looking at, and without this arm a base being
     * razed off screen looked exactly like a base at peace. The state it
     * needed, the cycle a unit was last struck, arrived with the help-cry
     * work as {@code Unit.attackedCycle} and is never cleared, so the one
     * field serves both rules.
     */
    private int minimapColour(Unit unit) {
        if (world.canControl(localPlayer, unit.player()) && underRecentAttack(unit)) {
            return 0xFF0000;
        }
        return minimapColour(unit.player(), unit.type(), unit.selected());
    }

    /**: solid red for one second of thirty cycles. */
    private static final int ATTACK_RED_CYCLES =
            net.chonkbase.chonkcraft.engine.World.CYCLES_PER_SECOND;

    /**: the whole affair lasts seven seconds. */
    private static final int ATTACK_BLINK_CYCLES =
            7 * net.chonkbase.chonkcraft.engine.World.CYCLES_PER_SECOND;

    /**
     * Whether this unit's dot is red right now.
     *
     * <p>Upstream flips {@code red_phase} off its frame counter once a
     * second; this reads the game cycle instead, which flips at the same
     * rate and is what the panel already draws from. The difference a frame
     * counter would make is which wall-clock instant the blink changes on,
     * and nothing else.
     */
    private boolean underRecentAttack(Unit unit) {
        long attacked = unit.attackedCycle();
        long now = world.cycle();
        if (attacked == 0 || attacked + ATTACK_BLINK_CYCLES <= now) {
            return false;
        }
        boolean redPhase = (now / net.chonkbase.chonkcraft.engine.World.CYCLES_PER_SECOND) % 2 == 0;
        return redPhase || attacked + ATTACK_RED_CYCLES > now;
    }

    /** The same, for a remembered building, which is a type and an owner. */
    private int minimapColour(int owner, UnitType type, boolean selected) {
        if (owner == net.chonkbase.chonkcraft.engine.World.NEUTRAL_PLAYER) {
            return neutralMinimapColour(type);
        }
        if (owner == localPlayer) {
            // UI.Minimap.ShowSelected, which every shipped layout leaves on.
            return selected ? 0xFFFFFF : 0x00FF00;
        }
        return PlayerColours.of(owner).getRGB() & 0xFFFFFF;
    }

    /**
     * One dot, the size of the thing's footprint.
     *
     * <p>{@code Map2MinimapX[type->TileWidth]}: a dot is the unit's footprint
     * at the minimap's scale, not one pixel with a second one bolted on for
     * buildings. A four by four keep and a footman used to be the same mark,
     * and a town read as a scattering of pairs.
     */
    private static void plotOnMinimap(java.awt.image.BufferedImage minimap, int minimapSize,
            int mapWidth, int mapHeight, int tileX, int tileY, UnitType type, int colour) {
        int x = tileX * minimapSize / mapWidth;
        int y = tileY * minimapSize / mapHeight;
        if (x < 0 || y < 0 || x >= minimapSize || y >= minimapSize) {
            return;
        }
        int across = Math.max(1, Math.max(1, type.tileWidth()) * minimapSize / mapWidth);
        int down = Math.max(1, Math.max(1, type.tileHeight()) * minimapSize / mapHeight);
        for (int dy = 0; dy < down && y + dy < minimapSize; dy++) {
            for (int dx = 0; dx < across && x + dx < minimapSize; dx++) {
                minimap.setRGB(x + dx, y + dy, colour);
            }
        }
    }

    /**
     * {@code NeutralMinimapColor}, which the scripts declare per type.
     *
     * <p>A gold mine is {@code {255, 255, 0}}, an oil patch
     * {@code {32, 32, 32}}, a critter {@code {192, 192, 192}}. Read off the
     * The native roster carries the packed colour directly.
     */
    private static int neutralMinimapColour(UnitType type) {
        if (type != null && type.neutralMinimapColour() >= 0) return type.neutralMinimapColour();
        // Nothing declared: the grey PlayerColours already reserves for the
        // owner nobody plays.
        return PlayerColours.of(-1).getRGB() & 0xFFFFFF;
    }

    /** A terrain colour for the minimap, dimmed where only remembered. */
    private int terrainColour(int tileX, int tileY, boolean remembered) {
        var field = world.map().field(tileX, tileY);
        int colour;
        if (field.isForest()) {
            colour = 0x1E5C1E;
        } else if (field.hasFlag(net.chonkbase.chonkcraft.engine.map.TileFlag.WATER_ALLOWED)) {
            colour = 0x1E3C78;
        } else if (field.hasFlag(net.chonkbase.chonkcraft.engine.map.TileFlag.ROCKS)) {
            colour = 0x6E6E6E;
        } else if (field.isWall()) {
            colour = 0x8C8C64;
        } else {
            colour = 0x2E7D32;
        }
        if (remembered) {
            colour = veil(colour, fogOpacity.explored());
        }
        return colour;
    }

    /**
     * Black at a given alpha over a colour, which is what upstream's minimap
     * does: it fills a fog surface with the fog colour at the level for each
     * tile and alpha-blends the whole thing over the terrain
     * ({@code CMinimap::Draw}).
     */
    private static int veil(int colour, int alpha) {
        int keep = 255 - Math.max(0, Math.min(255, alpha));
        int red = ((colour >> 16) & 0xFF) * keep / 255;
        int green = ((colour >> 8) & 0xFF) * keep / 255;
        int blue = (colour & 0xFF) * keep / 255;
        return (red << 16) | (green << 8) | blue;
    }

    /**
     * The selected unit's details, or a strip of icons when several are.
     *
     * <p>Warcraft II shows one unit in full and a group as a row of portraits
     * with health bars, because those answer different questions: what is this
     * thing, versus which of these is hurt.
     *
     * <p>The single-unit side of it is laid out where the game lays it out.
     * {@code UI.SingleSelectedButton} is the portrait's own slot -- declared by
     * every shipped interface script at 9, 169 and never once referred to by
     * this implementation, which wrote the unit's name where its picture belongs -- and
     * the rest of the figures come from {@code panel-general-contents} in
     * {@code scripts/ui.legacy-declaration}: the name centred at 114, the health bar under the
     * portrait at 8, 211 and the statistics down the right at 100.
     */
    private void drawSelected(Graphics2D g2, Unit unit) {
        java.util.List<Unit> group = selectedUnits();
        if (group.size() > 1) {
            drawGroup(g2, group);
            return;
        }
        if (unit == null) {
            return;
        }
        // Everything below is measured from the info panel's own origin, the
        // way DefinePanelContents measures it.
        int top = layout != null ? layout.infoPanel().y() : MINIMAP_FRAME_HEIGHT + 16;
        UnitType type = unit.type();

        drawSinglePortrait(g2, unit);
        drawName(g2, type.name());
        drawHealth(g2, unit, type, top);

        if (unit.order() == Unit.Order.UNDER_CONSTRUCTION) {
            completedBar(g2, top, unit.progressFraction());
            return;
        }
        // Three jobs share one panel, as the script's three
        // "only" conditions do: Training, Research and UpgradeTo. UpgradeTo
        // was missing, so a town hall becoming a keep fell through to the
        // ordinary statistics block and read as an idle building.
        if (unit.producing() != null || unit.researching() != null
                || unit.upgradingTo() != null) {
            drawProduction(g2, unit, top);
            return;
        }
        drawStatistics(g2, unit, type, top);
    }

    /**
     * The unit's name, beside its picture.
     *
     * <p>{@code Line(1, UnitName("Active"), 110, "game")} centred on 114, and
     * the second line fourteen pixels under it. The centre is the script's; the
     * height is not. The script writes line one at eleven and line two at
     * twenty-five whether or not there is a line two, so a one-word name sits
     * against the top of a picture forty pixels tall with nothing under it and
     * reads as having slipped. Here the block of one or two lines is centred on
     * the picture instead, which is the same place for a two line name and the
     * obvious place for a one line one.
     */
    private void drawName(Graphics2D g2, String name) {
        java.util.List<String> lines = nameLines(name);
        if (lines.isEmpty()) {
            return;
        }
        java.awt.Rectangle slot = singleSelectedBounds();
        int stride = Math.max(lineHeight() + 1, NAME_LINE_STRIDE);
        int y = slot.y + (slot.height - (lines.size() - 1) * stride - lineHeight()) / 2;
        // Never over the picture and never off the panel: the room is what is
        // left between the two, and 114 is its middle to within a pixel.
        int left = slot.x + slot.width + 4;
        for (String line : lines) {
            int width = textWidth(line);
            int x = Math.max(left, Math.min(NAME_CENTRE_X - width / 2, WIDTH - 4 - width));
            text(g2, line, x, y, GameFont.Ink.YELLOW);
            y += stride;
        }
    }

    /** {@code Pos = {114, 11}}: where the name is centred. */
    private static final int NAME_CENTRE_X = 114;

    /**
     * The foot of the name block, in the same coordinates {@link #drawName}
     * writes in.
     *
     * <p>Mirrors that method's arithmetic rather than guessing at a constant,
     * so a two-line name ("Elven Destroyer") pushes the figures down instead
     * of being written over them.
     */
    private int nameBottom(String name) {
        java.awt.Rectangle slot = singleSelectedBounds();
        int stride = Math.max(lineHeight() + 1, NAME_LINE_STRIDE);
        int lines = Math.max(1, nameLines(name).size());
        int top = slot.y + (slot.height - (lines - 1) * stride - lineHeight()) / 2;
        return top + (lines - 1) * stride + lineHeight();
    }

    /** How much clear panel to leave between the name and the figures. */
    private static final int STAT_GAP = 10;

    /** Eleven to twenty-five, which is what the script leaves between them. */
    private static final int NAME_LINE_STRIDE = 14;

    /**
     * The health bar and the figure under it.
     *
     * <p>{@code LifeBar} at {@code Pos = {8, 51}}, fifty by seven, with
     * {@code FormattedText2} centred under it. Two departures, both forced and
     * both small. The bar is drawn at seven rather than eight, which puts it
     * exactly under the sunken frame the picture sits in -- the script's eight
     * lines up with nothing, and three pixels of bar sticking out past the
     * corner of a portrait is the sort of thing that reads as sloppy without
     * the eye being able to say why. And the figure is written in the small
     * face, which is what the script asks for and what this implementation ignored:
     * "1200/1200" in the game face is sixty-three pixels wide against a fifty
     * pixel bar, so it hung off both ends and was then shoved right up against
     * the edge of the screen by the clamp that kept it on the panel.
     */
    private void drawHealth(Graphics2D g2, Unit unit, UnitType type, int top) {
        java.awt.Rectangle slot = singleSelectedBounds();
        int x = slot.x - 2;
        int width = slot.width + 4;
        double health = unit.hitPoints() / (double) Math.max(1, type.hitPoints());
        bar(g2, x, top + LIFE_BAR_Y, width, LIFE_BAR_HEIGHT, health,
                healthColour(unit.hitPoints(), type.hitPoints()));

        String figure = unit.hitPoints() + "/" + type.hitPoints();
        GameFont face = smallFont == null ? font : smallFont;
        int figureWidth = face == null ? figure.length() * 6 : face.widthOf(figure);
        int figureX = Math.max(2, x + (width - figureWidth) / 2);
        if (face == null) {
            g2.setColor(GameFont.colourOf(normalInk));
            g2.drawString(figure, figureX, top + HEALTH_TEXT_Y + 10);
            return;
        }
        face.draw(g2, figure, figureX, top + HEALTH_TEXT_Y, normalInk);
    }

    /** {@code Pos = {8, 51}}, {@code Height = 7}. */
    private static final int LIFE_BAR_Y = 51;

    private static final int LIFE_BAR_HEIGHT = 7;

    /** {@code Pos = {35, 61}}, in the small face. */
    private static final int HEALTH_TEXT_Y = 61;

    /**
     * The numbers a player compares units by, down the right of the picture.
     *
     * <p>Armour and damage come through the owner's research rather than off
     * the type, because Warcraft II's upgrades are army-wide.
     *
     * <p>The script gives four rows -- 71, 86, 102 and 118 -- and ties each one
     * to a particular figure, so a peasant, which has an armour and a sight and
     * neither of the two between them, gets its two lines forty-seven pixels
     * apart with a hole in the middle. That reads as something having failed to
     * draw. The rows here are filled in order instead, so a unit with two lines
     * gets the first two rows and one with four gets all four.
     *
     * <p>The column is the script's 100 whenever the widest line fits in what
     * is left of the panel, and moves left far enough when it does not:
     * "Damage 12-25" on a fully upgraded knight is seventy-eight pixels in this
     * face, and a hundred plus seventy-eight is past the edge of a panel a
     * hundred and seventy-six wide.
     */
    private void drawStatistics(Graphics2D g2, Unit unit, UnitType type, int top) {
        var upgrades = world.upgrades(unit.player());
        java.util.List<String> stats = new java.util.ArrayList<>();
        if (upgrades != null) {
            stats.add("Armour " + upgrades.armor(type));
            if (type.canAttack()) {
                stats.add("Damage " + upgrades.basicDamage(type)
                        + "-" + (upgrades.basicDamage(type) + upgrades.piercingDamage(type)));
                stats.add("Range " + type.maxAttackRange());
            }
            stats.add("Sight " + upgrades.sightRange(type));
        }
        boolean caster = unit.isCaster() && type.mana() > 0;
        if (caster) {
            stats.add("Mana");
        } else if (unit.carrying() != null && unit.carried() > 0) {
            stats.add("Carry " + unit.carried() + " " + unit.carrying().name()
                    .toLowerCase(java.util.Locale.ROOT));
        }

        int widest = 0;
        for (String line : stats) {
            widest = Math.max(widest, textWidth(line));
        }
        // Centred on the same axis the name is centred on, rather than started
        // at the script's literal 100. The script wrote 100 for a bitmap face
        // several pixels narrower per glyph than this one: "Damage 6-9" set
        // there runs to 168 of a 176-wide panel, so the block ends up jammed
        // against the right edge and twenty pixels off the title it sits
        // under, with forty pixels of dead panel beside the picture. Following
        // the number rather than what the number was for gets the layout the
        // script was describing.
        int column = NAME_CENTRE_X - widest / 2;
        column = Math.min(column, WIDTH - STAT_MARGIN - widest);
        // Never so far left that it runs under the picture and the health
        // figure, which own everything to the left of this column.
        column = Math.max(column, singleSelectedBounds().x + singleSelectedBounds().width + 6);

        // Anchored a fixed breath under the name rather than floated in the
        // room below it. The block used to centre itself between the name and
        // the foot of the panel, and the roomier the panel the further it
        // drifted from the title it belongs to -- a player at interface two
        // sent the screenshot: title at the top, four white lines adrift half
        // a panel below, "a bit of an eyesore alignment and spacing wise".
        // The name is the block's heading, and a heading and its figures sit
        // together; the empty stone below is simply empty, which reads as
        // calm rather than as something missing.
        int rows = Math.min(stats.size(), STAT_ROWS.length);
        int blockHeight = STAT_ROWS[rows - 1] - STAT_ROWS[0] + lineHeight();
        // drawName writes in the portrait slot's coordinates and the rows are
        // measured from the panel's, so the two are reconciled here rather
        // than by assuming they agree.
        int from = nameBottom(type.name()) + STAT_GAP;
        int floor = top + INFO_PANEL_FRAME_HEIGHT - STAT_MARGIN;
        int first = Math.min(from, Math.max(top, floor - blockHeight));

        for (int i = 0; i < rows; i++) {
            text(g2, stats.get(i), column,
                    first + STAT_ROWS[i] - STAT_ROWS[0], normalInk);
        }

        // Mana, for anything that has a pool. Without this a caster's whole
        // resource is invisible: it is spent, it regenerates, and the player
        // has no way to see either. The frame the backdrop uses for a caster
        // has the well for it cut in at this height, and the script gives the
        // bar sixty by fourteen with the figure written across it.
        if (caster) {
            int barY = first + blockHeight + 3;
            bar(g2, column, barY, MANA_BAR_WIDTH, MANA_BAR_HEIGHT,
                    unit.mana() / (double) type.mana(), MANA);
            String figure = unit.mana() + "/" + type.mana();
            GameFont face = smallFont == null ? font : smallFont;
            if (face != null) {
                face.draw(g2, figure,
                        column + (MANA_BAR_WIDTH - face.widthOf(figure)) / 2,
                        barY + (MANA_BAR_HEIGHT - face.height()) / 2, normalInk);
            }
        }
    }

    /** {@code Pos = {100, 71}} and the three rows under it. */
    private static final int[] STAT_ROWS = {71, 86, 102, 118, 133};

    /** The column those five rows share. */
    private static final int STAT_COLUMN = 100;

    /** How much bare panel to leave to the right of the longest line. */
    private static final int STAT_MARGIN = 8;

    /** {@code Width = 60, Height = 14} on the mana bar. */
    private static final int MANA_BAR_WIDTH = 60;

    private static final int MANA_BAR_HEIGHT = 14;

    /** The "light-blue" the script fills the mana bar with. */
    private static final Color MANA = new Color(70, 110, 235);

    /**
     * The lone unit's picture, in the slot the layout keeps for it.
     *
     * <p>{@code UI.SingleSelectedButton}. Every shipped layout declares it and
     * nothing read it: the panel showed a name where the original shows a
     * face, which is the one thing on the panel a player recognises without
     * reading.
     */
    private void drawSinglePortrait(Graphics2D g2, Unit unit) {
        java.awt.Rectangle slot = singleSelectedBounds();
        PanelArt.sunken(g2, slot.x - 2, slot.y - 2, slot.width + 4, slot.height + 4,
                StoneTexture.Tint.STONE);
        drawPortrait(g2, unit, slot.x, slot.y);
    }

    /** Where the lone portrait sits, from {@code UI.SingleSelectedButton}. */
    java.awt.Rectangle singleSelectedBounds() {
        int x = layout != null && layout.singleSelected().width() > 0
                ? layout.singleSelected().x() : 9;
        int y = layout != null && layout.singleSelected().width() > 0
                ? layout.singleSelected().y() : MINIMAP_FRAME_HEIGHT + 33;
        return new java.awt.Rectangle(x, y, ICON_WIDTH, ICON_HEIGHT);
    }

    /** How wide the name may run before it wraps, from {@code Line(.., 110..)}. */
    private static final int NAME_WIDTH = 110;

    /** Splits a unit name over the two lines the panel keeps for it. */
    private java.util.List<String> nameLines(String name) {
        if (name == null || name.isBlank()) {
            return List.of();
        }
        if (textWidth(name) <= NAME_WIDTH || !name.contains(" ")) {
            return List.of(name);
        }
        // One break, at the last space that still fits: the original allows
        // two lines and no more.
        int split = name.lastIndexOf(' ');
        while (split > 0) {
            String head = name.substring(0, split);
            if (textWidth(head) <= NAME_WIDTH) {
                return List.of(head, name.substring(split + 1));
            }
            split = name.lastIndexOf(' ', split - 1);
        }
        return List.of(name);
    }

    /** Every unit the local player currently has selected. */
    private java.util.List<Unit> selectedUnits() {
        java.util.List<Unit> found = new java.util.ArrayList<>();
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.selected() && unit.isAlive()) {
                found.add(unit);
            }
        }
        return found;
    }

    /**
     * A row of portraits, one per selected unit.
     *
     * <p>Nine at most, which is what the panel and the native selection packet
     * both hold. The input boundary rejects a tenth before this is drawn.
     */
    private void drawGroup(Graphics2D g2, java.util.List<Unit> group) {
        // AddSelectedButton places these: three columns at 9, 65 and 121 and
        // three rows at the info panel's y plus 9, 63 and 117.
        var boxes = layout == null || layout.selectedButtons().size() < 9
                ? null
                : layout.selectedButtons();
        int fallbackTop = MINIMAP_FRAME_HEIGHT + 16;
        int slots = boxes == null ? 9 : boxes.size();
        int shown = Math.min(slots, group.size());
        for (int i = 0; i < shown; i++) {
            Unit unit = group.get(i);
            int x = boxes != null ? boxes.get(i).x() : 12 + (i % 3) * 48;
            int y = boxes != null ? boxes.get(i).y() : fallbackTop + (i / 3) * 48;
            PanelArt.sunken(g2, x - 2, y - 2, ICON_WIDTH + 4, ICON_HEIGHT + 4,
                    StoneTexture.Tint.STONE);
            drawPortrait(g2, unit, x, y);
            // Under the frame rather than across its bottom edge, and the
            // width of it rather than six pixels short: a row of bars that all
            // stop before the picture above them do is the sort of thing that
            // makes a grid look hand-placed.
            bar(g2, x - 2, y + ICON_HEIGHT + 3, ICON_WIDTH + 4, 4,
                    unit.hitPoints() / (double) Math.max(1, unit.type().hitPoints()),
                    healthColour(unit.hitPoints(), unit.type().hitPoints()));
        }
        if (group.size() > slots) {
            // UI.MaxSelectedTextX and Y, which is where
            // InfoPanel_draw_multiple_selection puts the overflow count. The
            // port wrote it under the last portrait, which on a full grid is
            // off the bottom of the panel.
            var where = maxSelectedTextAt();
            String count = "+" + (group.size() - slots);
            // On a dark plate. The script puts this on the corner of the first
            // portrait, and a portrait is a painting: white lettering with no
            // outline laid straight on a silver helmet cannot be read.
            int width = (font == null ? count.length() * 7 : font.widthOf(count)) + 4;
            g2.setColor(new Color(0, 0, 0, 190));
            g2.fillRect(where.x - 2, where.y - 1, width, lineHeight() + 2);
            text(g2, count, where.x, where.y, normalInk);
        }
    }

    /** Where the overflow count goes, from {@code UI.MaxSelectedText}. */
    java.awt.Point maxSelectedTextAt() {
        if (layout != null && layout.maxSelectedTextY() > 0) {
            return new java.awt.Point(layout.maxSelectedTextX(), layout.maxSelectedTextY());
        }
        return new java.awt.Point(10, MINIMAP_FRAME_HEIGHT + 34);
    }

    /**
     * The bar a building fills as it works.
     *
     * <p>{@code CContentTypeCompleteBar}, in the place and at the size
     * {@code scripts/ui.legacy-declaration} declares -- 12, 313, a hundred and fifty two by
     * fourteen -- and in the colour {@code UI.CompletedBarColorRGB} names. The
     * port printed "Building 47%" instead, in a place the original keeps for
     * nothing, and the colour the script has been declaring all along was read
     * and thrown away.
     */
    private void completedBar(Graphics2D g2, int panelTop, double fraction) {
        int x = 12;
        int y = panelTop + COMPLETED_BAR_Y;
        int width = COMPLETED_BAR_WIDTH;
        int height = COMPLETED_BAR_HEIGHT;
        double filled = Math.max(0, Math.min(1, fraction));
        int across = (int) (width * filled);

        g2.setColor(new Color(20, 20, 24));
        g2.fillRect(x, y, width, height);
        g2.setColor(completedBarColour());
        g2.fillRect(x, y, across, height);
        if (completedBarShadow()) {
            // UI.CompletedBarShadow: a grey line down the leading edge and
            // along the bottom, a white one along the top and left. Off in
            // every layout ChonkCraft ships, which is exactly why it has to be
            // read rather than assumed one way or the other.
            g2.setColor(Color.GRAY);
            g2.drawLine(x + across, y, x + across, y + height - 1);
            g2.drawLine(x, y + height, x + across, y + height);
            g2.setColor(Color.WHITE);
            g2.drawLine(x, y, x, y + height - 1);
            g2.drawLine(x, y, x + across, y);
        }
        // "% Complete". The script writes it across the bar, at {50, 154}
        // against a bar at {12, 153}, and gets away with it because its face
        // carries a black outline of its own: white lettering laid over the
        // join between a green fill and a black bed with no outline is
        // readable for exactly as long as the join is not under a letter. So
        // it goes immediately above instead, on bare stone, centred on the bar
        // it belongs to.
        String label = (int) Math.round(filled * 100) + "% Complete";
        textCentred(g2, label, x + width / 2, y - lineHeight() - 3, normalInk);
    }

    /** From {@code Pos = {12, 153}} in {@code panel-general-contents}. */
    private static final int COMPLETED_BAR_Y = 153;

    private static final int COMPLETED_BAR_WIDTH = 152;

    private static final int COMPLETED_BAR_HEIGHT = 14;

    /** {@code UI.CompletedBarColorRGB}, or the game's own green if unread. */
    private Color completedBarColour() {
        int declared = layout == null ? -1 : layout.completedBarColour();
        return declared < 0 ? COMPLETED_BAR : new Color(declared);
    }

    /** {@code UI.CompletedBarShadow}. */
    private boolean completedBarShadow() {
        return layout != null && layout.completedBarShadow();
    }

    /**
     * What a building is making, as an icon with a bar under it.
     *
     * <p>{@code UI.SingleTrainingButton}, and the upgrading and researching
     * buttons declared in the same place, because Warcraft II builds one thing
     * at a time. The implementation said "Training Peasant" in words: you could not see
     * what was coming without reading, and the icon is the thing a player
     * actually looks for.
     *
     * <p>Clicking it cancels, which is how the original works and is why the
     * bounds are published rather than kept here.
     */
    private void drawProduction(Graphics2D g2, Unit unit, int panelTop) {
        java.awt.Rectangle slot = productionBounds();
        String icon = producingIcon(unit);
        int frame = icon == null ? -1 : icons.icons().frame(icon);

        // The well the icon sits in, so it reads as a button rather than a
        // picture floating on the panel.
        PanelArt.sunken(g2, slot.x - 2, slot.y - 2, slot.width + 4, slot.height + 4,
                StoneTexture.Tint.STONE);
        if (iconSheet != null && frame >= 0) {
            int perRow = Math.max(1, iconSheet.getWidth() / ICON_WIDTH);
            int sx = (frame % perRow) * ICON_WIDTH;
            int sy = (frame / perRow) * ICON_HEIGHT;
            if (sy + ICON_HEIGHT <= iconSheet.getHeight()) {
                g2.drawImage(iconSheet, slot.x, slot.y,
                        slot.x + slot.width, slot.y + slot.height,
                        sx, sy, sx + ICON_WIDTH, sy + ICON_HEIGHT, null);
            }
        }

        String what = unit.producing() != null
                ? unit.producing().name()
                : unit.upgradingTo() != null
                ? unit.upgradingTo().name()
                : readable(unit.researching());
        // Left of the icon rather than on it. The script's own column for
        // these words is 100, which is where the icon slot begins: upstream
        // gets away with it because its research panel draws no icon and its
        // training panel draws no words.
        //
        // Ranged against the middle of the icon, not started at a row of its
        // own, because the two are one statement -- this is what is being
        // made, and this is its picture -- and a caption whose baseline has
        // nothing to do with the thing it captions is the clearest sign of a
        // panel laid out by eye. Long upgrade names wrap into the same block,
        // which is why the room is measured rather than assumed: "stronger
        // throwing" at one line would have run under the icon.
        java.util.List<String> said = new java.util.ArrayList<>();
        // The script's own three captions: "Researching~|:" under
        // {Research = "only"} and "Upgrading~|:" under {UpgradeTo = "only"};
        // the training panel has no caption of its own and takes the word from
        // UI.SingleTrainingText.
        said.add(unit.producing() != null ? "Training"
                : unit.upgradingTo() != null ? "Upgrading" : "Researching");
        said.addAll(wrapped(what, slot.x - PRODUCTION_TEXT_X - 6));
        int stride = lineHeight() + 3;
        int y = slot.y + (slot.height - said.size() * stride + 3) / 2;
        for (int i = 0; i < said.size(); i++) {
            text(g2, said.get(i), PRODUCTION_TEXT_X, y,
                    i == 0 ? normalInk : GameFont.Ink.YELLOW);
            y += stride;
        }

        completedBar(g2, panelTop, unit.progressFraction());
    }

    /** The words go where the script's own left-hand column starts. */
    private static final int PRODUCTION_TEXT_X = 12;

    /** The upgrade's name without its prefix, which is not for reading. */
    private static String readable(String ident) {
        if (ident == null) {
            return "";
        }
        String name = ident.startsWith("upgrade-") ? ident.substring(8) : ident;
        return name.replace('-', ' ');
    }

    /** The icon of whatever a building is currently making. */
    private String producingIcon(Unit unit) {
        if (unit.producing() != null) {
            return unit.producing().icon();
        }
        // What it is turning into, which does have an icon of its own -- the
        // keep's, not the town hall's.
        if (unit.upgradingTo() != null) {
            return unit.upgradingTo().icon();
        }
        if (unit.researching() != null) {
            // An upgrade carries no icon of its own; the icon belongs to the
            // button that buys it, which is where the scripts put it.
            for (var button : icons.buttons().all()) {
                if ("research".equals(button.action())
                        && unit.researching().equals(button.value())) {
                    return button.icon();
                }
            }
        }
        return null;
    }

    /**
     * Where the production icon is, in the interface's own pixels.
     *
     * <p>Published because the click that cancels is handled by the screen.
     */
    static java.awt.Rectangle productionBounds() {
        return new java.awt.Rectangle(productionX, productionY, ICON_WIDTH, ICON_HEIGHT);
    }

    private static int productionX = 110;
    private static int productionY = 241;

    /** {@code UI.CompletedBarColorRGB = CColor(48, 100, 4)}. */
    private static final Color COMPLETED_BAR = new Color(48, 100, 4);

    /** One unit's icon, or its name if the icon cannot be found. */
    private void drawPortrait(Graphics2D g2, Unit unit, int x, int y) {
        String named = unit.type().icon();
        int frame = named == null || named.isBlank() ? -1 : icons.icons().frame(named);
        if (iconSheet != null && frame >= 0) {
            int perRow = Math.max(1, iconSheet.getWidth() / ICON_WIDTH);
            int sx = (frame % perRow) * ICON_WIDTH;
            int sy = (frame / perRow) * ICON_HEIGHT;
            if (sy + ICON_HEIGHT <= iconSheet.getHeight()) {
                g2.drawImage(iconSheet, x, y, x + ICON_WIDTH, y + ICON_HEIGHT,
                        sx, sy, sx + ICON_WIDTH, sy + ICON_HEIGHT, null);
                return;
            }
        }
        g2.setColor(Color.DARK_GRAY);
        g2.fillRect(x, y, ICON_WIDTH, ICON_HEIGHT);
    }

    /**
     * A filled bar with a dark bed, the shape the game uses for everything.
     *
     * <p>Rectangles rather than a picture, so it is exact at any interface
     * size: a bar cut from a bitmap is the one thing on the panel that has to
     * change length every frame, and stretching a bitmap to a length is how
     * you get a bar with a smeared end on it. The bed is sunk with a dark rim
     * and the fill lit along its top, which is what makes it read as a gauge
     * rather than as two rectangles.
     */
    private void bar(Graphics2D g2, int x, int y, int width, int height,
            double fraction, Color colour) {
        if (width <= 0 || height <= 0) {
            return;
        }
        g2.setColor(new Color(12, 12, 16));
        g2.fillRect(x, y, width, height);
        int across = (int) Math.round(width * Math.max(0, Math.min(1, fraction)));
        if (across > 0) {
            g2.setColor(colour);
            g2.fillRect(x, y, across, height);
            if (height >= 5) {
                g2.setColor(new Color(255, 255, 255, 60));
                g2.fillRect(x, y, across, 1);
                g2.setColor(new Color(0, 0, 0, 70));
                g2.fillRect(x, y + height - 1, across, 1);
            }
        }
        g2.setColor(new Color(0, 0, 0, 150));
        g2.drawRect(x, y, width - 1, height - 1);
    }

    /** Green, amber then red, as health falls. */
    private static Color healthColour(int hitPoints, int max) {
        double fraction = hitPoints / (double) Math.max(1, max);
        if (fraction > 0.66) {
            return new Color(70, 190, 70);
        }
        return fraction > 0.33 ? new Color(210, 180, 60) : new Color(200, 60, 60);
    }

    /**
     * Writes a line in the game's lettering, or the JVM's if there is none.
     *
     * <p>Falling back rather than drawing nothing: an installation whose
     * archive is missing a font should still be readable.
     */
    private void text(Graphics2D g2, String line, int x, int y, GameFont.Ink ink) {
        if (font != null) {
            font.draw(g2, line, x, y, ink);
            return;
        }
        g2.setColor(GameFont.colourOf(ink));
        g2.drawString(line, x, y + 11);
    }

    /**
     * The same, centred on a point, which is how the panel places its names.
     *
     * <p>Kept inside the column. The script centres the hit point figure on
     * x 35, which is under the portrait and fine for "60/60"; a town hall's
     * "1200/1200" centred there begins at minus one and the first digit is cut
     * off by the edge of the screen.
     */
    private void textCentred(Graphics2D g2, String line, int centreX, int y,
            GameFont.Ink ink) {
        int width = font == null ? line.length() * 6 : font.widthOf(line);
        int x = Math.max(4, Math.min(centreX - width / 2, WIDTH - 4 - width));
        text(g2, line, x, y, ink);
    }

    /** How tall a line of the game's lettering is. */
    private int lineHeight() {
        return font == null ? 13 : font.height();
    }

    /**
     * The counts along the top bar.
     *
     * <p>Warcraft II shows an icon and a number, not a labelled figure. The
     * layout script declares seven slots and this implementation drew four of them: the
     * score and the idle worker count -- {@code UI.Resources[ScoreCost]} and
     * {@code [FreeWorkersCount]} -- were parsed but never filled in.
     *
     * <p>All six visible counters share one spacing rule: icon, four clear
     * pixels, value, fourteen clear pixels, next icon. That keeps each value
     * visibly attached to its own icon while preserving identical whitespace
     * between counter groups. A slot parked off screen, as the mana one is,
     * remains undrawn.
     *
     * <p>What the script cannot know is how wide the figures are. Its spacing
     * was measured for a bitmap face five pixels to the digit, and the last
     * count on the bar is declared twenty-two pixels from the right edge with
     * a sixteen pixel strip of art drawn over that edge; anything of two
     * digits ran under the strip. So the declared positions are treated as
     * where each count would like to be and {@link #layOutTopBar} moves the
     * ones that would not fit, which keeps the cluster safe at every interface
     * size rather than only at the one it was measured at.
     */
    private void drawResources(Graphics2D g2, int viewWidth) {
        Player player = world.player(localPlayer);
        double scale = PanelArt.scaleOf(g2);
        String[] counts = new String[RESOURCE_SLOTS];
        counts[GOLD_SLOT] = String.valueOf(player.get(UnitType.Resource.GOLD));
        counts[LUMBER_SLOT] = String.valueOf(player.get(UnitType.Resource.WOOD));
        counts[OIL_SLOT] = String.valueOf(player.get(UnitType.Resource.OIL));
        counts[FOOD_SLOT] = player.demand() + "/" + player.supply();
        counts[SCORE_SLOT] = String.valueOf(player.score());
        counts[WORKERS_SLOT] = String.valueOf(idleWorkers(world, localPlayer).size());

        int[] widths = new int[RESOURCE_SLOTS];
        for (int i = 0; i < RESOURCE_SLOTS; i++) {
            widths[i] = counts[i] == null ? -1 : textWidth(counts[i]);
        }
        topBar = layOutTopBar(layout == null ? null : layout.resources(), widths, viewWidth);

        for (TopBarCell cell : topBar) {
            var icon = ResourceIcons.of(ICON_KINDS[cell.slot()], RESOURCE_ICON, scale);
            if (icon != null) {
                g2.drawImage(icon, cell.iconX(), cell.iconY(),
                        RESOURCE_ICON, RESOURCE_ICON, null);
            }
            // DrawResources calls label.Draw for every count, which is the
            // default ink -- UI.NormalFontColor, white for a human player and
            // yellow for an orc. This said Ink.YELLOW outright, so a human
            // mission's counts came out in the orcs' colour.
            //
            // The over-supply figure is upstream's one label.DrawReverse on
            // this bar, and it stays red here rather than following
            // UI.ReverseFontColor. That is a deviation: red is a warning
            // colour in a way that the human interface's declared reverse ink
            // -- yellow, which is what every other count would be drawn in on
            // an orc mission -- is not, and "you cannot train anything" is the
            // one thing on this bar that has to be noticed.
            GameFont.Ink ink = cell.slot() == FOOD_SLOT && player.supply() < player.demand()
                    ? GameFont.Ink.RED
                    : normalInk;
            text(g2, counts[cell.slot()], cell.textX(), cell.textY(), ink);
        }
    }

    /** One count in the top bar, after the crowding has been resolved. */
    record TopBarCell(int slot, int iconX, int iconY, int textX, int textY, int width) {

        /** Everything the count covers, icon and figure together. */
        java.awt.Rectangle bounds() {
            return new java.awt.Rectangle(iconX, 0, width, TOP_BAR_HEIGHT);
        }
    }

    /** Where the counts ended up last time the bar was drawn. */
    private java.util.List<TopBarCell> topBar = java.util.List.of();

    /**
     * Where the counts actually went, for tests and render probes.
     *
     * <p>The result of the last draw rather than a fresh calculation, so a
     * test can ask what a real screen at a real scale did rather than what
     * {@link #layOutTopBar} would do if handed the same numbers.
     */
    java.util.List<TopBarCell> topBarForTest() {
        return java.util.List.copyOf(topBar);
    }

    /**
     * The bevel {@link PanelArt} lays down each edge of a slab.
     *
     * <p>The marble strip along the top is sixteen pixels tall and two of those
     * at each edge are its moulding, so the field an icon may sit in is the
     * twelve between them.
     */
    static final int TOP_BAR_BEVEL = 2;

    /**
     * How big a resource icon is drawn.
     *
     * <p>The script declares fourteen -- {@code CGraphic:New("ui/gold,wood,
     * oil,mana.png", 14, 14)} -- and draws it at {@code IconY = 0}, which on
     * the original art was right because that art is a flat strip with no
     * moulding on it. This implementation generates the strip, and a generated slab has a
     * two pixel bevel top and bottom; fourteen pixels of icon at y nought or
     * one therefore crossed it at both ends, and the gold line round the icon
     * stood proud of the marble it was supposed to be sitting on. So the icon
     * is the height of the field rather than the height of the strip.
     */
    static final int RESOURCE_ICON = TOP_BAR_HEIGHT - TOP_BAR_BEVEL * 2;

    /** The strip of art down the right hand edge, from {@code filler-right}. */
    private static final int RIGHT_FILLER = 16;

    /** How much clear bar to leave between two counts. */
    static final int COUNT_GAP = 14;

    /**
     * Which count gives way first when the bar is too narrow to hold them all.
     *
     * <p>A player can lose the score without losing the game; they cannot lose
     * the gold. At four times the interface size in a 1024 pixel window the bar
     * is sixty-four design pixels wide, which is one count and no more, so
     * something has to give and it had better be the right thing.
     */
    private static final int[] GIVE_WAY = {SidePanel.SCORE_SLOT, SidePanel.WORKERS_SLOT,
        SidePanel.OIL_SLOT, SidePanel.FOOD_SLOT, SidePanel.LUMBER_SLOT};

    /**
     * Places the counts along the bar, moving any that would not fit.
     *
     * <p>Every visible count advances by its icon-plus-value width and the same
     * clear gap. This deliberately measures visual groups rather than icon
     * centers: fixed-width number fields detach a one-digit value from its
     * icon, while fixed icon positions leave different amounts of whitespace
     * after values with different digit counts. If the group reaches the
     * right trim, the least important count is dropped and the whole thing is
     * tried again.
     *
     * <p>Static and given its measurements rather than taking them, so a test
     * can ask where a bar of any width would put a count of any length.
     *
     * @param declared    the script's slots, or {@code null} if it was unreadable
     * @param textWidths  how wide each figure draws, or negative for a slot
     *                    that carries no figure
     * @param viewWidth   the width of the whole interface, in design pixels
     */
    static java.util.List<TopBarCell> layOutTopBar(
            java.util.List<net.chonkbase.chonkcraft.engine.ui.UiLayout.ResourceSlot> declared,
            int[] textWidths, int viewWidth) {
        java.util.List<Integer> shown = new java.util.ArrayList<>();
        for (int i = 0; i < textWidths.length; i++) {
            if (textWidths[i] < 0) {
                continue;
            }
            int iconX = declaredIconX(declared, i);
            int textX = declaredTextX(declared, i);
            // The mana slot is parked at minus a hundred by every layout the
            // game ships, which is how DrawResources is told not to draw it.
            if (iconX < 0 || textX < 0) {
                continue;
            }
            shown.add(i);
        }

        for (int attempt = 0; attempt <= GIVE_WAY.length; attempt++) {
            java.util.List<TopBarCell> placed = place(declared, textWidths, viewWidth, shown);
            if (placed != null) {
                return placed;
            }
            if (attempt == GIVE_WAY.length) {
                break;
            }
            shown.remove(Integer.valueOf(GIVE_WAY[attempt]));
        }
        return java.util.List.of();
    }

    /** One pass at placing a set of counts, or null when they will not fit. */
    private static java.util.List<TopBarCell> place(
            java.util.List<net.chonkbase.chonkcraft.engine.ui.UiLayout.ResourceSlot> declared,
            int[] textWidths, int viewWidth, java.util.List<Integer> shown) {
        java.util.Map<Integer, Integer> at = new java.util.LinkedHashMap<>();

        // One left-aligned group, in the order the script declares it. Start
        // inside the strip's own moulding, then place every counter by the
        // same icon/value/gap rule. Mixing fixed positions for the first three
        // with packed positions for the last three caused the original uneven
        // bar; fixed-width value fields then detached single digits from their
        // icons. Content-width placement avoids both failures.
        int cursor = WIDTH + TOP_BAR_BEVEL;
        for (int slot : shown) {
            int x = cursor;
            at.put(slot, x);
            cursor = x + cellWidth(declared, textWidths, slot) + COUNT_GAP;
        }

        java.util.List<TopBarCell> cells = new java.util.ArrayList<>();
        for (int slot : shown) {
            Integer x = at.get(slot);
            if (x == null || x < WIDTH + TOP_BAR_BEVEL) {
                return null;
            }
            int width = cellWidth(declared, textWidths, slot);
            if (x + width > viewWidth - RIGHT_FILLER) {
                return null;
            }
            int offset = declaredTextX(declared, slot) - declaredIconX(declared, slot);
            cells.add(new TopBarCell(slot, x, TOP_BAR_BEVEL,
                    x + (offset > 0 ? offset : RESOURCE_ICON + 4),
                    declaredTextY(declared, slot), width));
        }
        // Nothing may overlap its neighbour, which is the whole point.
        cells.sort(java.util.Comparator.comparingInt(TopBarCell::iconX));
        for (int i = 1; i < cells.size(); i++) {
            if (cells.get(i).iconX() < cells.get(i - 1).iconX() + cells.get(i - 1).width()) {
                return null;
            }
        }
        return cells;
    }

    /** Icon, gap and figure: what one count actually covers. */
    private static int cellWidth(
            java.util.List<net.chonkbase.chonkcraft.engine.ui.UiLayout.ResourceSlot> declared,
            int[] textWidths, int slot) {
        int offset = declaredTextX(declared, slot) - declaredIconX(declared, slot);
        return (offset > 0 ? offset : RESOURCE_ICON + 4) + textWidths[slot];
    }

    /**
     * The script's own left edge for a count, or a spread of the bar.
     *
     * <p>The fallback is only ever reached by an installation whose interface
     * scripts could not be read at all.
     */
    private static int declaredIconX(
            java.util.List<net.chonkbase.chonkcraft.engine.ui.UiLayout.ResourceSlot> declared,
            int slot) {
        return declared != null && declared.size() > slot
                ? declared.get(slot).iconX()
                : WIDTH + 16 + slot * 90;
    }

    private static int declaredTextX(
            java.util.List<net.chonkbase.chonkcraft.engine.ui.UiLayout.ResourceSlot> declared,
            int slot) {
        return declared != null && declared.size() > slot
                ? declared.get(slot).textX()
                : declaredIconX(declared, slot) + RESOURCE_ICON + 4;
    }

    private static int declaredTextY(
            java.util.List<net.chonkbase.chonkcraft.engine.ui.UiLayout.ResourceSlot> declared,
            int slot) {
        return declared != null && declared.size() > slot ? declared.get(slot).textY() : 1;
    }

    /**
     * Where the idle worker count sits, or {@code null} if it is not shown.
     *
     * <p>Published because clicking it is half of what it is for:
     * {@code UiFindIdleWorker} walks the view to the next worker standing
     * about. A number that only tells you there is a problem, with no way to
     * go and look at it, is a number a player learns to ignore.
     *
     * <p>Where it was actually drawn, not where the script asked for it: on a
     * narrow bar the two are not the same place, and a click has to land on
     * what the player can see.
     */
    java.awt.Rectangle freeWorkersBounds() {
        for (TopBarCell cell : topBar) {
            if (cell.slot() == WORKERS_SLOT) {
                return cell.bounds();
            }
        }
        return null;
    }

    /**
     * What each count along the top bar actually means.
     *
     * <p>Warcraft II explains none of this anywhere in the game, and three of
     * the six figures are not self-evident: oil comes from one place and buys
     * one kind of thing, the food figure is two numbers whose order matters,
     * and the score does nothing at all. A player who has never read the manual
     * has no way to find any of it out.
     *
     * <p>The first line is the name and the rest is prose, wrapped to the box.
     * Upstream has no tooltip on the resource bar to take wording from -- the
     * popups it does have are the command buttons' -- so all of this is
     * written for this implementation. The idle worker line in particular describes
     * behaviour ({@code UiFindIdleWorker}) that upstream never labels.
     */
    private final String[][] countMeaning = new String[SidePanel.RESOURCE_SLOTS][];

    /**
     * The picture on each count's card: the thing itself, in the game's own
     * pixels.
     *
     * <p>Gold is the mine, food the farm and the idle count the worker, each
     * cut from the sprite the current tileset draws them with, so a winter
     * map explains itself with snowed-in art. Lumber and oil arrive later,
     * through {@link #setTerrainSamples}: the trees are a crop of the map's
     * own forest -- the right trees for the map, not a stock picture -- and
     * the oil card is the map's own water with the slick sprite laid over
     * it, which is exactly what a player is being taught to look for.
     */
    private final BufferedImage[] countThumbs = new BufferedImage[SidePanel.RESOURCE_SLOTS];

    /** The slick sprite, waiting for water to sit on. */
    private final BufferedImage oilPatchArt;

    /**
     * Hands the panel crops of the live map for the lumber and oil cards.
     *
     * <p>From the screen, because the screen owns the rasterised terrain and
     * the panel deliberately does not: the panel's map knowledge is the
     * minimap's colour table. Either may be null when a map has no forest or
     * no sea, and the card simply goes without its picture.
     */
    void setTerrainSamples(BufferedImage forest, BufferedImage water) {
        countThumbs[LUMBER_SLOT] = forest;
        if (water != null) {
            BufferedImage sea = new BufferedImage(water.getWidth(), water.getHeight(),
                    BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = sea.createGraphics();
            g2.drawImage(water, 0, 0, null);
            if (oilPatchArt != null) {
                // The slick over the water, as the map shows it.
                g2.drawImage(oilPatchArt, 0, 0, sea.getWidth(), sea.getHeight(), null);
            }
            g2.dispose();
            countThumbs[OIL_SLOT] = sea;
        }
    }

    /** How wide the explaining card is allowed to be, in design pixels. */
    private static final int TOOLTIP_WIDTH = 230;

    /** The card's picture, framed: a square this size on the card's left. */
    private static final int TOOLTIP_THUMB = 44;

    /**
     * The count the pointer is resting on, or {@code -1}.
     *
     * <p>The hit area is the icon and the figure together: a player aims at
     * the pair, not at the fourteen pixels of picture.
     */
    int countAt(int x, int y) {
        for (TopBarCell cell : topBar) {
            if (cell.bounds().contains(x, y)) {
                return cell.slot();
            }
        }
        return -1;
    }

    /**
     * Says what a count means while the pointer rests on it.
     *
     * <p>The same box {@code GameScreen} puts under a command button, in the
     * same colours, because two kinds of hover panel on one screen is one kind
     * too many.
     */
    private void drawCountTooltip(Graphics2D g2, int viewWidth, int viewHeight) {
        java.awt.Point at = pointerAt(g2);
        int slot = at == null ? -1 : countAt(at.x, at.y);
        String[] meaning = slot < 0 ? null : countMeaning[slot];
        if (meaning == null) {
            return;
        }
        java.awt.Rectangle cell = null;
        for (TopBarCell candidate : topBar) {
            if (candidate.slot() == slot) {
                cell = candidate.bounds();
            }
        }
        if (cell == null) {
            return;
        }
        BufferedImage thumb = countThumbs[slot];

        // A card, not a paragraph: the picture on the left, the name and one
        // sentence beside it. Sized from its contents up to a comfortable
        // measure, and never wider than the window leaves room for.
        int room = Math.max(96, Math.min(TOOLTIP_WIDTH, viewWidth - 8));
        int thumbSpan = thumb == null ? 0 : TOOLTIP_THUMB + 6;
        java.util.List<String> lines = wrapped(meaning[1], room - thumbSpan - 16);
        int width = textWidth(meaning[0]);
        for (String line : lines) {
            width = Math.max(width, textWidth(line));
        }
        int boxWidth = Math.min(room, thumbSpan + width + 16);
        int textHeight = (lines.size() + 1) * lineHeight();
        int boxHeight = Math.max(thumb == null ? 0 : TOOLTIP_THUMB + 12,
                textHeight + 12);
        // Under the bar and starting at the count it explains, pushed back on
        // screen if the count is near the right hand end. Allowed over the
        // sidebar, which on a narrow screen is the only way it fits at all: it
        // is gone the moment the pointer moves.
        int boxX = Math.max(4,
                Math.min(cell.x, viewWidth - RIGHT_FILLER - boxWidth - 4));
        int boxY = Math.min(TOP_BAR_HEIGHT + 4, Math.max(0, viewHeight - boxHeight - 2));

        g2.setColor(new Color(40, 40, 40, 220));
        g2.fillRect(boxX, boxY, boxWidth, boxHeight);
        g2.setColor(new Color(180, 180, 220, 200));
        g2.drawRect(boxX, boxY, boxWidth, boxHeight);

        int textX = boxX + 8;
        if (thumb != null) {
            // The picture fills its square edge to edge, cropped rather than
            // squashed: a 96-pixel mine keeps its shape at 44.
            int side = Math.min(thumb.getWidth(), thumb.getHeight());
            int sourceX = (thumb.getWidth() - side) / 2;
            int sourceY = (thumb.getHeight() - side) / 2;
            int artX = boxX + 6;
            int artY = boxY + (boxHeight - TOOLTIP_THUMB) / 2;
            g2.drawImage(thumb, artX, artY, artX + TOOLTIP_THUMB, artY + TOOLTIP_THUMB,
                    sourceX, sourceY, sourceX + side, sourceY + side, null);
            g2.setColor(new Color(180, 180, 220, 120));
            g2.drawRect(artX - 1, artY - 1, TOOLTIP_THUMB + 1, TOOLTIP_THUMB + 1);
            textX = artX + TOOLTIP_THUMB + 6;
        }

        int y = boxY + (boxHeight - textHeight) / 2 + 1;
        text(g2, meaning[0], textX, y, GameFont.Ink.YELLOW);
        y += lineHeight();
        for (String line : lines) {
            text(g2, line, textX, y, normalInk);
            y += lineHeight();
        }
    }

    /** Breaks prose into lines no wider than a box. */
    private java.util.List<String> wrapped(String prose, int width) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : prose.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && textWidth(candidate) > width) {
                lines.add(line.toString());
                line = new StringBuilder(word);
            } else {
                line = new StringBuilder(candidate);
            }
        }
        if (!line.isEmpty()) {
            lines.add(line.toString());
        }
        return lines;
    }

    /** How wide a line draws in the panel's own face. */
    private int textWidth(String line) {
        return font == null ? line.length() * 7 : font.widthOf(line);
    }

    /** Where the pointer is, in design pixels, when something has said. */
    private java.awt.Point pointer;

    /**
     * Tells the panel where the pointer is, in its own design pixels.
     *
     * <p>The screen feeds this from its own mouse handling, dividing by the
     * interface scale it applied itself, and that is now the path that
     * matters. The AWT tracker below divides window coordinates by the full
     * transform scale -- which on a HiDPI display includes a device doubling
     * that mouse events are already normalised for -- so on such a display
     * at interface two, every hover landed at half its real position: the
     * gold card answered a pointer resting on the oil figure, and the four
     * counts on the right could not be hovered at all. The tracker stays as
     * the fallback for a panel drawn by something that never says where the
     * pointer is, and a test uses this the same way the screen does.
     */
    void setPointer(java.awt.Point designPoint) {
        this.pointer = designPoint;
    }

    /** The pointer in design pixels: what was set, or what the tracker saw. */
    private java.awt.Point pointerAt(Graphics2D g2) {
        if (pointer != null) {
            return pointer;
        }
        PointerTracker seen = tracker();
        return seen == null ? null : seen.designPoint(PanelArt.scaleOf(g2));
    }

    /**
     * Where the mouse last was, in the window it was in.
     *
     * <p>The panel is not a component: it is drawn into whatever graphics the
     * screen hands it, and the screen's own mouse handling belongs to the
     * screen. Rather than reach into it, this listens to the event queue --
     * which is the one place a thing that is not a component can still see the
     * pointer -- and converts what it saw into design pixels using the scale
     * already on the transform.
     */
    private static final class PointerTracker implements java.awt.event.AWTEventListener {

        private volatile java.awt.Component over;
        private volatile int x;
        private volatile int y;

        @Override
        public void eventDispatched(java.awt.AWTEvent event) {
            if (!(event instanceof java.awt.event.MouseEvent mouse)) {
                return;
            }
            if (mouse.getID() == java.awt.event.MouseEvent.MOUSE_EXITED) {
                over = null;
                return;
            }
            if (mouse.getID() != java.awt.event.MouseEvent.MOUSE_MOVED
                    && mouse.getID() != java.awt.event.MouseEvent.MOUSE_DRAGGED
                    && mouse.getID() != java.awt.event.MouseEvent.MOUSE_ENTERED) {
                return;
            }
            if (!(mouse.getSource() instanceof java.awt.Component source)) {
                return;
            }
            over = source;
            x = mouse.getX();
            y = mouse.getY();
        }

        /** The last point seen, in the design pixels a scale implies. */
        java.awt.Point designPoint(double scale) {
            java.awt.Component source = over;
            if (source == null || !source.isShowing() || scale <= 0) {
                return null;
            }
            return new java.awt.Point((int) (x / scale), (int) (y / scale));
        }
    }

    private static PointerTracker tracker;

    private static boolean trackerTried;

    /** The one tracker, installed the first time anything asks for it. */
    private static synchronized PointerTracker tracker() {
        if (!trackerTried) {
            trackerTried = true;
            if (!java.awt.GraphicsEnvironment.isHeadless()) {
                try {
                    PointerTracker made = new PointerTracker();
                    java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(made,
                            java.awt.AWTEvent.MOUSE_MOTION_EVENT_MASK
                                    | java.awt.AWTEvent.MOUSE_EVENT_MASK);
                    tracker = made;
                } catch (RuntimeException denied) {
                    // A security manager that will not have listeners is not a
                    // reason for the sidebar to stop drawing.
                    tracker = null;
                }
            }
        }
        return tracker;
    }

    /** Which drawn icon belongs to each slot, in the script's own order. */
    private static final ResourceIcons.Kind[] ICON_KINDS = {
        ResourceIcons.Kind.GOLD, ResourceIcons.Kind.LUMBER, ResourceIcons.Kind.OIL,
        ResourceIcons.Kind.FOOD, ResourceIcons.Kind.SCORE, null,
        ResourceIcons.Kind.WORKERS,
    };

    /**
     * The workers standing about doing nothing.
     *
     * <p>{@code CPlayer::UpdateFreeWorkers}: alive, on the map, able to
     * gather, and idle. Published because the count in the top bar is only
     * half of what upstream does with it -- clicking the figure walks the view
     * to the next one, which is the whole reason a player looks at it.
     */
    static java.util.List<Unit> idleWorkers(World world, int player) {
        java.util.List<Unit> found = new java.util.ArrayList<>();
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.player() == player && unit.isAlive() && unit.isOnMap()
                    && unit.type() != null && !unit.type().building()
                    && unit.type().canGather()
                    && unit.order() == Unit.Order.STILL
                    && unit.target() == null && unit.producing() == null) {
                found.add(unit);
            }
        }
        return found;
    }

    /** Where the counts sit in {@code UI.Resources}, less one for the array. */
    static final int GOLD_SLOT = 0;

    private static final int LUMBER_SLOT = 1;

    private static final int OIL_SLOT = 2;

    /** {@code FoodCost} is four, so the fourth slot. */
    private static final int FOOD_SLOT = 3;

    /** {@code ScoreCost}. */
    private static final int SCORE_SLOT = 4;

    /** {@code FreeWorkersCount}. The sixth is mana, which no layout shows. */
    private static final int WORKERS_SLOT = 6;

    /** How many of the eight declared slots carry a figure. */
    private static final int RESOURCE_SLOTS = 7;

    /**
     * The Menu button, above the minimap.
     *
     * <p>{@code UI.MenuButton} at 24, 2 with the caption "Menu (F10)". The
     * port read its position from the layout script and never drew it, which
     * left the only way into the menu a key that macOS takes for itself before
     * any application sees it. A game with no visible way to reach its own menu
     * is a game you cannot leave.
     */
    private void drawMenuButton(Graphics2D g2) {
        var box = menuButtonBounds();
        PanelArt.panel(g2, box.x, box.y, box.width, box.height, StoneTexture.Tint.SLATE);
        if (font != null) {
            font.drawCentred(g2, "Menu (F10)", box.x + box.width / 2,
                    box.y + (box.height - font.height()) / 2, normalInk);
        }
    }

    /**
     * Where the Menu button is, in the interface's own pixels.
     *
     * <p>Static because the hit test lives in the screen and the drawing lives
     * here, and they have to agree.
     */
    static java.awt.Rectangle menuButtonBounds() {
        return new java.awt.Rectangle(MENU_BUTTON_X, MENU_BUTTON_Y,
                MENU_BUTTON_WIDTH, MENU_BUTTON_HEIGHT);
    }

    /** From {@code UI.MenuButton.X} and {@code .Y} in the layout script. */
    private static final int MENU_BUTTON_X = 24;

    private static final int MENU_BUTTON_Y = 2;

    /**
     * The original button graphic's own width.
     *
     * <p>128, because that is what makes the script's {@code X = 24} a
     * design rather than an accident: 24 on the left and 24 on the right of
     * a 176-wide column is a centred button. This used to be 106 -- wide
     * enough for the caption and nothing else -- which kept the script's
     * left edge and gave up its symmetry, so the button sat visibly askew
     * in its strip, leaning left with forty-six pixels of dead stone after
     * it. A player sized it up at interface two and asked what the plan
     * was; this is the plan: the art's own proportions, centred caption,
     * filling the strip above the minimap edge to edge of its margins.
     */
    private static final int MENU_BUTTON_WIDTH = 128;

    private static final int MENU_BUTTON_HEIGHT = 20;

    /** The gold line round the minimap well, as the original art has. */
    private static final Color MINIMAP_EDGE = new Color(150, 122, 52);

}
