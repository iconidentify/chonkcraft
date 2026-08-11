package net.chonkbase.chonkcraft.desktop;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.Palette;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.ui.IconCatalog;
import net.chonkbase.chonkcraft.engine.ui.ButtonAvailability;
import net.chonkbase.chonkcraft.engine.ui.UnitButton;
import net.chonkbase.chonkcraft.engine.unit.Unit;

/**
 * The nine command slots at the bottom of the sidebar.
 *
 * <p>Implements {@code CButtonPanel::Draw}.
 *
 * <p>Nothing here decides what a unit can do. It asks the button set, which
 * answers from the game's own {@code DefineButton} declarations, and draws
 * whatever comes back. That is why a peasant, a barracks and a mage show three
 * unrelated grids without any of them being described in this file.
 *
 * <p>The slot coordinates are the game's, from {@code UI.ButtonPanel.Buttons}
 * in the shipped interface scripts: three columns at 9, 65 and 121 pixels and
 * three rows at 340, 387 and 434, against a panel that starts at y 336. They
 * are held here as offsets from the panel's own origin so the grid stays put
 * when the window is taller than the 640 by 480 those numbers assume.
 */
final class CommandPanel {

    /**
     * Where the nine slots go when the interface scripts cannot be read.
     *
     * <p>The script's own numbers, as absolute positions rather than offsets:
     * {@code AddButtonPanelButton} is called with 9, 65 and 121 across and 340,
     * 387 and 434 down. They do not move when the window grows, which is the
     * point -- the grid belongs under the info panel, not at the bottom of a
     * tall window.
     */
    private static final int[] COLUMN_X = {9, 65, 121};

    private static final int[] ROW_Y = {340, 387, 434};

    /** Where the slots actually are, from the layout script. */
    private java.util.List<net.chonkbase.chonkcraft.engine.ui.UiLayout.Box> slots;

    /** Replaces the slot positions, as a resize does. */
    void setLayout(net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout layout) {
        if (layout != null && layout.buttons().size() == 9) {
            slots = java.util.List.copyOf(layout.buttons());
        }
        if (layout != null) {
            cargoSlots = java.util.List.copyOf(layout.transporting());
            autoCastBorder = borderColour(layout);
        }
    }

    /**
     * {@code UI.ButtonPanel.AutoCastBorderColorRGB}.
     *
     * <p>Declared by every shipped layout as a strong blue and never once
     * drawn: a mage set to cast on sight looked exactly like one that was not,
     * which makes the setting unusable however well it works underneath.
     */
    private Color autoCastBorder = DEFAULT_AUTOCAST_BORDER;

    private static final Color DEFAULT_AUTOCAST_BORDER = new Color(0, 0, 252);

    private static Color borderColour(
            net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout layout) {
        int declared = layout == null ? -1 : layout.autoCastBorderColour();
        return declared < 0 ? DEFAULT_AUTOCAST_BORDER : new Color(declared);
    }

    /** How thick that border is, from {@code s.Default.BorderSize = 2}. */
    private static final int AUTOCAST_BORDER = 2;

    private final World world;
    private final GameData.Interface ui;
    private final net.chonkbase.chonkcraft.engine.upgrade.DependencyRules dependencies;
    private final int localPlayer;

    /** The icon sheet for this tileset, one 46 by 38 cell per frame. */
    private final BufferedImage icons;
    private final int iconsPerRow;

    /** The face the hotkey letters are drawn in. */
    private final GameFont font;

    /** Which page the panel is showing; the build buttons move between them. */
    private int level;

    /** The roster, for the costs of what a slot offers to build or train. */
    private final java.util.Map<String, net.chonkbase.chonkcraft.engine.unit.UnitType> types;

    /** Where each slot was last drawn, for hit testing. */
    private final Rectangle[] slotBounds = new Rectangle[9];

    /** What was in each slot when it was last drawn. */
    private final UnitButton[] shown = new UnitButton[9];

    /** The player's race, for the {@code human-group} button masks. */
    private final String race;

    CommandPanel(World world, GameData data, GameData.Interface ui,
            net.chonkbase.chonkcraft.engine.upgrade.DependencyRules dependencies,
            int localPlayer, String tilesetName, String race,
            java.util.Map<String, net.chonkbase.chonkcraft.engine.unit.UnitType> types) {
        this(world, data, ui, dependencies, localPlayer, tilesetName, race, types, null);
    }

    CommandPanel(World world, GameData data, GameData.Interface ui,
            net.chonkbase.chonkcraft.engine.upgrade.DependencyRules dependencies,
            int localPlayer, String tilesetName, String race,
            java.util.Map<String, net.chonkbase.chonkcraft.engine.unit.UnitType> types,
            net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout layout) {
        java.util.List<net.chonkbase.chonkcraft.engine.ui.UiLayout.Box> positions =
                new java.util.ArrayList<>();
        if (layout != null && layout.buttons().size() == 9) {
            positions.addAll(layout.buttons());
        } else {
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    positions.add(new net.chonkbase.chonkcraft.engine.ui.UiLayout.Box(
                            COLUMN_X[column], ROW_Y[row],
                            IconCatalog.ICON_WIDTH, IconCatalog.ICON_HEIGHT));
                }
            }
        }
        this.slots = java.util.List.copyOf(positions);
        this.cargoSlots = layout == null
                ? java.util.List.of()
                : java.util.List.copyOf(layout.transporting());
        this.autoCastBorder = borderColour(layout);
        this.types = types;
        this.world = world;
        this.ui = ui;
        this.dependencies = dependencies;
        this.localPlayer = localPlayer;
        this.race = race == null || race.isBlank() ? "human" : race.toLowerCase(java.util.Locale.ROOT);

        // The small face, because a hotkey letter shares a 46 pixel icon with
        // the picture it belongs to.
        this.font = GameFont.load(data, GameFont.Face.SMALL);
        IndexedImage sheet = data.sprite("tilesets/" + tilesetName + "/icons");
        Palette palette = sheet == null ? null : data.paletteFor("tilesets/" + tilesetName + "/icons");
        this.icons = sheet == null || palette == null ? null : sheet.toBufferedImage(palette);
        this.iconsPerRow = icons == null ? 0 : Math.max(1, icons.getWidth() / IconCatalog.ICON_WIDTH);
    }

    /** What is in the grid, for tests and render probes. */
    java.util.List<String> describeForTest() {
        java.util.List<String> names = new java.util.ArrayList<>();
        for (UnitButton button : shown) {
            names.add(button == null ? "-" : button.action()
                    + (button.value() == null ? "" : ":" + button.value()));
        }
        return names;
    }

    /** Returns to the root page, as selecting a different unit does. */
    void resetLevel() {
        level = 0;
    }

    int level() {
        return level;
    }

    /**
     * Draws the grid for a unit.
     *
     * <p>The slots are at absolute positions from the layout script, so this
     * does not need to be told where the panel art starts.
     */
    void draw(Graphics2D g2, Unit selected) {
        java.util.Arrays.fill(shown, null);
        java.util.Arrays.fill(slotBounds, null);
        cargoShown.clear();
        cargoBounds.clear();
        if (selected == null || selected.type() == null
                || !world.canControl(localPlayer, selected.player())) {
            // Another player's unit can be looked at but not ordered about.
            return;
        }

        // A loaded transport shows what is aboard as well as its orders, which
        // is what UI.TransportingButtons is for and how the original lets you
        // land one unit rather than the whole ship. As well, not instead: the
        // cargo slots are the lower two rows of the grid and a ship's own
        // buttons -- move, stop, unload -- are declared at positions one to
        // three, which is the top row. That is why the script leaves it free.
        drawCargo(g2, selected);

        String ident = isMixed(selected) ? race + "-group" : identFor(selected);
        UnitButton[] page = ui.buttons().page(ident, level, availability(selected));
        for (int slot = 0; slot < page.length; slot++) {
            UnitButton button = page[slot];
            if (button == null) {
                continue;
            }
            var box = slots.get(slot);
            int x = box.x();
            int y = box.y();
            Rectangle bounds = new Rectangle(x, y, IconCatalog.ICON_WIDTH, IconCatalog.ICON_HEIGHT);
            // A button that would land on a passenger gives way to the
            // passenger: a picture of a footman you can put ashore says more
            // than a second copy of a button whose row is already full.
            if (overlapsCargo(bounds)) {
                continue;
            }
            shown[slot] = button;
            slotBounds[slot] = bounds;
            drawIcon(g2, button, x, y, affordable(button));
        }
    }

    /** Whether a slot is already taken by something aboard a transport. */
    private boolean overlapsCargo(Rectangle box) {
        for (Rectangle taken : cargoBounds) {
            if (taken.intersects(box)) {
                return true;
            }
        }
        return false;
    }

    /** The cargo slots, from the layout, or empty when it could not be read. */
    private java.util.List<net.chonkbase.chonkcraft.engine.ui.UiLayout.Box> cargoSlots =
            java.util.List.of();

    /** What is in each cargo slot, for the click that lands one. */
    private final java.util.List<Unit> cargoShown = new java.util.ArrayList<>();

    /** Where each of those was drawn, alongside {@link #cargoShown}. */
    private final java.util.List<Rectangle> cargoBounds = new java.util.ArrayList<>();

    /**
     * Draws a transport's passengers.
     *
     * <p>In reading order. The script declares the six slots a column at a
     * time -- 9 then 9 again, then 65 twice, then 121 twice -- so a ship with
     * two aboard filled the left hand column top to bottom and left the space
     * beside them empty, which looks like a slot that failed to draw rather
     * than like a ship with two passengers.
     *
     * @return whether anything was drawn
     */
    private boolean drawCargo(Graphics2D g2, Unit selected) {
        cargoShown.clear();
        cargoBounds.clear();
        if (cargoSlots.isEmpty() || selected.type() == null
                || !selected.type().canTransport() || selected.cargo().isEmpty()) {
            return false;
        }
        java.util.List<net.chonkbase.chonkcraft.engine.ui.UiLayout.Box> order =
                new java.util.ArrayList<>(cargoSlots);
        order.sort(java.util.Comparator
                .comparingInt(net.chonkbase.chonkcraft.engine.ui.UiLayout.Box::y)
                .thenComparingInt(net.chonkbase.chonkcraft.engine.ui.UiLayout.Box::x));
        for (int i = 0; i < selected.cargo().size() && i < order.size(); i++) {
            Unit passenger = selected.cargo().get(i);
            var box = order.get(i);
            drawUnitIcon(g2, passenger, box.x(), box.y());
            // UiDrawLifeBar, which upstream draws on every passenger: a
            // transport is how a hurt unit is pulled out of a fight, and which
            // of the four aboard is the hurt one is the thing a player wants
            // to know before landing any of them.
            lifeBar(g2, passenger, box.x(), box.y());
            cargoShown.add(passenger);
            cargoBounds.add(new Rectangle(box.x(), box.y(),
                    IconCatalog.ICON_WIDTH, IconCatalog.ICON_HEIGHT));
        }
        return true;
    }

    /** The bar under a passenger's picture, the same one the sidebar draws. */
    private static void lifeBar(Graphics2D g2, Unit unit, int x, int y) {
        if (unit.type() == null) {
            return;
        }
        double fraction = Math.max(0, Math.min(1,
                unit.hitPoints() / (double) Math.max(1, unit.type().hitPoints())));
        int width = IconCatalog.ICON_WIDTH + 4;
        int across = (int) Math.round(width * fraction);
        g2.setColor(new Color(12, 12, 16));
        g2.fillRect(x - 2, y + IconCatalog.ICON_HEIGHT + 3, width, 4);
        g2.setColor(fraction > 0.66
                ? new Color(70, 190, 70)
                : fraction > 0.33 ? new Color(210, 180, 60) : new Color(200, 60, 60));
        g2.fillRect(x - 2, y + IconCatalog.ICON_HEIGHT + 3, across, 4);
    }

    /** The passenger in a slot, or null: what a click on the panel lands. */
    Unit cargoAt(int x, int y) {
        for (int i = 0; i < cargoShown.size() && i < cargoBounds.size(); i++) {
            if (cargoBounds.get(i).contains(x, y)) {
                return cargoShown.get(i);
            }
        }
        return null;
    }

    /** One unit's own icon, in a slot. */
    private void drawUnitIcon(Graphics2D g2, Unit unit, int x, int y) {
        String named = unit.type() == null ? null : unit.type().icon();
        int frame = named == null || named.isBlank() ? -1 : ui.icons().frame(named);
        if (icons == null || frame < 0) {
            return;
        }
        int sx = (frame % iconsPerRow) * IconCatalog.ICON_WIDTH;
        int sy = (frame / iconsPerRow) * IconCatalog.ICON_HEIGHT;
        if (sy + IconCatalog.ICON_HEIGHT > icons.getHeight()) {
            return;
        }
        PanelArt.sunken(g2, x - 2, y - 2, IconCatalog.ICON_WIDTH + 4,
                IconCatalog.ICON_HEIGHT + 4, StoneTexture.Tint.SLATE);
        g2.drawImage(icons, x, y, x + IconCatalog.ICON_WIDTH, y + IconCatalog.ICON_HEIGHT,
                sx, sy, sx + IconCatalog.ICON_WIDTH, sy + IconCatalog.ICON_HEIGHT, null);
    }

    /**
     * The key the button set is asked under.
     *
     * <p>Not always the unit's own type. {@code UpdateButtonPanelSingleUnit}
     * substitutes two names it calls "trick 17": a building going up asks under
     * {@code cancel-build} and one researching asks under
     * {@code cancel-upgrade}. Because the substitution replaces the type
     * entirely, a half-built barracks offers a cancel button and nothing else,
     * rather than its own grid with a cancel added -- which is exactly what the
     * player sees in Warcraft II.
     *
     * <p>Upstream's second case is {@code UnitAction::UpgradeTo} <em>and</em>
     * {@code UnitAction::Research} -- the two share one arm of the switch.
     * This asked only about research, so a town hall turning into a keep kept
     * its ordinary grid: no cancel, and the buttons it did show were for a
     * building that was in the middle of becoming something else. The gold was
     * committed with no way back, and {@code World.cancelUpgradeTo} -- which
     * exists, works and refunds correctly -- had no path from the mouse.
     */
    private String identFor(Unit unit) {
        if (unit.order() == Unit.Order.UNDER_CONSTRUCTION) {
            return "cancel-build";
        }
        if (unit.researching() != null || unit.upgradingTo() != null) {
            return "cancel-upgrade";
        }
        return unit.type().ident();
    }

    /**
     * Every living unit of the local player that is currently selected.
     *
     * <p>Off the published snapshot, never {@code World.units()}. This runs on
     * the event thread once a frame, and the live roster's modification count
     * moves thirty times a second even on a quiet map -- {@code tick} ends
     * with {@code units.addAll(pending)}, which bumps the count before it
     * checks whether anything is pending -- so walking the live list from here
     * was a {@code ConcurrentModificationException} within 56 reads, measured,
     * which a player saw as the window dying mid-battle. The snapshot is at
     * most one tick stale, and staleness cannot mis-describe the selection:
     * the units in it are the same objects, so {@code selected()} is current,
     * and a unit born this cycle cannot have been clicked yet.
     */
    private java.util.List<Unit> selection() {
        java.util.List<Unit> chosen = new java.util.ArrayList<>();
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.selected() && unit.isAlive()
                    && world.canControl(localPlayer, unit.player())) {
                chosen.add(unit);
            }
        }
        return chosen;
    }

    /**
     * The check the button set filters through.
     *
     * <p>For a mixed selection every unit has to allow the button, per
     * {@code UpdateButtonPanelMultipleUnits}: a group of footmen and peasants
     * offers move and stop but not repair, because the footmen cannot repair.
     */
    private net.chonkbase.chonkcraft.engine.ui.ButtonSet.Availability availability(Unit selected) {
        java.util.List<Unit> chosen = selection();
        if (chosen.size() <= 1) {
            return new ButtonAvailability(world, selected, dependencies, false);
        }
        java.util.List<ButtonAvailability> each = new java.util.ArrayList<>();
        for (Unit unit : chosen) {
            each.add(new ButtonAvailability(world, unit, dependencies, false));
        }
        return button -> each.stream().allMatch(check -> check.test(button));
    }

    /**
     * Whether a selection holds more than one kind of unit.
     *
     * <p>The panel for a mixed group is not the first unit's: it comes from the
     * race's {@code human-group} or {@code orc-group} mask, which is a short
     * list of the orders that make sense for a crowd.
     */
    private boolean isMixed(Unit selected) {
        for (Unit unit : selection()) {
            if (unit.type() != selected.type()) {
                return true;
            }
        }
        return false;
    }

    private void drawIcon(Graphics2D g2, UnitButton button, int x, int y, boolean affordable) {
        // The well the icon sits in. A single grey line round the picture is
        // what this had, and at threefold that line is three screen pixels of
        // flat grey with nothing behind it; a bevelled well generated at the
        // size it is really drawn gives the slot a shape at any scale, and
        // matches the wells the info panel and the production slot already
        // use.
        PanelArt.sunken(g2, x - 2, y - 2, IconCatalog.ICON_WIDTH + 4,
                IconCatalog.ICON_HEIGHT + 4, StoneTexture.Tint.SLATE);

        int frame = ui.icons().frame(button.icon());
        if (icons != null && frame >= 0) {
            int sx = (frame % iconsPerRow) * IconCatalog.ICON_WIDTH;
            int sy = (frame / iconsPerRow) * IconCatalog.ICON_HEIGHT;
            if (sy + IconCatalog.ICON_HEIGHT <= icons.getHeight()) {
                g2.drawImage(icons, x, y, x + IconCatalog.ICON_WIDTH, y + IconCatalog.ICON_HEIGHT,
                        sx, sy, sx + IconCatalog.ICON_WIDTH, sy + IconCatalog.ICON_HEIGHT, null);
            }
        } else {
            // No art for it: a labelled box still says what the slot does.
            g2.setColor(Color.DARK_GRAY);
            g2.fillRect(x, y, IconCatalog.ICON_WIDTH, IconCatalog.ICON_HEIGHT);
            g2.setColor(Color.LIGHT_GRAY);
            g2.drawString(button.key() == null ? "?" : button.key(), x + 18, y + 24);
        }
        if (!affordable) {
            // Dimmed rather than hidden, which is how the original says "not
            // yet": the player can see what they are saving for.
            g2.setColor(UNAFFORDABLE);
            g2.fillRect(x, y, IconCatalog.ICON_WIDTH, IconCatalog.ICON_HEIGHT);
        }
        if (isAutoCasting(button)) {
            // CIcon::DrawUnitIcon: a two pixel border in the panel's own
            // autocast colour, and no other mark. That border is the entire
            // answer to "which of my spells is set to cast itself".
            g2.setColor(autoCastBorder);
            for (int ring = 0; ring < AUTOCAST_BORDER; ring++) {
                g2.drawRect(x - ring, y - ring,
                        IconCatalog.ICON_WIDTH - 1 + ring * 2,
                        IconCatalog.ICON_HEIGHT - 1 + ring * 2);
            }
        }
        drawCommandKey(g2, button, x, y);
    }

    /**
     * The button's hotkey letter, on the corner of its icon.
     *
     * <p>{@code CButtonPanel::Draw} builds this string under the comment
     * "Tutorial show command key in icons" and hands it to
     * {@code DrawUnitIcon}: the key in upper case, or the literal "ESC" for
     * the cancel button. {@code UI.ButtonPanel.ShowCommandKey} switches it and
     * defaults to true -- {@code ShowCommandKey = true} in
     * {@code scripts/legacyEngine.legacy-declaration} -- so the original shows it and this implementation
     * showed nothing. The keys all worked; there was no way to find out what
     * they were except by reading the hint on the status line one slot at a
     * time.
     *
     * <p>Bottom left, on a dark plate. Upstream takes the position from the
     * button style's {@code TextPos}, which this implementation does not model; what it
     * does model is that an icon is a painting and unbacked lettering laid on
     * one cannot be read, which is the same reason the selection overflow
     * count sits on a plate.
     */
    private void drawCommandKey(Graphics2D g2, UnitButton button, int x, int y) {
        if (!showCommandKey) {
            return;
        }
        String key = button.key();
        if (key == null || key.isEmpty()) {
            return;
        }
        String text = "ESC".equalsIgnoreCase(key) || "".equals(key)
                ? "ESC"
                : key.substring(0, 1).toUpperCase(java.util.Locale.ROOT);
        int width = (font == null ? text.length() * 6 : font.widthOf(text)) + 3;
        int height = (font == null ? 9 : font.height()) + 1;
        int left = x + 1;
        int top = y + IconCatalog.ICON_HEIGHT - height - 1;
        g2.setColor(COMMAND_KEY_PLATE);
        g2.fillRect(left, top, width, height);
        if (font != null) {
            font.draw(g2, text, left + 2, top, GameFont.Ink.YELLOW);
        } else {
            g2.setColor(GameFont.colourOf(GameFont.Ink.YELLOW));
            g2.drawString(text, left + 2, top + height - 2);
        }
    }

    /** {@code UI.ButtonPanel.ShowCommandKey}, which defaults to true. */
    private boolean showCommandKey = true;

    /** Turns the hotkey letters off, as the options screen's tick box does. */
    void setShowCommandKey(boolean show) {
        this.showCommandKey = show;
    }

    /** Dark enough that a yellow letter reads on any icon. */
    private static final Color COMMAND_KEY_PLATE = new Color(0, 0, 0, 170);

    private static final Color UNAFFORDABLE = new Color(0, 0, 0, 140);

    /**
     * Whether every selected caster has this spell set to cast itself.
     *
     * <p>Every, not any, and for the same reason the toggle turns it on for
     * everybody when anybody lacks it: the border says what a press would
     * undo.
     */
    boolean isAutoCasting(UnitButton button) {
        if (button == null || !"cast-spell".equals(button.action()) || button.value() == null) {
            return false;
        }
        java.util.List<Unit> casters = new java.util.ArrayList<>();
        for (Unit unit : selection()) {
            if (unit.isCaster()) {
                casters.add(unit);
            }
        }
        if (casters.isEmpty()) {
            return false;
        }
        for (Unit caster : casters) {
            if (!button.value().equals(caster.autoCast())) {
                return false;
            }
        }
        return true;
    }

    /** Whether the player can currently pay for what a slot offers. */
    private boolean affordable(UnitButton button) {
        String value = button.value();
        if (value == null) {
            return true;
        }
        java.util.Map<net.chonkbase.chonkcraft.engine.unit.UnitType.Resource, Integer> costs;
        if (value.startsWith("upgrade-")) {
            var upgrade = world.upgradeSet() == null ? null : world.upgradeSet().get(value);
            costs = upgrade == null ? null : upgrade.costs();
        } else {
            var type = types == null ? null : types.get(value);
            costs = type == null ? null : type.costs();
        }
        if (costs == null) {
            return true;
        }
        java.util.List<Unit> controlled = selection();
        int payingPlayer = controlled.isEmpty() ? localPlayer : controlled.getFirst().player();
        var player = world.player(payingPlayer);
        for (var entry : costs.entrySet()) {
            // TIME is how long a thing takes, not something the player holds.
            // Comparing it against a stockpile nobody has dims every icon on
            // the panel while every one of them still works when pressed.
            if (entry.getKey() == net.chonkbase.chonkcraft.engine.unit.UnitType.Resource.TIME) {
                continue;
            }
            if (player.get(entry.getKey()) < entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The button under a point, or {@code null}.
     *
     * <p>Only ever the slots drawn on the last frame, which is what makes the
     * panel honest: a slot the player cannot see cannot be clicked.
     */
    /** The slot a point is over, or {@code -1}. */
    int slotAt(int x, int y) {
        for (int slot = 0; slot < slotBounds.length; slot++) {
            if (slotBounds[slot] != null && slotBounds[slot].contains(x, y)) {
                return slot;
            }
        }
        return -1;
    }

    /** Where a slot was drawn, for anchoring a popup to it. */
    java.awt.Rectangle boundsOf(int slot) {
        return slot >= 0 && slot < slotBounds.length ? slotBounds[slot] : null;
    }

    /**
     * What a slot costs, as lines of text.
     *
     * <p>Gold, lumber, oil and the mana a spell spends. TIME is left out: it
     * is how long the thing takes, and the panel already shows that as a
     * progress bar once the work starts.
     *
     * <p>That agrees with upstream, which was worth checking rather than
     * assuming. {@code CPopupContentTypeCosts::Draw}
     * loops {@code for (i = 1; i <= MaxCosts; ++i)} -- from one, not from
     * zero -- and index zero is {@code TimeCost}. Warcraft II's own popup does
     * not show a build time either.
     */
    java.util.List<String> costLines(UnitButton button) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String value = button.value();
        if (value == null) {
            return lines;
        }
        if (value.startsWith("upgrade-")) {
            var upgrade = world.upgradeSet() == null ? null : world.upgradeSet().get(value);
            if (upgrade != null) {
                addCosts(lines, upgrade.costs());
            }
            return lines;
        }
        if (value.startsWith("spell-")) {
            var spell = world.spells() == null ? null : world.spells().get(value);
            if (spell != null && spell.manaCost() > 0) {
                lines.add("Mana " + spell.manaCost());
            }
            return lines;
        }
        var type = types == null ? null : types.get(value);
        if (type != null) {
            addCosts(lines, type.costs());
        }
        return lines;
    }

    private static void addCosts(java.util.List<String> lines,
            java.util.Map<net.chonkbase.chonkcraft.engine.unit.UnitType.Resource, Integer> costs) {
        for (var entry : costs.entrySet()) {
            if (entry.getKey() == net.chonkbase.chonkcraft.engine.unit.UnitType.Resource.TIME
                    || entry.getValue() <= 0) {
                continue;
            }
            String name = entry.getKey().name().charAt(0)
                    + entry.getKey().name().substring(1).toLowerCase(java.util.Locale.ROOT);
            lines.add(name + " " + entry.getValue());
        }
    }

    UnitButton buttonAt(int x, int y) {
        for (int slot = 0; slot < slotBounds.length; slot++) {
            if (slotBounds[slot] != null && slotBounds[slot].contains(x, y)) {
                return shown[slot];
            }
        }
        return null;
    }

    /** The button whose hotkey a character matches, or {@code null}. */
    UnitButton buttonForKey(char typed) {
        for (UnitButton button : shown) {
            if (button != null && button.key() != null && !button.key().isEmpty()
                    && Character.toLowerCase(button.key().charAt(0))
                            == Character.toLowerCase(typed)) {
                return button;
            }
        }
        return null;
    }

    /** Switches page, as the two build buttons do. */
    void setLevel(int level) {
        this.level = level;
    }
}
