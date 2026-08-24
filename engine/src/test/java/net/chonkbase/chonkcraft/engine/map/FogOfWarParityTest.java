package net.chonkbase.chonkcraft.engine.map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The shape of what a unit sees, and the shape of the fog around it.
 *
 * <p>Both were wrong in ways that only showed once the fog stopped being drawn
 * as opaque squares, so they are pinned here together.
 *
 * <p>The sight disc was {@code dx*dx + dy*dy <= range*range}, which is the
 * obvious reading of "a unit sees a circle" and is not the circle the game
 * draws. Upstream's {@code CFieldOfView::ProceedSimpleRadial} measures against
 * {@code (range + 1)^2 - 1}, giving a wider disc, and -- the part that
 * actually matters -- one that tapers at top and bottom instead of ending in a
 * single square. That single square was a spike of vision one tile wide
 * sticking out of every unit, invisible while fog was drawn as filled squares
 * and a hole in the fog the moment it was drawn with corner masks.
 */
class FogOfWarParityTest {

    private static FogOfWar seeing(int size, int atX, int atY, int range) {
        FogOfWar fog = new FogOfWar(size, size, 2);
        fog.addSight(0, atX, atY, 1, 1, range);
        return fog;
    }

    /** How wide the visible band is on each row, top to bottom. */
    private static int[] rowWidths(FogOfWar fog, int size) {
        int[] widths = new int[size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                if (fog.isVisible(0, x, y)) {
                    widths[y]++;
                }
            }
        }
        return widths;
    }

    /**
     * The row widths are computable by hand from upstream's own expression,
     * so they are written out rather than derived, which is the only way this
     * test can catch the implementation drifting back towards the naive disc.
     *
     * <p>For a one-square unit with sight 5, {@code isqrt(36 - dy*dy - 1)}
     * gives half-widths of 5, 5, 5, 4, 3 going up from the unit's row, and the
     * same going down. A full row is twice that plus the unit's own square.
     */
    @Test
    @DisplayName("A unit's sight is upstream's radial shape, not a naive disc")
    void theSightShapeMatchesUpstream() {
        int size = 21;
        int centre = 10;
        FogOfWar fog = seeing(size, centre, centre, 5);
        int[] widths = rowWidths(fog, size);

        int[] expected = new int[size];
        expected[centre - 5] = 3 * 2 + 1;
        expected[centre - 4] = 4 * 2 + 1;
        expected[centre - 3] = 5 * 2 + 1;
        expected[centre - 2] = 5 * 2 + 1;
        expected[centre - 1] = 5 * 2 + 1;
        expected[centre] = 5 * 2 + 1;
        expected[centre + 1] = 5 * 2 + 1;
        expected[centre + 2] = 5 * 2 + 1;
        expected[centre + 3] = 5 * 2 + 1;
        expected[centre + 4] = 4 * 2 + 1;
        expected[centre + 5] = 3 * 2 + 1;

        assertArrayEquals(expected, widths,
                "the sight shape is not ProceedSimpleRadial's");
    }

    /**
     * The naive disc would give one here. This is the specific number that
     * made a hole appear in the fog.
     */
    @Test
    @DisplayName("The far edge of sight tapers rather than ending in a spike")
    void theEdgeTapers() {
        FogOfWar fog = seeing(21, 10, 10, 5);
        int topRow = 0;
        for (int x = 0; x < 21; x++) {
            if (fog.isVisible(0, x, 5)) {
                topRow++;
            }
        }
        assertEquals(7, topRow,
                "the topmost row of a sight-5 disc should be seven squares wide; one square"
                        + " means the naive disc is back, and a one-square spike of vision has"
                        + " no fog mask that fits around it");
    }

    /**
     * The property the tapering exists to guarantee, stated directly: the fog
     * masks cover every combination of corners <em>except</em> all sixteen and
     * none, so a lone explored square surrounded by hidden ones cannot be
     * drawn. If sight ever produces one, there is a hole on screen.
     */
    @Test
    @DisplayName("Sight never leaves a square the fog cannot cover")
    void noSquareIsLeftUncoverable() {
        for (int range = 1; range <= 9; range++) {
            FogOfWar fog = seeing(41, 20, 20, range);
            for (int y = 1; y < 40; y++) {
                for (int x = 1; x < 40; x++) {
                    if (!fog.isExplored(0, x, y)) {
                        continue;
                    }
                    boolean anyNeighbourSeen = false;
                    for (int dy = -1; dy <= 1 && !anyNeighbourSeen; dy++) {
                        for (int dx = -1; dx <= 1; dx++) {
                            if ((dx != 0 || dy != 0) && fog.isExplored(0, x + dx, y + dy)) {
                                anyNeighbourSeen = true;
                                break;
                            }
                        }
                    }
                    assertTrue(anyNeighbourSeen,
                            "sight range " + range + " left " + x + "," + y
                                    + " explored with every neighbour hidden, which no fog"
                                    + " mask can draw around");
                }
            }
        }
    }

    /** A building sees from all four of its edges, not from one corner. */
    @Test
    @DisplayName("A building's sight is measured from its whole footprint")
    void aBuildingSeesFromEveryEdge() {
        FogOfWar fog = new FogOfWar(41, 41, 2);
        fog.addSight(0, 20, 20, 4, 4, 3);

        assertTrue(fog.isVisible(0, 20 - 3, 21), "it cannot see left");
        assertTrue(fog.isVisible(0, 20 + 4 + 2, 21), "it cannot see right");
        assertTrue(fog.isVisible(0, 21, 20 - 3), "it cannot see up");
        assertTrue(fog.isVisible(0, 21, 20 + 4 + 2), "it cannot see down");
    }

    /** Sight is counted, so one unit walking away does not blind the rest. */
    @Test
    @DisplayName("Overlapping sight is reference counted")
    void sightIsCounted() {
        FogOfWar fog = new FogOfWar(41, 41, 2);
        fog.addSight(0, 20, 20, 1, 1, 4);
        fog.addSight(0, 21, 20, 1, 1, 4);
        assertTrue(fog.isVisible(0, 20, 20));

        fog.removeSight(0, 21, 20, 1, 1, 4);
        assertTrue(fog.isVisible(0, 20, 20), "one unit leaving blinded the other");

        fog.removeSight(0, 20, 20, 1, 1, 4);
        assertFalse(fog.isVisible(0, 20, 20), "sight outlived every unit that had it");
        assertTrue(fog.isExplored(0, 20, 20), "the ground was forgotten, not merely unwatched");
    }

    /** Adding and removing the same sight must leave nothing behind. */
    @Test
    @DisplayName("Removing a unit's sight undoes exactly what adding it did")
    void addAndRemoveAreInverses() {
        for (int range = 1; range <= 8; range++) {
            FogOfWar fog = new FogOfWar(41, 41, 2);
            fog.addSight(0, 20, 20, 2, 3, range);
            fog.removeSight(0, 20, 20, 2, 3, range);
            for (int y = 0; y < 41; y++) {
                for (int x = 0; x < 41; x++) {
                    assertFalse(fog.isVisible(0, x, y),
                            "range " + range + " left " + x + "," + y + " still watched");
                }
            }
        }
    }

    /**
     * The mask index is marching squares over the four corners of a square.
     * Upstream's constants look arbitrary -- 2, 3, 1, 10, 5, 8, 12, 4 -- and
     * are not: each is the pair of corners that neighbour touches.
     */
    @Test
    @DisplayName("Fully covered and fully clear squares need no mask")
    void theExtremesDrawNothing() {
        FogOfWar blind = new FogOfWar(9, 9, 2);
        assertEquals(0, blind.blackFrame(0, 4, 4),
                "a square with nothing explored around it should be filled, not masked");

        FogOfWar open = new FogOfWar(9, 9, 2);
        open.addSight(0, 4, 4, 1, 1, 8);
        assertEquals(0, open.fogFrame(0, 4, 4), "open ground should need no fog at all");
        assertEquals(0, open.blackFrame(0, 4, 4), "open ground should need no black fog");
    }

    /** A straight edge picks the mask for the two corners on that side. */
    @Test
    @DisplayName("A straight fog edge uses the frame for the two corners it covers")
    void aStraightEdgePicksItsFrame() {
        // Explore the whole map, then take back a half-plane, leaving one
        // clean north-south boundary to read the frames off.
        FogOfWar fog = new FogOfWar(9, 9, 2);
        fog.revealAll(0);
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 9; x++) {
                if (x <= 4) {
                    fog.addSight(0, x, y, 1, 1, 0);
                }
            }
        }
        // At x == 4 the eastern neighbour is unwatched, so the two eastern
        // corners are covered: bits 1 and 4, which is index 5.
        assertEquals(6, fog.fogFrame(0, 4, 4),
                "the frame for a fog edge to the east is TiledFogTable[5]");
        // At x == 3 nothing adjacent is hidden.
        assertEquals(0, fog.fogFrame(0, 3, 4), "a square well inside sight needs no fog");
    }

    @Test
    @DisplayName("allied sight uses one combined fog mask across the meeting seam")
    void alliedSightUsesOneCombinedFogMaskAcrossTheMeetingSeam() {
        FogOfWar fog = new FogOfWar(9, 9, 2);
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 9; x++) {
                fog.addSight(x <= 4 ? 0 : 1, x, y, 1, 1, 0);
            }
        }
        FogOfWar.VisibilityLookup team = (x, y) -> {
            if (fog.isVisible(0, x, y) || fog.isVisible(1, x, y)) {
                return FogOfWar.Visibility.VISIBLE;
            }
            if (fog.isExplored(0, x, y) || fog.isExplored(1, x, y)) {
                return FogOfWar.Visibility.EXPLORED;
            }
            return FogOfWar.Visibility.UNEXPLORED;
        };

        assertEquals(6, fog.fogFrame(0, 4, 4),
                "the local-only mask demonstrates the false eastern triangle");
        assertEquals(0, fog.fogFrame(4, 4, team),
                "BNE TeamVisibilityState leaves no fog edge where allied sight joins");
        assertEquals(0, fog.blackFrame(4, 4, team),
                "allied explored memory must not cut a black wedge into the seam");
    }

    /**
     * Off-map neighbours contribute nothing, or every map would be permanently
     * fringed with fog around its border.
     */
    @Test
    @DisplayName("The edge of the map is not treated as hidden ground")
    void theMapBorderIsNotFogged() {
        FogOfWar fog = new FogOfWar(9, 9, 2);
        fog.revealAll(0);
        for (int y = 0; y < 9; y++) {
            for (int x = 0; x < 9; x++) {
                fog.addSight(0, x, y, 1, 1, 0);
            }
        }
        assertEquals(0, fog.fogFrame(0, 0, 0), "the top-left corner of the map is fogged");
        assertEquals(0, fog.fogFrame(0, 8, 8), "the bottom-right corner of the map is fogged");
        assertEquals(0, fog.fogFrame(0, 4, 0), "the top edge of the map is fogged");
    }
}
