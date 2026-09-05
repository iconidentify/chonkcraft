package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A catapult's Attack wait owns the visit until the sequence marker.
 *
 * <p>Authenticated campaign-1800 fixture {@code retail-xhuman-10-idle}:
 * catapult 1487 / Java 113 is Attack 540/42 at fixture 562 with a parked
 * empty route. Native stays on (74,89) through a melee hit. Java's
 * presentation wait hitting zero used to fall through to MoveToBetterPos
 * and dest-arm southwest, spending three synchronized direction draws.</p>
 */
class XHuman10CatapultAttackWaitHoldRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a catapult attack wait does not dest-arm on a melee hit")
    void aCatapultAttackWaitDoesNotDestArmOnAMeleeHit() {
        Mission mission = mission("campaigns/human-exp/levelx10h");
        Unit catapult = byId(mission.world(), 113);
        assertNotNull(catapult,
                "XHuman 10 has no Java twin for native catapult 1487");

        tickThrough(mission, 561);
        assertEquals(74, catapult.tileX(),
                "the catapult is still on its firing square before the hit");
        assertEquals(89, catapult.tileY(),
                "the catapult is still on its firing square before the hit");
        assertEquals(540, catapult.battleNetSequenceOffset(),
                "Attack 540's wait is still counting at fixture 561");
        assertEquals(43, catapult.battleNetAnimationTimer(),
                "native records timer 43 on the visit before the melee hit");
        assertEquals(0, catapult.pathLength(),
                "the parked empty route is still empty");

        tickThrough(mission, 562);
        assertEquals(74, catapult.tileX(),
                "native remains on 74,89 through Attack 540's wait");
        assertEquals(89, catapult.tileY(),
                "MoveToBetterPos must not dest-arm during the sequence wait");
        assertEquals(540, catapult.battleNetSequenceOffset());
        assertEquals(42, catapult.battleNetAnimationTimer(),
                "the wait opcode still owns the visit");
        assertEquals(0, catapult.pathLength(),
                "the parked empty route is not replaced");
        assertEquals(56, catapult.hitPoints(),
                "the melee hit still lands for native's eight damage");
    }

    @Test
    @DisplayName("the same catapult still wraps its attack wait onto construction")
    void theSameCatapultStillWrapsItsAttackWaitOntoConstruction() {
        Mission mission = mission("campaigns/human-exp/levelx10h");
        Unit catapult = byId(mission.world(), 113);
        assertNotNull(catapult,
                "XHuman 10 has no Java twin for native catapult 1487");

        tickThrough(mission, 203);
        assertEquals(503, catapult.battleNetSequenceOffset(),
                "native wraps 540/1 onto Attack construction at fixture 203");
        assertEquals(3, catapult.battleNetAnimationTimer());
    }

    private static Mission mission(String map) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
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

    private static Unit byId(World world, int id) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }
}
