package net.chonkbase.chonkcraft.desktop;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.WritableRaster;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JPanel;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.Palette;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.construction.ConstructionCatalog;
import net.chonkbase.chonkcraft.engine.map.FogOfWar;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapRenderer;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.save.SaveGame;
import net.chonkbase.chonkcraft.engine.ui.FogOfWarSettings;
import net.chonkbase.chonkcraft.engine.unit.SpriteFrame;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * The playing field: scrolling terrain with live units on it.
 *
 * <p>Terrain is rendered once into a {@link BufferedImage}, because it does
 * not change between ticks and re-rasterising a 3072 by 3072 map every frame
 * would dominate the budget. Units are drawn over it each frame at their
 * current interpolated positions.
 */
final class GameScreen extends JPanel {

    private static final int SCROLL_PIXELS_PER_TICK = 8;
    private static final int TILE = 32;
    /** The native selection packet stores at most nine ordered unit slots. */
    private static final int MAX_SELECTED_UNITS = 9;

    private final World world;
    private final GameData data;

    /** Which side's interface art and layout to use. */
    private final String race;

    /**
     * The ink for HUD text the scripts do not colour explicitly.
     *
     * <p>{@code UI.NormalFontColor} -- yellow on an orc mission, white on a
     * human one. The status line said {@code Ink.WHITE} outright, so an orc
     * campaign showed a white status line under a yellow-lettered side panel.
     *
     * @see GameFont#normalInkFor(String)
     */
    private final GameFont.Ink normalInk;
    private final BufferedImage terrain;
    private final Palette palette;
    private final String tilesetName;

    private final AtomicBoolean[] held = new AtomicBoolean[4];
    private volatile int cameraX;
    private volatile int cameraY;

    /** Which player the mouse commands. */
    private final int localPlayer;
    private final net.chonkbase.chonkcraft.engine.sound.GameAudio audio;
    private final SidePanel panel;

    /**
     * The unit shown in the sidebar's info area.
     *
     * <p>Written in one place, {@link #selectionChanged}, and nowhere else.
     * {@code SelectionChangeTest} reads this file and fails the build on a
     * second assignment, because the nine sites that used to write it directly
     * are how a player came to click a town hall and be shown nothing at all.
     */
    private volatile Unit selected;

    /** The order in which the current units entered retail's selection list. */
    private final java.util.List<Integer> selectionOrder = new java.util.ArrayList<>(
            MAX_SELECTED_UNITS);

    /** Recent causal input included when the player captures evidence. */
    private final PlayerIntentJournal intents = new PlayerIntentJournal();

    private volatile String status = "";

    /** Player conversation, present only in a network match. */
    private InGameChat chat;

    /**
     * Where orders go: straight into the world, or onto the network.
     *
     * <p>The screen does not know which, and must not: a click that took a
     * shortcut in single player would be a different code path from the one a
     * networked game exercises, and the difference would only ever show up as
     * a desync.
     */
    private final CommandSink commands;

    /** Turns unit types, upgrades and spells into the indices a command carries. */
    private final CommandApplier applier;

    /** The game's own lettering, or null when the archive has no font. */
    private final GameFont font;

    /** The game's own pointers, or null when the archive has none. */
    private final GameCursors cursors;

    /** Which pointer is showing, so it is only swapped when it changes. */
    private GameCursors.Kind cursorKind;

    /**
     * Chooses the pointer for whatever is under it.
     *
     * <p>This is how the game answers "what would a click do here" before the
     * click. A pending command turns the pointer green over anything it can be
     * carried out on and crosses it out everywhere else, which is what makes
     * press-then-click legible.
     */
    /**
     * Puts the game's pointer back after the window has changed.
     *
     * <p>Moving into a full-screen Space hands the window to AppKit, which
     * resets its cursor; nothing tells the component, so the game silently
     * reverts to the system arrow and stays there. Leaving and re-entering
     * fixed it by accident, because that re-realises the component.
     */
    void refreshCursor() {
        cursorKind = null;
        java.awt.Point at = pointer;
        if (at != null) {
            updateCursor(at.x, at.y);
        } else if (cursors != null) {
            setCursor(cursors.cursor(GameCursors.Kind.POINT));
        }
    }

    private void updateCursor(int screenX, int screenY) {
        if (cursors == null) {
            return;
        }
        GameCursors.Kind kind = kindAt(screenX, screenY);
        if (kind == cursorKind) {
            return;
        }
        cursorKind = kind;
        setCursor(cursors.cursor(kind));
    }

    private GameCursors.Kind kindAt(int screenX, int screenY) {
        // The menu owns the pointer while it is up. Without this the cursor
        // answers to whatever the modal happens to be covering -- it turned
        // into a sword over a panel because there was an enemy behind that
        // panel, which is the game reacting to something the player cannot see
        // and has been told is not there.
        if (menu != null && menu.isOpen()) {
            return GameCursors.Kind.POINT;
        }
        if (screenX < viewportX() || screenY < viewportY()) {
            return GameCursors.Kind.POINT;
        }
        int tileX = worldX(screenX) / TILE;
        int tileY = worldY(screenY) / TILE;
        if (!world.map().contains(tileX, tileY)) {
            return GameCursors.Kind.POINT;
        }

        Unit unit = selected;
        if (placing != null) {
            return world.canPlaceBuilding(selected, placing, tileX, tileY)
                    ? GameCursors.Kind.ACT
                    : GameCursors.Kind.FORBIDDEN;
        }
        if (pendingAction != null) {
            return canAimAt(unit, pendingAction, tileX, tileY)
                    ? GameCursors.Kind.ACT
                    : GameCursors.Kind.FORBIDDEN;
        }

        // By the drawn box, like the click that follows it: a pointer that
        // turns red a tile away from where the attack actually lands is worse
        // than no pointer at all.
        Unit under = unitUnder(screenX, screenY);
        if (under == null) {
            if (unit != null && unit.type() != null && unit.type().canAttack()
                    && world.map().field(tileX, tileY).isWall()) {
                return GameCursors.Kind.ENEMY;
            }
            return GameCursors.Kind.POINT;
        }
        if (world.canControl(localPlayer, under.player())) {
            return GameCursors.Kind.OWN;
        }
        if (world.isEnemyPlayer(localPlayer, under.player())) {
            return GameCursors.Kind.ENEMY;
        }
        // Neutral: a mine or a patch, which a worker can work.
        return unit != null && world.canHarvestAt(unit, tileX, tileY)
                ? GameCursors.Kind.ACT
                : GameCursors.Kind.POINT;
    }

    /**
     * Whether pressing unload lands the cargo here and now.
     *
     * <p>The test {@code DoClicked_Unload} makes: one unit selected, standing
     * still, naval, and on a coast square -- or unable to move at all, which is
     * the bunker case, where there is nowhere to sail to and no point asking.
     */
    private boolean unloadsOnTheSpot(Unit unit) {
        if (unit.type().speed() <= 0) {
            return true;
        }
        if (selectedUnits().size() != 1 || !unit.type().seaUnit()
                || unit.order() != Unit.Order.STILL) {
            return false;
        }
        var field = world.map().fieldOrNull(unit.tileX(), unit.tileY());
        return field != null
                && field.hasFlag(net.chonkbase.chonkcraft.engine.map.TileFlag.COAST_ALLOWED);
    }

    /** Whether an armed command could be carried out at a square. */
    private boolean canAimAt(Unit unit, String action, int tileX, int tileY) {
        if (unit == null || !world.canControl(localPlayer, unit.player())) {
            return false;
        }
        Unit under = world.unitAt(tileX, tileY);
        return switch (action) {
            case "move", "patrol" -> unit.type().speed() > 0;
            case "attack" -> under == null
                    || world.isEnemyPlayer(localPlayer, under.player());
            case "repair" -> under != null && under != unit
                    && world.canControl(localPlayer, under.player())
                    && under.hitPoints() < under.type().hitPoints();
            case "harvest" -> world.canHarvestAt(unit, tileX, tileY);
            case "attack-ground" -> unit.type().firesMissile();
            // A spell needs something to land on, and the spell itself says
            // what it will accept.
            case "cast-spell" -> under != null && under.isAlive() && castable(under);
            default -> true;
        };
    }

    /** Whether the armed spell will accept this unit as its target. */
    private boolean castable(Unit under) {
        var spell = pendingSpell == null ? null : data.spells().spells().get(pendingSpell);
        if (spell == null) {
            return false;
        }
        if (!spell.allowBuildings() && under.type().building()) {
            return false;
        }
        return !spell.organicOnly() || !under.type().building();
    }

    /**
     * The palette ranges this tileset animates, and the walking copy.
     *
     * <p>Warcraft II's water moves by rotating palette entries rather than by
     * drawing frames, so a step of the water is a new colour model over the
     * same pixels: an allocation rather than a four-million-pixel redraw.
     *
     * <p>The pixels are not fixed, though this said for a long time that they
     * were, and a woodcutter chopping in the middle of a forest that was no
     * longer there is what that cost. {@link #refreshChangedGround()} redraws
     * the squares the simulation has changed. The two are compatible because
     * they work on different things -- one moves the colour model, the other
     * the raster underneath it -- but only in that order: a square redrawn
     * into the raster has to be published through a fresh view, because the
     * view handed out last time may be sitting behind a cached copy.
     */
    private final java.util.List<int[]> cyclingRanges;
    private volatile net.chonkbase.chonkcraft.data.graphic.Palette cyclingPalette;
    private volatile BufferedImage cyclingTerrain;

    /** Cycling runs at about six steps a second, as the original does. */
    private static final int CYCLE_INTERVAL = 5;
    private int cycleTick;

    /**
     * The ten saved selections.
     *
     * <p>Unit identifiers rather than references, because a group outlives the
     * units in it: recalling a group whose members died should give what is
     * left, not a handful of corpses.
     */
    private final java.util.List<java.util.List<Integer>> groups = new java.util.ArrayList<>(
            java.util.Collections.nCopies(10, java.util.List.of()));

    /**
     * What a digit key does to a numbered group.
     *
     * <p>The five arms of {@code CommandKey_Group}. Upstream also distinguishes a double press
     * and two selection modes; neither is modelled here, because this implementation has
     * no double-press timer and no {@code CanSelect(mode)} on a type, so those
     * arms collapse into the ones below.
     */
    enum GroupAction {
        /** Plain digit, and shift-alt: the group becomes the selection. */
        SELECT,
        /** Control: the selection becomes the group, replacing what was in it. */
        DEFINE,
        /** Shift-control: the selection is added to the group. */
        ADD_TO_GROUP,
        /** Shift: the group's units are added to the selection. */
        ADD_TO_SELECTION,
        /** Alt: the view moves to the group without disturbing the selection. */
        CENTRE
    }

    /**
     * Which of the five a digit press means.
     *
     * <p>This is the whole of the bug. It used to be
     * {@code groupKey(digit, control || alt || shift)} -- <em>any</em>
     * modifier meant "define" -- so Alt-3, which in the original centres the
     * view on group three, instead overwrote group three with whatever
     * happened to be selected, and Shift-3, which adds to the selection,
     * did the same. A player using either lost a group they had spent the
     * game building up, and it reads as their own mistake rather than as a
     * bug, which is why it survived.
     */
    static GroupAction groupActionFor(boolean shift, boolean control, boolean alt) {
        if (shift) {
            if (control) {
                return GroupAction.ADD_TO_GROUP;
            }
            return alt ? GroupAction.SELECT : GroupAction.ADD_TO_SELECTION;
        }
        if (alt) {
            return GroupAction.CENTRE;
        }
        return control ? GroupAction.DEFINE : GroupAction.SELECT;
    }

    /** The old two-state entry point, kept for the plain and defining cases. */
    boolean groupKey(int digit, boolean save) {
        return groupKey(digit, save ? GroupAction.DEFINE : GroupAction.SELECT);
    }

    /**
     * Saves, recalls, extends or centres on a numbered group.
     *
     * @param digit  which group, nought to nine
     * @param action what the modifiers asked for
     * @return whether the key was used
     */
    boolean groupKey(int digit, GroupAction action) {
        if (digit < 0 || digit > 9) {
            return false;
        }
        switch (action) {
            case DEFINE -> {
                groups.set(digit, java.util.List.copyOf(selectedIds()));
                java.util.List<Integer> ids = groups.get(digit);
                status = ids.isEmpty() ? "Group " + digit + " cleared."
                        : ids.size() + " units in group " + digit + ".";
                repaint();
                return true;
            }
            case ADD_TO_GROUP -> {
                // UiAddToGroup: the selection joins what is already there,
                // rather than replacing it. Order is preserved and duplicates
                // are dropped, so pressing it twice does not double the group.
                java.util.List<Integer> ids = new java.util.ArrayList<>(groups.get(digit));
                for (int id : selectedIds()) {
                    if (!ids.contains(id)) {
                        ids.add(id);
                    }
                }
                groups.set(digit, java.util.List.copyOf(ids));
                status = ids.size() + " units in group " + digit + ".";
                repaint();
                return true;
            }
            case ADD_TO_SELECTION -> {
                // UiAddGroupToSelection, including its two refusals: an empty
                // group does nothing at all, and a selection that begins with
                // a building will not take units, because the original never
                // mixes the two.
                java.util.List<Integer> ids = groups.get(digit);
                Unit head = firstSelected();
                if (ids.isEmpty()
                        || (head != null && head.type() != null && head.type().building())) {
                    return true;
                }
                java.util.Map<Integer, Unit> byId = unitsById();
                for (int id : ids) {
                    Unit unit = byId.get(id);
                    if (unit != null && unit.isAlive() && !unit.selected()
                            && unit.type() != null && !unit.type().building()
                            && selectionOrder.size() < MAX_SELECTED_UNITS) {
                        unit.setSelected(true);
                        selectionOrder.add(unit.id());
                    }
                }
                selectionChanged(selected == null ? firstSelected() : selected);
                status = countSelected() + " selected.";
                repaint();
                return true;
            }
            case CENTRE -> {
                // UiCenterOnGroup: the average position of the group's living
                // members, and the selection is left exactly as it was.
                long sumX = 0;
                long sumY = 0;
                int found = 0;
                java.util.List<Integer> ids = groups.get(digit);
                for (Unit unit : world.unitsSnapshot()) {
                    if (unit.isAlive() && ids.contains(unit.id())) {
                        sumX += unit.tileX();
                        sumY += unit.tileY();
                        found++;
                    }
                }
                if (found == 0) {
                    status = "Group " + digit + " is empty.";
                    repaint();
                    return true;
                }
                centreOn((int) (sumX / found), (int) (sumY / found));
                repaint();
                return true;
            }
            default -> { }
        }

        java.util.List<Integer> ids = groups.get(digit);
        Unit first = null;
        int found = 0;
        selectionOrder.clear();
        for (Unit unit : world.unitsSnapshot()) {
            boolean inGroup = unit.isAlive() && ids.contains(unit.id());
            unit.setSelected(inGroup);
        }
        java.util.Map<Integer, Unit> byId = unitsById();
        for (int id : ids) {
            Unit unit = byId.get(id);
            if (unit != null && unit.selected() && found < MAX_SELECTED_UNITS) {
                selectionOrder.add(id);
                found++;
                first = first == null ? unit : first;
            } else if (unit != null) {
                unit.setSelected(false);
            }
        }
        selectionChanged(first);
        status = found == 0 ? "Group " + digit + " is empty." : found + " selected.";
        if (first != null) {
            playUnit(first, "selected", this::chooseSample);
        }
        repaint();
        return true;
    }

    /** The identifiers of everything of the local player's that is selected. */
    private java.util.List<Integer> selectedIds() {
        return selectedUnits().stream().map(Unit::id).toList();
    }

    /** The command slot the pointer is resting on, or {@code -1}. */
    private volatile int hoveredSlot = -1;

    /**
     * Notes which command slot the pointer is over.
     *
     * <p>The status line names it and a panel beside it says what it costs,
     * which is how a player learns the grid without a manual.
     */
    private void updateHover(int x, int y) {
        if (commandPanel == null) {
            return;
        }
        int slot = commandPanel.slotAt(toDesign(x), toDesign(y));
        if (slot == hoveredSlot) {
            return;
        }
        hoveredSlot = slot;
        var button = slot < 0 ? null : commandPanel.buttonAt(toDesign(x), toDesign(y));
        if (button != null) {
            status = button.plainHint();
        } else if (placing == null && pendingAction == null) {
            status = "";
        }
        repaint();
    }

    /**
     * Draws the hover panel for a command slot.
     *
     * <p>Upstream builds these from DefinePopup, a small layout language of
     * margins, lines and content types. What a player reads off one is the
     * hint and the cost, so that is what this shows; the layout language is
     * not ported.
     */
    private void drawPopup(Graphics2D g2) {
        if (commandPanel == null || hoveredSlot < 0) {
            return;
        }
        java.awt.Rectangle slot = commandPanel.boundsOf(hoveredSlot);
        var button = slot == null ? null : commandPanel.buttonAt(slot.x + 1, slot.y + 1);
        if (button == null) {
            return;
        }
        java.util.List<String> lines = new java.util.ArrayList<>();
        lines.add(button.plainHint());
        lines.addAll(commandPanel.costLines(button));

        int lineHeight = font != null ? font.height() : 14;
        int width = 0;
        for (String line : lines) {
            width = Math.max(width, font != null ? font.widthOf(line) : line.length() * 7);
        }
        int boxWidth = width + 16;
        int boxHeight = lines.size() * lineHeight + 12;
        // To the right of the slot, pushed back on screen if it would spill.
        int boxX = Math.min(slot.x + slot.width + 6, toDesign(getWidth()) - boxWidth - 2);
        int boxY = Math.max(2, Math.min(slot.y, toDesign(getHeight()) - boxHeight - 2));

        g2.setColor(new Color(40, 40, 40, 220));
        g2.fillRect(boxX, boxY, boxWidth, boxHeight);
        g2.setColor(new Color(180, 180, 220, 200));
        g2.drawRect(boxX, boxY, boxWidth, boxHeight);

        int y = boxY + 6;
        for (int i = 0; i < lines.size(); i++) {
            GameFont.Ink ink = i == 0 ? normalInk : GameFont.Ink.YELLOW;
            if (font != null) {
                font.draw(g2, lines.get(i), boxX + 8, y, ink);
            } else {
                g2.setColor(GameFont.colourOf(ink));
                g2.drawString(lines.get(i), boxX + 8, y + lineHeight - 3);
            }
            y += lineHeight;
        }
    }

    /**
     * The visible part of the map, copied out at its own size.
     *
     * <p>Kept and reused rather than made each frame: it is viewport sized and
     * allocating one a frame is work the collector then has to undo.
     */
    private BufferedImage terrainSlice;

    /**
     * The tile sheet the ground was rasterised out of, and the tileset that
     * says which piece of it a tile code means.
     *
     * <p>Fetched here rather than handed in because the screen is deliberately
     * given the map already rasterised -- the same reason {@link #fogTiles}
     * arrives through a setter -- and because until a square changed there was
     * nothing to fetch it for. Resolved once, on the first change, which on a
     * map with no woods to cut and no walls to knock down is never. A tileset
     * costs a few milliseconds and a third of a megabyte.
     *
     * @see #refreshChangedGround()
     */
    private MapRenderer groundRenderer;

    /** Whether the fetch above has been tried; a failure is not retried. */
    private boolean groundRendererResolved;

    /** One square's worth of palette indices, reused between redraws. */
    private IndexedImage groundScratch;

    /**
     * Redraws the squares the simulation has changed since the last frame.
     *
     * <p>A player reported a woodcutter standing in the middle of a stand of
     * trees, surrounded by intact forest on every side, chopping. The worker
     * was not in the wood. It had spent fourteen game minutes felling its way
     * in, its square's flags said open ground, and the pathfinder had never
     * let it near an unpassable one -- {@code PathFinder.enterCost} refuses an
     * unpassable square outright, before the allowance that lets a route end
     * on a building. What the player was looking at was the map as it stood at
     * load. The ground below is rasterised once, into an image the size of the
     * whole map, and nothing ever touched it again; the only thing that
     * regenerated it was the palette walking for the water. So the trees came
     * down in the simulation, the minimap -- which is redrawn from the live
     * map every frame -- showed the clearing spreading, and the field the
     * player was actually watching stayed solid canopy with a peasant swinging
     * an axe in the middle of it.
     *
     * <p>Warcraft II cannot have this bug.
     * {@code CViewport::DrawMapBackgroundInViewport}
     * blits every visible square out of the tile sheet on every frame, so a
     * felled square is simply drawn differently the next time round.
     * Rasterising once is this implementation's deviation, taken so that colour cycling
     * costs a palette swap instead of a four-million-pixel redraw, and the
     * price of it is this method: {@link GameMap#drainChangedPictures} names
     * the squares that moved, and only those are drawn again.
     *
     * <p>It is not only trees. {@code clearWoodTile} repaints a felled square
     * and repairs its eight neighbours, {@code clearRockTile} does the same
     * for rock a demolition squad has blown open, and {@code hitWall} and
     * {@code breakWall} do it for a wall battered to rubble and then breached.
     * All three wrote tile codes that the screen would never have drawn: a
     * breached wall stayed drawn as an intact one with an army walking through
     * the picture of it.
     *
     * <p>Called from the paint, not from the simulation loop, so that the
     * ground is brought up to date by the act of looking at it. Anything that
     * paints a frame -- the game, a replay, a test harness -- gets a true one
     * without having to know to ask.
     */
    private void refreshChangedGround() {
        GameMap map = world.map();
        int[] changed = map.drainChangedPictures();
        if (changed.length == 0) {
            return;
        }
        MapRenderer renderer = groundRenderer();
        if (renderer == null) {
            return;
        }
        if (groundScratch == null) {
            groundScratch = new IndexedImage(TILE, TILE);
        }
        WritableRaster raster = terrain.getRaster();
        for (int index : changed) {
            int tileX = index % map.width();
            int tileY = index / map.width();
            int left = tileX * TILE;
            int top = tileY * TILE;
            if (left + TILE > terrain.getWidth() || top + TILE > terrain.getHeight()) {
                continue;
            }
            // Read the square back before drawing over it. A tile code
            // pointing past the sheet leaves the square alone rather than
            // throwing, which is what the whole-map render does with a bad
            // code too, and starting from what is already there is what makes
            // "leaves it alone" mean the picture rather than whatever the
            // scratch happened to hold from the square before.
            raster.getDataElements(left, top, TILE, TILE, groundScratch.pixels());
            renderer.drawTile(groundScratch, 0, 0,
                    map.tileset().graphicFor(map.field(tileX, tileY).tile()));
            raster.setDataElements(left, top, TILE, TILE, groundScratch.pixels());
        }
        // A fresh view over the same pixels. The raster is shared, so the
        // squares above are already in whatever the last cycle handed out --
        // but that image may have an accelerated copy cached behind it, and a
        // copy taken before the axe fell is exactly the picture this method
        // exists to stop the player seeing.
        cyclingTerrain = IndexedImage.recolour(terrain, cyclingPalette);
    }

    /** The tile sheet, loaded on first use, or null if it cannot be had. */
    private MapRenderer groundRenderer() {
        if (groundRendererResolved) {
            return groundRenderer;
        }
        groundRendererResolved = true;
        try {
            GameData.LoadedTileset loaded = data.loadTileset(tilesetOf(tilesetName));
            groundRenderer = new MapRenderer(world.map().tileset(), loaded.sheet());
        } catch (RuntimeException failed) {
            // The ground stays as it was rather than the game stopping. A map
            // drawn a little out of date is worth more than a crash.
            System.err.println("terrain cannot be redrawn: " + failed);
            groundRenderer = null;
        }
        return groundRenderer;
    }

    /**
     * The in-game tileset name back to the one the map data uses.
     *
     * <p>The inverse of the mapping {@code Main} applies on the way in: the
     * forest tileset is called "summer" everywhere in the script tree, and
     * only the PUD calls it forest.
     */
    private static PudMap.Tileset tilesetOf(String name) {
        if (name == null || "summer".equals(name) || "forest".equals(name)) {
            return PudMap.Tileset.FOREST;
        }
        return PudMap.Tileset.valueOf(name.toUpperCase(java.util.Locale.ROOT));
    }

    /**
     * Draws the ground.
     *
     * <p>Not the whole map. The map is rasterised once into an image the size
     * of the map -- four million pixels on a large one -- and colour cycling
     * hands out a fresh view of it every few ticks with a new palette. At one
     * to one that is a blit and costs nothing worth measuring. Under a zoom it
     * is not: an indexed-colour image put through a scaling transform drops
     * off the accelerated path onto the software loop, and the software loop
     * reads every source pixel, including the nine tenths of the map that are
     * not on the screen. That is why this appeared the day the zoom did.
     *
     * <p>So the visible rectangle is copied out at its own size first, which
     * is a straight blit, and the copy is what gets scaled. The scaling then
     * has a few hundred thousand pixels to think about instead of four
     * million, and the copy is a plain RGB image, which is the kind the
     * pipeline can accelerate.
     */
    private void drawTerrain(Graphics2D g2) {
        // Before the blit, not after: what is drawn this frame is the map as
        // it is now, not as it was when it loaded.
        refreshChangedGround();
        BufferedImage ground = cyclingTerrain;
        if (ground == null) {
            return;
        }
        if (gameScale <= 1.0) {
            // No transform to fall off, and the blit is already the fast path.
            g2.drawImage(ground, -cameraX, -cameraY, null);
            return;
        }
        int left = Math.max(0, Math.min(cameraX, ground.getWidth()));
        int top = Math.max(0, Math.min(cameraY, ground.getHeight()));
        int width = Math.min(visibleWorldWidth() + 1, ground.getWidth() - left);
        int height = Math.min(visibleWorldHeight() + 1, ground.getHeight() - top);
        if (width <= 0 || height <= 0) {
            return;
        }
        if (terrainSlice == null || terrainSlice.getWidth() < width
                || terrainSlice.getHeight() < height) {
            terrainSlice = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D slice = terrainSlice.createGraphics();
        slice.drawImage(ground, 0, 0, width, height,
                left, top, left + width, top + height, null);
        slice.dispose();
        g2.drawImage(terrainSlice, left - cameraX, top - cameraY,
                left - cameraX + width, top - cameraY + height,
                0, 0, width, height, null);
    }

    /** Advances the animated palette. Called from the simulation loop. */
    void cycleStep() {
        if (cyclingRanges.isEmpty() || ++cycleTick % CYCLE_INTERVAL != 0) {
            return;
        }
        cyclingPalette = cyclingPalette.cycled(cyclingRanges);
        cyclingTerrain = net.chonkbase.chonkcraft.data.graphic.IndexedImage.recolour(
                terrain, cyclingPalette);
    }

    /** The nine command slots, or null when the interface art is missing. */
    private final CommandPanel commandPanel;

    /**
     * The building the player has chosen but not yet placed.
     *
     * <p>Warcraft II makes building a two-step act: press the icon, then click
     * the ground. Nothing is spent and no worker is sent until the second
     * click, so the choice can be abandoned.
     */
    private volatile UnitType placing;

    /**
     * Where the pointer last was, tracked rather than queried.
     *
     * <p>{@code getMousePosition} asks the window system, which means it
     * throws outright when there is no display. Following the motion events
     * keeps the placement outline working in a headless render.
     */
    private volatile java.awt.Point pointer;

    /**
     * A command waiting for the player to say where.
     *
     * <p>Move, attack, patrol, repair, harvest and attack-ground all need a
     * point before they mean anything, so pressing the icon arms them and the
     * next click on the ground fires them.
     */
    private volatile String pendingAction;

    /**
     * Which spell the armed cast is for.
     *
     * <p>The action alone is not enough: every spell shares the action
     * "cast-spell" and they are told apart by the button's value.
     */
    private volatile String pendingSpell;

    /**
     * Spell identifiers in the order the command layer numbers them.
     *
     * <p>A cast travels the wire as an index into this list, so both sides
     * have to agree on it. Sorted, which is what CommandApplier does.
     */
    private java.util.List<String> spellOrder;

    private int spellIndex(String ident) {
        if (spellOrder == null) {
            spellOrder = data.spells().spells().all().keySet().stream().sorted().toList();
        }
        return spellOrder.indexOf(ident);
    }

    /**
     * Turns a spell into a standing order, or takes it back off.
     *
     * <p>Implements the control-click branch of {@code DoClicked_SpellCast}.
     * Two things are worth keeping from it exactly. A spell whose declaration
     * carries no {@code autocast} clause cannot be set this way at all --
     * blizzard is one -- and is refused rather than quietly accepted. And when
     * a mixed group is selected, the switch goes the way of whichever unit
     * lacks it: if any selected caster is not casting this spell on its own,
     * they are all turned on, so that one press makes the whole group agree
     * rather than inverting each of them separately.
     */
    private void toggleAutoCast(net.chonkbase.chonkcraft.engine.ui.UnitButton button) {
        String ident = button.value();
        var spell = ident == null ? null : data.spells().spells().get(ident);
        if (spell == null) {
            return;
        }
        if (!spell.autoCastable()) {
            status = BattleNetMessages.sentence(
                    spell.name() + " cannot be set to cast itself");
            return;
        }
        java.util.List<Unit> casters = new java.util.ArrayList<>();
        for (Unit each : selectedUnits()) {
            if (each.isCaster()) {
                casters.add(each);
            }
        }
        if (casters.isEmpty()) {
            return;
        }
        boolean turningOn = casters.stream().anyMatch(each -> !ident.equals(each.autoCast()));
        int index = spellIndex(ident);
        for (Unit each : casters) {
            if (turningOn != ident.equals(each.autoCast())) {
                commands.issue(GameCommand.autoCast(localPlayer, each.id(), index, turningOn));
            }
        }
        status = BattleNetMessages.sentence(
                spell.name() + (turningOn ? ": cast on sight" : ": no longer cast on sight"));
    }

    /**
     * Commands that mean nothing until the player says where.
     *
     * <p>Named rather than listed in the switch so the panel and the tests
     * read the same set: a button whose action is in neither this nor the
     * switch below does nothing when pressed, which is what the whole command
     * panel used to do for a fifth of its buttons.
     */
    static final java.util.Set<String> TARGETED_ACTIONS = java.util.Set.of(
            "move", "attack", "patrol", "repair", "harvest", "attack-ground", "cast-spell");

    /** Commands that take effect the moment the icon is pressed. */
    static final java.util.Set<String> IMMEDIATE_ACTIONS = java.util.Set.of(
            "button", "stop", "build", "train-unit", "upgrade-to", "research",
            "cancel", "cancel-build", "cancel-train-unit", "cancel-upgrade",
            "return-goods", "stand-ground", "explore");

    /**
     * Commands that are instant or aimed depending on the situation.
     *
     * <p>Only unload, and only because {@code DoClicked_Unload} is written
     * that way: a beached transport dumps its cargo at once, everything else
     * asks the player where. It is in neither set above because it is honestly
     * in neither, and putting it in one of them is what hid the missing half.
     */
    static final java.util.Set<String> CONDITIONAL_ACTIONS = java.util.Set.of("unload");

    /** Whether pressing a button with this action does anything at all. */
    static boolean handles(String action) {
        return TARGETED_ACTIONS.contains(action) || IMMEDIATE_ACTIONS.contains(action)
                || CONDITIONAL_ACTIONS.contains(action);
    }

    /** The rubber band being dragged, in screen coordinates, or null. */
    private volatile java.awt.Rectangle band;
    private int bandStartX;
    private int bandStartY;

    /**
     * The tileset's own fog masks, or null before they have been handed over.
     *
     * <p>They come from the decoded tile sheet, which the screen is not given
     * -- it gets the map already rasterised out of it -- so the caller that
     * loaded the tileset passes them in.
     */
    private FogTiles fogTiles;

    /**
     * Gives the fog its edges. Without this the fog still draws, as squares.
     *
     * @see FogTiles
     */
    void setFogTiles(FogTiles fogTiles) {
        this.fogTiles = fogTiles;
    }

    GameScreen(World world, GameData data, BufferedImage terrain, Palette palette,
            String tilesetName, int localPlayer, int width, int height,
            net.chonkbase.chonkcraft.engine.sound.GameAudio audio, SidePanel panel,
            CommandPanel commandPanel, CommandApplier applier, CommandSink commands,
            java.util.List<int[]> cyclingRanges, String race) {
        this.font = GameFont.load(data, GameFont.Face.GAME);
        this.race = race;
        this.normalInk = GameFont.normalInkFor(race);
        this.cursors = GameCursors.load(data, race);
        this.cyclingRanges = cyclingRanges == null ? java.util.List.of() : cyclingRanges;
        this.cyclingPalette = palette;
        this.cyclingTerrain = terrain;
        this.commandPanel = commandPanel;
        this.applier = applier;
        this.commands = intents.wrap(commands, world::cycle, this::selectedIds, world);
        this.audio = audio;
        this.panel = panel;
        this.world = world;
        this.data = data;
        this.terrain = terrain;
        this.palette = palette;
        this.tilesetName = tilesetName;
        this.localPlayer = localPlayer;

        for (int i = 0; i < held.length; i++) {
            held[i] = new AtomicBoolean();
        }
        if (panel != null && terrain != null) {
            panel.setTerrainSamples(
                    terrainSample(net.chonkbase.chonkcraft.engine.map.MapField::isForest),
                    terrainSample(net.chonkbase.chonkcraft.engine.map.MapField::isWaterPassable));
        }
        setPreferredSize(new Dimension(width, height));
        setBackground(Color.BLACK);
        setDoubleBuffered(true);
        // Tab is the minimap's terrain switch, and a focus manager takes it
        // first unless it is told not to.
        setFocusTraversalKeysEnabled(false);
        installMouse();
    }

    /**
     * A two-by-two crop of the map's own ground, for the resource cards.
     *
     * <p>The lumber card shows the trees of the map being played -- snowed-in
     * on a winter map -- and the oil card shows the slick on this map's own
     * water, because the cards teach a player what to look for and the honest
     * picture is the one already on their screen. Cut from the rasterised
     * terrain, which is the map as loaded; null when the map simply has no
     * such ground, and the card goes without its picture.
     */
    private BufferedImage terrainSample(
            java.util.function.Predicate<net.chonkbase.chonkcraft.engine.map.MapField> ground) {
        var map = world.map();
        for (int y = 0; y + 1 < map.height(); y++) {
            for (int x = 0; x + 1 < map.width(); x++) {
                if (ground.test(map.field(x, y)) && ground.test(map.field(x + 1, y))
                        && ground.test(map.field(x, y + 1))
                        && ground.test(map.field(x + 1, y + 1))) {
                    int left = x * TILE;
                    int top = y * TILE;
                    if (left + TILE * 2 <= terrain.getWidth()
                            && top + TILE * 2 <= terrain.getHeight()) {
                        BufferedImage crop = new BufferedImage(TILE * 2, TILE * 2,
                                BufferedImage.TYPE_INT_RGB);
                        var g2 = crop.createGraphics();
                        g2.drawImage(terrain, 0, 0, TILE * 2, TILE * 2,
                                left, top, left + TILE * 2, top + TILE * 2, null);
                        g2.dispose();
                        return crop;
                    }
                }
            }
        }
        return null;
    }

    /** Where the playing field starts, to the right of the sidebar. */
    /**
     * The playing field, from {@code UI.MapArea} in the layout script.
     *
     * <p>It is not simply everything to the right of the sidebar. The script
     * ends it sixteen pixels short of the right edge and sixteen short of the
     * bottom, because the filler strip and the status line sit there; running
     * the map under them draws terrain that is then covered over, and puts the
     * edge of the world somewhere the player cannot click.
     */
    private int scaled(int designPixels) {
        return (int) Math.round(designPixels * interfaceScale);
    }

    private int viewportX() {
        if (layout != null) {
            return scaled(layout.mapArea().x());
        }
        return panel != null && panel.isAvailable() ? scaled(SidePanel.WIDTH) : 0;
    }

    /** Where it starts vertically, below the resource bar. */
    private int viewportY() {
        if (layout != null) {
            return scaled(layout.mapArea().y());
        }
        return panel != null && panel.isAvailable() ? scaled(SidePanel.TOP_BAR_HEIGHT) : 0;
    }

    private int viewportWidth() {
        if (layout != null) {
            return Math.max(1,
                    Math.min(scaled(layout.mapArea().width()), getWidth() - viewportX()));
        }
        return Math.max(1, getWidth() - viewportX());
    }

    private int viewportHeight() {
        if (layout != null) {
            return Math.max(1,
                    Math.min(scaled(layout.mapArea().height()), getHeight() - viewportY()));
        }
        return Math.max(1, getHeight() - viewportY()
                - (panel != null && panel.isAvailable() ? scaled(16) : 0));
    }

    /**
     * How much bigger than its design size the interface is drawn.
     *
     * <p>Warcraft II's chrome was drawn for a 640 by 480 screen. On a modern
     * display that is a sliver down one side and lettering too small to read,
     * which is what "none of it resizes" means: the layout scripts do lay the
     * interface out for the window, but they lay out the same 176 pixel column
     * however large the window is.
     *
     * <p>So the chrome is drawn through a scale transform and the layout is
     * computed for the window divided by it. Everything in the sidebar --
     * art, icons, lettering, the minimap -- grows together, and because the
     * transform is nearest-neighbour the pixel art stays pixel art rather than
     * turning to soup. The playing field is deliberately left at one to one:
     * scaling that would show less of the map, which is a loss rather than a
     * gain.
     */
    private double interfaceScale = 1.0;

    /** The smallest and largest either scale may be drawn at. */
    static final double MIN_SCALE = 1.0;

    static final double MAX_SCALE = 4.0;

    double interfaceScale() {
        return interfaceScale;
    }

    /**
     * How large the world itself is drawn: the map, the units, the buildings.
     *
     * <p>Separate from the interface, because the two answer different
     * questions. The interface scale is about whether the lettering can be
     * read; this is about how much of the battle is on the screen at once, and
     * a player who wants a big readable sidebar does not necessarily want to
     * see a quarter of the map.
     *
     * <p>It follows the window until it is set, and the default is not one.
     * Warcraft II showed about fifteen tiles across on its 640 by 480 screen;
     * drawn at one on a modern display the same map area holds forty-odd, the
     * units are specks, and the game plays quite differently from the one it
     * is meant to be. Matching the window is what keeps the field of view the
     * game was designed around.
     *
     * <p>Whole steps, for the same reason the interface uses them: half a pixel
     * of sprite is a smeared pixel of sprite.
     */
    private double gameScale = 1.0;

    double gameScale() {
        return gameScale;
    }

    private boolean gameScaleChosen;

    void setGameScale(double scale) {
        gameScaleChosen = true;
        applyGameScale(scale);
    }

    private void applyGameScale(double scale) {
        double wanted = Math.max(MIN_SCALE, Math.min(MAX_SCALE, Math.round(scale)));
        if (wanted == gameScale) {
            return;
        }
        // Whatever was in the middle of the view stays in the middle of it.
        // Holding the top-left corner instead means the thing being looked at
        // slides off as the zoom changes, and the next thing anyone does is
        // scroll it back.
        int centreX = cameraX + visibleWorldWidth() / 2;
        int centreY = cameraY + visibleWorldHeight() / 2;
        gameScale = wanted;
        cameraX = clamp(centreX - visibleWorldWidth() / 2,
                Math.max(0, terrain.getWidth() - visibleWorldWidth()));
        cameraY = clamp(centreY - visibleWorldHeight() / 2,
                Math.max(0, terrain.getHeight() - visibleWorldHeight()));
        repaint();
    }

    /**
     * Whether the wheel changes the zoom.
     *
     * <p>Off is a real preference rather than an oversight: a player who has
     * settled on a size does not want it moving because they brushed the
     * wheel reaching for something else.
     */
    private boolean wheelZoom = true;

    boolean wheelZoomEnabled() {
        return wheelZoom;
    }

    void setWheelZoom(boolean enabled) {
        wheelZoom = enabled;
    }

    /** How much of the world, in its own pixels, the map area shows. */
    /**
     * Wheel rotation seen but not yet acted on.
     *
     * <p>Kept between events so a slow, deliberate scroll adds up to a step
     * rather than being rounded away, and a fast one does not take four.
     */
    private double wheelCarried;

    private int visibleWorldWidth() {
        return (int) Math.ceil(viewportWidth() / gameScale);
    }

    private int visibleWorldHeight() {
        return (int) Math.ceil(viewportHeight() / gameScale);
    }

    /** A point on the screen in world pixels. */
    private int worldX(int screenX) {
        return cameraX + (int) Math.floor((screenX - viewportX()) / gameScale);
    }

    private int worldY(int screenY) {
        return cameraY + (int) Math.floor((screenY - viewportY()) / gameScale);
    }

    /**
     * Whether the player has picked a size themselves.
     *
     * <p>Until they do, the interface follows the window: going full screen on
     * a large display should make the chrome bigger, not leave a 176 pixel
     * column and an unreadable minimap stranded down one side. Once they have
     * chosen, their choice stands and the window stops overruling it.
     */
    private boolean scaleChosen;

    void setInterfaceScale(double scale) {
        scaleChosen = true;
        applyScale(scale);
    }

    private void applyScale(double scale) {
        double wanted = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
        if (wanted == interfaceScale) {
            return;
        }
        interfaceScale = wanted;
        // The layout is in design pixels, so a different scale is a different
        // layout.
        layoutWidth = 0;
        layoutHeight = 0;
        refreshLayout();
        repaint();
    }

    /**
     * The scale to start at for a window of this size.
     *
     * <p>Whole steps, because a fractional one would put the pixel grid out of
     * register and make the lettering ragged. Anchored on the 480 pixel design
     * height rather than the width: the sidebar's usefulness is set by how
     * many rows of it fit.
     */
    static double naturalScale(int width, int height) {
        // The sidebar is 176 wide and 480 tall in its own pixels. Doubling it
        // is right once there is room for the map beside it and the whole
        // column above the fold: 900 across and 600 down leaves both. Tripling
        // wants half again as much of each.
        double byHeight = height / 600.0;
        double byWidth = width / 900.0;
        double whole = Math.floor(Math.min(byHeight, byWidth));
        return Math.max(MIN_SCALE, Math.min(MAX_SCALE, whole));
    }

    /** A screen coordinate in the interface's own design pixels. */
    private int toDesign(int screenCoordinate) {
        return (int) Math.floor(screenCoordinate / interfaceScale);
    }

    /** The interface layout, or null when the scripts could not be read. */
    private net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout layout;

    /** The window size the current layout was computed for. */
    private int layoutWidth;
    private int layoutHeight;

    void setLayout(net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout layout) {
        this.layout = layout;
        this.layoutWidth = getWidth();
        this.layoutHeight = getHeight();
    }

    /**
     * Rebuilds the layout when the window is not the size it was built for.
     *
     * <p>Half the interface is measured back from the right and bottom edges,
     * so a layout computed for one window size describes a different window.
     * Held to the size it was built for, the map area stops early and the
     * chrome sits in from the edges with black behind it -- which is exactly
     * what a window manager that hands out a size other than the one asked for
     * produces.
     */
    private void refreshLayout() {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0 || (width == layoutWidth && height == layoutHeight)) {
            return;
        }
        // A window that has changed size gets a size of interface to match,
        // unless the player has said otherwise.
        if (!scaleChosen) {
            double natural = naturalScale(width, height);
            if (natural != interfaceScale) {
                interfaceScale = natural;
            }
        }
        // And so does the world, separately, so that a large window shows the
        // fifteen-odd tiles across the original did rather than forty tiny
        // ones.
        if (!gameScaleChosen) {
            gameScale = naturalScale(width, height);
        }
        var rebuilt = data.uiLayout(race, toDesign(width), toDesign(height));
        layoutWidth = width;
        layoutHeight = height;
        if (rebuilt == null) {
            return;
        }
        layout = rebuilt;
        if (panel != null) {
            panel.setLayout(rebuilt);
        }
        if (commandPanel != null) {
            commandPanel.setLayout(rebuilt);
        }
    }

    /**
     * Whether a point is on the playing field rather than the chrome.
     *
     * <p>Not just "right of the sidebar and below the resource bar". The map
     * area stops short of the right edge and the status line, and a click in
     * that margin used to be turned into a tile and acted on -- so clicking
     * the filler strip gave an order.
     */
    private boolean isOnMap(int x, int y) {
        return x >= viewportX() && y >= viewportY()
                && x < viewportX() + viewportWidth()
                && y < viewportY() + viewportHeight();
    }

    /**
     * A click on the minimap.
     *
     * <p>Left centres the view, right gives the order, which is
     * {@code UIHandleButtonDown_OnMinimap}. That division is the reliable way
     * to send an army across a map without scrolling to look at where it is
     * going, and it is worth stating because it is not obvious: the button
     * that moves your units on the map is the same button that moves them
     * from the minimap.
     *
     * <p>No ping. Marking where the view jumped to was my own idea and a bad
     * one -- it put a signal meant for talking to other players on the most
     * ordinary navigation there is.
     */
    private void clickMinimap(int x, int y, boolean rightButton, Modifiers keys) {
        int[] tile = SidePanel.tileAt(toDesign(x), toDesign(y),
                world.map().width(), world.map().height());
        if (rightButton) {
            commandSelected(tile[0], tile[1], keys);
            clickFeedback(tile[0], tile[1]);
        } else {
            centreOn(tile[0], tile[1]);
        }
    }

    /**
     * The green cross the game throws down where an order landed.
     *
     * <p>{@code SetClickMissile("missile-green-cross")} in the prelude, drawn
     * by {@code MakeLocalMissile} at the spot a right click was aimed. It is
     * the only confirmation an order gets when the units it was given to are
     * off screen, which is exactly the case when the click was on the minimap.
     */
    private void clickFeedback(int tileX, int tileY) {
        world.markOrder(tileX, tileY);
    }

    private void installMouse() {
        addMouseWheelListener(event -> {
            if (!wheelZoom || (menu != null && menu.isOpen())) {
                return;
            }
            // A trackpad sends dozens of small rotations for one gesture, and
            // a mouse one large one. Acting on each would run the zoom from
            // end to end and back inside a single flick, and redraw the world
            // at a new size for every one of them. So the rotation is
            // accumulated and a step is taken only when a whole notch's worth
            // has arrived.
            wheelCarried += event.getPreciseWheelRotation();
            double notches = wheelCarried > 0
                    ? Math.floor(wheelCarried)
                    : Math.ceil(wheelCarried);
            if (notches == 0) {
                return;
            }
            wheelCarried -= notches;
            // Away from the player zooms in, which is the direction every map
            // and every other game uses.
            double wanted = gameScale - Math.signum(notches);
            if (wanted == gameScale) {
                return;
            }
            // Keep the tile under the pointer under the pointer. Zooming about
            // the middle of the screen means the thing being looked at slides
            // away from the pointer, and the next thing the player does is
            // scroll it back.
            int anchorX = worldX(event.getX());
            int anchorY = worldY(event.getY());
            setGameScale(wanted);
            int offsetX = (int) Math.floor((event.getX() - viewportX()) / gameScale);
            int offsetY = (int) Math.floor((event.getY() - viewportY()) / gameScale);
            cameraX = clamp(anchorX - offsetX,
                    Math.max(0, terrain.getWidth() - visibleWorldWidth()));
            cameraY = clamp(anchorY - offsetY,
                    Math.max(0, terrain.getHeight() - visibleWorldHeight()));
            repaint();
        });
        setFocusable(true);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                // Clicking the game gives the game the keyboard.
                requestFocusInWindow();
                // The menu sits over the game and swallows anything aimed at
                // the map behind it.
                if (menu != null && menu.isOpen()) {
                    // In design pixels, because that is what the menu was
                    // drawn in. Clicking it in screen pixels agreed with the
                    // drawing only while the interface scale was one, so the
                    // menu became unclickable the moment the window went full
                    // screen and started drawing at two.
                    menu.click(toDesign(event.getX()), toDesign(event.getY()));
                    refreshCursor();
                    repaint();
                    return;
                }
                if (chat != null && chat.click(toDesign(event.getX()), toDesign(event.getY()))) {
                    repaint();
                    return;
                }
                // Where the press landed, whatever it landed on: the drag
                // handler decides what a drag means from this, and leaving it
                // holding the last click on the map turns a drag across the
                // sidebar into a selection band.
                bandStartX = event.getX();
                bandStartY = event.getY();
                if (!isOnMap(event.getX(), event.getY())) {
                    // The chrome. Two things in it answer a click: the minimap
                    // and the command grid. Everything else is decoration, and
                    // a click on decoration has to stop here rather than fall
                    // through and be read as a point on the map.
                    // Clicking what a building is making cancels it, which is
                    // how the original works and the only way to cancel one
                    // item rather than everything a building is doing.
                    Unit shown = selected;
                    // Three jobs can be showing in that slot, not two. An
                    // upgrade-to was left out, so the one job whose panel had
                    // no cancel button either was also the one whose icon did
                    // not answer a click.
                    if (shown != null && world.canControl(localPlayer, shown.player())
                            && (shown.producing() != null || shown.researching() != null
                                    || shown.upgradingTo() != null)
                            && SidePanel.productionBounds().contains(
                                    toDesign(event.getX()), toDesign(event.getY()))) {
                        // Through the sink, like the three cancel buttons in
                        // the command grid that mean the same three things.
                        if (shown.producing() != null) {
                            commands.issue(GameCommand.cancelTraining(
                                    localPlayer, shown.id()));
                            status = "Training cancelled.";
                        } else if (shown.upgradingTo() != null) {
                            commands.issue(GameCommand.cancelUpgradeTo(
                                    localPlayer, shown.id()));
                            status = "Upgrade cancelled.";
                        } else {
                            commands.issue(GameCommand.cancelResearch(
                                    localPlayer, shown.id()));
                            status = "Research cancelled.";
                        }
                        repaint();
                        return;
                    }
                    if (SidePanel.menuButtonBounds().contains(
                            toDesign(event.getX()), toDesign(event.getY()))) {
                        if (menu != null) {
                            menu.open();
                    refreshCursor();
                            repaint();
                        }
                        return;
                    }
                    var idleCount = panel == null ? null : panel.freeWorkersBounds();
                    if (idleCount != null && idleCount.contains(
                            toDesign(event.getX()), toDesign(event.getY()))) {
                        findIdleWorker();
                        repaint();
                        return;
                    }
                    if (SidePanel.isOnMinimap(toDesign(event.getX()), toDesign(event.getY()))) {
                        clickMinimap(event.getX(), event.getY(),
                                javax.swing.SwingUtilities.isRightMouseButton(event),
                                Modifiers.of(event));
                        repaint();
                        return;
                    }
                    if (commands != null && !javax.swing.SwingUtilities.isRightMouseButton(event)) {
                        // A loaded transport shows its passengers here instead
                        // of its orders, and clicking one puts that one ashore.
                        Unit aboard = commandPanel.cargoAt(
                                toDesign(event.getX()), toDesign(event.getY()));
                        if (aboard != null && selected != null) {
                            commands.issue(GameCommand.unloadOne(
                                    localPlayer, selected.id(), aboard.id()));
                            status = "";
                            repaint();
                            return;
                        }
                        var button = commandPanel.buttonAt(
                                toDesign(event.getX()), toDesign(event.getY()));
                        if (button != null) {
                            // Control, or command on a Mac keyboard that has
                            // no comfortable control: KeyModifiers &
                            // ModifierControl in DoClicked_SpellCast.
                            press(button, event.isControlDown() || event.isMetaDown());
                            repaint();
                        }
                    }
                    return;
                }
                int tileX = worldX(event.getX()) / TILE;
                int tileY = worldY(event.getY()) / TILE;
                if (!world.map().contains(tileX, tileY)) {
                    return;
                }
                // Alt and a click points at the ground for everyone, wherever
                // it lands.
                if (event.isAltDown()
                        && !javax.swing.SwingUtilities.isRightMouseButton(event)) {
                    ping(tileX, tileY);
                    repaint();
                    return;
                }
                if (javax.swing.SwingUtilities.isRightMouseButton(event)) {
                    // A right click abandons a pending placement rather than
                    // ordering anything, which is how the original cancels.
                    if (placing != null || pendingAction != null) {
                        placing = null;
                        pendingAction = null;
                        pendingSpell = null;
                        status = "";
                        // And back to the root page. CancelBuildingMode, at
                        // clears the cursor and sets
                        // CurrentButtonLevel to zero together. Clearing only
                        // the cursor left the build page up over a peasant
                        // that was no longer building anything.
                        if (commandPanel != null) {
                            commandPanel.resetLevel();
                        }
                    } else {
                        commandSelected(tileX, tileY,
                                unitUnder(event.getX(), event.getY()), Modifiers.of(event));
                    }
                } else if (placing != null) {
                    placeBuilding(tileX, tileY, event.isShiftDown());
                } else if (pendingAction != null) {
                    aimPendingAction(tileX, tileY,
                            unitUnder(event.getX(), event.getY()), event.isShiftDown());
                } else {
                    bandStartX = event.getX();
                    bandStartY = event.getY();
                    // Control does what a double click does. The game's own
                    // Tips page promises it -- "you can select all of your
                    // currently visible units of the same type by holding down
                    // the CTRL key and selecting a unit or by double clicking",
                    // scripts/menus/help.legacy-declaration:93 -- and only the double click
                    // worked. UIHandleButtonUp,, treats the two
                    // as one case: (KeyModifiers & ModifierControl) ||
                    // (button & (1 << MouseDoubleShift)). With shift as well it
                    // toggles the type into the selection rather than
                    // replacing it, which is ToggleUnitsByType.
                    boolean control = event.isControlDown() || event.isMetaDown();
                    if (event.getClickCount() >= 2 || control) {
                        selectAllOfTypeOnScreen(unitUnder(event.getX(), event.getY()),
                                event.isShiftDown());
                    } else {
                        selectUnit(unitUnder(event.getX(), event.getY()),
                                event.isShiftDown());
                    }
                }
                repaint();
            }

            @Override
            public void mouseReleased(MouseEvent event) {
                if (menu != null && menu.isOpen()) {
                    menu.release();
                    return;
                }
                java.awt.Rectangle dragged = band;
                band = null;
                if (dragged != null && dragged.width > 4 && dragged.height > 4) {
                    selectWithin(dragged, event.isShiftDown());
                }
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                // A tooltip pinned to the last point seen outlives the
                // pointer that asked for it.
                if (panel != null) {
                    panel.setPointer(null);
                }
            }
        });

        addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                pointer = event.getPoint();
                // The panel's hover state, in design pixels the screen
                // converts itself. The panel used to work the pointer out on
                // its own from an AWT event listener, dividing window
                // coordinates by the full transform scale -- which on a HiDPI
                // display includes the device's own doubling that mouse
                // events are already normalised for. At interface two on
                // such a display every hover landed at half its real
                // position: the gold tooltip answered a pointer resting on
                // the oil figure, and the four counts on the right could not
                // be reached at all, because their halved positions fell off
                // the window.
                if (panel != null) {
                    panel.setPointer(new java.awt.Point(
                            toDesign(event.getX()), toDesign(event.getY())));
                }
                updateCursor(event.getX(), event.getY());
                if (menu != null && menu.isOpen()) {
                    // Likewise the hover highlight: nothing behind the menu
                    // should light up under a pointer aimed at the menu.
                    return;
                }
                updateHover(event.getX(), event.getY());
                if (placing != null) {
                    repaint();
                }
            }

            @Override
            public void mouseDragged(MouseEvent event) {
                pointer = event.getPoint();
                if (menu != null && menu.isOpen()) {
                    // A slider is dragged, not clicked, and the drag has to
                    // reach it rather than being read as a selection band over
                    // the map the menu is covering.
                    if (menu.drag(toDesign(event.getX()), toDesign(event.getY()))) {
                        repaint();
                    }
                    return;
                }
                // Dragging across the minimap keeps scrolling, which is how the
                // original lets you follow something across the map.
                if (SidePanel.isOnMinimap(toDesign(bandStartX), toDesign(bandStartY))) {
                    if (!javax.swing.SwingUtilities.isRightMouseButton(event)
                            && SidePanel.isOnMinimap(
                                    toDesign(event.getX()), toDesign(event.getY()))) {
                        clickMinimap(event.getX(), event.getY(), false, Modifiers.of(event));
                        repaint();
                    }
                    return;
                }
                if (placing != null || javax.swing.SwingUtilities.isRightMouseButton(event)
                        || !isOnMap(bandStartX, bandStartY)) {
                    return;
                }
                band = new java.awt.Rectangle(
                        Math.min(bandStartX, event.getX()), Math.min(bandStartY, event.getY()),
                        Math.abs(event.getX() - bandStartX), Math.abs(event.getY() - bandStartY));
                repaint();
            }
        });
    }

    /**
     * Carries out what a command slot means.
     *
     * <p>The button says what to do and to what; this turns that into the
     * engine call. Building is the exception, because it needs a square: the
     * choice is remembered and the next click on the ground completes it.
     */
    /**
     * Everything of yours that is selected and alive.
     *
     * <p>The command panel already thinks in selections: it works out which
     * buttons are available by intersecting across the whole group, and draws
     * the {@code <race>-group} icon set when the selection is mixed. It was
     * only the carrying out that thought in single units, so pressing Stop
     * with a group selected stopped one of them. The native command paths all
     * iterate the ordered selection; so does the right-click path here.
     */
    private java.util.List<Unit> selectedUnits() {
        reconcileSelectionOrder();
        java.util.List<Unit> mine = new java.util.ArrayList<>();
        java.util.Map<Integer, Unit> byId = new java.util.HashMap<>();
        for (Unit unit : world.unitsSnapshot()) {
            byId.put(unit.id(), unit);
        }
        for (int id : selectionOrder) {
            Unit unit = byId.get(id);
            if (unit != null && unit.selected()
                    && world.canControl(localPlayer, unit.player()) && unit.isAlive()) {
                mine.add(unit);
            }
        }
        if (mine.isEmpty() && selected != null
                && world.canControl(localPlayer, selected.player())
                && selected.isAlive()) {
            mine.add(selected);
        }
        return mine;
    }

    /** Reconciles per-unit flags with the native ordered nine-slot selection. */
    private void reconcileSelectionOrder() {
        java.util.Map<Integer, Unit> byId = unitsById();
        selectionOrder.removeIf(id -> {
            Unit unit = byId.get(id);
            return unit == null || !unit.selected() || !unit.isAlive()
                    || !world.canControl(localPlayer, unit.player());
        });
        for (Unit unit : byId.values()) {
            if (!unit.selected() || !unit.isAlive()
                    || !world.canControl(localPlayer, unit.player())
                    || selectionOrder.contains(unit.id())) {
                continue;
            }
            if (selectionOrder.size() < MAX_SELECTED_UNITS) {
                selectionOrder.add(unit.id());
            } else {
                unit.setSelected(false);
            }
        }
    }

    private java.util.Map<Integer, Unit> unitsById() {
        java.util.Map<Integer, Unit> byId = new java.util.LinkedHashMap<>();
        for (Unit unit : world.unitsSnapshot()) {
            byId.put(unit.id(), unit);
        }
        return byId;
    }

    private void press(net.chonkbase.chonkcraft.engine.ui.UnitButton button) {
        press(button, false);
    }

    /**
     * Presses a command slot.
     *
     * @param toggleAutoCast whether the modifier that turns a spell into a
     *                       standing order was held. Upstream reads
     *                       {@code KeyModifiers & ModifierControl} inside
     *                       {@code DoClicked_SpellCast}; here it is passed in,
     *                       because a screen that consulted a global would be
     *                       a screen whose behaviour a test cannot set up
     */
    void press(net.chonkbase.chonkcraft.engine.ui.UnitButton button,
            boolean toggleAutoCast) {
        Unit unit = selected;
        if (unit == null || !world.canControl(localPlayer, unit.player()) || !unit.isAlive()) {
            return;
        }
        // Every command icon that answers a click clicks back.
        // {@code CButtonPanel::DoClicked} plays {@code GameSounds.Click} at
        // after the availability check and
        // before the switch, so a page switch clicks as well as an order does.
        // The whole grid was silent here.
        playUi("click");
        int page = button.switchesToLevel();
        if (page >= 0) {
            commandPanel.setLevel(page);
            return;
        }
        if (toggleAutoCast && "cast-spell".equals(button.action())) {
            toggleAutoCast(button);
            return;
        }
        // Anything that needs somewhere to point is armed here rather than
        // carried out. Warcraft II's model is press the icon, then click the
        // ground, and this is that second click waiting to happen.
        if ("cast-spell".equals(button.action())) {
            // A spell that acts on its caster needs no target and is cast on
            // every selected caster at once, as DoClicked_SpellCast does.
            var spell = button.value() == null
                    ? null
                    : data.spells().spells().get(button.value());
            if (spell == null) {
                status = "";
                return;
            }
            if (spell.target() == net.chonkbase.chonkcraft.engine.spell.Spell.Target.SELF) {
                int index = spellIndex(button.value());
                for (Unit each : selectedUnits()) {
                    if (each.isCaster()) {
                        commands.issue(GameCommand.cast(localPlayer, each.id(), 0, index));
                    }
                }
                status = "";
                return;
            }
            pendingSpell = button.value();
        }
        // Unloading is the one command that is sometimes instant and
        // sometimes aimed. {@code CButtonPanel::DoClicked_Unload},
        // The game a single naval transport sitting
        // still on a coast tile dumps its cargo where it is, and anything else
        // -- a boat out at sea, a group, a bunker under way -- falls through to
        // DoClicked_SelectTarget and waits for the player to point at a beach.
        //
        // The implementation only had the first half, and only ever tried the square the
        // boat was floating on. That is why the button appeared to do nothing:
        // a transport one tile off the coast is not on a coast tile, so the
        // single attempt found nowhere to put anybody and there was no second
        // half to sail the last tile.
        if ("unload".equals(button.action())) {
            if (unloadsOnTheSpot(unit)) {
                for (Unit each : selectedUnits()) {
                    commands.issue(GameCommand.unload(
                            localPlayer, each.id(), each.tileX(), each.tileY()));
                }
                status = "";
                commandPanel.resetLevel();
                return;
            }
            pendingAction = "unload";
            status = "Select a target.";
            commandPanel.setLevel(CANCEL_LEVEL);
            return;
        }
        if (TARGETED_ACTIONS.contains(button.action())) {
            pendingAction = button.action();
            status = "Select a target.";
            // Page nine is the cancel-only page. Warcraft II clears the grid
            // down to one ESC icon while it waits for the target, so there is
            // no doubt the next click is spoken for.
            commandPanel.setLevel(CANCEL_LEVEL);
            return;
        }
        String value = button.value();
        switch (button.action()) {
            case "stop" -> {
                for (Unit each : selectedUnits()) {
                    commands.issue(GameCommand.stop(localPlayer, each.id()));
                }
                status = "";
            }
            // Warcraft II will not put a building on the cursor that the
            // player cannot pay for. DoClicked_Build, at
            // is one condition -- "if
            // (!Selected[0]->Player->CheckUnitType(type))" -- and
            // CheckUnitType is CheckCosts, which answers zero when everything
            // is affordable. A Build Farm pressed with three hundred gold
            // therefore never reaches CursorBuilding; it prints why and stops.
            //
            // This implementation dimmed the icon and armed the cursor anyway, and a
            // player reported the whole of it in one sentence: a dimmed Build
            // Farm with 300 gold gave the placement cursor, the farm was
            // placed, the peasant answered with its acknowledgement, and
            // nothing was built. Measured on human mission one with the bank
            // set to 300 against a farm at 500 gold and 250 lumber:
            // CommandPanel.affordable answered false and dimmed the icon,
            // press() set placing=unit-farm all the same, the cursor over open
            // ground read ACT, the click left the line reading "building
            // Farm", the peasant acknowledged, and the roster went from twelve
            // units to twelve. The dimming reached the drawing and nothing
            // else.
            case "build" -> {
                UnitType what = value == null ? null : types().get(value);
                String cannotPay = what == null ? null : shortfall(what.costs());
                if (cannotPay != null) {
                    status = cannotPay;
                } else {
                    placing = what;
                    status = placing == null ? "" : "Place " + placing.name() + ".";
                }
            }
            // The eight below used to call the world where they stand, while
            // every other button in this switch went through the sink. In a
            // network game the first "Train Peasant" spent four hundred gold
            // on the machine that clicked it and nothing on the other, and
            // the two simulations disagreed from that cycle on. Upstream
            // sends all eight: SendCommandTrainUnit and its siblings,
            // The game onward.
            //
            // What is lost by sending rather than calling is the answer: the
            // world says yes or no when it is asked, and a command has not
            // been obeyed yet when the status line is written. So the refusals
            // a player can see for himself are checked here, without touching
            // anything, exactly as DoClicked_Train checks CheckLimits before
            // it sends.
            case "train-unit" -> {
                UnitType what = value == null ? null : types().get(value);
                int index = what == null ? -1 : applier.indexOf(what);
                // Supply before cost, which is the order DoClicked_Train asks
                // in: "CheckLimits(type) == ECheckLimit::Ok &&
                // !CheckUnitType(type)".
                String refused = what == null ? null : noRoom(what);
                if (refused == null && what != null) {
                    refused = shortfall(what.costs());
                }
                if (index < 0) {
                    status = "Cannot train that now.";
                } else if (refused != null) {
                    status = refused;
                } else {
                    boolean accepted = commands.issueAccepted(
                            GameCommand.train(localPlayer, unit.id(), index));
                    status = accepted ? "" : "Cannot train that now.";
                }
            }
            case "upgrade-to" -> {
                UnitType what = value == null ? null : types().get(value);
                int index = what == null ? -1 : applier.indexOf(what);
                // No supply test here, deliberately: DoClicked_UpgradeTo
                // The game asks only that the specific unit limit
                // is not reached and that the costs are met. A keep replaces
                // the town hall it grew from, so it needs no extra food.
                String cannotPay = what == null ? null : shortfall(what.costs());
                if (index < 0) {
                    status = "Cannot upgrade now.";
                } else if (cannotPay != null) {
                    status = cannotPay;
                } else {
                    boolean accepted = commands.issueAccepted(
                            GameCommand.upgradeTo(localPlayer, unit.id(), index));
                    status = accepted ? "" : "Cannot upgrade now.";
                }
            }
            case "research" -> {
                int index = value == null ? -1 : applier.indexOfUpgrade(value);
                // DoClicked_Research: "if
                // (!Selected[0]->Player->CheckCosts(AllUpgrades[index]
                // ->Costs))". This implementation checked nothing at all, so pressing
                // an upgrade with an empty bank said "researching
                // upgrade-sword1" and the blacksmith went on standing there.
                var upgrade = value == null || world.upgradeSet() == null
                        ? null
                        : world.upgradeSet().get(value);
                String cannotPay = upgrade == null ? null : shortfall(upgrade.costs());
                if (index < 0) {
                    status = "Cannot research that now.";
                } else if (cannotPay != null) {
                    status = cannotPay;
                } else {
                    boolean accepted = commands.issueAccepted(
                            GameCommand.research(localPlayer, unit.id(), index));
                    status = accepted ? "" : "Cannot research that now.";
                }
            }
            // Four separate things, not one. Plain cancel abandons a pending
            // command; the other three give back part of what was spent.
            case "cancel" -> {
                cancelPending();
                status = "";
            }
            case "cancel-train-unit" -> {
                commands.issue(GameCommand.cancelTraining(localPlayer, unit.id()));
                status = unit.producing() == null ? "" : "Training cancelled.";
            }
            // One button, two meanings, as DoClicked_CancelUpgrade has it: it
            // stops whichever of the two a building is doing. Which of the two
            // is decided here rather than by trying one and then the other,
            // because trying is a mutation and this side of the sink may not
            // make one -- and it is what upstream does, switching on
            // CurrentAction before it picks a message to send.
            case "cancel-upgrade" -> {
                if (unit.upgradingTo() != null) {
                    commands.issue(GameCommand.cancelUpgradeTo(localPlayer, unit.id()));
                    status = "Upgrade cancelled.";
                } else if (unit.researching() != null) {
                    commands.issue(GameCommand.cancelResearch(localPlayer, unit.id()));
                    status = "Research cancelled.";
                } else {
                    status = "";
                }
            }
            case "cancel-build" -> {
                commands.issue(GameCommand.cancelConstruction(localPlayer, unit.id()));
                status = "Construction cancelled.";
            }
            case "return-goods" -> {
                boolean accepted = false;
                for (Unit each : selectedUnits()) {
                    accepted |= commands.issueAccepted(
                            GameCommand.returnGoods(localPlayer, each.id()));
                }
                status = unit.carrying() == null ? "Nothing to return."
                        : accepted ? ""
                        : bne(BattleNetMessages.Key.NOWHERE_TO_RETURN);
            }
            case "stand-ground" -> {
                for (Unit each : selectedUnits()) {
                    commands.issue(GameCommand.standGround(localPlayer, each.id()));
                }
                status = "";
            }
            case "explore" -> {
                for (Unit each : selectedUnits()) {
                    commands.issue(GameCommand.explore(localPlayer, each.id()));
                }
                status = "";
            }
            default -> status = button.plainHint();
        }
        // Every command that is not a page switch comes back to the root, as
        // the original does once an order is given.
        if (!"build".equals(button.action())) {
            commandPanel.resetLevel();
        }
    }

    /**
     * What the game says when the player cannot pay, or {@code null} when they
     * can.
     *
     * <p>Implements {@code CPlayer::CheckCosts}. Upstream walks the cost
     * table from index one -- index zero is the build time, which is not
     * something anybody holds -- and for each resource that is short it calls
     * {@code Notify(_("Not enough %s...%s more %s."))} and sets a bit. The
     * answer here is the sentence rather than the bit mask, because the only
     * caller that ever read the mask read it as a boolean.
     *
     * <p>Asked rather than attempted. {@code Player.pay} answers the same
     * question by spending the money, which is the whole difficulty: the
     * screen may not spend anything, because in a network game the spending
     * has to happen on every machine on the same cycle and not on this one
     * now. This reads the bank and changes nothing.
     *
     * <p>In upstream's order -- gold, then wood, then oil -- walked from
     * {@link net.chonkbase.chonkcraft.engine.unit.UnitType.Resource} rather than
     * from the cost map. Nothing here reaches the simulation, so no two
     * machines can disagree over it, but a player short of both gold and
     * lumber would be told about whichever resource a hash map happened to
     * hand over first, and the sentence they read should not depend on that.
     *
     * <p>Departs from upstream in one bounded way, and the bound is one line
     * of text. Upstream notifies once per resource that is short and its
     * message system stacks the lines, so a player short of gold and lumber
     * reads both. This implementation has a single status line -- {@code UI.StatusLine},
     * one line tall in the artwork -- so it says the first shortfall in the
     * game's own resource order and not the second. Everything the player is
     * short of is on the sidebar beside it.
     */
    private String shortfall(java.util.Map<UnitType.Resource, Integer> costs) {
        var player = world.player(localPlayer);
        if (player == null || costs == null) {
            return null;
        }
        for (UnitType.Resource resource : UnitType.Resource.values()) {
            // Time is how long the thing takes, not something the player
            // holds. Upstream skips it by counting from one.
            if (resource == UnitType.Resource.TIME) {
                continue;
            }
            int price = costs.getOrDefault(resource, 0);
            if (price > 0 && player.get(resource) < price) {
                return notEnough(resource);
            }
        }
        return null;
    }

    /**
     * What the game says when there is no food for another unit, or
     * {@code null} when there is.
     *
     * <p>The supply arm of {@code CPlayer::CheckLimits}
     * The game "this->Demand +
     * type.Demand > this->Supply", which {@code DoClicked_Train} asks before
     * it asks about money at all.
     *
     * <p>Counted off the roster rather than read off {@code Player.supply}
     * and {@code Player.demand}, and that is not fussiness. Those two are a
     * cache that {@code World.recalculateSupply} fills, and the world fills it
     * when something happens -- a building finishes, a unit is trained, a unit
     * dies -- not on the cycle this is asked. A fixture that has never had one
     * of those events reads nought supply and nought demand, so a hall with
     * four farms behind it would be told to build more farms; and the screen
     * may not call {@code recalculateSupply} itself, because that writes to
     * every player from the event thread. This walks the published snapshot
     * and adds up exactly what {@code recalculateSupply} adds up.
     *
     * <p>Departs from {@code World.orderTrain} by the training queue, and it
     * is worth saying which way. The world adds the demand of every job
     * already paid for at every barracks before it answers, so a player with
     * exactly one place left and three footmen on order is refused by the
     * world and allowed by this. The difference is that this implementation has a queue
     * and upstream's {@code CheckLimits} does not look at it either, so the
     * preflight agrees with upstream while the authoritative engine remains
     * stricter. A local rejection is reported immediately. A network sink can
     * only confirm that the command entered lockstep, so this screen stays
     * silent rather than claiming the unit began training before the shared
     * simulation applies it.
     */
    private String noRoom(UnitType what) {
        if (what == null || what.demand() <= 0) {
            return null;
        }
        int supply = 0;
        int demand = 0;
        int owner = selected == null ? localPlayer : selected.player();
        for (Unit unit : world.unitsSnapshot()) {
            if (!unit.isAlive() || unit.player() != owner || unit.type() == null) {
                continue;
            }
            supply += unit.type().supply();
            demand += unit.type().demand();
        }
        return demand + what.demand() <= supply
                ? null : bne(BattleNetMessages.Key.NOT_ENOUGH_FOOD);
    }

    /**
     * The 1995 sentence for a resource the player has run out of.
     *
     * <p>Recovered rather than written. LegacyEngine builds its own wording out
     * of {@code DefineDefaultResourceNames} and {@code DefineDefaultActions}
     * ({@code scripts/legacyEngine.legacy-declaration:239-244}) and gets close -- "Not enough
     * gold...mine more gold." is character for character what Warcraft II
     * says -- but not all the way: it would say "Not enough wood...chop more
     * wood." where the game says lumber and trees, and "Not enough
     * oil...drill more oil." where the game says "drill for oil".
     *
     * <p>The real ones are in the data the player installed. Entry 1 of
     * {@code strdat.war} is the string table this implementation already reads for
     * asset paths -- {@code NameTable}, published by {@code GameData.names} --
     * and its slots 438 to 441 hold the four sentences in resource order. The
     * indices are fixed the same way every other index into that table is:
     * the conversion table names assets by number through it, so a release
     * that moved them would break far more than a message.
     */
    private String notEnough(UnitType.Resource resource) {
        return switch (resource) {
            case GOLD -> bne(BattleNetMessages.Key.NOT_ENOUGH_GOLD);
            case WOOD -> bne(BattleNetMessages.Key.NOT_ENOUGH_LUMBER);
            case OIL -> bne(BattleNetMessages.Key.NOT_ENOUGH_OIL);
            case TIME -> null;
        };
    }

    /** One of the game's own strings, with an exact fallback for incomplete packs. */
    private String bne(BattleNetMessages.Key key) {
        return BattleNetMessages.text(data, key);
    }

    /**
     * Carries out the armed command at the square just clicked.
     *
     * <p>Each of these has the same shape: work out what the click meant for
     * this order, then issue it as a command, so single player and multiplayer
     * take the same path.
     */
    private void aimPendingAction(int tileX, int tileY) {
        aimPendingAction(tileX, tileY, world.unitAt(tileX, tileY), false);
    }

    private void aimPendingAction(int tileX, int tileY, boolean queued) {
        aimPendingAction(tileX, tileY, world.unitAt(tileX, tileY), queued);
    }

    private void aimPendingAction(int tileX, int tileY, Unit under, boolean queued) {
        String action = pendingAction;
        pendingAction = null;
        // Arming the order put the grid on page nine, the cancel-only page;
        // carrying it out takes it off again. SendCommand does this first
        // thing,, before it works out which order it
        // is sending. Left set, the panel showed one ESC icon and nothing else
        // for every unit clicked afterwards.
        if (commandPanel != null) {
            commandPanel.resetLevel();
        }
        Unit unit = selected;
        if (unit == null || !world.canControl(localPlayer, unit.player()) || !unit.isAlive()) {
            return;
        }
        if (under != null) {
            tileX = under.tileX();
            tileY = under.tileY();
        }
        // Every selected unit, not just the one whose panel was pressed. An
        // aimed order given to a group is the commonest order in the game.
        java.util.List<Unit> group = selectedUnits();
        Unit answering = null;
        switch (action) {
            case "move" -> {
                for (Unit each : group) {
                    if (commands.issueAccepted(GameCommand.move(
                            localPlayer, each.id(), tileX, tileY).withQueued(queued))
                            && answering == null) {
                        answering = each;
                    }
                }
                status = answering == null ? "No selected unit can move there." : "";
            }
            // Point at the beach and the boat sails there. The square clicked
            // need not be water, or land, or reachable: COrder_Unload searches
            // twenty tiles out from it for somewhere a boat can sit and a
            // passenger can step off.
            case "unload" -> {
                for (Unit each : group) {
                    if (commands.issueAccepted(GameCommand.unload(
                            localPlayer, each.id(), tileX, tileY)) && answering == null) {
                        answering = each;
                    }
                }
                status = answering == null ? "No selected transport can unload there." : "";
            }
            case "attack" -> {
                boolean wall = world.map().field(tileX, tileY).isWall();
                for (Unit each : group) {
                    boolean accepted;
                    if (under != null && under != each) {
                        accepted = commands.issueAccepted(GameCommand.attack(
                                localPlayer, each.id(), under.id()).withQueued(queued));
                    } else if (wall && each.type().canAttack()) {
                        accepted = commands.issueAccepted(GameCommand.attackGround(
                                localPlayer, each.id(), tileX, tileY).withQueued(queued));
                    } else {
                        accepted = commands.issueAccepted(GameCommand.move(
                                localPlayer, each.id(), tileX, tileY).withQueued(queued));
                    }
                    if (accepted && answering == null) {
                        answering = each;
                    }
                }
                status = answering == null ? "No selected unit can attack that." : "";
            }
            case "patrol" -> {
                for (Unit each : group) {
                    if (commands.issueAccepted(GameCommand.patrol(
                            localPlayer, each.id(), tileX, tileY).withQueued(queued))
                            && answering == null) {
                        answering = each;
                    }
                }
                status = answering == null ? "No selected unit can patrol there." : "";
            }
            case "repair" -> {
                if (under != null && world.canControl(localPlayer, under.player())) {
                    for (Unit each : group) {
                        if (each != under) {
                            if (commands.issueAccepted(GameCommand.repair(
                                    localPlayer, each.id(), under.id()).withQueued(queued))
                                    && answering == null) {
                                answering = each;
                            }
                        }
                    }
                    status = answering == null ? "No selected worker can repair that." : "";
                } else {
                    status = "Nothing of yours to repair there.";
                }
            }
            case "harvest" -> {
                for (Unit each : group) {
                    if (commands.issueAccepted(GameCommand.harvest(
                            localPlayer, each.id(), tileX, tileY).withQueued(queued))
                            && answering == null) {
                        answering = each;
                    }
                }
                status = answering == null ? "Nothing to gather there." : "";
            }
            case "attack-ground" -> {
                for (Unit each : group) {
                    if (commands.issueAccepted(GameCommand.attackGround(
                            localPlayer, each.id(), tileX, tileY).withQueued(queued))
                            && answering == null) {
                        answering = each;
                    }
                }
                status = answering == null ? "No selected unit can attack the ground." : "";
            }
            case "cast-spell" -> {
                String ident = pendingSpell;
                var spell = ident == null ? null : data.spells().spells().get(ident);
                if (spell == null) {
                    status = "Nothing there to cast at.";
                } else if (spell.target()
                        == net.chonkbase.chonkcraft.engine.spell.Spell.Target.UNIT
                        && under == null) {
                    status = "Nothing there to cast at.";
                } else {
                    int index = spellIndex(ident);
                    for (Unit each : group) {
                        if (each.isCaster() && (under == null || each != under)) {
                            GameCommand cast = spell.target()
                                    == net.chonkbase.chonkcraft.engine.spell.Spell.Target.POSITION
                                    ? GameCommand.castAt(localPlayer, each.id(), tileX, tileY, index)
                                    : GameCommand.cast(localPlayer, each.id(),
                                            under == null ? each.id() : under.id(), index);
                            if (commands.issueAccepted(cast.withQueued(queued))
                                    && answering == null) {
                                answering = each;
                            }
                        }
                    }
                    status = answering == null ? "No selected caster can cast that." : "";
                }
                pendingSpell = null;
            }
            default -> status = "";
        }
        if (answering != null) {
            playUnit(answering, "acknowledge", this::chooseSample);
        }
    }

    /**
     * Selects every unit of the clicked type that is on screen.
     *
     * <p>What a double click does. On screen rather than on the map: the
     * gesture is meant to gather what you are looking at, not to reach across
     * the whole map for stragglers.
     */
    private void selectAllOfTypeOnScreen(Unit clicked) {
        selectAllOfTypeOnScreen(clicked, false);
    }

    /**
     * @param add whether to add the type to the selection rather than replace
     *            it, which is what {@code ToggleUnitsByType} does when shift
     *            is held with the control click or the double click
     */
    private void selectAllOfTypeOnScreen(Unit clicked, boolean add) {
        if (clicked == null || !world.canControl(localPlayer, clicked.player())
                || clicked.type() == null) {
            selectUnit(clicked, add);
            return;
        }
        // A building or a critter does not gather its kind. Both upstream
        // selectors stop before the sweep when the clicked type lacks
        // SelectableByRectangle: SelectUnitsByType keeps just the clicked
        // unit and ToggleUnitsByType leaves the
        // selection as it stands. This honoured the flag
        // for a band drag and not here, so control-clicking a farm collected
        // every farm on screen, which Warcraft II never did -- 87 of the 143
        // shipped types lack the flag, every building in both tech trees
        // among them.
        if (!clicked.type().selectableByRectangle()) {
            if (!add) {
                selectUnit(clicked, false);
            }
            return;
        }
        int count = 0;
        Unit first = null;
        for (Unit unit : world.unitsSnapshot()) {
            boolean sameKind = unit.player() == clicked.player() && unit.isAlive() && unit.isOnMap()
                    && unit.type() == clicked.type() && isOnScreen(unit);
            boolean take = sameKind || (add && unit.selected());
            unit.setSelected(take);
            if (take) {
                count++;
                if (first == null) {
                    first = unit;
                }
            }
        }
        selectionChanged(first);
        status = count + " " + clicked.type().name() + ".";
        playUnit(clicked, "selected", this::chooseSample);
        repaint();
    }

    /**
     * The sounds, with the device allowed to be absent.
     *
     * <p>Several tests and every headless peer build the screen with no
     * {@code GameAudio} at all, and a screen that can only run with speakers
     * is a screen the suite cannot drive. The choice is still made either way,
     * because which clip the game reaches for is a fact about the game.
     */
    private void playUi(String name) {
        if (audio != null) {
            audio.playUi(name);
        }
    }

    private void playUnit(Unit unit, String event,
            java.util.function.IntUnaryOperator pick) {
        if (audio != null) {
            audio.playUnit(unit, event, pick);
        } else {
            pick.applyAsInt(1);
        }
    }

    private void playNamedAt(String name,
            java.util.function.IntUnaryOperator pick, float pan) {
        if (audio != null) {
            audio.playNamedAt(name, pick, pan);
        } else {
            pick.applyAsInt(1);
        }
    }

    /** Frames drawn since the game started. */
    private int frameCounter;

    /** How many times a sound has been asked which of its clips to use. */
    private long soundChoices;

    /**
     * Which of a sound's clips to play this time.
     *
     * <p>Implements {@code SimpleChooseSample},
     * which is one line and uses no random number at all:
     * {@code sound.Sound.OneGroup[FrameCounter % sound.Number]}.
     *
     * <p>This used to hand {@code World.syncRand} to every sound the screen
     * played, and that is a desync waiting for a network game. The simulation's
     * generator is one integer that both machines advance in step, and half
     * these calls sit inside branches that are local by definition -- is the
     * unit on screen, is it inside the local player's fog, does it belong to
     * the player sitting here. A draw taken on one machine and not on the other
     * puts the two on different numbers for the rest of the match, and the
     * first thing to disagree afterwards is a damage roll rather than a sound.
     * Moving the choice onto a frame counter removes the hazard instead of
     * working around it: the screen's own frame count is nobody else's
     * business, so it does not matter that it differs between machines.
     *
     * <p>{@code GameAudio.choose} still asks exactly once per sound, before
     * any early return. That rule was written for the synchronised generator
     * and is kept, because it is also what makes {@link #soundChoices} a
     * count of sounds rather than a count of sounds that happened to have a
     * clip.
     */
    private int chooseSample(int chunks) {
        soundChoices++;
        return chunks <= 1 ? 0 : Math.floorMod(frameCounter, chunks);
    }

    /**
     * How many sounds have been chosen between.
     *
     * <p>For the tests that count voices. There is no audio device on a
     * headless machine and nothing is mixed, so the only way to ask "how many
     * units spoke" is to count the choices, which is exactly one per sound.
     */
    long soundChoicesForTest() {
        return soundChoices;
    }

    private boolean isOnScreen(Unit unit) {
        int x = unit.tileX() * TILE - cameraX;
        int y = unit.tileY() * TILE - cameraY;
        return x >= -TILE && y >= -TILE
                && x <= visibleWorldWidth() && y <= visibleWorldHeight();
    }

    private Unit firstSelected() {
        reconcileSelectionOrder();
        if (!selectionOrder.isEmpty()) {
            int wanted = selectionOrder.get(0);
            for (Unit unit : world.unitsSnapshot()) {
                if (unit.id() == wanted) {
                    return unit;
                }
            }
        }
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.selected()) {
                return unit;
            }
        }
        return null;
    }

    /**
     * Changes what is selected, and puts back everything that belonged to what
     * was selected before.
     *
     * <p>Implements {@code SelectionChanged}, whose own comment is the whole rule:
     * "We Changed out selection, anything pending buttonwise must be cleared".
     * Upstream clears five things in one breath -- the status line, the costs
     * printed beside it, {@code CurrentButtonLevel}, the last drawn popup, and
     * {@code CursorBuilding} together with {@code CursorState} -- and fifteen
     * sites call it, from the mouse to a control group to a saved game being
     * loaded.
     *
     * <p>This implementation had no such function. Nine methods assigned {@code selected}
     * and each cleared the button page by hand, which is the arrangement where
     * the tenth is written without one. A player reported the result twice in a
     * single session, and it is one fault.
     *
     * <p>First: "clicking on buildings, they will have no options to build
     * anything." Send a peasant to put up a farm -- which switches the grid to
     * page one -- then click the town hall, and the panel asked page one for a
     * town hall. Nothing in the shipped data declares a town hall or a barracks
     * button on page one or page two, so the grid came back completely empty,
     * with the portrait, the name, the health bar and the armour line beside it
     * all drawn correctly. Empty, not dimmed: a button nobody can afford is
     * drawn dimmed, and this player had 1100 gold and 900 wood in the bank.
     *
     * <p>Then, later: "right now EVERYTHING I click on has the same ESC button
     * and nothing else." That is page nine, the cancel-only page an aimed order
     * puts the grid on while it waits for a target. Exactly one button in the
     * whole game sits there -- {@code scripts/buttons.legacy-declaration:47} -- and it declares
     * {@code ForUnit = {"*"}}, so a peasant, a town hall and a farm all show
     * that one icon and no other.
     *
     * <p>Clearing the page alone is not enough, and that is the half that
     * outlived nine separate hand-written resets. A build cursor or an armed
     * order belongs to whatever was selected when the icon was pressed. Left
     * standing across a selection change it eats the clicks that would have
     * changed the selection back: measured headless on mission one, a farm left
     * on the cursor by a peasant meant every later left click on the map went to
     * {@code placeBuilding} and answered "cannot build there", so nothing was
     * ever selected again, so the grid never left the page it was stuck on. That
     * is why the ESC icon was on everything the player clicked rather than on
     * the next thing only.
     *
     * @param nowShown what the info panel and the command grid are for from
     *                 here on, or {@code null} for nothing selected
     */
    private void selectionChanged(Unit nowShown) {
        reconcileSelectionOrder();
        selected = nowShown;
        // CursorBuilding = nullptr; CursorState = CursorStates::Point.
        placing = null;
        pendingAction = null;
        pendingSpell = null;
        // UI.StatusLine.Clear() and ClearCosts(). Callers with something to
        // report -- "8 selected" -- say it after this returns.
        status = "";
        // CurrentButtonLevel = 0.
        if (commandPanel != null) {
            commandPanel.resetLevel();
        }
        intents.selection(world.cycle(), java.util.List.copyOf(selectionOrder));
    }

    /**
     * Takes out of the selection anything the world has taken off the map.
     *
     * <p>{@code CUnit::Remove} ends with this: "if (this-&gt;Selected) { if
     * (Selected.size() == 1) CancelBuildingMode(); UnSelectUnit(*this);
     * SelectionChanged(); }". {@code LetUnitDie} calls {@code Remove}, so in Warcraft II a unit that dies, walks into a
     * gold mine, boards a transport or garrisons a tower stops being selected
     * on the instant, and the panel is put back with it.
     *
     * <p>Nothing in this implementation can tell the screen that. The engine has no
     * callback into the interface and is not allowed one -- the simulation does
     * not draw -- so the screen asks instead, once a frame, and that is the
     * deviation. The consequence is bounded at a single frame, a thirtieth of a
     * second, and no click can get through the gap: {@code press} and
     * {@code aimPendingAction} both refuse a unit that is not alive.
     *
     * <p>What it costs to leave out: aim a move across the map, lose the
     * footman to a grunt on the way, and the sidebar kept his portrait and his
     * health bar and the grid kept its single ESC icon. Proved headless on
     * mission one -- "the footman was killed, level=9, grid=[cancel]" -- and it
     * is the one route to that symptom the mouse cannot cause, so no amount of
     * clicking clears it.
     */
    private void dropLostUnits() {
        Unit shown = selected;
        boolean lost = shown != null && !(shown.isAlive() && shown.isOnMap());
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.selected() && !(unit.isAlive() && unit.isOnMap())) {
                unit.setSelected(false);
                lost = true;
            }
        }
        if (lost) {
            // Whatever is left of the selection, which is what UnSelectUnit
            // leaves behind: the other eleven footmen are still yours.
            selectionChanged(firstSelected());
        }
    }

    private int countSelected() {
        int count = 0;
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.selected()) {
                count++;
            }
        }
        return count;
    }

    /**
     * Sends the selected worker to put up the building it is holding.
     *
     * <p>The money is asked about here as well as when the icon was pressed,
     * and the second look is not belt and braces. A player can arm a farm with
     * six hundred gold in the bank and spend it on a footman before choosing
     * where the farm goes, and {@code World.orderBuild} then refuses at
     * {@code Player.pay} and drops the order on the floor -- by which time
     * this screen has said "building Farm", played the placement chime and had
     * the peasant answer. That was the worst part of what the player
     * reported: the game affirmatively said it was doing something it had
     * already decided not to do.
     *
     * <p>Upstream reaches the same place by a longer road and cannot be copied
     * exactly. {@code UIHandleButtonDown_OnMap}
     * The game tests only whether the ground will
     * take it, plays {@code PlacementSuccess} and the worker's Build voice,
     * and sends the order; the peasant then walks to the site and
     * {@code CheckLimit} tells
     * the player "Not enough resources to build %s" when it arrives. This implementation
     * has no such order -- {@code orderBuild} answers yes or no on the cycle
     * it is given -- so there is no walk during which to find out, and the
     * only honest place left to look is before the acknowledgement.
     */
    private void placeBuilding(int tileX, int tileY, boolean queued) {
        Unit worker = selected;
        UnitType what = placing;
        if (worker == null || what == null) {
            return;
        }
        String cannotPay = shortfall(what.costs());
        if (cannotPay != null) {
            // No chime and no acknowledgement: nothing was ordered. The
            // building stays on the cursor, as it does upstream, so the player
            // can gather what is missing and put it down.
            status = cannotPay;
            return;
        }
        // On behalf of this worker, not of nobody. An on-top rule turns down a
        // site with anything of the parent's own movement kind standing inside
        // it, and the player who has just sailed the tanker onto the oil is
        // standing in it. Upstream passes Selected[0] here --
        // and only the map editor passes nothing.
        //
        // Measured across all 52 campaign maps: of the 103 oil patches that
        // will take a platform on bare water, 103 answered this click with
        // "cannot build there" when the tanker was on the oil, while
        // orderBuild given that same tanker accepted every one. The engine
        // could found a platform and the interface could not, so whether the
        // third resource existed depended on the order the player did it in.
        int index = applier.indexOf(what);
        if (index >= 0 && world.canPlaceBuilding(worker, what, tileX, tileY)) {
            boolean accepted = commands.issueAccepted(GameCommand.build(
                    localPlayer, worker.id(), index, tileX, tileY).withQueued(queued));
            if (!accepted) {
                status = buildRefusal(what, tileX, tileY);
                playUi("placement-error-" + race);
                return;
            }
            status = "";
            // Holding Shift is also what lets a player stamp out several
            // sites without reopening the build menu for every one.
            //
            // Upstream ends a committed placement with CancelBuildingMode,
            // which puts the cursor and the button page back together --
            // "if (!(KeyModifiers & (ModifierAlt | ModifierShift)))
            // CancelBuildingMode();". Dropping the
            // cursor and keeping the page is what left the grid on page one
            // after a farm went down, so the next building clicked showed
            // nothing at all.
            if (!queued) {
                placing = null;
                if (commandPanel != null) {
                    commandPanel.resetLevel();
                }
            }
            // GameSounds.PlacementSuccess[race] and the worker's own Build
            // voice, both.
            playUi("placement-success-" + race);
            playUnit(worker, "acknowledge", this::chooseSample);
        } else {
            status = buildRefusal(what, tileX, tileY);
            // The distinctive refusal thunk. Placing a
            // building on ground that will not take it was silent, which is
            // the one case where a player is looking at the map rather than at
            // the status line.
            playUi("placement-error-" + race);
        }
    }

    /**
     * Chooses the specific retail placement notification that can be proved
     * from the cursor state. More involved distance restrictions remain the
     * authentic generic refusal until the engine exposes which rule failed;
     * guessing a more specific sentence would be worse than being concise.
     */
    private String buildRefusal(UnitType what, int tileX, int tileY) {
        int right = tileX + Math.max(1, what.tileWidth()) - 1;
        int bottom = tileY + Math.max(1, what.tileHeight()) - 1;
        if (!world.map().contains(tileX, tileY) || !world.map().contains(right, bottom)) {
            return bne(BattleNetMessages.Key.BUILD_OFF_MAP);
        }
        for (int y = tileY; y <= bottom; y++) {
            for (int x = tileX; x <= right; x++) {
                if (!world.fog().isExplored(localPlayer, x, y)) {
                    return bne(BattleNetMessages.Key.EXPLORE_FIRST);
                }
            }
        }
        if (what.givesResource() == UnitType.Resource.OIL) {
            return bne(BattleNetMessages.Key.PLATFORM_OVER_OIL);
        }
        if (what.shoreBuilding()) {
            return bne(BattleNetMessages.Key.BUILD_ON_COAST);
        }
        return bne(BattleNetMessages.Key.CANNOT_BUILD_THERE);
    }

    /** The roster, resolved once and kept. */
    private java.util.Map<String, UnitType> types() {
        if (roster == null) {
            roster = data.unitTypes().types();
        }
        return roster;
    }

    private java.util.Map<String, UnitType> roster;

    /**
     * Selects every one of the player's units inside a dragged rectangle.
     *
     * <p>Only the local player's, and only mobile ones when there are any: a
     * band that catches a town hall along with six peasants should give the
     * peasants, because that is what the player was reaching for.
     */
    private void selectWithin(java.awt.Rectangle screen) {
        selectWithin(screen, false);
    }

    /**
     * @param add whether the band joins what was already selected.
     *            {@code UIHandleButtonUp}
     *            branches on shift into {@code AddSelectedUnitsInRectangle}
     *            rather than {@code SelectUnitsInRectangle}. Without it a
     *            second drag threw the first one away, which is how a mixed
     *            army of footmen and archers could not be gathered from two
     *            corners of the screen
     */
    private void selectWithin(java.awt.Rectangle screen, boolean add) {
        java.util.List<Unit> caught = new java.util.ArrayList<>();
        for (Unit unit : world.unitsSnapshot()) {
            if (!world.canControl(localPlayer, unit.player())
                    || !unit.isAlive() || !unit.isOnMap()) {
                continue;
            }
            // SelectableByRectangle: a band drag gathers troops. Buildings and
            // critters declare themselves out of it, and a drag across your
            // own base should not come back holding four farms.
            if (unit.type() != null && !unit.type().selectableByRectangle()) {
                continue;
            }
            int x = viewportX() + (int) ((unit.tileX() * TILE - cameraX) * gameScale);
            int y = viewportY() + (int) ((unit.tileY() * TILE - cameraY) * gameScale);
            if (screen.intersects(new java.awt.Rectangle(x, y,
                    (int) (Math.max(1, unit.type().tileWidth()) * TILE * gameScale),
                    (int) (Math.max(1, unit.type().tileHeight()) * TILE * gameScale)))) {
                caught.add(unit);
            }
        }
        boolean anyMobile = caught.stream().anyMatch(u -> u.type().speed() > 0);
        if (!add) {
            for (Unit unit : world.unitsSnapshot()) {
                unit.setSelected(false);
            }
        }
        Unit first = null;
        int count = 0;
        for (Unit unit : caught) {
            if (anyMobile && unit.type().speed() == 0) {
                continue;
            }
            unit.setSelected(true);
        }
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.selected()) {
                count++;
                if (first == null) {
                    first = unit;
                }
            }
        }
        selectionChanged(first);
        if (selected != null && !selected.selected()) {
            selectionChanged(firstSelected());
        }
        int kept = countSelected();
        status = kept == 0 ? "" : kept + " selected.";
        if (first != null) {
            playUnit(first, "selected", this::chooseSample);
        }
    }

    /**
     * The unit under a screen point, or null.
     *
     * <p>By its drawn box rather than by the tile the point falls in. Those are
     * different for anything that is moving, and a click that misses a walking
     * unit is the difference between an order and a wasted second.
     */
    private Unit unitUnder(int screenX, int screenY) {
        return world.unitAtPixel(worldX(screenX), worldY(screenY), this::isUnitVisible);
    }

    private void selectUnit(Unit clicked, boolean add) {
        // Before the selection changes, because upstream's HandleSuicideClick
        // is called out of ChangeSelectedUnits ahead of UnSelectAll and asks
        // IsOnlySelected about the selection as it stands.
        if (!add) {
            handleSuicideClick(clicked);
        }
        if (add && clicked != null && world.canControl(localPlayer, clicked.player())) {
            // Shift adds to the selection rather than replacing it, which is
            // how a mixed group is put together. A tenth unit is refused:
            // selecting it used to steal the sidebar even though the packet
            // never took it.
            if (!clicked.selected() && countSelected() >= MAX_SELECTED_UNITS) {
                return;
            }
            clicked.setSelected(!clicked.selected());
            selectionChanged(clicked.selected() ? clicked : firstSelected());
            if (selected != null && !selected.selected()) {
                selectionChanged(firstSelected());
            }
            status = countSelected() + " selected.";
            return;
        }
        for (Unit unit : world.unitsSnapshot()) {
            unit.setSelected(unit == clicked);
        }
        // "CurrentButtonLevel = 0; SelectionChanged();" -- the map-click arm of
        // UIHandleButtonUp, and again in
        // DoSelectionButtons for a click on the
        // portrait strip.
        //
        // This also clears the status line, and that is deliberate rather than
        // incidental: the sidebar already shows the unit's name and health,
        // with a bar, the moment anything is selected. Printing them again
        // along the bottom of the screen said nothing the player was not
        // already looking at. The line is for things that happen -- an order
        // given, a game saved, a base under attack -- not for things that are
        // simply true.
        selectionChanged(clicked);
        answer(clicked);
    }

    /**
     * The noise a click on one unit gets back.
     *
     * <p>{@code UIHandleButtonUp}'s selecting-sound block, which branches on
     * what the thing is doing before it decides it is a voice at all --
     *
     *
     * <pre>{@code
     * if (Selected[0]->CurrentAction() == UnitAction::Built
     *     && Selected[0]->Player->Index == ThisPlayer->Index) {
     *     PlayUnitSound(*Selected[0], EUnitVoice::Building);
     * } else if (Selected[0]->Burning) {
     *     PlayGameSound(SoundForName("burning").get(), MaxSampleVolume);
     * } else if (...) {
     *     PlayUnitSound(*Selected[0], EUnitVoice::Selected);
     * }
     * }</pre>
     *
     * <p>This implementation had the third arm and nothing else, so an unfinished farm
     * answered with the finished farm's selection line and a keep with its
     * roof alight answered with the keep's. A player asked for the first of
     * those by name: "I am familiar with an under construction sound when you
     * click on a building that's in progress of being built, in the original
     * game you hear hammer and nails, saws etc. but in our game I'm not
     * hearing that."
     *
     * <p>Both files were in the bank the whole time and neither had a caller.
     * {@code misc/building_construction.wav} is bound to
     * {@code building-construction} for both races by
     * {@code scripts/sound.legacy-declaration:857-858}, which {@code GameAudio.chosenPath}
     * resolves through the race fallback exactly as
     * {@code ChooseUnitVoiceSound} resolves {@code EUnitVoice::Building} to
     * {@code GameSounds.BuildingConstruction[race]}
     * {@code misc/burning.wav} is asked for
     * by that literal name in the C++ and appears in no ChonkCraft script at all,
     * which is why a check that read only the retired scripting language would have missed it.
     *
     * <p>Measured before the change on human mission one, by putting a farm up
     * and clicking it two hundred cycles in at 133 of its 400 hit points: the
     * mixer rendered 1.86 seconds of audio matching {@code human/buildings/
     * farm.wav}, the same file the finished farm beside it answered with.
     */
    private void answer(Unit clicked) {
        if (clicked == null) {
            playUi("click");
            return;
        }
        if (clicked.order() == Unit.Order.UNDER_CONSTRUCTION
                && world.canControl(localPlayer, clicked.player())) {
            playUnit(clicked, "building-construction", this::chooseSample);
            return;
        }
        if (clicked.isBurning()) {
            // Not a voice, and not positioned. Upstream reaches past the unit
            // for a named game sound at full volume -- PlayGameSound, the same
            // call the click and the two placement chimes go through, which
            // this implementation routes through playUi -- with a FIXME beside it saying
            // it ought to have been GameSounds.Burning. Copied as written,
            // including for another player's building, which is what upstream
            // does: the branch is tested before the one that asks whose it is.
            playUi("burning");
            return;
        }
        if (world.canControl(localPlayer, clicked.player())) {
            // Only your own units answer you.
            playUnit(clicked, "selected", this::chooseSample);
        } else {
            playUi("click");
        }
    }

    /** Consecutive clicks on the same lone unit, for {@link #handleSuicideClick}. */
    private int suicideClicks;

    /**
     * How many clicks in a row this type takes before it blows up, or nought.
     *
     * <p>{@code ClicksToExplode}, which {@code unit-critter} sets to ten and
     * nothing else in the game sets at all. It is an easter egg: click the
     * same sheep ten times running and it goes off. It was in the parser's
     * unmodelled set, so nothing counted and nothing ever exploded.
     *
     * <p>Read off the raw properties rather than through an accessor because
     * the accessor would have to be added to the parser, which belongs to
     * another lane. The value in the shipped script is a plain number.
     */
    private static int clicksToExplode(UnitType type) {
        Object declared = type == null ? null : type.rawProperties().get("ClicksToExplode");
        if (declared instanceof Number number) {
            return number.intValue();
        }
        if (declared == null) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(declared));
        } catch (NumberFormatException notANumber) {
            return 0;
        }
    }

    /**
     * Counts consecutive clicks on a critter and sets it off at the tenth.
     *
     * <p>Implements {@code HandleSuicideClick}, which upstream calls from
     * {@code ChangeSelectedUnits} when the new selection is exactly one unit
     * of a type with {@code ClicksToExplode}. Its counter runs up while that
     * unit is the only one selected and goes back to one the moment anything
     * else is clicked, so ten clicks spread across two sheep do nothing.
     *
     * <p>Upstream then sends {@code CommandDismiss}, which is
     * {@code LetUnitDie(unit, true)}: the {@code suicide} argument makes it
     * throw the type's own {@code Missile} -- {@code missile-critter-explosion},
     * the only type in the game that declares a {@code FiredSound} -- and then
     * kill the unit outright. Neither half does damage.
     *
     * <p>Sent rather than done. It used to call {@code World.hit} and
     * {@code World.kill} from here, which is a unit dying on one machine and
     * not on the other. Upstream sends it too, and goes out of its way to let
     * it through: {@code IsAValidCommand_Dismiss}
     * The game waves through any Dismiss naming a
     * type with {@code ClicksToExplode}, because a critter belongs to the
     * neutral player and the player clicking it does not own it.
     */
    private void handleSuicideClick(Unit clicked) {
        if (clicked == null || !clicked.isAlive()) {
            return;
        }
        int needed = clicksToExplode(clicked.type());
        if (needed <= 0) {
            suicideClicks = 0;
            return;
        }
        // IsOnlySelected: the click before this one left this critter, and
        // only this critter, selected.
        boolean onlySelected = clicked.selected() && countSelected() == 1;
        suicideClicks = onlySelected ? suicideClicks + 1 : 1;
        if (suicideClicks >= needed) {
            suicideClicks = 0;
            commands.issue(GameCommand.dismiss(localPlayer, clicked.id()));
        }
    }

    /**
     * Right click: attack what is there if it is an enemy, otherwise walk to
     * the square. This is Warcraft II's single-button command model.
     */
    private void commandSelected(int tileX, int tileY) {
        commandSelected(tileX, tileY, world.unitAt(tileX, tileY), Modifiers.NONE);
    }

    private void commandSelected(int tileX, int tileY, Modifiers keys) {
        commandSelected(tileX, tileY, world.unitAt(tileX, tileY), keys);
    }

    /**
     * Right click, with the target already resolved.
     *
     * <p>The caller passes what it found under the pointer because it knows
     * where the pointer was: resolving it again from the tile would undo the
     * box test and put the miss back.
     */
    private void commandSelected(int tileX, int tileY, Unit under, Modifiers keys) {
        // Aim at the target's own square rather than the pixel that was
        // clicked, or an order given at the edge of a big unit's box lands on
        // the ground beside it.
        if (under != null) {
            tileX = under.tileX();
            tileY = under.tileY();
        }
        // Exactly one unit answers, whatever the size of the selection.
        // {@code DoRightButton} declares "int acknowledged = 0; // to play
        // sound" before its loop and threads it by reference through all
        // twenty-six branches, so the first unit that actually takes an order
        // speaks and the rest carry it out in silence. This played the
        // acknowledgement once per unit, so ten footmen told to move all
        // shouted at once -- correct with one unit selected, which is how most
        // of the testing happens.
        //
        // Which unit speaks is decided by the iteration order of the world's
        // own unit list, not by anything drawn or timed, so it is the same
        // unit on every machine given the same selection. The draw it makes
        // still comes off the simulation's synchronised generator from a
        // local, selection-driven path -- which is a hazard this change
        // reduces from "N draws" to "one draw" but does not remove, and which
        // belongs with the command-sink work rather than here.
        // Every branch of DoRightButton that has a target sets its Blink, so
        // the thing you aimed at flashes back. Set once for the click rather
        // than once per selected unit, which is the same thing: the value is
        // assigned, not accumulated.
        blink(under);
        boolean acknowledged = false;
        for (Unit unit : selectedUnits()) {
            // A building that makes units cannot be told to walk anywhere, so
            // a right click on the map is the one thing it can mean: where
            // what it makes should go. World.setRallyPoint has worked and been
            // tested since it was written and no click reached it, so a
            // trained unit always appeared beside its barracks whatever the
            // player had chosen.
            if (setRallyPoint(unit, tileX, tileY)) {
                continue;
            }
            String said = rightClick(unit, tileX, tileY, under, keys);
            if (said == null) {
                // This kind of unit does not take orders.
                continue;
            }
            status = said;
            if (!acknowledged) {
                playUnit(unit, "acknowledge", this::chooseSample);
                acknowledged = true;
            }
        }
    }

    /**
     * Points a producing building's output at a square.
     *
     * <p>Through the sink like everything else. It used to go straight into
     * the world, because there was no {@code GameCommand} kind for it -- and
     * a rally point is simulation state: it decides where the next footman
     * walks, so a machine that was never told sends its copy of that footman
     * somewhere else, and from there the two games are different games.
     *
     * @return whether this unit's click was a rally point rather than an order
     */
    private boolean setRallyPoint(Unit unit, int tileX, int tileY) {
        if (!producesUnits(unit)) {
            return false;
        }
        boolean accepted = commands.issueAccepted(
                GameCommand.rallyPoint(localPlayer, unit.id(), tileX, tileY));
        status = accepted ? "" : "Cannot set a rally point there.";
        if (accepted) {
            playUi("click");
        }
        return true;
    }

    /** Building types with a train button, worked out once from the scripts. */
    private java.util.Set<String> producerTypes;

    /**
     * Whether this building makes units, and so has somewhere to send them.
     *
     * <p>Asked of the button tables rather than hard-coded, because which
     * buildings train is a property of the shipped scripts and differs between
     * the two races and between the tiers of each.
     */
    private boolean producesUnits(Unit unit) {
        if (unit == null || unit.type() == null || !unit.type().building()) {
            return false;
        }
        if (producerTypes == null) {
            java.util.Set<String> found = new java.util.LinkedHashSet<>();
            for (var button : data.userInterface(tilesetName).buttons().all()) {
                if ("train-unit".equals(button.action())) {
                    found.addAll(button.forUnits());
                }
            }
            producerTypes = found;
        }
        return producerTypes.contains(unit.type().ident());
    }

    /**
     * What a right click means for one unit.
     *
     * <p>{@code DoRightButton} dispatches on the unit type's own
     * {@code RightMouseAction} rather than applying one rule to everything. A
     * peasant harvests, a footman attacks, a transport sails, and a sheep does
     * not take orders at all. The implementation asked the same question of all four,
     * because the field was parsed and never read.
     *
     * @return what to put in the status line, or null if the unit ignores the
     *         click entirely
     */
    private String rightClick(Unit unit, int tileX, int tileY, Unit under, boolean queued) {
        return rightClick(unit, tileX, tileY, under, new Modifiers(queued, false, false));
    }

    /**
     * Which of the three modifier keys were held when the button went down.
     *
     * <p>{@code KeyModifiers}, carried rather than
     * read off a global. The right button used to read none of them: shift
     * reached the queue and control and alt were dropped on the floor, so
     * three of upstream's branches had no way in.
     *
     * @param shift   append behind whatever the unit is already doing --
     *                upstream's {@code EFlushMode::Off}
     * @param control follow anything, including an enemy
     * @param alt     defend a friendly unit, which this implementation cannot yet do
     */
    record Modifiers(boolean shift, boolean control, boolean alt) {

        static final Modifiers NONE = new Modifiers(false, false, false);

        static Modifiers of(MouseEvent event) {
            // Command counts as control on a Mac keyboard, which is what the
            // spell-cast toggle in this file already assumes.
            return new Modifiers(event.isShiftDown(),
                    event.isControlDown() || event.isMetaDown(), event.isAltDown());
        }
    }

    private String rightClick(Unit unit, int tileX, int tileY, Unit under, Modifiers keys) {
        boolean queued = keys.shift();
        // The three modifier branches, in upstream's order and at the top of
        // the table where upstream puts them --
        // {@code DoRightButton_ForSelectedUnit}.
        //
        // Control and alt together shell a square, whether or not anything is
        // standing on it. Only for a unit that can: upstream tests
        // {@code BoolFlag[GROUNDATTACK_INDEX]} and falls through to the rest
        // of the table when it is not set, so ctrl-alt on a footman still
        // means whatever a plain right click means.
        if (keys.control() && keys.alt()
                && unit.type() != null && unit.type().groundAttack()) {
            return issueStatus(GameCommand.attackGround(
                    localPlayer, unit.id(), tileX, tileY).withQueued(queued));
        }
        // Control alone follows anything at all, which is the only way to keep
        // a scout on an enemy unit rather than attacking it.
        if (keys.control() && under != null && under != unit) {
            return follow(unit, under, tileX, tileY, queued);
        }
        // Alt alone is BNE's SendCommandDefend: keep up with a friendly unit
        // and fight whatever attacks it. A missing order used to fall through
        // to Move, so the click looked accepted while the unit walked off.
        if (keys.alt() && !keys.control() && under != null && under != unit) {
            return issueStatus(GameCommand.defend(
                    localPlayer, unit.id(), under.id()).withQueued(queued));
        }

        // Boarding is asked before the unit's own right-click rule, because
        // it is the one order that depends on what was clicked rather than on
        // what is doing the clicking: a footman, an archer and a peasant all
        // board the same boat the same way. DoRightButton checks for a
        // transporter first for exactly this reason.
        //
        // Nothing asked before. World.board existed, was documented, and had
        // no callers at all -- the one method that could put a unit on a
        // transport was unreachable from the game, so a right click on a boat
        // ordered a walk into the sea and the unit stopped at the water's
        // edge.
        if (under != null && under != unit
                && under.player() == unit.player()
                && under.type() != null && unit.type() != null
                && under.type().canCarry(unit.type())) {
            if (!under.hasRoom()) {
                return BattleNetMessages.sentence(under.type().name() + " is full");
            }
            return issueStatus(GameCommand.board(
                    localPlayer, unit.id(), under.id()).withQueued(queued));
        }

        // A wall has no Unit under the pointer: it is terrain with hit points.
        // This used to fall through to every type's move branch, so the sword
        // cursor disappeared over a wall and a melee army merely walked up to
        // it. Upstream's tile form of CommandAttack is precisely this case.
        if (unit.type() != null && unit.type().canAttack()
                && world.map().field(tileX, tileY).isWall()) {
            return issueStatus(GameCommand.attackGround(
                    localPlayer, unit.id(), tileX, tileY).withQueued(queued));
        }

        String action = unit.type() == null || unit.type().rightMouseAction() == null
                ? ""
                : unit.type().rightMouseAction();
        return switch (action) {
            case "harvest" -> asWorker(unit, tileX, tileY, under, queued);
            case "attack", "spell-cast" ->
                    asFighter(unit, tileX, tileY, under, queued, keys.control());
            case "move", "sail" -> asMover(unit, tileX, tileY, under, queued);
            // Sheep and seals. A right click on one is not an order.
            case "none" -> null;
            default -> asMover(unit, tileX, tileY, under, queued);
        };
    }

    /**
     * A worker gathers, repairs, delivers, and defends itself if it must.
     *
     * <p>{@code DoRightButton_Worker},, in its
     * order: repair, then harvest -- whose own first question is whether the
     * thing clicked will take the load already being carried -- then follow,
     * then move.
     */
    private String asWorker(Unit unit, int tileX, int tileY, Unit under, boolean queued) {
        // Repair first, as upstream has it. Putting gathering first looks
        // safer -- a gold mine belongs to the neutral player, so an enemy test
        // ahead of a resource test would send the worker to attack the mine --
        // but the repair branch cannot take a mine: it asks the target's own
        // RepairHP, and only things that can be repaired declare one.
        //
        // Allied as well as your own. A damaged ally's building could not be
        // repaired by right click at all, which on a campaign map with a
        // rescued ally is most of what a spare peasant is for. Upstream:
        // (dest->Player == unit.Player || unit.IsAllied(*dest)).
        if (under != null && under != unit
                && under.type() != null && under.type().repairHp() > 0
                && under.hitPoints() < under.type().hitPoints()
                && unit.type().repairRange() > 0
                && (under.player() == unit.player()
                        || world.isAllied(unit.player(), under.player()))) {
            return issueStatus(GameCommand.repair(
                    localPlayer, unit.id(), under.id()).withQueued(queued));
        }
        // Deliver what is being carried. This is the first test inside
        // {@code DoRightButton_Harvest_Unit} and the
        // port had nothing like it: a peasant holding a hundred gold, right
        // clicked onto its own town hall, walked up to the hall and stood
        // there, because a hall at full health declined the repair branch and
        // the worker fell through to move. Nothing said so and nothing looked
        // broken; the gold simply never arrived.
        if (under != null && under != unit && unit.carrying() != null && unit.carried() > 0
                && under.type() != null && under.type().storesResource(unit.carrying())
                && (under.player() == unit.player()
                        || (world.isAllied(unit.player(), under.player())
                                && world.isAllied(under.player(), unit.player())))) {
            return issueStatus(GameCommand.returnGoods(
                    localPlayer, unit.id()).withQueued(queued));
        }
        if (world.canHarvestAt(unit, tileX, tileY)) {
            return issueStatus(GameCommand.harvest(
                    localPlayer, unit.id(), tileX, tileY).withQueued(queued));
        }
        if (canFollow(unit, under)) {
            return follow(unit, under, tileX, tileY, queued);
        }
        // DoRightButton_Worker has no attack branch. Java used to send Attack
        // here, so a peasant ordered onto an enemy footman started a fight
        // the native table never asked for.
        return issueStatus(GameCommand.move(
                localPlayer, unit.id(), tileX, tileY).withQueued(queued));
    }

    /**
     * A fighter attacks what is there and walks to where nothing is.
     *
     * <p>{@code DoRightButton_Attack} and {@code DoRightButton_AttackUnit},
     * {@code :337}.
     */
    private String asFighter(Unit unit, int tileX, int tileY, Unit under, boolean queued,
            boolean control) {
        // Control on empty ground: advance on the square and fight what comes
        // into reach. Upstream sends it as an attack with no target --
        // "empty space", SendCommandAttack(unit, pos,
        // nullptr) -- with RightButtonAttacks off, which is the shipped
        // preference. The implementation had no tile-form attack order at all until
        // World.orderAttackMove landed, so ctrl on open ground was a plain
        // walk and an army sent across a map arrived having ignored
        // everything it passed.
        if (control && under == null && unit.type() != null && unit.type().canAttack()) {
            return issueStatus(GameCommand.attackMove(
                    localPlayer, unit.id(), tileX, tileY).withQueued(queued));
        }
        if (canFollow(unit, under)) {
            return follow(unit, under, tileX, tileY, queued);
        }
        if (under != null && under != unit
                && world.isEnemyPlayer(unit.player(), under.player())) {
            return issueStatus(GameCommand.attack(
                    localPlayer, unit.id(), under.id()).withQueued(queued));
        }
        return issueStatus(GameCommand.move(
                localPlayer, unit.id(), tileX, tileY).withQueued(queued));
    }

    /** Everything else goes where it is sent. {@code DoRightButton_Follow}. */
    private String asMover(Unit unit, int tileX, int tileY, Unit under, boolean queued) {
        if (canFollow(unit, under)) {
            return follow(unit, under, tileX, tileY, queued);
        }
        if (under != null && under != unit && unit.type().canAttack()
                && world.isEnemyPlayer(unit.player(), under.player())) {
            return issueStatus(GameCommand.attack(
                    localPlayer, unit.id(), under.id()).withQueued(queued));
        }
        return issueStatus(GameCommand.move(
                localPlayer, unit.id(), tileX, tileY).withQueued(queued));
    }

    /**
     * Whether a right click on this target means "keep up with it".
     *
     * <p>Upstream asks the same three-part question at every one of its follow
     * branches: {@code dest->Player == unit.Player || unit.IsAllied(*dest) ||
     * dest->Player->Index == PlayerNumNeutral}. The neutral third was missing
     * here, which is why a right click on a sheep or on a rescuable prisoner
     * walked to where it was standing rather than after it.
     */
    private boolean canFollow(Unit unit, Unit under) {
        return under != null && under != unit && under.type() != null
                && (under.player() == unit.player()
                        || under.player() == World.NEUTRAL_PLAYER
                        || world.isAllied(unit.player(), under.player()));
    }

    /**
     * Follows something, or walks to it when it cannot move.
     *
     * <p>Upstream's follow branches all end the same way:
     * {@code if (dest.Type->CanMove() == false) SendCommandMove(unit,
     * dest.tilePos) else SendCommandFollow(unit, dest)}. Ordering a footman to
     * follow a farm is an order to keep up with something that never goes
     * anywhere, and the distinction matters because the two produce different
     * orders in the queue: a move finishes and a follow does not.
     */
    private String follow(Unit unit, Unit under, int tileX, int tileY, boolean queued) {
        blink(under);
        if (under.type().speed() <= 0) {
            return issueStatus(GameCommand.move(
                    localPlayer, unit.id(), tileX, tileY).withQueued(queued));
        }
        return issueStatus(GameCommand.follow(
                localPlayer, unit.id(), under.id()).withQueued(queued));
    }

    /**
     * Returns a non-null marker only when the command path accepted the order.
     *
     * <p>Retail acknowledges ordinary orders with the unit voice and target
     * blink; it does not leave an English debug verb such as "moving" in the
     * notification strip. The empty marker preserves the accepted/ignored
     * distinction used by the caller without inventing player-facing copy.
     */
    private String issueStatus(GameCommand command) {
        return commands.issueAccepted(command) ? "" : null;
    }

    /**
     * Presses the command slot whose hotkey was typed.
     *
     * <p>Only slots currently drawn answer, so a key means whatever the panel
     * is showing rather than a fixed command. That is the original's
     * behaviour: {@code b} builds a barracks on the peasant's build page and
     * nothing at all on its root page.
     */
    boolean typed(char character) {
        if (commandPanel == null) {
            return false;
        }
        var button = commandPanel.buttonForKey(character);
        if (button == null) {
            return false;
        }
        press(button);
        repaint();
        return true;
    }

    /** Selects a unit directly, for rendering checks. */
    World worldForTest() {
        return world;
    }

    String rightClickForTest(Unit unit, int tileX, int tileY) {
        return rightClick(unit, tileX, tileY, world.unitAt(tileX, tileY), false);
    }

    String rightClickForTest(Unit unit, int tileX, int tileY, boolean queued) {
        return rightClick(unit, tileX, tileY, world.unitAt(tileX, tileY), queued);
    }

    /** Drives the real selected-group right-click path without screen coordinates. */
    void commandSelectedForTest(int tileX, int tileY, Unit under) {
        commandSelected(tileX, tileY, under, Modifiers.NONE);
    }

    GameCursors.Kind kindAtForTest(int screenX, int screenY) {
        return kindAt(screenX, screenY);
    }

    /**
     * Selects units by type, for a screenshot.
     *
     * <p>The harness can open the game and photograph it, and until now every
     * such photograph showed an empty info panel. Half of what the interface
     * does it does only when something is selected.
     *
     * @param ident the type to look for, and {@code all} for everything owned
     * @param count how many, at most
     */
    void selectForScreenshot(String ident, int count) {
        java.util.List<Unit> chosen = new java.util.ArrayList<>();
        for (Unit unit : world.unitsSnapshot()) {
            unit.setSelected(false);
            if (!world.canControl(localPlayer, unit.player())
                    || !unit.isAlive() || unit.type() == null) {
                continue;
            }
            if (chosen.size() < Math.max(1, count)
                    && ("all".equals(ident) || ident.equals(unit.type().ident()))) {
                chosen.add(unit);
            }
        }
        for (Unit unit : chosen) {
            unit.setSelected(true);
        }
        selectionChanged(chosen.isEmpty() ? null : chosen.get(0));
        if (selected != null) {
            centreOn(selected.tileX(), selected.tileY());
        }
    }

    void selectWithinForTest(java.awt.Rectangle screen) {
        selectWithin(screen, false);
    }

    void shiftSelectForTest(Unit unit) {
        selectUnit(unit, true);
    }

    void selectForTest(Unit unit) {
        for (Unit other : world.unitsSnapshot()) {
            other.setSelected(other == unit);
        }
        selectionChanged(unit);
    }

    /** Installs a selection in a deliberate order for command-delivery gates. */
    void selectForTest(java.util.List<Unit> units) {
        selectionOrder.clear();
        for (Unit other : world.unitsSnapshot()) {
            other.setSelected(false);
        }
        for (Unit unit : units) {
            unit.setSelected(true);
            if (selectionOrder.size() < MAX_SELECTED_UNITS) {
                selectionOrder.add(unit.id());
            }
        }
        selectionChanged(units.isEmpty() ? null : units.get(0));
    }

    java.util.List<Integer> selectedIdsForTest() {
        return java.util.List.copyOf(selectedIds());
    }

    Unit selectedForTest() {
        return selected;
    }

    java.util.List<PlayerIntentJournal.Entry> intentEntriesForTest() {
        return intents.snapshot();
    }

    java.util.List<PlayerIntentJournal.Outcome> intentOutcomesForTest() {
        return intents.outcomeSnapshot();
    }

    /** Samples the causal result of every command after the world advances. */
    void observePlayerIntents() {
        intents.observe(world.cycle(), world);
    }

    /**
     * Abandons a pending command or building placement.
     *
     * <p>What Escape does. It used to quit the game, which is a poor fate for
     * a key that sits next to the ones used for scrolling.
     */
    void cancelPending() {
        if (placing == null && pendingAction == null) {
            commandPanel.resetLevel();
            return;
        }
        placing = null;
        pendingAction = null;
        status = "";
        commandPanel.resetLevel();
        repaint();
    }

    /**
     * The cancel-only page.
     *
     * <p>{@code CButtonPanel::DoClicked_SelectTarget} sets this and the comment
     * beside it says all there is to say: "level 9 is cancel-only".
     */
    private static final int CANCEL_LEVEL = 9;

    /** Puts a line in the status bar. */
    void setStatus(String status) {
        this.status = BattleNetMessages.sentence(status);
    }

    /** What the status line is saying, for the tests that read it. */
    String status() {
        return status;
    }

    /** What is waiting on the cursor to be placed, for the same. */
    UnitType placingForTest() {
        return placing;
    }

    /** The in-game menu, once the launcher has one to give. */
    private GameMenu menu;

    void setMenu(GameMenu menu) {
        this.menu = menu;
    }

    GameMenu menu() {
        return menu;
    }

    void setNetworkChat(net.chonkbase.chonkcraft.engine.network.NetworkGame network) {
        chat = network == null ? null : new InGameChat(network, font, audio);
    }

    void acceptChat(net.chonkbase.chonkcraft.engine.network.NetworkGame.ChatEvent event) {
        if (chat != null) {
            chat.accept(event);
            repaint();
        }
    }

    /** What the screen can ask of the loop around it. */
    private GameMenu.Session session;

    void setSession(GameMenu.Session session) {
        this.session = session;
    }

    /** Saved camera positions, recalled with F2 to F4. */
    private final int[][] bookmarks = new int[3][];

    /**
     * How far through the workers the idle search has got.
     *
     * <p>Warcraft II's Alt-I goes to the next idle worker each time rather than
     * the same one, which is the difference between a key you press repeatedly
     * and one you press once and give up on.
     */
    private int idleSearchFrom;

    /**
     * The whole in-game key map.
     *
     * <p>The bindings are {@code HandleIngameCommandKey} in
     * {@code scripts/commands.legacy-declaration} and the {@code keystrokes} table in
     * {@code scripts/menus/help.legacy-declaration}. Keeping them in one method rather than
     * spread between the frame and the screen is what makes it possible to
     * check the implementation against that table at all.
     *
     * @return whether the key was used
     */
    boolean keyPressed(KeyEvent event) {
        int code = event.getKeyCode();
        boolean alt = event.isAltDown();
        boolean control = event.isControlDown() || event.isMetaDown();

        // Once Enter has opened the BNE message line, gameplay hotkeys become
        // ordinary text until the line is sent or cancelled.
        if (chat != null && chat.isTyping()) {
            chat.keyPressed(event);
            repaint();
            return true;
        }

        // The arrows are claimed before anything else looks at them: they are
        // what a focus manager reaches for first, and scrolling the map must
        // never depend on what else happens to be bound.
        //
        // W, A, S and D are not. They scroll too, but they are also command
        // hotkeys -- twenty-six of the shipped buttons are keyed to one of
        // them, including the wall, the stronghold, both blacksmith upgrades
        // and the sapper's demolish. Claiming them here made every one of
        // those buttons mouse-only, and did it while a comment a hundred
        // lines further down promised the opposite. So the panel is offered
        // the letter first and it scrolls only if the panel does not want it.
        if (!alt && !control && isArrowKey(code)) {
            keyDown(code, true);
            return true;
        }

        // The menu takes everything while it is up.
        if (menu != null && menu.isOpen()) {
            boolean taken = menu.key(code);
            // Closing with Escape puts the pointer back in the game's hands
            // without waiting for it to be moved first.
            refreshCursor();
            return taken;
        }

        if (chat != null && chat.keyPressed(event)) {
            repaint();
            return true;
        }

        switch (code) {
            case KeyEvent.VK_F10, KeyEvent.VK_BACK_SPACE -> {
                if (menu != null) {
                    menu.open();
                    refreshCursor();
                    repaint();
                }
                return true;
            }
            case KeyEvent.VK_F11 -> {
                status = BattleNetMessages.sentence(saveGame());
                repaint();
                return true;
            }
            case KeyEvent.VK_F12 -> {
                status = BattleNetMessages.sentence(
                        session == null ? "cannot load from here" : session.load());
                repaint();
                return true;
            }
            case KeyEvent.VK_TAB -> {
                // UiToggleTerrain. The minimap drops its
                // ground so the dots can be read on their own.
                if (panel != null) {
                    status = panel.toggleMinimapTerrain()
                            ? "" : "Minimap terrain hidden.";
                    repaint();
                }
                return true;
            }
            case KeyEvent.VK_PRINTSCREEN -> {
                status = BattleNetMessages.sentence(screenshot());
                repaint();
                return true;
            }
            case KeyEvent.VK_PERIOD -> {
                // The help table's "." next to Alt-I: both find the next idle
                // worker, and this is the one a hand already resting on the
                // mouse can reach. UiFindIdleWorker.
                findIdleWorker();
                return true;
            }
            case KeyEvent.VK_F1, KeyEvent.VK_F5, KeyEvent.VK_F6,
                    KeyEvent.VK_F7, KeyEvent.VK_F8 -> {
                if (menu != null) {
                    menu.open();
                    refreshCursor();
                    menu.key(code);
                    repaint();
                }
                return true;
            }
            case KeyEvent.VK_PAUSE -> {
                togglePause();
                return true;
            }
            case KeyEvent.VK_SPACE -> {
                if (!showLastAlert()) {
                    status = "Nothing has happened yet.";
                    repaint();
                }
                return true;
            }
            case KeyEvent.VK_ESCAPE -> {
                cancelPending();
                return true;
            }
            default -> { }
        }

        // The three camera bookmarks, saved with shift and recalled without.
        if (code >= KeyEvent.VK_F2 && code <= KeyEvent.VK_F4) {
            bookmark(code - KeyEvent.VK_F2, event.isShiftDown());
            return true;
        }

        // A Mac-friendly playtest capture: Command is folded into control at
        // the top of this method, so Command-Shift-E and Ctrl-Shift-E are the
        // same one-action packet. Function keys remain available for the BNE
        // save/load bindings without requiring an Fn-key setting change.
        if (control && event.isShiftDown() && code == KeyEvent.VK_E) {
            status = BattleNetMessages.sentence(evidencePacket());
            repaint();
            return true;
        }

        // Alt and control were one modifier here, and for M that is a
        // collision rather than generosity: commands.legacy-declaration binds Alt-M to the
        // game menu and the help table reserves Ctrl-M for muting the music,
        // so the implementation had no way to mute music at all and Ctrl-M opened a
        // menu instead. Where upstream really does accept either -- H, Q, R
        // and X are all written (ctrl or alt) in commands.legacy-declaration:34-42 -- both
        // are still taken.
        if (alt) {
            switch (code) {
                case KeyEvent.VK_M -> {
                    if (menu != null) {
                        menu.open();
                        refreshCursor();
                        repaint();
                    }
                    return true;
                }
                case KeyEvent.VK_S -> {
                    status = BattleNetMessages.sentence(saveGame());
                    repaint();
                    return true;
                }
                case KeyEvent.VK_L -> {
                    status = BattleNetMessages.sentence(
                            session == null ? "cannot load from here" : session.load());
                    repaint();
                    return true;
                }
                default -> { }
            }
        }
        if (control) {
            switch (code) {
                case KeyEvent.VK_M -> {
                    status = BattleNetMessages.sentence(toggleMusic());
                    repaint();
                    return true;
                }
                case KeyEvent.VK_S -> {
                    status = BattleNetMessages.sentence(toggleSound());
                    repaint();
                    return true;
                }
                case KeyEvent.VK_T -> {
                    status = BattleNetMessages.sentence(toggleTracking());
                    repaint();
                    return true;
                }
                default -> { }
            }
        }
        if (alt || control) {
            switch (code) {
                case KeyEvent.VK_P -> {
                    togglePause();
                    return true;
                }
                case KeyEvent.VK_I -> {
                    findIdleWorker();
                    return true;
                }
                case KeyEvent.VK_C -> {
                    centreOnSelection();
                    return true;
                }
                // (ctrl or alt) in commands.legacy-declaration for all three, each behind its
                // own confirmation upstream. The confirmation here is the
                // menu's End Scenario page, which is the one this implementation has.
                case KeyEvent.VK_H -> {
                    if (menu != null) {
                        menu.open();
                        refreshCursor();
                        menu.key(KeyEvent.VK_F1);
                        repaint();
                    }
                    return true;
                }
                case KeyEvent.VK_Q, KeyEvent.VK_X -> {
                    if (menu != null) {
                        menu.openEndScenario();
                        refreshCursor();
                        repaint();
                    }
                    return true;
                }
                default -> { }
            }
        }

        // Speed, on the two keys upstream uses and on the ones a keyboard
        // without a numeric pad actually has.
        if (code == KeyEvent.VK_EQUALS || code == KeyEvent.VK_PLUS || code == KeyEvent.VK_ADD) {
            changeSpeed(1);
            return true;
        }
        if (code == KeyEvent.VK_MINUS || code == KeyEvent.VK_SUBTRACT) {
            changeSpeed(-1);
            return true;
        }

        // Digits are control groups. Which of the five things a digit means is
        // decided by the modifiers, per CommandKey_Group. They come before the
        // panel's hotkeys because a digit is never a command icon.
        int digit = code - KeyEvent.VK_0;
        if (digit >= 0 && digit <= 9 && groupKey(digit,
                groupActionFor(event.isShiftDown(), control, alt))) {
            return true;
        }

        // Caret clears the selection, which is what the help screen's "^"
        // entry means by "select nothing".
        if (event.getKeyChar() == '^') {
            selectNothing();
            return true;
        }

        // Then a command hotkey: the panel's keys take precedence over
        // scrolling, so pressing "s" on a selected unit stops it rather than
        // scrolling south.
        if (typed(event.getKeyChar())) {
            return true;
        }
        keyDown(code, true);
        return false;
    }

    /**
     * How loud the effects and the music were before they were muted.
     *
     * <p>{@code UiToggleSound} and {@code UiToggleMusic}
     * The game are switches rather
     * than sliders, and the options page has an On/Off pair beside each volume
     * for the same reason: a player silencing the game for a phone call wants
     * it back where it was afterwards, not at whatever the slider is dragged
     * to next. Minus one means not muted.
     */
    private float mutedEffects = -1f;
    private float mutedMusic = -1f;

    private String toggleSound() {
        if (session == null) {
            return "";
        }
        if (mutedEffects >= 0) {
            session.setEffectVolume(mutedEffects);
            mutedEffects = -1f;
            return "sound on";
        }
        mutedEffects = session.effectVolume();
        session.setEffectVolume(0f);
        return "sound off";
    }

    private String toggleMusic() {
        if (session == null) {
            return "";
        }
        if (mutedMusic >= 0) {
            session.setMusicVolume(mutedMusic);
            mutedMusic = -1f;
            return "music on";
        }
        mutedMusic = session.musicVolume();
        session.setMusicVolume(0f);
        return "music off";
    }

    /** Whether the effects are silenced, for the test that drives the key. */
    boolean soundMuted() {
        return mutedEffects >= 0;
    }

    boolean musicMuted() {
        return mutedMusic >= 0;
    }

    /**
     * The unit the camera is following, or null.
     *
     * <p>{@code UiTrackUnit}. Pressing it
     * again lets go, and so does pressing it with nothing selected, which is
     * what upstream's {@code if (UnitUnderCursor) ... else ...} amounts to.
     */
    private volatile Unit tracked;

    private String toggleTracking() {
        Unit first = firstSelected();
        if (tracked != null || first == null) {
            tracked = null;
            return "no longer following";
        }
        tracked = first;
        centreOn(first.tileX(), first.tileY());
        return "following " + first.type().name();
    }

    Unit trackedForTest() {
        return tracked;
    }

    /**
     * Writes what is on screen to a file.
     *
     * <p>{@code UiScreenshot}, bound to Print. Beside the saves rather than in
     * the working directory, because the working directory of a packaged game
     * is wherever the launcher happened to be.
     */
    private String screenshot() {
        try {
            java.nio.file.Path directory = saveDirectory().getParent().resolve("screenshots");
            java.nio.file.Files.createDirectories(directory);
            java.nio.file.Path file = directory.resolve(
                    "chonkcraft-" + System.currentTimeMillis() + ".png");
            javax.imageio.ImageIO.write(captureFrame(), "png", file.toFile());
            return "screenshot " + file.getFileName();
        } catch (java.io.IOException | RuntimeException failed) {
            return "could not take a screenshot";
        }
    }

    /** The exact component image used by screenshots and playtest packets. */
    private BufferedImage captureFrame() {
        BufferedImage frame = new BufferedImage(
                Math.max(1, getWidth()), Math.max(1, getHeight()),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g2 = frame.createGraphics();
        paint(g2);
        g2.dispose();
        return frame;
    }

    /**
     * Captures a screenshot, resumable save and focused forensic JSON together.
     *
     * <p>The status line names the packet on success, so the player never has
     * to guess whether Command/Ctrl-Shift-E worked. Keeping the three artifacts
     * in one timestamped directory also prevents a screenshot from being
     * separated from the simulation state that produced it.
     */
    String evidencePacket() {
        if (saveMapPath == null) {
            return "nothing to capture";
        }
        int focusX = selected == null
                ? clamp((cameraX + visibleWorldWidth() / 2) / TILE, world.map().width() - 1)
                : selected.tileX();
        int focusY = selected == null
                ? clamp((cameraY + visibleWorldHeight() / 2) / TILE, world.map().height() - 1)
                : selected.tileY();
        try {
            var result = PlaytestEvidence.write(new PlaytestEvidence.Request(
                    world, captureFrame(), selected, localPlayer, cameraX, cameraY,
                    focusX, focusY, saveMapPath, saveCampaign, saveMission,
                    triggers == null ? null : triggers.armedTriggers(),
                    evidenceDirectory(), java.time.Instant.now(), intents.snapshot(),
                    intents.outcomeSnapshot()));
            return "evidence saved " + result.directory().getFileName()
                    + " (" + result.units() + " units, " + result.missiles() + " missiles)";
        } catch (java.io.IOException | RuntimeException failed) {
            return "could not capture evidence: " + failed.getMessage();
        }
    }

    /** Where complete playtest packets live. */
    static java.nio.file.Path evidenceDirectory() {
        return saveDirectory().getParent().resolve("evidence");
    }

    private void togglePause() {
        if (session == null) {
            return;
        }
        // Upstream refuses in a network game, for the obvious reason that one
        // player cannot stop the others.
        if (session.isNetworked()) {
            status = "Cannot pause a network game.";
            repaint();
            return;
        }
        boolean paused = !session.isPaused();
        session.setPaused(paused);
        status = paused ? "Game paused." : "Game resumed.";
        repaint();
    }

    private void changeSpeed(int by) {
        if (session == null) {
            return;
        }
        int wanted = Math.max(1, session.speed() + by);
        session.setSpeed(wanted);
        status = by > 0 ? "Faster." : "Slower.";
        repaint();
    }

    /** Saves or recalls one of the three camera bookmarks. */
    private void bookmark(int slot, boolean save) {
        if (save) {
            bookmarks[slot] = new int[] {cameraX, cameraY};
            status = "Position " + (slot + 1) + " saved.";
        } else if (bookmarks[slot] != null) {
            cameraX = clamp(bookmarks[slot][0],
                    Math.max(0, terrain.getWidth() - visibleWorldWidth()));
            cameraY = clamp(bookmarks[slot][1],
                    Math.max(0, terrain.getHeight() - visibleWorldHeight()));
            status = "";
        }
        repaint();
    }

    /** Clears the selection without selecting anything else. */
    private void selectNothing() {
        for (Unit unit : world.unitsSnapshot()) {
            unit.setSelected(false);
        }
        selectionChanged(null);
        repaint();
    }

    /** Puts the camera on whatever is selected. */
    private void centreOnSelection() {
        Unit first = firstSelected();
        if (first != null) {
            centreOn(first.tileX(), first.tileY());
            repaint();
        }
    }

    /**
     * Selects and goes to the next worker with nothing to do.
     *
     * <p>Idle means standing still with no order at all, which is the state a
     * worker ends up in when its patch of gold runs out or its building
     * finishes. Finding them by hand on a busy map is the tedium this key
     * exists to remove.
     *
     * <p>The list is the same one the top bar counts, deliberately: a count
     * that says three and a key that finds four is worse than no count at all.
     */
    private void findIdleWorker() {
        java.util.List<Unit> workers = SidePanel.idleWorkers(world, localPlayer);
        if (workers.isEmpty()) {
            status = "No idle workers.";
            repaint();
            return;
        }
        idleSearchFrom = idleSearchFrom % workers.size();
        Unit next = workers.get(idleSearchFrom);
        idleSearchFrom = (idleSearchFrom + 1) % workers.size();
        selectForTest(next);
        next.setSelected(true);
        centreOn(next.tileX(), next.tileY());
        status = workers.size() == 1 ? "1 idle worker." : workers.size() + " idle workers.";
        repaint();
    }

    /**
     * Draws whoever is pointing at the map.
     *
     * <p>Over the fog rather than under it: a ping is a player speaking, and
     * pointing at a place you have not explored is exactly when you most want
     * to.
     */
    private void drawPings(Graphics2D g2) {
        for (var ping : world.pings()) {
            double age = (world.cycle() - ping.cycle()) / (double) World.PING_CYCLES;
            int x = ping.tileX() * TILE + TILE / 2 - cameraX;
            int y = ping.tileY() * TILE + TILE / 2 - cameraY;
            PingArt.draw(g2, x, y, TILE * 2.2, age, PlayerColours.of(ping.player()));
        }
    }

    /**
     * Points at a place for everyone to see.
     *
     * <p>Issued as a command so it travels to the other players by the same
     * road as an order does.
     */
    private void ping(int tileX, int tileY) {
        commands.issue(GameCommand.ping(localPlayer, tileX, tileY));
        playUi("click");
    }

    /** The four arrows, which always scroll and are never a command. */
    private static boolean isArrowKey(int code) {
        return code == KeyEvent.VK_LEFT || code == KeyEvent.VK_RIGHT
                || code == KeyEvent.VK_UP || code == KeyEvent.VK_DOWN;
    }

    /**
     * Whether a key scrolls the view.
     *
     * <p>The letters as well as the arrows, but see {@code keyPressed}: the
     * letters only reach here once the command panel has declined them.
     */
    private static boolean isScrollKey(int code) {
        return isArrowKey(code)
                || code == KeyEvent.VK_A || code == KeyEvent.VK_D
                || code == KeyEvent.VK_W || code == KeyEvent.VK_S;
    }

    /** How the player wants the pointer to scroll, if at all. */
    enum EdgeScroll {
        /** Only when the game has the whole screen, which is the default. */
        FULLSCREEN_ONLY,
        /** Wherever the pointer reaches an edge. */
        ALWAYS,
        /** Never; the keys still scroll. */
        NEVER
    }

    private volatile EdgeScroll edgeScroll = EdgeScroll.FULLSCREEN_ONLY;

    EdgeScroll edgeScroll() {
        return edgeScroll;
    }

    void setEdgeScroll(EdgeScroll setting) {
        this.edgeScroll = setting == null ? EdgeScroll.FULLSCREEN_ONLY : setting;
    }

    /** Whether the window is currently filling the screen. */
    private volatile boolean fullscreen;

    void setFullscreen(boolean fullscreen) {
        this.fullscreen = fullscreen;
    }

    private boolean edgeScrollAllowed() {
        return switch (edgeScroll) {
            case ALWAYS -> true;
            case NEVER -> false;
            case FULLSCREEN_ONLY -> fullscreen;
        };
    }

    void keyDown(int keyCode, boolean down) {
        switch (keyCode) {
            case KeyEvent.VK_LEFT, KeyEvent.VK_A -> held[0].set(down);
            case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> held[1].set(down);
            case KeyEvent.VK_UP, KeyEvent.VK_W -> held[2].set(down);
            case KeyEvent.VK_DOWN, KeyEvent.VK_S -> held[3].set(down);
            default -> { }
        }
    }

    /**
     * Plays whatever the last cycle announced.
     *
     * <p>Only for things the local player has a stake in: an enemy losing a
     * unit across the map is not news, and a game that announced every death
     * on a busy map would be unlistenable.
     */
    void playAnnouncements() {
        for (var notice : world.drainAttackNotices()) {
            Unit unit = notice.unit();
            if (!world.canControl(localPlayer, unit.player())) {
                continue;
            }
            status = unit.type().name() + " is under attack.";
            alertX = unit.tileX();
            alertY = unit.tileY();
        }
        for (var event : world.drainSoundEvents()) {
            Unit unit = event.unit();
            // A sound an animation asked for is a thing that happened in the
            // world, not a report to its owner: a sword landing is heard by
            // whoever is looking at it, so these are filtered by what is on
            // screen rather than by whose unit it is.
            if (event.named()) {
                if (isOnScreen(unit) && isUnitVisible(unit)) {
                    playNamedAt(event.event(), this::chooseSample, panOf(unit));
                }
                continue;
            }
            // A death is something that happened on the field, not a report to
            // its owner: an orc falling in front of you should be audible.
            // Filter enemy deaths by what is on screen and visible, as the
            // animation sounds are. A local unit is also audible on screen
            // after its own sight has been removed: otherwise the last town
            // hall takes its BNE building-destroyed voice into the fog when
            // World.kill darkens the map before this queue is drained.
            if ("dead".equals(event.event())) {
                if (isOnScreen(unit)
                        && (world.canControl(localPlayer, unit.player()) || isUnitVisible(unit))) {
                    playUnit(unit, "dead", this::chooseSample);
                }
                continue;
            }
            if (!world.canControl(localPlayer, unit.player())) {
                continue;
            }
            switch (event.event()) {
                // The help cry used to be pinned to clip nought so that a
                // sound raised inside a branch only the owning player takes
                // could not move the simulation's generator on. Nothing here
                // touches that generator any more, so it chooses like the
                // rest.
                case "help" -> playUnit(unit, "help", this::chooseSample);
                // Rescue and research-complete both fell through the default
                // and were dropped. World.announce(unit, "rescue") has raised
                // its event since prisoners were made rescuable and nothing
                // listened; DefineGameSounds binds rescue-human, rescue-orc,
                // research-complete-human and research-complete-orc, and
                // GameAudio falls back to the race's game sound when a unit
                // declares none of its own, so both resolve.
                // building-construction is the third arm of upstream's
                // completion sound: a site that
                // finishes with no builder left to speak for it makes the
                // generic construction noise instead. GameAudio's race
                // fallback turns it into building-construction-human or -orc,
                // both of which the sound table binds.
                case "ready", "work-complete", "rescue", "research-complete",
                        "building-construction" ->
                        playUnit(unit, event.event(), this::chooseSample);
                default -> { }
            }
        }
        hammerAwayAtBuildingSites();
    }

    /**
     * The hammering a player hears from a building site without clicking on
     * anything.
     *
     * <p>{@code COrder_Built::Execute},, which is one line and every clause of it matters:
     *
     * <pre>{@code
     * // Check if we should make some random noise
     * // IMPORTANT: this is local randomization, do not use the SyncRand function!
     * if (unit.Frame == 0 && unit.Player == ThisPlayer && GameCycle % 150 == 0
     *     && (MyRand() % 3) == 0) {
     *     PlayUnitSound(unit, EUnitVoice::Building, true);
     * }
     * }</pre>
     *
     * <p>Every five seconds, one site in three, and only the ones whose
     * construction sprite is on its first frame, so a base with four things
     * going up sounds busy without four hammers landing together. The implementation had
     * none of it: a site made no noise at all unless the player clicked it,
     * which is the other half of the same report.
     *
     * <p>The draw is deliberately <em>not</em> {@code World.syncRand}, and the
     * upstream comment in capitals is why. Everything else in this implementation that
     * draws a number draws from the simulation's own stream, because a draw
     * taken on one machine and not on another is a desync; this one is taken
     * on a condition that is local by definition -- {@code unit.Player ==
     * ThisPlayer} asks who is sitting here -- so drawing from the shared
     * stream is the thing that would break lockstep. It is the same rule that
     * put sound selection on {@code FrameCounter} rather than on
     * {@code SyncRand}. The generator is this screen's own and is seeded from
     * nothing the simulation can see.
     *
     * <p>This lives in the interface rather than in the order because the
     * simulation does not play sounds; it says what happened and the interface
     * decides what that sounds like. A headless peer runs the same cycles with
     * no audio device and draws no numbers here at all, which is exactly what
     * the rule is for.
     *
     * <p>The frame test is copied and currently excludes nothing, which is
     * worth writing down rather than leaving to be rediscovered. Upstream's
     * construction sprite animates, so a site is on frame nought for part of
     * its life and the clause thins the hammering out; this implementation's building
     * sites hold frame nought throughout. Measured on human mission one: a
     * farm took 600 cycles to go up, {@code frame()} was 0 on all 600 of them,
     * and four of those cycles were multiples of 150 -- so upstream would
     * consider a farm four times and this considers it four times too. The
     * clause stays because it is upstream's, and the day the sprite animates
     * it will start doing its job without anybody having to remember it.
     */
    private void hammerAwayAtBuildingSites() {
        if (world.cycle() % HAMMER_INTERVAL != 0) {
            return;
        }
        for (Unit unit : world.unitsSnapshot()) {
            if (!world.canControl(localPlayer, unit.player())
                    || unit.order() != Unit.Order.UNDER_CONSTRUCTION
                    || !unit.isAlive() || unit.frame() != 0) {
                continue;
            }
            if (localNoise.nextInt(HAMMER_ODDS) == 0) {
                playUnit(unit, "building-construction", this::chooseSample);
            }
        }
    }

    /** {@code GameCycle % 150}: once every five seconds at thirty cycles. */
    private static final int HAMMER_INTERVAL = 150;

    /** {@code (MyRand() % 3) == 0}. */
    private static final int HAMMER_ODDS = 3;

    /**
     * {@code MyRand}, and it must not be {@code World.syncRand}.
     *
     * <p>Upstream's own comment: "IMPORTANT: this is local randomization, do
     * not use the SyncRand function". See
     * {@link #hammerAwayAtBuildingSites()}.
     */
    private final java.util.Random localNoise = new java.util.Random(0x5eed1e55L);

    /**
     * How far left or right of the view a unit is, for stereo placement.
     *
     * <p>Minus one at the left edge of the playing field, plus one at the
     * right.
     */
    private float panOf(Unit unit) {
        int width = Math.max(1, visibleWorldWidth());
        int x = unit.pixelX() - cameraX;
        return Math.max(-1f, Math.min(1f, (x - width / 2f) / (width / 2f)));
    }

    /** Where the last under-attack warning came from, or -1. */
    private volatile int alertX = -1;
    private volatile int alertY = -1;

    /** Jumps the view to the last trouble spot, if there was one. */
    boolean showLastAlert() {
        if (alertX < 0) {
            return false;
        }
        centreOn(alertX, alertY);
        repaint();
        return true;
    }

    /**
     * How close to an edge the pointer has to be to push the view.
     *
     * <p>A margin rather than the last pixel: a pointer resting against the
     * physical edge of the screen is easy to reach and easy to leave, and a
     * one-pixel band is neither.
     */
    private static final int EDGE_MARGIN = 12;

    /** Advances the camera. Called from the simulation loop. */
    void scrollStep() {
        // Ctrl-T pins the view to a unit, so the camera goes wherever it does
        // and the keys and the pointer are left for something else. A unit
        // that has died stops being followed rather than holding the camera
        // over the spot where it fell.
        Unit following = tracked;
        if (following != null) {
            if (!following.isAlive() || !following.isOnMap()) {
                tracked = null;
            } else {
                centreOn(following.tileX(), following.tileY());
                return;
            }
        }
        int dx = (held[1].get() ? SCROLL_PIXELS_PER_TICK : 0) - (held[0].get() ? SCROLL_PIXELS_PER_TICK : 0);
        int dy = (held[3].get() ? SCROLL_PIXELS_PER_TICK : 0) - (held[2].get() ? SCROLL_PIXELS_PER_TICK : 0);

        // The pointer pushes the view when it reaches an edge, as it does in
        // the original. Suppressed while dragging a selection box, or the
        // band would run away from the pointer.
        //
        // And only in full screen. In a window the edge of the game is not the
        // edge of anything: reaching for another application, or for the title
        // bar, drags the map along with the pointer. The original had no
        // window to be beside.
        java.awt.Point at = pointer;
        if (at != null && band == null && isShowing() && edgeScrollAllowed()) {
            if (at.x >= viewportX() && at.x < viewportX() + EDGE_MARGIN) {
                dx = -SCROLL_PIXELS_PER_TICK;
            } else if (at.x >= getWidth() - EDGE_MARGIN && at.x < getWidth()) {
                dx = SCROLL_PIXELS_PER_TICK;
            }
            if (at.y >= viewportY() && at.y < viewportY() + EDGE_MARGIN) {
                dy = -SCROLL_PIXELS_PER_TICK;
            } else if (at.y >= getHeight() - EDGE_MARGIN && at.y < getHeight()) {
                dy = SCROLL_PIXELS_PER_TICK;
            }
        }

        if (dx == 0 && dy == 0) {
            return;
        }
        cameraX = clamp(cameraX + dx, Math.max(0, terrain.getWidth() - visibleWorldWidth()));
        cameraY = clamp(cameraY + dy, Math.max(0, terrain.getHeight() - visibleWorldHeight()));
    }

    /**
     * Where the camera is, in world pixels.
     *
     * <p>For {@link RenderingTruth}, which has to work out for itself where on
     * the frame a thing the world says exists ought to have landed. Asking the
     * screen to say where it drew something would make that check circular.
     */
    int cameraX() {
        return cameraX;
    }

    /** Which tileset the sprites are cut for, so a harness can load one. */
    String tilesetName() {
        return tilesetName;
    }

    int cameraY() {
        return cameraY;
    }

    /** Centres the view on a tile. */
    void centreOn(int tileX, int tileY) {
        int width = visibleWorldWidth();
        int height = visibleWorldHeight();
        cameraX = clamp(tileX * TILE - width / 2, Math.max(0, terrain.getWidth() - width));
        cameraY = clamp(tileY * TILE - height / 2, Math.max(0, terrain.getHeight() - height));
    }

    private static int clamp(int value, int max) {
        return Math.max(0, Math.min(value, max));
    }

    /**
     * One thing the screen is to paint the next frame without.
     *
     * <p>This exists for {@link RenderingTruth}, and it is the only way to ask
     * the question that harness exists to ask. "Does this corpse reach a
     * pixel?" cannot be answered by looking at the frame, because a frame with
     * the corpse drawn and a frame with bare ground under it are both just
     * pixels; it is answered by painting the frame twice, once with the thing
     * held back, and requiring the two to differ where the thing stands. The
     * alternative -- taking the unit out of the world and painting again -- was
     * tried first and is worse: it destroys the state the sweep is walking, so
     * a run can ask about exactly one thing and then has to start over.
     *
     * <p>Nothing in the game ever sets this. It costs four reference
     * comparisons a frame.
     *
     * @param unit        a unit whose sprite is not to be drawn, or null
     * @param decorations a unit whose health bar and spell badges are not to
     *                    be drawn, its sprite still being drawn, or null
     * @param missile     a projectile that is not to be drawn, or null
     * @param memory      a remembered building that is not to be drawn, or
     *                    null. One rather than all of them, because holding
     *                    back the whole set would let one memory's pixels
     *                    answer for another's
     */
    record Withheld(Unit unit, Unit decorations,
            net.chonkbase.chonkcraft.engine.missile.Missile missile,
            net.chonkbase.chonkcraft.engine.map.SeenBuildings.Memory memory) {

        static final Withheld NOTHING = new Withheld(null, null, null, null);

        static Withheld ofUnit(Unit unit) {
            return new Withheld(unit, null, null, null);
        }

        static Withheld ofDecorations(Unit unit) {
            return new Withheld(null, unit, null, null);
        }

        static Withheld ofMissile(net.chonkbase.chonkcraft.engine.missile.Missile missile) {
            return new Withheld(null, null, missile, null);
        }

        static Withheld ofMemory(
                net.chonkbase.chonkcraft.engine.map.SeenBuildings.Memory memory) {
            return new Withheld(null, null, null, memory);
        }
    }

    private Withheld withheld = Withheld.NOTHING;

    /** Paints without one named thing from here on. */
    void withhold(Withheld what) {
        this.withheld = what == null ? Withheld.NOTHING : what;
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        frameCounter++;
        refreshLayout();
        // Before anything is drawn, because the panels below are handed
        // whatever this leaves behind and a dead unit's portrait is the first
        // thing a player notices.
        dropLostUnits();
        Graphics2D g2 = (Graphics2D) g;
        // Pixel art: never smooth it.
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);

        // Everything in the playing field is drawn shifted by the chrome, and
        // clipped so it cannot spill over the sidebar.
        java.awt.Shape savedClip = g2.getClip();
        java.awt.geom.AffineTransform savedWorld = g2.getTransform();
        g2.setClip(viewportX(), viewportY(), viewportWidth(), viewportHeight());
        g2.translate(viewportX(), viewportY());
        // Everything below draws in world pixels and knows nothing about the
        // zoom, which is the point of putting it in the transform: a sprite
        // that has to multiply its own coordinates is a sprite that will one
        // day forget to.
        g2.scale(gameScale, gameScale);

        drawTerrain(g2);

        // Dying units are still drawn: their death animation is part of the
        // game's feedback, not an artefact.
        //
        // In the order the game lays them down, not the order they happen to
        // sit in the list. Every type carries a DrawLevel -- a corpse is 10, a
        // building 20 to 40, a soldier 40, a flying unit 60 -- and drawing by
        // list order puts a gryphon under a farm as often as over it. Ties go
        // to whatever is further down the map, so a unit standing in front of
        // another is drawn in front of it.
        java.util.List<Unit> visible = new java.util.ArrayList<>();
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isOnMap() && isUnitVisible(unit)) {
                visible.add(unit);
            }
        }
        visible.sort(java.util.Comparator
                .comparingInt(this::drawLevelOf)
                .thenComparingInt(Unit::pixelY)
                .thenComparingInt(Unit::pixelX));
        // Under the live units, because a memory is the older picture: if
        // something is standing where you remember a building, the thing that
        // is actually there wins.
        drawRemembered(g2);
        for (Unit unit : visible) {
            if (unit != withheld.unit()) {
                // The box right before its own sprite, inside the sorted
                // loop: see drawSelectionBox.
                drawSelectionBox(g2, unit);
                drawUnit(g2, unit);
            }
        }
        // Over the units, because a shot passes in front of whatever it flies
        // across, and under the fog, because a catapult firing into ground you
        // cannot see should not show you where the boulder went.
        drawMissiles(g2);
        drawRallyPoints(g2);
        drawFog(g2);
        drawPings(g2);
        drawPlacementGhost(g2);

        g2.setTransform(savedWorld);
        g2.setClip(savedClip);

        if (panel != null && panel.isAvailable()) {
            // The chrome is laid out in design pixels and blown up here, so
            // every part of it grows together and none of it has to know the
            // scale exists.
            var savedTransform = g2.getTransform();
            g2.scale(interfaceScale, interfaceScale);
            panel.draw(g2, toDesign(getWidth()), toDesign(getHeight()), selected,
                    cameraX, cameraY, visibleWorldWidth(), visibleWorldHeight());
            if (commandPanel != null) {
                // Over the panel art, which is the frame the slots sit in.
                commandPanel.draw(g2, selected);
            }
            drawPopup(g2);
            drawStatus(g2);
            g2.setTransform(savedTransform);
        }

        if (chat != null) {
            var savedTransform = g2.getTransform();
            g2.scale(interfaceScale, interfaceScale);
            int mapX = layout == null ? toDesign(viewportX()) : layout.mapArea().x();
            int mapY = layout == null ? toDesign(viewportY()) : layout.mapArea().y();
            int mapWidth = layout == null ? toDesign(viewportWidth()) : layout.mapArea().width();
            int mapHeight = layout == null ? toDesign(viewportHeight()) : layout.mapArea().height();
            chat.draw(g2, mapX, mapY, mapWidth, mapHeight);
            g2.setTransform(savedTransform);
        }

        // The selection band is drawn over the map, in screen pixels, because
        // that is where it was dragged.
        java.awt.Rectangle dragged = band;
        if (dragged != null) {
            g2.setColor(Color.GREEN);
            g2.setStroke(new BasicStroke(1f));
            g2.drawRect(dragged.x, dragged.y, dragged.width, dragged.height);
        }
        drawOutcome(g2);
        if (menu != null) {
            var savedTransform = g2.getTransform();
            g2.scale(interfaceScale, interfaceScale);
            menu.draw(g2, toDesign(getWidth()), toDesign(getHeight()));
            g2.setTransform(savedTransform);
        }
    }

    /**
     * Where a save should go, and what it should say it is.
     *
     * <p>Set by whoever started the game, because only they know: the screen
     * has a world but no idea which map it came out of.
     */
    private String saveMapPath;
    private String saveCampaign;
    private int saveMission;

    void setSaveContext(String mapPath, String campaign, int mission) {
        this.saveMapPath = mapPath;
        this.saveCampaign = campaign;
        this.saveMission = mission;
    }

    /**
     * The mission's triggers, so a save can say which of them are still armed.
     *
     * <p>A one-shot trigger fires once and disarms itself. Nothing recorded
     * that, so loading a campaign save put every trigger back as the script
     * had installed it and a mission could be won -- or lost -- a second time
     * on a condition it had already spent.
     */
    private net.chonkbase.chonkcraft.engine.trigger.TriggerSystem triggers;

    void setTriggers(net.chonkbase.chonkcraft.engine.trigger.TriggerSystem triggers) {
        this.triggers = triggers;
    }

    /**
     * Writes the game out.
     *
     * <p>Named for the game rather than timestamped, so pressing save twice
     * overwrites rather than filling the directory. A player who wants two
     * saves of the same mission is asking for something this does not offer
     * yet; a player who presses save every few minutes is not.
     */
    String saveGame() {
        if (saveMapPath == null) {
            return "nothing to save";
        }
        try {
            java.nio.file.Path file = saveDirectory().resolve(saveName() + SaveGame.SUFFIX);
            SaveGame.write(world, saveMapPath, saveCampaign, saveMission,
                    triggers == null ? null : triggers.armedTriggers(), file);
            return "saved to " + file.getFileName();
        } catch (java.io.IOException e) {
            return "could not save: " + e.getMessage();
        }
    }

    /** Where saves live, beside the other per-user state. */
    static java.nio.file.Path saveDirectory() {
        return java.nio.file.Paths.get(System.getProperty("user.home"), ".chonkcraft", "saves");
    }

    private String saveName() {
        return saveCampaign != null
                ? saveCampaign + "-mission-" + saveMission
                : saveMapPath.replaceAll("[^A-Za-z0-9._-]", "-");
    }

    /** How the mission ended, or null while it is still running. */
    private volatile net.chonkbase.chonkcraft.engine.trigger.TriggerSystem.Outcome outcome;

    /** Records that the mission is over, so the result can be shown. */
    void setOutcome(net.chonkbase.chonkcraft.engine.trigger.TriggerSystem.Outcome outcome) {
        this.outcome = outcome;
    }

    /**
     * Announces the result across the playing field.
     *
     * <p>The world keeps ticking underneath. Warcraft II does the same: the
     * mission is decided, but the units carry on until the player dismisses
     * the result, so what happened stays on screen.
     */
    private void drawOutcome(Graphics2D g2) {
        var result = outcome;
        if (result == null
                || result == net.chonkbase.chonkcraft.engine.trigger.TriggerSystem.Outcome.RUNNING) {
            return;
        }
        String text = switch (result) {
            case VICTORY -> "MISSION ACCOMPLISHED";
            case DEFEAT -> "MISSION FAILED";
            default -> "DRAW";
        };
        GameFont.Ink ink =
                result == net.chonkbase.chonkcraft.engine.trigger.TriggerSystem.Outcome.VICTORY
                        ? GameFont.Ink.YELLOW
                        : GameFont.Ink.RED;
        int centre = viewportX() + viewportWidth() / 2;
        int y = viewportY() + viewportHeight() / 2;
        if (font != null) {
            int width = font.widthOf(text);
            g2.setColor(new Color(0, 0, 0, 190));
            g2.fillRect(centre - width / 2 - 20, y - 12, width + 40, font.height() + 24);
            font.drawCentred(g2, text, centre, y, ink);
            return;
        }
        g2.setFont(g2.getFont().deriveFont(java.awt.Font.BOLD, 28f));
        java.awt.FontMetrics metrics = g2.getFontMetrics();
        int width = metrics.stringWidth(text);
        g2.setColor(new Color(0, 0, 0, 190));
        g2.fillRect(centre - width / 2 - 20, y - metrics.getAscent() - 12, width + 40,
                metrics.getHeight() + 24);
        g2.setColor(GameFont.colourOf(ink));
        g2.drawString(text, centre - width / 2, y);
    }

    /**
     * The sheet a worker draws from.
     *
     * <p>A worker working a resource is a different sprite sheet, not a badge
     * added to the usual one: the archive ships peasant_with_gold beside
     * peasant, and oil_tanker_full and oil_tanker_empty beside oil_tanker.
     * Drawing the plain sheet makes a full worker and an empty one look
     * identical, which is most of what a player watches an economy by.
     *
     * <p>Which sheet is the resource's own {@code file-when-loaded} and
     * {@code file-when-empty}, so the answer comes from the data rather than
     * from a naming convention two of the four workers do not follow.
     */
    private String workerSprite(Unit unit, UnitType type) {
        return type.imageFileFor(tilesetName, unit.carrying(), unit.carried() > 0);
    }

    /** Height of the button panel art, which anchors the command grid. */
    private static final int BUTTON_PANEL_HEIGHT = 144;

    /**
     * Outlines where a chosen building would go.
     *
     * <p>Green where it fits and red where it does not, asked of the same
     * check that will run when the click lands, so the outline cannot promise
     * something the placement then refuses.
     */
    private void drawPlacementGhost(Graphics2D g2) {
        UnitType what = placing;
        java.awt.Point mouse = pointer;
        if (what == null || mouse == null || mouse.x < viewportX() || mouse.y < viewportY()) {
            return;
        }
        int tileX = worldX(mouse.x) / TILE;
        int tileY = worldY(mouse.y) / TILE;
        int width = Math.max(1, what.tileWidth());
        int height = Math.max(1, what.tileHeight());
        g2.setColor(world.canPlaceBuilding(selected, what, tileX, tileY)
                ? new Color(0, 255, 0, 90)
                : new Color(255, 0, 0, 90));
        g2.fillRect(tileX * TILE - cameraX, tileY * TILE - cameraY, width * TILE, height * TILE);
    }

    /**
     * Whether the local player may see a unit.
     *
     * <p>Your own units always. Everyone else's only while a square they
     * stand on is currently lit: remembering ground is not the same as
     * watching it, so an enemy that walks out of sight disappears while the
     * terrain it crossed stays drawn.
     *
     * <p>Implements {@code CUnit::IsVisibleInViewport}, which is the predicate upstream's
     * {@code FindAndSortUnits} filters the draw list with. Upstream keeps two
     * predicates and this implementation had only one. {@code IsVisibleOnMap} is
     * {@code IsAliveOnMap() && ... && IsVisible(player)} and is what one unit
     * asks about another -- the comment on {@code IsAliveOnMap} says so:
     * "Another unit can interact only with alive map units."
     * {@code IsVisibleInViewport} asks the fog the same question and then
     * returns {@code !Destroyed}, with no test for life at all, because a
     * thing that is dying is still a thing on the ground.
     *
     * <p>{@link World#isVisibleTo(int, Unit)} is the interaction predicate: it
     * opens with {@code !unit.isAlive()} and {@code Unit.isAlive} is false the
     * moment the order becomes {@code DYING}. Drawing through it meant that on
     * the cycle a unit was killed it stopped being painted, so every death
     * animation in the game ran for its hundred-odd cycles with nobody able to
     * see it, and the troll or the orc a player had just shot blinked out of
     * existence instead of falling over. The corpse that {@code leaveCorpse}
     * then puts down is a unit of the neutral player whose order is
     * {@code DYING} for its whole life, so no corpse has ever been drawn
     * either: a battlefield stayed swept clean while the simulation carefully
     * kept bodies on it. Measured on a killed grunt, the death animation
     * advanced through frames 45, 50 and 55 and changed the painted frame zero
     * times.
     */
    private boolean isUnitVisible(Unit unit) {
        UnitType type = unit.type();
        if (type == null || type.revealer()) {
            return false;
        }
        if (unit.isAlive()) {
            return world.isVisibleTo(localPlayer, unit);
        }
        // Dying, or a corpse, which is dying for ever. Upstream's viewport
        // test is the fog and nothing else, so ask the fog directly rather
        // than through the predicate that has already refused.
        //
        // No shortcut for the owner or an ally, for the reason
        // World.isVisibleTo now gives at length: an alliance is not shared
        // vision, and a unit stands inside its own sight anyway. Permanent
        // cloak is not consulted either, and that is upstream's rule rather
        // than an omission -- IsVisibleInViewport guards on IsInvisible, the
        // invisibility spell, and never on PermanentCloak, so a submarine
        // dying on ground you are watching is drawn dying.
        return seenByFog(world, localPlayer, unit);
    }

    /**
     * Whether a player's eyes reach a unit, without asking whether it lives.
     *
     * <p>The footprint half of {@code CUnit::IsVisible}, split out because two
     * surfaces need it for things {@link Unit#isAlive} calls dead. A corpse and
     * a dying soldier are the obvious pair; the less obvious one is scenery.
     * The oil patch, the circle of power and the start location all declare
     * {@code HitPoints = 0} with {@code Indestructible = 1}, and this implementation's
     * {@code isAlive} demands health above zero, so all three read as dead
     * from the moment the map loads. Upstream's {@code CUnit::IsAlive} is
     * {@code !Destroyed && CurrentAction() != Die} and never looks at health at
     * all.
     */
    static boolean seenByFog(World world, int player, Unit unit) {
        if (unit == null || unit.type() == null || !unit.isOnMap()) {
            return false;
        }
        int width = Math.max(1, unit.type().tileWidth());
        int height = Math.max(1, unit.type().tileHeight());
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (world.isVisibleTo(player, unit.tileX() + x, unit.tileY() + y)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Where in the pile a unit is laid down.
     *
     * <p>Implements {@code CUnit::GetDrawLevel},
     * which is what {@code DrawLevelCompare} sorts on -- not
     * {@code Type->DrawLevel}, which is only the last branch of it.
     *
     * <p>The branches matter now that dying units are drawn at all. A footman
     * is drawn at 40 and the body he leaves at 30, and upstream moves him down
     * to the body's level the instant he starts to fall, so a soldier dying in
     * a melee sinks under the ones still standing rather than being painted
     * over them. A type that leaves nothing behind drops ten instead, and so
     * does a building still going up, which is why scaffolding does not cover
     * the workers walking past it.
     */
    private int drawLevelOf(Unit unit) {
        UnitType type = unit.type();
        if (type == null) {
            return 0;
        }
        if (unit.isDying()) {
            UnitType corpse = corpseTypeOf(type);
            return corpse != null ? corpse.drawLevel() : type.drawLevel() - 10;
        }
        if (unit.order() == Unit.Order.UNDER_CONSTRUCTION) {
            return type.drawLevel() - 10;
        }
        return type.drawLevel();
    }

    /** {@code Type->CorpseType}, resolved through the roster and remembered. */
    private UnitType corpseTypeOf(UnitType type) {
        String ident = type.corpse();
        if (ident == null || ident.isEmpty()) {
            return null;
        }
        return data.unitTypes().types().get(ident);
    }

    /**
     * Lays the fog over the terrain.
     *
     * <p>Implements {@code CFogOfWar::DrawTiledLegacy} and the {@code TheScreen}
     * half of {@code DrawFogTile}, which is the tiled fog the original game
     * had rather than the blurred one later LegacyEngine offers.
     *
     * <p>Two layers, drawn after the units so they are covered too: ground
     * never seen is filled solid, ground remembered but unwatched is veiled at
     * half alpha. What makes it look like Warcraft II rather than like a grid
     * is that neither layer's <em>edge</em> is square. Both boundaries are
     * drawn with the tileset's own sixteen fog masks, chosen by which corners
     * of the square are covered, so the fringe curves and dithers the way the
     * original's does. Filling squares instead -- which is what this did --
     * produces exactly the staircase the player is looking at.
     *
     * <p>Only the squares on screen are considered.
     */
    private void drawFog(Graphics2D g2) {
        int fromX = Math.max(0, cameraX / TILE);
        int fromY = Math.max(0, cameraY / TILE);
        int toX = Math.min(world.map().width() - 1,
                (cameraX + visibleWorldWidth()) / TILE);
        int toY = Math.min(world.map().height() - 1,
                (cameraY + visibleWorldHeight()) / TILE);
        var fog = world.fog();

        for (int y = fromY; y <= toY; y++) {
            for (int x = fromX; x <= toX; x++) {
                int left = x * TILE - cameraX;
                int top = y * TILE - cameraY;
                var visibility = teamVisibility(x, y);

                if (visibility == FogOfWar.Visibility.UNEXPLORED) {
                    // Never seen: the whole square is fog, so there is no
                    // fringe to draw. Upstream fills it with the fog colour
                    // and does not consult the masks at all
                    // (DrawFullShroudOfFog at the unseen opacity).
                    g2.setColor(unseenColour);
                    g2.fillRect(left, top, TILE, TILE);
                    continue;
                }

                if (fogTiles == null) {
                    // A tileset whose sheet had no masks in it. Squares are
                    // wrong but visible, which beats no fog at all.
                    if (visibility == FogOfWar.Visibility.EXPLORED) {
                        g2.setColor(dimmedColour);
                        g2.fillRect(left, top, TILE, TILE);
                    }
                    continue;
                }

                int fogFrame = fog.fogFrame(localPlayer, x, y);
                int blackFrame = fog.blackFrame(localPlayer, x, y);

                if (visibility == FogOfWar.Visibility.VISIBLE) {
                    // Watched ground takes only the fringe of whatever is
                    // hidden beside it. The second test is upstream's: where
                    // the two masks agree the black one is about to be drawn
                    // over this one anyway, and drawing both would darken the
                    // overlap twice.
                    if (fogFrame != 0 && fogFrame != blackFrame) {
                        g2.drawImage(fogTiles.explored(fogFrame), left, top, null);
                    }
                } else {
                    g2.setColor(dimmedColour);
                    g2.fillRect(left, top, TILE, TILE);
                }
                if (blackFrame != 0) {
                    g2.drawImage(fogTiles.unseen(blackFrame), left, top, null);
                }
            }
        }
    }

    /**
     * How a square should be drawn, counting the eyes of everybody who shows
     * this player their map.
     *
     * <p>Implements {@code CMapFieldPlayerInfo::TeamVisibilityState}, which is what
     * {@code IsTeamVisible} is and what draws from. Two
     * clauses: the square is lit if this player or anyone they have vision
     * from lights it, and explored if this player or any of them has ever seen
     * it.
     *
     * <p>The fog used to ask {@code fog.visibility(localPlayer...)} and so
     * knew nothing about shared vision, while the units beside it were chosen
     * by {@link World#isVisibleTo(int, int, int)}, which does. In a team game
     * or a save with shared vision restored, an ally's units would be drawn
     * correctly and then veiled by a fog that never lifted off the ground they
     * were standing on -- the same fault that had ally units showing through
     * fog, arriving by the other door.
     *
     * <p>What is still single-player here is the <em>shape</em> of the fringe.
     * {@code FogOfWar.fogFrame} and {@code blackFrame} pick their corner masks
     * from one player's own counts, so under shared vision the veil lifts on
     * the right squares and the mask at the boundary is cut from this player's
     * map rather than the team's. The bounded consequence is a wrong sixteenth
     * of a tile along a shared-vision border and nothing anywhere else; a
     * complete fix wants the team test inside {@code FogOfWar.covered}, which
     * is not this file.
     */
    private FogOfWar.Visibility teamVisibility(int x, int y) {
        if (world.isVisibleTo(localPlayer, x, y)) {
            return FogOfWar.Visibility.VISIBLE;
        }
        var fog = world.fog();
        if (fog.isExplored(localPlayer, x, y)) {
            return FogOfWar.Visibility.EXPLORED;
        }
        for (int other = 0; other < net.chonkbase.chonkcraft.engine.Player.MAX; other++) {
            if (other != localPlayer && world.sharesVisionWith(localPlayer, other)
                    && fog.isExplored(other, x, y)) {
                return FogOfWar.Visibility.EXPLORED;
            }
        }
        return FogOfWar.Visibility.UNEXPLORED;
    }

    /**
     * How dark the fog is, as the prelude states it.
     *
     * <p>{@code SetFogOfWarOpacityLevels} in {@code scripts/legacyEngine.legacy-declaration}.
     * These used to be constants in this file and in {@link FogTiles}, each
     * with a comment naming the script line it had been copied from, while the
     * script's own call went unbound: three numbers stated twice, one of which
     * could not change anything.
     */
    private FogOfWarSettings.Levels fogOpacity = FogOfWarSettings.DEFAULT;

    /**
     * The veil over ground the player remembers but cannot currently see, and
     * the fill over ground never seen.
     *
     * <p>Kept as colours rather than made per square: a screen of fog is
     * several hundred squares and it is redrawn sixty times a second.
     */
    private Color dimmedColour = new Color(0, 0, 0, FogOfWarSettings.DEFAULT.explored());
    private Color unseenColour = new Color(0, 0, 0, FogOfWarSettings.DEFAULT.unseen());

    /**
     * Sets how dark the fog is.
     *
     * <p>Handed the levels the game declared. {@link FogTiles} has to be built
     * with the same ones -- it bakes them into its masks -- which is why the
     * two go together at the call site.
     */
    void setFogOpacity(FogOfWarSettings.Levels levels) {
        if (levels == null) {
            return;
        }
        fogOpacity = levels;
        dimmedColour = new Color(0, 0, 0, levels.explored());
        unseenColour = new Color(0, 0, 0, levels.unseen());
    }

    /** How dark the fog is, for a test that wants to check the plumbing. */
    FogOfWarSettings.Levels fogOpacity() {
        return fogOpacity;
    }

    /**
     * Everything in the air.
     *
     * <p>Implements {@code DrawMissiles}.
     * Without this the world simulated arrows, axes, boulders and dragon
     * breath faithfully and drew none of them: damage arrived out of nowhere,
     * and a catapult firing looked exactly like a catapult doing nothing.
     *
     * <p>In draw-level order, as {@code MissileDrawLevelCompare} sorts them.
     * The levels the scripts hand out are deliberate: dragon breath is 70 so
     * it passes over a gryphon, a rune is 20 so it sits on the ground under
     * whatever walks onto it.
     */
    private void drawMissiles(Graphics2D g2) {
        java.util.List<net.chonkbase.chonkcraft.engine.missile.Missile> flying = world.missiles();
        if (flying.isEmpty()) {
            return;
        }
        if (flying.size() > 1) {
            flying = new java.util.ArrayList<>(flying);
            flying.sort(java.util.Comparator.comparingInt(
                    missile -> missile.type().drawLevel()));
        }
        for (var missile : flying) {
            if (missile != withheld.missile()
                    && world.missileVisible(missile)) {
                drawMissile(g2, missile);
            }
        }
    }

    /**
     * The exact sprite cell and mirror the live renderer will use.
     *
     * <p>Package-visible so the real-media lifecycle gate tests the renderer's
     * decision rather than maintaining a second copy of its arithmetic.
     */
    static SpriteFrame.Resolved missileSpriteFrame(
            net.chonkbase.chonkcraft.engine.missile.Missile missile) {
        var type = missile.type();
        return SpriteFrame.resolve(missile.frame() * type.storedFacings(),
                missile.direction(), type.headingCount());
    }

    /**
     * One projectile.
     *
     * <p>The sheet is cut the way a unit's is, and for the same reason: a
     * frame is picked by an animation step and a facing together. What differs
     * is where the step comes from. A unit's animation script names its frame;
     * a missile's runs on its own, one step per cycle of flight, and its facing
     * is the heading it was launched on rather than one it can turn to.
     *
     * <p>So {@code missiles/arrow.png} is 200 by 40 -- five 40-pixel frames on
     * a single row -- and those five are not an animation at all but the five
     * drawn facings of one arrow, the other three being mirrors. Reading its
     * declared five frames as five animation steps, which is the obvious
     * mistake, spins the arrow through every compass point on its way across.
     * {@code missiles/catapult_rock.png} is the case that proves the rule:
     * fifteen frames over the same five facings, so three steps of an actual
     * tumble.
     */
    private void drawMissile(Graphics2D g2,
            net.chonkbase.chonkcraft.engine.missile.Missile missile) {
        var type = missile.type();
        if (type.missileClass()
                == net.chonkbase.chonkcraft.engine.missile.MissileClass.HIT) {
            int x = (int) Math.round(missile.x()) - cameraX;
            int y = (int) Math.round(missile.y()) - cameraY;
            String text = Integer.toString(missile.damage());
            if (font != null) {
                font.draw(g2, text, x, y, GameFont.Ink.WHITE);
            } else {
                g2.setColor(GameFont.colourOf(GameFont.Ink.WHITE));
                g2.drawString(text, x, y + 12);
            }
            return;
        }
        if (type.sprite() == null) {
            // missile-none and other invisible helpers have no File.
            return;
        }
        IndexedImage sheet = data.sprite(type.sprite());
        if (sheet == null) {
            return;
        }
        int frameWidth = Math.max(1, type.frameWidth());
        int frameHeight = Math.max(1, type.frameHeight());
        int columns = Math.max(1, sheet.width() / frameWidth);
        int rows = Math.max(1, sheet.height() / frameHeight);

        // The animation step names the row; the heading picks within it, and
        // the western half of the compass is the eastern half flipped.
        SpriteFrame.Resolved resolved = missileSpriteFrame(missile);
        int index = Math.floorMod(resolved.index(), columns * rows);
        int sourceX = (index % columns) * frameWidth;
        int sourceY = (index / columns) * frameHeight;

        // The world holds a missile by its centre, which is what a unit fires
        // and what it is aimed at. Upstream stores the corner instead and
        // takes half the frame off at launch; the subtraction happens here.
        int drawX = (int) Math.round(missile.x()) - frameWidth / 2 - cameraX;
        int drawY = (int) Math.round(missile.y()) - frameHeight / 2 - cameraY;
        if (drawX + frameWidth < 0 || drawY + frameHeight < 0
                || drawX > visibleWorldWidth() || drawY > visibleWorldHeight()) {
            return;
        }

        // No owning player: a missile carries no team colour, and asking for a
        // ramp would repaint an arrow in somebody's livery.
        BufferedImage image = spriteImage(sheet, sourceX, sourceY, frameWidth, frameHeight, -1);
        if (image == null) {
            return;
        }
        if (resolved.mirrored()) {
            g2.drawImage(image, drawX + frameWidth, drawY, drawX, drawY + frameHeight,
                    0, 0, frameWidth, frameHeight, null);
        } else {
            g2.drawImage(image, drawX, drawY, null);
        }
    }

    /**
     * The buildings the player remembers but cannot currently see.
     *
     * <p>The drawing half of {@code CUnit::IsVisibleInViewport}'s second
     * branch: a scouted enemy town stays on your map after the scout that
     * found it has gone. Drawn from the snapshot the engine took when the
     * ground went dark, so it shows the building as it was, and covered by the
     * explored veil a moment later like everything else on remembered ground.
     *
     * <p>Nothing here can be selected or clicked -- the hit tests all go
     * through the live unit list, which these are deliberately not in.
     */
    private void drawRemembered(Graphics2D g2) {
        var memories = world.seenBuildings().forPlayer(localPlayer);
        if (memories.isEmpty()) {
            return;
        }
        for (var memory : memories) {
            if (memory.equals(withheld.memory())) {
                continue;
            }
            UnitType type = memory.type();
            IndexedImage sheet = data.sprite(type.imageFileFor(tilesetName));
            if (sheet == null) {
                continue;
            }
            int frameWidth = Math.max(1, type.imageWidth());
            int frameHeight = Math.max(1, type.imageHeight());
            int columns = Math.max(1, sheet.width() / frameWidth);
            int rows = Math.max(1, sheet.height() / frameHeight);
            int index = Math.floorMod(memory.spriteIndex(), columns * rows);

            int footprintWidth = Math.max(1, type.tileWidth()) * TILE;
            int footprintHeight = Math.max(1, type.tileHeight()) * TILE;
            int drawX = memory.tileX() * TILE + (footprintWidth - frameWidth) / 2 - cameraX;
            int drawY = memory.tileY() * TILE + (footprintHeight - frameHeight) / 2 - cameraY;
            if (drawX + frameWidth < 0 || drawY + frameHeight < 0
                    || drawX > visibleWorldWidth() || drawY > visibleWorldHeight()) {
                continue;
            }

            BufferedImage image = spriteImage(sheet,
                    (index % columns) * frameWidth, (index / columns) * frameHeight,
                    frameWidth, frameHeight, memory.owner());
            if (memory.mirrored()) {
                g2.drawImage(image, drawX + frameWidth, drawY, drawX, drawY + frameHeight,
                        0, 0, frameWidth, frameHeight, null);
            } else {
                g2.drawImage(image, drawX, drawY, null);
            }
        }
    }

    private void drawUnit(Graphics2D g2, Unit unit) {
        UnitType type = unit.type();
        IndexedImage sheet = data.sprite(workerSprite(unit, type));
        if (sheet == null) {
            sheet = data.sprite(type.imageFileFor(tilesetName));
        }
        if (sheet == null) {
            return;
        }
        int frameWidth = Math.max(1, type.imageWidth());
        int frameHeight = Math.max(1, type.imageHeight());
        int columns = Math.max(1, sheet.width() / frameWidth);

        // The animation supplies the row, the heading supplies the column, and
        // the western three facings are the eastern three drawn mirrored.
        SpriteFrame.Resolved resolved = unit.spriteFrame();
        int index = Math.floorMod(resolved.index(), Math.max(1, columns * (sheet.height() / frameHeight)));

        // A building going up is scaffolding first and itself part-drawn
        // later, which the scripts describe as a sequence of stages rather
        // than as the finished sprite dimmed.
        if (unit.order() == Unit.Order.UNDER_CONSTRUCTION) {
            var stage = constructionStage(type, unit.progressFraction());
            if (stage != null && stage.source()
                    == ConstructionCatalog.Source.CONSTRUCTION) {
                IndexedImage site = constructionSheet(type);
                if (site != null) {
                    drawConstruction(g2, unit, site, stage.frame(), type);
                    return;
                }
            } else if (stage != null) {
                index = stage.frame();
            }
        }
        int sourceX = (index % columns) * frameWidth;
        int sourceY = (index / columns) * frameHeight;

        int footprintWidth = Math.max(1, type.tileWidth()) * TILE;
        int footprintHeight = Math.max(1, type.tileHeight()) * TILE;
        int drawX = unit.pixelX() + (footprintWidth - frameWidth) / 2 - cameraX;
        int drawY = unit.pixelY() + (footprintHeight - frameHeight) / 2 - cameraY;

        if (drawX + frameWidth < 0 || drawY + frameHeight < 0
                || drawX > visibleWorldWidth() || drawY > visibleWorldHeight()) {
            return;
        }

        if (unit.isAlive()) {
            drawShadow(g2, unit, type, footprintWidth, footprintHeight);
        }

        BufferedImage image = spriteImage(sheet, sourceX, sourceY, frameWidth, frameHeight,
                unit.player());
        if (resolved.mirrored()) {
            // Flip by drawing with the horizontal extents swapped.
            g2.drawImage(image, drawX + frameWidth, drawY, drawX, drawY + frameHeight,
                    0, 0, frameWidth, frameHeight, null);
        } else {
            g2.drawImage(image, drawX, drawY, null);
        }

        if (unit == withheld.decorations()) {
            return;
        }
        if (unit.isAlive() && unit.hitPoints() < type.hitPoints()) {
            drawHealthBar(g2, unit, footprintWidth, footprintHeight,
                    unit.pixelX() - cameraX, unit.pixelY() - cameraY);
        }
        if (unit.isAlive()) {
            drawSpellBadges(g2, unit,
                    unit.pixelX() - cameraX, unit.pixelY() - cameraY);
        }
    }

    /**
     * The five spell badges over an enchanted unit.
     *
     * <p>{@code ui.legacy-declaration:53-65}: one sheet, {@code
     * ui/bloodlust,haste,slow,invisible,shield.png}, five 16 by 16 cells, and
     * five {@code static-sprite} decorations reading the timers --
     * Bloodlust in the first slot, Haste and Slow sharing the second because
     * a unit is one or the other, Invisible in the third and Unholy Armour in
     * the fourth. Every one declares {@code ShowOpponent}, so an enemy's
     * bloodlusted grunt wears the badge too, which is the point: the badge is
     * the warning.
     *
     * <p>The timers themselves have been right since the spell lane -- the
     * five spells cost their mana, wrote the timers and changed the fight --
     * but nothing drew them, so whether a grunt was bloodlusted was knowable
     * only by watching the damage numbers. This is the drawing half focused tests
     * said was left.
     */
    private void drawSpellBadges(Graphics2D g2, Unit unit, int x, int y) {
        if (unit.buff(Unit.Buff.BLOODLUST) <= 0 && unit.buff(Unit.Buff.HASTE) <= 0
                && unit.buff(Unit.Buff.SLOW) <= 0 && unit.buff(Unit.Buff.INVISIBLE) <= 0
                && unit.buff(Unit.Buff.UNHOLY_ARMOR) <= 0) {
            return;
        }
        BufferedImage sheet = spellBadgeSheet();
        if (sheet == null) {
            return;
        }
        drawSpellBadge(g2, sheet, unit.buff(Unit.Buff.BLOODLUST), 0, x, y);
        drawSpellBadge(g2, sheet, unit.buff(Unit.Buff.HASTE), 1, x + 16, y);
        drawSpellBadge(g2, sheet, unit.buff(Unit.Buff.SLOW), 2, x + 16, y);
        drawSpellBadge(g2, sheet, unit.buff(Unit.Buff.INVISIBLE), 3, x + 32, y);
        drawSpellBadge(g2, sheet, unit.buff(Unit.Buff.UNHOLY_ARMOR), 4, x + 48, y);
    }

    /** One badge: cell {@code frame} of the sheet, at the slot's offset. */
    private void drawSpellBadge(Graphics2D g2, BufferedImage sheet, int cycles,
            int frame, int x, int y) {
        if (cycles <= 0) {
            return;
        }
        // The DefineSprites Offset {1, 1}, then cell n of a sheet five cells
        // tall: 16 by 80, frames stacked downwards.
        int sourceY = frame * SPELL_BADGE;
        g2.drawImage(sheet,
                x + 1, y + 1, x + 1 + SPELL_BADGE, y + 1 + SPELL_BADGE,
                0, sourceY, SPELL_BADGE, sourceY + SPELL_BADGE, null);
    }

    /**
     * The shadow under a flyer, drawn before the flyer so it lies beneath.
     *
     * <p>Implements {@code DrawShadow},
     * reduced to what the shipped data reaches: every one of the eight
     * declarations is {@code ShadowDefinition(scale)} from
     * {@code scripts/units.legacy-declaration:32} -- one shared 32 by 32 sheet,
     * {@code missiles/unit_shadow.png}, the cell picked by
     * {@code sprite-frame} and the whole thing pushed south-west by
     * {@code offset}. The cell is {@code ShadowSpriteFrame - 1}
     * a declared frame of nought -- the eye of
     * kilrogg alone -- takes upstream's directional branch, whose arithmetic
     * on a single-frame sprite lands back on cell nought, so both roads end
     * at the same picture and this draws it directly.
     *
     * <p>{@code UnitType.shadowFile} was parsed and read by nothing, and the
     * other three keys were not even kept, so a gryphon and a dragon flew
     * with nothing under them -- the one visual cue that a thing is in the
     * air rather than standing very still.
     */
    private void drawShadow(Graphics2D g2, Unit unit, UnitType type,
            int footprintWidth, int footprintHeight) {
        if (type.shadowFile().isEmpty()) {
            return;
        }
        BufferedImage sheet = shadowSheet(type.shadowFile());
        if (sheet == null) {
            return;
        }
        int width = Math.max(1, type.shadowWidth());
        int height = Math.max(1, type.shadowHeight());
        int cell = Math.max(0, type.shadowSpriteFrame() - 1);
        int sourceY = cell * height;
        if (sourceY + height > sheet.getHeight()) {
            return;
        }
        // The game centred on the footprint, then the declared
        // offset. The shipped types declare no type Offset, so it is not added.
        int x = unit.pixelX() - cameraX - (width - footprintWidth) / 2 + type.shadowOffsetX();
        int y = unit.pixelY() - cameraY - (height - footprintHeight) / 2 + type.shadowOffsetY();
        g2.drawImage(sheet, x, y, x + width, y + height,
                0, sourceY, width, sourceY + height, null);
    }

    /** Shadow sheets under their own palettes, converted once each. */
    private final java.util.Map<String, java.util.Optional<BufferedImage>> shadowSheets =
            new java.util.HashMap<>();

    private BufferedImage shadowSheet(String file) {
        return shadowSheets.computeIfAbsent(file, name -> {
            IndexedImage sheet = data.sprite(name);
            var own = data.paletteFor(name);
            return sheet == null || own == null
                    ? java.util.Optional.empty()
                    : java.util.Optional.of(sheet.toBufferedImage(own));
        }).orElse(null);
    }

    private static final int SPELL_BADGE = 16;

    private static final String SPELL_BADGE_FILE = "ui/bloodlust,haste,slow,invisible,shield";

    /** The badge sheet under its own palette, converted once; null if absent. */
    private BufferedImage spellBadgeSheet;

    private boolean spellBadgeSheetMissing;

    private BufferedImage spellBadgeSheet() {
        if (spellBadgeSheet == null && !spellBadgeSheetMissing) {
            IndexedImage sheet = data.sprite(SPELL_BADGE_FILE);
            // Its own palette, not the tileset's: the badge picture ships with
            // one, and reading it through the terrain palette paints the five
            // icons in whatever the current season keeps in those slots.
            var own = data.paletteFor(SPELL_BADGE_FILE);
            if (sheet == null || own == null) {
                spellBadgeSheetMissing = true;
            } else {
                spellBadgeSheet = sheet.toBufferedImage(own);
            }
        }
        return spellBadgeSheet;
    }

    /**
     * A cross on the square a selected building sends its output to.
     *
     * <p>Only while the building that owns it is selected, which is how the
     * original shows it: a base with six barracks would otherwise be a screen
     * full of crosses. Drawn over the units and under the fog, for the same
     * reason a missile is -- it is a mark on the ground, not a thing standing
     * on it, but it should not show you ground you cannot see.
     */
    private void drawRallyPoints(Graphics2D g2) {
        for (Unit unit : world.unitsSnapshot()) {
            if (!unit.selected() || !world.canControl(localPlayer, unit.player()) || !unit.isAlive()
                    || !unit.hasRallyPoint() || !producesUnits(unit)) {
                continue;
            }
            int x = unit.rallyX() * TILE + TILE / 2 - cameraX;
            int y = unit.rallyY() * TILE + TILE / 2 - cameraY;
            if (x < -TILE || y < -TILE
                    || x > visibleWorldWidth() + TILE || y > visibleWorldHeight() + TILE) {
                continue;
            }
            int arm = TILE / 4;
            g2.setStroke(new BasicStroke(1f));
            // Black first and a pixel off, so the cross stays readable on
            // grass, on snow and on the dark of an unexplored edge alike.
            g2.setColor(Color.BLACK);
            g2.drawLine(x - arm + 1, y + 1, x + arm + 1, y + 1);
            g2.drawLine(x + 1, y - arm + 1, x + 1, y + arm + 1);
            g2.setColor(Color.GREEN);
            g2.drawLine(x - arm, y, x + arm, y);
            g2.drawLine(x, y - arm, x, y + arm);
        }
    }

    /**
     * The green box around one selected unit, drawn right before its sprite.
     *
     * <p>Inside the sorted draw loop, which is where {@code CUnit::Draw} calls
     * {@code DrawUnitSelection}: under its own
     * unit -- an ogre's shoulders and a catapult's arm hang over the edge of
     * their box rather than being fenced in by it -- under anything drawn
     * after it, and <em>over</em> anything drawn before it. All three clauses
     * matter and this has now been wrong in both directions in turn. Drawing
     * the box at the end of its own unit painted a selected footman's box
     * across the roof of the farm behind him; the fix for that was a single
     * pass before any sprite at all, which put every box under everything --
     * and a player sent in a tanker moored on an oil patch with its corner
     * brackets swallowed by the slick, because the patch's sprite went down
     * after them. The slick draws at {@code DrawLevel = 5} and the ship at
     * 40, so in upstream's order the ship's box lands on top of the slick and
     * under the ship, which is what the original game shows.
     */
    private void drawSelectionBox(Graphics2D g2, Unit unit) {
        if (unit.type() == null || !(unit.selected() || isBlinking(unit))) {
            return;
        }
        g2.setStroke(new BasicStroke(1f));
        java.awt.Rectangle box = selectionBoxForTest(unit);
        g2.setColor(selectionColour(unit));
        drawSelectionCorners(g2, box.x, box.y, box.x + box.width, box.y + box.height);
    }

    /**
     * Where a unit's selection box lands on screen.
     *
     * <p>BoxWidth and BoxHeight, centred on the middle of the footprint,
     * exactly as {@code DrawUnitSelection} measures it. The box used to be
     * the footprint itself, which is wrong in both directions: a footman's
     * declared box is 31 on a 32-pixel tile and a catapult's is 63 on the
     * same tile, so the catapult's marker was drawn a quarter of its size
     * and disappeared behind its own sprite. Package-private so a render
     * test can ask where the brackets belong instead of re-deriving it.
     */
    java.awt.Rectangle selectionBoxForTest(Unit unit) {
        UnitType type = unit.type();
        int footprintWidth = Math.max(1, type.tileWidth()) * TILE;
        int footprintHeight = Math.max(1, type.tileHeight()) * TILE;
        int boxWidth = type.boxWidth() > 0 ? type.boxWidth() : footprintWidth - 1;
        int boxHeight = type.boxHeight() > 0 ? type.boxHeight() : footprintHeight - 1;
        int left = unit.pixelX() - cameraX + (footprintWidth - boxWidth) / 2;
        int top = unit.pixelY() - cameraY + (footprintHeight - boxHeight) / 2;
        return new java.awt.Rectangle(left, top, boxWidth, boxHeight);
    }

    /**
     * What colour a selection marker is drawn in.
     *
     * <p>{@code DrawUnitSelection}'s own ladder, in its own order: a neutral
     * unit is yellow, your own and your team's are green, an enemy's is red,
     * and anybody else -- an ally who is not on your team -- is marked in
     * their own player colour. Everything used to be green, so a click on a
     * gold mine, an enemy tower and one of your own footmen all looked
     * identical.
     */
    private Color selectionColour(Unit unit) {
        if (unit.player() == World.NEUTRAL_PLAYER) {
            return Color.YELLOW;
        }
        if (world.canControl(localPlayer, unit.player())) {
            return Color.GREEN;
        }
        if (world.isEnemyPlayer(localPlayer, unit.player())) {
            return Color.RED;
        }
        int[] ramp = rampFor(unit.player());
        // PlayerColorsRGB[...][0]: the first entry of the side's own ramp.
        return ramp == null || ramp.length == 0
                ? Color.GREEN
                : new Color(ramp[0] | 0xFF000000, true);
    }

    /**
     * Four corner brackets rather than a closed rectangle.
     *
     * <p>{@code DrawSelectionCorners} with its own {@code CORNER_PIXELS = 6},
     * which is the style ChonkCraft asks for: {@code SelectionStyle = "corners"} in
     * {@code scripts/legacyEngine.legacy-declaration}. A closed box round a unit hides the unit;
     * the corners say the same thing and leave the sprite visible, which
     * matters most on exactly the units whose box is biggest.
     */
    private static void drawSelectionCorners(Graphics2D g2, int x1, int y1, int x2, int y2) {
        int arm = SELECTION_CORNER_PIXELS;
        g2.drawLine(x1, y1, x1, y1 + arm - 1);
        g2.drawLine(x1 + 1, y1, x1 + arm - 1, y1);

        g2.drawLine(x2, y1, x2, y1 + arm - 1);
        g2.drawLine(x2 - arm + 1, y1, x2 - 1, y1);

        g2.drawLine(x1, y2 - arm + 1, x1, y2);
        g2.drawLine(x1, y2, x1 + arm - 2, y2);

        g2.drawLine(x2, y2 - arm + 1, x2, y2);
        g2.drawLine(x2 - arm + 1, y2, x2 - 1, y2);
    }

    /** {@code CORNER_PIXELS} in {@code DrawSelectionCorners}. */
    private static final int SELECTION_CORNER_PIXELS = 6;

    /**
     * When a unit was last flashed, by identifier.
     *
     * <p>Upstream carries this as {@code CUnit::Blink}, a small counter on the
     * unit that {@code HandleActions} decrements once a cycle. It is held here
     * instead of on {@code Unit} because it is presentation and nothing else:
     * it changes what is drawn, never what happens, and simulation state that
     * only the drawing reads is exactly what the sync hash's own
     * documentation says must not be in it. Keeping it on this side also means
     * the two players' screens can flash different things, which is right --
     * it answers "your click landed", and only one of them clicked.
     */
    private final java.util.Map<Integer, Long> blinkStarted = new java.util.LinkedHashMap<>();

    /** {@code Blink = 4}: two flashes over four cycles. */
    private static final int BLINK_CYCLES = 4;

    /**
     * Flashes a unit, to say a click landed on it.
     *
     * <p>{@code dest->Blink = 4} appears twenty-odd times through
     * {@code DoRightButton}: every branch with a target sets it. Nothing here
     * set it at all, so a right click on a distant unit gave no confirmation
     * that it had been aimed at anything rather than at the ground beside it.
     */
    private void blink(Unit unit) {
        if (unit == null || !unit.isAlive()) {
            return;
        }
        blinkStarted.put(unit.id(), world.cycle());
        // Anything long finished is dropped here rather than on a timer, so
        // the map cannot grow over a long game.
        blinkStarted.values().removeIf(started -> world.cycle() - started > BLINK_CYCLES);
    }

    /**
     * Whether a unit is showing a flash this frame.
     *
     * <p>{@code unit.Blink & 1}: the counter runs 4, 3, 2, 1 and the marker is
     * drawn on the odd values, which is what makes it a flash rather than a
     * quarter-second of solid outline.
     */
    private boolean isBlinking(Unit unit) {
        Long started = blinkStarted.get(unit.id());
        if (started == null) {
            return false;
        }
        long remaining = BLINK_CYCLES - (world.cycle() - started);
        return remaining > 0 && (remaining & 1) == 1;
    }

    /**
     * The horizontal half of the health decoration's {@code OffsetPercent}, as a
     * percentage of the unit's tile footprint.
     *
     * <p>Fifty percent across and a hundred percent down is the bottom-centre
     * of the footprint. Other changing values belong in BNE's selected-unit
     * panel and are deliberately not field decorations.
     */
    private static final int DECORATION_OFFSET_PERCENT_X = 50;

    /** The vertical half of {@code OffsetPercent}; see the horizontal one. */
    private static final int DECORATION_OFFSET_PERCENT_Y = 100;

    /**
     * The height of BNE's damage bar, whose rows sit above the footprint edge.
     */
    private static final int DECORATION_BAR_HEIGHT = 4;

    /**
     * A health bar above a damaged unit: green, amber, then red.
     *
     * <p>Warcraft II draws these only for hurt units, which keeps a healthy
     * army from being a wall of bars.
     *
     * <p>Positioned from the tile footprint, not from the sprite frame. It
     * used to be {@code drawY + frameHeight - 4}, which put the bar four
     * pixels above the bottom of the <em>picture</em> -- and a sprite is
     * routinely much taller than the ground it stands on, which is the whole
     * reason upstream's decorations carry an {@code OffsetPercent} of the
     * footprint rather than an offset into the frame. A dragon's frame is 80
     * pixels tall over a single 32-pixel tile, so its bar floated 24 pixels
     * below it on bare ground, and a row of mixed units had their bars at as
     * many different heights as they had sprite sizes. Rendering four unit
     * sizes side by side is how it was found.
     */
    private void drawHealthBar(Graphics2D g2, Unit unit, int footprintWidth, int footprintHeight,
            int footprintX, int footprintY) {
        int width = Math.max(12, footprintWidth - 4);
        int x = footprintX + footprintWidth * DECORATION_OFFSET_PERCENT_X / 100 - width / 2;
        int y = footprintY + footprintHeight * DECORATION_OFFSET_PERCENT_Y / 100
                - DECORATION_BAR_HEIGHT;
        double fraction = (double) unit.hitPoints() / Math.max(1, unit.type().hitPoints());

        g2.setColor(Color.BLACK);
        g2.fillRect(x, y, width, 3);
        g2.setColor(fraction > 0.66 ? Color.GREEN : fraction > 0.33 ? Color.ORANGE : Color.RED);
        g2.fillRect(x, y, (int) (width * fraction), 3);
    }

    /**
     * Converts one sprite frame to an image.
     *
     * <p>Allocating per frame is wasteful and will go once the renderer keeps
     * resolved sheets; it is fine at the tens-of-units scale this currently
     * draws, and keeps the palette in one place until team colour remapping
     * lands.
     */
    /**
     * The construction sequence a type uses, or null.
     *
     * <p>Types name one; anything that names nothing gets the plain land site,
     * which is what almost every building uses.
     */
    private ConstructionCatalog.Construction constructionFor(UnitType type) {
        var catalog = data.constructions(tilesetName);
        Object named = type.rawProperties().get("Construction");
        if (named != null) {
            var found = catalog.get(String.valueOf(named));
            if (found != null) {
                return found;
            }
        }
        return catalog.get("construction-land");
    }

    private ConstructionCatalog.Stage constructionStage(
            UnitType type, double fraction) {
        var construction = constructionFor(type);
        return construction == null ? null : construction.stageAt(fraction);
    }

    private IndexedImage constructionSheet(UnitType type) {
        var construction = constructionFor(type);
        return construction == null || construction.sprite() == null
                ? null
                : data.sprite(construction.sprite());
    }

    /** Draws one frame of a construction site centred on the footprint. */
    private void drawConstruction(Graphics2D g2, Unit unit, IndexedImage sheet, int frame,
            UnitType type) {
        var construction = constructionFor(type);
        int width = construction.width();
        int height = construction.height();
        int columns = Math.max(1, sheet.width() / width);
        int sourceX = (frame % columns) * width;
        int sourceY = (frame / columns) * height;
        if (sourceY + height > sheet.height()) {
            return;
        }
        int footprintWidth = Math.max(1, type.tileWidth()) * TILE;
        int footprintHeight = Math.max(1, type.tileHeight()) * TILE;
        int drawX = unit.pixelX() + (footprintWidth - width) / 2 - cameraX;
        int drawY = unit.pixelY() + (footprintHeight - height) / 2 - cameraY;
        BufferedImage image = spriteImage(sheet, sourceX, sourceY, width, height,
                unit.player());
        if (image != null) {
            g2.drawImage(image, drawX, drawY, null);
        }
    }

    /**
     * Frames already cut out of a sheet.
     *
     * <p>Bounded and least-recently-used, so a long game cannot fill memory
     * with animation frames it has stopped drawing.
     *
     * <p>The key is the sheet, the rectangle and the owning player -- the
     * player because the same rectangle is a different picture for each side
     * once the colour ramp is swapped in.
     */
    private final java.util.Map<SpriteKey, BufferedImage> sprites =
            new java.util.LinkedHashMap<>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(
                        java.util.Map.Entry<SpriteKey, BufferedImage> eldest) {
                    // Eight sides' worth of frames, since the player is part
                    // of the key now.
                    return size() > 3072;
                }
            };

    /**
     * What identifies one cut frame: which sheet, which rectangle, whose
     * colours.
     *
     * <p>The sheet is held by reference. It used to be
     * {@code System.identityHashCode(sheet)} pasted into a string, which is
     * not an identity: it is a 31-bit hash, and two sheets that happened to
     * collide would have shared a cache entry and drawn as each other -- for
     * the rest of the session, silently, with no way to tell from the frame
     * that anything was wrong. {@code GameData} caches sheets forever so the
     * references are stable, which is what makes reference equality both
     * correct and exact.
     *
     * <p>The record also takes the string concatenation -- five appends and an
     * allocation -- out of a path that runs once per drawn frame per unit per
     * repaint.
     */
    private record SpriteKey(IndexedImage sheet, int sourceX, int sourceY,
            int width, int height, int player) {

        /**
         * Compares the sheet by reference rather than by value.
         *
         * <p>The generated {@code equals} would call {@link Object#equals} on
         * the sheet, and if {@link IndexedImage} ever grows a value-based one
         * the cache would start comparing megabytes of pixels on every lookup.
         * The question being asked is "the same sheet", not "an equal sheet".
         */
        @Override
        public boolean equals(Object other) {
            return other instanceof SpriteKey key
                    && key.sheet == sheet
                    && key.sourceX == sourceX && key.sourceY == sourceY
                    && key.width == width && key.height == height
                    && key.player == player;
        }

        @Override
        public int hashCode() {
            int hash = System.identityHashCode(sheet);
            hash = hash * 31 + sourceX;
            hash = hash * 31 + sourceY;
            hash = hash * 31 + width;
            hash = hash * 31 + height;
            return hash * 31 + player;
        }
    }

    /**
     * One frame of a sprite sheet, as an image that can be drawn.
     *
     * <p>Cut once and kept. This was cutting every frame of every unit out
     * again on every repaint, a pixel at a time through {@code setRGB} --
     * five thousand calls for one footman, and the same five thousand again a
     * sixtieth of a second later for a sprite that had not changed. It was the
     * largest single cost in drawing the game and none of it was necessary:
     * the same rectangle of the same sheet is the same picture every time.
     *
     * <p>Built a row at a time even on a miss. Setting a pixel at a time goes
     * through the colour model on every one of them.
     */
    BufferedImage spriteImage(IndexedImage sheet, int sourceX, int sourceY,
            int width, int height, int player) {
        int[] ramp = rampFor(player);
        SpriteKey key = new SpriteKey(sheet, sourceX, sourceY, width, height,
                ramp == null ? -1 : player);
        BufferedImage cached = sprites.get(key);
        if (cached != null) {
            return cached;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        int[] row = new int[width];
        for (int y = 0; y < height; y++) {
            if (sourceY + y >= sheet.height()) {
                break;
            }
            int span = Math.min(width, sheet.width() - sourceX);
            if (span <= 0) {
                break;
            }
            java.util.Arrays.fill(row, 0);
            for (int x = 0; x < span; x++) {
                int index = sheet.get(sourceX + x, sourceY + y);
                // The team colour band, swapped for this player's ramp. This
                // is how one sprite serves every side: the archive holds a
                // single footman and the four entries at 208 decide whose he
                // is. Drawing them straight from the palette, which is what
                // this did, paints every army the same colour.
                int shade = ramp == null ? -1 : index - colourFirstIndex;
                row[x] = shade >= 0 && shade < ramp.length
                        ? 0xFF000000 | ramp[shade]
                        : palette.argb(index);
            }
            image.setRGB(0, y, width, 1, row, 0, width);
        }
        sprites.put(key, image);
        return image;
    }

    /** The first palette entry the team colour occupies, or -1 when undeclared. */
    private int colourFirstIndex = -1;

    /** Each player's ramp, worked out once. */
    private int[][] playerRamps;

    /**
     * The colours this player's units are painted in, or null to leave the
     * sprite as the archive drew it.
     */
    private int[] rampFor(int player) {
        if (playerRamps == null) {
            var declared = data.playerColours();
            if (!declared.isDefined()) {
                playerRamps = new int[0][];
                return null;
            }
            colourFirstIndex = declared.firstIndex();
            var ramps = declared.ramps();
            playerRamps = new int[ramps.size()][];
            for (int i = 0; i < ramps.size(); i++) {
                playerRamps[i] = ramps.get(i).colours();
            }
        }
        if (playerRamps.length == 0 || player < 0) {
            return null;
        }
        return playerRamps[Math.floorMod(player, playerRamps.length)];
    }

    private void drawStatus(Graphics2D g2) {
        // The sidebar carries the resources; this line is only for the
        // transient messages and the cycle counter.
        if (status.isEmpty()) {
            return;
        }
        // From the layout, so the line sits on its own strip of art rather
        // than wherever the window happens to end.
        int x = layout != null ? layout.statusLineX() : SidePanel.WIDTH + 16;
        int y = layout != null
                ? layout.statusLineY()
                : toDesign(getHeight()) - (font != null ? font.height() : 14) - 2;

        // UI.StatusLine.Width, which was parsed and never read. Upstream's
        // CStatusLine::Draw sets a clip of TextX .. TextX + Width - 1 round
        // this one line and draws inside it, so a long message stops at the
        // end of its strip of art. With nothing clipping it, a long name or a
        // long refusal ran off the end of the panel and, at a small window,
        // off the edge of the screen.
        //
        // Clipped rather than wrapped or shortened with an ellipsis: the
        // status line is one line tall in the artwork, and upstream loses the
        // tail rather than reflowing it.
        java.awt.Shape savedClip = g2.getClip();
        int width = layout == null ? 0 : layout.statusLineWidth();
        if (width > 0) {
            g2.clipRect(x, y - 2, width, (font != null ? font.height() : 14) + 6);
        }
        // Centred on its strip of art rather than started at the strip's left
        // edge. Upstream draws from TextX, which reads as deliberate at 640
        // wide where the strip is short; on a wide window the strip runs the
        // whole bottom of the screen and a five-letter order name huddled in
        // its far left corner read as misplaced, which a player said in as
        // many words. The clip still holds, so a message longer than the
        // strip loses both ends evenly instead of its tail.
        if (font != null) {
            if (width > 0) {
                font.drawCentred(g2, status, x + width / 2, y, normalInk);
            } else {
                font.draw(g2, status, x, y, normalInk);
            }
        } else {
            g2.setColor(GameFont.colourOf(normalInk));
            g2.drawString(status, x, y + 12);
        }
        g2.setClip(savedClip);
    }
}
