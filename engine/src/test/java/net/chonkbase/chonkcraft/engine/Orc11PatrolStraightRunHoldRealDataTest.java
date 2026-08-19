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
 * Orc 11's type-two assault archer holds after a three-heading straight run.
 *
 * <p>Authenticated campaign-1800 fixture {@code retail-orc-11-idle}: archer
 * 1559 consumes its third consecutive north-east route byte onto 115,41, then
 * moves the route cursor to 20 on fixture 49. Native serves Still construction
 * 3,2,1 on fixtures 50 through 52 and commits the replacement north-east step
 * only on fixture 53. Java used to consume the fourth cached heading on
 * fixture 50.</p>
 */
class Orc11PatrolStraightRunHoldRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("orc 11's assault archer holds through fixture 52 after three NE steps")
    void orc11AssaultArcherHoldsThroughFixture52AfterThreeNorthEastSteps() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc/level11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Orc 11 is not in the pack");
        World world = mission.world();

        Unit archer = unitAt(world, "unit-archer", 1, 112, 44);
        assertNotNull(archer,
                "Orc 11 has no player-one assault archer opening on 112,44");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        int xAt49 = -1;
        int yAt49 = -1;
        int xAt50 = -1;
        int yAt50 = -1;
        int xAt52 = -1;
        int yAt52 = -1;
        int xAt53 = -1;
        int yAt53 = -1;
        while (fixtureCycle(world) < 53) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 49) {
                xAt49 = archer.tileX();
                yAt49 = archer.tileY();
            }
            if (fixture == 50) {
                xAt50 = archer.tileX();
                yAt50 = archer.tileY();
            }
            if (fixture == 52) {
                xAt52 = archer.tileX();
                yAt52 = archer.tileY();
            }
            if (fixture == 53) {
                xAt53 = archer.tileX();
                yAt53 = archer.tileY();
            }
        }

        assertEquals(115, xAt49,
                "the third north-east step lands on 115,41 before the hold");
        assertEquals(41, yAt49,
                "the third north-east step lands on native's 115,41");
        assertEquals(115, xAt50,
                "Still construction must prevent the fourth step on fixture 50");
        assertEquals(41, yAt50,
                "the archer stays on 115,41 at the hold's first visit");
        assertEquals(115, xAt52,
                "native remains held through fixture 52");
        assertEquals(41, yAt52,
                "native remains on 115,41 through fixture 52");
        assertEquals(116, xAt53,
                "the replacement route commits north-east on fixture 53");
        assertEquals(40, yAt53,
                "fixture 53 reaches native's 116,40");
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit unitAt(World world, String ident, int player,
            int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.player() == player
                    && unit.type() != null
                    && ident.equals(unit.type().ident())
                    && unit.tileX() == x && unit.tileY() == y) {
                return unit;
            }
        }
        return null;
    }
}
