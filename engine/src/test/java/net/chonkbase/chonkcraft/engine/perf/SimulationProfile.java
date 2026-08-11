package net.chonkbase.chonkcraft.engine.perf;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.campaign.Mission;

/**
 * A stack-sampling profiler for a headless mission run.
 *
 * <p>Exists because the twelve-fold slowdown on one campaign map could not be
 * explained by reading the code. Wall time per cycle says a map is slow; it
 * does not say which line is slow, and the four plausible causes -- a
 * pathfinding search running to its node cap, a unit stuck re-planning, an
 * O(n^2) sweep, or trigger evaluation -- are indistinguishable from the
 * outside. This samples the simulation thread's stack while it runs, so the
 * answer is a measurement rather than an argument.
 *
 * <p>Sampling rather than instrumenting on purpose. Instrumenting the tick
 * loop would mean editing {@code World}, which this agent does not own, and
 * would change the thing being measured; a sampler outside the loop sees the
 * real cost including allocation and garbage collection, which turned out to
 * be the whole story here.
 */
public final class SimulationProfile {

    private SimulationProfile() {
    }

    /** How often the sampler grabs the simulation thread's stack. */
    private static final long SAMPLE_INTERVAL_MILLIS = 2;

    /** One mission's cost, as measured. */
    public record Run(String path, int cycles, int mapWidth, int mapHeight, int units,
            long wallMillis, long allocatedBytes, Map<String, Integer> samplesByFrame,
            int totalSamples) {

        /** Milliseconds of simulation per thousand cycles, the comparable figure. */
        public double millisPerThousandCycles() {
            return wallMillis * 1000.0 / cycles;
        }

        /**
         * The same figure divided by the units on the map.
         *
         * <p>The one number that separates "this map is bigger" from "this map
         * is doing something wrong". A map with twice the units should cost
         * about twice as much per cycle; if the per-unit cost is also higher,
         * the extra is a bug.
         */
        public double nanosPerUnitCycle() {
            return units == 0 ? 0 : wallMillis * 1_000_000.0 / cycles / units;
        }
    }

    /** Loads the game, or returns null when no retail asset source is configured. */
    public static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        if (assets == null) {
            return null;
        }
        return new GameData(assets);
    }

    /**
     * Runs one mission for a fixed number of cycles, sampling as it goes.
     *
     * <p>The mission is loaded before the clock starts: loading reads the PUD
     * and constructs its native declaration, a one-off cost not included in
     * measured.
     */
    public static Run profile(GameData data, String path, int cycles) {
        Mission mission = data.loadMission(path);
        if (mission == null) {
            return null;
        }
        // A short warm-up so the JIT has compiled the tick loop before the
        // clock starts. Without it the first mission measured looks slow and
        // every later one looks fast, which is an artefact and not a finding.
        for (int i = 0; i < 60; i++) {
            mission.tick();
        }

        Map<String, Integer> frames = new HashMap<>();
        int[] samples = {0};
        Thread simulation = Thread.currentThread();
        boolean[] running = {true};
        Thread sampler = new Thread(() -> {
            while (running[0]) {
                StackTraceElement[] stack = simulation.getStackTrace();
                if (stack.length > 0) {
                    samples[0]++;
                    // Every frame in the stack is counted, so a method is
                    // credited with everything beneath it. Self time alone
                    // would attribute the whole run to Arrays.fill and say
                    // nothing about who called it.
                    java.util.Set<String> seen = new java.util.HashSet<>();
                    for (StackTraceElement element : stack) {
                        String name = element.getClassName() + "." + element.getMethodName();
                        if (seen.add(name)) {
                            frames.merge(name, 1, Integer::sum);
                        }
                    }
                }
                try {
                    Thread.sleep(SAMPLE_INTERVAL_MILLIS);
                } catch (InterruptedException stop) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        });
        sampler.setDaemon(true);

        long allocatedBefore = allocatedBytes();
        long start = System.nanoTime();
        sampler.start();
        for (int i = 0; i < cycles; i++) {
            mission.tick();
        }
        long elapsed = System.nanoTime() - start;
        running[0] = false;
        try {
            sampler.join(1000);
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        }
        long allocated = allocatedBytes() - allocatedBefore;

        return new Run(path, cycles, mission.world().map().width(),
                mission.world().map().height(), mission.world().units().size(),
                elapsed / 1_000_000, allocated, frames, samples[0]);
    }

    /**
     * Bytes this thread has allocated, where the JVM will say.
     *
     * <p>Allocation rate is the measurement that separates "doing more work"
     * from "making more garbage", and the two look identical in wall time.
     */
    private static long allocatedBytes() {
        ThreadMXBean bean = ManagementFactory.getThreadMXBean();
        if (bean instanceof com.sun.management.ThreadMXBean sun) {
            return sun.getCurrentThreadAllocatedBytes();
        }
        return 0;
    }

    /** Prints a run's hottest frames, deepest first by sample count. */
    public static void report(Run run, int topFrames) {
        System.out.printf("%-40s %3dx%-3d units=%-4d cycles=%d  %6d ms  %8.1f ms/1000cy"
                        + "  %7.1f ns/unit-cycle  %6d MB allocated%n",
                run.path(), run.mapWidth(), run.mapHeight(), run.units(), run.cycles(),
                run.wallMillis(), run.millisPerThousandCycles(), run.nanosPerUnitCycle(),
                run.allocatedBytes() / (1024 * 1024));
        List<Map.Entry<String, Integer>> hot = new ArrayList<>(run.samplesByFrame().entrySet());
        hot.sort(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue)
                .reversed());
        int shown = 0;
        for (Map.Entry<String, Integer> entry : hot) {
            String name = entry.getKey();
            if (!name.startsWith("net.chonkbase") && !name.startsWith("java.util.Arrays")
                    && !name.startsWith("java.util.PriorityQueue")) {
                continue;
            }
            System.out.printf("      %5.1f%%  %s%n",
                    entry.getValue() * 100.0 / Math.max(1, run.totalSamples()), name);
            if (++shown >= topFrames) {
                break;
            }
        }
    }

    /** Runs a list of missions and prints a comparison. */
    public static void main(String[] args) {
        GameData data = load();
        if (data == null) {
            System.out.println("No Warcraft II installation configured.");
            return;
        }
        int cycles = Integer.getInteger("profile.cycles", 900);
        List<String> paths = args.length > 0 ? List.of(args) : List.of(
                "campaigns/human/level01h",
                "campaigns/human/level12h",
                "campaigns/human-exp/levelx11h",
                "campaigns/human-exp/levelx12h");
        for (String path : paths) {
            Run run = profile(data, path, cycles);
            if (run == null) {
                System.out.println(path + ": will not load");
                continue;
            }
            report(run, 25);
            System.out.println();
        }
    }
}
