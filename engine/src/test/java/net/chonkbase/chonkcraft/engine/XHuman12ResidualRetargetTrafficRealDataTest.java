package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * XHuman 12's cycle-41 residual retarget leaves the axe lane open.
 *
 * <p>Authenticated campaign-1800 fixture {@code retail-xhuman-12-idle}:
 * grunt 1496 finishes its blocked SE residual on 30,39 at fixture 41 and
 * immediately retargets from the footman on 32,43 to the knight on 30,44.
 * It consequently stays on 30,39 at fixture 42. Grunt 1514 has no better
 * target and still takes native's free-compass N detour to 28,37. That lets
 * axethrower 1522 commit its cached SE into 30,38 in the same action cycle.
 * Java used to move grunt 1496 north first, block the axe, and make both
 * units diverge from the retail traffic pattern.
 */
class XHuman12ResidualRetargetTrafficRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 12 retargets before its cycle-42 axe traffic step")
    void xhuman12RetargetsBeforeItsCycle42AxeTrafficStep() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx12h",
                GameData.personIn(data.campaignMap(
                        "campaigns/human-exp/levelx12h")), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit north = unitAt(world, "unit-grunt", 30, 38);
        Unit west = unitAt(world, "unit-grunt", 27, 37);
        Unit axe = unitAt(world, "unit-axethrower", 28, 36);
        Unit knight = unitAt(world, "unit-knight", 30, 44);
        assertNotNull(north, "XHuman 12 has no north grunt on 30,38");
        assertNotNull(west, "XHuman 12 has no west grunt opening on 27,37");
        assertNotNull(axe, "XHuman 12 has no axethrower on 28,36");
        assertNotNull(knight, "XHuman 12 has no knight on 30,44");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Unit northTargetAt41 = null;
        int northPathLengthAt41 = -1;
        int northHeadingAt41 = -1;
        int northDelayAt41 = -1;
        int northTimerAt41 = -1;
        int northXAt42 = -1;
        int northYAt42 = -1;
        int westXAt42 = -1;
        int westYAt42 = -1;
        int axeXAt42 = -1;
        int axeYAt42 = -1;
        while (world.cycle() < 44) {
            mission.tick();
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 41) {
                northTargetAt41 = north.target();
                northPathLengthAt41 = north.pathLength();
                northHeadingAt41 = north.peekHeading();
                northDelayAt41 = north.battleNetOrderDelay();
                northTimerAt41 = north.battleNetAnimationTimer();
            }
            if (fixture == 42) {
                northXAt42 = north.tileX();
                northYAt42 = north.tileY();
                westXAt42 = west.tileX();
                westYAt42 = west.tileY();
                axeXAt42 = axe.tileX();
                axeYAt42 = axe.tileY();
            }
        }

        assertSame(knight, northTargetAt41,
                "native retargets the settled north grunt on fixture 41");
        assertEquals(5, northPathLengthAt41,
                "native caches five direct south headings to the knight");
        assertEquals(Direction.fromDelta(0, 1), northHeadingAt41,
                "the cached retarget route opens due south");
        assertEquals(14, northDelayAt41,
                "timer fifteen leaves fourteen quiet action visits");
        assertEquals(15, northTimerAt41,
                "the residual retarget transfers to Move timer fifteen");
        assertEquals(30, northXAt42,
                "the retargeted grunt must leave the axe lane open");
        assertEquals(39, northYAt42,
                "the retargeted grunt waits on 30,39 through fixture 42");
        assertEquals(28, westXAt42,
                "the west grunt keeps native's independent north detour");
        assertEquals(37, westYAt42,
                "the west grunt free-steps north on fixture 42");
        assertEquals(30, axeXAt42,
                "the axe must be able to commit its cached southeast step");
        assertEquals(38, axeYAt42,
                "the axe reaches native's 30,38 on fixture 42");
    }

    private static Unit unitAt(World world, String ident, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.type() != null
                    && ident.equals(unit.type().ident())
                    && unit.tileX() == x && unit.tileY() == y) {
                return unit;
            }
        }
        return null;
    }
}
