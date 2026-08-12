package net.chonkbase.chonkcraft.engine.parity;

import java.io.PrintWriter;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.missile.Missile;
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
    private static final String COMMANDS_PROPERTY = "chonkcraft.trace.commands";
    private static final String SEMANTIC_V2_PROPERTY =
            "chonkcraft.trace.bne.semantic-v2";
    private static final String SEMANTIC_V2_FAMILIES_PROPERTY =
            "chonkcraft.trace.bne.semantic-v2.families";
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

    /** One translated player command, using this engine's paired unit id. */
    record ScriptedCommand(int cycle, int unitId, int x, int y) {
        static ScriptedCommand parse(String line, int lineNumber) {
            String[] fields = line.trim().split("\\s+");
            if (fields.length != 9 || !"cycle".equals(fields[0])
                    || !"move".equals(fields[2]) || !"unit".equals(fields[3])
                    || !"x".equals(fields[5]) || !"y".equals(fields[7])) {
                throw new IllegalArgumentException("command line " + lineNumber
                        + " must be 'cycle N move unit ID x X y Y'");
            }
            try {
                int cycle = Integer.parseInt(fields[1]);
                int unit = Integer.parseInt(fields[4]);
                int x = Integer.parseInt(fields[6]);
                int y = Integer.parseInt(fields[8]);
                if (cycle <= 0 || unit <= 0 || x < 0 || y < 0) {
                    throw new NumberFormatException("non-positive command field");
                }
                return new ScriptedCommand(cycle, unit, x, y);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("command line " + lineNumber
                        + " has an invalid number", exception);
            }
        }
    }

    static final class ScriptedCommandPlan {
        private final List<ScriptedCommand> commands;

        private ScriptedCommandPlan(List<ScriptedCommand> commands) {
            this.commands = List.copyOf(commands);
        }

        static ScriptedCommandPlan load(Path path) throws java.io.IOException {
            List<ScriptedCommand> commands = new ArrayList<>();
            int previous = 0;
            List<String> lines = Files.readAllLines(path);
            for (int index = 0; index < lines.size(); index++) {
                String line = lines.get(index).trim();
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                ScriptedCommand command = ScriptedCommand.parse(line, index + 1);
                if (command.cycle() < previous) {
                    throw new IllegalArgumentException(
                            "commands are not cycle-sorted at line " + (index + 1));
                }
                previous = command.cycle();
                commands.add(command);
            }
            if (commands.isEmpty()) {
                throw new IllegalArgumentException("command plan is empty");
            }
            return new ScriptedCommandPlan(commands);
        }

        void apply(int cycle, World world) {
            for (ScriptedCommand command : commands) {
                if (command.cycle() != cycle) {
                    continue;
                }
                Unit unit = world.units().stream()
                        .filter(candidate -> candidate.id() == command.unitId())
                        .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                "scripted command unit " + command.unitId()
                                + " is absent at cycle " + cycle));
                boolean accepted = world.orderCommandMove(
                        unit, command.x(), command.y());
                System.err.printf("JBNECOMMAND cycle=%d unit=%d x=%d y=%d accepted=%d%n",
                        cycle, unit.id(), command.x(), command.y(), accepted ? 1 : 0);
                if (!accepted) {
                    throw new IllegalStateException(
                            "scripted move was rejected at cycle " + cycle);
                }
            }
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
        if (semanticV2Enabled()) {
            dumpSemanticV2(out, world, reportedCycle);
        }
    }

    /**
     * The broader authenticated comparison tier.
     *
     * <p>These rows are additive: the semantic-v1 parser ignores their
     * prefixes, so enabling the new tier cannot silently change the old
     * baseline. Values are deliberately primitive and stable. A native field
     * without a proved Java equivalent is omitted instead of being guessed;
     * the companion scorer reports those omissions as uncovered state.</p>
     */
    private static void dumpSemanticV2(PrintWriter out, World world,
            long reportedCycle) {
        Set<String> families = semanticV2Families();
        List<Missile> missiles = families.contains("projectile")
                ? new ArrayList<>(world.missiles()) : new ArrayList<>();
        missiles.sort(Comparator.comparingInt(Missile::battleNetPoolSlot)
                .thenComparing(missile -> missile.type().ident()));
        List<GameMap.TerrainChange> terrain = families.contains("terrain")
                ? world.map().terrainChangedSinceLoad() : new ArrayList<>();
        out.printf("v2w cycle=%d sync_seed=%08x async_seed=%08x async_draws=%d "
                        + "units=%d missiles=%d terrain=%d%n",
                reportedCycle, world.randomSeed(), world.battleNetRandomSeed(),
                world.battleNetRandomDraws(), world.units().size(),
                missiles.size(), terrain.size());

        if (families.contains("player")) for (Player player : world.players()) {
            if (player.type() == PudMap.PlayerType.NOBODY) {
                continue;
            }
            int units = 0;
            int buildings = 0;
            for (Unit unit : world.units()) {
                if (unit.player() != player.index() || unit.type() == null
                        || unit.destroyed() || unit.isDying()
                        || unit.type().revealer()) {
                    continue;
                }
                if (unit.type().building()) {
                    buildings++;
                } else {
                    units++;
                }
            }
            List<String> researched = new ArrayList<>(
                    world.upgrades(player.index()).researched());
            researched.sort(String::compareTo);
            out.printf("v2p cycle=%d player=%d supply=%d demand=%d units=%d "
                            + "buildings=%d score=%d kills=%d razings=%d "
                            + "arrows=%d swords=%d shields=%d ship_attack=%d "
                            + "ship_armor=%d catapult_damage=%d "
                            + "ranger_berserker=%d marksmanship=%d "
                            + "longbow=%d scouting=%d "
                            + "researched=%s%n",
                    reportedCycle, player.index(), player.supply(), player.demand(),
                    units, buildings, player.score(), player.totalKills(),
                    player.totalRazings(),
                    techLevel(researched, "upgrade-arrow1", "upgrade-arrow2",
                            "upgrade-throwing-axe1", "upgrade-throwing-axe2"),
                    techLevel(researched, "upgrade-sword1", "upgrade-sword2",
                            "upgrade-battle-axe1", "upgrade-battle-axe2"),
                    techLevel(researched, "upgrade-human-shield1",
                            "upgrade-human-shield2", "upgrade-orc-shield1",
                            "upgrade-orc-shield2"),
                    techLevel(researched, "upgrade-human-ship-cannon1",
                            "upgrade-human-ship-cannon2",
                            "upgrade-orc-ship-cannon1", "upgrade-orc-ship-cannon2"),
                    techLevel(researched, "upgrade-human-ship-armor1",
                            "upgrade-human-ship-armor2",
                            "upgrade-orc-ship-armor1", "upgrade-orc-ship-armor2"),
                    techLevel(researched, "upgrade-ballista1", "upgrade-ballista2",
                            "upgrade-catapult1", "upgrade-catapult2"),
                    techLevel(researched, "upgrade-ranger", "upgrade-berserker"),
                    techLevel(researched, "upgrade-ranger-marksmanship",
                            "upgrade-berserker-regeneration"),
                    techLevel(researched, "upgrade-longbow", "upgrade-light-axes"),
                    techLevel(researched, "upgrade-ranger-scouting",
                            "upgrade-berserker-scouting"),
                    researched.isEmpty()
                            ? "-" : String.join(",", researched));
        }

        List<Unit> units = families.contains("unit")
                ? new ArrayList<>(world.units()) : new ArrayList<>();
        units.sort(Comparator.comparingInt(Unit::id));
        for (Unit unit : units) {
            if (unit.type() == null || unit.destroyed() || unit.type().revealer()) {
                continue;
            }
            Unit target = unit.target();
            int[] orderPoint = semanticV2OrderPoint(unit);
            out.printf("v2u cycle=%d unit=%d type=%s player=%d x=%d y=%d "
                            + "px=%d py=%d ox=%d oy=%d hp=%d mana=%d "
                            + "frame=%d face=%d mobile=%d timer=%d seqoff=%d order=%s "
                            + "saved=%s orderx=%d ordery=%d target=%d wait=%d "
                            + "collision=%d refusals=%d route=%s%n",
                    reportedCycle, unit.id(), unit.type().ident(), unit.player(),
                    unit.tileX(), unit.tileY(), unit.pixelX(), unit.pixelY(),
                    unit.offsetX(), unit.offsetY(), unit.hitPoints(), unit.mana(),
                    unit.frame(), unit.direction(), unit.canMove() ? 1 : 0,
                    unit.battleNetAnimationTimer(),
                    unit.battleNetSequenceOffset(), unit.order(), unit.savedOrder(),
                    orderPoint[0], orderPoint[1],
                    target == null ? -1 : target.id(), unit.waitCycles(),
                    unit.battleNetCollisionCounter(), unit.battleNetRefusals(),
                    semanticV2Route(unit));
        }

        for (Missile missile : missiles) {
            Missile.SavedState state = missile.savedState();
            out.printf("v2m cycle=%d slot=%d type=%s source=%d target=%d "
                            + "x=%d y=%d fromx=%d fromy=%d tox=%d toy=%d "
                            + "frame=%d face=%d delay=%d ttl=%d damage=%d "
                            + "remaining=%d flags=%d error=%d major=%d minor=%d "
                            + "pending=%d impact_wait=%d%n",
                    reportedCycle, missile.battleNetPoolSlot(),
                    missile.type().ident(), id(missile.source()), id(missile.target()),
                    rounded(state.x()), rounded(state.y()),
                    rounded(state.fromX()), rounded(state.fromY()),
                    rounded(state.toX()), rounded(state.toY()), state.frame(),
                    state.direction(), state.delay(), state.timeToLive(),
                    state.damage(), state.battleNetRemaining(),
                    state.battleNetFlags(), state.battleNetError(),
                    state.battleNetMajor(), state.battleNetMinor(),
                    state.battleNetPendingImpact() ? 1 : 0,
                    state.battleNetImpactWait());
        }

        terrain.sort(Comparator.comparingInt(GameMap.TerrainChange::y)
                .thenComparingInt(GameMap.TerrainChange::x));
        for (GameMap.TerrainChange change : terrain) {
            out.printf("v2t cycle=%d x=%d y=%d tile=%d graphic=%d flags=%x value=%d%n",
                    reportedCycle, change.x(), change.y(), change.tile(),
                    change.graphic(), change.flags(), change.value());
        }
    }

    private static boolean semanticV2Enabled() {
        return System.getenv("CHONKCRAFT_TRACE_BNE_SEMANTIC_V2") != null
                || Boolean.getBoolean(SEMANTIC_V2_PROPERTY);
    }

    private static Set<String> semanticV2Families() {
        String configured = System.getProperty(SEMANTIC_V2_FAMILIES_PROPERTY,
                "player,unit,projectile,terrain");
        Set<String> families = new java.util.LinkedHashSet<>();
        for (String value : configured.split(",")) {
            String family = value.trim().toLowerCase(java.util.Locale.ROOT);
            if (!family.isEmpty()) {
                families.add(family);
            }
        }
        Set<String> allowed = Set.of("player", "unit", "projectile", "terrain");
        if (families.isEmpty() || !allowed.containsAll(families)) {
            throw new IllegalArgumentException(
                    "semantic-v2 families must be player, unit, projectile or terrain");
        }
        return Set.copyOf(families);
    }

    private static int id(Unit unit) {
        return unit == null ? -1 : unit.id();
    }

    private static int rounded(double value) {
        return (int) Math.round(value);
    }

    private static int techLevel(List<String> researched, String... names) {
        int level = 0;
        for (String name : names) {
            if (researched.contains(name)) {
                level++;
            }
        }
        return level;
    }

    private static String semanticV2Route(Unit unit) {
        StringBuilder route = new StringBuilder();
        for (int depth = 0; depth < unit.pathLength(); depth++) {
            route.append(unit.peekHeadingAtDepth(depth));
        }
        return route.length() == 0 ? "-" : route.toString();
    }

    /**
     * The point stored in BNE's order union for the active order family.
     *
     * <p>Move/patrol/build already keep the general order target. Attack
     * owns a remembered goal beside its weak target pointer, and resource
     * orders own their selected resource square. Emitting only
     * {@code orderTarget} made those two families look uncovered even though
     * the engine already models both native union arms.</p>
     */
    private static int[] semanticV2OrderPoint(Unit unit) {
        return switch (unit.order()) {
            case ATTACK, ATTACK_MOVE -> new int[] {
                    unit.attackGoalX(), unit.attackGoalY()};
            case HARVEST, RETURN_GOODS -> new int[] {
                    unit.resourceTileX(), unit.resourceTileY()};
            case BUILD -> new int[] {unit.buildGoalX(), unit.buildGoalY()};
            default -> new int[] {unit.orderTargetX(), unit.orderTargetY()};
        };
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
        String commandsPath = System.getProperty(COMMANDS_PROPERTY);
        if (counterfactualPath != null && !counterfactualPath.isBlank()
                && commandsPath != null && !commandsPath.isBlank()) {
            throw new IllegalArgumentException(
                    "counterfactual and commanded traces cannot run together");
        }
        try (PrintWriter out = new PrintWriter(Files.newBufferedWriter(outPath))) {
            if (counterfactualPath != null && !counterfactualPath.isBlank()) {
                CounterfactualPlan plan = CounterfactualPlan.load(
                        Paths.get(counterfactualPath));
                traceCounterfactual(out, world, ticker, cycles, plan);
            } else if (commandsPath != null && !commandsPath.isBlank()) {
                ScriptedCommandPlan plan = ScriptedCommandPlan.load(
                        Paths.get(commandsPath));
                traceCommands(out, world, ticker, cycles, plan);
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

    private static void traceCommands(PrintWriter out, World world,
            Runnable ticker, int cycles, ScriptedCommandPlan plan) {
        for (int cycle = 1; cycle <= cycles; cycle++) {
            plan.apply(cycle, world);
            ticker.run();
            dump(out, world, cycle);
        }
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
