package net.chonkbase.chonkcraft.engine.pathfinder;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import net.chonkbase.chonkcraft.engine.map.Direction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BattleNetPathFinderTest {

    @Test
    @DisplayName("BNE's unobstructed line uses its integer error convention")
    void directLineMatchesTheNativeStepGenerator() {
        PathFinder.Path path = BattleNetPathFinder.find(
                69, 73, 80, 63, (x, y) -> true);

        assertEquals(PathFinder.Result.FOUND, path.result());
        assertEquals(11, path.length());
        assertEquals(1, path.headings()[path.length() - 1],
                "the next native Bresenham step is north-east");
    }

    @Test
    @DisplayName("DAT_00490e88 rewrites north-east then south-east into pure east")
    void twoHeadingShortcutRewritesNortheastSouthEastIntoEast() {
        // Authenticated XHuman 11 peon 1584 route write at fixture 38:
        // native 0x450350 changes the route head 1→2 (NE+SE → E).
        assertEquals(2, BattleNetPathFinder.twoHeadingShortcut(1, 3),
                "NE then SE is the free-cell shortcut pure east");
        assertEquals(-1, BattleNetPathFinder.twoHeadingShortcut(3, 2),
                "SE then E is not a 0x80 shortcut rewrite");
    }

    @Test
    @DisplayName("a double-step mover on an odd tile finishes its search instead of eating the heap")
    void aDoubleStepSearchFromAnOddTileTerminates() {
        // A two-by-two mover walks the even grid, and this method rounds its
        // goal down to an even square before the ray is drawn. Standing on an
        // odd column it then steps in twos from an odd coordinate toward an
        // even one, and the two never meet: the walk that collects the squares
        // behind a blocked step ran until the heap was gone. A destroyer on
        // 9,26 ordered to attack-move at a dragon on 5,31 took nine gigabytes
        // in three seconds and killed the process, which is why AttackMoveTest
        // reported nought tests run for as long as it has.
        //
        // Nothing about the answer is asserted here beyond its existence.
        // What is being required is that the search comes back at all.
        PathFinder.Path path = assertTimeoutPreemptively(
                Duration.ofSeconds(10),
                () -> BattleNetPathFinder.find(
                        9, 26, 5, 31, 2,
                        (x, y) -> x != 7 || y != 28,
                        (x, y) -> x != 7 || y != 28,
                        (x, y) -> Math.max(Math.abs(x - 5),
                                Math.abs(y - 31)) <= 1,
                        false, false),
                "the path search never returned, which is the unbounded walk");

        assertTrue(path != null, "a search that returns must return an answer");
    }

    @Test
    @DisplayName("a blocked point goal retains the clear prefix of its ray")
    void blockedTreeGoalKeepsTheCapturedHumanThirteenPrefix() {
        PathFinder.Path path = BattleNetPathFinder.find(
                80, 7, 78, 5, 1,
                (x, y) -> x != 78 || y != 5,
                (x, y) -> x != 78 || y != 5,
                (x, y) -> Math.max(Math.abs(x - 78),
                        Math.abs(y - 5)) <= 1,
                false, true);

        assertArrayEquals(new int[] {7}, path.headings(),
                "BNE stores the north-west step before the blocked tree");
    }

    @Test
    @DisplayName("a marked target skirt may finish beneath a mobile occupant")
    void markedSkirtIgnoresMobileOccupancyWithoutEnteringTheMine() {
        // Authenticated Orc 11 fixture 137. Peasant 139 routes west toward the
        // gold-mine approach point 7,123 while peasant 124 occupies the marked
        // skirt at 8,124.
        // Native finishes the wall face on that skirt and optimizes the route
        // to SW,W,W,W,W,W. Treating the peasant like terrain instead chooses
        // the next skirt row and writes SW,SW,W,W,W,W.
        BattleNetPathFinder.Passability passability =
                new BattleNetPathFinder.Passability() {
                    @Override
                    public boolean canEnter(int x, int y) {
                        return !intrinsicallyBlocked(x, y)
                                && (x != 8 || y != 124);
                    }

                    @Override
                    public boolean canEnterIgnoringMobileOccupancy(int x, int y) {
                        return !intrinsicallyBlocked(x, y);
                    }

                    private boolean intrinsicallyBlocked(int x, int y) {
                        return (x == 7 && y == 123)
                                || (x == 7 && y == 124);
                    }
                };

        PathFinder.Path path = BattleNetPathFinder.find(
                14, 123, 7, 123, 1,
                passability, passability,
                (x, y) -> Math.max(Math.abs(x - 7),
                        Math.abs(y - 123)) <= 1);

        assertArrayEquals(new int[] {6, 6, 6, 6, 6, 5}, path.headings(),
                "the stored reverse path executes as SW then five west steps");
    }

    @Test
    @DisplayName("Human 8's blocked mine route escapes east, then north-east")
    void humanEightMineApproachMatchesTheCapturedBnePrefix() {
        PathFinder.Path path = BattleNetPathFinder.find(
                69, 73, 80, 63,
                (x, y) -> x != 70 || y != 72);

        assertArrayEquals(new int[] {1, 2}, path.headings(),
                "PathFinder stores the native forward bytes 2,1 in reverse");
    }

    @Test
    @DisplayName("Orc 5's wall crossing rewinds the last clear west step")
    void orcFiveMineApproachMatchesTheCapturedBneCrossing() {
        PathFinder.Path path = BattleNetPathFinder.find(
                37, 100, 30, 100,
                (x, y) -> x != 35 || y != 100);

        assertArrayEquals(new int[] {7, 6, 5}, path.headings(),
                "the native forward route is south-west, west, north-west");
    }

    @Test
    @DisplayName("large BNE movers use doubled deltas on the even grid")
    void doubledDirectionTableMatchesTheCapturedZeppelinRoute() {
        PathFinder.Path path = BattleNetPathFinder.find(
                92, 14, 83, 10, 2, (x, y) -> true);

        assertArrayEquals(new int[] {6, 7, 6, 7, 6}, path.headings(),
                "the captured forward route is west, north-west, west,"
                        + " north-west, west and happens to be palindromic");
    }

    @Test
    @DisplayName("a stride-2 flyer skirts an occupied point on the native side")
    void occupiedDaemonPointKeepsTheCapturedSecondNortheastStride() {
        // Human 13 daemon 1556 is commanded from 82,6 to the occupied point
        // 86,4. Retail stores NE,NE: it reaches 84,4, skirts the occupant on
        // its north side, and commits the second doubled stride to 86,2.
        BattleNetPathFinder.Passability pass =
                (x, y) -> x != 86 || y != 4;

        PathFinder.Path path = BattleNetPathFinder.find(
                82, 6, 86, 4, 2, pass, pass, null,
                false, false, true);

        assertArrayEquals(new int[] {1, 1}, path.headings(),
                "stored route executes as north-east, north-east");
    }

    @Test
    @DisplayName("Orc 14 tanker wall-follows around the stationary boarding hull")
    void orcFourteenTankerRoutesAroundStationaryTanker() {
        // Retail tanker 1566 plans from 116,6 toward the platform at 123,1
        // while tanker 1576 still occupies anchor 120,4. Its free line would
        // be NE,E,NE; the occupied second anchor makes native 0x4500f0 store
        // NE,NE,SE (raw route bytes 01 01 03).
        BattleNetPathFinder.Passability occupiedTanker =
                (x, y) -> x != 120 || y != 4;
        PathFinder.Path route = BattleNetPathFinder.find(
                116, 6, 123, 3, 2,
                occupiedTanker, occupiedTanker,
                (x, y) -> x >= 122 && x <= 126 && y >= 0 && y <= 4,
                false, false, false);

        assertArrayEquals(new int[] {3, 1, 1}, route.headings(),
                "stored reverse path executes as NE, NE, SE");
    }

    @Test
    @DisplayName("Human 10 routes to the marked ring without entering its mine")
    void humanTenMineTargetRingMatchesTheCapturedBneRoute() {
        PathFinder.Path path = BattleNetPathFinder.find(
                16, 108, 16, 116, 1,
                (x, y) -> x < 16 || x > 18 || y < 116 || y > 118,
                (x, y) -> x >= 15 && x <= 19 && y >= 115 && y <= 119);

        assertArrayEquals(new int[] {4, 4, 4, 4, 4, 4, 3},
                path.headings(),
                "the captured forward route is south-east, then south");
    }

    @Test
    @DisplayName("Orc 7 routes around its mine to the marked target edge")
    void orcSevenMineTargetRingMatchesTheCapturedBneRoute() {
        PathFinder.Path path = BattleNetPathFinder.find(
                87, 122, 84, 122, 1,
                (x, y) -> x < 82 || x > 84 || y < 121 || y > 123,
                (x, y) -> x >= 81 && x <= 85 && y >= 120 && y <= 124);

        assertArrayEquals(new int[] {6, 5}, path.headings(),
                "the captured forward route is south-west, then west");
    }

    @Test
    @DisplayName("Orc 8's tanker tests doubled route anchors, not its footprint")
    void orcEightTankerUsesTheNativeDoubledAnchorGrid() {
        PathFinder.Path path = BattleNetPathFinder.find(
                92, 102, 87, 103, 2,
                (x, y) -> true,
                (x, y) -> x >= 84 && x <= 88
                        && y >= 102 && y <= 106);

        assertArrayEquals(new int[] {6, 6, 6}, path.headings(),
                "the captured route contains three west headings even though"
                        + " the last anchor's visual footprint overlaps the"
                        + " platform");
    }

    @Test
    @DisplayName("XOrc 11 battleship line to the even shipyard is pure north")
    void xorcElevenBattleshipLineToShipyardIsPureNorth() {
        // From 20,40 to shipyard 21,34; stride 2 snaps the goal to 20,34.
        // Native takes pure west on the real map for other reasons, but the
        // unobstructed Bresenham must not invent a north-west first step.
        PathFinder.Path path = BattleNetPathFinder.find(
                20, 40, 21, 34, 2, (x, y) -> true);

        assertEquals(PathFinder.Result.FOUND, path.result());
        assertEquals(0, path.headings()[path.length() - 1],
                "first step toward the even-snapped shipyard is north");
        for (int heading : path.headings()) {
            assertEquals(0, heading, "every step on the open ray is north");
        }
    }

    @Test
    @DisplayName("XOrc 11 southern battleship opens south-east toward the yard")
    void xorcElevenSouthernBattleshipOpensSoutheast() {
        PathFinder.Path path = BattleNetPathFinder.find(
                6, 24, 21, 34, 2, (x, y) -> true);

        assertEquals(PathFinder.Result.FOUND, path.result());
        assertEquals(3, path.headings()[path.length() - 1],
                "first step is south-east, matching native 6,24 to 8,26");
    }

    @Test
    @DisplayName("XHuman 12's tower-wall ray keeps the north-east prefix")
    void xhumanTwelveTowerWallKeepsNortheastPrefix() {
        // Grunt 1476 at 22,44 aims at guard tower 25,42 (2x2). The Bresenham
        // ray takes one free north-east step onto 23,43 and hits the marked
        // wall at 24,43. Wall-follow used to walk the long south face first
        // (22,45) while native steps to 23,43. Neighbours that still need the
        // south-east wall-follow first step are covered by the first-step
        // Chebyshev-gain comparison, not by discarding wall-follow entirely.
        java.util.Set<String> blocked = java.util.Set.of(
                "24:42", "25:42", "26:42",
                "24:43", "25:43", "26:43",
                "24:44", "24:45",
                "21:42", "22:42");
        BattleNetPathFinder.Passability passability =
                (x, y) -> !blocked.contains(x + ":" + y);
        BattleNetPathFinder.GoalMarker marked =
                (x, y) -> x >= 24 && x <= 27 && y >= 41 && y <= 44;

        PathFinder.Path path = BattleNetPathFinder.find(
                22, 44, 25, 43, 1,
                passability, passability, marked, true);

        assertEquals(PathFinder.Result.FOUND, path.result());
        assertEquals(1, path.headings()[path.length() - 1],
                "first step is north-east onto 23,43, matching native grunt 1476");
    }

    @Test
    @DisplayName("a dead SE ray outside the marked skirt yields to wall-follow")
    void deadRayOutsideMarkedSkirtYieldsToWallFollow() {
        // Property under test (XHuman 12 grunt 1470): when preserveEmptyFailure
        // is set, a free SE ray that only improves first-step Chebyshev but
        // ends outside the marked tower skirt must not beat wall-follow. The
        // sealed retail-xhuman-12-idle fixture is the end-to-end witness
        // (19,46→20,45 NE at cycle 9); this unit test pins the ray/wall pick.
        java.util.Set<String> blocked = java.util.Set.of(
                "24:42", "25:42", "26:42",
                "24:43", "25:43", "26:43",
                "24:44", "25:44", "26:44",
                "24:45", "24:46", "24:47",
                "23:44", "23:45");
        BattleNetPathFinder.Passability passability =
                (x, y) -> x >= 18 && x <= 30 && y >= 40 && y <= 50
                        && !blocked.contains(x + ":" + y);
        // Skirt around tower top-left 24,42 -- ray SE from 20,44 dies before
        // entering x>=23.
        BattleNetPathFinder.GoalMarker marked =
                (x, y) -> x >= 23 && x <= 27 && y >= 41 && y <= 45;

        PathFinder.Path path = BattleNetPathFinder.find(
                20, 44, 25, 43, 1,
                passability, passability, marked, true);

        assertEquals(PathFinder.Result.FOUND, path.result());
        assertTrue(path.length() > 0);
        int first = path.headings()[path.length() - 1];
        assertTrue(first != 3,
                "dead SE ray outside the skirt must not be first step; was "
                        + first);
    }

    @Test
    @DisplayName("XOrc 11 platform corridor opens east for a stride-2 destroyer")
    void xorcElevenPlatformCorridorOpensEastNotSouth() {
        // Destroyer 1558 at 4,18 aims at 21,34 with stride 2. Oil platform
        // 5,19 covers 5-7,19-21 so the first SE cell (6,20) is blocked while
        // E (6,18) is free water. Native steps east (route 02 03); wall-follow
        // must not invent pure south onto 4,20.
        BattleNetPathFinder.Passability pass = (x, y) -> {
            if (x >= 5 && x <= 7 && y >= 19 && y <= 21) {
                return false;
            }
            return x >= 0 && y >= 0 && x < 64 && y < 64;
        };
        PathFinder.Path path = BattleNetPathFinder.find(
                4, 18, 21, 34, 2, pass, pass, null, false, false);

        assertEquals(PathFinder.Result.FOUND, path.result());
        assertEquals(2, path.headings()[path.length() - 1],
                "first step is east onto 6,18, matching native destroyer 1558");
    }

    @Test
    @DisplayName("stride-2 oil approach prefers free major-axis west over north wall")
    void xorcTenDestroyerOilApproachOpensWestNotNorth() {
        // XOrc 10 destroyer 1483 at (124,74) toward oil platform (99,79).
        // South of the start row is land, so Bresenham's south component
        // wall-follows; the optimised north face first-stepped pure north
        // (gain 0) while pure west is free water and closes two tiles.
        // Native first-steps west to (122,74).
        BattleNetPathFinder.Passability pass = (x, y) -> {
            if (y >= 76) {
                return false;
            }
            return x >= 0 && y >= 0 && x < 160 && y < 160;
        };
        PathFinder.Path path = BattleNetPathFinder.find(
                124, 74, 99, 79, 2, pass, pass, null, false, false);

        assertEquals(PathFinder.Result.FOUND, path.result(),
                "oil approach must produce a route");
        assertEquals(6, path.headings()[path.length() - 1],
                "first step is west onto 122,74, matching native destroyer 1483");
    }


    @Test
    @DisplayName("XHuman 12's moving-ally tower approach opens north-east")
    void xhumanTwelveMovingAllyTowerApproachOpensNortheast() {
        // Grunt 1482 at 22,42 aims at guard tower 25,42. Allied mover soft-
        // clears 23,43 for the wall trace but the optimizer still sees it.
        // The north wall rejoin used to fail progressFrom and the south face
        // won with SE through the ally; native steps NE to 23,41.
        java.util.Set<String> hardBlocked = java.util.Set.of(
                "24:41", "25:41", "26:41",
                "24:42", "25:42", "26:42",
                "24:43", "25:43", "26:43");
        java.util.Set<String> optimizerBlocked = new java.util.HashSet<>(hardBlocked);
        optimizerBlocked.add("23:43");
        BattleNetPathFinder.Passability traversal =
                (x, y) -> !hardBlocked.contains(x + ":" + y);
        BattleNetPathFinder.Passability optimization =
                (x, y) -> !optimizerBlocked.contains(x + ":" + y);
        BattleNetPathFinder.GoalMarker marked =
                (x, y) -> x >= 24 && x <= 27 && y >= 41 && y <= 44;

        PathFinder.Path path = BattleNetPathFinder.find(
                22, 42, 25, 42, 1,
                traversal, optimization, marked, true);

        assertEquals(PathFinder.Result.FOUND, path.result());
        assertEquals(1, path.headings()[path.length() - 1],
                "first step is north-east onto 23,41, matching native grunt 1482");
    }

    @Test
    @DisplayName("doubled air Patrol keeps a moving ally hard only on its direct ray")
    void doubledAirPatrolUsesHardDirectAndSoftWallViews() {
        BattleNetPathFinder.Passability wall = new BattleNetPathFinder.Passability() {
            @Override
            public boolean canEnter(int x, int y) {
                return x >= 0 && y >= 0;
            }

            @Override
            public boolean isOutOfBounds(int x, int y) {
                return x < 0 || y < 0;
            }
        };
        BattleNetPathFinder.Passability direct = (x, y) ->
                x >= 0 && y >= 0 && (x != 0 || y != 16);

        PathFinder.Path path = BattleNetPathFinder.find(
                2, 10, 0, 16, 2, wall, direct, null,
                false, false, false, true,
                false, false, false, true);

        assertArrayEquals(new int[] {4, 4, 4}, path.headings(),
                "native XOrc 8 gryphon 1550 stores S,S,S beside the ally");
    }

    @Test
    @DisplayName("wood reverse-free blocked goal keeps Bresenham south-west prefix")
    void xhumanTwelvePeonWoodKeepsSouthwestTowardBlockedOrderPoint() {
        // XHuman 12 peon 1497 at 76,38 with reverse-free order point 74,40
        // (tree 72,40). 74,40 is forest/blocked and peon 109 occupies 75,40.
        // Pure Bresenham is SW onto free 75,39. Live pathfinder answers S
        // onto 76,39 (path=445); native steps SW to 75,39.
        java.util.Set<String> blocked = new java.util.HashSet<>(java.util.Set.of(
                "72:40", "73:40", "74:40", "75:40",
                "73:37", "74:37", "73:41", "74:41"));
        BattleNetPathFinder.Passability pass =
                (x, y) -> x >= 70 && x <= 80 && y >= 35 && y <= 45
                        && !blocked.contains(x + ":" + y);
        BattleNetPathFinder.GoalMarker marked =
                (x, y) -> Math.max(Math.abs(x - 74), Math.abs(y - 40)) <= 1;

        PathFinder.Path path = BattleNetPathFinder.find(
                76, 38, 74, 40, 1,
                pass, pass, marked, false, true);

        assertEquals(PathFinder.Result.FOUND, path.result(),
                "blocked forest order point still yields a route prefix");
        assertTrue(path.length() > 0, "route is non-empty");
        assertEquals(5, path.headings()[path.length() - 1],
                "first step is south-west onto 75,39 matching native peon 1497");
    }

    @Test
    @DisplayName("wood path aimed at the tree itself first-steps south-west")
    void xhumanTwelvePeonWoodTreeGoalKeepsSouthwest() {
        // Same map, pathfinder goal = native orderXY tree 72,40.
        java.util.Set<String> blocked = new java.util.HashSet<>(java.util.Set.of(
                "72:40", "73:40", "74:40", "75:40",
                "73:37", "74:37", "73:41", "74:41"));
        BattleNetPathFinder.Passability pass =
                (x, y) -> x >= 70 && x <= 80 && y >= 35 && y <= 45
                        && !blocked.contains(x + ":" + y);
        BattleNetPathFinder.GoalMarker marked =
                (x, y) -> Math.max(Math.abs(x - 72), Math.abs(y - 40)) <= 1;

        PathFinder.Path path = BattleNetPathFinder.find(
                76, 38, 72, 40, 1,
                pass, pass, marked, false, true);

        assertEquals(PathFinder.Result.FOUND, path.result());
        assertTrue(path.length() > 0);
        assertEquals(5, path.headings()[path.length() - 1],
                "tree goal first-steps south-west onto 75,39");
    }

    @Test
    @DisplayName("gold pure-major free prefix is kept when wall ends no closer")
    void goldPureMajorFreePrefixIsKeptWhenWallEndsNoCloser() {
        // Minimal geometry for Orc 7 peon 1582: free WW tip, short wall W,SW
        // ending the same Chebyshev distance from the blocked goal.
        BattleNetPathFinder.Passability pass = (x, y) -> {
            if (x == 0 && y == 5) {
                return false; // blocked goal
            }
            if (x == 2 && y == 5) {
                return false; // mid-ray block after free WW
            }
            if (y == 5 && x >= 3 && x <= 5) {
                return true; // free corridor
            }
            if (y == 6 && x >= 3 && x <= 4) {
                return true; // wall-follow SW cells
            }
            return false;
        };
        BattleNetPathFinder.GoalMarker marked =
                (x, y) -> Math.max(Math.abs(x - 0), Math.abs(y - 5)) <= 1;

        PathFinder.Path withFlag = BattleNetPathFinder.find(
                5, 5, 0, 5, 1, pass, pass, marked, false, false, true);
        assertEquals(PathFinder.Result.FOUND, withFlag.result());
        assertEquals(2, withFlag.length(), "gold flag keeps free WW");
        assertEquals(6, withFlag.headings()[1], "first west");
        assertEquals(6, withFlag.headings()[0], "second west");

        PathFinder.Path withoutFlag = BattleNetPathFinder.find(
                5, 5, 0, 5, 1, pass, pass, marked, false, false, false);
        assertEquals(PathFinder.Result.FOUND, withoutFlag.result());
        // Without the gold flag, wall-follow may append a non-improving SW.
        assertTrue(withoutFlag.length() >= 2, "wall-follow still finds a route");
    }

    @Test
    @DisplayName("human 8 wood free tip is kept under forest free-prefix, wall under gain compare")
    void humanEightWoodFreePrefixAndWallRewritePaths() {
        // retail-human-08-idle peasant 1507 (Java 93) at 78,75 toward tree
        // 85,83. Forest free-prefix keeps Bresenham tip 3333433 onto 84,82.
        // Without that preserve, equal first-step gain keeps wall-follow
        // 333222223544 (E as fourth step onto 82,78) -- the sealed native
        // route. Wood harvest probes both and prefers wall when it rewrites
        // onto a different skirt face.
        java.util.Set<String> blocked = new java.util.HashSet<>(java.util.Set.of(
                "77:74", "77:75", "77:81", "78:83",
                "84:83", "84:84",
                "85:79", "85:80", "85:81", "85:82", "85:83", "85:84",
                "86:79"));
        BattleNetPathFinder.Passability pass =
                (x, y) -> x >= 70 && x <= 95 && y >= 70 && y <= 95
                        && !blocked.contains(x + ":" + y);
        BattleNetPathFinder.GoalMarker marked =
                (x, y) -> Math.max(Math.abs(x - 85), Math.abs(y - 83)) <= 1;

        PathFinder.Path free = BattleNetPathFinder.find(
                78, 75, 85, 83, 1,
                pass, pass, marked, false, true);
        assertEquals(PathFinder.Result.FOUND, free.result());
        assertEquals(7, free.length(), "forest free-prefix keeps the clear ray tip");
        assertEquals(3, free.headings()[free.length() - 1], "free first step SE");
        assertEquals(3, free.headings()[free.length() - 4],
                "free fourth step stays SE onto 82,79");

        PathFinder.Path wall = BattleNetPathFinder.find(
                78, 75, 85, 83, 1,
                pass, pass, marked, false, false);
        assertEquals(PathFinder.Result.FOUND, wall.result());
        assertEquals(12, wall.length(), "gain-compare wall packs the east face");
        assertEquals(2, wall.headings()[wall.length() - 4],
                "wall fourth step is east onto 82,78");
        assertTrue(BattleNetPathFinder.wallExtendsFreePrefix(free, free),
                "a path extends itself");
        assertTrue(!BattleNetPathFinder.wallExtendsFreePrefix(free, wall),
                "wall rewrites free at the fourth step");
        int x = 78;
        int y = 75;
        for (int i = wall.length() - 1; i >= 0; i--) {
            x += Direction.deltaX(wall.headings()[i]);
            y += Direction.deltaY(wall.headings()[i]);
        }
        assertEquals(86, x, "wall-follow ends on the free east face of the tree");
        assertEquals(82, y, "wall-follow ends adjacent south-east of the tree");
    }

    @Test
    @DisplayName("a wood ray blocked by intermediate forest keeps only the free prefix")
    void orcSevenWoodRayKeepsFreeNorthwestPrefixNotWallFollowSoutheast() {
        // retail-orc-07-idle peasant 1567 at (41,9) with wood goal (32,2).
        // Live near-flags: N/NE/E/NW free, SE/S/SW/W solid. Open Bresenham
        // steps north-west onto free (40,8); the next ray cell is forest.
        // Wall-follow used to append south-east (path=73); native stores only
        // NW, then chops from (40,8) and draws SyncRand on fixture cycle 24.
        java.util.Set<String> blocked = new java.util.HashSet<>();
        // Forest mass north-west of the free landing, including the goal tree.
        for (int x = 30; x <= 39; x++) {
            for (int y = 1; y <= 8; y++) {
                blocked.add(x + ":" + y);
            }
        }
        blocked.add("32:2");
        // From (41,9): W/SE/S/SW solid (live landFlags 913 family).
        blocked.add("40:9");
        blocked.add("42:10");
        blocked.add("41:10");
        blocked.add("40:10");
        // From free tip (40,8), block improving wall-follow escapes so the
        // only wall answer is SE back toward (41,9) -- the live path=73 miss.
        blocked.add("39:9");
        blocked.add("39:8");
        blocked.add("40:7");
        blocked.add("41:7");
        // (40,8) stays free -- that is the free-prefix landing.
        BattleNetPathFinder.Passability pass =
                (x, y) -> x >= 28 && x <= 45 && y >= 0 && y <= 12
                        && !blocked.contains(x + ":" + y);
        BattleNetPathFinder.GoalMarker marked =
                (x, y) -> Math.max(Math.abs(x - 32), Math.abs(y - 2)) <= 1;

        PathFinder.Path path = BattleNetPathFinder.find(
                41, 9, 32, 2, 1,
                pass, pass, marked, false, true);

        assertEquals(PathFinder.Result.FOUND, path.result());
        assertEquals(1, path.length(),
                "keeps only the free north-west prefix, not a wall-follow SE tail");
        assertEquals(7, path.headings()[0],
                "sole heading is north-west onto (40,8)");
    }

    @Test
    @DisplayName("a pure two-east free ray keeps both easts when wall faces fail")
    void xhumanTwelveGruntKeepsTwoEastPrefixWhenWallFacesFail() {
        // Grunt 1358 at (10,90) toward (26,87). Bresenham is east,east then
        // north-east onto (13,89). On the live map both wall faces fail and
        // fallbackEscape used to invent east,north,north-east (path 201);
        // native stores route 02 02. The passability below leaves the free
        // east,east corridor open while closing the plane so both wall
        // traces fail -- matching the live "best == null" arm.
        BattleNetPathFinder.Passability pass = (x, y) -> {
            if (y == 90 && x >= 10 && x <= 12) {
                return true;
            }
            if (x == 26 && y == 87) {
                return true;
            }
            if (y == 90 && x > 12) {
                return false;
            }
            if (x >= 13 && x <= 25) {
                return false;
            }
            return true;
        };
        PathFinder.Path path = BattleNetPathFinder.find(
                10, 90, 26, 87, 1, pass);

        assertEquals(PathFinder.Result.FOUND, path.result());
        assertEquals(2, path.length(), "keeps only the free two-east prefix");
        assertEquals(2, path.headings()[path.length() - 1],
                "first step is pure east onto (11,90)");
        assertEquals(2, path.headings()[path.length() - 2],
                "second step stays pure east onto (12,90), not north");
    }

    @Test
    @DisplayName("a free double-step ray onto a blocked unit goal keeps the Bresenham prefix")
    void freeDoubleStepRayOntoBlockedUnitGoalKeepsBresenhamPrefix() {
        // XOrc 8 destroyer 1426: open water from 60,100 toward juggernaught
        // 98,122. The ray is free until the goal cell; wall-follow on a
        // first-step gain tie rewrote SE,E into SE,SE and stepped to 64,104
        // while native stores 03 02... and steps 62,102→64,102.
        PathFinder.Path path = BattleNetPathFinder.find(
                60, 100, 98, 122, 2,
                (x, y) -> x != 98 || y != 122);

        assertEquals(PathFinder.Result.FOUND, path.result());
        assertTrue(path.length() >= 2, "open water prefix must be multi-step");
        assertEquals(3, path.headings()[path.length() - 1],
                "first step is SE onto 62,102");
        assertEquals(2, path.headings()[path.length() - 2],
                "second step is pure E onto 64,102, not a rewritten SE");
    }

    @Test
    @DisplayName("a doubled marked-target tie keeps the wall route")
    void doubledMarkedTargetTieKeepsTheWallRoute() {
        // XHuman 8 tanker 1538 returns from (66,58) toward the refinery point
        // (58,58). The exact point and its north-west doubled anchor are
        // blocked, while allied hulls occupy (64,56)/(66,56) and the widened
        // target marker includes (60,56). Native wall-follow joins there and
        // 0x450350 rewrites W,W,W,N to W,NW,W without cutting through a hull.
        BattleNetPathFinder.Passability passability = (x, y) ->
                (x != 58 || y != 58) && (x != 58 || y != 56)
                        && (x != 64 || y != 56)
                        && (x != 66 || y != 56);
        BattleNetPathFinder.GoalMarker marked =
                (x, y) -> x == 60 && y == 56;

        PathFinder.Path path = BattleNetPathFinder.find(
                66, 58, 58, 58, 2,
                passability, passability, marked,
                true, false, false, true);

        assertArrayEquals(new int[] {6, 7, 6}, path.headings(),
                "stored route executes as W, NW, W to the refinery skirt");
    }

}
