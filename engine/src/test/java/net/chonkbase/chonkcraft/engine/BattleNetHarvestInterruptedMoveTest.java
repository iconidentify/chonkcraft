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
 * A harvest leftover does not swallow a player Move click.
 *
 * <p>Authenticated commanded fixture {@code batch-1/08}: Human 5 peasant
 * 1512 is dest-armed on harvest at 33,106 when the click to 34,103 lands.
 * Native keeps Harvest, rewrites the order point, promotes Move around
 * fixture 22 and stands on 34,103 at 70. Java used to Still on 33,106
 * at 22.
 */
class BattleNetHarvestInterruptedMoveTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a harvest dest-arm still walks the clicked square")
    void aHarvestDestArmStillWalksTheClickedSquare() {
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

        Unit peasant = workerNear(world, 34, 105);
        assertNotNull(peasant, "Human 5 has no peasant near 34,105");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        boolean commandIssued = false;
        while (world.cycle() - BNE_INITIALIZATION_TICKS <= 5) {
            int fixture = (int) world.cycle() - BNE_INITIALIZATION_TICKS;
            if (fixture == 5) {
                assertTrue(commands.apply(GameCommand.move(
                                0, peasant.id(), 34, 103)),
                        "the harvest-interrupted click must be accepted");
                commandIssued = true;
            }
            mission.tick();
        }
        assertTrue(commandIssued,
                "the fixture-cycle-five Move command must be exercised");

        for (int i = 0; i < 80; i++) {
            mission.tick();
            if (peasant.tileX() == 34 && peasant.tileY() == 103
                    && peasant.order() == Unit.Order.STILL) {
                break;
            }
        }

        assertEquals(34, peasant.tileX(),
                "retail's peasant walks the click to 34,103, not "
                        + peasant.tileX() + "," + peasant.tileY());
        assertEquals(103, peasant.tileY(),
                "retail's peasant walks the click to 34,103, not "
                        + peasant.tileX() + "," + peasant.tileY());
    }

    private static Unit workerNear(World world, int x, int y) {
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
}
