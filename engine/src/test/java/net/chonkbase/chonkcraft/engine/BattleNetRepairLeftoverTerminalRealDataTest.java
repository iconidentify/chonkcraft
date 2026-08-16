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
 * Leftover Harvest-to-Repair residual settle beside a full hall is Still.
 *
 * <p>Authenticated repair-1/04: Human 5 peasant 1512 keeps Harvest through
 * leftover dest-arm, pops Repair at leftover-land, and Stills on 39,100
 * when the Repair leftover last heading drains. Arming another delay 3
 * on that settle visit Still'd one cycle late.
 */
class BattleNetRepairLeftoverTerminalRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a leftover harvest repair stills when its leftover pixels land")
    void aLeftoverHarvestRepairStillsWhenItsLeftoverPixelsLand() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level05h", 0);
        Assumptions.assumeTrue(mission != null, "Human 5 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit peasant = nearestWorker(world, 34, 105);
        assertNotNull(peasant, "Human 5 has no peasant near 34,105");
        Unit hall = nearestHall(world, peasant);
        assertNotNull(hall, "Human 5 has no hall for the peasant to mend");
        boolean issued = false;
        Integer stillAt = null;
        Integer stillX = null;
        Integer stillY = null;
        while (fixtureCycle(world) <= 130) {
            if (fixtureCycle(world) == 5 && !issued) {
                assertTrue(commands.apply(GameCommand.repair(
                                peasant.player(), peasant.id(), hall.id())),
                        "GiveOrder 27 must accept the leftover harvest click");
                issued = true;
                assertEquals(Unit.Order.HARVEST, peasant.order(),
                        "retail keeps Harvest through the leftover dest-arm");
            }
            mission.tick();
            if (issued && stillAt == null
                    && peasant.order() == Unit.Order.STILL) {
                stillAt = fixtureCycle(world);
                stillX = peasant.tileX();
                stillY = peasant.tileY();
            }
        }
        assertTrue(issued, "the repair click must be issued");
        assertTrue(stillAt != null && stillAt < 121,
                "retail leftover last heading Stills on the leftover-promote "
                        + "quiet visits, not delay-3 fixture " + stillAt);
        assertEquals(39, stillX,
                "retail stands on 39,100, not " + stillX + "," + stillY);
        assertEquals(100, stillY,
                "retail stands on 39,100, not " + stillX + "," + stillY);
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit nearestWorker(World world, int x, int y) {
        Unit best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Unit unit : world.unitsSnapshot()) {
            if (!unit.isAlive() || !unit.isOnMap() || unit.type() == null) {
                continue;
            }
            String ident = unit.type().ident();
            if (!ident.contains("peasant") && !ident.contains("peon")) {
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

    private static Unit nearestHall(World world, Unit worker) {
        Unit best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Unit unit : world.unitsSnapshot()) {
            if (!unit.isAlive() || unit.type() == null
                    || !unit.type().building()
                    || unit.player() != worker.player()) {
                continue;
            }
            String ident = unit.type().ident();
            if (!ident.contains("town-hall") && !ident.contains("keep")
                    && !ident.contains("castle")) {
                continue;
            }
            int dist = worker.distanceTo(unit);
            if (dist < bestDist) {
                best = unit;
                bestDist = dist;
            }
        }
        return best;
    }
}
