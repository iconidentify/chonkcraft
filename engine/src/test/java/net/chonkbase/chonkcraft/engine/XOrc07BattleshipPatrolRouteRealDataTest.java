package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated retained-route cadence for XOrc 7's map battleship patrol. */
class XOrc07BattleshipPatrolRouteRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("XOrc 7's battleship retains its patrol route through the fourth stride")
    void xOrc7BattleshipRetainsItsPatrolRouteThroughTheFourthStride() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc-exp/levelx07o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 7 is not in the pack");

        Unit battleship = at(mission.world(), "unit-battleship", 92, 6);
        assertNotNull(battleship,
                "XOrc 7 has no startup battleship at 92,6");
        assertEquals(6, battleship.battleNetAiBehavior(),
                "the map-authored patrol is an ordinary behavior-six route");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int fixtureCycle = 1; fixtureCycle <= 160; fixtureCycle++) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, battleship.order());
        assertEquals(86, battleship.tileX());
        assertEquals(6, battleship.tileY(),
                "native has spent three west headings through fixture 160");
        mission.tick();
        assertEquals(Unit.Order.PATROL, battleship.order());
        assertEquals(84, battleship.tileX(),
                "native spends the retained route's fourth heading at fixture 161");
        assertEquals(8, battleship.tileY(),
                "the retained fourth heading is south-west, not a replanned west");
    }

    private static Unit at(World world, String ident, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.tileX() == x
                    && unit.tileY() == y && unit.type() != null
                    && ident.equals(unit.type().ident())) {
                return unit;
            }
        }
        return null;
    }
}
