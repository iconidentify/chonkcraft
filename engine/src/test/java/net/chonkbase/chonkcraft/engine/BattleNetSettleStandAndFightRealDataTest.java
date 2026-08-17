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
 * A settled Human 1 footman Attacks in place when the grunt steps adjacent.
 *
 * <p>Authenticated field walk: 1598 is Still on 25,28 at 393 with 46 hp.
 * Native's first post-settle Still OP0 at 396 leaves it Still (grunt 1591
 * still at 25,26, dist 2). Attack (order 16) starts on 25,28 at 401 when
 * that grunt dest-arms 25,27. The settled footman first lands on the grunt at
 * 414 (60 to 55) and never leaves dest. Hits taken on the walk used to
 * leave an offer, so Java chased at 396 and was already on 25,27 taking
 * 7 at 412.
 */
class BattleNetSettleStandAndFightRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;
    private static final String MAP = "campaigns/human/level01h";

    @Test
    @DisplayName("a settled human 1 footman attacks in place when the grunt steps adjacent")
    void aSettledHuman1FootmanAttacksInPlaceWhenTheGruntStepsAdjacent() {
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
        Unit grunt = atTile(world, 29, 24);
        assertNotNull(walker, "Human 1 has no footman on 21,5");
        assertNotNull(grunt, "Human 1 has no grunt on 29,24");
        boolean issued = false;
        Unit.Order at393 = null;
        Unit.Order at396 = null;
        Integer opened = null;
        Integer openedX = null;
        Integer openedY = null;
        Integer gruntHp = null;
        Integer hpDropX = null;
        Integer hpDropY = null;
        boolean sawWalkDamage = false;
        boolean leftDest = false;
        while (fixtureCycle(world) <= 430) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.move(
                                walker.player(), walker.id(), 25, 28)),
                        "the field click to 25,28 must be accepted");
                issued = true;
            }
            int before = walker.hitPoints();
            int gruntBefore = grunt.hitPoints();
            mission.tick();
            int cycle = fixtureCycle(world);
            if (cycle >= 393 && (walker.tileX() != 25 || walker.tileY() != 28)) {
                leftDest = true;
            }
            if (walker.hitPoints() < before) {
                if (cycle < 393) {
                    sawWalkDamage = true;
                } else if (hpDropX == null) {
                    hpDropX = walker.tileX();
                    hpDropY = walker.tileY();
                }
            }
            if (cycle >= 393 && gruntHp == null
                    && grunt.hitPoints() < gruntBefore) {
                gruntHp = cycle;
            }
            if (cycle == 393) {
                at393 = walker.order();
            }
            if (cycle == 396) {
                at396 = walker.order();
            }
            if (opened == null && cycle >= 393
                    && walker.order() == Unit.Order.ATTACK) {
                opened = cycle;
                openedX = walker.tileX();
                openedY = walker.tileY();
            }
        }
        assertTrue(issued, "the field click must be issued");
        assertTrue(sawWalkDamage,
                "retail's walk already took the 281 and 306 blows before settle");
        assertEquals(Unit.Order.STILL, at393,
                "retail is Still on dest at cycle 393, not " + at393);
        assertEquals(Unit.Order.STILL, at396,
                "retail's first post-settle Still OP0 stays Still, not " + at396);
        assertEquals(401, opened,
                "retail opens Attack when the grunt dest-arms adjacent, not "
                        + opened);
        assertEquals(25, openedX,
                "retail Attacks in place on 25,28, not "
                        + openedX + "," + openedY);
        assertEquals(28, openedY,
                "retail Attacks in place on 25,28, not "
                        + openedX + "," + openedY);
        assertTrue(!leftDest,
                "retail never leaves 25,28 after settle; a leftover offer used to chase onto 25,27");
        assertEquals(414, gruntHp,
                "retail's settled footman first lands on the grunt at 414, not "
                        + gruntHp);
        assertEquals(25, hpDropX,
                "retail first hurts the settled walker on 25,28, not "
                        + hpDropX + "," + hpDropY);
        assertEquals(28, hpDropY,
                "retail first hurts the settled walker on 25,28, not "
                        + hpDropX + "," + hpDropY);
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
