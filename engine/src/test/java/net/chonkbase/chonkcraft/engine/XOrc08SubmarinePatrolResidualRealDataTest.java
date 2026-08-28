package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated consumed-route Patrol refusal from retail XOrc 8. */
class XOrc08SubmarinePatrolResidualRealDataTest {

    private static final String MAP = "campaigns/orc-exp/levelx08o";
    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("XOrc 8 submarine holds its consumed Patrol tail behind an allied hull")
    void xOrc8SubmarineHoldsConsumedPatrolTailBehindAlliedHull() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK");
        Mission mission = mission(new GameData(assets));

        tickThrough(mission, 259);
        Unit submarine = focusSubmarine(mission.world());
        assertNotNull(submarine,
                "XOrc 8 has no behavior-six submarine approaching 91,75");
        assertEquals(Unit.Order.PATROL, submarine.order());
        assertEquals(92, submarine.tileX());
        assertEquals(78, submarine.tileY());
        assertTrue(submarine.isMoving());
        assertEquals(1, submarine.pathLength(),
                "the seventh northwest heading is still cached");
        assertEquals(6, submarine.battleNetPathStepsTaken(),
                "six headings from the original route were consumed");

        Unit blocker = alliedSubmarineAt(mission.world(), 90, 76);
        assertNotNull(blocker,
                "native slot 1434 occupies the cached northwest destination");
        assertTrue(blocker.isMoving(),
                "the allied hull is a temporary body, not permanent terrain");

        tickThrough(mission, 260);
        assertEquals(92, submarine.tileX());
        assertEquals(78, submarine.tileY());
        assertFalse(submarine.isMoving(),
                "the preceding stride has settled at the blocked anchor");
        assertEquals(1, submarine.pathLength(),
                "native retains the consumed northwest tail through refusal");
        assertEquals(6, submarine.battleNetPathStepsTaken());
        assertEquals(14, submarine.battleNetOrderDelay(),
                "the cached live-body refusal owns a full Move band");
        assertEquals(15, submarine.battleNetAnimationTimer());

        tickThrough(mission, 261);
        assertEquals(92, submarine.tileX(),
                "native remains at 92,78 on the first divergent fixture");
        assertEquals(78, submarine.tileY());
        assertEquals(1, submarine.pathLength());
        assertEquals(14, submarine.battleNetAnimationTimer());
    }

    private static Mission mission(GameData data) {
        Mission mission = data.loadMission(MAP,
                GameData.personIn(data.campaignMap(MAP)), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 8 is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        return mission;
    }

    private static void tickThrough(Mission mission, int fixtureCycle) {
        while (mission.world().cycle() - BNE_INITIALIZATION_TICKS
                < fixtureCycle) {
            mission.tick();
        }
    }

    private static Unit focusSubmarine(World world) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.player() == 2
                    && unit.type() != null
                    && "unit-human-submarine".equals(unit.type().ident())
                    && unit.battleNetAiBehavior() == 6
                    && unit.orderTargetX() == 91
                    && unit.orderTargetY() == 75) {
                return unit;
            }
        }
        return null;
    }

    private static Unit alliedSubmarineAt(World world, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.player() == 2
                    && unit.tileX() == x && unit.tileY() == y
                    && unit.type() != null
                    && "unit-human-submarine".equals(unit.type().ident())) {
                return unit;
            }
        }
        return null;
    }
}
