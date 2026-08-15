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
 * A worker Attack Ground click into forest walks the Move leftover.
 *
 * <p>Authenticated attack-ground-1/02: peon 1594 at 25,18 is Attack-Move
 * dest 28,18 at fixture 5, leftover-lands 26,17, dest-arms onto 27,17,
 * and is Still there at 43. Installing Attack Ground on the click walked
 * due east to 27,18 and never stood down.
 */
class BattleNetAttackGroundWorkerWalkRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an orc 1 forest attack-ground is still at 27,17 at 43")
    void anOrc1ForestAttackGroundIsStillAt2717At43() {
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
        Integer stillAt = null;
        while (fixtureCycle(world) <= 50) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.attackGround(
                                peon.player(), peon.id(), 30, 18)),
                        "GiveOrder 17 must accept the forest click");
                issued = true;
            }
            mission.tick();
            if (issued && stillAt == null
                    && peon.order() == Unit.Order.STILL
                    && peon.tileX() == 27 && peon.tileY() == 17
                    && peon.offsetX() == 0 && peon.offsetY() == 0) {
                stillAt = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the attack-ground click must be issued");
        assertEquals(43, stillAt,
                "retail leftover-lands 27,17 and Stills at fixture 43, not "
                        + stillAt);
    }

    @Test
    @DisplayName("an orc 1 forest attack-ground dest-arms 28,18 at fixture 5")
    void anOrc1ForestAttackGroundDestArms2818AtFixture5() {
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
        assertTrue(commands.apply(GameCommand.attackGround(
                        peon.player(), peon.id(), 30, 18)),
                "GiveOrder 17 must accept the forest click");
        mission.tick();
        assertEquals(28, peon.orderTargetX(),
                "retail stores the first tree 28,18, not the forest click");
        assertEquals(18, peon.orderTargetY(),
                "retail stores the first tree 28,18, not the forest click");
        assertTrue(peon.order() == Unit.Order.MOVE
                        || peon.order() == Unit.Order.ATTACK_MOVE,
                "retail starts the forest click as a walk, not "
                        + peon.order());
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
