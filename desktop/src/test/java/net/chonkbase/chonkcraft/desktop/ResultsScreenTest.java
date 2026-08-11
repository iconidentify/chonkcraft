package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import javax.imageio.ImageIO;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The end of a mission says what it came to.
 *
 * <p>Finishing a mission put MISSION ACCOMPLISHED on a black strip over the
 * map for two and a half seconds and returned to the menu. No rank, no score
 * and no statistics -- and {@code Player.totalKills} and
 * {@code Player.totalRazings} had been counted all along with, in the combat
 * audit's own words, "nothing displays them yet".
 *
 * <p>{@code RunResultsMenu} ({@code chonkcraft/scripts/menus/results.legacy-declaration}) shows
 * eight columns per player and a rank read off the score, nineteen tiers deep
 * and different for each race. The checks below drive the two halves that can
 * be got wrong quietly: the rank table, which is a boundary rule copied by hand
 * from a flat retired scripting language list and is off by one tier if the copy starts in the wrong
 * place, and the reading of the columns off a live world.
 *
 * <p>Then it paints the screen and looks at it, because a table whose figures
 * are right and whose rows land on top of each other is not a table.
 */
class ResultsScreenTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int WIDTH = 640;
    private static final int HEIGHT = 480;

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("a rank is the last tier the score passes, and each race has its own")
    void theRankTableIsTheScriptsOwn() {
        // results.legacy-declaration:120-127 walks the table and stops at the first threshold
        // the score does not exceed, so the comparison is strict and a player
        // sitting exactly on a threshold has not reached it.
        assertEquals("Servant", ResultsScreen.rankFor("human", 0),
                "a score of nought is the bottom rank");
        assertEquals("Servant", ResultsScreen.rankFor("human", 2000),
                "2000 exactly does not reach Peasant: the script's test is \">\"");
        assertEquals("Peasant", ResultsScreen.rankFor("human", 2001));
        assertEquals("Knight", ResultsScreen.rankFor("human", 90000));
        assertEquals("Designer", ResultsScreen.rankFor("human", 500000),
                "the top tier is a joke Blizzard left in and it stays in");

        assertEquals("Slave", ResultsScreen.rankFor("orc", 0));
        assertEquals("Peon", ResultsScreen.rankFor("orc", 2001));
        // The two tables diverge from the fifth tier and agree again at the
        // eighth, which is what makes "did the right table get used" a
        // question worth asking rather than a formality.
        assertEquals("Slasher", ResultsScreen.rankFor("orc", 19000));
        assertEquals("Corporal", ResultsScreen.rankFor("human", 19000));
        assertEquals("Captain", ResultsScreen.rankFor("orc", 56000));
        assertEquals("Captain", ResultsScreen.rankFor("human", 56000));
    }

    @Test
    @DisplayName("the columns are read off the world that was just played")
    void theStatisticsComeOffThePlayers() {
        GameData data = data();
        PudMap pud = data.campaignMap(MAP);
        Assumptions.assumeTrue(pud != null, "no campaign map available");
        var tileset = data.loadTileset(pud.tileset());
        World world = new World(GameMap.from(pud, tileset.tileset()), Player.from(pud));
        world.setUnitTypes(data.unitTypes().types());

        UnitType footman = data.unitTypes().types().get("unit-footman");
        UnitType farm = data.unitTypes().types().get("unit-farm");
        assertNotNull(footman);
        assertNotNull(farm);

        int soldiers = 0;
        int farms = 0;
        for (int x = 2; x < 12 && soldiers < 3; x++) {
            if (world.createUnit(footman, 0, x, 2) != null) {
                soldiers++;
            }
        }
        for (int x = 2; x < 20 && farms < 2; x++) {
            if (world.createUnit(farm, 0, x, 6) != null) {
                farms++;
            }
        }
        Assumptions.assumeTrue(soldiers == 3 && farms == 2,
                "nowhere on this map to stand three footmen and two farms");

        world.player(0).set(UnitType.Resource.GOLD, 1234);
        world.player(0).set(UnitType.Resource.WOOD, 567);
        world.player(0).set(UnitType.Resource.OIL, 89);
        world.player(0).setTotalKills(7);
        world.player(0).setTotalRazings(3);
        world.player(0).setScore(4321);

        List<ResultsScreen.Row> rows = ResultsScreen.statisticsOf(world, 0);
        ResultsScreen.Row mine = null;
        for (ResultsScreen.Row row : rows) {
            if ("You".equals(row.name())) {
                mine = row;
            }
        }
        assertNotNull(mine, "the local player has no row on their own results screen");
        assertEquals(soldiers, mine.stats()[0], "the Units column");
        assertEquals(farms, mine.stats()[1], "the Buildings column");
        assertEquals(1234, mine.stats()[2], "the Gold column");
        assertEquals(567, mine.stats()[3], "the Lumber column");
        assertEquals(89, mine.stats()[4], "the Oil column");
        assertEquals(7, mine.stats()[5],
                "the Kills column: Player.totalKills has been counted all along and shown"
                        + " nowhere");
        assertEquals(3, mine.stats()[6], "the Razings column");
        assertEquals(4321, mine.stats()[7], "the Score column");
    }

    @Test
    @DisplayName("the screen draws its rank, its eight columns and a way onward")
    void theScreenIsLegible() {
        GameData data = data();
        int[] pressed = new int[1];
        List<ResultsScreen.Row> rows = List.of(
                new ResultsScreen.Row("You", java.awt.Color.RED,
                        new int[] {12, 7, 1234, 567, 89, 21, 4, 90000}),
                new ResultsScreen.Row("Player 1", java.awt.Color.BLUE,
                        new int[] {3, 1, 40, 0, 0, 5, 0, 900}));
        ResultsScreen screen = new ResultsScreen(data, "human", WIDTH, HEIGHT,
                ResultsScreen.Outcome.VICTORY, 90000, rows, "Next Mission",
                () -> pressed[0]++);
        screen.setSize(WIDTH, HEIGHT);

        BufferedImage frame = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        var g = frame.createGraphics();
        screen.paint(g);
        g.dispose();
        save(frame, "results-victory.png");

        assertEquals("Knight", screen.rankForTest(),
                "90000 points is a Knight on the human table: the tier above 85000");

        // Every row of the table has ink on it. A layout fault that stacked
        // two rows on one line, or ran them off the panel, would leave one of
        // these bands empty while the figures themselves stayed right.
        int firstRow = 150;
        for (int row = 0; row < rows.size(); row++) {
            int top = firstRow + row * 30 - 4;
            assertTrue(inkIn(frame, 16, top, WIDTH - 32, 18) > 60,
                    "row " + row + " of the results table is blank between y " + top
                            + " and " + (top + 18));
        }
        // And the header above them.
        assertTrue(inkIn(frame, 16, 118, WIDTH - 32, 18) > 60,
                "the column captions were not drawn");

        java.awt.Rectangle button = screen.continueBoundsForTest();
        assertNotNull(button, "the results screen has no way onward");
        assertTrue(button.y + button.height <= HEIGHT,
                "the continue button is off the bottom of the screen at " + button);
        screen.pressForTest();
        assertEquals(1, pressed[0], "pressing continue did not go on to the next mission");
    }

    /**
     * Pixels in a band that are neither the plate nor the picture behind it.
     *
     * <p>The table sits on a near-black plate, so lettering is anything much
     * brighter than that. Counting rather than checking: a band with four
     * bright pixels in it is a coincidence, and a band with sixty is a row of
     * figures.
     */
    private static int inkIn(BufferedImage frame, int x, int y, int width, int height) {
        int ink = 0;
        for (int row = Math.max(0, y); row < Math.min(frame.getHeight(), y + height); row++) {
            for (int col = Math.max(0, x); col < Math.min(frame.getWidth(), x + width); col++) {
                int rgb = frame.getRGB(col, row);
                int r = (rgb >> 16) & 0xFF;
                int gr = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                if (r + gr + b > 300) {
                    ink++;
                }
            }
        }
        return ink;
    }

    private static void save(BufferedImage frame, String name) {
        try {
            Path out = Paths.get("target");
            Files.createDirectories(out);
            ImageIO.write(frame, "png", out.resolve(name).toFile());
        } catch (IOException unwritable) {
            // A frame that could not be written is not a failing assertion.
        }
    }
}
