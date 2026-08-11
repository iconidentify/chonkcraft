package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * XHuman 07 submarine patrol return lands on open water at fixture 48.
 *
 * <p>Coast-goal Still used to leave the sub at 20,52 under STILL while native
 * double-stepped to 18,52 under PATROL. The retail map is the efficacy check:
 * synthetic maps rarely answer empty-FOUND the way the coast rewrite does.
 */
class NavalPatrolCoastGoalRealDataTest {

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");
        return new GameData(assets);
    }

    @Test
    @DisplayName("xhuman 07 submarine patrols open water at fixture 48")
    void xHuman07SubmarinePatrolsOpenWaterAtFixture48() {
        Mission mission = load().loadMission("campaigns/human-exp/levelx07h");
        Assumptions.assumeTrue(mission != null, "levelx07h did not load");
        // Fixture cycle = world − 2; fixture 48 is world 50.
        for (int i = 0; i < 50; i++) {
            mission.tick();
        }
        World world = mission.world();
        Unit sub = null;
        for (Unit u : world.units) {
            if (u != null && u.type() != null
                    && "unit-orc-submarine".equals(u.type().ident())
                    && u.player() == 6
                    && u.tileX() >= 16 && u.tileX() <= 22
                    && u.tileY() >= 50 && u.tileY() <= 56) {
                sub = u;
                break;
            }
        }
        assertTrue(sub != null, "western orc submarine not found");
        assertEquals(Unit.Order.PATROL, sub.order(),
                "coast empty-FOUND must keep Patrol, not Still "
                        + "(native 1511 fixture 48)");
        assertEquals(18, sub.tileX(),
                "native double-steps west to open water 18,52 at fixture 48");
        assertEquals(52, sub.tileY(),
                "native double-steps west to open water 18,52 at fixture 48");
    }
}
