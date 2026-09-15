package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A slowed fighter's visible frame and damage must follow the same native clock.
 *
 * <p>BNE 0x402440 holds both sequence and frame while its timer decrements,
 * then reads the next frame and spell-adjusted wait. Footman Attack from real
 * script.bin arms its first windup wait at 2544: timer 6/3/1 under Slow/plain/
 * Haste. The port's native clock ignored spells while its presentation clock
 * applied them, and the latter kept overwriting native frames between visits.
 */
class BattleNetSpellAnimationRealDataTest {
    @Test
    @DisplayName("slow and haste hold real attack frames until the native wait expires")
    void realAttackFramesAndDamageShareTheSpellAdjustedClock() {
        AssetSource source = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(source != null, "BNE assets are required for the native animation");
        GameData data = new GameData(source);
        for (int speed : new int[] {-1, 0, 1}) {
            GameMap map = new GameMap(32, 32, new Tileset());
            for (int y = 0; y < 32; y++) {
                for (int x = 0; x < 32; x++) {
                    map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
                }
            }
            World world = new World(map);
            data.configureWorld(world, PudMap.Tileset.FOREST);
            Assumptions.assumeTrue(world.battleNetSequence != null,
                    "the source must include BNE script.bin");
            world.fog().revealAll(0);
            Unit attacker = world.createUnit(data.unitTypes().types().get("unit-footman"), 0, 10, 10);
            Unit target = world.createUnit(data.unitTypes().types().get("unit-dark-portal"), 15, 11, 10);
            if (speed != 0) {
                attacker.setBuff(speed < 0 ? Unit.Buff.SLOW : Unit.Buff.HASTE, 1000);
            }
            assertTrue(world.orderAttack(attacker, target, true), "the player attack must start");
            int cycle = 0;
            while (cycle++ < 30 && attacker.battleNetSequenceOffset() != 2544) {
                world.tick();
            }
            int wait = speed < 0 ? 6 : speed > 0 ? 1 : 3;
            assertEquals(2544, attacker.battleNetSequenceOffset(), "the first footman windup must be reached");
            assertEquals(wait, attacker.battleNetAnimationTimer(), "native first windup wait at speed " + speed);
            assertEquals(25, attacker.frame(), "the windup must begin on native frame 25");
            int health = target.hitPoints();
            for (int quiet = 1; quiet < wait; quiet++) {
                world.tick();
                assertEquals(25, attacker.frame(), "presentation must not advance during native quiet visit " + quiet);
                assertEquals(health, target.hitPoints(), "a quiet windup visit must not deal damage");
            }
            world.tick();
            assertEquals(30, attacker.frame(), "the next native instruction selects frame 30");
            assertEquals(wait, attacker.battleNetAnimationTimer(), "the next wait uses the same spell state");
            for (int visit = 0; visit < wait * 3 && target.hitPoints() == health; visit++) {
                world.tick();
            }
            assertTrue(target.hitPoints() < health, "the adjusted animation must still deliver its blow");
        }
    }
}
