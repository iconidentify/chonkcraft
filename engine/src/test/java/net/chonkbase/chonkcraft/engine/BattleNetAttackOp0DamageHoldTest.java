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
 * Mid-OP0 free-scan retarget re-arms the Attack opening wait.
 *
 * <p>Human 13 knight 1490 starts Attack OP0 against the axe at 124,33, then
 * at fixture 34 switches goal to the ogre at 123,31 with face 5 and timer 3
 * again. That re-arm keeps the cursor on Attack@1922 when catapult splash
 * lands at fixture 35, so native bulk-holds instead of walking into OP10.
 * Pre-fix Java never retargeted during OP0, advanced into windup, and the
 * splash landed mid-swing -- OP10 still hit the ogre at fixture 44.</p>
 */
class BattleNetAttackOp0DamageHoldTest {

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
        type.setSightRange(5);
        type.setReactRangeComputer(6);
        type.setReactRangePerson(4);
        type.setArmor(0);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet("knight");
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move", List.of(
                "unbreakable begin", "frame 0", "move 16", "wait 1",
                "frame 5", "move 16", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack", List.of(
                "unbreakable begin", "frame 25", "wait 3", "frame 30", "wait 3",
                "frame 35", "wait 3", "frame 40", "attack", "wait 5",
                "frame 0", "wait 10", "unbreakable end", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    private static UnitType prey(String ident, int hp) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setBoxSize(42, 42);
        type.setHitPoints(hp);
        type.setLandUnit(true);
        type.setArmor(0);
        type.setNumDirections(8);
        AnimationSet set = new AnimationSet(ident);
        set.put(AnimationSet.State.STILL,
                Animation.parse("still", List.of("frame 0", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    @DisplayName("mid-op0 free-scan retarget re-arms attack-start timer three")
    void midOp0FreeScanRetargetReArmsAttackStartTimerThree() throws Exception {
        byte[] script = retailScriptBin();
        BattleNetSequence sequence = new BattleNetSequence(script);
        int attackStart = sequence.sequenceStart(
                6, BattleNetSequence.ATTACK_ANIMATION);
        assumeTrue(attackStart == 1922,
                "retail knight Attack must start at script offset 1922");

        GameMap map = grass(32);
        World world = new World(map);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, false);
        world.setBattleNetSequenceData(script);
        world.restoreRandom(1, 0);

        // Knight at 10,10; first prey (axe stand-in) at 10,13 (range 3);
        // better prey steps to 11,10 (range 1) after OP0 has begun -- the
        // Human 13 sealed shape (axe 124,33 then ogre 123,31).
        Unit attacker = world.createUnit(knight(), 1, 10, 10);
        Unit farPrey = world.createUnit(prey("unit-axethrower", 40), 0, 10, 13);
        Unit nearPrey = world.createUnit(prey("unit-ogre", 90), 0, 14, 10);
        assumeTrue(attacker != null && farPrey != null && nearPrey != null,
                "units must place");
        assertTrue(world.orderAttack(attacker, farPrey),
                "knight must accept the far attack order");

        attacker.setBattleNetSequenceOffset(attackStart);
        attacker.setBattleNetAnimationTimer(2);
        attacker.setChasing(false);
        attacker.setFighting(true);
        assertEquals(farPrey, attacker.target(),
                "OP0 opens against the far prey");

        // Better target walks into melee range while OP0 is still counting.
        nearPrey.setTile(11, 10);
        assertTrue(world.targets.inAttackRange(attacker, nearPrey),
                "near prey must be in knight weapon range after the step");

        world.combat.stepBattleNetAttackSequence(attacker);

        assertEquals(nearPrey, attacker.target(),
                "mid-OP0 free-scan must retarget onto the closer in-range prey");
        assertEquals(attackStart, attacker.battleNetSequenceOffset(),
                "retarget must keep the Attack cursor on opening OP0");
        assertEquals(3, attacker.battleNetAnimationTimer(),
                "native re-arms OP0 wait to timer 3 on that retarget "
                        + "(Human 13 knight 1490 fixture 34)");
    }
}
