package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
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
    @DisplayName("an XOrc 8 assault battleship keeps its southeast route after crossing the goal row")
    void anXOrc8AssaultBattleshipKeepsSoutheastAfterCrossingGoalRow() {
        Mission mission = mission("campaigns/orc-exp/levelx08o");
        Unit battleship = at(mission.world(), "unit-battleship", 40, 108);
        assertNotNull(battleship,
                "XOrc 8 has no startup battleship at 40,108");
        assertEquals(2, battleship.battleNetAiBehavior(),
                "the full diagonal route belongs to the launched assault");

        for (int cycle = 1; cycle <= 393; cycle++) {
            mission.tick();
        }
        assertEquals(54, battleship.tileX());
        assertEquals(122, battleship.tileY());

        mission.tick();
        assertEquals(56, battleship.tileX(),
                "native slot 1424 keeps southeast instead of reducing it to south");
        assertEquals(124, battleship.tileY());
        assertEquals(Direction.fromDelta(1, 1),
                battleship.lastStepHeading());
    }

    @Test
    @DisplayName("an XHuman 7 juggernaught stands down when its map patrol route ends")
    void anXHuman7JuggernaughtStandsDownWhenItsMapPatrolRouteEnds() {
        Mission mission = mission("campaigns/human-exp/levelx07h");
        Unit juggernaught = at(mission.world(),
                "unit-ogre-juggernaught", 24, 24);
        assertNotNull(juggernaught,
                "XHuman 7 has no startup juggernaught at 24,24");
        assertEquals(6, juggernaught.battleNetAiBehavior(),
                "native ordinary naval patrols carry behavior six");

        for (int cycle = 1; cycle <= 58; cycle++) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, juggernaught.order());
        assertEquals(6, juggernaught.battleNetAiBehavior(),
                "ordinary AI-player patrols must remain behavior six");
        assertEquals(24, juggernaught.tileX());
        assertEquals(26, juggernaught.tileY());
        assertEquals(-2, juggernaught.offsetY(),
                "fixture 58 still owes the final two southbound pixels");

        mission.tick();
        assertEquals(Unit.Order.STILL, juggernaught.order(),
                "native exhausts the one-heading map patrol instead of "
                        + "inventing a north-west recovery leg");
        assertEquals(24, juggernaught.tileX());
        assertEquals(26, juggernaught.tileY());
        assertEquals(0, juggernaught.offsetX());
        assertEquals(0, juggernaught.offsetY());
    }

    @Test
    @DisplayName("an XHuman 7 juggernaught follows its tanker into the completed platform")
    void anXHuman7JuggernaughtFollowsItsTankerIntoTheCompletedPlatform() {
        Mission mission = mission("campaigns/human-exp/levelx07h");
        Unit juggernaught = at(mission.world(),
                "unit-ogre-juggernaught", 24, 24);
        assertNotNull(juggernaught,
                "XHuman 7 has no startup juggernaught at 24,24");

        for (int cycle = 1; cycle <= 248; cycle++) {
            mission.tick();
        }
        assertEquals(Unit.Order.STILL, juggernaught.order());
        assertEquals(22, juggernaught.battleNetAiHomeX());
        assertEquals(27, juggernaught.battleNetAiHomeY(),
                "the old shipyard home survives until the tanker's platform is ready");

        mission.tick();
        assertEquals(Unit.Order.STILL, juggernaught.order());
        assertEquals(24, juggernaught.battleNetAiHomeX(),
                "the fixture-249 naval beat resets home to the parked hull");
        assertEquals(26, juggernaught.battleNetAiHomeY());
        assertEquals(24, juggernaught.battleNetPendingPatrolX());
        assertEquals(26, juggernaught.battleNetPendingPatrolY());
        assertEquals(39, juggernaught.battleNetPendingPatrolBackX());
        assertEquals(33, juggernaught.battleNetPendingPatrolBackY(),
                "the completed platform becomes the far patrol endpoint");

        for (int cycle = 250; cycle <= 252; cycle++) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, juggernaught.order());
        assertEquals(24, juggernaught.orderTargetX());
        assertEquals(26, juggernaught.orderTargetY(),
                "the queued patrol promotes at the native fixture-252 marker");

        for (int cycle = 253; cycle <= 255; cycle++) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, juggernaught.order());
        assertEquals(39, juggernaught.orderTargetX());
        assertEquals(33, juggernaught.orderTargetY(),
                "the fixture-255 endpoint exchange opens the platform leg");

        mission.tick();
        mission.tick();
        assertEquals(24, juggernaught.tileX());
        assertEquals(26, juggernaught.tileY(),
                "the reconstructed Patrol holds through fixture 257");
        assertEquals(0, juggernaught.battleNetCollisionCounter());
        assertEquals(0, juggernaught.battleNetRefusals());

        mission.tick();
        assertEquals(24, juggernaught.tileX(),
                "native refuses its occupied direct east heading on fixture 258");
        assertEquals(26, juggernaught.tileY());
        assertEquals(7, juggernaught.pathLength(),
                "the complete direct platform route remains behind the refusal");
        int[] directPlatformRoute = {
            Direction.fromDelta(1, 0),
            Direction.fromDelta(1, 1),
            Direction.fromDelta(1, 0),
            Direction.fromDelta(1, 1),
            Direction.fromDelta(1, 0),
            Direction.fromDelta(1, 1),
            Direction.fromDelta(1, 0)
        };
        for (int depth = 0; depth < directPlatformRoute.length; depth++) {
            assertEquals(directPlatformRoute[depth],
                    juggernaught.peekHeadingAtDepth(depth),
                    "native direct platform route heading at depth " + depth);
        }
        assertEquals(1, juggernaught.battleNetCollisionCounter());
        assertEquals(14, juggernaught.battleNetOrderDelay());
        assertEquals(15, juggernaught.battleNetAnimationTimer());
        assertEquals(true, juggernaught.battleNetRefusalHold(),
                "the first occupied route byte owns a complete Move refusal band");

        for (int cycle = 259; cycle <= 272; cycle++) {
            mission.tick();
        }
        assertEquals(7, juggernaught.pathLength(),
                "the first paid band keeps the direct platform route live");
        assertEquals(1, juggernaught.battleNetCollisionCounter());
        assertEquals(1, juggernaught.battleNetAnimationTimer());

        mission.tick();
        assertEquals(7, juggernaught.pathLength(),
                "the blocked timer-one wake reuses the same route buffer");
        assertEquals(2, juggernaught.battleNetCollisionCounter(),
                "the second occupied wake advances the packed generation");
        assertEquals(0, juggernaught.battleNetRefusals(),
                "the live capital route does not enter the parked-route ladder");
        assertEquals(14, juggernaught.battleNetOrderDelay());
        assertEquals(15, juggernaught.battleNetAnimationTimer());

        for (int cycle = 274; cycle <= 287; cycle++) {
            mission.tick();
        }
        mission.tick();
        assertEquals(7, juggernaught.pathLength());
        assertEquals(3, juggernaught.battleNetCollisionCounter(),
                "the third blocked wake owns one final complete Move band");
        assertEquals(15, juggernaught.battleNetAnimationTimer());

        for (int cycle = 289; cycle <= 302; cycle++) {
            mission.tick();
        }
        mission.tick();
        assertEquals(26, juggernaught.tileX(),
                "fixture 303 consumes east when the retained route finally frees");
        assertEquals(26, juggernaught.tileY());
        assertEquals(6, juggernaught.pathLength());
        assertEquals(1, juggernaught.battleNetPathStepsTaken());
        assertEquals(3, juggernaught.battleNetCollisionCounter(),
                "the successful probe keeps native generation-three provenance");
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
