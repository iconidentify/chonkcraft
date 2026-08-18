package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What spends a number from the shared random stream.
 *
 * <p>Both engines can agree about every unit on the map and still be playing
 * different games, because the next draw either of them makes will differ. A
 * draw skipped is as bad as a draw made wrongly, and neither shows up as
 * anything a player could see until the divergence compounds. These are the
 * places the parity harness has caught this implementation not drawing.
 */
class SharedStreamDrawsTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        type.setNumDirections(8);
        return type;
    }

    /** A farm: a building faces one way and takes no heading. */
    private static UnitType farm() {
        UnitType type = new UnitType("unit-farm");
        type.setTileSize(2, 2);
        type.setHitPoints(400);
        type.setBuilding(true);
        type.setNumDirections(1);
        return type;
    }

    /** ChonkCraft's vision revealer, which is what a corpse leaves behind. */
    private static UnitType revealer() {
        UnitType type = new UnitType("unit-dead-vision-1-4");
        type.setTileSize(1, 1);
        type.setHitPoints(1);
        type.setIndestructible(true);
        type.setNumDirections(1);
        type.setRevealer(true);
        return type;
    }

    @Test
    @DisplayName("an implementation-side death revealer costs no native async draw")
    void makingADeathRevealerDoesNotDebitTheNativeStream() {
        World world = new World(grass(20));

        int before = world.battleNetRandomSeed();
        world.createUnit(revealer(), 1, 5, 6);

        assertEquals(before, world.battleNetRandomSeed(),
                "the Java sight carrier spent a native construction draw even though the"
                        + " authenticated Human 13 ledger has no constructor call when the"
                        + " corresponding death vision appears");
    }

    @Test
    @DisplayName("mobile and building constructors debit the native async stream")
    void nativeConstructorsDebitTheirTypeSpecificDraws() {
        World world = new World(grass(20));

        long before = world.battleNetRandomDraws();
        world.createUnit(footman(), 0, 5, 5);
        assertEquals(before + 2, world.battleNetRandomDraws(),
                "FUN_00451b50 gives a mobile its opening heading and idle timer");

        // BNE buildings do not draw a heading. They draw one of six building
        // animation variants and then their idle timer, also from the async
        // stream.
        long afterFootman = world.battleNetRandomDraws();
        world.createUnit(farm(), 0, 10, 10);
        assertEquals(afterFootman + 2, world.battleNetRandomDraws(),
                "the building constructor did not debit variant plus timer");
    }

    @Test
    @DisplayName("damage is rolled even against something it cannot hurt")
    void anInvulnerableTargetStillCostsTheRoll() {
        World world = new World(grass(20));
        world.setAllied(0, 1, false);
        Unit attacker = world.createUnit(footman(), 0, 5, 5);
        Unit corpseMarker = world.createUnit(revealer(), 1, 5, 6);

        // Upstream splits the two across a caller and a callee:
        // MissileHitsGoal works the damage out for any goal that is not
        // already dying and hands it to HitUnit, which is where the
        // invulnerable are turned away. CalculateDamageStats
        // takes a number from the shared stream, so turning the target away
        // before the arithmetic skips a draw.
        //
        // On demo02 a peasant dies at 0,25 on cycle 13, its death animation
        // spawns one of these over the body, and the second shell lands on
        // cycle 16 and rolls against it for nothing.
        int before = world.randomSeed();
        int health = corpseMarker.hitPoints();
        world.hit(attacker, corpseMarker);

        assertEquals(health, corpseMarker.hitPoints(),
                "the revealer took damage, so this measures the wrong thing entirely");
        assertNotEquals(before, world.randomSeed(),
                "the blow was turned away before the damage was rolled, so it cost no draw"
                        + " where upstream's costs one");
    }
}
