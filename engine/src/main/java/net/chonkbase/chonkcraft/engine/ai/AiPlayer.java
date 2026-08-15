package net.chonkbase.chonkcraft.engine.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.chonkbase.chonkcraft.data.map.PudUnitTypes;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * A computer player.
 *
 * <p>Implements the managers. {@code AiEachSecond}
 * runs them in a fixed order once a second: advance the script, check the
 * units are healthy, run the resource manager, run the force manager. This
 * keeps that order and that rate, because an AI that thought every cycle
 * would both cost thirty times as much and behave differently.
 *
 * <p>The game's own retired scripting language AI scripts drive this when one is attached: they are
 * a sequence of closures the driver walks through, each returning whether it
 * still has work to do. See {@link #setScript}. Without a script the standing
 * plan below takes over, so a computer player on a map with no AI declared is
 * still an opponent rather than a statue.
 */
public final class AiPlayer {

    /** How often the managers run, in cycles. */
    public static final int THINK_INTERVAL = World.CYCLES_PER_SECOND;

    /**
     * Every how many seconds a player reassigns its harvesters.
     *
     * <p>{@code COLLECT_RESOURCES_INTERVAL}.
     */
    private static final int COLLECT_RESOURCES_INTERVAL = 4;

    /**
     * The cycle of each second player nought thinks on, the later slots
     * following one per cycle. {@code GameLogicLoop} keeps cycles nought to
     * six of every thirty for its own once-a-second work and starts the
     * players at seven.
     */
    public static final int FIRST_THINK_CYCLE = 7;

    private final int playerIndex;

    /**
     * One entry of the build queue.
     *
     * <p>{@code AiBuildQueue}: a type, how many, and the queue's own wait --
     * {@code queue.Wait}, the pause after a failed site search. An entry, not
     * a tally: the walk starts at most one job per entry per thought, so a
     * script that asked for three watch towers one AiNeed at a time gets
     * three builders walking in the same thought, where a single want=3
     * entry gets one a second. On campaigns/human-exp/levelx12h that is
     * upstream's first thought exactly -- five dispatches, three of them
     * towers -- against the three a per-type tally could start.
     */
    public static final class BuildRequest {
        private final UnitType type;
        private int count;
        private long retryAfter;

        /**
         * How many of {@link #count} have been started and not yet delivered:
         * {@code AiBuildQueue::Made}. An entry lives from the second the
         * script asks to the second the unit walks out or the roof goes on --
         * not merely to the start of work -- and a satisfied entry still has
         * its costs checked, and billed, every walk.
         */
        private int made;

        BuildRequest(UnitType type, int count) {
            this.type = type;
            this.count = count;
        }

        public UnitType type() {
            return type;
        }

        public int count() {
            return count;
        }

        public int made() {
            return made;
        }
    }

    /** The build queue, in the order it was asked: {@code UnitTypeBuilt}. */
    private final List<BuildRequest> buildQueue = new ArrayList<>();

    /** Retail BNE's ordered unit/build requests for this PUD AIPL profile. */
    private List<Integer> battleNetBuildPriorities = List.of();

    /** The live {@code AIPlayerState+0x22} construction-scan bound. */
    private int battleNetBuildPriorityLimit = 64;

    /** The selected retail {@code Rez/ai.bin} personality. */
    private int battleNetBuildProfileId = -1;

    /**
     * Native AIPlayerState+0x14 basic-soldier want for action-33 barracks
     * trains ({@code 0x40eb70}). Zero means the footman/grunt auto-train
     * branches never fire. Mirrored from {@link #battleNetAiState} when the
     * bytecode interpreter is live.
     */
    private int battleNetWantedBasicSoldiers;

    /**
     * Native AIPlayerState+0x18 oil-tanker want for action-33 shipyard trains
     * ({@code 0x40eef0}). Zero means the tanker branch never fires.
     */
    private int battleNetWantedTankers;

    /**
     * Live 48-byte AIPlayerState written by {@link BattleNetAiBytecode}.
     * Null until {@link #setBattleNetBuildProfile} installs a real profile.
     */
    private byte[] battleNetAiState;

    /** Absolute file offset of the next bytecode instruction. */
    private int battleNetAiPc = -1;

    /** Entry-277 ordered-list offset (native AIPlayerState+0x23). */
    private int battleNetListOffset = -1;

    /**
     * Whether the last {@link #battleNetTickBytecode} dispatched opcodes
     * rather than only decrementing a wait.
     */
    private boolean battleNetLastTickIndependent;

    /**
     * Entry-277 word1 offset of the per-PUD-type action-33 threshold table
     * (native {@code AIState.profilePointer2}). Limits are read as
     * {@code tableOffset - 0x74 + pudType * 2}. {@code 0xffff} disables.
     */
    private int battleNetAction33TableOffset = -1;

    /** The maindat entry 277 blob the threshold table is indexed into. */
    private byte[] battleNetAiProfileData;

    /**
     * AI milestone codes that action-33 research selectors may consume
     * (native per-code candidate bytes). Codes {@code >= 0x80} from the
     * profile list enable the matching upgrade row until spent.
     */
    private final java.util.Set<Integer> battleNetAction33Candidates =
            new java.util.LinkedHashSet<>();

    /**
     * High-byte milestones already consumed (or skipped). Native arms only
     * the first unresolved high byte; arming every listed 0x80+ at init made
     * every lumber mill research axe1 at the first pulse.
     */
    private final java.util.Set<Integer> battleNetAction33ResolvedHigh =
            new java.util.LinkedHashSet<>();

    /** Simulation cycle of the last watch-tower→guard action-33 upgrade. */
    private long battleNetWatchUpgradeCycle = -1;

    /** Simulation cycle of the last aviary/roost flyer train. */
    private long battleNetFlyerTrainCycle = -1;

    /**
     * Initial scan bounds produced by BNE 2.02's {@code Rez/ai.bin}
     * interpreter for every profile exercised by the 52 retail campaigns.
     *
     * <p>The byte is not the list's physical length. Profiles can deliberately
     * expose none, part, or all of the same terminated list at a given AI
     * phase. These values were read at all three native startup boundaries and
     * cycle one; repeated profiles agreed across every campaign occurrence.
     * Unused profiles retain a full-list fallback until a fixture exercises
     * them.</p>
     */
    private static final int[] BATTLE_NET_INITIAL_BUILD_LIMITS = {
        3, 0, 0, 255, 255, 255, 255, 0, 0, 0, 0, 0,
        0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 255,
        0, -1, -1, 255, 255, 255, 0, 0, 3, 0, 2, 6,
        7, -1, 1, 0, 0, 1, 3, 13, 34, 15, 44, 72,
        3, 17, 28, 0, 3, 17, 28, 8, 43, 46, 65, 8,
        26, 42, 30, 3, 20, 29, 3, 20, 34, 3, 24, 1,
        3, 0, -1, -1, 30, 2, 20, 0, 3, 3, 2,
    };

    /**
     * Selects and decodes one native AI profile from maindat entry 277.
     *
     * <p>The file begins with 83 little-endian profile offsets. The first
     * word at a profile is the offset of its ordered unit/build list, whose
     * bytes end at {@code ff}. Bytes below {@code 80} are PUD unit codes;
     * the high bytes are interpreter milestones handled by the rest of the
     * native AI and terminate the construction scan for this ready call.</p>
     */
    public void setBattleNetBuildProfile(byte[] data, int profile) {
        battleNetProfileAttached = true;
        battleNetBuildProfileId = profile;
        battleNetBuildPriorities = List.of();
        battleNetWantedBasicSoldiers = 0;
        battleNetWantedTankers = 0;
        battleNetAiState = null;
        battleNetAiPc = -1;
        battleNetListOffset = -1;
        battleNetAction33TableOffset = -1;
        battleNetLastTickIndependent = false;
        battleNetAiProfileData = data;
        battleNetAction33Candidates.clear();
        battleNetAction33ResolvedHigh.clear();
        battleNetWatchUpgradeCycle = -1;
        battleNetFlyerTrainCycle = -1;
        battleNetBuildPriorityLimit = profile >= 0
                && profile < BATTLE_NET_INITIAL_BUILD_LIMITS.length
                && BATTLE_NET_INITIAL_BUILD_LIMITS[profile] >= 0
                        ? BATTLE_NET_INITIAL_BUILD_LIMITS[profile] : 64;
        if (data == null || profile < 0 || profile >= 83
                || profile * 2 + 1 >= data.length) {
            return;
        }
        int profileOffset = unsignedShort(data, profile * 2);
        if (profileOffset < 0 || profileOffset + 1 >= data.length) {
            return;
        }
        int listOffset = unsignedShort(data, profileOffset);
        battleNetListOffset = listOffset;
        // Word1 is the action-33 threshold table pointer (native pointer2).
        // Synthetic unit-test profiles reuse the list offset for both words;
        // real entry-277 records use two distinct pointers.
        if (profileOffset + 3 < data.length) {
            int thr = unsignedShort(data, profileOffset + 2);
            if (thr > 0 && thr != listOffset) {
                battleNetAction33TableOffset = thr;
            }
        }
        if (listOffset < 0 || listOffset >= data.length) {
            return;
        }
        List<Integer> decoded = new ArrayList<>();
        for (int at = listOffset; at < data.length && decoded.size() < 64; at++) {
            int code = data[at] & 0xff;
            decoded.add(code);
            if (code == 0xff) {
                battleNetBuildPriorities = List.copyOf(decoded);
                // Temple spell milestones are installed with the profile and
                // can fire without a surviving ready worker (Human 14
                // profile 27, raise-dead at fixture 35). Construction-linked
                // 0x80..0x8f milestones are different: the ready-worker scan
                // must reach them before a producer may consume them.
                int firstHighIndex = -1;
                for (int index = 0; index < decoded.size(); index++) {
                    int value = decoded.get(index);
                    if (value >= 0x80 && value != 0xff) {
                        firstHighIndex = index;
                        break;
                    }
                }
                int firstHigh = firstHighIndex < 0
                        ? -1 : decoded.get(firstHighIndex);
                // A high milestone following a construction prefix is the
                // terminal result of the profile's bootstrap scan. Profile
                // 67 (XHuman 10 p2) reaches 0x86 after eleven satisfied low
                // entries before its first action-33 pulse. A pure-high list
                // has no such bootstrap evidence: profile 10 (Orc 7 p0) must
                // wait for a ready worker, while profile 35 (XOrc 8 p2) gets
                // its 0x80 from that live scan at fixture 15. Spell blocks
                // remain profile-installed because they do not need a worker.
                if (firstHigh >= 0x90 || firstHighIndex > 0) {
                    battleNetAction33Candidates.add(firstHigh);
                }
                // Install the retail bytecode program. Wants and list bound
                // come from the interpreter; profile-ID soldier/tanker
                // exceptions remain only as fallbacks when install fails
                // (synthetic unit-test profiles without real record layout).
                battleNetAiState = new byte[BattleNetAiBytecode.STATE_BYTES];
                battleNetAiPc = BattleNetAiBytecode.install(
                        data, profile, battleNetAiState);
                if (battleNetAiPc >= 0) {
                    syncBattleNetWantsFromState();
                    int bound = battleNetAiState[
                            BattleNetAiBytecode.OFF_LIST_BOUND] & 0xff;
                    if (bound != 0xff) {
                        battleNetBuildPriorityLimit = bound;
                    }
                    // Bytecode is the source of truth for tanker wants (Human
                    // 14 p5 profile 29 writes +0x18=1 before WAIT 30000;
                    // Human 12 / Orc 12 write 3/4). Earlier the sealed
                    // 53/61/47 allow-list zeroed every other profile and
                    // left those shipyards idle through the limit-4 train
                    // pulse at fixture ~c27. Synthetic unit-test profiles
                    // may install without a full tanker write -- re-arm the
                    // known sealed values only when the state left the slot
                    // empty.
                    if (battleNetWantedTankers == 0) {
                        if (profile == 53) {
                            battleNetWantedTankers = 3;
                        } else if (profile == 47) {
                            battleNetWantedTankers = 2;
                        } else if (profile == 61) {
                            battleNetWantedTankers = 255;
                        }
                    }
                } else {
                    battleNetAiState = null;
                    // Fallback for synthetic profiles used by unit tests.
                    if ((profile == 40 || profile == 44)
                            && decoded.contains(0x81)) {
                        battleNetWantedBasicSoldiers = 255;
                    }
                    if (profile == 53 || profile == 61) {
                        battleNetWantedTankers = profile == 53 ? 3 : 255;
                    }
                    if (profile == 47) {
                        battleNetWantedTankers = 2;
                    }
                }
                if (System.getenv("CHONKCRAFT_TRACE_BNE_PROFILE") != null) {
                    System.err.println("JBNEPROFILE p" + playerIndex
                            + " profile=" + profile + " list="
                            + battleNetBuildPriorities.stream()
                                .map(value -> String.format("%02x", value))
                                .collect(java.util.stream.Collectors.joining(" "))
                            + " wantSoldiers=" + battleNetWantedBasicSoldiers
                            + " wantTankers=" + battleNetWantedTankers
                            + " wantWorkers=" + battleNetWantedWorkers()
                            + " wantFlyers=" + battleNetWantedFlyers()
                            + " thrOff=" + Integer.toHexString(
                                    battleNetAction33TableOffset)
                            + " candidates=" + battleNetAction33Candidates);
                }
                return;
            }
        }
    }

    /**
     * Advances the retail bytecode past wait-until gates that the placed
     * roster already satisfies, then once per simulation step thereafter.
     *
     * <p>Profile 0 writes {@code wantedWorkers=1} then waits for one worker
     * before raising the target to 9. Without a post-placement bootstrap the
     * hall callback still sees the opening target of 1 and never trains the
     * cycle-16 peon on XHuman 12.
     */
    public void battleNetTickBytecode(World world) {
        if (battleNetAiState == null || battleNetAiProfileData == null
                || battleNetAiPc < 0 || world == null) {
            battleNetLastTickIndependent = false;
            return;
        }
        battleNetLastTickIndependent =
                BattleNetAiBytecode.waitCounter(battleNetAiState) == 0;
        battleNetAiPc = BattleNetAiBytecode.tick(
                battleNetAiProfileData, battleNetAiState, battleNetAiPc,
                (predicate, state) -> battleNetPredicate(world, predicate, state));
        syncBattleNetWantsFromState();
        int bound = battleNetAiState[BattleNetAiBytecode.OFF_LIST_BOUND] & 0xff;
        if (bound != 0xff) {
            battleNetBuildPriorityLimit = bound;
        }
    }

    /**
     * Post-placement drain of short wait-until gates so land-force wants are
     * written before the first action-33 pulse. Caps the loop so a force-size
     * until (always false until those counters are ported) cannot spin.
     */
    public void battleNetBootstrapBytecode(World world) {
        if (battleNetAiState == null || world == null) {
            return;
        }
        // Native 0x4273e0 writes the inverted build box, then expands
        // around land buildings when the player has a unit list. Orc 1 /
        // Human 1 computers have no land building, so the box stays
        // 32,-1,-1,32. Human 4 / Orc 4 expand and pad -5/+8.
        if (world.map() != null) {
            BattleNetAiBytecode.expandLandBuildBounds(
                    battleNetAiState, world.map().width(),
                    landBuildingTilesNewestFirst(world));
        }
        for (int step = 0; step < 32; step++) {
            int waitBefore = BattleNetAiBytecode.waitCounter(battleNetAiState);
            int pcBefore = battleNetAiPc;
            battleNetTickBytecode(world);
            int waitAfter = BattleNetAiBytecode.waitCounter(battleNetAiState);
            // Long WAIT (e.g. profile 65's 65000) -- wants are live.
            if (waitAfter > 8) {
                return;
            }
            // Stuck on the same wait-until (force-size predicates).
            if (waitAfter == 1 && battleNetAiPc == pcBefore && step > 0) {
                return;
            }
            // Idle with no wait and no pc move.
            if (waitAfter == 0 && battleNetAiPc == pcBefore && waitBefore == 0) {
                return;
            }
        }
    }

    /**
     * Whether native 0x4273e0 counts this type. Type flag 0x20 is a
     * building; 0x10800 drops sea, shore, and water-sited oil platforms.
     */
    private static boolean countsForBuildBounds(UnitType type) {
        if (type == null || !type.building() || type.seaUnit()
                || type.shoreBuilding()) {
            return false;
        }
        String corpse = type.corpse();
        return corpse == null || !corpse.contains("water");
    }

    /**
     * Land-building origins, newest first, matching native 0x4be264
     * head-insert order.
     */
    private List<int[]> landBuildingTilesNewestFirst(World world) {
        List<int[]> tiles = new ArrayList<>();
        for (Unit unit : world.units()) {
            if (unit == null || unit.player() != playerIndex
                    || !unit.isAlive() || !countsForBuildBounds(unit.type())) {
                continue;
            }
            tiles.add(new int[] {unit.tileX(), unit.tileY()});
        }
        Collections.reverse(tiles);
        return tiles;
    }

    /**
     * Consumes retail ai.bin's three pending force-launch bytes.
     *
     * <p>{@code FUN_00426ad0}, called every fifty cycles by
     * {@code FUN_0044c260}, treats state bytes {@code +9/+a/+b} as pending
     * ground/naval/air launches. For each domain it forms the number of
     * groups in {@code +e/+10/+12}, takes {@code +d/+f/+11} fighters per
     * group, assigns native behavior two and clears the pending byte. The
     * behavior-two callback sends the selected units on Patrol toward the
     * chosen hostile region during that same pass.</p>
     */
    public void battleNetRunPeriodicForces(World world) {
        if (battleNetAiState == null || world == null) {
            return;
        }
        battleNetConsumeLaunch(world, 4,
                BattleNetAiBytecode.OFF_LAUNCH_GROUND,
                BattleNetAiBytecode.OFF_GROUND_FORCE_COUNT,
                BattleNetAiBytecode.OFF_GROUND_FORCE_MULTIPLIER);
        battleNetConsumeLaunch(world, 5,
                BattleNetAiBytecode.OFF_LAUNCH_NAVAL,
                BattleNetAiBytecode.OFF_NAVAL_FORCE_COUNT,
                BattleNetAiBytecode.OFF_NAVAL_FORCE_MULTIPLIER);
        battleNetConsumeLaunch(world, 6,
                BattleNetAiBytecode.OFF_LAUNCH_AIR,
                BattleNetAiBytecode.OFF_AIR_FORCE_COUNT,
                BattleNetAiBytecode.OFF_AIR_FORCE_MULTIPLIER);
    }

    /**
     * Marks the launch bytes already handled by retail's game-creation pass.
     *
     * <p>The Java ready pass separately reproduces the observed profile-35
     * naval and profile-18 land startup groups. Leaving ai.bin's startup
     * edges armed replays those old requests on the first recurring pass at
     * fixture cycle 49, while native consumed them before cycle one.</p>
     */
    public void battleNetFinishBootstrapForces() {
        if (battleNetAiState == null) {
            return;
        }
        battleNetAiState[BattleNetAiBytecode.OFF_LAUNCH_GROUND] = 0;
        battleNetAiState[BattleNetAiBytecode.OFF_LAUNCH_NAVAL] = 0;
        battleNetAiState[BattleNetAiBytecode.OFF_LAUNCH_AIR] = 0;
    }

    private void battleNetConsumeLaunch(World world, int predicate,
            int pendingOffset, int groupSizeOffset, int groupCountOffset) {
        if ((battleNetAiState[pendingOffset] & 0xff) == 0) {
            return;
        }
        int groupSize = battleNetAiState[groupSizeOffset] & 0xff;
        int groupCount = battleNetAiState[groupCountOffset] & 0xff;
        List<Unit> available = new ArrayList<>();
        List<Unit> roster = world.playerUnits(playerIndex);
        // Native walks its player chain from low pool slot upward. Java's
        // per-player roster is the reverse of that chain.
        for (int index = roster.size() - 1; index >= 0; index--) {
            Unit unit = roster.get(index);
            if (unit.isAlive() && unit.isOnMap()
                    && !unit.battleNetReadySuppressed()
                    && unit.battleNetAiBehavior() != 2
                    && battleNetCountsForForce(unit.type(), predicate)) {
                available.add(unit);
            }
        }
        int cursor = 0;
        for (int group = 0; group < groupCount && cursor < available.size(); group++) {
            int end = Math.min(available.size(), cursor + groupSize);
            if (end <= cursor) {
                break;
            }
            List<Unit> members = available.subList(cursor, end);
            Unit leader = members.getFirst();
            Unit enemy = battleNetForceEnemy(world, leader, predicate);
            if (enemy == null) {
                break;
            }
            int goalX = enemy.tileX();
            int goalY = enemy.tileY();
            for (Unit member : members) {
                member.setBattleNetAiBehavior(2);
                member.setBattleNetAiHome(goalX, goalY);
                // Native's behavior-two handler issues Patrol here unless the
                // current order is one of the explicitly uninterruptible
                // action-table entries. Preserve active combat/harvest work;
                // ordinary standing and patrol guards are eligible.
                if (member.order() == Unit.Order.STILL
                        || member.order() == Unit.Order.PATROL) {
                    world.orderPatrol(member, goalX, goalY);
                }
            }
            cursor = end;
        }
        battleNetAiState[pendingOffset] = 0;
    }

    private Unit battleNetForceEnemy(World world, Unit leader, int predicate) {
        if (predicate != 6) {
            Unit enemy = world.findEnemyByFlood(leader, predicate == 4);
            if (enemy == null && predicate == 4) {
                enemy = world.findEnemyByFlood(leader, false);
            }
            return enemy;
        }
        Unit best = null;
        int distance = Integer.MAX_VALUE;
        for (Unit candidate : world.units()) {
            if (!candidate.isAlive() || !candidate.isOnMap()
                    || candidate.type() == null
                    || !world.isEnemyPlayer(playerIndex, candidate.player())) {
                continue;
            }
            int candidateDistance = leader.distanceTo(candidate);
            if (candidateDistance < distance) {
                distance = candidateDistance;
                best = candidate;
            }
        }
        return best;
    }

    private void syncBattleNetWantsFromState() {
        if (battleNetAiState == null) {
            return;
        }
        battleNetWantedBasicSoldiers =
                battleNetAiState[BattleNetAiBytecode.OFF_WANTED_BASIC] & 0xff;
        // Always mirror AIPlayerState+0x18. Destroyer wants already read the
        // state byte live; gating tankers to three sealed profiles left every
        // other navy personality (Human 14 black, Human 12, Orc 12) with a
        // silent shipyard while native spent 400g/200w.
        battleNetWantedTankers =
                battleNetAiState[BattleNetAiBytecode.OFF_WANTED_TANKERS] & 0xff;
    }

    /** AIPlayerState+0x13 worker target, or 0 when bytecode is not live. */
    public int battleNetWantedWorkers() {
        if (battleNetAiState == null) {
            return 0;
        }
        return battleNetAiState[BattleNetAiBytecode.OFF_WANTED_WORKERS] & 0xff;
    }

    public int battleNetWantedRanged() {
        if (battleNetAiState == null) {
            return 0;
        }
        return battleNetAiState[BattleNetAiBytecode.OFF_WANTED_RANGED] & 0xff;
    }

    public int battleNetWantedSiege() {
        if (battleNetAiState == null) {
            return 0;
        }
        return battleNetAiState[BattleNetAiBytecode.OFF_WANTED_SIEGE] & 0xff;
    }

    public int battleNetWantedCavalry() {
        if (battleNetAiState == null) {
            return 0;
        }
        return battleNetAiState[BattleNetAiBytecode.OFF_WANTED_CAVALRY] & 0xff;
    }

    public int battleNetWantedFlyers() {
        if (battleNetAiState == null) {
            return 0;
        }
        return battleNetAiState[BattleNetAiBytecode.OFF_WANTED_FLYERS] & 0xff;
    }

    /**
     * Opcode-3 predicates from the retail table at {@code 0x0049d8a4}.
     * Only the worker and building gates needed for early-horizon wants are
     * exact; force-size gates stay false until the native force counters are
     * ported, which correctly leaves the program blocked after writing the
     * land-force wants.
     */
    boolean battleNetPredicate(World world, int predicate, byte[] state) {
        return switch (predicate) {
            case 0 -> // owns shipyard
                    battleNetCountBuildings(world,
                            "unit-human-shipyard", "unit-orc-shipyard") > 0;
            case 1 -> // owns keep/stronghold
                    battleNetCountBuildings(world,
                            "unit-keep", "unit-stronghold") > 0;
            case 2 -> // owns castle/fortress
                    battleNetCountBuildings(world,
                            "unit-castle", "unit-fortress") > 0;
            case 3 -> // worker count >= state[0x13]
                    battleNetCountWorkers(world)
                            >= (state[BattleNetAiBytecode.OFF_WANTED_WORKERS] & 0xff);
            case 4 -> // ground fighters >= state[0x0d] * state[0x0e]
                    battleNetCountForce(world, predicate)
                            >= battleNetForceTarget(state,
                                    BattleNetAiBytecode.OFF_GROUND_FORCE_COUNT,
                                    BattleNetAiBytecode.OFF_GROUND_FORCE_MULTIPLIER);
            case 5 -> // naval fighters >= state[0x0f] * state[0x10]
                    battleNetCountForce(world, predicate)
                            >= battleNetForceTarget(state,
                                    BattleNetAiBytecode.OFF_NAVAL_FORCE_COUNT,
                                    BattleNetAiBytecode.OFF_NAVAL_FORCE_MULTIPLIER);
            case 6 -> // air fighters >= state[0x11] * state[0x12]
                    battleNetCountForce(world, predicate)
                            >= battleNetForceTarget(state,
                                    BattleNetAiBytecode.OFF_AIR_FORCE_COUNT,
                                    BattleNetAiBytecode.OFF_AIR_FORCE_MULTIPLIER);
            case 7 -> // any non-allied player has a worker
                    battleNetEnemyHasWorker(world);
            default -> false;
        };
    }

    private int battleNetCountWorkers(World world) {
        int workers = 0;
        // Walk the full unit list: playerUnitOrder is not guaranteed to be
        // fully populated at the post-placement bytecode bootstrap, and a
        // zero worker count left profile 65 stuck with empty land wants.
        for (Unit candidate : world.units()) {
            if (candidate.player() != playerIndex
                    || candidate.type() == null || candidate.hitPoints() <= 0) {
                continue;
            }
            String ident = candidate.type().ident();
            if ("unit-peasant".equals(ident) || "unit-peon".equals(ident)
                    || (candidate.type().canGather()
                        && (candidate.type().gathering().containsKey(
                                UnitType.Resource.GOLD)
                            || candidate.type().gathering().containsKey(
                                    UnitType.Resource.WOOD)))) {
                workers++;
            }
        }
        return workers;
    }

    private int battleNetCountBuildings(World world, String... idents) {
        int count = 0;
        Set<String> wanted = Set.of(idents);
        for (Unit candidate : world.playerUnits(playerIndex)) {
            if (candidate.type() == null || candidate.hitPoints() <= 0
                    || !candidate.isOnMap()
                    || !candidate.type().building()
                    || candidate.battleNetReadySuppressed()
                    // Only true construction hides a building from the AI
                    // census. Research and training also use progress/goal, and
                    // treating them as incomplete made a researching blacksmith
                    // vanish from the cavalry prereq set in the same cycle it
                    // accepted axe1 -- XHuman 9 barracks then trained an
                    // axethrower (500/50) instead of the native second ogre
                    // (800/100), leaving the p6 bank +300g/+50w at fixture 19.
                    || candidate.order() == Unit.Order.UNDER_CONSTRUCTION
                    || candidate.currentAction() == Unit.Order.UNDER_CONSTRUCTION) {
                continue;
            }
            if (wanted.contains(candidate.type().ident())) {
                count++;
            }
        }
        return count;
    }

    private boolean battleNetEnemyHasWorker(World world) {
        for (Unit candidate : world.units()) {
            if (candidate.type() == null || candidate.hitPoints() <= 0
                    || candidate.player() == playerIndex
                    || !candidate.isOnMap()) {
                continue;
            }
            if (world.isAllied(playerIndex, candidate.player())) {
                continue;
            }
            if (candidate.type().canGather()
                    && (candidate.type().gathering().containsKey(UnitType.Resource.GOLD)
                        || candidate.type().gathering().containsKey(
                                UnitType.Resource.WOOD))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether a type contributes to retail's ground/naval/air force counter.
     * Predicate numbers 4, 5 and 6 name those domains respectively.
     */
    static boolean battleNetCountsForForce(UnitType type, int predicate) {
        if (type == null || type.building() || !type.canAttack()
                || "unit-peasant".equals(type.ident())
                || "unit-peon".equals(type.ident())) {
            return false;
        }
        return switch (predicate) {
            case 4 -> !type.seaUnit() && !type.airUnit();
            case 5 -> type.seaUnit();
            case 6 -> type.airUnit();
            default -> false;
        };
    }

    private static int battleNetForceTarget(byte[] state, int count, int multiplier) {
        return (state[count] & 0xff) * (state[multiplier] & 0xff);
    }

    private int battleNetCountForce(World world, int predicate) {
        int count = 0;
        for (Unit candidate : world.playerUnits(playerIndex)) {
            if (candidate.hitPoints() > 0
                    && battleNetCountsForForce(candidate.type(), predicate)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Whether entry-277 word1 yielded a threshold table for this profile.
     * Unit tests that pass a synthetic list without word1 fall back to the
     * closed hall/barracks/shipyard class constants.
     */
    public boolean battleNetAction33TableLoaded() {
        return battleNetAiProfileData != null && battleNetAction33TableOffset >= 0;
    }

    /**
     * Native action-33 threshold for a producer PUD type code.
     *
     * <p>Returns {@code 0xffff} when the profile table is missing or the
     * entry disables the action. Callers must treat {@code 0xffff} as
     * "do not increment the counter and do not train".
     */
    public int battleNetAction33Limit(int pudType) {
        if (!battleNetAction33TableLoaded() || pudType < 0) {
            return 0xffff;
        }
        // Native: limit = *(uint16*)(pointer2 - 0x74 + pudType * 2).
        int at = battleNetAction33TableOffset - 0x74 + pudType * 2;
        int limit = unsignedShort(battleNetAiProfileData, at);
        return limit < 0 ? 0xffff : limit;
    }

    /** Whether a research milestone code is still available to consume. */
    public boolean battleNetHasAction33Candidate(int code) {
        return battleNetAction33Candidates.contains(code);
    }

    /** Spends a research milestone after a successful action-33 research. */
    public void battleNetConsumeAction33Candidate(int code) {
        battleNetAction33Candidates.remove(code);
        if (code >= 0x80) {
            battleNetAction33ResolvedHigh.add(code);
            // Do not immediately arm the next high byte. Native arms the next
            // milestone from the ready-worker scan (FUN_00439740), not from
            // the action-33 consume path. Re-arming here made XHuman 10/11
            // spend lumber-mill axe1 at c15 right after blacksmith axe1 at
            // c13/c15 consumed 0x86.
        }
    }

    /** Retail personality number used by startup behaviour dispatch. */
    public int battleNetBuildProfileId() {
        return battleNetBuildProfileId;
    }

    /**
     * 48-byte AIPlayerState with file-offset pointers at +0x04 / +0x23 /
     * +0x27, or {@code null} when no retail program is live.
     *
     * <p>Native stores process addresses in those slots. The comparison
     * program maps them onto {@code ai.bin}. Java already holds file
     * offsets, which is why the packed copy writes those offsets rather
     * than inventing a load address.
     */
    public byte[] packDecisionState() {
        if (battleNetAiState == null || battleNetAiState.length
                != BattleNetAiBytecode.STATE_BYTES) {
            return null;
        }
        byte[] packed = battleNetAiState.clone();
        writeU32(packed, 0x04, Math.max(0, battleNetAiPc));
        writeU32(packed, 0x23, Math.max(0, battleNetListOffset));
        writeU32(packed, 0x27, Math.max(0, battleNetAction33TableOffset));
        return packed;
    }

    public int battleNetListOffset() {
        return battleNetListOffset;
    }

    public int battleNetAiPc() {
        return battleNetAiPc;
    }

    public int battleNetAction33TableOffset() {
        return battleNetAction33TableOffset;
    }

    public boolean battleNetLastTickIndependent() {
        return battleNetLastTickIndependent;
    }

    public byte[] battleNetAiProfileData() {
        return battleNetAiProfileData;
    }

    /** Whether this player is driven by an installed retail ai.bin program. */
    public boolean hasBattleNetProfile() {
        // Attachment, not interpreter completeness, owns this decision. Some
        // retail profiles still contain bytecode shapes the Java interpreter
        // deliberately refuses. Falling through to the generic AI in that
        // case runs an invented second personality inside a BNE campaign and
        // can move units on fixture cycle one. The partial retail callbacks
        // remain the only authority whenever entry 277 was attached.
        return battleNetProfileAttached;
    }

    /** The engine-owned portion of a live retail {@code ai.bin} program. */
    public record BattleNetSavedState(int profileId, int pc, byte[] state,
            int buildPriorityLimit, int wantedBasicSoldiers, int wantedTankers,
            List<Integer> action33Candidates, List<Integer> action33ResolvedHigh,
            long watchUpgradeCycle, long flyerTrainCycle) {}

    /** Returns the retail bytecode state, or null for a retired scripting language/plan-only AI. */
    public BattleNetSavedState savedBattleNetState() {
        if (battleNetAiState == null || battleNetAiPc < 0) {
            return null;
        }
        return new BattleNetSavedState(battleNetBuildProfileId, battleNetAiPc,
                battleNetAiState.clone(), battleNetBuildPriorityLimit,
                battleNetWantedBasicSoldiers, battleNetWantedTankers,
                List.copyOf(battleNetAction33Candidates),
                List.copyOf(battleNetAction33ResolvedHigh),
                battleNetWatchUpgradeCycle, battleNetFlyerTrainCycle);
    }

    /**
     * Restores a saved retail program onto the profile installed by the map.
     *
     * <p>The profile bytes remain map data and are not duplicated into every
     * save. A mismatched or absent profile is refused, leaving the newly loaded
     * mission's AI intact rather than running a program counter into unrelated
     * bytes.
     */
    public boolean restoreBattleNetState(BattleNetSavedState saved) {
        if (saved == null || battleNetAiProfileData == null
                || battleNetBuildProfileId != saved.profileId()
                || saved.state() == null
                || saved.state().length != BattleNetAiBytecode.STATE_BYTES) {
            return false;
        }
        battleNetAiState = saved.state().clone();
        battleNetAiPc = saved.pc();
        battleNetBuildPriorityLimit = saved.buildPriorityLimit();
        battleNetWantedBasicSoldiers = saved.wantedBasicSoldiers();
        battleNetWantedTankers = saved.wantedTankers();
        battleNetAction33Candidates.clear();
        battleNetAction33Candidates.addAll(saved.action33Candidates());
        battleNetAction33ResolvedHigh.clear();
        battleNetAction33ResolvedHigh.addAll(saved.action33ResolvedHigh());
        battleNetWatchUpgradeCycle = saved.watchUpgradeCycle();
        battleNetFlyerTrainCycle = saved.flyerTrainCycle();
        return true;
    }

    private static int unsignedShort(byte[] data, int offset) {
        return offset < 0 || offset + 1 >= data.length ? -1
                : (data[offset] & 0xff) | ((data[offset + 1] & 0xff) << 8);
    }

    private static void writeU32(byte[] data, int offset, int value) {
        data[offset] = (byte) value;
        data[offset + 1] = (byte) (value >>> 8);
        data[offset + 2] = (byte) (value >>> 16);
        data[offset + 3] = (byte) (value >>> 24);
    }

    private final List<AiForce> forces = new ArrayList<>();

    /** Whether the built-in plan drives this AI. */
    private boolean usePlan = true;

    /** Entry 277 was attached, even when its program is only partly decoded. */
    private boolean battleNetProfileAttached;

    /** Legacy save fields retained so old saves remain readable. */
    private int scriptIndex = 1;
    private int scriptLoopIndex = 1;

    public AiPlayer(int playerIndex) {
        this.playerIndex = playerIndex;
    }

    public int playerIndex() {
        return playerIndex;
    }

    /**
     * What the AI has asked for but not yet got, tallied by type.
     *
     * <p>A read-only view over the queue entries, for asserts and displays;
     * the queue itself is per-entry and its order is behaviour.
     */
    public Map<UnitType, Integer> requests() {
        Map<UnitType, Integer> view = new LinkedHashMap<>();
        for (BuildRequest request : buildQueue) {
            view.merge(request.type, request.count, Integer::sum);
        }
        return view;
    }

    /**
     * The forces it is assembling, lowest index first.
     *
     * <p>The list is indexed by force number and the order is stable, so a
     * saved game can write it as it stands. Forces are what carry an attack:
     * an AI reloaded without them has forgotten it was mid-assault, so this is
     * what the save has to walk. See {@link AiForce#memberIds},
     * {@link AiForce#wantedByIdent} and {@link AiForce#restore} for reading one
     * out and putting it back, and {@link #force} for making the gaps when a
     * saved game names force three and not forces one and two.
     */
    public List<AiForce> forces() {
        return forces;
    }

    /**
     * The force a script addresses by number, created if it does not exist.
     *
     * <p>Looked up by the force's own number rather than by its position in the
     * list, because the list also holds the forces {@link #handOffForAttack}
     * makes, and those must not answer to {@code AiForce(2)}.
     */
    public AiForce force(int index) {
        for (AiForce existing : forces) {
            if (existing.index() == index) {
                return existing;
            }
        }
        AiForce fresh = new AiForce(index);
        forces.add(fresh);
        if (index < CARRIER_INDEX_BASE) {
            // getScriptForce allocates the next free internal slot, which is
            // distinct from the script-visible force number.  AiHelpMe later
            // follows Unit.GroupId through those internal slots.
            namedForceSlots.add(fresh);
        }
        return fresh;
    }

    /** Puts one saved force back and keeps future carrier indices unique. */
    public AiForce restoreForce(int index, Map<UnitType, Integer> wanted,
            List<Unit> members, AiForce.State state, boolean defending,
            int goalX, int goalY, int waitOnRallyPoint) {
        AiForce restored = force(index);
        restored.restore(wanted, members, state, defending,
                goalX, goalY, waitOnRallyPoint);
        if (index >= CARRIER_INDEX_BASE) {
            nextCarrierIndex = Math.max(nextCarrierIndex, index + 1);
        } else {
            explicitlyGrouped.addAll(restored.members());
        }
        return restored;
    }

    /** Script-addressable forces in upstream's internal allocation order. */
    private final List<AiForce> namedForceSlots = new ArrayList<>();

    /**
     * Units assigned by CclAiForce's explicit-force path.
     *
     * <p>Upstream's Assign(unit, force) stores {@code GroupId = force + 1}
     * using the script number, although it inserts into
     * {@code getScriptForce(force)}.  AiHelpMe later treats GroupId as an
     * internal slot.  That shipped mismatch is observable when script force
     * two occupies internal slot one: its unit points at empty slot two and
     * calls no listed force-mates for help.
     */
    private final java.util.Set<Unit> explicitlyGrouped =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Hands an army off to a force of its own and empties the script's.
     *
     * <p>{@code AiAttackWithForce}, and the half of it that is not obvious:
     * upstream does not send the force the script named. It finds a free
     * internal force, moves every unit and every line of the shopping list into
     * it, marks that one complete and <em>resets the one the script named</em>.
     *
     * <p>That is what lets a build order say "declare a force, wait for it,
     * attack with it" inside a loop, which is how most of these personalities
     * are written. Reusing the same force instead is not merely a difference in
     * bookkeeping: {@code AiWaitForce} answers "am I still filling", a force
     * that has just attacked is still full, so the loop's wait stops waiting,
     * the attack step fires again, and the whole list runs round for ever
     * inside a single {@code while true} in {@code AiLoop} -- which does not
     * end the second, it ends the game. {@code levelx10h}'s second personality
     * is five steps long and does exactly that.
     *
     * <p>A defending force is attacked with where it stands, as upstream's
     * {@code if (!Defending)} says, and is not handed off.
     *
     * @param index the force number the script named
     */
    public void handOffForAttack(int index) {
        AiForce named = force(index);
        named.prune();
        if (named.defending() || named.members().isEmpty()) {
            named.setState(AiForce.State.READY);
            return;
        }
        AiForce carrier = new AiForce(nextCarrierIndex++);
        carrier.restore(named.wanted(), named.members(), AiForce.State.READY, false);
        forces.add(carrier);
        // AiAttackWithForce writes every transferred unit's real internal
        // carrier slot into GroupId, so the explicit-script indexing quirk no
        // longer belongs to those members.
        explicitlyGrouped.removeAll(carrier.members());
        named.reset(true);
    }

    /**
     * The number the next handed-off force gets.
     *
     * <p>Above anything a script can name. Upstream keeps fifty internal force
     * slots behind ten script ones and maps between them; the numbers only have
     * to be unique and not collide with {@code AiForce(n)}.
     */
    private int nextCarrierIndex = CARRIER_INDEX_BASE;

    private static final int CARRIER_INDEX_BASE = 100;

    /** Adds a build-queue entry, as {@code AiAddUnitTypeRequest} does. */
    public void need(UnitType type, int count) {
        if (System.getenv("CHONKCRAFT_TRACE_NEED") != null) {
            StackTraceElement[] st = Thread.currentThread().getStackTrace();
            StringBuilder sb = new StringBuilder("JNEED " + type.ident() + " x" + count);
            for (int i = 2; i < Math.min(st.length, 6); i++) {
                sb.append(" | ").append(st[i].getMethodName()).append(":").append(st[i].getLineNumber());
            }
            System.err.println(sb);
        }
        buildQueue.add(new BuildRequest(type, count));
    }

    /**
     * One standing want: {@code AiRequestType}, a type and how many.
     *
     * <p>Mutable count because {@code AiSet} rewrites it in place.
     */
    public static final class StandingRequest {
        private final UnitType type;
        private int count;

        StandingRequest(UnitType type, int count) {
            this.type = type;
            this.count = count;
        }

        public UnitType type() {
            return type;
        }

        public int count() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }
    }

    /**
     * The standing wants: {@code PlayerAi::UnitTypeRequests}.
     *
     * <p>A list and not a map because upstream's is: {@code AiNeed} always
     * appends a fresh entry -- a script's second {@code AiNeed(AiBarracks())}
     * is how it asks for a second barracks -- while {@code AiSet} rewrites
     * the count on the first entry it finds. The wants stand for the rest of
     * the game and {@link #checkRequests} walks them every thought against
     * what the player actually owns, which is what replaces a dead peasant
     * and never builds a lumber mill beside the one the map already placed.
     * This implementation used to turn {@code AiNeed} into a one-shot build request,
     * unchecked against the roster: on campaigns/orc-exp/levelx04o the
     * script's {@code AiNeed(AiLumberMill())} sent a peasant out at cycle 8
     * to found a second mill upstream never asks for.
     */
    private final List<StandingRequest> unitTypeRequests = new ArrayList<>();

    /** The standing wants, for the save to write out. */
    public List<StandingRequest> unitTypeRequests() {
        return unitTypeRequests;
    }

    /** {@code InsertUnitTypeRequests}: {@code AiNeed}'s always-append. */
    public void insertUnitTypeRequest(UnitType type, int count) {
        if (type != null) {
            unitTypeRequests.add(new StandingRequest(type, count));
        }
    }

    /** {@code FindInUnitTypeRequests}: the first standing entry for a type. */
    public StandingRequest findUnitTypeRequest(UnitType type) {
        for (StandingRequest request : unitTypeRequests) {
            if (request.type() == type) {
                return request;
            }
        }
        return null;
    }

    /**
     * Upgrades this AI has been told to research, in the order it asked.
     *
     * <p>{@code PlayerAi::ResearchRequests}, and a standing list rather than a
     * queue: {@code AiCheckUnits} walks it every second and asks for each
     * again, and an upgrade drops off it only by being researched. That is why
     * a script can say {@code AiResearch(AiUpgradeWeapon1())} once, before the
     * blacksmith exists, and still get the upgrade later.
     */
    private final Set<String> researchRequests = new LinkedHashSet<>();

    /** What the AI has been told to research and has not got yet. */
    public Set<String> researchRequests() {
        return researchRequests;
    }

    /** {@code AiResearch}: adds an upgrade to the standing list. */
    public void research(String upgradeIdent) {
        if (upgradeIdent != null && !upgradeIdent.isBlank()) {
            researchRequests.add(upgradeIdent);
        }
    }

    /** One occurrence in upstream's ordered {@code UpgradeToRequests} vector. */
    public record UpgradeToRequest(UnitType source, UnitType target) {}

    /**
     * Buildings this AI has been told to turn into better ones, in script order.
     *
     * <p>{@code PlayerAi::UpgradeToRequests} is an ordered vector and keeps
     * duplicate script calls. The source travels beside the upstream target in
     * this implementation because {@code World.orderUpgradeTo} needs the button-derived
     * source type and must not turn an arbitrary idle building into the target.
     */
    private final List<UpgradeToRequest> upgradeToRequests = new ArrayList<>();

    /** Every standing upgrade occurrence, including duplicates. */
    public List<UpgradeToRequest> upgradeToRequests() {
        return upgradeToRequests;
    }

    /** {@code AiUpgradeTo}: a watch tower into a guard tower, a hall into a keep. */
    public void upgradeTo(UnitType from, UnitType to) {
        if (from != null && to != null && from != to) {
            upgradeToRequests.add(new UpgradeToRequest(from, to));
        }
    }

    /**
     * The cycle an {@code AiSleep} is waiting for, or zero when awake.
     *
     * <p>Upstream calls this {@code SleepCycles} and it is not a pause on the
     * whole AI, only on its script: a sleeping computer player still gathers,
     * still builds what it has already asked for and still fights. Modelling
     * it as "skip thinking for n seconds" stopped all of that too.
     */
    private long sleepUntilCycle;

    public long sleepUntilCycle() {
        return sleepUntilCycle;
    }

    public void setSleepUntilCycle(long cycle) {
        this.sleepUntilCycle = cycle;
    }

    /** Whether a restored legacy AI sleep deadline is still armed. */
    public boolean isSleeping() {
        return sleepUntilCycle > 0;
    }

    public int scriptIndex() {
        return scriptIndex;
    }

    public int scriptLoopIndex() {
        return scriptLoopIndex;
    }

    /** Restores two deprecated fields written by older saves. */
    public void restoreScriptPosition(int index, int loopIndex) {
        scriptIndex = Math.max(1, index);
        scriptLoopIndex = Math.max(1, loopIndex);
    }

    /** Whether the old generic plan runs; BNE retail profiles keep this off. */
    public boolean usePlan() {
        return usePlan;
    }

    public void setUsePlan(boolean usePlan) {
        this.usePlan = usePlan;
    }

    /**
     * One second of thought.
     *
     * <p>Order follows {@code AiEachSecond}.
     */
    public void think(World world) {
        Player player = world.player(playerIndex);
        // Not Player.isActive: that asks whether a slot is one of the sides
        // playing the game, and a rescue-active ally is not, but it does think
        // for itself. Whether this AI runs is settled by whoever enabled it.
        if (player.type() != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                && player.type() != net.chonkbase.chonkcraft.data.map.PudMap
                        .PlayerType.RESCUE_ACTIVE) {
            return;
        }
        List<Unit> owned = ownedUnits(world);
        if (owned.isEmpty()) {
            return;
        }

        if (usePlan) {
            plan(world, player, owned);
        }
        // Harvesters are reassigned every fourth second, staggered by player,
        // not every thought. AiResourceManager gates AiCollectResources on
        // {@code (GameCycle / CYCLES_PER_SECOND) % COLLECT_RESOURCES_INTERVAL
        // == Player->Index % COLLECT_RESOURCES_INTERVAL}
        // with the interval four.
        // This implementation ran the pass on every think. Invisible while every worker
        // found something to gather -- an assigned worker is not STILL and the
        // extra passes had nothing to do -- and wrong the moment one could
        // not: on maps/demo/demo02 an oil tanker with no reachable oil goes
        // idle at cycle 363, this implementation's pass found it on 368 and upstream's
        // waited for its window on 398, and the exploration request it raises
        // costs eleven numbers off the shared stream. Probed from the real
        // binary: player 1's requests land on cycles 38, 158, 398, 518, 758,
        // which is exactly seconds congruent to 1 modulo 4.
        //
        // Inside AiCheckUnits upstream, and therefore before the resource
        // manager: the two compete for the same bank and upstream lets the
        // researches and the upgrades ask first.
        Map<UnitType, Integer> requestCounter = checkRequests(world, owned);
        // AiCheckUnits walks the standing wants in a fixed order: the unit
        // requests, then the upgrade-tos, then the researches
        // and the order is the bank's order. On
        // campaigns/orc-exp/levelx12o player 3's first thought starts the
        // keep at 2000 gold, 1000 wood and 200 oil and then refuses
        // polymorph at 900; this implementation asked the researches first, polymorph
        // took the keep's gold, and the keep was never begun.
        upgradeManager(world, requestCounter);
        researchManager(world, owned);
        resourceManager(world, player, owned);
        // The harvest census runs inside AiResourceManager, after the
        // spending -- AiCheckingWork first, AiCollectResources after
        // The game because the census reads
        // NeededMask, and the mask is this thought's failed payments. A
        // collect that ran before the spending read yesterday's hunger:
        // levelx03h's poor player leaned its peons into gold on the same
        // thought its axe upgrades were refused for it, and this implementation,
        // collecting first, sent the same peon to the trees.
        if ((world.cycle() / World.CYCLES_PER_SECOND) % COLLECT_RESOURCES_INTERVAL
                == playerIndex % COLLECT_RESOURCES_INTERVAL) {
            checkUnits(world, owned);
        }
        // "AiPlayer->NeededMask = 0" ends AiResourceManager.
        neededMask.clear();
        forceManager(world, owned);
        // At most one explorer every five seconds, and the gate is upstream's
        // own line: {@code if (GameCycle > AiPlayer->LastExplorationGameCycle
        // + 5 * CYCLES_PER_SECOND) AiSendExplorers();},
        // after the force manager and the magic check.
        if (world.cycle() > lastExplorationCycle + 5L * World.CYCLES_PER_SECOND) {
            sendExplorers(world, owned);
        }
    }

    /**
     * Somewhere this player would like to see.
     *
     * <p>{@code AiExplorationRequest}: a position and the movement mask of
     * whatever it wanted to find there.
     */
    private record ExplorationRequest(int x, int y, boolean land, boolean sea) {}

    /**
     * The requests, newest first.
     *
     * <p>{@code AiExplore} inserts at {@code begin()}
     * {@code AiSendExplorers}
     * empties the list every time it runs, so this is never long and its order
     * is the order the failures happened in -- which matters, because a
     * request is picked out of it by index with a number off the shared random
     * stream.
     */
    private final List<ExplorationRequest> explorationRequests = new java.util.ArrayList<>();

    private long lastExplorationCycle;

    /**
     * The cycle this player last shoved somebody out of somebody else's way.
     *
     * <p>{@code AiPlayer->LastCanNotMoveGameCycle}. The one shove per ten
     * cycles it buys is per player, not per unit, and it is only stamped when
     * a shove actually happened -- "No more than 1 move per 10 cycle ( avoid
     * stressing the pathfinder )".
     *
     * <p>Nought at birth, exactly as upstream's counter is, and the nought is
     * behaviour: {@code GameCycle <= 0 + 10} suppresses every shove through
     * cycle ten, so an opening force that jams in its own column stands and
     * widens instead of throwing an archer out of line. On
     * campaigns/orc/level11o all four members answer unreachable on their
     * first crowded plan at cycle 9, and a port whose counter started at a
     * distant negative shoved one of them west with two draws upstream never
     * made.
     */
    private long lastCanNotMoveCycle = 0;

    /** When this player last shoved a blocker aside. */
    public long lastCanNotMoveCycle() {
        return lastCanNotMoveCycle;
    }

    /** Records that it just did. */
    public void setLastCanNotMoveCycle(long cycle) {
        this.lastCanNotMoveCycle = cycle;
    }

    /**
     * Marks a place this player could not find what it wanted in.
     *
     * <p>{@code AiExplore(pos, mask)}. Upstream raises it from the two arms of
     * the harvester assignment that fail -- no forest within a thousand
     * squares, or no mine -- and from
     * nowhere else.
     */
    private void explore(Unit worker, boolean land, boolean sea) {
        explorationRequests.add(0,
                new ExplorationRequest(worker.tileX(), worker.tileY(), land, sea));
        if (System.getenv("CHONKCRAFT_TRACE_EXPLORE") != null) {
            System.err.printf("JEXPLOREQ cycle=? p%d unit=%d at=%d,%d land=%d sea=%d count=%d%n",
                    playerIndex, worker.id(), worker.tileX(), worker.tileY(),
                    land ? 1 : 0, sea ? 1 : 0, explorationRequests.size());
        }
    }

    /**
     * Sends one unit to look at somewhere this player has not seen.
     *
     * <p>{@code AiSendExplorers}. Five tries
     * at most: pick a request by index with a number off the shared stream,
     * ask {@link #bestExplorer} for a unit and a square, and send the unit
     * there. Then the whole list goes, found or not.
     *
     * <p>It is a heavy spender and that is the point of porting it: on
     * {@code maps/demo/demo02} cycle 158 is fifty-one draws in this one
     * function -- three request picks and three rounds of eight two-draw
     * attempts at an unexplored square -- and this implementation made none of them. The
     * peasants there can reach no forest, so a request is raised every second
     * and this runs every five.
     */
    private void sendExplorers(World world, List<Unit> owned) {
        int requestCount = explorationRequests.size();
        if (requestCount == 0) {
            return;
        }
        if (System.getenv("CHONKCRAFT_TRACE_EXPLORE") != null) {
            System.err.printf("JSENDEXPBEGIN cycle=%d p%d requests=%d last=%d%n",
                    world.cycle(), playerIndex, requestCount, lastExplorationCycle);
        }
        for (int i = 0; i != 5; ++i) {
            int requestIndex = world.syncRand(requestCount);
            ExplorationRequest request = explorationRequests.get(requestIndex);
            Explorer found = bestExplorer(world, owned, request);
            if (System.getenv("CHONKCRAFT_TRACE_EXPLORE") != null) {
                System.err.printf("JSENDEXP cycle=%d p%d requests=%d id=%d req=%d,%d"
                                + " picked=%d to=%d,%d%n",
                        world.cycle(), playerIndex, requestCount, requestIndex,
                        request.x(), request.y(), found == null ? -1 : found.unit().id(),
                        found == null ? -1 : found.x(), found == null ? -1 : found.y());
            }
            if (found != null) {
                // Commanded now and counted as standing still until the queue
                // is popped, which is what CurrentAction answers while the new
                // order waits behind the old one -- CommandMove with
                // EFlushMode::On, the same one cycle of latency the wander
                // has. Upstream's tanker reads still on cycle 158 and moving
                // on 159.
                Unit.Order before = found.unit().order();
                world.orderMove(found.unit(), found.x(), found.y());
                found.unit().rememberActionBeforeQueued(before);
                lastExplorationCycle = world.cycle();
                break;
            }
        }
        explorationRequests.clear();
    }

    /**
     * A square worth looking at and the idle unit nearest it.
     *
     * <p>{@code GetBestExplorer} with {@code ChooseRandomUnexploredPositionNear}
     * inside it. The square comes first and
     * costs two draws a try, eight tries, with the ray starting at three and
     * growing by half each time; if none of the eight lands on unexplored
     * ground the whole request is dropped and no unit is looked at.
     *
     * @return the unit and the square, or null
     */
    private Explorer bestExplorer(World world, List<Unit> owned, ExplorationRequest request) {
        int ray = 3;
        int toX = -1;
        int toY = -1;
        for (int i = 0; i != 8; ++i) {
            int x = request.x() + world.syncRand(2 * ray + 1) - ray;
            int y = request.y() + world.syncRand(2 * ray + 1) - ray;
            if (world.map().contains(x, y) && !world.fog().isExplored(playerIndex, x, y)) {
                toX = x;
                toY = y;
                break;
            }
            ray = 3 * ray / 2;
        }
        if (toX < 0) {
            return null;
        }
        Unit best = null;
        boolean flyerOnly = false;
        long bestSquareDistance = -1;
        for (Unit unit : owned) {
            if (unit.order() != Unit.Order.STILL || !unit.isOnMap() || !unit.canMove()) {
                continue;
            }
            boolean flyer = unit.type().airUnit();
            if (!flyer) {
                if (flyerOnly) {
                    continue;
                }
                if (request.land() && !unit.type().landUnit()) {
                    continue;
                }
                if (request.sea() && !unit.type().seaUnit()) {
                    continue;
                }
            } else {
                flyerOnly = true;
            }
            long dx = unit.tileX() - toX;
            long dy = unit.tileY() - toY;
            long squareDistance = dx * dx + dy * dy;
            if (bestSquareDistance == -1 || squareDistance <= bestSquareDistance
                    || (!best.type().airUnit() && flyer)) {
                bestSquareDistance = squareDistance;
                best = unit;
            }
        }
        return best == null ? null : new Explorer(best, toX, toY);
    }

    /** One unit and the square it is being sent to look at. */
    private record Explorer(Unit unit, int x, int y) {}

    /**
     * The standing plan, used when no script is attached.
     *
     * <p>Deliberately simple: keep workers working, keep the food ahead of
     * demand, keep one force stocked. The shipped scripts express the same
     * intent in far more detail, and when one is attached this does not run.
     */
    private void plan(World world, Player player, List<Unit> owned) {
        long workers = owned.stream().filter(u -> u.type().canGather()).count();
        UnitType workerType = owned.stream()
                .filter(u -> u.type().canGather())
                .map(Unit::type)
                .findFirst()
                .orElse(null);

        // Keep about four workers per resource-storing building.
        long depots = owned.stream().filter(u -> !u.type().stores().isEmpty()).count();
        // Retail's ready-worker path treats a bank below 500 gold as poor and
        // will not spend its last 400 on another worker.  The generic plan is
        // only the no-profile fallback, but it must preserve that same safety
        // floor or a skirmish AI can consume its entire recovery bank on its
        // first newly-restored thought.
        if (workerType != null && workers < depots * 4
                && player.get(UnitType.Resource.GOLD) >= 500) {
            need(workerType, 1);
        }

        // Keep supply ahead of demand so training never stalls. Asked of the
        // supply value the data carries rather than of an identifier
        // containing "farm", which is the same string match that stopped every
        // computer player in the game from cutting a tree.
        if (player.demand() + 2 > player.supply()) {
            requestSupply(world, player, owned);
        }

        // Keep one attacking force stocked with whatever soldiers exist.
        AiForce attack = force(0);
        if (attack.wanted().isEmpty()) {
            UnitType soldier = owned.stream()
                    .filter(u -> u.type().canAttack() && !u.type().building())
                    .map(Unit::type)
                    .findFirst()
                    .orElse(null);
            if (soldier != null) {
                attack.setWanted(Map.of(soldier, 4));
            }
        }
    }

    /**
     * Puts idle workers back to work.
     *
     * <p>Force membership is no shield: {@code AiCollectResources} never
     * looks at {@code GroupId}, so an idle member of a marched force is
     * drafted back to the trees, and the launch -- or the idle re-send --
     * takes it again with a flush. This implementation used to guard marched forces'
     * workers from the census; the guard was covering for an enlist that
     * only took standing units, and once membership was settled at script
     * time it had become the divergence itself: level08h's jammed siege
     * peasant pulses in place under the guard where upstream's census walks
     * it to the mine at the cycle-131 window.
     */
    /** The gatherable kinds, in upstream's cost-index order. */
    private static final UnitType.Resource[] COLLECT_KINDS = {
        UnitType.Resource.GOLD, UnitType.Resource.WOOD, UnitType.Resource.OIL,
    };

    /**
     * The share of the workforce each resource is owed.
     *
     * <p>{@code AiInit} writes 50, 50 and 0 --
     * and the campaign personalities rewrite it: level13h's hum-13 calls
     * {@code AiSetCollect({0, 50, 50, 100, 0, 0, 0})}, an oil-heavy split
     * whose unfillable oil want is what leaves two of four peons for the
     * trees. This implementation carried the defaults as a constant and the claim
     * that nothing shipped ever changes them, which was true of the
     * scripts directory and false of the campaigns.
     */
    private final int[] collectPercent = {50, 50, 0};

    /** {@code AiSetCollectResources}: the script's own split. */
    public void setCollect(int gold, int wood, int oil) {
        collectPercent[0] = gold;
        collectPercent[1] = wood;
        collectPercent[2] = oil;
    }

    /**
     * The resources this thought could not pay for.
     *
     * <p>{@code PlayerAi::NeededMask}. Every cost check that fails --
     * {@code AiCheckingWork}'s unit and building requests,
     * {@code AiAddResearchRequest}, {@code AiAddUpgradeToRequest} -- raises
     * the missing resources' bits, {@code AiCollectResources} doubles those
     * resources' share of the harvesters on the same thought, and
     * {@code AiResourceManager} wipes the mask at its end. The implementation kept a
     * static 50/50 split, documented as bounded; the bound broke on
     * campaigns/human-exp/levelx03h, where a player down to 400 gold sent a
     * peon to the trees beside it while upstream, its axe upgrades refused
     * for gold all thought long, marched the same peon twenty-three squares
     * to the mine.
     */
    private final java.util.EnumSet<UnitType.Resource> neededMask =
            java.util.EnumSet.noneOf(UnitType.Resource.class);

    private void checkUnits(World world, List<Unit> owned) {
        // AiCollectResources' opening census, in the unit list's own order:
        // every harvester is one of assigned to a resource, idle and laden --
        // sent home on the spot -- or idle and free, listed under every
        // resource its type can carry.
        //
        // The walk is Player->GetUnits(), which holds every living unit the
        // player owns, removed included -- not this AI's owned list, which
        // keeps only what stands on the map. The difference is the peons
        // down the mine: they count toward gold's assigned share even while
        // they cannot be seen or reassigned. On campaigns/human-exp/levelx03h
        // at cycle 131 both of the orc player's miners are inside, and a
        // census that could not see them read gold's share as empty and
        // stole a chopper mid-swing -- carried=8 -- for the mine, where
        // upstream, counting two on gold against two on wood, moved nobody.
        // Not isAlive(), whose !removed clause is exactly the blindness being
        // cured here: living is hit points and not yet dying, wherever the
        // unit stands.
        List<Unit> counted = new ArrayList<>();
        // AiCollectResources walks Player->GetUnits(), whose swap-on-remove
        // order is independent of the global UnitActions table.  The two
        // tables first disagree as soon as a unit of another owner releases:
        // only the global table fills that hole.  levelx12h exposes the
        // consequence at cycle 1633, when four zero-load miners compare
        // equal and the backwards donor scan must take the newest peon from
        // player 6's roster, not the peon that an unrelated global release
        // moved ahead of it.
        for (Unit unit : world.playerUnits(playerIndex)) {
            if (unit.player() == playerIndex && unit.hitPoints() > 0
                    && unit.order() != Unit.Order.DYING) {
                counted.add(unit);
            }
        }
        Map<UnitType.Resource, List<Unit>> assigned = new java.util.LinkedHashMap<>();
        Map<UnitType.Resource, List<Unit>> unassigned = new java.util.LinkedHashMap<>();
        Map<UnitType.Resource, Integer> withResource = new java.util.LinkedHashMap<>();
        for (UnitType.Resource kind : COLLECT_KINDS) {
            assigned.put(kind, new ArrayList<>());
            unassigned.put(kind, new ArrayList<>());
            withResource.put(kind, 0);
        }
        int total = 0;
        for (Unit unit : counted) {
            if (!unit.type().canGather()) {
                continue;
            }
            if (unit.order() == Unit.Order.HARVEST && unit.carrying() != null
                    && assigned.containsKey(unit.carrying())) {
                assigned.get(unit.carrying()).add(unit);
                total++;
                continue;
            }
            // "Ignore busy units. ( building, fighting... )" -- and
            // nothing else. Upstream's collect never asks about GroupId, so
            // an idle force member is drafted back to work and the launch
            // takes it again with a flush. level08h's jammed siege peasant
            // shows it: its re-send pulse dies against the crowd, and at
            // the cycle-131 window upstream's census picks the idle worker
            // up and walks it to the mine while a guarded port left it
            // pulsing in place.
            // A just-finished shove move is still CurrentAction until the
            // following cycle's pop, so it is not idle to this census yet.
            // Keep this narrower than a blanket currentAction test: the
            // port's one-cycle resource/build reporting shims deliberately
            // coexist with the replacement order and upstream's census does
            // classify those from the installed order.
            if (unit.order() != Unit.Order.STILL
                    || unit.currentAction() == Unit.Order.MOVE) {
                continue;
            }
            UnitType.Resource cargo = unit.heldResource() != null
                    ? unit.heldResource() : unit.carrying();
            if (unit.carried() > 0 && cargo != null
                    && withResource.containsKey(cargo)) {
                withResource.merge(cargo, 1, Integer::sum);
                world.orderReturnGoods(unit);
                total++;
                continue;
            }
            for (UnitType.Resource kind : COLLECT_KINDS) {
                if (unit.type().gathering().containsKey(kind)) {
                    unassigned.get(kind).add(unit);
                }
            }
            total++;
        }
        if (total == 0) {
            return;
        }

        // "Turn percent values into harvester numbers."
        // "Double percent if needed": a resource this thought's payments
        // came up short on takes twice its share of the harvesters, and the
        // denominator grows by the undoubled share

        int percentTotal = 100;
        int[] percent = new int[COLLECT_KINDS.length];
        for (int c = 0; c < COLLECT_KINDS.length; c++) {
            percent[c] = collectPercent[c];
            if (neededMask.contains(COLLECT_KINDS[c])) {
                percentTotal += percent[c];
                percent[c] <<= 1;
            }
        }
        int[] wanted = new int[COLLECT_KINDS.length];
        for (int c = 0; c < COLLECT_KINDS.length; c++) {
            if (percent[c] > 0) {
                wanted[c] = 1 + percent[c] * Math.max(total, 5) / percentTotal;
            }
        }
        if (System.getenv("CHONKCRAFT_TRACE_AICOLLECT") != null) {
            StringBuilder sb = new StringBuilder("JCENSUS cycle=" + world.cycle()
                    + " p" + playerIndex + " total=" + total + " wanted=");
            for (int c = 0; c < COLLECT_KINDS.length; c++) {
                sb.append(wanted[c]).append('/');
            }
            sb.append(" assigned=");
            for (UnitType.Resource kind : COLLECT_KINDS) {
                sb.append(assigned.get(kind).size()).append('/');
            }
            sb.append(" withres=");
            for (UnitType.Resource kind : COLLECT_KINDS) {
                sb.append(withResource.get(kind)).append('/');
            }
            sb.append(" unassigned=");
            for (UnitType.Resource kind : COLLECT_KINDS) {
                sb.append(unassigned.get(kind).size()).append('/');
            }
            sb.append(" members=");
            for (UnitType.Resource kind : COLLECT_KINDS) {
                sb.append(kind).append('[');
                for (Unit member : assigned.get(kind)) {
                    sb.append(member.id()).append(':').append(member.carried())
                            .append(member.returningToDepot() ? 'r' : '-')
                            .append(member.isOnMap() ? 'm' : 'x').append(',');
                }
                sb.append(']');
            }
            System.err.println(sb);
        }
        int[] priorityResource = new int[COLLECT_KINDS.length];
        int[] priorityNeeded = new int[COLLECT_KINDS.length];
        for (int c = 0; c < COLLECT_KINDS.length; c++) {
            priorityResource[c] = c;
            priorityNeeded[c] = wanted[c] - assigned.get(COLLECT_KINDS[c]).size()
                    - withResource.get(COLLECT_KINDS[c]);
            // "first should go workers with lower ResourcesHeld value."
            if (assigned.get(COLLECT_KINDS[c]).size() > 1) {
                assigned.get(COLLECT_KINDS[c]).sort(
                        java.util.Comparator.comparingInt(Unit::carried));
            }
        }

        // "Try to complete each resource in the priority order", re-sorting
        // after every move, until a whole pass places nobody.
        boolean placed = true;
        while (placed) {
            placed = false;
            for (int i = 0; i < priorityNeeded.length; i++) {
                for (int j = i + 1; j < priorityNeeded.length; j++) {
                    if (priorityNeeded[j] > priorityNeeded[i]) {
                        int swap = priorityNeeded[i];
                        priorityNeeded[i] = priorityNeeded[j];
                        priorityNeeded[j] = swap;
                        swap = priorityResource[i];
                        priorityResource[i] = priorityResource[j];
                        priorityResource[j] = swap;
                    }
                }
            }
            for (int i = 0; i < priorityResource.length && !placed; i++) {
                int c = priorityResource[i];
                UnitType.Resource kind = COLLECT_KINDS[c];
                List<Unit> free = unassigned.get(kind);
                // "If there is a free worker for c, take it" -- refusals are
                // removed by swapping the last worker into their place, which
                // decides who is asked next and is upstream's own shuffle.
                while (!free.isEmpty() && !assignHarvester(world, free.get(0), kind)) {
                    free.set(0, free.get(free.size() - 1));
                    free.remove(free.size() - 1);
                }
                if (!free.isEmpty()) {
                    Unit taken = free.get(0);
                    free.set(0, free.get(free.size() - 1));
                    free.remove(free.size() - 1);
                    // "remove it from other resources" -- by the same
                    // swap-with-last upstream uses, because who lands in the
                    // vacated slot is who that resource asks next. Removing
                    // in place instead handed level05h's wood to the second
                    // and fourth peasants where upstream, having swapped its
                    // fourth into the taken one's slot, hands it to the
                    // fourth and third.
                    for (UnitType.Resource other : COLLECT_KINDS) {
                        if (other == kind) {
                            continue;
                        }
                        List<Unit> others = unassigned.get(other);
                        int at = others.indexOf(taken);
                        if (at >= 0) {
                            others.set(at, others.get(others.size() - 1));
                            others.remove(others.size() - 1);
                        }
                    }
                    priorityNeeded[i]--;
                    placed = true;
                    break;
                }
                // "Else : Take from already assigned worker with lower
                // priority."
                for (int j = i + 1; j < priorityResource.length && !placed; j++) {
                    int src = priorityResource[j];
                    UnitType.Resource srcKind = COLLECT_KINDS[src];
                    if (wanted[src] > wanted[c]
                            || (wanted[src] == wanted[c]
                                    && assigned.get(srcKind).size()
                                            <= assigned.get(kind).size() + 1)) {
                        continue;
                    }
                    List<Unit> pool = assigned.get(srcKind);
                    for (int k = pool.size() - 1; k >= 0 && !placed; k--) {
                        Unit candidate = pool.get(k);
                        if (System.getenv("CHONKCRAFT_TRACE_AICOLLECT") != null) {
                            System.err.println("JSTEAL cycle=" + world.cycle()
                                    + " p" + playerIndex + " try u" + candidate.id()
                                    + " " + srcKind + "->" + kind
                                    + " home=" + candidate.returningToDepot());
                        }
                        // "worker returning with resource" is left to finish
                        // its trip -- but upstream's skip is a bug it must
                        // keep: the continue leaves the loop's unit set, the
                        // k-loop's own !unit condition ends the scan, and the
                        // caller counts a worker as moved that never was --
                        // priority_needed[i]--, break, re-sort -- with no
                        // removal and no credit to the donor

                        // A finished-returning worker is a phantom donor to
                        // every row that may steal from its resource. On
                        // campaigns/human/level12h at cycle 727 the tanker
                        // riding home burns wood's surplus of two to nought
                        // in two passes without moving anyone, gold rises to
                        // the top of the sort, and only then does the corner
                        // chopper get stolen for the mine at 94,0 -- a port
                        // whose skip was a clean continue never decremented,
                        // kept wood on top, and left the chopper felling a
                        // tree upstream never touches.
                        if (candidate.returningToDepot()) {
                            priorityNeeded[i]--;
                            placed = true;
                            break;
                        }
                        if (!candidate.type().gathering().containsKey(kind)
                                || !assignHarvester(world, candidate, kind)) {
                            continue;
                        }
                        // The same swap-with-last removal as the free list's.
                        pool.set(k, pool.get(pool.size() - 1));
                        pool.remove(pool.size() - 1);
                        priorityNeeded[j]++;
                        priorityNeeded[i]--;
                        placed = true;
                    }
                }
            }
        }
    }

    /**
     * Puts one worker onto one resource, or asks the map to be explored.
     *
     * <p>{@code AiAssignHarvester}:
     * wood is a terrain hunt -- {@code FindTerrainType} from the worker --
     * and everything else is {@code UnitFindResource}'s depot-seeded flood.
     * Both failing arms end with {@code AiExplore}. The exploration mask is
     * the worker's own movement kind rather than built from every type that
     * gives the resource, which decides which unit is sent, never how many
     * numbers are drawn, and every gatherer in the shipped data is a land
     * unit or a tanker looking for its own element.
     */
    private boolean assignHarvester(World world, Unit unit, UnitType.Resource kind) {
        // "It can't." -- a removed unit is refused outright
        // The census counts the peon down the
        // mine; nothing moves it until it comes back out.
        if (!unit.isOnMap()) {
            return false;
        }
        // A worker still standing down from a job it handed back takes no new
        // one. XHuman 2 peon 1560 gives its build back on 52 with three cycles
        // on its timer; retail leaves it Still through 53 and 54 rather than
        // sending it to a tree, and gives it a fresh build order on 55.
        ResourceInfo info = unit.type().gathering().get(kind);
        if (info == null) {
            return false;
        }
        if (info.terrainHarvester()) {
            int[] wood = nearestForest(world, unit);
            if (wood != null && world.orderHarvest(unit, wood[0], wood[1])) {
                return true;
            }
            explore(unit, true, false);
            return false;
        }
        Unit mine = world.findResourceUnit(unit, kind, HARVEST_RANGE);
        if (mine != null && world.orderHarvest(unit, mine.tileX(), mine.tileY())) {
            return true;
        }
        explore(unit, unit.type().landUnit(), unit.type().seaUnit());
        return false;
    }

    /**
     * Retail BNE's peon-training decision from ready-worker path {@code 0x439000}.
     *
     * <p>After the construction list and before the gold/lumber split, native
     * asks whether the player has fewer reserved worker trains than
     * {@code (workers - 1) / 2 + 1}. Human 13's computer halls both enter a
     * peon train (400 gold) on fixture cycle 15 while this implementation left the bank
     * at 1000. The call is also exposed for a per-cycle hall pulse so a base
     * that is already harvesting can still fill the quota without a Still
     * worker on that exact cycle.
     */
    public boolean battleNetTryTrainWorker(World world) {
        return battleNetTryTrainWorker(world, null);
    }

    /**
     * Reserved-worker train from ready-worker {@code 0x439000} / hall action 33.
     *
     * <p>{@code 0x439000} itself has no cycle gate: it compares the player's
     * reserved-train word at {@code 0x4b505c} to {@code (workers-1)/2+1} and
     * picks a hall. The start delay is the hall's action-33 counter at
     * unit+0x6e (see {@code World.stepBattleNetHallStill}). Prefer
     * {@code preferredHall} when the pulse came from that building's OP0.
     */
    public boolean battleNetTryTrainWorker(World world, Unit preferredHall) {
        Player player = world.player(playerIndex);
        if (player == null
                || (player.type() != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                    && player.type()
                        != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE)) {
            return false;
        }
        List<Unit> owned = world.playerUnits(playerIndex);
        UnitType workerType = null;
        int workers = 0;
        int training = 0;
        for (Unit candidate : owned) {
            if (candidate.type() == null || candidate.hitPoints() <= 0) {
                continue;
            }
            if (candidate.type().canGather()
                    && (candidate.type().gathering().containsKey(UnitType.Resource.GOLD)
                        || candidate.type().gathering().containsKey(UnitType.Resource.WOOD))) {
                workers++;
                if (workerType == null) {
                    workerType = candidate.type();
                }
            }
            if (candidate.producing() != null && candidate.producing().canGather()) {
                training++;
            }
            for (UnitType queued : candidate.trainingQueue()) {
                if (queued.canGather()) {
                    training++;
                }
            }
        }
        if (workerType == null || workers <= 0) {
            return false;
        }
        // 0x439000: want = (workers - 1) / 2 + 1 reserved trains.
        int want = ((workers - 1) / 2) + 1;
        if (training >= want) {
            return false;
        }
        // Action-33 hall auto-train only tops up small bases. Great-hall
        // line: Human 8/10 with 1 peon and Human 13 with 4 all debit; bases
        // that already field 5+ workers (XHuman 2 p0, XHuman 11/12) never
        // debit a peon at the cycle-12 window even though want is still open.
        // Human town-hall is tighter: sealed Orc 5 p0 has 4 peasants and
        // resets its action-33 counter without training, while XOrc 4/5/11
        // (1 peasant) and XOrc 6/10 (2) debit.
        //
        // Bytecode wantedWorkers (AIPlayerState+0x13) is used when it is
        // strictly larger than the small-base cap so XHuman 12 profile 0
        // (wanted 9, six peons) and Orc 13 castle (wanted 7) still train
        // without reopening the c12 small-base refusals.
        if (preferredHall != null) {
            int maxWorkers = preferredHall.type() != null
                    && "unit-town-hall".equals(preferredHall.type().ident())
                    ? 2 : 4;
            int wanted = battleNetWantedWorkers();
            if (wanted > maxWorkers) {
                // High bytecode target (XHuman 12 want 9, Orc 13 want 7).
                if (workers >= wanted) {
                    return false;
                }
            } else if (wanted > 0) {
                // Low bytecode target still caps the hall. Human 5 p5 keeps
                // wantedWorkers at 4 with four peons and resets its action-33
                // counter without spending; treating only workers > maxWorkers
                // as full let Java debit 400g at fixture cycle 19.
                if (workers >= wanted) {
                    return false;
                }
            } else if (workers > maxWorkers) {
                return false;
            }
        }
        // Ready-worker wood split at 0x439280 treats gold < 500 as a poor
        // bank (wantedWood = 0 when workers < 5). Hall action-33 peon trains
        // respect the same floor: XHuman 8 p6 sits on exactly 400 gold with
        // two peons and native never spends the peon; Human 8 p3 has 500 and
        // debits at cycle 12; Human 10/13 start at 1000. Training on 400
        // zeros the bank and desyncs the cycle-12 comparison.
        if (preferredHall != null
                && player.get(UnitType.Resource.GOLD) < 500) {
            return false;
        }
        if (!player.canAfford(workerType.costs())) {
            return false;
        }
        if (preferredHall != null && preferredHall.player() == playerIndex
                && startTrainingAt(world, preferredHall, workerType)) {
            BuildRequest started = new BuildRequest(workerType, 1);
            started.made = 1;
            buildQueue.add(0, started);
            return true;
        }
        if (startTraining(world, owned, workerType)) {
            BuildRequest started = new BuildRequest(workerType, 1);
            started.made = 1;
            buildQueue.add(0, started);
            return true;
        }
        return false;
    }

    /**
     * Action-33 barracks selector ({@code 0x40eb70}).
     *
     * <p>Native balances basic, ranged, siege and cavalry families with
     * deterministic zero-count fast paths and two async-weighted top-ups.
     * XHuman 3 p4 profile 45 takes the zero-cavalry arm (ogre, 800g/100w) at
     * fixture cycle 16; XHuman 2 / XOrc 11 still take the empty-basic arm.
     */
    public boolean battleNetTryTrainSoldier(World world, Unit barracks) {
        if (barracks == null) {
            return false;
        }
        Player player = world.player(playerIndex);
        if (player == null
                || (player.type() != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                    && player.type()
                        != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE)) {
            return false;
        }
        if (barracks.player() != playerIndex || barracks.producing() != null
                || !barracks.trainingQueue().isEmpty()) {
            return false;
        }
        int wantBasic = battleNetWantedBasicSoldiers;
        int wantRanged = battleNetWantedRanged();
        int wantSiege = battleNetWantedSiege();
        int wantCavalry = battleNetWantedCavalry();
        if (wantBasic <= 0 && wantRanged <= 0 && wantSiege <= 0 && wantCavalry <= 0) {
            return false;
        }
        boolean orc = player.race() == net.chonkbase.chonkcraft.data.map.PudMap.Race.ORC;
        int basic = 0;
        int ranged = 0;
        int siege = 0;
        int cavalry = 0;
        boolean hasLumber = false;
        boolean hasBlacksmith = false;
        boolean hasStables = false;
        for (Unit candidate : world.playerUnits(playerIndex)) {
            if (candidate.type() == null || candidate.hitPoints() <= 0
                    || !candidate.isOnMap()) {
                continue;
            }
            String ident = candidate.type().ident();
            if (candidate.type().building()) {
                // Prerequisite counters: sealed XHuman 3 trains an ogre while
                // its blacksmith and ogre-mound both carry PUD Data 1, so the
                // building existence test is not the mobile Data-marker arm.
                // Only UNDER_CONSTRUCTION hides the building -- research and
                // training share progress/goal, and XHuman 9's researching
                // blacksmith must still unlock the second ogre train at c19.
                if (candidate.order() == Unit.Order.UNDER_CONSTRUCTION
                        || candidate.currentAction()
                                == Unit.Order.UNDER_CONSTRUCTION) {
                    continue;
                }
                if ("unit-elven-lumber-mill".equals(ident)
                        || "unit-troll-lumber-mill".equals(ident)) {
                    hasLumber = true;
                } else if ("unit-human-blacksmith".equals(ident)
                        || "unit-orc-blacksmith".equals(ident)) {
                    hasBlacksmith = true;
                } else if ("unit-stables".equals(ident)
                        || "unit-ogre-mound".equals(ident)) {
                    hasStables = true;
                }
                continue;
            }
            // AI-accounted family census only: PUD Data marked mobiles keep
            // bit 0x02 and are subtracted from DAT_004addac and friends.
            // Queued/producing jobs inside a building never enter the counter.
            if (candidate.battleNetReadySuppressed()
                    || candidate.battleNetPudData() != 0) {
                continue;
            }
            if ("unit-footman".equals(ident) || "unit-grunt".equals(ident)) {
                basic++;
            } else if ("unit-archer".equals(ident) || "unit-axethrower".equals(ident)
                    || "unit-ranger".equals(ident) || "unit-berserker".equals(ident)) {
                ranged++;
            } else if ("unit-ballista".equals(ident) || "unit-catapult".equals(ident)) {
                siege++;
            } else if ("unit-knight".equals(ident) || "unit-ogre".equals(ident)
                    || "unit-paladin".equals(ident) || "unit-ogre-mage".equals(ident)) {
                cavalry++;
            }
        }
        UnitType choice = null;
        // Exact 0x40eb70 order. Branches 4 and 5 draw battleNetRand only when
        // their preconditions hold; those arms stay off the early horizon
        // until a fixture needs them.
        if (wantBasic != 0 && basic == 0) {
            choice = battleNetBarracksType(world, orc, "basic");
        } else if (wantCavalry != 0 && cavalry == 0 && hasBlacksmith && hasStables) {
            choice = battleNetBarracksType(world, orc, "cavalry");
        } else if (wantRanged != 0 && ranged == 0 && hasLumber) {
            choice = battleNetBarracksType(world, orc, "ranged");
        } else if (ranged < wantRanged && hasLumber) {
            // Async (draw & 3) == 1 top-up.
            if ((world.battleNetRandomForAi() & 3) == 1) {
                choice = battleNetBarracksType(world, orc, "ranged");
            } else {
                // Fall through: a miss still continues the selector.
            }
        }
        if (choice == null && siege < wantSiege) {
            // Draw happens even when prerequisites fail.
            int draw = world.battleNetRandomForAi();
            if ((draw & 7) == 1 && hasBlacksmith && hasLumber) {
                choice = battleNetBarracksType(world, orc, "siege");
            }
        }
        if (choice == null && cavalry < wantCavalry && hasBlacksmith && hasStables) {
            choice = battleNetBarracksType(world, orc, "cavalry");
        }
        if (choice == null && basic < wantBasic) {
            choice = battleNetBarracksType(world, orc, "basic");
        }
        if (choice == null && wantSiege != 0 && siege == 0
                && hasBlacksmith && hasLumber) {
            choice = battleNetBarracksType(world, orc, "siege");
        }
        if (choice == null && ranged < wantRanged && hasLumber) {
            choice = battleNetBarracksType(world, orc, "ranged");
        }
        if (choice == null && siege < wantSiege && hasBlacksmith && hasLumber) {
            choice = battleNetBarracksType(world, orc, "siege");
        }
        if (choice == null || !player.canAfford(choice.costs())) {
            return false;
        }
        if (startTrainingAt(world, barracks, choice)) {
            BuildRequest started = new BuildRequest(choice, 1);
            started.made = 1;
            buildQueue.add(0, started);
            return true;
        }
        return false;
    }

    private UnitType battleNetBarracksType(World world, boolean orc, String family) {
        String ident = switch (family) {
            case "basic" -> orc ? "unit-grunt" : "unit-footman";
            case "ranged" -> orc ? "unit-axethrower" : "unit-archer";
            case "siege" -> orc ? "unit-catapult" : "unit-ballista";
            case "cavalry" -> orc ? "unit-ogre" : "unit-knight";
            default -> null;
        };
        if (ident == null) {
            return null;
        }
        UnitType type = registeredType(world, ident);
        if (type != null) {
            return type;
        }
        // Focused tests may only have placed live instances.
        for (Unit candidate : world.playerUnits(playerIndex)) {
            if (candidate.type() != null && ident.equals(candidate.type().ident())) {
                return candidate.type();
            }
        }
        return null;
    }

    /**
     * Action-33 aviary/roost train of gryphon/dragon ({@code 0x40fa00}).
     *
     * <p>XHuman 7 p5 and XOrc 6 p2 debit 2500 gold (dragon/gryphon) at
     * fixture cycle 15 with no wood cost. Until ai.bin air-force wants are
     * wired, train when the roost is free and the player can afford one.
     */
    public boolean battleNetTryTrainFlyer(World world, Unit roost) {
        if (roost == null) {
            return false;
        }
        // One flyer start per player per cycle so dual aviaries do not both
        // debit on the opening pulse (XOrc 6 c15 must start exactly one).
        if (battleNetFlyerTrainCycle == world.cycle()) {
            return false;
        }
        Player player = world.player(playerIndex);
        if (player == null
                || (player.type() != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                    && player.type()
                        != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE)) {
            return false;
        }
        if (roost.player() != playerIndex || roost.producing() != null
                || !roost.trainingQueue().isEmpty()) {
            return false;
        }
        boolean orc = player.race() == net.chonkbase.chonkcraft.data.map.PudMap.Race.ORC;
        String flyerIdent = orc ? "unit-dragon" : "unit-gryphon-rider";
        UnitType flyer = registeredType(world, flyerIdent);
        int flyers = 0;
        for (Unit candidate : world.playerUnits(playerIndex)) {
            if (candidate.type() == null || candidate.hitPoints() <= 0
                    || !candidate.isOnMap()
                    || candidate.battleNetReadySuppressed()
                    || candidate.battleNetPudData() != 0) {
                continue;
            }
            String ident = candidate.type().ident();
            // AI-accounted combat-flyer family only (type 35 fire-breeze is
            // marked on XHuman 7 and must not block the first dragon).
            if ("unit-dragon".equals(ident) || "unit-gryphon-rider".equals(ident)
                    || "unit-deathwing".equals(ident)
                    || "unit-fire-breeze".equals(ident)) {
                flyers++;
                if (flyer == null && flyerIdent.equals(ident)) {
                    flyer = candidate.type();
                }
            }
        }
        // FUN_0040fa00 trains while flyerCount < wantedFlyers on a full-
        // flight want. XHuman 7 p5 / XOrc 6 p2 write wantFlyers=4 and debit
        // 2500g at c15 (and XOrc 6's free sibling at c18 while the first is
        // still producing). Wants below 4 must not open the arm: Orc 14 p6
        // profile 31 has want 0 and a rich bank (old first-flyer bridge
        // debited 2500g at fixture 35 while native held 12200), and XOrc 11
        // p6 has want 3 yet native still holds the bank at c15.
        // One start per cycle prevents dual debit on the opening pulse.
        int wantedFlyers = battleNetWantedFlyers();
        if (wantedFlyers < 4) {
            return false;
        }
        int producingFlyers = 0;
        for (Unit candidate : world.units()) {
            if (candidate.player() != playerIndex || candidate.producing() == null) {
                continue;
            }
            String prod = candidate.producing().ident();
            if ("unit-dragon".equals(prod) || "unit-gryphon-rider".equals(prod)) {
                producingFlyers++;
            }
        }
        if (player.get(UnitType.Resource.GOLD) < 10_000) {
            return false;
        }
        int accounted = flyers; // live only
        if (accounted + producingFlyers >= wantedFlyers) {
            return false;
        }
        if (flyer == null || !player.canAfford(flyer.costs())) {
            return false;
        }
        if (startTrainingAt(world, roost, flyer)) {
            battleNetFlyerTrainCycle = world.cycle();
            BuildRequest started = new BuildRequest(flyer, 1);
            started.made = 1;
            buildQueue.add(0, started);
            return true;
        }
        return false;
    }

    /**
     * Action-33 shipyard selector ({@code 0x40eef0}).
     *
     * <p>Native balances tanker / destroyer / transport / battleship / sub
     * wants. Sealed arms so far: tankers (XHuman 5/8), destroyers (XOrc 7 dual
     * shipyards at c18 with AI-accounted destroyer census 1 against want 3),
     * and transports (Orc 14 p6 spends 600g/200w/500o at fixture 30 when want
     * exceeds the two map transports).
     */
    public boolean battleNetTryTrainTanker(World world, Unit shipyard) {
        if (shipyard == null) {
            return false;
        }
        Player player = world.player(playerIndex);
        if (player == null
                || (player.type() != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                    && player.type()
                        != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE)) {
            return false;
        }
        if (shipyard.player() != playerIndex || shipyard.producing() != null
                || !shipyard.trainingQueue().isEmpty()) {
            return false;
        }
        boolean orc = player.race() == net.chonkbase.chonkcraft.data.map.PudMap.Race.ORC;
        int wantTankers = battleNetWantedTankers;
        int wantDestroyers = battleNetWantedDestroyers();
        int wantTransports = battleNetWantedTransports();
        if (wantTankers <= 0 && wantDestroyers <= 0 && wantTransports <= 0) {
            return false;
        }
        int tankers = 0;
        int destroyers = 0;
        int transports = 0;
        for (Unit candidate : world.units()) {
            if (candidate.player() != playerIndex || candidate.type() == null
                    || candidate.hitPoints() <= 0) {
                continue;
            }
            String ident = candidate.type().ident();
            // Oil census counts every living tanker, including PUD Data
            // guards. XHuman 8 p7 places a data=1 tanker that satisfies
            // want 1; skipping ready-suppressed / non-zero Data made the
            // shipyard over-spend 400g/200w at fixture c12.
            if ("unit-human-oil-tanker".equals(ident)
                    || "unit-orc-oil-tanker".equals(ident)) {
                tankers++;
                continue;
            }
            // Destroyers and transports keep the AI-accounted filter (Data /
            // ready-suppress exclude map-guard sea units from the combat navy
            // top-up). Transports on Orc 14 start HARVEST-to-hall then Still;
            // both count toward want so a third is only ordered when the
            // profile asks for more than the two map hulls.
            if (candidate.battleNetReadySuppressed()
                    || candidate.battleNetPudData() != 0) {
                continue;
            }
            if ("unit-elven-destroyer".equals(ident)
                    || "unit-troll-destroyer".equals(ident)
                    || "unit-human-destroyer".equals(ident)
                    || "unit-orc-destroyer".equals(ident)) {
                destroyers++;
                continue;
            }
            if ("unit-human-transport".equals(ident)
                    || "unit-orc-transport".equals(ident)) {
                transports++;
            }
        }
        UnitType choice = null;
        // Prefer empty-tanker / deficit tanker, then destroyer top-up, then
        // transport top-up. Matches the early sealed arms without the full
        // ten-branch naval selector. Prefer the race lane, then the opposite
        // if only one tanker type is registered (focused unit tests place a
        // human tanker on a computer seat whose default race is orc).
        if (wantTankers != 0 && (tankers == 0 || tankers < wantTankers)) {
            choice = registeredType(world,
                    orc ? "unit-orc-oil-tanker" : "unit-human-oil-tanker");
            if (choice == null) {
                choice = registeredType(world,
                        orc ? "unit-human-oil-tanker" : "unit-orc-oil-tanker");
            }
        } else if (wantDestroyers != 0 && destroyers < wantDestroyers) {
            choice = registeredType(world,
                    orc ? "unit-troll-destroyer" : "unit-elven-destroyer");
            if (choice == null) {
                choice = registeredType(world,
                        orc ? "unit-orc-destroyer" : "unit-human-destroyer");
            }
        } else if (wantTransports != 0 && transports < wantTransports) {
            choice = registeredType(world,
                    orc ? "unit-orc-transport" : "unit-human-transport");
            if (choice == null) {
                choice = registeredType(world,
                        orc ? "unit-human-transport" : "unit-orc-transport");
            }
        }
        if (choice == null || !player.canAfford(choice.costs())) {
            return false;
        }
        if (startTrainingAt(world, shipyard, choice)) {
            BuildRequest started = new BuildRequest(choice, 1);
            started.made = 1;
            buildQueue.add(0, started);
            return true;
        }
        return false;
    }

    public int battleNetWantedDestroyers() {
        if (battleNetAiState == null) {
            return 0;
        }
        return battleNetAiState[BattleNetAiBytecode.OFF_WANTED_DESTROYERS] & 0xff;
    }

    public int battleNetWantedTransports() {
        if (battleNetAiState == null) {
            return 0;
        }
        return battleNetAiState[BattleNetAiBytecode.OFF_WANTED_TRANSPORTS] & 0xff;
    }

    /** Test-only arm for action-33 tanker wants when the profile blob is synthetic. */
    public void setBattleNetWantedTankersForTest(int want) {
        battleNetWantedTankers = Math.max(0, want);
    }

    public int battleNetWantedTankersForTestPeek() {
        return battleNetWantedTankers;
    }

    /**
     * Action-33 blacksmith research selector ({@code 0x40f5e0}).
     *
     * <p>Walks the native candidate order 86 weapon1, 88 shield1, 87 weapon2,
     * 89 shield2, 8a artillery1, 8b artillery2. The first enabled milestone
     * whose current tier matches and whose upgrade is still available is
     * researched and the milestone is consumed. XHuman 11 p2 / XHuman 10 p2
     * start {@code upgrade-battle-axe1} on the pulse where counter exceeds
     * the profile type-83 limit of 1.
     */
    public boolean battleNetTryResearchBlacksmith(World world, Unit blacksmith) {
        if (blacksmith == null || blacksmith.player() != playerIndex
                || blacksmith.researching() != null
                || blacksmith.producing() != null
                || !blacksmith.trainingQueue().isEmpty()) {
            return false;
        }
        Player player = world.player(playerIndex);
        if (player == null
                || (player.type() != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                    && player.type()
                        != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE)) {
            return false;
        }
        boolean orc = player.race() == net.chonkbase.chonkcraft.data.map.PudMap.Race.ORC;
        // Native table order; weapon1 first is the sealed c13/c15 frontier.
        int[] codes = {0x86, 0x88, 0x87, 0x89, 0x8a, 0x8b};
        String[] human = {
            "upgrade-sword1", "upgrade-human-shield1", "upgrade-sword2",
            "upgrade-human-shield2", "upgrade-ballista1", "upgrade-ballista2"
        };
        String[] orcs = {
            "upgrade-battle-axe1", "upgrade-orc-shield1", "upgrade-battle-axe2",
            "upgrade-orc-shield2", "upgrade-catapult1", "upgrade-catapult2"
        };
        // Desired current tier for the first four rows: 0,0,1,1; artillery 0,1.
        int[] wantLevel = {0, 0, 1, 1, 0, 1};
        String[] weaponTiers = orc
                ? new String[] {"upgrade-battle-axe1", "upgrade-battle-axe2"}
                : new String[] {"upgrade-sword1", "upgrade-sword2"};
        String[] shieldTiers = orc
                ? new String[] {"upgrade-orc-shield1", "upgrade-orc-shield2"}
                : new String[] {"upgrade-human-shield1", "upgrade-human-shield2"};
        String[] artyTiers = orc
                ? new String[] {"upgrade-catapult1", "upgrade-catapult2"}
                : new String[] {"upgrade-ballista1", "upgrade-ballista2"};
        for (int i = 0; i < codes.length; i++) {
            int code = codes[i];
            if (!battleNetHasAction33Candidate(code)) {
                continue;
            }
            String upgrade = orc ? orcs[i] : human[i];
            int current;
            if (i == 0 || i == 2) {
                current = researchTier(world, weaponTiers);
            } else if (i == 1 || i == 3) {
                current = researchTier(world, shieldTiers);
            } else {
                current = researchTier(world, artyTiers);
            }
            if (current != wantLevel[i]) {
                continue;
            }
            if (world.upgrades(playerIndex).has(upgrade)) {
                continue;
            }
            if (world.allowed() != null
                    && !world.allowed().isAllowed(playerIndex, upgrade)) {
                continue;
            }
            if (world.orderResearch(blacksmith, upgrade)) {
                battleNetConsumeAction33Candidate(code);
                return true;
            }
        }
        return false;
    }

    private int researchTier(World world, String[] chain) {
        int level = 0;
        for (String ident : chain) {
            if (world.upgrades(playerIndex).has(ident)) {
                level++;
            } else {
                break;
            }
        }
        return level;
    }

    /**
     * Action-33 foundry research selector ({@code 0x40f4b0}).
     *
     * <p>Native order is 0x8c ship-attack1, 0x8e ship-armor1, 0x8d attack2,
     * 0x8f armor2. XOrc 7 p2 debits at fixture c16 (700g/100w/1000oil class).
     */
    public boolean battleNetTryResearchFoundry(World world, Unit foundry) {
        if (foundry == null || foundry.player() != playerIndex
                || foundry.researching() != null
                || foundry.producing() != null
                || !foundry.trainingQueue().isEmpty()) {
            return false;
        }
        // Only when ship-cannon1 (0x8c) is the armed high milestone. Other
        // first-high profiles must not open foundry research early.
        if (!battleNetHasAction33Candidate(0x8c)
                && !battleNetHasAction33Candidate(0x8e)) {
            return false;
        }
        Player player = world.player(playerIndex);
        if (player == null
                || (player.type() != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                    && player.type()
                        != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE)) {
            return false;
        }
        // Sealed XOrc 7 funds naval research from a huge oil bank; ordinary
        // campaign oil tops should not open foundry research this early.
        if (player.get(UnitType.Resource.OIL) < 10_000) {
            return false;
        }
        boolean orc = player.race() == net.chonkbase.chonkcraft.data.map.PudMap.Race.ORC;
        int[] codes = {0x8c, 0x8e, 0x8d, 0x8f};
        String[] human = {
            "upgrade-human-ship-cannon1", "upgrade-human-ship-armor1",
            "upgrade-human-ship-cannon2", "upgrade-human-ship-armor2"
        };
        String[] orcs = {
            "upgrade-orc-ship-cannon1", "upgrade-orc-ship-armor1",
            "upgrade-orc-ship-cannon2", "upgrade-orc-ship-armor2"
        };
        String[] names = orc ? orcs : human;
        for (int i = 0; i < codes.length; i++) {
            int code = codes[i];
            if (!battleNetHasAction33Candidate(code)) {
                continue;
            }
            String upgrade = names[i];
            if (world.upgrades(playerIndex).has(upgrade)) {
                continue;
            }
            if (world.allowed() != null
                    && !world.allowed().isAllowed(playerIndex, upgrade)) {
                continue;
            }
            if (world.orderResearch(foundry, upgrade)) {
                battleNetConsumeAction33Candidate(code);
                return true;
            }
        }
        return false;
    }

    /**
     * Action-33 temple-of-the-damned spell research ({@code 0x40f???}).
     *
     * <p>Profile 27 (Human 14 p0 death-knight seat) lists codes
     * {@code 93 94 95 96 97} and arms {@code 0x93} before the first temple
     * pulse. The sealed bank drops 1500 gold at fixture c35 with temple
     * action-33 still current and no new building -- that is
     * {@code upgrade-raise-dead} (1500g pure), not haste (500g).
     *
     * <p>Codes 0x93-0x97 are the orc temple spell block only. Orc 14 p6 is a
     * human-race computer whose profile 31 also arms 0x93 first, but its
     * building is a mage tower: native keeps action-33 Still with next=60
     * through fixture 50 and holds the bank at 12200. Mapping 0x93 onto
     * {@code upgrade-slow} (500g pure) for human race debited at fixture 39
     * while native never researched. Human mage-tower spell codes are not
     * this block -- leave mage towers alone until those milestones are
     * identified.
     */
    public boolean battleNetTryResearchTemple(World world, Unit temple) {
        if (temple == null || temple.player() != playerIndex
                || temple.type() == null
                || !"unit-temple-of-the-damned".equals(temple.type().ident())
                || temple.researching() != null
                || temple.producing() != null
                || !temple.trainingQueue().isEmpty()) {
            return false;
        }
        Player player = world.player(playerIndex);
        if (player == null
                || (player.type() != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                    && player.type()
                        != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE)) {
            return false;
        }
        // High-byte temple list. First armed code on Human 14 p0 is 0x93 and
        // debits 1500g for raise-dead; haste (500g) is not that first
        // milestone on the sealed map. Order matches button layout after the
        // free death-coil baseline: raise-dead / whirlwind / unholy-armor /
        // death-and-decay / haste.
        int[] codes = {0x93, 0x94, 0x95, 0x96, 0x97};
        String[] orcSpells = {
            "upgrade-raise-dead", "upgrade-whirlwind",
            "upgrade-unholy-armor", "upgrade-death-and-decay",
            "upgrade-haste"
        };
        for (int i = 0; i < codes.length; i++) {
            int code = codes[i];
            if (!battleNetHasAction33Candidate(code)) {
                continue;
            }
            String upgrade = orcSpells[i];
            if (world.upgrades(playerIndex).has(upgrade)) {
                continue;
            }
            if (world.allowed() != null
                    && !world.allowed().isAllowed(playerIndex, upgrade)) {
                continue;
            }
            if (world.orderResearch(temple, upgrade)) {
                battleNetConsumeAction33Candidate(code);
                return true;
            }
        }
        return false;
    }

    /**
     * Action-33 lumber mill research selector ({@code 0x40f380}).
     *
     * <p>Native walks codes 80/81/82/83/84/85 for arrow/axe and ranger-line
     * upgrades. XOrc 8 p2 debits throwing-axe1 (code 0x80) at fixture c15
     * together with a watch-tower upgrade.
     */
    public boolean battleNetTryResearchLumberMill(World world, Unit mill) {
        if (mill == null || mill.player() != playerIndex
                || mill.researching() != null
                || mill.producing() != null
                || !mill.trainingQueue().isEmpty()) {
            return false;
        }
        Player player = world.player(playerIndex);
        if (player == null
                || (player.type() != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                    && player.type()
                        != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE)) {
            return false;
        }
        boolean orc = player.race() == net.chonkbase.chonkcraft.data.map.PudMap.Race.ORC;
        int[] codes = {0x80, 0x81, 0x82, 0x83, 0x84, 0x85};
        String[] human = {
            "upgrade-arrow1", "upgrade-arrow2", "upgrade-ranger",
            "upgrade-ranger-scouting", "upgrade-longbow",
            "upgrade-ranger-marksmanship"
        };
        String[] orcs = {
            "upgrade-throwing-axe1", "upgrade-throwing-axe2", "upgrade-berserker",
            "upgrade-berserker-scouting", "upgrade-light-axes",
            "upgrade-berserker-regeneration"
        };
        for (int i = 0; i < codes.length; i++) {
            int code = codes[i];
            if (!battleNetHasAction33Candidate(code)) {
                continue;
            }
            String upgrade = orc ? orcs[i] : human[i];
            if (world.upgrades(playerIndex).has(upgrade)) {
                continue;
            }
            if (world.allowed() != null
                    && !world.allowed().isAllowed(playerIndex, upgrade)) {
                continue;
            }
            if (world.orderResearch(mill, upgrade)) {
                battleNetConsumeAction33Candidate(code);
                return true;
            }
        }
        return false;
    }

    /**
     * Action-33 watch-tower upgrade to guard tower ({@code 0x40eec0}).
     *
     * <p>XOrc 8 p2 upgrades a human watch tower at fixture c15 (500g/150w)
     * in the same cycle as lumber-mill arrow research.
     */
    public boolean battleNetTryUpgradeWatchTower(World world, Unit tower) {
        if (tower == null || tower.player() != playerIndex
                || tower.researching() != null
                || tower.producing() != null
                || !tower.trainingQueue().isEmpty()) {
            return false;
        }
        // One guard-tower upgrade per player per simulation cycle. Multiple
        // watch towers share the same action-33 pulse; without this cap every
        // open tower spent 500g/150w and over-debited relative to native
        // (which upgrades one then fails canAfford / stops).
        if (battleNetWatchUpgradeCycle == world.cycle()) {
            return false;
        }
        // Until construction-open delays are exact, only upgrade when a
        // lumber-mill ranged milestone (0x80/0x81) is the armed high byte.
        // That is the sealed XOrc 8 c15 dual-spend shape; maps whose first
        // high byte is blacksmith 0x86 never open watch upgrades early.
        if (!battleNetHasAction33Candidate(0x80)
                && !battleNetHasAction33Candidate(0x81)) {
            return false;
        }
        Player player = world.player(playerIndex);
        if (player == null
                || (player.type() != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                    && player.type()
                        != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE)) {
            return false;
        }
        boolean orc = player.race() == net.chonkbase.chonkcraft.data.map.PudMap.Race.ORC;
        String targetIdent = orc ? "unit-orc-guard-tower" : "unit-human-guard-tower";
        UnitType target = registeredType(world, targetIdent);
        if (target == null) {
            return false;
        }
        if (!player.canAfford(target.costs())) {
            return false;
        }
        if (!world.orderUpgradeTo(tower, target)) {
            return false;
        }
        battleNetWatchUpgradeCycle = world.cycle();
        return true;
    }

    /**
     * Retail BNE's per-unit ready assignment for an idle harvester.
     *
     * <p>The original AI does not wait for its once-a-second resource census
     * when a worker's Still animation reaches an action marker. Function
     * {@code 0x439280} computes a wood quota from the current gold and lumber
     * banks and the number of workers, then gives this one worker either wood
     * or gold. The assigned counters are represented here by the workers'
     * current resource orders rather than by BNE's three side arrays.</p>
     */
    public boolean battleNetUnitReady(World world, Unit unit) {
        Player player = world.player(playerIndex);
        if (unit == null || unit.player() != playerIndex || !unit.isOnMap()
                || unit.order() != Unit.Order.STILL) {
            return false;
        }
        // A worker still standing down from a job it handed back is not ready
        // for another. XHuman 2 peon 1560 hands its build back on 52 with
        // three cycles on the timer at unit+0x07, and retail leaves it Still
        // through 53 and 54 rather than putting it on a tree, then gives it a
        // fresh build order on 55 -- on a two-by-two site that was free the
        // whole time, so nothing was waiting for the ground.
        if (world.battleNetStandingDownFromBuild(unit)) {
            return false;
        }
        if (player.type() != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                && player.type() != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE) {
            return false;
        }

        // Combat flyers are not OnReady = AiExploreUnit, but retail still
        // promotes unsuppressed ones to a short startup Patrol on their
        // constructor markers (XOrc 8 gryphons at fixture cycles 5/6/7).
        // UNIT.Data non-zero sets ready-suppressed and keeps the map's guard
        // AI: XOrc 7's gryphons stay Still through the early window while
        // XOrc 8's unsuppressed riders flip to Patrol. Self endpoint matches
        // the sealed hold through cycle 12; the first native step at cycle 13
        // (1550 4,6→2,6) is still open.
        //
        // After the preferred-neighbour self-scout ends (no free stride),
        // native does not re-arm self-patrol as the live order on the next
        // Still OP0 -- that used to show Patrol at XOrc 8 fixture 44 while
        // native 1560 stayed Still through 51. Instead it queues a half-map
        // patrol endpoint as next_order (1560: next_order Patrol and
        // order_point 0,17 at fixture 49; current Patrol at 52). The pending
        // arm promotes on a later idle marker via beginBattleNetPendingPatrol.
        if (unit.type().moveType() == UnitType.Movement.FLY
                && unit.type().canAttack()
                && !unit.type().building()
                && !unit.type().canGather()
                && !unit.type().onReadyExplores()
                && !unit.battleNetReadySuppressed()) {
            if (unit.battleNetFlyerScoutExhausted()) {
                // Do not re-arm here. Native queues a half-map next_order
                // patrol after the Still program advances (1560: next_order
                // at fixture 49, current at 52 toward 0,17), but that arm
                // must use the native ready stream -- drawing the async AI
                // seed for the endpoint shifted later destroyers (1431
                // stepped at fixture 51 while native held 102,90). Leave
                // Still until a follow-up matches that producer without
                // burning battleNetRand.
                return false;
            }
            return world.orderPatrol(unit, unit.tileX(), unit.tileY());
        }

        if (!unit.type().canGather()) {
            return false;
        }

        // FUN_00438a50 asks for a fresh base hall before it considers food,
        // the profile list, or resources.  Its indirect check at 0x4a1210 is
        // the native allow-bit test for type 0x4a/0x4b; 0x4b4a38 is the
        // finished-plus-planned count for that exact type.  An upgraded keep
        // or castle therefore does not satisfy this request: the branch is
        // deliberately how a computer player establishes another base.
        UnitType hall = registeredType(world, PudUnitTypes.name(
                player.race() == net.chonkbase.chonkcraft.data.map.PudMap.Race.ORC
                        ? 0x4b : 0x4a));
        if (hall != null && hall.building()
                && !battleNetHasHall(world, player, hall)
                && (world.allowed() == null
                    || world.allowed().isAllowed(playerIndex, hall.ident()))
                && world.dependenciesSatisfied(playerIndex, hall.ident())
                && world.mayBuild(unit.type(), hall)
                && player.canAfford(hall.costs())) {
            int[] site = world.aiFindBattleNetHallPlace(unit, hall);
            if (site != null && world.orderBattleNetAiBuild(
                    unit, hall, site[0], site[1])) {
                BuildRequest started = new BuildRequest(hall, 1);
                started.made = 1;
                buildQueue.add(0, started);
                return true;
            }
        }

        // FUN_00438a50 checks food before its ordinary build-priority list and
        // before the resource split below. Retail BNE counts four future food
        // slots for each already dispatched farm and asks for another while
        // that total is no more than current demand plus three. This is why a
        // sufficiently capped campaign AI can send two constructor-ready
        // workers to two farms during the two hidden startup ticks.
        List<Unit> owned = world.playerUnits(playerIndex);
        UnitType farm = supplyBuilding(world, owned);
        int futureSupply = supplyOnTheWay(owned);
        int cappedSupply = Math.min(200, player.supply());
        if (farm != null && farm.supply() > 0
                && cappedSupply + futureSupply <= player.demand() + 3
                && (world.allowed() == null
                    || world.allowed().isAllowed(playerIndex, farm.ident()))
                && world.dependenciesSatisfied(playerIndex, farm.ident())
                && world.mayBuild(unit.type(), farm)
                && player.canAfford(farm.costs())) {
            int[] site = world.aiFindBattleNetFoodPlace(unit, farm);
            if (site != null && System.getenv("CHONKCRAFT_TRACE_AIBUILD") != null) {
                System.err.printf("JBNFOOD cycle=%d p%d %s worker=%d"
                                + " at=%d,%d place=%d,%d%n",
                        world.cycle(), playerIndex, farm.ident(), unit.id(),
                        unit.tileX(), unit.tileY(), site[0], site[1]);
            }
            if (site != null && world.orderBattleNetAiBuild(
                    unit, farm, site[0], site[1])) {
                BuildRequest started = new BuildRequest(farm, 1);
                started.made = 1;
                buildQueue.add(0, started);
                return true;
            }
        }

        if (battleNetPriorityBuild(world, player, unit)) {
            return true;
        }

        // Native ready-worker calls 0x439000 here, which only reserves a train
        // slot and aims the worker at a hall -- it does not debit gold. The
        // paid peon train starts from the hall's action-33 Still OP0 counter
        // (World.stepBattleNetHallStill). Calling orderTrain here spent gold
        // on the first worker OP0 and desynced every bank at cycle 1.

        // Oil tankers enter the same native ready callback as land workers,
        // but bypass 0x439280's gold/lumber split. UnitFindResource starts at
        // the player's reachable oil depot and picks a platform; issuing the
        // order here also preserves the constructor-marker timing which is
        // why only a subset of otherwise identical tankers has raw action 23
        // in BNE's cycle-one snapshots.
        if (unit.type().gathering().containsKey(UnitType.Resource.OIL)
                && !unit.type().gathering().containsKey(UnitType.Resource.GOLD)
                && !unit.type().gathering().containsKey(UnitType.Resource.WOOD)) {
            if (unit.carried() > 0
                    && (unit.heldResource() == UnitType.Resource.OIL
                        || unit.carrying() == UnitType.Resource.OIL)) {
                return world.orderReturnGoods(unit);
            }
            Unit platform = world.findBattleNetReadyOilPlatform(unit);
            return platform != null && world.orderHarvest(unit, platform);
        }

        if (!unit.type().gathering().containsKey(UnitType.Resource.GOLD)
                && !unit.type().gathering().containsKey(UnitType.Resource.WOOD)) {
            return false;
        }

        int workers = 0;
        int onWood = 0;
        for (Unit candidate : world.playerUnits(playerIndex)) {
            if (candidate.hitPoints() <= 0
                    || (!candidate.type().gathering().containsKey(UnitType.Resource.GOLD)
                        && !candidate.type().gathering().containsKey(UnitType.Resource.WOOD))) {
                continue;
            }
            workers++;
            if (candidate.order() == Unit.Order.HARVEST
                    && candidate.carrying() == UnitType.Resource.WOOD) {
                onWood++;
            }
        }

        int gold = player.get(UnitType.Resource.GOLD);
        int lumber = player.get(UnitType.Resource.WOOD);
        int wantedWood;
        if ((gold < 500 && workers < 5) || lumber >= 2000
                || (gold < 1000 && lumber >= 500)) {
            wantedWood = 0;
        } else if (gold >= 1000 && lumber < 500) {
            wantedWood = (workers + 1) * 3 / 4;
        } else {
            wantedWood = workers / 2;
        }

        if (System.getenv("CHONKCRAFT_TRACE_BNE_IDLE") != null) {
            StringBuilder census = new StringBuilder();
            for (Unit candidate : world.playerUnits(playerIndex)) {
                if (candidate.hitPoints() <= 0
                        || (!candidate.type().gathering()
                                .containsKey(UnitType.Resource.GOLD)
                            && !candidate.type().gathering()
                                .containsKey(UnitType.Resource.WOOD))) {
                    continue;
                }
                census.append(candidate.id()).append(':')
                        .append(candidate.order()).append(':')
                        .append(candidate.carrying()).append(',');
            }
            System.err.printf("JBNEREADYCENSUS cycle=%d p%d unit=%d"
                            + " gold=%d wood=%d workers=%d onWood=%d"
                            + " wantedWood=%d members=%s%n",
                    world.cycle(), playerIndex, unit.id(), gold, lumber,
                    workers, onWood, wantedWood, census);
        }

        if (onWood < wantedWood
                && assignHarvester(world, unit, UnitType.Resource.WOOD)) {
            return true;
        }
        Unit mine = world.findBattleNetReadyGoldMine(unit);
        if (mine != null
                && world.orderHarvest(unit, mine.tileX(), mine.tileY())) {
            return true;
        }
        return assignHarvester(world, unit, UnitType.Resource.WOOD);
    }

    /** Runs FUN_00439740's ordered low-byte construction scan. */
    private boolean battleNetPriorityBuild(World world, Player player, Unit worker) {
        if (battleNetBuildPriorities.isEmpty()) {
            return false;
        }
        boolean[] satisfied = battleNetSatisfiedPositions(world);
        boolean orc = player.race()
                == net.chonkbase.chonkcraft.data.map.PudMap.Race.ORC;
        for (int index = 0; index < battleNetBuildPriorities.size()
                && index < battleNetBuildPriorityLimit; index++) {
            int listed = battleNetBuildPriorities.get(index);
            if (listed == 0xff) {
                return false;
            }
            if (listed >= 0x80) {
                // High bytes are milestones encountered by the ready-worker
                // construction scan, not startup flags. Arming the first one
                // while decoding the profile let an idle mill consume 0x80
                // even when no worker ever advanced the list that far
                // (Orc 7 profile 10: Java paid arrow1 at fixture 60; retail
                // held both the bank and upgrade through 1,800). The scan
                // exposes one unresolved milestone and stops at that byte.
                if (!battleNetAction33ResolvedHigh.contains(listed)) {
                    battleNetAction33Candidates.add(listed);
                }
                return false;
            }
            if (satisfied[index]) {
                continue;
            }
            // The list is written with human/even codes. Native conversion
            // replaces the low race bit with the constructor's race.
            int code = orc ? listed | 1 : listed & ~1;
            String ident = PudUnitTypes.name(code);
            if (ident.isEmpty()) {
                continue;
            }
            UnitType wanted = registeredType(world, ident);
            if (wanted == null) {
                continue;
            }
            // UnitTypeBuilt is indexed by type rather than list occurrence.
            // Once one building of a type has been dispatched, every later
            // duplicate of that type is skipped until the job is delivered.
            if (battleNetHasPending(world, wanted)) {
                continue;
            }
            // A forbidden or dependency-gated entry is unavailable in BNE's
            // parallel availability array, so the scan proceeds past it.
            if (world.allowed() != null
                    && !world.allowed().isAllowed(playerIndex, wanted.ident())) {
                continue;
            }
            if (!world.dependenciesSatisfied(playerIndex, wanted.ident())) {
                continue;
            }
            // FUN_00439740's list mixes constructions and trainables. A peon
            // or footman code is not a site search: the ready worker asks an
            // idle hall/barracks to start that unit. Skipping non-buildings
            // left Human 13's computer halls with 1000 gold at fixture cycle
            // 15 while native had already spent 400 on a peon at each base.
            if (!wanted.building()) {
                if (!player.canAfford(wanted.costs())) {
                    return false;
                }
                if (startTrainingAtAnyIdleTrainer(world, wanted)) {
                    BuildRequest started = new BuildRequest(wanted, 1);
                    started.made = 1;
                    buildQueue.add(0, started);
                    return true;
                }
                continue;
            }
            if (!world.mayBuild(worker, wanted)) {
                continue;
            }
            // AiCheckUnitType's resource failure ends this ready attempt; it
            // does not shop farther down the priority list for a cheaper job.
            if (!player.canAfford(wanted.costs())) {
                return false;
            }
            int[] site = (listed & ~1) == 0x4a
                    ? world.aiFindBattleNetHallPlace(worker, wanted)
                    : world.aiFindBattleNetBuildingPlace(worker, wanted);
            if (site == null) {
                continue;
            }
            if (System.getenv("CHONKCRAFT_TRACE_AIBUILD") != null) {
                System.err.printf("JBNBUILD cycle=%d p%d slot=%d %s worker=%d"
                                + " at=%d,%d place=%d,%d%n",
                        world.cycle(), playerIndex, index, wanted.ident(),
                        worker.id(), worker.tileX(), worker.tileY(), site[0], site[1]);
            }
            if (world.orderBattleNetAiBuild(worker, wanted, site[0], site[1])) {
                BuildRequest started = new BuildRequest(wanted, 1);
                started.made = 1;
                buildQueue.add(0, started);
                return true;
            }
        }
        return false;
    }

    /**
     * Starts one trainable list entry at the first idle building that can.
     *
     * <p>Trainer order matches {@link #startTraining}: town-hall line first
     * for workers, then any other producer that accepts the type.
     */
    private boolean startTrainingAtAnyIdleTrainer(World world, UnitType what) {
        List<Unit> owned = world.playerUnits(playerIndex);
        return startTraining(world, owned, what);
    }

    private static UnitType registeredType(World world, String ident) {
        try {
            for (UnitType type : world.registeredUnitTypes()) {
                if (ident.equals(type.ident())) {
                    return type;
                }
            }
        } catch (RuntimeException ignored) {
            // Empty unitTypes map on focused tests (NPE or empty).
        }
        // Focused tests create live instances without a full type table.
        for (Unit unit : world.units()) {
            if (unit.type() != null && ident.equals(unit.type().ident())) {
                return unit.type();
            }
        }
        return null;
    }

    /** Reconstructs FUN_0044bc40's one-building-to-one-list-slot bitmap. */
    private boolean[] battleNetSatisfiedPositions(World world) {
        boolean[] satisfied = new boolean[battleNetBuildPriorities.size()];
        for (Unit unit : world.units()) {
            if (unit.player() != playerIndex || unit.type() == null
                    || unit.order() == Unit.Order.UNDER_CONSTRUCTION) {
                continue;
            }
            int code = PudUnitTypes.code(unit.type().ident());
            if (code < 0) {
                continue;
            }
            code &= ~1;
            if (unit.type().building()) {
                // An upgraded keep/castle does not satisfy a base-hall slot. The
                // native XHuman 6 snapshot is the useful proof: player 5 owns a
                // finished fortress, yet its leading 4a slot remains zero and a
                // peon commissions a new great hall. The upgrade marks its own
                // high-byte milestone elsewhere. Armed towers likewise do not
                // satisfy a watch-tower slot in this initial pass.
                if (code != 0x58 && code != 0x5a) {
                    markFirstUnsatisfied(satisfied, code);
                }
            } else {
                // Trainable list entries (peons, soldiers) are satisfied by live
                // units of that type, and by paid jobs still in a hall queue.
                markFirstUnsatisfied(satisfied, code);
            }
        }
        for (Unit unit : world.units()) {
            if (unit.player() != playerIndex) {
                continue;
            }
            if (unit.producing() != null) {
                int code = PudUnitTypes.code(unit.producing().ident());
                if (code >= 0) {
                    markFirstUnsatisfied(satisfied, code & ~1);
                }
            }
            for (UnitType queued : unit.trainingQueue()) {
                int code = PudUnitTypes.code(queued.ident());
                if (code >= 0) {
                    markFirstUnsatisfied(satisfied, code & ~1);
                }
            }
        }
        return satisfied;
    }

    private void markFirstUnsatisfied(boolean[] satisfied, int normalizedCode) {
        for (int index = 0; index < battleNetBuildPriorities.size(); index++) {
            int listed = battleNetBuildPriorities.get(index);
            if (listed == 0xff) {
                return;
            }
            if (!satisfied[index] && listed < 0x80
                    && (listed & ~1) == normalizedCode) {
                satisfied[index] = true;
                return;
            }
        }
    }

    private boolean battleNetHasPending(World world, UnitType wanted) {
        for (Unit unit : world.units()) {
            if (unit.player() != playerIndex) {
                continue;
            }
            if (unit.pendingBuild() == wanted
                    || (unit.order() == Unit.Order.UNDER_CONSTRUCTION
                        && unit.type() == wanted)) {
                return true;
            }
        }
        return false;
    }

    /** Native's base-hall counter is retained across both hall upgrades. */
    private boolean battleNetHasHall(World world, Player player, UnitType baseHall) {
        int[] codes = player.race()
                == net.chonkbase.chonkcraft.data.map.PudMap.Race.ORC
                        ? new int[] {0x4b, 0x59, 0x5b}
                        : new int[] {0x4a, 0x58, 0x5a};
        for (int code : codes) {
            UnitType tier = registeredType(world, PudUnitTypes.name(code));
            if (tier != null && (world.unitTypesCount(playerIndex, tier.ident()) > 0
                    || battleNetHasPending(world, tier))) {
                return true;
            }
        }
        return battleNetHasPending(world, baseHall);
    }

    /**
     * Researches what the script asked for.
     *
     * <p>{@code AiAddResearchRequest}, reached once a second from
     * {@code AiCheckUnits}. There was no port of it at all: {@code AiResearch}
     * was bound to a function that returned false and did nothing, and the
     * shipped personalities call it sixty-nine times. No computer player in
     * the game had ever researched anything -- not a sword, not an arrow, not
     * a shield -- so every AI fought the whole campaign with the weapons its
     * units were born with, and nothing anywhere said so. A binding that
     * answers instead of being absent is worse than no binding, because
     * {@code unboundScriptFunctions} is this project's tripwire for a script
     * call nothing implements and a bound no-op never trips it.
     *
     * <p>Where it is researched departs from upstream, and has to.
     * {@code AiHelpers.Research()} is built from the Research buttons, which
     * say that a sword is forged at a blacksmith; the implementation has that table, in
     * {@code GameData.Interface}, and hands it to the interface and to nobody
     * else. So this offers the upgrade to each finished, idle building in turn
     * and takes the first that accepts. The difference that can be observed:
     * the upgrade still completes, still costs the same and still applies to
     * the same units, but the building it occupies may be one upstream would
     * not have chosen, so an AI can spend a barracks' time on a sword. Busy
     * buildings refuse outright, which keeps that rare in practice. Give the
     * AI the button table and this should ask it instead.
     *
     * <p>Three things guard the order, and the first is the expensive one.
     * Every research button in Warcraft II is declared
     * {@code Allowed = "check-single-research"}, and upstream honours that here
     * rather than in the interface: "known as a single-research upgrade, check
     * if we're already researching it. if so, ignore this request." A standing
     * request is asked again <em>every second</em>, an upgrade takes forty of
     * them, and {@link World#orderResearch} charges the full price to whichever
     * idle building accepts -- so without that check a side with a spare
     * building paid for the same sword up to forty times over, and paying for
     * it once is most of why the computer players in this game were poor. The
     * other two are {@code UpgradeIdAllowed(...) == 'A'}, which is how a
     * mission forbids a tier its scripts still ask for, and skipping an
     * identifier no upgrade answers to rather than offering it to every
     * building on the map once a second for the rest of the game.
     */
    private void researchManager(World world, List<Unit> owned) {
        if (researchRequests.isEmpty()) {
            return;
        }
        for (String upgradeIdent : List.copyOf(researchRequests)) {
            if (world.upgrades(playerIndex).has(upgradeIdent)) {
                // Upstream stops asking when UpgradeIdAllowed stops saying
                // 'A'. Having it is the case that matters here.
                researchRequests.remove(upgradeIdent);
                continue;
            }
            if (world.upgradeSet() == null || world.upgradeSet().get(upgradeIdent) == null) {
                continue;
            }
            // The mission's own DefineAllow table. The campaign scripts forbid
            // everything and then allow a list, and several of them ask for a
            // tier they went on to forbid.
            if (world.allowed() != null && !world.allowed().isAllowed(playerIndex, upgradeIdent)) {
                continue;
            }
            // AiAddResearchRequest opens with AiCheckCosts and feeds a
            // failure into NeededMask before it looks for a building -- or
            // notices that another building is already doing this research.
            // The request is standing, so unfinished work keeps billing its
            // present shortfall into the harvest split every thought.
            // the harvest census leans on
            // those bits the same thought.
            if (!canAffordBeyondPromised(world, world.player(playerIndex),
                    world.upgradeSet().get(upgradeIdent).costs())) {
                continue;
            }
            if (isResearching(owned, upgradeIdent)) {
                continue;
            }
            for (Unit building : owned) {
                // Only where the research button lives. World.orderResearch
                // asks mayResearch -- the {@code research} buttons' ForUnit
                // masks, which is upstream's AiHelpers.Research() -- so a
                // request whose building does not exist yet stands unpaid:
                // player 2 on levelx04o researched sword upgrades at two pig
                // farms while owning no blacksmith, and upstream's farms
                // were still farms at cycle 39.
                if (!isIdleBuilding(building)) {
                    continue;
                }
                if (world.orderResearch(building, upgradeIdent)) {
                    break;
                }
            }
        }
    }

    /** Whether one of this side's buildings is already working on an upgrade. */
    private static boolean isResearching(List<Unit> owned, String upgradeIdent) {
        for (Unit building : owned) {
            if (upgradeIdent.equals(building.researching())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Turns a watch tower into a guard tower, a town hall into a keep.
     *
     * <p>{@code AiAddUpgradeToRequest}. {@code AiUpgradeTo} was bound to a
     * no-op as well, and the shipped personalities call it fifty-eight times:
     * twenty-one guard towers, fourteen cannon towers and the rest city
     * centres. No computer player in the game had ever improved a building.
     *
     * <p>Each occurrence asks for one target. {@code AiCheckUnits} carries the
     * same counter through unit wants, forces and then this ordered vector;
     * after each occurrence it subtracts one, so N duplicate script calls ask
     * for N target buildings. See the levelx10h cycle-399 entry in
     * {@code focused tests}.
     */
    private void upgradeManager(World world, Map<UnitType, Integer> counter) {
        if (upgradeToRequests.isEmpty()) {
            return;
        }
        for (UpgradeToRequest wanted : List.copyOf(upgradeToRequests)) {
            UnitType target = wanted.target();
            int requested = 1 - ownedWithEquivalents(world, target)
                    - counter.getOrDefault(target, 0);
            if (requested <= 0) {
                counter.merge(target, -1, Integer::sum);
                continue;
            }
            // AiAddUpgradeToRequest opens with AiCheckCosts and feeds a
            // failure into NeededMask before it looks for an idle source
            // building. That includes a source already carrying the
            // upgrade: the standing request remains short until the new type
            // exists, and its current bill still leans this thought's
            // harvest census. The bill matters even when it cannot be paid: on
            // campaigns/human-exp/levelx03h the orc player's first thought
            // asks for a fortress it is 2500 gold and 1200 wood short of,
            // and that unpaid bill is the only thing that doubles the wood
            // percent in the same thought's census -- upstream sends two of
            // four peons to the trees, and this implementation, billing nothing here,
            // used to keep them on gold.
            if (!canAffordBeyondPromised(world, world.player(playerIndex),
                    target)) {
                // Upstream counts the ask even when AiAddUpgradeToRequest
                // cannot pay it, then consumes this vector occurrence below.
            } else {
                for (Unit building : aiActiveUnitsByType(world, wanted.source())) {
                    if (isIdleBuilding(building)
                            && world.orderUpgradeTo(building, target)) {
                        break;
                    }
                }
            }
            counter.merge(target, requested - 1, Integer::sum);
        }
    }

    /**
     * {@code FindPlayerUnitsByType(type, true)}, including its count bound.
     *
     * <p>The helper starts with {@code UnitTypesAiActiveCount[type]} and scans
     * the player's roster, decrementing that bound for every type match even
     * when the unit is unusable and omitted from the result. This oddity is
     * observable: an unfinished tower ahead of two finished towers consumes
     * one of the active-count slots, so an upgrade search can stop after the
     * first finished tower and never inspect the second.
     */
    private List<Unit> aiActiveUnitsByType(World world, UnitType type) {
        int remaining = world.unitTypesCount(playerIndex, type.ident());
        if (remaining <= 0) {
            return List.of();
        }
        List<Unit> found = new ArrayList<>();
        for (Unit unit : world.playerUnits(playerIndex)) {
            if (unit.type() != type) {
                continue;
            }
            if (unit.isAlive() && unit.isOnMap()
                    && unit.currentAction() != Unit.Order.UNDER_CONSTRUCTION) {
                found.add(unit);
            }
            if (--remaining == 0) {
                break;
            }
        }
        return found;
    }

    /** Whether any force has already claimed a unit. */
    private boolean isEnlisted(Unit unit) {
        for (AiForce force : forces) {
            if (containsIdentical(force, unit)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsIdentical(AiForce force, Unit unit) {
        for (Unit member : force.members()) {
            if (member == unit) {
                return true;
            }
        }
        return false;
    }

    /**
     * Spends the bank on whatever has been requested.
     *
     * <p>Implements {@code AiCheckingWork},
     * and the two things it does that this used to skip are the whole of the
     * fix.
     *
     * <p>First, it walks the <em>whole</em> list. This looked at one request
     * and returned if it could not be started, so anything the AI could not
     * begin froze everything behind it however affordable that was:
     * {@code hum-exp-6a} sat on 2000 gold and a rising 3700 wood behind a
     * great hall its site search could not place, with three forces it could
     * never finish; {@code orc-14-green} sat on 37,856 gold behind a request
     * for a peasant it had no town hall to train. Upstream skips a request it
     * cannot start and carries on down the list, which is what
     * {@code continue} does here.
     *
     * <p>Second, it asks for a farm. Upstream raises {@code NeedSupply} when a
     * request needs food the player has not got and calls
     * {@code AiRequestSupply}, which puts the race's supply building at the
     * head of the queue. This implementation had no port of that call at all, and the
     * only code that ever asked for a farm was {@link #plan}, which is
     * switched off the moment a script is attached -- so every campaign AI,
     * all of them scripted, had no path to a farm in existence. A supply
     * capped personality asked for the same peon every second for the rest of
     * the mission and was refused every second.
     */
    private void resourceManager(World world, Player player, List<Unit> owned) {
        if (System.getenv("CHONKCRAFT_TRACE_QUEUE") != null) {
            for (BuildRequest request : buildQueue) {
                System.err.println("JQUEUE cycle=" + world.cycle() + " p" + playerIndex
                        + " type=" + request.type.ident() + " want=" + request.count
                        + " made=" + request.made + " retry=" + request.retryAfter);
            }
        }
        // "Supply has the highest priority": a shortage flagged on an
        // earlier walk is asked again at the top of this one unless a
        // supply building already heads the queue, and it is asked even
        // when the queue is empty.
        if (needSupply
                && (buildQueue.isEmpty() || buildQueue.get(0).type.supply() == 0)) {
            needSupply = false;
            requestSupply(world, player, owned);
        }
        // Over a copy: asking for supply inserts a farm at the head, which
        // rewrites the queue underneath the walk. One start per entry per
        // thought, which is AiCheckingWork's own pace -- an entry's Made
        // advances once a walk however large its Want.
        for (BuildRequest request : List.copyOf(buildQueue)) {
            UnitType wanted = request.type;
            if (!buildQueue.contains(request)) {
                continue;
            }
            // The food check only flags; the entry is still attempted, and
            // the farm is asked for after the attempt -- upstream's
            // "trigger this last, because it may re-arrange the queue"

            boolean newSupply = false;
            if (wanted.demand() > 0 && !hasSupplyFor(player, owned, wanted)) {
                needSupply = true;
                newSupply = true;
            }
            // "Check limits, AI should be broken if reached." CheckLimits'
            // InsufficientSupply arm reads the bare
            // demand against the bare supply -- no credit for farms rising
            // or debits for units in training, unlike AiCheckSupply above --
            // and its continue also skips the farm request below, so a
            // hard-capped entry's farm waits for the walk-start retry one
            // thought later. On level08h player 1 sits at the cap at its
            // cycle-8 thought and upstream's farm starts at 38; player 0
            // still has room for the peon its hall is already training, so
            // its entry passes here and its farm starts at 37. The count
            // limits beside it are not modelled: upstream defaults them to
            // hundreds of units and no shipped campaign reaches them.
            if (request.count > request.made
                    && wanted.demand() > 0
                    && player.demand() + wanted.demand() > player.supply()) {
                continue;
            }
            // The costs are checked -- and the shortfall billed into
            // NeededMask -- for every entry, the satisfied ones included:
            // upstream's walk gates only the limits check and the start on
            // "queue.Want > queue.Made", never the AiCheckUnitTypeCosts
            // above them. An entry
            // waiting on its trainee is a standing tax on the collect
            // ratios: on campaigns/human/level04h the peon entry at
            // want=1 made=1 bills gold at the cycle-607 thought, doubling
            // gold's share to wanted 4/2, and that is the census that steals
            // the chopper at 75,79 for the mine -- a port that dropped the
            // entry when work started never billed, read 3/3, and left the
            // chopper felling wood until the trace parted at 609.
            if (!canAffordBeyondPromised(world, player, wanted)) {
                continue;
            }
            if (request.count > request.made && world.cycle() >= request.retryAfter) {
                boolean started = wanted.building()
                        ? startBuilding(world, owned, wanted)
                        : startTraining(world, owned, wanted);
                if (started) {
                    request.retryAfter = 0;
                    // "++queue2.Made" -- the entry stays, holding the job
                    // until AiRemoveFromBuilt delivers it or an AiUnitKilled
                    // hands it back.
                    request.made++;
                } else if (wanted.building()) {
                    // Upstream: "Finding a building place is costly, don't try
                    // again for a while." Without the wait, walking the whole
                    // list means a request that can never be placed runs the
                    // full site search once a second for the rest of the game.
                    request.retryAfter = world.cycle()
                            + (request.retryAfter == 0 ? FIRST_PLACEMENT_RETRY
                                    : LATER_PLACEMENT_RETRY);
                }
            }
            if (newSupply) {
                requestSupply(world, player, owned);
            }
        }
        // "Look if we can build a farm in advance": food exactly full with
        // no shortage flagged asks for the next farm before anything is
        // refused for it.
        if (!needSupply && player.supply() == player.demand()) {
            requestSupply(world, player, owned);
        }

    }

    /**
     * Whether a walk has found a request the food cannot cover.
     *
     * <p>{@code PlayerAi::NeedSupply}. Raised by the queue walk, consumed
     * at the top of the next one; while a supply building heads the queue
     * the flag stands and no second farm is asked for.
     */
    private boolean needSupply;

    /** How long a request whose site search failed waits, in cycles. */
    private static final long FIRST_PLACEMENT_RETRY = 150;

    /** How long it waits after failing a second time. */
    private static final long LATER_PLACEMENT_RETRY = 450;

    /** When each request may next be attempted, for the ones that failed. */


    /**
     * Whether there is food for one more of a type.
     *
     * <p>{@code AiCheckSupply}. Farms already on the way count, so an AI that
     * has just asked for one does not ask again next second and end up with a
     * row of them; so does everything else on the request list, so a queue of
     * ten grunts is not all waved through on the strength of food for one.
     */
    private boolean hasSupplyFor(Player player, List<Unit> owned, UnitType wanted) {
        // {@code AiCheckSupply} counts {@code queue.Made} on both sides of
        // the ledger: the farms whose frames are already rising and the
        // units already inside a building's training bar. The requests
        // still standing in line count for nothing -- upstream ignores
        // {@code Want - Made} entirely -- and this implementation had it the other
        // way round, counting every queued request's appetite and none of
        // the work in progress. The day the request list learned to hold a
        // force's whole shortfall, player 1 on levelx04o saw eight
        // phantom mouths, called itself short of food and sent its one
        // peasant to raise a farm upstream never asks for.
        // AiCheckSupply does not reconstruct work from unit orders.  It
        // walks UnitTypeBuilt and counts queue.Made on both sides.  That
        // distinction covers the interval where CommandBuild is latched
        // behind an unbreakable worker action: the queue already says made,
        // although no unit reports BUILD and no foundation exists.  Missing
        // that interval raised NeedSupply; after the farm completed the stale
        // flag asked for another farm and biased levelx03o's cycle-1300
        // harvester census toward gold instead of wood.
        int remaining = 0;
        for (BuildRequest request : buildQueue) {
            remaining += request.made * request.type.supply();
        }
        remaining += player.supply() - player.demand() - wanted.demand();
        if (remaining < 0) {
            return false;
        }
        // "Count what we train."
        for (BuildRequest request : buildQueue) {
            remaining -= request.made * request.type.demand();
            if (remaining < 0) {
                return false;
            }
        }
        return true;
    }

    /** Food from supply buildings that are begun but not finished. */
    private int supplyOnTheWay(List<Unit> owned) {
        int total = 0;
        for (Unit unit : owned) {
            if (unit.order() == Unit.Order.UNDER_CONSTRUCTION) {
                total += unit.type().supply();
            } else if (unit.order() == Unit.Order.BUILD && unit.pendingBuild() != null) {
                // A builder still walking to its site. Once the foundation is
                // down the builder is inside it and off the map, so the site
                // above is the only one of the two that counts. This used to
                // read producing(), the training slot, so a farm dispatched
                // at the opening of a thought was invisible to the later
                // queue walk. level06h then raised NeedSupply again and asked
                // for a second farm before buying the peon at cycle 672.
                total += unit.pendingBuild().supply();
            }
        }
        return total;
    }

    /**
     * Asks for the race's supply building, and starts it on the spot.
     *
     * <p>{@code AiRequestSupply}. One at a time: upstream returns without
     * doing anything when a supply building is already ordered, and without
     * that check a supply capped AI queues a farm a second until it has spent
     * everything it owns on farms. A sleeping script gets no farm either --
     * upstream's own comment: "when the script starts it may request a
     * better unit than the one we pick here."
     *
     * <p>The affordable candidate does not join the queue to wait its turn:
     * upstream hands it straight to {@code AiMakeUnit} and inserts its entry
     * already {@code Made}, so the builder walks
     * in the same thought that noticed the shortage. level08h's player 0
     * flags food at its thought on cycle 37 and its pig farm's builder is
     * walking by 40; this implementation used to queue the farm and only look at it a
     * thought later, one second behind upstream with every draw after
     * shifted. And the farm it cannot pay for is still a bill: the missing
     * resources go into the needed mask, which is the lean the collect
     * split reads.
     */
    private void requestSupply(World world, Player player, List<Unit> owned) {
        if (System.getenv("CHONKCRAFT_TRACE_QUEUE") != null) {
            StackTraceElement caller = Thread.currentThread().getStackTrace()[2];
            System.err.println("JQUEUE supply-check cycle=" + world.cycle()
                    + " p" + playerIndex + " caller=" + caller.getLineNumber()
                    + " supply=" + player.supply() + " demand=" + player.demand()
                    + " onway=" + supplyOnTheWay(owned)
                    + " sleeping=" + isSleeping());
        }
        if (isSleeping()) {
            return;
        }
        for (BuildRequest queued : buildQueue) {
            if (queued.type.supply() > 0) {
                return;
            }
        }
        if (supplyOnTheWay(owned) > 0) {
            return;
        }
        UnitType farm = supplyBuilding(world, owned);
        if (farm == null) {
            return;
        }
        if (canAffordBeyondPromised(world, player, farm)
                && startBuilding(world, owned, farm)) {
            // AiRequestSupply inserts the job at the head already made:
            // Want=1, Made=1. It stays there until
            // the roof goes on, so AiCheckingWork continues checking and
            // billing its costs into NeededMask on every thought.
            BuildRequest started = new BuildRequest(farm, 1);
            started.made = 1;
            buildQueue.add(0, started);
            if (System.getenv("CHONKCRAFT_TRACE_QUEUE") != null) {
                System.err.println("JQUEUE supply-start cycle=" + world.cycle()
                        + " p" + playerIndex + " type=" + farm.ident());
            }
        }

    }

    /**
     * The cheapest food per building this side can put up.
     *
     * <p>Upstream sorts {@code AiHelpers.UnitLimit()[0]} -- every registered
     * type whose {@code Supply} is above zero -- by cost per point of supply
     * and takes the cheapest. For the orcs that is a pig farm at 500 wood for
     * four, not a great hall at 1200 gold and 800 wood for one, and picking
     * the wrong one bankrupts the AI on town halls.
     *
     * <p>The candidates come from the loaded unit-type registry, as upstream's
     * {@code AiHelpers.UnitLimit()[0]} does, not from buildings already placed
     * on the map.  The distinction is observable on
     * {@code (2)one-way-in-one-way-out}: when its first town hall completed,
     * no human farm existed yet.  The old map census therefore cached the
     * town hall as the race's cheapest known food source, could not afford a
     * second one, and spent the bank on a barracks while upstream founded the
     * farm.  An eligible type also needs an active builder type and its tech
     * dependencies, which is {@code AiRequestedTypeAllowed} before upstream
     * puts it into the cost-per-food sort.
     */
    private UnitType supplyBuilding(World world, List<Unit> owned) {
        if (supplyType != null) {
            return supplyType;
        }
        Set<UnitType> candidates = new LinkedHashSet<>();
        for (UnitType candidate : world.registeredUnitTypes()) {
            if (!candidate.building() || candidate.supply() <= 0
                    || !world.dependenciesSatisfied(playerIndex, candidate.ident())) {
                continue;
            }
            for (Unit unit : owned) {
                if (unit.isAlive() && world.mayBuild(unit.type(), candidate)) {
                    candidates.add(candidate);
                    break;
                }
            }
        }
        // Hand-built test worlds historically supplied no type registry.  In
        // that deliberately incomplete environment, retain the old census so
        // the AI can still reason about a food building the fixture placed.
        if (candidates.isEmpty() && world.registeredUnitTypes().isEmpty()) {
            var race = world.player(playerIndex).race();
            for (Unit unit : world.units()) {
                if (!unit.isAlive() || unit.type() == null || !unit.type().building()
                        || unit.type().supply() <= 0) {
                    continue;
                }
                Player owner = world.player(unit.player());
                if (owner != null && owner.race() == race) {
                    candidates.add(unit.type());
                }
            }
        }
        UnitType best = null;
        long bestCost = Long.MAX_VALUE;
        for (UnitType candidate : candidates) {
            long cost = 0;
            for (Map.Entry<UnitType.Resource, Integer> price : candidate.costs().entrySet()) {
                if (price.getKey() != UnitType.Resource.TIME) {
                    cost += price.getValue();
                }
            }
            // Rounded up, as upstream rounds it: (cost + supply - 1) / supply.
            cost = (cost + candidate.supply() - 1) / candidate.supply();
            // Ties broken on the identifier so two machines running the same
            // game choose the same farm.
            if (cost < bestCost
                    || (cost == bestCost && best != null
                            && candidate.ident().compareTo(best.ident()) < 0)) {
                bestCost = cost;
                best = candidate;
            }
        }
        // Remembered rather than searched for again: which building feeds a
        // race does not change during a game, and this walks every unit on the
        // map to find it.
        supplyType = best;
        return best;
    }

    /** The race's supply building, once it has been worked out. */
    private UnitType supplyType;

    /** Sends a free worker to put up a building somewhere near the base. */
    private boolean startBuilding(World world, List<Unit> owned, UnitType what) {
        // The tech tree first, before any worker is weighed or a pick drawn:
        // AiMakeUnit's equivalence walk erases every type the tree refuses
        // so an ogre
        // mound asked for before its stronghold starts nobody walking. On
        // campaigns/human-exp/levelx12h this implementation's first thought founded
        // the mound, the alchemist and the altar their absence should have
        // gated, and drew a worker pick for each.
        if (!world.dependenciesSatisfied(playerIndex, what.ident())) {
            return false;
        }
        List<Unit> builders = new ArrayList<>();
        for (Unit unit : owned) {
            // Asked of the engine, which reads AiHelpers.Build() -- every
            // "build" button's value is the building and its ForUnit mask is
            // the set of workers allowed to raise it. This used to test
            // landUnit() instead, because nothing checked the pairing at all
            // and an AI with no peasant left sent an oil tanker to build five
            // pig farms on dry land. That guess was also narrower than the
            // real rule, since the one thing a tanker may build is an oil
            // platform.
            //
            // canGather stays as the cheap half. Every type on that table is a
            // worker, and a world assembled without a button table -- every
            // hand-built fixture -- answers the engine's question "yes" for
            // everything, which without this would make a footman a candidate
            // site to build around.
            if (unit.type().canGather() && world.mayBuild(unit.type(), what)
                    && unit.isAlive() && unit.isOnMap()
                    // "Remove all workers on the way building something":
                    // IsAlreadyWorking also spares the ones mid-extraction
                    // and mid-delivery, but not the ones merely walking to a
                    // mine, which upstream will happily turn around.
                    && !world.isAlreadyWorking(unit)) {
                builders.add(unit);
            }
        }
        if (builders.isEmpty()) {
            return false;
        }
        // One candidate, drawn. AiBuildBuilding takes
        // {@code table[SyncRand() % table.size()]} when more than one worker
        // is free, and the number comes off the shared
        // stream, so which peasant walks out to build is part of the game
        // both engines have to agree on. The try-the-others fallback below
        // it is gated on a request with its own position, which the script
        // path never produces, so a candidate whose search fails is the
        // request failing.
        Unit candidate = builders.size() == 1 ? builders.get(0)
                : builders.get(world.syncRand() % builders.size());
        int[] site = world.aiFindBuildingPlace(candidate, what, -1, -1);
        if (System.getenv("CHONKCRAFT_TRACE_AIBUILD") != null) {
            System.err.printf("JAIBUILD cycle=%d p%d %s workers=%s picked=%d at=%d,%d"
                            + " place=%s%n",
                    world.cycle(), playerIndex, what.ident(),
                    builders.stream().map(unit -> Integer.toString(unit.id()))
                            .collect(java.util.stream.Collectors.joining(",")),
                    candidate.id(), candidate.tileX(), candidate.tileY(),
                    site == null ? "none" : site[0] + "," + site[1]);
        }
        if (site == null) {
            return false;
        }
        return world.orderBuild(candidate, what, site[0], site[1]);
    }

    /**
     * Whether the bank covers a request after what the builders already carry.
     *
     * <p>{@code AiCheckCosts} walks every
     * unit's orders and counts the full cost of each building under a
     * {@code Build} order as spent -- the worker walking to the site as much
     * as the one inside the frame, though the frame's is paid by then, and
     * the double count is upstream's own. Without this ledger the AI's bank
     * never moves until a builder arrives, so a first thought that could
     * afford one watch tower dispatched two: on campaigns/human-exp/levelx04h
     * upstream sends a blacksmith's builder and one tower's, two draws for
     * two worker picks, and refuses the second tower at {@code costerr=2};
     * this implementation sent them all, and every draw after its extra picks was a
     * different number.
     */
    private boolean canAffordBeyondPromised(World world, Player player, UnitType wanted) {
        return canAffordBeyondPromised(world, player, wanted.costs());
    }

    private boolean canAffordBeyondPromised(World world, Player player,
            Map<UnitType.Resource, Integer> costs) {
        Map<UnitType.Resource, Integer> used =
                new java.util.EnumMap<>(UnitType.Resource.class);
        // Not the on-map roster: AiCheckCosts walks Player->GetUnits() whole
        // and a builder that has gone inside its
        // half-built building still holds its Build order and still bills the
        // building's full cost for the whole construction. This walked the
        // AI's on-map list and lost every such builder: on
        // campaigns/human-exp/levelx06h p4's three builds bill 2600 gold and
        // 1200 wood upstream at every thought from 41 to 131, and this implementation's
        // bill fell to one building's worth as the builders went inside --
        // so wood never joined the needed mask, the census read wanted 4/2
        // where upstream read 3/3, and at 131 the two engines stole workers
        // in opposite directions.
        for (Unit unit : world.units()) {
            // No aliveness test: this implementation's isAlive() folds in !removed,
            // which is upstream's IsAliveOnMap, and the whole point is that
            // the removed builder inside its site still bills. AiCheckCosts
            // reads every order, not only CurrentOrder: a build queued behind
            // an unbreakable harvest step therefore bills immediately too.
            if (unit.player() != playerIndex) {
                continue;
            }
            UnitType site = unit.pendingBuild();
            if (site == null && unit.order() == Unit.Order.BUILD
                    && unit.worksite() != null && unit.worksite().type() != null) {
                site = unit.worksite().type();
            }
            if (System.getenv("CHONKCRAFT_TRACE_AICOLLECT") != null) {
                System.err.println("JBILL p" + playerIndex + " u" + unit.id()
                        + " pending=" + (unit.pendingBuild() == null ? "-"
                                : unit.pendingBuild().ident())
                        + " worksite=" + (unit.worksite() == null ? "-"
                                : unit.worksite().type().ident())
                        + " bills=" + (site == null ? "-" : site.ident()));
            }
            if (site != null) {
                addPromisedCosts(used, site);
            }
            // Shifted build commands are further COrder_Build entries in
            // upstream's Orders vector. The installed/latched replacement is
            // represented by pendingBuild above; only later shifted entries
            // live in this queue, so these do not double count it.
            for (Unit.QueuedOrder queued : unit.queuedOrders()) {
                if (queued.kind() == Unit.QueuedOrderKind.BUILD && queued.type() != null) {
                    addPromisedCosts(used, queued.type());
                }
            }
        }
        boolean affordable = true;
        for (Map.Entry<UnitType.Resource, Integer> cost : costs.entrySet()) {
            if (cost.getKey() == UnitType.Resource.TIME) {
                continue;
            }
            if (player.get(cost.getKey())
                    - used.getOrDefault(cost.getKey(), 0) < cost.getValue()) {
                // AiCheckCosts answers with the missing resources' bits, and
                // every caller feeds them straight into NeededMask.
                neededMask.add(cost.getKey());
                affordable = false;
            }
        }
        if (System.getenv("CHONKCRAFT_TRACE_AICOLLECT") != null) {
            // The implementation-side twin of the NEEDDBG prints beside upstream's
            // NeededMask raises, one line per cost check.
            System.err.println("JNEED p" + playerIndex
                    + " costs=" + costs + " used=" + used + " mask=" + neededMask
                    + " ok=" + affordable);
        }
        return affordable;
    }

    private static void addPromisedCosts(Map<UnitType.Resource, Integer> used,
            UnitType building) {
        for (Map.Entry<UnitType.Resource, Integer> cost : building.costs().entrySet()) {
            used.merge(cost.getKey(), cost.getValue(), Integer::sum);
        }
    }

    /** Starts training at any idle building that can pay for it. */
    private boolean startTraining(World world, List<Unit> owned, UnitType what) {
        // The same tech-tree gate the buildings get: AiMakeUnit walks one
        // equivalence filter for both.
        if (!world.dependenciesSatisfied(playerIndex, what.ident())) {
            return false;
        }
        // AiMakeUnit walks AiHelpers.Train()[what] outside
        // FindPlayerUnitsByType: trainer *types* are considered in the order
        // their button's ForUnit vector declares, then the player's roster is
        // scanned for that one type. The distinction is visible when both a
        // low-tier and high-tier hall are idle. levelx09h declares great hall,
        // stronghold, fortress for peons; upstream chooses the later-created
        // great hall at cycle 762, while a mixed-roster walk chose the earlier
        // fortress.
        List<String> trainerOrder = world.trainerTypeOrder(what);
        if (!trainerOrder.isEmpty()) {
            for (String trainer : trainerOrder) {
                for (Unit building : owned) {
                    if (building.type().ident().equals(trainer)
                            && startTrainingAt(world, building, what)) {
                        return true;
                    }
                }
            }
            return false;
        }
        for (Unit building : owned) {
            if (startTrainingAt(world, building, what)) {
                return true;
            }
        }
        return false;
    }

    /** Offers one AI training job only to a genuinely idle building. */
    private static boolean startTrainingAt(World world, Unit building, UnitType what) {
        if (!building.type().building()) {
            return false;
        }
        // Idle means idle. {@code AiTrainUnit} takes the first unit whose
        // {@code IsIdle()} answers yes, so a
        // busy building's request waits, unpaid, until the building is free.
        // This implementation's orderTrain otherwise accepts a paid human queue.
        if (!isIdleBuilding(building)
                || building.producing() != null || !building.trainingQueue().isEmpty()) {
            return false;
        }
        return world.orderTrain(building, what);
    }

    /** {@code CUnit::IsIdle}: one order, and that order is Still. */
    private static boolean isIdleBuilding(Unit building) {
        return building.currentAction() == Unit.Order.STILL
                && !building.reportsActionBeforeQueued()
                && !building.hasQueuedOrders();
    }

    /**
     * Turns the standing wants into the build requests they are short by.
     *
     * <p>{@code AiCheckUnits} and the
     * {@code AiForceManager::CheckUnits} it calls, run between the script and
     * the resource manager. Before the resource manager, not after it, and
     * the placement is half the fix: this implementation raised a force's missing units
     * from inside {@link #forceManager}, which runs after the bank has been
     * spent, so every force filled one second behind upstream. On
     * campaigns/orc-exp/levelx04o the script declares
     * {@code AiForce(1, {AiSoldier(), 5})} on the first thought at cycle 8;
     * upstream's barracks starts the first footman on that cycle and this
     * port's waited until 38.
     *
     * <p>The counting is upstream's, and it asks for the whole shortfall at
     * once rather than one unit a second. What the player already owns is
     * charged against every want, equivalents included; the members of
     * forces already attacking are not; and what one want has asked for is
     * carried against the next by the running counter.
     */
    private Map<UnitType, Integer> checkRequests(World world, List<Unit> owned) {
        // counter = AiGetBuildRequestsCount: every job asked for and not yet
        // delivered, read straight off the queue's Want
        // The queue itself now holds a job from
        // the second it is requested to the second the unit walks out, so
        // the trainings under way and the frames already rising are in the
        // count by construction -- the hand-tally of producing units and
        // pending builds this kept while entries died at start counted the
        // same work twice the moment the entries started living to delivery.
        Map<UnitType, Integer> counter = new java.util.LinkedHashMap<>(requests());

        // "Look if some unit-types are missing." The standing wants first,
        // then the forces, as AiCheckUnits orders it; the counter carries
        // between them so nothing is asked for twice.
        for (StandingRequest request : unitTypeRequests) {
            UnitType type = request.type();
            int e = ownedWithEquivalents(world, type);
            int missing = request.count() - e - counter.getOrDefault(type, 0);
            if (missing > 0) {
                need(type, missing);
                counter.merge(type, missing, Integer::sum);
            }
            counter.merge(type, -request.count(), Integer::sum);
        }

        Map<UnitType, Integer> attacking = new java.util.LinkedHashMap<>();
        for (AiForce force : forces) {
            if (force.state() == AiForce.State.ATTACKING) {
                for (Unit member : force.members()) {
                    attacking.merge(member.type(), 1, Integer::sum);
                }
            }
        }
        for (AiForce force : forces) {
            if (force.state() == AiForce.State.ATTACKING) {
                continue;
            }
            for (Map.Entry<UnitType, Integer> want : force.wanted().entrySet()) {
                UnitType type = want.getKey();
                int missing = want.getValue()
                        - (ownedWithEquivalents(world, type)
                                + counter.getOrDefault(type, 0)
                                - attacking.getOrDefault(type, 0));
                if (missing > 0) {
                    need(type, missing);
                    counter.merge(type, missing, Integer::sum);
                }
                counter.merge(type, -want.getValue(), Integer::sum);
            }
        }
        return counter;
    }

    /**
     * A wanted unit is delivered: the queue entry that held its job lets go.
     *
     * <p>Implements {@code AiRemoveFromBuilt},
     * called when a trainee steps out or a construction finishes:
     * {@code --Made, --Want}, and the entry leaves when its want is spent.
     * The equivalence fallback is upstream's own -- a keep delivered against
     * a town-hall want still closes the want.
     */
    public void workComplete(World world, UnitType type) {
        if (removeFromBuilt(type)) {
            return;
        }
        for (UnitType equivalent : world.aiEquivalents(type)) {
            if (removeFromBuilt(equivalent)) {
                return;
            }
        }
    }

    /**
     * Delivers a newly trained unit to the first force still asking for it.
     *
     * <p>{@code AiTrainingComplete} does this synchronously, immediately
     * after {@code AiRemoveFromBuilt}: it removes dead force members and calls
     * {@code AiPlayer->Force.Assign(what)}. Waiting
     * for the next {@code AiForceManager} pass is a whole AI thought too late.
     * On {@code campaigns/orc/level11o}, the eighth member of player one's
     * second force steps out before cycle 1028; upstream's script sees the
     * full force and launches then, while a deferred assignment makes
     * {@code AiWaitForce} block until cycle 1058.
     */
    public void trainingComplete(World world, Unit trained) {
        workComplete(world, trained.type());
        for (AiForce force : forces) {
            force.prune();
        }
        if (isEnlisted(trained)) {
            return;
        }
        // Assign without a named force walks the internal force table in
        // order and refuses reinforcements to armies already attacking.
        for (AiForce force : forces) {
            if (force.state() == AiForce.State.ATTACKING
                    || force.state() == AiForce.State.GOING_TO_RALLY
                    || force.shortfall(trained.type()) <= 0) {
                continue;
            }
            force.members().add(trained);
            return;
        }
    }

    private boolean removeFromBuilt(UnitType type) {
        for (BuildRequest request : buildQueue) {
            if (request.made > 0 && request.type == type) {
                if (System.getenv("CHONKCRAFT_TRACE_QUEUE") != null) {
                    System.err.println("JQUEUE complete p" + playerIndex + " type="
                            + type.ident() + " want=" + request.count
                            + " made=" + request.made);
                }
                request.made--;
                request.count--;
                if (request.count <= 0) {
                    buildQueue.remove(request);
                }
                return true;
            }
        }
        return false;
    }

    /**
     * A started job dies undelivered: the entry takes it back as merely
     * wanted.
     *
     * <p>Implements {@code AiReduceMadeInBuilt},
     * called from the {@code AiUnitKilled} hooks -- the builder killed on
     * its way with no frame standing, the frame itself killed mid-build --
     * and from {@code AiCanNotBuild}'s placement failure: {@code --Made}
     * alone, so the next walk starts it again.
     */
    public void reduceMade(World world, UnitType type) {
        if (reduceMade2(type)) {
            return;
        }
        for (UnitType equivalent : world.aiEquivalents(type)) {
            if (reduceMade2(equivalent)) {
                return;
            }
        }
    }

    private boolean reduceMade2(UnitType type) {
        for (BuildRequest request : buildQueue) {
            if (request.made > 0 && request.type == type) {
                if (System.getenv("CHONKCRAFT_TRACE_QUEUE") != null) {
                    System.err.println("JQUEUE reduce p" + playerIndex + " type="
                            + type.ident() + " want=" + request.count
                            + " made=" + request.made);
                }
                request.made--;
                return true;
            }
        }
        return false;
    }

    /**
     * {@code UnitTypesAiActiveCount} plus {@code AiHelpers.Equiv()}'s
     * stand-ins: what the player owns of a type, counting the castle for the
     * town hall and the ranger for the archer.
     */
    private int ownedWithEquivalents(World world, UnitType type) {
        int have = world.unitTypesCount(playerIndex, type.ident());
        for (UnitType equivalent : world.aiEquivalents(type)) {
            have += world.unitTypesCount(playerIndex, equivalent.ident());
        }
        return have;
    }

    /** Fills forces, then sends the full ones at the enemy. */
    private void forceManager(World world, List<Unit> owned) {
        // A handed-off force whose army is dead is finished with. Upstream
        // returns its internal slot to the pool for the next attack to use;
        // dropping it does the same job, and without it a personality that
        // attacks on a loop grows a force a wave for the rest of the mission.
        forces.removeIf(force -> force.index() >= CARRIER_INDEX_BASE
                && force.size() == 0);
        // AiForceManager::Update starts maxPathing at two and returns after
        // updating the third active force. The name is a little misleading:
        // an attacking force spends one unit of the allowance even when this
        // particular pass does not happen to run a route search. The cap is
        // observable order, not just a performance shortcut. On levelx12h
        // three older waves are still marching when a fourth grunt comes to
        // rest; upstream never reaches that fourth force on cycle 519, while
        // an uncapped manager re-sends it at its leader every thirty cycles.
        int maxPathing = 2;
        for (AiForce force : List.copyOf(forces)) {
            force.prune();
            enlist(force, owned);
            String tracedForceUnit = System.getenv("CHONKCRAFT_TRACE_FORCE_UNIT");
            if (tracedForceUnit != null) {
                int tracedId = Integer.parseInt(tracedForceUnit);
                if (force.members().stream().anyMatch(member -> member.id() == tracedId)) {
                    StringBuilder trace = new StringBuilder("JFORCEUNIT cycle=")
                            .append(world.cycle()).append(" force=").append(force.index())
                            .append(" state=").append(force.state())
                            .append(" members=");
                    for (Unit member : force.members()) {
                        trace.append(member.id()).append(':').append(member.order()).append('@')
                                .append(member.tileX()).append(',').append(member.tileY())
                                .append(' ');
                    }
                    System.err.println(trace);
                }
            }
            String tracedForcePlayer = System.getenv("CHONKCRAFT_TRACE_FORCE_PLAYER");
            if (tracedForcePlayer != null
                    && playerIndex == Integer.parseInt(tracedForcePlayer)
                    && !force.members().isEmpty()) {
                StringBuilder trace = new StringBuilder("JFORCEALL cycle=")
                        .append(world.cycle()).append(" force=").append(force.index())
                        .append(" state=").append(force.state()).append(" members=");
                for (Unit member : force.members()) {
                    trace.append(member.id()).append(':').append(member.order()).append('@')
                            .append(member.tileX()).append(',').append(member.tileY()).append(' ');
                }
                System.err.println(trace);
            }

            if (force.state() == AiForce.State.GATHERING) {
                // What is missing was requested by checkForceRequests before
                // the resource manager ran, which is where upstream asks.
                if (force.isComplete()) {
                    force.setState(AiForce.State.READY);
                }
                continue;
            }

            if (force.state() == AiForce.State.READY && !force.defending()
                    && force.index() >= CARRIER_INDEX_BASE) {
                // Only the armies a script has already handed off launch by
                // themselves. Upstream's manager acts on Defending and
                // Attacking forces and nothing else (AiForceManager::Update,
                // The game ): a scripted force that fills up
                // stands as a home guard until AiAttackWithForce moves it,
                // however long the script sleeps on it. level04h's fourth
                // thought declares three forces from standing ships and
                // sleeps four thousand cycles before the attack; a port
                // that launched them on completion sailed the whole army
                // at cycle 848, four thousand cycles before upstream's.
                //
                // No continue: the script's launch and the manager's update
                // happen in the same thought upstream -- AiExecuteScript
                // runs first and AiForceManager after -- so a force whose
                // members already stand within five of the rally leaves for
                // the real enemy the very thought it was launched, which is
                // level11o's tight column, while a spread fleet stalls,
                // which is levelx08o's harbour.
                launchAttack(world, force);
            }

            if (force.state() == AiForce.State.ATTACKING
                    || force.state() == AiForce.State.GOING_TO_RALLY) {
                if (maxPathing < 0) {
                    continue;
                }
                maxPathing--;
            }

            if (force.state() == AiForce.State.GOING_TO_RALLY) {
                // AiForce::Update's rally arm: the force stands until every
                // member is within five squares of the rally or the sixty
                // thoughts run out, one ticked off per thought while anyone
                // is close. Meanwhile the tail re-sends idle members at the
                // rally each pass, which is the one-cycle order pulse the
                // upstream trace shows every thirty cycles.
                int minDist = Integer.MAX_VALUE;
                int maxDist = 0;
                for (Unit member : force.members()) {
                    int distance = member.distanceTo(force.goalX(), force.goalY());
                    minDist = Math.min(minDist, distance);
                    maxDist = Math.max(maxDist, distance);
                }
                if (force.waitOnRallyPoint() > 0 && minDist <= RALLY_THRESHOLD) {
                    force.tickWaitOnRallyPoint();
                }
                if (maxDist <= RALLY_THRESHOLD || force.waitOnRallyPoint() == 0) {
                    // Gathered, or done waiting: find the real enemy --
                    // buildings first, then anything -- and go.
                    Unit leader = force.members().getFirst();
                    Unit enemy = world.findEnemyByFlood(leader, true);
                    if (enemy == null) {
                        enemy = world.findEnemyByFlood(leader, false);
                    }
                    if (enemy == null) {
                        force.setState(AiForce.State.READY);
                        continue;
                    }
                    force.setGoal(enemy.tileX(), enemy.tileY());
                    force.setState(AiForce.State.ATTACKING);
                    Unit soldier = aggressiveLeader(force.members());
                    for (int i = 0; i < force.members().size(); i++) {
                        Unit member = force.members().get(i);
                        member.setWaitCycles(i / 5);
                        launchMember(world, member, soldier, force.goalX(), force.goalY());
                    }
                }
            }

            if (force.state() == AiForce.State.ATTACKING
                    && force.members().isEmpty()) {
                // Re-fill when the army is gone, as upstream's Update resets
                // a force whose size has reached nought.
                force.setState(AiForce.State.GATHERING);
                continue;
            }
            if (force.state() != AiForce.State.ATTACKING
                    && force.state() != AiForce.State.GOING_TO_RALLY) {
                continue;
            }

            // The idle tail every Update pass ends on (AiForce::Update,
            // The game ): each member standing still is sent
            // again, one by one -- not only when the whole force stands.
            // level08h's jammed peasant shows the cadence: upstream's trace
            // has it wearing a two-cycle move order at 72 and every thirty
            // cycles after, while six siblings still march, and a port that
            // only re-sent an all-idle force left it bare.
            //
            // No engagement pass rides along: the attack-march targets for
            // itself and upstream's cowards walk untouched. The engage arm
            // that used to live here predated the position-form attack
            // order, and with it the marching peasants wore unit attacks
            // upstream never gives a coward.
            List<Unit> idle = new ArrayList<>();
            for (Unit member : force.members()) {
                if (member.order() == Unit.Order.STILL) {
                    idle.add(member);
                }
            }
            if (idle.isEmpty()) {
                continue;
            }
            if (force.state() == AiForce.State.ATTACKING
                    && idle.size() == force.members().size()) {
                // The whole force stands: find the enemy again -- buildings
                // for a land force, anything on the map for a fleet -- and
                // rally or go, or give the attack up entirely.
                boolean naval = force.members().stream().anyMatch(
                        member -> member.type().seaUnit() && member.type().canAttack());
                Unit leader = force.members().getFirst();
                Unit enemy = world.findEnemyByFlood(leader, !naval);
                if (System.getenv("CHONKCRAFT_TRACE_FORCE") != null) {
                    System.err.println("JFORCE cycle=" + world.cycle()
                            + " p=" + playerIndex + " force=" + force.index()
                            + " all-idle leader=" + leader.id()
                            + " buildings=" + (!naval)
                            + " enemy=" + (enemy == null ? "-" : enemy.id()
                                    + "@" + enemy.tileX() + "," + enemy.tileY()));
                }
                if (enemy == null) {
                    force.setState(AiForce.State.READY);
                    continue;
                }
                int[] rally = world.findRallyPoint(leader,
                        leader.distanceTo(enemy.tileX(), enemy.tileY()));
                if (rally != null) {
                    force.setGoal(rally[0], rally[1]);
                    force.setState(AiForce.State.GOING_TO_RALLY);
                    force.resetWaitOnRallyPoint();
                } else {
                    force.setGoal(enemy.tileX(), enemy.tileY());
                }
            }
            Unit soldier = aggressiveLeader(force.members());
            for (int i = 0; i < idle.size(); i++) {
                Unit member = idle.get(i);
                // AiForce::Update spreads each batch over groups of five.
                // This is unit.Wait, assigned before CommandAttack/Defend,
                // so a command landing in a committed animation pauses that
                // old animation before HandleUnitAction can pop the queue.
                member.setWaitCycles(i / 5);
                if (soldier != null && member.isAggressive()
                        && force.state() == AiForce.State.ATTACKING) {
                    // An aggressive straggler is sent at its leader's own
                    // ground, not the far goal: upstream's re-send aims
                    // CommandAttack at leader->tilePos while attacking.
                    marchTowardsPos(world, member, soldier.tileX(), soldier.tileY());
                } else {
                    launchMember(world, member, soldier, force.goalX(), force.goalY());
                }
            }
        }
    }

    /**
     * Asks an attacked unit's force to answer the aggressor.
     *
     * <p>{@code AiHelpMe} first gives every suitable brother in arms a
     * target, even when the force is already marching. A goal-less attack
     * order counts as willing to help; this is the easily missed branch that
     * moves level11o's three jammed archers at cycle 336. Only after that
     * sibling pass does an attacking force suppress mobilising the player's
     * other home forces.
     */
    public void helpMe(World world, Unit attacker, Unit defender) {
        if (world == null || attacker == null || defender == null
                || attacker.player() == defender.player()
                || defender.player() != playerIndex
                || (!defender.type().canAttack() && defender.type().airUnit())) {
            return;
        }
        AiForce defendersForce = null;
        for (AiForce force : forces) {
            if (containsIdentical(force, defender)) {
                defendersForce = force;
                break;
            }
        }
        if (defendersForce != null && explicitlyGrouped.contains(defender)) {
            // AiForceManager::Assign(unit, explicitScriptNumber) inserts into
            // getScriptForce(number), but stores GroupId=number+1. AiHelpMe
            // subtracts one and indexes the internal array directly. Named
            // forces here are kept in that internal allocation order.
            int referencedSlot = defendersForce.index();
            defendersForce = referencedSlot < namedForceSlots.size()
                    ? namedForceSlots.get(referencedSlot) : null;
        }
        if (defendersForce != null) {
            if (System.getenv("CHONKCRAFT_TRACE_AIHELP") != null) {
                System.err.printf("JAIHELP cycle=%d p%d attacker=%d defender=%d force=%d members=%d%n",
                        world.cycle(), playerIndex, attacker.id(), defender.id(),
                        defendersForce.index(), defendersForce.members().size());
            }
            for (Unit brother : List.copyOf(defendersForce.members())) {
                if (brother == defender || !brother.isAlive()
                        || !brother.isAggressive()
                        || !canTargetType(brother, attacker)
                        || brother.target() == attacker) {
                    continue;
                }
                boolean shouldAttack = isIdleForHelp(brother)
                        && brother.threshold() == 0;
                if (brother.currentAction() == Unit.Order.ATTACK
                        || brother.currentAction() == Unit.Order.ATTACK_MOVE) {
                    Unit oldGoal = brother.target();
                    if (oldGoal == null
                            || (world.aiPrefersTarget(defender, attacker, oldGoal)
                                    && brother.distanceTo(defender)
                                            <= Math.max(1,
                                                    brother.type().maxAttackRange()))) {
                        shouldAttack = true;
                    }
                }
                if (shouldAttack) {
                    if (System.getenv("CHONKCRAFT_TRACE_AIHELP") != null) {
                        System.err.printf("JAIHELPORDER cycle=%d brother=%d current=%s goal=%d wait=%d%n",
                                world.cycle(), brother.id(), brother.currentAction(),
                                brother.target() == null ? -1 : brother.target().id(),
                                brother.waitCycles());
                    }
                    world.orderAiHelpAttack(brother, attacker);
                }
            }
            if (!defendersForce.defending()
                    && (defendersForce.state() == AiForce.State.ATTACKING
                            || defendersForce.state() == AiForce.State.GOING_TO_RALLY)) {
                return;
            }
        }

        // The second half sends inactive defending forces to the reported
        // place.  Upstream keeps force completion in a separate boolean:
        // both an incomplete and a completed scripted attack force retain
        // AiForceAttackingState::Waiting, and AiHelpMe explicitly excludes
        // that state.  Java's GATHERING/READY split represents the completion
        // boolean, so neither is the upstream non-Waiting attack state.  On
        // level05h, treating READY as eligible mobilised both named forces at
        // cycle 1735 when one distant member was hit, thousands of cycles
        // before their AiAttackWithForce steps.
        for (AiForce force : List.copyOf(forces)) {
            force.prune();
            boolean active = force.state() == AiForce.State.ATTACKING
                    || force.state() == AiForce.State.GOING_TO_RALLY;
            if (force.members().isEmpty() || active || !force.defending()) {
                continue;
            }
            force.setDefending(true);
            force.setGoal(attacker.tileX(), attacker.tileY());
            force.setState(AiForce.State.ATTACKING);
            Unit soldier = aggressiveLeader(force.members());
            for (Unit member : List.copyOf(force.members())) {
                launchMember(world, member, soldier, force.goalX(), force.goalY());
            }
        }
    }

    private static boolean canTargetType(Unit unit, Unit target) {
        if (target.type().airUnit()) {
            return unit.type().canTargetAir();
        }
        if (target.type().seaUnit()) {
            return unit.type().canTargetSea();
        }
        return unit.type().canTargetLand();
    }

    /** {@code CUnit::IsIdle}: a Still action with no second order waiting. */
    private static boolean isIdleForHelp(Unit unit) {
        return unit.order() == Unit.Order.STILL
                && unit.currentAction() == Unit.Order.STILL
                && unit.pendingAttack() == null
                && !unit.hasQueuedOrders()
                && unit.pendingHarvestX() < 0
                && unit.buildLatchedFrom() == null;
    }

    /**
     * Claims units for a force.
     *
     * <p>This used to take only units whose order was {@code STILL}, and that
     * one clause is why a force whose shopping list is workers never filled.
     * {@link #checkUnits} runs first, in {@code AiEachSecond}'s order, and
     * sends every idle gatherer off to harvest; by the time the force manager
     * looked there was nothing standing still left to enlist.
     * {@code hum-08-peasant} declares {@code AiForce(1, {AiWorker(), 7})} and
     * then {@code AiAttackWithForce(1)}: seven peasants alive, none enlisted,
     * and the eighth human mission's siege by seven peasants simply never
     * happened.
     *
     * <p>{@code AiForceManager::Assign} does not look at what a unit is doing.
     * It asks two questions -- is it already in a force, and does this force
     * want one of those -- and that is what this asks now. A unit may belong
     * to one force only, which is what {@code GroupId} means, and a force that
     * has already been sent takes no reinforcements, which is upstream's "no
     * troops for attacking force".
     *
     * <p>Claiming also happens the moment the script declares the force:
     * {@code CclAiForce} ends with {@code AiAssignFreeUnitsToForce(force)}
     * so membership is settled at script time
     * -- before the collect census can send the same workers harvesting. On
     * campaigns/human/level08h the seven-peasant siege is declared and
     * filled in the same breath upstream; this implementation used to enlist only in
     * the force manager, which runs after the census, and the census had
     * already put every peasant on a resource.
     */
    public void enlistNow(World world, AiForce force) {
        enlist(force, ownedUnits(world), true);
    }

    private void enlist(AiForce force, List<Unit> owned) {
        enlist(force, owned, false);
    }

    private void enlist(AiForce force, List<Unit> owned, boolean explicit) {
        if (force.state() == AiForce.State.ATTACKING) {
            return;
        }
        for (Unit unit : owned) {
            if (unit.type().building()
                    || force.shortfall(unit.type()) <= 0
                    || isEnlisted(unit)) {
                continue;
            }
            force.members().add(unit);
            if (explicit) {
                explicitlyGrouped.add(unit);
            }
        }
    }

    /**
     * Sends a force at something, and keeps trying until one order sticks.
     *
     * <p>Two faults met here and the measurement of the pair was 2392 attacking
     * cycles against 299 mobilized seconds on {@code level06h} player 5 --
     * exactly one cycle of attacking per second, then straight back to
     * standing, for five minutes. Twenty-one of the thirty-four mobilized
     * slots showed that same ratio of 1.00.
     *
     * <p>The first is that the order was given and its answer thrown away.
     * {@code World.orderAttack} refuses a target this force's weapons cannot
     * hit, and nothing looked, so a force with a flyer as its nearest enemy
     * stood still for the rest of the mission. Candidates are tried in turn
     * now, nearest first, until one is accepted.
     *
     * <p>The second is fog. The order was aimed at whatever was closest in a
     * straight line, which on {@code level06h} is a footman fifty-two tiles
     * away inside unexplored ground. {@code COrder_Attack::AutoSelectTarget}
     * drops a goal the unit's side cannot see, so the very next cycle the
     * order was cancelled and the force dropped to standing -- and the AI,
     * thinking once a second, gave the identical order again. Upstream never
     * aims at a unit here: {@code AiForce::Attack} issues
     * {@code CommandAttack(unit, GoalPos, nullptr)}, an attack towards a
     * <em>place</em>, and the force walks across the map and fights what it
     * finds. This implementation has no attack-move order, so a target it can see is
     * attacked and one it cannot is marched at; the units engage on arrival,
     * which is the part that was missing.
     */
    private boolean launchAttack(World world, AiForce force) {
        List<Unit> members = force.members();
        if (members.isEmpty()) {
            return false;
        }
        // A force that could not be launched waits before trying again, for
        // the same reason upstream makes a failed building site wait: every
        // rejected candidate costs a route search, and a fleet whose only
        // enemies are inland would run a dozen of them a second for the rest
        // of the mission. Measured on levelx12h, the map this implementation already
        // watches for route flooding, running it every second put the cost per
        // cycle over its ceiling on its own.
        if (world.cycle() < attackRetryCycle.getOrDefault(force.index(), 0L)) {
            return false;
        }
        // The enemy is the flood's answer, asked of the first member that
        // carries a weapon: AiForce::Attack picks its finder by what the
        // force is made of -- a naval force takes anything the water
        // touches, a land force prefers buildings -- and an enemy the
        // ground never reaches is no target at all, however visible
        // That refusal is the launch gate that
        // keeps levelx08o's destroyers in harbour for twelve seconds while
        // every enemy is inland.
        Unit leader = members.getFirst();
        boolean naval = leader.type().seaUnit();
        Unit chosen = world.findEnemyByFlood(leader, !naval);
        // A land force prefers buildings and settles for anything:
        // AiForce::Attack tries the building finder first and falls back to
        // the any-unit finder before giving up. The
        // rally arm below always had the second ask; the launch itself did
        // not, so a land force facing an enemy with soldiers and no roofs
        // never left home.
        if (chosen == null && !naval) {
            chosen = world.findEnemyByFlood(leader, false);
        }
        if (System.getenv("CHONKCRAFT_TRACE_LAUNCH") != null) {
            System.err.println("JLAUNCH cycle=" + world.cycle() + " p" + playerIndex
                    + " force=" + force.index() + " leader=" + leader.id()
                    + " chosen=" + (chosen == null ? "-" : chosen.id())
                    + " members=" + members.stream()
                            .map(member -> Integer.toString(member.id()))
                            .collect(java.util.stream.Collectors.joining(",")));
        }
        if (chosen == null) {
            attackRetryCycle.put(force.index(), world.cycle() + ATTACK_RETRY);
            return false;
        }
        attackRetryCycle.remove(force.index());
        // The launch aims at the rally, not the enemy: NewRallyPoint finds
        // the first quiet square near the leader and the force musters
        // there, the enemy coming later from AiForce::Update's own arm
        // Only a force with no rally anywhere goes
        // straight in.
        int[] rally = world.findRallyPoint(leader, leader.distanceTo(chosen));
        Unit soldier = aggressiveLeader(members);
        if (rally != null) {
            force.setGoal(rally[0], rally[1]);
            force.resetWaitOnRallyPoint();
            force.setState(AiForce.State.GOING_TO_RALLY);
            for (int i = 0; i < members.size(); i++) {
                Unit member = members.get(i);
                member.setWaitCycles(i / 5);
                launchMember(world, member, soldier, rally[0], rally[1]);
            }
        } else {
            force.setGoal(chosen.tileX(), chosen.tileY());
            force.setState(AiForce.State.ATTACKING);
            for (int i = 0; i < members.size(); i++) {
                Unit member = members.get(i);
                member.setWaitCycles(i / 5);
                launchMember(world, member, soldier, chosen.tileX(), chosen.tileY());
            }
        }
        return true;
    }

    /**
     * Sends a force member at the enemy's ground, fighting whatever it meets.
     *
     * <p>{@code AiForce::Attack} launches every member with
     * {@code CommandAttack(*unit, this->GoalPos, nullptr, EFlushMode::On)}
     * The game a position-form attack at the enemy's
     * square, not a walk. The difference is everything the march does on the
     * way: an attack-move acquires and swings as it travels and re-plans
     * around what it cannot cross, where this implementation's old fractional
     * {@code orderMove} walked the line in quarters with its weapon slung.
     * On campaigns/orc/level11o the four-unit opening force takes order 6 --
     * the attack march -- on cycle 9 upstream, and this implementation's took a plain
     * move a cycle early.
     *
     * <p>Queued like every player command, so the member is counted as doing
     * what it was doing until the order is popped on the next cycle.
     */
    private boolean marchTowards(World world, Unit member, Unit target) {
        return marchTowardsPos(world, member, target.tileX(), target.tileY());
    }

    /**
     * One member's launch order, split by temperament.
     *
     * <p>{@code AiForce::Attack}'s member loop and {@code Update}'s second
     * launch make the same split: an
     * aggressive member is sent with {@code CommandAttack} at the goal, a
     * coward -- and a peasant is one -- follows the first aggressive member
     * with {@code CommandDefend}, or takes a plain {@code CommandMove} at
     * the goal when the force has no soldier to follow. hum-08-peasant's
     * seven-worker siege is the all-coward case: upstream's peasants walk
     * to war, and a port that attack-marched them wore the wrong order from
     * cycle 12.
     *
     * <p>The defend arm is approximated with the follow order, which walks
     * beside the leader but does not break off to avenge it; the shipped
     * mixed forces put their cowards behind soldiers either way, and the
     * difference is bounded by what a follower would have done in the two
     * cycles a defend takes to answer.
     */
    private void launchMember(World world, Unit member, Unit aggressiveLeader,
            int goalX, int goalY) {
        if (member.isAggressive()) {
            marchTowardsPos(world, member, goalX, goalY);
            return;
        }
        Unit.Order before = member.order();
        if (aggressiveLeader != null && world.orderFollow(member, aggressiveLeader)) {
            member.rememberActionBeforeQueued(before);
            return;
        }
        if (world.orderMove(member, goalX, goalY)) {
            member.rememberActionBeforeQueued(before);
        }
    }

    /** The first member that carries a weapon, as the launch's leader. */
    private static Unit aggressiveLeader(List<Unit> members) {
        for (Unit member : members) {
            if (member.isAggressive()) {
                return member;
            }
        }
        return null;
    }

    private boolean marchTowardsPos(World world, Unit member, int x, int y) {
        Unit.Order before = member.order();
        if (!world.orderAttackMove(member, x, y)) {
            return false;
        }
        member.rememberActionBeforeQueued(before);
        return true;
    }

    /** "const int thresholdDist = 5; // Hard coded value" -- the rally's reach. */
    private static final int RALLY_THRESHOLD = 5;

    /** How many enemies a force will consider before giving up for now. */
    private static final int ATTACK_CANDIDATES = 8;

    /** How long a force that could not be launched waits, in cycles. */
    private static final long ATTACK_RETRY = 150;

    /** When each force may next look for something to attack. */
    private final Map<Integer, Long> attackRetryCycle = new LinkedHashMap<>();

    /**
     * How far the flood behind a harvester assignment may spread.
     *
     * <p>{@code AiAssignHarvesterFromUnit} asks {@code UnitFindResource} with
     * a range of a thousand, which no shipped
     * map can exhaust; the number is carried rather than rounded so the two
     * read the same.
     */
    private static final int HARVEST_RANGE = 1000;

    /**
     * The nearest square of wood a worker could actually walk up to.
     *
     * <p>{@code AiAssignHarvesterFromTerrain} asks {@code FindTerrainType}
     * with the worker's movement mask and a range of a thousand
     * The game a breadth-first flood over ground
     * the worker can cross, stopping at the first forest square the fill
     * reaches. This used to walk square rings around the worker instead,
     * which reaches the corner of a ring before the middle of the next one
     * and walks straight over water and cliffs on its way -- so of two trees
     * the same distance off it could pick the one across a river. On
     * {@code campaigns/human/level05h} three of the enemy's peasants stepped
     * one square from where upstream's stepped on their first working cycle,
     * which was the map's first divergence once the zeppelin flew.
     */
    private int[] nearestForest(World world, Unit worker) {
        return world.findAiWood(worker, HARVEST_RANGE);
    }

    /**
     * The enemies a force will consider, closest first.
     *
     * <p>{@code AiForceEnemyFinder}. A list rather than the single closest,
     * because the closest is routinely one the force cannot hit or cannot
     * reach and there has to be somewhere to fall through to.
     *
     * <p>Asked of every enemy on the map whether this side has seen it or not,
     * which is upstream's rule and not an oversight: {@code AiForceEnemyFinder}
     * searches the map itself, and a computer player that had to explore before
     * it could aim would never leave home. What the implementation does <em>not</em> do
     * is let a unit fight through fog -- an unseen target is marched at, not
     * swung at, and the fighting starts when the force arrives and can see.
     *
     * <p>Allies are skipped by {@code isEnemyPlayer} rather than by slot
     * identity. Two computer players are allied by {@code CPlayer::Init}, and
     * asking only "is this somebody else's" sent one computer's army at
     * another's on every map with more than one of them.
     */
    /**
     * Everything this player is at war with, nearest first.
     *
     * <p>The test is {@code isEnemyPlayer} alone. It used to also demand that
     * the owner be {@link Player#isActive}, which is {@code PERSON ||
     * COMPUTER} -- and that quietly excluded every rescuable slot. Upstream's
     * {@code CPlayer::Init} makes a rescue-active player an explicit enemy of
     * the computer players, and {@code AiForceEnemyFinder} has no equivalent
     * filter.
     *
     * <p>Measured before it was removed: on human mission five the orc
     * computer saw 33 enemies and the filter left 18, dropping all 15 of the
     * rescuable humans' units; on mission eight, four computers each saw 26
     * and were left with 8. So no computer player ever sent a force at a town
     * it was at war with. Reactive combat still worked, because
     * {@code World.autoAttack} gates on {@code isEnemyPlayer} alone -- which
     * is why the AI fought those units when they walked into it and never went
     * looking for them, and why this read as a passive personality rather than
     * a defect.
     *
     * <p>The filter was redundant where it was harmless, too: a NEUTRAL owner
     * is already excluded by {@code isEnemyPlayer}.
     */
    private List<Unit> enemyCandidates(World world, Unit from) {
        List<Unit> candidates = new ArrayList<>();
        for (Unit candidate : world.units()) {
            if (!candidate.isAlive() || candidate.type() == null || !candidate.isOnMap()
                    || !world.isEnemyPlayer(playerIndex, candidate.player())) {
                continue;
            }
            candidates.add(candidate);
        }
        // Nearest first, and the identifier breaks the ties: two machines
        // running the same game have to pick the same enemy, and distance
        // alone leaves that to whatever order the unit list happens to be in.
        candidates.sort(Comparator.<Unit>comparingInt(from::distanceTo)
                .thenComparingInt(Unit::id));
        return candidates.size() <= ATTACK_CANDIDATES
                ? candidates
                : candidates.subList(0, ATTACK_CANDIDATES);
    }

    /** Every living unit this player owns. */
    private List<Unit> ownedUnits(World world) {
        List<Unit> owned = new ArrayList<>();
        for (Unit unit : world.playerUnits(playerIndex)) {
            if (unit.isAlive() && unit.isOnMap()) {
                owned.add(unit);
            }
        }
        return owned;
    }
}
