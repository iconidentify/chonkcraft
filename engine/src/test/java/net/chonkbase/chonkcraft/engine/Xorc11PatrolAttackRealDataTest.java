package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * XOrc 11's human battleship leaves Patrol for Attack at fixture 58.
 *
 * <p>Authenticated campaign-1800 fixture {@code retail-xorc-11-idle}:
 * battleship 1511 opens on 20,40, takes its first west stride under Patrol
 * (order 5) at fixture 5, and is on Attack (order 12) at fixture 58, still
 * on 18,40. The case's named patrol/attack witness is this promotion, not
 * the neighbouring destroyer's brief fixture-53 patrol.
 */
class Xorc11PatrolAttackRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xorc 11's opening battleship is attacking on cycle 58")
    void xorc11sOpeningBattleshipIsAttackingOnCycle58() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc-exp/levelx11o", 0);
        Assumptions.assumeTrue(mission != null, "XOrc 11 is not in the pack");
        World world = mission.world();

        // Native slot 1511 stands on 18,40. Java's 2x2 even-grid placement
        // opens that hull on 20,40; take the battleship nearest 18,40.
        Unit ship = nearest(world, "unit-battleship", 18, 40);
        assertNotNull(ship, "XOrc 11 has no human battleship near 18,40");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Unit.Order orderAt5 = null;
        Unit.Order orderAt58 = null;
        while (world.cycle() < 60) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 5) {
                orderAt5 = ship.order();
            }
            if (fixture == 58) {
                orderAt58 = ship.order();
            }
        }

        assertEquals(Unit.Order.PATROL, orderAt5,
                "retail's battleship is still on Patrol at cycle 5, not "
                        + orderAt5);
        assertEquals(Unit.Order.ATTACK, orderAt58,
                "retail's battleship leaves Patrol for Attack at cycle 58, not "
                        + orderAt58);
        assertEquals(40, ship.tileY(),
                "the battleship must still stand on the 40-row when it opens Attack");
        assertTrue(Math.abs(ship.tileX() - 18) <= 2,
                "the battleship must still stand beside 18,40 when it opens Attack, not "
                        + ship.tileX() + "," + ship.tileY());
    }

    private static Unit nearest(World world, String ident, int x, int y) {
        Unit best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Unit unit : world.unitsSnapshot()) {
            if (!unit.isAlive() || !unit.isOnMap() || unit.type() == null
                    || !ident.equals(unit.type().ident())) {
                continue;
            }
            int dist = Math.max(Math.abs(unit.tileX() - x),
                    Math.abs(unit.tileY() - y));
            if (dist < bestDist) {
                best = unit;
                bestDist = dist;
            }
        }
        return best;
    }
}
