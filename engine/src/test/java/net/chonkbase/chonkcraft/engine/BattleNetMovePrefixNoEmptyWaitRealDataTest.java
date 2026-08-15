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
 * A player Move whose prefix runs out still walks the remaining heading.
 *
 * <p>Authenticated batch-1/31 Orc 10 grunt 1480 leftover-lands on 73,92
 * two tiles short of 75,91. Native dest-arms through 74,91 and is Still
 * on 75,91 at fixture 73. batch-1/29 Orc 9 footman 1485 is the same
 * two-short leftover onto 4,84; Java's Still program is already on the
 * shared 4985 body at the click so the first tile is 12 not native 8,
 * and Still lands at 76. A gold harvest still waits before it enters
 * the mine.
 */
class BattleNetMovePrefixNoEmptyWaitRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an orc 9 north-east click dest-arms the two-short leftover")
    void anOrc9NorthEastClickDestArmsTheTwoShortLeftover() {
        assertStillOnDestBy("campaigns/orc/level09o", 1, 87, 4, 84, 76);
    }

    @Test
    @DisplayName("an orc 10 north-east click is still on the dest by fixture 73")
    void anOrc10NorthEastClickIsStillOnTheDestByFixture73() {
        assertStillOnDestBy("campaigns/orc/level10o", 72, 94, 75, 91, 73);
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

    private static void assertStillOnDestBy(String map, int x, int y,
            int destX, int destY, int byFixture) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map, 0);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        World world = mission.world();
        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit mover = nearest(world, x, y, ident -> true);
        assertNotNull(mover, map + " has no mover near " + x + "," + y);
        boolean issued = false;
        Integer stillOnDest = null;
        while (fixtureCycle(world) <= byFixture + 20) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.move(
                                mover.player(), mover.id(), destX, destY)),
                        "the move click must be accepted");
                issued = true;
            }
            mission.tick();
            if (issued && stillOnDest == null
                    && mover.tileX() == destX && mover.tileY() == destY
                    && mover.offsetX() == 0 && mover.offsetY() == 0
                    && mover.order() == Unit.Order.STILL) {
                stillOnDest = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the move click must be issued");
        assertTrue(stillOnDest != null && stillOnDest <= byFixture,
                "retail stands still on the click after leftover dest-arm, not fixture "
                        + stillOnDest);
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
