package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.ai.AiPlayer;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks down the tree ordering used by retail Battle.net Edition's AI. */
class BattleNetAiWoodTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.gathering().put(UnitType.Resource.GOLD,
                new ResourceInfo(UnitType.Resource.GOLD));
        return type;
    }

    private static UnitType building(String ident, int width, int height) {
        UnitType type = new UnitType(ident);
        type.setTileSize(width, height);
        type.setHitPoints(1_000);
        type.setBuilding(true);
        type.setLandUnit(true);
        return type;
    }

    @Test
    @DisplayName("BNE's clockwise square ring reaches the lower western tree first")
    void nativeSquareRingDeterminesWhichEquidistantTreeTheAiChooses() {
        GameMap map = grass(24);
        // Both trees are five columns west of the worker and touch the same
        // connected land mass. A north-first breadth-first search reaches
        // (5,9); BNE's final, northbound edge of this ring reaches (5,12).
        map.field(5, 9).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        map.field(5, 12).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);

        World world = new World(map);
        Unit worker = world.createUnit(peasant(), 0, 10, 10);

        assertArrayEquals(new int[] {5, 12}, world.findAiWood(worker, 20),
                "retail BNE 2.02 scans each expanding ring east, south, west, north");
    }

    @Test
    @DisplayName("BNE's failed-gold wood fallback searches one row north")
    void failedGoldReadyFallbackUsesTheNorthShiftedUnitCenter() {
        GameMap map = grass(24);
        map.field(9, 8).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        map.field(9, 9).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);

        World world = new World(map);
        Unit worker = world.createUnit(woodcutter(), 0, 10, 10);
        assertTrue(worker != null, "the worker must place");
        worker.setBattleNetWoodReadyPathRequired(true);

        assertArrayEquals(new int[] {9, 8, 1}, world.findAiWood(worker, 20),
                "failed-gold UnitReady shifts the terrain ring north before searching");
    }

    @Test
    @DisplayName("a distant failed-gold wood fallback keeps the ordinary ring")
    void distantFailedGoldFallbackRetainsTheAnchorCenteredSearch() {
        GameMap map = grass(24);
        map.field(14, 10).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);

        World world = new World(map);
        Unit worker = world.createUnit(woodcutter(), 0, 10, 10);
        assertTrue(worker != null, "the worker must place");
        worker.setBattleNetWoodReadyPathRequired(true);

        assertArrayEquals(new int[] {14, 10}, world.findAiWood(worker, 20),
                "distant fallbacks use the same ring as ordinary lumber assignment");
        assertTrue(!worker.battleNetWoodReadyPathRequired(),
                "a distant terrain result does not retain the adjacent retry marker");
    }

    private static UnitType woodcutter() {
        UnitType type = peasant();
        ResourceInfo wood = new ResourceInfo(UnitType.Resource.WOOD);
        wood.setTerrainHarvester(true);
        wood.setWaitAtResource(1);
        wood.setStep(1);
        type.gathering().put(UnitType.Resource.WOOD, wood);
        AnimationSet animations = new AnimationSet("unit-peasant");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of("frame 0", "move 32", "wait 1")));
        type.setAnimationSet(animations);
        return type;
    }

    @Test
    @DisplayName("Human 5 wood free-prefix is SW,SW onto 32,107 toward the farm order point")
    void humanFiveWoodFreePrefixStepsSouthwestTwiceTowardFarmOrderPoint() {
        // retail-human-05-idle peasant 1512: native orderXY is 31,106 under the
        // farm footprint at 30,106, free route [SW,SW] empties at 32,107, and
        // residual settle holds through fixture 35-37 before the next segment
        // toward the tree. Preferring the tree as order packed free-prefix
        // 5556 and cold-committed the third SW at fixture 35; Bresenham to
        // 31,106 packed SW,W onto 32,106.
        GameMap map = grass(120);
        map.field(29, 107).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        map.field(29, 107).setValue(100);
        // Farm 2-by-2 at 30,106 covers 30-31,106-107 (order cell 31,106).
        for (int y = 106; y <= 107; y++) {
            for (int x = 30; x <= 31; x++) {
                map.field(x, y).setFlags(
                        TileFlag.LAND_ALLOWED | TileFlag.BUILDING);
            }
        }
        World world = new World(map);
        UnitType farmType = building("unit-farm", 2, 2);
        AnimationSet farmAnim = new AnimationSet("farm");
        farmAnim.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        farmType.setAnimationSet(farmAnim);
        farmType.setHitPoints(400);
        Unit farm = world.createUnit(farmType, 0, 30, 106);
        Unit worker = world.createUnit(woodcutter(), 0, 34, 105);
        assertTrue(farm != null && worker != null, "farm and peasant place");
        assertTrue(world.orderHarvest(worker, 29, 107),
                "the peasant accepts the forest square as a wood order");
        int startX = worker.tileX();
        int startY = worker.tileY();
        for (int cycle = 0; cycle < 24
                && worker.tileX() == startX
                && worker.tileY() == startY; cycle++) {
            world.tick();
        }
        assertEquals(33, worker.tileX(),
                "first free-prefix step is south-west toward farm order 31,106");
        assertEquals(106, worker.tileY(),
                "first free-prefix step is south-west toward farm order 31,106");
        int midX = worker.tileX();
        int midY = worker.tileY();
        for (int cycle = 0; cycle < 40
                && worker.tileX() == midX
                && worker.tileY() == midY; cycle++) {
            world.tick();
        }
        assertEquals(32, worker.tileX(),
                "second free-prefix step continues south-west onto 32,107");
        assertEquals(107, worker.tileY(),
                "second free-prefix step continues south-west onto 32,107, not west onto 32,106");
        // Free prefix ends here: native route is only [SW,SW] with order 31,106.
        // A tree-ordered 5556 free-prefix would already have taken a third SW.
        assertTrue(worker.pathLength() == 0 || worker.routeSpent()
                        || worker.pathLength() <= 1,
                "farm free-prefix empties at 32,107 rather than continuing SW");
    }

    @Test
    @DisplayName("XHuman 8's peon spends its diagonal first when only the tree stops the ray")
    void xhumanEightPeonTakesTheDiagonalFirstWhenOnlyTheTreeStopsTheRay() {
        // retail-xhuman-08-idle peon 1511 plans on fixture cycle 5 from 2,67 to
        // the tree at 4,61 and stores NE,N,N,N,N,N -- the diagonal first. The
        // drawn line is N,NE,N,N,NE,N, so the stored route is the wall follow
        // native runs after 0x00450690 stops the ray on the tree itself.
        //
        // This implementation used to aim at a free neighbour and keep the ray prefix
        // whole, which stepped the peon north onto 2,66 and put the case one
        // tile out at fixture 53 for the rest of the run.
        GameMap map = grass(120);
        map.field(4, 61).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        map.field(4, 61).setValue(100);
        World world = new World(map);
        Unit worker = world.createUnit(woodcutter(), 0, 2, 67);
        assertTrue(worker != null, "the peon places on open ground");
        assertTrue(world.orderHarvest(worker, 4, 61),
                "the peon accepts the forest square as a wood order");
        java.util.List<String> walked = new java.util.ArrayList<>();
        String at = worker.tileX() + "," + worker.tileY();
        for (int cycle = 0; cycle < 200 && walked.size() < 4; cycle++) {
            world.tick();
            String now = worker.tileX() + "," + worker.tileY();
            if (!now.equals(at)) {
                walked.add(now);
                at = now;
            }
        }
        assertEquals(List.of("3,66", "3,65", "3,64", "3,63"), walked,
                "the peon must spend its diagonal at the start and then walk the"
                        + " column, the way retail stores NE,N,N,N,N,N. Aiming at a"
                        + " free neighbour instead keeps the diagonal in hand and"
                        + " spends it on the fourth step onto 4,63, which is the tile"
                        + " XHuman 8 was out by at fixture 53");
    }

    @Test
    @DisplayName("XOrc 12's wood approach prefers west clearance beside the mill")
    void xorcTwelveWoodOrderPointStepsWestNotSouthwest() {
        GameMap map = grass(40);
        // Tree at (14,29); Bresenham from (16,28) first steps south-west onto
        // (15,29). The sealed map also has the elven lumber mill at (14,30),
        // so (15,29) sits against a building while (15,28) does not. Clearance
        // keeps west -- the retail-xorc-12-idle cycle-3 answer.
        map.field(14, 29).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        map.field(14, 29).setValue(100);

        World world = new World(map);
        world.createUnit(building("unit-elven-lumber-mill", 3, 3), 0, 14, 30);
        Unit worker = world.createUnit(woodcutter(), 0, 16, 28);

        assertTrue(world.orderHarvest(worker, 14, 29),
                "the peasant must accept the forest square as a wood order");
        int startX = worker.tileX();
        int startY = worker.tileY();
        for (int cycle = 0; cycle < 20
                && worker.tileX() == startX
                && worker.tileY() == startY; cycle++) {
            world.tick();
        }
        assertEquals(15, worker.tileX(),
                "retail BNE steps west toward the clear (15,28) approach");
        assertEquals(28, worker.tileY(),
                "the south-west approach against the mill must not win");
    }

    @Test
    @DisplayName("XHuman 2's wood approach keeps Bresenham north-west when both approaches are clear")
    void xhumanTwoWoodOrderPointStepsNorthwestNotNorth() {
        GameMap map = grass(40);
        // Tree at (14,10); worker at (15,12). One-step approaches (14,11) and
        // (15,11) are both open. A distant mill at (9,11) makes (15,11) farther
        // from buildings, but native still takes Bresenham north-west -- only
        // approaches hard against a building (clearance < 2) are rejected.
        map.field(14, 10).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        map.field(14, 10).setValue(100);
        map.field(15, 10).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        map.field(15, 10).setValue(100);

        World world = new World(map);
        world.createUnit(building("unit-troll-lumber-mill", 3, 3), 0, 9, 11);
        Unit worker = world.createUnit(woodcutter(), 0, 15, 12);

        assertTrue(world.orderHarvest(worker, 14, 10),
                "the peon must accept the forest square as a wood order");
        int startX = worker.tileX();
        int startY = worker.tileY();
        for (int cycle = 0; cycle < 20
                && worker.tileX() == startX
                && worker.tileY() == startY; cycle++) {
            world.tick();
        }
        assertEquals(14, worker.tileX(),
                "retail BNE steps north-west onto (14,11) beside the tree");
        assertEquals(11, worker.tileY(),
                "a farther-from-mill north step must not beat Bresenham");
    }

    @Test
    @DisplayName("XHuman 12 peon 1497 steps south-west toward a blocked gold-mine approach")
    void xhumanTwelvePeon1497StepsSouthwestNotSouth() {
        // Sealed retail-xhuman-12-idle: peon at 76,38 harvests the gold mine
        // at 72,40. battleNetApproachPoint selects (74,40) on the mine's east
        // face -- a blocked footprint cell. Native first-steps south-west onto
        // 75,39; without preserveBlockedGoalPrefix wall-follow answers pure
        // south onto 76,39.
        GameMap map = grass(100);
        World world = new World(map);
        UnitType mineType = new UnitType("unit-gold-mine");
        mineType.setTileSize(3, 3);
        mineType.setHitPoints(25_500);
        mineType.setBuilding(true);
        mineType.setLandUnit(true);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        mineType.setCanHarvest(true);
        UnitType miner = peasant();
        ResourceInfo gold = new ResourceInfo(UnitType.Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(50);
        gold.setWaitAtDepot(50);
        miner.gathering().put(UnitType.Resource.GOLD, gold);
        AnimationSet animations = new AnimationSet("unit-peasant");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of("frame 0", "move 32", "wait 1")));
        miner.setAnimationSet(animations);

        Unit mine = world.createUnit(mineType, 15, 72, 40);
        Unit worker = world.createUnit(miner, 0, 76, 38);
        // Occupied approach ring as in the fixture (peon at 75,40).
        world.createUnit(miner, 0, 75, 40);

        assertTrue(mine != null && worker != null);
        assertTrue(world.orderHarvest(worker, mine),
                "the peon must accept the gold mine as a harvest order");
        int startX = worker.tileX();
        int startY = worker.tileY();
        for (int cycle = 0; cycle < 24
                && worker.tileX() == startX
                && worker.tileY() == startY; cycle++) {
            world.tick();
        }
        assertEquals(75, worker.tileX(),
                "retail BNE steps west one column toward the blocked mine face");
        assertEquals(39, worker.tileY(),
                "the first step is south-west onto 75,39, not pure south");
    }

    @Test
    @DisplayName("human 13 wood wall-follows west onto 51,48 instead of free-tip NW onto 51,47")
    void humanThirteenWoodWallFollowsWestOntoTheSouthTreeFace() {
        // retail-human-13-idle peon 1467: from 55,51 toward tree 50,46 the
        // free Bresenham tip is NW x4 onto 51,47, which is beside the tree it
        // was aimed at. Native stores 333 66 -- three north-west then two west
        // -- and fourth-steps west onto 51,48, on its way to 50,48 beside the
        // tree at 50,47. Fixture 53 read y 47 against oracle 48 until the wall
        // rewrite was allowed to finish beside a different cell of the same
        // forest than the free ray aimed at.
        GameMap map = grass(100);
        for (int x = 49; x <= 51; x++) {
            for (int y = 45; y <= 46; y++) {
                map.field(x, y).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
                map.field(x, y).setValue(100);
            }
        }
        for (int[] cell : new int[][] {{49, 47}, {50, 47}, {49, 48}}) {
            map.field(cell[0], cell[1])
                    .setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
            map.field(cell[0], cell[1]).setValue(100);
        }

        World world = new World(map);
        Unit worker = world.createUnit(woodcutter(), 0, 55, 51);
        assertTrue(worker != null, "peon places at 55,51");
        assertTrue(world.orderHarvest(worker, 50, 46),
                "the peon must accept tree 50,46 as a wood order");

        // Three north-west steps land on 52,48; the fourth step is the witness.
        int[][] expected = {
                {54, 50}, {53, 49}, {52, 48}, {51, 48},
        };
        for (int step = 0; step < expected.length; step++) {
            int startX = worker.tileX();
            int startY = worker.tileY();
            for (int cycle = 0; cycle < 40
                    && worker.tileX() == startX
                    && worker.tileY() == startY; cycle++) {
                world.tick();
            }
            assertEquals(expected[step][0], worker.tileX(),
                    "step " + (step + 1) + " x toward the south tree face");
            assertEquals(expected[step][1], worker.tileY(),
                    "step " + (step + 1) + " y toward the south tree face; a "
                            + "fourth north-west step is the free tip the wall "
                            + "rewrite replaces");
        }
    }


    @Test
    @DisplayName("human 8 wood wall-follows east onto 82,78 instead of free-tip SE onto 82,79")
    void humanEightWoodWallFollowsEastOntoTreeFace() {
        // retail-human-08-idle peasant 1507: from 78,75 toward tree 85,83 the
        // free Bresenham tip is SE×4 onto 82,79 (path 3333433). Native stores
        // wall-follow 333222223544 and fourth-steps east onto 82,78. Fixture
        // 52 was y 78 vs 79 until harvest preferred the wall rewrite onto the
        // opposite skirt face and skipped tip-upgrade of that detour.
        GameMap map = grass(100);
        // Forest column and tree only -- matches the live scan mass that
        // forces wall-follow around the east face. Extra coast/critter cells
        // from the live scan are not required for the path rewrite.
        for (int y = 79; y <= 84; y++) {
            map.field(85, y).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
            map.field(85, y).setValue(100);
        }
        map.field(84, 83).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        map.field(84, 84).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        map.field(86, 79).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);

        World world = new World(map);
        Unit worker = world.createUnit(woodcutter(), 0, 78, 75);
        assertTrue(worker != null, "peasant places at 78,75");
        assertTrue(world.orderHarvest(worker, 85, 83),
                "the peasant must accept tree 85,83 as a wood order");

        // Three SE steps land on 81,78; the fourth step is the witness.
        int[][] expected = {
                {79, 76}, {80, 77}, {81, 78}, {82, 78},
        };
        for (int step = 0; step < expected.length; step++) {
            int startX = worker.tileX();
            int startY = worker.tileY();
            for (int cycle = 0; cycle < 40
                    && worker.tileX() == startX
                    && worker.tileY() == startY; cycle++) {
                world.tick();
            }
            assertEquals(expected[step][0], worker.tileX(),
                    "step " + (step + 1) + " x toward the east tree face");
            assertEquals(expected[step][1], worker.tileY(),
                    "step " + (step + 1) + " y; fourth step must be east onto "
                            + "82,78 not free-tip SE onto 82,79");
        }
    }

    @Test
    @DisplayName("XHuman 8 peon 1510 keeps the tree when reverse-free is pure east")
    void xhumanEightPeon1510StepsNortheastTowardTheTree() {
        GameMap map = grass(100);
        // Tree at (9,65); peon at (4,67). Reverse-free is (8,66) and pure
        // Bresenham there first-steps east to (5,67). Native orderXY stays
        // 9,65 and first-steps north-east to (5,66).
        map.field(9, 65).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        map.field(9, 65).setValue(100);

        World world = new World(map);
        Unit worker = world.createUnit(woodcutter(), 0, 4, 67);

        assertTrue(world.orderHarvest(worker, 9, 65),
                "the peon must accept the forest square as a wood order");
        int startX = worker.tileX();
        int startY = worker.tileY();
        for (int cycle = 0; cycle < 20
                && worker.tileX() == startX
                && worker.tileY() == startY; cycle++) {
            world.tick();
        }
        assertEquals(5, worker.tileX(),
                "retail BNE steps east one column toward the tree at (9,65)");
        assertEquals(66, worker.tileY(),
                "the first step is north-east, not pure east onto (5,67)");
        int midX = worker.tileX();
        int midY = worker.tileY();
        for (int cycle = 0; cycle < 40
                && worker.tileX() == midX
                && worker.tileY() == midY; cycle++) {
            world.tick();
        }
        assertEquals(6, worker.tileX(),
                "the second step continues east toward the tree");
        assertEquals(65, worker.tileY(),
                "the second step stays north-east onto (6,65), not pure east");
        // Sealed retail-xhuman-08-idle peon 1510 (Java 90): native third step
        // is still north-east onto 7,64 (route 01 01 01 02 02). Endpoint-only
        // pack NE,NE,E,E drifted pure east onto 7,65 at fixture 38.
        int thirdX = worker.tileX();
        int thirdY = worker.tileY();
        for (int cycle = 0; cycle < 40
                && worker.tileX() == thirdX
                && worker.tileY() == thirdY; cycle++) {
            world.tick();
        }
        assertEquals(7, worker.tileX(),
                "the third step continues east toward the tree");
        assertEquals(64, worker.tileY(),
                "the third step is north-east onto (7,64), not pure east onto (7,65)");
    }

    @Test
    @DisplayName("XHuman 11 peon 1584 rewrites ally-blocked SE into east after one quiet refuse")
    void xhumanElevenPeonRewritesAllyBlockedSouthEastIntoEast() {
        // retail-xhuman-11-idle peon 1584: after NE onto 10,6 the leftover SE
        // lands on ally 11,7. Native 0x450350 rewrites NE+SE → E onto 11,6 at
        // fixture 38 after a route_index-20 quiet visit at 37.
        GameMap map = grass(30);
        map.field(20, 18).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        map.field(20, 18).setValue(100);

        World world = new World(map);
        Unit ally = world.createUnit(woodcutter(), 0, 11, 7);
        Unit worker = world.createUnit(woodcutter(), 0, 10, 6);
        assertTrue(ally != null && worker != null, "ally and peon place");
        // Leftover SE then E; last step was NE so DAT_00490e88 rewrites NE+SE
        // into E. One residual pixel remains so the first visit walks before
        // the gate and quiets on the settle (native route_index 20).
        worker.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {2, 3}));
        worker.setLastStepHeading(1);
        worker.setOffset(-1, 1);
        worker.setWalkHolding(true);
        worker.setResourceTile(20, 18);
        worker.setOrder(Unit.Order.HARVEST);
        worker.setCarrying(UnitType.Resource.WOOD);

        AnimationSet animations = worker.type().animationSet();
        worker.animation().switchTo(animations.get(AnimationSet.State.MOVE));

        // First decide visit: residual drains, ally blocks SE, quiet refuse.
        world.movement.stepMove(worker, true);
        assertEquals(10, worker.tileX(),
                "the settle visit keeps the peon on 10,6 (route_index 20)");
        assertEquals(6, worker.tileY(),
                "the settle visit does not take SE onto the ally");
        assertEquals(3, worker.peekHeading(),
                "quiet refuse must keep SE on the route, not PF_WAIT-pop it");
        assertEquals(2, worker.pathLength(),
                "quiet refuse must not spend the leftover route");

        // Second visit: shortcut E onto 11,6.
        world.movement.stepMove(worker, true);
        assertEquals(11, worker.tileX(),
                "native rewrites the blocked SE into pure east onto 11,6");
        assertEquals(6, worker.tileY(),
                "the rewritten step stays on the row, not SE onto the ally");
    }

    @Test
    @DisplayName("XHuman 8 peon 1511 keeps reverse-free when it first-steps diagonally")
    void xhumanEightPeon1511StepsNortheastViaReverseFree() {
        GameMap map = grass(100);
        // Tree at (4,61); peon at (2,67). Reverse-free is (4,62) and first
        // steps north-east to (3,66). Aiming at the tree itself first-steps
        // pure north onto (2,66), which is the cycle-5 regression.
        map.field(4, 61).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        map.field(4, 61).setValue(100);

        World world = new World(map);
        Unit worker = world.createUnit(woodcutter(), 0, 2, 67);

        assertTrue(world.orderHarvest(worker, 4, 61),
                "the peon must accept the forest square as a wood order");
        int startX = worker.tileX();
        int startY = worker.tileY();
        for (int cycle = 0; cycle < 20
                && worker.tileX() == startX
                && worker.tileY() == startY; cycle++) {
            world.tick();
        }
        assertEquals(3, worker.tileX(),
                "retail BNE steps east one column on the reverse-free ray");
        assertEquals(66, worker.tileY(),
                "the first step is north-east, not pure north onto (2,66)");
    }

    @Test
    @DisplayName("BNE's ready callback does not require an A* route to its gold depot")
    void readyGoldSelectionUsesTheNativeTerrainComponentAndCost() {
        World world = new World(grass(20));
        Unit worker = world.createUnit(peasant(), 0, 10, 10);

        UnitType depotType = building("unit-town-hall", 3, 3);
        depotType.stores().add(UnitType.Resource.GOLD);
        world.createUnit(depotType, 0, 2, 2);

        UnitType mineType = building("unit-gold-mine", 3, 3);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        Unit mine = world.createUnit(mineType, 15, 5, 5);
        mine.setResourcesHeld(25_000);

        UnitType blocker = building("unit-wall", 1, 1);
        for (int y = 9; y <= 11; y++) {
            for (int x = 9; x <= 11; x++) {
                if (x != 10 || y != 10) {
                    world.createUnit(blocker, 0, x, y);
                }
            }
        }

        assertNull(world.findResourceUnit(worker, UnitType.Resource.GOLD, 20),
                "the ordinary resource finder cannot route out of the box");
        assertSame(mine, world.findBattleNetReadyGoldMine(worker),
                "retail's ready callback only checks the terrain component");
    }

    @Test
    @DisplayName("an empty gold route makes the ready callback switch resource class")
    void failedGoldWalkFallsThroughToWoodInsteadOfAnotherMine() {
        GameMap map = grass(20);
        map.field(13, 10).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        World world = new World(map);
        world.player(0).setType(
                net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER);
        Unit worker = world.createUnit(woodcutter(), 0, 10, 10);

        UnitType depotType = building("unit-town-hall", 3, 3);
        depotType.stores().add(UnitType.Resource.GOLD);
        world.createUnit(depotType, 0, 2, 2);
        UnitType mineType = building("unit-gold-mine", 3, 3);
        mineType.setGivesResource(UnitType.Resource.GOLD);
        Unit failed = world.createUnit(mineType, 15, 5, 5);
        failed.setResourcesHeld(25_000);
        Unit alternative = world.createUnit(mineType, 15, 15, 15);
        alternative.setResourcesHeld(25_000);

        AiPlayer ai = new AiPlayer(0);
        assertTrue(ai.battleNetUnitReadyAfterResourceFailure(
                        world, worker, failed),
                "the native ready callback must find replacement work");
        assertEquals(Unit.Order.HARVEST, worker.order());
        assertEquals(UnitType.Resource.WOOD, worker.carrying(),
                "another connected gold mine must not restart the failed class");
        assertEquals(13, worker.resourceTileX());
        assertEquals(10, worker.resourceTileY());
        assertTrue(alternative.isAlive(), "the alternative mine is available");
    }

    @Test
    @DisplayName("a wall between a worker and tree is the blocked wood order point")
    void wallBetweenWorkerAndTreeBecomesTheBlockedOrderPoint() {
        GameMap map = grass(100);
        map.field(14, 89).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        map.field(13, 89).setFlags(TileFlag.WALL | TileFlag.UNPASSABLE);
        World world = new World(map);
        Unit worker = world.createUnit(woodcutter(), 0, 9, 88);

        assertArrayEquals(new int[] {13, 89},
                world.harvest.battleNetWoodOrderPoint(worker, 14, 89),
                "retail stores the last static blocker before open terrain");
    }

}
