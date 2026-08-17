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
 * A walking Human 1 footman takes the grunt's FUN_00418370 remainder.
 *
 * <p>Authenticated field walk: grunt 1591 dest-arms the chase at 223 and
 * lands opcode ten at dest-arm 281 on 26,22. Native 00418412 at seed
 * 64151463 remainder 3 is a seven-point blow (60 to 53). A second
 * seven-point blow at 306 leaves 46, and the walk settles on 25,28 at 393
 * still at 46. The queued Move promote used to spend a Still OP0 on cycle
 * 6, which stole that remainder and left 55 then 47.
 */
class BattleNetWalkFirstBlowAmountRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;
    private static final String MAP = "campaigns/human/level01h";

    @Test
    @DisplayName("a human 1 footman takes a seven-point first blow on dest-arm 281")
    void aHuman1FootmanTakesASevenPointFirstBlowOnDestArm281() {
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
        Unit walker = atTile(world, 21, 5);
        assertNotNull(walker, "Human 1 has no footman on 21,5");
        boolean issued = false;
        Integer firstHp = null;
        Integer firstX = null;
        Integer firstY = null;
        Integer firstAmount = null;
        Integer secondHp = null;
        Integer settledHp = null;
        while (fixtureCycle(world) <= 393) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.move(
                                walker.player(), walker.id(), 25, 28)),
                        "the field click to 25,28 must be accepted");
                issued = true;
            }
            int before = walker.hitPoints();
            mission.tick();
            int cycle = fixtureCycle(world);
            if (walker.hitPoints() < before) {
                if (firstHp == null) {
                    firstHp = cycle;
                    firstX = walker.tileX();
                    firstY = walker.tileY();
                    firstAmount = before - walker.hitPoints();
                } else if (secondHp == null) {
                    secondHp = cycle;
                }
            }
            if (cycle == 393) {
                settledHp = walker.hitPoints();
            }
        }
        assertTrue(issued, "the field click must be issued");
        assertEquals(281, firstHp,
                "retail's first blow lands at dest-arm 281, not " + firstHp);
        assertEquals(26, firstX,
                "retail first hurts the walker on 26,22, not "
                        + firstX + "," + firstY);
        assertEquals(22, firstY,
                "retail first hurts the walker on 26,22, not "
                        + firstX + "," + firstY);
        assertEquals(7, firstAmount,
                "retail's first grunt blow is 7 (60 to 53), not " + firstAmount);
        assertEquals(306, secondHp,
                "retail's second blow lands at 306, not " + secondHp);
        assertEquals(46, settledHp,
                "retail settles the walk at 46 hit points, not " + settledHp);
        assertEquals(25, walker.tileX(),
                "retail settles the walk on 25,28, not "
                        + walker.tileX() + "," + walker.tileY());
        assertEquals(28, walker.tileY(),
                "retail settles the walk on 25,28, not "
                        + walker.tileX() + "," + walker.tileY());
        assertEquals(Unit.Order.STILL, walker.order(),
                "retail is Still on 25,28 at cycle 393, not " + walker.order());
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
}
