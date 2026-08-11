package net.chonkbase.chonkcraft.engine;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.map.FogOfWar;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A probe, not an assertion suite. It ticks a real campaign map and counts how
 * often each square's visibility flips while the world runs, so that "the fog
 * flickers" can be answered with a number instead of an opinion.
 */
class FogOscillationProbeTest {

    private static final int CYCLES = Integer.getInteger("probe.cycles", 3600);

    private record Loaded(World world, GameData data, PudMap pud, int player,
            net.chonkbase.chonkcraft.engine.campaign.Mission mission) {}

    private static GameData data() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    /**
     * The real mission-start path, not a bare world: the campaign script is
     * what gives the computer slots their personality, and an opponent that
     * never thinks is an opponent whose sight never changes.
     */
    private static Loaded load(GameData data, String mapPath) {
        PudMap pud = data.campaignMap(mapPath);
        Assumptions.assumeTrue(pud != null, "no campaign map " + mapPath);
        net.chonkbase.chonkcraft.engine.campaign.Mission mission = data.loadMission(mapPath);
        Assumptions.assumeTrue(mission != null, "mission would not load: " + mapPath);
        return new Loaded(mission.world(), data, pud, GameData.personIn(pud), mission);
    }

    // ------------------------------------------------------------------
    // 0. What maps exist, and on what tileset.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("probe: list the campaign maps and their tilesets")
    void listCampaignMaps() {
        GameData data = data();
        for (String path : data.campaignMapPaths()) {
            PudMap pud = data.campaignMap(path);
            if (pud == null) {
                continue;
            }
            System.out.println(path + "  " + pud.tileset()
                    + "  " + pud.width() + "x" + pud.height()
                    + "  units=" + pud.units().size());
        }
    }

    // ------------------------------------------------------------------
    // 1-5. Does the fog oscillate on a real map?
    // ------------------------------------------------------------------

    @Test
    @DisplayName("probe: fog flips on an orc campaign map")
    void orcMap() {
        run(data(), "campaigns/orc/level06o");
    }

    @Test
    @DisplayName("probe: fog flips on a wasteland campaign map with orcs to see")
    void wastelandMap() {
        run(data(), "campaigns/human/level06h");
    }

    @Test
    @DisplayName("probe: fog flips on a human campaign map")
    void humanMap() {
        run(data(), "campaigns/human/level02h");
    }

    /** Everything the probe records about one unit across the run. */
    private static final class Track {
        String label;
        int player;
        int lastRange = Integer.MIN_VALUE;
        int rangeChanges;
        int disagreements;
        long firstDisagreementCycle = -1;
        String worstDisagreement = "";
        int lastX;
        int lastY;
        int moves;
        String orders = "";
        int orderChanges;
    }

    private void run(GameData data, String mapPath) {
        Loaded loaded = load(data, mapPath);
        World world = loaded.world();
        GameMap map = world.map();
        int w = map.width();
        int h = map.height();
        int player = loaded.player();

        System.out.println("=== " + mapPath + "  " + loaded.pud().tileset()
                + "  " + w + "x" + h + "  units=" + world.units().size()
                + "  probing player " + player + " for " + CYCLES + " cycles"
                + "  ai=" + loaded.mission().ai());

        boolean[] previous = snapshot(world, player, w, h);
        int[] flips = new int[w * h];
        // Which cycles each tile flipped on, for the worst offenders.
        Map<Integer, List<Long>> when = new LinkedHashMap<>();
        Map<Integer, Track> tracks = new LinkedHashMap<>();
        // Flips that happened on a cycle where not one unit on the map changed
        // square. These are the ones the reported bug is made of.
        int flipsWithNothingMoving = 0;
        int quietCycles = 0;

        System.out.println("visible tiles at cycle 0: " + countTrue(previous));
        for (int i = 0; i < CYCLES; i++) {
            loaded.mission().tick();
            int moved = observeUnits(world, tracks);
            if (moved == 0) {
                quietCycles++;
            }
            boolean[] now = snapshot(world, player, w, h);
            for (int index = 0; index < now.length; index++) {
                if (now[index] != previous[index]) {
                    flips[index]++;
                    if (moved == 0) {
                        flipsWithNothingMoving++;
                    }
                    when.computeIfAbsent(index, k -> new ArrayList<>()).add(world.cycle());
                }
            }
            previous = now;
        }

        System.out.println("visible tiles at the end: " + countTrue(previous));
        System.out.println("cycles on which no unit changed square: " + quietCycles
                + " of " + CYCLES);
        System.out.println("flips that happened on such a cycle: " + flipsWithNothingMoving);
        report(world, w, h, flips, when, tracks, player);
    }

    private static int countTrue(boolean[] values) {
        int count = 0;
        for (boolean value : values) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    private static boolean[] snapshot(World world, int player, int w, int h) {
        boolean[] out = new boolean[w * h];
        FogOfWar fog = world.fog();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                out[y * w + x] = fog.isVisible(player, x, y);
            }
        }
        return out;
    }

    /**
     * World's own {@code sightRangeOf}, replicated so the test can predict what
     * {@code refreshChangedSight} will decide without touching World.
     */
    private static int sightRangeOf(World world, Unit unit) {
        if (unit.type() == null) {
            return 0;
        }
        if (unit.order() == Unit.Order.UNDER_CONSTRUCTION) {
            return 1;
        }
        return world.upgrades(unit.player()).sightRange(unit.type());
    }

    /** @return how many units changed square this cycle */
    private static int observeUnits(World world, Map<Integer, Track> tracks) {
        int moved = 0;
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.type() == null) {
                continue;
            }
            Track track = tracks.computeIfAbsent(unit.id(), k -> {
                Track fresh = new Track();
                fresh.label = unit.type().ident();
                fresh.player = unit.player();
                fresh.lastX = unit.tileX();
                fresh.lastY = unit.tileY();
                return fresh;
            });
            int want = sightRangeOf(world, unit);
            if (track.lastRange != Integer.MIN_VALUE && want != track.lastRange) {
                track.rangeChanges++;
            }
            track.lastRange = want;
            if (unit.tileX() != track.lastX || unit.tileY() != track.lastY) {
                track.moves++;
                moved++;
                track.lastX = unit.tileX();
                track.lastY = unit.tileY();
            }
            String order = String.valueOf(unit.order());
            if (!order.equals(track.orders)) {
                if (!track.orders.isEmpty()) {
                    track.orderChanges++;
                }
                track.orders = order;
            }
            // Would refreshChangedSight re-mark this unit? Sampled after the
            // tick, so on a refresh cycle a disagreement means the sweep did
            // not converge; on any other cycle it means it is pending.
            boolean eligible = unit.isAlive() && !unit.isDying() && unit.isOnMap();
            if (eligible && want != unit.markedSightRange()) {
                track.disagreements++;
                if (track.firstDisagreementCycle < 0) {
                    track.firstDisagreementCycle = world.cycle();
                }
                track.worstDisagreement = "wants " + want + ", marked "
                        + unit.markedSightRange() + ", order " + unit.order()
                        + ", cycle " + world.cycle() + " (cycle%30=" + world.cycle() % 30 + ")";
            }
        }
        return moved;
    }

    private void report(World world, int w, int h, int[] flips,
            Map<Integer, List<Long>> when, Map<Integer, Track> tracks, int player) {
        int flippedAtAll = 0;
        int flippedMoreThanFour = 0;
        int totalFlips = 0;
        for (int value : flips) {
            if (value > 0) {
                flippedAtAll++;
            }
            if (value > 4) {
                flippedMoreThanFour++;
            }
            totalFlips += value;
        }
        System.out.println("tiles that flipped at all: " + flippedAtAll
                + " of " + (w * h));
        System.out.println("tiles that flipped more than 4 times: " + flippedMoreThanFour);
        System.out.println("total flips: " + totalFlips);

        List<Integer> worst = new ArrayList<>();
        for (int index = 0; index < flips.length; index++) {
            if (flips[index] > 0) {
                worst.add(index);
            }
        }
        worst.sort(Comparator.comparingInt((Integer i) -> flips[i]).reversed());
        System.out.println("worst offenders:");
        for (int i = 0; i < Math.min(15, worst.size()); i++) {
            int index = worst.get(i);
            int x = index % w;
            int y = index / w;
            List<Long> cycles = when.get(index);
            System.out.println("  (" + x + "," + y + ") flips=" + flips[index]
                    + " at cycles " + cycles.subList(0, Math.min(12, cycles.size()))
                    + "  nearestUnits=" + near(world, x, y));
        }

        System.out.println("units whose sight range moved, or whose marked range disagreed:");
        List<Track> interesting = new ArrayList<>();
        for (Track track : tracks.values()) {
            if (track.rangeChanges > 0 || track.disagreements > 0) {
                interesting.add(track);
            }
        }
        interesting.sort(Comparator.comparingInt((Track t) -> t.disagreements + t.rangeChanges)
                .reversed());
        for (int i = 0; i < Math.min(25, interesting.size()); i++) {
            Track track = interesting.get(i);
            System.out.println("  " + track.label + " p" + track.player
                    + " rangeChanges=" + track.rangeChanges
                    + " disagreeCycles=" + track.disagreements
                    + " moves=" + track.moves
                    + " orderChanges=" + track.orderChanges
                    + " lastOrder=" + track.orders
                    + (track.worstDisagreement.isEmpty()
                            ? "" : "  last: " + track.worstDisagreement));
        }
        if (interesting.isEmpty()) {
            System.out.println("  (none)");
        }

        // Stationary units: a square in the middle of one's sight must never
        // flip. This is the sharpest statement of the reported bug.
        int stationaryFlips = 0;
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.player() != player || !unit.isOnMap()) {
                continue;
            }
            Track track = tracks.get(unit.id());
            if (track == null || track.moves > 0) {
                continue;
            }
            int index = unit.tileY() * w + unit.tileX();
            if (index >= 0 && index < flips.length && flips[index] > 0) {
                stationaryFlips++;
                System.out.println("  STATIONARY " + unit.type().ident()
                        + " at (" + unit.tileX() + "," + unit.tileY() + ") stands on a tile"
                        + " that flipped " + flips[index] + " times");
            }
        }
        System.out.println("stationary own units standing on a flipping tile: "
                + stationaryFlips);
    }

    private static String near(World world, int x, int y) {
        StringBuilder out = new StringBuilder();
        for (Unit unit : world.unitsSnapshot()) {
            int dx = Math.abs(unit.tileX() - x);
            int dy = Math.abs(unit.tileY() - y);
            if (dx <= 8 && dy <= 8) {
                if (out.length() > 0) {
                    out.append(", ");
                }
                out.append(unit.type() == null ? "?" : unit.type().ident())
                        .append("/p").append(unit.player())
                        .append("@").append(unit.tileX()).append(",").append(unit.tileY())
                        .append(unit.isOnMap() ? "" : "(off)");
            }
            if (out.length() > 400) {
                break;
            }
        }
        return out.length() == 0 ? "none within 8" : out.toString();
    }

    /**
     * A wide sweep rather than a deep one: every visibility flip on every map,
     * for every player, with the reference-count invariant checked each cycle.
     * If sight bookkeeping can drift anywhere, this is where it shows.
     */
    @Test
    @DisplayName("probe: sweep several campaign maps for flips and count drift")
    void sweep() {
        GameData data = data();
        List<String> maps = List.of(
                "campaigns/orc/level05o", "campaigns/orc/level06o", "campaigns/orc/level07o",
                "campaigns/orc/level12o", "campaigns/human/level05h", "campaigns/human/level06h",
                "campaigns/human/level07h", "campaigns/human/level12h",
                "campaigns/orc-exp/levelx09o", "campaigns/orc-exp/levelx12o");
        int cycles = Integer.getInteger("probe.sweep.cycles", 900);
        for (String mapPath : maps) {
            Loaded loaded = load(data, mapPath);
            World world = loaded.world();
            int w = world.map().width();
            int h = world.map().height();
            int players = world.fog().playerCount();

            boolean[][] previous = new boolean[players][];
            for (int p = 0; p < players; p++) {
                previous[p] = snapshot(world, p, w, h);
            }
            long[] flips = new long[players];
            long moves = 0;
            long flipsWhileStill = 0;
            int leakCycles = 0;
            String firstLeak = null;
            Map<Integer, Track> tracks = new LinkedHashMap<>();
            for (int i = 0; i < cycles; i++) {
                loaded.mission().tick();
                int moved = observeUnits(world, tracks);
                moves += moved;
                String bad = leak(world);
                if (bad != null) {
                    leakCycles++;
                    if (firstLeak == null) {
                        firstLeak = "cycle " + world.cycle() + ": " + bad;
                    }
                }
                for (int p = 0; p < players; p++) {
                    boolean[] now = snapshot(world, p, w, h);
                    for (int index = 0; index < now.length; index++) {
                        if (now[index] != previous[p][index]) {
                            flips[p]++;
                            if (moved == 0) {
                                flipsWhileStill++;
                            }
                        }
                    }
                    previous[p] = now;
                }
            }
            StringBuilder perPlayer = new StringBuilder();
            for (int p = 0; p < players; p++) {
                if (flips[p] > 0) {
                    perPlayer.append(" p").append(p).append('=').append(flips[p]);
                }
            }
            System.out.println(mapPath + "  " + loaded.pud().tileset()
                    + "  tileMoves=" + moves
                    + "  flips:" + (perPlayer.length() == 0 ? " none" : perPlayer)
                    + "  flipsOnCyclesWithNoMovement=" + flipsWhileStill
                    + "  countDriftCycles=" + leakCycles
                    + (firstLeak == null ? "" : " (" + firstLeak + ")"));
            for (Track track : tracks.values()) {
                if (track.rangeChanges > 0 || track.disagreements > 0) {
                    System.out.println("    " + track.label + " p" + track.player
                            + " rangeChanges=" + track.rangeChanges
                            + " disagreeCycles=" + track.disagreements
                            + " " + track.worstDisagreement);
                }
            }
        }
    }

    /**
     * The timeline of one player's fog on one map: every cycle on which a
     * square changed, and every change in what any unit had marked, side by
     * side. Whatever moves the fog with nothing walking has to appear here.
     */
    @Test
    @DisplayName("probe: timeline of the flips that happen with nothing moving")
    void timeline() {
        GameData data = data();
        String mapPath = System.getProperty("probe.map", "campaigns/orc/level06o");
        Loaded loaded = load(data, mapPath);
        World world = loaded.world();
        int w = world.map().width();
        int h = world.map().height();
        int watch = Integer.getInteger("probe.player", 3);
        System.out.println("=== timeline " + mapPath + " player " + watch);

        System.out.println("before the first tick, leak: " + leak(world));
        for (Unit unit : world.unitsSnapshot()) {
            int want = sightRangeOf(world, unit);
            if (want != unit.markedSightRange()) {
                System.out.println("  disagrees at load: " + describe(unit)
                        + " wants " + want + ", marked " + unit.markedSightRange());
            }
        }

        boolean[] previous = snapshot(world, watch, w, h);
        Map<Integer, String> seen = new LinkedHashMap<>();
        Map<Integer, Integer> marked = new LinkedHashMap<>();
        Map<Integer, int[]> where = new LinkedHashMap<>();
        for (Unit unit : world.unitsSnapshot()) {
            seen.put(unit.id(), describe(unit));
            marked.put(unit.id(), unit.markedSightRange());
            where.put(unit.id(), new int[] {unit.tileX(), unit.tileY()});
        }
        Map<Integer, Integer> owner = new LinkedHashMap<>();
        for (Unit unit : world.unitsSnapshot()) {
            owner.put(unit.id(), unit.player());
        }
        int cycles = Integer.getInteger("probe.cycles.timeline", 900);
        int printed = 0;
        int[] byPhase = new int[World.CYCLES_PER_SECOND];
        int flipCycles = 0;
        long totalFlips = 0;
        for (int i = 0; i < cycles; i++) {
            loaded.mission().tick();
            boolean[] now = snapshot(world, watch, w, h);
            List<String> changed = new ArrayList<>();
            int count = 0;
            for (int index = 0; index < now.length; index++) {
                if (now[index] != previous[index]) {
                    count++;
                    if (changed.size() < 6) {
                        changed.add((now[index] ? "+" : "-")
                                + "(" + index % w + "," + index / w + ")");
                    }
                }
            }
            previous = now;
            if (count > 0) {
                flipCycles++;
                totalFlips += count;
                byPhase[(int) (world.cycle() % World.CYCLES_PER_SECOND)]++;
            }

            List<String> events = new ArrayList<>();
            Map<Integer, Boolean> present = new LinkedHashMap<>();
            for (Unit unit : world.unitsSnapshot()) {
                present.put(unit.id(), true);
                String label = describe(unit);
                if (!seen.containsKey(unit.id())) {
                    events.add("born " + label);
                }
                Integer wasOwner = owner.get(unit.id());
                if (wasOwner != null && wasOwner != unit.player()) {
                    events.add("changed hands p" + wasOwner + "->p" + unit.player()
                            + " " + label);
                }
                Integer was = marked.get(unit.id());
                if (was == null || was != unit.markedSightRange()) {
                    int[] old = where.get(unit.id());
                    boolean movedHere = old != null
                            && (old[0] != unit.tileX() || old[1] != unit.tileY());
                    events.add("marked " + was + "->" + unit.markedSightRange()
                            + " " + label + (movedHere ? " (moved)" : " (did not move)"));
                }
                seen.put(unit.id(), label);
                owner.put(unit.id(), unit.player());
                marked.put(unit.id(), unit.markedSightRange());
                where.put(unit.id(), new int[] {unit.tileX(), unit.tileY()});
            }
            for (Map.Entry<Integer, String> entry : List.copyOf(seen.entrySet())) {
                if (!present.containsKey(entry.getKey())) {
                    events.add("gone " + entry.getValue());
                    seen.remove(entry.getKey());
                    owner.remove(entry.getKey());
                    marked.remove(entry.getKey());
                    where.remove(entry.getKey());
                }
            }
            if ((count > 0 || !events.isEmpty()) && printed < 80) {
                printed++;
                System.out.println("cycle " + world.cycle()
                        + " (%30=" + world.cycle() % 30 + ")  fogFlips=" + count
                        + " " + changed + "  events:" + events);
            }
        }
        System.out.println("cycles with a flip: " + flipCycles + " of " + cycles
                + ", total flips " + totalFlips);
        StringBuilder phases = new StringBuilder();
        for (int i = 0; i < byPhase.length; i++) {
            if (byPhase[i] > 0) {
                phases.append(" cycle%30=").append(i).append(':').append(byPhase[i]);
            }
        }
        System.out.println("when they happened:" + (phases.length() == 0 ? " never" : phases));
    }

    private static String describe(Unit unit) {
        return (unit.type() == null ? "?" : unit.type().ident())
                + "/p" + unit.player() + "@" + unit.tileX() + "," + unit.tileY()
                + "/" + unit.order()
                + (unit.isOnMap() ? "" : "/off")
                + (unit.isAlive() ? "" : "/dead")
                + (unit.isDying() ? "/dying" : "");
    }

    // ------------------------------------------------------------------
    // The same, but with the map actually being played.
    // ------------------------------------------------------------------

    @Test
    @DisplayName("probe: fog flips after a busy stretch of play on a wasteland map")
    void afterPlay() {
        busy(data(), "campaigns/orc/level06o");
    }

    @Test
    @DisplayName("probe: fog flips after a busy stretch of play on a human map")
    void afterPlayHuman() {
        busy(data(), "campaigns/human/level06h");
    }

    /**
     * Marches everything at everything, lets it settle, then watches a still
     * map. The reported symptom is a flicker with nothing moving, and a world
     * that has never moved is not the same world as one that has stopped.
     */
    private void busy(GameData data, String mapPath) {
        Loaded loaded = load(data, mapPath);
        World world = loaded.world();
        int w = world.map().width();
        int h = world.map().height();
        int player = loaded.player();
        System.out.println("=== busy " + mapPath + "  player " + player);

        // Every mobile unit on the map heads for the middle, which crosses the
        // armies over each other and starts fights.
        int ordered = 0;
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.type() == null || unit.type().building() || unit.type().speed() <= 0) {
                continue;
            }
            if (world.orderMove(unit, w / 2, h / 2)) {
                ordered++;
            }
        }
        System.out.println("ordered " + ordered + " units to the middle");

        String firstLeak = null;
        long firstLeakCycle = -1;
        int leakCycles = 0;
        for (int i = 0; i < 2400; i++) {
            loaded.mission().tick();
            // Sight-changing events with nothing moving, which is exactly what
            // refreshChangedSight exists for.
            if (i == 600) {
                world.upgrades(player).complete("upgrade-ranger-scouting");
                world.upgrades(player).complete("upgrade-ranger");
                System.out.println("completed the scouting upgrades at cycle " + world.cycle());
            }
            String bad = leak(world);
            if (bad != null) {
                leakCycles++;
                if (firstLeak == null) {
                    firstLeak = bad;
                    firstLeakCycle = world.cycle();
                }
            }
        }
        System.out.println("cycles during play whose counts did not match the units: "
                + leakCycles + " of 2400"
                + (firstLeak == null ? "" : "; first at cycle " + firstLeakCycle
                        + ": " + firstLeak));

        // Now stop everything and watch a still map.
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && !unit.type().building()) {
                world.orderStop(unit);
            }
        }
        for (int i = 0; i < 60; i++) {
            loaded.mission().tick();
        }

        boolean[] previous = snapshot(world, player, w, h);
        int[] flips = new int[w * h];
        Map<Integer, List<Long>> when = new LinkedHashMap<>();
        Map<Integer, Track> tracks = new LinkedHashMap<>();
        int quiet = 0;
        int quietFlips = 0;
        int quietLeaks = 0;
        for (int i = 0; i < CYCLES; i++) {
            loaded.mission().tick();
            int moved = observeUnits(world, tracks);
            if (moved == 0) {
                quiet++;
            }
            if (leak(world) != null) {
                quietLeaks++;
            }
            boolean[] now = snapshot(world, player, w, h);
            for (int index = 0; index < now.length; index++) {
                if (now[index] != previous[index]) {
                    flips[index]++;
                    if (moved == 0) {
                        quietFlips++;
                    }
                    when.computeIfAbsent(index, k -> new ArrayList<>()).add(world.cycle());
                }
            }
            previous = now;
        }
        System.out.println("after settling: " + quiet + " of " + CYCLES
                + " cycles had nothing move; " + quietFlips
                + " flips happened on those cycles; " + quietLeaks
                + " cycles had counts that did not match the units");
        report(world, w, h, flips, when, tracks, player);
    }

    /**
     * The reported symptom, stated as tightly as it can be: one of my units
     * stands still, an orc stands still in front of it, and the question is
     * whether the orc is drawn on every cycle or only on some of them.
     *
     * <p>An orc is placed at every distance from one to twelve so that the rim
     * of the sight disc -- where a square is lit by one unit and one square of
     * shape -- is covered as well as the middle.
     */
    @Test
    @DisplayName("probe: a still orc watched by a still grunt never blinks")
    void aStillEnemyNeverBlinks() {
        GameData data = data();
        Loaded loaded = load(data, "campaigns/human/level06h");
        World world = loaded.world();
        int player = loaded.player();
        int enemy = firstEnemyOf(world, player);
        System.out.println("=== still watcher on campaigns/human/level06h, player " + player
                + " watching player " + enemy);

        UnitType watcher = data.unitTypes().types().get("unit-footman");
        UnitType orc = data.unitTypes().types().get("unit-grunt");
        int[] spot = clearStrip(world, 30);
        Assumptions.assumeTrue(spot != null, "no clear strip on this map");
        Unit mine = world.createUnit(watcher, player, spot[0], spot[1]);
        Assumptions.assumeTrue(mine != null, "could not place the watcher");
        System.out.println("watcher " + describe(mine)
                + " sees " + sightRangeOf(world, mine) + " squares");

        List<Unit> orcs = new ArrayList<>();
        for (int distance = 1; distance <= 12; distance++) {
            Unit placed = world.createUnit(orc, enemy, spot[0] + distance, spot[1]);
            if (placed != null) {
                orcs.add(placed);
            }
        }
        Assumptions.assumeTrue(orcs.size() > 6, "could not place the orcs");
        // Nobody is to attack anybody: the question is about drawing, not
        // about combat, and a dead orc is invisible for good reason.
        for (Unit unit : world.unitsSnapshot()) {
            unit.setOrder(Unit.Order.STAND_GROUND);
        }
        world.tick();

        Map<Integer, Boolean> was = new LinkedHashMap<>();
        Map<Integer, Integer> blinks = new LinkedHashMap<>();
        for (Unit unit : orcs) {
            was.put(unit.id(), visibleTo(world, player, unit));
            blinks.put(unit.id(), 0);
        }
        int cycles = 3600;
        for (int i = 0; i < cycles; i++) {
            loaded.mission().tick();
            for (Unit unit : orcs) {
                boolean now = visibleTo(world, player, unit);
                if (now != was.get(unit.id())) {
                    blinks.put(unit.id(), blinks.get(unit.id()) + 1);
                    was.put(unit.id(), now);
                }
            }
        }
        for (Unit unit : orcs) {
            System.out.println("  orc at distance "
                    + (unit.tileX() - mine.tileX()) + ": visible=" + was.get(unit.id())
                    + " blinks over " + cycles + " cycles = " + blinks.get(unit.id())
                    + "  (moved to " + unit.tileX() + "," + unit.tileY()
                    + ", order " + unit.order() + ")");
        }
    }

    private static boolean visibleTo(World world, int player, Unit unit) {
        int w = Math.max(1, unit.type().tileWidth());
        int h = Math.max(1, unit.type().tileHeight());
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (world.fog().isVisible(player, unit.tileX() + x, unit.tileY() + y)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static int firstEnemyOf(World world, int player) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.player() != player && unit.player() < 8) {
                return unit.player();
            }
        }
        return player == 0 ? 1 : 0;
    }

    /** A run of empty walkable land, so the watcher and its orcs all fit. */
    private static int[] clearStrip(World world, int length) {
        for (int y = 2; y < world.map().height() - 2; y++) {
            for (int x = 2; x + length < world.map().width() - 2; x++) {
                boolean clear = true;
                for (int i = 0; i < length && clear; i++) {
                    var field = world.map().fieldOrNull(x + i, y);
                    clear = field != null && field.isLandPassable() && !field.isOccupied();
                }
                if (clear) {
                    return new int[] {x, y};
                }
            }
        }
        return null;
    }

    /**
     * The one shape of never-settling that {@code refreshChangedSight} cannot
     * escape: {@code markSight} writes a marked range of zero whenever the
     * effective range is zero <em>or less</em>, so a unit whose effective range
     * is negative disagrees with its own mark for ever and is torn down and
     * rebuilt once a second until the map ends.
     *
     * <p>This looks for any type or upgrade that could produce one.
     */
    @Test
    @DisplayName("probe: can any unit's effective sight range be zero or less")
    void sightRangesThatCannotSettle() {
        GameData data = data();
        int zero = 0;
        int negative = 0;
        for (UnitType type : data.unitTypes().types().values()) {
            if (type.sightRange() < 0) {
                negative++;
                System.out.println("  negative base sight: " + type.ident()
                        + " = " + type.sightRange());
            } else if (type.sightRange() == 0) {
                zero++;
                System.out.println("  zero base sight: " + type.ident());
            }
        }
        System.out.println("unit types with zero base sight: " + zero
                + ", with negative: " + negative);

        int lowering = 0;
        for (var upgrade : data.upgrades().upgrades().all().values()) {
            int change = upgrade.change(
                    net.chonkbase.chonkcraft.engine.upgrade.Upgrade.Stat.SIGHT_RANGE);
            if (change < 0) {
                lowering++;
                System.out.println("  upgrade lowering sight: " + upgrade.ident()
                        + " by " + change);
            }
        }
        System.out.println("upgrades that lower sight range: " + lowering);
    }

    /**
     * What a reader on another thread sees while the simulation runs.
     *
     * <p>The fog is not oscillating between ticks -- the rest of this file
     * establishes that -- but it is torn down and rebuilt <em>within</em> one.
     * Every {@code markSight(unit, false)} followed by
     * {@code markSight(unit, true)} leaves a window in which that unit's whole
     * sight disc is counted at zero, and the desktop paints from the event
     * thread while {@code FixedStepLoop} ticks the world on its own. A paint
     * landing in that window draws the hole.
     *
     * <p>So: tick on this thread, count lit squares from another as fast as it
     * can go, and compare what the reader saw with what was actually true at
     * the end of every tick. A reader that only ever sees settled values will
     * report nothing below the settled minimum.
     */
    @Test
    @DisplayName("probe: what a reader on another thread sees mid-tick")
    void tornReads() throws InterruptedException {
        GameData data = data();
        Loaded loaded = load(data, "campaigns/human/level05h");
        World world = loaded.world();
        int player = loaded.player();
        int w = world.map().width();
        int h = world.map().height();

        int ordered = 0;
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.player() == player && unit.type() != null && !unit.type().building()
                    && unit.type().speed() > 0 && world.orderMove(unit, w / 2, h / 2)) {
                ordered++;
            }
        }
        System.out.println("=== torn reads: " + ordered + " of player " + player
                + "'s units are walking");

        java.util.concurrent.atomic.AtomicBoolean running =
                new java.util.concurrent.atomic.AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger lowest =
                new java.util.concurrent.atomic.AtomicInteger(Integer.MAX_VALUE);
        java.util.concurrent.atomic.AtomicLong samples =
                new java.util.concurrent.atomic.AtomicLong();
        List<Integer> seen = java.util.Collections.synchronizedList(new ArrayList<>());
        Thread reader = new Thread(() -> {
            FogOfWar fog = world.fog();
            while (running.get()) {
                int lit = 0;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        if (fog.isVisible(player, x, y)) {
                            lit++;
                        }
                    }
                }
                samples.incrementAndGet();
                seen.add(lit);
                lowest.accumulateAndGet(lit, Math::min);
            }
        }, "fog-reader");
        reader.setDaemon(true);
        reader.start();

        int settledLow = Integer.MAX_VALUE;
        int settledHigh = 0;
        for (int i = 0; i < 1200; i++) {
            loaded.mission().tick();
            int lit = countTrue(snapshot(world, player, w, h));
            settledLow = Math.min(settledLow, lit);
            settledHigh = Math.max(settledHigh, lit);
        }
        running.set(false);
        reader.join(2000);

        System.out.println("between ticks the lit count stayed within ["
                + settledLow + ", " + settledHigh + "]");
        System.out.println("a reader on another thread took " + samples.get()
                + " samples and saw a low of " + lowest.get());
        int torn = 0;
        for (int value : List.copyOf(seen)) {
            if (value < settledLow) {
                torn++;
            }
        }
        System.out.println(lowest.get() < settledLow
                ? "  -> it caught the fog mid-teardown on " + torn + " of " + samples.get()
                        + " samples, worst " + (settledLow - lowest.get())
                        + " squares below anything settled"
                : "  -> nothing below the settled minimum");
    }

    // ------------------------------------------------------------------
    // 6. Is add/remove exactly symmetric, edges included?
    // ------------------------------------------------------------------

    @Test
    @DisplayName("probe: addSight then removeSight leaves the counts as they were")
    void addRemoveIsSymmetric() {
        int failures = 0;
        int cases = 0;
        StringBuilder report = new StringBuilder();
        for (int size : new int[] {16, 17, 33}) {
            for (int uw = 1; uw <= 4; uw++) {
                for (int uh = 1; uh <= 4; uh++) {
                    for (int range = 0; range <= 9; range++) {
                        for (int y = 0; y < size + 3; y++) {
                            for (int x = -3; x < size + 3; x++) {
                                cases++;
                                FogOfWar fog = new FogOfWar(size, size, 2);
                                // A background of five everywhere, so an
                                // over-decrement is visible rather than being
                                // swallowed by the "if > 0" guard in remove.
                                short[] counts = counts(fog);
                                java.util.Arrays.fill(counts, (short) 5);
                                short[] before = counts.clone();
                                fog.addSight(0, x, y, uw, uh, range);
                                fog.removeSight(0, x, y, uw, uh, range);
                                String bad = firstDifference(before, counts, size);
                                if (bad != null) {
                                    failures++;
                                    if (report.length() < 3000) {
                                        report.append("  map ").append(size)
                                                .append(" unit ").append(uw).append('x')
                                                .append(uh)
                                                .append(" at (").append(x).append(',')
                                                .append(y).append(") range ").append(range)
                                                .append(": ").append(bad).append('\n');
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.println("add/remove asymmetries: " + failures + " of " + cases + " cases");
        System.out.print(report);
    }

    private static String firstDifference(short[] before, short[] after, int width) {
        for (int i = 0; i < before.length; i++) {
            if (before[i] != after[i]) {
                return "tile (" + (i % width) + "," + (i / width) + ") was " + before[i]
                        + " now " + after[i];
            }
        }
        return null;
    }

    /**
     * The same, but with the counters starting at zero, which is how the game
     * actually meets them. Here an over-decrement is hidden by the guard in
     * removeSight and shows up instead as an under-decrement: a square left
     * lit forever.
     */
    @Test
    @DisplayName("probe: addSight then removeSight from zero returns to zero")
    void addRemoveFromZeroReturnsToZero() {
        int failures = 0;
        StringBuilder report = new StringBuilder();
        for (int size : new int[] {16, 17, 33}) {
            for (int uw = 1; uw <= 4; uw++) {
                for (int uh = 1; uh <= 4; uh++) {
                    for (int range = 0; range <= 9; range++) {
                        for (int y = 0; y < size + 3; y++) {
                            for (int x = -3; x < size + 3; x++) {
                                FogOfWar fog = new FogOfWar(size, size, 2);
                                fog.addSight(0, x, y, uw, uh, range);
                                fog.removeSight(0, x, y, uw, uh, range);
                                short[] after = counts(fog);
                                for (int i = 0; i < after.length; i++) {
                                    if (after[i] != 0) {
                                        failures++;
                                        if (report.length() < 3000) {
                                            report.append("  map ").append(size)
                                                    .append(" unit ").append(uw).append('x')
                                                    .append(uh)
                                                    .append(" at (").append(x).append(',')
                                                    .append(y).append(") range ").append(range)
                                                    .append(": tile (").append(i % size)
                                                    .append(',').append(i / size)
                                                    .append(") left at ").append(after[i])
                                                    .append('\n');
                                        }
                                        i = after.length;
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        System.out.println("add-then-remove-from-zero failures: " + failures);
        System.out.print(report);
    }

    /**
     * The mismatch the engine can actually produce: add at one range and remove
     * at another, which is what {@code markSight} does when a unit's marked
     * range is stale. Counts how many squares are left lit.
     */
    @Test
    @DisplayName("probe: adding at one range and removing at another leaves residue")
    void mismatchedRangesLeaveResidue() {
        int size = 32;
        for (int[] pair : new int[][] {{4, 1}, {1, 4}, {5, 4}, {4, 5}, {9, 1}}) {
            FogOfWar fog = new FogOfWar(size, size, 2);
            fog.addSight(0, 16, 16, 1, 1, pair[0]);
            fog.removeSight(0, 16, 16, 1, 1, pair[1]);
            int left = 0;
            for (short value : counts(fog)) {
                if (value != 0) {
                    left++;
                }
            }
            System.out.println("added at " + pair[0] + ", removed at " + pair[1]
                    + ": " + left + " squares left non-zero");
        }
    }

    // ------------------------------------------------------------------
    // The reference-count invariant, which is what oscillation would break.
    // ------------------------------------------------------------------

    /**
     * Rebuilds the visible counts from scratch out of what every unit says it
     * has marked, and compares them with what the fog actually holds.
     *
     * <p>This is the invariant the whole scheme rests on: the count on a square
     * is exactly the number of units whose marked sight covers it. Every leak
     * -- a double add, a remove at the wrong range, a remove after the unit has
     * already moved -- shows up here as a difference, whatever produced it.
     *
     * @return a description of the first square that disagrees, or null
     */
    private static String leak(World world) {
        int w = world.map().width();
        int h = world.map().height();
        int players = world.fog().playerCount();
        FogOfWar expected = new FogOfWar(w, h, players);
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.type() == null || unit.markedSightRange() <= 0) {
                continue;
            }
            expected.addSight(unit.player(), unit.tileX(), unit.tileY(),
                    Math.max(1, unit.type().tileWidth()),
                    Math.max(1, unit.type().tileHeight()),
                    unit.markedSightRange());
        }
        for (int player = 0; player < players; player++) {
            short[] want = counts(expected, player);
            short[] have = counts(world.fog(), player);
            for (int i = 0; i < want.length; i++) {
                if (want[i] != have[i]) {
                    return "player " + player + " tile (" + (i % w) + "," + (i / w)
                            + "): units account for " + want[i] + ", fog holds " + have[i];
                }
            }
        }
        return null;
    }

    /**
     * A negative tile Y makes {@code forEachInSight} run away.
     *
     * <p>{@code minY = Math.max(-range, -tileY)} is meant to clip the top of
     * the disc to the map, and for a tile Y at or above zero it is negative or
     * zero as the loop below it assumes. Feed it a negative tile Y and it comes
     * out positive, and {@code for (offsetY = minY; offsetY != 0; offsetY++)}
     * then counts upward away from zero until the multiplication overflows --
     * four thousand million iterations, not an exception.
     *
     * <p>Nothing in the engine passes one today, so this is latent rather than
     * the reported bug; it is here because it is what made the exhaustive sweep
     * above hang, and because {@code < 0} rather than {@code != 0} costs
     * nothing.
     */
    @Test
    @DisplayName("probe: a negative tile Y runs away in forEachInSight")
    void negativeTileYRunsAway() {
        Thread worker = new Thread(() -> {
            FogOfWar fog = new FogOfWar(32, 32, 2);
            fog.addSight(0, 5, -1, 1, 1, 4);
        });
        worker.setDaemon(true);
        long started = System.nanoTime();
        worker.start();
        try {
            worker.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        long millis = (System.nanoTime() - started) / 1_000_000;
        System.out.println("addSight at tileY = -1 " + (worker.isAlive()
                ? "was still running after " + millis + "ms"
                : "returned after " + millis + "ms"));
    }

    private static final java.lang.reflect.Field VISIBLE = visibleField();

    private static java.lang.reflect.Field visibleField() {
        try {
            java.lang.reflect.Field field = FogOfWar.class.getDeclaredField("visible");
            field.setAccessible(true);
            return field;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Player zero's live count array. {@code isVisible} only says "greater than
     * zero", which cannot tell a leak from a level, so the counter itself is
     * what has to be read.
     */
    private static short[] counts(FogOfWar fog) {
        return counts(fog, 0);
    }

    private static short[] counts(FogOfWar fog, int player) {
        try {
            return ((short[][]) VISIBLE.get(fog))[player];
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
