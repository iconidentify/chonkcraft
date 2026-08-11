package net.chonkbase.chonkcraft.engine.parity;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.unit.Unit;

/**
 * Writes this engine's simulation, cycle by cycle, in the same words the
 * real one writes its own.
 *
 * <p>The strategy this serves: LegacyEngine is deterministic and so is this
 * port, so the two can be <em>diffed</em>. The same map, the same seed, no
 * player at the controls -- if the implementation were a perfect port, both engines
 * would write the same file, and every line they disagree on is a parity
 * bug found mechanically rather than waiting for a screenshot from play.
 * The reference side is the upstream binary built with
 * {@code tools/legacyEngine-trace.patch}, run as
 *
 * <pre>
 * LEGACY_ENGINE_TRACE=upstream.txt LEGACY_ENGINE_TRACE_CYCLES=900 \
 * LEGACY_ENGINE_TRACE_EXIT=1 SDL_VIDEODRIVER=dummy SDL_AUDIODRIVER=dummy \
 * legacyEngine -d /path/to/chonkcraft-data -r -l campaigns/human/level01h.smp.gz
 * </pre>
 *
 * and this side as
 *
 * <pre>
 * java ... net.chonkbase.chonkcraft.engine.parity.EngineTrace \
 *     campaigns/human/level01h 900 java.txt
 * </pre>
 *
 * with {@code scripts/diff-determinism.py} reporting the first cycle the
 * two disagree on and what disagreed. The loop is: run both, read the first
 * divergence, fix it, run again, until the traces agree for the whole
 * window -- then lengthen the window.
 *
 * <p>For a map path the scenario is the bare map, which is what the
 * upstream binary starts when a map is named on its command line: the PUD's
 * units and players, the map's own script, computer slots running the
 * personalities the PUD's {@code AIPL} bytes name, nobody giving orders.
 * For a {@code campaigns/} path it is the full mission -- triggers,
 * {@code DefineAllow} rules, script-written opponents -- because upstream's
 * command line runs the campaign wrapper too: the one thing between a
 * harness and the fifty-two missions was the briefing menu the wrapper
 * opens, and the trace build skips it ({@code CreateGame} redefines
 * {@code Briefing} to set {@code Objectives} and return, which from the
 * command line is everything the shipped function does besides its menu).
 * One known asymmetry remains: this implementation's order enumeration is its own --
 * the differ compares order names only through its translation table, and
 * positions, health, banks and the random seed always.
 */
public final class EngineTrace {

    private static final String TRACE_SEED_PROPERTY = "chonkcraft.trace.seed";
    private static final String TRACE_PROFILE_PROPERTY = "chonkcraft.trace.profile";
    private static final String COUNTERFACTUAL_PROPERTY =
            "chonkcraft.trace.counterfactual";
    private static final int BNE_INITIALIZATION_TICKS = 2;

    private EngineTrace() {
    }

    enum CounterfactualPhase {
        PRE,
        POST
    }

    /** One test-harness-only intervention from a counterfactual plan. */
    record CounterfactualIntervention(CounterfactualPhase phase, int cycle,
            int unitId, String operation, String value) {

        static CounterfactualIntervention parse(String line, int lineNumber) {
            String[] fields = line.split("\\t", -1);
            if (fields.length != 5) {
                throw new IllegalArgumentException(
                        "counterfactual line " + lineNumber
                        + " must contain five tab-separated fields");
            }
            CounterfactualPhase phase;
            try {
                phase = CounterfactualPhase.valueOf(fields[0].toUpperCase());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "counterfactual line " + lineNumber
                        + " has invalid phase " + fields[0], exception);
            }
            int cycle;
            int unitId;
            try {
                cycle = Integer.parseInt(fields[1]);
                unitId = Integer.parseInt(fields[2]);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        "counterfactual line " + lineNumber
                        + " has a non-integer cycle or unit", exception);
            }
            if (cycle <= 0 || unitId <= 0) {
                throw new IllegalArgumentException(
                        "counterfactual line " + lineNumber
                        + " requires a positive cycle and unit id");
            }
            String operation = fields[3];
            if (!List.of("set-order", "set-reported-order", "set-saved-order",
                    "set-tile", "set-wait", "set-hp", "clear-path")
                    .contains(operation)) {
                throw new IllegalArgumentException(
                        "counterfactual line " + lineNumber
                        + " has unsupported operation " + operation);
            }
            return new CounterfactualIntervention(
                    phase, cycle, unitId, operation, fields[4]);
        }
    }

    /**
     * An opt-in intervention plan used only by this test trace executable.
     *
     * <p>The ordinary parity path never enters this loop: {@link #main}
     * retains its original branch-free cycle loop when the property is not
     * set. Production engine classes contain no counterfactual hook.</p>
     */
    static final class CounterfactualPlan {
        private static final String HEADER = "# bne-counterfactual-v1";
        private final List<CounterfactualIntervention> interventions;

        private CounterfactualPlan(
                List<CounterfactualIntervention> interventions) {
            this.interventions = List.copyOf(interventions);
        }

        static CounterfactualPlan load(Path path) throws java.io.IOException {
            List<String> lines = Files.readAllLines(path);
            if (lines.isEmpty() || !HEADER.equals(lines.get(0))) {
                throw new IllegalArgumentException(
                        "counterfactual plan has no " + HEADER + " header");
            }
            List<CounterfactualIntervention> interventions = new ArrayList<>();
            for (int index = 1; index < lines.size(); index++) {
                String line = lines.get(index);
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                interventions.add(CounterfactualIntervention.parse(
                        line, index + 1));
            }
            if (interventions.isEmpty()) {
                throw new IllegalArgumentException(
                        "counterfactual plan contains no interventions");
            }
            return new CounterfactualPlan(interventions);
        }

        List<CounterfactualIntervention> interventions() {
            return interventions;
        }

        void apply(CounterfactualPhase phase, int cycle, World world) {
            for (CounterfactualIntervention intervention : interventions) {
                if (intervention.phase() != phase
                        || intervention.cycle() != cycle) {
                    continue;
                }
                Unit unit = world.units().stream()
                        .filter(candidate -> candidate.id() == intervention.unitId())
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException(
                                "counterfactual unit " + intervention.unitId()
                                + " is absent at cycle " + cycle));
                apply(intervention, world, unit);
                System.err.printf(
                        "JBNECTF phase=%s cycle=%d unit=%d operation=%s value=%s%n",
                        phase.name().toLowerCase(), cycle, unit.id(),
                        intervention.operation(), intervention.value());
            }
        }

        private static void apply(CounterfactualIntervention intervention,
                World world, Unit unit) {
            switch (intervention.operation()) {
                case "set-order" -> unit.setOrder(order(intervention.value()));
                case "set-reported-order" ->
                    unit.setActionBeforeQueued(order(intervention.value()));
                case "set-saved-order" ->
                    unit.setSavedOrder(order(intervention.value()));
                case "set-wait" ->
                    unit.setWaitCycles(integer(intervention, intervention.value()));
                case "set-hp" ->
                    unit.setHitPoints(integer(intervention, intervention.value()));
                case "clear-path" -> unit.clearPath();
                case "set-tile" -> relocate(world, unit, intervention);
                default -> throw new IllegalArgumentException(
                        "unsupported counterfactual operation "
                        + intervention.operation());
            }
        }

        private static Unit.Order order(String value) {
            try {
                return Unit.Order.valueOf(value.toUpperCase());
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException(
                        "unsupported counterfactual order " + value, exception);
            }
        }

        private static int integer(CounterfactualIntervention intervention,
                String value) {
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException(
                        intervention.operation() + " requires an integer value",
                        exception);
            }
        }

        private static void relocate(World world, Unit unit,
                CounterfactualIntervention intervention) {
            String[] coordinates = intervention.value().split(",", -1);
            if (coordinates.length != 2) {
                throw new IllegalArgumentException(
                        "set-tile requires x,y coordinates");
            }
            int x = integer(intervention, coordinates[0]);
            int y = integer(intervention, coordinates[1]);
            if (unit.isOnMap()) {
                invokeWorldMarker(world, "markSight", unit, false);
                invokeWorldMarker(world, "markOccupancy", unit, false);
            }
            unit.setTile(x, y);
            unit.setOffset(0, 0);
            unit.clearPath();
            if (unit.isOnMap()) {
                invokeWorldMarker(world, "markOccupancy", unit, true);
                invokeWorldMarker(world, "markSight", unit, true);
            }
        }

        private static void invokeWorldMarker(World world, String name,
                Unit unit, boolean enabled) {
            try {
                Method method = World.class.getDeclaredMethod(
                        name, Unit.class, boolean.class);
                method.setAccessible(true);
                method.invoke(world, unit, enabled);
            } catch (NoSuchMethodException | IllegalAccessException
                    | InvocationTargetException exception) {
                throw new IllegalStateException(
                        "cannot apply test-only counterfactual relocation", exception);
            }
        }
    }

    public static GameData data() {
        AssetSource assets = AssetSource.fromEnvironment();
        if (assets == null) {
            return null;
        }
        return new GameData(assets);
    }

    /** The bare-map world, loaded the way the upstream command line loads one. */
    public static World world(GameData data, String mapPath) {
        return world(data, mapPath, World.DEFAULT_RANDOM_SEED);
    }

    /** The bare-map world at an explicitly pinned initialization seed. */
    public static World world(GameData data, String mapPath,
            int initializationSeed) {
        PudMap pud = data.campaignMap(mapPath);
        if (pud == null) {
            return null;
        }
        World world = new World(GameMap.from(pud, data.loadTileset(pud.tileset()).tileset()),
                Player.from(pud),
                initializationSeed);
        if (data.mainArchive().isValid(278)) {
            world.setBattleNetSequenceData(data.mainArchive().entry(278));
        }
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setSpells(data.spells().spells());
        world.setMissileTypes(data.missiles().types());
        // wc2.legacy-declaration's CreateUnit wrapper converts every load-time unit to its
        // owner's race, on bare maps as much as on missions.
        data.applyRaceEquivalents(world);
        data.applyAiEquivalents(world);
        // The button relations hold on bare maps too -- upstream loads
        // buttons.legacy-declaration on every path, and they are how the AI knows a
        // blacksmith researches swords and a pig farm does not.
        world.setBuilders(data.buildRelation(pud.tileset()));
        world.setTrainers(data.trainRelation(pud.tileset()));
        world.setResearchers(data.researchRelation(pud.tileset()));
        data.populate(world, pud);
        // CreateGame's post-placement UpdateForNewUnit state. Campaign
        // loading performs this after placeUnits; the bare-map harness must
        // do it too. Otherwise demo02's starting refinery exists visually but
        // never raises player 1's oil income from 100 to 125, a dormant
        // harness error until its tanker first banks on cycle 1241.
        world.recalculateSupply();
        // Switch the computer players on and then give them their scripts,
        // in that order, because that is the order {@code GameData}'s own
        // mission loading uses and the order matters: {@code attachPerSlot}
        // walks {@code World.ais()}, which is empty until the slots are
        // enabled, so attaching first attaches to nobody.
        //
        // This harness got it wrong twice, each time in a way that looked
        // like an engine bug. First it never enabled them at all, and
        // upstream played every map with its opponents running while this
        // side played it with them asleep. Then it enabled them after
        // attaching, so they ran with no personality at all -- and a bare
        // {@code AiPlayer} makes its own decisions, which read as this implementation
        // marching four archers off a map upstream leaves standing still.
        // A harness that does not build the world the way the engine builds
        // it measures the harness.
        world.enableAiForComputerPlayers();
        data.attachRetailAi(world, pud, java.util.Map.of());
        // And the ready pass, CreateGame's tail: an AI's scout flyers go
        // exploring before the first cycle on a bare map too.
        return world;
    }

    /** {@code World.battleNetMoveAnimation}, which is package private. */
    private static boolean moveAnimation(Unit unit) {
        if (unit.type() == null || unit.type().animationSet() == null) {
            return false;
        }
        var move = unit.type().animationSet().get(
                net.chonkbase.chonkcraft.engine.animation.AnimationSet.State.MOVE);
        return move != null && unit.animation().current() == move;
    }

    /** One cycle's state, in the shared format. */
    public static void dump(PrintWriter out, World world) {
        dump(out, world, world.cycle());
    }

    private static void dump(PrintWriter out, World world, long reportedCycle) {
        String fieldParity = System.getenv("CHONKCRAFT_TRACE_BNE_FIELDS");
        boolean subtileTrace = System.getenv("CHONKCRAFT_TRACE_BNE_SUBTILE") != null
                || Boolean.getBoolean("chonkcraft.trace.bne.subtile");
        out.printf("cycle %d seed %08x%n", reportedCycle, world.randomSeed());
        for (Player player : world.players()) {
            if (player.type() == PudMap.PlayerType.NOBODY) {
                continue;
            }
            out.printf("p %d gold %d wood %d oil %d%n", player.index(),
                    player.get(net.chonkbase.chonkcraft.engine.unit.UnitType.Resource.GOLD),
                    player.get(net.chonkbase.chonkcraft.engine.unit.UnitType.Resource.WOOD),
                    player.get(net.chonkbase.chonkcraft.engine.unit.UnitType.Resource.OIL));
        }
        for (Unit unit : world.units()) {
            if (unit.type() == null || unit.destroyed()) {
                continue;
            }
            // Native semantic-v1 omits revealers (dead-vision markers). Emitting
            // them made every death an unmatched Java-only unit (XHuman 10
            // footman 1492's unit-dead-vision-1-4 at fixture 43) after the
            // knight help order already matched.
            if (unit.type().revealer()) {
                continue;
            }
            // Training, researching and upgrading are orders upstream --
            // UnitAction::Train and its siblings -- and building-state here,
            // carried on a still building. The ChonkCraft-profile trace reports
            // the state under upstream's name so the differ compares what
            // the building is doing rather than how each engine files it.
            // Retail BNE's coarse order byte for a training hall stays Still
            // (Human 13 fixture cycle 15: fortress and great hall keep order
            // 33 while peon training is underway), so the BNE profile leaves
            // the Still label alone.
            Object action = unit.currentAction();
            // BNE keeps the construction job on the contained builder. The
            // new building itself remains raw Still while its hit points rise;
            // Java drives equivalent progress through its internal
            // UNDER_CONSTRUCTION label.
            if (action == Unit.Order.UNDER_CONSTRUCTION) {
                action = Unit.Order.STILL;
            }
            if (fieldParity != null) {
                // Every column this implementation could stand in for a native record
                // field, beside the position the differ already pairs on. The
                // soft clear at 0x00450766 reads fields this implementation has no copy
                // of, and each has been guessed at with one of these columns
                // and measured on the survey, one hypothesis per four minutes.
                // The captures hold the answer for every unit on every cycle,
                // so the mapping can be fitted instead.
                System.err.printf("JBNEFIELD cycle=%s unit=%d x=%d y=%d %s%n",
                        reportedCycle, unit.id(), unit.tileX(), unit.tileY(),
                        world.battleNetFieldParity(unit));
            }
            if (subtileTrace) {
                out.printf("u %d %s p%d %d %d hp %d o %s%s px %d %d%n",
                        unit.id(), unit.type().ident(), unit.player(),
                        unit.tileX(), unit.tileY(), unit.hitPoints(), action,
                        unit.isOnMap() ? "" : " removed",
                        unit.pixelX(), unit.pixelY());
            } else {
                out.printf("u %d %s p%d %d %d hp %d o %s%s%n",
                        unit.id(), unit.type().ident(), unit.player(),
                        unit.tileX(), unit.tileY(), unit.hitPoints(), action,
                        unit.isOnMap() ? "" : " removed");
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String mapPath = args.length > 0 ? args[0] : "campaigns/human/level01h";
        int cycles = args.length > 1 ? Integer.parseInt(args[1]) : 900;
        Path outPath = Paths.get(args.length > 2 ? args[2] : "java-trace.txt");
        int initializationSeed = traceSeed();

        GameData data = data();
        if (data == null) {
            System.out.println("No Warcraft II installation configured. "
                    + "Set -Dwc2.install.dir=/path/to/game.");
            return;
        }
        World world;
        Runnable ticker;
        if (mapPath.startsWith("campaigns/")) {
            // The full mission, triggers and all, because that is what
            // upstream's command line runs for one of these now that the
            // trace build steps past the briefing.
            PudMap source = data.campaignMap(mapPath);
            net.chonkbase.chonkcraft.engine.campaign.Mission mission = source == null
                    ? null : data.loadMission(mapPath, GameData.personIn(source),
                            initializationSeed);
            if (mission == null) {
                System.out.println(mapPath + " will not load");
                return;
            }
            world = mission.world();
            ticker = mission::tick;
        } else {
            World bare = world(data, mapPath, initializationSeed);
            if (bare == null) {
                System.out.println(mapPath + " will not load");
                return;
            }
            world = bare;
            ticker = bare::tick;
        }
        // Retail BNE enters its timed play loop only after two complete
        // HandleEachCycle calls (0x420bc7 and 0x420ca6). The fixture tracer
        // intentionally numbers the first later, timed call as corpus cycle
        // one. Run the same unrecorded initialization cycles here so Java's
        // semantic state is sampled at the same boundary.
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            ticker.run();
        }
        String counterfactualPath = System.getProperty(COUNTERFACTUAL_PROPERTY);
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(outPath))) {
            if (counterfactualPath != null && !counterfactualPath.isBlank()) {
                CounterfactualPlan plan = CounterfactualPlan.load(
                        Paths.get(counterfactualPath));
                traceCounterfactual(out, world, ticker, cycles, plan);
            } else {
                traceOrdinary(out, world, ticker, cycles);
            }
        }
        System.out.println("wrote " + cycles + " cycles of " + mapPath
                + " to " + outPath + " seed="
                + Integer.toUnsignedString(initializationSeed)
                + " profile=bne"
                + (counterfactualPath == null || counterfactualPath.isBlank()
                   ? "" : " counterfactual=" + counterfactualPath));
    }

    /** The normal parity loop, kept free of intervention checks. */
    private static void traceOrdinary(PrintWriter out, World world,
            Runnable ticker, int cycles) {
        for (int cycle = 0; cycle < cycles; cycle++) {
            ticker.run();
            dump(out, world, cycle + 1);
        }
    }

    private static void traceCounterfactual(PrintWriter out, World world,
            Runnable ticker, int cycles, CounterfactualPlan plan) {
        for (int cycle = 1; cycle <= cycles; cycle++) {
            plan.apply(CounterfactualPhase.PRE, cycle, world);
            ticker.run();
            plan.apply(CounterfactualPhase.POST, cycle, world);
            dump(out, world, cycle);
        }
    }

    private static int traceSeed() {
        String value = System.getProperty(TRACE_SEED_PROPERTY);
        if (value == null || value.isBlank()) {
            return World.DEFAULT_RANDOM_SEED;
        }
        long parsed = Long.parseLong(value);
        if (parsed < 0 || parsed > 0xffffffffL) {
            throw new IllegalArgumentException(
                    TRACE_SEED_PROPERTY + " must be an unsigned 32-bit integer");
        }
        return (int) parsed;
    }
}
