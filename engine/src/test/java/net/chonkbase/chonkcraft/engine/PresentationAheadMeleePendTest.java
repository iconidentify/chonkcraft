package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.lang.reflect.Method;
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
 * Pre-OP10 wait presentation: empty-route residual cold settles may collapse;
 * leftover residual settles must keep the full t3..t1 hold.
 *
 * <p>Human 13 knight 1500 parks on Attack@1935 for three ticks before OP10
 * damage at fixture 50. Collapsing that wait applied the blow at fixture 47.
 * Human 13 grunt 1507 / Java 93 after an empty-route residual needs the
 * collapse so fixture 46 still receives the hit.
 */
class PresentationAheadMeleePendTest {

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

    private static UnitType knight() {
        UnitType type = new UnitType("unit-knight");
        type.setTileSize(1, 1);
        type.setBoxSize(42, 42);
        type.setHitPoints(90);
        type.setSpeed(13);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(8);
        type.setPiercingDamage(4);
        type.setMaxAttackRange(1);
        type.setSightRange(4);
        type.setReactRangeComputer(6);
        type.setReactRangePerson(4);
        type.setNumDirections(8);
        type.setMissile("missile-none");
        AnimationSet set = new AnimationSet("knight");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 25", "wait 3", "frame 30", "wait 3",
                "frame 35", "wait 3", "frame 40", "attack", "wait 10",
                "frame 0", "wait 10", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static UnitType prey() {
        UnitType type = new UnitType("unit-ogre");
        type.setTileSize(1, 1);
        type.setBoxSize(42, 42);
        type.setHitPoints(90);
        type.setLandUnit(true);
        type.setArmor(0);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("prey");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static void setEmptyRouteSettle(Unit unit, boolean value)
            throws Exception {
        Method m;
        try {
            m = Unit.class.getMethod(
                    "setBattleNetResidualEmptyRouteSettle", boolean.class);
        } catch (NoSuchMethodException missing) {
            assertTrue(false,
                    "setBattleNetResidualEmptyRouteSettle is missing; "
                            + "pre-OP10 wait collapse cannot distinguish empty-route "
                            + "residual (grunt 93) from leftover residual (knight 100)");
            return;
        }
        m.invoke(unit, value);
    }

    @Test
    @DisplayName("a leftover residual pre-op10 wait is not collapsed by presentation")
    void aLeftoverResidualPreOp10WaitIsNotCollapsedByPresentation()
            throws Exception {
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        Method opcodeAt;
        try {
            opcodeAt = BattleNetSequence.class.getMethod("opcodeAt", int.class);
        } catch (NoSuchMethodException missing) {
            assertTrue(false,
                    "BattleNetSequence.opcodeAt is missing; pre-OP10 wait "
                            + "cannot be detected for the presentation collapse gate");
            return;
        }
        assumeTrue(((Integer) opcodeAt.invoke(sequence, 1935)) == 10,
                "retail knight OP10 must sit at offset 1935");

        GameMap map = grass(16);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.restoreRandom(1, 0);

        Unit attacker = world.createUnit(knight(), 0, 4, 4);
        Unit target = world.createUnit(prey(), 1, 5, 4);
        assumeTrue(attacker != null && target != null, "units must place");

        attacker.setBattleNetSequenceOffset(1935);
        attacker.setBattleNetAnimationTimer(3);
        setEmptyRouteSettle(attacker, false);
        int hpBefore = target.hitPoints();

        world.combat.hit(attacker, target);

        assertEquals(3, attacker.battleNetAnimationTimer(),
                "leftover residual must keep the native pre-OP10 t3..t1 hold");
        assertEquals(hpBefore, target.hitPoints(),
                "presentation must not land damage on the pre-OP10 wait "
                        + "(native knight 1500 damages ogre 1510 at fixture 50)");
        assertTrue(world.battleNetPendingMeleeHits.containsKey(attacker),
                "victim stays pending for OP10");
    }

    @Test
    @DisplayName("an empty-route residual pre-op10 wait collapses for the late op0")
    void anEmptyRouteResidualPreOp10WaitCollapsesForTheLateOp0()
            throws Exception {
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        Method opcodeAt;
        try {
            opcodeAt = BattleNetSequence.class.getMethod("opcodeAt", int.class);
        } catch (NoSuchMethodException missing) {
            assertTrue(false,
                    "BattleNetSequence.opcodeAt is missing; pre-OP10 wait "
                            + "cannot be detected for the presentation collapse gate");
            return;
        }
        assumeTrue(((Integer) opcodeAt.invoke(sequence, 1935)) == 10,
                "retail knight OP10 must sit at offset 1935");

        GameMap map = grass(16);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.restoreRandom(1, 0);

        Unit attacker = world.createUnit(knight(), 0, 4, 4);
        Unit target = world.createUnit(prey(), 1, 5, 4);
        assumeTrue(attacker != null && target != null, "units must place");

        attacker.setBattleNetSequenceOffset(1935);
        attacker.setBattleNetAnimationTimer(3);
        setEmptyRouteSettle(attacker, true);
        int hpBefore = target.hitPoints();

        world.combat.hit(attacker, target);

        assertEquals(1, attacker.battleNetAnimationTimer(),
                "empty-route residual may collapse pre-OP10 wait so a late OP0 "
                        + "still lands on native's process cycle");
        assertTrue(target.hitPoints() < hpBefore,
                "empty-route residual collapse resolves presentation damage "
                        + "(Human 13 grunt 93 / fixture 46)");
    }
}
