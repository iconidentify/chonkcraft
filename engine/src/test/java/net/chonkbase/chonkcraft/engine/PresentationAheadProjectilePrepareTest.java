package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipFile;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.missile.MissileClass;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mobile projectile constructor draws belong on the presentation impact frame
 * when the Attack wait still has more than one tick before opcode ten.
 *
 * <p>XHuman 12's axe fired on internal cycle 33 while the Attack sequence
 * sat mid-wait (timer 3 before OP10). Without presentation-ahead prepare the
 * three constructor draws (damage + two aim jitters) waited until OP10 at
 * cycle 36, leaving the asynchronous stream three draws short for the next
 * melee rem on tower 1370 (dmg 3 vs native 2 at fixture 32).
 */
class PresentationAheadProjectilePrepareTest {

    @Test
    @DisplayName("a ranged attack sound waits for its visible BNE projectile")
    void rangedAttackSoundWaitsForTheVisibleProjectile() throws Exception {
        World world = new World(grass(16));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(retailScriptBin());
        world.setMissileTypes(Map.of("missile-axe", axeMissile()));

        UnitType trollType = axethrower();
        trollType.animationSet().put(AnimationSet.State.ATTACK,
                Animation.parse("attack-with-sound", List.of(
                        "unbreakable begin", "frame 40", "attack",
                        "sound axethrower-attack", "wait 1",
                        "unbreakable end", "wait 1")));
        Unit troll = world.createUnit(trollType, 0, 4, 4);
        Unit target = world.createUnit(destroyer(), 1, 8, 4);
        assertTrue(troll != null && target != null, "units place");

        world.strike(troll, target);
        assertEquals(1, world.missiles().size(),
                "the presentation frame must own one pending axe");
        Missile pending = world.missiles().get(0);
        assertFalse(world.missileVisible(pending));
        assertTrue(world.drainSoundEvents().isEmpty(),
                "the invisible placeholder made a projectile sound");

        // Opcode ten takes ownership out of the presentation placeholder map
        // immediately before it arms the constructor.
        world.battleNetPendingProjectileShots.remove(troll);
        world.prepareBattleNetProjectile(pending, true);

        assertTrue(world.missileVisible(pending));
        assertEquals(List.of(new World.SoundEvent(
                        troll, "axethrower-attack", true)),
                world.drainSoundEvents(),
                "the throw sound must coincide with the real projectile");
    }

    @Test
    @DisplayName("stand-ground OP10 preserves birth but defers constructor to cycle end")
    void standGroundOp10DefersOnlyTheConstructorToCycleEnd() throws Exception {
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        int attackStart = sequence.sequenceStart(
                net.chonkbase.chonkcraft.data.map.PudUnitTypes.code(
                        "unit-axethrower"),
                BattleNetSequence.ATTACK_ANIMATION);
        assumeTrue(attackStart >= 0, "axethrower Attack sequence must exist");
        int op10 = -1;
        for (int off = attackStart; off < attackStart + 64; off++) {
            if (sequence.opcodeAt(off) == 10) {
                op10 = off;
                break;
            }
        }
        assumeTrue(op10 >= 0, "axethrower Attack must contain opcode ten");

        World world = new World(grass(16));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        MissileType axe = new MissileType("missile-axe", null,
                MissileClass.POINT_TO_POINT, 32, 32, 1, 1, 12, 1, 1,
                0, 0, null, null, false, 0, 0, false, null, 0);
        world.setMissileTypes(Map.of("missile-axe", axe));
        world.restoreRandom(1, 0);
        Unit attacker = world.createUnit(axethrower(), 0, 2, 2);
        Unit victim = world.createUnit(destroyer(), 1, 6, 2);
        assertTrue(attacker != null && victim != null, "units place");
        attacker.setOrder(Unit.Order.STAND_GROUND);
        attacker.setTarget(victim);
        attacker.setFighting(true);
        attacker.setBattleNetSequenceOffset(op10);
        attacker.setBattleNetAnimationTimer(1);

        int seedBefore = world.battleNetRandomSeed();
        world.combat.stepBattleNetAttackSequence(attacker);
        assertEquals(1, world.missiles().size(),
                "OP10, not a presentation callback, births the standing shot");
        Missile shot = world.missiles().get(0);
        assertFalse(shot.battleNetConstructorDrawn(),
                "later unit visits must precede constructor RNG");
        assertEquals(seedBefore, world.battleNetRandomSeed(),
                "birth itself cannot debit constructor RNG");
        world.projectiles.flushBattleNetCycleEndConstructorDebit();
        assertTrue(shot.battleNetConstructorDrawn(),
                "cycle end spends damage and aim draws");
        assertNotEquals(seedBefore, world.battleNetRandomSeed(),
                "the cycle-end constructor owns the three async draws");
        assertTrue(shot.battleNetMotion(),
                "the same cycle-end boundary arms flight");
    }

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

    private static UnitType axethrower() {
        UnitType type = new UnitType("unit-axethrower");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(40);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setCanTargetSea(true);
        type.setBasicDamage(3);
        type.setPiercingDamage(4);
        type.setMaxAttackRange(4);
        type.setSightRange(5);
        type.setReactRangeComputer(5);
        type.setReactRangePerson(5);
        type.setNumDirections(8);
        type.setMissile("missile-axe");
        AnimationSet set = new AnimationSet("axe");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 0", "wait 3", "attack",
                "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static UnitType tower() {
        UnitType type = new UnitType("unit-human-guard-tower");
        type.setTileSize(2, 2);
        type.setBoxSize(63, 63);
        type.setHitPoints(130);
        type.setBuilding(true);
        type.setLandUnit(true);
        type.setArmor(20);
        type.setNumDirections(1);
        AnimationSet set = new AnimationSet("tower");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static UnitType destroyer() {
        UnitType type = new UnitType("unit-elven-destroyer");
        type.setTileSize(2, 2);
        type.setBoxSize(63, 63);
        type.setHitPoints(100);
        type.setSeaUnit(true);
        type.setArmor(5);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("destroyer");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static UnitType cannonShip() {
        UnitType type = destroyer();
        type.setSpeed(10);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(12);
        type.setPiercingDamage(8);
        type.setMaxAttackRange(4);
        type.setSightRange(5);
        type.setReactRangeComputer(5);
        type.setReactRangePerson(5);
        type.setMissile("missile-small-cannon");
        type.animationSet().put(AnimationSet.State.ATTACK,
                Animation.parse("attack", List.of(
                        "unbreakable begin", "frame 0", "wait 3", "attack",
                        "unbreakable end", "wait 1")));
        return type;
    }

    private static MissileType smallCannon() {
        return new MissileType("missile-small-cannon", null,
                MissileClass.PARABOLIC, 32, 32, 15, 9, 22, 1, 2, 3, 50,
                "missile-cannon-tower-explosion", "explosion", false,
                0, 0, false, null, 0);
    }

    private static MissileType axeMissile() {
        // range 1: single target (not splash). Splash skips the damage-band
        // async draw and would leave only the two aim jitters.
        return new MissileType("missile-axe", null, MissileClass.POINT_TO_POINT,
                32, 32, 1, 1, 12, 1, 1, 0, 0, null, null, false, 0, 0, false,
                null, 0);
    }

    @Test
    @DisplayName("a mid-wait presentation axe runs its constructor draws before OP10")
    void aMidWaitPresentationAxeRunsConstructorDrawsBeforeOpcodeTen()
            throws Exception {
        // The sealed XHuman 12 gap: presentation impact with Attack timer 3
        // must debit the three FUN_0040fb10 draws now, not three waits later.
        byte[] script = retailScriptBin();
        GameMap map = grass(24);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.setMissileTypes(Map.of("missile-axe", axeMissile()));
        world.restoreRandom(1, 0);

        // Computer thrower: person shooters keep the OP10 deferral; the
        // sealed XHuman 12 axe is computer-owned.
        Unit thrower = world.createUnit(axethrower(), 1, 8, 10);
        Unit target = world.createUnit(tower(), 0, 10, 10);
        assertTrue(thrower != null && target != null, "units must place");
        // Mark player 1 computer so presentation-ahead applies.
        world.player(1).setType(
                net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER);
        assertTrue(world.orderAttack(thrower, target), "attack accepted");
        thrower.setBattleNetOrderDelay(0);
        thrower.setChasing(false);
        thrower.setFighting(true);
        thrower.setAutoTargeting(false);

        int attackStart = new BattleNetSequence(script).sequenceStart(
                net.chonkbase.chonkcraft.data.map.PudUnitTypes.code(
                        thrower.type().ident()),
                BattleNetSequence.ATTACK_ANIMATION);
        assumeTrue(attackStart >= 0, "axethrower Attack sequence must exist");
        // Mid-wait before OP10: timer 3 is the sealed Attack wait that
        // presentation reaches while OP10 is still three ticks away.
        thrower.setBattleNetSequenceOffset(attackStart);
        thrower.setBattleNetAnimationTimer(3);

        int asyncBefore = world.battleNetRandomSeed();
        world.hit(thrower, target);

        int after = world.battleNetRandomSeed();
        assertTrue(after != asyncBefore,
                "presentation-ahead axe must debit the async constructor "
                        + "stream on the impact frame");
        // Count LCG steps as unsigned so overflow matches battleNetRand.
        long seed = Integer.toUnsignedLong(asyncBefore);
        long want = Integer.toUnsignedLong(after);
        int draws = 0;
        while (seed != want && draws < 8) {
            seed = (seed * 0x015a4e35L + 1L) & 0xffff_ffffL;
            draws++;
        }
        assertEquals(3, draws,
                "mobile constructor spends three async draws (damage + two "
                        + "aim jitters); XHuman 12 was three short before the "
                        + "tower melee rem");

        Missile armed = null;
        for (Missile shot : world.missiles()) {
            if (shot.battleNetConstructorDrawn()) {
                armed = shot;
                break;
            }
        }
        assertTrue(armed != null,
                "presentation-ahead must mark constructor draws spent");
        assertTrue(!armed.battleNetMotion(),
                "flight must still wait for OP10; early motion pulled Human "
                        + "13's critter stream (still vs MOVE at fixture 34)");
        assertEquals(1, thrower.battleNetAnimationTimer(),
                "building-target presentation must collapse Attack wait to 1 "
                        + "so OP10 arms flight next visit (XHuman 12 axe "
                        + "127→tower: timer 3 left flight three cycles late "
                        + "and the rock splash rolled two draws short)");
    }

    @Test
    @DisplayName("a building mid-wait axe spends constructor draws before later reverse-order idles")
    void aBuildingMidWaitAxeSpendsConstructorDrawsBeforeLaterReverseOrderIdles()
            throws Exception {
        // Native walks the unit pool low-to-high; Java reverse-creation
        // processes high id first. On XHuman 12 fixture 31, axe 127 (native
        // 1473) fires mid-pool and only then do lower-id Still OP0s (knight
        // 125 / native 1475) draw idle. Cycle-end constructor debit spent
        // those three draws after every later idle, shifting the async
        // stream so fixture-38 footman melee rolled 5+4 instead of 6+6.
        // While the unit loop is active (ticking), the building mid-wait
        // path must debit on the axe visit -- not queue for the loop tail.
        byte[] script = retailScriptBin();
        GameMap map = grass(24);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.setMissileTypes(Map.of("missile-axe", axeMissile()));
        world.restoreRandom(1, 0);

        Unit thrower = world.createUnit(axethrower(), 1, 8, 10);
        Unit target = world.createUnit(tower(), 0, 10, 10);
        assertTrue(thrower != null && target != null, "units must place");
        world.player(1).setType(
                net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER);
        assertTrue(world.orderAttack(thrower, target), "attack accepted");
        thrower.setBattleNetOrderDelay(0);
        thrower.setChasing(false);
        thrower.setFighting(true);
        thrower.setAutoTargeting(false);
        int attackStart = new BattleNetSequence(script).sequenceStart(
                net.chonkbase.chonkcraft.data.map.PudUnitTypes.code(
                        thrower.type().ident()),
                BattleNetSequence.ATTACK_ANIMATION);
        assumeTrue(attackStart >= 0, "axethrower Attack sequence must exist");
        thrower.setBattleNetSequenceOffset(attackStart);
        thrower.setBattleNetAnimationTimer(3);

        int asyncBefore = world.battleNetRandomSeed();
        // Simulate the mid-loop visit: old code only flushed cycle-end when
        // not ticking, so a live HandleEachCycle left the trio queued until
        // after every later reverse-order idle.
        world.ticking = true;
        world.hit(thrower, target);
        world.ticking = false;

        int afterHit = world.battleNetRandomSeed();
        long seed = Integer.toUnsignedLong(asyncBefore);
        long want = Integer.toUnsignedLong(afterHit);
        int draws = 0;
        while (seed != want && draws < 8) {
            seed = (seed * 0x015a4e35L + 1L) & 0xffff_ffffL;
            draws++;
        }
        assertEquals(3, draws,
                "while the unit loop is active, building mid-wait must spend "
                        + "the three constructor draws on the axe visit so "
                        + "later reverse-order idles do not take those "
                        + "ordinals (XHuman 12 fixture 31→38 HP stream)");

        Missile armed = null;
        for (Missile shot : world.missiles()) {
            if (shot.battleNetConstructorDrawn()) {
                armed = shot;
                break;
            }
        }
        assertTrue(armed != null,
                "constructor draws must be spent before later idles run");
        assertTrue(!armed.battleNetMotion(),
                "flight still waits for OP10 after mid-visit constructor debit");
        assertTrue(world.battleNetCycleEndConstructorDebit.isEmpty(),
                "building mid-wait must not leave a cycle-end constructor "
                        + "queue that steals later idle ordinals");
    }

    @Test
    @DisplayName("presentation-ahead building flight starts on the debit cycle and spends the first stream draw")
    void presentationAheadBuildingFlightStartsOnTheDebitCycleAndSpendsTheFirstStreamDraw()
            throws Exception {
        // XHuman 12 axe→tower: native construction rem 146@33, first motion
        // 134@34 with 0041025A, free@47. OP10-as-start free'd@48 so start is
        // back-dated to the presentation debit cycle. Silencing that first
        // stream draw left native cycle 34 short one motion (16580) and free
        // splash took 20970 (tower 84) instead of 6888 (tower 92).
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        int attackStart = sequence.sequenceStart(
                net.chonkbase.chonkcraft.data.map.PudUnitTypes.code("unit-axethrower"),
                BattleNetSequence.ATTACK_ANIMATION);
        assumeTrue(attackStart >= 0, "axethrower Attack sequence must exist");
        int op10 = -1;
        for (int off = attackStart; off < attackStart + 64; off++) {
            if (sequence.opcodeAt(off) == 10) {
                op10 = off;
                break;
            }
        }
        assumeTrue(op10 >= 0, "axethrower Attack must contain opcode ten");

        GameMap map = grass(24);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.setMissileTypes(Map.of("missile-axe", axeMissile()));
        world.restoreRandom(1, 0);

        Unit thrower = world.createUnit(axethrower(), 1, 8, 10);
        Unit target = world.createUnit(tower(), 0, 10, 10);
        assertTrue(thrower != null && target != null, "units must place");
        world.player(1).setType(
                net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER);
        assertTrue(world.orderAttack(thrower, target), "attack accepted");
        thrower.setBattleNetOrderDelay(0);
        thrower.setChasing(false);
        thrower.setFighting(true);
        thrower.setAutoTargeting(false);
        thrower.setBattleNetSequenceOffset(op10);
        thrower.setBattleNetAnimationTimer(3);
        world.cycle = 10;
        world.hit(thrower, target);

        Missile shot = null;
        for (Missile m : world.missiles()) {
            if (m.battleNetConstructorDrawn()) {
                shot = m;
                break;
            }
        }
        assertTrue(shot != null, "presentation debits the constructor");
        assertTrue(!shot.battleNetMotion(),
                "flight still waits for OP10 after mid-visit debit");

        thrower.setBattleNetSequenceOffset(op10);
        thrower.setBattleNetAnimationTimer(1);
        world.cycle = 11;
        world.combat.stepBattleNetAttackSequence(thrower);

        assertTrue(shot.battleNetMotion(), "OP10 arms flight");
        Long started = world.battleNetProjectileStartCycles.get(shot);
        assertTrue(started != null, "start cycle recorded");
        assertEquals(10L, started.longValue(),
                "start cycle is the presentation debit cycle");
        assertFalse(shot.battleNetSkipNextMotionDraw(),
                "first flight step must spend 0041025A so free@35 keeps "
                        + "splash ordinal 6888");

        int seedBefore = world.battleNetRandomSeed();
        world.tick();
        assertNotEquals(seedBefore, world.battleNetRandomSeed(),
                "presentation-ahead first motion spends the async stream "
                        + "(native cycle 34 ends with four 0041025A draws)");
    }

    private static UnitType archer() {
        UnitType type = new UnitType("unit-archer");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(40);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(3);
        type.setPiercingDamage(6);
        type.setMaxAttackRange(4);
        type.setSightRange(5);
        type.setReactRangeComputer(5);
        type.setReactRangePerson(5);
        type.setNumDirections(8);
        type.setMissile("missile-arrow");
        AnimationSet set = new AnimationSet("arrow");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 0", "wait 3", "attack",
                "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
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
        type.setArmor(0);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("grunt");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static MissileType arrowMissile() {
        return new MissileType("missile-arrow", null, MissileClass.POINT_TO_POINT,
                32, 32, 0, 0, 12, 1, 1, 0, 0, null, null, false, 0, 0, false,
                null, 0);
    }

    @Test
    @DisplayName("opcode ten re-aims a pending arrow at a walking target before jitter")
    void opcodeTenReAimsAPendingArrowAtAWalkingTargetBeforeJitter() throws IOException {
        // XHuman 12 archer 150→grunt 152: presentation hit froze aim; OP10
        // FUN_0040fb10 must re-read the walking grunt before the two aim
        // jitters measure remaining (rem 134→131, free fixture 36→35).
        GameMap map = grass(32);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setMissileTypes(Map.of("missile-arrow", arrowMissile()));
        world.setBattleNetSequenceData(retailScriptBin());
        world.restoreRandom(1, 0);

        Unit shooter = world.createUnit(archer(), 1, 8, 10);
        Unit target = world.createUnit(grunt(), 0, 12, 10);
        assertTrue(shooter != null && target != null, "units must place");
        world.player(1).setType(
                net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.PERSON);
        target.setOffset(0, 0);
        target.setResidual(0, 0);

        // Presentation allocate: person unit-target stays pending until OP10.
        world.hit(shooter, target);
        assertEquals(1, world.missiles().size(), "one pending arrow");
        Missile pending = world.missiles().get(0);
        assertTrue(!pending.battleNetConstructorDrawn(),
                "unit-target constructor waits for OP10");
        int aimAtPresentation = (int) pending.toX();

        // Grunt walks three pixels east before the constructor boundary.
        target.setOffset(3, 0);
        int liveCentreX = target.pixelX() + target.residualX() + 16;

        world.prepareBattleNetProjectile(pending, true);
        assertTrue(pending.battleNetMotion(),
                "OP10 prepare must arm BNE motion");
        assertTrue(pending.battleNetConstructorDrawn(),
                "OP10 prepare must spend constructor draws");
        // Jitter is -3..4; live centre must be within that of the armed aim.
        assertTrue(Math.abs((int) pending.toX() - liveCentreX) <= 4,
                "armed aim must track the walked grunt centre " + liveCentreX
                        + " (presentation was " + aimAtPresentation
                        + ", armed " + (int) pending.toX() + ")");
        assertTrue((int) pending.toX() != aimAtPresentation,
                "constructor must not keep the presentation-frame aim when "
                        + "the target walked three pixels");
    }

    @Test
    @DisplayName("a firer killed before opcode ten leaves no phantom projectile")
    void interruptedPresentationShotIsCancelledWithItsAttackOrder() throws IOException {
        GameMap map = grass(32);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setMissileTypes(Map.of("missile-axe", axeMissile()));
        world.setBattleNetSequenceData(retailScriptBin());

        Unit shooter = world.createUnit(axethrower(), 1, 8, 10);
        Unit target = world.createUnit(grunt(), 0, 12, 10);
        world.player(1).setType(
                net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.PERSON);

        world.hit(shooter, target);
        assertEquals(1, world.missiles().size(), "the fixture needs one pending axe");
        Missile pending = world.missiles().get(0);
        assertFalse(pending.battleNetConstructorDrawn(),
                "the fixture must still be before opcode ten");
        int slot = pending.battleNetPoolSlot();

        world.kill(shooter);

        assertTrue(world.missiles().isEmpty(),
                "an unconstructed axe survived destruction of its attack order");
        assertTrue(world.battleNetPendingProjectileShots.isEmpty());
        assertFalse(world.battleNetProjectileSlots[slot],
                "the cancelled placeholder leaked its fixed BNE pool slot");
    }

    @Test
    @DisplayName("moving a siege unit cancels its pre-opcode projectile")
    void moveOrderCancelsPresentationShotAtTheOldMuzzle() throws IOException {
        World world = new World(grass(32));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setMissileTypes(Map.of("missile-axe", axeMissile()));
        world.setBattleNetSequenceData(retailScriptBin());

        Unit shooter = world.createUnit(axethrower(), 0, 8, 10);
        Unit target = world.createUnit(grunt(), 1, 12, 10);
        assertTrue(world.orderAttack(shooter, target));
        world.hit(shooter, target);

        assertEquals(1, world.missiles().size(), "one pre-opcode placeholder");
        Missile oldMuzzle = world.missiles().get(0);
        int oldSlot = oldMuzzle.battleNetPoolSlot();
        assertFalse(oldMuzzle.battleNetConstructorDrawn());

        assertTrue(world.orderMove(shooter, 8, 14), "move order accepted");

        assertFalse(world.missiles().contains(oldMuzzle),
                "the projectile sprite remained at the siege unit's old position");
        assertTrue(world.battleNetPendingProjectileShots.isEmpty(),
                "the replaced attack order still owned a projectile");
        assertFalse(world.battleNetProjectileSlots[oldSlot],
                "the cancelled placeholder leaked its projectile-pool slot");
    }

    @Test
    @DisplayName("one attacker can own only one pre-opcode projectile")
    void repeatedPresentationHitReplacesRatherThanOrphansThePlaceholder()
            throws IOException {
        World world = new World(grass(32));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setMissileTypes(Map.of("missile-axe", axeMissile()));
        world.setBattleNetSequenceData(retailScriptBin());

        Unit shooter = world.createUnit(axethrower(), 0, 8, 10);
        Unit target = world.createUnit(grunt(), 1, 12, 10);
        assertTrue(world.orderAttack(shooter, target));
        world.hit(shooter, target);
        Missile first = world.missiles().get(0);

        world.hit(shooter, target);

        assertEquals(1, world.missiles().size(),
                "overwriting the owner map left an unowned phantom missile");
        assertTrue(world.missiles().contains(first),
                "a duplicate callback replaced the attack order's original shot");
        assertEquals(first,
                world.battleNetPendingProjectileShots.get(shooter));
    }

    @Test
    @DisplayName("retargeting cancels the previous target's pre-opcode shot")
    void attackRetargetCannotCarryTheOldTargetsPlaceholder() throws IOException {
        World world = new World(grass(32));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setMissileTypes(Map.of("missile-axe", axeMissile()));
        world.setBattleNetSequenceData(retailScriptBin());

        Unit shooter = world.createUnit(axethrower(), 0, 8, 10);
        Unit firstTarget = world.createUnit(grunt(), 1, 12, 10);
        Unit secondTarget = world.createUnit(grunt(), 1, 12, 12);
        assertTrue(world.orderAttack(shooter, firstTarget));
        world.hit(shooter, firstTarget);
        Missile oldShot = world.missiles().get(0);

        assertTrue(world.orderAttack(shooter, secondTarget));

        assertFalse(world.missiles().contains(oldShot),
                "the new attack inherited the previous target's projectile");
        assertTrue(world.battleNetPendingProjectileShots.isEmpty());
    }

    @Test
    @DisplayName("loading a broken duplicate projectile save heals its owner list")
    void duplicateLegacySavePlaceholdersCollapseToOne() {
        World world = new World(grass(32));
        MissileType axe = axeMissile();
        world.setMissileTypes(Map.of("missile-axe", axe));
        Unit shooter = world.createUnit(axethrower(), 0, 8, 10);
        Unit target = world.createUnit(grunt(), 1, 12, 10);
        shooter.setOrder(Unit.Order.ATTACK);
        shooter.setTarget(target);

        Missile overwritten = new Missile(axe, shooter, target,
                272, 336, 400, 336);
        overwritten.setBattleNetPoolSlot(3);
        Missile owned = new Missile(axe, shooter, target,
                304, 336, 400, 336);
        owned.setBattleNetPoolSlot(4);

        // This is the schema produced by the bug: the older shot was still
        // serialized but no longer marked pending after the owner map was
        // overwritten by the newer one.
        world.restoreMissile("missile-axe", shooter, target,
                overwritten.savedState(), -1, 20, false);
        world.restoreMissile("missile-axe", shooter, target,
                owned.savedState(), -1, 21, true);

        assertEquals(1, world.missiles().size(),
                "load retained both the orphan and the owned placeholder");
        assertEquals(owned.savedState(), world.missiles().get(0).savedState(),
                "load discarded the attack order's newest placeholder");
        assertEquals(world.missiles().get(0),
                world.battleNetPendingProjectileShots.get(shooter));
        assertFalse(world.battleNetProjectileSlots[3],
                "healing the old save leaked the orphan's pool slot");
    }

    @Test
    @DisplayName("a destroyer's unconstructed final shell vanishes when its target dies")
    void targetDeathCancelsTheShipsOrphanedPendingCannonball() throws IOException {
        GameMap map = grass(24);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setMissileTypes(Map.of("missile-small-cannon", smallCannon()));
        world.setBattleNetSequenceData(retailScriptBin());

        Unit ship = world.createUnit(cannonShip(), 0, 8, 10);
        Unit victim = world.createUnit(grunt(), 1, 11, 10);
        assertTrue(ship != null && victim != null, "ship and shore target must place");
        world.player(0).setType(
                net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.PERSON);
        assertTrue(world.orderAttack(ship, victim), "ship attack accepted");

        // Presentation has drawn the shell at the muzzle, but retail opcode
        // ten has not constructed it yet. Killing the target ends the order
        // before that handoff -- the exact visible orphan from playtesting.
        world.hit(ship, victim);
        assertEquals(1, world.missiles().size(), "one pending cannonball");
        Missile orphan = world.missiles().get(0);
        int orphanSlot = orphan.battleNetPoolSlot();
        assertFalse(orphan.battleNetConstructorDrawn(),
                "the fixture must still be before opcode ten");
        world.kill(victim, ship);
        world.tick();

        assertFalse(world.missiles().contains(orphan),
                "the dead attack order left its cannonball painted in the water");
        assertTrue(world.battleNetPendingProjectileShots.isEmpty(),
                "the next attack could revive the orphaned shell");
        assertFalse(world.battleNetProjectileSlots[orphanSlot],
                "the abandoned cannonball leaked its fixed BNE projectile slot");
    }

    @Test
    @DisplayName("a live troll axe crosses the map and damages a destroyer")
    void trollAxeAgainstASeaTargetHasVisibleFlightAndImpact() throws IOException {
        GameMap map = grass(32);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setMissileTypes(Map.of("missile-axe", axeMissile()));
        world.setBattleNetSequenceData(retailScriptBin());
        world.restoreRandom(1, 0);

        Unit troll = world.createUnit(axethrower(), 1, 8, 10);
        Unit ship = world.createUnit(destroyer(), 0, 12, 10);
        world.player(1).setType(
                net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.PERSON);

        world.hit(troll, ship);
        Missile axe = world.battleNetPendingProjectileShots.remove(troll);
        assertTrue(axe != null, "the troll attack did not allocate its axe");
        double launchX = axe.x();
        int launchDirection = axe.direction();
        world.prepareBattleNetProjectile(axe, true);

        boolean crossedPixels = false;
        for (int cycle = 0; cycle < 100 && world.missiles().contains(axe); cycle++) {
            world.setCycle(world.cycle() + 1);
            world.projectiles.stepMissiles();
            crossedPixels |= axe.x() != launchX;
            assertEquals(launchDirection, axe.direction(),
                    "the axe flipped facings during one flight");
        }

        assertTrue(crossedPixels, "the troll axe never became visibly in flight");
        assertFalse(world.missiles().contains(axe), "the axe never reached the ship");
        assertTrue(ship.hitPoints() < ship.type().hitPoints(),
                "the visible axe reached the ship without applying its hit");
    }

    @Test
    @DisplayName("an old source-less pending missile is not restored as a ghost")
    void sourceLessLegacyPendingMissileIsDiscarded() {
        World world = new World(grass(16));
        MissileType axe = axeMissile();
        world.setMissileTypes(Map.of("missile-axe", axe));
        Unit target = world.createUnit(destroyer(), 0, 8, 8);
        Missile impossible = new Missile(axe, null, target,
                32, 32, 64, 64);
        impossible.setBattleNetPoolSlot(3);

        world.restoreMissile("missile-axe", null, target,
                impossible.savedState(), -1, 12, true);

        assertTrue(world.missiles().isEmpty(),
                "a pending projectile with no attack-order owner was restored");
        assertFalse(world.battleNetProjectileSlots[3],
                "discarding the ghost occupied its recorded pool slot");
    }
}
