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
 * A ship click onto an oil patch dest-arms onto the patch face.
 *
 * <p>Authenticated batch-2/03: Orc 5 destroyer 1513 at 112,120 GiveOrder 3
 * dest 112,117. Native is Move at 5, dest-arms N at 8, and is Still on
 * 112,118 at 40. Treating the patch as occupied ground the hull already
 * touched swallowed the click and left the ship Still on 112,120.
 */
class BattleNetOilPatchMoveRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an orc 5 destroyer dest-arms north onto the oil patch")
    void anOrc5DestroyerDestArmsNorthOntoTheOilPatch() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc/level05o",
                GameData.personIn(data.campaignMap("campaigns/orc/level05o")), 1);
        Assumptions.assumeTrue(mission != null, "Orc 5 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit ship = atTile(world, 112, 120);
        assertNotNull(ship, "Orc 5 has no ship on 112,120");
        boolean issued = false;
        Integer destArm = null;
        Integer stillAt = null;
        while (fixtureCycle(world) <= 45) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.move(
                                ship.player(), ship.id(), 112, 117)),
                        "GiveOrder 3 must accept the oil-patch click");
                issued = true;
            }
            mission.tick();
            if (issued && destArm == null
                    && (ship.tileX() != 112 || ship.tileY() != 120
                    || ship.offsetX() != 0 || ship.offsetY() != 0)) {
                destArm = fixtureCycle(world);
            }
            if (issued && stillAt == null
                    && ship.order() == Unit.Order.STILL
                    && destArm != null) {
                stillAt = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the oil-patch click must be issued");
        assertEquals(8, destArm,
                "retail dest-arms north at fixture 8, not " + destArm);
        assertEquals(40, stillAt,
                "retail is Still on 112,118 at fixture 40, not " + stillAt);
        assertEquals(112, ship.tileX(),
                "retail stands on 112,118, not "
                        + ship.tileX() + "," + ship.tileY());
        assertEquals(118, ship.tileY(),
                "retail stands on 112,118, not "
                        + ship.tileX() + "," + ship.tileY());
        assertEquals(Unit.Order.STILL, ship.order(),
                "retail is Still on the patch face, not " + ship.order());
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - 2;
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
