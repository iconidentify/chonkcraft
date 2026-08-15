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
 * A soldier's empty send-home from Still queues Return-Goods.
 *
 * <p>Authenticated return-goods-1/01: grunt 1592 at 18,23 keeps Still and
 * next_order 24 through fixture 8, Return-Goods at 9, and is inside the
 * hall at 79. Installing the walk on the issue cycle first-progressed at
 * 5 and never left 18,23.
 */
class BattleNetReturnGoodsSoldierQueueRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an orc 1 grunt send-home stays Still until fixture 9")
    void anOrc1GruntSendHomeStaysStillUntilFixture9() {
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
        assertTrue(grunt.type() == null || !grunt.type().canGather(),
                "the 18,23 actor must be a soldier, not a peon");
        boolean issued = false;
        Integer walkAt = null;
        Integer enterAt = null;
        while (fixtureCycle(world) <= 85) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.returnGoods(
                                grunt.player(), grunt.id())),
                        "GiveOrder 24 must accept the soldier send-home");
                issued = true;
            }
            mission.tick();
            if (issued && walkAt == null
                    && grunt.order() != Unit.Order.STILL) {
                walkAt = fixtureCycle(world);
            }
            if (issued && enterAt == null && !grunt.isOnMap()) {
                enterAt = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the send-home must be issued");
        assertEquals(9, walkAt,
                "retail keeps Still through fixture 8 and walks at 9, not "
                        + walkAt);
        assertEquals(79, enterAt,
                "retail is inside the hall at fixture 79, not " + enterAt);
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
