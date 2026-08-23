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
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * After an out-of-range Attack OP0 and a Move-to-Attack resume, the next
 * in-range OP0 stalls on attack-start with timer 63.
 *
 * <p>Human 13 axe 1483/117 used to walk the windup into OP10 and spend the
 * three-draw mobile constructor at world 38 while native stayed on sequence
 * 887 with timer 63 -- shifting critter 1399's phase-8 choice onto wander.
 */
class BattleNetAttackResumeHoldTest {

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
        if (packProp == null || packProp.isBlank()) {
            packProp = System.getenv("CHONKCRAFT_ASSET_PACK");
        }
        assumeTrue(packProp != null && !packProp.isBlank(),
                "BNE asset pack path required via chonkcraft.pack or CHONKCRAFT_ASSET_PACK");
        Path pack = Path.of(packProp);
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
        type.setBoxSize(36, 36);
        type.setHitPoints(40);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(3);
        type.setPiercingDamage(6);
        type.setMaxAttackRange(4);
        type.setSightRange(5);
        type.setReactRangeComputer(7);
        type.setReactRangePerson(5);
        type.setNumDirections(8);
        type.setMissile("missile-axe");
        AnimationSet set = new AnimationSet("axe");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 25", "wait 3", "frame 30", "wait 3",
                "frame 35", "wait 3", "frame 40", "attack", "wait 12",
                "frame 0", "wait 52", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static UnitType prey() {
        UnitType type = new UnitType("unit-knight");
        type.setTileSize(1, 1);
        type.setBoxSize(42, 42);
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
    @DisplayName("an approach then move-resume axe stalls on attack-start op0 with timer 63")
    void anApproachThenMoveResumeAxeStallsOnAttackStartOp0WithTimer63()
            throws Exception {
        // Human 13 axe 1483: out-of-range OP0, Move approach, resume Attack,
        // then in-range OP0 stays on 887 with timer 63 (native c26–42).
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        int attackStart = sequence.sequenceStart(
                9, BattleNetSequence.ATTACK_ANIMATION);
        int moveStart = sequence.sequenceStart(
                9, BattleNetSequence.MOVE_ANIMATION);
        assumeTrue(attackStart == 887,
                "retail axe Attack must start at script offset 887");
        assumeTrue(moveStart >= 0, "retail axe Move sequence must resolve");

        GameMap map = grass(32);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.restoreRandom(1, 0);

        Unit attacker = world.createUnit(axethrower(), 0, 8, 8);
        // Far prey so the first Attack OP0 is out of weapon range.
        Unit target = world.createUnit(prey(), 1, 20, 8);
        assumeTrue(attacker != null && target != null, "units must place");
        assertTrue(world.orderAttack(attacker, target),
                "axe must accept the distant attack order");

        attacker.setBattleNetSequenceOffset(attackStart);
        attacker.setBattleNetAnimationTimer(1);
        attacker.setBattleNetAttackOp0OutOfRange(false);
        attacker.setBattleNetAttackResumeFromMove(false);

        // Expiry of timer 1 at attack-start fires OP0 while out of range.
        for (int i = 0; i < 4; i++) {
            world.tick();
        }
        assertTrue(attacker.battleNetAttackOp0OutOfRange(),
                "opening OP0 against a distant prey must mark the approach");

        // Simulate Move-body resume into Attack while now in range.
        attacker.setTile(10, 8);
        target.setTile(12, 8);
        attacker.setChasing(false);
        attacker.setBattleNetSequenceOffset(moveStart + 4);
        attacker.setBattleNetAnimationTimer(1);
        attacker.setBattleNetAttackResumeFromMove(true);

        // Standing-in-range restart + short attack-start countdown + OP0 hold.
        int guard = 0;
        while (attacker.battleNetAnimationTimer() != 63 && guard++ < 12) {
            world.tick();
        }
        assertEquals(attackStart, attacker.battleNetSequenceOffset(),
                "post-approach in-range OP0 must remain on attack-start, "
                        + "not walk into the windup/OP10");
        assertEquals(63, attacker.battleNetAnimationTimer(),
                "native Human 13 axe 1483 seals timer 63 on that stall");
        assertTrue(attacker.battleNetAttackResumeHoldActive(),
                "approach hold must suppress presentation projectile queueing");

        // Presentation must not pend while the OP0 stall is live (Human 13
        // axe 117 used to pend-put at timer 56 during the hold).
        int missilesBefore = 0;
        for (var ignored : world.missiles()) {
            missilesBefore++;
        }
        world.hit(attacker, target);
        int missilesAfter = 0;
        for (var ignored : world.missiles()) {
            missilesAfter++;
        }
        assertEquals(missilesBefore, missilesAfter,
                "presentation during approach-hold must not launch a pending "
                        + "axe while the sequence is still on attack-start");
    }

    @Test
    @DisplayName("a dest-arm leftover residual axe stalls on attack-start op0 with timer 63")
    void aDestArmLeftoverResidualAxeStallsOnAttackStartOp0WithTimer63()
            throws Exception {
        // Human 13 axe 1505 dest-arms onto 124,25, lands the leftover
        // residual in range of the knight, then native stays on 887 with
        // construction 3,2,1 and seals timer 63. Java used to consume that
        // start wait as the construction timer, walk into OP10, and throw
        // at fixture 38.
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        int attackStart = sequence.sequenceStart(
                9, BattleNetSequence.ATTACK_ANIMATION);
        int moveStart = sequence.sequenceStart(
                9, BattleNetSequence.MOVE_ANIMATION);
        assumeTrue(attackStart == 887,
                "retail axe Attack must start at script offset 887");
        assumeTrue(moveStart >= 0, "retail axe Move sequence must resolve");

        GameMap map = grass(32);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.restoreRandom(1, 0);

        Unit attacker = world.createUnit(axethrower(), 0, 10, 8);
        Unit target = world.createUnit(prey(), 1, 12, 8);
        assumeTrue(attacker != null && target != null, "units must place");
        assertTrue(world.targets.inAttackRange(attacker, target),
                "the dest-arm residual lands already in weapon range");
        assertTrue(world.orderAttack(attacker, target),
                "axe must accept the in-range attack order");

        // Dest-arm leftover residual just landed: exhausted route, still
        // chasing, cursor on the Move body. Opening OP0 was in range, so
        // the 1483 approach flag is off -- this is the 1505 seam.
        attacker.setChasing(true);
        attacker.setFighting(false);
        attacker.clearPath();
        attacker.setBattleNetSequenceOffset(moveStart + 4);
        attacker.setBattleNetAnimationTimer(1);
        attacker.setBattleNetAttackResumeFromMove(false);
        attacker.setBattleNetAttackOp0OutOfRange(false);

        int guard = 0;
        while (attacker.battleNetAnimationTimer() != 63 && guard++ < 12) {
            world.tick();
        }
        assertEquals(attackStart, attacker.battleNetSequenceOffset(),
                "dest-arm leftover residual must remain on attack-start, "
                        + "not walk into the windup/OP10");
        assertEquals(63, attacker.battleNetAnimationTimer(),
                "native Human 13 axe 1505 seals timer 63 after dest-arm "
                        + "leftover residual lands in range");
        assertTrue(attacker.battleNetAttackResumeHoldActive(),
                "dest-arm residual hold must suppress presentation "
                        + "projectile queueing");
        assertTrue(!attacker.chasing(),
                "an arrived dest-arm residual is no longer a chase");

        int missilesBefore = 0;
        for (var ignored : world.missiles()) {
            missilesBefore++;
        }
        world.hit(attacker, target);
        int missilesAfter = 0;
        for (var ignored : world.missiles()) {
            missilesAfter++;
        }
        assertEquals(missilesBefore, missilesAfter,
                "presentation during dest-arm residual hold must not launch "
                        + "a pending axe while the sequence is still on "
                        + "attack-start");
    }

    @Test
    @DisplayName("an axe resumes chasing when its quarry leaves during the 63-cycle hold")
    void anAxeResumesChasingWhenItsQuarryLeavesDuringTheHold()
            throws Exception {
        // Human 13 axe 1505: knight 1493 is in range when the hold opens at
        // native cycle 28, but has left the four-square ring by cycle 90.
        // Retail changes Attack@887/1 directly to Move@833 at cycle 91.
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        int attackStart = sequence.sequenceStart(
                9, BattleNetSequence.ATTACK_ANIMATION);
        int moveStart = sequence.sequenceStart(
                9, BattleNetSequence.MOVE_ANIMATION);

        GameMap map = grass(32);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.restoreRandom(1, 0);

        Unit attacker = world.createUnit(axethrower(), 0, 10, 8);
        Unit target = world.createUnit(prey(), 1, 12, 8);
        assumeTrue(attacker != null && target != null, "units must place");
        assertTrue(world.orderAttack(attacker, target),
                "axe must accept the in-range attack order");

        attacker.setChasing(true);
        attacker.setFighting(false);
        attacker.clearPath();
        attacker.setBattleNetSequenceOffset(moveStart + 4);
        attacker.setBattleNetAnimationTimer(1);

        int guard = 0;
        while (attacker.battleNetAnimationTimer() != 63 && guard++ < 12) {
            world.tick();
        }
        assertEquals(attackStart, attacker.battleNetSequenceOffset());
        assertTrue(attacker.battleNetAttackResumeHoldActive());

        // Five squares away: the first chase step lands back in range, just
        // as native 1505's SW step at cycle 91 does.
        target.setTile(15, 9);
        for (int i = 0; i < 62; i++) {
            world.tick();
        }
        assertEquals(1, attacker.battleNetAnimationTimer(),
                "the committed hold must be allowed to drain in full");
        world.tick();

        assertTrue(attacker.chasing(),
                "the first visit after the hold must resume the chase");
        assertTrue(attacker.battleNetSequenceOffset() >= moveStart
                        && attacker.battleNetSequenceOffset() < attackStart,
                "retail changes from Attack start directly into the Move body");

        boolean enteredAttackBody = false;
        for (int i = 0; i < 40; i++) {
            world.tick();
            assertTrue(attacker.battleNetAnimationTimer() != 63,
                    "the hold was already paid before this chase leg; "
                            + "arrival must not charge it a second time");
            if (attacker.battleNetSequenceOffset() > attackStart) {
                enteredAttackBody = true;
                break;
            }
        }
        assertTrue(enteredAttackBody,
                "after its one-tile chase the axe must enter the firing body");
    }

    @Test
    @DisplayName("an already in-range axe walks the opening attack wait")
    void anAlreadyInRangeAxeWalksTheOpeningAttackWait() throws Exception {
        // Human 13's first commanded axe starts in range and must still
        // construct. Sealing timer 63 on every Attack open would stall that
        // first shot.
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        int attackStart = sequence.sequenceStart(
                9, BattleNetSequence.ATTACK_ANIMATION);
        assumeTrue(attackStart == 887,
                "retail axe Attack must start at script offset 887");

        GameMap map = grass(32);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.restoreRandom(1, 0);

        Unit attacker = world.createUnit(axethrower(), 0, 10, 8);
        Unit target = world.createUnit(prey(), 1, 12, 8);
        assumeTrue(attacker != null && target != null, "units must place");
        assertTrue(world.targets.inAttackRange(attacker, target),
                "the first swing starts already in weapon range");
        assertTrue(world.orderAttack(attacker, target),
                "axe must accept the in-range attack order");
        attacker.setBattleNetSequenceOffset(attackStart);
        attacker.setBattleNetAnimationTimer(1);
        attacker.setChasing(false);
        attacker.setBattleNetAttackResumeFromMove(false);
        attacker.setBattleNetAttackOp0OutOfRange(false);

        int guard = 0;
        while (attacker.battleNetSequenceOffset() == attackStart
                && attacker.battleNetAnimationTimer() != 63
                && guard++ < 16) {
            world.tick();
        }
        assertTrue(attacker.battleNetSequenceOffset() > attackStart,
                "a cold in-range first swing must walk past attack-start "
                        + "into the windup, not seal timer 63");
        assertTrue(attacker.battleNetAnimationTimer() != 63,
                "timer 63 is the dest-arm/approach hold, not the first "
                        + "in-range swing");
    }

    @Test
    @DisplayName("a dest-arm leftover residual ogre stalls on attack-start with the body wait")
    void aDestArmLeftoverResidualOgreStallsOnAttackStartWithTheBodyWait()
            throws Exception {
        // Human 13 ogre 1491 dest-arms leftover toward knight 1500, lands
        // on 118,27, free-scans onto 1493, and native stays on 643 with
        // construction 3 then bodyWaitSum-1 (23). Java used to walk OP0
        // into opcode 10 at fixture 53.
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        int attackStart = sequence.sequenceStart(
                7, BattleNetSequence.ATTACK_ANIMATION);
        int moveStart = sequence.sequenceStart(
                7, BattleNetSequence.MOVE_ANIMATION);
        assumeTrue(attackStart == 643,
                "retail ogre Attack must start at script offset 643");
        assumeTrue(moveStart >= 0, "retail ogre Move sequence must resolve");
        int bodySum = sequence.attackBodyWaitSum(attackStart);
        assumeTrue(bodySum == 24,
                "ogre Attack body waits 3+3+5+3+10 so the OP0 hold is 23");

        GameMap map = grass(32);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.restoreRandom(1, 0);

        Unit attacker = world.createUnit(meleeOgre(), 0, 10, 8);
        Unit target = world.createUnit(prey(), 1, 11, 8);
        assumeTrue(attacker != null && target != null, "units must place");
        assertTrue(world.targets.inAttackRange(attacker, target),
                "the dest-arm residual lands already in weapon range");
        assertTrue(world.orderAttack(attacker, target),
                "ogre must accept the in-range attack order");

        attacker.setChasing(true);
        attacker.setFighting(false);
        attacker.clearPath();
        attacker.setBattleNetSequenceOffset(moveStart + 4);
        attacker.setBattleNetAnimationTimer(1);
        attacker.setBattleNetAttackResumeFromMove(false);
        attacker.setBattleNetAttackOp0OutOfRange(false);

        int hold = bodySum - 1;
        int hpBefore = target.hitPoints();
        int guard = 0;
        while (attacker.battleNetAnimationTimer() != hold && guard++ < 16) {
            world.tick();
        }
        assertEquals(attackStart, attacker.battleNetSequenceOffset(),
                "dest-arm leftover residual must remain on attack-start, "
                        + "not walk into the windup/OP10");
        assertEquals(hold, attacker.battleNetAnimationTimer(),
                "native Human 13 ogre 1491 seals bodyWaitSum-1 after dest-arm "
                        + "leftover residual lands in range");
        assertEquals(hpBefore, target.hitPoints(),
                "the stall must not land the first melee during the body hold");
    }

    @Test
    @DisplayName("an already in-range ogre walks the opening attack wait")
    void anAlreadyInRangeOgreWalksTheOpeningAttackWait() throws Exception {
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        int attackStart = sequence.sequenceStart(
                7, BattleNetSequence.ATTACK_ANIMATION);
        assumeTrue(attackStart == 643,
                "retail ogre Attack must start at script offset 643");
        int hold = sequence.attackBodyWaitSum(attackStart) - 1;

        GameMap map = grass(32);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.restoreRandom(1, 0);

        Unit attacker = world.createUnit(meleeOgre(), 0, 10, 8);
        Unit target = world.createUnit(prey(), 1, 11, 8);
        assumeTrue(attacker != null && target != null, "units must place");
        assertTrue(world.orderAttack(attacker, target),
                "ogre must accept the in-range attack order");
        attacker.setBattleNetSequenceOffset(attackStart);
        attacker.setBattleNetAnimationTimer(1);
        attacker.setChasing(false);
        attacker.setBattleNetAttackResumeFromMove(false);
        attacker.setBattleNetAttackOp0OutOfRange(false);

        int guard = 0;
        while (attacker.battleNetSequenceOffset() == attackStart
                && attacker.battleNetAnimationTimer() != hold
                && guard++ < 16) {
            world.tick();
        }
        assertTrue(attacker.battleNetSequenceOffset() > attackStart,
                "a cold in-range first melee must walk past attack-start "
                        + "into the windup, not seal the dest-arm body hold");
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
}
