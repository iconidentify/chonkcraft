package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.ZipFile;
import net.chonkbase.chonkcraft.data.map.PudUnitTypes;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A melee Attack tail wrap free-scans, including when the quarry is dying.
 *
 * <p>Human 13 ogre 1511 wraps 666/1 onto Attack@643/3 and names knight 1493
 * at fixture 115 even though that knight is two tiles off, then dest-arms
 * leftover SW,S onto 119,27 at 118. Wrap onto an already-adjacent replacement
 * restarts construction 3. Wrap with the quarry still alive and no in-range
 * replacement still walks OP0.
 */
class MeleeAttackTailWrapRetargetTest {

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

    private static UnitType meleeOgre() {
        UnitType type = new UnitType("unit-ogre");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(90);
        type.setSpeed(13);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(8);
        type.setPiercingDamage(4);
        type.setMaxAttackRange(1);
        type.setSightRange(5);
        type.setReactRangeComputer(6);
        type.setReactRangePerson(4);
        type.setNumDirections(8);
        type.setPriority(0x3f);
        AnimationSet set = new AnimationSet("ogre");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "frame 0", "wait 3", "attack", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static UnitType attackPeasant() {
        UnitType type = new UnitType("unit-attack-peasant");
        type.setTileSize(1, 1);
        type.setBoxSize(31, 31);
        type.setHitPoints(30);
        type.setSpeed(13);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(2);
        type.setPiercingDamage(1);
        type.setMaxAttackRange(1);
        type.setSightRange(4);
        type.setReactRangeComputer(4);
        type.setReactRangePerson(4);
        type.setNumDirections(8);
        type.setPriority(0x20);
        AnimationSet set = new AnimationSet("attack-peasant");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "frame 0", "wait 3", "attack", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static UnitType prey(String ident, int priority) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setBoxSize(42, 42);
        type.setHitPoints(90);
        type.setLandUnit(true);
        type.setArmor(0);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setNumDirections(8);
        type.setPriority(priority);
        AnimationSet set = new AnimationSet(ident);
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static int wrapGotoOffset(byte[] script, int attackStart) {
        int cursor = attackStart + 1;
        for (int i = 0; i < 64 && cursor + 2 < script.length; i++) {
            int opcode = Byte.toUnsignedInt(script[cursor]);
            if (opcode == 3) {
                int target = Byte.toUnsignedInt(script[cursor + 1])
                        | (Byte.toUnsignedInt(script[cursor + 2]) << 8);
                if (target == attackStart) {
                    return cursor;
                }
                cursor = target;
            } else if (opcode == 0) {
                return cursor;
            } else if (opcode == 1 || opcode == 4 || opcode == 5
                    || opcode == 7 || opcode == 8 || opcode == 9
                    || opcode == 12 || opcode == 13) {
                cursor += 2;
            } else if (opcode == 6 || opcode == 14) {
                cursor += 3;
            } else if (opcode == 2 || opcode == 10 || opcode == 11
                    || opcode == 15) {
                cursor += 1;
            } else {
                return -1;
            }
        }
        return -1;
    }

    private static int firstOpcode(byte[] script, int start, int wanted) {
        for (int cursor = start; cursor < start + 64 && cursor < script.length;
                cursor++) {
            if (Byte.toUnsignedInt(script[cursor]) == wanted) {
                return cursor;
            }
        }
        return -1;
    }

    private static World armedWorld(byte[] script) {
        GameMap map = grass(32);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.restoreRandom(1, 0);
        return world;
    }

    @Test
    @DisplayName("an opcode-ten swing still spends its damage roll after the quarry starts dying")
    void anOpcodeTenSwingStillRollsDamageAgainstADyingQuarry() throws Exception {
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        int attackStart = sequence.sequenceStart(
                7, BattleNetSequence.ATTACK_ANIMATION);
        int opcodeTen = firstOpcode(script, attackStart, 10);
        assumeTrue(opcodeTen >= 0, "ogre Attack must contain opcode ten");

        World world = armedWorld(script);
        Unit attacker = world.createUnit(meleeOgre(), 0, 10, 10);
        Unit dying = world.createUnit(prey("unit-knight", 0x37), 1, 11, 10);
        assertTrue(world.orderAttack(attacker, dying));
        dying.setOrder(Unit.Order.DYING);
        attacker.setTarget(dying);
        attacker.setFighting(true);
        attacker.setChasing(false);
        attacker.setBattleNetSequenceOffset(opcodeTen);
        attacker.setBattleNetAnimationTimer(1);

        int seedBefore = world.battleNetRandomSeed();
        int hpBefore = dying.hitPoints();
        world.tick();

        assertNotEquals(seedBefore, world.battleNetRandomSeed(),
                "retail spends FUN_00418370 even though HitUnit discards the"
                        + " already-committed swing against Die");
        assertEquals(hpBefore, dying.hitPoints(),
                "a late swing may spend its roll but cannot damage the corpse");
        assertEquals(Unit.Order.DYING, dying.order(),
                "the discarded swing must not restart or replace Die");
    }

    @Test
    @DisplayName("a melee tail wrap names a dying quarry's out-of-range neighbour and dest-arms leftover")
    void aMeleeTailWrapNamesADyingQuarrysOutOfRangeNeighbourAndDestArmsLeftover()
            throws Exception {
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        int attackStart = sequence.sequenceStart(
                7, BattleNetSequence.ATTACK_ANIMATION);
        assumeTrue(attackStart == 643,
                "retail ogre Attack must start at script offset 643");
        int wrap = wrapGotoOffset(script, attackStart);
        assumeTrue(wrap >= 0, "ogre Attack must loop back to its opening OP0");

        World world = armedWorld(script);
        Unit attacker = world.createUnit(meleeOgre(), 0, 10, 10);
        Unit dying = world.createUnit(prey("unit-knight", 0x37), 1, 11, 10);
        Unit next = world.createUnit(prey("unit-footman", 0x3f), 1, 10, 12);
        assumeTrue(attacker != null && dying != null && next != null,
                "units must place");
        assertTrue(world.orderAttack(attacker, dying),
                "ogre must accept the adjacent attack order");
        assertTrue(world.targets.inAttackRange(attacker, dying),
                "the dying quarry starts in melee range");
        assertTrue(!world.targets.inAttackRange(attacker, next),
                "the replacement starts two tiles off");

        dying.setOrder(Unit.Order.DYING);
        attacker.setTarget(dying);
        attacker.setFighting(true);
        attacker.setChasing(false);
        attacker.setBattleNetSequenceOffset(wrap);
        attacker.setBattleNetAnimationTimer(1);
        attacker.setBattleNetMeleeSyncRemaining(1);

        int syncSeedBeforeWrap = world.battleNetRandomSeed();
        world.tick();
        assertEquals(Unit.Order.ATTACK, attacker.order(),
                "a tail wrap after the quarry dies must keep Attack, not Still");
        assertSame(next, attacker.target(),
                "the wrap must name the out-of-range neighbour");
        assertEquals(attackStart, attacker.battleNetSequenceOffset(),
                "native restarts Attack construction 3 on that wrap");
        assertEquals(3, attacker.battleNetAnimationTimer(),
                "native restarts Attack construction 3 on that wrap");
        assertEquals(10, attacker.tileX(),
                "construction 3 holds the tile; dest-arm leftover is later");
        assertEquals(10, attacker.tileY(),
                "construction 3 holds the tile; dest-arm leftover is later");
        assertEquals(syncSeedBeforeWrap, world.battleNetRandomSeed(),
                "an out-of-range replacement cannot refresh the expired melee "
                        + "variant; retail drops that arm before chase");

        world.tick();
        world.tick();
        assertEquals(10, attacker.tileX(),
                "construction 2,1 still stands through the countdown");
        assertEquals(10, attacker.tileY(),
                "construction 2,1 still stands through the countdown");
        assertEquals(1, attacker.battleNetAnimationTimer(),
                "construction spends down to timer 1 before dest-arm leftover");

        world.tick();
        assertEquals(10, attacker.tileX(),
                "dest-arm leftover steps south toward the named knight");
        assertEquals(11, attacker.tileY(),
                "dest-arm leftover steps south toward the named knight");
        assertEquals(1, attacker.pathLength(),
                "dest-arm leftover remaining is one heading after the dest-arm");
        assertSame(next, attacker.target(),
                "the dest-arm leftover still belongs to the wrap-named knight");

        // The tail wrap already paid construction 3,2,1 before it asked for
        // this leftover.  Once the residual settles in range, retail enters
        // 644/1 directly; it does not charge another 643/3,2,1.  Human 13
        // ogre 1511 is the full-corpus witness (native damage at fixture
        // 137, formerly Java 140).
        int guard = 0;
        boolean repeatedConstruction = false;
        while (attacker.isMoving() && guard++ < 24) {
            world.tick();
            if (!attacker.isMoving()
                    && attacker.battleNetSequenceOffset() == attackStart
                    && attacker.battleNetAnimationTimer() == 3) {
                repeatedConstruction = true;
            }
        }
        assertTrue(!repeatedConstruction,
                "a tail-wrap leftover must not pay Attack construction twice");
        assertEquals(attackStart + 1, attacker.battleNetSequenceOffset(),
                "the paid tail-wrap leftover must cross OP0 on its arrival visit");
        assertEquals(1, attacker.battleNetAnimationTimer(),
                "the first attack-body byte begins with the native timer");
    }

    @Test
    @DisplayName("a melee tail replaces a mine-contained quarry and chases in the same visit")
    void aMeleeTailReplacesAMineContainedQuarryAndChasesInTheSameVisit()
            throws Exception {
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        int type = PudUnitTypes.code("unit-attack-peasant");
        int attackStart = sequence.sequenceStart(
                type, BattleNetSequence.ATTACK_ANIMATION);
        int moveStart = sequence.sequenceStart(
                type, BattleNetSequence.MOVE_ANIMATION);
        int wrap = wrapGotoOffset(script, attackStart);
        assumeTrue(attackStart >= 0 && moveStart >= 0 && wrap >= 0,
                "retail attack-peasant must have Move and Attack sequences");

        World world = armedWorld(script);
        Unit attacker = world.createUnit(attackPeasant(), 0, 10, 10);
        Unit contained = world.createUnit(
                prey("unit-peasant", 0x20), 1, 11, 10);
        Unit replacement = world.createUnit(
                prey("unit-peasant", 0x20), 1, 8, 10);
        assumeTrue(attacker != null && contained != null
                        && replacement != null,
                "units must place");
        assertTrue(world.orderAttack(attacker, contained),
                "attack-peasant must accept the first quarry");

        contained.setOrder(Unit.Order.HARVEST);
        contained.setRemoved(true);
        attacker.setTarget(contained);
        attacker.setFighting(true);
        attacker.setChasing(false);
        attacker.setBattleNetSequenceOffset(wrap);
        attacker.setBattleNetAnimationTimer(1);

        world.tick();

        assertSame(replacement, attacker.target(),
                "the tail scan must replace the mine-contained quarry");
        assertEquals(9, attacker.tileX(),
                "the replacement scan and first west chase step share a visit");
        assertEquals(10, attacker.tileY(),
                "the immediate chase must stay on the replacement's row");
        assertEquals(moveStart + 3, attacker.battleNetSequenceOffset(),
                "the same visit must execute Move OP0's first step");
        assertEquals(1, attacker.battleNetAnimationTimer(),
                "native exposes Move-start timer one after the committed step");
        assertEquals(1, attacker.pathLength(),
                "one west heading remains after the immediate first step");
    }

    @Test
    @DisplayName("a melee tail wrap onto an in-range replacement restarts construction")
    void aMeleeTailWrapOntoAnInRangeReplacementRestartsConstruction()
            throws Exception {
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        int attackStart = sequence.sequenceStart(
                7, BattleNetSequence.ATTACK_ANIMATION);
        assumeTrue(attackStart == 643,
                "retail ogre Attack must start at script offset 643");
        int wrap = wrapGotoOffset(script, attackStart);
        assumeTrue(wrap >= 0, "ogre Attack must loop back to its opening OP0");

        World world = armedWorld(script);
        Unit attacker = world.createUnit(meleeOgre(), 0, 10, 10);
        Unit first = world.createUnit(prey("unit-peasant", 0x20), 1, 11, 10);
        Unit second = world.createUnit(prey("unit-knight", 0x37), 1, 10, 11);
        assumeTrue(attacker != null && first != null && second != null,
                "units must place");
        assertTrue(world.orderAttack(attacker, first),
                "ogre must accept the first attack order");
        assertTrue(world.targets.inAttackRange(attacker, first),
                "the first quarry starts in melee range");
        assertTrue(world.targets.inAttackRange(attacker, second),
                "the replacement starts in melee range");

        attacker.setFighting(true);
        attacker.setChasing(false);
        attacker.setBattleNetSequenceOffset(wrap);
        attacker.setBattleNetAnimationTimer(1);

        world.tick();
        assertSame(second, attacker.target(),
                "an in-range wrap free-scan must name the other quarry");
        assertEquals(attackStart, attacker.battleNetSequenceOffset(),
                "in-range wrap retarget restarts Attack construction 3");
        assertEquals(3, attacker.battleNetAnimationTimer(),
                "in-range wrap retarget restarts Attack construction 3");
        assertEquals(Unit.Order.ATTACK, attacker.order(),
                "in-range wrap retarget keeps Attack");
    }

    @Test
    @DisplayName("a melee tail wrap prices an offered target before equal spatial hits")
    void aMeleeTailWrapPricesAnOfferedTargetBeforeEqualSpatialHits()
            throws Exception {
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        int attackStart = sequence.sequenceStart(
                7, BattleNetSequence.ATTACK_ANIMATION);
        assumeTrue(attackStart == 643,
                "retail ogre Attack must start at script offset 643");
        int wrap = wrapGotoOffset(script, attackStart);
        assumeTrue(wrap >= 0, "ogre Attack must loop back to its opening OP0");

        World world = armedWorld(script);
        Unit attacker = world.createUnit(meleeOgre(), 0, 10, 10);
        Unit spatialFirst = world.createUnit(
                prey("unit-ogre", 0x3f), 1, 11, 10);
        Unit offered = world.createUnit(
                prey("unit-ogre", 0x3f), 1, 10, 11);
        assumeTrue(attacker != null && spatialFirst != null && offered != null,
                "units must place");
        assertTrue(world.orderAttack(attacker, spatialFirst),
                "ogre must accept the first adjacent quarry");
        assertTrue(world.targets.inAttackRange(attacker, offered),
                "the equally scored offer starts in melee range");

        attacker.setOfferedTarget(offered);
        attacker.setFighting(true);
        attacker.setChasing(false);
        attacker.setBattleNetSequenceOffset(wrap);
        attacker.setBattleNetAnimationTimer(1);

        world.tick();
        assertSame(offered, attacker.target(),
                "the banked hit offer wins an equal-score spatial tie");
        assertEquals(attackStart, attacker.battleNetSequenceOffset());
        assertEquals(3, attacker.battleNetAnimationTimer(),
                "the offered retarget restarts Attack construction");

        world.tick();
        world.tick();
        world.tick();
        assertEquals(attackStart, attacker.battleNetSequenceOffset());
        assertEquals(23, attacker.battleNetAnimationTimer(),
                "the offered retarget enters native's committed melee hold");
    }

    @Test
    @DisplayName("a melee tail wrap without an in-range replacement walks op0")
    void aMeleeTailWrapWithoutAnInRangeReplacementWalksOp0() throws Exception {
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        int attackStart = sequence.sequenceStart(
                7, BattleNetSequence.ATTACK_ANIMATION);
        assumeTrue(attackStart == 643,
                "retail ogre Attack must start at script offset 643");
        int wrap = wrapGotoOffset(script, attackStart);
        assumeTrue(wrap >= 0, "ogre Attack must loop back to its opening OP0");

        World world = armedWorld(script);
        Unit attacker = world.createUnit(meleeOgre(), 0, 10, 10);
        Unit first = world.createUnit(prey("unit-knight", 0x37), 1, 11, 10);
        Unit far = world.createUnit(prey("unit-footman", 0x3f), 1, 10, 14);
        assumeTrue(attacker != null && first != null && far != null,
                "units must place");
        assertTrue(world.orderAttack(attacker, first),
                "ogre must accept the adjacent attack order");
        assertTrue(!world.targets.inAttackRange(attacker, far),
                "the other quarry starts out of melee range");

        attacker.setFighting(true);
        attacker.setChasing(false);
        attacker.setBattleNetSequenceOffset(wrap);
        attacker.setBattleNetAnimationTimer(1);

        world.tick();
        assertSame(first, attacker.target(),
                "wrap must keep the live in-range quarry when the other is off");
        assertNotEquals(3, attacker.battleNetAnimationTimer(),
                "wrap without an in-range replacement walks OP0, "
                        + "not construction 3");
        assertTrue(attacker.battleNetSequenceOffset() != attackStart
                        || attacker.battleNetAnimationTimer() == 1,
                "native walks Attack past OP0 (644/1) when the quarry lives "
                        + "and no in-range replacement is named");
    }
}
