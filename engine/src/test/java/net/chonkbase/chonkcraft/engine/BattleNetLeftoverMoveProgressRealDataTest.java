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
 * A player Move click does not freeze dest-arm leftover pixels.
 *
 * <p>Authenticated commanded fixtures keep the walk-bearing action and let
 * leftover dest-arm continue. Human 5 peasant 1512 first changes pixels at
 * fixture 6 while still Harvest, for both 34,103 and 36,103. A Still grunt
 * or balloon still pays the Still queue.
 */
class BattleNetLeftoverMoveProgressRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a harvest dest-arm leftover keeps walking the cycle after a click")
    void aHarvestDestArmLeftoverKeepsWalkingTheCycleAfterAClick() {
        Loaded loaded = load("campaigns/human/level05h");
        World world = loaded.mission.world();
        Unit peasant = workerNear(world, 34, 105);
        assertNotNull(peasant, "Human 5 has no peasant near 34,105");

        Progress progress = issueMoveAndWatch(loaded.mission, loaded.commands, peasant, 34, 103);
        assertEquals(6, progress.firstProgressFixture,
                "retail's harvest leftover first walks at fixture 6, not "
                        + progress.firstProgressFixture);
        assertEquals(Unit.Order.HARVEST, progress.orderAtProgress,
                "retail keeps Harvest through the leftover dest-arm, not "
                        + progress.orderAtProgress);
    }

    @Test
    @DisplayName("a second harvest leftover click still walks the next cycle")
    void aSecondHarvestLeftoverClickStillWalksTheNextCycle() {
        Loaded loaded = load("campaigns/human/level05h");
        World world = loaded.mission.world();
        Unit peasant = workerNear(world, 34, 105);
        assertNotNull(peasant, "Human 5 has no peasant near 34,105");

        Progress progress = issueMoveAndWatch(loaded.mission, loaded.commands, peasant, 36, 103);
        assertEquals(6, progress.firstProgressFixture,
                "retail's harvest leftover first walks at fixture 6, not "
                        + progress.firstProgressFixture);
        assertEquals(Unit.Order.HARVEST, progress.orderAtProgress,
                "retail keeps Harvest through the leftover dest-arm, not "
                        + progress.orderAtProgress);
    }

    @Test
    @DisplayName("a still balloon still waits before its first dest-arm")
    void aStillBalloonStillWaitsBeforeItsFirstDestArm() {
        Loaded loaded = load("campaigns/orc/level06o");
        World world = loaded.mission.world();
        Unit balloon = flyerNear(world, 36, 42);
        assertNotNull(balloon, "Orc 6 has no balloon near 36,42");

        Progress progress = issueMoveAndWatch(loaded.mission, loaded.commands, balloon, 36, 39);
        assertTrue(progress.firstProgressFixture >= 8,
                "a still balloon still pays the Still queue before dest-arm, not fixture "
                        + progress.firstProgressFixture);
        assertEquals(Unit.Order.MOVE, progress.orderAtProgress,
                "retail dest-arms the still balloon on Move, not "
                        + progress.orderAtProgress);
    }

    @Test
    @DisplayName("a still grunt still waits before its first dest-arm")
    void aStillGruntStillWaitsBeforeItsFirstDestArm() {
        Loaded loaded = load("campaigns/human/level04h");
        World world = loaded.mission.world();
        Unit grunt = fighterNear(world, 87, 94);
        assertNotNull(grunt, "Human 4 has no fighter near 87,94");

        Progress progress = issueMoveAndWatch(loaded.mission, loaded.commands, grunt, 89, 92);
        assertTrue(progress.firstProgressFixture >= 10,
                "a still grunt still pays the Still queue before dest-arm, not fixture "
                        + progress.firstProgressFixture);
        assertEquals(Unit.Order.MOVE, progress.orderAtProgress,
                "retail dest-arms the still grunt on Move, not "
                        + progress.orderAtProgress);
    }

    private static Progress issueMoveAndWatch(Mission mission,
            CommandApplier commands, Unit unit, int destX, int destY) {
        World world = mission.world();
        int baselineX = unit.pixelX();
        int baselineY = unit.pixelY();
        int baselineTileX = unit.tileX();
        int baselineTileY = unit.tileY();
        boolean issued = false;
        Integer firstProgress = null;
        Unit.Order orderAtProgress = null;
        while (fixtureCycle(world) <= 80) {
            if (fixtureCycle(world) == 5 && !issued) {
                baselineX = unit.pixelX();
                baselineY = unit.pixelY();
                baselineTileX = unit.tileX();
                baselineTileY = unit.tileY();
                assertTrue(commands.apply(GameCommand.move(
                                unit.player(), unit.id(), destX, destY)),
                        "the leftover click must be accepted");
                issued = true;
            }
            mission.tick();
            if (issued && firstProgress == null
                    && (unit.pixelX() != baselineX || unit.pixelY() != baselineY
                    || unit.tileX() != baselineTileX
                    || unit.tileY() != baselineTileY)) {
                firstProgress = fixtureCycle(world);
                orderAtProgress = unit.order();
                break;
            }
        }
        assertTrue(issued, "the fixture-cycle-five Move must be issued");
        assertNotNull(firstProgress, "the click must produce a walk pixel");
        return new Progress(firstProgress, orderAtProgress);
    }

    private static Loaded load(String ident) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(ident, 0);
        Assumptions.assumeTrue(mission != null, ident + " is not in the pack");
        CommandApplier commands = new CommandApplier(
                mission.world(), new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(commands);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        return new Loaded(mission, commands);
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit workerNear(World world, int x, int y) {
        return nearest(world, x, y, ident ->
                ident.contains("peasant") || ident.contains("peon"));
    }

    private static Unit flyerNear(World world, int x, int y) {
        return nearest(world, x, y, ident ->
                ident.contains("zeppelin") || ident.contains("balloon")
                        || ident.contains("flying"));
    }

    private static Unit fighterNear(World world, int x, int y) {
        return nearest(world, x, y, ident ->
                ident.contains("footman") || ident.contains("grunt"));
    }

    private static Unit nearest(World world, int x, int y,
            java.util.function.Predicate<String> wanted) {
        Unit best = null;
        int bestDist = Integer.MAX_VALUE;
        for (Unit unit : world.unitsSnapshot()) {
            if (!unit.isAlive() || !unit.isOnMap() || unit.type() == null) {
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

    private record Progress(int firstProgressFixture, Unit.Order orderAtProgress) {
    }

    private record Loaded(Mission mission, CommandApplier commands) {
    }
}
