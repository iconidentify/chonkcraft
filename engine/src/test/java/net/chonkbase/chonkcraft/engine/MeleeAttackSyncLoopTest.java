package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Table-0x27 melee re-seeds unit+0xb every attack animation loop.
 *
 * <p>Human 5 standing grunt 1531 draws SyncRand at fixture 6 then again at
 * 31 (twenty-five cycles) while remaining on the barracks; chasers 1528 and
 * 1532 draw at 22 then 47. A one-shot pending flag left Java on seed
 * 2781e494 at fixture 31 while native advanced to c46b9b3d.
 */
class MeleeAttackSyncLoopTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static byte[] retailScriptBin() throws IOException {
        String packProp = System.getProperty("chonkcraft.pack");
        Path pack = packProp != null && !packProp.isBlank()
                ? Path.of(packProp)
                : Path.of(System.getProperty("user.home"),
                        ".chonkcraft/work",
                        "warcraft-ii-battle-net-edition-usa.pre-full-media-2026-07-30.chonkpack");
        assumeTrue(Files.isRegularFile(pack),
                "BNE asset pack required for retail Attack sequence");
        try (ZipFile zip = new ZipFile(pack.toFile())) {
            var entry = zip.getEntry("assets/archives/maindat/0278.bin");
            assumeTrue(entry != null, "pack must contain maindat entry 278");
            try (var in = zip.getInputStream(entry)) {
                return in.readAllBytes();
            }
        }
    }

    private static UnitType grunt() {
        UnitType type = new UnitType("unit-grunt");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setPiercingDamage(3);
        type.setMaxAttackRange(1);
        type.setSightRange(4);
        type.setReactRangeComputer(4);
        type.setReactRangePerson(4);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("grunt");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 0", "wait 3", "attack",
                "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static UnitType prey() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(500);
        type.setLandUnit(true);
        type.setArmor(0);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("prey");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    @DisplayName("a standing melee re-seeds SyncRand every twenty-five attack cycles")
    void aStandingMeleeReseedsSyncRandEveryTwentyFiveAttackCycles()
            throws Exception {
        // Human 5 grunt 1531 at 49,91: first 0x4234b0 at fixture 6, next at
        // 31 while still swinging at the barracks. The sealed gap is 25.
        byte[] script = retailScriptBin();
        GameMap map = grass(24);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.restoreRandom(1, 0);

        Unit attacker = world.createUnit(grunt(), 0, 10, 10);
        Unit target = world.createUnit(prey(), 1, 10, 11);
        assertTrue(attacker != null && target != null, "units must place");
        assertTrue(world.orderAttack(attacker, target), "attack accepted");
        attacker.setBattleNetOrderDelay(0);
        attacker.setChasing(false);
        attacker.setFighting(true);
        attacker.setAutoTargeting(false);

        int seed = world.randomSeed();
        int firstDraw = -1;
        for (int call = 0; call < 80; call++) {
            world.tick();
            if (world.randomSeed() != seed) {
                firstDraw = call + 1;
                seed = world.randomSeed();
                break;
            }
        }
        assertTrue(firstDraw > 0,
                "first in-range Attack marker must debit table-0x27 SyncRand");
        assertTrue(attacker.battleNetMeleeSyncRemaining() > 0,
                "first debit arms the twenty-five-cycle attack loop");

        int secondDraw = -1;
        for (int call = firstDraw; call < firstDraw + 40; call++) {
            world.tick();
            if (world.randomSeed() != seed) {
                secondDraw = call + 1;
                break;
            }
        }
        assertTrue(secondDraw > 0,
                "attack animation loop must re-seed SyncRand; Human 5 seed "
                        + "stayed on 2781e494 at fixture 31 without the loop");
        assertEquals(25, secondDraw - firstDraw,
                "melee attack-loop SyncRand repeats every twenty-five cycles "
                        + "(native 1531: 6 then 31); gap was "
                        + (secondDraw - firstDraw));
    }

    @Test
    @DisplayName("a refused first attack heading enters the native move wait")
    void aRefusedFirstAttackHeadingEntersTheNativeMoveWait()
            throws Exception {
        // XHuman 4 grunt 1505: Attack OP0 @2539/1 lays W,SW,W,W,W,
        // refuses W on an ally, then records Move @2482/15 while the order
        // remains Attack. The axethrower beside it does the same at 830/15.
        byte[] script = retailScriptBin();
        GameMap map = grass(24);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);

        Unit attacker = world.createUnit(grunt(), 0, 10, 10);
        Unit blocker = world.createUnit(grunt(), 0, 9, 10);
        Unit target = world.createUnit(prey(), 1, 4, 10);
        assertTrue(attacker != null && blocker != null && target != null,
                "attacker, ally and quarry must place");
        blocker.setOrder(Unit.Order.MOVE);
        blocker.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {net.chonkbase.chonkcraft.engine.map.Direction
                        .fromDelta(-1, 0)}));
        blocker.setPathGoal(8, 10);
        blocker.animation().switchTo(
                blocker.type().animationSet().get(AnimationSet.State.MOVE));
        assertTrue(world.orderAttack(attacker, target), "attack accepted");
        attacker.setBattleNetOrderDelay(0);
        attacker.setChasing(false);
        attacker.setFighting(false);
        int west = net.chonkbase.chonkcraft.engine.map.Direction
                .fromDelta(-1, 0);
        attacker.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {west}));
        attacker.setPathGoal(target.tileX(), target.tileY());
        int attackStart = world.idle.battleNetSequenceStart(attacker,
                net.chonkbase.chonkcraft.engine.animation.BattleNetSequence
                        .ATTACK_ANIMATION);
        int moveStart = world.idle.battleNetSequenceStart(attacker,
                net.chonkbase.chonkcraft.engine.animation.BattleNetSequence
                        .MOVE_ANIMATION);
        assumeTrue(attackStart >= 0 && moveStart >= 0,
                "retail Move and Attack sequences must resolve");
        attacker.setBattleNetSequenceOffset(attackStart);
        attacker.setBattleNetAnimationTimer(1);

        world.combat.stepAttack(attacker);

        assertEquals(Unit.Order.ATTACK, attacker.order());
        assertEquals(moveStart, attacker.battleNetSequenceOffset(),
                "refusal belongs to Move even though Attack owns the order");
        assertEquals(15, attacker.battleNetAnimationTimer());
        assertEquals(1, attacker.battleNetCollisionCounter(),
                "cooperative refusal raises the native collision nibble");
    }

    @Test
    @DisplayName("a residual settle in weapon range pays table-0x27 SyncRand that visit")
    void aResidualSettleInWeaponRangePaysTable0x27SyncRandThatVisit()
            throws Exception {
        // Human 13 wise-man 1496 / grunt 1507 debit on the settle visit at
        // fixture 36. Waiting for the next top-of-stepAttack left only one
        // of the two native draws in c36.
        byte[] script = retailScriptBin();
        GameMap map = grass(24);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.restoreRandom(1, 0);

        Unit attacker = world.createUnit(grunt(), 0, 10, 8);
        Unit target = world.createUnit(prey(), 1, 10, 11);
        assertTrue(attacker != null && target != null, "units must place");
        assertTrue(world.orderAttack(attacker, target), "attack accepted");
        attacker.setBattleNetOrderDelay(0);

        int seed = world.randomSeed();
        int firstDraw = -1;
        boolean sawMoving = false;
        for (int call = 0; call < 80; call++) {
            if (attacker.isMoving() || attacker.walkHolding()) {
                sawMoving = true;
            }
            world.tick();
            if (world.randomSeed() != seed) {
                firstDraw = call + 1;
                break;
            }
        }
        assertTrue(sawMoving,
                "chase must walk residual pixels before the first SyncRand");
        assertTrue(firstDraw > 0,
                "residual settle in weapon range must debit table-0x27 "
                        + "SyncRand that visit; Human 13 c36 was one draw short");
        assertTrue(attacker.battleNetMeleeSyncRemaining() > 0,
                "first settle debit arms the twenty-five-cycle attack loop");
        assertTrue(!attacker.isMoving() && !attacker.walkHolding(),
                "first debit lands once residual pixels have settled");
    }

    @Test
    @DisplayName("a chase arrival that reaches melee switches off the Move body onto Attack")
    void aChaseArrivalThatReachesMeleeSwitchesOntoAttackSequence()
            throws Exception {
        // Human 5 grunts 1528/1532 kept Move-body offsets after the approach
        // step, so opcode 10 never resolved pending melee and the barracks
        // only took axe damage at fixture 32 (789 vs native 784).
        byte[] script = retailScriptBin();
        GameMap map = grass(24);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.restoreRandom(1, 0);

        Unit attacker = world.createUnit(grunt(), 0, 10, 8);
        Unit target = world.createUnit(prey(), 1, 10, 11);
        assertTrue(attacker != null && target != null, "units must place");
        assertTrue(world.orderAttack(attacker, target), "attack accepted");
        attacker.setBattleNetOrderDelay(0);

        int attackStart = -1;
        int moveStart = -1;
        // Discover sequence starts from the live unit after first ticks.
        Integer barracksDamage = null;
        for (int call = 0; call < 80; call++) {
            int hpBefore = target.hitPoints();
            world.tick();
            if (target.hitPoints() < hpBefore && !attacker.isMoving()
                    && attacker.tileY() >= 10) {
                // First melee or any damage after arrival.
                int off = attacker.battleNetSequenceOffset();
                // After arrival and fighting, offset must not sit on Move.
                assertTrue(off < 0
                                || off >= 2539
                                || attacker.chasing()
                                || attacker.isMoving(),
                        "in-range melee must leave the Move body for Attack; "
                                + "offset was " + off + " after call " + call);
                if (target.hitPoints() < hpBefore
                        && attacker.distanceTo(target) <= 1
                        && !attacker.isMoving()) {
                    barracksDamage = hpBefore - target.hitPoints();
                    break;
                }
            }
        }
        // Walk until adjacent and fighting has had time to land a blow.
        for (int call = 0; call < 120 && barracksDamage == null; call++) {
            int hpBefore = target.hitPoints();
            world.tick();
            if (attacker.distanceTo(target) <= 1 && !attacker.isMoving()
                    && !attacker.chasing()
                    && target.hitPoints() < hpBefore) {
                barracksDamage = hpBefore - target.hitPoints();
                int off = attacker.battleNetSequenceOffset();
                assertTrue(off < 0 || off >= 2539,
                        "melee damage must come from Attack sequence not Move; "
                                + "offset " + off);
            }
        }
        assertTrue(barracksDamage != null && barracksDamage > 0,
                "a chase grunt that reaches the quarry must land melee damage "
                        + "(Human 5 1528/1532 at fixture 32)");
    }

    @Test
    @DisplayName("a single leftover chase heading defers table-0x27 SyncRand past residual settle")
    void aSingleLeftoverChaseHeadingDefersTable0x27SyncRandPastResidualSettle()
            throws Exception {
        // Human 13 grunt 1485 keeps route index 1 beside wise-man 1496 from
        // fixture 25 through 43 and only spends FUN_004234b0 at F43. Java used
        // to residual-settle that leftover and debit at world 43 (fixture 41).
        byte[] script = retailScriptBin();
        GameMap map = grass(24);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.restoreRandom(1, 0);

        Unit attacker = world.createUnit(grunt(), 0, 10, 10);
        Unit target = world.createUnit(prey(), 1, 10, 11);
        assertTrue(attacker != null && target != null, "units must place");
        assertTrue(world.orderAttack(attacker, target), "attack accepted");
        attacker.setBattleNetOrderDelay(0);
        attacker.setChasing(true);
        attacker.setFighting(false);
        // One leftover heading while already in weapon range -- native 1485.
        // Heading 0 is north; value is irrelevant while the leftover is unpaid.
        attacker.setPath(new net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Path(
                net.chonkbase.chonkcraft.engine.pathfinder.PathFinder.Result.FOUND,
                new int[] {0}));
        assertEquals(1, attacker.pathLength(),
                "fixture installs a single leftover chase heading");
        assertTrue(attacker.battleNetPendingMeleeSyncRand(),
                "melee order must leave table-0x27 pending until first in-range");

        int seed = world.randomSeed();
        // While the single leftover remains, residual settle must not spend
        // SyncRand (native keeps index 1 unpaid through fixture 43).
        for (int call = 0; call < 12; call++) {
            world.tick();
            if (attacker.pathLength() == 1 && attacker.chasing()) {
                assertEquals(seed, world.randomSeed(),
                        "pathLen-1 residual settle must not pay table-0x27; "
                                + "Human 13 grunt 115 seed jumped at fixture 41");
                assertTrue(attacker.battleNetPendingMeleeSyncRand(),
                        "table-0x27 must stay pending while the leftover heading "
                                + "remains");
            }
        }
    }

    @Test
    @DisplayName("a residual settle beside a dying quarry does not pay table-0x27 SyncRand")
    void aResidualSettleBesideADyingQuarryDoesNotPayTable0x27SyncRand()
            throws Exception {
        // XHuman 10 grunt 105 residual-settled at 81,89 beside footman 108
        // after that footman was already DYING. Java spent FUN_004234b0 on
        // that visit (world c53) and advanced the shared seed past native's
        // two-draw ledger (only the live footmen paid). Native never draws
        // for 1495 through fixture 55.
        byte[] script = retailScriptBin();
        GameMap map = grass(24);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.restoreRandom(0x967eb0e7, 0);

        Unit attacker = world.createUnit(grunt(), 0, 10, 10);
        Unit quarry = world.createUnit(prey(), 1, 11, 10);
        assertTrue(attacker != null && quarry != null, "units must place");
        assertTrue(world.orderAttack(attacker, quarry), "attack accepted");
        attacker.setBattleNetOrderDelay(0);
        attacker.setChasing(true);
        attacker.setFighting(false);
        // Residual of the approach step draining on an empty path -- the
        // walked && pathLen-0 shape of grunt 105 at the extra debit.
        attacker.setOffset(-16, 0);
        attacker.clearPath();
        assertTrue(attacker.isMoving(),
                "fixture installs residual pixels of the approach step");
        assertTrue(attacker.battleNetPendingMeleeSyncRand(),
                "melee order leaves table-0x27 pending");
        // Footman is already playing its death animation when residual ends.
        quarry.setOrder(Unit.Order.DYING);
        assertTrue(quarry.isDying() && !quarry.isAlive(),
                "quarry is DYING and no longer isAlive for SyncRand");
        assertTrue(world.targets.inAttackRange(attacker, quarry),
                "arrival tile still reports range of the dying footprint");

        int seedBefore = world.randomSeed();
        for (int call = 0; call < 40; call++) {
            world.tick();
            assertEquals(seedBefore, world.randomSeed(),
                    "residual settle beside a DYING quarry must not pay "
                            + "table-0x27; XHuman 10 seed jumped at fixture 51");
            if (!attacker.isMoving() && !attacker.walkHolding()) {
                break;
            }
        }
        assertTrue(!attacker.isMoving() && !attacker.walkHolding(),
                "residual must finish so the settle visit is observed");
        assertEquals(seedBefore, world.randomSeed(),
                "after residual settle seed must still match native's "
                        + "two-draw ledger");
    }

    @Test
    @DisplayName("an approach residual settle into range pays the first table-0x27 SyncRand")
    void anApproachResidualSettleIntoRangePaysTheFirstTable0x27SyncRand()
            throws IOException {
        // XHuman 12 grunt 225 (native 1375) residual-settles onto 12,86 in
        // range of tower 13,86 at fixture 40 with order-time pending still
        // unpaid. stepMoveTowardsTarget flips temporarily to MOVE so Attack
        // OP0 never consumes FUN_004234b0; native caller 0x4234CD advanced
        // 2781e494→c46b9b3d on that arrival. Drive the temporary MOVE path
        // only -- full ticks would also debit via Attack OP0 and hide the fix.
        byte[] script = retailScriptBin();
        GameMap map = grass(24);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.restoreRandom(0x2781e494, 0);

        Unit attacker = world.createUnit(grunt(), 0, 11, 11);
        Unit tower = world.createUnit(prey(), 1, 12, 11);
        assertTrue(attacker != null && tower != null, "units must place");
        tower.setHitPoints(500);
        assertTrue(world.orderAttack(attacker, tower), "attack accepted");
        attacker.setBattleNetOrderDelay(0);
        // Adjacent after the approach step: residual still sliding, path empty,
        // pending unpaid -- the fixture-40 shape for grunt 225.
        attacker.setTile(11, 11);
        attacker.setOffset(-16, 0);
        attacker.clearPath();
        attacker.setChasing(true);
        attacker.setFighting(false);
        assertTrue(attacker.battleNetPendingMeleeSyncRand(),
                "melee order must leave table-0x27 pending for the approach");
        assertTrue(attacker.isMoving(),
                "fixture installs residual pixels of the approach step");
        assertTrue(world.targets.inAttackRange(attacker, tower),
                "arrival tile is already in weapon range");

        int seedBefore = world.randomSeed();
        boolean paid = false;
        for (int call = 0; call < 40; call++) {
            world.combat.stepMoveTowardsTarget(attacker);
            if (world.randomSeed() != seedBefore) {
                paid = true;
                assertTrue(!attacker.isMoving(),
                        "SyncRand must land on the residual-zero visit");
                break;
            }
        }
        assertTrue(paid,
                "approach residual settle into range must pay FUN_004234b0 via "
                        + "stepMoveTowardsTarget; seed stayed "
                        + Integer.toHexString(seedBefore));
        assertTrue(attacker.battleNetMeleeSyncRemaining() > 0,
                "first debit must arm the twenty-five-cycle attack loop");
    }
}
