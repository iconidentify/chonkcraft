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
 * An empty send-home leftover-lands on the hall ring, then dest-arms in.
 *
 * <p>Authenticated return-goods-1/00: peon 1594 leftover-lands 26,21 at
 * fixture 56, stands action 25 through 58, dest-arms onto 25,22, and is
 * inside at 75. Walking the connected origin leftover-landed 22,21 at 53,
 * paid PF_WAIT 10, and entered at 65.
 */
class BattleNetReturnGoodsLeftoverLandRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an orc 1 send-home leftover-lands 26,21 then is inside at 75")
    void anOrc1SendHomeLeftoverLands2621ThenIsInsideAt75() {
        assertLandThenEnter("campaigns/orc/level01o", 25, 18, 26, 21, 56, 75);
    }

    @Test
    @DisplayName("an orc 2 send-home leftover-lands 45,59 then is inside at 107")
    void anOrc2SendHomeLeftoverLands4559ThenIsInsideAt107() {
        assertLandThenEnter("campaigns/orc/level02o", 50, 58, 45, 59, 88, 107);
    }

    private static void assertLandThenEnter(String map, int x, int y,
            int landX, int landY, int landBy, int enterBy) {
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
        Unit peon = atTile(world, x, y);
        assertNotNull(peon, map + " has no peon on " + x + "," + y);
        boolean issued = false;
        Integer landAt = null;
        Integer enterAt = null;
        while (fixtureCycle(world) <= enterBy + 8) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.returnGoods(
                                peon.player(), peon.id())),
                        "GiveOrder 24 must accept the empty send-home");
                issued = true;
            }
            mission.tick();
            if (issued && landAt == null
                    && peon.tileX() == landX && peon.tileY() == landY
                    && peon.offsetX() == 0 && peon.offsetY() == 0
                    && !peon.isMoving()) {
                landAt = fixtureCycle(world);
            }
            if (issued && enterAt == null && !peon.isOnMap()) {
                enterAt = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the send-home must be issued");
        assertEquals(landBy, landAt,
                "retail leftover-lands " + landX + "," + landY + " at fixture "
                        + landBy + ", not " + landAt);
        assertEquals(enterBy, enterAt,
                "retail is inside the hall at fixture " + enterBy + ", not "
                        + enterAt);
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
