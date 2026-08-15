package net.chonkbase.chonkcraft.engine;

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
 * A leftover scout Patrol click keeps dest-arming onto the even neighbour.
 *
 * <p>Authenticated batch-2/16 Orc 6 balloon 1553 dest-arms west on Patrol
 * at the click and stands Still on 34,42 at fixture 26 after an odd
 * 33,42 point. dest-arm-1/00 Human 12 zeppelin 1559 dest-arms west and
 * stands Still on 90,14 at fixture 25 after 89,14. The odd dest overlaps
 * a 2x2 hull, so the occupied-neighbour test used to swallow the click
 * and leave Patrol walking to 18,51 / 83,10.
 */
class BattleNetPatrolLeftoverMoveRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an orc 6 leftover balloon stands still on 34,42 by fixture 26")
    void anOrc6LeftoverBalloonStandsStillOnTheEvenNeighbourByFixture26() {
        assertStillOnEvenBy("campaigns/orc/level06o", 36, 42, 33, 42, 34, 42, 26);
    }

    @Test
    @DisplayName("a human 12 leftover zeppelin stands still on 90,14 by fixture 25")
    void aHuman12LeftoverZeppelinStandsStillOnTheEvenNeighbourByFixture25() {
        assertStillOnEvenBy("campaigns/human/level12h", 92, 14, 89, 14, 90, 14, 25);
    }

    @Test
    @DisplayName("a gold click still vanishes into the mine after its wait")
    void aGoldClickStillVanishesIntoTheMineAfterItsWait() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc/level01o", 0);
        Assumptions.assumeTrue(mission != null, "Orc 1 is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit peon = nearest(world, 25, 18, ident -> ident.contains("peon"));
        assertNotNull(peon, "Orc 1 has no peon near 25,18");
        boolean issued = false;
        Integer offMap = null;
        while (fixtureCycle(world) <= 80) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.harvest(
                                peon.player(), peon.id(), 26, 13)),
                        "the gold click must be accepted");
                issued = true;
            }
            mission.tick();
            if (issued && offMap == null && !peon.isOnMap()) {
                offMap = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the gold click must be issued");
        assertTrue(offMap != null && offMap >= 50,
                "a gold harvest still pays the mine wait, not fixture " + offMap);
    }

    private static void assertStillOnEvenBy(String map, int x, int y,
            int destX, int destY, int evenX, int evenY, int byFixture) {
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
        Unit flyer = nearest(world, x, y, ident -> ident.contains("balloon")
                || ident.contains("zeppelin"));
        assertNotNull(flyer, map + " has no flyer near " + x + "," + y);
        boolean issued = false;
        Integer stillOnEven = null;
        while (fixtureCycle(world) <= byFixture + 20) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.move(
                                flyer.player(), flyer.id(), destX, destY)),
                        "the leftover click must be accepted");
                issued = true;
            }
            mission.tick();
            if (issued && stillOnEven == null
                    && flyer.tileX() == evenX && flyer.tileY() == evenY
                    && flyer.offsetX() == 0 && flyer.offsetY() == 0
                    && flyer.order() == Unit.Order.STILL) {
                stillOnEven = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the leftover click must be issued");
        assertTrue(stillOnEven != null && stillOnEven <= byFixture,
                "retail dest-arms leftover Patrol onto the even neighbour, not fixture "
                        + stillOnEven);
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit nearest(World world, int x, int y,
            java.util.function.Predicate<String> wanted) {
        Unit best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Unit unit : world.unitsSnapshot()) {
            if (!unit.isAlive() || !unit.isOnMap() || unit.type() == null
                    || unit.type().building()) {
                continue;
            }
            if (!wanted.test(unit.type().ident())) {
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
