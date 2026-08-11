package net.chonkbase.chonkcraft.engine.perf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * Plays every mission with nobody at the controls and asks whether the world
 * is still coherent afterwards.
 *
 * <p>Why this exists, in one sentence from the regression rationale: "the tripwires this repo
 * has all look for something <em>missing</em>, and none of them fire on
 * something present that declines to act." The four mechanical checks in
 * {@code scripts/audit-gaps.py} find an accessor with no callers, a layout
 * element nobody draws, a script name nobody binds and a sound nobody plays.
 * Every one of them is a check for absence. None of them can see a method that
 * exists, is called, returns cleanly, and does the wrong thing -- and that is
 * the shape of every bug in this implementation that reached a player.
 *
 * <p>Look at how each of those was actually found. A transport that could not
 * unload: reported from play. A woodcutter standing in a clearing swinging at
 * nothing: reported from play, with a screenshot. Ground that never redrew:
 * reported from play. Five spells' worth of an archer that could not walk onto
 * the circle of power: reported from play. The suite was green for all of
 * them, because the suite tests the units of behaviour somebody thought to
 * write a test for, and the failures were in the joins.
 *
 * <p>So this does not test a unit of behaviour. It runs the whole game and
 * asserts properties that must hold in <em>any</em> mission at <em>any</em>
 * moment, of the kind a player notices immediately and a unit test never
 * checks. Each one below is derived from a fault that actually shipped, and
 * the entry for each says which.
 *
 * <p>Run {@link #main} for the full table over all fifty-two missions.
 * {@link PlayInvariantsTest} pins it.
 */
public final class PlayInvariants {

    private PlayInvariants() {
    }

    /**
     * How long each mission is left alone.
     *
     * <p>A hundred and twenty simulated seconds. Long enough for the scripts
     * to arm their triggers, the computer players to take their first turns
     * and the starting workers to reach their first resource; short enough
     * that all fifty-two fit in a test.
     *
     * <p>It was sixty, and sixty put the one mission that decides itself
     * within a second or two of the boundary. {@code human-exp/levelx11h} is
     * a defeat because the orc war band kills the knight-rider, and measured
     * across a few unrelated engine changes that death lands anywhere from
     * cycle 1,770 to cycle 1,830 -- on both sides of a 1,800-cycle window. So
     * {@link PlayInvariantsTest#noMissionDecidesItselfWithNobodyPlaying}
     * would go red for a change that had moved a fight by two seconds and say
     * "a regression in the victory conditions", which is not what happened and
     * cost an investigation to establish. Doubling the window puts the one
     * real answer well inside it. Nothing else joins the set at 120 seconds,
     * which was checked rather than assumed.
     */
    public static final int CYCLES = World.CYCLES_PER_SECOND * 120;

    /**
     * How often the per-unit sweeps run, in cycles.
     *
     * <p>Every tenth. The faults these look for are states a unit stays in --
     * a worker aimed at a stump stays aimed at it for the length of a walk, a
     * unit buried under restored terrain stays buried -- so sampling costs
     * nothing in detection and takes the sweep off the hot path.
     */
    private static final int SAMPLE_EVERY = 10;

    /** One breach of one invariant, in words a person can act on. */
    public record Breach(String mission, String invariant, String detail) {
        @Override
        public String toString() {
            return mission + ": " + detail;
        }
    }

    /** What one mission's run produced. */
    public static final class Run {
        public String mission = "?";
        public final List<Breach> breaches = new ArrayList<>();
        /** Units examined by the standing-ground sweep, so it cannot pass empty. */
        public int groundSamples;
        /** Units examined by the clickability sweep, so it cannot pass empty. */
        public int clickSamples;
        /** Harvesters examined, so it cannot pass empty. */
        public int harvestSamples;
        /** Woodcutters that took the chop order, so the sweep cannot be vacuous. */
        public int woodcuttersOrdered;
        /** The most gatherers seen working at one sample. */
        public int mostWorkingAtOnce;
        public TriggerSystem.Outcome outcome = TriggerSystem.Outcome.RUNNING;
        public String error;

        public boolean clean() {
            return breaches.isEmpty() && error == null;
        }
    }

    /**
     * Nobody stands where they could not walk.
     *
     * <p>{@code UnitTypeCanBeAt} is the question every mover
     * asks before a step, and the invariant is that the answer stays true for
     * a unit already standing there. It broke twice, in places nothing
     * connected: felling a tree wrote a graphic index in as a tile code and
     * assigned rather than or-ed the flags it looked up, so a cleared square
     * ended with a flag word of nought -- not land, therefore not walkable,
     * and a peasant had chopped a hole in the world it was standing in. Then a
     * save restored the wood over the woodcutter, putting a live unit inside
     * terrain nothing can enter or leave.
     *
     * <p>Buildings are exempt for the reason upstream exempts them: a building
     * is placed by the rules in {@code CanBuildHere} rather than by the
     * movement mask, and it sits on ground it has itself marked occupied.
     */
    private static void checkStandingGround(World world, Run run) {
        for (Unit unit : world.unitsSnapshot()) {
            UnitType type = unit.type();
            if (type == null || !unit.isAlive() || !unit.isOnMap() || type.building()) {
                continue;
            }
            run.groundSamples++;
            MapField field = world.map().fieldOrNull(unit.tileX(), unit.tileY());
            if (field == null) {
                run.breaches.add(new Breach(run.mission, "standing ground",
                        type.ident() + " is at " + unit.tileX() + "," + unit.tileY()
                                + ", which is off the map"));
                continue;
            }
            if (!field.hasFlag(unit.movementMask())) {
                run.breaches.add(new Breach(run.mission, "standing ground",
                        type.ident() + " is standing at " + unit.tileX() + "," + unit.tileY()
                                + " on ground it could not walk onto: tile flags 0x"
                                + Long.toHexString(field.flags()) + " against a movement mask of 0x"
                                + Long.toHexString(unit.movementMask())));
            }
        }
    }

    /**
     * Everything on the map can be clicked on.
     *
     * <p>{@code CUnit::IsAlive} is {@code !Destroyed && CurrentAction() !=
     * UnitAction::Die} and says nothing about hit points. This
     * port's {@code Unit.isAlive()} adds {@code hitPoints > 0}, and {@code
     * Unit.setHitPoints} clamps to the type's maximum, so a type declared
     * {@code HitPoints = 0} can never have any and is invisible to every
     * lookup. In Warcraft II you click an oil patch to see how much oil is in
     * it, and the circle of power is the objective of the Dark Portal
     * missions.
     *
     * <p>The measurement is the one a player makes: ask the world what is at
     * the unit's own centre square and require it to name something. It does
     * not require the answer to be that same unit, because a square can hold
     * a corpse under a soldier and upstream's own lookup has an order to it.
     */
    private static void checkClickable(World world, Run run) {
        for (Unit unit : world.unitsSnapshot()) {
            UnitType type = unit.type();
            // Upstream's rule written out here rather than called, and that
            // distinction is the whole test. The condition wanted is
            // CUnit::IsAlive -- on the map, not playing out a death animation
            // -- and calling Unit.isPointable() to express it makes the
            // measurement agree with whatever that method currently says.
            // Measured: with the sweep phrased as isPointable(), breaking
            // isPointable() to the old hitPoints > 0 rule left this test
            // passing, because the oil patches it should have caught were
            // excluded from the sample by the same clause that hid them from
            // the game. Not isAlive() either, for the same reason.
            if (type == null || !unit.isOnMap() || unit.order() == Unit.Order.DYING
                    || type.vanishes() || type.revealer()) {
                continue;
            }
            run.clickSamples++;
            int x = unit.tileX() + type.tileWidth() / 2;
            int y = unit.tileY() + type.tileHeight() / 2;
            if (world.unitAt(x, y) == null) {
                run.breaches.add(new Breach(run.mission, "clickable",
                        type.ident() + " at " + unit.tileX() + "," + unit.tileY()
                                + " answers nothing when clicked at its own centre " + x + "," + y
                                + " [order=" + unit.order() + " alive=" + unit.isAlive()
                                + " pointable=" + unit.isPointable()
                                + " hp=" + unit.hitPoints() + "]"));
            }
        }
    }

    /**
     * No gatherer is walking to a square that has nothing on it.
     *
     * <p>The reported one, and the clearest case of a method that runs cleanly
     * and does the wrong thing. A forest square holds a hundred wood and a
     * peasant carries a hundred, so the square a woodcutter remembers when it
     * walks into the hall is always the one it has just felled; {@code
     * World.leaveDepot} handed the remembered square straight back.
     * {@code WaitInDepot} searches out from
     * the last worked square for one that still has a tree on it and only then
     * picks a face of the hall to put the worker on. Measured before the fix:
     * seventy-seven return trips out of seventy-seven came out of the hall
     * aimed at bare ground and walked the whole way back to it.
     */
    private static void checkHarvestTargets(World world, Run run) {
        for (Unit unit : world.unitsSnapshot()) {
            UnitType type = unit.type();
            if (type == null || !unit.isAlive() || !unit.isOnMap() || !type.canGather()) {
                continue;
            }
            if (unit.order() != Unit.Order.HARVEST || unit.returningToDepot()) {
                continue;
            }
            // A worker aimed at a resource *building* -- a mine, a platform --
            // is a different path and is covered by the unit still existing.
            // resourceUnit and resourceTile, not target and orderTarget: a
            // gathering order keeps its own pair, which is the whole reason a
            // re-plan that consults the wrong one asks for a route to nowhere.
            // The first draft of this check read orderTarget and sampled
            // nought workers across all fifty-two missions.
            if (unit.resourceUnit() != null) {
                continue;
            }
            int x = unit.resourceTileX();
            int y = unit.resourceTileY();
            MapField field = world.map().fieldOrNull(x, y);
            if (field == null) {
                continue;
            }
            run.harvestSamples++;
            if (!field.isForest()) {
                run.breaches.add(new Breach(run.mission, "harvest target",
                        type.ident() + " at " + unit.tileX() + "," + unit.tileY()
                                + " is harvesting towards " + x + "," + y
                                + ", which has no tree on it"));
            }
            if (unit.gatherClockStarted() && field.isForest()) {
                int expected = Missile.directionToHeading(
                        x - unit.tileX(), y - unit.tileY());
                if (unit.direction() != expected) {
                    run.breaches.add(new Breach(run.mission, "harvest facing",
                            type.ident() + " at " + unit.tileX() + "," + unit.tileY()
                                    + " is chopping " + x + "," + y
                                    + " while facing direction " + unit.direction()
                                    + " instead of " + expected));
                }
            }
        }
    }

    /**
     * Sets every woodcutter on the map chopping, and says how many took the
     * order.
     *
     * <p>Left alone, a campaign mission's own workers stand still, so a purely
     * passive sweep measures nothing about harvesting -- the first draft of
     * this class ran all fifty-two missions and sampled <em>nought</em>
     * harvesters, which is a check that passes because it never ran. The one
     * thing this class does that a player would do is issue this order.
     *
     * <p>Aimed at whatever forest square is nearest by Chebyshev distance,
     * deliberately without asking whether it can be reached or whether it is
     * buried in the middle of a stand. That is the case the woodcutter lane
     * closed: {@code MoveToResource_Terrain} answers {@code PF_UNREACHABLE} by
     * searching out from the worker for the nearest tree it <em>can</em> reach
     * and this implementation used to drop the worker to
     * STILL for the rest of the game instead. Clicking a tree in the middle of
     * a wood is a thing a player does constantly, so pointing every worker at
     * one is the honest stress.
     */
    private static int orderEveryGathererToChop(World world) {
        int ordered = 0;
        for (Unit unit : world.unitsSnapshot()) {
            UnitType type = unit.type();
            if (type == null || !unit.isAlive() || !unit.isOnMap() || !type.canGather()) {
                continue;
            }
            if (!type.gathering().containsKey(UnitType.Resource.WOOD)) {
                continue;
            }
            int bestX = -1;
            int bestY = -1;
            int best = Integer.MAX_VALUE;
            for (int y = 0; y < world.map().height(); y++) {
                for (int x = 0; x < world.map().width(); x++) {
                    MapField field = world.map().fieldOrNull(x, y);
                    if (field == null || !field.isForest()) {
                        continue;
                    }
                    int distance = Math.max(Math.abs(x - unit.tileX()), Math.abs(y - unit.tileY()));
                    if (distance < best) {
                        best = distance;
                        bestX = x;
                        bestY = y;
                    }
                }
            }
            if (bestX >= 0 && world.orderHarvest(unit, bestX, bestY)) {
                ordered++;
            }
        }
        return ordered;
    }

    /** Plays one mission and returns what it found. */
    public static Run measure(GameData data, String path, int cycles) {
        Run run = new Run();
        run.mission = path;
        Mission mission;
        try {
            mission = data.loadMission(path);
        } catch (RuntimeException failure) {
            run.error = String.valueOf(failure);
            return run;
        }
        World world = mission.world();
        run.woodcuttersOrdered = orderEveryGathererToChop(world);
        for (int cycle = 0; cycle < cycles; cycle++) {
            mission.tick();
            if (cycle % SAMPLE_EVERY == 0) {
                checkStandingGround(world, run);
                checkClickable(world, run);
                checkHarvestTargets(world, run);
                int working = 0;
                for (Unit unit : world.unitsSnapshot()) {
                    if (unit.type() != null && unit.type().canGather() && unit.isAlive()
                            && (unit.order() == Unit.Order.HARVEST
                                    || unit.order() == Unit.Order.RETURN_GOODS)) {
                        working++;
                    }
                }
                run.mostWorkingAtOnce = Math.max(run.mostWorkingAtOnce, working);
            }
        }
        run.outcome = mission.outcome();
        return run;
    }

    /** Every mission, in campaign order. */
    public static List<Run> measureAll(GameData data, int cycles) {
        List<Run> runs = new ArrayList<>();
        for (String path : AiProbe.missionPaths(data)) {
            runs.add(measure(data, path, cycles));
        }
        return runs;
    }

    /** Breaches grouped by invariant, most-breached first. */
    public static Map<String, Integer> byInvariant(List<Run> runs) {
        Map<String, Integer> counts = new TreeMap<>();
        for (Run run : runs) {
            for (Breach breach : run.breaches) {
                counts.merge(breach.invariant(), 1, Integer::sum);
            }
        }
        return counts;
    }

    /** The full table. */
    public static void main(String[] args) {
        GameData data = SimulationProfile.load();
        long started = System.nanoTime();
        List<Run> runs = measureAll(data, CYCLES);
        long millis = (System.nanoTime() - started) / 1_000_000;

        int groundSamples = 0;
        int clickSamples = 0;
        int harvestSamples = 0;
        Map<String, List<Breach>> distinct = new LinkedHashMap<>();
        for (Run run : runs) {
            groundSamples += run.groundSamples;
            clickSamples += run.clickSamples;
            harvestSamples += run.harvestSamples;
            for (Breach breach : run.breaches) {
                distinct.computeIfAbsent(breach.invariant(), key -> new ArrayList<>()).add(breach);
            }
        }

        int ordered = 0;
        int working = 0;
        for (Run run : runs) {
            ordered += run.woodcuttersOrdered;
            working += run.mostWorkingAtOnce;
        }
        System.out.printf("%d missions, %d cycles each, %d ms%n", runs.size(), CYCLES, millis);
        System.out.printf("samples: %,d standing-ground, %,d clickable, %,d harvest%n",
                groundSamples, clickSamples, harvestSamples);
        System.out.printf("woodcutters: %d took the chop order, %d working at the peak%n",
                ordered, working);
        System.out.println();

        for (Map.Entry<String, Integer> entry : byInvariant(runs).entrySet()) {
            System.out.printf("  %-18s %,8d breaches%n", entry.getKey(), entry.getValue());
        }
        System.out.println();

        for (Map.Entry<String, List<Breach>> entry : distinct.entrySet()) {
            System.out.println("--- " + entry.getKey() + " ---");
            // One line per distinct message, so a fault repeated every sample
            // does not bury a different one.
            Map<String, Integer> seen = new LinkedHashMap<>();
            for (Breach breach : entry.getValue()) {
                seen.merge(breach.mission() + " | " + breach.detail(), 1, Integer::sum);
            }
            int printed = 0;
            for (Map.Entry<String, Integer> line : seen.entrySet()) {
                System.out.printf("  %5dx %s%n", line.getValue(), line.getKey());
                if (++printed >= 25) {
                    System.out.printf("  ... and %d more distinct%n", seen.size() - printed);
                    break;
                }
            }
            System.out.println();
        }

        System.out.println("--- missions that decided themselves with nobody playing ---");
        int decided = 0;
        for (Run run : runs) {
            if (run.outcome != TriggerSystem.Outcome.RUNNING) {
                System.out.printf("  %-34s %s%n", run.mission, run.outcome);
                decided++;
            }
            if (run.error != null) {
                System.out.printf("  %-34s ERROR %s%n", run.mission, run.error);
            }
        }
        System.out.printf("  %d of %d%n", decided, runs.size());
    }
}
