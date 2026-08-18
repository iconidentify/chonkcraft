package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
 * Mid-OP0 free-scan retarget for a standing archer re-arms timer 3, then the
 * next in-range OP0 seals timer 63 instead of walking into windup.
 *
 * <p>XHuman 10 archer 98 (native slot 1502 at 84,85) free-scans goal 80,89
 * to 80,87 at fixture 23 while still on Attack@2039, re-arms timer 3 and face
 * 5, then seals timer 63 on the next OP0. Melee-only free-scan left that
 * archer on windup so it prepared at world 36 and dropped grunt 1495 one
 * fixture cycle early (45 vs 53 at 47).
 */
class RangedOp0FreeScanRetargetHoldTest {

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

    private static UnitType archer() {
        UnitType type = new UnitType("unit-archer");
        type.setTileSize(1, 1);
        type.setBoxSize(32, 32);
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
        type.setMissile("missile-arrow");
        AnimationSet set = new AnimationSet("archer");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 25", "wait 10", "frame 30",
                "wait 10", "attack", "frame 0", "wait 44",
                "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static UnitType grunt() {
        UnitType type = new UnitType("unit-grunt");
        type.setTileSize(1, 1);
        type.setBoxSize(36, 36);
        type.setHitPoints(60);
        type.setLandUnit(true);
        type.setArmor(0);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("grunt");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    @DisplayName("a mid-op0 free-scan retarget stalls the next archer op0 with timer 63")
    void aMidOp0FreeScanRetargetStallsTheNextArcherOp0WithTimer63()
            throws Exception {
        // XHuman 10 archer 98: free-scan 80,89 → 80,87 at fixture 23 while
        // still on Attack@2039; next OP0 seals timer 63 (native c26+) instead
        // of walking windup/OP10.
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        int attackStart = sequence.sequenceStart(
                8, BattleNetSequence.ATTACK_ANIMATION);
        assumeTrue(attackStart == 2039,
                "retail archer Attack must start at script offset 2039");

        GameMap map = grass(32);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.restoreRandom(1, 0);

        // Archer at 84,85 equivalent local tile; two in-range hostiles so a
        // free-scan can prefer a different quarry mid-OP0.
        Unit attacker = world.createUnit(archer(), 0, 12, 12);
        Unit first = world.createUnit(grunt(), 1, 8, 16);
        Unit second = world.createUnit(grunt(), 1, 8, 14);
        assumeTrue(attacker != null && first != null && second != null,
                "units must place");
        assertTrue(world.targets.inAttackRange(attacker, first),
                "first quarry must start in archer range");
        assertTrue(world.targets.inAttackRange(attacker, second),
                "second quarry must start in archer range");

        assertTrue(world.orderAttack(attacker, first),
                "archer must accept the first attack order");
        attacker.setBattleNetSequenceOffset(attackStart);
        attacker.setBattleNetAnimationTimer(1);
        attacker.setChasing(false);

        // Drive until free-scan retarget re-arms timer 3 or OP0 advances.
        int guard = 0;
        boolean retargeted = false;
        while (guard++ < 16) {
            world.tick();
            if (attacker.target() == second
                    && attacker.battleNetSequenceOffset() == attackStart
                    && attacker.battleNetAnimationTimer() == 3) {
                retargeted = true;
                break;
            }
            if (attacker.battleNetSequenceOffset() > attackStart) {
                break;
            }
        }
        assertTrue(retargeted,
                "mid-OP0 free-scan must switch the standing archer onto the "
                        + "other in-range grunt and re-arm timer 3 on "
                        + "attack-start (native 1502 goal 80,89 to 80,87)");
        assertEquals(attackStart, attacker.battleNetSequenceOffset(),
                "retarget must stay on attack-start, not enter windup");

        // Next in-range OP0 seals timer 63 (approach-style post-retarget hold).
        guard = 0;
        while (attacker.battleNetAnimationTimer() != 63 && guard++ < 16) {
            world.tick();
        }
        assertEquals(attackStart, attacker.battleNetSequenceOffset(),
                "post-retarget OP0 must remain on attack-start");
        assertEquals(63, attacker.battleNetAnimationTimer(),
                "native XHuman 10 archer 1502 seals timer 63 after free-scan "
                        + "retarget (not windup into OP10)");
        assertTrue(attacker.battleNetAttackResumeHoldActive(),
                "post-retarget hold must suppress presentation projectile queueing");
        assertNotEquals(first, attacker.target(),
                "hold is after free-scan onto the second quarry");

        int missilesBefore = 0;
        for (var ignored : world.missiles()) {
            missilesBefore++;
        }
        world.hit(attacker, attacker.target());
        int missilesAfter = 0;
        for (var ignored : world.missiles()) {
            missilesAfter++;
        }
        assertEquals(missilesBefore, missilesAfter,
                "presentation during post-retarget hold must not launch an "
                        + "arrow while the sequence is still on attack-start");
    }

    @Test
    @DisplayName("a dest-arm residual free-scan keeps the construction timer")
    void aDestArmResidualFreeScanKeepsTheConstructionTimer() throws Exception {
        // Human 13 axe 1483 lands dest-arm leftover and names the wise-man
        // on the Attack@887/3 open. Re-arming construction there delayed
        // the first axe from fixture 99 to 102.
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

        Unit attacker = world.createUnit(axethrower(), 0, 12, 12);
        Unit first = world.createUnit(grunt(), 1, 8, 16);
        Unit second = world.createUnit(grunt(), 1, 8, 14);
        assumeTrue(attacker != null && first != null && second != null,
                "units must place");
        assertTrue(world.orderAttack(attacker, first),
                "axe must accept the first attack order");

        attacker.setChasing(true);
        attacker.setFighting(false);
        attacker.clearPath();
        attacker.setBattleNetSequenceOffset(moveStart + 4);
        attacker.setBattleNetAnimationTimer(1);
        attacker.setBattleNetAttackResumeFromMove(false);
        attacker.setBattleNetAttackOp0OutOfRange(false);

        int guard = 0;
        int constructionVisits = 0;
        boolean sawConstruction = false;
        while (attacker.battleNetAnimationTimer() != 63 && guard++ < 16) {
            world.tick();
            if (attacker.battleNetSequenceOffset() == attackStart
                    && attacker.battleNetAnimationTimer() >= 1
                    && attacker.battleNetAnimationTimer() <= 3
                    && attacker.target() != null) {
                sawConstruction = true;
                constructionVisits++;
            }
        }
        assertTrue(sawConstruction,
                "dest-arm residual must open Attack construction");
        assertTrue(constructionVisits <= 3,
                "naming a new quarry on the construction-open visit must "
                        + "keep the 3,2,1 countdown, not restart it; "
                        + "visits=" + constructionVisits);
        assertEquals(attackStart, attacker.battleNetSequenceOffset(),
                "post-residual OP0 must remain on attack-start");
        assertEquals(63, attacker.battleNetAnimationTimer(),
                "native Human 13 axe 1483 seals timer 63 three visits after "
                        + "the dest-arm residual opens Attack");
        assertNotEquals(first, attacker.target(),
                "the construction-open free-scan must name the other grunt");
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
}
