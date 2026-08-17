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
 * A settled Human 1 footman first takes the grunt's leftover-land blow
 * at dest-arm plus leftover 16 plus ten.
 *
 * <p>Authenticated field walk: 1598 is Attack 16 on 25,28 from 401 with
 * leftover heading south and pixels 0,0. Native dest-arm 401 leftover
 * lands leftover 0 at 417. The walker first takes 6 at 427 (46 to 40)
 * still on dest. Java leftover-settled at leftover 8 on 413 and took 4
 * at 423.
 */
class BattleNetInPlaceFirstTakeRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;
    private static final String MAP = "campaigns/human/level01h";

    @Test
    @DisplayName("a settled human 1 footman first takes six at cycle 427")
    void aSettledHuman1FootmanFirstTakesSixAtCycle427() {
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
        Unit attacker = atTile(world, 29, 24);
        assertNotNull(walker, "Human 1 has no footman on 21,5");
        assertNotNull(attacker, "Human 1 has no grunt on 29,24");
        int opened = walker.hitPoints();
        boolean issued = false;
        Integer firstTake = null;
        Integer firstAmount = null;
        Integer firstX = null;
        Integer firstY = null;
        Integer leftoverLand = null;
        while (fixtureCycle(world) <= 430) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.move(
                                walker.player(), walker.id(), 25, 28)),
                        "the field click to 25,28 must be accepted");
                issued = true;
            }
            int before = walker.hitPoints();
            mission.tick();
            int cycle = fixtureCycle(world);
            if (cycle >= 401 && leftoverLand == null
                    && walker.order() == Unit.Order.ATTACK) {
                Unit grunt = walker.target();
                if (grunt != null && grunt.tileX() == 25 && grunt.tileY() == 27
                        && grunt.offsetX() == 0 && grunt.offsetY() == 0
                        && !grunt.chasing()) {
                    leftoverLand = cycle;
                }
            }
            if (walker.hitPoints() < before && cycle > 393 && firstTake == null) {
                firstTake = cycle;
                firstAmount = before - walker.hitPoints();
                firstX = walker.tileX();
                firstY = walker.tileY();
            }
        }
        assertTrue(issued, "the field click must be issued");
        assertEquals(417, leftoverLand,
                "retail leftover-lands dest-arm 401 at leftover 0 on 417, not "
                        + leftoverLand);
        assertEquals(427, firstTake,
                "retail first hurts the settled walker at 427, not " + firstTake);
        assertEquals(6, firstAmount,
                "retail's first in-place take is 6 (46 to 40), not " + firstAmount);
        assertEquals(25, firstX,
                "retail first hurts the settled walker on 25,28, not "
                        + firstX + "," + firstY);
        assertEquals(28, firstY,
                "retail first hurts the settled walker on 25,28, not "
                        + firstX + "," + firstY);
        assertEquals(opened - 14 - 6, walker.hitPoints(),
                "retail is at 40 after the walk blows and the 427 take, not "
                        + walker.hitPoints());
    }

    @Test
    @DisplayName("a moving human 8 quarry keeps the refill route at cycle 133")
    void aMovingHuman8QuarryDoesNotReuseAnOlderExhaustedRefill() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 8 is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit attacker = null;
        for (Unit unit : mission.world().unitsSnapshot()) {
            if (unit.player() == 4 && unit.tileX() == 72 && unit.tileY() == 60
                    && unit.type() != null
                    && "unit-attack-peasant".equals(unit.type().ident())) {
                attacker = unit;
                break;
            }
        }
        assertNotNull(attacker, "Human 8 has no attack peasant on 72,60");
        while (fixtureCycle(mission.world()) < 133) {
            mission.tick();
        }
        // Native 1538 is still action-state 3 with route heading 6 at 133.
        // The old broad refill marker instead opened Attack at this visit.
        assertTrue(attacker.isMoving(),
                "retail still owns the final movement residual at cycle 133");
        assertEquals(1, attacker.pathLength(),
                "retail still holds the occupied-quarry heading at cycle 133");
        assertEquals(-7, attacker.offsetX(),
                "retail has seven horizontal pixels left at cycle 133");
        assertEquals(0, attacker.offsetY(),
                "the final Human 8 approach is horizontal");
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
