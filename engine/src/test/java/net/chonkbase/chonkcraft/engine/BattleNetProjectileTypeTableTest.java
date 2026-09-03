package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.chonkbase.chonkcraft.engine.missile.MissileClass;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks projectile values read directly from retail BNE's type tables. */
class BattleNetProjectileTypeTableTest {

    @Test
    @DisplayName("native type 10 overrides touch-of-death's generated speed")
    void nativeTypeTenOverridesTouchOfDeathsGeneratedSpeed() {
        MissileType touch = new MissileType("missile-touch-of-death", null,
                MissileClass.POINT_TO_POINT_WITH_HIT,
                32, 32, 30, 9, 16, 1, 1, 1, 50,
                null, null, false, 0, 0, false);

        assertEquals(12, BattleNetProjectileSystem.battleNetMissileSpeed(touch),
                "BNE table 0x00494e0c byte 10 is 0x0c");
    }
}
