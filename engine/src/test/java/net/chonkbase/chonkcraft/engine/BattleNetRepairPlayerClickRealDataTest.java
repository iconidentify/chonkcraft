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
 * A player Repair click uses leftover dest-arm and player Move.
 *
 * <p>Authenticated repair-1/02: peon 1594 told to mend grunt 1592 is
 * Move with timer 3 and Still on 19,23 at fixture 104. Autonomous
 * orderMove delay 2 Still'd at 103.
 *
 * <p>Authenticated repair-1/04: leftover harvest 1512 keeps Harvest and
 * next_order 27 until leftover lands, Repair at 19, Still at 118.
 * Installing Repair on the issue cycle Still'd at 119.
 */
class BattleNetRepairPlayerClickRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a peon told to mend a grunt is still on 19,23 at fixture 104")
    void aPeonToldToMendAGruntIsStillOn1923AtFixture104() {
        assertStillAt("campaigns/orc/level01o", 25, 18, 18, 23,
                Unit.Order.MOVE, 104, 19, 23, true);
    }

    @Test
    @DisplayName("a leftover harvest mend stays Harvest until leftover lands")
    void aLeftoverHarvestMendStaysHarvestUntilLeftoverLands() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level05h",
                GameData.personIn(data.campaignMap("campaigns/human/level05h")), 1);
        Assumptions.assumeTrue(mission != null, "Human 5 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit worker = atTile(world, 34, 105, false);
        Unit hall = atTile(world, 40, 96, true);
        assertNotNull(worker, "Human 5 has no peasant on 34,105");
        assertNotNull(hall, "Human 5 has no hall on 40,96");
        boolean issued = false;
        Integer repaired = null;
        while (fixtureCycle(world) <= 24) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.repair(
                                worker.player(), worker.id(), hall.id())),
                        "GiveOrder 27 must accept the leftover harvest mend");
                issued = true;
                assertEquals(Unit.Order.HARVEST, worker.order(),
                        "retail keeps Harvest and next_order 27 through leftover dest-arm");
            }
            mission.tick();
            if (issued && repaired == null
                    && worker.order() == Unit.Order.REPAIR) {
                repaired = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the mend click must be issued");
        assertEquals(19, repaired,
                "retail pops Repair at leftover land fixture 19, not " + repaired);
    }

    @Test
    @DisplayName("an orc 1 hall mend is still Repair through fixture 55")
    void anOrc1HallMendIsStillRepairThroughFixture55() {
        assertStillAt("campaigns/orc/level01o", 25, 18, 22, 22,
                Unit.Order.REPAIR, 56, 26, 21, false);
    }

    private static void assertStillAt(String map, int x, int y,
            int destX, int destY, Unit.Order walking, int stillFixture,
            int stillX, int stillY, boolean gruntTarget) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map, GameData.personIn(
                data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit worker = atTile(world, x, y, false);
        if (worker == null) {
            worker = nearest(world, x, y, false);
        }
        Unit target = gruntTarget
                ? nearest(world, destX, destY, false)
                : atTile(world, destX, destY, true);
        if (target == null) {
            target = nearest(world, destX, destY, true);
        }
        assertNotNull(worker, map + " has no worker near " + x + "," + y);
        assertNotNull(target, map + " has no target near " + destX + "," + destY);
        boolean issued = false;
        Integer stillAt = null;
        Integer stoodX = null;
        Integer stoodY = null;
        while (fixtureCycle(world) <= stillFixture + 8) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.repair(
                                worker.player(), worker.id(), target.id())),
                        "GiveOrder 27 must accept the mend click");
                issued = true;
            }
            mission.tick();
            if (issued && stillAt == null && worker.order() == Unit.Order.STILL
                    && (walking != Unit.Order.MOVE
                            || worker.tileX() == stillX
                            && worker.tileY() == stillY)) {
                stillAt = fixtureCycle(world);
                stoodX = worker.tileX();
                stoodY = worker.tileY();
            }
        }
        assertTrue(issued, "the mend click must be issued");
        assertEquals(stillX, stoodX,
                "retail stands on " + stillX + "," + stillY + ", not "
                        + stoodX + "," + stoodY);
        assertEquals(stillY, stoodY,
                "retail stands on " + stillX + "," + stillY + ", not "
                        + stoodX + "," + stoodY);
        assertEquals(stillFixture, stillAt,
                "retail stands Still at fixture " + stillFixture + ", not "
                        + stillAt);
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit atTile(World world, int x, int y, boolean building) {
        for (Unit unit : world.unitsSnapshot()) {
            if (!unit.isAlive() || !unit.isOnMap() || unit.type() == null) {
                continue;
            }
            if (unit.type().building() != building) {
                continue;
            }
            if (unit.tileX() == x && unit.tileY() == y) {
                return unit;
            }
        }
        return null;
    }

    private static Unit nearest(World world, int x, int y, boolean building) {
        Unit best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Unit unit : world.unitsSnapshot()) {
            if (!unit.isAlive() || !unit.isOnMap() || unit.type() == null) {
                continue;
            }
            if (unit.type().building() != building) {
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
