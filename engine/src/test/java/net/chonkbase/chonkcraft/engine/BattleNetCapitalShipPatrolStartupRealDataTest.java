package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated capital-ship startup patrol timing from retail BNE. */
class BattleNetCapitalShipPatrolStartupRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an XOrc 7 battleship takes its opening west patrol stride on fixture cycle two")
    void anXOrc7BattleshipTakesItsOpeningWestPatrolStrideOnFixtureCycleTwo() {
        Mission mission = mission("campaigns/orc-exp/levelx07o");
        Unit battleship = at(mission.world(), "unit-battleship", 92, 6);
        assertNotNull(battleship, "XOrc 7 has no startup battleship at 92,6");

        mission.tick();
        assertEquals(92, battleship.tileX(), "native holds through fixture cycle one");
        mission.tick();
        assertEquals(Unit.Order.PATROL, battleship.order());
        assertEquals(90, battleship.tileX(),
                "native slot 1592 takes its first doubled west stride on cycle two");
        assertEquals(6, battleship.tileY());
    }

    @Test
    @DisplayName("an XOrc 11 battleship takes its opening west patrol stride on fixture cycle five")
    void anXOrc11BattleshipTakesItsOpeningWestPatrolStrideOnFixtureCycleFive() {
        Mission mission = mission("campaigns/orc-exp/levelx11o");
        Unit battleship = at(mission.world(), "unit-battleship", 20, 40);
        assertNotNull(battleship, "XOrc 11 has no startup battleship at 20,40");

        for (int cycle = 1; cycle < 5; cycle++) {
            mission.tick();
            assertEquals(20, battleship.tileX(),
                    "native holds its starting tile through fixture cycle " + cycle);
        }
        mission.tick();
        assertEquals(Unit.Order.PATROL, battleship.order());
        assertEquals(18, battleship.tileX(),
                "native slot 1511 takes its first doubled west stride on cycle five");
        assertEquals(40, battleship.tileY());

        for (int cycle = 6; cycle < 58; cycle++) {
            mission.tick();
            assertEquals(Unit.Order.PATROL, battleship.order(),
                    "the queued attack must remain behind Patrol through cycle " + cycle);
            assertEquals(18, battleship.tileX());
            assertEquals(40, battleship.tileY());
        }
        mission.tick();
        assertEquals(Unit.Order.ATTACK, battleship.order(),
                "native promotes the queued attack at the next Move-body marker");
        assertEquals(18, battleship.tileX(),
                "the attack promotion must not spend another stride on cycle 58");
        assertEquals(40, battleship.tileY());
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
