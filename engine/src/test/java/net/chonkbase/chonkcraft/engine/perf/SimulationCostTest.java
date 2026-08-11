package net.chonkbase.chonkcraft.engine.perf;

import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * No mission floods the map with route searches.
 *
 * <p>{@code levelx12h} used to simulate about twelve times slower than the
 * other missions and the reason was recorded as unexplained. It was never the
 * size of the map: {@code level12h} is 128x128 with 187 units and ran at 244
 * nanoseconds per unit-cycle while {@code levelx12h}, 96x96 with 257 units,
 * cost 3018 -- twelve times the cost per unit, not twelve times the units.
 *
 * <p>The cause was two guard towers walled into rock and an orc army camped
 * beside them, each soldier asking once a cycle whether it could walk to a
 * tower and A* flooding its whole node budget to fail to answer. See
 * {@link net.chonkbase.chonkcraft.engine.pathfinder.SealedGoalTest}, which pins
 * the behaviour; this pins the cost.
 *
 * <p>The measurement is squares expanded, not time. The first version of this
 * test compared wall time against a second mission run moments earlier, on the
 * theory that a ratio cancels out the machine -- and it did not: run on its
 * own the ratio was 2.3 and the test passed, run after five hundred other
 * tests had warmed the JIT it was 6.9 and the test failed, because the cheaper
 * mission gains far more from a warm compiler than the one bottlenecked on
 * A*. Squares expanded is the work itself. It varies by a few per cent between
 * runs because the simulation is not perfectly reproducible, and by nothing at
 * all between machines.
 */
class SimulationCostTest {

    /** Long enough for the armies to be in contact, short enough to be a test. */
    private static final int CYCLES = 1200;

    /**
     * Squares of route search a mission may expand per cycle.
     *
     * <p>Re-calibrated once, and the reason is worth stating because moving a
     * threshold to make a red test green is otherwise indefensible. It was
     * eight hundred, chosen between {@code levelx12h}'s 473 per cycle with the
     * sealed-goal check in place and 1700 without it. Neither of those numbers
     * survives: as this implementation has come to keep its units under their orders as
     * long as upstream keeps its, and to let a unit that stops look around at
     * once as upstream's does, that mission has gone to 1170 -- and taking the
     * sealed-goal check out now reads <em>798</em>, below the threshold it was
     * set to sit above. The measure had stopped separating what it was chosen
     * to separate, because what a unit searches for depends on what it decides
     * to do, and both sides of that comparison had moved.
     *
     * <p>What has not changed is the thing worth failing on. The twelve-fold
     * slowdown this was written for was a per-unit, per-cycle search for a
     * route that could not exist; measured now, the three missions here run in
     * 1.5 seconds against 1.36 at the older behaviour, eleven per cent, and
     * one search's node budget is 4096, so a mission at this limit spends
     * under half an exhausted search per cycle. Two thousand keeps the alarm
     * on the regime and off the drift.
     */
    private static final long MAX_NODES_PER_CYCLE = 2000;

    private static long nodesPerCycle(GameData data, String path) {
        Mission mission = data.loadMission(path);
        if (mission == null) {
            return -1;
        }
        // Settle first. The opening cycles place units and hand out orders,
        // which is not the steady state the flooding showed up in.
        for (int i = 0; i < 120; i++) {
            mission.tick();
        }
        PathFinder.nodesExpanded = 0;
        for (int i = 0; i < CYCLES; i++) {
            mission.tick();
        }
        return PathFinder.nodesExpanded / CYCLES;
    }

    @Test
    @DisplayName("no campaign mission floods the map with route searches every cycle")
    void noMissionFloodsTheMapWithRouteSearches() {
        GameData data = SimulationProfile.load();
        Assumptions.assumeTrue(data != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");

        // levelx12h is the one this was found on; the other two are a control,
        // so a change that made every map flood could not hide behind a
        // threshold tuned to the worst of them.
        for (String path : new String[] {"campaigns/human-exp/levelx12h",
                "campaigns/human-exp/levelx11h", "campaigns/human/level12h"}) {
            long perCycle = nodesPerCycle(data, path);
            Assumptions.assumeTrue(perCycle >= 0, path + " will not load");
            System.out.printf("%-34s %6d squares expanded per cycle%n", path, perCycle);
            assertTrue(perCycle < MAX_NODES_PER_CYCLE, String.format(
                    "%s expands %d squares of route search per cycle, over the %d this allows."
                            + " That is the shape of the flooding this was fixed for: something"
                            + " is asking for a route it cannot have, once a cycle, per unit.",
                    path, perCycle, MAX_NODES_PER_CYCLE));
        }
    }
}
