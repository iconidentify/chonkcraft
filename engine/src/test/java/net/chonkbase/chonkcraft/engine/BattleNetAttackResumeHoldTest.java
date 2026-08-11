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
}
