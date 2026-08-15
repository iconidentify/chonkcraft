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
 * A player Move leftover that lands one tile short takes the last step.
 *
 * <p>Authenticated batch-1/26 Orc 8 ogre 1579 dest-arms 31,8 then stands
 * on 32,7 at fixture 36. Java paid an empty-route wait of ten on 31,8
 * and dest-armed the last tile only at 47. A gold harvest still waits
 * before it enters the mine.
 */
class BattleNetMoveLastStepNoEmptyWaitRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an orc 8 north click is on the dest tile by fixture 36")
    void anOrc8NorthClickIsOnTheDestTileByFixture36() {
        assertOnDestBy("campaigns/orc/level08o", 32, 10, 32, 7, 36);
    }

    @Test
    @DisplayName("an orc 9 north click is on the dest tile by fixture 48")
    void anOrc9NorthClickIsOnTheDestTileByFixture48() {
        // Native batch-1/28 first_progress 12, settled 60. Same one-short
        // leftover then last step; dest 2,85 is three tiles like 32,7.
        assertOnDestBy("campaigns/orc/level09o", 2, 88, 2, 85, 48);
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

    private static void assertOnDestBy(String map, int x, int y,
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
        Integer onDest = null;
        while (fixtureCycle(world) <= byFixture + 20) {
            if (fixtureCycle(world) == 4 && !issued) {
                assertTrue(commands.apply(GameCommand.move(
                                mover.player(), mover.id(), destX, destY)),
                        "the move click must be accepted");
                issued = true;
            }
            mission.tick();
            if (issued && onDest == null
                    && mover.tileX() == destX && mover.tileY() == destY) {
                onDest = fixtureCycle(world);
            }
        }
        assertTrue(issued, "the move click must be issued");
        assertTrue(onDest != null && onDest <= byFixture,
                "retail stands on the click after leftover dest-arm, not fixture "
                        + onDest);
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
