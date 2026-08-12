package net.chonkbase.chonkcraft.engine.missile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exact action-5 cadence captured from retail BNE projectile type 21. */
class BattleNetImpactCadenceTest {

    private static Missile impact() {
        MissileType type = new MissileType("missile-impact", null,
                MissileClass.STAY, 48, 48, 10, 1, 16, 1, 1, 1, 50,
                null, null, false, 0, 0, false);
        return new Missile(type, null, null, 32, 32, 32, 32);
    }

    @Test
    @DisplayName("retail impact holds six frames for fourteen pool visits")
    void retailImpactHoldsSixFramesForFourteenPoolVisits() {
        Missile missile = impact();
        int[] nativeFrames = {0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5};
        for (int frame : nativeFrames) {
            missile.step();
            assertFalse(missile.hasArrived(),
                    "type 21 remains allocated through retail's final frame hold");
            assertEquals(frame, missile.frame());
        }
        missile.step();
        assertTrue(missile.hasArrived(),
                "Human 13 type-21 birth@35 frees on visit@49");
        assertEquals(5, missile.frame());
    }
}
