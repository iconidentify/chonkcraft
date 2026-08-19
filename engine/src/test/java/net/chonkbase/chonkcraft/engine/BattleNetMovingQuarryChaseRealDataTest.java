package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class BattleNetMovingQuarryChaseRealDataTest {

    private static final int INITIALIZATION_TICKS = 2;

    @Test
    void aHuman8AttackPeasantKeepsPaceWithItsMovingQuarry() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No BNE asset pack/install is configured");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is unavailable");
        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit attacker = unitAt(mission.world(), 4,
                "unit-attack-peasant", 68, 67);
        Unit quarry = unitAt(mission.world(), 2,
                "unit-peasant", 69, 68);
        assertNotNull(attacker);
        assertNotNull(quarry);

        while (fixtureCycle(mission.world()) < 21) {
            mission.tick();
        }
        assertChaser(attacker, 70, 67, -32, 0, true);
        assertEquals(30, quarry.hitPoints(),
                "the moving quarry cannot be struck from its former tile");

        while (fixtureCycle(mission.world()) < 37) {
            mission.tick();
        }
        assertChaser(attacker, 71, 66, -32, 32, true);

        while (fixtureCycle(mission.world()) < 114) {
            mission.tick();
        }
        assertChaser(attacker, 75, 62, -7, 7, true);
        assertFalse(quarry.isMoving(),
                "retail's quarry has just settled at fixture 114");
        assertEquals(30, quarry.hitPoints());

        while (fixtureCycle(mission.world()) < 117) {
            mission.tick();
        }
        assertChaser(attacker, 75, 62, 0, 0, false);
        assertEquals(30, quarry.hitPoints(),
                "the first swing begins only after the pursuer settles");

        while (fixtureCycle(mission.world()) < 127) {
            mission.tick();
        }
        assertEquals(25, quarry.hitPoints(),
                "retail's first legal blow lands at fixture 127");
    }

    private static Unit unitAt(World world, int player, String ident,
            int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.player() == player && unit.tileX() == x && unit.tileY() == y
                    && unit.type() != null && ident.equals(unit.type().ident())) {
                return unit;
            }
        }
        return null;
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - INITIALIZATION_TICKS;
    }

    private static void assertChaser(Unit attacker, int x, int y,
            int offsetX, int offsetY, boolean moving) {
        assertEquals(x, attacker.tileX());
        assertEquals(y, attacker.tileY());
        assertEquals(offsetX, attacker.offsetX());
        assertEquals(offsetY, attacker.offsetY());
        assertEquals(moving, attacker.isMoving());
        assertTrue(attacker.order() == Unit.Order.ATTACK,
                "the native chase remains an Attack order");
    }
}
