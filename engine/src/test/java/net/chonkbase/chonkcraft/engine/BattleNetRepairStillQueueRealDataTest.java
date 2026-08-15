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
 * A soldier's hall-mend click from Still queues order 27.
 *
 * <p>Authenticated repair-1/03: Orc 1 grunt 1592 is Still with next_order
 * 27 through fixture 8 and Repair at 9, then Still on 21,23 at 60.
 * Installing Repair on the issue cycle walked at 5 and stood down at 56.
 * A peon who can mend still installs Repair immediately (repair-1/00).
 */
class BattleNetRepairStillQueueRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an orc 1 grunt stays Still until fixture 9 on a hall mend")
    void anOrc1GruntStaysStillUntilFixture9OnAHallMend() {
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
        Unit grunt = nearest(world, 18, 23, ident -> ident.contains("grunt"));
        Unit hall = nearest(world, 22, 22, ident -> ident.contains("great-hall"));
        assertNotNull(grunt, "Orc 1 has no grunt near 18,23");
        assertNotNull(hall, "Orc 1 has no great hall");
        boolean issued = false;
        Integer promoted = null;
        Integer stillAt = null;
        while (fixtureCycle(world) <= 70) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.repair(
                                grunt.player(), grunt.id(), hall.id())),
                        "GiveOrder 27 must accept a grunt told to mend a hall");
                issued = true;
            }
            mission.tick();
            if (issued && promoted == null
                    && grunt.order() == Unit.Order.REPAIR) {
                promoted = fixtureCycle(world);
            }
            if (issued && stillAt == null && grunt.order() == Unit.Order.STILL
                    && promoted != null) {
                stillAt = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the hall mend click must be issued");
        assertEquals(9, promoted,
                "retail keeps Still then Repair at fixture 9, not " + promoted);
        assertEquals(21, grunt.tileX(),
                "retail's grunt stands on 21,23, not "
                        + grunt.tileX() + "," + grunt.tileY());
        assertEquals(23, grunt.tileY(),
                "retail's grunt stands on 21,23, not "
                        + grunt.tileX() + "," + grunt.tileY());
        assertEquals(60, stillAt,
                "retail stands Still at fixture 60 after the walk, not "
                        + stillAt);
    }

    @Test
    @DisplayName("an orc 1 peon installs Repair on the hall-mend issue visit")
    void anOrc1PeonInstallsRepairOnTheHallMendIssueVisit() {
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
        Unit peon = nearest(world, 25, 18, ident -> ident.contains("peon"));
        Unit hall = nearest(world, 22, 22, ident -> ident.contains("great-hall"));
        assertNotNull(peon, "Orc 1 has no peon near 25,18");
        assertNotNull(hall, "Orc 1 has no great hall");
        boolean issued = false;
        Integer promoted = null;
        while (fixtureCycle(world) <= 16) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.repair(
                                peon.player(), peon.id(), hall.id())),
                        "GiveOrder 27 must accept a peon told to mend a hall");
                issued = true;
            }
            mission.tick();
            if (issued && promoted == null
                    && peon.order() == Unit.Order.REPAIR) {
                promoted = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the hall mend click must be issued");
        assertEquals(5, promoted,
                "a peon who can mend installs Repair on the issue visit, not "
                        + promoted);
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit nearest(World world, int x, int y,
            java.util.function.Predicate<String> wanted) {
        Unit best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Unit unit : world.unitsSnapshot()) {
            if (!unit.isAlive() || !unit.isOnMap() || unit.type() == null) {
                continue;
            }
            if (!wanted.test(unit.type().ident())) {
                continue;
            }
            int dist = Math.max(Math.abs(unit.tileX() - x),
                    Math.abs(unit.tileY() - y));
            if (dist < bestDist) {
                best = unit;
                bestDist = dist;
            }
        }
        return best;
    }
}
