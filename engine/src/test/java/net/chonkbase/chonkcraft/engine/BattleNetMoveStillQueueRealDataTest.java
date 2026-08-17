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
 * A player Move click from Still queues order 3 when Still wait remains.
 *
 * <p>Authenticated Human 1 field twins: footman 1598 at 21,5 is Still with
 * next_order 3 dest 25,28 at cycle 5 and MOVE at 6, then dest-arms at 9.
 * Installing MOVE on the issue visit showed MOVE at cycle 5. Footman 1597
 * at 17,7 has queueWait 4 and stays Still through cycle 8, MOVE at 9,
 * dest-arm at 12.
 */
class BattleNetMoveStillQueueRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;
    private static final String MAP = "campaigns/human/level01h";

    @Test
    @DisplayName("a human 1 footman stays Still with next Move on the issue visit")
    void aHuman1FootmanStaysStillWithNextMoveOnTheIssueVisit() {
        Walk walk = walk(21, 5, 25, 28, 20);
        assertEquals(Unit.Order.STILL, walk.orderAt(5),
                "retail is Still with next Move at cycle 5, not " + walk.orderAt(5));
        assertEquals(Unit.Order.MOVE, walk.orderAt(6),
                "retail promotes the queued Move at cycle 6, not " + walk.orderAt(6));
        assertEquals(9, walk.destArm,
                "retail dest-arms the popped Move at cycle 9, not " + walk.destArm);
        assertEquals(22, walk.destArmX,
                "retail dest-arms onto 22,6, not "
                        + walk.destArmX + "," + walk.destArmY);
        assertEquals(6, walk.destArmY,
                "retail dest-arms onto 22,6, not "
                        + walk.destArmX + "," + walk.destArmY);
    }

    @Test
    @DisplayName("a second human 1 footman stays Still through the shared Still body")
    void aSecondHuman1FootmanStaysStillThroughTheSharedStillBody() {
        Walk walk = walk(17, 7, 25, 28, 20);
        assertEquals(Unit.Order.STILL, walk.orderAt(5),
                "retail is Still with next Move at cycle 5, not " + walk.orderAt(5));
        assertEquals(Unit.Order.STILL, walk.orderAt(8),
                "retail keeps Still through the shared 4985 body, not "
                        + walk.orderAt(8) + " at cycle 8");
        assertEquals(Unit.Order.MOVE, walk.orderAt(9),
                "retail promotes the queued Move at cycle 9, not " + walk.orderAt(9));
        assertEquals(12, walk.destArm,
                "retail dest-arms the popped Move at cycle 12, not " + walk.destArm);
    }

    private static Walk walk(int startX, int startY, int destX, int destY,
            int lastFixture) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(MAP,
                GameData.personIn(data.campaignMap(MAP)), 1);
        Assumptions.assumeTrue(mission != null, "Human 1 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit footman = atTile(world, startX, startY);
        assertNotNull(footman, "Human 1 has no footman on " + startX + "," + startY);
        boolean issued = false;
        Integer destArm = null;
        int destArmX = Integer.MIN_VALUE;
        int destArmY = Integer.MIN_VALUE;
        Unit.Order[] orders = new Unit.Order[lastFixture + 1];
        while (fixtureCycle(world) <= lastFixture) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.move(
                                footman.player(), footman.id(), destX, destY)),
                        "the field click to " + destX + "," + destY + " must be accepted");
                assertEquals(Unit.Order.STILL, footman.order(),
                        "retail keeps Still and next_order Move through the remaining Still wait");
                issued = true;
            }
            mission.tick();
            int cycle = fixtureCycle(world);
            if (cycle >= 0 && cycle < orders.length) {
                orders[cycle] = footman.order();
            }
            if (issued && destArm == null
                    && (footman.tileX() != startX || footman.tileY() != startY
                    || footman.offsetX() != 0 || footman.offsetY() != 0)) {
                destArm = cycle;
                destArmX = footman.tileX();
                destArmY = footman.tileY();
            }
        }
        assertTrue(issued, "the field click must be issued");
        return new Walk(destArm, destArmX, destArmY, orders);
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit atTile(World world, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.tileX() == x
                    && unit.tileY() == y && unit.type() != null
                    && !unit.type().building() && unit.type().speed() > 0) {
                return unit;
            }
        }
        return null;
    }

    private static final class Walk {
        private final Integer destArm;
        private final int destArmX;
        private final int destArmY;
        private final Unit.Order[] orders;

        private Walk(Integer destArm, int destArmX, int destArmY, Unit.Order[] orders) {
            this.destArm = destArm;
            this.destArmX = destArmX;
            this.destArmY = destArmY;
            this.orders = orders;
        }

        private Unit.Order orderAt(int cycle) {
            return cycle >= 0 && cycle < orders.length ? orders[cycle] : null;
        }
    }
}
