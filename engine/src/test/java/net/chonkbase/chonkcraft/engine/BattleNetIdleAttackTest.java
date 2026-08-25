package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.missile.MissileClass;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks down the type branches in BNE's startup Still dispatcher. */
class BattleNetIdleAttackTest {

    private static UnitType destroyer() {
        UnitType type = new UnitType("unit-human-destroyer");
        type.setTileSize(2, 2);
        type.setHitPoints(100);
        type.setSpeed(10);
        type.setSeaUnit(true);
        type.setCanAttack(true);
        type.setCanTargetSea(true);
        type.setMaxAttackRange(4);
        type.setReactRangeComputer(10);
        type.setReactRangePerson(5);
        type.setSightRange(8);
        AnimationSet animations = new AnimationSet("destroyer");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of("frame 0", "move 32", "wait 1")));
        type.setAnimationSet(animations);
        return type;
    }

    private static UnitType transport() {
        UnitType type = new UnitType("unit-human-transport");
        type.setTileSize(2, 2);
        type.setHitPoints(150);
        type.setSpeed(10);
        type.setSeaUnit(true);
        type.setSightRange(4);
        type.setMaxOnBoard(6);
        type.canTransport_().add("LandUnit");
        AnimationSet animations = new AnimationSet("transport");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of("frame 0", "move 32", "wait 1")));
        type.setAnimationSet(animations);
        return type;
    }

    private static UnitType submarine() {
        UnitType type = new UnitType("unit-human-submarine");
        type.setTileSize(2, 2);
        type.setHitPoints(60);
        type.setSpeed(7);
        type.setSeaUnit(true);
        type.setCanAttack(true);
        type.setCanTargetSea(true);
        type.setPermanentCloak(true);
        type.setDetectCloak(true);
        type.setMaxAttackRange(4);
        type.setReactRangeComputer(7);
        type.setReactRangePerson(5);
        type.setSightRange(5);
        AnimationSet animations = new AnimationSet("submarine");
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of("frame 0", "move 32", "wait 1")));
        type.setAnimationSet(animations);
        return type;
    }

    private static UnitType fighter(String ident, int priority) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setMaxAttackRange(4);
        type.setReactRangeComputer(7);
        type.setSightRange(8);
        type.setPriority(priority);
        AnimationSet animations = new AnimationSet(ident);
        animations.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        animations.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of("frame 0", "move 32", "wait 1")));
        type.setAnimationSet(animations);
        return type;
    }

    private static byte[] towerSequence() {
        byte[] program = new byte[256];
        putWord(program, 0, 16);
        putWord(program, 20, 32); // type zero Still makes the table usable
        program[32] = 0;

        putWord(program, 96 * 2, 200);
        putWord(program, 200 + 2 * 2, 220); // tower Still
        putWord(program, 200 + 4 * 2, 230); // tower attack/action 14
        program[220] = 0;
        program[221] = 1;
        program[222] = 30;
        program[223] = 3;
        putWord(program, 224, 220);
        program[230] = 0;
        program[231] = 1;
        program[232] = 59;
        program[233] = 3;
        putWord(program, 234, 230);
        return program;
    }

    private static byte[] critterSequence() {
        byte[] program = new byte[400];
        putWord(program, 0, 300);
        putWord(program, 300 + 2 * 2, 320); // type zero Still: usability probe
        program[320] = 0;

        putWord(program, 57 * 2, 340);
        putWord(program, 340 + 2 * 2, 360); // critter Still action marker
        program[360] = 0;
        return program;
    }

    private static byte[] projectileSequence() {
        byte[] program = new byte[560];
        putWord(program, 0, 200);
        putWord(program, 200 + 2 * 2, 220); // type zero Still: usability probe
        program[220] = 0;

        putWord(program, 5 * 2, 300);
        putWord(program, 300 + 4 * 2, 504); // catapult attack
        program[504] = 10; // invoke the current order, then continue
        program[505] = 1;
        program[506] = 6;
        return program;
    }

    private static void putWord(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static int nextAsyncSeed(int seed) {
        return seed * 0x015a4e35 + 1;
    }

    private static Player[] opponents() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i == 0 ? PudMap.PlayerType.COMPUTER
                            : i == 1 ? PudMap.PlayerType.PERSON
                                    : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        return players;
    }

    @Test
    @DisplayName("siege active-order Still callbacks never draw idle random")
    void siegeActiveOrderStillCallbacksNeverDrawIdleRandom() {
        GameMap map = new GameMap(16, 16, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        Unit ballista = world.createUnit(fighter("unit-ballista", 50),
                0, 4, 4);
        Unit catapult = world.createUnit(fighter("unit-catapult", 50),
                1, 8, 8);
        assertNotNull(ballista);
        assertNotNull(catapult);

        int seedBefore = world.battleNetRandomSeed();
        world.idle.advanceBattleNetActiveOrderIdleRandom(ballista);
        world.idle.advanceBattleNetActiveOrderIdleRandom(catapult);

        assertEquals(seedBefore, world.battleNetRandomSeed(),
                "ballistae and catapults use the non-random siege Still arm");
    }

    @Test
    @DisplayName("BNE naval units acquire targets when their idle countdown fires")
    void navalUnitUsesTheNativeIdleTargetScan() {
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        Unit guard = world.createUnit(destroyer(), 0, 4, 4);
        Unit enemy = world.createUnit(destroyer(), 1, 8, 4);
        guard.setBattleNetAnimationTimer(2);
        enemy.setBattleNetAnimationTimer(8);

        world.tick();
        world.tick();

        assertEquals(Unit.Order.ATTACK, guard.order());
        assertSame(enemy, guard.target());
    }

    @Test
    @DisplayName("a person submarine with UNIT.Data still acquires at its idle marker")
    void personSubmarineAcquiresDespiteReadySuppressed() {
        // XHuman 7: person submarine 1422 carries non-zero PUD Data (native
        // flag 0x5f:1 / ready-suppressed) and still flips Still to Attack on
        // its first idle marker when an orc destroyer sits four tiles away.
        // UNIT.Data only blocks the ready-pass patrol assignment, not the
        // HandleEachCycle hostile scan.
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        // player 1 is PERSON in opponents(); player 0 is COMPUTER (enemy).
        Unit sub = world.createUnit(submarine(), 1, 4, 4);
        Unit enemy = world.createUnit(destroyer(), 0, 6, 8);
        sub.setBattleNetReadySuppressed(true);
        sub.setBattleNetAnimationTimer(2);
        enemy.setBattleNetAnimationTimer(8);

        world.tick();
        world.tick();

        assertEquals(Unit.Order.ATTACK, sub.order(),
                "ready-suppressed person submarine must still open fire");
        assertSame(enemy, sub.target(),
                "the adjacent hostile destroyer is the idle scan winner");
        assertTrue(sub.battleNetStationaryAttack(),
                "idle scan is native action 16 (stationary)");
    }

    @Test
    @DisplayName("a person archer with UNIT.Data acquires a footman inside weapon range")
    void personArcherWithDataAcquiresStationaryAttack() {
        // XHuman 12 archer 1450 carries non-zero PUD Data and at fixture
        // cycle 9 flips Still to action 16 against the footman at 24,60
        // (weapon range 4) while remaining at 28,59. Full reaction-range
        // scans for Data-marked person troops opened XHuman 4's ballista at
        // cycle 1 while native stayed Still until cycle 15.
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        UnitType archer = fighter("unit-archer", 40);
        archer.setMaxAttackRange(4);
        archer.setReactRangePerson(8);
        archer.setReactRangeComputer(10);
        archer.setCanTargetLand(true);
        UnitType footman = fighter("unit-footman", 60);
        footman.setReactRangePerson(5);
        Unit guard = world.createUnit(archer, 1, 10, 10);
        // Chebyshev 4: inside weapon range.
        Unit enemy = world.createUnit(footman, 0, 6, 11);
        guard.setBattleNetReadySuppressed(true);
        guard.setBattleNetAnimationTimer(2);
        enemy.setBattleNetAnimationTimer(8);
        int startX = guard.tileX();
        int startY = guard.tileY();

        world.tick();
        world.tick();

        assertEquals(Unit.Order.ATTACK, guard.order(),
                "Data-marked person archer must open fire in weapon range");
        assertSame(enemy, guard.target(),
                "the footman inside weapon range is the idle scan winner");
        assertTrue(guard.battleNetStationaryAttack(),
                "person idle scan is native action 16 (stationary)");
        for (int i = 0; i < 6; i++) {
            world.tick();
        }
        assertEquals(startX, guard.tileX(),
                "action 16 must not chase the footman off the archer's tile");
        assertEquals(startY, guard.tileY(),
                "action 16 must not chase the footman off the archer's tile");
    }

    @Test
    @DisplayName("a person ballista with UNIT.Data ignores hostiles outside weapon range")
    void personBallistaWithDataIgnoresOutOfWeaponRangeHostile() {
        // XHuman 4 ballista 1488: native Still through cycle 10 with hostiles
        // inside person reaction range but outside weapon range; action 16
        // only at cycle 15 once the target is in range.
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        UnitType ballista = fighter("unit-ballista", 50);
        ballista.setMaxAttackRange(4);
        ballista.setReactRangePerson(10);
        ballista.setReactRangeComputer(10);
        ballista.setCanTargetLand(true);
        UnitType grunt = fighter("unit-grunt", 40);
        Unit guard = world.createUnit(ballista, 1, 10, 10);
        // Chebyshev 8: inside react 10, outside weapon 4.
        Unit enemy = world.createUnit(grunt, 0, 18, 10);
        guard.setBattleNetReadySuppressed(true);
        guard.setBattleNetAnimationTimer(2);
        enemy.setBattleNetAnimationTimer(8);

        world.tick();
        world.tick();

        assertEquals(Unit.Order.STILL, guard.order(),
                "Data-marked person ballista must not open fire outside weapon range");
        assertNull(guard.target());
    }

    @Test
    @DisplayName("BNE naval idle markers count down without drawing every time")
    void navalUnitUsesTheNativeIdleCountdown() {
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        Unit ship = world.createUnit(destroyer(), 0, 4, 4);
        ship.setBattleNetAnimationTimer(1);
        ship.setBattleNetFlyingIdleTimer(2);
        int seedBefore = world.battleNetRandomSeed();

        world.tick();

        assertEquals(seedBefore, world.battleNetRandomSeed(),
                "a non-zero naval idle timer consumes no asynchronous draw");
        assertEquals(1, ship.battleNetFlyingIdleTimer());
    }

    @Test
    @DisplayName("an idle transport that has carried nothing draws again when its countdown runs out")
    void anIdleTransportDrawsOnEveryIdleCountdownExpiry() {
        // Human 7's hull at 20,6 and Orc 12's both sit still all game, count
        // ten idle visits down and re-arm on the eleventh. Retail spends an
        // asynchronous draw on that re-arm; this engine used to spend one only
        // on a hull's first, so from the second onwards every later number in
        // the shared stream landed on the wrong unit -- a Human 7 critter stood
        // still where retail sent it wandering, and an Orc 12 critter wandered
        // where retail left it standing, both at cycle 52.
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.WATER_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        Unit hull = world.createUnit(transport(), 0, 4, 4);
        assertNotNull(hull, "the transport must place on open water");

        hull.setBattleNetAnimationTimer(1);
        hull.setBattleNetFlyingIdleTimer(0);
        int beforeFirst = world.battleNetRandomSeed();
        world.tick();
        assertNotEquals(beforeFirst, world.battleNetRandomSeed(),
                "an expiring idle countdown draws for its replacement");

        hull.setBattleNetAnimationTimer(1);
        hull.setBattleNetFlyingIdleTimer(0);
        int beforeSecond = world.battleNetRandomSeed();
        world.tick();
        assertNotEquals(beforeSecond, world.battleNetRandomSeed(),
                "a hull that has carried nothing keeps drawing on every later "
                        + "countdown too, so the units drawing after it in the "
                        + "shared stream are not handed each other's numbers");
    }

    @Test
    @DisplayName("BNE executes a critter's replacement Still marker on arrival")
    void completedCritterMoveRunsTheReplacementActionImmediately() {
        GameMap map = new GameMap(12, 12, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        UnitType critterType = fighter("unit-critter", 1);
        critterType.setCanAttack(false);
        Unit critter = world.createUnit(critterType, 15, 4, 4);
        world.orderMove(critter, 4, 4);
        critter.setBattleNetOrderDelay(0);
        critter.setBattleNetIdlePhase(2);
        int seedBefore = world.battleNetRandomSeed();

        world.tick();

        assertNotEquals(seedBefore, world.battleNetRandomSeed(),
                "the replacement Still action must dispatch in the arrival call");
    }

    @Test
    @DisplayName("a critter constructor marker can replace a delayed move")
    void constructorMarkerKeepsRunningUntilTheFirstMoveStarts() {
        GameMap map = new GameMap(12, 12, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents(), 1);
        world.setBattleNetSequenceData(critterSequence());
        UnitType critterType = fighter("unit-critter", 1);
        critterType.setCanAttack(false);
        UnitType blockerType = fighter("unit-critter-blocker", 1);
        blockerType.setCanAttack(false);
        blockerType.setSpeed(0);
        world.createUnit(blockerType, 15, 3, 4);
        Unit critter = world.createUnit(critterType, 15, 4, 5);
        world.orderMove(critter, 3, 4);
        critter.setBattleNetIdlePhase(2);
        critter.setBattleNetSequenceOffset(360);
        critter.setBattleNetAnimationTimer(3);

        world.tick();
        assertEquals(2, critter.battleNetAnimationTimer(), "first countdown");
        assertEquals(2, critter.battleNetIdlePhase(), "first countdown phase");
        world.tick();
        assertEquals(1, critter.battleNetAnimationTimer(), "second countdown");
        assertEquals(2, critter.battleNetIdlePhase());
        assertEquals(0, critter.pathLength());

        world.tick();

        assertEquals(3, critter.battleNetIdlePhase(),
                "the adjacent constructor action marker did not execute");
        assertEquals(0, critter.pathLength(),
                "the superseded move must not reserve or enter a tile first");
        assertEquals(4, critter.tileX());
        assertEquals(5, critter.tileY());
    }

    @Test
    @DisplayName("a rock splash rolls the impact-tile victim before a northern neighbour")
    void rockSplashRollsImpactTileVictimBeforeNorthernNeighbour() {
        // XHuman 2 fixture 35: slot-6 rock frees on footman 60,68 while ogre
        // 61,66 sits north. FUN_00410520 walks the impact tile first; a pure
        // ascending tileY sort spent the first async roll on the ogre (outer
        // band) and the second on the footman (full band) -- footman 41 / ogre
        // 8 instead of native 57 / 12 with the same stored 80 and armours.
        GameMap map = new GameMap(32, 32, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents(), 1);
        world.setBattleNetSequenceData(projectileSequence());
        UnitType catapultType = fighter("unit-catapult", 200);
        catapultType.setBasicDamage(80);
        catapultType.setPiercingDamage(0);
        catapultType.setMissile("missile-catapult-rock");
        catapultType.setMaxAttackRange(8);
        catapultType.setMinAttackRange(1);
        catapultType.setReactRangeComputer(9);
        catapultType.setSightRange(9);
        UnitType footmanType = fighter("unit-footman", 50);
        footmanType.setHitPoints(60);
        footmanType.setArmor(2);
        footmanType.setCanAttack(false);
        UnitType ogreType = fighter("unit-ogre", 40);
        ogreType.setHitPoints(90);
        ogreType.setArmor(4);
        ogreType.setCanAttack(false);
        // range>1 so splashes(); PARABOLIC + catapult-rock ident for impact wait 5.
        MissileType rock = new MissileType("missile-catapult-rock", null,
                MissileClass.PARABOLIC, 32, 32, 1, 1, 8, 1, 2, 2,
                0, null, null, false, 0, 0, false);
        world.setMissileTypes(Map.of(rock.ident(), rock));
        // Fire from the west so the impact tile is the footman's. Ogre sits
        // one tile north (lower tileY) inside the outer splash ring
        // (metric 1024: >0x1ff, <0x700) so y-sort would visit it first.
        Unit catapult = world.createUnit(catapultType, 0, 4, 10);
        Unit footman = world.createUnit(footmanType, 1, 10, 10);
        Unit ogre = world.createUnit(ogreType, 1, 10, 9);
        world.orderAttack(catapult, footman);
        catapult.setBattleNetAnimationTimer(1);
        world.hit(catapult, footman);

        int footBefore = footman.hitPoints();
        int ogreBefore = ogre.hitPoints();
        boolean hit = false;
        for (int cycle = 0; cycle < 80 && !hit; cycle++) {
            world.tick();
            if (footman.hitPoints() < footBefore || ogre.hitPoints() < ogreBefore) {
                hit = true;
            }
        }
        assertTrue(hit, "the catapult rock must land within the wait window");

        int footDamage = footBefore - footman.hitPoints();
        int ogreDamage = ogreBefore - ogre.hitPoints();
        // Full-band on armor 2 is 39-78; outer-quartered on armor 4 is 8-16.
        // Center-primary spends the first roll on the footman (full) and the
        // second on the ogre (outer). Y-order swapped them and under-damaged
        // the impact victim relative to the first full-band roll.
        assertTrue(footDamage >= 39 && footDamage <= 78,
                "impact-tile footman must take a full-band roll, not outer; took "
                        + footDamage);
        assertTrue(ogreDamage >= 8 && ogreDamage <= 16,
                "northern ogre must take an outer-band roll; took " + ogreDamage);
        // Pin the seed-1 stream's exact rolls under center-primary order.
        // Without the impact-tile-first sort the same two async values swap
        // victims and the footman ends on a different full-band HP (test-
        // efficacy fails the pre-fix commit on this pair).
        assertEquals(20, footman.hitPoints(),
                "center-primary spends the first splash roll on the impact-tile "
                        + "footman (seed 1 → full-band damage 40)");
        assertEquals(74, ogre.hitPoints(),
                "second splash roll is the northern neighbour's outer band "
                        + "(seed 1 → outer damage 16)");
    }

    @Test
    @DisplayName("BNE projectile creation preserves its two cosmetic random draws")
    void projectileCreationAdvancesTheNativeAsynchronousStreamTwice() {
        GameMap map = new GameMap(12, 12, new Tileset());
        World world = new World(map, opponents(), 1);
        world.setBattleNetSequenceData(projectileSequence());
        UnitType attackerType = fighter("unit-catapult", 200);
        attackerType.setMissile("missile-catapult-rock");
        UnitType targetType = fighter("unit-footman", 60);
        MissileType rock = new MissileType("missile-catapult-rock", null,
                MissileClass.POINT_TO_POINT, 32, 32, 1, 1, 16, 1, 2, 1,
                0, null, null, false, 0, 0, false);
        world.setMissileTypes(Map.of(rock.ident(), rock));
        Unit attacker = world.createUnit(attackerType, 0, 2, 2);
        Unit target = world.createUnit(targetType, 1, 6, 2);
        world.orderAttack(attacker, target);
        attacker.setBattleNetAnimationTimer(1);
        int seedBefore = world.battleNetRandomSeed();

        world.hit(attacker, target);
        assertEquals(seedBefore, world.battleNetRandomSeed(),
                "the early Java presentation frame must not debit BNE yet");
        world.tick();

        int afterCreation = nextAsyncSeed(nextAsyncSeed(seedBefore));
        assertEquals(afterCreation, world.battleNetRandomSeed());

        world.tick();

        assertEquals(nextAsyncSeed(afterCreation), world.battleNetRandomSeed(),
                "a live point-to-point shot makes one native motion draw");
    }

    @Test
    @DisplayName("BNE tower arrows roll damage but skip mobile constructor jitter")
    void towerArrowHasDamageAndMotionRandomnessButNoConstructorJitter() {
        GameMap map = new GameMap(12, 12, new Tileset());
        World world = new World(map, opponents(), 1);
        UnitType towerType = fighter("unit-human-guard-tower", 100);
        towerType.setBuilding(true);
        towerType.setSpeed(0);
        towerType.setMissile("missile-arrow");
        towerType.setBasicDamage(4);
        towerType.setPiercingDamage(12);
        UnitType targetType = fighter("unit-target", 60);
        targetType.setBuilding(true);
        targetType.setSpeed(0);
        targetType.setArmor(4);
        MissileType arrow = new MissileType("missile-arrow", null,
                MissileClass.POINT_TO_POINT, 32, 32, 1, 1, 16, 1, 1, 1,
                0, null, null, false, 0, 0, false);
        world.setMissileTypes(Map.of(arrow.ident(), arrow));
        Unit tower = world.createUnit(towerType, 0, 2, 2);
        Unit target = world.createUnit(targetType, 1, 6, 2);
        int seedBefore = world.battleNetRandomSeed();

        world.hit(tower, target);

        int afterDamage = nextAsyncSeed(seedBefore);
        assertEquals(afterDamage, world.battleNetRandomSeed(),
                "fixed emplacements store one damage roll but no muzzle jitter");
        world.tick();
        assertEquals(nextAsyncSeed(afterDamage), world.battleNetRandomSeed(),
                "the tower arrow still makes its native point-motion draw");
    }

    @Test
    @DisplayName("BNE idle targeting applies its squared-distance priority")
    void landUnitChoosesTheNativeDistanceWeightedTarget() {
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        Unit guard = world.createUnit(fighter("unit-axethrower", 50), 0, 4, 4);
        Unit near = world.createUnit(fighter("unit-footman", 40), 1, 8, 4);
        Unit far = world.createUnit(fighter("unit-knight", 60), 1, 10, 4);
        guard.setBattleNetAnimationTimer(2);
        near.setBattleNetAnimationTimer(8);
        far.setBattleNetAnimationTimer(8);

        world.tick();
        world.tick();

        assertEquals(Unit.Order.ATTACK, guard.order());
        assertSame(near, guard.target(),
                "the native squared-distance penalty outweighs 20 priority points");
    }

    @Test
    @DisplayName("an in-range catapult prefers a footman over a guard tower")
    void inRangeCombatBuildingDoesNotOutrankAGroundFighter() {
        // XHuman 2 catapult 45 at (56,63): old battleNetTargetScore gave every
        // in-range combat building 0x30000 and every in-range ground fighter
        // 0x20000, so guard-tower 54 beat footman 52. Native rock from that
        // catapult aims the footman (rem 157 at c10, free at fixture 35). Air
        // still gets 0x30000; ground combatants share 0x20000 whether mobile
        // or combat-building; passive buildings stay priority-distance only.
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        UnitType catapultType = fighter("unit-catapult", 200);
        catapultType.setMaxAttackRange(8);
        catapultType.setMinAttackRange(2);
        catapultType.setReactRangeComputer(9);
        catapultType.setReactRangePerson(9);
        catapultType.setSightRange(9);
        UnitType towerType = fighter("unit-human-guard-tower", 40);
        towerType.setBuilding(true);
        towerType.setSpeed(0);
        towerType.setCanAttack(true);
        towerType.setMaxAttackRange(6);
        UnitType footmanType = fighter("unit-footman", 50);
        Unit catapult = world.createUnit(catapultType, 0, 4, 4);
        // Tower is farther; under the old 0x30000 building bonus it still won.
        // With shared 0x20000 the closer footman wins on distance alone.
        Unit tower = world.createUnit(towerType, 1, 10, 4);
        Unit footman = world.createUnit(footmanType, 1, 7, 4);
        catapult.setBattleNetAnimationTimer(2);
        tower.setBattleNetAnimationTimer(8);
        footman.setBattleNetAnimationTimer(8);

        world.tick();
        world.tick();

        assertEquals(Unit.Order.ATTACK, catapult.order(),
                "the catapult must open fire on an in-range hostile");
        assertSame(footman, catapult.target(),
                "in-range ground combatants share 0x20000; the closer footman "
                        + "must beat the farther guard tower");
    }

    @Test
    @DisplayName("BNE equal-score targets retain persistent screen-Y order")
    void equalScoreTargetsUseNativeSpatialOrder() {
        // XHuman 12 grunt 1503 at (31,38): equal scores 0x10036 for footmen
        // at (29,43) and (32,43). Native inserts the newer equal-Y unit before
        // the older one (FUN_00453c00) and keeps the first on a score tie
        // (FUN_00409ff0), so the right footman wins. Row-major map scan used
        // to pick the left footman and step SW.
        GameMap map = new GameMap(64, 64, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        UnitType gruntType = fighter("unit-grunt", 50);
        gruntType.setReactRangeComputer(6);
        Unit guard = world.createUnit(gruntType, 0, 31, 38);
        UnitType footmanType = fighter("unit-footman", 60);
        Unit leftOlder = world.createUnit(footmanType, 1, 29, 43);
        Unit rightNewer = world.createUnit(footmanType, 1, 32, 43);
        guard.setBattleNetAnimationTimer(2);
        leftOlder.setBattleNetAnimationTimer(8);
        rightNewer.setBattleNetAnimationTimer(8);

        world.tick();
        world.tick();

        assertSame(rightNewer, guard.target(),
                "native inserts a newly created equal-screen-Y unit before the older one");
    }

    @Test
    @DisplayName("BNE target scans treat a ChonkCraft 2x2 balloon as 1x1")
    void flyingTargetUsesTheNativeOneTileSelectionExtent() {
        GameMap map = new GameMap(128, 128, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        UnitType axe = fighter("unit-axethrower", 55);
        axe.setCanTargetAir(true);
        Unit far = world.createUnit(axe, 0, 80, 116);
        Unit near = world.createUnit(axe, 0, 79, 114);
        UnitType balloonType = fighter("unit-balloon", 40);
        balloonType.setCanAttack(false);
        balloonType.setAirUnit(true);
        balloonType.setTileSize(2, 2);
        Unit balloon = world.createUnit(balloonType, 1, 78, 108);
        far.setBattleNetAnimationTimer(2);
        near.setBattleNetAnimationTimer(2);
        balloon.setBattleNetAnimationTimer(8);

        world.tick();
        world.tick();

        assertEquals(Unit.Order.STILL, far.order(),
                "the balloon's ChonkCraft-only second row must not enter BNE's scan box");
        assertEquals(Unit.Order.ATTACK, near.order());
        assertSame(balloon, near.target());
    }

    @Test
    @DisplayName("BNE routes straight through a movable attack goal")
    void movableTargetTileDoesNotBecomeAPathfindingWall() {
        GameMap map = new GameMap(12, 12, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        UnitType attackerType = fighter("unit-attack-peasant", 30);
        attackerType.setMaxAttackRange(1);
        Unit attacker = world.createUnit(attackerType, 0, 4, 4);
        Unit target = world.createUnit(fighter("unit-peasant", 20), 1, 6, 4);

        world.orderAttack(attacker, target);
        world.tick();

        assertEquals(5, attacker.tileX());
        assertEquals(4, attacker.tileY(),
                "the occupied goal must not deflect the first step diagonally");
    }

    @Test
    @DisplayName("BNE out-of-range attack replans within two waits when blocked")
    void blockedAttackChaseDoesNotSleepTenCyclesOnEmptyRoute() {
        // XHuman 4's axethrower 1506 sits out of range with an empty 0xff
        // marked-target route while allies clog the approach. Native retries
        // after a short wait; PF_WAIT(10) left Java standing through fixture
        // cycle 6. An open western corridor here must produce a west step in
        // fewer than ten ticks after the order is issued.
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());

        UnitType axeType = fighter("unit-axethrower", 50);
        axeType.setMaxAttackRange(4);
        axeType.setSpeed(10);
        AnimationSet axeAnim = new AnimationSet("axe");
        axeAnim.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        axeAnim.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of("frame 0", "move 32", "wait 1")));
        axeAnim.put(AnimationSet.State.ATTACK,
                Animation.parse("attack", List.of("frame 0", "wait 1",
                        "attack", "wait 1")));
        axeType.setAnimationSet(axeAnim);

        Unit axe = world.createUnit(axeType, 0, 10, 10);
        Unit enemy = world.createUnit(fighter("unit-footman", 50), 1, 4, 10);

        world.orderAttack(axe, enemy);
        int startX = axe.tileX();
        for (int i = 0; i < 8 && axe.tileX() == startX; i++) {
            world.tick();
        }
        assertEquals(Unit.Order.ATTACK, axe.order(),
                "the chase must keep the Attack order while closing");
        assertEquals(startX - 1, axe.tileX(),
                "an open path must step within two short empty-route waits, "
                        + "not after a full PF_WAIT(10)");
    }

    @Test
    @DisplayName("a drained ranged chase does not sleep ten cycles on a refused leftover heading")
    void drainedRangedChaseReplansWhenLeftoverHeadingIsRefused() {
        // XHuman 4 axethrower 1521 took W onto (77,59) with a multi-step
        // cache, then refused the next W and slept PF_WAIT 10 while native
        // ended the route and stepped SW at fixture 25. After a drained step
        // with leftover headings, a solid refuse must clear and replan.
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        // Wall column blocks pure west of the first step; SW stays free.
        for (int y = 0; y < map.height(); y++) {
            map.field(8, y).setFlags(0);
        }
        World world = new World(map, opponents());

        UnitType axeType = fighter("unit-axethrower", 50);
        axeType.setMaxAttackRange(4);
        axeType.setSpeed(10);
        AnimationSet axeAnim = new AnimationSet("axe");
        axeAnim.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        // Multi-cycle Move so the first step drains before the next heading.
        axeAnim.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of(
                        "unbreakable begin",
                        "frame 0", "move 8", "wait 1",
                        "frame 0", "move 8", "wait 1",
                        "frame 0", "move 8", "wait 1",
                        "frame 0", "move 8", "wait 1",
                        "unbreakable end", "wait 1")));
        axeAnim.put(AnimationSet.State.ATTACK,
                Animation.parse("attack", List.of("frame 0", "wait 1",
                        "attack", "wait 1")));
        axeType.setAnimationSet(axeAnim);

        Unit axe = world.createUnit(axeType, 0, 12, 10);
        Unit enemy = world.createUnit(fighter("unit-footman", 50), 1, 4, 12);

        world.orderAttack(axe, enemy);
        int firstX = axe.tileX();
        int firstY = axe.tileY();
        // Reach the first new tile.
        for (int i = 0; i < 12 && axe.tileX() == firstX && axe.tileY() == firstY; i++) {
            world.tick();
        }
        assertTrue(axe.tileX() != firstX || axe.tileY() != firstY,
                "the axethrower must take a first chase step");
        int afterFirstX = axe.tileX();
        int afterFirstY = axe.tileY();
        // A refused leftover must replan within a few ticks, not PF_WAIT 10.
        boolean movedAgain = false;
        for (int i = 0; i < 8; i++) {
            world.tick();
            if (axe.tileX() != afterFirstX || axe.tileY() != afterFirstY) {
                movedAgain = true;
                break;
            }
        }
        assertTrue(movedAgain,
                "after a drained step a refused leftover heading must replan "
                        + "within eight cycles, not sleep PF_WAIT(10)");
        assertEquals(Unit.Order.ATTACK, axe.order(),
                "the chase keeps Attack while closing after the replan");
    }

    @Test
    @DisplayName("a drained ranged leftover keeps its route when an ally is cooperative")
    void drainedRangedLeftoverHoldsWhenAllyIsCooperative() {
        // XHuman 12 axethrower 1523 (Java 77) at (31,37) held leftover SE,S
        // with SE onto ally axethrower 76. Native kept the route under timer
        // 15 through fixtures 24..38 and stepped S at 40. Clearing the multi-
        // step cache on that refuse (as hard leftover replan does for walls)
        // residual-settled path 2→0 and SW-replanned onto (30,38) at 35.
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());

        UnitType axeType = fighter("unit-axethrower", 50);
        axeType.setMaxAttackRange(4);
        axeType.setSpeed(10);
        AnimationSet axeAnim = new AnimationSet("axe");
        axeAnim.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        axeAnim.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of(
                        "unbreakable begin",
                        "frame 0", "move 8", "wait 1",
                        "frame 0", "move 8", "wait 1",
                        "frame 0", "move 8", "wait 1",
                        "frame 0", "move 8", "wait 1",
                        "unbreakable end", "wait 1")));
        axeAnim.put(AnimationSet.State.ATTACK,
                Animation.parse("attack", List.of("frame 0", "wait 1",
                        "attack", "wait 1")));
        axeType.setAnimationSet(axeAnim);

        // Ally first so the axethrower has a higher pool slot and acts first
        // under BNE high-to-low ordering (ally still on Move when refuse runs).
        Unit ally = world.createUnit(fighter("unit-axethrower", 50), 0, 13, 11);
        Unit axe = world.createUnit(axeType, 0, 12, 10);
        // Beyond weapon range so in-range leftover discard does not fire.
        Unit enemy = world.createUnit(fighter("unit-footman", 50), 1, 20, 18);
        assertTrue(axe != null && ally != null && enemy != null,
                "axethrower, ally and enemy must place");
        assertTrue(world.orderAttack(axe, enemy), "attack accepted");

        int se = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(1, 1);
        int s = net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(0, 1);
        // Stack: first consumed is last index (SE then S).
        axe.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {s, se}));
        axe.setPathGoal(enemy.tileX(), enemy.tileY());
        axe.setTarget(enemy);
        axe.setChasing(true);
        axe.setOffset(0, 0);
        axe.setWalkHolding(false);
        axe.setStepDrained(true);
        axe.setBattleNetOrderDelay(0);
        axe.setBattleNetCollisionCounter(0);
        axe.animation().switchTo(axeAnim.get(AnimationSet.State.STILL));

        // Ally occupies the SE leftover while still mid-route (cooperative
        // and still routing). Idle Still is not cooperative; a pathless
        // standing ally hard-replans (XHuman 4 axethrower 1516). XHuman 12
        // axethrower 76 had leftover path and non-zero offsets while blocking.
        ally.setOffset(-8, -8);
        ally.setWalkHolding(true);
        ally.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {s}));
        ally.setPathGoal(13, 12);
        ally.setOrder(Unit.Order.MOVE);
        ally.animation().switchTo(
                ally.type().animationSet().get(AnimationSet.State.MOVE));

        int holdX = axe.tileX();
        int holdY = axe.tileY();
        int pathBefore = axe.pathLength();
        world.tick();
        assertEquals(pathBefore, axe.pathLength(),
                "cooperative refuse must keep the leftover SE,S route, not "
                        + "clear it for hard replan");
        assertEquals(holdX, axe.tileX(),
                "the axethrower stays on its tile during the cooperative hold");
        assertEquals(holdY, axe.tileY(),
                "the axethrower stays on its tile during the cooperative hold");
        assertEquals(14, axe.battleNetOrderDelay(),
                "cooperative leftover refuse arms fourteen quiet visits "
                        + "(native timer 15)");
        assertEquals(Unit.Order.ATTACK, axe.order(),
                "the chase keeps Attack while holding the leftover route");
    }

    @Test
    @DisplayName("a ranged chase retarget at the end of Move holds three quiet visits")
    void rangedChaseRetargetHoldsThreeVisitsBeforeTheNextStep() {
        // Human 13 axethrower 1505 retargeted from the wise-man to the knight
        // at fixture 25 and native held attack-animation timer 3 before the
        // SW step at 28. Stepping on the retarget visit put Java one tile
        // early. After Move ends, a new equal-score quarry must not move the
        // axethrower for three more visits.
        GameMap map = new GameMap(32, 32, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());

        UnitType axeType = fighter("unit-axethrower", 50);
        axeType.setMaxAttackRange(4);
        axeType.setReactRangeComputer(12);
        axeType.setSpeed(10);
        AnimationSet axeAnim = new AnimationSet("axe");
        axeAnim.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        axeAnim.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of(
                        "unbreakable begin",
                        "frame 0", "move 16", "wait 1",
                        "frame 0", "move 16", "wait 1",
                        "unbreakable end", "wait 1")));
        axeAnim.put(AnimationSet.State.ATTACK,
                Animation.parse("attack", List.of("frame 0", "wait 1",
                        "attack", "wait 1")));
        axeType.setAnimationSet(axeAnim);

        Unit axe = world.createUnit(axeType, 0, 16, 10);
        Unit first = world.createUnit(fighter("unit-footman", 50), 1, 10, 10);
        Unit second = world.createUnit(fighter("unit-footman", 50), 1, 10, 14);
        // Keep the second target dark until after the first step lands.
        second.setRemoved(true);

        world.orderAttack(axe, first);
        int startX = axe.tileX();
        int startY = axe.tileY();
        for (int i = 0; i < 10 && axe.tileX() == startX && axe.tileY() == startY; i++) {
            world.tick();
        }
        assertTrue(axe.tileX() != startX || axe.tileY() != startY,
                "the first chase step must land before the retarget");
        int midX = axe.tileX();
        int midY = axe.tileY();
        // Reveal the second quarry and force a rescan at the move boundary.
        second.setRemoved(false);
        second.setTile(10, 14);
        first.setRemoved(true);
        int quiet = 0;
        for (int i = 0; i < 6; i++) {
            world.tick();
            if (axe.tileX() == midX && axe.tileY() == midY) {
                quiet++;
            } else {
                break;
            }
        }
        assertTrue(quiet >= 3,
                "a ranged retarget after Move must hold at least three quiet "
                        + "visits before the next step, not step on the retarget");
    }

    @Test
    @DisplayName("a chase free-scans after the order delay and keeps an equal-cost face step")
    void aChaseFreeScansAfterTheOrderDelayAndKeepsAnEqualCostFaceStep() {
        // Human 13 knights 1493/1500 both free-scan after next_order 60.
        // 1493 switches axe→ogre and first-steps SE; 1500 free-scans onto an
        // ogre due north but keeps face NW so the equal-cost approach lands
        // on 119,25 rather than pure north.
        GameMap map = new GameMap(32, 32, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        int[] priorities = new int[110];
        priorities[9] = 0x37;
        priorities[7] = 0x3f;
        world.setBattleNetUnitPriorities(priorities);

        UnitType knightType = fighter("unit-knight", 50);
        knightType.setMaxAttackRange(1);
        knightType.setReactRangePerson(6);
        knightType.setReactRangeComputer(8);
        knightType.setCanTargetLand(true);
        AnimationSet knightAnim = new AnimationSet("knight");
        knightAnim.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        knightAnim.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of(
                        "unbreakable begin",
                        "frame 0", "move 16", "wait 1",
                        "frame 0", "move 16", "wait 1",
                        "unbreakable end", "wait 1")));
        knightAnim.put(AnimationSet.State.ATTACK,
                Animation.parse("attack", List.of("frame 0", "wait 1",
                        "attack", "wait 1")));
        knightType.setAnimationSet(knightAnim);

        UnitType axeType = fighter("unit-axethrower", 40);
        axeType.setMaxAttackRange(4);
        axeType.setCanTargetLand(true);
        UnitType ogreType = fighter("unit-ogre", 60);
        ogreType.setMaxAttackRange(1);
        ogreType.setCanTargetLand(true);

        // 1500 geometry: knight at 10,10, axe NW at 8,8, ogre due north at 10,8.
        Unit knight = world.createUnit(knightType, 1, 10, 10);
        Unit axe = world.createUnit(axeType, 0, 8, 8);
        Unit ogre = world.createUnit(ogreType, 0, 10, 8);
        knight.setBattleNetReadySuppressed(true);
        knight.setOfferedTarget(axe);
        knight.setBattleNetAnimationTimer(2);
        axe.setBattleNetAnimationTimer(8);
        ogre.setBattleNetAnimationTimer(8);

        world.tick();
        world.tick();
        assertEquals(Unit.Order.ATTACK, knight.order());
        assertSame(axe, knight.target());
        int startX = knight.tileX();
        int startY = knight.tileY();
        boolean moved = false;
        for (int i = 0; i < 8; i++) {
            world.tick();
            if (knight.tileX() != startX || knight.tileY() != startY) {
                moved = true;
                break;
            }
        }
        assertTrue(moved, "the knight must step after the order delay");
        assertSame(ogre, knight.target(),
                "free-scan after the delay must switch onto the higher-priority ogre");
        assertEquals(9, knight.tileX(),
                "equal-cost face preference keeps the NW approach, not pure north");
        assertEquals(9, knight.tileY(),
                "equal-cost face preference keeps the NW approach, not pure north");
    }

    @Test
    @DisplayName("a dest-arm leftover walks around a hostile on the acquired quarry's column")
    void aDestArmLeftoverWalksAroundAHostileOnTheAcquiredQuarrysColumn() {
        // Human 13 knight 1490 dest-arms SE onto 125,31 around ogre 1482
        // sitting on 124,32, then free-scans that ogre. Retargeting first
        // dest-armed due south onto 124,31 through the still-occupied square.
        GameMap map = new GameMap(32, 32, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        int[] priorities = new int[110];
        priorities[9] = 0x37;
        priorities[7] = 0x3f;
        world.setBattleNetUnitPriorities(priorities);

        UnitType knightType = fighter("unit-knight", 50);
        knightType.setMaxAttackRange(1);
        knightType.setReactRangePerson(6);
        knightType.setReactRangeComputer(8);
        knightType.setCanTargetLand(true);
        AnimationSet knightAnim = new AnimationSet("knight");
        knightAnim.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        knightAnim.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of(
                        "unbreakable begin",
                        "frame 0", "move 16", "wait 1",
                        "frame 0", "move 16", "wait 1",
                        "unbreakable end", "wait 1")));
        knightAnim.put(AnimationSet.State.ATTACK,
                Animation.parse("attack", List.of("frame 0", "wait 1",
                        "attack", "wait 1")));
        knightType.setAnimationSet(knightAnim);

        UnitType axeType = fighter("unit-axethrower", 40);
        axeType.setMaxAttackRange(4);
        axeType.setCanTargetLand(true);
        UnitType ogreType = fighter("unit-ogre", 60);
        ogreType.setMaxAttackRange(1);
        ogreType.setCanTargetLand(true);

        Unit knight = world.createUnit(knightType, 1, 10, 10);
        Unit axe = world.createUnit(axeType, 0, 10, 13);
        Unit ogre = world.createUnit(ogreType, 0, 10, 12);
        // Human 13 knight 1490's Still face is east; dest-arm combines that
        // axis with the south approach around the ogre.
        knight.setHeading(net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(1, 0));
        knight.setBattleNetReadySuppressed(true);
        knight.setOfferedTarget(axe);
        knight.setBattleNetAnimationTimer(2);
        axe.setBattleNetAnimationTimer(8);
        ogre.setBattleNetAnimationTimer(8);

        world.tick();
        world.tick();
        assertEquals(Unit.Order.ATTACK, knight.order(),
                "the offered hit-response must open Attack");
        assertSame(axe, knight.target(),
                "Attack opens on the acquired axethrower");
        int startX = knight.tileX();
        int startY = knight.tileY();
        boolean destArmed = false;
        for (int i = 0; i < 8; i++) {
            world.tick();
            if (knight.tileX() != startX || knight.tileY() != startY) {
                destArmed = true;
                break;
            }
        }
        assertTrue(destArmed, "the knight must dest-arm after the order delay");
        assertEquals(11, knight.tileX(),
                "the first leftover dest-arms around the ogre, not through it");
        assertEquals(11, knight.tileY(),
                "the first leftover dest-arms south-east onto the free skirt");
        assertEquals(1, knight.pathLength(),
                "dest-arm leftover remaining is one heading after the dest-arm "
                        + "step; a full pathfind leftover residual-opens past OP0");
        assertSame(ogre, knight.target(),
                "the free-scan may name the ogre after the leftover is written");
    }

    @Test
    @DisplayName("BNE target scoring reads UDTA priority instead of ChonkCraft priority")
    void unitDataPriorityBreaksTheCannonTowerTie() {
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        int[] priorities = new int[110];
        priorities[0] = 60;   // footman
        priorities[98] = 40;  // human cannon tower
        world.setBattleNetUnitPriorities(priorities);

        UnitType gruntType = fighter("unit-grunt", 50);
        gruntType.setMaxAttackRange(1);
        Unit guard = world.createUnit(gruntType, 0, 4, 4);
        UnitType towerType = fighter("unit-human-cannon-tower", 60);
        towerType.setTileSize(2, 2);
        towerType.setBuilding(true);
        Unit tower = world.createUnit(towerType, 1, 8, 2);
        Unit footman = world.createUnit(fighter("unit-footman", 60), 1, 8, 5);
        guard.setBattleNetAnimationTimer(2);
        tower.setBattleNetAnimationTimer(8);
        footman.setBattleNetAnimationTimer(8);

        world.tick();
        world.tick();

        assertSame(footman, guard.target(),
                "BNE's 40-point cannon tower loses to its 60-point footman");
    }

    @Test
    @DisplayName("BNE towers fire only at their action-14 script marker")
    void armedTowerUsesNativeAttackSequenceTiming() {
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        world.setBattleNetSequenceData(towerSequence());

        UnitType towerType = fighter("unit-human-guard-tower", 50);
        towerType.setBuilding(true);
        towerType.setTileSize(2, 2);
        towerType.setSpeed(0);
        Unit tower = world.createUnit(towerType, 0, 4, 4);
        Unit victim = world.createUnit(fighter("unit-grunt", 50), 1, 7, 4);
        tower.setBattleNetAnimationTimer(1);
        victim.setBattleNetAnimationTimer(8);
        int initialHp = victim.hitPoints();

        world.tick(); // Still action marker promotes the tower to action 14.
        world.tick(); // attack timer 3 -> 2
        world.tick(); // attack timer 2 -> 1
        assertEquals(initialHp, victim.hitPoints(),
                "the promotion delay is three calls; no shot yet");

        world.tick(); // action-14 marker fires
        // This fixture tower has no missile type, so hit() applies damage
        // immediately. BNE arrows defer one projectile pass via action 6
        // (see BattleNetMissileMotionTest).
        assertEquals(initialHp - 1, victim.hitPoints(),
                "the first action-14 marker looses one shot");
        assertEquals(Unit.Order.STILL, tower.order());
        assertNull(tower.target(), "action 14 scans afresh and retains no goal");
    }

    @Test
    @DisplayName("stationary action 16 defers still one visit after the last recovery tick")
    void stationaryAction16DefersStillOneVisitAfterLastRecoveryTick() {
        // XHuman 2 footman 1548: post-swing recovery leaves timer==1 on the
        // out-of-range visit; finishing that visit is fixture 39 while native
        // keeps action 16 through 40. Arm the hold on timer==1, finish the
        // next visit. Never-swung action 16 (Human 9 destroyers) still drops
        // immediately via the !fighting arm.
        GameMap map = new GameMap(20, 20, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        world.setBattleNetSequenceData(towerSequence());
        UnitType footType = fighter("unit-footman", 60);
        footType.setMaxAttackRange(1);
        footType.setCanTargetLand(true);
        UnitType ogreType = fighter("unit-ogre", 90);
        ogreType.setCanTargetLand(true);
        Unit foot = world.createUnit(footType, 1, 5, 5);
        // Chebyshev 3: outside melee range.
        Unit ogre = world.createUnit(ogreType, 0, 8, 5);
        assertTrue(world.orderAttack(foot, ogre),
                "footman accepts the attack order");
        foot.setBattleNetStationaryAttack(true);
        foot.setFighting(true);
        foot.setChasing(false);
        foot.setBattleNetSequenceOffset(230);
        foot.setBattleNetAnimationTimer(1);
        foot.setBattleNetStationaryRecoveryHeld(false);

        world.combat.stepAttack(foot);
        assertEquals(Unit.Order.ATTACK, foot.order(),
                "timer==1 recovery must keep action 16 for one more visit");
        assertTrue(foot.battleNetStationaryRecoveryHeld(),
                "the recovery hold must arm for the deferred Still");

        world.combat.stepAttack(foot);
        assertEquals(Unit.Order.STILL, foot.order(),
                "the following visit must drop action 16 to Still");
        assertFalse(foot.battleNetStationaryRecoveryHeld(),
                "finishing must clear the recovery hold");
    }

    @Test
    @DisplayName("person melee brothers answer a splash kill of an ally")
    void personMeleeBrothersAnswerASplashKillOfAnAlly() {
        // XHuman 10: catapult splash kills footman 1492; Still knight 1489
        // (Data-marked, weapon-range idle only) queues Attack toward the
        // catapult. Splash used to skip HitUnit help, so brothers stayed Still.
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        UnitType footType = fighter("unit-footman", 10);
        footType.setMaxAttackRange(1);
        footType.setReactRangePerson(5);
        UnitType knightType = fighter("unit-knight", 50);
        knightType.setMaxAttackRange(1);
        knightType.setReactRangePerson(6);
        knightType.setCanTargetLand(true);
        UnitType catType = fighter("unit-catapult", 50);
        catType.setMaxAttackRange(8);
        catType.setCanTargetLand(true);

        Unit foot = world.createUnit(footType, 1, 20, 20);
        Unit knight = world.createUnit(knightType, 1, 22, 20);
        Unit catapult = world.createUnit(catType, 0, 10, 20);
        assertTrue(foot != null && knight != null && catapult != null);
        knight.setBattleNetReadySuppressed(true);
        knight.setBattleNetAnimationTimer(8);
        foot.setBattleNetAnimationTimer(8);
        catapult.setBattleNetAnimationTimer(8);

        // Lethal splash path (not ordinary hits) must recruit person melee help.
        world.combat.noteAttacked(catapult, foot);
        world.battleNetPersonMeleeHelpOnSplash(catapult, foot);
        world.kill(foot, catapult);

        assertSame(catapult, knight.battleNetPendingHelpAttack(),
                "person melee brother next to the ally must be pending help");

        // Promote on the next Still visit of the owner.
        for (int i = 0; i < 4; i++) {
            world.tick();
            if (knight.order() == Unit.Order.ATTACK) {
                break;
            }
        }
        assertEquals(Unit.Order.ATTACK, knight.order(),
                "pending person help must promote the knight to Attack");
        assertSame(catapult, knight.target(),
                "help attack targets the splash source catapult");
    }

    @Test
    @DisplayName("a person-help first chase prefers an equal-cost goal-axis diagonal onto a lead brother")
    void aPersonHelpFirstChasePrefersAnEqualCostGoalAxisDiagonalOntoALeadBrother() {
        // XHuman 10 knights: lead 1489 already west on the catapult row;
        // trailer 1493 one row north. Person-help first chase must open SW
        // onto the brother, not free pure-west (fixture 48 x 83 vs 84).
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map, opponents());
        UnitType knightType = fighter("unit-knight", 50);
        knightType.setMaxAttackRange(1);
        knightType.setReactRangePerson(6);
        knightType.setCanTargetLand(true);
        AnimationSet knightAnim = new AnimationSet("knight");
        knightAnim.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        knightAnim.put(AnimationSet.State.MOVE,
                Animation.parse("move", List.of(
                        "unbreakable begin",
                        "frame 0", "move 16", "wait 1",
                        "frame 0", "move 16", "wait 1",
                        "unbreakable end", "wait 1")));
        knightAnim.put(AnimationSet.State.ATTACK,
                Animation.parse("attack", List.of("frame 0", "wait 1",
                        "attack", "wait 1")));
        knightType.setAnimationSet(knightAnim);
        UnitType catType = fighter("unit-catapult", 50);
        catType.setMaxAttackRange(8);
        catType.setCanTargetLand(true);

        Unit lead = world.createUnit(knightType, 1, 12, 10);
        Unit trailer = world.createUnit(knightType, 1, 13, 9);
        Unit catapult = world.createUnit(catType, 0, 5, 10);
        assertTrue(lead != null && trailer != null && catapult != null);
        lead.setBattleNetReadySuppressed(true);
        trailer.setBattleNetReadySuppressed(true);
        lead.setBattleNetAnimationTimer(8);
        trailer.setBattleNetAnimationTimer(8);
        catapult.setBattleNetAnimationTimer(8);

        assertTrue(world.orderAttack(lead, catapult), "lead attack accepted");
        lead.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {
                        net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(-1, 0),
                        net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(-1, 0)
                }));
        lead.setPathGoal(catapult.tileX(), catapult.tileY());
        lead.setChasing(true);
        lead.setOffset(-16, 0);
        lead.setWalkHolding(true);

        assertTrue(world.orderAttack(trailer, catapult),
                "trailer attack accepted");
        // Same flag person-help promote sets before the first chase path.
        trailer.setBattleNetPersonHelpFirstChase(true);

        assertEquals(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                world.planTowards(trailer, catapult),
                "trailer must path toward the catapult");
        assertTrue(trailer.pathLength() > 0,
                "person-help first chase must install a route");
        assertEquals(
                net.chonkbase.chonkcraft.engine.map.Direction.fromDelta(-1, 1),
                trailer.peekHeading(),
                "equal-cost goal-axis diagonal must open SW onto the lead "
                        + "brother, not free pure west");
        assertFalse(trailer.battleNetPersonHelpFirstChase(),
                "first-chase flag clears once the opening path is installed");
    }
}
