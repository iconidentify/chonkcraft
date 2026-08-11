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
import javax.swing.JPanel;
import net.chonkbase.chonkcraft.data.graphic.IndexedImage;
import net.chonkbase.chonkcraft.data.graphic.Palette;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * What a mission came to: the rank, the score and eight columns of statistics.
 *
 * <p>Implements {@code RunResultsMenu} in
 * {@code chonkcraft/scripts/menus/results.legacy-declaration}. The end of a mission used to be
 * the words MISSION ACCOMPLISHED on a black strip in the middle of the map,
 * two and a half seconds of the game still running underneath, and the menu.
 * No score, no rank, no statistics -- and the campaign score the implementation has
 * banked all along was shown to nobody.
 *
 * <p>The eight columns are the script's, in its order and with its captions:
 * Units, Buildings, Gold, Lumber, Oil, Kills, Razings, Score
 * ({@code results.legacy-declaration:203-226}). So is the nineteen-tier rank per race
 * ({@code :6-48}) and the five hundred points a victory is worth
 * ({@code :64}).
 *
 * <p><b>Three of the columns are not what upstream puts in them, and it is
 * worth being exact about which.</b> Upstream reads {@code TotalUnits},
 * {@code TotalBuildings} and {@code TotalResources} -- everything a player ever
 * made and everything it ever gathered, counted as the game went on. This implementation
 * has no such tallies: {@code Player} carries the current bank, and
 * {@code totalKills} and {@code totalRazings}, and nothing else. So Units and
 * Buildings are what is standing at the end and Gold, Lumber and Oil are what
 * is in the treasury. For a mission won by building up, the two readings are
 * close; for one won after heavy losses, this implementation's Units column is lower
 * than upstream's, and for one won after spending everything its Gold column
 * is far lower. Kills, Razings and Score are exact. Adding the three tallies
 * is three counters on {@code Player}, which lives in another lane.
 *
 * <p>The staged reveal, the ticking counters and the Save Replay button are
 * not here. What a player reads off this screen is the rank and the numbers.
 */
final class ResultsScreen extends JPanel {

    private static final int DESIGN_WIDTH = 640;
    private static final int DESIGN_HEIGHT = 480;

    /** How a mission ended, which decides the art, the music and the heading. */
    enum Outcome { VICTORY, DEFEAT, DRAW }

    /**
     * One player's line.
     *
     * @param name     what to call them, including which one is you
     * @param colour   their player colour, which the script uses for the bars
     * @param stats    the eight figures, in the script's column order
     */
    record Row(String name, Color colour, int[] stats) {}

    /** The captions, in {@code results.legacy-declaration}'s order. */
    static final List<String> COLUMNS = List.of(
            "Units", "Buildings", "Gold", "Lumber", "Oil", "Kills", "Razings", "Score");

    /**
     * The human ranks, {@code results.legacy-declaration:6-25}.
     *
     * <p>Nineteen tiers, and the score a player has to pass to reach each. The
     * last is a joke Blizzard left in and it is kept, because a port that
     * quietly tidies its source's jokes is a port that has stopped being one.
     */
    private static final int[] RANK_SCORES = {
        0, 2000, 5000, 8000, 18000, 28000, 40000, 55000, 70000, 85000,
        105000, 125000, 145000, 165000, 185000, 205000, 230000, 255000, 280000};

    private static final String[] HUMAN_RANKS = {
        "Servant", "Peasant", "Squire", "Footman", "Corporal", "Sergeant", "Lieutenant",
        "Captain", "Major", "Knight", "General", "Admiral", "Marshall", "Lord",
        "Grand Admiral", "Highlord", "Thundergod", "God", "Designer"};

    private static final String[] ORC_RANKS = {
        "Slave", "Peon", "Rogue", "Grunt", "Slasher", "Marauder", "Commander",
        "Captain", "Major", "Knight", "General", "Master", "Marshall", "Chieftain",
        "Overlord", "War Chief", "Demigod", "God", "Designer"};

    /**
     * The rank a score earns.
     *
     * <p>{@code results.legacy-declaration:120-127} walks the table and stops at the first
     * threshold the score does not pass, so the tier is the last one strictly
     * exceeded and a player on exactly 2000 is still a Servant.
     */
    static String rankFor(String race, int score) {
        String[] ranks = "orc".equalsIgnoreCase(race) ? ORC_RANKS : HUMAN_RANKS;
        // The first name in the table, not the second. results.legacy-declaration's ranks
        // are one flat list of alternating threshold and name, so its
        // ranksTable[2] is the name beside the nought -- Servant, not Peasant.
        String rank = ranks[0];
        for (int tier = 0; tier < RANK_SCORES.length; tier++) {
            if (score > RANK_SCORES[tier]) {
                rank = ranks[tier];
            } else {
                break;
            }
        }
        return rank;
    }

    /** What a victory is worth on top of everything banked, {@code results.legacy-declaration:64}. */
    static final int VICTORY_BONUS = 500;

    /**
     * Reads the eight columns off the world, for every player that took part.
     *
     * <p>Taken while the game is still standing. The screen is built after the
     * world has been put down, so the numbers have to be lifted before that
     * rather than asked for afterwards.
     */
    static List<Row> statisticsOf(World world, int localPlayer) {
        List<Row> rows = new ArrayList<>();
        for (int index = 0; index < world.players().length; index++) {
            Player player = world.player(index);
            if (player == null || index == World.NEUTRAL_PLAYER) {
                continue;
            }
            int units = 0;
            int buildings = 0;
            for (var unit : world.unitsSnapshot()) {
                if (unit.player() != index || !unit.isAlive() || unit.type() == null) {
                    continue;
                }
                if (unit.type().building()) {
                    buildings++;
                } else {
                    units++;
                }
            }
            // Nobody at all: a slot the map declares and nothing was ever put
            // in. Upstream skips these too rather than printing a row of
            // noughts for six empty seats.
            if (units == 0 && buildings == 0 && player.score() == 0) {
                continue;
            }
            // Short, because the name column is the width the eight figures
            // leave it and "Player 0 (you)" runs into the Units column.
            String name = index == localPlayer ? "You" : "Player " + index;
            rows.add(new Row(name, PlayerColours.of(index), new int[] {
                units,
                buildings,
                player.get(UnitType.Resource.GOLD),
                player.get(UnitType.Resource.WOOD),
                player.get(UnitType.Resource.OIL),
                player.totalKills(),
                player.totalRazings(),
                player.score()}));
        }
        return rows;
    }

    private final GameFont headingFont;
    private final GameFont bodyFont;
    private final BufferedImage background;
    private final BufferedImage button;
    private final String heading;
    private final String rank;
    private final int score;
    private final List<Row> rows;
    private final Runnable onContinue;
    private final String caption;

    private Rectangle continueBounds;
    private BufferedImage design;
    private BufferedImage scaleCache;

    /**
     * @param score the figure the rank is read off: this mission's points plus
     *              whatever the campaign has banked before it, plus five
     *              hundred for a victory. The caller adds them up because only
     *              the caller knows which campaign this is
     */
    ResultsScreen(GameData data, String race, int width, int height, Outcome outcome,
            int score, List<Row> rows, String caption, Runnable onContinue) {
        this.headingFont = GameFont.load(data, GameFont.Face.LARGE);
        this.bodyFont = GameFont.load(data, GameFont.Face.GAME);
        this.rows = List.copyOf(rows);
        this.score = score;
        this.rank = rankFor(race, score);
        this.caption = caption;
        this.onContinue = onContinue;
        this.heading = switch (outcome) {
            case VICTORY -> "Victory!";
            case DEFEAT -> "Defeat!";
            case DRAW -> "Draw!";
        };
        String side = "orc".equalsIgnoreCase(race) ? "orc" : "human";
        // A draw takes the defeat picture, which is what results.legacy-declaration:82-88
        // does with it.
        BufferedImage art = load(data,
                "ui/" + side + "/" + (outcome == Outcome.VICTORY ? "victory" : "defeat"));
        this.background = art != null ? art : load(data, "ui/Menu_background_without_title");
        this.button = load(data, "ui/" + side + "/menubutton");

        setPreferredSize(new Dimension(width, height));
        setBackground(Color.BLACK);
        setDoubleBuffered(true);
        setFocusable(true);
        installInput();
    }

    private static BufferedImage load(GameData data, String path) {
        IndexedImage image = data == null ? null : data.image(path);
        if (image == null) {
            return null;
        }
        Palette palette = data.paletteFor(path);
        return palette == null ? null : image.toBufferedImage(palette);
    }

    private void installInput() {
        addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent event) {
                if (continueBounds != null
                        && continueBounds.contains(toDesign(event.getPoint()))) {
                    press();
                }
            }
        });
        addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent event) {
                switch (event.getKeyCode()) {
                    case KeyEvent.VK_ENTER, KeyEvent.VK_SPACE, KeyEvent.VK_ESCAPE,
                            KeyEvent.VK_C -> press();
                    default -> { }
                }
            }
        });
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        if (design == null) {
            design = new BufferedImage(DESIGN_WIDTH, DESIGN_HEIGHT,
                    BufferedImage.TYPE_INT_RGB);
        }
        Graphics2D into = design.createGraphics();
        into.setColor(Color.BLACK);
        into.fillRect(0, 0, DESIGN_WIDTH, DESIGN_HEIGHT);
        paintDesign(into);
        into.dispose();
        scaleCache = PixelScaler.draw((Graphics2D) g, design,
                getWidth(), getHeight(), false, scaleCache);
    }

    /** Where the first player's row sits, and how far apart the rows are. */
    private static final int FIRST_ROW_Y = 150;

    private static final int ROW_PITCH = 30;

    /** The header row, above the first player. */
    private static final int HEADER_Y = 126;

    /** The left edge of the table and the width of a column. */
    private static final int TABLE_LEFT = 8;

    /**
     * Eight columns and a name have to fit across 640 design pixels, which is
     * what decides this rather than taste: the script lays its own out at 80
     * apart on a screen it centres a 640 panel in, and eight of those plus a
     * name column runs the Score figure off the right-hand edge.
     */
    private static final int COLUMN_WIDTH = 66;

    /** A dark plate under the table, for the same reason the briefing has one. */
    private static final Color PLATE = new Color(18, 12, 6, 210);

    private void paintDesign(Graphics2D g2) {
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        if (background != null) {
            g2.drawImage(background, 0, 0, null);
        }

        if (headingFont != null) {
            headingFont.drawCentred(g2, heading, DESIGN_WIDTH / 2, 24, GameFont.Ink.YELLOW);
        }

        int tableHeight = ROW_PITCH * Math.max(1, rows.size()) + 34;
        g2.setColor(PLATE);
        g2.fillRect(TABLE_LEFT - 8, HEADER_Y - 14,
                DESIGN_WIDTH - 2 * (TABLE_LEFT - 8), tableHeight);

        // A plate behind the rank and the score as well. These four words sit
        // on painted sky and painted torchlight, and on the victory picture
        // the score fell across a lit banner and could not be read.
        g2.setColor(PLATE);
        g2.fillRect(DESIGN_WIDTH / 2 - 110, 52, 220, 52);

        if (bodyFont != null) {
            // Rank and score above the table, where results.legacy-declaration puts them.
            bodyFont.drawCentred(g2, "Rank: " + rank, DESIGN_WIDTH / 2, 62,
                    GameFont.Ink.YELLOW);
            bodyFont.drawCentred(g2, "Score: " + score, DESIGN_WIDTH / 2, 84,
                    GameFont.Ink.YELLOW);

            // The header row. The name column is wider than the figures, so
            // the figures start one column in.
            bodyFont.draw(g2, "Player", TABLE_LEFT, HEADER_Y, GameFont.Ink.YELLOW);
            for (int column = 0; column < COLUMNS.size(); column++) {
                bodyFont.draw(g2, COLUMNS.get(column),
                        TABLE_LEFT + NAME_WIDTH + column * COLUMN_WIDTH, HEADER_Y,
                        GameFont.Ink.YELLOW);
            }

            int y = FIRST_ROW_Y;
            for (Row row : rows) {
                // The player's own colour on the name, as the script gives the
                // stat boxes: with four sides in a skirmish, "Player 3" tells
                // you nothing and red tells you everything.
                g2.setColor(row.colour());
                g2.fillRect(TABLE_LEFT, y + 2, 8, 8);
                bodyFont.draw(g2, row.name(), TABLE_LEFT + 12, y, GameFont.Ink.WHITE);
                for (int column = 0; column < COLUMNS.size()
                        && column < row.stats().length; column++) {
                    bodyFont.draw(g2, String.valueOf(row.stats()[column]),
                            TABLE_LEFT + NAME_WIDTH + column * COLUMN_WIDTH, y,
                            GameFont.Ink.WHITE);
                }
                y += ROW_PITCH;
            }
        }

        int buttonWidth = button != null ? button.getWidth() : 176;
        int buttonHeight = button != null ? button.getHeight() : 24;
        int buttonX = (DESIGN_WIDTH - buttonWidth) / 2;
        int buttonY = DESIGN_HEIGHT - buttonHeight - 40;
        continueBounds = new Rectangle(buttonX, buttonY, buttonWidth, buttonHeight);
        if (button != null) {
            g2.drawImage(button, buttonX, buttonY, null);
        } else {
            g2.setColor(new Color(60, 40, 20));
            g2.fill(continueBounds);
        }
        if (bodyFont != null) {
            bodyFont.drawCentred(g2, caption, buttonX + buttonWidth / 2,
                    buttonY + (buttonHeight - bodyFont.height()) / 2, GameFont.Ink.YELLOW);
        }
    }

    /** How much room the name column takes before the figures start. */
    private static final int NAME_WIDTH = 92;

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

    /** The rank this screen worked out, for tests. */
    String rankForTest() {
        return rank;
    }

    /** Where the button landed, for tests. */
    Rectangle continueBoundsForTest() {
        return continueBounds;
    }

    /**
     * Whether the player has already gone on.
     *
     * <p>Three places reach {@link #press} -- the mouse listener, four keys,
     * and {@link #pressForTest} -- and none of them had a guard, while
     * {@code SplashScreen} has had one all along. {@code onContinue} here is
     * the next mission, so two presses walk two missions on: the second one
     * builds a whole game underneath the first, including its own audio device
     * and its own soundtrack, and the player hears both at once over a map
     * that only one of them belongs to.
     */
    private boolean pressed;

    /** Going on, once. */
    private void press() {
        if (pressed) {
            return;
        }
        pressed = true;
        onContinue.run();
    }

    /** Presses it, as clicking would. */
    void pressForTest() {
        press();
    }
}
