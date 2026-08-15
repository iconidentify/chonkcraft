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
 * A Human 13 north click still has two live shots when the walker stands.
 *
 * <p>Authenticated batch-1/24 axethrower 1449 is Still on 98,55 at fixture
 * 40. Native's projectile pool still holds the second catapult rock
 * (remaining -3) and its type-21 impact. Reading the list after extra
 * settle ticks dropped that pair and left a later axe.
 */
class BattleNetMoveMissileCountRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a human 13 north click still has two live shots at fixture 40")
    void aHuman13NorthClickStillHasTwoLiveShotsAtFixture40() {
        assertMissileCountAtStill("campaigns/human/level13h", 98, 57, 98, 55, 40, 2);
    }

    @Test
    @DisplayName("a human 13 north-east click still has two live shots at fixture 40")
    void aHuman13NorthEastClickStillHasTwoLiveShotsAtFixture40() {
        assertMissileCountAtStill("campaigns/human/level13h", 98, 57, 100, 55, 40, 2);
    }

    private static void assertMissileCountAtStill(String map, int x, int y,
            int destX, int destY, int stillFixture, int shots) {
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
        Unit mover = nearest(world, x, y);
        assertNotNull(mover, map + " has no mover near " + x + "," + y);
        boolean issued = false;
        Integer stillAt = null;
        int missilesAtStill = -1;
        while (fixtureCycle(world) <= stillFixture + 5) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.move(
                                mover.player(), mover.id(), destX, destY)),
                        "the move click must be accepted");
                issued = true;
            }
            mission.tick();
            if (issued && stillAt == null
                    && mover.tileX() == destX && mover.tileY() == destY
                    && mover.offsetX() == 0 && mover.offsetY() == 0
                    && mover.order() == Unit.Order.STILL) {
                stillAt = fixtureCycle(world);
                missilesAtStill = world.missiles().size();
            }
        }
        assertTrue(issued, "the move click must be issued");
        assertTrue(stillAt != null && stillAt <= stillFixture,
                "retail stands still on the click, not fixture " + stillAt);
        assertEquals(shots, missilesAtStill,
                "retail still has the landed rock and its impact at that Still visit, not "
                        + missilesAtStill);
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit nearest(World world, int x, int y) {
        Unit best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Unit unit : world.unitsSnapshot()) {
            if (!unit.isAlive() || !unit.isOnMap() || unit.type() == null
                    || unit.type().building()) {
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
