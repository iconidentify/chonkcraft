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
    @DisplayName("an XHuman 8 juggernaught restarts its sequence after the startup endpoint swap")
    void anXHuman8JuggernaughtRestartsItsSequenceAfterTheStartupEndpointSwap() {
        Mission mission = mission("campaigns/human-exp/levelx08h");
        Unit juggernaught = at(mission.world(), "unit-ogre-juggernaught", 20, 58);
        assertNotNull(juggernaught,
                "XHuman 8 has no startup juggernaught at 20,58");

        for (int cycle = 1; cycle < 5; cycle++) {
            mission.tick();
            assertEquals(Unit.Order.PATROL, juggernaught.order());
            assertEquals(20, juggernaught.tileX(),
                    "native holds its starting tile through fixture cycle " + cycle);
            assertEquals(58, juggernaught.tileY());
        }
        mission.tick();
        assertEquals(Unit.Order.PATROL, juggernaught.order());
        assertEquals(22, juggernaught.tileX(),
                "native slot 1535 takes its first doubled east stride on cycle five");
        assertEquals(58, juggernaught.tileY());
    }

    @Test
    @DisplayName("an XHuman 8 juggernaught restarts after its far endpoint swap")
    void anXHuman8JuggernaughtRestartsAfterItsFarEndpointSwap() {
        Mission mission = mission("campaigns/human-exp/levelx08h");
        Unit juggernaught = at(mission.world(), "unit-ogre-juggernaught", 20, 58);
        assertNotNull(juggernaught,
                "XHuman 8 has no startup juggernaught at 20,58");

        for (int cycle = 1; cycle <= 219; cycle++) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, juggernaught.order());
        assertEquals(28, juggernaught.tileX());
        assertEquals(58, juggernaught.tileY());

        mission.tick();
        assertEquals(Unit.Order.PATROL, juggernaught.order());
        assertEquals(26, juggernaught.tileX(),
                "native slot 1535 takes its doubled west stride on cycle 220");
        assertEquals(58, juggernaught.tileY());
    }

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
    @DisplayName("an XOrc 8 battleship finishes each doubled patrol stride before replanning")
    void anXOrc8BattleshipFinishesItsDoubledStrideBeforeReplanning() {
        Mission mission = mission("campaigns/orc-exp/levelx08o");
        Unit battleship = at(mission.world(), "unit-battleship", 40, 108);
        assertNotNull(battleship,
                "XOrc 8 has no startup battleship at 40,108");

        for (int cycle = 1; cycle <= 54; cycle++) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, battleship.order());
        assertEquals(42, battleship.tileX());
        assertEquals(110, battleship.tileY());
        assertEquals(-2, battleship.offsetX(),
                "fixture 54 still owes the last two horizontal pixels");
        assertEquals(-2, battleship.offsetY(),
                "fixture 54 still owes the last two vertical pixels");

        mission.tick();
        assertEquals(42, battleship.tileX(),
                "fixture 55 settles the first doubled stride before replanning");
        assertEquals(110, battleship.tileY());
        assertEquals(0, battleship.offsetX());
        assertEquals(0, battleship.offsetY());

        mission.tick();
        mission.tick();
        assertEquals(42, battleship.tileX(),
                "native holds the settled patrol through fixture 57");
        assertEquals(110, battleship.tileY());

        mission.tick();
        assertEquals(44, battleship.tileX(),
                "fixture 58 spends the next doubled south-east patrol stride");
        assertEquals(112, battleship.tileY());
        assertEquals(-64, battleship.offsetX());
        assertEquals(-64, battleship.offsetY());
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
