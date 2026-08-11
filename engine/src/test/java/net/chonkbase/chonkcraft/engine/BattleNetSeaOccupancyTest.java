package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudUnitTypes;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BattleNetSeaOccupancyTest {

    private static UnitType tankerType() {
        UnitType tank = new UnitType("unit-orc-oil-tanker");
        tank.setTileSize(2, 2);
        tank.setHitPoints(90);
        tank.setSpeed(32);
        tank.setSeaUnit(true);
        ResourceInfo oil = new ResourceInfo(UnitType.Resource.OIL);
        oil.setCapacity(100);
        oil.setWaitAtResource(100);
        oil.setWaitAtDepot(100);
        tank.gathering().put(UnitType.Resource.OIL, oil);
        AnimationSet animations = new AnimationSet("tanker");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of("frame 0", "move 32", "wait 1")));
        tank.setAnimationSet(animations);
        return tank;
    }

    private static UnitType destroyerType() {
        UnitType ship = new UnitType("unit-orc-destroyer");
        ship.setTileSize(2, 2);
        ship.setHitPoints(100);
        ship.setSpeed(32);
        ship.setSeaUnit(true);
        ship.setCanAttack(true);
        AnimationSet animations = new AnimationSet("destroyer");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of("frame 0", "move 32", "wait 1")));
        ship.setAnimationSet(animations);
        return ship;
    }

    private static World openWater() {
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        World world = new World(map);
        return world;
    }

    @Test
    @DisplayName("one south-east double-step clears the vacated 2-by-2 footprint")
    void oneDiagonalDoubleStepClearsOccupancy() {
        World world = openWater();
        Unit boat = world.createUnit(tankerType(), 0, 10, 10);
        // Match XHuman 7 geometry: even-even origin, SE double step to 12,12
        assertTrue(world.orderMove(boat, 12, 12));
        for (int i = 0; i < 20 && boat.tileX() == 10 && boat.tileY() == 10; i++) {
            world.tick();
        }
        for (int y = 10; y <= 11; y++) {
            for (int x = 10; x <= 11; x++) {
                long flags = world.map().field(x, y).flags();
                assertEquals(0, flags & TileFlag.SEA_UNIT,
                        "vacated " + x + "," + y + " must not keep SEA_UNIT");
            }
        }
        assertTrue(boat.tileX() >= 12 || boat.tileY() >= 12,
                "tanker must leave 10,10");
    }

    @Test
    @DisplayName("a destroyer keeps Patrol while residual pixels drain a multi-step naval route")
    void aDestroyerKeepsPatrolWhileAMultiStepNavalRouteRemains() {
        // XOrc 11 destroyer 1542 (Java 58) sits at (10,24) with leftover SE
        // headings under native action 5 through fixtures 21..39 while residual
        // pixels still slide. Mid-residual autoAttack used to promote
        // ATTACK_MOVE at fixture 25 while the sealed order field stayed Patrol.
        // Residual-settled acquisition is a different visit (see the settle
        // test below); this test only covers the mid-residual silence.
        World world = openWater();
        UnitType destroyerType = destroyerType();
        destroyerType.setCanTargetSea(true);
        destroyerType.setMaxAttackRange(4);
        destroyerType.setReactRangeComputer(8);
        destroyerType.setReactRangePerson(8);
        destroyerType.setSightRange(8);
        destroyerType.setBasicDamage(10);
        Unit destroyer = world.createUnit(destroyerType, 0, 10, 10);
        UnitType preyType = tankerType();
        preyType.setCanAttack(false);
        Unit prey = world.createUnit(preyType, 1, 20, 20);
        assertTrue(destroyer != null && prey != null,
                "destroyer and prey must place");
        assertTrue(destroyer.battleNetDoubleStep(),
                "destroyer is a two-tile ship");
        assertTrue(world.orderPatrol(destroyer, 30, 30),
                "destroyer accepts a far patrol");
        // Multi-step leftover SE corridor like native route 03 03 03...
        int se = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(1, 1);
        destroyer.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {se, se, se, se, se, se}));
        // Mid-residual of the first double-step: non-zero offsets keep
        // isMoving true so acquisition stays silent.
        destroyer.setOffset(-16, -16);
        destroyer.setWalkHolding(false);
        destroyer.setBattleNetOrderDelay(0);
        destroyer.setAttackScanSleep(0);

        boolean leftPatrol = false;
        for (int call = 0; call < 12; call++) {
            if (destroyer.pathLength() == 0
                    && destroyer.order() == Unit.Order.PATROL) {
                destroyer.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                        net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                        new int[] {se, se, se, se, se, se}));
            }
            // Re-arm residual so the visit stays mid-slide for the whole
            // window; walkPixels would otherwise drain offsets to zero.
            if (destroyer.offsetX() == 0 && destroyer.offsetY() == 0) {
                destroyer.setOffset(-16, -16);
            }
            world.tick();
            if (destroyer.order() != Unit.Order.PATROL) {
                leftPatrol = true;
                break;
            }
        }
        assertTrue(destroyer.pathLength() > 0 || destroyer.order() == Unit.Order.PATROL,
                "patrol route must remain available for the mid-residual check");
        assertTrue(!leftPatrol && destroyer.order() == Unit.Order.PATROL,
                "mid-residual naval patrol must keep Patrol, not promote AttackMove");
        assertEquals(Unit.Order.PATROL, destroyer.currentAction(),
                "reported current action must stay Patrol while residual drains");
    }

    @Test
    @DisplayName("a residual-settled destroyer patrol acquires instead of free-consuming leftover SE")
    void aResidualSettledDestroyerPatrolAcquiresInsteadOfFreeConsumingLeftoverSe() {
        // XOrc 11 destroyer 1542 at (10,24): residual of SE@8 settles at
        // fixture 40 with leftover SE headings onto empty (12,26). Native
        // queues Attack (next_order 12, order_point toward hostile), installs
        // a SW route and holds; Java used to free-step SE because multi-step
        // leftovers silenced autoAttack entirely.
        World world = openWater();
        UnitType destroyerType = destroyerType();
        destroyerType.setCanTargetSea(true);
        destroyerType.setMaxAttackRange(4);
        destroyerType.setReactRangeComputer(12);
        destroyerType.setReactRangePerson(12);
        destroyerType.setSightRange(12);
        destroyerType.setBasicDamage(10);
        Unit destroyer = world.createUnit(destroyerType, 0, 10, 10);
        UnitType preyType = tankerType();
        preyType.setCanAttack(false);
        // Hostile inside react range so residual-settled autoAttack finds it.
        Unit prey = world.createUnit(preyType, 1, 4, 18);
        assertTrue(destroyer != null && prey != null,
                "destroyer and prey must place");
        assertTrue(destroyer.battleNetDoubleStep(),
                "destroyer is a two-tile ship");
        assertTrue(world.orderPatrol(destroyer, 30, 30),
                "destroyer accepts a far patrol");
        int se = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(1, 1);
        destroyer.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {se, se, se, se, se, se}));
        destroyer.setOffset(0, 0);
        destroyer.setWalkHolding(false);
        destroyer.setStepDrained(true);
        destroyer.setBattleNetOrderDelay(0);
        destroyer.setAttackScanSleep(0);

        int startX = destroyer.tileX();
        int startY = destroyer.tileY();
        world.tick();
        assertEquals(startX, destroyer.tileX(),
                "residual-settled acquisition must not free-step SE on leftover; x="
                        + destroyer.tileX());
        assertEquals(startY, destroyer.tileY(),
                "residual-settled acquisition must not free-step SE on leftover; y="
                        + destroyer.tileY());
        assertEquals(Unit.Order.PATROL, destroyer.order(),
                "residual-settled acquisition holds Patrol while the native timer runs");
        assertTrue(destroyer.pendingAttack() != null
                        || destroyer.battleNetOrderDelay() > 0,
                "residual-settled naval patrol must queue Attack and arm the hold delay");
        // Through the hold the destroyer must stay on its tile under Patrol
        // (native 1542 fixtures 40..54) rather than promote Attack a cycle early.
        for (int hold = 0; hold < 14; hold++) {
            world.tick();
            assertEquals(Unit.Order.PATROL, destroyer.order(),
                    "hold visit " + hold + " must keep Patrol; order="
                            + destroyer.order());
            assertEquals(startX, destroyer.tileX(),
                    "hold visit " + hold + " must not leave the settle tile; x="
                            + destroyer.tileX());
            assertEquals(startY, destroyer.tileY(),
                    "hold visit " + hold + " must not leave the settle tile; y="
                            + destroyer.tileY());
        }
    }

    @Test
    @DisplayName("a double-step multi-step leftover free-closers onto a better corridor")
    void aDoubleStepMultiStepLeftoverFreeClosersOntoABetterCorridor() {
        // XOrc 11 destroyer 1558: wall-follow leftover EESESW after tanker
        // hard-blocked SE at plan time. After first E onto (6,18), free E to
        // (8,18) is worse than free SE to (8,20) toward patrol goal 21,34.
        // Native second-steps SE at fixture 40.
        World world = openWater();
        Unit destroyer = world.createUnit(destroyerType(), 0, 6, 18);
        assertTrue(destroyer != null && destroyer.battleNetDoubleStep(),
                "destroyer must place as a two-tile ship");
        assertTrue(world.orderPatrol(destroyer, 22, 34),
                "destroyer accepts the far patrol goal");
        int east = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(1, 0);
        int se = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(1, 1);
        int sw = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(-1, 1);
        // Leftover after first E: E,SE,SW -- reverse storage last = next.
        destroyer.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {sw, se, east}));
        destroyer.setOffset(0, 0);
        destroyer.setWalkHolding(false);
        destroyer.setStepDrained(true);
        destroyer.setBattleNetOrderDelay(0);
        destroyer.setBattleNetBorrowedMoveForStep(true);
        try {
            world.movement.stepMove(destroyer, true);
        } finally {
            destroyer.setBattleNetBorrowedMoveForStep(false);
        }
        assertEquals(8, destroyer.tileX(),
                "free-closer must step east onto the SE cell's column");
        assertEquals(20, destroyer.tileY(),
                "free-closer must step south onto the SE cell, not pure E; y="
                        + destroyer.tileY());
    }

    @Test
    @DisplayName("a residual-settled destroyer holds a blocked leftover for fifteen beats")
    void aResidualSettledDestroyerHoldsABlockedLeftoverForFifteenBeats() {
        // XORc 8 destroyer 1431: residual of N@6 settles at 102,90 with
        // leftover NW onto sub 1432 at 100,88. Native arms Move timer 15 and
        // keeps the route through c52; clearPath re-query stepped NW the
        // cycle the sub left (fixture 51 vs native 53).
        World world = openWater();
        Unit destroyer = world.createUnit(destroyerType(), 0, 10, 10);
        Unit blocker = world.createUnit(tankerType(), 0, 8, 8);
        assertTrue(destroyer != null && blocker != null
                        && destroyer.battleNetDoubleStep(),
                "destroyer and ally tanker place");
        assertTrue(world.orderPatrol(destroyer, 4, 4),
                "destroyer accepts a north-west patrol");
        int nw = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(-1, -1);
        int north = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(0, -1);
        // Leftover after first N: NW (stack last = next).
        destroyer.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {nw, north}));
        // Pop the N so only NW remains, residual already settled.
        destroyer.popHeading();
        destroyer.setOffset(0, 0);
        destroyer.setWalkHolding(false);
        destroyer.setStepDrained(true);
        destroyer.setBattleNetOrderDelay(0);
        int startX = destroyer.tileX();
        int startY = destroyer.tileY();
        int pathnBefore = destroyer.pathLength();
        world.movement.stepMove(destroyer, true);
        assertEquals(startX, destroyer.tileX(),
                "blocked residual leftover must not step NW same visit");
        assertEquals(startY, destroyer.tileY(),
                "blocked residual leftover must not step NW same visit");
        assertEquals(pathnBefore, destroyer.pathLength(),
                "route must stay (clearPath re-query stepped early)");
        assertTrue(destroyer.battleNetOrderDelay() >= 10,
                "native Move timer 15 / order delay 14 on residual refuse");
        for (int hold = 0; hold < 12; hold++) {
            world.movement.stepMove(destroyer, true);
            assertEquals(startX, destroyer.tileX(),
                    "hold visit " + hold + " must not leave the settle tile; x="
                            + destroyer.tileX());
            assertEquals(startY, destroyer.tileY(),
                    "hold visit " + hold + " must not leave the settle tile; y="
                            + destroyer.tileY());
        }
    }

    @Test
    @DisplayName("a destroyer replans west after a tanker vacates the corridor")
    void destroyerReplansWestAfterTankerLeaves() {
        // XHuman 7: tanker at 26,26 and destroyer at 28,26 share the even
        // grid. BNE walks high pool ids first, so the destroyer acts while
        // the tanker still occupies the west corridor, then the tanker steps
        // south-east later in the same cycle. A ten-cycle PF_WAIT on that
        // refused detour made the destroyer miss the west step native takes
        // once the tanker has gone.
        World world = openWater();
        Unit tanker = world.createUnit(tankerType(), 0, 10, 10);
        Unit destroyer = world.createUnit(destroyerType(), 0, 12, 10);
        assertTrue(tanker.battleNetDoubleStep(), "tanker is a two-tile ship");
        assertTrue(destroyer.battleNetDoubleStep(),
                "destroyer is a two-tile ship");
        assertTrue(world.orderMove(tanker, 14, 14),
                "tanker accepts the south-east depart");
        assertTrue(world.orderPatrol(destroyer, 4, 10),
                "destroyer accepts a west patrol");
        // Allow the destroyer's first refused visit, the tanker's depart, and
        // the destroyer's replan. A stuck PF_WAIT of ten never leaves 12,10.
        boolean steppedOntoTankerSquare = false;
        for (int i = 0; i < 12; i++) {
            world.tick();
            if (destroyer.tileX() == 10 && destroyer.tileY() == 10) {
                steppedOntoTankerSquare = true;
            }
            if (destroyer.tileX() < 12 && destroyer.tileY() == 10
                    && (tanker.tileX() != 10 || tanker.tileY() != 10)) {
                steppedOntoTankerSquare = true;
                break;
            }
        }
        assertTrue(destroyer.tileX() < 12,
                "destroyer must leave 12,10 west after the corridor clears");
        assertEquals(10, destroyer.tileY(),
                "destroyer keeps its row while stepping west");
        assertTrue(steppedOntoTankerSquare
                        || destroyer.tileX() <= 10,
                "destroyer must replan onto or past the vacated tanker square");
        assertTrue(tanker.tileX() > 10 || tanker.tileY() > 10,
                "tanker must have left 10,10 before the destroyer advances");
    }

    @Test
    @DisplayName("destroyer without tanker keeps self near and neutral platform far")
    void destroyerPatrolsTowardNeutralOilPlatform() {
        // XHuman 8 destroyer 1480: no tanker for the owner, near stays self
        // (34,82), far is the neutral oil square at (41,85). The open-water
        // wiggle used to invent near = west and fail into Still at cycle 9
        // while native stayed on Patrol after the at-self endpoint swap.
        // XOrc 10's west step is the first stride toward far platform 99,79.
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int index = 0; index < players.length; index++) {
            players[index] = new Player(index,
                    index == 0 ? PudMap.PlayerType.COMPUTER
                            : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.enableAi(0);
        UnitType platformType = new UnitType("unit-orc-oil-platform");
        platformType.setTileSize(1, 1);
        platformType.setHitPoints(650);
        platformType.setSeaUnit(true);
        AnimationSet platformAnim = new AnimationSet("oil-platform");
        platformAnim.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        platformType.setAnimationSet(platformAnim);
        // Platform west of the ship (mirrors XOrc 10 neutral far 99,79 and
        // XHuman 8 far 41,85). Ownership does not matter once the global
        // oil-platform pass runs; player 0 keeps placement simple.
        Unit platform = world.createUnit(platformType, 0, 10, 18);
        Unit destroyer = world.createUnit(destroyerType(), 0, 20, 20);
        destroyer.setBattleNetAnimationTimer(1);
        world.fireBattleNetReadyForAll();
        assertTrue(platform != null && destroyer != null,
                "platform and destroyer must place on open water");
        assertTrue(destroyer.hasBattleNetPendingPatrol(),
                "ready pass must queue a destroyer patrol");
        assertEquals(20, destroyer.battleNetPendingPatrolX(),
                "near endpoint is the destroyer's own square without a tanker");
        assertEquals(20, destroyer.battleNetPendingPatrolY(),
                "near endpoint is the destroyer's own square without a tanker");
        assertEquals(10, destroyer.battleNetPendingPatrolBackX(),
                "far endpoint is the neutral oil platform");
        assertEquals(18, destroyer.battleNetPendingPatrolBackY(),
                "far endpoint is the neutral oil platform");
        // Promote the pending patrol, swap at self, then step toward the
        // platform. Native holds three animation ticks before the first
        // logical step; the important property is Patrol stays live and the
        // ship eventually leaves start toward the far oil square.
        boolean leftStart = false;
        for (int i = 0; i < 40; i++) {
            world.tick();
            if (destroyer.order() == Unit.Order.STILL
                    && destroyer.tileX() == 20 && destroyer.tileY() == 20) {
                // Must not collapse to Still while still sitting on start.
                break;
            }
            if (destroyer.tileX() != 20 || destroyer.tileY() != 20) {
                leftStart = true;
                break;
            }
        }
        assertTrue(leftStart,
                "destroyer must leave start toward the far oil platform");
        assertTrue(destroyer.tileX() < 20,
                "first departure is west toward the platform at 10,18");
        assertNotEquals(Unit.Order.STILL, destroyer.order(),
                "patrol must not drop to Still on the at-self near goal");
    }

    @Test
    @DisplayName("destroyer far endpoint prefers oil patch over foreign platform")
    void destroyerFarPrefersOilPatchOverForeignPlatform() {
        // XHuman 8: destroyer 1480 (p3) has far = oil-patch (41,85) type 93
        // / p15, not the enemy platform at (67,55). Owned platforms still
        // win when present (XOrc 8/10); with none, patches beat foreign
        // platforms so the patrol opens east to 36,82 rather than NE.
        GameMap map = new GameMap(80, 80, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int index = 0; index < players.length; index++) {
            players[index] = new Player(index,
                    index <= 1 ? PudMap.PlayerType.COMPUTER
                            : PudMap.PlayerType.NOBODY,
                    PudMap.Race.ORC);
        }
        World world = new World(map, players);
        world.enableAi(0);
        UnitType platformType = new UnitType("unit-orc-oil-platform");
        platformType.setTileSize(2, 2);
        platformType.setHitPoints(650);
        platformType.setSeaUnit(true);
        AnimationSet platformAnim = new AnimationSet("oil-platform");
        platformAnim.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        platformType.setAnimationSet(platformAnim);
        UnitType patchType = new UnitType("unit-oil-patch");
        patchType.setTileSize(1, 1);
        patchType.setHitPoints(1);
        patchType.setSeaUnit(true);
        AnimationSet patchAnim = new AnimationSet("oil-patch");
        patchAnim.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        patchType.setAnimationSet(patchAnim);
        // Foreign platform (player 1) and a neutral-style patch on player 1
        // as well -- ownership of the patch does not matter once owned
        // platforms are empty for the destroyer's player.
        Unit foreignPlatform = world.createUnit(platformType, 1, 50, 30);
        Unit nearPatch = world.createUnit(patchType, 1, 28, 24);
        Unit destroyer = world.createUnit(destroyerType(), 0, 20, 20);
        destroyer.setBattleNetAnimationTimer(1);
        world.fireBattleNetReadyForAll();
        assertTrue(foreignPlatform != null && nearPatch != null
                        && destroyer != null,
                "platform, patch, and destroyer must place on open water");
        assertTrue(destroyer.hasBattleNetPendingPatrol(),
                "ready pass must queue a destroyer patrol");
        assertEquals(28, destroyer.battleNetPendingPatrolBackX(),
                "far endpoint is the oil patch, not the foreign platform");
        assertEquals(24, destroyer.battleNetPendingPatrolBackY(),
                "far endpoint is the oil patch, not the foreign platform");
    }

    private static UnitType battleshipType() {
        UnitType ship = new UnitType("unit-battleship");
        ship.setTileSize(2, 2);
        ship.setHitPoints(150);
        ship.setSpeed(32);
        ship.setSeaUnit(true);
        ship.setCanAttack(true);
        AnimationSet animations = new AnimationSet("battleship");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of("frame 0", "move 32", "wait 1")));
        ship.setAnimationSet(animations);
        return ship;
    }

    @Test
    @DisplayName("battleship keeps wall-follow detour west when pure north is free")
    void battleshipPrefersDetourCardinalWhenPureRayIsFree() {
        // XOrc 11 unit 1511 at 20,40 plans north to the shipyard; the open
        // Bresenham ray is pure north and free for one stride, but the
        // pathfinder wall-follows north-west around a later tanker. Native
        // steps pure west (18,40) rather than the diagonal (18,38).
        World world = openWater();
        // Even lattice inside the 40x40 open-water map (y < 40).
        Unit battleship = world.createUnit(battleshipType(), 0, 20, 30);
        Unit tanker = world.createUnit(tankerType(), 0, 20, 24);
        assertTrue(battleship != null && tanker != null,
                "ships must place on open water");
        assertTrue(battleship.battleNetDoubleStep(),
                "battleship is a two-tile ship");
        // Goal north of the ship; tanker sits on the even-snapped approach.
        assertTrue(world.orderPatrol(battleship, 21, 24),
                "battleship accepts a northbound patrol");
        for (int i = 0; i < 16
                && battleship.tileX() == 20 && battleship.tileY() == 30; i++) {
            world.tick();
        }
        assertEquals(18, battleship.tileX(),
                "battleship must take the wall-follow west detour");
        assertEquals(30, battleship.tileY(),
                "battleship must not also step north on the first diagonal");
        assertTrue(tanker.isOnMap(), "tanker remains as the corridor obstacle");
    }

    @Test
    @DisplayName("unsuppressed combat flyers patrol on their ready marker")
    void unsuppressedCombatFlyerPatrolsOnReadyMarker() {
        // XOrc 8 gryphons (UNIT.Data zero) flip STILL→PATROL on constructor
        // markers while holding their start tile. XOrc 7 gryphons carry
        // UNIT.Data and must keep Still.
        GameMap map = new GameMap(32, 32, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(
                        TileFlag.LAND_ALLOWED | TileFlag.WATER_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int index = 0; index < players.length; index++) {
            players[index] = new Player(index,
                    index == 0 ? PudMap.PlayerType.COMPUTER
                            : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.enableAi(0);
        UnitType gryphon = new UnitType("unit-gryphon-rider");
        gryphon.setTileSize(1, 1);
        gryphon.setHitPoints(100);
        gryphon.setSpeed(32);
        gryphon.setAirUnit(true);
        gryphon.setCanAttack(true);
        AnimationSet animations = new AnimationSet("gryphon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of("frame 0", "move 32", "wait 1")));
        gryphon.setAnimationSet(animations);

        Unit free = world.createUnit(gryphon, 0, 4, 6);
        free.setBattleNetAnimationTimer(1);
        free.setBattleNetReadySuppressed(false);
        Unit guarded = world.createUnit(gryphon, 0, 8, 6);
        guarded.setBattleNetAnimationTimer(1);
        guarded.setBattleNetReadySuppressed(true);

        for (int i = 0; i < 12 && free.order() != Unit.Order.PATROL; i++) {
            world.tick();
        }
        assertEquals(Unit.Order.PATROL, free.order(),
                "unsuppressed combat flyer promotes to Patrol");
        assertEquals(4, free.tileX(),
                "startup flyer patrol holds the start tile");
        assertEquals(6, free.tileY(),
                "startup flyer patrol holds the start tile");
        assertEquals(Unit.Order.STILL, guarded.order(),
                "UNIT.Data-suppressed flyer keeps its guard Still");
    }

    @Test
    @DisplayName("a self-patrol flyer takes a second scout double-step after residual settles")
    void selfPatrolFlyerTakesSecondScoutDoubleStepAfterResidualSettles() {
        // XOrc 11 gryphon 1589 self-patrols from 42,4: first double-step south
        // onto 42,6, then the next free residual-zero visit continues south
        // onto 42,8. Writing the old tile as the far endpoint used to bounce
        // back to 42,4; draining residual only inside walkTowards skipped the
        // free-visit arm and stepped one cycle late (or never).
        GameMap map = new GameMap(64, 32, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(
                        TileFlag.LAND_ALLOWED | TileFlag.WATER_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int index = 0; index < players.length; index++) {
            players[index] = new Player(index,
                    index == 0 ? PudMap.PlayerType.COMPUTER
                            : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.enableAi(0);
        UnitType gryphon = new UnitType("unit-gryphon-rider");
        // Retail gryphon TileSize is 2x2, which arms battleNetDoubleStep.
        gryphon.setTileSize(2, 2);
        gryphon.setHitPoints(100);
        gryphon.setSpeed(32);
        gryphon.setAirUnit(true);
        gryphon.setCanAttack(true);
        gryphon.setOnReadyExplores(true);
        AnimationSet animations = new AnimationSet("gryphon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of("frame 0", "move 32", "wait 1")));
        gryphon.setAnimationSet(animations);

        Unit rider = world.createUnit(gryphon, 0, 42, 4);
        assertTrue(rider.battleNetDoubleStep(),
                "2x2 flyer must arm double-step stride");
        rider.setBattleNetAnimationTimer(1);
        rider.setBattleNetReadySuppressed(false);
        rider.setHeading(2); // east facing must not block south scout
        // Self-patrol: both endpoints on the start tile so the free scout arm
        // invents the next double-step rather than bouncing a two-point beat.
        assertTrue(world.orderPatrol(rider, 42, 4),
                "self-patrol on the start tile is accepted");
        rider.setPatrol(42, 4);
        rider.setBattleNetOrderDelay(0);

        int firstY = -1;
        int secondY = -1;
        for (int i = 0; i < 120; i++) {
            world.tick();
            if (rider.order() != Unit.Order.PATROL) {
                continue;
            }
            if (firstY < 0 && rider.tileY() > 4) {
                firstY = rider.tileY();
                assertEquals(42, rider.tileX(),
                        "first scout double-step keeps the column");
                assertEquals(6, firstY,
                        "north-edge flyer first-steps two tiles south");
            } else if (firstY >= 0 && rider.tileY() > firstY) {
                secondY = rider.tileY();
                break;
            } else if (firstY >= 0 && rider.tileY() < firstY) {
                // Bounce north is the bug this test must catch.
                secondY = rider.tileY();
                break;
            }
        }
        assertEquals(6, firstY,
                "self-patrol flyer must leave the start tile south");
        assertEquals(8, secondY,
                "second scout double-step continues south, not bounce north");
        assertEquals(42, rider.tileX(),
                "scout line stays on the start column");
    }

    @Test
    @DisplayName("a mid-journey flyer settles pathn-zero residual and continues the patrol")
    void aMidJourneyFlyerSettlesPathnZeroResidualAndContinuesThePatrol() {
        // XORc 8 balloon 1452: short pathfinder prefix E then SE lands on
        // (82,70) with pathn 0 while residual still drains. Residual drain
        // used to require tile==orderTarget (self-patrol endpoint only), so
        // mid-journey balloons never cleared isMoving and never replanned;
        // native double-stepped SE to (84,72) at fixture 42. Drain any fly
        // pathn-0 residual and replan the patrol toward the far endpoint.
        GameMap map = new GameMap(64, 64, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(
                        TileFlag.LAND_ALLOWED | TileFlag.WATER_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int index = 0; index < players.length; index++) {
            players[index] = new Player(index,
                    index == 0 ? PudMap.PlayerType.COMPUTER
                            : PudMap.PlayerType.NOBODY,
                    PudMap.Race.ORC);
        }
        World world = new World(map, players);
        UnitType balloon = new UnitType("unit-balloon");
        balloon.setTileSize(2, 2);
        balloon.setHitPoints(150);
        balloon.setSpeed(32);
        balloon.setAirUnit(true);
        balloon.setCanAttack(false);
        AnimationSet animations = new AnimationSet("balloon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        // Fast residual drain: 32+32 covers a double-step prime quickly.
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of(
                        "unbreakable begin", "frame 0", "move 32", "wait 1",
                        "frame 0", "move 32", "unbreakable end", "wait 1")));
        balloon.setAnimationSet(animations);

        Unit flyer = world.createUnit(balloon, 0, 20, 20);
        assertTrue(flyer != null && flyer.battleNetDoubleStep(),
                "2x2 balloon arms double-step");
        assertTrue(world.orderPatrol(flyer, 40, 40),
                "far patrol endpoint accepted");
        // Mid-journey: just landed a double-step SE with path spent, residual
        // of that step still owed, far from order target (40,40).
        int se = Direction.fromDelta(1, 1);
        flyer.setTile(20, 20);
        flyer.setPath(new PathFinder.Path(PathFinder.Result.FOUND, new int[0]));
        flyer.setRouteSpent(true);
        flyer.setLastStepHeading(se);
        flyer.setOffset(-64, -64);
        flyer.setResidual(0, 0);
        flyer.setWalkHolding(true);
        flyer.setStepDrained(false);
        flyer.setBattleNetOrderDelay(0);
        flyer.animation().switchTo(animations.get(AnimationSet.State.MOVE));

        // Mid-journey residual arm must free the balloon to replan. Endpoint-
        // only residual drain left XORc 8 1452 stuck on (82,70) through
        // fixture 50 while native SE@42. Fixture case is the timed proof;
        // this asserts the replan path is reachable after pathn-0 residual.
        Integer continued = null;
        for (int i = 0; i < 40; i++) {
            world.tick();
            if (flyer.tileX() != 20 || flyer.tileY() != 20) {
                continued = i;
                break;
            }
        }
        assertTrue(continued != null,
                "mid-journey pathn-0 residual must settle and replan off 20,20");
        assertTrue(flyer.tileX() >= 20 && flyer.tileY() >= 20,
                "continues toward far SE patrol endpoint; at "
                        + flyer.tileX() + "," + flyer.tileY());
    }

    @Test
    @DisplayName("a west-edge self-patrol flyer scouts south-west when an ally holds the pure-west stride")
    void westEdgeSelfPatrolFlyerScoutsSouthwestWhenAllyHoldsPureWest() {
        // XOrc 8 gryphon 1550 residual-settled at 2,6 after its first west
        // scout. The free invent prefers pure west onto 0,6, but ally 1560
        // already holds that air footprint; native invents free SW to 0,8.
        // Assert the invented order point itself (not a later wall-follow
        // tile), so a blocked west invent that pathfinds around cannot pass.
        GameMap map = new GameMap(32, 32, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(
                        TileFlag.LAND_ALLOWED | TileFlag.WATER_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int index = 0; index < players.length; index++) {
            players[index] = new Player(index,
                    index == 0 ? PudMap.PlayerType.COMPUTER
                            : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.enableAi(0);
        UnitType gryphon = new UnitType("unit-gryphon-rider");
        gryphon.setTileSize(2, 2);
        gryphon.setHitPoints(100);
        gryphon.setSpeed(32);
        gryphon.setAirUnit(true);
        gryphon.setCanAttack(true);
        gryphon.setOnReadyExplores(true);
        AnimationSet animations = new AnimationSet("gryphon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of("frame 0", "move 32", "wait 1")));
        gryphon.setAnimationSet(animations);

        Unit ally = world.createUnit(gryphon, 0, 0, 6);
        ally.setBattleNetAnimationTimer(1);
        ally.setBattleNetReadySuppressed(true);
        ally.setOrder(Unit.Order.STILL);

        Unit rider = world.createUnit(gryphon, 0, 2, 6);
        assertTrue(rider.battleNetDoubleStep(),
                "2x2 flyer must arm double-step stride");
        rider.setBattleNetAnimationTimer(1);
        rider.setBattleNetReadySuppressed(false);
        rider.setHeading(6); // west -- preferred west-edge invent
        rider.setOffset(0, 0);
        rider.clearPath();
        // Self-patrol settled on the free endpoint after the first west leg.
        assertTrue(world.orderPatrol(rider, 2, 6),
                "self-patrol on the residual-settled tile is accepted");
        rider.setPatrol(2, 6);
        rider.setOrderTarget(2, 6);
        rider.setBattleNetOrderDelay(0);
        // One free visit short of the invent threshold so the next tick steps.
        rider.setBattleNetSelfPatrolHolds(5);

        int inventedX = -1;
        int inventedY = -1;
        for (int i = 0; i < 8; i++) {
            world.tick();
            if (rider.orderTargetX() != 2 || rider.orderTargetY() != 6) {
                inventedX = rider.orderTargetX();
                inventedY = rider.orderTargetY();
                break;
            }
        }
        assertEquals(0, inventedX,
                "free scout invent still moves two tiles west");
        assertEquals(8, inventedY,
                "ally on pure-west stride invents free south-west, not blocked west");
        assertEquals(0, ally.tileX(), "ally stays on the blocked pure-west tile");
        assertEquals(6, ally.tileY(), "ally stays on the blocked pure-west tile");
    }

    @Test
    @DisplayName("a corner self-patrol flyer stands down when preferred scout strides leave the map")
    void cornerSelfPatrolFlyerStandsDownWhenPreferredScoutStridesLeaveTheMap() {
        // XOrc 8 gryphon 1560 residual-settles at 0,6 after its first SW leg.
        // Edge invent prefers west; west/SW/NW double-steps leave the map.
        // Native promotes Still at fixture 38 rather than free-ring scouting
        // east or south.
        GameMap map = new GameMap(32, 32, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(
                        TileFlag.LAND_ALLOWED | TileFlag.WATER_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int index = 0; index < players.length; index++) {
            players[index] = new Player(index,
                    index == 0 ? PudMap.PlayerType.COMPUTER
                            : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.enableAi(0);
        UnitType gryphon = new UnitType("unit-gryphon-rider");
        gryphon.setTileSize(2, 2);
        gryphon.setHitPoints(100);
        gryphon.setSpeed(32);
        gryphon.setAirUnit(true);
        gryphon.setCanAttack(true);
        // Combat flyers are not OnReady = AiExploreUnit. Leaving explores true
        // skipped the unit-ready self-patrol re-arm path this case must cover.
        gryphon.setOnReadyExplores(false);
        AnimationSet animations = new AnimationSet("gryphon");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of("frame 0", "move 32", "wait 1")));
        gryphon.setAnimationSet(animations);

        Unit rider = world.createUnit(gryphon, 0, 0, 6);
        assertTrue(rider.battleNetDoubleStep(),
                "2x2 flyer must arm double-step stride");
        rider.setBattleNetAnimationTimer(1);
        rider.setBattleNetReadySuppressed(false);
        rider.setHeading(5); // south-west -- last leg into the corner
        rider.setOffset(0, 0);
        rider.clearPath();
        assertTrue(world.orderPatrol(rider, 0, 6),
                "self-patrol on the residual-settled corner tile is accepted");
        rider.setPatrol(0, 6);
        rider.setOrderTarget(0, 6);
        rider.setBattleNetOrderDelay(0);
        rider.setBattleNetSelfPatrolHolds(5);

        boolean sawStill = false;
        for (int i = 0; i < 8; i++) {
            world.tick();
            if (rider.order() == Unit.Order.STILL) {
                sawStill = true;
                break;
            }
            assertEquals(0, rider.tileX(),
                    "corner flyer must not free-ring scout off the invent trio");
            assertEquals(6, rider.tileY(),
                    "corner flyer must not free-ring scout off the invent trio");
        }
        assertTrue(sawStill,
                "no free preferred/neighbour stride ends the self-patrol scout");
        assertEquals(0, rider.tileX(), "corner flyer stands on the settled tile");
        assertEquals(6, rider.tileY(), "corner flyer stands on the settled tile");
        // XOrc 8 1560 stays Still through fixture 51 after invent-fail. Without
        // the exhaust gate, unit-ready re-armed self-patrol as the live order
        // within a few Still visits (Patrol at fixture 44).
        for (int i = 0; i < 12; i++) {
            world.tick();
            assertEquals(Unit.Order.STILL, rider.order(),
                    "exhausted self-scout must not re-arm Patrol as the live order");
            assertEquals(0, rider.tileX(),
                    "exhausted corner flyer stays on the settled tile");
            assertEquals(6, rider.tileY(),
                    "exhausted corner flyer stays on the settled tile");
        }
    }

    @Test
    @DisplayName("a destroyer does not chase an out-of-range balloon")
    void destroyerDoesNotChaseOutOfRangeBalloon() {
        // Human 9: destroyers at 26,38 acquire the balloon at 32,42 (react
        // range 10, weapon range 4), hold Attack for the order-delay window,
        // then return to Still without stepping. Chasing double-stepped SE.
        World world = openWater();
        UnitType destroyer = destroyerType();
        destroyer.setCanTargetAir(true);
        destroyer.setMaxAttackRange(4);
        destroyer.setReactRangeComputer(10);
        destroyer.setReactRangePerson(8);
        UnitType balloon = new UnitType("unit-balloon");
        balloon.setTileSize(1, 1);
        balloon.setHitPoints(150);
        balloon.setAirUnit(true);
        balloon.setSpeed(0);
        AnimationSet balloonAnim = new AnimationSet("balloon");
        balloonAnim.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        balloon.setAnimationSet(balloonAnim);

        Unit ship = world.createUnit(destroyer, 0, 10, 10);
        Unit air = world.createUnit(balloon, 1, 16, 14);
        assertTrue(ship != null && air != null);
        assertTrue(world.orderAttack(ship, air),
                "destroyer accepts an attack order on the balloon");
        // Idle auto-acquire marks action 16 (stationary). A plain orderAttack
        // is action 12; the Human 9 path is the stationary form.
        ship.setBattleNetStationaryAttack(true);
        ship.setBattleNetOrderDelay(2);
        int startX = ship.tileX();
        int startY = ship.tileY();
        for (int i = 0; i < 8; i++) {
            world.tick();
        }
        assertEquals(startX, ship.tileX(),
                "out-of-range balloon must not pull the destroyer off its tile");
        assertEquals(startY, ship.tileY(),
                "out-of-range balloon must not pull the destroyer off its tile");
        assertEquals(Unit.Order.STILL, ship.order(),
                "after the delay the destroyer drops back to Still");
    }

    @Test
    @DisplayName("a far destroyer rewrites a shore-base patrol onto the footprint edge")
    void farDestroyerRewritesShoreBasePatrolOntoFootprintEdge() {
        // XOrc 8 destroyer 1430: shore-base Patrol to refinery top-left 87,71
        // rewrites to 88,73 (last blocked cell on the ray toward the ship).
        // The old Chebyshev-6 gate left the far goal on the top-left, so the
        // pathfinder packed pure NW (7771) and first double-stepped 96,92→
        // 94,90 while native's 88,73 route interleaved pure N onto 96,90.
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        for (int y = 10; y <= 12; y++) {
            for (int x = 10; x <= 12; x++) {
                map.field(x, y).setFlags(
                        TileFlag.LAND_ALLOWED | TileFlag.BUILDING
                                | TileFlag.COAST_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int index = 0; index < players.length; index++) {
            players[index] = new Player(index,
                    index == 0 ? PudMap.PlayerType.COMPUTER
                            : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        UnitType refineryType = new UnitType("unit-human-refinery");
        refineryType.setTileSize(3, 3);
        refineryType.setHitPoints(600);
        refineryType.setBuilding(true);
        refineryType.setShoreBuilding(true);
        AnimationSet refineryAnim = new AnimationSet("refinery");
        refineryAnim.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        refineryType.setAnimationSet(refineryAnim);
        Unit refinery = world.createUnit(refineryType, 0, 10, 10);
        // Chebyshev 23 from the yard -- past the old distance-6 rewrite gate.
        Unit destroyer = world.createUnit(destroyerType(), 0, 21, 33);
        assertTrue(refinery != null && destroyer != null,
                "refinery and destroyer place");
        destroyer.setBattleNetPendingPatrol(10, 10, 30, 30);
        destroyer.setBattleNetAnimationTimer(1);
        for (int i = 0; i < 8
                && destroyer.order() != Unit.Order.PATROL; i++) {
            world.tick();
        }
        assertEquals(Unit.Order.PATROL, destroyer.order(),
                "pending shore-base patrol must promote");
        assertEquals(11, destroyer.orderTargetX(),
                "far shore-base top-left rewrites onto the footprint edge");
        assertEquals(12, destroyer.orderTargetY(),
                "far shore-base top-left rewrites onto the footprint edge");
        assertEquals(21, destroyer.tileX(),
                "rewrite does not move the ship");
        assertEquals(33, destroyer.tileY(),
                "rewrite does not move the ship");
    }

    @Test
    @DisplayName("a destroyer fails a shipyard-footprint patrol goal into Still")
    void destroyerStillsWhenShipyardPatrolGoalIsBlocked() {
        // XOrc 11 slot 1519: native rewrites shipyard (21,34) to footprint
        // edge (22,36), fails the route, and surfaces Still at (22,38) without
        // swapping to the far endpoint. Java used to wall-follow NW onto free
        // water under Patrol.
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        // 3-by-3 shipyard footprint as land/building so open-water rewrite
        // treats it as blocked.
        for (int y = 10; y <= 12; y++) {
            for (int x = 10; x <= 12; x++) {
                map.field(x, y).setFlags(
                        TileFlag.LAND_ALLOWED | TileFlag.BUILDING
                                | TileFlag.COAST_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int index = 0; index < players.length; index++) {
            players[index] = new Player(index,
                    index == 0 ? PudMap.PlayerType.COMPUTER
                            : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        UnitType yardType = new UnitType("unit-human-shipyard");
        yardType.setTileSize(3, 3);
        yardType.setHitPoints(1100);
        yardType.setBuilding(true);
        yardType.setShoreBuilding(true);
        AnimationSet yardAnim = new AnimationSet("yard");
        yardAnim.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        yardType.setAnimationSet(yardAnim);
        Unit yard = world.createUnit(yardType, 0, 10, 10);
        Unit destroyer = world.createUnit(destroyerType(), 0, 12, 14);
        assertTrue(yard != null && destroyer != null, "ships and yard place");
        assertTrue(destroyer.battleNetDoubleStep(),
                "destroyer is a two-tile ship");
        assertTrue(world.orderPatrol(destroyer, 10, 10),
                "destroyer accepts a patrol toward the shipyard");
        // Mirror the ready-pass rewrite: last blocked cell on the ray from
        // the yard toward the destroyer is the footprint edge, not free water.
        // Promote with the rewritten goal the same way beginBattleNetPendingPatrol
        // does for non-capital ships.
        destroyer.setOrderTarget(12, 12);
        destroyer.setBattleNetOrderDelay(2);
        for (int i = 0; i < 16
                && destroyer.order() == Unit.Order.PATROL
                && destroyer.tileX() == 12 && destroyer.tileY() == 14; i++) {
            world.tick();
        }
        assertEquals(12, destroyer.tileX(),
                "destroyer must not leave its start tile toward the yard");
        assertEquals(14, destroyer.tileY(),
                "destroyer must not leave its start tile toward the yard");
        assertEquals(Unit.Order.STILL, destroyer.order(),
                "blocked shipyard-footprint patrol must surface Still");
    }

    @Test
    @DisplayName("profile 35 assigns three surface ships to the sea assault home")
    void profileThirtyFiveAssaultGroupTakesThreeSurfaceShips() {
        // XOrc 8: native slots 1404/1424/1426 all carry behavior 2 and goal
        // (98,122) at cycle 1. Taking only two left the destroyer at (60,100)
        // on shore-base Patrol toward the refinery, so its first double-step
        // was NE (62,98) while native stepped SE toward the juggernaught home
        // (62,102).
        GameMap map = new GameMap(48, 48, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int index = 0; index < players.length; index++) {
            players[index] = new Player(index,
                    index == 0 ? PudMap.PlayerType.COMPUTER
                            : index == 1 ? PudMap.PlayerType.PERSON
                                    : PudMap.PlayerType.NOBODY,
                    index == 0 ? PudMap.Race.HUMAN : PudMap.Race.ORC);
        }
        World world = new World(map, players);
        var ai = world.enableAi(0);
        ai.setBattleNetBuildProfile(null, 35);

        UnitType juggernaught = new UnitType("unit-ogre-juggernaught");
        juggernaught.setTileSize(2, 2);
        juggernaught.setHitPoints(150);
        juggernaught.setSpeed(0);
        juggernaught.setSeaUnit(true);
        juggernaught.setCanAttack(true);
        AnimationSet jugAnim = new AnimationSet("juggernaught");
        jugAnim.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        juggernaught.setAnimationSet(jugAnim);
        Unit home = world.createUnit(juggernaught, 1, 30, 30);
        assertTrue(home != null, "person capital ship must place");

        Unit fourth = world.createUnit(destroyerType(), 0, 8, 8);
        Unit third = world.createUnit(destroyerType(), 0, 12, 12);
        Unit second = world.createUnit(battleshipType(), 0, 16, 16);
        Unit first = world.createUnit(destroyerType(), 0, 20, 20);
        assertTrue(first != null && second != null && third != null
                && fourth != null, "computer fleet must place");

        world.fireBattleNetReadyForAll();

        assertEquals(2, first.battleNetAiBehavior(),
                "latest surface attacker joins the type-two assault");
        assertEquals(2, second.battleNetAiBehavior(),
                "second surface attacker joins the type-two assault");
        assertEquals(2, third.battleNetAiBehavior(),
                "third surface attacker joins the type-two assault");
        assertEquals(0, fourth.battleNetAiBehavior(),
                "a fourth surface ship stays on ordinary naval behaviour");
        assertEquals(30, first.battleNetAiHomeX(),
                "assault home is the person capital ship");
        assertEquals(30, first.battleNetAiHomeY(),
                "assault home is the person capital ship");
        assertEquals(30, third.battleNetAiHomeX(),
                "third assault ship aims at the same capital home");
        assertEquals(30, third.battleNetAiHomeY(),
                "third assault ship aims at the same capital home");
        assertTrue(third.hasBattleNetPendingPatrol(),
                "third assault ship is queued toward the capital home");
        assertEquals(30, third.battleNetPendingPatrolX(),
                "third assault ship patrol target is the capital home");
        assertEquals(30, third.battleNetPendingPatrolY(),
                "third assault ship patrol target is the capital home");
    }

    @Test
    @DisplayName("a submarine residual holds every four beats of the script.bin move body")
    void aSubmarineResidualHoldsEveryFourBeatsOfTheScriptBinMoveBody() {
        // XORc 8 human-sub 1433: ChonkCraft Move waits skipped two native holds so
        // the second double-step NW committed at fixture 44; retail holds
        // residual two for one more cycle and steps at 45. script.bin Move is
        // op13+wait2, op13+wait1, op13+wait1 repeating (drain, hold, drain,
        // drain, drain, hold...).
        int subType = PudUnitTypes.code("unit-human-submarine");
        assertTrue(subType >= 0, "submarine has a PUD type code");
        byte[] script = submarineMoveSequence(subType);
        World world = openWater();
        world.setBattleNetSequenceData(script);

        UnitType subTypeDef = new UnitType("unit-human-submarine");
        subTypeDef.setTileSize(2, 2);
        subTypeDef.setHitPoints(60);
        subTypeDef.setSpeed(7);
        subTypeDef.setSeaUnit(true);
        // Deliberately irregular ChonkCraft Move -- the implementation used to follow this
        // and step one cycle early. Native residual pace must win.
        AnimationSet animations = new AnimationSet("sub-chonkcraft-irregular");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0",
                "move 2", "wait 2", "move 2", "wait 1", "move 2", "wait 1",
                "move 2", "wait 2", "move 2", "wait 1", "move 2", "wait 1",
                "move 2", "wait 2", "move 2", "wait 1", "move 2", "wait 1",
                "move 2", "wait 2", "move 2", "wait 1", "move 2", "wait 1",
                "move 2", "wait 1", "move 2", "wait 1", "move 2", "wait 1",
                "move 2", "wait 1", "move 2", "wait 2", "move 2",
                "unbreakable end", "wait 1")));
        subTypeDef.setAnimationSet(animations);

        Unit sub = world.createUnit(subTypeDef, 0, 20, 20);
        assertTrue(sub != null && sub.battleNetDoubleStep(),
                "2x2 submarine arms double-step");
        assertTrue(world.orderMove(sub, 4, 4),
                "far north-west corridor is accepted");

        int firstStepCycle = -1;
        int secondStepCycle = -1;
        int prevX = sub.tileX();
        int prevY = sub.tileY();
        for (int cycle = 1; cycle <= 120; cycle++) {
            world.tick();
            if (sub.tileX() != prevX || sub.tileY() != prevY) {
                if (firstStepCycle < 0) {
                    firstStepCycle = cycle;
                } else if (secondStepCycle < 0) {
                    secondStepCycle = cycle;
                    break;
                }
                prevX = sub.tileX();
                prevY = sub.tileY();
            }
        }
        assertTrue(firstStepCycle > 0, "submarine must take a first double-step");
        assertTrue(secondStepCycle > 0, "submarine must take a second double-step");
        // One full script.bin Move body between commits: 32 drains of 2px plus
        // the hold after every wait-2 beat. Measured on XORc 8 as 43 cycles
        // from first NW land to the next (fixture 2→45).
        assertEquals(43, secondStepCycle - firstStepCycle,
                "second double-step must wait the native residual body, not the "
                        + "irregular ChonkCraft Move that used to step one cycle early");
    }

    /**
     * Minimal {@code script.bin} with a submarine Move body of thirty-two
     * {@code op13 1} beats under wait 2,1,1 -- the retail residual cadence.
     */
    private static byte[] submarineMoveSequence(int pudType) {
        int table = 200;
        int moveSeq = 300;
        byte[] program = new byte[900];
        putWord(program, pudType * 2, table);
        // animation slot 3 (Move)
        putWord(program, table + BattleNetSequence.MOVE_ANIMATION * 2, moveSeq);
        int at = moveSeq;
        program[at++] = 4; // frame
        program[at++] = 0;
        program[at++] = 0; // OP0
        int body = at;
        for (int i = 0; i < 32; i++) {
            program[at++] = 13; // op13 pixel
            program[at++] = 1;
            int wait = (i % 3 == 0) ? 2 : 1;
            program[at++] = 7; // wait opcode
            program[at++] = (byte) wait;
        }
        program[at++] = 3; // goto body
        putWord(program, at, body);
        return program;
    }

    private static void putWord(byte[] data, int offset, int value) {
        data[offset] = (byte) (value & 0xff);
        data[offset + 1] = (byte) ((value >> 8) & 0xff);
    }

}
