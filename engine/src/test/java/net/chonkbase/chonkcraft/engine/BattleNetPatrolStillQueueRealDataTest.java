package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A player Patrol click from Still queues when Still wait remains, then
 * leftover dest-arm refuses a heading that does not close dest.
 *
 * <p>Authenticated patrol-1/00: Orc 1 grunt 1592 is Still with next_order
 * 5 through fixture 8, Patrol at 9, dest-arms NE at 12, and leftover last
 * heading is NE onto 22,18. Installing Patrol on the issue cycle
 * first-progressed at 5 and leftover last heading N from 22,19.
 *
 * <p>Authenticated patrol-1/01: peon 1594 is already on the Still marker
 * and dest-arms west at fixture 8. patrol-1/02 stays rejected.
 */
class BattleNetPatrolStillQueueRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an orc 1 grunt stays still until fixture 9 on a far patrol")
    void anOrc1GruntStaysStillUntilFixture9OnAFarPatrol() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc/level01o",
                GameData.personIn(data.campaignMap("campaigns/orc/level01o")), 1);
        Assumptions.assumeTrue(mission != null, "Orc 1 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit grunt = atTile(world, 18, 23);
        assertNotNull(grunt, "Orc 1 has no soldier on 18,23");
        boolean issued = false;
        Integer promoted = null;
        Integer destArm = null;
        Integer destFromX = null;
        Integer destFromY = null;
        int prevX = grunt.tileX();
        int prevY = grunt.tileY();
        while (fixtureCycle(world) <= 80) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.patrol(
                                grunt.player(), grunt.id(), 22, 18)),
                        "GiveOrder 5 must accept the grunt told to patrol");
                issued = true;
                assertEquals(Unit.Order.STILL, grunt.order(),
                        "retail keeps Still and next_order 5 through the remaining Still wait");
            }
            mission.tick();
            if (issued && promoted == null
                    && grunt.order() == Unit.Order.PATROL) {
                promoted = fixtureCycle(world);
            }
            if (issued && destArm == null
                    && (grunt.tileX() != 18 || grunt.tileY() != 23
                    || grunt.offsetX() != 0 || grunt.offsetY() != 0)) {
                destArm = fixtureCycle(world);
            }
            if (destFromX == null
                    && grunt.tileX() == 22 && grunt.tileY() == 18
                    && (prevX != 22 || prevY != 18)) {
                destFromX = prevX;
                destFromY = prevY;
            }
            prevX = grunt.tileX();
            prevY = grunt.tileY();
        }
        assertTrue(issued, "the patrol click must be issued");
        assertEquals(9, promoted,
                "retail keeps Still then Patrol at fixture 9, not " + promoted);
        assertEquals(12, destArm,
                "retail dest-arms the popped Patrol at fixture 12, not " + destArm);
        assertEquals(22, grunt.tileX(),
                "retail leftover last heading lands 22,18, not "
                        + grunt.tileX() + "," + grunt.tileY());
        assertEquals(18, grunt.tileY(),
                "retail leftover last heading lands 22,18, not "
                        + grunt.tileX() + "," + grunt.tileY());
        assertEquals(21, destFromX,
                "retail leftover last heading is NE onto 22,18 from 21,19, not "
                        + destFromX + "," + destFromY);
        assertEquals(19, destFromY,
                "retail leftover last heading is NE onto 22,18 from 21,19, not "
                        + destFromX + "," + destFromY);
        assertEquals(9, Math.floorMod(grunt.offsetX(), 32),
                "retail leftover last heading still has offset 9,23, not "
                        + grunt.offsetX() + "," + grunt.offsetY());
        assertEquals(23, Math.floorMod(grunt.offsetY(), 32),
                "retail leftover last heading still has offset 9,23, not "
                        + grunt.offsetX() + "," + grunt.offsetY());
    }

    @Test
    @DisplayName("an orc 1 peon already on still marker dest-arms patrol at fixture 8")
    void anOrc1PeonAlreadyOnStillMarkerDestArmsPatrolAtFixture8() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc/level01o",
                GameData.personIn(data.campaignMap("campaigns/orc/level01o")), 1);
        Assumptions.assumeTrue(mission != null, "Orc 1 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit peon = atTile(world, 25, 18);
        assertNotNull(peon, "Orc 1 has no peon on 25,18");
        boolean issued = false;
        Integer destArm = null;
        Integer destWait = null;
        while (fixtureCycle(world) <= 80) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.patrol(
                                peon.player(), peon.id(), 22, 18)),
                        "GiveOrder 5 must accept the marker-ready peon");
                issued = true;
                assertEquals(Unit.Order.PATROL, peon.order(),
                        "a peon already on the Still marker installs Patrol on the issue visit");
            }
            mission.tick();
            if (issued && destArm == null
                    && (peon.tileX() != 25 || peon.tileY() != 18
                    || peon.offsetX() != 0 || peon.offsetY() != 0)) {
                destArm = fixtureCycle(world);
            }
            if (issued && destWait == null && peon.waitCycles() >= 10
                    && peon.tileX() == 22 && peon.tileY() == 18) {
                destWait = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the patrol click must be issued");
        assertEquals(8, destArm,
                "retail dest-arms the marker-ready Patrol at fixture 8, not " + destArm);
        assertTrue(destWait == null,
                "retail leftover dest-arm that lands on dest is not PF_WAIT 10 at fixture "
                        + destWait);
        assertEquals(24, peon.tileX(),
                "retail turns around without PF_WAIT 10 and is on 24,18, not "
                        + peon.tileX() + "," + peon.tileY());
        assertEquals(18, peon.tileY(),
                "retail turns around without PF_WAIT 10 and is on 24,18, not "
                        + peon.tileX() + "," + peon.tileY());
        assertEquals(25, peon.orderTargetX(),
                "retail dest after the land visit is the start square 25,18");
    }

    @Test
    @DisplayName("a gold click still vanishes into the mine after its wait")
    void aGoldClickStillVanishesIntoTheMineAfterItsWait() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc/level01o", 0);
        Assumptions.assumeTrue(mission != null, "Orc 1 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit peon = atTile(world, 25, 18);
        assertNotNull(peon, "Orc 1 has no peon on 25,18");
        boolean issued = false;
        Integer offMap = null;
        while (fixtureCycle(world) <= 80) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.harvest(
                                peon.player(), peon.id(), 26, 13)),
                        "the gold click must be accepted");
                issued = true;
            }
            mission.tick();
            if (issued && offMap == null && !peon.isOnMap()) {
                offMap = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the gold click must be issued");
        assertTrue(offMap != null && offMap >= 50,
                "a gold harvest still pays the mine wait, not fixture " + offMap);
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit atTile(World world, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.type() != null
                    && !unit.type().building()
                    && unit.tileX() == x && unit.tileY() == y) {
                return unit;
            }
        }
        return null;
    }
}
