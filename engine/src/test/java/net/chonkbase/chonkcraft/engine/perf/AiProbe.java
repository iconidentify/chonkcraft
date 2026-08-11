package net.chonkbase.chonkcraft.engine.perf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.ai.AiForce;
import net.chonkbase.chonkcraft.engine.ai.AiPlayer;
import net.chonkbase.chonkcraft.engine.campaign.Campaign;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * Does a computer player actually do anything?
 *
 * <p>It had been established that all seventy-nine personalities run the
 * script their map names and that none throws in fifteen seconds. That is a
 * different question from whether any of them plays, and an AI that runs clean
 * and builds nothing is worse than one that throws, because nothing reports
 * it. This is what asks.
 *
 * <p>Run {@link #main} for the whole campaign -- fifty-two missions, five
 * simulated minutes each, about ten seconds of wall time -- when the full
 * table is wanted. {@link AiCompetenceTest} pins a subset of it.
 *
 * <p>One measurement decision is worth keeping in view, because getting it
 * wrong reverses the answer. Unit orders are sampled every <em>cycle</em>, not
 * every second. The AI thinks at one hertz, so a once-a-second sample beats
 * against its own cadence: when the engine was still throwing an attack order
 * away within the same second it was given, sampling on the second reported
 * either that everything attacked all the time or that nothing ever did,
 * depending on which side of the AI's own tick it landed, and both are
 * believable. The per-cycle ratio of attacking cycles to mobilized seconds is
 * what exposed that: a ratio of exactly 1.00 means the order lasted one cycle.
 */
public final class AiProbe {

    private AiProbe() {
    }

    /** The three resources worth watching; food and score are not spent. */
    private static final UnitType.Resource[] RESOURCES = {
        UnitType.Resource.GOLD, UnitType.Resource.WOOD, UnitType.Resource.OIL,
    };

    /** What one computer-player slot did over the run. */
    public static final class Slot {
        public String mission = "?";
        public int index;
        public String script = "?";
        public int unitsStart;
        public int unitsEnd;
        /** Units that appeared during the run: trained, built or summoned. */
        public int created;
        public final Map<String, Integer> createdTypes = new TreeMap<>();
        public final int[] resourcesStart = new int[3];
        public final int[] resourcesEnd = new int[3];
        public final int[] spent = new int[3];
        /** Positive treasury edges observed while the simulation ran. */
        public final int[] credited = new int[3];
        /** Cycles in which at least one worker harvested or returned a load. */
        public int harvestCycles;
        /** Cycles in which at least one of its units was attacking. */
        public int attackCycles;
        public int mostAttackersAtOnce;
        /** Cycles with a member assigned retail ai.bin behavior two. */
        public int battleNetForceCycles;
        /** Seconds in which a force with members had left the gathering state. */
        public int mobilizedSeconds;
        /**
         * Upgrades that finished during the run.
         *
         * <p>The whole of issue 54 is measured here. {@code AiResearch} and
         * {@code AiUpgradeTo} were bound to a function that returned false, so
         * this figure and the next were nought in every one of the hundred and
         * fourteen slots, and nothing in the probe said so.
         */
        public int researched;
        public final Map<String, Integer> researchedIdents = new TreeMap<>();
        /**
         * Researches begun, which is the number of times one was paid for.
         *
         * <p>Kept beside {@code researched} because the gap between the two is
         * a fault and not a rounding: a research request is a standing one,
         * asked again every second until it is had, and every idle building it
         * is offered to pays the full price. Anything above one start per
         * upgrade is the same upgrade bought twice.
         */
        public int researchStarts;
        /** Buildings that became a better building: a keep, a guard tower. */
        public int improved;
        public final Map<String, Integer> improvedInto = new TreeMap<>();
        /** Building upgrades begun, and so paid for, on the same footing. */
        public int improveStarts;

        public boolean grew() {
            return unitsEnd > unitsStart;
        }

        public boolean built() {
            return created > 0;
        }

        public boolean spentAnything() {
            return spent[0] > 0 || spent[1] > 0 || spent[2] > 0;
        }

        public boolean attacked() {
            return attackCycles > 0;
        }

        /** Whether it researched anything at all. */
        public boolean researchedAnything() {
            return researched > 0;
        }

        /** Whether it turned any building into a better one. */
        public boolean improvedAnything() {
            return improved > 0;
        }

        /** Nothing whatever: it did not grow, spend, build or fight. */
        public boolean inert() {
            return !grew() && !built() && !spentAnything() && !attacked();
        }

        public int goldSpent() {
            return spent[0];
        }

        @Override
        public String toString() {
            return mission + " slot " + index + " (" + script + ")";
        }
    }

    /** Every mission the campaign scripts name, in campaign order. */
    public static List<String> missionPaths(GameData data) {
        List<String> paths = new ArrayList<>();
        for (Campaign campaign : data.campaigns()) {
            for (var step : campaign.missions()) {
                paths.add(step.mapArchivePath());
            }
        }
        return paths;
    }

    /**
     * Whether a unit appeared because something died rather than because
     * somebody built it.
     *
     * <p>Every death animation in the shipped data opens with
     * {@code spawn-unit unit-dead-vision-<size>-<sight>}, which briefly reveals
     * where a unit fell, and the revealer belongs to the dying unit's owner.
     * Corpses arrive the same way through the CorpseType chain.
     *
     * <p>Counting those as things the player built is wrong in the direction
     * that matters: a garrison being wiped out looked like a garrison that had
     * started building. It said so, too -- when the AI first learned to send a
     * force at a rescuable town, this counter reported the victim as having
     * "built something" fourteen times, and every one of the fourteen was a
     * revealer over one of its own dead.
     */
    private static boolean isSpawnedByDying(Unit unit) {
        String ident = unit.type() == null ? "" : unit.type().ident();
        return ident.contains("dead-vision") || ident.contains("dead-body")
                || ident.contains("destroyed-");
    }

    private static int countOwned(World world, int player) {
        int count = 0;
        for (Unit unit : world.units()) {
            if (unit.isAlive() && unit.player() == player) {
                count++;
            }
        }
        return count;
    }

    /**
     * Plays one mission headless and reports what each computer player did.
     *
     * @param cycles how long to play, in simulation cycles
     * @return one entry per thinking slot, empty if the mission will not load
     */
    public static List<Slot> measure(GameData data, String path, int cycles) {
        Mission mission;
        try {
            mission = data.loadMission(path);
        } catch (RuntimeException broken) {
            return List.of();
        }
        if (mission == null) {
            return List.of();
        }
        World world = mission.world();

        Map<Integer, Slot> slots = new LinkedHashMap<>();
        for (Map.Entry<Integer, AiPlayer> entry : world.ais().entrySet()) {
            Slot slot = new Slot();
            slot.mission = path;
            slot.index = entry.getKey();
            slot.script = "retail-ai.bin:" + entry.getValue().battleNetBuildProfileId();
            Player player = world.player(slot.index);
            for (int r = 0; r < RESOURCES.length; r++) {
                slot.resourcesStart[r] = player.get(RESOURCES[r]);
            }
            slot.unitsStart = countOwned(world, slot.index);
            slots.put(slot.index, slot);
        }
        if (slots.isEmpty()) {
            return List.of();
        }

        // Everything alive before the run, by identity, so anything seen later
        // that is not in here was created during it. By identity and not by
        // count, because a slot that loses two units and trains two more has an
        // unchanged count and has plainly been playing.
        Set<Unit> preexisting = Collections.newSetFromMap(new IdentityHashMap<>());
        preexisting.addAll(world.units());
        Set<Unit> alreadyCounted = Collections.newSetFromMap(new IdentityHashMap<>());

        Map<Integer, int[]> previous = new LinkedHashMap<>();
        Map<Integer, int[]> previousEveryCycle = new LinkedHashMap<>();
        for (Slot slot : slots.values()) {
            previous.put(slot.index, slot.resourcesStart.clone());
            previousEveryCycle.put(slot.index, slot.resourcesStart.clone());
        }

        // What each side had researched before the run, so a campaign that
        // starts a mission with a tier already granted is not counted as work
        // the AI did.
        Map<Integer, Set<String>> researchedBefore = new LinkedHashMap<>();
        for (Slot slot : slots.values()) {
            researchedBefore.put(slot.index, Set.copyOf(world.upgrades(slot.index).researched()));
        }
        // The type each unit was last seen as. A building that becomes a better
        // building keeps its identity and changes its type, which is exactly
        // what AiUpgradeTo is for and the only way to see it happen.
        Map<Unit, String> lastType = new IdentityHashMap<>();
        // What each building was working on last cycle, so the moment one
        // starts -- and is charged for -- can be counted.
        Map<Unit, String> lastResearching = new IdentityHashMap<>();
        Map<Unit, String> lastUpgradingTo = new IdentityHashMap<>();

        for (int cycle = 0; cycle < cycles; cycle++) {
            mission.tick();
            Map<Integer, Integer> attackers = new LinkedHashMap<>();
            java.util.Set<Integer> harvesters = new java.util.HashSet<>();
            java.util.Set<Integer> battleNetForces = new java.util.HashSet<>();
            for (Unit unit : world.units()) {
                if (!unit.isAlive()) {
                    continue;
                }
                Slot slot = slots.get(unit.player());
                if (slot == null) {
                    continue;
                }
                if (!preexisting.contains(unit) && alreadyCounted.add(unit)
                        && !isSpawnedByDying(unit)) {
                    slot.created++;
                    slot.createdTypes.merge(
                            unit.type() == null ? "?" : unit.type().ident(), 1, Integer::sum);
                }
                // Both forms of the attack order count: upstream files a
                // march at a position and a swing at a unit under the one
                // COrder_Attack, and the AI's launch hands out the
                // position form.
                if (unit.order() == Unit.Order.ATTACK
                        || unit.order() == Unit.Order.ATTACK_MOVE
                        || unit.order() == Unit.Order.ATTACK_GROUND) {
                    attackers.merge(unit.player(), 1, Integer::sum);
                }
                if (unit.order() == Unit.Order.HARVEST
                        || unit.order() == Unit.Order.RETURN_GOODS) {
                    harvesters.add(unit.player());
                }
                if (unit.battleNetAiBehavior() == 2) {
                    battleNetForces.add(unit.player());
                }
                String ident = unit.type() == null ? "?" : unit.type().ident();
                String was = lastType.put(unit, ident);
                if (was != null && !was.equals(ident)) {
                    slot.improved++;
                    slot.improvedInto.merge(ident, 1, Integer::sum);
                }
                String research = unit.researching();
                String wasResearching = lastResearching.put(unit, research);
                if (research != null && !research.equals(wasResearching)) {
                    slot.researchStarts++;
                }
                String becoming = unit.upgradingTo() == null ? null : unit.upgradingTo().ident();
                String wasBecoming = lastUpgradingTo.put(unit, becoming);
                if (becoming != null && !becoming.equals(wasBecoming)) {
                    slot.improveStarts++;
                }
            }
            for (Slot slot : slots.values()) {
                int now = attackers.getOrDefault(slot.index, 0);
                if (now > 0) {
                    slot.attackCycles++;
                    slot.mostAttackersAtOnce = Math.max(slot.mostAttackersAtOnce, now);
                }
                if (harvesters.contains(slot.index)) {
                    slot.harvestCycles++;
                }
                if (battleNetForces.contains(slot.index)) {
                    slot.battleNetForceCycles++;
                }
                Player player = world.player(slot.index);
                int[] before = previousEveryCycle.get(slot.index);
                for (int r = 0; r < RESOURCES.length; r++) {
                    int value = player.get(RESOURCES[r]);
                    if (value > before[r]) {
                        slot.credited[r] += value - before[r];
                    }
                    before[r] = value;
                }
            }

            // Spending is sampled once a second. It is a sum of falls rather
            // than a difference of ends, because income during the run would
            // otherwise hide it: a slot that earns nine hundred and spends a
            // thousand shows a treasury that barely moved.
            if (cycle % World.CYCLES_PER_SECOND != 0) {
                continue;
            }
            for (Slot slot : slots.values()) {
                for (AiForce force : world.ais().get(slot.index).forces()) {
                    if (!force.members().isEmpty()
                            && force.state() != AiForce.State.GATHERING) {
                        slot.mobilizedSeconds++;
                        break;
                    }
                }
                Player player = world.player(slot.index);
                int[] before = previous.get(slot.index);
                for (int r = 0; r < RESOURCES.length; r++) {
                    int value = player.get(RESOURCES[r]);
                    if (value < before[r]) {
                        slot.spent[r] += before[r] - value;
                    }
                    before[r] = value;
                }
            }
        }

        for (Slot slot : slots.values()) {
            Player player = world.player(slot.index);
            for (String ident : world.upgrades(slot.index).researched()) {
                if (!researchedBefore.get(slot.index).contains(ident)) {
                    slot.researched++;
                    slot.researchedIdents.merge(ident, 1, Integer::sum);
                }
            }
            slot.unitsEnd = countOwned(world, slot.index);
            for (int r = 0; r < RESOURCES.length; r++) {
                slot.resourcesEnd[r] = player.get(RESOURCES[r]);
            }
        }
        return List.copyOf(slots.values());
    }

    /** Plays the whole campaign and prints a slot per line, tab separated. */
    public static void main(String[] args) {
        GameData data = SimulationProfile.load();
        if (data == null) {
            System.out.println("No Warcraft II installation configured.");
            return;
        }
        int seconds = Integer.getInteger("probe.seconds", 300);
        int cycles = World.CYCLES_PER_SECOND * seconds;
        List<String> paths = args.length > 0 ? List.of(args) : missionPaths(data);
        System.out.println("# " + paths.size() + " missions, " + seconds
                + " simulated seconds each");
        System.out.println(String.join("\t", "mission", "slot", "script", "unitsStart",
                "unitsEnd", "created", "goldSpent", "woodSpent", "oilSpent", "attackCycles",
                "harvestCycles", "goldCredited", "woodCredited", "oilCredited",
                "battleNetForceCycles", "mobilizedSeconds", "researched", "researchStarts",
                "improved", "improveStarts",
                "createdTypes", "researchedIdents",
                "improvedInto", "error"));

        List<Slot> all = new ArrayList<>();
        for (String path : paths) {
            List<Slot> slots = measure(data, path, cycles);
            if (slots.isEmpty()) {
                System.out.println(path + "\t-\tNO AI SLOTS OR WILL NOT LOAD");
                continue;
            }
            all.addAll(slots);
            for (Slot slot : slots) {
                System.out.println(String.join("\t", path, String.valueOf(slot.index),
                        slot.script, String.valueOf(slot.unitsStart),
                        String.valueOf(slot.unitsEnd), String.valueOf(slot.created),
                        String.valueOf(slot.spent[0]), String.valueOf(slot.spent[1]),
                        String.valueOf(slot.spent[2]), String.valueOf(slot.attackCycles),
                        String.valueOf(slot.harvestCycles), String.valueOf(slot.credited[0]),
                        String.valueOf(slot.credited[1]), String.valueOf(slot.credited[2]),
                        String.valueOf(slot.battleNetForceCycles),
                        String.valueOf(slot.mobilizedSeconds), String.valueOf(slot.researched),
                        String.valueOf(slot.researchStarts), String.valueOf(slot.improved),
                        String.valueOf(slot.improveStarts), slot.createdTypes.toString(),
                        slot.researchedIdents.keySet().toString(), slot.improvedInto.toString()));
            }
        }

        Map<String, Slot> byScript = new TreeMap<>();
        all.forEach(slot -> byScript.merge(slot.script, slot,
                (a, b) -> a.created >= b.created ? a : b));
        List<String> inert = byScript.entrySet().stream()
                .filter(entry -> entry.getValue().inert())
                .map(Map.Entry::getKey)
                .toList();
        System.out.println();
        System.out.println("slots: " + all.size() + "   personalities: " + byScript.size());
        System.out.println("built something: "
                + byScript.values().stream().filter(Slot::built).count());
        System.out.println("spent something: "
                + byScript.values().stream().filter(Slot::spentAnything).count());
        System.out.println("attacked: "
                + byScript.values().stream().filter(Slot::attacked).count());
        System.out.println("did nothing at all (" + inert.size() + "): " + inert);
        System.out.println("upgrades researched: "
                + all.stream().mapToInt(s -> s.researched).sum() + " in "
                + all.stream().filter(Slot::researchedAnything).count() + " of " + all.size()
                + " slots, paid for " + all.stream().mapToInt(s -> s.researchStarts).sum()
                + " times");
        System.out.println("buildings improved: "
                + all.stream().mapToInt(s -> s.improved).sum() + " in "
                + all.stream().filter(Slot::improvedAnything).count() + " of " + all.size()
                + " slots, paid for " + all.stream().mapToInt(s -> s.improveStarts).sum()
                + " times");
        System.out.println("slots whose lumber rose: " + all.stream()
                .filter(s -> s.resourcesEnd[1] > s.resourcesStart[1]).count() + " of " + all.size());
        System.out.println("slots whose oil rose: " + all.stream()
                .filter(s -> s.resourcesEnd[2] > s.resourcesStart[2]).count() + " of " + all.size());
    }
}
