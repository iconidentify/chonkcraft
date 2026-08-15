package net.chonkbase.chonkcraft.engine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.animation.AnimationRunner;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.animation.AnimationState;
import net.chonkbase.chonkcraft.data.map.PudUnitTypes;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.ai.AiPlayer;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.map.FogOfWar;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.missile.MissileClass;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.map.SeenBuildings;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.pathfinder.BattleNetPathFinder;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.spell.Spell;
import net.chonkbase.chonkcraft.engine.spell.SpellSet;
import net.chonkbase.chonkcraft.engine.upgrade.Upgrade;
import net.chonkbase.chonkcraft.engine.upgrade.UpgradeSet;
import net.chonkbase.chonkcraft.engine.upgrade.UpgradeState;

/**
 * The running game: a map, the units on it, and the cycle that advances them.
 *
 * <p>Owns the action systems that create, move, fight, harvest, and remove units.
 *
 * <p>Runs at {@link #CYCLES_PER_SECOND}, matching {@code CYCLES_PER_SECOND} in
 * {@code settings.h}. The rate is part of the game's behaviour, not a
 * rendering choice: unit speeds, build times and spell durations are all
 * counted in cycles, so changing it would change the game.
 */
public final class World {

    /** Simulation rate. Must stay 30 to match upstream timings. */
    public static final int CYCLES_PER_SECOND = 30;

    /** LegacyEngine/ChonkCraft's ordinary synchronized and load-time seed. */
    public static final int DEFAULT_RANDOM_SEED = 0x87654321;

    final GameMap map;
    final PathFinder pathFinder;
    /**
     * Seeded on purpose. Animations branch on random-goto, and a simulation
     * that has to stay in step across machines cannot pull from a source that
     * differs between them.
     */
    /**
     * The simulation's random seed, which is the generator's whole state.
     *
     * <p>Implements {@code SyncRandSeed}. The
     * generator upstream is a plain linear congruential one, and its comment
     * says why: "This random value must be same on all machines in network
     * game. Very simple random generations, enough for us."
     *
     * <p>Simplicity is the feature, not a compromise. Because one integer is
     * the entire state, a saved game restores the sequence by restoring that
     * integer, and two machines agree without exchanging anything. A better
     * generator would give up both: {@code java.util.Random.nextInt(bound)}
     * rejects and redraws for bounds that are not powers of two, so it does
     * not even consume a fixed amount of its own stream, and a save could not
     * put it back by counting.
     */
    /**
     * The synchronised random stream's seed.
     *
     * <p>{@code InitSyncRand} starts it at this value, and starting at zero is
     * not merely a different game -- it is a worse one. The generator is
     * multiplicative, so from zero its first several draws are all zero, and
     * every blow at the start of a game landed for its full nominal damage
     * with none of the variance that makes Warcraft II's combat swing.
     */
    private int randomSeed = DEFAULT_RANDOM_SEED;

    /** Retail BNE's unsynchronised constructor/idle random stream. */
    int battleNetRandomSeed = DEFAULT_RANDOM_SEED;

    /** Structured opt-in evidence for aligning Java actions with native calls. */
    CausalTrace causalTrace = CausalTrace.fromEnvironment();

    /** Stable creation identities for the causal projectile lifecycle. */
    final java.util.Map<Missile, Long> battleNetProjectileCausalOrdinals =
            new java.util.IdentityHashMap<>();
    long nextBattleNetProjectileCausalOrdinal;

    /**
     * Sends causal evidence somewhere a test can read instead of a file.
     *
     * <p>The ordinary game never calls this and keeps the environment's
     * decision, which is no evidence at all. Without it the only way to check
     * that a draw names the class that asked for it would be to run a whole
     * fixture and read a file, and the check that matters -- that a subsystem
     * extraction did not turn its callers into question marks -- is worth a
     * cheap test.
     */
    void recordCausalEventsTo(java.io.Writer sink, Integer unitFilter) {
        causalTrace = new CausalTrace(sink, unitFilter);
    }

    static final boolean BNE_IDLE_TRACE =
            System.getenv("CHONKCRAFT_TRACE_BNE_IDLE") != null;

    /**
     * Ownership/lifetime ledger for pending projectile constructor draws.
     * Set {@code CHONKCRAFT_TRACE_BNE_PEND=1}. Used to identify who spends the
     * Human 13 async constructor trio before trusting randContext anim labels.
     */
    static final boolean BNE_PEND_TRACE =
            System.getenv("CHONKCRAFT_TRACE_BNE_PEND") != null;

    /** Splash victim accept list; set {@code CHONKCRAFT_TRACE_BNE_SPLASH=1}. */
    static final boolean BNE_SPLASH_TRACE =
            System.getenv("CHONKCRAFT_TRACE_BNE_SPLASH") != null;

    /**
     * Single-player BNE projectile pool capacity ({@code 0x00420520}).
     * Ambient slots 0–2 stay occupied with rem=0 forever; live shots take
     * the lowest free index and the timed pass walks ascending.
     */
    static final int BNE_PROJECTILE_POOL = 200;

    /** Occupied flags for {@link #BNE_PROJECTILE_POOL}; 0–2 reserved ambient. */
    final boolean[] battleNetProjectileSlots = new boolean[BNE_PROJECTILE_POOL];

    {
        // Ambient rem=0 slots never free for real shots (XHuman 10 free@42).
        battleNetProjectileSlots[0] = true;
        battleNetProjectileSlots[1] = true;
        battleNetProjectileSlots[2] = true;
    }

    /** Lowest free BNE projectile slot, or -1 if the pool is full. */
    int allocateBattleNetProjectileSlot() {
        for (int slot = 0; slot < BNE_PROJECTILE_POOL; slot++) {
            if (!battleNetProjectileSlots[slot]) {
                battleNetProjectileSlots[slot] = true;
                return slot;
            }
        }
        return -1;
    }

    /** Returns a slot when the shot leaves the live list. */
    void freeBattleNetProjectileSlot(int slot) {
        if (slot >= 3 && slot < BNE_PROJECTILE_POOL) {
            battleNetProjectileSlots[slot] = false;
        }
    }

    /** World cycle when each pending shot was put (for queue/flush contrast). */
    final java.util.Map<Missile, Long> battleNetPendingProjectileQueuedCycle =
            new java.util.IdentityHashMap<>();

    /**
     * Players that already promoted a spatial-help Attack this cycle.
     * Native staggers brothers that share a react+1 band (XHuman 12 ogres
     * 1381 then 1394); promoting every Still visit collapsed them.
     */
    private final boolean[] battleNetHelpPromotedThisCycle =
            new boolean[Player.MAX];
    /** Person help staggers one quiet cycle after the first promote only. */
    private final int[] battleNetPersonHelpLastPromoteCycle =
            new int[Player.MAX];
    private final int[] battleNetPersonHelpPromoteCount = new int[Player.MAX];

    /**
     * Set when {@link #stepAttack} drains the old Move element before its
     * chase-boundary consult so {@link #stepMove} does not walk twice.
     * Retail's NextPathElement runs after that drain; without the shared
     * flag the consult saw Moving still set, skipped the equal-score
     * retarget, and Human 13 ogre 1482 kept the knight's leftover N.
     */
    boolean actionMoveWalked;

    /** Authoritative BNE action timing loaded from maindat entry 278. */
    BattleNetSequence battleNetSequence;

    /** Native UDTA byte priorities, kept separate from ChonkCraft unit types. */
    int[] battleNetUnitPriorities;

    /** World-local UDTA combat and cost tables. Null or useDefaults leaves the catalog alone. */
    private net.chonkbase.chonkcraft.data.map.PudMap.PudUnitData battleNetUnitProfile;

    /** World-local UGRD costs. Null or useDefaults leaves the catalog alone. */
    private net.chonkbase.chonkcraft.data.map.PudMap.PudUpgradeData battleNetUpgradeProfile;

    /**
     * Retail BNE's mutable map-square {@code 0x400} construction exclusion.
     *
     * <p>This is deliberately not reference counted. Native function
     * {@code 0x438560} paints the bit and {@code 0x438610} clears it; clearing
     * one AI job may therefore punch a hole through a building's older
     * exclusion field. Several campaign opening placements depend on that
     * overlap behavior.</p>
     */
    final boolean[] battleNetNoBuild;

    /** Workers whose current build order painted {@link #battleNetNoBuild}. */
    final java.util.Set<Unit> battleNetAiBuildReservations =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Attack animation markers awaiting the order's same-call outcome. */
    final java.util.Set<Unit> battleNetAttackMarkers =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Mobile projectile shots waiting for BNE animation opcode ten. */
    final java.util.Map<Unit, Missile> battleNetPendingProjectileShots =
            new java.util.IdentityHashMap<>();

    /** Ranged OP10 shots already fired before presentation reached its hit. */
    final java.util.Set<Unit> battleNetSequenceProjectileFired =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Terrain squares reserved from free forest to claimed forest by
     * {@code FUN_0044dec0} (native map codes {@code -2} → {@code -4}).
     *
     * <p>Indexed as {@code x + y * map.width()}. Only the claiming worker may
     * swing on that tree; a later arrival in pool order re-aims within fifteen
     * without drawing. Released when the claim owner leaves or the tree
     * falls, matching {@code FUN_0044df10}.
     */
    final java.util.Map<Integer, Unit> battleNetClaimedWood =
            new java.util.HashMap<>();

    /**
     * Mobile melee blows waiting for BNE animation opcode ten.
     *
     * <p>ChonkCraft's presentation animation can invoke the melee command one
     * scheduler call before retail {@code script.bin} reaches opcode 10.
     * Human 5's standing grunt hit the barracks at fixture cycle 15 and
     * burned a synchronized damage roll; native lands three damage at cycle
     * 16 from the asynchronous stream only. The named victim is preserved
     * here and resolved at the authoritative opcode-10 boundary.
     */
    final java.util.Map<Unit, Unit> battleNetPendingMeleeHits =
            new java.util.IdentityHashMap<>();

    /**
     * Melee blows whose damage roll is owned by native opcode ten
     * ({@code FUN_00418370} / async half-band, basic-armor floor 0).
     */
    final java.util.Set<Unit> battleNetNativeMeleeDamage =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /** Opcode-ten boundaries that may fire later in the same unit call. */
    final java.util.Set<Unit> battleNetInlineAttackMarkers =
            java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

    /**
     * Mobile shots whose presentation ran mid-wait before OP10; constructor
     * draws are spent after the unit loop so same-cycle Still OP0 markers
     * keep their place in the async stream (Human 13 critter 1576).
     */
    final java.util.List<Unit> battleNetCycleEndConstructorDebit =
            new java.util.ArrayList<>();

    /** Stand-ground OP10 shots whose constructor runs after the unit table. */
    final java.util.List<Missile> battleNetCycleEndProjectileArm =
            new java.util.ArrayList<>();

    /** BNE projectile animation start, keyed by Java's already-created shot. */
    final java.util.Map<Missile, Long> battleNetProjectileStartCycles =
            new java.util.IdentityHashMap<>();

    /** Kept for the record only; the seed alone restores the sequence. */
    private long randomDraws;

    /**
     * How many asynchronous draws have been taken, for the causal ledger.
     *
     * <p>Kept for the record like {@code randomDraws}: the seed restores the
     * sequence by itself. What the ordinal buys the cross-engine ledger is a
     * way to say "native's fourth draw, Java's fifth" when one engine takes a
     * number the other does not.
     */
    private long battleNetRandomDraws;
    /**
     * Animations draw from the same sequence as everything else.
     *
     * <p>Upstream's animations call {@code SyncRand} directly, so a random
     * wait in an idle animation advances the same seed a damage roll does.
     * Giving the runner its own generator would look harmless and put two
     * machines out of step the first time a unit stood still.
     */
    private final AnimationRunner animations = new AnimationRunner(
            new java.util.random.RandomGenerator() {
                @Override
                public long nextLong() {
                    return ((long) syncRand() << 32) | (syncRand() & 0xFFFFFFFFL);
                }

                @Override
                public int nextInt(int bound) {
                    return syncRand(bound);
                }

                /**
                 * One draw, which is what {@code SyncRand()} is.
                 *
                 * <p>Overridden rather than inherited on purpose. The
                 * interface's default builds an int out of
                 * {@link #nextLong()}, which draws <em>twice</em>, and a
                 * draw that happens here and not upstream is exactly the
                 * desync the differential harness exists to catch --
                 * {@code random-rotate} reads one draw and so must this.
                 */
                @Override
                public int nextInt() {
                    return syncRand();
                }
            });

    /**
     * Optional hook for tests. Retail BNE leaves random animation flourishes
     * disabled so idle units do not advance the synchronized seed.
     */
    public void setRandomAnimationInstructionsEnabled(boolean enabled) {
        animations.setRandomInstructionsEnabled(enabled);
    }

    /** @see BattleNetTargetSelection#isVisibleAsGoal */
    public boolean isVisibleAsGoal(int player, Unit unit) {
        return targets.isVisibleAsGoal(player, unit);
    }

    /** @see BattleNetHarvestSystem#orderHarvest */
    public boolean orderHarvest(Unit worker, int tileX, int tileY) {
        return harvest.orderHarvest(worker, tileX, tileY);
    }

    /** Quiet visits a player harvest or move click waits before the walk. */
    public int playerCommandDelay(Unit unit) {
        return movement.playerCommandDelay(unit);
    }

    /** @see BattleNetHarvestSystem#orderHarvest */
    public boolean orderHarvest(Unit worker, Unit resourceBuilding) {
        return harvest.orderHarvest(worker, resourceBuilding);
    }

    /** @see BattleNetHarvestSystem#canHarvestAt */
    public boolean canHarvestAt(Unit worker, int tileX, int tileY) {
        return harvest.canHarvestAt(worker, tileX, tileY);
    }

    /** @see BattleNetHarvestSystem#findAiWood */
    public int[] findAiWood(Unit worker, int range) {
        return harvest.findAiWood(worker, range);
    }

    /** @see BattleNetHarvestSystem#findResourceUnit */
    public Unit findResourceUnit(Unit worker, UnitType.Resource resource, int range) {
        return harvest.findResourceUnit(worker, resource, range);
    }

    /** @see BattleNetHarvestSystem#findResourceUnitFromWorker */
    public Unit findResourceUnitFromWorker(
            Unit worker, UnitType.Resource resource, int range) {
        return harvest.findResourceUnitFromWorker(worker, resource, range);
    }

    /** @see BattleNetHarvestSystem#findBattleNetReadyGoldMine */
    public Unit findBattleNetReadyGoldMine(Unit worker) {
        return harvest.findBattleNetReadyGoldMine(worker);
    }

    /** @see BattleNetHarvestSystem#findBattleNetReadyOilPlatform */
    public Unit findBattleNetReadyOilPlatform(Unit tanker) {
        return harvest.findBattleNetReadyOilPlatform(tanker);
    }

    /** @see BattleNetHarvestSystem#restoreHarvestState */
    public void restoreHarvestState(Unit worker, Unit resource, int tileX, int tileY,
            boolean returningToDepot, int waitCycles) {
        harvest.restoreHarvestState(worker, resource, tileX, tileY,
                returningToDepot, waitCycles);
    }

    /** Repairs schema-three saves written with native oil action 24 but no resource order. */
    public void repairRestoredOilOrders() {
        harvest.repairRestoredOilOrders();
    }

    /** @see BattleNetConstructionSystem#cancelConstruction */
    public boolean cancelConstruction(Unit site) {
        return construction.cancelConstruction(site);
    }

    /** @see BattleNetConstructionSystem#orderBuild */
    public boolean orderBuild(Unit worker, UnitType what, int tileX, int tileY) {
        return construction.orderBuild(worker, what, tileX, tileY);
    }

    /** @see BattleNetConstructionSystem#orderBattleNetAiBuild */
    public boolean orderBattleNetAiBuild(Unit worker, UnitType what,
            int tileX, int tileY) {
        return construction.orderBattleNetAiBuild(worker, what, tileX, tileY);
    }

    /** @see BattleNetConstructionSystem#mayBuild */
    public boolean mayBuild(UnitType worker, UnitType what) {
        return construction.mayBuild(worker, what);
    }

    /**
     * Whether this worker may raise this building on this mission.
     *
     * <p>The button table is only the first question. Human 1 forbids a
     * barracks even though a peasant's buttons name one, and the first
     * stronghold is still gated by the keep-equivalent until the hall
     * upgrades. Asking only the relation let a typed command found what
     * the panel had hidden.
     */
    public boolean mayBuild(Unit worker, UnitType what) {
        if (worker == null || !mayBuild(worker.type(), what)) {
            return false;
        }
        return productionRefusal(worker.player(), what.ident()) == null;
    }

    /** @see BattleNetConstructionSystem#canPlaceBuilding */
    public boolean canPlaceBuilding(UnitType what, int tileX, int tileY) {
        return construction.canPlaceBuilding(what, tileX, tileY);
    }

    /** @see BattleNetConstructionSystem#canPlaceBuilding */
    public boolean canPlaceBuilding(Unit builder, UnitType what, int tileX, int tileY) {
        return construction.canPlaceBuilding(builder, what, tileX, tileY);
    }

    /** @see BattleNetConstructionSystem#orderRepair */
    public boolean orderRepair(Unit unit, Unit target) {
        return construction.orderRepair(unit, target, false);
    }

    /**
     * @param fromPlayer {@code true} for a GiveOrder click: a soldier on
     *     Still writes next_order 27 through the remaining Still wait
     */
    public boolean orderRepair(Unit unit, Unit target, boolean fromPlayer) {
        return construction.orderRepair(unit, target, fromPlayer);
    }

    /** @see BattleNetMovementSystem#orderMove */
    public boolean orderMove(Unit unit, int tileX, int tileY) {
        return movement.orderMove(unit, tileX, tileY);
    }

    /** Applies a player command at the deterministic network command boundary. */
    public boolean orderCommandMove(Unit unit, int tileX, int tileY) {
        return movement.orderCommandMove(unit, tileX, tileY);
    }

    /** @see BattleNetCombatSystem#orderAttackMove */
    public boolean orderAttackMove(Unit unit, int tileX, int tileY) {
        return combat.orderAttackMove(unit, tileX, tileY);
    }

    /** @see BattleNetCombatSystem#hit */
    public void hit(Unit attacker, Unit target) {
        combat.hit(attacker, target);
    }

    /** @see BattleNetIdleSystem#fireBattleNetReadyForAll */
    public void fireBattleNetReadyForAll() {
        idle.fireBattleNetReadyForAll();
    }

    /** What a unit does when it has been given nothing to do. */
    final BattleNetIdleSystem idle = new BattleNetIdleSystem(this);

    /** Closing on something, swinging at it, and taking the blow back. */
    final BattleNetCombatSystem combat = new BattleNetCombatSystem(this);

    /** Walking: the route, the step off it, and the pixels in between. */
    final BattleNetMovementSystem movement = new BattleNetMovementSystem(this);

    /** Putting a building up, and keeping it up. */
    final BattleNetConstructionSystem construction =
            new BattleNetConstructionSystem(this);

    /** A worker's round trip: out to the resource, and home with the load. */
    final BattleNetHarvestSystem harvest = new BattleNetHarvestSystem(this);

    /** What a unit may shoot at, and which of those it picks. */
    final BattleNetTargetSelection targets =
            new BattleNetTargetSelection(this);

    /** Every shot in flight, from its constructor to its impact. */
    final BattleNetProjectileSystem projectiles =
            new BattleNetProjectileSystem(this);

    /** @see BattleNetProjectileSystem#prepareBattleNetProjectile */
    void prepareBattleNetProjectile(Missile shot, boolean mobileShot) {
        projectiles.prepareBattleNetProjectile(shot, mobileShot);
    }

    /** Where this world's computer players put their buildings. */
    final BattleNetBuildingPlacement placement =
            new BattleNetBuildingPlacement(this);

    /** @see BattleNetBuildingPlacement#aiFindBuildingPlace */
    public int[] aiFindBuildingPlace(Unit worker, UnitType type, int nearX, int nearY) {
        return placement.aiFindBuildingPlace(worker, type, nearX, nearY);
    }

    /** @see BattleNetBuildingPlacement#aiFindBattleNetBuildingPlace */
    public int[] aiFindBattleNetBuildingPlace(Unit worker, UnitType type) {
        return placement.aiFindBattleNetBuildingPlace(worker, type);
    }

    /** @see BattleNetBuildingPlacement#aiFindBattleNetFoodPlace */
    public int[] aiFindBattleNetFoodPlace(Unit worker, UnitType type) {
        return placement.aiFindBattleNetFoodPlace(worker, type);
    }

    /** @see BattleNetBuildingPlacement#aiFindBattleNetHallPlace */
    public int[] aiFindBattleNetHallPlace(Unit worker, UnitType type) {
        return placement.aiFindBattleNetHallPlace(worker, type);
    }

    /** Supplies the PUD/default UDTA priorities used by BNE target scoring. */
    public void setBattleNetUnitPriorities(int[] priorities) {
        battleNetUnitPriorities = priorities == null ? null : priorities.clone();
    }

    /**
     * Installs this map's UDTA table. The shared unit catalog is not
     * rewritten, which is why Rescue cannot cheapen the next mission's
     * footman.
     */
    public void setBattleNetUnitProfile(
            net.chonkbase.chonkcraft.data.map.PudMap.PudUnitData profile) {
        battleNetUnitProfile = profile;
        if (!unitTypes.isEmpty()) {
            applyBattleNetUnitProfile();
        }
    }

    /**
     * Training and build costs for a type, with this map's UDTA overlay.
     *
     * <p>Authenticated Rescue and Garden of War store time as a raw byte
     * at UDTA 2008 (peasant 45, ballista 250) and gold/lumber as tens at
     * 2118/2228 (footman 60, farm lumber 25). When the map clears
     * useDefaults those values replace the catalog without mutating it.
     */
    java.util.Map<UnitType.Resource, Integer> unitCosts(UnitType type) {
        java.util.Map<UnitType.Resource, Integer> costs =
                type == null ? new java.util.EnumMap<>(UnitType.Resource.class)
                        : new java.util.EnumMap<>(type.costs());
        var profile = battleNetUnitProfile;
        if (type == null || profile == null || profile.useDefaults()) {
            return costs;
        }
        int code = PudUnitTypes.code(type.ident());
        if (code < 0) {
            return costs;
        }
        int gold = profile.gold(code);
        int lumber = profile.lumber(code);
        int oil = profile.oil(code);
        int time = profile.time(code);
        if (gold > 0) {
            costs.put(UnitType.Resource.GOLD, gold);
        }
        if (lumber > 0) {
            costs.put(UnitType.Resource.WOOD, lumber);
        }
        if (oil > 0) {
            costs.put(UnitType.Resource.OIL, oil);
        }
        if (time > 0) {
            costs.put(UnitType.Resource.TIME, time);
        }
        return costs;
    }

    /**
     * Installs this map's UGRD table. The shared upgrade catalog is not
     * rewritten, which is why Great Wall cannot cheapen the next mission.
     */
    public void setBattleNetUpgradeProfile(
            net.chonkbase.chonkcraft.data.map.PudMap.PudUpgradeData profile) {
        battleNetUpgradeProfile = profile;
    }

    java.util.Map<UnitType.Resource, Integer> researchCosts(Upgrade upgrade) {
        java.util.Map<UnitType.Resource, Integer> costs =
                new java.util.EnumMap<>(upgrade.costs());
        var profile = battleNetUpgradeProfile;
        if (upgrade == null || profile == null || profile.useDefaults()) {
            return costs;
        }
        int index = net.chonkbase.chonkcraft.data.map.PudUpgradeIds.indexOf(upgrade.ident());
        if (index < 0) {
            return costs;
        }
        int gold = profile.gold(index);
        int lumber = profile.lumber(index);
        int oil = profile.oil(index);
        int time = profile.time(index);
        if (gold > 0 && gold < 0xF000) {
            costs.put(UnitType.Resource.GOLD, gold);
        }
        if (lumber > 0 && lumber < 0xF000) {
            costs.put(UnitType.Resource.WOOD, lumber);
        }
        if (oil > 0 && oil < 0xF000) {
            costs.put(UnitType.Resource.OIL, oil);
        }
        if (time > 0 && time < 0xF000) {
            costs.put(UnitType.Resource.TIME, time);
        }
        return costs;
    }

    /**
     * Supplies retail BNE's {@code Rez\\script.bin} animation program.
     *
     * <p>Battle.net installations and chonkpacks expose it as maindat entry
     * 278. A missing or malformed entry simply retains the older conservative
     * timing approximation, which keeps hand-built test worlds and non-BNE
     * sources usable.</p>
     */
    public void setBattleNetSequenceData(byte[] data) {
        BattleNetSequence candidate = new BattleNetSequence(data);
        battleNetSequence = candidate.usable() ? candidate : null;
    }

    /**
     * LegacyEngine's global {@code CUnitManager::units} action table.
     *
     * <p>Its order is not creation order after the first release. Removing an
     * active unit fills its hole with the final pointer and pops the back; see
     * {@link #releaseUnitFromActionTable(Unit)}. {@code UnitActions} walks
     * this exact table, so the order decides which unit consumes each shared
     * animation and wandering random draw.
     */
    final List<Unit> units = new ArrayList<>();

    /**
     * BNE's persistent screen-Y unit order at native {@code DAT_004bf1d8}.
     *
     * <p>Implements {@code FUN_00453c00} / {@code FUN_00453ae0} / the candidate
     * walk of {@code FUN_00409ff0}. Distinct from the action table
     * ({@link #units}); target-score ties retain the first entry in this list.
     */
    final List<Unit> battleNetSpatialUnits = new ArrayList<>();

    /**
     * Critter that already ran Still OP0 for empty-route on this unit visit.
     * {@link #stepMoveOrderWithBattleNetCritter} must not idle it again.
     */
    Unit battleNetEmptyRouteIdled;

    /**
     * When set, the next constructor stream burn is counted without drawing
     * (the free-empty OP0 choice already advanced the async stream).
     */
    boolean battleNetEmptyRouteBurnSubstituted;

    /**
     * Native screen/pixel Y: {@code tileY * 32 + offsetY}, matching raw BNE
     * coordinates (XHuman 12 mover {@code 43*32+29 = 1405}).
     */
    static int battleNetScreenY(Unit unit) {
        return unit.tileY() * Unit.TILE_PIXELS + unit.offsetY();
    }

    /** Native {@code 0x453c00}: insert a new unit before an equal-Y unit. */
    private void insertBattleNetSpatialUnit(Unit unit) {
        int key = battleNetScreenY(unit);
        int index = 0;
        while (index < battleNetSpatialUnits.size()
                && battleNetScreenY(battleNetSpatialUnits.get(index)) < key) {
            index++;
        }
        battleNetSpatialUnits.add(index, unit);
    }

    /**
     * Each owner's {@code CPlayer::Units} pointer table, including its
     * swap-on-remove order.
     *
     * <p>This is not the world's creation-ordered roster. Upstream removes a
     * lost unit by moving the table's final pointer into its hole
     * ({@code CPlayer::RemoveUnit}), and AI assignment later walks that
     * mutated table. Which equally suitable soldier fills the next force is
     * therefore observable simulation state.
     */
    private final List<List<Unit>> playerUnitOrder;

    /**
     * Units covering each map square, indexed by {@code x + y * width}.
     *
     * <p>Implements {@code CMap::Insert}, {@code CMap::Remove}, and
     * {@code CMapField::UnitCache} in {@code src/include/tile.h}. The map flags say that something occupies a
     * square; this supplies the unit itself without walking the whole roster
     * once for every square a route search examines.
     *
     * <p>Entries are kept in unit-id order. The old lookup walked the roster
     * in creation order, so a ground unit and a flyer sharing a square keep
     * the same deterministic answer after the lookup becomes local.
     */
    final List<List<Unit>> unitCache;

    /** Projectiles in the air. */
    final List<Missile> missiles = new ArrayList<>();

    /**
     * Things that happened this cycle which are worth hearing.
     *
     * <p>The simulation does not play sounds: it says what happened and the
     * interface decides what that sounds like. Keeping it that way is what
     * lets a headless peer run the same cycles as a windowed one without an
     * audio device, and what keeps the lockstep hash free of anything to do
     * with playback.
     *
     * @param unit  who it happened to
     * @param event the name the unit type gives it, such as {@code dead}
     */
    /**
     * Something that happened and wants to be heard.
     *
     * @param named whether {@code event} is a mapped sound name from an
     *              animation's {@code sound} instruction rather than one of the
     *              unit's voice events. The two are looked up differently:
     *              a voice goes through the unit type's own Sounds table, a
     *              named sound straight to the bank.
     */
    public record SoundEvent(Unit unit, String event, boolean named) {}

    private final List<SoundEvent> soundEvents = new ArrayList<>();

    /** A written under-attack notice, kept separate from its throttled voice. */
    public record AttackNotice(Unit unit) {}

    final List<AttackNotice> attackNotices = new ArrayList<>();

    /** Takes the cycle's events, leaving the queue empty. */
    public List<SoundEvent> drainSoundEvents() {
        if (soundEvents.isEmpty()) {
            return List.of();
        }
        List<SoundEvent> drained = List.copyOf(soundEvents);
        soundEvents.clear();
        return drained;
    }

    /** Takes written attack notices without consuming any sound events. */
    public List<AttackNotice> drainAttackNotices() {
        if (attackNotices.isEmpty()) {
            return List.of();
        }
        List<AttackNotice> drained = List.copyOf(attackNotices);
        attackNotices.clear();
        return drained;
    }

    /** Notes something worth hearing. */
    void announce(Unit unit, String event) {
        if (unit != null && unit.type() != null && soundEvents.size() < 64) {
            soundEvents.add(new SoundEvent(unit, event, false));
        }
    }

    /**
     * Queues a sound an animation asked for.
     *
     * <p>{@code CAnimation_Sound::Action}. Every blow struck in Warcraft II is
     * one of these: the attack animations carry
     * {@code "sound peasant-attack"} and the like, and the chopping noise is
     * {@code "sound tree-chopping"} inside {@code Harvest_wood}. The
     * instruction was parsed from the first day and its result thrown away,
     * which is why a battle was silent.
     */
    void announceNamed(Unit unit, String sound) {
        if (unit != null && sound != null && !sound.isEmpty() && soundEvents.size() < 64) {
            soundEvents.add(new SoundEvent(unit, sound, true));
        }
    }

    /**
     * Runs one cycle of a unit's animation and keeps what it produced.
     *
     * <p>Everything that steps an animation goes through here so no site can
     * forget the sound again.
     */
    /**
     * Turns a unit on the spot, by whole facings.
     *
     * <p>{@code UnitRotate} is
     * {@code Direction += rotate * 256 / NumDirections} followed by
     * {@code UnitUpdateHeading}. Upstream keeps a facing as one of 256 and
     * maps it down to the sprite's directions; this implementation keeps the facing it
     * draws with, so a rotation of one step is one facing and the division
     * has already happened. A building has one direction and cannot turn,
     * which the modulus would otherwise turn into a divide by nothing.
     */
    private void rotate(Unit unit, int steps) {
        int facings = unit.type() == null ? 8 : Math.max(1, unit.type().numDirections());
        if (facings <= 1) {
            return;
        }
        unit.setHeading(Math.floorMod(unit.heading() + steps, facings));
    }

    AnimationRunner.Step advance(Unit unit) {
        if (RAND_TRACE_PATH != null) {
            randContext = "anim:" + unit.id();
        }
        AnimationRunner.Step step = animations.step(unit.animation(), 1, unit.frame(),
                unit.hasBuff(Unit.Buff.SLOW), unit.hasBuff(Unit.Buff.HASTE),
                unit.pendingRotation(), unit.type().rotationSpeed());
        unit.setPendingRotation(step.rotation());
        unit.setFrame(step.frame());
        announceNamed(unit, step.sound());
        for (Animation.Instruction effect : step.effects()) {
            switch (effect.kind()) {
                case SPAWN_UNIT -> spawnAnimationUnit(unit, effect.operand());
                case SET_VAR -> applyAnimationVariable(unit, effect.operand());
                case DIE -> vanishFromAnimation(unit);
                case RANDOM_ROTATE -> rotate(unit, effect.value());
                case WIGGLE -> applyWiggle(unit, effect.operand());
                default -> { }
            }
        }
        return step;
    }

    /**
     * Executes {@code wiggle x y absolute|heading}: a pixel nudge to the
     * unit's displacement.
     *
     * <p>{@code CAnimation_Wiggle}: the absolute form adds the pair to {@code IX/IY} outright,
     * and the heading form scales each axis by the facing's delta first.
     * The shipped data uses it for one thing -- ships and fliers bobbing at
     * anchor, one pixel down and along the facing every sixty-four cycles
     * and back again -- but the bob is not cosmetic: upstream's walk primes
     * its displacement with {@code +=}, so a ship that sets off mid-bob
     * carries the pixel, drains a pixel long, and can miss the decide
     * window's wrap by exactly that pixel. On campaigns/orc-exp/levelx11o
     * destroyer 78 enters its cycle-19 step carrying minus one and one from
     * its bob and stands sixteen cycles at 34 that a port without the
     * wiggle never stood.
     *
     * <p>Deviation: the nudge lands in the residual pair rather than the
     * drawn offset, so the simulation's arithmetic is upstream's exactly
     * but the boat does not visibly bob. Upstream's rule is IX/IY moving
     * whole; the bounded difference is one pixel of idle animation on
     * screen and nothing else -- the offset-reading gates never see a bob
     * either way, because upstream has no such gates.
     */
    private void applyWiggle(Unit unit, String operand) {
        String[] words = operand == null ? new String[0] : operand.trim().split("\\s+");
        if (words.length < 3) {
            return;
        }
        int x;
        int y;
        try {
            x = Integer.parseInt(words[0]);
            y = Integer.parseInt(words[1]);
        } catch (NumberFormatException notANumber) {
            return;
        }
        if ("heading".equals(words[2])) {
            // Truncated, not rounded: upstream reads
            // Heading2X[unit.Direction / NextDirection], the raw angle cut
            // into its band, where the drawn facing rounds to the nearest.
            int heading = (unit.direction() / 32) % 8;
            x *= Direction.deltaX(heading);
            y *= Direction.deltaY(heading);
        } else if (!"absolute".equals(words[2])) {
            // The if-not-reached form; nothing shipped uses it.
            return;
        }
        unit.setResidual(unit.residualX() + x, unit.residualY() + y);
    }

    /**
     * Executes {@code spawn-unit type dx dy range player [flags]}.
     *
     * <p>The death animations generated by ChonkCraft use zero offsets and
     * {@code l.this}, meaning the dying unit's owner. Keeping the parser here
     * also leaves the instruction usable by other shipped animations.
     */
    private void spawnAnimationUnit(Unit source, String operand) {
        String[] words = operand == null ? new String[0] : operand.trim().split("\\s+");
        if (words.length < 5) {
            return;
        }
        UnitType type = unitTypes.get(words[0]);
        if (type == null) {
            return;
        }
        int x = source.tileX() + animationInt(source, words[1]);
        int y = source.tileY() + animationInt(source, words[2]);
        int player = animationInt(source, words[4]);
        createUnit(type, player, x, y);
    }

    /** The integer forms used by ChonkCraft's generated death-vision spawn. */
    private static int animationInt(Unit source, String word) {
        if ("l.this".equals(word)) {
            return source.player();
        }
        try {
            return Integer.parseInt(word);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** Applies the animation variable needed by the generated revealer. */
    private void applyAnimationVariable(Unit unit, String operand) {
        String[] words = operand == null ? new String[0] : operand.trim().split("\\s+");
        if (words.length < 3 || !"SightRange.Max".equals(words[0])
                || !"=".equals(words[1])) {
            return;
        }
        int range = Math.max(0, animationInt(unit, words[2]));
        markSight(unit, false);
        unit.setSightRangeOverride(range);
        markSight(unit, true);
    }

    /** Implements the animation {@code die} instruction without death effects. */
    private void vanishFromAnimation(Unit unit) {
        if (unit.isDying()) {
            return;
        }
        markOccupancy(unit, false);
        markSight(unit, false);
        unit.setHitPoints(0);
        unit.clearPath();
        unit.setTarget(null);
        unit.setSelected(false);
        unit.setOrder(Unit.Order.DYING);
        unit.setRemoved(true);
        // Dying, not gone. The `die` instruction throws
        // AnimationDie_Exception and the catch is one line --
        // {@code AnimationDie_OnCatch(unit) { LetUnitDie(unit); }}
        // so the unit is given
        // the die order and taken off the map now. COrder_Die::Execute runs
        // at this unit's ordinary action-table position on the cycle after
        // and performs Release there -- importantly, after any earlier unit
        // has acted and perhaps appended a newborn to that same table.
        //
        // One cycle, and it is measurable: on maps/demo/demo02 the vision
        // marker a dead peasant leaves runs its 160-cycle Still animation to
        // the same instruction on the same cycle in both engines, 175, and
        // upstream's is still in the world when that cycle is read.
        unit.setDeathTimer(deathCycles(unit));
    }

    /** The projectile definitions, or null before they are loaded. */
    java.util.Map<String, MissileType> missileTypes;

    /** The local feedback missile named by {@code SetDamageMissile}, or null. */
    private String damageMissile;

    /** Whether buildings may accept another training job while busy. */
    private boolean trainingQueueEnabled;

    /** Tells the world what the scripts say a projectile does. */
    public void setMissileTypes(java.util.Map<String, MissileType> missileTypes) {
        this.missileTypes = missileTypes;
    }

    /** Enables or disables floating damage figures for this world. */
    public void setDamageMissile(String damageMissile) {
        this.damageMissile = damageMissile == null || damageMissile.isBlank()
                ? null : damageMissile;
    }

    public boolean trainingQueueEnabled() {
        return trainingQueueEnabled;
    }

    public void setTrainingQueueEnabled(boolean enabled) {
        trainingQueueEnabled = enabled;
    }

    /** A table of its own, overriding what the scripts declared. */
    private net.chonkbase.chonkcraft.engine.missile.BurningBuildingFrames burningBuildings;

    /**
     * Which fire a damaged building wears at what health.
     *
     * <p>Falls back to whatever {@code DefineBurningBuilding} declared, which
     * is a global upstream and is one here for the same reason: it is a single
     * table read from a single script and shared by every building in the
     * game. Set one explicitly to run a world on a table of its own.
     */
    public void setBurningBuildings(
            net.chonkbase.chonkcraft.engine.missile.BurningBuildingFrames frames) {
        this.burningBuildings = frames;
    }

    /** The table this world burns buildings by. */
    public net.chonkbase.chonkcraft.engine.missile.BurningBuildingFrames burningBuildings() {
        return burningBuildings != null
                ? burningBuildings
                : net.chonkbase.chonkcraft.engine.missile.BurningBuildingFrames.declared();
    }

    /**
     * What is currently in the air, for the renderer.
     *
     * <p>A snapshot published once a tick, for the reason
     * {@link #unitsSnapshot()} is: the renderer runs on the event thread and
     * copying the live list from there races the simulation adding a shot to
     * it. Missiles are the shortest-lived things in the game -- an arrow is in
     * the air for a handful of cycles -- so the list this hands back is
     * usually empty and the copy costs nothing.
     */
    public List<Missile> missiles() {
        return missileSnapshot;
    }

    /** Published for the renderer; see {@link #missiles()}. */
    volatile List<Missile> missileSnapshot = List.of();

    /**
     * Units created during a tick, added once the tick's iteration is over.
     *
     * <p>A building finishing or a unit being trained both create units from
     * inside the loop over units, which cannot append to the list it is
     * walking. Queueing keeps the iteration valid and gives every new unit the
     * same birthday, which matters for determinism.
     */
    final List<Unit> pending = new ArrayList<>();
    boolean ticking;

    /** Published for the renderer; see {@link #unitsSnapshot()}. */
    private volatile List<Unit> snapshot = List.of();
    private int nextUnitId = 1;
    long cycle;

    final Player[] players;
    private final FogOfWar fog;

    /**
     * Command authority granted when a network player leaves.
     *
     * <p>Indexed by the departed owner; bit N grants player N authority over
     * that owner's units. Ownership itself never moves, so colours, supply,
     * scores and resource banks remain exactly where the synchronized world
     * put them.
     */
    private final int[] departedControlMasks = new int[Player.MAX];

    /** What each player has researched. Indexed by player slot. */
    private UpgradeState[] upgradeStates;
    private UpgradeSet upgradeSet = new UpgradeSet();
    private SpellSet spellSet = new SpellSet();
    final Map<Integer, AiPlayer> ais = new java.util.LinkedHashMap<>();

    public World(GameMap map) {
        this(map, defaultPlayers(), DEFAULT_RANDOM_SEED);
    }

    public World(GameMap map, Player[] players) {
        this(map, players, DEFAULT_RANDOM_SEED);
    }

    /**
     * Builds a world with an explicitly pinned construction and live seed.
     *
     * <p>The original game uses the same supplied seed for the disposable
     * map-construction stream and for the synchronized stream installed just
     * before play. They remain separate fields here because map creation is
     * allowed to consume the former without moving cycle one's live state.
     * The ordinary launch path delegates with {@link #DEFAULT_RANDOM_SEED};
     * the BNE fixture runner supplies the seed frozen in the fixture.
     */
    public World(GameMap map, Player[] players, int initializationSeed) {
        this.map = map;
        this.pathFinder = new PathFinder(map);
        this.unitCache = new ArrayList<>(
                Collections.nCopies(Math.multiplyExact(map.width(), map.height()), null));
        this.battleNetNoBuild = new boolean[
                Math.multiplyExact(map.width(), map.height())];
        this.players = players;
        this.playerUnitOrder = new ArrayList<>(players.length);
        for (int i = 0; i < players.length; i++) {
            this.playerUnitOrder.add(new ArrayList<>());
        }
        this.fog = new FogOfWar(map.width(), map.height(), players.length);
        this.randomSeed = initializationSeed;
        this.loadRandomSeed = initializationSeed;
        this.battleNetRandomSeed = initializationSeed;
        establishDiplomacy();
    }

    /** The owner's roster in {@code CPlayer::GetUnits()} order. */
    public List<Unit> playerUnits(int player) {
        if (player < 0 || player >= playerUnitOrder.size()) {
            return List.of();
        }
        return Collections.unmodifiableList(playerUnitOrder.get(player));
    }

    /** {@code CPlayer::AddUnit}, excluding scenery types AssignToPlayer skips. */
    private void registerPlayerUnit(Unit unit) {
        if (unit.type() == null || unit.type().vanishes() || unit.isDying()
                || unit.player() < 0 || unit.player() >= playerUnitOrder.size()) {
            return;
        }
        List<Unit> roster = playerUnitOrder.get(unit.player());
        if (!roster.contains(unit)) {
            roster.add(unit);
        }
    }

    /** {@code CPlayer::RemoveUnit}: fill the hole with the final pointer. */
    private void unregisterPlayerUnit(Unit unit) {
        if (unit.player() < 0 || unit.player() >= playerUnitOrder.size()) {
            return;
        }
        List<Unit> roster = playerUnitOrder.get(unit.player());
        int index = roster.indexOf(unit);
        if (index < 0) {
            return;
        }
        int last = roster.size() - 1;
        if (index != last) {
            roster.set(index, roster.get(last));
        }
        roster.remove(last);
    }

    /**
     * {@code CUnitManager::ReleaseUnit}: fill the active-table hole with its
     * final pointer, then pop the final entry.
     *
     * <p>{@link #pending} is the suffix of that same upstream table which was
     * born after this cycle's {@link #snapshot} was taken. If a release occurs
     * after such a birth, upstream swaps the final newborn into the hole even
     * though it cannot act until the next cycle; treating the two Java lists
     * as one logical table preserves that case too.
     */
    private void releaseUnitFromActionTable(Unit unit) {
        // Native FUN_00453bc0 shifts the spatial tail left; do not swap-fill.
        battleNetSpatialUnits.remove(unit);
        int index = units.indexOf(unit);
        if (index >= 0) {
            int last = units.size() - 1;
            if (!pending.isEmpty()) {
                Unit replacement = pending.remove(pending.size() - 1);
                units.set(index, replacement);
            } else {
                if (index != last) {
                    units.set(index, units.get(last));
                }
                units.remove(last);
            }
            return;
        }

        index = pending.indexOf(unit);
        if (index >= 0) {
            int last = pending.size() - 1;
            if (index != last) {
                pending.set(index, pending.get(last));
            }
            pending.remove(last);
        }
    }

    /**
     * Works out who is at war with whom, from what each slot is.
     *
     * <p>Nothing did this. The alliance table was left all-false, and since
     * "not allied" means "enemy", every side in the game was hostile to every
     * other -- including a campaign ally and, worse, including the rescuable
     * prisoners a mission is about. On "A Time for Heroes" the three heroes
     * the player is sent to free were being killed by the orcs across the map
     * within four seconds, and the mission's own defeat condition then fired
     * correctly.
     *
     * <p>The rules are {@code CPlayer::Init},
     * implemented as follows:
     *
     * <ul>
     *   <li>a computer is allied with other computers, and the enemy of every
     *       person and of rescue-active;
     *   <li>a person is the enemy of computers and of other people, and allied
     *       with both kinds of rescuable player;
     *   <li>rescue-passive is allied with people and hostile to nobody, which
     *       is what keeps a prisoner alive until somebody frees them;
     *   <li>rescue-active is allied with people and the enemy of computers.
     * </ul>
     *
     * <p>Neutral and empty slots take no part. Nobody is at war with the owner
     * of the gold mines.
     *
     * <p>Two tables, not one, because the rules above name three standings.
     * A computer and a rescue-passive slot are neither allied nor enemies:
     * the guards do not free the prisoner and they do not kill it either.
     * While "enemy" was derived as "not allied", that pair had no way to be
     * said, the guards on the first orc expansion mission killed the caged
     * hero in 54 simulated seconds, and the mission lost itself before a
     * player could act. {@code CPlayer::Init} writes {@code Enemy} and
     * {@code Allied} as separate masks for exactly this reason.
     */
    /**
     * One player's stance toward another, set one way only.
     *
     * <p>Implements {@code CommandDiplomacy} and the four
     * {@code CPlayer::SetDiplomacy*With} setters
     *
     * enemy sets the enemy bit and clears the ally bit, allied the reverse,
     * neutral clears both and crazy sets both -- and only for {@code player},
     * never the opponent, which is why campaigns/human/level08h's opening
     * trigger says every direction it means. Its rescue-active slot four is
     * born hating only the computers; the trigger is what turns it on the
     * player and the rescuable humans it besieges.
     */
    public void setDiplomacy(int player, String stance, int opponent) {
        if (player < 0 || player >= players.length
                || opponent < 0 || opponent >= players.length || stance == null) {
            return;
        }
        switch (stance) {
            case "neutral" -> {
                enemy[player][opponent] = false;
                allied[player][opponent] = false;
            }
            case "allied" -> {
                enemy[player][opponent] = false;
                allied[player][opponent] = true;
            }
            case "enemy" -> {
                enemy[player][opponent] = true;
                allied[player][opponent] = false;
            }
            case "crazy" -> {
                enemy[player][opponent] = true;
                allied[player][opponent] = true;
            }
            default -> { }
        }
    }

    public void establishDiplomacy() {
        for (int a = 0; a < players.length; a++) {
            for (int b = 0; b < players.length; b++) {
                if (a == b || players[a] == null || players[b] == null) {
                    continue;
                }
                allied[a][b] = alliedByType(players[a].type(), players[b].type());
                enemy[a][b] = enemyByType(players[a].type(), players[b].type());
            }
        }
    }

    /**
     * Marks where an order was aimed, so the player can see that it landed.
     *
     * <p>{@code SetClickMissile("missile-green-cross")}: upstream throws a
     * short-lived missile down at the spot a right click was aimed at. It
     * matters most when the units being ordered are off screen, which is
     * every order given on the minimap -- without it, a click on a small
     * minimap is indistinguishable from a click that missed.
     *
     * <p>Local rather than networked, like the pointer itself: it is feedback
     * for the player who clicked, not an event in the game.
     *
     * <p>Queued rather than dropped straight into the missile list. This is
     * called from the mouse handler on the event thread and the missile list
     * belongs to the simulation thread, which walks it and rewrites it every
     * cycle; a click landing in the middle of that could take the simulation
     * itself down with an {@code ArrayIndexOutOfBoundsException} out of
     * {@code ArrayList.removeAll}, which the player sees as the game freezing
     * on the frame they clicked. Handing the marker over instead of placing it
     * costs at most one cycle -- a thirtieth of a second, against the marker's
     * own visible life of about eight -- and the cross still animates and
     * expires under the simulation's own bookkeeping, so nothing about how it
     * looks changes.
     */
    public void markOrder(int tileX, int tileY) {
        if (missileTypes == null || !map.contains(tileX, tileY)) {
            return;
        }
        // Bounded for the same reason the ping list is: the queue is drained
        // thirty times a second, so a hand can never fill it, and anything that
        // can is not a hand and must not be able to exhaust the heap.
        if (clickMarkers.size() >= 64) {
            return;
        }
        clickMarkers.add(new int[] {tileX, tileY});
    }

    /**
     * Places the markers the interface asked for, on the simulation's thread.
     *
     * <p>Drained at the top of {@link #tick}, before anything reads the missile
     * list, so a marker is either wholly there for a cycle or not there at all.
     */
    private void placeClickMarkers() {
        MissileType cross = missileTypes == null ? null : missileTypes.get(CLICK_MISSILE);
        int[] where;
        while ((where = clickMarkers.poll()) != null) {
            if (cross == null) {
                continue;
            }
            int x = where[0] * 32 + 16;
            int y = where[1] * 32 + 16;
            missiles.add(new Missile(cross, null, null, x, y, x, y));
        }
    }

    /**
     * Order markers the interface has asked for and the simulation has not yet
     * placed. Concurrent because the two ends are on different threads.
     */
    private final java.util.concurrent.ConcurrentLinkedQueue<int[]> clickMarkers =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /** What the prelude names as the mark for a click. */
    private static final String CLICK_MISSILE = "missile-green-cross";

    /**
     * Frees prisoners standing next to somebody who can fight for them.
     *
     * <p>{@code RescueUnits}, run once a second. A
     * rescuable player's unit changes hands the moment an armed ally of a real
     * player stands beside it -- and if what is standing there is a town hall,
     * the whole slot changes hands at once, which is upstream's own trick for
     * "walk into the village and the village is yours".
     *
     * <p>Nine campaign missions are won by getting rescued units to a circle
     * of power. Without this nobody could ever be rescued, so those nine had
     * no way to be finished even once their victory condition could be asked.
     */
    private void rescueUnits() {
        for (Player player : players) {
            if (player == null || !isRescuable(player.type())) {
                continue;
            }
            for (Unit prisoner : List.copyOf(units)) {
                if (prisoner.player() != player.index() || !prisoner.isAlive()
                        || !prisoner.isOnMap()) {
                    continue;
                }
                Unit saviour = armedAllyBeside(prisoner);
                if (saviour == null) {
                    continue;
                }
                if (prisoner.type() != null
                        && prisoner.type().storesResource(UnitType.Resource.GOLD)) {
                    // A town hall carries its whole side with it.
                    changePlayerOwner(player.index(), saviour.player());
                    break;
                }
                rescue(prisoner, saviour.player());
            }
        }
    }

    /** Somebody able to fight, allied with a prisoner, standing next to it. */
    private Unit armedAllyBeside(Unit prisoner) {
        for (Unit other : units) {
            if (other == prisoner || !other.isAlive() || !other.isOnMap()
                    || other.type() == null || !other.type().canAttack()) {
                continue;
            }
            if (isRescuable(players[other.player()].type())) {
                // One prisoner cannot free another.
                continue;
            }
            if (!isAllied(prisoner.player(), other.player())) {
                continue;
            }
            if (prisoner.distanceTo(other) <= 1) {
                return other;
            }
        }
        return null;
    }

    /**
     * BNE's per-unit rescue check from {@code HandleEachCycle}.
     *
     * <p>The retail engine does not use LegacyEngine's once-a-second
     * {@code RescueUnits} sweep.  When a rescuable unit reaches an animation
     * action marker, the scheduler scans the rectangle one square around its
     * footprint, in map row order, and hands it to the first person-controlled
     * unit found there.  The check happens in low-to-high pool order, so a
     * unit rescued earlier in the same cycle can rescue another one later.
     * This is why the attack peasants in Human 10 cross the checkerboard over
     * several ticks instead of changing owner as one group.</p>
     */
    void rescueBattleNetUnit(Unit prisoner) {
        Player owner = player(prisoner.player());
        if (owner == null || !isRescuable(owner.type()) || !prisoner.isOnMap()) {
            return;
        }
        // Flyers are rescuable: XOrc 12's fire-breeze at (120,8) changes from
        // rescue-passive player 0 to person player 5 on fixture cycle 5 when
        // an adjacent person-owned axethrower is present. Skipping fly
        // prisoners left it stranded on p0 and omitted the SyncRand debit
        // that lands with the rest of that cycle's rescue fallout.
        int border = prisoner.type().tileWidth() > 1
                || prisoner.type().tileHeight() > 1 ? 2 : 1;
        int left = Math.max(0, prisoner.tileX() - border);
        int top = Math.max(0, prisoner.tileY() - border);
        int right = Math.min(map.width(), prisoner.tileX()
                + Math.max(1, prisoner.type().tileWidth()) + border);
        int bottom = Math.min(map.height(), prisoner.tileY()
                + Math.max(1, prisoner.type().tileHeight()) + border);
        for (int y = top; y < bottom; y++) {
            for (int x = left; x < right; x++) {
                List<Unit> cached = unitCache.get(x + y * map.width());
                if (cached == null) {
                    continue;
                }
                for (Unit other : cached) {
                    // Rescuers must be ground/sea: a flyer does not free a
                    // prisoner by flying past. Prisoners themselves may fly.
                    if (other == prisoner || other.destroyed() || !other.isOnMap()
                            || other.type() == null
                            || other.type().moveType() == UnitType.Movement.FLY) {
                        continue;
                    }
                    Player rescuer = player(other.player());
                    if (rescuer != null
                            && rescuer.type()
                                    == net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.PERSON) {
                        rescue(prisoner, other.player());
                        return;
                    }
                }
            }
        }
    }

    private static boolean isRescuable(net.chonkbase.chonkcraft.data.map.PudMap.PlayerType type) {
        return type == net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_PASSIVE
                || type == net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE;
    }

    /** Hands one unit over, remembering who it was freed from. */
    private void rescue(Unit unit, int toPlayer) {
        markSight(unit, false);
        unregisterPlayerUnit(unit);
        unit.setRescuedFrom(unit.player());
        unit.setPlayer(toPlayer);
        registerPlayerUnit(unit);
        unitCountSeen(unit);
        markSight(unit, true);
        recalculateSupply();
        announce(unit, "rescue");
    }

    /** Hands a whole slot over, as walking into a rescuable town hall does. */
    private void changePlayerOwner(int from, int to) {
        for (Unit unit : List.copyOf(units)) {
            if (unit.player() == from && unit.isAlive()) {
                rescue(unit, to);
            }
        }
    }

    /** Whether a slot of one kind starts out allied with a slot of another. */
    private static boolean alliedByType(net.chonkbase.chonkcraft.data.map.PudMap.PlayerType mine,
            net.chonkbase.chonkcraft.data.map.PudMap.PlayerType theirs) {
        return switch (mine) {
            case COMPUTER -> theirs == net.chonkbase.chonkcraft.data.map.PudMap
                    .PlayerType.COMPUTER;
            case PERSON -> theirs == net.chonkbase.chonkcraft.data.map.PudMap
                    .PlayerType.RESCUE_PASSIVE
                    || theirs == net.chonkbase.chonkcraft.data.map.PudMap
                            .PlayerType.RESCUE_ACTIVE;
            case RESCUE_PASSIVE -> theirs == net.chonkbase.chonkcraft.data.map.PudMap
                    .PlayerType.PERSON;
            case RESCUE_ACTIVE -> theirs == net.chonkbase.chonkcraft.data.map.PudMap
                    .PlayerType.PERSON;
            // Neutral and unused slots are at war with nobody, but they are
            // not allies either: CPlayer::Init leaves them out of both masks.
            // The distinction is observable in AiMoveUnitInTheWay. A stuck
            // computer worker may shove an ally, but not a neutral critter
            // beside it; treating neutral as allied spent two shared random
            // draws on levelx04h cycle 1455.
            default -> false;
        };
    }

    /**
     * Whether a slot of one kind starts out at war with a slot of another.
     *
     * <p>The enemy half of {@code CPlayer::Init}, as written there: a computer
     * is the enemy of persons and of rescue-active; a person is the enemy of
     * computers and of other persons; rescue-active is the enemy of
     * computers. Nothing else is anybody's enemy -- in particular
     * rescue-passive, whose whole part in a mission is to stand in a cage
     * beside hostile guards until somebody arrives.
     */
    private static boolean enemyByType(net.chonkbase.chonkcraft.data.map.PudMap.PlayerType mine,
            net.chonkbase.chonkcraft.data.map.PudMap.PlayerType theirs) {
        return switch (mine) {
            case COMPUTER -> theirs == net.chonkbase.chonkcraft.data.map.PudMap
                    .PlayerType.PERSON
                    || theirs == net.chonkbase.chonkcraft.data.map.PudMap
                            .PlayerType.RESCUE_ACTIVE;
            case PERSON -> theirs == net.chonkbase.chonkcraft.data.map.PudMap
                    .PlayerType.COMPUTER
                    || theirs == net.chonkbase.chonkcraft.data.map.PudMap
                            .PlayerType.PERSON;
            case RESCUE_ACTIVE -> theirs == net.chonkbase.chonkcraft.data.map.PudMap
                    .PlayerType.COMPUTER;
            default -> false;
        };
    }

    /** What each player has seen. */
    /**
     * What each player remembers on ground they can no longer watch.
     *
     * @see SeenBuildings
     */
    public SeenBuildings seenBuildings() {
        return seenBuildings;
    }

    private final SeenBuildings seenBuildings = new SeenBuildings();

    /**
     * Refreshes every player's memory of the buildings they have scouted.
     *
     * <p>Upstream does this on the transition, in {@code UnitGoesUnderFog},
     * and keeps destroyed units alive so a razed building can still be drawn.
     * Recomputing it is equivalent and needs no resurrection: a memory is only
     * ever read for ground the player cannot see, so forgetting everything
     * they can see and then recording everything they cannot leaves exactly
     * the set upstream's bookkeeping arrives at. A building torn down while
     * the player was away survives in their memory until they look at the spot
     * -- which is the behaviour, not a leak.
     *
     * <p>Only types carrying {@code VisibleUnderFog} are remembered, which in
     * the shipped data is the buildings and nothing else.
     */
    private void updateSeenBuildings() {
        for (int player = 0; player < players.length; player++) {
            if (players[player] == null || !players[player].isActive()) {
                continue;
            }
            seenBuildings.forgetVisible(player, fog);
        }
        for (Unit unit : units) {
            if (!unit.isAlive() || unit.isDying() || unit.type() == null
                    || !unit.type().visibleUnderFog() || !unit.isOnMap()) {
                continue;
            }
            for (int player = 0; player < players.length; player++) {
                if (players[player] == null || !players[player].isActive()
                        || player == unit.player()) {
                    continue;
                }
                if (!isSeenBy(unit, player)) {
                    continue;
                }
                var frame = unit.spriteFrame();
                seenBuildings.remember(player, new SeenBuildings.Memory(
                        unit.type(), unit.player(), unit.tileX(), unit.tileY(),
                        frame.index(), frame.mirrored(),
                        unit.order() == Unit.Order.UNDER_CONSTRUCTION,
                        unit.progressFraction()));
            }
        }
    }

    /**
     * Whether a player has explored a building's ground but cannot watch it
     * now, which is the only state a memory is any use in.
     */
    private boolean isSeenBy(Unit unit, int player) {
        int width = Math.max(1, unit.type().tileWidth());
        int height = Math.max(1, unit.type().tileHeight());
        boolean explored = false;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (fog.isVisible(player, unit.tileX() + x, unit.tileY() + y)) {
                    // Watched right now: it is drawn live, and a memory of it
                    // would only be a stale copy under the real thing.
                    return false;
                }
                explored |= fog.isExplored(player, unit.tileX() + x, unit.tileY() + y);
            }
        }
        return explored;
    }

    public FogOfWar fog() {
        return fog;
    }

    /** Tells the world which spells exist, so they can be cast. */
    public void setSpells(SpellSet spells) {
        this.spellSet = spells;
    }

    /**
     * Casts a spell.
     *
     * <p>Checked in the order the game checks: the caster must know it, have
     * the mana, be in range, and the target must be legal. Failing any of
     * those refuses the cast rather than half-applying it.
     *
     * @param target the unit aimed at, or {@code null} for a self-cast
     * @return whether the spell went off
     */
    /**
     * Sends a caster to cast a spell, walking into range first if it must.
     *
     * <p>{@code castSpell} is the moment of casting and refuses anybody out of
     * range, which is right. What was missing was everything before that
     * moment: nothing in the game ever called it, and a mage cannot usefully
     * be told to cast only at what it is already standing beside.
     */
    public boolean orderCast(Unit caster, String spellIdent, Unit target) {
        Spell spell = spellSet == null ? null : spellSet.get(spellIdent);
        if (spell == null || caster == null || !caster.isAlive() || !caster.isCaster()) {
            return false;
        }
        if (!spell.dependUpgrade().isEmpty()
                && !upgrades(caster.player()).has(spell.dependUpgrade())) {
            return false;
        }
        if (spell.target() == Spell.Target.SELF) {
            return castSpell(caster, spellIdent, caster);
        }
        if (target == null || !target.isAlive()) {
            return false;
        }
        caster.clearPath();
        caster.setTarget(target);
        caster.setCastingSpell(spellIdent);
        caster.setOrder(Unit.Order.SPELL_CAST);
        return true;
    }

    /** Sends a caster to cast a position spell at the selected map square. */
    public boolean orderCast(Unit caster, String spellIdent, int tileX, int tileY) {
        Spell spell = spellSet == null ? null : spellSet.get(spellIdent);
        if (spell == null || spell.target() != Spell.Target.POSITION
                || caster == null || !caster.isAlive() || !caster.isCaster()
                || !map.contains(tileX, tileY)) {
            return false;
        }
        if (!spell.dependUpgrade().isEmpty()
                && !upgrades(caster.player()).has(spell.dependUpgrade())) {
            return false;
        }
        // A zero range is global (Holy Vision), not melee range.
        if (spell.range() == 0 || caster.distanceTo(tileX, tileY) <= spell.range()) {
            return castSpell(caster, spellIdent, null, tileX, tileY);
        }
        caster.clearPath();
        caster.setTarget(null);
        caster.setOrderTarget(tileX, tileY);
        caster.setCastingSpell(spellIdent);
        caster.setOrder(Unit.Order.SPELL_CAST);
        return true;
    }

    /** Walks a caster into range and casts when it arrives. */
    private void stepSpellCast(Unit unit) {
        String ident = unit.castingSpell();
        Spell spell = ident == null || spellSet == null ? null : spellSet.get(ident);
        Unit target = unit.target();
        if (spell == null || !unit.isAlive()) {
            unit.setCastingSpell(null);
            unit.setTarget(null);
            unit.setOrder(Unit.Order.STILL);
            return;
        }
        if (spell.target() == Spell.Target.POSITION) {
            int tileX = unit.orderTargetX();
            int tileY = unit.orderTargetY();
            if (!map.contains(tileX, tileY)) {
                unit.setCastingSpell(null);
                unit.setOrder(Unit.Order.STILL);
                return;
            }
            if (spell.range() > 0 && unit.distanceTo(tileX, tileY) > spell.range()) {
                movement.walkTowards(unit, tileX, tileY);
                return;
            }
            castSpell(unit, ident, null, tileX, tileY);
            unit.setCastingSpell(null);
            unit.setOrder(Unit.Order.STILL);
            return;
        }
        if (target == null || !target.isAlive()) {
            unit.setCastingSpell(null);
            unit.setTarget(null);
            unit.setOrder(Unit.Order.STILL);
            return;
        }
        int range = Math.max(1, spell.range());
        if (unit.distanceTo(target) > range) {
            movement.walkTowards(unit, target.tileX(), target.tileY());
            return;
        }
        castSpell(unit, ident, target);
        unit.setCastingSpell(null);
        unit.setTarget(null);
        unit.setOrder(Unit.Order.STILL);
    }

    public boolean castSpell(Unit caster, String spellIdent, Unit target) {
        int tileX = target == null ? caster.tileX() : target.tileX();
        int tileY = target == null ? caster.tileY() : target.tileY();
        return castSpell(caster, spellIdent, target, tileX, tileY);
    }

    /** Casts a position spell immediately at a map square. */
    public boolean castSpell(Unit caster, String spellIdent, int tileX, int tileY) {
        return castSpell(caster, spellIdent, null, tileX, tileY);
    }

    private boolean castSpell(Unit caster, String spellIdent, Unit target,
            int tileX, int tileY) {
        Spell spell = spellSet.get(spellIdent);
        if (spell == null || !caster.isAlive() || !caster.isCaster()) {
            return false;
        }
        if (caster.mana() < spell.manaCost()) {
            return false;
        }
        if (!spell.dependUpgrade().isEmpty() && !upgrades(caster.player()).has(spell.dependUpgrade())) {
            return false;
        }

        Unit victim = spell.target() == Spell.Target.SELF ? caster : target;
        if (spell.target() == Spell.Target.UNIT) {
            if (victim == null || !victim.isAlive()) {
                return false;
            }
            if (caster.distanceTo(victim) > Math.max(1, spell.range())) {
                return false;
            }
            if (!spell.allowBuildings() && victim.type().building()) {
                return false;
            }
            if (spell.organicOnly() && victim.type().building()) {
                return false;
            }
        }
        if (spell.target() == Spell.Target.POSITION) {
            if (!map.contains(tileX, tileY)) {
                return false;
            }
            // A live order checks range before it reaches this resolver. The
            // unit-target overload is also retained as a deterministic
            // simulation seam for position effects aimed at an occupied tile;
            // those callers have already selected the impact point.
            if (target == null && spell.range() > 0
                    && caster.distanceTo(tileX, tileY) > spell.range()) {
                return false;
            }
        }

        // Casting anything drops the caster's own invisibility, before the
        // spell resolves. SpellCast's first line:
        // "caster.Variable[INVISIBLE_INDEX].Value = 0; // unit is invisible
        // until attacks". Ordered before the effects so that a mage casting
        // Invisibility on itself comes out invisible rather than being
        // uncloaked by its own spell.
        caster.setBuff(Unit.Buff.INVISIBLE, 0);

        boolean adjustsVitals = spell.effects().stream()
                .anyMatch(effect -> effect.kind() == Spell.EffectKind.ADJUST_VITALS);
        // The sound the spell declares. Parsed from DefineSpell and never
        // played, because nothing ever cast anything.
        announceNamed(caster, spell.soundWhenCast());
        for (Spell.Effect effect : spell.effects()) {
            applyEffect(caster, victim, tileX, tileY, spell, effect);
        }
        // Adjust-vitals determines how many repetitions fit in the caster's
        // mana and pays for all of them itself.
        if (!adjustsVitals) {
            caster.setMana(caster.mana() - spell.manaCost());
        }
        return true;
    }

    /** Applies one of a spell's effects. */
    private void applyEffect(Unit caster, Unit victim, int tileX, int tileY,
            Spell spell, Spell.Effect effect) {
        switch (effect.kind()) {
            case ADJUST_VITALS -> adjustVitals(caster, victim, spell, effect);
            case AREA_ADJUST_VITALS -> {
                if (victim == null) {
                    return;
                }
                // Everything within the declared range. Warcraft II ships no
                // spell that uses this, so the range keyword falls back to the
                // single square the implementation always assumed.
                int radius = effect.number("range", 1);
                for (Unit nearby : new java.util.ArrayList<>(units)) {
                    if (nearby.isAlive() && nearby.distanceTo(victim) <= radius) {
                        nearby.setHitPoints(nearby.hitPoints() + effect.amount());
                        if (nearby.hitPoints() <= 0) {
                            kill(nearby, caster);
                        }
                    }
                }
            }
            case DEMOLISH -> demolish(caster, victim, effect);
            case SPAWN_MISSILE -> spawnMissile(caster, victim, tileX, tileY, effect);
            case AREA_BOMBARDMENT -> bombard(caster, victim, tileX, tileY, effect);
            case SUMMON -> {
                UnitType type = summonType(effect.what());
                if (type != null) {
                    int[] spot = dropOutNearest(type, tileX, tileY, null, tileX, tileY);
                    if (spot != null) {
                        createUnit(type, caster.player(), spot[0], spot[1]);
                    }
                }
            }
            case ADJUST_VARIABLE -> adjustVariable(victim, effect);
            case EYE_OF_KILROGG -> summonEye(caster, tileX, tileY);
            case POLYMORPH -> polymorph(victim, effect);
            case UNHOLY_ARMOR -> unholyArmor(victim);
            // No generated spell currently carries either kind. Keeping the
            // refusal explicit prevents a future declaration silently
            // masquerading as implemented behavior.
            case REVEAL, OTHER -> throw new IllegalStateException(
                    "unimplemented spell effect " + effect.kind() + " in " + spell.ident());
        }
    }

    /**
     * The five timed spells, and the whole of what they do.
     *
     * <p>{@code Spell_AdjustVariable::Cast}
     * writes each named variable onto the target. The names are the ones
     * scripts/spells.legacy-declaration uses and they are the {@link Unit.Buff} constants:
     * Haste at :238, Slow at :251, Bloodlust at :268 and :285, Invisibility at
     * :301 and Unholy Armour through its retired scripting language callback at :332.
     *
     * <p>Every value in the shipped data is a cycle count -- {@code Haste =
     * 1000}, {@code Invisible = 2000} -- and every one of the five was thrown
     * away here: this arm of the switch was an empty block whose comment said
     * the system was "not yet ported". So a mage could Haste a knight, spend
     * fifty mana, hear the spell go off and watch the knight walk away at
     * exactly the speed it walked before.
     *
     * <p>A name the implementation does not model is ignored rather than refused. That
     * is upstream's behaviour and not a shortcut: {@code adjust-variable} is a
     * general mechanism over every unit variable, and ChonkCraft uses it for these
     * five alone.
     */
    private void adjustVariable(Unit victim, Spell.Effect effect) {
        if (victim == null) {
            return;
        }
        for (java.util.Map.Entry<String, Object> entry : effect.args().entrySet()) {
            if (!(entry.getValue() instanceof Number cycles)) {
                continue;
            }
            Unit.Buff buff = switch (entry.getKey()) {
                case "Bloodlust" -> Unit.Buff.BLOODLUST;
                case "Haste" -> Unit.Buff.HASTE;
                case "Slow" -> Unit.Buff.SLOW;
                case "Invisible" -> Unit.Buff.INVISIBLE;
                case "UnholyArmor" -> Unit.Buff.UNHOLY_ARMOR;
                case "Poison" -> Unit.Buff.POISON;
                default -> null;
            };
            if (buff != null) {
                victim.setBuff(buff, cycles.intValue());
            }
        }
    }

    /**
     * Repeats healing or harming until the target's vital is satisfied or the
     * caster runs out of mana.
     *
     * <p>{@code Spell_AdjustVital::Cast} treats one click as a batch. Healing
     * is one hit point per six mana in the shipped data, not one hit point
     * total; a caster with sixty mana can therefore buy ten repetitions.
     */
    private void adjustVitals(Unit caster, Unit victim, Spell spell, Spell.Effect effect) {
        if (victim == null) {
            return;
        }
        int amount = effect.amount();
        int difference;
        if ("hit-points".equals(effect.what())) {
            difference = amount > 0
                    ? victim.type().hitPoints() - victim.hitPoints()
                    : victim.hitPoints();
        } else if ("mana".equals(effect.what()) || "mana-points".equals(effect.what())) {
            difference = amount > 0
                    ? victim.type().mana() - victim.mana()
                    : victim.mana();
        } else {
            return;
        }

        int casts = 1;
        if (amount != 0) {
            int magnitude = Math.abs(amount);
            int needed = difference / magnitude;
            // Harm must include the final partial cast: reaching exactly zero
            // kills too. Healing clamps at the maximum after the batch.
            if (amount < 0 && difference % magnitude > 0) {
                needed++;
            }
            casts = Math.max(casts, needed);
        }
        if (spell.manaCost() > 0) {
            casts = Math.min(casts, caster.mana() / spell.manaCost());
        }
        int maximum = effect.number("max-multi-cast", 0);
        if (maximum > 0) {
            casts = Math.min(casts, maximum);
        }

        caster.setMana(caster.mana() - casts * spell.manaCost());
        int change = casts * amount;
        if ("hit-points".equals(effect.what())) {
            if (change < 0 && caster != victim) {
                hitDirectly(caster, victim, -change);
            } else {
                victim.setHitPoints(victim.hitPoints() + change);
                if (victim.hitPoints() <= 0) {
                    kill(victim, caster);
                }
            }
        } else {
            victim.setMana(victim.mana() + change);
        }
    }

    /**
     * Throws a spell's missile at something.
     *
     * <p>{@code Spell_SpawnMissile::Cast}.
     * This is the whole of Fireball and the whole of Death Coil -- the spells
     * declare one action apiece and it is this one -- and the implementation treated it
     * as an explicit no-op, so a mage spent a hundred mana, played its sound,
     * and nothing came out.
     *
     * <p>The damage the action declares is written onto the shot rather than
     * left to the caster's stats, which is what makes a fireball worth twenty
     * whoever throws it. Upstream only sets the source unit when the damage is
     * non-zero, and that matters: a missile with a source and no damage would
     * fall through to the firer's stats and have a mage doing melee damage at
     * range with the visual effect of a healing sparkle.
     */
    private void spawnMissile(Unit caster, Unit victim, int tileX, int tileY,
            Spell.Effect effect) {
        MissileType type = missileTypes == null ? null : missileTypes.get(effect.what());
        if (type == null || caster == null) {
            return;
        }
        Unit goal = victim;
        int damage = effect.number("damage", 0);
        double[] start = spellPoint(effect.args().get("start-point"), caster, goal,
                tileX, tileY, true);
        double[] end = spellPoint(effect.args().get("end-point"), caster, goal,
                tileX, tileY, false);
        Missile shot = spawn(new Missile(type, damage != 0 ? caster : null, goal,
                start[0], start[1], end[0], end[1]));
        shot.setDamage(damage);
        shot.setTimeToLive(effect.number("ttl", 0));
        missileSnapshot = List.copyOf(missiles);
    }

    /** Resolves a generated spell point such as base/target/add-x/add-y. */
    private static double[] spellPoint(Object declaration, Unit caster, Unit target,
            int tileX, int tileY, boolean defaultCaster) {
        boolean useTarget = !defaultCaster;
        int addX = 0;
        int addY = 0;
        if (declaration instanceof List<?> words) {
            useTarget = words.contains("target") && !words.contains("caster");
            for (int index = 0; index + 1 < words.size(); index++) {
                Object value = words.get(index + 1);
                if ("add-x".equals(words.get(index)) && value instanceof Number number) {
                    addX = number.intValue();
                } else if ("add-y".equals(words.get(index)) && value instanceof Number number) {
                    addY = number.intValue();
                }
            }
        }
        if (!useTarget) {
            return new double[] {
                    caster.pixelX() + centreOffset(caster.type(), true) + addX,
                    caster.pixelY() + centreOffset(caster.type(), false) + addY
            };
        }
        if (target != null) {
            return new double[] {
                    target.tileX() * TILE_SIZE + centreOffset(target.type(), true) + addX,
                    target.tileY() * TILE_SIZE + centreOffset(target.type(), false) + addY
            };
        }
        return new double[] {
                tileX * TILE_SIZE + TILE_SIZE / 2.0 + addX,
                tileY * TILE_SIZE + TILE_SIZE / 2.0 + addY
        };
    }

    /**
     * Blows a sapper up.
     *
     * <p>{@code Spell_Demolish::Cast}. Two
     * halves, and the implementation had neither. Walls, rocks and trees inside the
     * radius are cleared, which is what a demolition squad is <em>for</em> --
     * it is the only way an orc army opens a walled base without siege. Then
     * everything on the ground within the range takes the declared damage,
     * including the caster, which is how the sapper dies in its own blast.
     *
     * <p>Flying units are exempt: upstream skips {@code EMovement::Fly}
     * outright, so a dragon overhead is untouched.
     *
     * <p>What the implementation did instead is worth recording, because it is the
     * clearest single symptom of reading an action's arguments by position:
     * {@code {"demolish", "range", 3, "damage", 400}} was read as
     * "heal by three within one tile", so a demolition squad detonating in a
     * crowd left everybody standing and slightly healthier.
     */
    private void demolish(Unit caster, Unit victim, Spell.Effect effect) {
        Unit centre = victim != null ? victim : caster;
        if (centre == null) {
            return;
        }
        int range = effect.number("range", 0);
        int damage = effect.number("damage", 0);
        int goalX = centre.tileX();
        int goalY = centre.tileY();

        for (int x = goalX - range; x <= goalX + range; x++) {
            for (int y = goalY - range; y <= goalY + range; y++) {
                // A circle, not a box: upstream compares squared distances
                // against the squared range.
                int dx = x - goalX;
                int dy = y - goalY;
                if (dx * dx + dy * dy > range * range) {
                    continue;
                }
                clearTile(x, y);
            }
        }
        if (damage == 0) {
            return;
        }
        for (Unit unit : List.copyOf(units)) {
            if (!unit.isAlive() || unit.isDying() || !unit.isOnMap()) {
                continue;
            }
            if (unit.type() != null && unit.type().airUnit()) {
                continue;
            }
            if (unit.distanceTo(goalX, goalY) > range) {
                continue;
            }
            // Through the ordinary hit path, which is what kills the caster,
            // sets buildings alight and makes the survivors turn round.
            hitDirectly(caster, unit, damage);
        }
    }

    /**
     * Rains missiles over a patch of ground: blizzard and death and decay.
     *
     * <p>{@code Spell_AreaBombardment::Cast},
     * Both spells parsed to <em>no effects
     * at all</em> -- the verb was unrecognised and fell through to the
     * do-nothing kind -- so twenty-five mana bought a sound and a cooldown.
     *
     * <p>Five fields are chosen in a five-by-five spread around the target and
     * each takes eleven shards. The shards of a field are staggered by their
     * own travel time so they land one after another rather than together,
     * which is what makes a blizzard a squall rather than a single flash. The
     * start offset is what makes the shards fall from up and to the left.
     *
     * <p>Neither spell declares a damage figure, so each shard falls back to
     * its missile type's own {@code Damage} -- {@code Rand(10)} for both, rolled
     * fresh per unit struck. Fifty-five shards of nought to nine is the spell.
     */
    private void bombard(Unit caster, Unit victim, int targetX, int targetY,
            Spell.Effect effect) {
        MissileType type = missileTypes == null ? null : missileTypes.get(effect.what());
        if (type == null || caster == null) {
            return;
        }
        int fields = effect.number("fields", 1);
        int shards = effect.number("shards", 1);
        int damage = effect.number("damage", 0);
        int offsetX = effect.number("start-offset-x", 0);
        int offsetY = effect.number("start-offset-y", 0);

        for (int field = 0; field < fields; field++) {
            int tileX;
            int tileY;
            // "find new destination in the map": upstream retries until the
            // square is on it, and the draws must come off the synchronised
            // generator in the same order on every machine.
            do {
                tileX = targetX + syncRand(5) - 2;
                tileY = targetY + syncRand(5) - 2;
            } while (!map.contains(tileX, tileY));

            double destX = tileX * TILE_SIZE + TILE_SIZE / 2.0;
            double destY = tileY * TILE_SIZE + TILE_SIZE / 2.0;
            for (int shard = 0; shard < shards; shard++) {
                Missile mis = spawn(new Missile(type, caster, null,
                        destX + offsetX, destY + offsetY, destX, destY));
                mis.setDelay(shardDelay(type, shard));
                mis.setDamage(damage);
            }
        }
        missileSnapshot = List.copyOf(missiles);
    }

    /** Retail order 48: create the player's flying eye on the selected square. */
    private void summonEye(Unit caster, int tileX, int tileY) {
        UnitType eye = summonType("unit-eye-of-vision");
        if (eye == null || !map.contains(tileX, tileY)) {
            return;
        }
        int[] spot = dropOutNearest(eye, tileX, tileY, null, tileX, tileY);
        if (spot != null) {
            createUnit(eye, caster.player(), spot[0], spot[1]);
        }
    }

    /** Retail order 46: preserve the slot but turn the victim into a neutral critter. */
    private void polymorph(Unit victim, Spell.Effect effect) {
        if (victim == null || !victim.isAlive()) {
            return;
        }
        UnitType critter = summonType(effect.text("new-form", "unit-critter"));
        if (critter == null || !transformInto(victim, critter)) {
            return;
        }
        if (effect.flag("player-neutral") && victim.player() != NEUTRAL_PLAYER) {
            markSight(victim, false);
            unregisterPlayerUnit(victim);
            victim.setPlayer(NEUTRAL_PLAYER);
            registerPlayerUnit(victim);
            unitCountSeen(victim);
            markSight(victim, true);
            recalculateSupply();
        }
        victim.clearPath();
        victim.setTarget(null);
        victim.setOrder(Unit.Order.STILL);
    }

    /** Retail order 54: halve life and grant exactly 500 cycles of invulnerability. */
    private static void unholyArmor(Unit victim) {
        if (victim == null || !victim.isAlive()) {
            return;
        }
        if (victim.hitPoints() >= 2) {
            victim.setHitPoints(victim.hitPoints() / 2);
        }
        victim.setBuff(Unit.Buff.UNHOLY_ARMOR, 500);
    }

    /** How long the nth shard of a bombardment waits before it falls. */
    private static int shardDelay(MissileType type, int shard) {
        return shard * type.sleep() * 2 * TILE_SIZE / type.shardSpeed();
    }

    /**
     * Strikes a unit for a flat amount, through the ordinary BNE hit path.
     *
     * <p>{@code HitUnit(&caster, *unit, Damage)} as the spells call it: no
     * missile, no stats, just a figure. The retail helper records the attacker,
     * offers it through spatial help, applies damage and stops. In particular,
     * it does not append LegacyEngine's separate {@code HitUnit_RunAway} /
     * {@code HitUnit_AttackBack} tail.
     */
    private void hitDirectly(Unit attacker, Unit target, int damage) {
        if (attacker == null || damage <= 0 || target.type() != null
                && target.type().indestructible()
                || target.hasBuff(Unit.Buff.UNHOLY_ARMOR)) {
            return;
        }
        combat.noteAttacked(attacker, target);
        // HitUnit calls AiHelpMe before it applies the damage, and does so
        // for a fatal blow too. This is not merely the
        // under-attack announcement above: the attacked unit's force may
        // turn its other members on the aggressor immediately. level11o's
        // goblin sapper clips a knight at cycle 335; three archers in that
        // knight's force abandon their spent march waits on the same cycle.
        AiPlayer targetAi = ais.get(target.player());
        if (System.getenv("CHONKCRAFT_TRACE_AIHELP") != null) {
            System.err.printf("JAIHELPPROBE cycle=%d attacker=%d target=%d p%d ai=%d wall=%d damage=%d%n",
                    cycle, attacker == null ? -1 : attacker.id(), target.id(), target.player(),
                    targetAi == null ? 0 : 1,
                    target.type() != null && target.type().wall() ? 1 : 0, damage);
        }
        if (targetAi != null && attacker != null
                && (target.type() == null || !target.type().wall())) {
            targetAi.helpMe(this, attacker, target);
        }
        if (attacker != null && (target.type() == null || !target.type().wall())) {
            battleNetSpatialHelpReactPlusOne(attacker, target);
        }
        // Lethal damage leaves last living HP (BNE corpse report; XHuman 10
        // footman 1492@42, XHuman 2 footman 1548@43).
        int before = target.hitPoints();
        if (before - damage <= 0) {
            if (attacker != null) {
                attacker.setThreshold(0);
            }
            kill(target, attacker);
            return;
        }
        target.setHitPoints(before - damage);
        catchFire(target);
    }

    /**
     * Takes whatever is standing on a square off it.
     *
     * <p>{@code CMap::ClearTile}: trees, rocks or a wall,
     * whichever the square holds. Only Demolish uses it, and it is the whole
     * reason a demolition squad exists.
     */
    private void clearTile(int x, int y) {
        var field = map.fieldOrNull(x, y);
        if (field == null) {
            return;
        }
        if (field.isForest()) {
            map.clearWoodTile(x, y);
        } else if (field.hasFlag(net.chonkbase.chonkcraft.engine.map.TileFlag.ROCKS)) {
            map.clearRockTile(x, y);
        } else if (field.isWall()) {
            map.hitWall(x, y, field.value(), wallMaxHitPoints(field));
        }
        reachable.clear();
    }

    /**
     * The roster, supplied by the loader.
     *
     * <p>Anything the world has to create by name needs this: a summoned
     * skeleton, a corpse left where a soldier fell. It had a setter that
     * nothing anywhere called, which meant summoning could never have worked
     * either.
     */
    private java.util.Map<String, UnitType> unitTypes = java.util.Map.of();

    /** Tells the world what types exist, by identifier. */
    public void setUnitTypes(java.util.Map<String, UnitType> types) {
        this.unitTypes = types == null
                ? java.util.Map.of()
                : new java.util.LinkedHashMap<>(types);
        applyBattleNetUnitProfile();
    }

    /** The registered type for this identifier, or null. */
    public UnitType registeredUnitType(String ident) {
        return ident == null ? null : unitTypes.get(ident);
    }

    /**
     * Rewrites this world's type table from the map's UDTA without
     * touching the shared catalog.
     *
     * <p>Gauntlet stores a 900-HP footman and Heroes 2 a 400-HP one.
     * Mutating the catalog made the next mission inherit those figures.
     * A zero hit-point column is a disabled unused type, not a corpse.
     */
    void applyBattleNetUnitProfile() {
        if (unitTypes.isEmpty()) {
            return;
        }
        java.util.LinkedHashMap<String, UnitType> next = new java.util.LinkedHashMap<>();
        for (var entry : unitTypes.entrySet()) {
            next.put(entry.getKey(), overlayCombatStats(entry.getValue()));
        }
        unitTypes = next;
    }

    /** Campaign-only hit-point overrides that are not a UDTA column. */
    public void overlayUnitHitPoints(String ident, int hitPoints) {
        UnitType type = unitTypes.get(ident);
        if (type == null || hitPoints <= 0 || type.hitPoints() == hitPoints) {
            return;
        }
        UnitType copy = type.copyForMapProfile();
        copy.setHitPoints(hitPoints);
        java.util.LinkedHashMap<String, UnitType> next = new java.util.LinkedHashMap<>(unitTypes);
        next.put(ident, copy);
        unitTypes = next;
    }

    private UnitType overlayCombatStats(UnitType type) {
        int hitPoints = type.hitPoints();
        int armor = type.armor();
        int basic = type.basicDamage();
        int piercing = type.piercingDamage();
        int range = type.maxAttackRange();
        int sight = type.sightRange();
        int priority = type.priority();
        var profile = battleNetUnitProfile;
        int code = PudUnitTypes.code(type.ident());
        if (profile != null && !profile.useDefaults() && code >= 0
                && profile.hitPoints(code) > 0) {
            hitPoints = profile.hitPoints(code);
            armor = profile.armor(code);
            basic = profile.basicDamage(code);
            piercing = profile.piercingDamage(code);
            if (profile.attackRange(code) > 0) {
                range = profile.attackRange(code);
            }
            if (profile.sight(code) > 0) {
                sight = profile.sight(code);
            }
            if (profile.priority(code) > 0) {
                priority = profile.priority(code);
            }
        }
        if ("unit-oil-patch".equals(type.ident())
                || "unit-circle-of-power".equals(type.ident())) {
            hitPoints = 1;
        }
        if ("unit-sharp-axe".equals(type.ident())) {
            hitPoints = 40;
        }
        if (hitPoints == type.hitPoints() && armor == type.armor()
                && basic == type.basicDamage() && piercing == type.piercingDamage()
                && range == type.maxAttackRange() && sight == type.sightRange()
                && priority == type.priority()) {
            return type;
        }
        UnitType copy = type.copyForMapProfile();
        copy.setHitPoints(hitPoints);
        copy.setArmor(armor);
        copy.setBasicDamage(basic);
        copy.setPiercingDamage(piercing);
        copy.setMaxAttackRange(range);
        copy.setSightRange(sight);
        copy.setPriority(priority);
        return copy;
    }

    /**
     * Every unit type registered by the loaded ruleset.
     *
     * <p>This is deliberately a read-only view.  Some engine subsystems need
     * the type table rather than the units which happen to have been placed
     * on this map: in particular {@code AiRequestSupply} searches every
     * registered food building.  A skirmish can begin without a farm on the
     * map, but that cannot make the AI forget that farms exist.
     */
    public java.util.Collection<UnitType> registeredUnitTypes() {
        return unitTypes.values();
    }

    private java.util.Map<String, java.util.List<String>> aiEquivalents = java.util.Map.of();

    /** Tells the AI which types count as one of another; see {@link #aiEquivalents}. */
    public void setAiEquivalents(java.util.Map<String, java.util.List<String>> equivalents) {
        this.aiEquivalents = java.util.Map.copyOf(equivalents);
    }

    /**
     * The types the computer counts as one of a base type.
     *
     * <p>{@code AiHelpers.Equiv()}, the {@code "unit-equiv"} blocks of
     * {@code DefineAiHelper}: a castle answers for a town hall, a ranger for
     * an archer. Every place upstream's AI counts what a player owns adds
     * these in -- {@code AiCheckUnits}, {@code AiForceManager::CheckUnits},
     * {@code CclAiWait} -- which is what keeps an AI whose script asks for a
     * town hall from building one in the shadow of its own castle.
     */
    public java.util.List<UnitType> aiEquivalents(UnitType type) {
        java.util.List<String> idents = aiEquivalents.get(type.ident());
        if (idents == null || idents.isEmpty()) {
            return java.util.List.of();
        }
        java.util.List<UnitType> types = new java.util.ArrayList<>(idents.size());
        for (String ident : idents) {
            UnitType found = unitTypes.get(ident);
            if (found != null) {
                types.add(found);
            }
        }
        return types;
    }

    /** The slot the game keeps for gold mines, critters and corpses. */
    public static final int NEUTRAL_PLAYER = 15;

    private UnitType summonType(String ident) {
        return unitTypes.get(ident);
    }

    /**
     * Regenerates mana.
     *
     * <p>Warcraft II gives a caster one point roughly every two seconds. It is
     * slow on purpose: the pace of mana is what stops a mage from casting
     * continuously.
     */
    private void regenerateMana() {
        if (cycle % (CYCLES_PER_SECOND * 2) != 0) {
            return;
        }
        for (Unit unit : units) {
            if (unit.isAlive() && unit.isCaster() && unit.mana() < unit.type().mana()) {
                unit.setMana(unit.mana() + 1);
            }
        }
    }

    /** Tells the world which upgrades exist, so research can be ordered. */
    public void setUpgrades(UpgradeSet upgrades) {
        this.upgradeSet = upgrades;
        this.upgradeStates = null;
    }

    /** What a player has researched. */
    /**
     * What the current mission permits, or null outside a campaign.
     *
     * <p>Skirmish maps allow everything, so the absence of a table is itself
     * the answer rather than something to guard against.
     */
    private net.chonkbase.chonkcraft.engine.upgrade.AllowState allowed;

    public void setAllowed(net.chonkbase.chonkcraft.engine.upgrade.AllowState allowed) {
        this.allowed = allowed;
    }

    public net.chonkbase.chonkcraft.engine.upgrade.AllowState allowed() {
        return allowed;
    }

    /** The spell definitions in play, for looking up costs. */
    public SpellSet spells() {
        return spellSet;
    }

    /** The upgrade definitions in play, for looking up costs and effects. */
    /**
     * How many finished units of a type a player has, as upstream's
     * {@code UnitTypesCount} counts them.
     *
     * <p>A building under construction is deliberately not counted, and
     * upstream is unusually explicit about it. Starting a building runs
     * {@code build->Player->UnitTypesCount[type.Slot]--} under the comment
     * "HACK: the building is not ready yet", and
     * finishing it runs the matching {@code ++} under "HACK: the building is
     * ready now". The scaffolding is a unit in every
     * other respect -- it occupies ground, it can be attacked, it shows in the
     * total -- but it does not count towards having one.
     *
     * <p>That matters well beyond bookkeeping. The first human mission is won
     * by {@code UnitTypesCount("unit-human-barracks") >= 1}, so counting the
     * site declares victory the moment the peasant starts digging rather than
     * when the barracks is built. Every mission with a "build one of these"
     * objective ends early the same way, and the AI scripts read the same
     * figure to decide what they still need.
     *
     * @param typeIdent a type to count, or null for every unit the player has
     */
    public int unitTypesCount(int player, String typeIdent) {
        int count = 0;
        for (Unit unit : units) {
            // Removed is not dead here either: UnitTypesCount moves at
            // creation, death and change of owner, never at CUnit::Remove,
            // so the peasant inside a mine or a building frame still counts
            // -- for the triggers that ask, and for the AI deciding whether
            // it already owns the worker its script wants.
            if (unit.player() != player || unit.type() == null
                    || unit.hitPoints() <= 0 || unit.order() == Unit.Order.DYING) {
                continue;
            }
            if (unit.order() == Unit.Order.UNDER_CONSTRUCTION) {
                continue;
            }
            // Revealer units are short-lived vision markers (holy vision,
            // death vision, and similar effects), not part of a player's
            // roster. In particular, they must not postpone a mission defeat
            // after the player's last real unit dies.
            //
            // Nor does anything that vanishes. Corpses, craters and the
            // destroyed-place markers are scenery a dead unit leaves; upstream
            // keeps them out of the same two counters this method answers for,
            // and states it as one condition in both places --
            // CUnit::AssignToPlayer skips a vanishing type when it increments
            // UnitTypesCount and TotalUnits and UnitLost matches
            // it when it decrements them (:1294). Thirty shipped types vanish:
            // three dead bodies, five destroyed-places and the twenty-two
            // dead-vision markers.
            //
            // Nothing a player can see today turns on the second half of that
            // condition, and saying so is more useful than implying otherwise.
            // This is the counter GetPlayerData(player, "TotalNumUnits")
            // answers and that is the defeat condition of most of the
            // campaign, so a corpse counted here would be a player who has
            // lost their last unit and not lost the mission -- but no corpse
            // is ever a player's. leaveCorpse puts a body down as the neutral
            // player's, the twenty-two dead-vision markers are revealers and
            // were already skipped by the line above, and the five
            // destroyed-place types are placed by nothing and appear on no
            // shipped map. Measured: over all 52 campaign maps as they ship,
            // zero units of a vanishing non-revealing type belong to any
            // player, and wiping out every unit a player owns on the seventh
            // human and twelfth orc missions leaves this counter at nought
            // with or without the test. It is here because the masking is
            // luck: a map that hands a player a marker, or a corpse credited
            // to whoever left it, and the count is wrong the same day.
            if (unit.type().revealer() || unit.type().vanishes()) {
                continue;
            }
            if (typeIdent == null || typeIdent.equals(unit.type().ident())) {
                count++;
            }
        }
        return count;
    }

    public UpgradeSet upgradeSet() {
        return upgradeSet;
    }

    public UpgradeState upgrades(int player) {
        if (upgradeStates == null) {
            upgradeStates = new UpgradeState[players.length];
        }
        if (upgradeStates[player] == null) {
            upgradeStates[player] = new UpgradeState(upgradeSet);
        }
        return upgradeStates[player];
    }

    /**
     * Starts researching an upgrade at a building.
     *
     * <p>Uses the same progress machinery as training, because in Warcraft II
     * they are the same thing from the building's point of view: it is busy
     * for a while and then something is different.
     *
     * @return whether the order was accepted
     */
    public boolean orderResearch(Unit building, String upgradeIdent) {
        boolean traceResearch = System.getenv("CHONKCRAFT_TRACE_RESEARCH") != null;
        Upgrade upgrade = upgradeSet.get(upgradeIdent);
        if (upgrade == null
                || !building.type().building()
                || building.order() != Unit.Order.STILL
                || building.producing() != null
                || building.researching() != null
                || !building.isAlive()) {
            if (traceResearch) {
                System.err.println("JRESEARCH reject-state cycle=" + cycle
                        + " p=" + (building == null ? -1 : building.player())
                        + " building=" + (building == null ? -1 : building.id())
                        + " what=" + upgradeIdent);
            }
            return false;
        }
        if (!mayResearch(building, upgradeIdent)) {
            if (traceResearch) {
                System.err.println("JRESEARCH reject-relation cycle=" + cycle
                        + " building=" + building.id() + " what=" + upgradeIdent
                        + " why=" + productionRefusal(building.player(), upgradeIdent));
            }
            return false;
        }
        if (upgrades(building.player()).has(upgradeIdent)) {
            if (traceResearch) {
                System.err.println("JRESEARCH reject-owned cycle=" + cycle
                        + " building=" + building.id() + " what=" + upgradeIdent);
            }
            return false;
        }
        if (!players[building.player()].pay(researchCosts(upgrade))) {
            if (traceResearch) {
                System.err.println("JRESEARCH reject-cost cycle=" + cycle
                        + " building=" + building.id() + " what=" + upgradeIdent
                        + " costs=" + upgrade.costs());
            }
            return false;
        }
        if (traceResearch) {
            System.err.println("JRESEARCH paid cycle=" + cycle + " p=" + building.player()
                    + " building=" + building.id() + ":" + building.type().ident()
                    + " what=" + upgradeIdent + " costs=" + upgrade.costs());
        }
        // Queued like every other command: CommandResearch's GetNextOrder is
        // EFlushMode::On behind the running still order, so the blacksmith
        // reads still for the rest of this cycle and research from the next
        // -- which is the cycle the label showed on both engines' traces for
        // training, and the missing cycle that made ten missions' first
        // finding "still vs research" at the right building on the right
        // thought with the right money already paid.
        building.rememberActionBeforeQueued(building.order());
        building.setResearching(upgradeIdent);
        building.setOrderFinished(false);
        building.setProgress(0);
        building.setProgressGoal(
                Math.max(1, researchCosts(upgrade).getOrDefault(UnitType.Resource.TIME, 1))
                        * PROGRESS_PER_TIME_UNIT);
        return true;
    }

    /** Advances any research in progress at a building. */
    boolean stepResearch(Unit building) {
        if (building.researching() == null) {
            return false;
        }
        stepWorkAnimation(building, AnimationSet.State.RESEARCH);
        // COrder_Research sets Finished on the completing execute. The order
        // remains CurrentOrder, and therefore still reports Research, until
        // HandleUnitAction advances it on the following turn

        if (building.orderFinished()) {
            building.setOrderFinished(false);
            building.setResearching(null);
            building.setProgress(0);
            return true;
        }
        building.setProgress(building.progress() + BUILD_PROGRESS_PER_CYCLE);
        // And research's timer walks the same way (:
        // 139-146): the increment lands before the completion test.
        if (building.progress() + PROGRESS_PER_TIME_UNIT - BUILD_PROGRESS_PER_CYCLE
                < building.progressGoal()) {
            return true;
        }
        String finished = building.researching();
        upgrades(building.player()).complete(finished);
        applyUpgradeConversion(building.player(), finished);
        building.setOrderFinished(true);
        return true;
    }

    /**
     * Turns the units an upgrade converts into what it makes them.
     *
     * <p>{@code ApplyUpgradeModifier}'s tail: {@code ConvertUnitTypeTo}
     * transforms every one of the player's units of the applied-to types
     * One shipped upgrade
     * carries a conversion -- researching {@code upgrade-paladin} knights
     * every knight the player owns, on the spot.
     */
    private void applyUpgradeConversion(int player, String ident) {
        if (upgradeSet() == null) {
            return;
        }
        net.chonkbase.chonkcraft.engine.upgrade.Upgrade upgrade = upgradeSet().get(ident);
        if (upgrade == null || upgrade.convertTo() == null) {
            return;
        }
        UnitType target = unitTypes.get(upgrade.convertTo());
        if (target == null) {
            return;
        }
        for (Unit unit : unitsSnapshot()) {
            if (unit.player() == player && unit.isAlive() && unit.type() != null
                    && upgrade.appliesTo().contains(unit.type().ident())) {
                // CommandTransformIntoType installs a CriticalOrder. It is
                // executed on the subject's next breakable HandleUnitAction,
                // not inline inside the researcher that acquired the upgrade.
                // level14h's army has already had its cycle-1507 turns when
                // the altar finishes, so its ogres and axethrowers change on
                // 1508; transforming the snapshot here made all twenty change
                // a cycle early. A second conversion command is ignored while
                // that one-shot order is already present.
                if (unit.pendingTransform() == null) {
                    unit.setPendingTransform(target);
                }
            }
        }
    }

    /**
     * Acquires, in full, every upgrade the map's allow table pre-researches.
     *
     * <p>{@code ApplyUpgrades} -- "Apply researched upgrades when map is
     * loading": a mission that writes
     * {@code DefineAllow("upgrade-paladin", "RRRR...")} means every slot has
     * done the research already, and upstream applies the modifiers at the
     * end of {@code CreateGame} -- stats and conversions both, over the
     * units the map has just placed. This implementation parsed the {@code R}s into
     * {@code AllowState.isPreResearched} and read the answer nowhere, so on
     * levelx07h every knight the map places stayed a knight where upstream
     * fields paladins.
     */
    public void applyResearchedAllows() {
        if (allowed() == null || upgradeSet() == null) {
            return;
        }
        for (int player = 0; player < players.length; player++) {
            if (players[player] == null) {
                continue;
            }
            for (net.chonkbase.chonkcraft.engine.upgrade.Upgrade upgrade
                    : upgradeSet().all().values()) {
                if (allowed().isPreResearched(player, upgrade.ident())
                        && !upgrades(player).has(upgrade.ident())) {
                    upgrades(player).complete(upgrade.ident());
                    applyUpgradeConversion(player, upgrade.ident());
                }
            }
        }
    }

    /**
     * What a cancelled order gives back, as a percentage of what it cost.
     *
     * <p>The three numbers are,
     * Only the
     * building loses anything, and losing a quarter of a keep is enough to make
     * misplacing one hurt, which is the point.
     */
    private static final int CANCEL_TRAINING_REFUND = 100;

    private static final int CANCEL_RESEARCH_REFUND = 100;

    static final int CANCEL_BUILDING_REFUND = 75;

    /** Hands back part of what something cost. */
    void refund(int player, java.util.Map<UnitType.Resource, Integer> costs, int factor) {
        for (var entry : costs.entrySet()) {
            // Time is a duration, not a stockpile.
            if (entry.getKey() == UnitType.Resource.TIME) {
                continue;
            }
            players[player].add(entry.getKey(), entry.getValue() * factor / 100);
        }
    }

    /**
     * Stops a building training, refunding what it cost.
     *
     * @return whether there was anything to cancel
     */
    public boolean cancelTraining(Unit building) {
        UnitType what = building.producing();
        if ((what == null && building.trainingQueue().isEmpty())
                || building.type() == null || !building.type().building()) {
            return false;
        }
        if (what != null) {
            refund(building.player(), unitCosts(what), CANCEL_TRAINING_REFUND);
        }
        for (UnitType queued : building.trainingQueue()) {
            refund(building.player(), unitCosts(queued), CANCEL_TRAINING_REFUND);
        }
        building.setProducing(null);
        building.clearTrainingQueue();
        building.setProgress(0);
        recalculateSupply();
        return true;
    }

    /** Stops a building researching, refunding what it cost. */
    public boolean cancelResearch(Unit building) {
        String ident = building.researching();
        if (ident == null) {
            return false;
        }
        Upgrade upgrade = upgradeSet.get(ident);
        if (upgrade != null) {
            refund(building.player(), upgrade.costs(), CANCEL_RESEARCH_REFUND);
        }
        building.setResearching(null);
        building.setOrderFinished(false);
        building.setProgress(0);
        return true;
    }

    /** Gives a player slot a computer opponent. */
    public AiPlayer enableAi(int playerIndex) {
        return ais.computeIfAbsent(playerIndex, AiPlayer::new);
    }

    /**
     * Turns on the AI for every slot the map says thinks for itself.
     *
     * <p>Computers, and rescue-active players too. That second kind is the
     * whole difference between the two rescuable sorts: a rescue-passive
     * prisoner stands where it was put until somebody frees it, and a
     * rescue-active ally fights the computers on your behalf from the first
     * second. {@code CPlayer::Init} sets {@code AiEnabled} for both, and the
     * maps rely on it -- the fifth human mission gives its rescue-active slot
     * the {@code orc-03} personality the mission script writes, and without an
     * AI attached that slot is a statue and the personality is unreachable.
     */
    public int enableAiForComputerPlayers() {
        int enabled = 0;
        for (Player player : players) {
            if (player.type() == net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                    || player.type() == net.chonkbase.chonkcraft.data.map.PudMap
                            .PlayerType.RESCUE_ACTIVE) {
                enableAi(player.index());
                enabled++;
            }
        }
        return enabled;
    }

    /** The computer players in play. */
    public Map<Integer, AiPlayer> ais() {
        return ais;
    }

    private static Player[] defaultPlayers() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i, net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.PERSON,
                    net.chonkbase.chonkcraft.data.map.PudMap.Race.HUMAN);
        }
        return players;
    }

    /** The sixteen player slots. */
    public Player[] players() {
        return players;
    }

    /** One player slot. */
    public Player player(int index) {
        return players[index];
    }

    public GameMap map() {
        return map;
    }

    /**
     * A snapshot of the unit list, published once per tick.
     *
     * <p>The renderer runs on the event thread while the simulation runs on
     * its own, so it cannot walk the live list: a unit created or swept
     * mid-frame throws ConcurrentModificationException. Publishing an
     * immutable copy at the end of each tick costs one allocation a tick and
     * gives the renderer a consistent view of one moment rather than a
     * half-updated one.
     */
    public List<Unit> unitsSnapshot() {
        return snapshot;
    }

    /** Every unit, living and dead. Simulation-side view. */
    public List<Unit> units() {
        return Collections.unmodifiableList(units);
    }

    /** How many cycles have elapsed. */
    /**
     * Sets how far the game has run.
     *
     * <p>Only a save has any business calling this: the cycle drives the
     * once-a-second work, and moving it by hand mid-game would skip or repeat
     * a second of triggers and upkeep.
     */
    public void setCycle(long cycle) {
        this.cycle = cycle;
    }

    public long cycle() {
        return cycle;
    }

    // ----------------------------------------------------------- population

    /**
     * Places a unit where a map asks for it, or beside there if it will not
     * fit.
     *
     * <p>Implements {@code CclCreateUnit}, which is how every unit a
     * Warcraft II map places actually arrives upstream: the requested square
     * is used when {@code UnitCanBeAt} allows it -- or, for a building, when
     * {@code CanBuildUnitType} does -- and otherwise the unit is dropped out
     * on a side rather than forced or forgotten.
     *
     * <p>This implementation used the requested square unconditionally, and the maps
     * say that is not the same thing. On {@code maps/demo/demo02} the
     * generated script asks for an oil platform at 13,21, where an oil
     * tanker asked for 14,20 is already sitting: upstream refuses the
     * square, drops the platform out to 12,22, and then refuses 12,24 to
     * the destroyer that asks for it next -- because the relocated platform
     * is standing there. This implementation put the platform on top of the tanker and
     * the destroyer on top of nothing in particular, and the two engines'
     * maps disagreed from the first cycle. One overlap at load cascades.
     *
     * <p>The side is drawn, as upstream draws it, from a generator that runs
     * alongside the game's own and never touches it. {@code CclCreateUnit}
     * takes {@code SyncRand() % 256} for the heading it hands
     * {@code DropOutOnSide}, and every
     * unit that faces more than one way draws one more as it is made
     * Those draws happen while the map's script
     * runs and {@code CreateGame} calls {@code InitSyncRand} again
     * afterwards, so they are not part of the sequence the game is played
     * with -- taking them from {@link #syncRand} would move this implementation's seed
     * off upstream's on cycle one, which is the one thing the harness
     * measures.
     *
     * <p>They are still reproducible, and the numbers were read off the real
     * binary rather than guessed at: with {@code SyncRand} logged through a
     * whole load of {@code maps/demo/demo02}, upstream makes exactly
     * eighteen draws, fourteen from the heading in {@code CUnit::Init} and
     * four from this displacement -- fourteen non-building units and four
     * that will not fit, which is that map exactly -- and the seed before
     * the first of them is **{@code 0x87654321}**. {@code InitSyncRand}
     * runs before the map script as well as after it. Nought was tried
     * first, on the reasoning that {@code SyncRandSeed} is a global with no
     * initialiser, and it is wrong.
     */
    public Unit createUnitForMap(UnitType type, int player, int tileX, int tileY) {
        if (type == null || !map.contains(tileX, tileY)) {
            return null;
        }
        // BNE still rewrites ordinary racial counterparts to the owner's
        // race at map load -- Garden of War's halls and workers will not
        // accept the other race's production packets without it. It does
        // not rewrite undead or summoned types. XOrc 9 stores fifty-two
        // skeletons on a human-race slot; the sealed cycle-one capture
        // keeps those skeletons, and converting them to militia used to
        // invent fifty-two unmatched identities before any unit moved.
        Player owner = player(player);
        if (owner != null && player != NEUTRAL_PLAYER) {
            if (owner.type()
                    == net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.NOBODY) {
                return null;
            }
            java.util.Map<String, String> equivalents = switch (owner.race()) {
                case HUMAN -> toHumanEquivalent;
                case ORC -> toOrcEquivalent;
                case NEUTRAL -> java.util.Map.of();
            };
            String converted = equivalents.get(type.ident());
            if (converted != null && !isNonRacialMapType(type.ident())) {
                UnitType convertedType = unitTypes.get(converted);
                if (convertedType == null) {
                    // A configured equivalence is part of the deterministic
                    // roster contract. Silently retaining the other race here
                    // produces a valid-looking but irreconcilable game.
                    throw new IllegalStateException(
                            "missing map-load race equivalent " + converted);
                }
                type = convertedType;
            }
        }
        int width = Math.max(1, type.tileWidth());
        int height = Math.max(1, type.tileHeight());
        // Every unit that faces more than one way draws its opening heading as
        // it is made: "Set a heading for the unit if it Handles Directions",
        // {@code Direction = (SyncRand() >> 8) & 0xFF}
        // The draw has to happen here because the
        // displacement heading below comes out of the same sequence and lands
        // elsewhere without it -- and the number is the unit's own facing, not
        // spare change. Throwing it away and leaving every unit the map places
        // looking south is what stood a ballista on demo03 still for thirty
        // cycles: its Move animation opens with "if-var R <= -60 turn", a step
        // due east from south is a quarter turn, and upstream's -- which had
        // its drawn angle of 34 to turn from -- rotates thirty and walks.
        int drawn = -1;
        if (!type.building() && type.numDirections() > 1) {
            drawn = (loadRand() >> 8) & 0xFF;
        }
        // Everything below makes its unit without a second heading draw: the
        // one above is this unit's, taken from the load-time generator.
        Unit placed;
        // Retail BNE consumes the PUD's UNIT records directly.  It does
        // not route them through ChonkCraft's retired scripting language CreateUnit wrapper and its
        // DropOutOnSide repair.  The distinction is visible on valid
        // Blizzard campaign maps: ships requested at 82,4 and 54,64 are
        // retained at those exact coordinates by BNE, while ChonkCraft's
        // footprint test relocates them to 81,4 and 54,63.  The PUD is
        // authoritative for this profile; later runtime spawns continue
        // to use the ordinary placement rules.
        placed = createUnitAt(type, player, tileX, tileY);
        if (placed != null && drawn >= 0) {
            placed.setDirection(drawn);
        }
        // CclCreateUnit fires the type's OnReady before it returns
        // while the map is still loading -- so a scout
        // flyer's first exploration target is drawn mid-load, from the
        // sequence the pre-game reseed throws away, and every Init heading
        // and displacement drawn after it sits two to eight draws further
        // along. The game-creation pass then fires OnReady again and the
        // fresh CommandExplore replaces the target with one off the live
        // stream, which is why only maps that place units after their flyer
        // ever showed this: on level07h an oil tanker asked for 56,74 landed
        // on 55,73 upstream and 55,74 here, because its displacement heading
        // was read six draws apart.
        return placed;
    }

    /**
     * Types BNE leaves as stored even when the owner is the other race.
     *
     * <p>Ordinary racial counterparts still convert so a melee hall can
     * train. Skeletons, daemons and critters are not racial units; rewriting
     * them is what turned XOrc 9's opening army into militia.
     */
    private static boolean isNonRacialMapType(String ident) {
        return "unit-skeleton".equals(ident)
                || "unit-attack-peasant".equals(ident)
                || "unit-attack-peon".equals(ident)
                || "unit-daemon".equals(ident)
                || "unit-critter".equals(ident);
    }

    /**
     * The generator a map's script draws from, which is not the game's.
     *
     * <p>Started where {@code InitSyncRand} leaves it, because it runs before
     * a map loads as well as after, and advanced by the step {@link #syncRand}
     * uses because it is the same function.
     */
    private int loadRand() {
        int value = loadRandomSeed >>> 16;
        loadRandomSeed = loadRandomSeed * (0x12345678 * 4 + 1) + 1;
        return value;
    }

    private int loadRandomSeed = DEFAULT_RANDOM_SEED;

    /**
     * Places a unit and marks the squares it covers.
     *
     * @return the new unit, or {@code null} if it will not fit on the map
     */
    public Unit createUnit(UnitType type, int player, int tileX, int tileY) {
        return createUnitAt(type, player, tileX, tileY);
    }

    /**
     * Makes a unit read back out of a saved game.
     *
     * <p>ChonkCraft drew an opening heading here, from the synchronized stream, so
     * that a restored unit could not be told from a fresh one:
     * {@code CUnit::Init} gave anything that
     * faces more than one way {@code Direction = (SyncRand() >> 8) & 0xFF}.
     * Retail BNE takes no draw at all -- the heading was written down with the
     * rest of the unit, and re-rolling one would move the generator the save
     * exists to preserve. {@link #initializeBattleNetUnit} is the whole of
     * what a new unit's heading now comes from, which is why this is a plain
     * alias for {@link #createUnit}.
     */
    public Unit restoreUnit(UnitType type, int player, int tileX, int tileY) {
        return createUnit(type, player, tileX, tileY);
    }

    private Unit createUnitAt(UnitType type, int player, int tileX, int tileY) {
        if (!map.contains(tileX, tileY)) {
            return null;
        }
        Unit unit = new Unit(nextUnitId++, type, player, tileX, tileY);
        initializeBattleNetUnit(unit);
        // A deposit starts holding whatever its type says, which the map then
        // overrides with its own figure where it has one. Without a default,
        // a mine placed by a script rather than by a map would be empty.
        unit.setResourcesHeld(type.hitPoints());
        // Native FUN_00451b50 inserts into DAT_004bf1d8 immediately, so a later
        // actor in the same tick can discover a newborn before its own action.
        insertBattleNetSpatialUnit(unit);
        if (ticking) {
            pending.add(unit);
        } else {
            units.add(unit);
            snapshot = List.copyOf(units);
        }
        registerPlayerUnit(unit);
        markOccupancy(unit, true);
        initializeBattleNetAiHome(unit);
        construction.markBattleNetExistingBuildingReservation(unit);
        unitCountSeen(unit);
        markSight(unit, true);
        return unit;
    }

    /** Mirrors FUN_00451b50's three type-dependent construction draws. */
    private void initializeBattleNetUnit(Unit unit) {
        int type = PudUnitTypes.code(unit.type().ident());
        // Native bit unit+0x1c&2 selects the doubled movement table. Every
        // flyer carries it, even the daemon whose collision footprint is only
        // 1x1; large ground/sea footprints carry it as well. Deriving the bit
        // from footprint alone made commanded daemons half-step while retail
        // commits directly to the next even-grid anchor.
        unit.setBattleNetDoubleStep(unit.canMove()
                && (unit.type().airUnit()
                    || unit.type().tileWidth() > 1
                    || unit.type().tileHeight() > 1));
        if (type >= 0 && type < 58) {
            unit.setHeading(battleNetRand() & 7);
        }
        if (type >= 58 && type <= 104) {
            // BNE chooses one of six building animation variants here. The
            // semantic tracer does not expose the cosmetic variant, but the
            // draw is part of the later critter stream and cannot be skipped.
            battleNetRand();
        }
        unit.setBattleNetAnimationTimer(1 + (battleNetRand() & 7));
        unit.setBattleNetSequenceOffset(idle.battleNetStillSequenceStart(unit));
        unit.setBattleNetIdlePhase(0);
        if (BNE_IDLE_TRACE) {
            System.err.printf("JBNEINIT unit=%d type=%s timer=%d seed=%s%n",
                    unit.id(), unit.type().ident(),
                    unit.battleNetAnimationTimer(),
                    Integer.toUnsignedString(battleNetRandomSeed));
        }
    }

    /** Assigns native behavior one's home while this unit is being created. */
    private void initializeBattleNetAiHome(Unit unit) {
        Player owner = player(unit.player());
        if (owner == null
                || owner.type()
                        != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                || unit.type().moveType() != UnitType.Movement.LAND
                || unit.type().building() || !unit.type().canAttack()
                || unit.type().canGather()) {
            return;
        }
        int[] home = battleNetHostileExistsAtCreation(unit)
                ? battleNetInitialLandHome(unit) : null;
        if (home == null) {
            home = new int[] {unit.tileX(), unit.tileY()};
        }
        unit.setBattleNetAiHome(home[0], home[1]);
    }

    /** Native {@code 0x427830}, evaluated against the partial creation list. */
    private boolean battleNetHostileExistsAtCreation(Unit unit) {
        for (Unit candidate : units) {
            if (candidate == unit || !candidate.isAlive() || !candidate.isOnMap()
                    || candidate.type() == null
                    || !isEnemyPlayer(unit.player(), candidate.player())
                    || !targets.canTarget(unit, candidate)) {
                continue;
            }
            return true;
        }
        return false;
    }

    /**
     * Adds or removes a unit's contribution to its owner's vision.
     *
     * <p>Reference counted, so it must be removed before a unit moves and
     * added after, exactly like its occupancy. Getting these out of step
     * leaves permanently lit squares.
     */
    void markSight(Unit unit, boolean seeing) {
        int width = Math.max(1, unit.type().tileWidth());
        int height = Math.max(1, unit.type().tileHeight());
        int player = unit.player();
        // The _TileSeen walks: a square lit from dark
        // raises the standing units' per-player counts, a square darkened
        // lowers them; ordinary sight touches the uncloaked, detection the
        // cloaked. This is how a unit's visibility follows other people's
        // movements without anyone recounting it.
        FogOfWar.IndexAction lit = index -> bumpUnitsOnTile(index, player, +1, false);
        FogOfWar.IndexAction dark = index -> bumpUnitsOnTile(index, player, -1, false);
        FogOfWar.IndexAction litCloak = index -> bumpUnitsOnTile(index, player, +1, true);
        FogOfWar.IndexAction darkCloak = index -> bumpUnitsOnTile(index, player, -1, true);
        if (seeing) {
            int range = sightRangeOf(unit);
            if (range <= 0) {
                unit.setMarkedSightRange(0);
                return;
            }
            fog.addSight(player, unit.tileX(), unit.tileY(), width, height, range, lit);
            if (unit.type().detectCloak()) {
                fog.addDetection(player, unit.tileX(), unit.tileY(), width, height, range,
                        litCloak);
            }
            unit.setMarkedSightRange(range);
        } else {
            // At the range it was granted at, not the range it would get now.
            int range = unit.markedSightRange();
            if (range <= 0) {
                return;
            }
            fog.removeSight(player, unit.tileX(), unit.tileY(), width, height, range, dark);
            if (unit.type().detectCloak()) {
                fog.removeDetection(player, unit.tileX(), unit.tileY(), width, height, range,
                        darkCloak);
            }
            unit.setMarkedSightRange(0);
        }
    }

    /** The body of the {@code _TileSeen} walk, over one square's occupants. */
    private void bumpUnitsOnTile(int index, int player, int delta, boolean cloaked) {
        List<Unit> cached = unitCache.get(index);
        if (cached == null) {
            return;
        }
        for (Unit other : cached) {
            if (other.type() == null || other.type().permanentCloak() != cloaked) {
                continue;
            }
            other.bumpVisCount(player, delta);
            if (delta > 0) {
                // UnitGoesOutOfFog sets Seen.ByPlayer for every player whose
                // IsVisible answer becomes true, including shared vision.
                for (int viewer = 0; viewer < Player.MAX; viewer++) {
                    if (isVisibleTo(viewer, other)) {
                        other.markSeenBy(viewer);
                    }
                }
            }
        }
    }

    /**
     * Recounts every unit's watchers from the settled fog.
     *
     * <p>{@code UpdateFogOfWarChange}'s "Global seen recount"
     * Upstream runs it only when the fog
     * setting itself is toggled -- {@code CclSetFogOfWar} outside a config
     * file, and the network fog command -- never at ordinary map load: a
     * loaded game's counts come from each placement's own recount plus the
     * tile walks of everyone placed after it. Calling this at load added a
     * phantom upstream does not have, and campaigns/human/level13h's knight
     * turned east again to prove it.
     */
    public void recountSeen() {
        for (Unit unit : units) {
            unitCountSeen(unit);
        }
    }

    /**
     * Recounts who can see a unit, from the fog under its feet.
     *
     * <p>{@code UnitCountSeen}, run whenever the
     * unit's own squares change -- placement and every step. The honest
     * count is the visible squares of the footprint; then the "tricky part",
     * kept bug for bug: on the went-out-of-fog transition upstream
     * increments once more, so a unit that has ever been seen carries one
     * watcher beyond what the tiles say and outlives its last real watcher
     * by one sight change. On campaigns/human/level13h that phantom is the
     * whole first divergence: the ogre at 120,33 stays a knight's valid
     * goal after its only watcher walks off, and an honest count led this
     * port's knight east where upstream's went west.
     */
    void unitCountSeen(Unit unit) {
        if (unit.type() == null) {
            return;
        }
        int width = Math.max(1, unit.type().tileWidth());
        int height = Math.max(1, unit.type().tileHeight());
        boolean[] oldVisible = new boolean[Player.MAX];
        for (int p = 0; p < Player.MAX; p++) {
            oldVisible[p] = isVisibleTo(p, unit);
        }
        for (int p = 0; p < Player.MAX; p++) {
            boolean cloaked = unit.type().permanentCloak() && p != unit.player();
            int count = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (cloaked
                            ? fog.isDetected(p, unit.tileX() + x, unit.tileY() + y)
                            : fog.isVisible(p, unit.tileX() + x, unit.tileY() + y)) {
                        count++;
                    }
                }
            }
            unit.setVisCount(p, count);
        }
        for (int p = 0; p < Player.MAX; p++) {
            boolean now = isVisibleTo(p, unit);
            if (!oldVisible[p] && now) {
                unit.markSeenBy(p);
                unit.bumpVisCount(p, 1);
            } else if (oldVisible[p] && !now) {
                unit.bumpVisCount(p, -1);
            } else if (now) {
                // A loaded or newly constructed unit may begin inside sight
                // without crossing a later tile callback.
                unit.markSeenBy(p);
            }
        }
    }

    /**
     * How far a unit can actually see, as {@code UpdateUnitSightRange} works
     * it out.
     *
     * <p>Two things this used to ignore, both of them visible in play.
     *
     * <p>A building going up sees one square, not its finished range. Upstream
     * says so outright -- "units under construction have no sight range" --
     * and without it planting a Watch Tower lights the neighbourhood before a
     * single peasant has swung a hammer.
     *
     * <p>And upgrades count. {@code upgrade-ranger-scouting} is
     * {@code {"SightRange", 3}} over a base of four, so a Ranger with it sees
     * seven squares rather than four: it is most of what the upgrade is for.
     * {@code UpgradeState.sightRange} already worked this out and nothing
     * asked it.
     */
    private int sightRangeOf(Unit unit) {
        if (unit.type() == null) {
            return 0;
        }
        if (unit.sightRangeOverride() >= 0) {
            return unit.sightRangeOverride();
        }
        if (unit.order() == Unit.Order.UNDER_CONSTRUCTION) {
            return 1;
        }
        return upgrades(unit.player()).sightRange(unit.type());
    }

    /**
     * Re-grants the sight of anything whose range has changed under it.
     *
     * <p>An upgrade finishing and a building being finished both change what a
     * unit can see without the unit moving, and sight is only otherwise
     * touched when something moves. Upstream reacts to each event;
     * a sweep arrives at the same place and cannot be forgotten in a new code
     * path that changes a stat.
     */
    private void refreshChangedSight() {
        for (Unit unit : units) {
            if (!unit.isAlive() || unit.isDying() || !unit.isOnMap() || unit.type() == null) {
                continue;
            }
            if (sightRangeOf(unit) != unit.markedSightRange()) {
                markSight(unit, false);
                markSight(unit, true);
            }
        }
    }

    /**
     * Places or removes a unit over its whole footprint.
     *
     * <p>The field flags and the per-tile unit cache are deliberately
     * separate. {@code CUnit::Place} calls {@code MarkUnitFieldFlags} and
     * {@code Map.Insert} independently: a vanishing corpse sets no obstacle
     * flag but is still present in {@code CMapField::UnitCache}. Enemy force
     * searches enumerate that cache directly, so treating "does not block"
     * as "is absent" loses destroyed-place targets before their decay
     * animation releases them.
     */
    void markOccupancy(Unit unit, boolean occupied) {
        boolean marksField = !unit.type().vanishes() && !unit.type().nonSolid();
        long flag = marksField ? unit.occupancyFlag() : 0;
        for (int dy = 0; dy < Math.max(1, unit.type().tileHeight()); dy++) {
            for (int dx = 0; dx < Math.max(1, unit.type().tileWidth()); dx++) {
                int x = unit.tileX() + dx;
                int y = unit.tileY() + dy;
                MapField field = map.fieldOrNull(x, y);
                if (field == null) {
                    continue;
                }
                int index = x + y * map.width();
                List<Unit> cached = unitCache.get(index);
                if (occupied) {
                    if (marksField) {
                        field.addFlags(flag);
                    }
                    if (cached == null) {
                        cached = new ArrayList<>(2);
                        unitCache.set(index, cached);
                    }
                    if (!cached.contains(unit)) {
                        // CMapField::UnitCache is insertion-ordered. A unit
                        // stepping onto an occupied tile goes behind whoever
                        // was already there; sorting by slot id changes which
                        // same-layer occupant A* sees first.
                        cached.add(unit);
                    }
                } else {
                    if (marksField) {
                        field.removeFlags(flag);
                    }
                    if (cached != null) {
                        cached.remove(unit);
                        if (cached.isEmpty()) {
                            unitCache.set(index, null);
                        } else {
                            // UnmarkUnitFieldFlags clears this unit's bit and
                            // rebuilds it from every other non-dying unit in
                            // the tile cache. Two bodies can overlap while a
                            // step is in flight; removing one must not erase
                            // the other's occupancy.
                            for (Unit other : cached) {
                                if (other.isOnMap() && !other.isDying()
                                        && other.type() != null
                                        && !other.type().vanishes()
                                        && !other.type().nonSolid()) {
                                    field.addFlags(other.occupancyFlag());
                                }
                            }
                        }
                    }
                }
            }
        }
        // A mobile body is a temporary obstacle. A building changes which
        // parts of the map are connected, so every pair proved before it went
        // up or came down has to be asked again.
        if (marksField && unit.type().building()) {
            reachable.clear();
        }
    }

    /**
     * Clears a dying unit's field flag but leaves it in the tile cache.
     *
     * <p>Upstream's {@code UnmarkUnitFieldFlags} does exactly that: the
     * corpse-to-be remains in {@code CMapField::UnitCache} until
     * {@code CUnit::Remove} at the end of its death animation. Usually the
     * distinction is invisible because the occupancy flag was cleared. When
     * another same-layer unit steps onto the tile, however, A* sees that
     * flag, asks the cache for its first matching unit, and can get the dying
     * one. On levelx12h a moving grunt shares 24,59 with the grunt it just
     * killed; the dead unit is first and makes that square impassable.
     */
    private void unmarkOccupancyForDeath(Unit unit) {
        if (unit.type().vanishes() || unit.type().nonSolid()) {
            return;
        }
        long flag = unit.occupancyFlag();
        for (int dy = 0; dy < Math.max(1, unit.type().tileHeight()); dy++) {
            for (int dx = 0; dx < Math.max(1, unit.type().tileWidth()); dx++) {
                int x = unit.tileX() + dx;
                int y = unit.tileY() + dy;
                MapField field = map.fieldOrNull(x, y);
                if (field == null) {
                    continue;
                }
                field.removeFlags(flag);
                List<Unit> cached = unitCache.get(x + y * map.width());
                if (cached == null) {
                    continue;
                }
                for (Unit other : cached) {
                    if (other != unit && other.isOnMap() && !other.isDying()
                            && other.type() != null
                            && !other.type().vanishes() && !other.type().nonSolid()) {
                        field.addFlags(other.occupancyFlag());
                    }
                }
            }
        }
        if (unit.type().building()) {
            reachable.clear();
        }
    }

    /**
     * Runs the pathfinder with the moving unit's field flag temporarily
     * removed, as {@code DoActionMove} does around {@code NextPathElement}.
     *
     * <p>The unit deliberately stays in the per-tile cache. Upstream's
     * {@code UnmarkUnitFieldFlags} clears its bit, rebuilds the bit from every
     * other non-dying occupant, but does not remove any cache entries. That
     * distinction is visible when a soldier shares a square with a dying
     * body: with no other live body the field has no land-unit bit, so A*
     * never consults the cache and the soldier can be on its own start
     * square. Leaving the soldier's bit marked exposes the older dying cache
     * entry and falsely makes that start square unreachable.
     */
    PathFinder.Path findMovementPath(Unit unit, PathFinder.Goal goal) {
        setMovementFieldFlags(unit, false);
        try {
            return pathFinder.find(unit.tileX(), unit.tileY(), goal, moverFor(unit));
        } finally {
            setMovementFieldFlags(unit, true);
        }
    }

    /** The field-flag-only half of {@link #findMovementPath}. */
    void setMovementFieldFlags(Unit unit, boolean marked) {
        long ownFlag = unit.occupancyFlag();
        for (int dy = 0; dy < Math.max(1, unit.type().tileHeight()); dy++) {
            for (int dx = 0; dx < Math.max(1, unit.type().tileWidth()); dx++) {
                int x = unit.tileX() + dx;
                int y = unit.tileY() + dy;
                MapField field = map.fieldOrNull(x, y);
                if (field == null) {
                    continue;
                }
                if (marked) {
                    field.addFlags(ownFlag);
                    continue;
                }
                field.removeFlags(ownFlag);
                List<Unit> cached = unitCache.get(x + y * map.width());
                if (cached == null) {
                    continue;
                }
                for (Unit other : cached) {
                    if (other != unit && other.isOnMap() && !other.isDying()
                            && other.type() != null
                            && !other.type().vanishes() && !other.type().nonSolid()) {
                        field.addFlags(other.occupancyFlag());
                    }
                }
            }
        }
    }

    /** The unit whose footprint covers a tile, or {@code null}. */
    /**
     * The unit whose selection box covers a point, in map pixels.
     *
     * <p>Implements {@code UnitOnScreen}. Asking which tile
     * a click landed on and then which unit owns that tile is not the same
     * question: a unit part way between two squares is drawn where its sprite
     * is, not where its logical tile is, so the box you can see and the tile
     * the click resolves to are up to a tile apart. Everything that moves --
     * which is to say everything worth clicking on in a fight -- is affected,
     * and the symptom is a right click on an enemy that walks your soldiers
     * past it instead of attacking.
     *
     * <p>The box is the type's own {@code BoxSize}, centred on the unit's
     * drawn centre. A building's box is much larger than a footman's, which is
     * why buildings never felt broken.
     *
     * <p>Over the published snapshot, not the live roster. This is only ever
     * asked by the interface, and the interface asks it from the event thread
     * while the simulation is adding and removing units on its own -- so a
     * player who moved the mouse across the battlefield got this and lost the
     * game:
     *
     * <pre>
     * Exception in thread "AWT-EventQueue-0" java.util.ConcurrentModificationException
     *     at net.chonkbase.chonkcraft.engine.World.unitAtPixel
     *     at net.chonkbase.chonkcraft.desktop.GameScreen.updateCursor
     * </pre>
     *
     * <p>It was not the narrow window a birth-and-death rate suggests. On
     * {@code campaigns/human/level13h} units are born 0.08 times a second and
     * die 0.07, but {@link #tick} ends with {@code units.addAll(pending)} and
     * {@code ArrayList.addAll} raises its modification count before it looks at
     * whether the collection it was handed is empty -- so the roster
     * invalidated every iterator thirty times a second on a map where nothing
     * at all was happening. Against a real mission it took four passes of the
     * pointer to crash.
     *
     * @param pixelX map pixel, not screen pixel
     * @param pixelY likewise
     * @param eligible an extra test, or null; used to skip units the player
     *                 cannot see
     */
    public Unit unitAtPixel(int pixelX, int pixelY, java.util.function.Predicate<Unit> eligible) {
        java.util.List<Unit> hits = null;
        for (Unit unit : snapshot) {
            // Unit.isPointable, not isAlive: an oil patch and a circle of
            // power are declared HitPoints = 0 and so are never "alive", and
            // clicking one is how a player reads the oil left in it and how
            // the Dark Portal missions are won.
            if (!unit.isPointable() || !unit.isOnMap() || unit.type() == null) {
                continue;
            }
            if (eligible != null && !eligible.test(unit)) {
                continue;
            }
            int boxWidth = unit.type().boxWidth() > 0
                    ? unit.type().boxWidth()
                    : Math.max(1, unit.type().tileWidth()) * Unit.TILE_PIXELS;
            int boxHeight = unit.type().boxHeight() > 0
                    ? unit.type().boxHeight()
                    : Math.max(1, unit.type().tileHeight()) * Unit.TILE_PIXELS;
            int centreX = unit.pixelX() + Math.max(1, unit.type().tileWidth()) * Unit.TILE_PIXELS / 2;
            int centreY = unit.pixelY() + Math.max(1, unit.type().tileHeight()) * Unit.TILE_PIXELS / 2;
            int left = centreX - boxWidth / 2;
            int top = centreY - boxHeight / 2;
            if (pixelX >= left && pixelX < left + boxWidth
                    && pixelY >= top && pixelY < top + boxHeight) {
                if (hits == null) {
                    hits = new java.util.ArrayList<>(2);
                }
                hits.add(unit);
            }
        }
        if (hits == null) {
            return null;
        }
        // The topmost drawn hit wins, and a repeated click cycles down the
        // stack. LegacyEngine's UnitOnScreen walks its units in roster order and
        // breaks at the first hit, which on a tanker moored over an oil patch
        // answers with the patch -- the patch was placed by the map and sits
        // earlier in the roster -- and a player reported exactly that as
        // "I cannot click units on the slick". That walk contradicts the
        // function's own comment, "More units on same position ... First
        // take highest unit", and the original game, where the ship above
        // the slick always takes the click; this follows the comment and the
        // game rather than the walk, sorting hits the way the renderer
        // layers them and reading from the top. The cycle survives: a click
        // on the thing already selected hands back the one beneath it, so
        // the patch under a selected ship is still reachable.
        if (hits.size() > 1) {
            hits.sort(java.util.Comparator
                    .comparingInt((Unit unit) -> unit.type().drawLevel())
                    .thenComparingInt(Unit::pixelY)
                    .thenComparingInt(Unit::pixelX));
        }
        for (int i = hits.size() - 1; i >= 0; i--) {
            if (!hits.get(i).selected()) {
                return hits.get(i);
            }
        }
        return hits.get(hits.size() - 1);
    }

    /**
     * The goal-tile occupant of a mover's own movement layer.
     *
     * <p>{@code UnitOnMapTile(pos, unit.Type->MoveType)}: the blocker test
     * that ends a waiting move order only consults units that actually
     * contest the mover's ground -- land against land, sea against sea, air
     * against air.
     */
    Unit blockerOnLayer(Unit mover, int tileX, int tileY) {
        if (!map.contains(tileX, tileY)) {
            return null;
        }
        List<Unit> cached = unitCache.get(tileX + tileY * map.width());
        if (cached == null) {
            return null;
        }
        for (Unit candidate : cached) {
            if (!candidate.isOnMap() || !candidate.isAlive() || candidate.type() == null) {
                continue;
            }
            if (candidate.type().airUnit() == mover.type().airUnit()
                    && candidate.type().seaUnit() == mover.type().seaUnit()) {
                return candidate;
            }
        }
        return null;
    }

    public Unit unitAt(int tileX, int tileY) {
        if (!map.contains(tileX, tileY)) {
            return null;
        }
        List<Unit> cached = unitCache.get(tileX + tileY * map.width());
        if (cached == null) {
            return null;
        }
        for (Unit unit : cached) {
            // As unitAtPixel, and for the same reason. The two callers inside
            // this file both go on to ask providesResource, which wants
            // GivesResource and CanHarvest together: an oil patch declares the
            // first and not the second, so widening the lookup hands them a
            // patch they already refuse and the harvest paths do not move.
            if (!unit.isPointable() || !unit.isOnMap()) {
                continue;
            }
            return unit;
        }
        return null;
    }

    // -------------------------------------------------------------- orders

    /**
     * Coast tile that land units cannot stand on (COAST_ALLOWED without
     * LAND_ALLOWED). Distinct from UNPASSABLE rock: Orc 12 76,93 and Orc 10
     * 54,61 are coast; Orc 7 121,106 and Orc 10 49,48 are rock.
     */
    boolean battleNetCritterCoastGoal(int x, int y) {
        if (!map.contains(x, y)) {
            return false;
        }
        MapField field = map.field(x, y);
        return field.hasFlag(TileFlag.COAST_ALLOWED)
                && !field.isLandPassable();
    }

    /**
     * Orders a unit to attack another.
     *
     * <p>The unit closes to its attack range first, so this is a move order
     * that turns into a fight, which is how the engine treats it.
     *
     * @return whether the order was accepted
     */
    public boolean orderAttack(Unit unit, Unit target) {
        return orderAttack(unit, target, true);
    }

    private boolean orderAttack(Unit unit, Unit target, boolean clearOfferedTarget) {
        if (unit == null || target == null || unit == target
                || unit.type() == null || !unit.type().canAttack()
                || !unit.isAlive() || !target.isAlive()) {
            return false;
        }
        if (!isEnemyPlayer(unit.player(), target.player()) || !targets.canTarget(unit, target)) {
            return false;
        }
        if (clearOfferedTarget) {
            unit.setOfferedTarget(null);
        }
        // A player's ordinary attack uses the same flush-on command boundary
        // as the position form in BattleNetCombatSystem. If a walk or swing
        // is unbreakable, BNE finishes that committed animation before it
        // promotes the replacement order. Replacing the order immediately
        // left the old animation running against freshly reset attack state;
        // when the animation released, the unit finished the new order and
        // went Still even though the commanded target was alive.
        unit.setSavedOrder(null);
        if (unit.animation().unbreakable()) {
            unit.clearQueuedOrders();
            unit.setPendingAttack(null, null, -1, -1);
            unit.enqueueOrder(new Unit.QueuedOrder(Unit.QueuedOrderKind.ATTACK,
                    target.tileX(), target.tileY(), target, null, null));
            unit.setQueuedReplacementPending(true);
            unit.rememberActionBeforeQueued(unit.order());
            return true;
        }
        projectiles.interruptPendingAttack(unit);
        construction.abandonPendingBuild(unit);
        unit.setPendingAttack(null, null, -1, -1);
        unit.setTarget(target);
        unit.setAttackGoal(target.tileX(), target.tileY());
        // A real click or automatic choice can only name something currently
        // visible, and that target must be released when it leaves sight.
        // Direct simulation callers may deliberately issue an omniscient
        // order, so retain whether visibility was part of this order.
        unit.setAttackRequiresVisibility(isVisibleTo(unit.player(), target));
        unit.setOrder(Unit.Order.ATTACK);
        // COrder::NewActionAttack constructs order state only. The route is
        // PathFinderOutput on CUnit and survives until the new order's first
        // DoActionMove compares its effective goal and invalidates it if
        // necessary. AiHelpMe makes this observable: an unseen temporary
        // unit goal restores a position attack at the same tile before any
        // movement call, so the interrupted route must remain untouched.
        // These three booleans are COrder_Attack::State in this implementation. A new
        // order starts at FIRST_ENTRY; none of the state belonging to the
        // order it replaced can come with it.
        unit.setChasing(false);
        unit.setFighting(false);
        unit.setSwingAtAir(false);
        // A commanded target: the unit does not go looking for a better one.
        // autoAttack and attackBack say otherwise for the ones they pick.
        unit.setAutoTargeting(false);
        // Commanded attacks are action 12 (chase). Stationary action-16 is
        // set only by battleNetAutoAttack after this returns.
        unit.setBattleNetStationaryAttack(false);
        unit.setBattleNetStationaryRecoveryHeld(false);
        unit.setAttackScanSleep(0);
        if (battleNetSequence != null) {
            int attack = idle.battleNetSequenceStart(unit,
                    BattleNetSequence.ATTACK_ANIMATION);
            if (attack >= 0) {
                unit.setBattleNetSequenceOffset(attack);
                // FUN_00452ef0 gives a freshly selected native action three
                // calls before its first binary animation instruction.
                unit.setBattleNetAnimationTimer(3);
            }
            // Melee table 0x27 only: first in-range Attack marker runs
            // 0x4234b0 (SyncRand into unit+0xb). Chasers that step before
            // firing never take that path (Human 5 grunts 1528/1532 keep
            // 0xb=0 while standing 1531 seeds at cycle 6).
            unit.setBattleNetPendingMeleeSyncRand(
                    battleNetMeleeSyncRandType(unit));
        }
        return true;
    }

    private static boolean battleNetMeleeSyncRandType(Unit unit) {
        int code = unit == null || unit.type() == null
                ? -1 : PudUnitTypes.code(unit.type().ident());
        return code == 0 || code == 1 || code == 6 || code == 12
                || code == 14 || code == 15 || code == 25 || code == 44
                || code == 46 || code == 47 || code == 50 || code == 52;
    }

    /**
     * Gives a force member the target chosen by {@code AiHelpMe}.
     *
     * <p>This is a separate entry point because the AI callback adds two
     * pieces to an ordinary target command. The flush is still observed as
     * a queued replacement until the unit's next action, so that pop clears
     * the old order's pathfinder wait; and the saved order is a position
     * attack at the aggressor's current tile, not a clone of the interrupted
     * march.
     */
    /**
     * Spatial help for brothers exactly one tile past their computer react
     * range of the aggressor (XHuman 12 grunt 1481: dist 7, react 6).
     *
     * <p>Broader radius-13 help regressed XHuman 2. Recruiting only
     * {@code distance == react + 1} combatants is the minimal band that
     * includes 1481 without pulling in units already inside react.
     */
    void battleNetSpatialHelpReactPlusOne(Unit attacker, Unit defender) {
        if (attacker == null || defender == null
                || !defender.isAlive() || !attacker.isAlive()
                || !defender.isOnMap() || defender.isDying()
                || defender.type() == null) {
            return;
        }
        // FUN_0040a9d0 installs the aggressor in the struck unit's +0x54
        // offer even for person defenders. Human 13 knight 1500 is Still when
        // axethrower 1506 hits it at c20; the offer seeds its c22 Still scan
        // into chase action 12. Returning immediately for every person left
        // the HP loss without the offer.
        Unit standing = defender.offeredTarget();
        if (standing != null) {
            if (standing.isAlive() && standing.isOnMap()
                    && !standing.isDying()) {
                return;
            }
            defender.setOfferedTarget(null);
        }
        if (!isEnemyPlayer(defender.player(), attacker.player())
                || !targets.canTarget(defender, attacker)
                || isPerson(defender.player()) && attacker.type() != null
                        && attacker.type().building()) {
            return;
        }
        defender.setOfferedTarget(attacker);
        // Person defenders only install the offer here. Recruiting every
        // nearby Still brother on ordinary hits raised Human 13 knight 1493
        // Attack at fixture 21 and XHuman 12 footman 1478 at 28. Person
        // brother help is limited to lethal splash (see
        // battleNetPersonMeleeHelpOnSplash).
        if (isPerson(defender.player())) {
            return;
        }
        final int band = 13;
        int left = Math.max(0, defender.tileX() - band);
        int top = Math.max(0, defender.tileY() - band);
        int right = defender.tileX()
                + Math.max(1, defender.type().tileWidth()) - 1 + band;
        int bottom = defender.tileY()
                + Math.max(1, defender.type().tileHeight()) - 1 + band;
        for (Unit brother : battleNetSpatialUnits) {
            if (brother == defender || brother == attacker
                    || !brother.isAlive() || brother.isDying()
                    || !brother.isOnMap()
                    || brother.player() != defender.player()
                    || brother.type() == null
                    || brother.type().building()
                    || !brother.type().canAttack()
                    || !brother.isAggressive()
                    || !brother.type().gathering().isEmpty()
                    || brother.order() != Unit.Order.STILL
                    || brother.currentAction() != Unit.Order.STILL
                    || brother.target() != null
                    || brother.battleNetPendingHelpAttack() != null
                    || brother.tileX() < left || brother.tileX() > right
                    || brother.tileY() < top || brother.tileY() > bottom
                    || !targets.canTarget(brother, attacker)
                    || !isEnemyPlayer(brother.player(), attacker.player())) {
                continue;
            }
            int react = brother.type().reactRange(false);
            if (react <= 0) {
                continue;
            }
            // Computer: exact react+1 band to the aggressor (XHuman 12 grunt
            // 1481).
            if (battleNetDistance(brother, attacker) != react + 1) {
                continue;
            }
            brother.setBattleNetPendingHelpAttack(attacker);
        }
    }

    /**
     * Person melee Still brothers answer a lethal splash on an ally.
     *
     * <p>XHuman 10 footman 1492 dies to catapult splash; knight 1489 (Data,
     * weapon-range idle only) must queue Attack toward that catapult.
     * Ordinary non-lethal hits must not recruit (Human 13@21 / XHuman 12@28
     * REG when person brothers answered every hit).
     */
    void battleNetPersonMeleeHelpOnSplash(Unit attacker, Unit defender) {
        if (attacker == null || defender == null
                || !isPerson(defender.player())
                || !defender.isAlive() || defender.isDying()
                || !defender.isOnMap() || defender.type() == null
                || !isEnemyPlayer(defender.player(), attacker.player())
                || !targets.canTarget(defender, attacker)
                || attacker.type() != null && attacker.type().building()) {
            return;
        }
        final int band = 13;
        int left = Math.max(0, defender.tileX() - band);
        int top = Math.max(0, defender.tileY() - band);
        int right = defender.tileX()
                + Math.max(1, defender.type().tileWidth()) - 1 + band;
        int bottom = defender.tileY()
                + Math.max(1, defender.type().tileHeight()) - 1 + band;
        for (Unit brother : battleNetSpatialUnits) {
            if (brother == defender || brother == attacker
                    || !brother.isAlive() || brother.isDying()
                    || !brother.isOnMap()
                    || brother.player() != defender.player()
                    || brother.type() == null
                    || brother.type().building()
                    || !brother.type().canAttack()
                    || !brother.isAggressive()
                    || !brother.type().gathering().isEmpty()
                    || brother.type().maxAttackRange() > 1
                    || brother.type().moveType() != UnitType.Movement.LAND
                    || brother.order() != Unit.Order.STILL
                    || brother.currentAction() != Unit.Order.STILL
                    || brother.target() != null
                    || brother.battleNetPendingHelpAttack() != null
                    || brother.tileX() < left || brother.tileX() > right
                    || brother.tileY() < top || brother.tileY() > bottom
                    || !targets.canTarget(brother, attacker)
                    || !isEnemyPlayer(brother.player(), attacker.player())) {
                continue;
            }
            int react = brother.type().reactRange(true);
            if (react <= 0
                    || battleNetDistance(brother, defender) > react) {
                continue;
            }
            brother.setBattleNetPendingHelpAttack(attacker);
        }
    }

    public boolean orderAiHelpAttack(Unit unit, Unit attacker) {
        if (unit == null || attacker == null || unit == attacker
                || !unit.isAlive() || !attacker.isAlive()
                || !unit.type().canAttack()
                || !isEnemyPlayer(unit.player(), attacker.player())
                || !targets.canTarget(unit, attacker)) {
            return false;
        }
        Unit.Order before = unit.order();
        // Position marches deliberately leave pathGoal unset while they own
        // their route. CUnit's PathFinderInput upstream still remembers that
        // position, so reconstruct it before replacing the order. The saved
        // position attack can then make the same comparison on restore:
        // level11o's first help call changes 118,22 to 113,23 and must
        // invalidate, while later calls repeat 113,23 and must preserve.
        boolean ownsPathOutput = unit.isMoving() || unit.pathLength() > 0
                || unit.routeSpent();
        if (ownsPathOutput && unit.pathGoalX() < 0 && unit.pathGoalY() < 0
                && unit.attackMoveX() >= 0 && unit.attackMoveY() >= 0) {
            unit.setPathGoal(unit.attackMoveX(), unit.attackMoveY());
        }
        unit.clearQueuedOrders();
        unit.setSavedOrder(null);
        if (unit.animation().unbreakable()) {
            unit.enqueueOrder(new Unit.QueuedOrder(Unit.QueuedOrderKind.ATTACK,
                    attacker.tileX(), attacker.tileY(), attacker, null, null));
            unit.setQueuedReplacementPending(true);
        } else if (!orderAttack(unit, attacker)) {
            return false;
        }
        unit.rememberActionBeforeQueued(before);
        unit.setSavedOrder(Unit.Order.ATTACK_MOVE);
        unit.setSavedAttackMove(attacker.tileX(), attacker.tileY());
        return true;
    }

    /** Whether {@code candidate} has the lower upstream threat bill. */
    public boolean aiPrefersTarget(Unit observer, Unit candidate, Unit incumbent) {
        if (observer == null || candidate == null) {
            return false;
        }
        if (incumbent == null) {
            return true;
        }
        if (simplifiedAutoTargeting) {
            return targets.targetPriority(observer, candidate) > targets.targetPriority(observer, incumbent);
        }
        return targets.targetCost(observer, candidate, observer.distanceTo(candidate))
                < targets.targetCost(observer, incumbent, observer.distanceTo(incumbent));
    }

    /**
     * Whether two units are on opposing sides.
     *
     * <p>Slot identity only, for now. Real diplomacy has alliances, shared
     * vision and neutral players, and arrives with the player system; until
     * then everything not yours is hostile, which is right for a skirmish and
     * wrong for a rescue mission.
     */
    public static boolean isEnemy(Unit unit, Unit other) {
        return unit.player() != other.player();
    }

    /**
     * Whether one player counts another as an enemy.
     *
     * <p>Read off its own table rather than derived from the alliance one,
     * because the two questions have three answers between them. "Not
     * allied" used to mean "enemy", and the pair that reading cannot say --
     * not allied and not at war, which is a computer player and the
     * rescue-passive prisoner its guards stand over -- is the pair the
     * first orc expansion mission is built on. The guards killed the caged
     * hero in 54 seconds and the mission lost itself. Upstream keeps
     * {@code CPlayer::Enemy} beside {@code CPlayer::Allied} and never
     * derives one from the other.
     *
     * <p>Alliance and enmity are stored per direction because they are
     * declared per direction: a player can offer an alliance and not have
     * it returned, and until it is returned only one side holds fire.
     * Nobody starts at war with the neutral player -- gold mines, oil
     * patches and critters belong to it, and a worker must not attack the
     * mine it was sent to work. {@code enemyByType} arranges that by
     * marking nobody an enemy of NEUTRAL, rather than a carve-out here.
     */
    public boolean isEnemyPlayer(int player, int other) {
        if (player == other) {
            return false;
        }
        return enemy[clampPlayer(player)][clampPlayer(other)];
    }

    /**
     * Declares that {@code player} treats {@code other} as an ally, or stops.
     *
     * <p>The two-state entry point, and every caller outside
     * {@code establishDiplomacy} means it two-state: a fixture arranging a
     * war or a truce, and the campaign scripts' own alliance changes. So it
     * writes both tables -- an offered alliance ends the war, a withdrawn
     * one starts it. The three-state standing that upstream's diplomacy menu
     * can reach exists only at {@code establishDiplomacy}, which writes the
     * tables directly; this implementation has no such menu (Alt-D is recorded
     * unbound in focused tests).
     */
    public void setAllied(int player, int other, boolean ally) {
        if (player == other) {
            return;
        }
        allied[clampPlayer(player)][clampPlayer(other)] = ally;
        enemy[clampPlayer(player)][clampPlayer(other)] = !ally;
    }

    public boolean isAllied(int player, int other) {
        return player == other || allied[clampPlayer(player)][clampPlayer(other)];
    }

    /** Whether {@code controller} may issue orders to {@code owner}'s units. */
    public boolean canControl(int controller, int owner) {
        if (controller == owner) {
            return true;
        }
        if (controller < 0 || controller >= Player.MAX || owner < 0 || owner >= Player.MAX) {
            return false;
        }
        return (departedControlMasks[owner] & (1 << controller)) != 0;
    }

    /** Installs the synchronized control handoff carried by a QUIT command. */
    public void setDepartedControlMask(int owner, int mask) {
        if (owner < 0 || owner >= Player.MAX) {
            return;
        }
        departedControlMasks[owner] = mask & ((1 << Player.MAX) - 1);
    }

    /** The exact handoff mask, included in sync hashes and diagnostics. */
    public int departedControlMask(int owner) {
        return owner < 0 || owner >= Player.MAX ? 0 : departedControlMasks[owner];
    }

    /** Whether {@code player} sees through {@code other}'s eyes. */
    public boolean sharesVisionWith(int player, int other) {
        return player == other || canControl(player, other)
                || sharedVision[clampPlayer(player)][clampPlayer(other)];
    }

    /** Declares that {@code player} shows {@code other} what it can see. */
    public void setSharedVision(int player, int other, boolean shared) {
        if (player == other) {
            return;
        }
        sharedVision[clampPlayer(player)][clampPlayer(other)] = shared;
    }

    private static int clampPlayer(int player) {
        return Math.max(0, Math.min(Player.MAX - 1, player));
    }

    /**
     * Whether a player can see a square, their own eyes or an ally's.
     *
     * <p>Shared vision is what makes an alliance worth having, and it is
     * asked here rather than by copying one player's fog into another's: the
     * fog is reference counted, and merging it would lose track of whose
     * units are lighting what.
     */
    public boolean isVisibleTo(int player, int x, int y) {
        if (fog.isVisible(player, x, y)) {
            return true;
        }
        for (int other = 0; other < Player.MAX; other++) {
            if (other != player && sharesVisionWith(player, other)
                    && fog.isVisible(other, x, y)) {
                return true;
            }
        }
        return false;
    }

    public boolean isVisibleTo(int player, Unit unit) {
        if (unit == null || !unit.isAlive() || !unit.isOnMap()) {
            return false;
        }
        // CUnit::IsVisible: the unit's own per-player
        // count, then the same count through everyone sharing vision. Not
        // the fog under the unit: the count carries UnitCountSeen's phantom
        // watcher, so a unit seen once stays visible one sight change after
        // its last real watcher leaves, and reading the tiles instead is
        // exactly the level13h divergence.
        if (unit.visCount(player) > 0) {
            return true;
        }
        for (int other = 0; other < Player.MAX; other++) {
            if (other != player && sharesVisionWith(player, other)
                    && unit.visCount(other) > 0) {
                return true;
            }
        }
        return false;
    }

    /** Whether this player or someone sharing vision detects a square. */
    private boolean isDetectedTo(int player, int x, int y) {
        if (fog.isDetected(player, x, y)) {
            return true;
        }
        for (int other = 0; other < Player.MAX; other++) {
            if (other != player && sharesVisionWith(player, other)
                    && fog.isDetected(other, x, y)) {
                return true;
            }
        }
        return false;
    }

    /** Who counts whom as an ally, and who shows whom their map. */
    private final boolean[][] allied = new boolean[Player.MAX][Player.MAX];

    /**
     * Who counts whom as an enemy, kept apart from {@link #allied} because
     * upstream keeps {@code CPlayer::Enemy} apart from {@code CPlayer::Allied}
     * and the campaign depends on the pair a single table cannot say: neither.
     *
     * <p>This used to be derived -- "not allied" meant "enemy" -- and that
     * reading killed a mission. A computer player and a rescue-passive slot
     * are not allied and not enemies: the guards stand over the prisoner and
     * neither side swings. Derived from one table, "not allied" put them at
     * war, so on the first mission of the orc expansion the four guards
     * around the caged Beast Cry cut the prisoner down from 240 hit points to
     * dead in 54 seconds with nobody touching the controls, and the mission
     * declared its own DEFEAT -- the hero both defeat triggers watch was
     * gone before a player could reach it.
     */
    private final boolean[][] enemy = new boolean[Player.MAX][Player.MAX];

    private final boolean[][] sharedVision = new boolean[Player.MAX][Player.MAX];

    /** Turns a unit's standing spell on or off. */
    public boolean setAutoCast(Unit unit, String spellIdent) {
        if (unit == null || !unit.isAlive() || !unit.isCaster()) {
            return false;
        }
        if (spellIdent != null && spellSet.get(spellIdent) == null) {
            return false;
        }
        unit.setAutoCast(spellIdent);
        return true;
    }

    /** Points a building's output at a square. */
    public boolean setRallyPoint(Unit building, int x, int y) {
        if (building == null || !building.isAlive() || !building.type().building()
                || !map.contains(x, y)) {
            return false;
        }
        building.setRallyPoint(x, y);
        return true;
    }

    /**
     * Sails a startup transport to the shoreline order point BNE rewrote for
     * its hall. The hall is normally well inland, so the ordinary resource
     * walk's one-tile range cannot represent this action.
     */
    void stepBattleNetTransportToHall(Unit transport, Unit hall) {
        if (!hall.isAlive()) {
            transport.setTarget(null);
            transport.setOrder(Unit.Order.STILL);
            return;
        }
        int goalX = transport.orderTargetX();
        int goalY = transport.orderTargetY();
        if (goalX < 0 || goalY < 0) {
            int[] orderPoint = battleNetTransportOrderPoint(transport, hall);
            goalX = orderPoint[0];
            goalY = orderPoint[1];
            transport.setOrderTarget(goalX, goalY);
        }
        // Cold-commit leaves the last leg's pixels draining after the tile
        // snap. Finishing only when !isMoving at the top of the visit left
        // Orc 14's startup transports on HARVEST through fixture 18 while
        // native had already gone Still on the shoreline order point.
        if (transport.pathLength() == 0) {
            if (transport.isMoving()) {
                movement.walkPixels(transport);
            }
            if (!transport.isMoving()) {
                if (transport.tileX() == goalX && transport.tileY() == goalY) {
                    transport.setTarget(null);
                    transport.setOrder(Unit.Order.STILL);
                    // Shoreline Still after harvest: mark AE30 already taken
                    // so the next fly-timer expiry does not invent a draw
                    // (Orc 14 transports 75/82 at world 21).
                    transport.setBattleNetTransportFlyDrawn(true);
                    return;
                }
                int heading = -1;
                long best = battleNetTransportDistance(
                        transport.tileX(), transport.tileY(), goalX, goalY);
                // Native's resource-family approach favours the direct compass
                // heading (north, then clockwise on a tie) and repeats it once
                // each movement animation completes. That makes the shoreline,
                // rather than a land building's unreachable footprint, the end.
                int stride = battleNetMovementStride(transport);
                for (int candidate = 0; candidate < Direction.COUNT; candidate++) {
                    int x = transport.tileX() + Direction.deltaX(candidate) * stride;
                    int y = transport.tileY() + Direction.deltaY(candidate) * stride;
                    boolean enter = canEnterBattleNetTransportAnchor(transport, x, y);
                    if (TRACE_MOVING != null && transport.id() == TRACE_MOVING_ID) {
                        System.err.printf("JBNETRANSPORT cycle=%d unit=%d goal=%d,%d"
                                        + " stride=%d candidate=%d at=%d,%d enter=%d%n",
                                cycle, transport.id(), goalX, goalY,
                                stride, candidate, x, y,
                                enter ? 1 : 0);
                    }
                    if (!enter) {
                        continue;
                    }
                    long distance = battleNetTransportDistance(x, y, goalX, goalY);
                    if (distance < best) {
                        best = distance;
                        heading = candidate;
                    }
                }
                if (heading < 0) {
                    transport.setTarget(null);
                    transport.setOrder(Unit.Order.STILL);
                    transport.setBattleNetTransportFlyDrawn(true);
                    return;
                }
                transport.setPath(new PathFinder.Path(
                        PathFinder.Result.FOUND, new int[] {heading}));
                transport.setPathGoal(-1, -1);
            } else {
                // Still draining the leg that snapped onto the shoreline.
                return;
            }
        }
        Unit.Order saved = transport.order();
        transport.setOrder(Unit.Order.MOVE);
        movement.stepMove(transport);
        if (transport.order() != Unit.Order.DYING
                && transport.order() != Unit.Order.STILL) {
            transport.setOrder(saved);
        } else if (transport.order() == Unit.Order.MOVE) {
            transport.setOrder(saved);
        }
    }

    private static long battleNetTransportDistance(
            int x, int y, int goalX, int goalY) {
        long dx = goalX - x;
        long dy = goalY - y;
        return dx * dx + dy * dy;
    }

    /**
     * Rewrites the transport's hall order X/Y the way {@code 0x4381d0} does.
     *
     * <p>GiveOrder {@code 0x4513d0} stores the hall's top-left. The rewrite
     * walks the {@code 0x429f10} Bresenham ray from that hall square toward
     * the ship; while the next square is not open water it advances, and when
     * the next square is open water it keeps the previous square as order
     * X/Y. Native's action mask {@code 0x0903} treats coast-edge squares
     * (fixture {@code 0x0482}) as blocked even though transports may later
     * sit on {@code COAST_ALLOWED} via {@code canEnter}. Using
     * {@code canEnter} here used to stop on the first coast tile and aim
     * Human 4 at the wrong even-grid double-step.</p>
     *
     * <p>Orc 4 lands on (17,37) -- last blocked cell before open water
     * (17,38) -- so the first step is a single north-west tile to (17,39).
     * Human 4 lands on (70,87) and first-steps north-east to (69,87).</p>
     */
    private int[] battleNetTransportOrderPoint(Unit ship, Unit hall) {
        int goalX = hall.tileX();
        int goalY = hall.tileY();
        int shipX = ship.tileX();
        int shipY = ship.tileY();
        if (goalX == shipX && goalY == shipY) {
            return new int[] {goalX, goalY};
        }
        int x = goalX;
        int y = goalY;
        int prevX = goalX;
        int prevY = goalY;
        int rawDx = shipX - goalX;
        int rawDy = shipY - goalY;
        int absoluteX = Math.abs(rawDx);
        int absoluteY = Math.abs(rawDy);
        boolean xMajor = absoluteX >= absoluteY;
        int major = xMajor ? absoluteX : absoluteY;
        int minor = xMajor ? absoluteY : absoluteX;
        int majorSign = Integer.signum(xMajor ? rawDx : rawDy);
        int minorSign = Integer.signum(xMajor ? rawDy : rawDx);
        int error = major >> 1;
        if (error == 0) {
            error = 1;
        }
        while (x != shipX || y != shipY) {
            int minorStep = 0;
            error -= minor;
            if (error < 1) {
                minorStep = minorSign;
                error += major;
            }
            int stepDx = xMajor ? majorSign : minorStep;
            int stepDy = xMajor ? minorStep : majorSign;
            int nextX = x + stepDx;
            int nextY = y + stepDy;
            if (battleNetTransportRewriteOpenWater(nextX, nextY)) {
                return new int[] {prevX, prevY};
            }
            prevX = nextX;
            prevY = nextY;
            x = nextX;
            y = nextY;
        }
        return new int[] {prevX, prevY};
    }

    /**
     * Open water for the action-30 order rewrite -- not coast, not land.
     * Ports the free side of native mask {@code 0x0903} on water tiles.
     */
    private boolean battleNetTransportRewriteOpenWater(int x, int y) {
        if (!map.contains(x, y)) {
            return false;
        }
        long flags = map.field(x, y).flags();
        return (flags & TileFlag.WATER_ALLOWED) != 0
                && (flags & TileFlag.COAST_ALLOWED) == 0
                && (flags & TileFlag.LAND_ALLOWED) == 0;
    }

    /**
     * How far a worker will look for more of what it was gathering.
     *
     * <p>Eight squares, as {@code FindAnotherResource} uses. Far enough to
     * carry on through a wood, close enough that it does not wander off across
     * the map when the wood is finished.
     */
    static final int ANOTHER_RESOURCE_RANGE = 8;

    /**
     * How far a woodcutter looks when the square it was walking to turns out
     * to have no tree on it. Sixteen, as {@code MoveToResource_Terrain} uses.
     */
    static final int LOST_WOOD_RANGE = 16;

    /**
     * How far a woodcutter looks when it cannot reach the tree it was told
     * about. Upstream passes 9999 here, which on any Warcraft II map is the
     * whole of it: rather than give up, the worker walks to the nearest tree
     * it can get at, however far that is.
     */
    static final int ANY_WOOD_RANGE = 9999;

    /**
     * How long a woodcutter stands still after failing to plan a route to its
     * tree, before trying again.
     *
     * <p>{@code unit.Wait = 10} on the {@code PF_UNREACHABLE} arm of
     * {@code MoveToResource_Terrain}. The number matters less than the fact
     * that there is one: a worker that cannot plan a route this cycle plans
     * again shortly, because the usual reason is another worker standing in
     * the way and workers move.
     */
    static final int UNREACHABLE_WAIT = 10;

    /**
     * How many times a worker looks at a blocked site before giving up.
     *
     * <p>{@code State_StartBuilding_Failed} minus {@code State_NearOfLocation}
     * which is nine.
     */
    static final int BUILD_TRIES = 9;

    /** First single-tile step of BNE's integer line from {@code from} to {@code to}. */
    static int[] battleNetBresenhamFirstStep(
            int fromX, int fromY, int toX, int toY) {
        int dx = toX - fromX;
        int dy = toY - fromY;
        if (dx == 0 && dy == 0) {
            return new int[] {fromX, fromY};
        }
        int absoluteX = Math.abs(dx);
        int absoluteY = Math.abs(dy);
        boolean xMajor = absoluteX >= absoluteY;
        int major = xMajor ? absoluteX : absoluteY;
        int minor = xMajor ? absoluteY : absoluteX;
        int majorSign = Integer.signum(xMajor ? dx : dy);
        int minorSign = Integer.signum(xMajor ? dy : dx);
        int error = major >> 1;
        if (error == 0) {
            error = 1;
        }
        int minorStep = 0;
        error -= minor;
        if (error < 1) {
            minorStep = minorSign;
        }
        int stepDx = xMajor ? majorSign : minorStep;
        int stepDy = xMajor ? minorStep : majorSign;
        return new int[] {fromX + stepDx, fromY + stepDy};
    }

    /**
     * The eight steps the fill takes, in {@code TerrainTraversal::PushNeighboor}'s
     * order: north, west, east, south, then the four diagonals.
     *
     * <p>Straights before diagonals, not the compass order {@link Direction}
     * counts in. The fill answers with the first tree it takes off the queue,
     * so among trees the same number of steps away the order decides which one
     * a worker is sent to, and two machines running the same game have to send
     * it to the same one.
     */
    static final int[] FILL_NEIGHBOURS = {
        0, -1, -1, 0, 1, 0, 0, 1, -1, -1, 1, -1, -1, 1, 1, 1
    };

    /**
     * The nearest tree a unit could actually walk up to, searched outward from
     * a square.
     *
     * <p>Implements {@code FindTerrainType},
     * over the breadth-first {@code TerrainTraversal} of
     * Every place upstream
     * re-finds wood -- coming out of a depot, losing the square mid-walk,
     * being told to chop something it cannot reach, running out of trees --
     * calls this one function, so this is the only search in this file that
     * looks for a tree.
     *
     * <p>The order of the two tests inside the visit is the whole point.
     * A square is tested for the forest flag <em>before</em> it is tested for
     * being walkable, and the fill only spreads through squares that are
     * walkable, so the first tree it meets is one with walkable ground beside
     * it: somewhere a worker can stand and chop. A tree more than one square
     * inside a stand is never returned, because reaching it would mean walking
     * through the trees around it.
     *
     * <p>What this replaced was a square box scan for the nearest square
     * carrying the forest flag. A box scan cannot tell an edge tree from one
     * buried in the middle of a wood, so a worker looking for its next tree
     * was regularly handed one it had no way of reaching; the route came back
     * empty, the order fell to STILL, and the peasant stood in the clearing
     * for the rest of the game holding nothing.
     *
     * <p>Departs from upstream in one respect, deliberately.
     * {@code TerrainFinder::Visit} treats a square the owning player has never
     * explored as a dead end, and this does not. For the two short searches --
     * eight and ten squares out from ground the worker is already standing on
     * -- the difference is unobservable, because a worker lights the ground it
     * walks. It is real on the last-resort search, which has no range: a
     * worker whose own wood is finished may be sent to one across ground its
     * owner has never scouted, where upstream would have stood it down.
     *
     * @param range how many steps the fill may spread, the starting square
     *              counting as the first
     * @return the tile of the tree, or {@code null} if there is none in range
     */
    public int[] findTerrainType(Unit unit, int startX, int startY, int range) {
        if (!map.contains(startX, startY)) {
            startX = unit.tileX();
            startY = unit.tileY();
        }
        if (!map.contains(startX, startY)) {
            return null;
        }
        long mask = unit.movementMask();
        // Upstream clears the three unit-occupancy bits out of the movement
        // mask before it starts the fill. Another worker standing in the way
        // is not a reason to call a wood unreachable, it is a reason to walk
        // round it -- and units move, so a search that respected them would
        // give a different answer on the next cycle.
        long blocking = unit.blockingFlags()
                & ~(TileFlag.LAND_UNIT | TileFlag.AIR_UNIT | TileFlag.SEA_UNIT);

        int width = map.width();
        int[] steps = new int[width * map.height()];
        int[] queue = new int[steps.length];
        int head = 0;
        int tail = 0;
        queue[tail++] = startX + startY * width;
        steps[startX + startY * width] = 1;
        while (head < tail) {
            int at = queue[head++];
            int x = at % width;
            int y = at / width;
            MapField field = map.field(x, y);
            if (field.isForest()
                    && (!battleNetClaimedWood.containsKey(at)
                        || battleNetClaimedWood.get(at) == unit)) {
                return new int[] {x, y};
            }
            if (!map.isFootprintFree(x, y, 1, 1, mask, blocking) || steps[at] > range) {
                continue;
            }
            for (int i = 0; i < FILL_NEIGHBOURS.length; i += 2) {
                int nx = x + FILL_NEIGHBOURS[i];
                int ny = y + FILL_NEIGHBOURS[i + 1];
                if (!map.contains(nx, ny) || steps[nx + ny * width] != 0) {
                    continue;
                }
                steps[nx + ny * width] = steps[at] + 1;
                queue[tail++] = nx + ny * width;
            }
        }
        return null;
    }

    boolean battleNetGoldDepotNear(Unit mine) {
        int minX = mine.tileX() - 5;
        int minY = mine.tileY() - 5;
        int maxX = mine.tileX() + 7;
        int maxY = mine.tileY() + 7;
        for (Unit unit : units) {
            if (!unit.isAlive() || !unit.isOnMap() || unit.type() == null
                    || !unit.type().stores().contains(UnitType.Resource.GOLD)) {
                continue;
            }
            int right = unit.tileX() + Math.max(1, unit.type().tileWidth()) - 1;
            int bottom = unit.tileY() + Math.max(1, unit.type().tileHeight()) - 1;
            if (right >= minX && unit.tileX() <= maxX
                    && bottom >= minY && unit.tileY() <= maxY) {
                return true;
            }
        }
        return false;
    }

    /** Reconstructs BNE's fixed terrain-component label for one worker. */
    boolean[] battleNetConnectivityCell(Unit worker) {
        int width = map.width();
        int height = map.height();
        boolean[] connected = new boolean[width * height];
        int[] queue = new int[connected.length];
        int head = 0;
        int tail = 0;
        int start = worker.tileX() + worker.tileY() * width;
        connected[start] = true;
        queue[tail++] = start;
        while (head < tail) {
            int at = queue[head++];
            int x = at % width;
            int y = at / width;
            for (int i = 0; i < FILL_NEIGHBOURS.length; i += 2) {
                int nx = x + FILL_NEIGHBOURS[i];
                int ny = y + FILL_NEIGHBOURS[i + 1];
                if (!map.contains(nx, ny)) {
                    continue;
                }
                int next = nx + ny * width;
                if (connected[next] || !battleNetTerrainPassable(worker, nx, ny)) {
                    continue;
                }
                connected[next] = true;
                queue[tail++] = next;
            }
        }
        return connected;
    }

    boolean battleNetTerrainPassable(Unit worker, int x, int y) {
        MapField field = map.field(x, y);
        if (worker.type().airUnit()) {
            return true;
        }
        if (worker.type().seaUnit()) {
            return field.isWaterPassable();
        }
        return field.isLandPassable();
    }

    /**
     * The exact point BNE derives from a resource-order unit target.
     *
     * <p>Resource-bearing map objects take {@code 0x41f430}, not the
     * building-corner helper. Starting from the target's top-left it projects
     * each coordinate onto the near side of the footprint only when the
     * mover lies below or to the right. Human 8 proves the asymmetric rule:
     * a peasant at (69,73) approaching the 3-by-3 mine at (80,61) receives
     * (80,63), not the top-left and not the geometrically nearest free tile.</p>
     *
     * <p>Ordinary building targets take {@code 0x41f4d0}. It first retains
     * the top-left when that point shares the mover's map component, then
     * considers bottom-right, top-right, bottom-left and top-left. The Java
     * map does not retain retail's component-label array, so the terrain
     * component reconstructed for BNE's AI placement is reused here.</p>
     */
    int[] battleNetApproachPoint(Unit worker, Unit target) {
        int left = target.tileX();
        int top = target.tileY();
        int width = Math.max(1, target.type().tileWidth());
        int height = Math.max(1, target.type().tileHeight());
        int right = left + width - 1;
        int bottom = top + height - 1;
        if (target.type().givesResource() != null) {
            return battleNetNearEdgePoint(worker, left, top, right, bottom, width, height);
        }

        boolean[] connected = battleNetConnectivityCell(worker);
        if (map.contains(left, top)
                && connected[left + top * map.width()]) {
            return new int[] {left, top};
        }
        int[][] candidates = {
            {right, bottom}, {right, top}, {left, bottom}, {left, top},
        };
        for (int[] point : candidates) {
            if (map.contains(point[0], point[1])
                    && connected[point[0] + point[1] * map.width()]) {
                return point;
            }
        }
        return new int[] {left, top};
    }

    /**
     * MoveToDepot leftover dest-arm point ({@code 0x41f430}).
     *
     * <p>An empty send-home leftover-lands on the repair ring (26,21 from
     * 25,18) then dest-arms onto this near-edge hall tile (25,22), not
     * the connected origin the walk used to aim at.</p>
     */
    int[] battleNetDepotEntryPoint(Unit worker, Unit depot) {
        int left = depot.tileX();
        int top = depot.tileY();
        int width = Math.max(1, depot.type().tileWidth());
        int height = Math.max(1, depot.type().tileHeight());
        return battleNetNearEdgePoint(worker, left, top,
                left + width - 1, top + height - 1, width, height);
    }

    private static int[] battleNetNearEdgePoint(Unit worker, int left, int top,
            int right, int bottom, int width, int height) {
        int x = left;
        int y = top;
        if (left < worker.tileX()) {
            x = worker.tileX() < right ? left + width / 2 : right;
        }
        if (top < worker.tileY()) {
            y = worker.tileY() < bottom ? top + height / 2 : bottom;
        }
        return new int[] {x, y};
    }

    /**
     * GiveOrder 27's stand point: project the mover onto the building's
     * one-tile ring, then slide a north approach to the north-east corner
     * and an east approach to the south-east corner.
     *
     * <p>Walking the connected origin used to park Orc 1's peon on 22,21
     * while native stood on 26,21, the east-side peon on 45,55 while
     * native stood on 45,59, and grunt 1592 on 22,21 while native stood
     * on 21,23. A bottom-right dest then walked the grunt past 21,23.
     */
    int[] battleNetRepairApproachPoint(Unit worker, Unit target) {
        int left = target.tileX();
        int top = target.tileY();
        int width = Math.max(1, target.type().tileWidth());
        int height = Math.max(1, target.type().tileHeight());
        int right = left + width - 1;
        int bottom = top + height - 1;
        int x = Math.max(left - 1, Math.min(right + 1, worker.tileX()));
        int y = Math.max(top - 1, Math.min(bottom + 1, worker.tileY()));
        if (x >= left && x <= right && y >= top && y <= bottom) {
            if (worker.tileX() < left) {
                x = left - 1;
            } else if (worker.tileX() > right) {
                x = right + 1;
            } else if (worker.tileY() < top) {
                y = top - 1;
            } else {
                y = bottom + 1;
            }
        }
        if (y == top - 1) {
            x = right + 1;
        } else if (x == right + 1) {
            y = bottom;
        }
        if (!map.contains(x, y) || !canEnter(worker, x, y)) {
            return new int[] {right, bottom};
        }
        return new int[] {x, y};
    }

    /** Returns the live native {@code unit+0x1c & 2} movement-grid state. */
    /**
     * How long a worker stands down after handing a build job back.
     *
     * <p>XHuman 2 peon 1560 hands back on fixture 52 with three on its own
     * timer, reads 3, 2 and 1 across 52, 53 and 54, and takes a fresh build
     * order on 55.
     */
    static final int BATTLE_NET_HAND_BACK_STAND_DOWN = 3;

    /** Whether this worker is still standing down from a build it gave back. */
    public boolean battleNetStandingDownFromBuild(Unit unit) {
        return cycle - unit.battleNetBuildHandBackCycle()
                < BATTLE_NET_HAND_BACK_STAND_DOWN;
    }

    int battleNetMovementStride(Unit unit) {
        return unit != null && unit.battleNetDoubleStep() ? 2 : 1;
    }

    /**
     * Whether an unarmed scout flyer is already on the even-lattice stop
     * beside an odd dest.
     *
     * <p>Human 12 zeppelin 1559 lands on 84,10 at fixture 62. Native stays
     * Patrol through 81 and is Still at 82. Exact even dests (1570 on 50,4)
     * stand down when residual settles.</p>
     */
    boolean battleNetScoutOddDestEvenStop(Unit unit) {
        if (unit == null || !unit.battleNetScoutPatrol()
                || unit.type() == null || unit.type().canAttack()
                || unit.type().moveType() != UnitType.Movement.FLY
                || unit.pathLength() != 0) {
            return false;
        }
        int destX = unit.orderTargetX();
        int destY = unit.orderTargetY();
        if (destX < 0 || destY < 0) {
            return false;
        }
        if (destX % 2 == 0 && destY % 2 == 0) {
            return false;
        }
        if ((unit.tileX() & 1) != 0 || (unit.tileY() & 1) != 0) {
            return false;
        }
        return Math.max(Math.abs(unit.tileX() - destX),
                Math.abs(unit.tileY() - destY)) == 1;
    }

    /**
     * Whether a stride-two mover is on the even neighbour of an odd click.
     *
     * <p>The pathfinder floors a stride-two goal ({@code &= ~1}). A
     * three-tile west or north click then still has a second heading that
     * walks past the odd dest. Native parks on the even square facing the
     * click. Campaign dest-arm still owns scout-patrol flyers.</p>
     */
    boolean battleNetStrideOddDestEvenStop(Unit unit) {
        if (unit == null || !unit.battleNetDoubleStep()
                || unit.type() == null) {
            return false;
        }
        // Campaign dest-arm still owns a scout-patrol flyer. A player click
        // is Move, and those balloons used to keep the leftover west/north
        // stride because dest-arm never sees the Move action.
        if (unit.battleNetScoutPatrol() && unit.order() != Unit.Order.MOVE) {
            return false;
        }
        int destX = unit.orderTargetX();
        int destY = unit.orderTargetY();
        if (destX < 0 || destY < 0) {
            return false;
        }
        if (destX % 2 == 0 && destY % 2 == 0) {
            return false;
        }
        if ((unit.tileX() & 1) != 0 || (unit.tileY() & 1) != 0) {
            return false;
        }
        return Math.max(Math.abs(unit.tileX() - destX),
                Math.abs(unit.tileY() - destY)) == 1;
    }

    /**
     * Repacks a free wood-ray prefix as diagonal-preferring steps toward a
     * blocked order point.
     *
     * <p>PathFinder stores headings as a stack (last index first). Free tips
     * are footprint-free cells within Chebyshev 1 of the blocked goal. Among
     * free signum paths to those tips, native prefers the most diagonal
     * steps -- Human 5 peasant 1512's farm order 31,106 has free tips 32,106
     * (SW,W) and 32,107 (SW,SW); the sealed route is {@code 5 5} onto 32,107,
     * not Bresenham {@code 5 6} onto 32,106. Open free rays that already end
     * short of a distant forest (XHuman 2 peon 1530 path 707) keep the
     * captured prefix when no tip path is longer-diagonal than that prefix.
     * </p>
     */
    PathFinder.Path battleNetDiagonalPreferPath(
            Unit worker, PathFinder.Path captured, int goalX, int goalY) {
        int startX = worker.tileX();
        int startY = worker.tileY();
        java.util.List<int[]> tips = new java.util.ArrayList<>();
        for (int ty = goalY - 1; ty <= goalY + 1; ty++) {
            for (int tx = goalX - 1; tx <= goalX + 1; tx++) {
                if (!map.contains(tx, ty)) {
                    continue;
                }
                if (map.isFootprintFree(tx, ty, 1, 1,
                        worker.movementMask(), worker.blockingFlags())) {
                    tips.add(new int[] {tx, ty});
                }
            }
        }
        java.util.List<Integer> bestForward = null;
        int bestDiagonals = -1;
        int bestLength = Integer.MAX_VALUE;
        for (int[] tip : tips) {
            int endX = tip[0];
            int endY = tip[1];
            int x = startX;
            int y = startY;
            java.util.List<Integer> forward = new java.util.ArrayList<>();
            int diagonals = 0;
            boolean blocked = false;
            while ((x != endX || y != endY) && forward.size() < 20) {
                int dx = Integer.signum(endX - x);
                int dy = Integer.signum(endY - y);
                if (dx == 0 && dy == 0) {
                    break;
                }
                int nx = x + dx;
                int ny = y + dy;
                if (!map.isFootprintFree(nx, ny, 1, 1,
                        worker.movementMask(), worker.blockingFlags())) {
                    blocked = true;
                    break;
                }
                if (dx != 0 && dy != 0) {
                    diagonals++;
                }
                forward.add(Direction.fromDelta(dx, dy));
                x = nx;
                y = ny;
            }
            if (blocked || forward.isEmpty() || x != endX || y != endY) {
                continue;
            }
            if (diagonals > bestDiagonals
                    || (diagonals == bestDiagonals
                            && forward.size() < bestLength)) {
                bestDiagonals = diagonals;
                bestLength = forward.size();
                bestForward = forward;
            }
        }
        if (bestForward == null || bestForward.isEmpty()) {
            return captured;
        }
        int[] reversed = new int[bestForward.size()];
        for (int i = 0; i < bestForward.size(); i++) {
            reversed[bestForward.size() - 1 - i] = bestForward.get(i);
        }
        return new PathFinder.Path(PathFinder.Result.FOUND, reversed);
    }

    /**
     * Signum-pack the free path to the captured free-ray endpoint.
     * Falls back to the captured Bresenham prefix if a diagonal step is
     * blocked (original forest free-prefix behaviour).
     */
    PathFinder.Path battleNetDiagonalPreferToEndpoint(
            Unit worker, PathFinder.Path captured) {
        int x = worker.tileX();
        int y = worker.tileY();
        for (int index = captured.length() - 1; index >= 0; index--) {
            int heading = captured.headings()[index];
            x += Direction.deltaX(heading);
            y += Direction.deltaY(heading);
        }
        int endX = x;
        int endY = y;
        x = worker.tileX();
        y = worker.tileY();
        java.util.List<Integer> forward = new java.util.ArrayList<>();
        while ((x != endX || y != endY) && forward.size() < 20) {
            int dx = Integer.signum(endX - x);
            int dy = Integer.signum(endY - y);
            if (dx == 0 && dy == 0) {
                break;
            }
            int nx = x + dx;
            int ny = y + dy;
            if (!map.isFootprintFree(nx, ny, 1, 1,
                    worker.movementMask(), worker.blockingFlags())) {
                return captured;
            }
            forward.add(Direction.fromDelta(dx, dy));
            x = nx;
            y = ny;
        }
        if (forward.isEmpty()) {
            return captured;
        }
        int[] reversed = new int[forward.size()];
        for (int i = 0; i < forward.size(); i++) {
            reversed[forward.size() - 1 - i] = forward.get(i);
        }
        return new PathFinder.Path(PathFinder.Result.FOUND, reversed);
    }

    /**
     * Forest free-ray diagonal pack with a tip upgrade beside the tree.
     *
     * <p>Endpoint packing alone (XHuman 8 peon 1510 Bresenham free ray ending
     * at 8,65) yields NE,NE,E,E and drifts pure east onto 7,65 at fixture 38.
     * Native's sealed route is 01 01 01 02 02 -- three north-east steps onto
     * 7,64 -- the free tip of tree 9,65 with the most diagonal steps. When the
     * free-ray endpoint already sits within Chebyshev 1 of the tree, upgrade
     * to that tip-search only if it strictly beats the endpoint pack on
     * diagonal count. Distant free rays keep the endpoint pack so a long
     * mid-corridor prefix (XHuman 2 path 707) is not replaced by a greedy
     * tip walk.</p>
     *
     * <p>Wall-follow detours that already end beside the tree must not be
     * tip-upgraded. Human 8 peasant 1507's pathfinder stores
     * {@code 333222223544} ending 86,82 (Chebyshev 8 from 78,75, length 12);
     * a tip search rewrote that into greedy {@code 3333334} and fourth-stepped
     * SE onto 82,79 while native keeps E onto 82,78. Free rays have length
     * equal to the Chebyshev span of their tip -- only those may upgrade.</p>
     */
    PathFinder.Path battleNetForestDiagonalPrefer(
            Unit worker, PathFinder.Path captured, int goalX, int goalY) {
        PathFinder.Path endpointPack = battleNetDiagonalPreferToEndpoint(
                worker, captured);
        int endX = worker.tileX();
        int endY = worker.tileY();
        for (int index = endpointPack.length() - 1; index >= 0; index--) {
            int heading = endpointPack.headings()[index];
            endX += Direction.deltaX(heading);
            endY += Direction.deltaY(heading);
        }
        if (Math.max(Math.abs(endX - goalX), Math.abs(endY - goalY)) > 1) {
            return endpointPack;
        }
        // Wall-follow detours are longer than the Chebyshev span of their
        // tip; free Bresenham tips are not. Only free-efficient packs may
        // tip-upgrade (XHuman 8 1510). Human 8's 12-step east face stays.
        int span = Math.max(Math.abs(endX - worker.tileX()),
                Math.abs(endY - worker.tileY()));
        if (endpointPack.length() != span) {
            return endpointPack;
        }
        PathFinder.Path tipPath = battleNetDiagonalPreferPath(
                worker, captured, goalX, goalY);
        if (pathDiagonalCount(tipPath) > pathDiagonalCount(endpointPack)) {
            return tipPath;
        }
        return endpointPack;
    }

    /** How many diagonal headings a stack-stored route carries. */
    private static int pathDiagonalCount(PathFinder.Path path) {
        if (path == null || path.length() == 0) {
            return 0;
        }
        int diagonals = 0;
        int[] headings = path.headings();
        for (int i = 0; i < path.length(); i++) {
            if (Direction.isDiagonal(headings[i])) {
                diagonals++;
            }
        }
        return diagonals;
    }

    /**
     * First heading of BNE's open Bresenham ray to a point, or {@code -1}
     * when already there.
     *
     * <p>Mirrors {@code BattleNetPathFinder.Line} without consulting the map:
     * used only to separate a pure cardinal approach from a wall-follow
     * diagonal that mixed in a detour component.</p>
     */
    static int battleNetFirstBresenhamHeading(
            int fromX, int fromY, int toX, int toY) {
        int dx = toX - fromX;
        int dy = toY - fromY;
        if (dx == 0 && dy == 0) {
            return -1;
        }
        int absoluteX = Math.abs(dx);
        int absoluteY = Math.abs(dy);
        boolean xMajor = absoluteX >= absoluteY;
        int major = xMajor ? absoluteX : absoluteY;
        int minor = xMajor ? absoluteY : absoluteX;
        int majorSign = xMajor ? Integer.signum(dx) : Integer.signum(dy);
        int minorSign = xMajor ? Integer.signum(dy) : Integer.signum(dx);
        int error = major >> 1;
        if (error == 0) {
            error = 1;
        }
        int minorStep = 0;
        error -= minor;
        if (error < 1) {
            minorStep = minorSign;
        }
        int stepX = xMajor ? majorSign : minorStep;
        int stepY = xMajor ? minorStep : majorSign;
        return Direction.fromDelta(stepX, stepY);
    }

    /** Finds a BNE route to a point while retaining the mover in UnitCache. */
    PathFinder.Path findBattleNetPointPath(Unit unit, int toX, int toY) {
        return findBattleNetPointPath(unit, toX, toY, null);
    }

    /** Finds a BNE point route with an optional native target-marker skirt. */
    PathFinder.Path findBattleNetPointPath(Unit unit, int toX, int toY,
            BattleNetPathFinder.GoalMarker goalMarker) {
        return findBattleNetPointPath(unit, toX, toY, goalMarker, false);
    }

    /** Finds a point route with the terrain-resource prefix convention. */
    PathFinder.Path findBattleNetPointPath(Unit unit, int toX, int toY,
            BattleNetPathFinder.GoalMarker goalMarker,
            boolean preserveBlockedGoalPrefix) {
        return findBattleNetPointPath(unit, toX, toY, goalMarker,
                preserveBlockedGoalPrefix, false);
    }

    /**
     * Finds a point route, optionally preserving an empty FOUND on wall-trace
     * failure instead of inventing a local side-step escape.
     *
     * <p>{@code preserveEmptyFailure} is the live-target / critter-wander
     * convention and is also used for naval action-5 goals rewritten onto a
     * building footprint (XOrc 11 destroyer 1519).</p>
     */
    PathFinder.Path findBattleNetPointPath(Unit unit, int toX, int toY,
            BattleNetPathFinder.GoalMarker goalMarker,
            boolean preserveBlockedGoalPrefix,
            boolean preserveEmptyFailure) {
        return findBattleNetPointPath(unit, toX, toY, goalMarker,
                preserveBlockedGoalPrefix, preserveEmptyFailure, true);
    }

    /**
     * Point route with control over the automatic forest free-prefix.
     *
     * @param autoForestFreePrefix when true (default), any forest goal uses
     *     the terrain-resource free-prefix convention even if the caller
     *     passed preserveBlockedGoalPrefix false (Orc 7 peon 1567). Wood
     *     harvest sets this false for a second probe so Human 8's wall-
     *     rewritten free ray can surface.
     */
    PathFinder.Path findBattleNetPointPath(Unit unit, int toX, int toY,
            BattleNetPathFinder.GoalMarker goalMarker,
            boolean preserveBlockedGoalPrefix,
            boolean preserveEmptyFailure,
            boolean autoForestFreePrefix) {
        java.util.List<Unit> softBlockers = new ArrayList<>();
        boolean hostilesStandAside = battleNetHostilesStandAside(unit);
        for (Unit candidate : units) {
            if (candidate == unit || !candidate.isOnMap()
                    || candidate.isDying()) {
                continue;
            }
            if (isAllied(unit.player(), candidate.player())) {
                // Native 0x4500f0 clears the occupancy bit for an allied unit
                // whose current animation is Move. Attack-sequence allies keep
                // hard occupancy even while residual/path leftover makes
                // isMoving true -- soft-clearing them opened NW for XHuman 12
                // grunt 1500 residual replan while native wall-follows east.
                if (!movement.battleNetSoftClearMoveAlly(candidate)) {
                    continue;
                }
            } else if (!hostilesStandAside) {
                continue;
            }
            setMovementFieldFlags(candidate, false);
            softBlockers.add(candidate);
        }
        setMovementFieldFlags(unit, false);
        try {
            BattleNetPathFinder.Passability traversalPassability =
                    battleNetTraversalPassability(unit);
            BattleNetPathFinder.Passability optimizationPassability =
                    (x, y) -> traversalPassability.canEnter(x, y)
                            && !battleNetUnitOccupies(softBlockers, x, y);
            // Critter one-tile wanders must not invent a fallbackEscape side
            // step when the goal is under a building: XOrc 2's 1580 aims at
            // 29,21 (hall), native keeps an all-0xff route and returns to
            // Still, while the generic fallback walked west around the hall.
            boolean emptyFailure = preserveEmptyFailure
                    || "unit-critter".equals(unit.type().ident());
            // Any path aimed at a forest square uses the terrain-resource
            // free-prefix convention, even when the caller did not pass
            // preserveBlockedGoalPrefix (Orc 7 peasant 1567's pathfind to
            // tree 32,2 arrived with preserve=false via a non-walkToWood
            // entry and wall-followed SE). Wood's wall-prefer probe disables
            // that auto so equal first-step gain can keep wall-follow.
            boolean blockedForestGoal = preserveBlockedGoalPrefix;
            if (!blockedForestGoal && autoForestFreePrefix) {
                MapField goalField = map.fieldOrNull(toX, toY);
                blockedForestGoal = goalField != null && goalField.isForest();
            }
            PathFinder.Path path = BattleNetPathFinder.find(
                    unit.tileX(), unit.tileY(), toX, toY,
                    battleNetMovementStride(unit),
                    traversalPassability, optimizationPassability, goalMarker,
                    emptyFailure, blockedForestGoal);
            traceBattleNetPath(unit, toX, toY, path);
            return path;
        } finally {
            setMovementFieldFlags(unit, true);
            for (Unit candidate : softBlockers) {
                setMovementFieldFlags(candidate, true);
            }
        }
    }

    /**
     * Whether a forest wall-follow that rewrites the free ray should replace
     * that free tip.
     *
     * <p>Human 8 peasant 1507 free tip {@code 3333433} ends 84,82 (west face
     * of tree 85,83); wall-follow {@code 333222223544} rewrites the fourth
     * step to east and ends 86,82 (east face). XHuman 11/12 wood peons whose
     * wall face only extends or ends on the same tip keep the free prefix.
     * </p>
     */
    /** Whether any of this cell's eight neighbours is forest. */
    private boolean battleNetTouchesForest(int x, int y) {
        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dy == 0) {
                    continue;
                }
                MapField field = map.fieldOrNull(x + dx, y + dy);
                if (field != null && field.isForest()) {
                    return true;
                }
            }
        }
        return false;
    }


    boolean battleNetPreferForestWallOverFree(
            PathFinder.Path free, PathFinder.Path wall,
            Unit worker, int goalX, int goalY) {
        if (free == null || wall == null
                || free.result() != PathFinder.Result.FOUND
                || wall.result() != PathFinder.Result.FOUND
                || free.length() == 0 || wall.length() == 0) {
            return false;
        }
        if (BattleNetPathFinder.wallExtendsFreePrefix(free, wall)) {
            return false;
        }
        int freeTipX = worker.tileX();
        int freeTipY = worker.tileY();
        for (int i = free.length() - 1; i >= 0; i--) {
            freeTipX += Direction.deltaX(free.headings()[i]);
            freeTipY += Direction.deltaY(free.headings()[i]);
        }
        int wallTipX = worker.tileX();
        int wallTipY = worker.tileY();
        for (int i = wall.length() - 1; i >= 0; i--) {
            wallTipX += Direction.deltaX(wall.headings()[i]);
            wallTipY += Direction.deltaY(wall.headings()[i]);
        }
        if (Math.max(Math.abs(freeTipX - goalX),
                Math.abs(freeTipY - goalY)) > 1) {
            return false;
        }
        if (Math.max(Math.abs(wallTipX - goalX),
                Math.abs(wallTipY - goalY)) > 1) {
            // A wall route is allowed to finish beside a different cell of the
            // same forest than the free ray was aimed at, and retail takes it
            // there. Human 13's peon 1467 walks from 55,51 toward the trees at
            // 50,46: this implementation packed four north-west steps onto 51,47 and
            // retail packs 333 66 -- three north-west then two west -- onto
            // 50,48, which is beside the tree at 50,47. Measuring the wall tip
            // against the goal cell alone threw that away for being two cells
            // from it and stepped north-west at fixture 53 where retail steps
            // west.
            if (!battleNetTouchesForest(wallTipX, wallTipY)) {
                return false;
            }
        }
        // Same skirt cell: wall added nothing useful (Human 13 skirt west).
        if (freeTipX == wallTipX && freeTipY == wallTipY) {
            return false;
        }
        return true;
    }

    /** Finds BNE's legacy route toward a live unit target. */
    private PathFinder.Path preferBattleNetFaceFirstHeading(Unit unit,
            PathFinder.Path path, Unit target) {
        if (path == null || path.length() == 0 || target == null) {
            return path;
        }
        int planned = path.headings()[path.length() - 1];
        int face = unit.heading();
        if (face < 0 || face >= Direction.COUNT || face == planned) {
            return path;
        }
        int stride = battleNetMovementStride(unit);
        int goalX = target.tileX();
        int goalY = target.tileY();
        int cur = Math.max(Math.abs(unit.tileX() - goalX),
                Math.abs(unit.tileY() - goalY));
        int planX = unit.tileX() + Direction.deltaX(planned) * stride;
        int planY = unit.tileY() + Direction.deltaY(planned) * stride;
        int faceX = unit.tileX() + Direction.deltaX(face) * stride;
        int faceY = unit.tileY() + Direction.deltaY(face) * stride;
        if (!map.contains(faceX, faceY) || !canEnter(unit, faceX, faceY)) {
            return path;
        }
        int planDist = Math.max(Math.abs(planX - goalX), Math.abs(planY - goalY));
        int faceDist = Math.max(Math.abs(faceX - goalX), Math.abs(faceY - goalY));
        if (faceDist > planDist || faceDist >= cur) {
            return path;
        }
        int[] headings = path.headings().clone();
        headings[headings.length - 1] = face;
        return new PathFinder.Path(path.result(), headings);
    }

    /**
     * Prefers an equal-cost diagonal first step that reduces the secondary
     * goal axis when a lead mid-Move brother occupies that diagonal.
     *
     * <p>Only consulted for {@link Unit#battleNetPersonHelpFirstChase()} so
     * ordinary combat opens keep Bresenham (XHuman 10 grunt 1486 @6). XHuman
     * 10 knights 1493/1485 stand one row off the catapult's y; pure-west
     * steps free at fixture 48 while native stores SW/NW onto lead brother
     * 1489 and holds Move timer 15 through fixture 67.</p>
     */
    private PathFinder.Path preferBattleNetGoalAxisFirstHeading(Unit unit,
            PathFinder.Path path, Unit target) {
        if (path == null || path.length() == 0 || target == null
                || unit.type() == null) {
            return path;
        }
        int planned = path.headings()[path.length() - 1];
        if (Direction.isDiagonal(planned)) {
            return path;
        }
        int goalX = target.tileX();
        int goalY = target.tileY();
        int dx = Integer.signum(goalX - unit.tileX());
        int dy = Integer.signum(goalY - unit.tileY());
        if (dx == 0 || dy == 0) {
            return path;
        }
        int diag = Direction.fromDelta(dx, dy);
        if (diag == planned || diag < 0 || diag >= Direction.COUNT) {
            return path;
        }
        int stride = battleNetMovementStride(unit);
        int cur = Math.max(Math.abs(unit.tileX() - goalX),
                Math.abs(unit.tileY() - goalY));
        int planX = unit.tileX() + Direction.deltaX(planned) * stride;
        int planY = unit.tileY() + Direction.deltaY(planned) * stride;
        int diagX = unit.tileX() + Direction.deltaX(diag) * stride;
        int diagY = unit.tileY() + Direction.deltaY(diag) * stride;
        if (!map.contains(diagX, diagY)) {
            return path;
        }
        int planDist = Math.max(Math.abs(planX - goalX),
                Math.abs(planY - goalY));
        int diagDist = Math.max(Math.abs(diagX - goalX),
                Math.abs(diagY - goalY));
        if (diagDist > planDist || diagDist >= cur) {
            return path;
        }
        // Free equal-cost diagonal: take it (XHuman 10 knight 1480 NW onto
        // 83,90 while pure-W would keep y=91). Occupied diagonal: only when
        // a lead marching brother sits there so soft-wait holds (1493 SW onto
        // 1489). Terrain/enemy blocks keep Bresenham.
        if (canEnter(unit, diagX, diagY)) {
            int[] freeHeadings = path.headings().clone();
            freeHeadings[freeHeadings.length - 1] = diag;
            return new PathFinder.Path(path.result(), freeHeadings);
        }
        Unit occ = unitAt(diagX, diagY);
        if (occ == null || occ == unit || occ.isDying() || !occ.isOnMap()
                || !isAllied(unit.player(), occ.player())) {
            return path;
        }
        boolean marchingBrother = movement.battleNetSoftClearMoveAlly(occ)
                || occ.isMoving()
                || (occ.pathLength() > 0
                        && (occ.order() == Unit.Order.ATTACK
                                || occ.order() == Unit.Order.ATTACK_MOVE
                                || occ.chasing()));
        if (!marchingBrother) {
            return path;
        }
        int allyDist = Math.max(Math.abs(occ.tileX() - goalX),
                Math.abs(occ.tileY() - goalY));
        if (allyDist >= cur) {
            return path;
        }
        int[] headings = path.headings().clone();
        headings[headings.length - 1] = diag;
        return new PathFinder.Path(path.result(), headings);
    }

    PathFinder.Path findBattleNetTargetPath(Unit unit, Unit target) {
        java.util.List<Unit> softBlockers = new ArrayList<>();
        boolean hostilesStandAside = battleNetHostilesStandAside(unit);
        for (Unit candidate : units) {
            if (candidate == unit || candidate == target
                    || !candidate.isOnMap() || candidate.isDying()) {
                continue;
            }
            if (isAllied(unit.player(), candidate.player())) {
                // 0x450690 may cross a friendly unit whose Move sequence has
                // already begun. Attack-sequence chasers keep hard occupancy
                // (XHuman 12 residual replan east wall-follow).
                if (!movement.battleNetSoftClearMoveAlly(candidate)) {
                    continue;
                }
            } else if (!hostilesStandAside || candidate.type().building()) {
                // 0x00450766 clears bit 0x100 and nothing else, so a hostile
                // building's footprint bits survive it and the square stays a
                // wall. Standing one aside here strips the whole footprint:
                // XHuman 12's grunt 1470 then walked its north wall through
                // 26,42, whose word reads 0x0801, and stored the south face's
                // twenty-five headings where native stores the north face's
                // twenty-two.
                continue;
            }
            setMovementFieldFlags(candidate, false);
            softBlockers.add(candidate);
        }
        setMovementFieldFlags(unit, false);
        try {
            int targetLeft = target.tileX();
            int targetTop = target.tileY();
            int targetWidth = Math.max(1, target.type().tileWidth());
            int targetHeight = Math.max(1, target.type().tileHeight());
            int targetRight = targetLeft + targetWidth - 1;
            int targetBottom = targetTop + targetHeight - 1;

            // 0x41f430 aims at the near edge of a target footprint, except
            // when the mover is already aligned inside that footprint where
            // it uses the midpoint. For one-tile combatants this is simply
            // the target's own square.
            int goalX = targetLeft;
            if (targetLeft < unit.tileX()) {
                goalX = unit.tileX() < targetRight
                        ? targetLeft + targetWidth / 2
                        : targetRight;
            }
            int goalY = targetTop;
            if (targetTop < unit.tileY()) {
                goalY = unit.tileY() < targetBottom
                        ? targetTop + targetHeight / 2
                        : targetBottom;
            }

            // Re-harden short-leftover combat allies on the approach corridor
            // before the route is drawn. Soft-clear already ran without the
            // goal; restore their occupancy and drop them from the optimizer
            // soft list so wall-follow sees them solid.
            // Re-harden short-leftover combat allies on the approach corridor
            // before the route is drawn. Soft-clear already ran without the
            // goal; restore their occupancy and drop them from the optimizer
            // soft list so wall-follow sees them solid.
            //
            // Measured as a compensation and it is one -- taking it out costs
            // 110 of 410,880 paired unit-cycles fleet-wide -- but those 110
            // include XHuman 12's grunt 1512, which stops stepping at fixture
            // 19 without it, so it stays until that rule is found.
            for (int i = softBlockers.size() - 1; i >= 0; i--) {
                Unit ally = softBlockers.get(i);
                if (!battleNetApproachCorridorHardAlly(
                        unit, ally, goalX, goalY)) {
                    continue;
                }
                setMovementFieldFlags(ally, true);
                softBlockers.remove(i);
            }

            // A live movable goal is not an ordinary wall to BNE's router.
            // Its marked-goal pass can write the straight route through the
            // goal square; MoveToTarget's range check stops the chaser before
            // overlap. Human08 slot 1526 therefore stores east,east toward a
            // moving peasant at 70,67 and takes its first step due east. A
            // building remains solid and is approached through its marked
            // perimeter, preserving the separately captured mine routes.
            boolean movableTarget = !target.type().building();
            BattleNetPathFinder.Passability base =
                    battleNetTraversalPassability(unit);
            BattleNetPathFinder.Passability traversalPassability =
                    new BattleNetPathFinder.Passability() {
                        @Override
                        public boolean canEnter(int x, int y) {
                            return (movableTarget
                                    && x >= targetLeft && x <= targetRight
                                    && y >= targetTop && y <= targetBottom)
                                    || base.canEnter(x, y);
                        }

                        @Override
                        public boolean isOutOfBounds(int x, int y) {
                            return base.isOutOfBounds(x, y);
                        }
                    };
            BattleNetPathFinder.Passability optimizationPassability =
                    (x, y) -> traversalPassability.canEnter(x, y)
                            && !battleNetUnitOccupies(softBlockers, x, y);
            PathFinder.Path path = BattleNetPathFinder.find(
                    unit.tileX(), unit.tileY(), goalX, goalY,
                    battleNetMovementStride(unit), traversalPassability,
                    optimizationPassability,
                    // 0x4508f0 marks this entire one-tile skirt, target
                    // footprint included, while leaving its blocking flags
                    // in place. The ray hits the target and its wall follower
                    // terminates on the marked edge.
                    (x, y) -> x >= targetLeft - 1
                            && x <= targetRight + 1
                            && y >= targetTop - 1
                            && y <= targetBottom + 1,
                    true);
            // A coastal building can have no reachable one-square target
            // skirt for a doubled naval anchor even though the weapon can
            // fire from open water. COrder_Attack::UpdatePathFinderData gives
            // retail's path input the weapon's min/max range as well as the
            // target footprint. Preserve the tightly captured one-square
            // marker when it produces a route; when it cannot produce even
            // one heading, retry with reachable in-range water anchors. This
            // is the missing half of that input and prevents a battleship
            // from acknowledging an attack, reaching the coast, then
            // replanning an empty route forever until the player manually
            // moves it closer.
            if (path.length() == 0
                    && path.result() == PathFinder.Result.FOUND
                    && unit.battleNetDoubleStep()
                    && unit.type().seaUnit()
                    && target.type().building()
                    && !targets.inAttackRange(unit, target)) {
                int minRange = Math.max(0, unit.type().minAttackRange());
                int maxRange = Math.max(1, unit.type().maxAttackRange());
                PathFinder.Path ranged = BattleNetPathFinder.find(
                        unit.tileX(), unit.tileY(), goalX, goalY,
                        battleNetMovementStride(unit), traversalPassability,
                        optimizationPassability,
                        (x, y) -> {
                            if (!traversalPassability.canEnter(x, y)) {
                                return false;
                            }
                            int nearX = battleNetNearFootprintCoordinate(
                                    x, targetLeft, targetWidth);
                            int nearY = battleNetNearFootprintCoordinate(
                                    y, targetTop, targetHeight);
                            int distance = Math.max(Math.abs(nearX - x),
                                    Math.abs(nearY - y));
                            return distance >= minRange && distance <= maxRange;
                        },
                        true);
                if (ranged.length() > 0) {
                    path = ranged;
                }
            }
            traceBattleNetPath(unit, goalX, goalY, path);
            return path;
        } finally {
            setMovementFieldFlags(unit, true);
            for (Unit candidate : softBlockers) {
                setMovementFieldFlags(candidate, true);
            }
        }
    }

    /**
     * Traversal view for BNE pathfinding, including wall-follow fatal cells.
     *
     * <p>Native {@code 0x4500f0} fails the whole wall face on an out-of-bounds
     * candidate step and on a free cell with map flag {@code 0x2000}
     * (LegacyEngine {@link TileFlag#AIR_UNIT}). Ordinary blocked terrain only
     * rotates the heading.</p>
     */
    /**
     * Whether the ray from {@code unit} to a resource square is stopped by that
     * square itself rather than by anything in front of it.
     *
     * <p>Native's {@code 0x00450690} draws the ray with {@code 0x00429f10} /
     * {@code 0x00429fa0} and stops it on {@code square & mask}. When the only
     * thing in the way is the resource, its own square is where the ray ends
     * and the wall follower runs from there; when something else intervenes,
     * the two are different squares and native does not always aim at the
     * resource. Measured over 183 sealed resource routes, the ray to native's
     * own goal is never clear and stops exactly on it 134 times.</p>
     */
    boolean battleNetRayReachesResource(Unit unit, int toX, int toY) {
        BattleNetPathFinder.Passability passability =
                battleNetTraversalPassability(unit);
        int x = unit.tileX();
        int y = unit.tileY();
        int absoluteX = Math.abs(toX - x);
        int absoluteY = Math.abs(toY - y);
        if (absoluteX == 0 && absoluteY == 0) {
            return false;
        }
        boolean xMajor = absoluteX >= absoluteY;
        int major = xMajor ? absoluteX : absoluteY;
        int minor = xMajor ? absoluteY : absoluteX;
        int majorSign = xMajor ? Integer.signum(toX - x) : Integer.signum(toY - y);
        int minorSign = xMajor ? Integer.signum(toY - y) : Integer.signum(toX - x);
        int error = major >> 1;
        if (error == 0) {
            error = 1;
        }
        for (int step = 0; step < BNE_RAY_STEPS; step++) {
            int minorStep = 0;
            error -= minor;
            if (error < 1) {
                minorStep = minorSign;
                error += major;
            }
            x += xMajor ? majorSign : minorStep;
            y += xMajor ? minorStep : majorSign;
            if (passability.isOutOfBounds(x, y) || !passability.canEnter(x, y)) {
                return x == toX && y == toY;
            }
            if (x == toX && y == toY) {
                return false;
            }
        }
        return false;
    }

    /** {@code 0x004507de}: twenty steps and the ray gives up. */
    private static final int BNE_RAY_STEPS = 20;

    BattleNetPathFinder.Passability battleNetTraversalPassability(Unit unit) {
        long mask = unit.movementMask();
        boolean ignoreBuilding = construction.builderWalksThroughBuildingBodies(unit);
        long blocking = ignoreBuilding
                ? construction.builderTraversalBlocking(unit)
                : unit.blockingFlags();
        int mapWidth = map.width();
        int mapHeight = map.height();
        return new BattleNetPathFinder.Passability() {
            @Override
            public boolean canEnter(int x, int y) {
                if (!map.isFootprintFree(x, y, 1, 1, mask, blocking)) {
                    return false;
                }
                return !ignoreBuilding || !construction.solidBuildingOriginAt(x, y);
            }

            @Override
            public boolean isOutOfBounds(int x, int y) {
                return x < 0 || y < 0 || x >= mapWidth || y >= mapHeight;
            }
        };
    }

    /**
     * Whether a unit this mover is at war with stands aside for its route.
     *
     * <p>Native {@code 0x00450766} opens with "the relation row says nothing
     * about this player, so clear the occupancy bit", and {@code 0x0044fc48}
     * hands a mover its own alliance row -- the one at
     * {@code 0x4abda4 + player * 16} -- exactly when the word at
     * {@code 0x496234 + order * 2} carries {@code 0x0002}. Order 12, the
     * chase, carries it. Order 3, a plain walk, and order 23, harvest, do not,
     * and those movers get row 15, which is all ones and admits nobody. So a
     * chaser routes straight through everything it is at war with and a walker
     * routes around it.</p>
     *
     * <p>This implementation had no such rule: it soft-cleared allies only, so a chaser
     * treated every enemy as a wall. XHuman 12's grunt 1494 is the witness --
     * native's route ends on 29,43, which a player 1 unit is standing on.
     * Measured over the 842 sealed routes, taking this test out of a faithful
     * transcription of {@code 0x0044fbd0} costs 65 routes, 701 down to 636.</p>
     *
     * <p>The list below was hand-written from the orders that looked like
     * fighting, and then read off the table instead. Pairing 811,949 unit-
     * cycles of the survey against the captures gives each native order byte
     * a name: byte 12 and byte 16 are this implementation's Attack, byte 3 is Move,
     * bytes 23, 25, 26 and 30 are Harvest, bytes 22 and 28 are Build, and
     * bytes 4 and 5 are Patrol and nothing else. Of those, 4 and 5 carry
     * {@code 0x0002} -- so a patrolling unit routes through the enemies in
     * its way exactly as a chaser does, which this implementation did not do. It is 40
     * of the 842 sealed routes.</p>
     *
     * <p>{@code 0x0044fc54} overrules the order for ten unit types -- those
     * whose word at {@code 0x4cf574 + type * 4} carries any of
     * {@code 0x06000300} -- and hands them row 15 whatever they are doing. The
     * ten are the two workers, the two oil tankers, the four spellcasters and
     * the two demolition units, and the four bits sort them exactly that way.
     * So a death knight closing on a target routes around the enemies in its
     * path and a grunt beside it routes through them.</p>
     *
     * <p>Two things the table says and this implementation still cannot: the six native
     * order bytes 2, 14, 32, 33, 37 and 59 all arrive here as Still, and 2,
     * 14 and 32 carry the bit while 33, 37 and 59 do not. That ambiguity
     * cannot reach this method -- not one of the 842 sealed routes was laid by
     * a unit on any of the six -- so it is left alone. And
     * {@code 0x0044fc48}'s first arm, a non-null unit pointer at record offset
     * {@code 0x54}, demotes 30 of the 342 chasers; this implementation has no copy of
     * that field, and it is in focused tests.</p>
     */
    private boolean battleNetHostilesStandAside(Unit unit) {
        if (BATTLE_NET_ENEMIES_ALWAYS_WALL.contains(unit.type().ident())) {
            return false;
        }
        Unit.Order order = unit.order();
        return order == Unit.Order.ATTACK
                || order == Unit.Order.ATTACK_MOVE
                || order == Unit.Order.ATTACK_GROUND
                || order == Unit.Order.SPELL_CAST
                || order == Unit.Order.PATROL
                || unit.chasing();
    }

    /**
     * The ten types whose enemies stay walls whatever order they are on.
     *
     * <p>Implements the {@code 0x06000300} test at {@code 0x0044fc54} in
     * {@code fcn.0044fbd0}, read off the flag word at
     * {@code 0x4cf574 + type * 4}. The four bits are one group each:
     * {@code 0x00000100} the workers, {@code 0x00000200} the oil tankers,
     * {@code 0x04000000} the spellcasters and {@code 0x02000000} the
     * demolition units.</p>
     */
    private static final Set<String> BATTLE_NET_ENEMIES_ALWAYS_WALL = Set.of(
            "unit-peasant", "unit-peon",
            "unit-human-oil-tanker", "unit-orc-oil-tanker",
            "unit-mage", "unit-death-knight", "unit-evil-knight",
            "unit-white-mage",
            "unit-dwarves", "unit-goblin-sappers");

    /**
     * Whether a short-leftover combat ally should stay hard on a combat
     * target route.
     *
     * <p>Native wall-follow needs friends sitting on the approach corridor
     * (Chebyshev to goal less than or equal to the router's) to remain solid
     * when they only hold a one-step leftover. XHuman 12 residual replan
     * soft-cleared grunt 95 at 30,40 (pathLength 1, closer to goal 30,44
     * than the router at 35,39) and free-detoured NW; native stores pure
     * east with that friend blocking. Allies farther from the goal than the
     * router, and multi-step Attack walks, still soft-clear so early
     * formation paths keep their free crossings.</p>
     */
    private static boolean battleNetApproachCorridorHardAlly(
            Unit router, Unit candidate, int goalX, int goalY) {
        if (candidate == null || router == null) {
            return false;
        }
        Unit.Order order = candidate.order();
        boolean combatOrder = order == Unit.Order.ATTACK
                || order == Unit.Order.ATTACK_MOVE
                || order == Unit.Order.ATTACK_GROUND
                || order == Unit.Order.SPELL_CAST;
        // Exactly one leftover heading on a long combat approach. Multi-step
        // walks and residual-only keep ordinary Move soft-clear. Short
        // approaches (router Chebyshev < 4) also soft-clear -- early XHuman
        // 12 formation at fixture 7 needs those free crossings.
        if (!combatOrder || candidate.pathLength() != 1) {
            return false;
        }
        int allyDist = Math.max(Math.abs(candidate.tileX() - goalX),
                Math.abs(candidate.tileY() - goalY));
        int routerDist = Math.max(Math.abs(router.tileX() - goalX),
                Math.abs(router.tileY() - goalY));
        if (routerDist < 5 || allyDist >= routerDist) {
            return false;
        }
        // On a pure approach axis of the goal (same column or row). Grunt 95
        // at 30,40 toward 30,44 shares the goal's x; early formation soft-
        // clears off-axis pathLength-1 allies without REG (XHuman 4 @6).
        return candidate.tileX() == goalX || candidate.tileY() == goalY;
    }

    /** Whether one temporarily softened unit still covers an optimizer tile. */
    boolean battleNetUnitOccupies(java.util.List<Unit> candidates,
            int x, int y) {
        for (Unit candidate : candidates) {
            int width = Math.max(1, candidate.type().tileWidth());
            int height = Math.max(1, candidate.type().tileHeight());
            if (x >= candidate.tileX() && x < candidate.tileX() + width
                    && y >= candidate.tileY()
                    && y < candidate.tileY() + height) {
                return true;
            }
        }
        return false;
    }

    /** Emits the same opt-in path diagnostic for point and resource goals. */
    void traceBattleNetPath(Unit unit, int toX, int toY,
            PathFinder.Path path) {
        String traced = System.getenv("CHONKCRAFT_TRACE_BNE_PATH");
        boolean diagnostic = traced != null && (traced.isBlank()
                || unit.id() == Integer.parseInt(traced.trim()));
        if (!diagnostic && !causalTrace.accepts(unit.id())) {
            return;
        }
        int width = Math.max(1, unit.type().tileWidth());
        int height = Math.max(1, unit.type().tileHeight());
        StringBuilder headings = new StringBuilder();
        for (int index = path.length() - 1; index >= 0; index--) {
            headings.append(path.headings()[index]);
        }
        causalTrace.event(cycle, "path.route", unit.id(),
                "fromX", unit.tileX(), "fromY", unit.tileY(),
                "goalX", toX, "goalY", toY,
                "stride", battleNetMovementStride(unit),
                "result", path.result(), "path", headings);
        if (!diagnostic) {
            return;
        }
        StringBuilder neighbours = new StringBuilder();
        for (int heading = 0; heading < Direction.COUNT; heading++) {
            int x = unit.tileX() + Direction.deltaX(heading)
                    * battleNetMovementStride(unit);
            int y = unit.tileY() + Direction.deltaY(heading)
                    * battleNetMovementStride(unit);
            boolean free = map.isFootprintFree(x, y, width, height,
                    unit.movementMask(), unit.blockingFlags());
            MapField field = map.fieldOrNull(x, y);
            neighbours.append(neighbours.isEmpty() ? "" : ",")
                    .append(heading).append(':').append(free ? 1 : 0)
                    .append(':').append(field == null ? "-"
                            : Long.toHexString(field.flags()));
        }
        StringBuilder corridor = new StringBuilder();
        int scanLeft = Math.max(0, Math.min(unit.tileX(), toX) - 1);
        int scanTop = Math.max(0, Math.min(unit.tileY(), toY) - 1);
        int scanRight = Math.min(map.width() - 1,
                Math.max(unit.tileX(), toX) + 1);
        int scanBottom = Math.min(map.height() - 1,
                Math.max(unit.tileY(), toY) + 1);
        if ((long) (scanRight - scanLeft + 1) * (scanBottom - scanTop + 1)
                <= 128) {
            for (int y = scanTop; y <= scanBottom; y++) {
                for (int x = scanLeft; x <= scanRight; x++) {
                    MapField field = map.fieldOrNull(x, y);
                    boolean free = map.isFootprintFree(x, y, width, height,
                            unit.movementMask(), unit.blockingFlags());
                    corridor.append(corridor.isEmpty() ? "" : ",")
                            .append(x).append(':').append(y).append(':')
                            .append(free ? 1 : 0).append(':')
                            .append(field == null ? "-" : field.tile())
                            .append(':').append(field == null ? "-"
                                    : map.tileset().graphicFor(field.tile()))
                            .append(':').append(field == null ? "-"
                                    : Long.toHexString(field.flags()));
                }
            }
        }
        System.err.printf("JBNEPATH cycle=%d unit=%d from=%d,%d "
                        + "goal=%d,%d stride=%d result=%s path=%s near=%s scan=%s%n",
                cycle, unit.id(), unit.tileX(), unit.tileY(), toX, toY,
                battleNetMovementStride(unit), path.result(), headings,
                neighbours, corridor);
        // Occupancy cache dump for tiles that still carry the mover's layer
        // flag: catches stale SEA_UNIT/LAND_UNIT bits left after a double
        // step. Opt-in only; the ordinary path line is already large.
        StringBuilder cacheDump = new StringBuilder();
        for (int y = scanTop; y <= scanBottom; y++) {
            for (int x = scanLeft; x <= scanRight; x++) {
                MapField field = map.fieldOrNull(x, y);
                if (field == null || !field.hasFlag(unit.occupancyFlag())) {
                    continue;
                }
                List<Unit> cached = unitCache.get(x + y * map.width());
                cacheDump.append(cacheDump.isEmpty() ? "" : ";")
                        .append(x).append(',').append(y).append('=')
                        .append(Long.toHexString(field.flags()));
                if (cached != null) {
                    for (Unit occupant : cached) {
                        cacheDump.append('/').append(occupant.id())
                                .append(':').append(occupant.type().ident())
                                .append('@').append(occupant.tileX())
                                .append(',').append(occupant.tileY())
                                .append(occupant.isOnMap() ? "" : "!rm")
                                .append(occupant.isMoving() ? "M" : "")
                                .append(occupant.walkHolding() ? "W" : "");
                    }
                } else {
                    cacheDump.append("/empty-cache");
                }
            }
        }
        if (!cacheDump.isEmpty()) {
            System.err.printf("JBNEOCC cycle=%d unit=%d %s%n",
                    cycle, unit.id(), cacheDump);
        }
    }

    /**
     * Whether a worker already has work that building would interrupt.
     *
     * <p>{@code IsAlreadyWorking}:
     * building or repairing counts, and so does gathering once the
     * extraction has begun -- a worker on its way to the mine may still be
     * pulled off to build, one inside it or chopping may not, and one
     * carrying goods home is mid-round-trip, which upstream's single
     * resource order keeps under the same roof.
     */
    public boolean isAlreadyWorking(Unit unit) {
        if (unit.order() == Unit.Order.BUILD || unit.order() == Unit.Order.REPAIR
                || unit.pendingBuild() != null) {
            return true;
        }
        if (unit.order() == Unit.Order.RETURN_GOODS) {
            return true;
        }
        if (unit.order() == Unit.Order.HARVEST) {
            // IsGatheringStarted is State > SUB_START_GATHERING, so it stays
            // true not only inside a mine and during an axe swing but through
            // SUB_STOP_GATHERING and the whole SUB_MOVE_TO_DEPOT leg. The
            // port keeps that leg inside HARVEST with this explicit bit.
            // Leaving it out made levelx02h's homeward peon look free at
            // cycle 277; AiBuildBuilding then had two candidates instead of
            // upstream's one and consumed a worker-choice random draw.
            return unit.returningToDepot() || !unit.isOnMap() || harvest.isChopping(unit);
        }
        return false;
    }

    /**
     * How many harvesters are bound to a mine, walking legs included.
     *
     * <p>{@code CUnit::Resource.AssignedWorkers}: a worker joins the list
     * the moment its resource order picks the mine and stays on it for the
     * whole round trip -- walking there, inside, and carrying home -- across
     * every player at once. The order gate stands in for upstream's
     * deassign-on-destruction: a worker flushed onto another errand keeps
     * its stale {@code resourceUnit} field, but its order has left the
     * harvest family and upstream's destructor would have dropped it.
     */
    int countAssignedWorkers(Unit mine) {
        int count = 0;
        for (Unit unit : units) {
            if (unit.isAlive() && unit.order() == Unit.Order.HARVEST
                    && unit.resourceUnit() == mine) {
                count++;
            }
        }
        return count;
    }

    /**
     * The first enemy a unit's own ground can carry it within gunshot of.
     *
     * <p>Implements {@code EnemyUnitFinder},
     * the question a force's launch actually asks. A terrain flood from the
     * seeker over the tiles its movement mask crosses -- other units cleared
     * from the mask, so a crowd is not a wall -- and at every reachable tile
     * a box the attack range wide is searched for an enemy the seeker's
     * weapon can target, invulnerable and unholy-armoured units excluded.
     * Fog is not consulted: the finder sees the whole map, which is
     * {@code AiForceEnemyFinder}'s own rule. A land force prefers buildings
     * -- the first enemy of any kind is kept as the fallback -- and a naval
     * force takes anything.
     *
     * <p>The flood is the launch gate: an enemy no water touches is never
     * found, and the force stands. On campaigns/orc-exp/levelx08o upstream's
     * three destroyers hold their harbour until cycle 370 because every
     * enemy is inland; this implementation's list of every enemy on the map sent them
     * sailing on their player's first thought.
     */
    public Unit findEnemyByFlood(Unit seeker, boolean preferBuildings) {
        long mask = seeker.movementMask();
        long blocking = seeker.blockingFlags()
                & ~(TileFlag.LAND_UNIT | TileFlag.AIR_UNIT | TileFlag.SEA_UNIT);
        int range = Math.max(1, seeker.type().maxAttackRange());
        int seekerWidth = Math.max(1, seeker.type().tileWidth());
        int seekerHeight = Math.max(1, seeker.type().tileHeight());
        int width = map.width();
        int[] steps = new int[width * map.height()];
        int[] queue = new int[steps.length];
        int head = 0;
        int tail = 0;
        for (int y = seeker.tileY() - 1; y != seeker.tileY() + seekerHeight; y++) {
            for (int x = seeker.tileX() - 1; x != seeker.tileX() + seekerWidth; x++) {
                if (map.contains(x, y) && steps[x + y * width] == 0) {
                    steps[x + y * width] = 1;
                    queue[tail++] = x + y * width;
                }
            }
        }
        Unit fallback = null;
        java.util.HashSet<Unit> weighed = new java.util.HashSet<>();
        while (head < tail) {
            int at = queue[head++];
            int x = at % width;
            int y = at / width;
            if (!map.isFootprintFree(x, y, 1, 1, mask, blocking)) {
                continue;
            }
            for (int by = y - range; by <= y + seekerHeight - 1 + range; by++) {
                for (int bx = x - range; bx <= x + seekerWidth - 1 + range; bx++) {
                    if (!map.contains(bx, by)) {
                        continue;
                    }
                    List<Unit> cached = unitCache.get(bx + by * width);
                    if (cached == null) {
                        continue;
                    }
                    for (Unit dest : cached) {
                        if (!weighed.add(dest)) {
                            continue;
                        }
                        // Select walks CMapField::UnitCache directly. A unit
                        // stays in that cache throughout its death animation,
                        // and EnemyUnitFinder asks only diplomacy, target
                        // layer and invulnerability -- not IsAlive. Thus a
                        // destroyed building place can be the flood's first
                        // building. levelx12h launches at the guard-tower
                        // rubble at 39,41 on cycle 849; filtering DYING skips
                        // it and sends the force at the farm behind it.
                        if (!dest.isOnMap()
                                || !isEnemyPlayer(seeker.player(), dest.player())
                                || !targets.canTarget(seeker, dest)
                                || dest.type().indestructible()
                                || dest.hasBuff(Unit.Buff.UNHOLY_ARMOR)) {
                            continue;
                        }
                        if (!preferBuildings || dest.type().building()) {
                            return dest;
                        }
                        if (fallback == null) {
                            fallback = dest;
                        }
                    }
                }
            }
            for (int i = 0; i < FILL_NEIGHBOURS.length; i += 2) {
                int nx = x + FILL_NEIGHBOURS[i];
                int ny = y + FILL_NEIGHBOURS[i + 1];
                if (!map.contains(nx, ny) || steps[nx + ny * width] != 0) {
                    continue;
                }
                steps[nx + ny * width] = steps[at] + 1;
                queue[tail++] = nx + ny * width;
            }
        }
        return fallback;
    }

    /**
     * A quiet square for a launched force to muster on, or {@code null}.
     *
     * <p>{@code AiForceRallyPointFinder}:
     * a flood from the leader's own tile over the ground it crosses --
     * units and buildings cleared from the mask -- taking the first square
     * with no enemy within fifteen and within {@code |distance - 15|} of
     * the leader, where {@code distance} is the leader's distance to the
     * enemy the launch found. The condition is tested before passability,
     * exactly as {@code Visit} orders it, and on a quiet harbour the first
     * square asked is the leader's own. The launch aims here, not at the
     * enemy; the enemy comes later, from the rally.
     */
    public int[] findRallyPoint(Unit leader, int distanceToEnemy) {
        int minDist = 15;
        long mask = leader.movementMask();
        long blocking = leader.blockingFlags()
                & ~(TileFlag.LAND_UNIT | TileFlag.AIR_UNIT | TileFlag.SEA_UNIT
                        | TileFlag.BUILDING);
        int width = map.width();
        int[] steps = new int[width * map.height()];
        int[] queue = new int[steps.length];
        int head = 0;
        int tail = 0;
        int startX = leader.tileX();
        int startY = leader.tileY();
        steps[startX + startY * width] = 1;
        queue[tail++] = startX + startY * width;
        int allowed = Math.abs(distanceToEnemy - minDist);
        while (head < tail) {
            int at = queue[head++];
            int x = at % width;
            int y = at / width;
            if (!targets.enemyWithin(leader.player(), x, y, minDist)
                    && straightDistance(x - startX, y - startY) <= allowed) {
                return new int[] {x, y};
            }
            if (!map.isFootprintFree(x, y, 1, 1, mask, blocking)) {
                continue;
            }
            for (int i = 0; i < FILL_NEIGHBOURS.length; i += 2) {
                int nx = x + FILL_NEIGHBOURS[i];
                int ny = y + FILL_NEIGHBOURS[i + 1];
                if (!map.contains(nx, ny) || steps[nx + ny * width] != 0) {
                    continue;
                }
                steps[nx + ny * width] = steps[at] + 1;
                queue[tail++] = nx + ny * width;
            }
        }
        return null;
    }

    /** {@code Distance}: the straight line, rounded down. */
    private static int straightDistance(int dx, int dy) {
        return (int) Math.sqrt((double) dx * dx + (double) dy * dy);
    }

    /**
     * The walked length of a route to a unit, nought when there is none.
     *
     * <p>Implements {@code UnitReachable} via {@code PlaceReachable}
     * Enemy units count as walls
     * for the whole ask -- {@code SetAStarFixedEnemyUnitsUnpassable(true)}
     * brackets it -- {@code PF_REACHED} answers one "since most of this
     * function usage check return value as bool", and a worker inside a
     * container does not path from the square its bookkeeping stands on:
     * that square is under the building, and a sea unit inside an oil
     * platform on a shoreline has no route at all from the corner tile.
     * Upstream tries every mask-passable top-left square around the container,
     * west column first, and takes the first that answers
     * On campaigns/human-exp/levelx03h
     * the tanker surfacing at cycle 215 asks this about the refinery six
     * squares away; pathing from the corner square answered "no road
     * anywhere", and the tanker dropped out by the wrong face aimed at
     * nothing.
     */
    int unitReachableTravel(Unit src, Unit dst, int range) {
        if (src.type().building()) {
            return 0;
        }
        PathFinder.Occupancy base = occupancyFor(src);
        PathFinder.Occupancy walled = (x, y) -> {
            int at = base.at(x, y);
            return at == PathFinder.Occupancy.STATIONARY_ENEMY
                    ? PathFinder.Occupancy.STATIONARY : at;
        };
        PathFinder.Mover mover = new PathFinder.Mover(src.movementMask(),
                src.blockingFlags(), src.type().tileWidth(),
                src.type().tileHeight(), walled);
        PathFinder.Goal goal = new PathFinder.Goal(dst.tileX(), dst.tileY(),
                Math.max(1, dst.type().tileWidth()),
                Math.max(1, dst.type().tileHeight()), 0, range);
        if (src.isOnMap() || src.worksite() == null) {
            return travelOf(pathFinder.find(src.tileX(), src.tileY(), goal, mover));
        }
        Unit box = src.worksite();
        int boxWidth = Math.max(1, box.type().tileWidth());
        int boxHeight = Math.max(1, box.type().tileHeight());
        int width = Math.max(1, src.type().tileWidth());
        int height = Math.max(1, src.type().tileHeight());
        for (int x = -1; x <= boxWidth; x++) {
            for (int y = -1; y <= boxHeight; y++) {
                if (x >= 0 && x < boxWidth && y >= 0 && y < boxHeight) {
                    continue;
                }
                int tileX = box.tileX() + x;
                int tileY = box.tileY() + y;
                if (!map.contains(tileX, tileY)
                        || !map.isFootprintFree(tileX, tileY, 1, 1,
                                src.movementMask(), src.blockingFlags())) {
                    continue;
                }
                int travel = travelOf(pathFinder.find(tileX, tileY, goal, mover));
                if (travel != 0) {
                    return travel;
                }
            }
        }
        return 0;
    }

    private static int travelOf(PathFinder.Path path) {
        return switch (path.result()) {
            case REACHED -> 1;
            case FOUND -> Math.max(1, path.length());
            case UNREACHABLE -> 0;
        };
    }

    Animation stillAnimation(Unit unit) {
        AnimationSet set = unit.type() == null ? null : unit.type().animationSet();
        return set == null ? null : set.getOrStill(AnimationSet.State.STILL);
    }

    /**
     * The square a contained unit should reappear on.
     *
     * <p>{@code DropOutNearest} towards something, {@code DropOutOnSide} west
     * when there is nothing to head for. Every resource emergence upstream is
     * one of these two, and which one it is decides where the player sees the
     * worker.
     */
    int[] placeBeside(Unit unit, Unit container, Unit towards) {
        if (towards == null) {
            return dropOutOnSide(unit.type(), LOOKING_WEST, container,
                    unit.tileX(), unit.tileY());
        }
        int[] goal = centreOf(towards);
        return dropOutNearest(unit.type(), goal[0], goal[1], container,
                unit.tileX(), unit.tileY());
    }

    /**
     * The references relevant to the AI's depot-congestion threshold.
     *
     * <p>Every live upstream unit owns its base reference. Resource orders
     * add one for their remembered Depot and, during the return leg, another
     * for their ordinary Goal. Other unit-goal pointers are included as well;
     * a contained unit's raw Container pointer deliberately is not.
     */
    int approximateUnitRefs(Unit referenced) {
        int refs = 1;
        for (Unit unit : units) {
            if (unit.resourceDepot() == referenced) {
                refs++;
            }
            if (unit.returnDepotGoal() == referenced) {
                refs++;
            }
            if (unit.target() == referenced && orderOwnsTargetPointer(unit)) {
                refs++;
            }
            // COrder_Attack::offeredTarget is a real CUnitPtr for as long as
            // that attack order lives.  LetUnitDie clears it with the rest of
            // the owner's orders; kill() mirrors that boundary rather than
            // leaving the shared Java projection attached to a corpse.
            if (unit.offeredTarget() == referenced) {
                refs++;
            }
            if (unit.pendingAttack() == referenced) {
                refs++;
            }
            if (unit.worksite() == referenced) {
                refs++;
            }
            for (Unit.QueuedOrder queued : unit.queuedOrders()) {
                if (queued.target() == referenced) {
                    refs++;
                }
            }
        }
        for (Missile missile : missiles) {
            if (missile.source() == referenced) {
                refs++;
            }
            if (missile.target() == referenced) {
                refs++;
            }
        }
        // VisibleUnderFog units also own one reference for each human player
        // who last saw them and has not looked at their square since. This is
        // why wrecked building places can remain Destroyed in UnitManager's
        // table after their decay animation ends: the renderer's remembered
        // building still owns them upstream. SeenBuildings stores the picture
        // independently here, but table ordering must retain the same hidden
        // slot until that player sees the ground again.
        if (referenced.type() != null && referenced.type().visibleUnderFog()) {
            for (Player viewer : players) {
                if (viewer != null
                        && viewer.type() == net.chonkbase.chonkcraft.data.map.PudMap
                                .PlayerType.PERSON
                        && referenced.wasSeenBy(viewer.index())
                        && isSeenBy(referenced, viewer.index())) {
                    refs++;
                }
            }
        }
        return refs;
    }

    /** Whether this Java unit's current COrder carries its shared target as Goal. */
    private static boolean orderOwnsTargetPointer(Unit unit) {
        Unit.Order order = unit.currentAction();
        return order == Unit.Order.ATTACK || order == Unit.Order.ATTACK_MOVE
                || order == Unit.Order.REPAIR || order == Unit.Order.FOLLOW
                || order == Unit.Order.DEFEND
                || order == Unit.Order.BOARD || order == Unit.Order.UNLOAD
                || order == Unit.Order.SPELL_CAST || order == Unit.Order.STAND_GROUND
                // COrder_Still::AutoAttackStand attacks in-place on that same
                // Still object and stores the quarry in its inherited Goal.
                // A mobile idle unit instead receives a separate Attack
                // order, so a target left after it returns to Still is stale.
                || (order == Unit.Order.STILL && unit.type() != null
                        && unit.type().speed() <= 0);
    }

    /**
     * Drops retained Destroyed entries at the action boundary which released
     * their final UnitPtr.
     *
     * <p>{@code CUnit::Release} marks a unit Destroyed when its own live
     * reference is surrendered, but returns without calling
     * {@code CUnitManager::ReleaseUnit} while another order or missile still
     * points at it. {@code CUnitPtr::Reset} performs the final release at the
     * exact action which clears that pointer. Java stores ordinary references,
     * so this sweep is the equivalent reference-count edge; it is called after
     * each unit action, while births from that action are still in
     * {@link #pending}, preserving swap-last order.
     */
    private void releaseUnreferencedDestroyedUnits() {
        for (Unit candidate : List.copyOf(units)) {
            if (candidate.destroyed() && approximateUnitRefs(candidate) <= 1) {
                if (System.getenv("CHONKCRAFT_TRACE_RELEASE_ALL") != null) {
                    System.err.printf("JFINALRELEASE cycle=%d unit=%d type=%s%n",
                            cycle, candidate.id(), candidate.type().ident());
                }
                releaseUnitFromActionTable(candidate);
            }
        }
    }

    /**
     * How far out of a depot a woodcutter looks for its next tree.
     *
     * <p>Ten squares from the one it last worked, as {@code WaitInDepot} uses.
     * "Range hardcoded. don't stray too far though" is upstream's own comment
     * on the number: a worker that came out of the hall and set off across the
     * map would be worse than one that stopped.
     */
    static final int DEPOT_WOOD_RANGE = 10;

    /**
     * The closest building of this player that accepts the resource.
     *
     * <p>By {@code CanStore} and nothing else, which is the whole of
     * upstream's filter: {@code FindDeposit}
     * hands {@code BestDepotFinder} the player's units and takes the nearest
     * that stores the resource. This used to add a rule of its own -- a
     * refinery-harvester may only unload at a building named "refinery" --
     * built on a misreading of {@code RefineryHarvester}, whose one consumer
     * upstream is the AI's building-place finder
     * and whose meaning is that the harvester's <em>mine</em> must be built
     * on top of the resource, the way a platform goes on a patch. It says
     * nothing about where the load goes. Both shipyards declare
     * {@code CanStore = {"oil"}} in the shipped data, so the invented rule
     * left a full tanker with no legal depot on any map where the player
     * built the natural thing -- a shipyard -- and no refinery: the tanker
     * loaded its hundred, found nowhere to take it, and stood down in open
     * water for the rest of the game. Reported from play on the third human
     * mission, and measured: nought oil banked in four simulated minutes
     * with a shipyard five tiles from the platform.
     */
    private Unit nearestDepot(Unit worker, UnitType.Resource resource) {
        Unit best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (Unit candidate : units) {
            if (!candidate.isAlive()
                    || candidate.player() != worker.player()
                    || !candidate.type().storesResource(resource)) {
                continue;
            }
            int distance = worker.distanceTo(candidate);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Marks a short path that ends short of {@code goal} as free-prefix so
     * residual settle replans without PF_WAIT 10.
     */
    void markBattleNetPointFreePrefix(Unit worker,
            PathFinder.Path path, int goalX, int goalY) {
        if (path == null || path.length() == 0) {
            worker.setBattleNetGoldFreePrefix(false);
            return;
        }
        int x = worker.tileX();
        int y = worker.tileY();
        int[] headings = path.headings();
        for (int i = headings.length - 1; i >= 0; i--) {
            x += Direction.deltaX(headings[i]);
            y += Direction.deltaY(headings[i]);
        }
        boolean endsAtGoal = x == goalX && y == goalY;
        boolean freePrefix = !endsAtGoal && path.length() < 20;
        worker.setBattleNetGoldFreePrefix(freePrefix);
        worker.setBattleNetGoldFreePrefixLength(
                freePrefix ? path.length() : 0);
    }

    /**
     * How many unreachable answers a builder's walk absorbs before it gives
     * the job back.
     *
     * <p>{@code MoveToLocation}'s {@code this->State++ < 10} with the walk
     * state entering at one: ten asks in all
     *
     */
    static final int BUILD_ROUTE_TRIES = 10;

    /**
     * The pause between a builder's unreachable answers.
     *
     * <p>"To keep the load low, retry each 1/4 second" --
     * {@code unit.Wait = CYCLES_PER_SECOND / 4}, which is seven
     *
     */
    static final int BUILD_RETRY_WAIT = CYCLES_PER_SECOND / 4;

    static final int RESOURCE_UNREACHABLE_TRIES = 30;

    /**
     * Progress per cycle contributed by a worker inside a building.
     *
     * <p>{@code COrder_Built::Execute} passes 100, and completion is at
     * {@code Costs[TimeCost] * 600}. So a farm costing 100 time takes 600
     * cycles, twenty seconds at thirty cycles a second, which is what
     * Warcraft II's farm takes.
     */
    private static final int BUILD_PROGRESS_PER_CYCLE = 100;

    /** Multiplier turning a type's time cost into progress units. */
    static final int PROGRESS_PER_TIME_UNIT = 600;

    /**
     * Selects BNE's fixed path goal on one axis of a target footprint.
     *
     * <p>This is the coordinate rule in native {@code 0x41f430}. A mover on
     * the near side aims at the near edge, a mover beyond the far side aims
     * at the far edge, and a mover already inside a multi-tile span aims at
     * its centre. The point is chosen when the order is installed and is not
     * recomputed as the worker advances.</p>
     */
    int battleNetFootprintGoal(int mover, int target, int size) {
        if (target < mover) {
            int far = target + size - 1;
            return mover < far ? target + size / 2 : far;
        }
        return target;
    }

    /**
     * Which worker types may put up which buildings.
     *
     * <p>{@code AiHelpers.Build()}, filled by {@code InitAiHelper} from the button table: every
     * {@code DefineButton} whose action is {@code "build"} names the building
     * in its value and the workers that may raise it in its {@code ForUnit}
     * mask. Upstream keeps no such field on a unit type -- the buttons are the
     * declaration -- so this is the same table read the same way, and the
     * engine consults it rather than trusting the panel to have hidden the
     * button.
     *
     * <p>Keyed by building identifier, holding the identifiers allowed to
     * build it.
     */
    java.util.Map<String, java.util.Set<String>> builders = java.util.Map.of();

    /** Supplies the build relation; see {@link #builders}. */
    public void setBuilders(java.util.Map<String, java.util.Set<String>> byBuilding) {
        this.builders = byBuilding == null ? java.util.Map.of() : byBuilding;
    }

    public void setTrainers(java.util.Map<String, java.util.Set<String>> byUnit) {
        this.trainers = byUnit == null ? java.util.Map.of() : byUnit;
    }

    public void setResearchers(java.util.Map<String, java.util.Set<String>> byUpgrade) {
        this.researchers = byUpgrade == null ? java.util.Map.of() : byUpgrade;
    }

    /**
     * Whether a building of this type may research this upgrade.
     *
     * <p>The {@code research} buttons are the relation, exactly as
     * {@link #mayTrain}'s {@code train-unit} buttons are: upstream's
     * {@code AiHelpers.Research()} is built from them and
     * {@code AiCheckResearchRequests} offers a research nowhere else. An
     * empty table means the question was never asked rather than answered
     * no, which keeps every hand-built fixture researching.
     */
    public boolean mayResearch(UnitType researcher, String upgradeIdent) {
        if (researchers.isEmpty() || researcher == null || upgradeIdent == null) {
            return true;
        }
        java.util.Set<String> allowed = researchers.get(upgradeIdent);
        return allowed != null && allowed.contains(researcher.ident());
    }

    /**
     * Whether this building may research this upgrade on this mission.
     *
     * <p>The research buttons are only the pair. Human 1 forbids sword1
     * even at a blacksmith, and later missions still wait on the tree.
     * Asking only the button let a typed packet buy what the panel hid.
     */
    public boolean mayResearch(Unit researcher, String upgradeIdent) {
        if (researcher == null || !mayResearch(researcher.type(), upgradeIdent)) {
            return false;
        }
        return productionRefusal(researcher.player(), upgradeIdent) == null;
    }

    private java.util.Map<String, java.util.Set<String>> researchers = java.util.Map.of();

    /**
     * The two race-equivalence directions, from {@code scripts/wc2.legacy-declaration}'s
     * own table: orc ident to human ident and back.
     */
    public void setRaceEquivalents(java.util.Map<String, String> toHuman,
            java.util.Map<String, String> toOrc) {
        this.toHumanEquivalent = toHuman == null ? java.util.Map.of() : toHuman;
        this.toOrcEquivalent = toOrc == null ? java.util.Map.of() : toOrc;
    }

    private java.util.Map<String, String> toHumanEquivalent = java.util.Map.of();

    private java.util.Map<String, String> toOrcEquivalent = java.util.Map.of();

    /**
     * Whether a building of this type may train this unit.
     *
     * <p>The {@code train-unit} buttons are the whole of the relation, which
     * is how upstream knows it too: {@code AiHelpers.Train()} is built from
     * them and {@code AiTrainUnit} offers a training nowhere else. Nothing
     * else said so here, so an AI whose shipyard was busy trained its oil
     * tanker at a pig farm -- the farm is a building and was idle, and that
     * was the whole of the old test.
     *
     * <p>As {@link #mayBuild}: an empty table means the question was never
     * asked rather than answered no, so every hand-built fixture keeps
     * training. {@code GameData.loadMission} always supplies the table.
     */
    public boolean mayTrain(UnitType trainer, UnitType what) {
        if (trainers.isEmpty() || trainer == null || what == null) {
            return true;
        }
        java.util.Set<String> allowed = trainers.get(what.ident());
        return allowed != null && allowed.contains(trainer.ident());
    }

    /**
     * Whether this building may train this unit on this mission.
     *
     * <p>The train-unit buttons are only who can produce. Human 1's hall
     * still names a knight in that table, and the mission's allow string
     * is what actually forbids it. Asking only the pair trained the
     * untaught roster from a typed command.
     */
    public boolean mayTrain(Unit trainer, UnitType what) {
        if (trainer == null || what == null || !mayTrain(trainer.type(), what)) {
            return false;
        }
        return productionRefusal(trainer.player(), what.ident()) == null;
    }

    /**
     * Why this player cannot have {@code ident} yet, or null when they can.
     *
     * <p>Two different tables answer. The allow string is the mission's
     * teaching order; the dependency list is the tech tree. The panel
     * already asked both, but {@link #orderTrain} used to ask neither.
     */
    public String productionRefusal(int player, String ident) {
        if (ident == null) {
            return "unknown";
        }
        if (allowed != null && !allowed.isAllowed(player, ident)) {
            return "forbidden";
        }
        if (!dependenciesSatisfied(player, ident)) {
            return "unmet-dependency";
        }
        return null;
    }

    /** Trainer identifiers in {@code AiHelpers.Train()} button order. */
    public java.util.List<String> trainerTypeOrder(UnitType what) {
        if (trainers.isEmpty() || what == null) {
            return java.util.List.of();
        }
        java.util.Set<String> allowed = trainers.get(what.ident());
        return allowed == null ? java.util.List.of() : java.util.List.copyOf(allowed);
    }

    private java.util.Map<String, java.util.Set<String>> trainers = java.util.Map.of();


    /**
     * The unit an on-top rule says has to be underneath, or {@code null}.
     *
     * <p>Implements {@code CBuildRestrictionOnTop::Check}
     * Three conditions, and the third is the one
     * that is easy to leave out: the parent has to be of the named type, its
     * own top-left corner has to be the square being built on -- a platform
     * covers a patch exactly, it is not laid over a corner of it -- and
     * nothing else of the same movement kind may be standing anywhere inside
     * the parent's footprint. That last is what stops a platform being founded
     * on a patch a destroyer happens to be sitting on. The builder itself is
     * skipped, because a tanker is standing on the patch by the time it starts
     * work.
     *
     * <p>Asked of the map's own unit cache rather than of the roster, which is
     * what upstream reads too ({@code Map.Field(pos)->UnitCache}).
     *
     * <p>{@code isAlive()} is deliberately not used for the parent.
     * Upstream's {@code CUnit::IsAlive} is {@code !Destroyed &&
     * CurrentAction() != UnitAction::Die} and this
     * port's folds in {@code hitPoints > 0}, which an oil patch fails by
     * design -- it is declared with {@code HitPoints = 0}. Asking it here
     * would refuse every oil patch in the game and put the platform back
     * exactly where it started.
     */
    Unit onTopTarget(Unit builder,
            net.chonkbase.chonkcraft.engine.unit.BuildRestriction.OnTop rule,
            int tileX, int tileY) {
        Unit parent = null;
        for (Unit candidate : unitsOn(tileX, tileY)) {
            if (candidate.type() == null || !candidate.type().ident().equals(rule.parentIdent())) {
                continue;
            }
            if (!candidate.isOnMap() || candidate.isDying()
                    || candidate.order() == Unit.Order.UNDER_CONSTRUCTION) {
                continue;
            }
            parent = candidate;
            break;
        }
        if (parent == null || parent.tileX() != tileX || parent.tileY() != tileY) {
            return null;
        }
        UnitType parentType = parent.type();
        for (int dy = 0; dy < Math.max(1, parentType.tileHeight()); dy++) {
            for (int dx = 0; dx < Math.max(1, parentType.tileWidth()); dx++) {
                for (Unit other : unitsOn(tileX + dx, tileY + dy)) {
                    if (other == parent || other == builder || other.type() == null) {
                        continue;
                    }
                    if (other.type().moveType() == parentType.moveType()) {
                        return null;
                    }
                }
            }
        }
        return parent;
    }

    /**
     * Whether a distance rule allows a site.
     *
     * <p>Implements {@code CBuildRestrictionDistance::Check}
     * reduced to the comparisons the data uses
     * and keeping its structure: every unit of the named type within the
     * search box is measured with {@code MapDistanceBetweenTypes}, a
     * "greater than" rule fails on the first one that is too close, and a
     * "less than" rule passes on the first one that is close enough.
     *
     * <p>This is the rule a player meets as "you cannot put the town hall on
     * the gold mine". Every hall, keep and castle in both tech trees carries
     * {@code Distance = 3, DistanceType = ">", Type = "unit-gold-mine"}, and
     * the shipyards and refineries carry the same three squares' clearance
     * from oil patches and oil platforms. Nothing in the implementation read them, so a
     * peasant could wall a mine in behind its own town hall and a shipyard
     * could be dropped straight onto the patch a refinery was wanted on.
     *
     * <p>Every unit is measured rather than only those in a box around the
     * site, which is the one thing here that departs from upstream: its
     * {@code Select(pos1, pos2)} reads the map's unit cache over a rectangle
     * reaching {@code Distance} beyond the footprint. The answers cannot
     * differ -- a unit whose footprint lies wholly outside that rectangle is
     * further than {@code Distance} away by construction, so the wider sweep
     * adds candidates that all fail the comparison -- and the cost was
     * measured rather than assumed: on {@code level12h}, 128 by 128 with 185
     * units, a site test for a type carrying a distance rule costs 517ns
     * against 61ns for one carrying none, and the computer's site search asks
     * at most about a thousand of them a second and stops at the first that
     * passes.
     */
    boolean passesDistanceRule(Unit builder, UnitType what,
            net.chonkbase.chonkcraft.engine.unit.BuildRestriction.Distance rule,
            int tileX, int tileY) {
        var comparison = rule.comparison();
        boolean greater = comparison
                        == net.chonkbase.chonkcraft.engine.unit.BuildRestriction
                                .Comparison.GREATER_THAN
                || comparison == net.chonkbase.chonkcraft.engine.unit.BuildRestriction
                        .Comparison.GREATER_THAN_EQUAL;
        boolean strict = comparison == net.chonkbase.chonkcraft.engine.unit.BuildRestriction
                        .Comparison.LESS_THAN
                || comparison == net.chonkbase.chonkcraft.engine.unit.BuildRestriction
                        .Comparison.GREATER_THAN_EQUAL;
        // The two strict forms measure against one less and search one square
        // wider, which is upstream's way of turning "< n" into "<= n - 1".
        int distance = strict ? rule.distance() - 1 : rule.distance();
        boolean negated = comparison == net.chonkbase.chonkcraft.engine.unit.BuildRestriction
                .Comparison.NOT_EQUAL;
        boolean exact = negated || comparison == net.chonkbase.chonkcraft.engine.unit.BuildRestriction
                .Comparison.EQUAL;
        for (Unit other : units) {
            if (other.type() == null || !other.isOnMap() || other.isDying()) {
                continue;
            }
            if (other == builder && !rule.checkBuilder()) {
                continue;
            }
            if (!other.type().ident().equals(rule.restrictIdent())) {
                continue;
            }
            int gap = Unit.distanceBetween(what, tileX, tileY, other.type(),
                    other.tileX(), other.tileY());
            boolean hit = exact ? gap == distance : gap <= distance;
            if (!hit) {
                continue;
            }
            // A unit off both axes only counts when the rule is diagonal,
            // which is upstream's default and what all 65 shipped rules use.
            if (!rule.diagonal() && tileX != other.tileX() && tileY != other.tileY()) {
                continue;
            }
            if (greater || negated) {
                return false;
            }
            return true;
        }
        // Nothing in the way. A "greater than" or "not equal" rule is
        // satisfied by finding nothing; a "less than" or "equal" one is not.
        return greater || negated;
    }

    /**
     * {@code HasAtLeastOneCoastTile}: a shore building
     * has to touch the water somewhere under its own footprint.
     */
    boolean hasAtLeastOneCoastTile(UnitType what, int tileX, int tileY) {
        for (int dy = 0; dy < Math.max(1, what.tileHeight()); dy++) {
            for (int dx = 0; dx < Math.max(1, what.tileWidth()); dx++) {
                MapField field = map.fieldOrNull(tileX + dx, tileY + dy);
                if (field != null && field.hasFlag(TileFlag.COAST_ALLOWED)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Every unit the map cache holds on a square, never null. */
    private List<Unit> unitsOn(int tileX, int tileY) {
        if (!map.contains(tileX, tileY)) {
            return List.of();
        }
        List<Unit> cached = unitCache.get(tileX + tileY * map.width());
        return cached == null ? List.of() : cached;
    }

    /**
     * Takes away the thing a building was founded on top of.
     *
     * <p>{@code COrder_Build::StartBuilding}
     * runs {@code ontop.Remove(nullptr); UnitLost(ontop); UnitClearOrders(ontop);
     * ontop.Release();} -- taken off the map and released, not killed. That
     * distinction is the point: an oil patch built over leaves no rubble, makes
     * no explosion, plays no death sound and scores nobody any points. It is
     * not destroyed, it is underneath a platform now.
     *
     * <p>Marked for the end-of-tick sweep rather than pulled out of the roster
     * here, because this runs inside the cycle's own walk of that list.
     */
    void removeReplacedParent(Unit parent) {
        markOccupancy(parent, false);
        markSight(parent, false);
        unregisterPlayerUnit(parent);
        parent.clearPath();
        parent.clearQueuedOrders();
        parent.setTarget(null);
        parent.setSelected(false);
        parent.setRemoved(true);
        parent.setOrder(Unit.Order.DYING);
        parent.setDeathTimer(0);
    }

    /**
     * Puts back the thing a destroyed building had been founded on top of.
     *
     * <p>Implements the tail of {@code UnitLost},
     * which is headed "Destroy resource-platform, must re-make resource patch."
     * An oil platform blown up over a patch that still holds oil leaves the
     * patch behind, carrying what was left, owned by nobody -- so the ground
     * can be built on again and the oil is not lost with the building. Without
     * it a bombed platform takes the whole oil field with it and the map is
     * permanently poorer, which is not how a naval mission is meant to be
     * fought.
     *
     * <p>Upstream's three conditions are kept exactly: the rule has to say
     * {@code ReplaceOnDie}, the dying type has to be a source of something, and
     * it has to still be holding some. A platform pumped dry leaves nothing.
     */
    private void replaceOnDie(Unit unit) {
        UnitType type = unit.type();
        if (type == null || unitTypes == null) {
            return;
        }
        net.chonkbase.chonkcraft.engine.unit.BuildRestriction.OnTop rule = type.onTopRule();
        if (rule == null || !rule.replaceOnDie()) {
            return;
        }
        if (type.givesResource() == null || unit.resourcesHeld() == 0) {
            return;
        }
        UnitType parentType = unitTypes.get(rule.parentIdent());
        if (parentType == null) {
            return;
        }
        Unit parent = createUnit(parentType, NEUTRAL_PLAYER, unit.tileX(), unit.tileY());
        if (parent != null) {
            parent.setResourcesHeld(unit.resourcesHeld());
        }
    }

    /**
     * Cycles between retail BNE construction HP boosts after the first
     * climb. XOrc 10 farm 1426: founded fixture c22 at HP 40, first boost
     * +3 at c33, then +4 at c45, +3 at c57 (twelve-cycle period).
     */
    static final int BATTLE_NET_CONSTRUCTION_BOOST_PERIOD = 12;

    /**
     * Starts training a unit at a building.
     *
     * @return whether the order was accepted
     */
    public boolean orderTrain(Unit building, UnitType what) {
        boolean traceTraining = System.getenv("CHONKCRAFT_TRACE_TRAIN") != null;
        if (traceTraining) {
            System.err.println("JTRAIN offer cycle=" + cycle + " p=" + building.player()
                    + " building=" + building.id() + ":" + building.type().ident()
                    + " what=" + (what == null ? "-" : what.ident())
                    + " action=" + building.currentAction()
                    + " queued=" + building.hasQueuedOrders()
                    + " before=" + building.reportsActionBeforeQueued()
                    + " producing=" + (building.producing() == null ? "-"
                            : building.producing().ident()));
        }
        if (what == null || !building.type().building()
                || building.order() != Unit.Order.STILL || !building.isAlive()
                || building.researching() != null || building.upgradingTo() != null
                || (building.producing() != null && !trainingQueueEnabled)
                || building.trainingJobCount() >= Unit.MAX_TRAINING_JOBS) {
            if (traceTraining) {
                System.err.println("JTRAIN reject-state cycle=" + cycle
                        + " building=" + building.id());
            }
            return false;
        }
        if (!mayTrain(building, what)) {
            if (traceTraining) {
                System.err.println("JTRAIN reject-relation cycle=" + cycle
                        + " building=" + building.id()
                        + " why=" + productionRefusal(building.player(), what.ident()));
            }
            return false;
        }
        // Queued like every other command: CommandTrainUnit's GetNextOrder is
        // EFlushMode::Off, so the order lands behind the building's still one
        // and pops on the next cycle -- the building reads still for the rest
        // of this one and train from the next, which is the cycle the label
        // showed on both engines' traces once this was in.
        building.rememberActionBeforeQueued(building.order());
        Player player = players[building.player()];
        recalculateSupply();
        int reservedDemand = reservedTrainingDemand(building.player());
        if (traceTraining) {
            System.err.println("JTRAIN limits cycle=" + cycle + " p=" + building.player()
                    + " supply=" + player.supply() + " demand=" + player.demand()
                    + " reserved=" + reservedDemand + " new=" + what.demand());
        }
        if (!player.hasSupplyRoom(reservedDemand + what.demand())) {
            if (traceTraining) {
                System.err.println("JTRAIN reject-supply cycle=" + cycle
                        + " building=" + building.id() + " supply=" + player.supply()
                        + " demand=" + player.demand() + " reserved=" + reservedDemand);
            }
            return false;
        }
        java.util.Map<UnitType.Resource, Integer> costs = unitCosts(what);
        if (!player.pay(costs)) {
            if (traceTraining) {
                System.err.println("JTRAIN reject-cost cycle=" + cycle
                        + " building=" + building.id() + " costs=" + costs);
            }
            return false;
        }
        if (traceTraining) {
            System.err.println("JTRAIN paid cycle=" + cycle + " p=" + building.player()
                    + " building=" + building.id() + " what=" + what.ident()
                    + " costs=" + costs);
        }
        if (building.producing() != null) {
            building.enqueueTraining(what);
            return true;
        }
        startTraining(building, what);
        return true;
    }

    /** Demand already promised by paid jobs at all of this player's buildings. */
    private int reservedTrainingDemand(int player) {
        int demand = 0;
        for (Unit unit : units) {
            if (unit.player() != player) {
                continue;
            }
            if (unit.producing() != null) {
                demand += unit.producing().demand();
            }
            for (UnitType queued : unit.trainingQueue()) {
                demand += queued.demand();
            }
        }
        return demand;
    }

    /** Makes one already-paid job the building's active training work. */
    private void startTraining(Unit building, UnitType what) {
        building.setProducing(what);
        building.setProgress(0);
        building.setProgressGoal(
                unitCosts(what).getOrDefault(UnitType.Resource.TIME, 1) * PROGRESS_PER_TIME_UNIT);
    }

    /**
     * Starts a building becoming a better one: a Keep from a Town Hall.
     *
     * <p>Not training. This used to call {@link #orderTrain}, which charges the
     * cost and then puts a whole new building on the ground beside the old one
     * -- and since the spot search only ever tested a single square, a four by
     * four Keep was jammed against the Town Hall that was supposed to have
     * become it, overlapping whatever stood there, with the original still
     * standing. Every tier of both tech trees behaved that way.
     *
     * <p>Upstream counts to the new type's time cost and then transforms the
     * unit where it stands.
     */
    public boolean orderUpgradeTo(Unit building, UnitType what) {
        if (what == null || building == null || !building.isAlive()
                || !building.type().building()
                || building.order() != Unit.Order.STILL
                || building.producing() != null || building.upgradingTo() != null) {
            return false;
        }
        if (productionRefusal(building.player(), what.ident()) != null) {
            return false;
        }
        Player player = players[building.player()];
        java.util.Map<UnitType.Resource, Integer> costs = unitCosts(what);
        if (!player.pay(costs)) {
            return false;
        }
        // The same one-cycle label convention as training and research.
        building.rememberActionBeforeQueued(building.order());
        building.setUpgradingTo(what);
        building.setOrderFinished(false);
        building.setProgress(0);
        building.setProgressGoal(
                costs.getOrDefault(UnitType.Resource.TIME, 1) * PROGRESS_PER_TIME_UNIT);
        return true;
    }

    /** Abandons an upgrade in progress, refunding as a cancelled training does. */
    public boolean cancelUpgradeTo(Unit building) {
        UnitType what = building.upgradingTo();
        if (what == null) {
            return false;
        }
        building.setUpgradingTo(null);
        building.setOrderFinished(false);
        building.setProgress(0);
        refund(building.player(), unitCosts(what), CANCEL_TRAINING_REFUND);
        return true;
    }

    /**
     * Turns a unit into another kind of unit where it stands.
     *
     * <p>The map's bookkeeping is unwound before the change and redone after,
     * because sight range and footprint both come from the type: changing it
     * underneath the marks would leave a Keep seeing a Town Hall's distance and
     * occupying a Town Hall's squares.
     *
     * <p>Damage carries across in proportion rather than in absolute points. A
     * Town Hall at half health becomes a Keep at half health; keeping the
     * number instead would make every upgrade a repair, or leave a bigger
     * building nearly dead.
     */
    public boolean transformInto(Unit unit, UnitType wanted) {
        if (unit == null || wanted == null || !unit.isAlive()) {
            return false;
        }
        UnitType registered = registeredUnitType(wanted.ident());
        if (registered != null) {
            wanted = registered;
        }
        UnitType was = unit.type();
        double share = was.hitPoints() <= 0
                ? 1.0
                : unit.hitPoints() / (double) was.hitPoints();

        markSight(unit, false);
        markOccupancy(unit, false);
        unit.becomeType(wanted);
        unit.setHitPoints(Math.max(1, (int) Math.round(wanted.hitPoints() * share)));
        markOccupancy(unit, true);
        unitCountSeen(unit);
        markSight(unit, true);
        recalculateSupply();
        return true;
    }

    /** Whether a building is part way through training something. */
    public boolean isTraining(Unit building) {
        return building.type() != null && building.type().building()
                && building.producing() != null && building.order() == Unit.Order.STILL;
    }

    /** Advances any training in progress at a building. */
    boolean stepTraining(Unit building) {
        if (building.type() == null || !building.type().building()) {
            return false;
        }
        if (building.upgradingTo() != null) {
            stepWorkAnimation(building, AnimationSet.State.UPGRADE);
            stepUpgradeTo(building);
            return true;
        }
        if (building.producing() == null) {
            return false;
        }
        stepWorkAnimation(building, AnimationSet.State.TRAIN);
        building.setProgress(building.progress() + BUILD_PROGRESS_PER_CYCLE);
        // The last time unit is not served: COrder_Train ticks on its first
        // execute and completes the moment Ticks reaches the cost, so a
        // trainee of time cost N takes (N - 1) * 6 + 1 cycles, not N * 6
        // level08h's second peon starts at 37
        // and steps out at 272 upstream, five cycles before a port that
        // waited out the whole final beat.
        if (building.progress() + PROGRESS_PER_TIME_UNIT - BUILD_PROGRESS_PER_CYCLE
                < building.progressGoal()) {
            return true;
        }
        UnitType what = building.producing();
        UnitType registered = registeredUnitType(what.ident());
        if (registered != null) {
            what = registered;
        }
        // The completing cycle still reads as training: upstream's order
        // spawns the unit and is only Finished, popping to Still on the
        // next cycle's advance -- level08h's great hall shows train at 272,
        // the very cycle its peon steps out, and still from 273.
        if (building.orderFinished()) {
            building.setOrderFinished(false);
            building.setProducing(null);
            building.setProgress(0);
            UnitType next = building.pollTraining();
            if (next != null) {
                startTraining(building, next);
            }
            return true;
        }
        // The game a trained unit comes out of the west face.
        int[] spot = dropOutOnSide(what, LOOKING_WEST, building,
                building.tileX(), building.tileY());
        if (spot == null) {
            // Nowhere to put it; hold at full progress and try again next
            // cycle, which is what the game does when a base is walled in.
            return true;
        }
        building.setOrderFinished(true);
        Unit trained = createUnit(what, building.player(), spot[0], spot[1]);
        if (trained != null) {
            // AiTrainingComplete -> AiRemoveFromBuilt:
            // the queue entry that held this job lets go of one want, and the
            // new unit is assigned to a waiting force in the same call.
            net.chonkbase.chonkcraft.engine.ai.AiPlayer trainerAi = ais().get(building.player());
            if (trainerAi != null) {
                trainerAi.trainingComplete(this, trained);
            }
            recalculateSupply();
            announce(trained, "ready");
            // Straight to the rally point, if the building has one.
            if (building.hasRallyPoint() && trained.type().speed() > 0) {
                movement.orderMove(trained, building.rallyX(), building.rallyY());
            }
            // The type's ready moment: a trained scout flyer goes exploring
            // Upstream fires this before the rally
            // handoff and queues the explore behind it, so a rallied flyer
            // flies to the post first; this implementation fires after and the explore
            // wins outright. No shipped AI sets a rally point, so the
            // difference waits for a person to rally a scout factory.
            fireOnReady(trained);
        }
        return true;
    }

    /** Counts a building towards becoming what it is turning into. */
    private void stepUpgradeTo(Unit building) {
        // COrder_UpgradeTo marks itself Finished on the transformation tick;
        // HandleUnitAction does not pop it until this building's following
        // turn. Keep the target as the order marker through that cycle, just
        // as stepResearch and stepTraining keep their work markers. Seven
        // expansion missions first exposed this as stronghold UpgradeTo vs
        // Still on cycles 1202-1208.
        if (building.orderFinished()) {
            building.setOrderFinished(false);
            building.setUpgradingTo(null);
            building.setProgress(0);
            return;
        }
        building.setProgress(building.progress() + BUILD_PROGRESS_PER_CYCLE);
        // The same first-tick arithmetic as training: COrder_UpgradeTo ticks
        // before it waits and completes the moment Ticks reaches the cost
        // so the last time unit is never
        // served.
        if (building.progress() + PROGRESS_PER_TIME_UNIT - BUILD_PROGRESS_PER_CYCLE
                < building.progressGoal()) {
            return;
        }
        UnitType wanted = building.upgradingTo();
        building.setProgress(0);
        if (transformInto(building, wanted)) {
            announce(building, "ready");
        }
        building.setOrderFinished(true);
    }

    // ------------------------------------------------------------ dropping out

    /**
     * Headings, north then clockwise, as {@link Unit#heading()} counts them.
     *
     * <p>Upstream measures a facing in 256ths of a turn -- {@code LookingN} is
     * 0, {@code LookingW} is 6*32 -- and the drop-out code compares against
     * those constants. Eight headings divide the same circle, so heading times
     * thirty-two is the upstream direction and the comparisons below are the
     * same comparisons.
     */
    public static final int LOOKING_NORTH = 0;
    public static final int LOOKING_WEST = 6;

    /** The four legs of the ring, in the order the search walks them. */
    private static final int LEG_WEST = 0;
    private static final int LEG_SOUTH = 1;
    private static final int LEG_EAST = 2;
    private static final int LEG_NORTH = 3;

    /**
     * How many times the ring may grow before the search gives up.
     *
     * <p>Upstream loops forever and carries a {@code FIXME: don't search
     * outside of the map} for it: a base walled in on every side hangs the
     * engine. The ring cannot usefully outgrow the map, so that is the bound.
     */
    private int maxRings() {
        return Math.max(map.width(), map.height()) + 2;
    }

    /**
     * Where a unit appears beside another, given the side it should come out.
     *
     * <p>Implements {@code DropOutOnSide}. The search is
     * a ring around the container's footprint, walked in a fixed order --
     * west column north-to-south, south row west-to-east, east column
     * south-to-north, north row east-to-west -- entered at whichever leg the
     * heading names, and growing by one square on each side per turn of the
     * spiral. First free square wins.
     *
     * <p>What this replaces scanned the bounding box row by row from the
     * north-west corner, so everything the game drops out appeared at a
     * building's top-left corner: a footman trained at a barracks came out of
     * its north-west corner rather than its west face, which is where fourteen
     * of them queue in a line upstream.
     *
     * @param type      what is being placed, for its footprint and its terrain
     * @param heading   the side to start from, 0 to 7 north-clockwise
     * @param container what it is coming out of, or {@code null} to spiral out
     *                  from {@code startX,startY} itself
     * @return the tile to place the unit's top-left at, or {@code null}
     */
    int[] dropOutOnSide(UnitType type, int heading, Unit container,
            int startX, int startY) {
        return dropOutOnSide(type, heading, container, startX, startY, maxRings());
    }

    int[] dropOutOnSide(UnitType type, int heading, Unit container,
            int startX, int startY, int rings) {
        long mask = Unit.movementMaskFor(type);
        long blocking = Unit.blockingFlagsFor(type);
        int width = Math.max(1, type.tileWidth());
        int height = Math.max(1, type.tileHeight());

        int x;
        int y;
        int addx;
        int addy;
        int leg;
        if (container != null) {
            // The ring is expressed in top-left corners, so the unit's own
            // size is subtracted out: a two-by-two unit has to start a square
            // further out to end up touching the container.
            x = container.tileX() - (width - 1);
            y = container.tileY() - (height - 1);
            addx = Math.max(1, container.type().tileWidth()) + width - 1;
            addy = Math.max(1, container.type().tileHeight()) + height - 1;
            switch (sideOf(heading)) {
                case LEG_NORTH -> {
                    x += addx - 1;
                    y -= 1;
                    leg = LEG_NORTH;
                }
                case LEG_EAST -> {
                    x += addx;
                    y += addy - 1;
                    leg = LEG_EAST;
                }
                case LEG_SOUTH -> {
                    y += addy;
                    leg = LEG_SOUTH;
                }
                default -> {
                    x -= 1;
                    leg = LEG_WEST;
                }
            }
        } else {
            // No container: the spiral starts on the unit's own square and the
            // entry leg is the one that reaches the named side first.
            x = startX;
            y = startY;
            addx = 0;
            addy = 0;
            leg = switch (sideOf(heading)) {
                case LEG_NORTH -> LEG_SOUTH;
                case LEG_EAST -> LEG_WEST;
                case LEG_SOUTH -> LEG_NORTH;
                default -> LEG_EAST;
            };
        }

        // Four legs to a turn of the spiral, and the caller's ring budget
        // counts turns.
        for (int step = 0; step < rings * 4; step++) {
            switch (leg) {
                case LEG_WEST -> {
                    for (int i = addy; i-- > 0; y++) {
                        if (map.isFootprintFree(x, y, width, height, mask, blocking)) {
                            return new int[] {x, y};
                        }
                    }
                    addx++;
                }
                case LEG_SOUTH -> {
                    for (int i = addx; i-- > 0; x++) {
                        if (map.isFootprintFree(x, y, width, height, mask, blocking)) {
                            return new int[] {x, y};
                        }
                    }
                    addy++;
                }
                case LEG_EAST -> {
                    for (int i = addy; i-- > 0; y--) {
                        if (map.isFootprintFree(x, y, width, height, mask, blocking)) {
                            return new int[] {x, y};
                        }
                    }
                    addx++;
                }
                default -> {
                    for (int i = addx; i-- > 0; x--) {
                        if (map.isFootprintFree(x, y, width, height, mask, blocking)) {
                            return new int[] {x, y};
                        }
                    }
                    addy++;
                }
            }
            leg = (leg + 1) & 3;
        }
        return null;
    }

    /**
     * Which side of a container a heading names.
     *
     * <p>The upstream comparisons, in eighths of a turn. North is north alone;
     * north-east and east are the east face; south-east and south the south
     * face; and the remaining three -- south-west, west, north-west -- the
     * west face. The bands are uneven because the constants they came from
     * are: {@code heading < LookingNE} is one heading wide and
     * {@code heading >= LookingSW} is three.
     */
    private static int sideOf(int heading) {
        int facing = Math.floorMod(heading, 8);
        if (facing == 0) {
            return LEG_NORTH;
        }
        if (facing < 3) {
            return LEG_EAST;
        }
        if (facing < 5) {
            return LEG_SOUTH;
        }
        return LEG_WEST;
    }

    /**
     * Where a unit appears beside another, as close as it can get to a point.
     *
     * <p>Implements {@code DropOutNearest}. The same ring
     * as {@link #dropOutOnSide}, always entered from the west, but it does not
     * stop at the first free square: it walks the whole turn of the spiral and
     * keeps the square closest to a goal, growing the ring only if the turn
     * found nothing at all.
     *
     * <p>This is what every resource emergence uses upstream, and it is the
     * difference the player notices. A peasant coming out of a gold mine
     * leaves by the face pointing at the hall it is carrying to, and a peasant
     * leaving the hall comes out of the face pointing back at the mine. With
     * first-free-wins from the north-west it always left by the same corner
     * however the base was laid out, so half the round trips walked the long
     * way round the building they had just been standing in.
     *
     * <p>The distance is the squared distance between the goal point and the
     * candidate's own tile, which is what upstream compares here. It is
     * deliberately not {@link Unit#distanceTo}: nothing is placed yet, so
     * there is no footprint to measure between, and the goal is a point
     * rather than a unit -- callers pass a building's centre square.
     *
     * @return the tile to place the unit's top-left at, or {@code null}
     */
    int[] dropOutNearest(UnitType type, int goalX, int goalY, Unit container,
            int startX, int startY) {
        long mask = Unit.movementMaskFor(type);
        long blocking = Unit.blockingFlagsFor(type);
        int width = Math.max(1, type.tileWidth());
        int height = Math.max(1, type.tileHeight());

        int x;
        int y;
        int addx;
        int addy;
        if (container != null) {
            x = container.tileX() - (width - 1) - 1;
            y = container.tileY() - (height - 1);
            addx = Math.max(1, container.type().tileWidth()) + width - 1;
            addy = Math.max(1, container.type().tileHeight()) + height - 1;
        } else {
            x = startX;
            y = startY;
            addx = 0;
            addy = 0;
        }

        int[] best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (int ring = 0; ring < maxRings(); ring++) {
            for (int i = addy; i-- > 0; y++) {
                if (map.isFootprintFree(x, y, width, height, mask, blocking)) {
                    int d = squareDistance(goalX, goalY, x, y);
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = new int[] {x, y};
                    }
                }
            }
            addx++;
            for (int i = addx; i-- > 0; x++) {
                if (map.isFootprintFree(x, y, width, height, mask, blocking)) {
                    int d = squareDistance(goalX, goalY, x, y);
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = new int[] {x, y};
                    }
                }
            }
            addy++;
            for (int i = addy; i-- > 0; y--) {
                if (map.isFootprintFree(x, y, width, height, mask, blocking)) {
                    int d = squareDistance(goalX, goalY, x, y);
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = new int[] {x, y};
                    }
                }
            }
            addx++;
            for (int i = addx; i-- > 0; x--) {
                if (map.isFootprintFree(x, y, width, height, mask, blocking)) {
                    int d = squareDistance(goalX, goalY, x, y);
                    if (d < bestDistance) {
                        bestDistance = d;
                        best = new int[] {x, y};
                    }
                }
            }
            if (best != null) {
                if (System.getenv("CHONKCRAFT_TRACE_DROPOUT") != null) {
                    System.err.printf("JDROP cycle=%d container=%d at=%d,%d"
                                    + " goal=%d,%d chose=%d,%d addx=%d addy=%d%n",
                            cycle, container == null ? -1 : container.id(),
                            container == null ? startX : container.tileX(),
                            container == null ? startY : container.tileY(),
                            goalX, goalY, best[0], best[1], addx, addy);
                }
                return best;
            }
            addy++;
        }
        return null;
    }

    /**
     * Repairs only a restored tanker state that cannot produce a native route.
     *
     * <p>This is intentionally not part of live platform dropout. Valid live
     * exits are observable BNE behavior and must keep their selected square.
     * A historical save can, however, contain an action-24 tanker on an odd
     * anchor from which the doubled pathfinder has no route at all. In that
     * compatibility-only case, choose the nearest free absolute-even water
     * anchor on the side toward the depot and resume the authoritative native
     * substate from there.</p>
     */
    void repairRestoredOilAnchor(Unit tanker, Unit towards) {
        if (tanker == null || tanker.type() == null || towards == null
                || !tanker.type().gathering().containsKey(UnitType.Resource.OIL)) {
            return;
        }
        boolean offNativeLattice = ((tanker.tileX() | tanker.tileY()) & 1) != 0;
        boolean routeDead = construction.findBattleNetBuildingPath(tanker, towards).result()
                == PathFinder.Result.UNREACHABLE;
        if (!offNativeLattice && !routeDead) {
            return;
        }
        int[] goal = centreOf(towards);
        markSight(tanker, false);
        markOccupancy(tanker, false);
        int[] repaired = null;
        int bestDistance = Integer.MAX_VALUE;
        int width = Math.max(1, tanker.type().tileWidth());
        int height = Math.max(1, tanker.type().tileHeight());
        for (int radius = 1; radius <= 3 && repaired == null; radius++) {
            for (int y = tanker.tileY() - radius; y <= tanker.tileY() + radius; y++) {
                for (int x = tanker.tileX() - radius; x <= tanker.tileX() + radius; x++) {
                    if (Math.max(Math.abs(x - tanker.tileX()),
                            Math.abs(y - tanker.tileY())) != radius
                            || ((x | y) & 1) != 0
                            || !map.contains(x, y)
                            || !map.isFootprintFree(x, y, width, height,
                                    tanker.movementMask(), tanker.blockingFlags())) {
                        continue;
                    }
                    int distance = squareDistance(goal[0], goal[1], x, y);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        repaired = new int[] {x, y};
                    }
                }
            }
        }
        if (repaired != null) {
            tanker.setTile(repaired[0], repaired[1]);
        }
        markOccupancy(tanker, true);
        markSight(tanker, true);
    }

    /**
     * Where a passenger lands beside its transport.
     *
     * <p>{@code FindUnloadPosition}: the same
     * ring entered from the west and taking the first free square, but with a
     * {@code maxRange} of one, so a passenger only ever steps off onto ground
     * touching the boat. Anything further out stays aboard, which is what
     * stops a landing party appearing halfway up a beach it never sailed to.
     */
    private int[] unloadPosition(Unit transport, Unit passenger) {
        return unloadPositionAt(transport, passenger,
                transport.tileX(), transport.tileY(), 1);
    }

    /**
     * The same search, from a square the transport is not standing on yet.
     *
     * <p>Implements {@code FindUnloadPosition},
     * rather than a call into
     * {@link #dropOutOnSide}. Upstream keeps these two separate and they are
     * not the same spiral: {@code DropOutOnSide} sizes its ring
     * {@code container + unit - 2}, this one {@code transporter + unit - 1}, so
     * this reaches one square further round each face. Sharing the other made
     * the implementation a square tighter than the original everywhere it mattered.
     *
     * <p>Taking the origin as an argument is what {@link #closestFreeDropZone}
     * needs: it has to ask "could anybody get off if the boat were <em>here</em>",
     * about squares the boat has not sailed to. Routing that through
     * {@code dropOutOnSide} silently answered about where the boat actually
     * was, because that method derives the ring from the container's own
     * position and ignores the coordinates it is passed.
     *
     * @return the tile to place the passenger's top-left at, or {@code null}
     */
    private int[] unloadPositionAt(Unit transport, Unit passenger,
            int originX, int originY, int maxRange) {
        UnitType type = passenger.type();
        long mask = Unit.movementMaskFor(type);
        long blocking = Unit.blockingFlagsFor(type);
        int width = Math.max(1, type.tileWidth());
        int height = Math.max(1, type.tileHeight());

        int x = originX - (width - 1) - 1;
        int y = originY - (height - 1);
        int addx = Math.max(1, transport.type().tileWidth()) + width - 1;
        int addy = Math.max(1, transport.type().tileHeight()) + height - 1;

        for (int range = 0; range < maxRange; range++) {
            for (int i = addy; i-- > 0; y++) {
                if (map.isFootprintFree(x, y, width, height, mask, blocking)) {
                    return new int[] {x, y};
                }
            }
            addx++;
            for (int i = addx; i-- > 0; x++) {
                if (map.isFootprintFree(x, y, width, height, mask, blocking)) {
                    return new int[] {x, y};
                }
            }
            addy++;
            for (int i = addy; i-- > 0; y--) {
                if (map.isFootprintFree(x, y, width, height, mask, blocking)) {
                    return new int[] {x, y};
                }
            }
            addx++;
            for (int i = addx; i-- > 0; x--) {
                if (map.isFootprintFree(x, y, width, height, mask, blocking)) {
                    return new int[] {x, y};
                }
            }
            addy++;
        }
        return null;
    }

    private static int squareDistance(int fromX, int fromY, int toX, int toY) {
        int dx = fromX - toX;
        int dy = fromY - toY;
        return dx * dx + dy * dy;
    }

    /** The middle square of a unit's footprint, as {@code GetHalfTileSize} finds it. */
    static int[] centreOf(Unit unit) {
        return new int[] {
            unit.tileX() + Math.max(1, unit.type().tileWidth()) / 2,
            unit.tileY() + Math.max(1, unit.type().tileHeight()) / 2,
        };
    }

    /** Recomputes every player's supply and demand from what they own. */
    public void recalculateSupply() {
        for (Player player : players) {
            player.setSupply(0);
            player.setDemand(0);
            for (UnitType.Resource resource : UnitType.Resource.values()) {
                player.setIncome(resource, 100);
            }
        }
        // A unit born during UnitActions is parked in pending until the live
        // action-table walk ends, but upstream assigns it to its player --
        // and therefore raises Demand -- immediately. The AI runs before
        // this implementation merges pending at the end of the cycle, so a recount
        // requested by the training completion must see both tables. On
        // levelx04o a peasant is trained at cycle 1713; omitting that pending
        // birth leaves demand at 24 instead of 25 through the cycle-1718 AI
        // thought, clears NeedSupply, and changes the cycle-1838 harvester
        // split that first becomes visible at 1848.
        java.util.ArrayList<Unit> counted = new java.util.ArrayList<>(
                units.size() + pending.size());
        counted.addAll(units);
        counted.addAll(pending);
        for (Unit unit : counted) {
            // Removed is not dead. Upstream's Supply and Demand are player
            // counters moved only by creation, death and change of owner --
            // CUnit::Remove touches neither -- so a peasant inside the
            // barracks frame it is raising, or riding a transport, or off
            // the map down a gold mine, eats its food the whole time. This
            // asked isAlive(), which is false for all three: on
            // campaigns/orc-exp/levelx04o player 3 owns one town hall, one
            // farm's worth of food and one peasant, and the moment that
            // peasant stepped inside its barracks frame the implementation saw a free
            // mouth of supply and trained a second peasant upstream's food
            // gate refuses for the rest of the mission.
            if (unit.hitPoints() <= 0 || unit.order() == Unit.Order.DYING) {
                continue;
            }
            Player player = players[unit.player()];
            // A foundation belongs to the player and counts as a building,
            // but it supplies no food yet.  Upstream adds Demand when the
            // unit is assigned to the player, then adds Supply only from
            // UpdateForNewUnit when COrder_Built completes.  Crediting a
            // rising farm here let levelx04o's capped player train a peasant
            // at cycle 1300 while upstream's CheckLimits refused it.
            if (unit.order() != Unit.Order.UNDER_CONSTRUCTION) {
                player.setSupply(player.supply() + unit.type().supply());
            }
            player.setDemand(player.demand() + unit.type().demand());
            // Incomes ride the same walk: the best standing finished
            // building's ImproveIncomes per resource, floored at the default
            // hundred. Upstream raises the number when a building finishes
            // and recomputes this same maximum when one
            // falls; a frame still going up improves
            // nothing.
            if (unit.order() != Unit.Order.UNDER_CONSTRUCTION) {
                for (Map.Entry<UnitType.Resource, Integer> improve
                        : unit.type().improveProduction().entrySet()) {
                    player.setIncome(improve.getKey(), Math.max(
                            player.income(improve.getKey()),
                            100 + improve.getValue()));
                }
            }
        }
    }

    /**
     * Loads a unit onto a transport.
     *
     * <p>A boarded unit leaves the map entirely: it stops occupying ground,
     * stops seeing, and cannot be attacked. That is what makes a transport a
     * risk worth taking, because sinking one drowns everything aboard.
     *
     * @return whether it boarded
     */
    public boolean board(Unit passenger, Unit transport) {
        if (passenger == transport
                || !passenger.isAlive() || !transport.isAlive()
                || passenger.isAboard()
                || !transport.type().canCarry(passenger.type())
                || !transport.hasRoom()) {
            return false;
        }
        // It has to be reachable, which for a boat means standing on the shore
        // beside it rather than swimming out.
        if (passenger.distanceTo(transport) > 1) {
            return false;
        }

        markSight(passenger, false);
        markOccupancy(passenger, false);
        passenger.clearPath();
        passenger.setOrder(Unit.Order.STILL);
        passenger.setRemoved(true);
        passenger.setCarrier(transport);
        transport.cargo().add(passenger);
        return true;
    }

    /**
     * Restores an off-map unit and its container after every saved unit exists.
     *
     * <p>Creation initially marks occupancy and sight, so both are unwound
     * before the saved {@code removed} state is applied.
     */
    public void restoreContained(Unit unit, Unit container, boolean aboard, Unit.Order order) {
        if (unit == null || container == null || unit == container) {
            return;
        }
        markSight(unit, false);
        markOccupancy(unit, false);
        unit.clearPath();
        unit.setTile(container.tileX(), container.tileY());
        unit.setRemoved(true);
        unit.setOrder(order == null ? Unit.Order.STILL : order);
        if (aboard) {
            unit.setCarrier(container);
            if (!container.cargo().contains(unit)) {
                container.cargo().add(unit);
            }
        } else {
            unit.setWorksite(container);
            if (container.order() == Unit.Order.UNDER_CONSTRUCTION) {
                container.setWorksite(unit);
            }
        }
    }

    /**
     * Puts a transport's cargo ashore.
     *
     * <p>Each passenger needs a free square beside the transport. Anything
     * that cannot be placed stays aboard rather than being dropped in the sea.
     *
     * @return how many disembarked
     */
    public int unload(Unit transport) {
        int landed = 0;
        for (Unit passenger : new ArrayList<>(transport.cargo())) {
            if (putAshore(transport, passenger)) {
                landed++;
            }
        }
        return landed;
    }

    /**
     * How far an unload order will look for somewhere to land.
     *
     * <p>{@code MAX_SEARCH_RANGE}. Twenty tiles
     * out from where the player pointed, which is what makes "click the beach"
     * work when the click lands in open water.
     */
    private static final int MAX_UNLOAD_SEARCH_RANGE = 20;

    /** {@code MAX_RETRIES}: how many failed approaches end the order. */
    private static final int MAX_UNLOAD_RETRIES = 20;

    /**
     * Whether a transport sitting here could put anybody ashore.
     *
     * <p>{@code IsDropZonePossible}: the boat
     * has to fit, and at least one of its passengers has to have a free square
     * in the ring around it. Both halves matter -- a spot the boat cannot
     * occupy is no good, and neither is one in the middle of the ocean.
     */
    /**
     * Whether a transport standing on a square could put somebody ashore.
     *
     * <p>Public because it is the one question worth asking of a map when
     * unloading misbehaves: if the answer is no everywhere, the problem is the
     * terrain rules rather than the order.
     */
    public boolean canUnloadFrom(Unit transport, int tileX, int tileY) {
        return isDropZonePossible(transport, tileX, tileY);
    }

    private boolean isDropZonePossible(Unit transport, int tileX, int tileY) {
        if (!map.isFootprintFree(tileX, tileY,
                Math.max(1, transport.type().tileWidth()),
                Math.max(1, transport.type().tileHeight()),
                Unit.movementMaskFor(transport.type()),
                Unit.blockingFlagsFor(transport.type()))) {
            return false;
        }
        for (Unit passenger : transport.cargo()) {
            if (unloadPositionAt(transport, passenger, tileX, tileY, 1) != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * The nearest place a transport could stand and unload.
     *
     * <p>{@code ClosestFreeDropZone},, walking
     * the same expanding ring outwards from where the player pointed and
     * taking the first workable spot.
     *
     * <p>The transport is lifted off the map for the duration, exactly as
     * upstream does it, and for the reason its comment gives: "remove
     * transporter to avoid collision with itself". Without that the boat's own
     * footprint blocks every candidate overlapping where it already is,
     * including -- most of the time -- the one square it is already on.
     *
     * @return the tile to sail to, or {@code null} if there is nowhere
     */
    private int[] closestFreeDropZone(Unit transport, int startX, int startY, int maxRange) {
        if (transport.cargo().isEmpty()) {
            return null;
        }
        boolean wasOnMap = transport.isOnMap();
        if (wasOnMap) {
            markOccupancy(transport, false);
        }
        try {
            int addx = 0;
            int addy = 1;
            int x = startX;
            int y = startY;
            for (int range = 0; range < maxRange; range++) {
                for (int i = addy; i-- > 0; y++) {
                    if (isDropZonePossible(transport, x, y)) {
                        return new int[] {x, y};
                    }
                }
                addx++;
                for (int i = addx; i-- > 0; x++) {
                    if (isDropZonePossible(transport, x, y)) {
                        return new int[] {x, y};
                    }
                }
                addy++;
                for (int i = addy; i-- > 0; y--) {
                    if (isDropZonePossible(transport, x, y)) {
                        return new int[] {x, y};
                    }
                }
                addx++;
                for (int i = addx; i-- > 0; x--) {
                    if (isDropZonePossible(transport, x, y)) {
                        return new int[] {x, y};
                    }
                }
                addy++;
            }
            return null;
        } finally {
            if (wasOnMap) {
                markOccupancy(transport, true);
            }
        }
    }

    /**
     * Orders a transport to put its cargo ashore.
     *
     * <p>{@code COrder::NewActionUnload}. The position is where the player
     * pointed, which need not be anywhere a boat can float: the order searches
     * outwards from it. A named passenger unloads alone; {@code null} means
     * everybody.
     *
     * <p>This replaced a straight call to {@link #unload}, which tried once at
     * whatever square the boat was sitting on and, finding nothing, did
     * nothing at all -- no order, no movement, no message. Measured on human
     * mission five: a loaded transport told to sail to a landing spot stopped
     * one tile short of it, and unloading there landed none of its six
     * passengers. Every one of the 591 coastal squares on that map can be
     * unloaded onto; the boat simply was not standing on one, and nothing in
     * the implementation would take it the last tile.
     *
     * @return whether the order was accepted
     */
    public boolean orderUnload(Unit transport, int tileX, int tileY, Unit passenger) {
        if (transport == null || !transport.isAlive()
                || !transport.type().canTransport() || transport.cargo().isEmpty()) {
            return false;
        }
        if (passenger != null && !transport.cargo().contains(passenger)) {
            return false;
        }
        transport.clearPath();
        transport.setTarget(passenger);
        transport.setOrderTarget(tileX, tileY);
        transport.setUnloadState(Unit.UNLOAD_FIND_DROPZONE);
        transport.setUnloadRetries(0);
        transport.setOrder(Unit.Order.UNLOAD);
        return true;
    }

    /**
     * Lets passengers off, and says whether the order is done.
     *
     * <p>{@code COrder_Unload::LeaveTransporter}. Anything that cannot be
     * placed stays aboard and the order goes back to looking for coast, which
     * is how a boat carrying more than its landing spot has room for makes two
     * trips up the beach instead of drowning the remainder.
     *
     * @return true when there is nobody left who still needs putting ashore
     */
    private boolean leaveTransporter(Unit transport) {
        Unit only = transport.target();
        int stillOnBoard = 0;
        if (only != null) {
            // Not isAlive: that demands the unit be on the map, and a
            // passenger is off the map by definition, so asking it here
            // reported every named passenger as gone and quietly ended the
            // order having landed nobody. Upstream asks {@code goal.Destroyed},
            // which is what these two tests amount to.
            if (only.hitPoints() <= 0 || !transport.cargo().contains(only)) {
                transport.setTarget(null);
                return true;
            }
            if (unloadOne(transport, only)) {
                transport.setTarget(null);
            } else {
                stillOnBoard++;
            }
        } else {
            for (Unit passenger : new ArrayList<>(transport.cargo())) {
                if (!putAshore(transport, passenger)) {
                    stillOnBoard++;
                }
            }
        }
        return stillOnBoard == 0;
    }

    /**
     * One step of an unload order.
     *
     * <p>{@code COrder_Unload::Execute}: find a drop zone, sail to it, let
     * them off; and if letting them off did not clear the hold, go round
     * again from wherever the boat now is.
     */
    private void stepUnload(Unit unit) {
        boolean traceUnload = System.getenv("CHONKCRAFT_TRACE_UNLOAD") != null;
        if (traceUnload) {
            System.err.printf("JUNLOAD cycle=%d unit=%d state=%d at=%d,%d goal=%d,%d"
                            + " cargo=%d path=%d moving=%d retries=%d%n",
                    cycle, unit.id(), unit.unloadState(), unit.tileX(), unit.tileY(),
                    unit.orderTargetX(), unit.orderTargetY(), unit.cargo().size(),
                    unit.pathLength(), unit.isMoving() ? 1 : 0, unit.unloadRetries());
        }
        if (unit.cargo().isEmpty()) {
            unit.setTarget(null);
            unit.setOrder(Unit.Order.STILL);
            return;
        }
        // A transport that cannot move has nothing to search for: it unloads
        // where it is or not at all. Upstream forces the state the same way.
        if (unit.type().speed() <= 0) {
            unit.setUnloadState(Unit.UNLOAD_LEAVING);
        }
        if (unit.unloadRetries() >= MAX_UNLOAD_RETRIES) {
            unit.setTarget(null);
            unit.setOrder(Unit.Order.STILL);
            return;
        }

        if (unit.unloadState() == Unit.UNLOAD_FIND_DROPZONE) {
            int[] zone = closestFreeDropZone(unit,
                    unit.orderTargetX(), unit.orderTargetY(), MAX_UNLOAD_SEARCH_RANGE);
            if (traceUnload) {
                System.err.printf("JUNLOADZONE cycle=%d unit=%d zone=%s%n",
                        cycle, unit.id(), zone == null ? "none"
                                : zone[0] + "," + zone[1]);
            }
            if (zone == null) {
                unit.setUnloadRetries(MAX_UNLOAD_RETRIES);
                return;
            }
            unit.setOrderTarget(zone[0], zone[1]);
            unit.setUnloadRetries(0);
            unit.setUnloadState(Unit.UNLOAD_MOVE_TO_DROPZONE);
            // and fall through, as upstream does, so the first cycle of the
            // order already sets off rather than idling one tick.
        }

        if (unit.unloadState() == Unit.UNLOAD_MOVE_TO_DROPZONE) {
            int goalX = unit.orderTargetX();
            int goalY = unit.orderTargetY();
            if (unit.tileX() == goalX && unit.tileY() == goalY) {
                unit.clearPath();
                unit.setUnloadRetries(0);
                unit.setUnloadState(Unit.UNLOAD_LEAVING);
            } else {
                if (unit.pathLength() == 0 && !unit.isMoving()) {
                    // Action 30 re-evaluates unit+0x1c&2 against every new
                    // order point. In particular, a one-tile final approach
                    // clears double-step; planning with the stale bit made a
                    // crowded transport repeatedly reject the very anchor it
                    // had just selected.
                    unit.setBattleNetDoubleStep(
                            battleNetTransportDoubleStep(unit, goalX, goalY));
                    // Large BNE ships consume a route on their native anchor
                    // stride. A one-tile path consumed as two-tile steps can
                    // sail past the selected drop zone forever.
                    PathFinder.Path path = findBattleNetPointPath(
                            unit, goalX, goalY);
                    if (path.result() == PathFinder.Result.REACHED) {
                        unit.setUnloadRetries(0);
                        unit.setUnloadState(Unit.UNLOAD_LEAVING);
                    } else if (path.result() != PathFinder.Result.FOUND) {
                        // Unreachable: wait a moment and try a different zone
                        // rather than hammering the planner every cycle.
                        unit.setWaitCycles(5);
                        unit.setUnloadRetries(unit.unloadRetries() + 1);
                        unit.setUnloadState(Unit.UNLOAD_FIND_DROPZONE);
                        return;
                    } else {
                        unit.setPath(path);
                        unit.setPathGoal(goalX, goalY);
                    }
                }
                if (unit.unloadState() == Unit.UNLOAD_MOVE_TO_DROPZONE) {
                    Unit.Order saved = unit.order();
                    unit.setBattleNetBorrowedMoveForStep(true);
                    unit.setOrder(Unit.Order.MOVE);
                    try {
                        movement.stepMove(unit, false);
                    } finally {
                        unit.setBattleNetBorrowedMoveForStep(false);
                    }
                    if (unit.order() == Unit.Order.DYING) {
                        return;
                    }
                    unit.setOrder(saved);
                    if (unit.tileX() != goalX || unit.tileY() != goalY) {
                        // Still on the way, or it ran out of path short of the
                        // goal. Either way, nothing more happens this cycle.
                        if (unit.pathLength() == 0 && !unit.isMoving()) {
                            unit.setUnloadRetries(unit.unloadRetries() + 1);
                            unit.setUnloadState(Unit.UNLOAD_FIND_DROPZONE);
                        }
                        return;
                    }
                    unit.setUnloadRetries(0);
                    unit.setUnloadState(Unit.UNLOAD_LEAVING);
                }
            }
        }

        if (unit.unloadState() == Unit.UNLOAD_LEAVING) {
            if (leaveTransporter(unit)) {
                unit.setTarget(null);
                unit.setOrder(Unit.Order.STILL);
            } else {
                // Somebody is still aboard. Look again from where we are now,
                // which is what makes a full boat land in two goes.
                unit.setOrderTarget(unit.tileX(), unit.tileY());
                unit.setUnloadState(Unit.UNLOAD_FIND_DROPZONE);
                unit.setUnloadRetries(unit.unloadRetries() + 1);
            }
        }
    }

    /**
     * Puts one named passenger on the ground beside its transport.
     *
     * <p>{@code UnloadUnit}. Shared by {@link #unload} and the unload order so
     * that both place cargo the same way.
     */
    private boolean putAshore(Unit transport, Unit passenger) {
        int[] spot = unloadPosition(transport, passenger);
        if (spot == null) {
            return false;
        }
        transport.cargo().remove(passenger);
        passenger.setCarrier(null);
        passenger.setTile(spot[0], spot[1]);
        passenger.setRemoved(false);
        markOccupancy(passenger, true);
        unitCountSeen(passenger);
        markSight(passenger, true);
        return true;
    }

    /**
     * Puts one passenger ashore and leaves the rest aboard.
     *
     * <p>What clicking a single cargo icon does. Unloading everything is the
     * command button; being able to land one unit is the reason the icons are
     * separate slots rather than a count.
     *
     * @return whether it found somewhere to put them
     */
    public boolean unloadOne(Unit transport, Unit passenger) {
        if (passenger == null || !transport.cargo().contains(passenger)) {
            return false;
        }
        return putAshore(transport, passenger);
    }

    /**
     * Somebody pointing at a place on the map.
     *
     * @param player who pointed, so it can be shown in their colour
     * @param cycle  when, so it can fade on its own
     */
    public record Ping(int player, int tileX, int tileY, long cycle) {}

    /** How long a ping stays on screen. */
    public static final int PING_CYCLES = CYCLES_PER_SECOND * 2;

    private final List<Ping> pings = new ArrayList<>();

    /** Published for the minimap and the field; see {@link #pings()}. */
    private volatile List<Ping> pingSnapshot = List.of();

    /**
     * Records a player pointing at somewhere.
     *
     * <p>Kept in the world rather than the screen because it arrives as a
     * command, which is what makes it reach the other players at all: a ping
     * only means anything if the people being pointed at can see it.
     */
    public void addPing(int player, int tileX, int tileY) {
        if (!map.contains(tileX, tileY)) {
            return;
        }
        // A player leaning on the button should not be able to fill the map.
        if (pings.size() > 32) {
            pings.remove(0);
        }
        pings.add(new Ping(player, tileX, tileY, cycle));
        pingSnapshot = List.copyOf(pings);
    }

    /**
     * Drops the pings that have faded. Called once a cycle by {@link #tick}.
     *
     * <p>This used to be done by {@link #pings()}, which is to say by the
     * renderer, on the event thread, to a list the simulation was adding to --
     * so the act of drawing a ping could throw
     * {@code ConcurrentModificationException} out of {@code ArrayList.removeIf}
     * and take the window down. It is drawn twice a frame, on the field and on
     * the minimap, and the ping it was drawing had arrived over the network
     * from the other player, which is the one moment the list is being written
     * to. Expiring them here puts the write back on the thread that owns the
     * list and leaves the interface with nothing to do but read.
     */
    private void expirePings() {
        if (pings.isEmpty()) {
            return;
        }
        if (pings.removeIf(ping -> cycle - ping.cycle() > PING_CYCLES)) {
            pingSnapshot = List.copyOf(pings);
        }
    }

    /** The pings still worth drawing, oldest first. */
    public List<Ping> pings() {
        return pingSnapshot;
    }

    /** Cancels whatever a unit is doing, leaving it where it stands. */
    public void orderStop(Unit unit) {
        if (unit.order() == Unit.Order.UNDER_CONSTRUCTION) {
            construction.cancelConstruction(unit);
            return;
        }
        projectiles.interruptPendingAttack(unit);
        unit.setPendingHarvest(-1, -1);
        unit.clearQueuedOrders();
        unit.setSavedOrder(null);
        construction.abandonPendingBuild(unit);
        if (unit.type() != null
                && unit.type().gathering().containsKey(UnitType.Resource.OIL)) {
            unit.setBattleNetOilAction(Unit.BattleNetOilAction.IDLE);
            unit.setBattleNetOilActionTicks(0);
            unit.setReturningToDepot(false);
            unit.setResourceDepot(null);
            unit.setReturnDepotGoal(null);
        }
        // Dest-arm leftover outlives Stop the same way it outlives a Move
        // click. Native stop-1/00 keeps Move after fixture 20 and is Still
        // only when leftover lands at 24. Clearing the leftover here used
        // to freeze offsets and Still immediately.
        if (movement.leftoverWalkBearing(unit.currentAction(), unit)
                || (unit.order() == Unit.Order.MOVE && unit.isMoving())) {
            unit.clearPath();
            unit.setBattleNetStopAfterLeftover(true);
            return;
        }
        unit.clearPath();
        unit.setOrder(Unit.Order.STILL);
    }

    /** Starts the next viable shifted command once the current order finishes. */
    private void beginNextQueuedOrder(Unit unit) {
        while (unit.order() == Unit.Order.STILL && unit.isOnMap()) {
            Unit.QueuedOrder queued = unit.pollQueuedOrder();
            if (queued == null) {
                return;
            }
            // Only the head can be the delayed flush replacement. Any orders
            // remaining behind it were explicitly shifted by the caller.
            unit.setQueuedReplacementPending(false);
            boolean accepted = switch (queued.kind()) {
                case MOVE -> movement.orderPoppedMove(unit, queued.x(), queued.y());
                case ATTACK -> orderAttack(unit, queued.target());
                case HARVEST -> harvest.orderHarvest(unit, queued.x(), queued.y());
                case BUILD -> construction.orderBuild(unit, queued.type(), queued.x(), queued.y());
                case CAST -> queued.target() != null
                        ? orderCast(unit, queued.value(), queued.target())
                        : orderCast(unit, queued.value(), queued.x(), queued.y());
                case PATROL -> orderPatrol(unit, queued.x(), queued.y());
                case REPAIR -> construction.orderRepair(unit, queued.target());
                case EXPLORE -> orderExplore(unit);
                case RETURN_GOODS -> orderReturnGoods(unit);
                case STAND_GROUND -> {
                    orderStandGround(unit);
                    yield unit.order() == Unit.Order.STAND_GROUND;
                }
                case ATTACK_GROUND -> orderAttackGround(
                        unit, queued.x(), queued.y(), false);
                case ATTACK_MOVE -> combat.orderAttackMove(unit, queued.x(), queued.y());
                case BOARD -> orderBoard(unit, queued.target());
                case FOLLOW -> orderFollow(unit, queued.target());
                case DEFEND -> orderDefend(unit, queued.target());
            };
            if (accepted && unit.order() != Unit.Order.STILL) {
                // "unit.Wait = 0" on the pop. Whatever the order that just ended was waiting
                // out is not the new order's to serve: a unit sitting through
                // the ten cycles a spent route costs, and then told to go
                // somewhere else, goes at once.
                unit.setWaitCycles(0);
                return;
            }
        }
    }

    // ---------------------------------------------------------------- cycle

    /** Advances the simulation one cycle. */
    public void tick() {
        cycle++;
        // Retail's per-cycle player pass enters the ai.bin interpreter first
        // (0x0044c260 calls 0x00424f00 before its fifty-cycle AI work). Each
        // active computer slot serves one 32-bit wait tick or dispatches until
        // the next wait. The four opcodes and all eight predicates are now
        // transcribed and covered against the pinned BNE executable.
        for (net.chonkbase.chonkcraft.engine.ai.AiPlayer ai : ais.values()) {
            ai.battleNetTickBytecode(this);
            // Maps without a retail ai.bin personality use the standing
            // skirmish plan. Its AiEachSecond pass is separate from the
            // per-cycle interpreter above: player zero thinks on cycle seven,
            // each later player on the following cycle, then every thirty
            // cycles. Losing this call left an ordinary skirmish computer with
            // all of its resource, construction and force managers present but
            // unreachable. Retail-profile campaign AIs deliberately stay on
            // the specialized ai.bin/ready/action-33 paths instead; running the
            // generic manager beside them would spend their bank twice.
            if (!ai.hasBattleNetProfile() && cycle % CYCLES_PER_SECOND
                    == AiPlayer.FIRST_THINK_CYCLE + ai.playerIndex()) {
                ai.think(this);
            }
            // FUN_0044c260 consumes ai.bin's pending ground/naval/air launch
            // bytes on the same fifty-cycle cadence as retail (49, 99...).
            if (cycle > 2 && (cycle - 2) % 50 == 49) {
                ai.battleNetRunPeriodicForces(this);
            }
        }
        java.util.Arrays.fill(battleNetHelpPromotedThisCycle, false);
        if (PathFinder.tracingAsks()) {
            pathFinder.setTraceCycle(cycle);
        }
        // Whether one unit can get at another is answered by a route search,
        // and what a route search answers depends on where everybody is
        // standing. Upstream asks it afresh every time
        // (COrder_Still::AutoAttack to AttackUnitsInReactRange to ComputeCost
        // to UnitReachable), so it is only ever true of the moment.
        //
        // Keeping the answer until the terrain changed, which is what this did
        // for the sake of the search cost, made "no" permanent. On
        // maps/demo/demo03 an ogre at 15,3 and a grunt at 13,3 are walled off
        // from the peasant every unit on the map is converging on -- by rock
        // below and by three of their own grunts standing in a column at 11,0,
        // 11,1 and 11,2 above, and upstream's A* refuses to cross a stationary
        // unit exactly as this one does. Those three grunts walk away on cycle
        // 2. Upstream's ogre asks again on its next idle scan, at cycle 17,
        // finds the way open and goes; this implementation's was still holding the
        // answer it got at cycle 1 and stood there.
        reachable.clear();
        // The two pieces of bookkeeping the interface used to do for itself, on
        // its own thread, to lists this one owns. Both are here now, before
        // anything walks either list.
        placeClickMarkers();
        expirePings();
        snapshot = List.copyOf(units);
        // Retail's scout pass is not only run at game creation; it walks the
        // aircraft it has given behaviour four again on a fifty-cycle beat.
        // The first beat stays on world.cycle 49 -- ScoutPassTest and the
        // early-mission dests that moving the first beat REG'd. Later beats
        // use the same fixture numbering as naval (49, 99, 149). Human 12
        // zeppelins 1559/1570 are Still at fixture 82/63; world.cycle % 50
        // == 49 sent them out at fixture 97 while native stays Still
        // through 98 and Patrols at 99.
        boolean firstAircraftBeat = cycle <= 50 && cycle % 50 == 49;
        // cycle 51 is (cycle-2)%50==49 but two ticks after the first
        // beat; that extra pass rewrote Human 12's commanded dest from
        // 107,51 to 69,30. The next native fixture beat is 99, world 101.
        boolean laterAircraftBeat = cycle >= 101
                && (cycle - 2) % 50 == 49;
        if (firstAircraftBeat || laterAircraftBeat) {
            idle.fireBattleNetScoutPass();
        }
        // The ships it has given behaviour six are walked on the same beat but
        // two ticks further on, which is what puts the queue between two of the
        // ship's action markers rather than immediately before one. XOrc 11's
        // destroyer 1519 is standing when the pass reaches it, is queued a
        // patrol after its marker on fixture 48, and is on that patrol at 53
        // and standing again at 56. Queueing it two ticks earlier let the
        // fixture-48 marker promote it and put the ship out five cycles early,
        // and moving the first aircraft beat to match cost four other missions
        // their proven horizon.
        if (cycle > 2 && (cycle - 2) % 50 == 49) {
            idle.fireBattleNetNavalPatrolPass();
        }
        // A point command temporarily replaces an autonomous scout Patrol.
        // Retail re-enters the scout callback before HandleEachCycle on the
        // tick after the Move finishes, so its two endpoint draws remain ahead
        // of every per-unit idle draw. Doing this inside stepStill selected the
        // same action at the same time but from the wrong RNG positions.
        idle.fireBattleNetCommandPatrolRestores();
        ticking = true;
        // Over the copy, not the live list: "Unit list may be modified
        // during loop... so make a copy".
        // A unit born mid-cycle -- a foundation the builder just laid, a
        // trainee stepping out -- acts for the first time on the NEXT
        // cycle. Walking the live list gave level06h's pig farm one
        // construction tick on the cycle it was placed, and it finished a
        // beat before upstream's for the whole mission.
        for (int actionIndex = 0; actionIndex < snapshot.size(); actionIndex++) {
            // BNE allocates map units from the high end of its fixed pool and
            // HandleEachCycle walks low to high: reverse creation order here.
            Unit unit = snapshot.get(snapshot.size() - 1 - actionIndex);
            // UnitActions begins with this exact guard. A released unit can
            // remain in the active table while an order or missile holds a
            // CUnitPtr to it; it occupies a table slot but never acts again.
            if (unit.destroyed()) {
                continue;
            }
            // The two combat counters run down for every unit every cycle,
            // wherever it is and whatever it is doing, as HandleBuffsEachCycle
            // does. Threshold is how long a unit refuses to re-aim; UnderAttack
            // is how long it stays interested in whoever hit it.
            if (unit.pendingHarvestX() >= 0 && !unit.animation().unbreakable()) {
                int toX = unit.pendingHarvestX();
                int toY = unit.pendingHarvestY();
                unit.setPendingHarvest(-1, -1);
                unit.setWaitCycles(0);
                harvest.orderHarvest(unit, toX, toY);
            }
            if (unit.orderFinished() && unit.hasQueuedOrders()
                    && !unit.animation().unbreakable()) {
                // HandleUnitAction advances a finished head to the command
                // already behind it before executing the unit. This matters
                // for FlushMode::Off as much as for shifted player commands:
                // level08h's peasant finishes its unreachable ReturnGoods,
                // is hit later in the cycle, and has a flee Move appended.
                // Upstream pops and spends that move's first step on 1573;
                // waiting for stepReturnGoods to notice Finished installs it
                // only at the loop tail and moves on 1574.
                unit.setOrderFinished(false);
                unit.setOrder(Unit.Order.STILL);
                unit.setWaitCycles(0);
                beginNextQueuedOrder(unit);
            }
            if (unit.reportsActionBeforeQueued()
                    && !unit.animation().unbreakable()
                    && !(unit.order() == Unit.Order.MOVE
                        && unit.battleNetOrderDelay()
                            > unit.actionBeforeQueuedReleaseDelay())) {
                // The label's wipe is the queue's pop, and the pop wipes the
                // wait with it: "unit.Orders.erase(...); unit.Wait = 0"
                // A command lands on
                // whatever wait the old order was serving, and the new order
                // must not serve out the rest: level12h's corner chopper is
                // stolen for gold at cycle 727 while three cycles into a
                // blocked step's ten, and upstream's peon steps toward the
                // mine during 728 where this implementation used to stand out the
                // three. The two label-only shims at a construction's finish
                // set no waits, so the wipe is theirs for free.
                //
                // But only with the animation let go: the whole pop block sits
                // under "if (!unit.Anim.Unbreakable)", so a
                // command landing mid-swing serves out the swing first.
                // level12o's chopper is stolen for gold at 157, two swings
                // into its tree, and upstream's peon finishes the period and
                // steps at 173 -- a blanket wipe here started it at 159.
                unit.setWaitCycles(0);
                if (unit.queuedReplacementPending()) {
                    // In this one shape the replacement itself is at the
                    // queue's head because an unbreakable step kept the old
                    // order current. Make that head eligible to pop. Ordinary
                    // shifted commands stay behind the replacement already
                    // installed in {@code order}.
                    unit.setOrder(Unit.Order.STILL);
                }
                // The implementation has already installed the first replacement in
                // {@code order}; only its old CurrentAction label is waiting
                // to pop here. Shifted commands belong behind that replacement
                // and must remain queued until it finishes. Turning the unit
                // STILL merely because such a command existed skipped the
                // first replacement: build at A followed in the same command
                // batch by shifted build at B marched straight to B.
                unit.setActionBeforeQueued(null);
            }
            if (unit.pendingAttack() != null) {
                beginPendingAttack(unit);
            }
            if (unit.buildLatchedFrom() != null) {
                if (unit.order() != unit.buildLatchedFrom()
                        && unit.order() != Unit.Order.STILL) {
                    // A later command took the unit; the waiting build
                    // dissolves, as upstream's next flush erases the queued
                    // order.
                    unit.setBuildLatchedFrom(null);
                    unit.setPendingBuild(null);
                } else if (!unit.animation().unbreakable()) {
                    // The pop: the finished order leaves, the build becomes
                    // current with the wait cleared -- "unit.Wait = 0" on
                    // the erase -- and runs this cycle.
                    unit.setBuildLatchedFrom(null);
                    unit.setOrder(Unit.Order.BUILD);
                    unit.setWaitCycles(0);
                    unit.clearPath();
                }
            }
            unit.setThreshold(unit.threshold() - 1);
            unit.setUnderAttack(unit.underAttack() - 1);
            // And the six timed spell effects, which HandleBuffsEachCycle runs
            // down in the same place and on the same terms. Before the loop
            // body's own early exits, because upstream decrements them for a
            // removed unit too: a passenger's Bloodlust runs out while it is
            // aboard the transport.
            unit.decayBuffs();
            // CriticalOrder executes before the ordinary current order, but
            // only after an unbreakable animation has released the unit
            // The order is consumed whether or not the
            // requested type can be placed.
            if (unit.pendingTransform() != null
                    && !unit.animation().unbreakable()) {
                UnitType pendingTransform = unit.pendingTransform();
                unit.setPendingTransform(null);
                transformInto(unit, pendingTransform);
            }
            // isOnMap, not isAlive: a dying unit still has to run its death
            // animation out before it is cleared, and isAlive is already false
            // by then.
            //
            // A worker inside a mine is off the map and still has to be
            // stepped, or it goes in and never comes out. Everything else that
            // is removed -- a passenger aboard a ship, a builder inside its
            // worksite -- is driven by whatever contains it.
            if (!unit.isOnMap() && unit.order() != Unit.Order.DYING
                    && !(unit.order() == Unit.Order.HARVEST && unit.worksite() != null)) {
                continue;
            }
            if (unit.order() == Unit.Order.STILL && unit.hasQueuedOrders()
                    && unit.battleNetOrderDelay() == 0) {
                beginNextQueuedOrder(unit);
            }
            if (unit.order() == Unit.Order.STILL
                    && unit.battleNetPendingHelpAttack() != null) {
                int owner = unit.player();
                // One spatial-help promote per player per cycle. XHuman 12
                // ogres 1381/1394 both sit at react+1 of the same aggressor;
                // promoting every brother the same visit made both Attack on
                // fixture 25 while native raises 1381 first and 1394 next.
                // Solo help (grunt 1481) is unaffected: the single brother
                // still promotes immediately with delay 3.
                if (owner >= 0 && owner < battleNetHelpPromotedThisCycle.length
                        && !battleNetHelpPromotedThisCycle[owner]) {
                    Unit helpTarget = unit.battleNetPendingHelpAttack();
                    boolean deferPromote = false;
                    // Person: promote the pending brother closest in row to the
                    // aggressor first (XHuman 10 knight 1489 shares y with
                    // catapult 74,89 before column neighbours). Tick order
                    // alone raised 1480 while native raised 1489.
                    if (isPerson(owner) && helpTarget != null) {
                        // After the first person help promote, skip one cycle
                        // before the second (native 1489@43, 1493@45); later
                        // promotes may batch same cycle (1485+1480@46).
                        if (battleNetPersonHelpPromoteCount[owner] == 1
                                && battleNetPersonHelpLastPromoteCycle[owner]
                                        > 0
                                && cycle - battleNetPersonHelpLastPromoteCycle[
                                        owner] < 2) {
                            deferPromote = true;
                        }
                        // Ordered pick only while single-file promotes;
                        // batch phase promotes every remaining pending.
                        if (!deferPromote
                                && battleNetPersonHelpPromoteCount[owner]
                                        < 2) {
                            int myRow = Math.abs(
                                    unit.tileY() - helpTarget.tileY());
                            int myDist = battleNetDistance(unit, helpTarget);
                            for (Unit other : units) {
                                if (other == unit || other.player() != owner
                                        || other.battleNetPendingHelpAttack()
                                                != helpTarget
                                        || other.order() != Unit.Order.STILL) {
                                    continue;
                                }
                                int oRow = Math.abs(
                                        other.tileY() - helpTarget.tileY());
                                int oDist = battleNetDistance(
                                        other, helpTarget);
                                if (oRow < myRow
                                        || (oRow == myRow && oDist < myDist)
                                        || (oRow == myRow && oDist == myDist
                                                && other.id() < unit.id())) {
                                    deferPromote = true;
                                    break;
                                }
                            }
                        }
                    }
                    if (!deferPromote) {
                        unit.setBattleNetPendingHelpAttack(null);
                        if (helpTarget.isAlive() && helpTarget.isOnMap()
                                && orderAttack(unit, helpTarget)) {
                            if (isPerson(owner)) {
                                battleNetPersonHelpLastPromoteCycle[owner] =
                                        (int) cycle;
                                battleNetPersonHelpPromoteCount[owner]++;
                                // First two person promotes are one-per-cycle
                                // (1489@43, 1493@45); later batch may raise
                                // two same cycle (1485+1480@46).
                                if (battleNetPersonHelpPromoteCount[owner]
                                        <= 2) {
                                    battleNetHelpPromotedThisCycle[owner] =
                                            true;
                                }
                            } else {
                                battleNetHelpPromotedThisCycle[owner] = true;
                            }
                            // Hold three order-delay cycles before the first
                            // chase step so XHuman 12 grunt 1481 stays at
                            // 18,42 through fixture cycle 16 and steps to 19
                            // only on cycle 17; delay 2 stepped on cycle 16.
                            unit.setBattleNetOrderDelay(3);
                            // First chase path after person help may prefer
                            // an equal-cost goal-axis diagonal onto a lead
                            // brother (XHuman 10 knight 1493 SW onto 1489).
                            if (isPerson(owner)) {
                                unit.setBattleNetPersonHelpFirstChase(true);
                            }
                        }
                    }
                }
            }
            if (ATTACKANIM_TRACE_ID >= 0 && unit.id() == ATTACKANIM_TRACE_ID) {
                // The twin of ATTACKANIMDBG at COrder_Attack::Execute's top,
                // printed before the wait is served exactly as upstream
                // prints before its IsWaiting return.
                System.err.printf(
                        "JATTACKANIM cycle=%d unit=%d anim=%d await=%d unbreak=%d"
                                + " uwait=%d path=%d spent=%d pathgoal=%d,%d"
                                + " target=%d fighting=%d chasing=%d%n",
                        cycle, unit.id(), unit.animation().index(),
                        unit.animation().waitCycles(),
                        unit.animation().unbreakable() ? 1 : 0,
                        unit.waitCycles(), unit.pathLength(),
                        unit.routeSpent() ? 1 : 0,
                        unit.pathGoalX(), unit.pathGoalY(),
                        unit.target() == null ? -1 : unit.target().id(),
                        unit.fighting() ? 1 : 0, unit.chasing() ? 1 : 0);
            }
            String tracedUnitBeforeWait = System.getenv("CHONKCRAFT_TRACE_UNIT");
            if (tracedUnitBeforeWait != null
                    && unit.id() == Integer.parseInt(tracedUnitBeforeWait)) {
                System.err.printf("JUNITPRE cycle=%d unit=%d order=%s current=%s"
                                + " wait=%d unbreak=%d anim=%d await=%d moving=%d"
                                + " path=%d spent=%d pos=%d,%d ix=%d iy=%d"
                                + " residual=%d,%d resource=%d depot=%d"
                                + " worksite=%d returning=%d carried=%d%n",
                        cycle, unit.id(), unit.order(), unit.currentAction(),
                        unit.waitCycles(), unit.animation().unbreakable() ? 1 : 0,
                        unit.animation().index(), unit.animation().waitCycles(),
                        unit.walkHolding() ? 1 : 0, unit.pathLength(),
                        unit.routeSpent() ? 1 : 0, unit.tileX(), unit.tileY(),
                        unit.offsetX(), unit.offsetY(), unit.residualX(), unit.residualY(),
                        unit.resourceUnit() == null ? -1 : unit.resourceUnit().id(),
                        unit.returnDepotGoal() == null ? -1 : unit.returnDepotGoal().id(),
                        unit.worksite() == null ? -1 : unit.worksite().id(),
                        unit.returningToDepot() ? 1 : 0, unit.carried());
            }
            // Waiting belongs to the actions that call COrder::IsWaiting.
            // Die does not: COrder_Die::Execute goes straight to
            // AnimateActionDie, so the wait left by
            // whichever order death interrupted cannot postpone the fall.
            // levelx11o's destroyer dies during its attack-move wait at 118;
            // upstream spawns its dead-vision marker at 119 while the blanket
            // gate here used to keep the death animation asleep.
            if (unit.waitCycles() > 0 && unit.order() != Unit.Order.DYING) {
                // A worker chopping in the open keeps swinging while the
                // period runs down. GatherResource animates every cycle and
                // only adds to the load when TimeToHarvest expires; skipping
                // the unit outright froze it mid-swing and threw the chopping
                // sound away with it.
                if (harvest.isChopping(unit)) {
                    advance(unit);
                } else if (unit.isOnMap() || unit.returningToDepot()) {
                    sleepStanding(unit);
                    // The Still loop plays over the wait: the order handler is
                    // asleep but BNE's animation program is not, and it keeps
                    // reaching its action markers. A critter that has finished
                    // a wander is left on the ten-cycle empty-route pause, and
                    // retail dispatches it again two cycles into that pause --
                    // Orc 4's animal comes to rest on cycle 50 and is
                    // dispatched at 52, 55, 56, 57 and 62, where this implementation
                    // skipped it outright and reached no marker until 62. In
                    // Human 4 that let the other critter spend the draw this
                    // one was owed, which is why that mission reported one
                    // animal standing and another moving.
                    if (unit.order() == Unit.Order.STILL
                            && battleNetSequence != null) {
                        idle.stepBattleNetIdle(unit);
                    }
                } else {
                    // Inside the mine nothing breathes. GatherResource sets
                    // Anim.CurrAnim to null for a harvester that went in
                    // and counts TimeToHarvest
                    // with no animation at all, where the stay inside the
                    // depot is unit.Wait and IsWaiting plays the Still loop
                    // over it. This implementation slept both stays the same way, so a
                    // mining peon wiggled unseen every five cycles: on
                    // campaigns/human-exp/levelx03h two peons walk into their
                    // mines during cycle 35 and upstream's next draw for
                    // either is its exit, while this implementation's each drew again
                    // on cycle 40 -- two draws no one else made, and every
                    // number after them belonged to a different game.
                }
                unit.setWaitCycles(unit.waitCycles() - 1);
                continue;
            }
            unit.animation().endWait();
            if (PathFinder.tracingAsks()) {
                pathFinder.setTraceUnit(unit.id());
            }
            String tracedUnit = System.getenv("CHONKCRAFT_TRACE_UNIT");
            if (tracedUnit != null && unit.id() == Integer.parseInt(tracedUnit)) {
                System.err.printf("JUNITDBG cycle=%d unit=%d order=%s current=%s"
                                + " wait=%d unbreak=%d anim=%d await=%d moving=%d"
                                + " path=%d spent=%d pos=%d,%d%n",
                        cycle, unit.id(), unit.order(), unit.currentAction(),
                        unit.waitCycles(), unit.animation().unbreakable() ? 1 : 0,
                        unit.animation().index(), unit.animation().waitCycles(),
                        unit.walkHolding() ? 1 : 0, unit.pathLength(),
                        unit.routeSpent() ? 1 : 0, unit.tileX(), unit.tileY());
            }
            switch (unit.order()) {
                case MOVE -> movement.stepMoveOrderWithBattleNetCritter(unit);
                case ATTACK -> combat.stepAttack(unit);
                case DYING -> stepDying(unit);
                case HARVEST -> harvest.stepHarvest(unit);
                case BUILD -> construction.stepWalkToSite(unit);
                case UNDER_CONSTRUCTION -> construction.stepConstruction(unit);
                case STILL -> idle.stepStill(unit);
                case STAND_GROUND -> combat.stepStandGround(unit);
                case PATROL -> stepPatrol(unit);
                case REPAIR -> construction.stepRepair(unit);
                case EXPLORE -> stepExplore(unit);
                case RETURN_GOODS -> harvest.stepReturnGoods(unit);
                case ATTACK_GROUND -> combat.stepAttackGround(unit);
                case ATTACK_MOVE -> combat.stepAttackMove(unit);
                case BOARD -> stepBoard(unit);
                case UNLOAD -> stepUnload(unit);
                case SPELL_CAST -> stepSpellCast(unit);
                case FOLLOW -> stepFollow(unit);
                case DEFEND -> stepDefend(unit);
            }
            combat.finishBattleNetAttackSequenceMarker(unit);
            if (unit.order() == Unit.Order.STILL && unit.hasQueuedOrders()
                    && unit.isOnMap()) {
                if (unit.battleNetOrderDelay() > 0) {
                    unit.setBattleNetOrderDelay(unit.battleNetOrderDelay() - 1);
                    if (unit.battleNetOrderDelay() == 0) {
                        beginNextQueuedOrder(unit);
                        // GiveOrder 27 pops after the Still body. Native
                        // first_progress is that Repair visit (repair-1/03
                        // fixture 9). Waiting for the next tick's step left
                        // first walk at 10.
                        if (unit.order() == Unit.Order.REPAIR) {
                            construction.stepRepair(unit);
                        }
                    }
                } else {
                    beginNextQueuedOrder(unit);
                }
            }
            releaseUnreferencedDestroyedUnits();
        }
        // Player and AI commands may replace an attack between its early
        // presentation frame and BNE opcode ten. Cancel that order-owned
        // placeholder before cycle-end constructor debits can turn it into a
        // real shot. stepMissiles repeats the check for direct/test callers.
        projectiles.discardInterruptedPlaceholders();
        projectiles.flushBattleNetCycleEndConstructorDebit();
        projectiles.stepMissiles();
        regenerateMana();

        // BNE rescue is per-unit ({@link #rescueBattleNetUnit}), not the
        // LegacyEngine once-per-second rescue/sight pass.
        updateSeenBuildings();

        // Retail BNE does not run LegacyEngine/ChonkCraft's retired scripting language AiEachSecond
        // scheduler. Computer players use the native ai.bin state machine
        // and per-unit ready callbacks ({@code battleNetUnitReady}).
        // Bytecode is bootstrapped at mission load past immediately satisfied
        // gates, then stepped at the top of every cycle above. retired scripting language scripts are
        // deliberately not resumed here.

        // Peon trains are no longer pulsed every timed update. Native action
        // 33 on computer halls increments unit+0x6e each Still OP0 and starts
        // the reserved train when the counter exceeds the type limit -- see
        // {@link #stepBattleNetHallStill}. A global cycle gate desynced maps
        // whose halls OP0 on different cadences (Human 8@12 vs Human 13@15).

        ticking = false;
        // Adding and removing here rather than mid-loop keeps the iteration
        // valid. Births go in before deaths are swept so a unit created this
        // cycle is never collected by it.
        units.addAll(pending);
        pending.clear();
        // Native FUN_00453ae0 is stable: equal screen-Y units retain insertion
        // order (Java TimSort is stable; sort only by Y).
        battleNetSpatialUnits.sort(java.util.Comparator.comparingInt(
                World::battleNetScreenY));
        String tableCycle = System.getenv("CHONKCRAFT_TRACE_TABLE");
        if (tableCycle != null && cycle == Long.parseLong(tableCycle)) {
            for (int index = 0; index < units.size(); index++) {
                Unit tableUnit = units.get(index);
                System.err.printf("JTABLE cycle=%d index=%d unit=%d type=%s"
                                + " destroyed=%d removed=%d refs=%d%n",
                        cycle, index, tableUnit.id(),
                        tableUnit.type() == null ? "-" : tableUnit.type().ident(),
                        tableUnit.destroyed() ? 1 : 0, tableUnit.removed() ? 1 : 0,
                        approximateUnitRefs(tableUnit));
            }
        }
        snapshot = List.copyOf(units);
        traceFollowedUnitState();
    }

    /**
     * Writes the followed unit's hidden state once per cycle, when asked.
     *
     * <p>The native side of the parity lab can reconstruct a unit's whole
     * 152-byte record at every cycle from the sealed fixture, so a miner can
     * watch a counter climb to a threshold and a timer arm behind it without
     * knowing what either is called. The implementation had no counterpart: its causal
     * events fire when something happens -- a route is searched, a step is
     * taken, a number is drawn -- and a state that climbs quietly while the
     * unit stands still is exactly the shape those miss.
     *
     * <p>This is diagnostic only and reads state the engine already keeps. It
     * runs when {@code CHONKCRAFT_TRACE_BNE_CAUSAL} names a file and
     * {@code CHONKCRAFT_TRACE_BNE_CAUSAL_UNIT} names a unit, and returns on its
     * first line in every ordinary game.
     */
    private void traceFollowedUnitState() {
        Integer followed = causalTrace.focus();
        if (followed == null) {
            return;
        }
        Unit unit = null;
        for (Unit candidate : units) {
            if (candidate.id() == followed) {
                unit = candidate;
                break;
            }
        }
        if (unit == null) {
            return;
        }
        Unit target = unit.target();
        causalTrace.event(cycle, "state.unit", unit.id(),
                "order", unit.order() == null ? null : unit.order().name(),
                "next_order", unit.queuedOrders().isEmpty() ? null
                        : String.valueOf(unit.queuedOrders().getFirst()),
                "queued", unit.queuedOrders().size(),
                "carrying", unit.carrying() == null ? null
                        : unit.carrying().name(),
                "carried", unit.carried(),
                "x", unit.tileX(), "y", unit.tileY(),
                "offset_x", unit.offsetX(), "offset_y", unit.offsetY(),
                "moving", unit.isMoving(),
                "hp", unit.hitPoints(),
                "heading", unit.heading(),
                "path_length", unit.pathLength(),
                "wait", unit.waitCycles(),
                "collision", unit.battleNetCollisionCounter(),
                "order_delay", unit.battleNetOrderDelay(),
                "animation", unit.animation().index(),
                "animation_wait", unit.animation().waitCycles(),
                "battlenet_animation_timer", unit.battleNetAnimationTimer(),
                "battlenet_sequence", unit.battleNetSequenceOffset(),
                "battlenet_idle_phase", unit.battleNetIdlePhase(),
                "melee_sync_remaining", unit.battleNetMeleeSyncRemaining(),
                "attack_resume_hold", unit.battleNetAttackResumeHoldActive(),
                "ranged_scan_hold", unit.battleNetRangedFreeScanHoldActive(),
                "ranged_scan_pending", unit.battleNetRangedFreeScanHoldPending(),
                "target", target == null ? -1 : target.id(),
                "removed", unit.removed());
    }

    /**
     * Whether a unit's current animation is the Move script.
     *
     * <p>Native path soft-clear {@code 0x4500f0} reads animation state
     * {@code +0x08 == 3} (Move), not pixel displacement. Distinct from
     * {@link #isStepping}, which also requires the unbreakable stretch.
     */
    /**
     * One unit's state, in the columns that could stand in for a native
     * record field, for the field-parity probe.
     *
     * <p>Public and on {@code World} because the sequence tables and
     * {@code idle} are package private, and the probe writes from the parity
     * harness in another package.</p>
     */
    public String battleNetFieldParity(Unit unit) {
        int move = idle.battleNetSequenceStart(
                unit, BattleNetSequence.MOVE_ANIMATION);
        int attack = idle.battleNetSequenceStart(
                unit, BattleNetSequence.ATTACK_ANIMATION);
        return String.format(
                "moving=%d moveanim=%d seqoff=%d movestart=%d attackstart=%d "
                        + "pathn=%d spent=%d drained=%d holding=%d "
                        + "ox=%d oy=%d coll=%d refusals=%d wait=%d delay=%d "
                        + "chasing=%d freeprefix=%d freeprefixn=%d order=%s",
                unit.isMoving() ? 1 : 0,
                battleNetMoveAnimation(unit) ? 1 : 0,
                unit.battleNetSequenceOffset(), move, attack,
                unit.pathLength(), unit.routeSpent() ? 1 : 0,
                unit.stepDrained() ? 1 : 0, unit.walkHolding() ? 1 : 0,
                unit.offsetX(), unit.offsetY(),
                unit.battleNetCollisionCounter(), unit.battleNetRefusals(),
                unit.waitCycles(),
                unit.battleNetOrderDelay(), unit.chasing() ? 1 : 0,
                unit.battleNetGoldFreePrefix() ? 1 : 0,
                unit.battleNetGoldFreePrefixLength(),
                unit.order())
                + " route=" + battleNetFieldParityRoute(unit);
    }

    /** The headings this unit still holds, in walk order, for the probe. */
    private static String battleNetFieldParityRoute(Unit unit) {
        StringBuilder out = new StringBuilder();
        for (int depth = 0; depth < unit.pathLength(); depth++) {
            out.append(unit.peekHeadingAtDepth(depth));
        }
        return out.length() == 0 ? "-" : out.toString();
    }

    boolean battleNetMoveAnimation(Unit unit) {
        if (unit == null || unit.type() == null
                || unit.type().animationSet() == null) {
            return false;
        }
        Animation move = unit.type().animationSet().get(AnimationSet.State.MOVE);
        return move != null && unit.animation().current() == move;
    }

    /**
     * Axethrower/archer family: retail rebuilds after a refused leftover
     * chase heading rather than sleeping PF_WAIT ten.
     */
    static boolean battleNetRangedChaseUnit(Unit unit) {
        if (unit == null || unit.type() == null) {
            return false;
        }
        String ident = unit.type().ident();
        return "unit-axethrower".equals(ident)
                || "unit-archer".equals(ident)
                || "unit-berserker".equals(ident)
                || "unit-ranger".equals(ident)
                || "unit-sharp-axe".equals(ident);
    }

    /** Native cooperative-refusal predicate used by {@code FUN_004379e0}. */
    /**
     * Quiet visits after a cooperative first-square refuse.
     *
     * <p>FUN_004379e0 keeps the route for fourteen quiet visits. When a melee
     * chase replan residual is still armed, Attack-four (three more) is also
     * owed before the first new heading (XHuman 12 grunt 1495).
     */
    static int battleNetCooperativeRefuseQuietVisits(
            boolean meleeReplanResidualHold) {
        return meleeReplanResidualHold ? 17 : 14;
    }

    boolean battleNetCooperativeBlocker(Unit mover, Unit blocker) {
        if (mover == null || blocker == null || blocker == mover
                || !blocker.isOnMap() || blocker.isDying()
                || !isAllied(mover.player(), blocker.player())
                // Native waits for a blocker that is actually mid-step and
                // gives up on one that is not -- grunt 1494's blockers carry
                // action state 4 on both the cycles it marks route index 20,
                // and 1513's carries 3 on the cycle it re-arms -- and only the
                // pixel step separates those two here. Asking it costs 31
                // unit-cycles fleet-wide and XHuman 4 fourteen, so the
                // animation stays until the link after it lands.
                || !battleNetMoveAnimation(blocker)
                || blocker.battleNetCollisionCounter() != 0) {
            return false;
        }
        // A blocker with nothing left to walk is not cooperative.
        // `0x0044fa20` answers "no destination" for a spent route and for a
        // cursor moved to 20 alike, and `0x004379e0` reads that answer and
        // takes the give-up path rather than the wait. Across the captures
        // that is 92% of all units: 84% hold no route bytes at all, 4% have
        // finished one and 4% are refused.
        // ... unless it is only this implementation that has emptied the route. Retail
        // keeps the bytes and stops the cursor when a resource order comes
        // into range, so `0x0044fa20` still names a square; this implementation clears
        // them and had nothing to answer with. XHuman 10's peon 1596 stands on
        // 57,4 from fixture 9 to 24 with retail holding NW,N on cursor 1, and
        // 1590 behind it takes retail's fifteen-cycle wait on that answer.
        if (blocker.pathLength() == 0
                && blocker.routeSpent()
                && blocker.battleNetSpentHeading() >= 0) {
            int spent = blocker.battleNetSpentHeading();
            int spentStride = battleNetMovementStride(blocker);
            int spentX = blocker.tileX()
                    + Direction.deltaX(spent) * spentStride;
            int spentY = blocker.tileY()
                    + Direction.deltaY(spent) * spentStride;
            return spentX != mover.tileX() || spentY != mover.tileY();
        }
        if (blocker.pathLength() == 0) {
            return false;
        }
        int heading = blocker.peekHeading();
        int stride = battleNetMovementStride(blocker);
        int nextX = blocker.tileX() + Direction.deltaX(heading) * stride;
        int nextY = blocker.tileY() + Direction.deltaY(heading) * stride;
        return nextX != mover.tileX() || nextY != mover.tileY();
    }

    /** Ends a fight and restores the patrol or exploration it interrupted. */
    void finishAttackOrder(Unit unit) {
        // A presentation frame may have drawn a mobile weapon at the muzzle
        // before retail opcode ten constructs it. That placeholder belongs to
        // this attack order, not to the unit or the global missile list. When
        // the target dies before the handoff, ending the order must destroy an
        // unconstructed placeholder (or arm one whose constructor already
        // ran). Otherwise it remains painted forever and a later attack makes
        // the abandoned projectile start moving again.
        projectiles.interruptPendingAttack(unit);
        unit.setChasing(false);
        unit.setFighting(false);
        unit.setBattleNetStationaryAttack(false);
        unit.setBattleNetStationaryRecoveryHeld(false);
        // RestoreOrder swaps COrder objects only. PathFinderInput/Output live
        // on CUnit and survive the swap; the restored order's first movement
        // call invalidates them if its effective goal differs. Keeping them
        // is essential when it does not differ: AiHelpMe saves a position
        // attack at the aggressor's tile, and a sibling that cannot see the
        // temporary unit goal restores that same position without touching
        // its route. level11o carries the surviving tail from cycle 419 to
        // its turn at cycle 435.
        if (unit.savedOrder() == null) {
            unit.clearPath();
        }
        unit.setTarget(null);
        int savedAttackMoveX = unit.savedAttackMoveX();
        int savedAttackMoveY = unit.savedAttackMoveY();
        int savedMoveRange = unit.savedMoveRange();
        int savedAttackScanSleep = unit.savedAttackScanSleep();
        boolean savedAttackMoveOpening = unit.savedAttackMoveOpening();
        Unit.Order saved = unit.takeSavedOrder();
        // An attack-move restored from a standing unit's post carries
        // AUTO_TARGETING with it, which is what lets it choose its next target
        // itself. See {@link #rememberInterruptedOrder}.
        if (saved == Unit.Order.ATTACK_MOVE
                && savedAttackMoveX >= 0 && savedAttackMoveY >= 0) {
            unit.setAttackMove(savedAttackMoveX, savedAttackMoveY);
            unit.setMoveRange(savedMoveRange);
            unit.setAttackScanSleep(savedAttackScanSleep);
        }
        if (saved != null && saved != Unit.Order.ATTACK_MOVE) {
            unit.setAttackScanSleep(0);
        }
        unit.setAutoTargeting(saved == Unit.Order.ATTACK_MOVE);
        unit.setAttackMoveOpening(saved == Unit.Order.ATTACK_MOVE
                && savedAttackMoveOpening);
        unit.setOrder(saved == null ? Unit.Order.STILL : saved);
    }

    /**
     * Starts the scan clock over only when combat restores another order.
     *
     * <p>{@code COrder_Attack::EndActionAttack} has two distinct exits. A
     * successful {@code RestoreOrder} installs a separate saved order, whose
     * own Sleep and state come from the clone. A still-scan saves a fresh
     * order; a shove may save one already inside MOVE_TO_TARGET. With no
     * saved order, an attack-move resumes
     * inside the same {@code COrder_Attack}: it copies {@code attackMovePos}
     * back to {@code goalPos} and changes State, deliberately retaining
     * Sleep. levelx11o's destroyer 106 finishes
     * its long fight with Sleep 6 and resumes its march on cycle 219; resetting
     * the unit-level surrogate made it scan immediately and chase north-east.
     */
    static void resetRestoredAttackScan(Unit unit) {
        // Resuming this same order returns to AUTO_TARGETING. A separate
        // saved order carries its own State, restored below.
        unit.setAttackMoveOpening(true);
        Unit.Order saved = unit.savedOrder();
        // The left side of EndActionAttack's literal guard:
        //   (unit.UnderAttack && IsAutoTargeting()) || !unit.RestoreOrder()
        // A live under-attack counter prevents RestoreOrder even when a save
        // exists. The current COrder_Attack resumes instead, with Sleep and
        // SavedOrder both intact.
        boolean restoreDeferred = unit.underAttack() > 0 && unit.autoTargeting();
        if (saved != null && !restoreDeferred) {
            // A standing unit's SavedOrder is its own AUTO_TARGETING
            // COrder_Attack back to the post it left. RestoreOrder installs
            // that clone, including its attackMovePos; it must not reuse the
            // live fight's destination. levelx11o's juggernaught 87 has live
            // attackMovePos 19,39 and a saved post at 10,40 on cycle 259.
            // UnderAttack defers the restoration and therefore resumes 19,39;
            // once the counter expires, restoration installs 10,40.
            if (saved == Unit.Order.ATTACK_MOVE) {
                int savedX = unit.savedAttackMoveX();
                int savedY = unit.savedAttackMoveY();
                int savedRange = unit.savedMoveRange();
                int savedSleep = unit.savedAttackScanSleep();
                boolean savedOpening = unit.savedAttackMoveOpening();
                if (savedRange != unit.moveRange()) {
                    // RestoreOrder itself leaves PathFinderOutput on CUnit,
                    // then the restored order's first DoActionMove runs
                    // UpdatePathFinderData. A different Range makes that
                    // output stale before NextPathElement can serve its
                    // empty-route PF_WAIT.
                    unit.clearPath();
                }
                if (savedX >= 0 && savedY >= 0) {
                    unit.setAttackMove(savedX, savedY);
                }
                unit.takeSavedOrder();
                unit.setMoveRange(savedRange);
                unit.setAttackScanSleep(savedSleep);
                unit.setAttackMoveOpening(savedOpening);
            } else {
                unit.setAttackScanSleep(0);
            }
        }
    }

    /**
     * One swing at something already in reach.
     *
     * <p>Shared by the attack order and stand-ground, which differ only in
     * whether the unit is willing to walk. The damage lands on the animation
     * frame that says so rather than on a timer, so a unit's reach and its
     * rate of fire both come from the animation the scripts gave it.
     */
    /**
     * Turns an attacker towards what it is about to hit.
     *
     * <p>Implements {@code COrder_Attack::TurnToTarget}. The aim is not a snap:
     * the angle comes from {@code DirectionToHeading}'s arctangent table on
     * the target's centre against the attacker's own corner tile -- the
     * corner is upstream's, quirk and all -- and the difference is left in
     * the pending rotation for the animation to walk down, which is what the
     * ballista's and catapult's Attack animation waits thirty cycles on
     * ({@code "if-var R >= 30 turn"}, {@code scripts/human/anim.legacy-declaration}). A
     * fast turner consumes the whole difference inside its first animation
     * cycle and fires exactly as it did when this was a snap; the siege
     * engines fire thirty-one cycles later. On
     * {@code campaigns/human-exp/levelx04h} that is the bolt leaving at 58
     * rather than 27, and landing on the cluster at 84 rather than 56.
     *
     * <p>The four broadside warships then offset the facing a quarter turn
     * to whichever side lies nearer the old one -- {@code SideAttack},
     * which was parsed and read by nothing -- without re-aiming the pending
     * rotation, as upstream's own ordering has it. {@code SurroundAttack}'s
     * early return is not modelled: no shipped type declares it.
     */
    void turnToTarget(Unit unit, Unit target, int goalX, int goalY) {
        int dx;
        int dy;
        if (target != null) {
            dx = target.tileX() + Math.max(1, target.type().tileWidth()) / 2 - unit.tileX();
            dy = target.tileY() + Math.max(1, target.type().tileHeight()) / 2 - unit.tileY();
        } else {
            dx = goalX - unit.tileX();
            dy = goalY - unit.tileY();
        }
        int oldDirection = unit.direction();
        unit.turnToAngle(net.chonkbase.chonkcraft.engine.missile.Missile.directionToHeading(dx, dy));
        if (unit.type().sideAttack()) {
            int leftTurn = (unit.direction() - 64) & 0xFF;
            int rightTurn = (unit.direction() + 64) & 0xFF;
            // Upstream's comparison is on the plain difference of the two
            // unsigned bytes, not the way round the circle

            if (Math.abs(leftTurn - oldDirection) < Math.abs(rightTurn - oldDirection)) {
                unit.setDirection(leftTurn);
            } else {
                unit.setDirection(rightTurn);
            }
        }
    }

    void strike(Unit unit, Unit target) {
        // The animation reads the turn, so the turn must not be refreshed
        // first: AttackTarget opens with AnimateActionAttack and only
        // reaches its own TurnToTarget on a breakable exit
        // The catapult's Attack script
        // opens "if-var R >= 30 turn" and holds thirty cycles; re-aiming
        // before the advance zeroed Anim.Rotate on the beat the script read
        // it, and on campaigns/human-exp/levelx10h this implementation's catapult at
        // 75,88 loosed its rock at 35 where upstream's, turned at 34 with
        // sixty-four still pending, holds until 66.
        AnimationSet set = unit.type().animationSet();
        if (set == null) {
            return;
        }
        Animation attack = set.get(AnimationSet.State.ATTACK);
        if (attack == null) {
            return;
        }
        unit.animation().switchTo(attack);
        AnimationRunner.Step step = advance(unit);
        if (step.attacked()) {
            combat.hit(unit, target);
        }
        if (!unit.animation().unbreakable()) {
            turnToTarget(unit, target, 0, 0);
        }
    }

    /**
     * Carries a committed swing through to its end.
     *
     * <p>The animation is stepped and nothing else about the unit is decided,
     * which is what {@code Anim.Unbreakable} means everywhere upstream reads
     * it. The blow still lands even if its target has since walked away: that
     * is Warcraft II's answer to kiting and it was missing here.
     */
    void finishSwing(Unit unit) {
        AnimationRunner.Step step = advance(unit);
        if (!step.attacked()) {
            return;
        }
        Unit target = unit.target();
        if (target != null && target.isAlive() && !target.isDying()) {
            combat.hit(unit, target);
        }
    }

    /**
     * A point roughly {@code minRange} tiles away along the line to or from
     * somewhere, with a little scatter.
     *
     * <p>{@code GetRndPosInDirection}, draws and all: the scatter comes off
     * the simulation's own generator so two machines pick the same square.
     *
     * @param away true to head away from the given point rather than towards it
     */
    int[] rndPosInDirection(int fromX, int fromY, int towardsX, int towardsY,
            boolean away, int minRange, int deviation) {
        int dx = towardsX - fromX;
        int dy = towardsY - fromY;
        if (away) {
            dx = -dx;
            dy = -dy;
        }
        int length = isqrt(dx * dx + dy * dy);
        if (length == 0) {
            length = 1;
        }
        int range = minRange + syncRand(RANGE_DEVIATION + 1);
        int x = fromX + dx * range / length + (deviation - syncRand(deviation * 2 + 1));
        int y = fromY + dy * range / length + (deviation - syncRand(deviation * 2 + 1));
        return new int[] {
            Math.max(0, Math.min(map.width() - 1, x)),
            Math.max(0, Math.min(map.height() - 1, y)),
        };
    }

    /** Upstream's {@code rangeDev} default: the throw is 0 to 3 tiles longer. */
    private static final int RANGE_DEVIATION = 3;

    /** The whole part of a square root, as computes it. */
    private static int isqrt(int value) {
        if (value <= 0) {
            return 0;
        }
        int root = (int) Math.sqrt(value);
        while (root * root > value) {
            root--;
        }
        while ((root + 1) * (root + 1) <= value) {
            root++;
        }
        return root;
    }

    /** Uses BNE's own distance metric, not the Chebyshev one on the unit. */
    int attackDistance(Unit unit, Unit target) {
        return battleNetDistance(unit, target);
    }

    /**
     * How long this unit's death animation runs, or nothing if it has none.
     *
     * <p>Nothing is the right answer and not a shortcut.
     * {@code AnimateActionDie} returns false when a
     * type declares no {@code Death} animation, and {@code COrder_Die::Execute}
     * then swaps the unit for its corpse on that same cycle. Every building in
     * Warcraft II is in that case -- {@code animations-building} in
     * {@code anim.legacy-declaration} declares {@code Still}, {@code Research}, {@code Train}
     * and {@code Upgrade} and no death -- so a keep goes straight to rubble
     * behind its own explosion.
     */
    private static int deathCycles(Unit unit) {
        AnimationSet set = unit.type() == null ? null : unit.type().animationSet();
        Animation death = set == null ? null : set.get(AnimationSet.State.DEATH);
        return death == null ? 0 : death.cycles();
    }

    /** Runs the death animation, then takes the unit off the map. */
    private void stepDying(Unit unit) {
        // The release is the animation's own: COrder_Die::Execute runs the
        // death animation and acts the moment Anim.Unbreakable has let go
        // The game the tail "wait 1" past the unbreakable
        // end is never served, and the corpse the unit becomes runs the same
        // rule through its own decay script. A timer summed from the whole
        // script ran one cycle long, which showed only on the corpseless:
        // levelx09h's battle-slain skeletons left upstream's roster at 87
        // and this one's at 88, once per skeleton for the whole map.
        AnimationSet set = unit.type().animationSet();
        Animation death = set == null ? null : set.get(AnimationSet.State.DEATH);
        if (death != null) {
            unit.animation().switchTo(death);
            advance(unit);
            if (unit.animation().unbreakable()) {
                return;
            }
        }
        if (!becomeCorpse(unit)) {
            // A type that leaves nothing is Removed and Released in the same
            // breath, and a released unit is out of
            // the roster before the cycle's state is read -- upstream's
            // trace lists levelx09h's spent skeleton through 86 and not at
            // 87, where this implementation's next-top sweep kept it one cycle more.
            // The animation-driven die instruction keeps the sweep: demo02's
            // vision marker is still listed on the cycle its die runs.
            markOccupancy(unit, false);
            unit.setRemoved(true);
            unit.setDeathTimer(0);
            int remainingRefs = approximateUnitRefs(unit);
            String releaseTrace = System.getenv("CHONKCRAFT_TRACE_RELEASE");
            if (releaseTrace != null && unit.id() == Integer.parseInt(releaseTrace)) {
                System.err.printf("JRELEASE cycle=%d unit=%d refs=%d%n",
                        cycle, unit.id(), remainingRefs);
                for (Unit holder : units) {
                    StringBuilder reasons = new StringBuilder();
                    if (holder.target() == unit) reasons.append(" target");
                    if (holder.offeredTarget() == unit) reasons.append(" offered");
                    if (holder.pendingAttack() == unit) reasons.append(" pending-attack");
                    if (holder.resourceDepot() == unit) reasons.append(" resource-depot");
                    if (holder.returnDepotGoal() == unit) reasons.append(" return-depot");
                    if (holder.worksite() == unit) reasons.append(" worksite");
                    for (Unit.QueuedOrder queued : holder.queuedOrders()) {
                        if (queued.target() == unit) reasons.append(" queued-target");
                    }
                    if (!reasons.isEmpty()) {
                        System.err.printf("JRELEASE holder=%d%s%n", holder.id(), reasons);
                    }
                }
                for (Missile missile : missiles) {
                    if (missile.source() == unit || missile.target() == unit) {
                        System.err.printf("JRELEASE missile source=%d target=%d%n",
                                missile.source() == null ? -1 : missile.source().id(),
                                missile.target() == null ? -1 : missile.target().id());
                    }
                }
            }
            if (remainingRefs > 1) {
                // First CUnit::Release: surrender the unit's own reference,
                // mark it Destroyed, and leave its active-table slot alone
                // until the last CUnitPtr is reset. levelx09h's skeleton 168
                // dies with attackers still holding it; immediately swapping
                // the final dead-vision marker into this hole made the action
                // table diverge at cycle 87 despite identical visible units.
                unit.setDestroyed(true);
            } else {
                releaseUnitFromActionTable(unit);
            }
        }
    }

    /**
     * Turns a unit that has finished dying into the body it leaves.
     *
     * <p>The unit <em>becomes</em> the corpse. It is not replaced by one:
     * {@code COrder_Die::Execute} does
     *
     * <pre>
     * unit.Remove(nullptr);
     * unit.Type = &amp;corpseType;
     * UpdateUnitSightRange(unit);
     * unit.Place(unit.tilePos);
     * unit.Frame = 0;
     * UnitUpdateHeading(unit);
     * AnimateActionDie(unit); // with new corpse.
     * </pre>
     *
     * so the body keeps the dead
     * unit's slot, its owner and its heading, and the death animation carries
     * straight on into the corpse's own. Only a type that leaves nothing --
     * {@code CorpseType == nullptr} -- is removed and released.
     *
     * <p>Making a new unit instead is visible three ways, all of them on
     * {@code maps/demo/demo02} at cycle 120 where a peasant finishes dying at
     * 0,25. The body is a different unit, so the harness cannot pair it with
     * upstream's. It belongs to nobody rather than to the peasant's own
     * player. And making a unit draws a heading from the shared random stream
     * so this implementation drew where
     * upstream did not, and every roll either engine made afterwards was a
     * different number.
     *
     * @return whether there was a body to become
     */
    private boolean becomeCorpse(Unit unit) {
        String ident = unit.type() == null ? null : unit.type().corpse();
        if (ident == null || ident.isEmpty() || unitTypes == null) {
            return false;
        }
        UnitType corpse = unitTypes.get(ident);
        if (corpse == null) {
            return false;
        }
        // The sight comes off before the type changes, because the two types
        // see different distances and it has to be taken away at the range it
        // was granted -- upstream says so on the line above its own Remove.
        markSight(unit, false);
        markOccupancy(unit, false);
        unit.becomeType(corpse);
        unit.setFrame(0);
        // Place always inserts the body in CMapField::UnitCache even when
        // MarkUnitFieldFlags declines to set a flag for a Vanishes type.
        // EnemyUnitFinder's Select sees that cache entry during the body's
        // decay animation.
        markOccupancy(unit, true);
        unit.setOrder(Unit.Order.DYING);
        unit.setDeathTimer(deathCycles(unit));
        // COrder_Die does not return after changing Type: its last statement
        // is AnimateActionDie(unit) with the corpse's new animation. That
        // first beat matters at the far end of the body as well as here.
        // levelx11o's battleship becomes its sea body on cycle 193; starting
        // this script only on 194 kept the body through 393, one cycle after
        // upstream released it.
        AnimationSet corpseSet = corpse.animationSet();
        Animation corpseDeath = corpseSet == null ? null
                : corpseSet.get(AnimationSet.State.DEATH);
        if (corpseDeath != null) {
            unit.animation().switchTo(corpseDeath);
            advance(unit);
        }
        return true;
    }

    /**
     * Starts a route towards a target and retains the pathfinder's verdict.
     *
     * <p>Most callers only distinguish a route from no route. An attack
     * chase also needs {@code PF_REACHED}: MoveToTarget handles that result
     * before it checks whether the goal is still valid, which commits one
     * swing even at a goal that began dying during the route's empty wait.
     */
    PathFinder.Result planTowards(Unit unit, Unit target) {
        // Aimed at anywhere this unit could hit the target from, not at the
        // square the target is standing on. That square is occupied by
        // definition, so a route to it can only end on top of somebody: the
        // walk then stops short, the attack order finds itself still out of
        // range, asks for the same route again, and the unit jogs on the spot
        // forever without ever swinging. Upstream never asks for that -- it
        // hands the pathfinder the target's footprint and the attack range
        // together, in COrder_Attack::UpdatePathFinderData.
        PathFinder.Goal goal = new PathFinder.Goal(
                target.tileX(), target.tileY(),
                Math.max(1, target.type().tileWidth()),
                Math.max(1, target.type().tileHeight()),
                unit.type().minAttackRange(),
                Math.max(1, unit.type().maxAttackRange()));
        // A dry chase route is re-planned from inside this method. If the
        // last heading already put the weapon in range, MoveToTarget's next
        // consult is PF_REACHED: it turns to the quarry instead of drawing a
        // fresh heading into the occupied target square. The BNE route
        // generator itself (0x0044fbd0) does not test the marked start tile;
        // this is the caller-level arrival verdict that consumes its route.
        if (targets.inAttackRange(unit, target)) {
            return PathFinder.Result.REACHED;
        }
        PathFinder.Path path = findBattleNetTargetPath(unit, target);
        if (path.result() != PathFinder.Result.FOUND) {
            if (path.result() == PathFinder.Result.UNREACHABLE) {
                // The chase planner is NextPathElement inside DoActionMove
                // upstream. Its PF_UNREACHABLE arm calls AiCanNotMove before
                // returning the verdict, using the target footprint currently
                // installed in PathFinderInput.
                aiCanNotMove(unit, goal.x(), goal.y(), goal.width(), goal.height());
            }
            return path.result();
        }
        if (path.length() > 0 && unit.offeredTarget() != null) {
            // Offered free-scan arm only. Equal Chebyshev first steps keep the
            // current face when free (1500 face 7 → NW; 1493 SE still wins).
            path = preferBattleNetFaceFirstHeading(unit, path, target);
        }
        if (path.length() > 0 && unit.battleNetPersonHelpFirstChase()) {
            // Person-help first chase only: equal-cost diagonal onto a lead
            // mid-Move brother (XHuman 10 knight 1493 SW onto 1489).
            path = preferBattleNetGoalAxisFirstHeading(unit, path, target);
            unit.setBattleNetPersonHelpFirstChase(false);
        }
        unit.setPath(path);
        if (path.length() == 0) {
            // Retail's failed marked-target route is twenty 0xff bytes. The
            // attack order stays active (not PF_UNREACHABLE → Still). A full
            // PF_WAIT(10) before the next plan misses XHuman 4: axethrowers
            // 1506/1516 hold empty routes while allies block the west approach,
            // then step south-west the cycle the grunt leaves. Two cycles is
            // enough for the action-marker idle draws Human 13's critters need
            // and short enough to replan once the square frees.
            unit.setRouteSpent(true);
            unit.setWaitCycles(2);
        }
        // Remember where it was aimed, so the chase can tell that the target
        // has since walked off and the route is stale.
        unit.setPathGoal(target.tileX(), target.tileY());
        return path.result();
    }

    /**
     * Applies MoveToTarget's PF_UNREACHABLE tail.
     *
     * <p>The order clears its goal and calls EndActionAttack. A shove made by
     * the same failed DoActionMove can have cloned this very COrder_Attack
     * into SavedOrder first; restoring that clone consumes the save while
     * leaving the chase state and Sleep exactly as they were. With no clone,
     * a weak auto-targeting order resumes its attack-move destination inside
     * the same order and therefore keeps Sleep.
     */
    void endUnreachableAttackChase(Unit unit, Unit target) {
        Unit.Order saved = unit.savedOrder();
        boolean restoreDeferred = unit.underAttack() > 0 && unit.autoTargeting();
        boolean sameAttackClone = !restoreDeferred
                && saved == unit.order()
                && (saved != Unit.Order.ATTACK_MOVE
                    || (unit.savedAttackMoveX() == unit.attackMoveX()
                        && unit.savedAttackMoveY() == unit.attackMoveY()));
        if (sameAttackClone) {
            int savedRange = unit.savedMoveRange();
            int savedSleep = unit.savedAttackScanSleep();
            boolean savedOpening = unit.savedAttackMoveOpening();
            unit.takeSavedOrder();
            unit.setMoveRange(savedRange);
            unit.setAttackScanSleep(savedSleep);
            unit.setAttackMoveOpening(savedOpening);
            return;
        }
        if (!restoreDeferred && saved != null) {
            finishAttackOrder(unit);
            return;
        }

        boolean resumesDestination = unit.autoTargeting()
                && target != null
                && (target.tileX() != unit.attackMoveX()
                    || target.tileY() != unit.attackMoveY());
        unit.setTarget(null);
        unit.setChasing(false);
        unit.setFighting(false);
        unit.setSwingAtAir(false);
        unit.clearPath();
        if (resumesDestination) {
            unit.setAttackMoveOpening(true);
            return;
        }
        unit.setAutoTargeting(false);
        finishOrder(unit);
    }

    /**
     * Whether the unbreakable animation a unit is running is its attack.
     *
     * <p>The distinction the plain flag cannot make. Attack, Move and Death
     * are all declared unbreakable in the shipped animations, and only the
     * first of the three means "do not disturb this unit, it is mid-swing".
     */
    boolean isSwinging(Unit unit) {
        if (unit.type() == null || unit.type().animationSet() == null) {
            return false;
        }
        Animation attack = unit.type().animationSet().get(AnimationSet.State.ATTACK);
        return attack != null && unit.animation().current() == attack;
    }

    /** Explicit pending-shot ownership line for {@code CHONKCRAFT_TRACE_BNE_PEND}. */
    void logBattleNetPend(String event, Unit attacker, Unit target,
            Missile shot, String reason, long queuedCycle) {
        if (!BNE_PEND_TRACE) {
            return;
        }
        String atType = attacker != null && attacker.type() != null
                ? attacker.type().ident() : "null";
        String tgType = target != null && target.type() != null
                ? target.type().ident() : "null";
        boolean building = target != null && target.type() != null
                && target.type().building();
        String mType = shot != null && shot.type() != null
                ? shot.type().ident() : "null";
        int seq = attacker == null ? -1 : attacker.battleNetSequenceOffset();
        int timer = attacker == null ? -1 : attacker.battleNetAnimationTimer();
        String order = attacker == null || attacker.order() == null
                ? "null" : attacker.order().name();
        long q = queuedCycle;
        if (q < 0 && shot != null) {
            q = battleNetPendingProjectileQueuedCycle.getOrDefault(shot, -1L);
        }
        System.err.printf("JBNEPEND event=%s cycle=%d attacker=%d type=%s "
                        + "order=%s seq=%d timer=%d target=%d type=%s "
                        + "building=%d missile=%s shot=%s queuedCycle=%d "
                        + "drawn=%d reason=%s%n",
                event, cycle,
                attacker == null ? -1 : attacker.id(), atType, order, seq,
                timer,
                target == null ? -1 : target.id(), tgType, building ? 1 : 0,
                mType,
                shot == null ? "null"
                        : Integer.toHexString(System.identityHashCode(shot)),
                q,
                shot != null && shot.battleNetConstructorDrawn() ? 1 : 0,
                reason);
    }

    /**
     * Puts a missile on the map and lets it be heard leaving.
     *
     * <p>Every {@code MakeMissile} upstream ends by playing the type's
     * {@code FiredSound}, and the implementation had no such
     * choke point, so the field was neither parsed nor played. Only
     * {@code missile-critter-explosion} sets it in Warcraft II, but routing
     * every creation through here is what stops the next missile that does from
     * being silent as well.
     *
     * @return the missile, so callers can go on to set a delay or a damage
     */
    Missile spawn(Missile missile) {
        if (missile != null && missile.battleNetPoolSlot() < 0) {
            missile.setBattleNetPoolSlot(allocateBattleNetProjectileSlot());
        }
        missiles.add(missile);
        missileSnapshot = List.copyOf(missiles);
        announceNamed(missile.source(), missile.type().firedSound());
        if (System.getenv("CHONKCRAFT_TRACE_MISSILE") != null) {
            // The implementation-side twin of MakeMissile's MISSILEDBG print in
            // tools/legacyEngine-trace.patch, in the same pixel coordinates.
            System.err.println("JMISSILE " + cycle + " fired type=" + missile.type().ident()
                    + " from " + (int) missile.x() + "," + (int) missile.y()
                    + " to " + (int) missile.toX() + "," + (int) missile.toY()
                    + " goal=" + (missile.target() == null ? -1 : missile.target().id())
                    + " slot=" + (missile == null ? -1 : missile.battleNetPoolSlot()));
        }
        return missile;
    }

    /**
     * Puts an already-flying missile back in the air, for a loaded game.
     *
     * <p>Not {@link #spawn}: that is the launch, and a launch plays the type's
     * {@code FiredSound}. A saved game restoring a catapult boulder halfway to
     * its target is not a catapult firing, and a loaded map that cracked with
     * every shot still in flight would be the giveaway. The bookkeeping is
     * otherwise identical -- the missile joins the live list and the snapshot
     * the renderer reads is republished.
     *
     * <p>Exists for {@code engine/save}, which had no way in: the list is
     * private and published only as an immutable copy, so a shot in the air
     * was the last thing a save could not carry across.
     */
    public void restoreMissile(Missile missile) {
        if (missile == null) {
            return;
        }
        int slot = missile.battleNetPoolSlot();
        if (slot >= 3 && slot < BNE_PROJECTILE_POOL) {
            battleNetProjectileSlots[slot] = true;
        }
        missiles.add(missile);
        missileSnapshot = List.copyOf(missiles);
    }

    /** Constructor cycle recorded for a BNE projectile, or -1 when not armed. */
    public long savedProjectileStartCycle(Missile missile) {
        return battleNetProjectileStartCycles.getOrDefault(missile, -1L);
    }

    /** Presentation-ahead allocation cycle, or -1 when the shot was not queued. */
    public long savedProjectileQueuedCycle(Missile missile) {
        return battleNetPendingProjectileQueuedCycle.getOrDefault(missile, -1L);
    }

    /** Whether this shot still waits for its source unit's opcode-ten boundary. */
    public boolean savedProjectilePending(Missile missile) {
        return battleNetPendingProjectileShots.containsValue(missile);
    }

    /**
     * Whether a missile has crossed the retail constructor boundary and may
     * be drawn.
     *
     * <p>Mobile attack presentation can allocate a bookkeeping placeholder
     * before BNE reaches opcode ten. It belongs to no retail frame yet. The
     * renderer used to draw that placeholder at the muzzle, leaving a
     * fireball apparently stuck on a battleship for several seconds before
     * the real launch. Keep the object private to simulation until its owner
     * reaches the authoritative firing opcode.</p>
     */
    public boolean missileVisible(Missile missile) {
        return missile != null && !savedProjectilePending(missile);
    }

    /** Resolves a saved missile type and restores it without a launch sound. */
    public void restoreMissile(String typeIdent, Unit source, Unit target,
            Missile.SavedState state, long startCycle, long queuedCycle,
            boolean pending) {
        // A pending shot is an order-owned pre-constructor placeholder. Old
        // saves could retain it after omitting the dead source unit; restoring
        // it as an ordinary missile would create a source-less ghost shot.
        if (pending && source == null) {
            return;
        }
        MissileType type = missileTypes == null ? null : missileTypes.get(typeIdent);
        if (type != null && state != null) {
            Missile missile = Missile.restore(type, source, target, state);
            restoreMissile(missile);
            if (startCycle >= 0) {
                battleNetProjectileStartCycles.put(missile, startCycle);
            }
            if (queuedCycle >= 0) {
                battleNetPendingProjectileQueuedCycle.put(missile, queuedCycle);
            }
            if (pending && source != null) {
                projectiles.queuePendingAttack(source, missile, queuedCycle);
            }
        }
    }

    /** Half a unit's footprint, in pixels: the offset from its corner to its middle. */
    static int centreOffset(UnitType type, boolean horizontal) {
        int tiles = type == null
                ? 1
                : Math.max(1, horizontal ? type.tileWidth() : type.tileHeight());
        return tiles * TILE_SIZE / 2;
    }

    /**
     * BNE's per-PUD-type projectile centre, initialized by native 0x00450f20.
     *
     * <p>The retail executable copies the 110 unit dimensions at
     * {@code 0x004cee6c/0x004cee6e} into the pixel-centre table at
     * {@code 0x004ae584/0x004ae586}, shifting each dimension left four bits.
     * These dimensions are deliberately distinct from ChonkCraft's gameplay
     * footprint: ships and flying machines are one tile wide in this table
     * even though the ChonkCraft definitions give them a 2x2 footprint.
     */
    static int battleNetCentreOffset(UnitType type, boolean horizontal) {
        int code = type == null ? -1 : PudUnitTypes.code(type.ident());
        if (code < 0 || code >= BNE_UNIT_DIMENSIONS.length
                || BNE_UNIT_DIMENSIONS[code] == 0) {
            return centreOffset(type, horizontal);
        }
        // Every retail 2.02b entry is square, so x and y share this table.
        return BNE_UNIT_DIMENSIONS[code] * TILE_SIZE / 2;
    }

    /** Retail BNE 2.02b unit dimensions, indexed by the PUD type byte. */
    private static final int[] BNE_UNIT_DIMENSIONS = {
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        1, 1, 0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1,
        0, 1, 1, 1, 1, 1, 0, 1, 1, 1, 2, 2, 3, 3, 3, 3,
        2, 2, 3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 3, 3, 3, 3,
        3, 3, 3, 3, 3, 3, 3, 3, 4, 4, 4, 4, 3, 3, 1, 1,
        2, 2, 2, 2, 2, 4, 2, 1, 1, 1, 1, 2, 3, 4
    };

    /** One tile, in pixels. */
    static final int TILE_SIZE = 32;

    /**
     * Knocks a piece out of a wall.
     *
     * <p>{@code MissileHitsWall} {@code CMap::HitWall}. The implementation had no
     * counterpart to either, so a wall was indestructible terrain: catapults,
     * ballistae and cannon towers could not break through a walled base and the
     * only way past one was a gap the mapper had left. Nine campaign maps wall
     * something off.
     *
     * <p>The damage figure follows upstream's split. A missile carrying its own
     * damage -- a spell -- spends it directly; anything else is the firer's
     * stats measured against the wall's own armour, which is why an upgraded
     * catapult breaks a wall faster.
     */
    void hitWall(Missile missile, int tileX, int tileY, int falloff) {
        var field = map.fieldOrNull(tileX, tileY);
        if (field == null || !field.isWall()) {
            return;
        }
        int damage;
        if (missile.damage() != 0) {
            damage = missile.damage() / Math.max(1, falloff);
        } else if (missile.source() == null) {
            return;
        } else {
            UnitType wall = wallTypeFor(field);
            UpgradeState upgrades = upgrades(missile.source().player());
            int basic = upgrades.basicDamage(missile.source().type());
            int armor = wall == null ? 0 : wall.armor();
            int raw = Math.max(basic - armor, 1)
                    + upgrades.piercingDamage(missile.source().type());
            raw -= syncRand((raw + 2) / 2);
            damage = raw / Math.max(1, falloff);
        }
        if (damage > 0) {
            boolean stood = field.isWall();
            map.hitWall(tileX, tileY, damage, wallMaxHitPoints(field));
            if (stood && !field.isWall()) {
                reachable.clear();
            }
        }
    }

    /**
     * Lands a weapon with no missile on wall terrain.
     *
     * <p>{@code FireMissile} handles
     * {@code MissileClass::Nothing} before it asks for a unit goal. When that
     * goal is a wall tile it applies the attacker's ordinary damage against
     * the wall type's armour. Omitting that branch left every footman and
     * grunt able to swing at units but unable to touch a wall.
     */
    void hitWall(Unit attacker, int tileX, int tileY) {
        MapField field = map.fieldOrNull(tileX, tileY);
        if (field == null || !field.isWall() || attacker == null) {
            return;
        }
        UnitType wall = wallTypeFor(field);
        UpgradeState state = upgrades(attacker.player());
        int basic = state.basicDamage(attacker.type());
        int piercing = state.piercingDamage(attacker.type());
        // A wall takes the doubling too. and :357 hand
        // CalculateDamageStats the attacker's BLOODLUST_INDEX for the wall
        // arm exactly as the unit arm does.
        if (attacker.hasBuff(Unit.Buff.BLOODLUST)) {
            basic *= 2;
            piercing *= 2;
        }
        int armor = wall == null ? 0 : wall.armor();
        int damage = Math.max(basic - armor, 1) + piercing;
        damage -= syncRand((damage + 2) / 2);
        if (damage <= 0) {
            return;
        }
        map.hitWall(tileX, tileY, damage, wallMaxHitPoints(field));
        if (!field.isWall()) {
            reachable.clear();
        }
    }

    /**
     * Which wall type a square holds.
     *
     * <p>Upstream keeps {@code UnitTypeHumanWall} and {@code UnitTypeOrcWall}
     * as globals and picks between them with {@code Map.HumanWallOnMap}. The
     * tile's {@code human} flag carries the same answer here, and it is set by
     * the tileset scripts on exactly the human wall slots.
     */
    private UnitType wallTypeFor(net.chonkbase.chonkcraft.engine.map.MapField field) {
        if (unitTypes == null) {
            return null;
        }
        boolean human = field.hasFlag(net.chonkbase.chonkcraft.engine.map.TileFlag.HUMAN);
        return unitTypes.get(human ? "unit-human-wall" : "unit-orc-wall");
    }

    /** How tough a whole wall of this race is, for picking the damaged graphic. */
    private int wallMaxHitPoints(net.chonkbase.chonkcraft.engine.map.MapField field) {
        UnitType wall = wallTypeFor(field);
        return wall == null || wall.hitPoints() <= 0
                ? net.chonkbase.chonkcraft.engine.map.GameMap.WALL_HIT_POINTS
                : wall.hitPoints();
    }

    /** Whether two types travel through the same element. */
    static boolean sameMedium(UnitType one, UnitType other) {
        return one.airUnit() == other.airUnit() && one.seaUnit() == other.seaUnit();
    }

    /** Raises the local {@code missile-class-hit} feedback for a nonfatal hit. */
    void showDamage(Unit target, int damage) {
        if (damageMissile == null || missileTypes == null) {
            return;
        }
        MissileType type = missileTypes.get(damageMissile);
        if (type == null || type.missileClass()
                != net.chonkbase.chonkcraft.engine.missile.MissileClass.HIT) {
            return;
        }
        int x = target.pixelX() + centreOffset(target.type(), true);
        int y = target.pixelY() + centreOffset(target.type(), false);
        Missile figure = new Missile(type, null, target,
                x, y, x + 3, y - Math.max(1, type.range()));
        figure.setDamage(-damage);
        spawn(figure);
    }

    /**
     * How much a blow is worth.
     *
     * <p>{@code MissileHitsGoal} picks between three
     * sources in this order, and the implementation only ever used the third. The first
     * is the missile type's own {@code Damage}, which in Warcraft II is
     * {@code Rand(10)} on a blizzard shard and on a death-and-decay cloud and
     * nothing anywhere else. The second is the figure a spell wrote onto the
     * individual shot -- a fireball's twenty, a death coil's fifty. Only when
     * neither is set does the firer's own strength decide it.
     */
    int damageFor(Unit attacker, Unit target, int falloff, Missile missile) {
        int divisor = Math.max(1, falloff);
        MissileType type = missile == null ? null : missile.type();
        if (type != null && type.declaresDamage()) {
            return type.damageAt(syncRand(type.damageRandom())) / divisor;
        }
        if (missile != null && missile.damage() != 0) {
            return missile.damage() / divisor;
        }
        if (missile == null && battleNetNativeMeleeDamage.remove(attacker)) {
            // Opcode-10 melee uses FUN_00418370 on the asynchronous stream.
            // The ordinary synchronized formula below desynced Human 5's
            // barracks combat.hit(seed advanced; damage 2 vs native 3).
            return battleNetMeleeDamage(attacker, target) / divisor;
        }
        // Stats are read through the owner's research rather than off the type,
        // because Warcraft II's upgrades are army-wide: a footman fights at
        // whatever strength its player has paid for.
        UpgradeState attackerUpgrades = upgrades(attacker.player());
        UpgradeState targetUpgrades = upgrades(target.player());

        int basic = attackerUpgrades.basicDamage(attacker.type());
        int piercing = attackerUpgrades.piercingDamage(attacker.type());
        // Bloodlust doubles both halves, before the armour is taken off:
        // CalculateDamageStats multiplies basic_damage
        // and piercing_damage and only then subtracts the target's armour, so
        // the spell is worth more against a well armoured target than doubling
        // the result would be. That ordering is the spell.
        if (attacker.hasBuff(Unit.Buff.BLOODLUST)) {
            basic *= 2;
            piercing *= 2;
        }
        int armor = targetUpgrades.armor(target.type());
        int damage = Math.max(basic - armor, 1);
        damage += piercing;
        if (RAND_TRACE_PATH != null) {
            randContext = attacker.id() + ">" + target.id();
        }
        damage -= syncRand((damage + 2) / 2);
        if (RAND_TRACE_PATH != null) {
            randContext = null;
        }
        return Math.max(1, damage / divisor);
    }

    /**
     * BNE 2.02 {@code FUN_00418370}, used by script.bin opcode-ten melee hits.
     *
     * <p>Basic-minus-armor floors at zero (not one), piercing is added, then
     * half + {@code AsyncRand() % (half + 1)}. The draw is the asynchronous
     * stream only; the schema-1.1 synchronized seed is untouched.
     */
    private int battleNetMeleeDamage(Unit attacker, Unit target) {
        UpgradeState attackerUpgrades = upgrades(attacker.player());
        UpgradeState targetUpgrades = upgrades(target.player());
        int basic = attackerUpgrades.basicDamage(attacker.type());
        int piercing = attackerUpgrades.piercingDamage(attacker.type());
        if (attacker.hasBuff(Unit.Buff.BLOODLUST)) {
            basic *= 2;
            piercing *= 2;
        }
        int maximum = Math.max(basic - targetUpgrades.armor(target.type()), 0)
                + piercing;
        if (maximum <= 0) {
            return 0;
        }
        int half = (maximum + 1) / 2;
        return Math.min(0xff, half + battleNetRand() % (half + 1));
    }

    /**
     * When and where a player was last told they were under attack.
     *
     * @param quietUntil the cycle before which no further cry is raised at all,
     *                   which upstream keeps as {@code HelpMeLastCycle}
     */
    record HelpCry(long cycle, long quietUntil, int tileX, int tileY) {}

    final java.util.Map<Integer, HelpCry> helpCries = new java.util.HashMap<>();

    /** How far a fresh attack has to be to be worth its own cry, in tiles. */
    static final int HELP_CRY_RADIUS = 14;

    /**
     * Sets a damaged building alight.
     *
     * <p>{@code HitUnit}'s {@code if (type->Building && !target.Burning)
     * HitUnit_Burning(target)}, at the same point in the sequence: after the
     * damage has landed and after the target has been checked for death, so a
     * building destroyed by the blow is never lit on its way down.
     *
     * <p>Both halves of that condition earn their keep. Without the building
     * test a footman would burn, which nothing in Warcraft II does. Without
     * the burning test every blow would light another fire on the same
     * building -- a keep under attack takes several a second -- and they would
     * pile up, never sharing a flag and so never going out either.
     *
     * <p>Whether it catches at all is the script's decision, not this one's:
     * the shipped table has no fire above three quarters health, so a building
     * that has taken a scratch shows nothing.
     */
    void catchFire(Unit target) {
        if (target.type() == null || !target.type().building() || target.isBurning()
                || target.isDying() || !target.isOnMap()) {
            return;
        }
        var frames = burningBuildings();
        int maxHitPoints = target.type().hitPoints();
        if (maxHitPoints <= 0) {
            return;
        }
        MissileType fire = frames.missileAt(100 * target.hitPoints() / maxHitPoints);
        if (fire == null) {
            return;
        }
        missiles.add(Missile.burning(fire, target, frames));
        target.setBurning(true);
    }

    /**
     * What being hit makes a unit do.
     *
     * <p>The tail of {@code HitUnit}. The implementation subtracted hit points and
     * stopped, so a ballista at range eight shelling a footman with a reaction
     * range of four was never answered: the footman stood and died. Sniping
     * from outside reaction range was a free win, and siege balance followed
     * from that one omission.
     *
     * <p>Three outcomes, in upstream's order. A unit that cannot fight the
     * thing hitting it runs. A unit already fixed on its attacker has that
     * grip renewed. Anything else turns and answers, whatever the range.
     */
    private void reactToHit(Unit attacker, Unit target) {
        if (attacker == null || attacker == target || !attacker.isAlive()
                || target.isDying() || !target.isOnMap()) {
            return;
        }
        // An AI attack order gets first refusal on the blow. Its
        // COrder_Attack::OnAiHitUnit consumes not only a hit from the current
        // goal, but any hit while that goal is itself attacking this unit:
        // "we already fight with one of attackers" (:
        // 313-334). The generic HitUnit_AttackBack below must not set
        // UnderAttack or offer the second attacker in that case. On
        // human-exp/levelx12h the grunt's footman goal is fighting it when
        // the adjacent second footman lands cycle 197; upstream keeps
        // UnderAttack at zero and restores the saved post at 218.
        if (ais.containsKey(target.player())
                && (target.order() == Unit.Order.ATTACK
                        || target.order() == Unit.Order.ATTACK_MOVE)) {
            Unit goal = target.target();
            if (goal != null && !goal.isAlive()) {
                target.setTarget(null);
                // COrder_Attack::OnAiHitUnit writes the dead goal's final
                // tile to this order's goalPos before clearing its weak
                // pointer. orderTarget is a
                // different, generic command field. On level13h the goal
                // was acquired on the march destination and died three
                // squares away; retaining the acquisition tile made
                // EndActionAttack falsely finish the order for cycle 99.
                target.setAttackGoal(goal.tileX(), goal.tileY());
            } else if (goal == attacker
                    || (goal != null
                        && (goal.currentAction() == Unit.Order.ATTACK
                                || goal.currentAction() == Unit.Order.ATTACK_MOVE)
                        && goal.target() == target)) {
                return;
            }
        }
        // Can't fight back: run. This is the coward flag earning its keep --
        // a peasant flees where a footman turns round, and both are armed.
        // CurrentAction, not the order. Upstream asks
        // `target.CurrentAction() == UnitAction::Still`, and the flee it
        // issues is CommandMove(target, pos, EFlushMode::Off) -- appended
        // rather than flushed, so the running order stays at Orders[0] and
        // CurrentAction goes on answering Still for the rest of the cycle. A
        // peasant struck twice in one cycle therefore runs twice, and each run
        // spends three numbers on GetRndPosInDirection.
        //
        // On maps/demo/demo03 that is cycle 39 exactly: two grunts reach the
        // peasant at 9,2 together, upstream draws fifteen numbers and this
        // port drew twelve.
        if (target.canMove() && target.currentAction() == Unit.Order.STILL
                && (!targets.canTarget(target, attacker) || !target.isAggressive()
                        || (attacker.type().permanentCloak()
                                && !isVisibleTo(target.player(), attacker)))) {
            runAway(target, attacker);
            return;
        }
        // Already fighting the thing that hit it: renew the grip rather than
        // re-deciding. Without this a unit trading blows re-evaluates on every
        // single hit and can be pulled off its aggressor by a passing peasant.
        if (simplifiedAutoTargeting) {
            // HitUnit's literal split: simplified targeting throws the
            // threshold away before considering AttackBack. The thirty-cycle
            // grip exists only in the classic targeting mode

            target.setThreshold(0);
        } else if (target.threshold() > 0 && target.target() == attacker) {
            target.setThreshold(TARGET_THRESHOLD);
            return;
        }
        if (target.threshold() == 0 && target.isAggressive() && target.canMove()) {
            combat.attackBack(attacker, target);
        }
    }

    /**
     * Sends a unit that cannot fight its attacker somewhere else.
     *
     * <p>{@code HitUnit_RunAway}: five tiles or so directly away, with a
     * little scatter, so a peasant under fire does not run in a dead straight
     * line and neither does a crowd of them run to the same square. An
     * armed-but-outmatched unit gets an attack-move rather than a plain one
     * -- {@code CommandAttack(target, pos, nullptr, EFlushMode::Off)} for
     * {@code IsAggressive()} -- so it fights
     * whatever it meets on the way out. This implementation walked everyone: on
     * campaigns/human-exp/levelx07h p6's destroyer, shelled by a submarine
     * it cannot see, reads move from cycle 47 where upstream's reads attack
     * for the rest of the run.
     */
    private void runAway(Unit target, Unit attacker) {
        int[] pos = rndPosInDirection(target.tileX(), target.tileY(),
                attacker.tileX() + Math.max(1, attacker.type().tileWidth()) / 2,
                attacker.tileY() + Math.max(1, attacker.type().tileHeight()) / 2,
                true, FLEE_DISTANCE, 3);
        // Appended, not flushed. HitUnit_RunAway commands with
        // EFlushMode::Off, and that is not only a cycle of latency: without
        // the flush ReleaseOrders never runs, so the new order goes on the
        // *end* of the queue. A unit struck twice in one cycle is left with
        // two runs behind whatever it was doing and takes the first of them,
        // where replacing the order outright would take the second.
        //
        // On maps/demo/demo03 those are different squares: upstream's peasant
        // at 9,2 is hit by two grunts on cycle 39 and goes to 8,3, and this
        // port's went to 8,1 -- the second draw's answer rather than the
        // first's.
        target.enqueueOrder(new Unit.QueuedOrder(
                target.isAggressive() ? Unit.QueuedOrderKind.ATTACK_MOVE
                        : Unit.QueuedOrderKind.MOVE,
                pos[0], pos[1], null, null, null));
    }

    /** How far a unit that cannot fight runs, in tiles. */
    private static final int FLEE_DISTANCE = 5;

    /** How long a unit refuses to re-aim after choosing a target. */
    static final int TARGET_THRESHOLD = 30;

    /** How long a unit stays fixed on whoever hit it. */
    static final int UNDER_ATTACK_CYCLES = 128;

    /** Whether a slot is played by a person rather than the computer. */
    boolean isPerson(int slot) {
        Player owner = player(slot);
        return owner != null && owner.type() == net.chonkbase.chonkcraft.data.map.PudMap
                .PlayerType.PERSON;
    }

    /** Starts a unit's death: it stops occupying ground and plays out its end. */
    /**
     * Takes a unit off the map at once, with no death animation.
     *
     * <p>Not the same as killing it. This is for a saved game, which is about
     * to say where everything really stands: the units the map placed are not
     * dying, they were never there in this game.
     */
    public void remove(Unit unit) {
        markOccupancy(unit, false);
        markSight(unit, false);
        releaseUnitFromActionTable(unit);
        snapshot = List.copyOf(units);
    }

    /**
     * The bang a sapper leaves behind.
     *
     * <p>{@code LetUnitDie}: a type carrying
     * {@code ExplodeWhenKilled} makes its named missile where it stood.
     *
     * <p>It does nothing but look like an explosion, and the code is careful
     * about it in a way worth copying exactly. {@code MakeMissile} is called
     * without a source unit and none is set afterwards, which is not an
     * oversight -- {@code Missile::MissileHit} returns before its splash loop
     * when there is no source, above the comment "no owner - green-cross ...".
     * The explosion the data names declares no damage of its own either. So a
     * demolition squad kills with the attack it makes and not with the crater,
     * and this adds a picture and nothing else. Passing no source here is what
     * keeps it that way: {@code resolve} has the same guard.
     */
    private void explode(Unit unit) {
        if (missileTypes == null || unit.type() == null || !unit.type().explodeWhenKilled()) {
            return;
        }
        MissileType type = missileTypes.get(unit.type().explosion());
        if (type == null || type.isNone()) {
            return;
        }
        // GetMapPixelPosCenter: the middle of the whole
        // footprint, not the middle of the top-left square. A keep is four
        // tiles across, so the old figure put its death explosion forty-eight
        // pixels up and to the left of the building -- on the corner of it
        // rather than in it.
        int x = unit.tileX() * TILE_SIZE + centreOffset(unit.type(), true);
        int y = unit.tileY() * TILE_SIZE + centreOffset(unit.type(), false);
        spawn(new Missile(type, null, null, x, y, x, y));
        // A death can be dealt outside the missile step -- a mine running out,
        // a script killing something -- so the renderer's view is republished
        // here rather than waiting for a cycle that may not come.
        missileSnapshot = List.copyOf(missiles);
    }

    public void kill(Unit unit) {
        kill(unit, null);
    }

    /**
     * Kills a unit and credits whoever did it.
     *
     * @param killer the unit that struck the last blow, or {@code null} when
     *               nobody did: a cancelled building, a summoned creature
     *               running out of time, a script clearing the board. Upstream
     *               has the same null path -- {@code HitUnit} only scores
     *               {@code if (attacker)} -- and it must credit nobody rather
     *               than guessing
     */
    public void kill(Unit unit, Unit killer) {
        if (unit.order() == Unit.Order.DYING) {
            return;
        }
        creditKill(unit, killer);
        // The AiUnitKilled hooks: a frame killed mid-build hands its job
        // back whole;
        // a builder killed on its way does too, but only while no frame
        // stands.
        net.chonkbase.chonkcraft.engine.ai.AiPlayer ownerAi = ais().get(unit.player());
        if (ownerAi != null) {
            if (unit.order() == Unit.Order.UNDER_CONSTRUCTION) {
                ownerAi.reduceMade(this, unit.type());
            } else if (unit.pendingBuild() != null && unit.worksite() == null
                    && !unit.orderFinished()) {
                ownerAi.reduceMade(this, unit.pendingBuild());
            }
        }
        announce(unit, "dead");
        // LetUnitDie destroys the attack order. Any unconstructed
        // presentation-ahead missile or melee handoff is owned by that order
        // and must not outlive it.
        projectiles.interruptPendingAttack(unit);
        battleNetPendingMeleeHits.remove(unit);
        battleNetAttackMarkers.remove(unit);
        battleNetInlineAttackMarkers.remove(unit);
        explode(unit);
        releaseOccupants(unit);
        // Everything aboard goes down with the ship. This is the whole risk of
        // moving an army by sea.
        for (Unit passenger : new ArrayList<>(unit.cargo())) {
            unregisterPlayerUnit(passenger);
            passenger.setCarrier(null);
            passenger.setHitPoints(0);
            passenger.setOrder(Unit.Order.DYING);
            passenger.setDeathTimer(0);
        }
        unit.cargo().clear();

        if (unit.isAboard()) {
            unit.carrier().cargo().remove(unit);
            unit.setCarrier(null);
        }
        unregisterPlayerUnit(unit);
        unmarkOccupancyForDeath(unit);
        markSight(unit, false);
        // After the occupancy comes off and before the corpse goes down, which
        // is where UnitLost sits: LetUnitDie calls Remove and then UnitLost
        // The patch is put back on ground that is clear
        // again by then.
        replaceOnDie(unit);
        unit.clearPath();
        unit.setPendingHarvest(-1, -1);
        unit.clearQueuedOrders();
        // LetUnitDie clears every order immediately. In particular it does
        // not leave CurrentAction reporting the finished head of a command
        // queue: levelx08o's destroyer is killed after an attack-move was
        // queued behind that label, and upstream reports Die on the killing
        // cycle while Java reported Attack until the next turn popped it.
        unit.setActionBeforeQueued(null);
        if (unit.order() == Unit.Order.RETURN_GOODS
                && unit.type().gathering().containsKey(UnitType.Resource.OIL)) {
            unit.setBattleNetOilAction(Unit.BattleNetOilAction.TO_DEPOT);
            unit.setBattleNetOilActionTicks(0);
        }
        unit.setSavedOrder(null);
        unit.setTarget(null);
        // offeredTarget is owned by COrder_Attack, not by CUnit.  LetUnitDie
        // destroys every order, and that order's CUnitPtr releases its offer
        // in the same operation.  Leaving the projection on the Java unit
        // let one expired corpse retain another: on levelx04h the latter then
        // occupied action-table slot 78 instead of being swap-released at
        // cycle 1692.
        unit.setOfferedTarget(null);
        unit.setAutoTargeting(false);
        unit.setSelected(false);
        unit.setOrder(Unit.Order.DYING);
        unit.setFrame(0);
        // LetUnitDie clears Unbreakable. A unit killed mid-swing must not keep
        // the flag, or the death animation never gets to run.
        unit.animation().clearUnbreakable();
        // As long as the death animation actually runs for, and no longer. A
        // footman's takes over a hundred cycles, so a flat second cut it off a
        // third of the way through and the unit blinked out mid-fall.
        //
        // The floor of one second that used to sit here was worse at the other
        // end. Buildings have no death animation at all -- anim.legacy-declaration's
        // animations-building declares Still, Research, Train and Upgrade and
        // nothing else -- so AnimateActionDie returns false and
        // COrder_Die::Execute swaps the building for its rubble on the very
        // cycle it dies. The floor held a destroyed keep standing there
        // undamaged for a full second after its own explosion had gone off.
        unit.setDeathTimer(deathCycles(unit));
    }

    /**
     * Credits a kill to the side that made it.
     *
     * <p>{@code HitUnit_IncreaseScoreForKill}, called
     * from {@code HitUnit} under {@code if (target.IsEnemy(*attacker))}. The
     * port had no notion of who struck the last blow, so the score was banked
     * by every player who counted the dead unit as an enemy. Two sides cannot
     * tell the two rules apart, and all fifty-two campaign missions are two
     * sided, which is how it survived; in a three-sided game it hands a
     * bystander points for a fight it took no part in.
     *
     * <p>The scorer, kill/razing tally, and last-blow owner are all committed
     * in this simulation event. A renderer is never part of game state.
     */
    private void creditKill(Unit victim, Unit killer) {
        int killerPlayer = killer == null ? -1 : killer.player();
        victim.setKilledBy(killerPlayer);
        if (killerPlayer < 0 || !isEnemyPlayer(killerPlayer, victim.player())) {
            return;
        }
        Player scorer = player(killerPlayer);
        if (scorer == null || !scorer.isActive()) {
            return;
        }
        // HitUnit_IncreaseScoreForKill is part of the lethal hit, before the
        // victim enters Die.  Deferring this to the desktop render loop made
        // score and kill state absent from headless and multiplayer worlds,
        // and one cycle late even when a screen happened to be running.
        // XHuman 10 is the authenticated witness: footman 1492 enters Die at
        // fixture 42 and retail's unit and kill counters change in that same
        // snapshot.  kill() is already guarded against a second transition,
        // so this is naturally paid once without a corpse-scanning sidecar.
        int points = victim.type() == null ? 0 : victim.type().points();
        if (points > 0) {
            scorer.addScore(points);
        }
        scorer.addKill(victim.type() != null && victim.type().building());
    }

    /**
     * Puts back on the map anything that was inside a unit that has just died.
     *
     * <p>Two holes, one shape. A peasant building a farm is removed from the
     * map and parked in the site ({@code worker.setRemoved(true)},
     * {@code worker.setWorksite(site)}), and it was only ever brought out again
     * by the site being finished or cancelled. Destroy the half-built farm and
     * the peasant was neither alive nor dead: still in the world list, still
     * holding its thirty hit points, permanently off the map, its food never
     * released, no body, no dying cry. A harvester inside a town hall or a
     * refinery when that building came down was lost the same way.
     *
     * <p>Upstream loses neither. {@code COrder_Built::~COrder_Built} runs
     * {@code CancelBuilt}, which does {@code DropOutOnSide(*worker, LookingW,
     * unit)}, and the order is destroyed by {@code UnitClearOrders} inside
     * {@code LetUnitDie} -- so a construction site that dies always puts its
     * builder down beside the ruins. Anything genuinely contained goes through
     * {@code DropOutAll} or {@code DestroyAllInside}.
     *
     * <p>If there is nowhere at all to stand -- a hall walled in on every side
     * -- the occupant dies with its container, which is the only other honest
     * answer and what {@code DestroyAllInside} would have done.
     */
    private void releaseOccupants(Unit container) {
        // Only a construction site points back at the thing inside it, and that
        // is exactly the one whose occupant is left with nothing to do: its
        // work has just been destroyed. A harvester keeps its order and its
        // load and walks on, which is what LoseResource does when a mine is
        // worked out from under it.
        Unit builder = container.worksite();
        // Both ends of the link are cleared; a leftover reference on either
        // side would keep the pair half-joined.
        container.setWorksite(null);
        for (Unit occupant : List.copyOf(units)) {
            if (occupant == container || occupant.worksite() != container) {
                continue;
            }
            occupant.setWorksite(null);
            if (!occupant.removed()) {
                continue;
            }
            int[] spot = dropOutOnSide(occupant.type(), LOOKING_WEST, container,
                    occupant.tileX(), occupant.tileY());
            if (spot == null) {
                kill(occupant, null);
                continue;
            }
            occupant.setTile(spot[0], spot[1]);
            occupant.setRemoved(false);
            markOccupancy(occupant, true);
            unitCountSeen(occupant);
            markSight(occupant, true);
            occupant.clearPath();
            if (occupant == builder) {
                occupant.setOrder(Unit.Order.STILL);
            } else {
                // A destroyed resource/depot cancels the contained stay. The
                // released worker must reconsider the dead container on its
                // next action, not sleep out the vanished building's timer.
                occupant.setWaitCycles(0);
            }
        }
    }

    /**
     * A random number below {@code bound}, from the simulation's own
     * generator.
     *
     * <p>Named for {@code SyncRand}, whose point is that every machine in a
     * networked game draws the same sequence. A bound of zero or less yields
     * zero rather than throwing, which the damage formula can produce.
     */
    public int syncRand(int bound) {
        return bound <= 0 ? 0 : Math.floorMod(syncRand(), bound);
    }

    /**
     * The next number in the sequence.
     *
     * <p>The value comes from the seed as it stands and the seed advances
     * after, which is upstream's order. Swapping the two is a different
     * sequence, and one that would agree with nothing.
     */
    public int syncRand() {
        int seedBefore = randomSeed;
        int value = randomSeed >>> 16;
        // LegacyEngine uses mult 0x48d159e1 (= 0x12345678*4+1). BNE 2.02b's
        // synchronized stream at 0x004a48dc is the classic MSVC LCG
        // (state * 0x41c64e6d + 0x3039). Fixture seeds (XOrc 12 cycle 5:
        // 1 → 0x41c67ea6 in one step) only match under the retail formula.
        randomSeed = randomSeed * 0x41c64e6d + 0x3039;
        randomDraws++;
        if (causalTrace.enabled()) {
            CausalCallsite site = CausalCallsite.resolve();
            causalTrace.event(cycle, "rng.sync.draw", null,
                    "before", Integer.toUnsignedLong(seedBefore),
                    "after", Integer.toUnsignedLong(randomSeed),
                    "result", value, "draw", randomDraws,
                    "caller", site.caller(), "caller_chain", site.chain(),
                    "caller_line", site.line(),
                    "context", randContext);
        }
        if (RAND_TRACE_PATH != null) {
            traceDraw(value);
        }
        return value;
    }

    /** The next value from BNE's asynchronous LCG (FUN_00479820). */
    /**
     * Asynchronous stream draw for AI selectors that port native
     * {@code FUN_00479820} (barracks top-up branches 4 and 5).
     */
    public int battleNetRandomForAi() {
        return battleNetRand();
    }

    int battleNetRand() {
        int seedBefore = battleNetRandomSeed;
        battleNetRandomSeed = battleNetRandomSeed * 0x015a4e35 + 1;
        int value = (battleNetRandomSeed >>> 16) & 0x7fff;
        battleNetRandomDraws++;
        if (causalTrace.enabled()) {
            CausalCallsite site = CausalCallsite.resolve();
            causalTrace.event(cycle, "rng.async.draw", null,
                    "before", Integer.toUnsignedLong(seedBefore),
                    "after", Integer.toUnsignedLong(battleNetRandomSeed),
                    "result", value, "draw", battleNetRandomDraws,
                    "caller", site.caller(), "caller_chain", site.chain(),
                    "caller_line", site.line(),
                    "context", randContext);
        }
        if (RAND_TRACE_PATH != null) {
            traceDraw(value);
        }
        return value;
    }

    /** Exposes the independent stream for deterministic BNE harness tests. */
    public int battleNetRandomSeed() {
        return battleNetRandomSeed;
    }

    /** How many asynchronous numbers have been drawn, for save diagnostics. */
    public long battleNetRandomDraws() {
        return battleNetRandomDraws;
    }

    /** Puts BNE's asynchronous generator back where the save left it. */
    public void restoreBattleNetRandom(int seed, long draws) {
        battleNetRandomSeed = seed;
        battleNetRandomDraws = draws;
    }

    /**
     * The implementation-side twin of the {@code LEGACY_ENGINE_TRACE_RAND} hook in
     * {@code tools/legacyEngine-trace.patch}: set {@code CHONKCRAFT_TRACE_RAND} to
     * a path and every draw logs its cycle, value and caller, one line
     * each, for finding the first divergent draw inside a cycle whose seed
     * fingerprints disagree. Off in every ordinary run.
     */
    private static final String RAND_TRACE_PATH = System.getenv("CHONKCRAFT_TRACE_RAND");

    /** The unit whose per-cycle animation state JATTACKANIM prints, or -1. */
    private static final int ATTACKANIM_TRACE_ID =
            System.getenv("CHONKCRAFT_TRACE_ATTACKANIM") == null ? -1
                    : Integer.parseInt(System.getenv("CHONKCRAFT_TRACE_ATTACKANIM"));

    private java.io.PrintWriter randTrace;

    /** Who is asking, for the ledger's damage lines; null otherwise. */
    private String randContext;

    /**
     * Java-side counterpart to the native return address in causal events.
     *
     * <p>{@code Class.method}, not {@code method:line}: the ledger that reads
     * these compares one run's callers with another's, and a line number makes
     * every draw in a file look like it moved when one comment above it did.
     */
    String causalCaller() {
        return CausalCallsite.resolve().caller();
    }

    private void traceDraw(int value) {
        if (randTrace == null) {
            try {
                randTrace = new java.io.PrintWriter(
                        new java.io.FileWriter(RAND_TRACE_PATH), false);
            } catch (java.io.IOException e) {
                throw new IllegalStateException("cannot open " + RAND_TRACE_PATH, e);
            }
        }
        String caller = StackWalker.getInstance().walk(frames -> frames
                .skip(2)
                .filter(f -> !f.getMethodName().equals("syncRand"))
                .findFirst()
                .map(f -> f.getMethodName() + ":" + f.getLineNumber())
                .orElse("?"));
        randTrace.printf("%d %d %s%s%n", cycle, value, caller,
                randContext == null ? "" : " " + randContext);
        randTrace.flush();
    }

    /** The generator's state, which is all a save needs to restore it. */
    public int randomSeed() {
        return randomSeed;
    }

    /** How many numbers have been drawn, for reporting rather than restoring. */
    public long randomDraws() {
        return randomDraws;
    }

    /** Puts the generator back exactly where a save left it. */
    public void restoreRandom(int seed, long draws) {
        randomSeed = seed;
        randomDraws = draws;
    }

    /**
     * Looks for a fight, for a unit that has nothing else to do.
     *
     * <p>Implements {@code AutoAttack}.
     * This is what makes a battle a battle rather than a sequence of orders:
     * without it two armies walk past each other, and a tower watches an enemy
     * stroll by.
     *
     * <p>The range is the type's own reaction range, and there are two of them
     * because Warcraft II gives the computer a longer one. A footman owned by
     * a person notices at four tiles; the same footman owned by the computer
     * notices at six.
     *
     * @return whether a target was found and an attack begun
     */
    /**
     * Casts a unit's standing spell at something in reach.
     *
     * <p>Tried before auto-attack, because a caster that walks into melee to
     * swing when it could have cast is a caster being wasted. Only offensive
     * spells are cast this way: healing an ally automatically would empty the
     * pool on scratches.
     */
    private boolean autoCast(Unit unit) {
        String ident = unit.autoCast();
        if (ident == null || !unit.isCaster()) {
            return false;
        }
        Spell spell = spellSet.get(ident);
        if (spell == null || unit.mana() < spell.manaCost()) {
            return false;
        }
        if (spell.target() == Spell.Target.SELF) {
            return castSpell(unit, ident, unit);
        }
        Unit victim = targets.findHostile(unit, 0, Math.max(1, spell.range()));
        return victim != null && castSpell(unit, ident, victim);
    }

    /**
     * Starts the attack a unit queued for itself last cycle.
     *
     * <p>{@code HandleUnitAction}'s pop, which is where the cycle of latency
     * comes from and where it ends.
     */
    private void beginPendingAttack(Unit unit) {
        // Residual-settled naval patrol acquisition keeps action 5 with a
        // queued next_order Attack and animation timer 15 through fixtures
        // 40..54 (XORc 11 destroyer 1542) before promoting order 12 at 55.
        // stepPatrol arms battleNetOrderDelay on that settle visit; hold the
        // pending pop until the delay has drained so the sealed order field
        // stays Patrol through the hold.
        if (unit.order() == Unit.Order.PATROL
                && unit.battleNetDoubleStep()
                && unit.type() != null && unit.type().seaUnit()
                && unit.battleNetOrderDelay() > 0) {
            return;
        }
        Unit target = unit.pendingAttack();
        Unit.Order queuedUnder = unit.pendingAttackFrom();
        int targetX = unit.pendingAttackX();
        int targetY = unit.pendingAttackY();
        unit.setPendingAttack(null, null, -1, -1);
        if (target == null || !map.contains(targetX, targetY)) {
            return;
        }
        // And thrown away if anything has been asked of this unit in the
        // meantime. Every command upstream issues with {@code EFlushMode::On}
        // runs {@code ReleaseOrders} first, which empties the queue
        // so an order given between the
        // scan and the switch takes the queued attack with it. The unit was
        // still doing what it was doing when it noticed the enemy; if it is
        // not, somebody else has spoken.
        if (unit.order() != queuedUnder) {
            return;
        }
        Unit.Order interrupted = unit.order();
        // At the square, not at the unit. AutoAttack commands
        // {@code CommandAttack(unit, goal->tilePos, nullptr, On)}
        // The game "Weak goal, can choose other unit"
        // -- so what the scan bought is an attack-move to where the enemy
        // was standing, and the order picks its own target when it first
        // runs, a cycle later, against a battlefield the intervening pops
        // have changed. On campaigns/human/level13h that cycle is the
        // difference between the ogre the wise-man's still-scan saw and
        // the axethrower whose freshly popped order visibly hunts him; and
        // a target the scanner saw that the order cannot see is not a
        // failure -- the march walks at the empty square and looks again
        // along the way. Landing this took the march learning the chase's
        // whole cadence first: the walk owning the cycle, the spent
        // route's ten-cycle wait, the mid-walk re-aim, and the turn on the
        // arrival cycle itself. demo03 measured every missing piece -- 12,
        // 23, 38, 42, a destroyer's broadside one cycle late -- and then
        // agreed for the whole window.
        if (!combat.orderAttackMove(unit, targetX, targetY)) {
            return;
        }
        // CommandAttack(..., EFlushMode::On) calls ClearSavedAction before
        // AutoAttack installs the order it cloned above
        // A stale save left
        // by AiMoveUnitInTheWay must therefore be replaced, not protected.
        // levelx12h's grunt carries a saved Move out of a shove at cycle 467;
        // its fresh still-scan at 470 replaces that with the attack-move back
        // to its post upstream. Keeping Move made the next two target drops
        // look like separate restores and reset the scan counter twice.
        rememberInterruptedOrder(unit, interrupted);
    }

    /** Keeps only the autonomous orders upstream restores after combat. */
    private void rememberInterruptedOrder(Unit unit, Unit.Order interrupted) {
        if (unit.savedOrder() != null) {
            return;
        }
        if (interrupted == Unit.Order.STILL) {
            // What a standing unit comes back to is not standing still.
            // {@code AutoAttack} saves {@code COrder::NewActionAttack(unit,
            // unit.tilePos)} before it commands the attack -- the comment on
            // the line is "Weak goal, can choose other unit, come back after
            // attack" -- and the position
            // form of that constructor sets {@code attackMovePos} and
            // {@code State = AUTO_TARGETING}
            // So it walks back to the
            // square it was standing on and goes on looking for something to
            // shoot: upstream's juggernaught on demo02 is still under an
            // attack order at cycle 28, back at 4,18 where it began, where
            // this implementation's had gone quiet at 14.
            unit.setSavedOrder(Unit.Order.ATTACK_MOVE);
            unit.setSavedAttackMove(unit.tileX(), unit.tileY());
            if (System.getenv("CHONKCRAFT_TRACE_SAVEORDER") != null) {
                System.err.printf("JSAVE cycle=%d unit=%d source=still saved=%d,%d%n",
                        cycle, unit.id(), unit.savedAttackMoveX(),
                        unit.savedAttackMoveY());
            }
            return;
        }
        if (interrupted == Unit.Order.PATROL || interrupted == Unit.Order.EXPLORE) {
            unit.setSavedOrder(interrupted);
        }
    }

    /**
     * How many blocked attempts a unit tolerates before asking for a new
     * route: {@code PathFinderOutput::MAX_FAST} (src/include/pathfinder.h:108).
     */
    static final int MAX_PATH_WAIT = 10;

    /** Cycles between scans while idle: {@code CYCLES_PER_SECOND / 2}. */
    static final int IDLE_SCAN_INTERVAL = CYCLES_PER_SECOND / 2;

    /** Cycles between scans while fighting: {@code CYCLES_PER_SECOND / 5}. */
    static final int ATTACK_SCAN_INTERVAL = CYCLES_PER_SECOND / 5;

    /** The bit weights of {@code TargetPriorityCalculate} ({@code unit.h:101-107}). */
    static final int AT_ATTACKED_BY_FACTOR = 0x40000000;

    static final int AT_THREAT_FACTOR = 0x20000000;

    static final int AT_PRIORITY_OFFSET = 15;

    static final int AT_DISTANCE_OFFSET = 7;

    static final int AT_FARAWAY_REDUCE_OFFSET = 14;

    /**
     * Whether target selection uses the simplified algorithm.
     *
     * <p>{@code GameSettings.SimplifiedAutoTargeting}, and ChonkCraft ships it on
     * -- {@code SimplifiedAutoTargeting = true} in
     * {@code scripts/legacyEngine.legacy-declaration:441} -- so every shipped game selects with
     * {@code TargetPriorityCalculate} and the {@code ComputeCost} walk below
     * is the branch the data never runs. It stays because the setting exists
     * and because it is this implementation's older transcription, verified in its day.
     */
    boolean simplifiedAutoTargeting = true;

    /**
     * {@code GameSettings.SimplifiedAutoTargeting}, for a fixture arranging
     * the branch the shipped data never runs. The data sets it true and
     * leaves it; nothing in a shipped game calls this.
     */
    public void setSimplifiedAutoTargeting(boolean simplified) {
        simplifiedAutoTargeting = simplified;
    }

    /** The tech tree, as the mission's scripts declared it. */
    private net.chonkbase.chonkcraft.engine.upgrade.DependencyRules dependencies;

    public void setDependencies(net.chonkbase.chonkcraft.engine.upgrade.DependencyRules rules) {
        this.dependencies = rules;
    }

    /**
     * Whether a player's tech tree allows a thing yet.
     *
     * <p>{@code CheckDependByIdent}: each
     * requirement is a researched upgrade or a completed unit of the named
     * type, and the mission's {@code DefineDependency} lines say which
     * combinations satisfy. The AI's own maker walks it unconditionally --
     * {@code AiFindAvailableUnitTypeEquiv} erases every type the tree
     * refuses -- which is what keeps a
     * first-thought AI from raising an ogre mound before its stronghold: on
     * campaigns/human-exp/levelx12h upstream's p0 bills the mound and the
     * alchemist at costerr=0 and still starts neither, while this implementation,
     * asking nothing of the tree, founded both plus the altar their absence
     * should have gated.
     *
     * <p>A world with no rules set -- every hand-built fixture -- refuses
     * nothing, exactly as an empty {@code DefineDependency} table would.
     */
    public boolean dependenciesSatisfied(int player, String ident) {
        if (dependencies == null) {
            return true;
        }
        return dependencies.isSatisfied(ident, requirement ->
                requirement.startsWith("upgrade-")
                        ? upgrades(player) != null && upgrades(player).has(requirement)
                        : unitTypesCount(player, requirement) > 0);
    }

    /**
     * Which unit's target bills the parity harness prints, or {@code -1}.
     *
     * <p>The implementation-side twin of the {@code LEGACY_ENGINE_TRACE_COST} hook the
     * instrumented upstream carries in {@code tools/legacyEngine-trace.patch}:
     * set {@code CHONKCRAFT_TRACE_COST} to a unit id and every
     * {@code targetPriority} bill that unit runs is printed to stderr in the
     * same shape, so the two engines' choices can be diffed line against
     * line. Off in every ordinary run, and behaviour-neutral when on --
     * the print draws nothing and decides nothing.
     */
    static final int TRACE_COST_UNIT;

    static {
        String traced = System.getenv("CHONKCRAFT_TRACE_COST");
        int unit = -1;
        if (traced != null) {
            try {
                unit = Integer.parseInt(traced.trim());
            } catch (NumberFormatException ignored) {
                unit = -1;
            }
        }
        TRACE_COST_UNIT = unit;
    }

    /**
     * How many steps the route to a unit takes, standing enemies as walls.
     *
     * <p>{@code CalcPathLengthToUnit}:
     * the one search in the game that runs with
     * {@code AStarFixedEnemyUnitsUnpassable} raised, so the crossable-enemy
     * price the planner normally pays is a wall here -- a candidate is only
     * as close as the route that goes round the bodies. Nought means already
     * inside the range band; minus one means no route at all.
     */
    int calcPathLengthToUnit(Unit src, Unit dst, int minRange, int range) {
        PathFinder.Occupancy base = occupancyFor(src);
        PathFinder.Occupancy walled = (x, y) -> {
            int at = base.at(x, y);
            return at == PathFinder.Occupancy.STATIONARY_ENEMY
                    ? PathFinder.Occupancy.STATIONARY : at;
        };
        PathFinder.Path path = pathFinder.find(src.tileX(), src.tileY(),
                new PathFinder.Goal(dst.tileX(), dst.tileY(),
                        Math.max(1, dst.type().tileWidth()),
                        Math.max(1, dst.type().tileHeight()),
                        minRange, range),
                new PathFinder.Mover(src.movementMask(), src.blockingFlags(),
                        src.type().tileWidth(), src.type().tileHeight(), walled));
        return switch (path.result()) {
            case REACHED -> 0;
            case FOUND -> path.length();
            default -> -1;
        };
    }

    /**
     * Whether a unit could get to something it cannot yet reach.
     *
     * <p>{@code UnitReachable}. Answered by the route planner, which is asked
     * once per pair per cycle and no more: the same question comes up several
     * times a cycle and a search is not cheap.
     */
    boolean isReachable(Unit unit, Unit target) {
        if (!unit.canMove()) {
            return unit.distanceTo(target) <= Math.max(1, unit.type().maxAttackRange());
        }
        long key = ((long) unit.id() << 32) | (target.id() & 0xffff_ffffL);
        Boolean cached = reachable.get(key);
        if (cached != null) {
            return cached;
        }
        // The same question the chase itself asks: can this unit get anywhere
        // it could strike from. Upstream's UnitReachable passes the target's
        // footprint and the attacker's range to PlaceReachable; this asked for
        // the target's top-left tile at range zero, which is a harder question
        // and sometimes a different one.
        //
        // It was also the most expensive thing in the game. A 2x2 Guard Tower
        // walled into rock -- deliberate map design on levelx12h, towers you
        // shoot and never storm -- has no open approach at all, so every grunt
        // beside it ran the search to its node cap every cycle. Budget
        // exhaustion returns the nearest square reached rather than nothing,
        // which reads as reachable, so they were told to walk at a tower they
        // could never touch and asked again next cycle. That single mission
        // spent twelve times the per-unit cost of any other.
        PathFinder.Goal goal = new PathFinder.Goal(
                target.tileX(), target.tileY(),
                Math.max(1, target.type().tileWidth()),
                Math.max(1, target.type().tileHeight()),
                0, Math.max(1, unit.type().maxAttackRange()));
        PathFinder.Path path = pathFinder.find(unit.tileX(), unit.tileY(), goal, moverFor(unit));
        boolean answer = path.result() != PathFinder.Result.UNREACHABLE;
        reachable.put(key, answer);
        return answer;
    }

    /**
     * Reachability answers, kept until fixed terrain or a building changes.
     *
     * <p>The old cache lived for one tick, so an idle soldier across an
     * impassable bank flooded the same map once per target scan even though
     * neither bank had changed. Units walking through a square are temporary
     * route costs; buildings, walls, trees, and rocks change whether the two
     * sides are connected and invalidate every answer.
     */
    final java.util.Map<Long, Boolean> reachable = new java.util.HashMap<>();

    /**
     * What a harmless aircraft is worth attacking: {@code INT_MAX / 2}, which
     * every genuine target beats and every genuine target is preferred to.
     */
    static final long PASSING_FLYER = Integer.MAX_VALUE / 2L;

    /** The weights from {@code unit.h}, unchanged. */
    static final long PRIORITY_FACTOR = 0x0008_0000L;

    static final long HEALTH_FACTOR = 0x0000_0001L;

    static final long DISTANCE_FACTOR = 0x0001_0000L;

    static final long INRANGE_FACTOR = 0x0000_8000L;

    static final long INRANGE_BONUS = 0x0100_0000L;

    static final long CANATTACK_BONUS = 0x0008_0000L;

    /**
     * Walks a beat between two points, fighting what it meets.
     *
     * <p>The beat is the pair of squares, and the unit turns round at each
     * end rather than stopping. Auto-attack runs here as it does when idle,
     * which is the point of patrolling rather than moving: a patrol notices
     * things.
     */
    private void stepPatrol(Unit unit) {
        boolean delayHold = unit.battleNetOrderDelay() > 0;
        if (delayHold) {
            unit.setBattleNetOrderDelay(unit.battleNetOrderDelay() - 1);
        }
        // Capital-ship Patrol keeps the Still/Move cursor and only scans on
        // opcode zero. The 15-cycle autoAttack used to fire once the first
        // leftover path existed, queue AttackMove, and arm delay 14, so
        // XOrc 11 battleship 1511 never left Patrol. Native 1511 stays
        // Patrol through the first-stride Move body and opens Attack 12
        // at the next OP0 (fixture 58) on 18,40. Tick even during the
        // constructor delay so timer 3 from promote expires on that first
        // free visit, not three visits later.
        boolean patrolOp0 = tickBattleNetPatrolSequence(unit);
        if (delayHold) {
            return;
        }
        if (patrolOp0 && battleNetPatrolAcquire(unit)) {
            return;
        }
        // Drain residual before acquisition and leftover free-consume. A
        // double-step sea patrol used to walk residual only inside walkTowards
        // at the bottom, so the settle visit's mayDecide free-consumed leftover
        // SE headings in the same breath -- XOrc 11 destroyer 1542 (Java 58)
        // stepped empty (12,26) at fixture 40 while native residual-settled
        // (px 320,768), queued next_order Attack, rewrote order_point toward
        // the hostile and held. Drain first and return while pixels remain
        // (action 5 + null target through fixtures 21..39). On settle, fall
        // through so autoAttack can replan before any leftover heading is
        // taken. Flyers keep the same pre-consult drain for self-patrol.
        boolean residualSettledThisVisit = false;
        if (unit.battleNetDoubleStep() && unit.isMoving()
                && (unit.type().seaUnit()
                || (unit.type().moveType() == UnitType.Movement.FLY
                        && unit.pathLength() == 0))) {
            // Fly pathn-0 residual drain was limited to the self-patrol
            // endpoint (tile==orderTarget). Mid-journey balloons that spent
            // a short pathfinder prefix (XORc 8 1452: E then SE onto 82,70
            // with pathn 0) never settled residual before replan, so native
            // double-stepped SE@42 while Java drained forever under isMoving.
            // Drain any fly pathn-0 residual the same way as sea double-step.
            movement.walkPixels(unit);
            if (unit.isMoving()) {
                // Offsets already zero but unbreakable Move keeps Moving:
                // clear so replan can run (same residual-zero settle as
                // self-patrol endpoint arm below).
                if (unit.offsetX() == 0 && unit.offsetY() == 0
                        && unit.residualX() == 0 && unit.residualY() == 0) {
                    unit.setWalkHolding(false);
                } else if (battleNetScoutOddDestEvenStop(unit)) {
                    // Fall through so the dest arm can count residual
                    // visits on the even stop (Human 12 1559).
                } else {
                    return;
                }
            }
            unit.setWalkHolding(false);
            if (unit.type().moveType() == UnitType.Movement.FLY) {
                unit.setRouteSpent(false);
                unit.setWaitCycles(0);
            }
            residualSettledThisVisit = true;
            if (movement.finishLeftoverReplacement(unit)) {
                return;
            }
        }
        // Large BNE ships on a fresh patrol must take their first even-grid
        // step under Patrol before acquisition may replace the order. XOrc 11
        // battleships used to convert to AttackMove on the first free visit
        // and step a cycle late (native still Patrol at 18,40 / 8,26 on
        // fixture cycle 5). Residual mid-slide never reaches here.
        boolean standingPatrol = battleNetStandingPatrolSequence(unit);
        if (standingPatrol && !patrolOp0) {
            // Move-body visits stay on Patrol until the next OP0. Destroyer
            // leftover-settle (1542) does not own this cursor and still
            // falls through to autoAttack below.
            return;
        }
        boolean awaitingFirstPatrolStep = unit.battleNetDoubleStep()
                && unit.pathLength() == 0
                && !unit.isMoving();
        if (!standingPatrol && !awaitingFirstPatrolStep && combat.autoAttack(unit)) {
            // Sea double-step with multi-step leftover (typically residual-
            // settled): native rewrites the route to the hostile and holds
            // under Patrol for fifteen animation ticks (1542: timer 15 at
            // fixture 40, order still 5 through 54). Drop the leftover free-
            // consume path and arm the order delay so beginPendingAttack
            // cannot promote until the hold ends. Standing path-empty patrol
            // acquisition keeps the ordinary one-cycle pending latency.
            if (unit.battleNetDoubleStep() && unit.type().seaUnit()
                    && unit.pathLength() > 0) {
                unit.clearPath();
                unit.setBattleNetOrderDelay(14);
            }
            // The attack order replaces the patrol; the beat is remembered on
            // the unit and picked up again when the fight is over.
            return;
        }
        if (unit.battleNetScoutPatrol() && !unit.type().canAttack()
                && unit.type().moveType() == UnitType.Movement.FLY
                && Math.max(Math.abs(unit.tileX() - unit.orderTargetX()),
                        Math.abs(unit.tileY() - unit.orderTargetY())) <= 1) {
            // Unarmed scout dests are one-shot. Human 12 zeppelin 1570
            // residual-settles on 50,4 at fixture 63 and goes Still; swapping
            // back toward 46,10 used to put it on Patrol at the survey's
            // first disagreement. 1559's dest 83,10 is off the even flight
            // lattice, so the hull stops on 84,10. A leftover west heading
            // used to walk it on to 82,10. Residual settles at 63; native
            // stays Patrol through 81 and is Still at 82. Immediate Still
            // at settle is nineteen cycles early and the 1800-cycle
            // survey disagrees there. Exact even dests (1570 on 50,4)
            // still stand down on residual settle.
            unit.clearPath();
            if (unit.isMoving() && !battleNetScoutOddDestEvenStop(unit)) {
                return;
            }
            if (battleNetScoutOddDestEvenStop(unit)) {
                int holds = unit.battleNetSelfPatrolHolds() + 1;
                unit.setBattleNetSelfPatrolHolds(holds);
                // Residual settle 63, native Still 82: twenty dest-arm
                // visits. Counting from the landing visit Still'd at 81.
                if (holds < 20) {
                    return;
                }
            }
            // Dest-arm counts those twenty visits while the hull is still
            // two pixels off 84,10. Leaving that slide on the unit used to
            // spend the first free visit after the next scout dest draining
            // it -- Human 12 1559 stayed on 84,10 through fixture 102 and
            // only stepped east at 103. Even dests (1570 on 50,4) already
            // wait for residual settle, so they resume on time. Park the
            // hull the same way when the dest-arm stand-down fires.
            unit.setBattleNetSelfPatrolHolds(0);
            unit.setWalkHolding(false);
            movement.resetDisplacement(unit);
            unit.setBattleNetScoutPatrol(false);
            unit.setOrder(Unit.Order.STILL);
            unit.setActionBeforeQueued(null);
            if (battleNetSequence != null) {
                int stillStart = idle.battleNetStillSequenceStart(unit);
                if (stillStart >= 0) {
                    unit.setBattleNetSequenceOffset(stillStart);
                    unit.setBattleNetAnimationTimer(3);
                }
            }
            return;
        }
        if (unit.pathLength() == 0 && !unit.isMoving()
                && unit.tileX() == unit.orderTargetX() && unit.tileY() == unit.orderTargetY()) {
            // Reached this end: swap the two and walk back.
            int backX = unit.patrolX();
            int backY = unit.patrolY();
            boolean selfPatrol = backX == unit.orderTargetX()
                    && backY == unit.orderTargetY();
            // Self-patrol combat flyers never leave the start tile under a
            // plain endpoint swap. After eight free visits (XOrc 8: ready c5
            // → first tile c13) take one double-step in the current facing
            // and walk it immediately -- native 1550 4,6→2,6.
            if (selfPatrol && unit.type().moveType() == UnitType.Movement.FLY
                    && unit.type().canAttack() && unit.battleNetDoubleStep()) {
                int holds = unit.battleNetSelfPatrolHolds() + 1;
                unit.setBattleNetSelfPatrolHolds(holds);
                // Free stepPatrol visits while sitting on the self endpoint.
                // Six holds after ready matches XOrc 8 rider 1550 first tile
                // at fixture c13 (4,6→2,6). Later riders still need the full
                // native scout endpoint (heading-only stride is interim).
                if (holds < 6) {
                    return;
                }
                int stride = battleNetMovementStride(unit);
                // Construction facing is not the scout direction (XOrc 11
                // 1589 faces east but first-steps south). Prefer the free
                // double-step that approaches map centre; fall back to
                // facing. Closes 1550 west (centre is SE of 4,6 so W is not
                // chosen -- keep facing as first try when it is free, then
                // centre-seeking).
                // First free double-step: edge-aware default, then facing.
                // North-edge interior riders (XOrc 11 1589 at 42,4) first-step
                // south; NW corner riders (XOrc 8 1560 at 2,4) first-step SW;
                // west-edge riders (XOrc 8 1550 at 4,6) first-step west.
                int preferred = unit.heading();
                if (unit.tileY() < stride * 4 && unit.tileX() > stride * 8) {
                    preferred = 4; // south
                } else if (unit.tileX() < stride * 2 && unit.tileY() < stride * 3) {
                    preferred = 5; // south-west
                } else if (unit.tileX() < stride * 4) {
                    preferred = 6; // west
                }
                // The preferred stride must land free. XOrc 8 rider 1550's
                // second scout from 2,6 prefers west onto 0,6, but ally 1560
                // already holds that air footprint; native takes the free SW
                // neighbour to 0,8 at fixture 38. Only preferred and its two
                // neighbouring compass headings are considered -- a full free
                // ring would keep corner rider 1560 on Patrol after residual
                // while native promotes Still at the same fixture (preferred
                // west and both SW/NW neighbours are off the map). When none
                // of the three land free, the scout ends.
                int nextX = unit.tileX();
                int nextY = unit.tileY();
                boolean found = false;
                boolean usedAlternate = false;
                int[] tryHeadings = {
                    preferred,
                    Math.floorMod(preferred - 1, Direction.COUNT),
                    Math.floorMod(preferred + 1, Direction.COUNT)
                };
                for (int attempt = 0; attempt < tryHeadings.length; attempt++) {
                    int heading = tryHeadings[attempt];
                    int candidateX = unit.tileX()
                            + Direction.deltaX(heading) * stride;
                    int candidateY = unit.tileY()
                            + Direction.deltaY(heading) * stride;
                    if (!map.contains(candidateX, candidateY)
                            || (candidateX == unit.tileX()
                                && candidateY == unit.tileY())) {
                        continue;
                    }
                    if (!canEnter(unit, candidateX, candidateY)) {
                        continue;
                    }
                    nextX = candidateX;
                    nextY = candidateY;
                    found = true;
                    usedAlternate = attempt > 0;
                    break;
                }
                if (!found) {
                    // Preferred and both neighbour strides left the map or
                    // were blocked (XOrc 8 1560 at 0,6). Stand down to Still
                    // and mark the self-scout exhausted so the next idle
                    // marker does not re-arm self-patrol as the live order
                    // (that used to flip fixture 44 to Patrol while native
                    // stayed Still through 51 and only then queued a far
                    // half-map patrol as next_order).
                    unit.clearPath();
                    unit.setBattleNetFlyerScoutExhausted(true);
                    unit.setBattleNetSelfPatrolHolds(0);
                    unit.setOrder(Unit.Order.STILL);
                    unit.setActionBeforeQueued(null);
                    // Native reselects Still at the FRAME head with timer 3
                    // (1560 c38 and 1550 c62: sequence 2233 timer 3, then
                    // WAIT 5 at 2237). Arming timer 1 advanced the first OP0
                    // two cycles early and promoted the re-scout Patrol at
                    // fixture 50 while native held Still through 51.
                    if (battleNetSequence != null) {
                        int stillStart = idle.battleNetStillSequenceStart(unit);
                        if (stillStart >= 0) {
                            unit.setBattleNetSequenceOffset(stillStart);
                            unit.setBattleNetAnimationTimer(3);
                        }
                    }
                    return;
                }
                // Keep self-patrol at the destination tile so the next free
                // double-step continues the scout line (XOrc 11 1589
                // 42,4→42,6→42,8). Writing the old tile as the far endpoint
                // made a two-point bounce that returned to 42,4 at fixture 52
                // instead of stepping south again at 37. Seed holds at 5 so
                // the residual-zero free visit (holds→6) takes the next
                // double-step the same cycle native advances route_index
                // (24-cycle scout period after the first tile at fixture 13).
                unit.setPatrol(nextX, nextY);
                unit.setOrderTarget(nextX, nextY);
                unit.setBattleNetSelfPatrolHolds(5);
                // Preferred free residual invent cold-commits same visit
                // (XOrc 11 1589 south→south at fixture 37). An alternate
                // neighbour invent after a blocked preferred must defer the
                // walk one visit -- cold-committing SW for XOrc 8 1550 put the
                // tile on 0,8 at fixture 37 while native still sat 2,6; native
                // lands 0,8 at 38. clearCurrent reopens the Move boundary for
                // whichever visit walks. Standing first invent (no residual)
                // still falls through and walks.
                unit.animation().clearCurrent();
                if (residualSettledThisVisit && usedAlternate) {
                    return;
                }
            } else {
                unit.setPatrol(unit.orderTargetX(), unit.orderTargetY());
                unit.setOrderTarget(backX, backY);
                // BNE's patrol action returns after exchanging its two
                // endpoints.  The following two action visits advance the
                // new movement animation; only the third can take a logical
                // tile step.  This is visible at startup when a behaviour-six
                // capital ship receives its own square as the near endpoint.
                unit.setBattleNetOrderDelay(2);
                return;
            }
        }
        // Destroyer/sub action-5: when the rewritten near goal is blocked,
        // native 0x4376c0 fails the route and promotes Still without running
        // the endpoint-swap executor. XOrc 11 destroyer 1519 keeps (22,38)
        // Still after failing (22,36). Capital ships keep ordinary wall-follow
        // so the XOrc 11 battleship west detour still fires at cycle 5.
        if (unit.type().seaUnit() && !isBattleNetCapitalShip(unit.type().ident())
                && unit.pathLength() == 0 && !unit.isMoving()
                && !battleNetNavalRewriteOpenWater( unit.orderTargetX(), unit.orderTargetY())) {
            // Building-footprint rewritten goals Still when the route fails
            // (XORc 11 destroyer 1519 → shipyard edge). Coast goals that
            // pathfind with a real heading keep that route (first leg NE
            // 18,54→20,52). Only when the coast goal yields an empty FOUND
            // do we snap to a free double-step open-water tile (return leg
            // 20,52→18,52 for XHuman 07 submarine 1511).
            int goalX = unit.orderTargetX();
            int goalY = unit.orderTargetY();
            boolean buildingFootprintGoal = map.contains(goalX, goalY)
                    && (map.field(goalX, goalY).flags()
                            & TileFlag.BUILDING) != 0;
            PathFinder.Path path = findBattleNetPointPath(unit,
                    goalX, goalY, null, false, true);
            boolean emptyOrMissing = path == null || path.length() == 0
                    || path.result() != PathFinder.Result.FOUND;
            if (emptyOrMissing && !buildingFootprintGoal) {
                int[] open = battleNetNearestNavalOpenWater(
                        unit, goalX, goalY);
                if (open != null) {
                    goalX = open[0];
                    goalY = open[1];
                    unit.setOrderTarget(goalX, goalY);
                    path = findBattleNetPointPath(unit,
                            goalX, goalY, null, false, true);
                    emptyOrMissing = path == null || path.length() == 0
                            || path.result() != PathFinder.Result.FOUND;
                }
            }
            if (emptyOrMissing) {
                if (buildingFootprintGoal) {
                    unit.clearPath();
                    unit.setOrder(Unit.Order.STILL);
                    unit.setActionBeforeQueued(null);
                    return;
                }
            } else {
                unit.setPath(path);
                unit.setPathGoal(-1, -1);
                Unit.Order saved = unit.order();
                unit.setOrder(Unit.Order.MOVE);
                movement.stepMove(unit);
                if (unit.order() != Unit.Order.DYING) {
                    unit.setOrder(saved);
                }
                return;
            }
        }
        // Through walkTowards, which is the only thing that may step a unit:
        // stepMove reads the order it is given, so a patrolling unit has to
        // borrow the move order for the duration of the step and give it back.
        movement.walkTowards(unit, unit.orderTargetX(), unit.orderTargetY());
        if (patrolOp0 && battleNetStandingPatrolSequence(unit)
                && unit.order() == Unit.Order.PATROL) {
            // One stride per OP0. Drop leftover headings so the Move body
            // can finish on this tile -- keeping them used to let autoAttack
            // see path>0 and arm the 15-tick hold, or walk off 18,40 before
            // fixture 58.
            armBattleNetPatrolMoveBody(unit);
            unit.clearPath();
        }
    }

    /**
     * Keeps a unit beside its target as that target moves.
     *
     * <p>Whose the target is does not enter into it, here or in the order:
     * {@code COrder_Follow::Execute} ends a follow when the goal is gone or
     * no longer visible as one, never over
     * diplomacy. The enemy check this used to make was the follow refusal in
     * {@code orderFollow} written a second time, so fixing the order alone
     * left a follower that took the order and stood down on its first step.
     */
    private void stepFollow(Unit unit) {
        Unit target = unit.target();
        if (target == null || !target.isAlive() || !target.isOnMap()) {
            unit.setTarget(null);
            unit.clearPath();
            unit.setOrder(Unit.Order.STILL);
            return;
        }
        if (unit.distanceTo(target) <= 1) {
            unit.clearPath();
            return;
        }

        boolean stale = unit.pathGoalX() != target.tileX()
                || unit.pathGoalY() != target.tileY();
        if (!unit.isMoving() && (unit.pathLength() == 0 || stale)) {
            unit.clearPath();
            PathFinder.Goal goal = new PathFinder.Goal(
                    target.tileX(), target.tileY(),
                    Math.max(1, target.type().tileWidth()),
                    Math.max(1, target.type().tileHeight()),
                    0, 1);
            PathFinder.Path path = pathFinder.find(
                    unit.tileX(), unit.tileY(), goal, moverFor(unit));
            if (path.result() != PathFinder.Result.FOUND) {
                unit.setTarget(null);
                unit.setOrder(Unit.Order.STILL);
                return;
            }
            unit.setPath(path);
            unit.setPathGoal(target.tileX(), target.tileY());
        }
        combat.stepMoveTowardsTarget(unit);
    }

    /**
     * Sends a unit after another.
     *
     * <p>After <em>anything</em>, on purpose: upstream's control-right-click
     * is "follow anything" in as many words, and
     * neither {@code SendCommandFollow} nor {@code CommandFollow} asks whose
     * the target is. The one place upstream restricts a follow to own, allied
     * or neutral units is the plain right-click table,
     * and that lives in the interface here too. This used to refuse an enemy
     * target, so control-following a scout's quarry was issued by the click
     * and dropped by the world with nothing said.
     *
     * @return whether the order was accepted
     */
    public boolean orderFollow(Unit unit, Unit target) {
        if (unit == null || target == null || unit == target
                || !unit.isAlive() || !target.isAlive() || !target.isOnMap()
                || unit.type().speed() <= 0) {
            return false;
        }
        unit.clearPath();
        unit.setTarget(target);
        unit.setOrder(Unit.Order.FOLLOW);
        return true;
    }

    /**
     * Sends a unit to stay with a friend and fight what threatens it.
     *
     * <p>BNE's Alt-right-click. Follow never draws a weapon; this order
     * does. A lost, dead, or hostile ward is a refusal -- Java used to
     * drop Alt-right-click through to Move, so the click looked accepted
     * while the unit walked away from the friend it was asked to guard.
     */
    public boolean orderDefend(Unit unit, Unit target) {
        if (unit == null || target == null || unit == target
                || !unit.isAlive() || !target.isAlive() || !target.isOnMap()
                || unit.type() == null || unit.type().speed() <= 0
                || !canDefend(unit, target)) {
            return false;
        }
        unit.clearPath();
        unit.setTarget(target);
        unit.setOrder(Unit.Order.DEFEND);
        return true;
    }

    private boolean canDefend(Unit unit, Unit target) {
        return unit.player() == target.player()
                || isAllied(unit.player(), target.player());
    }

    private void stepDefend(Unit unit) {
        Unit ward = unit.target();
        if (ward == null || !ward.isAlive() || !ward.isOnMap()) {
            unit.setTarget(null);
            unit.clearPath();
            unit.setOrder(Unit.Order.STILL);
            return;
        }
        if (unit.type() != null && unit.type().canAttack() && unit.isAggressive()) {
            Unit threat = nearestThreatToWard(unit, ward);
            if (threat != null) {
                unit.setTarget(threat);
                combat.stepAttack(unit);
                if (unit.order() == Unit.Order.DEFEND) {
                    unit.setTarget(ward);
                }
                return;
            }
        }
        if (unit.distanceTo(ward) <= 1) {
            unit.clearPath();
            return;
        }
        boolean stale = unit.pathGoalX() != ward.tileX()
                || unit.pathGoalY() != ward.tileY();
        if (!unit.isMoving() && (unit.pathLength() == 0 || stale)) {
            unit.clearPath();
            PathFinder.Goal goal = new PathFinder.Goal(
                    ward.tileX(), ward.tileY(),
                    Math.max(1, ward.type().tileWidth()),
                    Math.max(1, ward.type().tileHeight()),
                    0, 1);
            PathFinder.Path path = pathFinder.find(
                    unit.tileX(), unit.tileY(), goal, moverFor(unit));
            if (path.result() != PathFinder.Result.FOUND) {
                return;
            }
            unit.setPath(path);
            unit.setPathGoal(ward.tileX(), ward.tileY());
        }
        combat.stepMoveTowardsTarget(unit);
        if (unit.target() != ward && unit.order() == Unit.Order.DEFEND) {
            unit.setTarget(ward);
        }
    }

    private Unit nearestThreatToWard(Unit defender, Unit ward) {
        Unit best = null;
        int bestDist = Integer.MAX_VALUE;
        int range = Math.max(1, defender.type().maxAttackRange());
        for (Unit other : unitsSnapshot()) {
            if (other == defender || other == ward || !other.isAlive()
                    || !other.isOnMap() || other.type() == null
                    || !other.type().canAttack()) {
                continue;
            }
            if (canDefend(defender, other)) {
                continue;
            }
            int dist = Math.min(defender.distanceTo(other), ward.distanceTo(other));
            if (dist <= range && dist < bestDist) {
                best = other;
                bestDist = dist;
            }
        }
        return best;
    }

    /**
     * Walking to a transport and getting aboard it.
     *
     * <p>Boarding is a journey, not an instant. {@link #board} refuses anybody
     * standing further than a square away, which is right -- a footman cannot
     * swim out to a boat -- but it left the implementation with no way for a soldier to
     * reach one: the only thing that could put a unit on a transport was a
     * method nothing called. So the order walks the unit to the shore beside
     * the boat and boards it on arrival, and it follows the boat if the boat
     * moves, because a transport that has drifted a square down the coast is
     * still the transport that was asked for.
     */
    private void stepBoard(Unit unit) {
        Unit transport = unit.target();
        if (transport == null || !transport.isAlive() || unit.isAboard()) {
            unit.setTarget(null);
            unit.setOrder(Unit.Order.STILL);
            return;
        }
        if (!transport.hasRoom() || !transport.type().canCarry(unit.type())) {
            // Full, or not the sort of thing this boat carries. Stopping is
            // better than walking to the water's edge and standing there.
            unit.setTarget(null);
            unit.setOrder(Unit.Order.STILL);
            return;
        }
        if (unit.distanceTo(transport) <= 1) {
            unit.setBoardRange(1);
            board(unit, transport);
            return;
        }
        approachTransport(unit, transport);
    }

    /**
     * Walks a passenger towards a boat it cannot necessarily reach yet.
     *
     * <p>Implements {@code COrder_Board::MoveToTransporter} and the retry around
     * it in {@code COrder_Board::Execute}.
     *
     * <p>The goal is the transport's whole <em>footprint</em>, not its top-left
     * tile. A transport is two squares by two and sits on water, so routing to
     * one corner offered eight neighbours that were usually all water: unless
     * the shore happened to meet that particular corner, the planner answered
     * unreachable and the passenger dropped to STILL without taking a step,
     * having already accepted the order and written "boarding" to the status
     * line. That is the same mistake as a worker routing to a Great Hall's
     * origin square, which trapped peons cycling in and out of gold mines, and
     * as a chase aimed at the square its target occupies. A goal is a place the
     * mover could stand, not the place the thing it wants happens to begin.
     *
     * <p>And when no square within reach satisfies it, the passenger widens
     * what it will settle for and tries again next cycle rather than giving up
     * where it stands. That is how you order troops onto a boat that is still
     * sailing in: they walk as close as the ground allows, usually the
     * shoreline, and wait. Before it, a passenger ordered at an incoming
     * transport stood still until the boat had already arrived.
     *
     * <p>The range resets the moment the unit actually moves. That is
     * upstream's own rule and it is load
     * bearing rather than tidy: a passenger that kept the width it earned while
     * stuck would be satisfied standing well short of the boat, and the two
     * would shuffle around each other indefinitely -- "or else they will circle
     * each other and stuff", as the comment there puts it.
     */
    private void approachTransport(Unit unit, Unit transport) {
        int fromX = unit.tileX();
        int fromY = unit.tileY();

        if (unit.pathLength() == 0 && !unit.isMoving()) {
            PathFinder.Goal goal = new PathFinder.Goal(
                    transport.tileX(), transport.tileY(),
                    Math.max(1, transport.type().tileWidth()),
                    Math.max(1, transport.type().tileHeight()),
                    0, Math.max(1, unit.boardRange()));
            PathFinder.Path path = pathFinder.find(
                    unit.tileX(), unit.tileY(), goal, moverFor(unit));
            if (path.result() == PathFinder.Result.REACHED) {
                // Already as close as this width allows: upstream's
                // State_WaitForTransporter. Wait where we are, and narrow back
                // to wanting adjacency so that the moment the boat comes within
                // reach the next search returns a real route and we close in.
                // Holding the widened width here instead would leave the
                // passenger permanently satisfied standing short of the boat.
                unit.setBoardRange(1);
                return;
            }
            if (path.result() != PathFinder.Result.FOUND) {
                if (unit.boardRange() >= MAX_BOARD_RANGE) {
                    unit.setBoardRange(1);
                    unit.setTarget(null);
                    unit.setOrder(Unit.Order.STILL);
                    return;
                }
                unit.setBoardRange(unit.boardRange() + 1);
                return;
            }
            unit.setPath(path);
            // No path goal: this order re-plans for itself when the route runs
            // out, and a stale goal would send the passenger somewhere else.
            unit.setPathGoal(-1, -1);
        }

        Unit.Order saved = unit.order();
        unit.setOrder(Unit.Order.MOVE);
        movement.stepMove(unit, false);
        if (unit.order() != Unit.Order.DYING) {
            unit.setOrder(saved);
        }
        if (unit.tileX() != fromX || unit.tileY() != fromY) {
            unit.setBoardRange(1);
        }
    }

    /**
     * How far a passenger will widen its approach before giving up.
     *
     * <p>Upstream counts attempts to two hundred while letting the range grow
     * with them, which on any real map means it never truly stops trying.
     * Bounded here by more than any map's span, because past that every square
     * already satisfies the goal and widening further would turn a board order
     * into standing still while reporting success.
     */
    private static final int MAX_BOARD_RANGE = 256;

    /**
     * Sends a unit to board a transport.
     *
     * @return whether the order was accepted
     */
    public boolean orderBoard(Unit passenger, Unit transport) {
        if (passenger == null || transport == null || passenger == transport
                || !passenger.isAlive() || !transport.isAlive()
                || passenger.isAboard()
                || !transport.type().canCarry(passenger.type())) {
            return false;
        }
        passenger.clearPath();
        passenger.setTarget(transport);
        passenger.setOrder(Unit.Order.BOARD);
        return true;
    }

    /**
     * Walks towards ground nobody has seen.
     *
     * <p>Implements {@code COrder_Explore::Execute}
     * which is a plain walk with
     * three habits of its own: arriving picks a fresh random destination and
     * carries on -- the order never finishes on arrival -- an unreachable one
     * widens the range and tries again, and the fifth consecutive waiting
     * answer gives the destination up and draws another. The draws are the
     * point: {@code GetExplorationTarget} spends two to eight numbers off the
     * shared stream every time it runs, so an engine whose explorer hunts
     * ground deterministically -- which is what this used to do, walking at
     * the nearest unexplored square -- is off the stream from the explorer's
     * first decision. level05h's zeppelin found it: the implementation that survived
     * nine maps' worth of combat diverged on the second campaign survey at
     * cycle 1.
     *
     * <p>The order ends only when the unit's own scans find something to do
     * -- {@code AutoAttack(unit) || AutoRepair(unit) || AutoCast(unit)},
     * asked outside the animation's hold -- so a scout that cannot fight
     * explores for the rest of the game, which is exactly what a goblin
     * zeppelin is for.
     */
    private void stepExplore(Unit unit) {
        boolean traceExplore = System.getenv("CHONKCRAFT_TRACE_EXPLORE") != null
                && unit.id() == Integer.parseInt(System.getenv("CHONKCRAFT_TRACE_EXPLORE"));
        if (traceExplore) {
            System.err.printf("JEXPLORE cycle=%d unit=%d arm=enter moving=%d stepping=%d"
                            + " path=%d spent=%d wait=%d range=%d waiting=%d goal=%d,%d%n",
                    cycle, unit.id(), unit.isMoving() ? 1 : 0, movement.isStepping(unit) ? 1 : 0,
                    unit.pathLength(), unit.routeSpent() ? 1 : 0, unit.waitCycles(),
                    unit.moveRange(), unit.exploreWaitingCycle(),
                    unit.orderTargetX(), unit.orderTargetY());
        }
        if (!unit.isMoving() && !movement.isStepping(unit) && unit.pathLength() == 0) {
            if (movement.spendTheEmptyRoute(unit)) {
                if (traceExplore) {
                    System.err.printf("JEXPLORE cycle=%d unit=%d arm=spent%n",
                            cycle, unit.id());
                }
                // The count-born wait: one waiting answer.
                bumpExploreWait(unit);
                return;
            }
            PathFinder.Path path = pathFinder.find(unit.tileX(), unit.tileY(),
                    new PathFinder.Goal(unit.orderTargetX(), unit.orderTargetY(),
                            1, 1, 0, unit.moveRange()),
                    moverFor(unit));
            switch (path.result()) {
                case REACHED -> {
                    if (traceExplore) {
                        System.err.printf("JEXPLORE cycle=%d unit=%d arm=reached%n",
                                cycle, unit.id());
                    }
                    // "pick a new place to explore" -- the next cycle plans
                    // towards it.
                    movement.resetDisplacement(unit);
                    unit.setExploreWaitingCycle(1);
                    unit.setMoveRange(0);
                    int[] next = explorationTarget(unit);
                    unit.setOrderTarget(next[0], next[1]);
                    return;
                }
                case UNREACHABLE -> {
                    if (traceExplore) {
                        System.err.printf("JEXPLORE cycle=%d unit=%d arm=unreachable%n",
                                cycle, unit.id());
                    }
                    // "Increase range and try again."
                    movement.resetDisplacement(unit);
                    unit.setExploreWaitingCycle(1);
                    unit.setMoveRange(unit.moveRange() + 1);
                    return;
                }
                default -> {
                    if (traceExplore) {
                        System.err.printf("JEXPLORE cycle=%d unit=%d arm=path length=%d%n",
                                cycle, unit.id(), path.headings().length);
                    }
                    unit.setPath(path);
                    // No path goal: this order re-plans for itself, as the
                    // march does.
                    unit.setPathGoal(-1, -1);
                    unit.setExploreWaitingCycle(0);
                }
            }
        }
        // The walk itself, borrowed the way the march borrows it.
        Unit.Order saved = unit.order();
        unit.setOrder(Unit.Order.MOVE);
        int waiting = unit.waitCycles();
        movement.stepMove(unit);
        if (unit.order() != Unit.Order.DYING) {
            unit.setOrder(saved);
        }
        if (unit.order() != Unit.Order.EXPLORE) {
            return;
        }
        if (unit.waitCycles() > waiting) {
            // A refused step: PF_WAIT, one waiting answer.
            bumpExploreWait(unit);
            return;
        }
        // COrder_Explore's default arm resets WaitingCycle on every moving
        // answer.  The counter measures consecutive PF_WAITs, not lifetime
        // obstruction: once a cached route advances, four old refusals must
        // not turn its later end-of-route pause into a fifth refusal and a
        // fresh random destination (level12h's zeppelin at cycle 1274).
        unit.setExploreWaitingCycle(0);
        // The acquisitions, outside the animation's hold. AutoRepair and
        // AutoCast go here when something that explores can do either; the
        // two shipped explorers are scouts with no gun, no hammer and no
        // spellbook, so today the only working arm is the attack.
        if (!unit.animation().unbreakable() && combat.autoAttack(unit)) {
            finishOrder(unit);
        }
    }

    /**
     * What a unit does the moment it is ready, if its type says anything.
     *
     * <p>{@code OnReady}, the per-type callback upstream fires for every unit
     * at game creation, for a trained unit
     * and for a finished building
     * The shipped data's one callback is
     * {@code AiExploreUnit} ({@code units.legacy-declaration:601}) on the two scout flyers,
     * and its body is four lines: an AI-enabled computer or rescue-active
     * player's flyer is ordered to explore -- "send those balloons flying".
     * That body runs here in Java rather than through the evaluator, the way
     * the behavioural scripts do generally; the type flag says whether the
     * data asked for it.
     */
    public void fireOnReady(Unit unit) {
        if (unit == null || unit.type() == null || !unit.type().onReadyExplores()
                || !unit.isAlive()) {
            return;
        }
        if (!ais().containsKey(unit.player())) {
            return;
        }
        Player owner = player(unit.player());
        if (owner == null
                || (owner.type() != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                        && owner.type()
                                != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE)) {
            return;
        }
        orderExplore(unit);
    }

    /**
     * The game-creation pass: every unit already standing gets its ready
     * moment, as {@code CreateGame}'s tail walks the unit list.
     */
    public void fireOnReadyForAll() {
        for (Unit unit : unitsSnapshot()) {
            fireOnReady(unit);
        }
    }

    /**
     * Applies the startup type-two assault groups queued by BNE AI profiles.
     *
     * <p>{@code FUN_00426ad0/FUN_00426f70} consumes each profile's pending
     * type-two group before the game-creation ready pass.  It walks the native
     * unit chain from low pool slot upward and assigns behavior two with the
     * person's assault objective.  The Java roster is the reverse of that pool
     * order, hence the reverse walks below.</p>
     *
     * <p>Profile 35 takes three unmarked surface naval attackers (XOrc 8
     * destroyers/battleship at home 98,122). Profile 18 takes four unmarked
     * land attackers (Orc 11 knight 1558 and archers 1559/1560/1563 at home
     * 106,7). Taking nearest-enemy patrol for every profile-18 land fighter
     * used to aim the knight at the alchemist (117,21) while native stepped
     * NW toward the farm-side assault home (106,7).</p>
     */
    void prepareBattleNetInitialAttackGroups(List<Unit> ready) {
        for (var entry : ais.entrySet()) {
            int owner = entry.getKey();
            AiPlayer ai = entry.getValue();
            if (ai == null) {
                continue;
            }
            int profile = ai.battleNetBuildProfileId();
            if (profile == 35) {
                prepareBattleNetNavalAssaultGroup(ready, owner, 3);
            } else if (profile == 18) {
                prepareBattleNetLandAssaultGroup(ready, owner, 4);
            }
        }
    }

    /** Profile 35's three-ship type-two sea assault. */
    private void prepareBattleNetNavalAssaultGroup(List<Unit> ready, int owner,
            int assaultGroupSize) {
        Unit target = battleNetInitialSeaAssaultTarget(owner);
        if (target == null) {
            return;
        }
        int assigned = 0;
        for (int index = ready.size() - 1;
                index >= 0 && assigned < assaultGroupSize;
                index--) {
            Unit unit = ready.get(index);
            if (BNE_IDLE_TRACE && unit != null && unit.player() == owner
                    && unit.type() != null
                    && unit.type().moveType() == UnitType.Movement.NAVAL
                    && unit.type().canAttack()) {
                System.err.printf("JBNEGROUP profile=35 unit=%d type=%s"
                                + " suppressed=%d selected=%d%n",
                        unit.id(), unit.type().ident(),
                        unit.battleNetReadySuppressed() ? 1 : 0,
                        assigned);
            }
            if (unit == null || unit.player() != owner || !unit.isAlive()
                    || !unit.isOnMap() || unit.type() == null
                    || unit.battleNetReadySuppressed()
                    || unit.type().moveType() != UnitType.Movement.NAVAL
                    || !unit.type().canAttack() || unit.type().canGather()
                    || isBattleNetSubmarine(unit.type().ident())) {
                continue;
            }
            unit.setBattleNetAiBehavior(2);
            unit.setBattleNetAiHome(target.tileX(), target.tileY());
            assigned++;
        }
    }

    /** Profile 18's four-fighter type-two land assault. */
    private void prepareBattleNetLandAssaultGroup(List<Unit> ready, int owner,
            int assaultGroupSize) {
        int[] home = battleNetInitialLandAssaultHome(owner);
        if (home == null) {
            return;
        }
        int assigned = 0;
        for (int index = ready.size() - 1;
                index >= 0 && assigned < assaultGroupSize;
                index--) {
            Unit unit = ready.get(index);
            if (BNE_IDLE_TRACE && unit != null && unit.player() == owner
                    && unit.type() != null
                    && unit.type().moveType() == UnitType.Movement.LAND
                    && unit.type().canAttack() && !unit.type().canGather()) {
                System.err.printf("JBNEGROUP profile=18 unit=%d type=%s"
                                + " suppressed=%d selected=%d home=%d,%d%n",
                        unit.id(), unit.type().ident(),
                        unit.battleNetReadySuppressed() ? 1 : 0,
                        assigned, home[0], home[1]);
            }
            if (unit == null || unit.player() != owner || !unit.isAlive()
                    || !unit.isOnMap() || unit.type() == null
                    || unit.battleNetReadySuppressed()
                    || unit.type().moveType() != UnitType.Movement.LAND
                    || unit.type().building()
                    || !unit.type().canAttack() || unit.type().canGather()) {
                continue;
            }
            unit.setBattleNetAiBehavior(2);
            unit.setBattleNetAiHome(home[0], home[1]);
            assigned++;
        }
    }

    /**
     * Free land square used as the type-two land assault home.
     *
     * <p>Native Orc 11 stores {@code aiHome=106,7} for the four unmarked
     * forward fighters -- the free tile west of pig-farm {@code 107,6}, not
     * the nearer alchemist/sappers. Selection walks the person's buildings
     * and keeps a free square from {@link #battleNetNormalizeLandHome} on the
     * first farm found; halls alone normalize too close to the keep and miss
     * the farm corridor native patrols.</p>
     */
    private int[] battleNetInitialLandAssaultHome(int owner) {
        int targetPlayer = -1;
        int mostUnits = 0;
        for (int player = 0; player < Math.min(8, players.length); player++) {
            Player candidate = player(player);
            if (candidate == null
                    || candidate.type()
                            != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.PERSON
                    || !isEnemyPlayer(owner, player)) {
                continue;
            }
            int count = 0;
            for (Unit unit : playerUnits(player)) {
                // Count buildings too: a person-only farm base (minimal
                // regression maps) must still be a valid assault objective.
                if (unit.isAlive() && unit.isOnMap() && unit.type() != null) {
                    count++;
                }
            }
            if (count > mostUnits) {
                mostUnits = count;
                targetPlayer = player;
            }
        }
        if (targetPlayer < 0) {
            return null;
        }
        Unit farm = null;
        Unit building = null;
        for (Unit unit : playerUnits(targetPlayer)) {
            if (!unit.isAlive() || !unit.isOnMap() || unit.type() == null
                    || !unit.type().building()) {
                continue;
            }
            String ident = unit.type().ident();
            if (building == null) {
                building = unit;
            }
            if (ident != null && ident.contains("farm") && farm == null) {
                farm = unit;
            }
        }
        Unit objective = farm != null ? farm : building;
        if (objective == null) {
            return null;
        }
        // Use the assault owner's connectivity so the free square is walkable
        // from the fighters' side of the map.
        Unit seed = null;
        for (Unit unit : playerUnits(owner)) {
            if (unit.isAlive() && unit.isOnMap() && unit.type() != null
                    && unit.type().moveType() == UnitType.Movement.LAND
                    && unit.type().canAttack() && !unit.type().canGather()
                    && !unit.battleNetReadySuppressed()) {
                seed = unit;
                break;
            }
        }
        if (seed == null) {
            return new int[] {objective.tileX(), objective.tileY()};
        }
        boolean[] component = battleNetConnectivityCell(seed);
        int[] home = battleNetNormalizeLandHome(objective.tileX(),
                objective.tileY(), component);
        return home != null ? home
                : new int[] {objective.tileX(), objective.tileY()};
    }

    /** Native type-two objective selector ({@code FUN_00426930}). */
    private Unit battleNetInitialSeaAssaultTarget(int owner) {
        int targetPlayer = -1;
        int mostUnits = 0;
        for (int player = 0; player < Math.min(8, players.length); player++) {
            Player candidate = player(player);
            if (candidate == null
                    || candidate.type()
                            != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.PERSON) {
                continue;
            }
            int count = 0;
            for (Unit unit : playerUnits(player)) {
                if (unit.isAlive() && unit.isOnMap() && !unit.type().building()) {
                    count++;
                }
            }
            if (count > mostUnits) {
                mostUnits = count;
                targetPlayer = player;
            }
        }
        if (targetPlayer < 0 || !isEnemyPlayer(owner, targetPlayer)) {
            return null;
        }

        Unit objective = null;
        for (Unit unit : playerUnits(targetPlayer)) {
            if (!unit.isAlive() || !unit.isOnMap() || unit.type() == null) {
                continue;
            }
            String ident = unit.type().ident();
            if ("unit-battleship".equals(ident)
                    || "unit-ogre-juggernaught".equals(ident)) {
                objective = unit;
            }
        }
        if (objective != null) {
            return objective;
        }
        for (Unit unit : playerUnits(targetPlayer)) {
            if (unit.isAlive() && unit.isOnMap() && unit.type() != null
                    && !unit.type().building()) {
                objective = unit;
            }
        }
        return objective;
    }

    private static boolean isBattleNetSubmarine(String ident) {
        return "unit-human-submarine".equals(ident)
                || "unit-orc-submarine".equals(ident);
    }

    private static boolean isBattleNetCapitalShip(String ident) {
        return "unit-battleship".equals(ident)
                || "unit-ogre-juggernaught".equals(ident);
    }

    /**
     * BNE's initial defensive point for an ordinary AI land fighter.
     *
     * <p>{@code 0x427130} starts at the closest owned gold depot in the
     * fighter's fixed terrain component. During the map-creation call which
     * assigns these homes, the later AI base rectangle has not yet been
     * populated, so the line toward the closest hostile stops on the depot
     * itself. The depot is occupied, so {@code 0x416a00/0x443a40} walks a
     * fixed square spiral, at most 24 tiles out, and retains the first free
     * land square in the fighter's component. Crucially this runs as each PUD
     * unit is created, not after the map is complete: units and buildings
     * created earlier alter the home chosen for units created later.</p>
     */
    private int[] battleNetInitialLandHome(Unit unit) {
        boolean[] component = battleNetConnectivityCell(unit);
        Unit hall = null;
        int bestDistance = Integer.MAX_VALUE;
        List<Unit> owned = playerUnits(unit.player());
        for (int index = owned.size() - 1; index >= 0; index--) {
            Unit candidate = owned.get(index);
            if (!candidate.isAlive() || !candidate.isOnMap()
                    || candidate.type() == null
                    || candidate.player() != unit.player()
                    || !isBattleNetHall(candidate.type().ident())) {
                continue;
            }
            int at = candidate.tileX() + candidate.tileY() * map.width();
            if (!component[at]) {
                continue;
            }
            int candidateDistance = Math.max(
                    Math.abs(unit.tileX() - candidate.tileX()),
                    Math.abs(unit.tileY() - candidate.tileY()));
            if (candidateDistance < bestDistance) {
                bestDistance = candidateDistance;
                hall = candidate;
            }
        }
        if (hall == null) {
            return null;
        }
        return battleNetNormalizeLandHome(hall.tileX(), hall.tileY(), component);
    }

    /** Ports the 1x1, radius-24 invocation of native {@code 0x443a40}. */
    private int[] battleNetNormalizeLandHome(int targetX, int targetY,
            boolean[] component) {
        if (battleNetLandHomeSquare(targetX, targetY, component)) {
            return new int[] {targetX, targetY};
        }
        int[] dx = {1, 0, -1, 0};
        int[] dy = {0, 1, 0, -1};
        int x = targetX - 1;
        int y = targetY - 1;
        int direction = 1;
        int horizontal = 1;
        int vertical = 1;
        while (horizontal < 24) {
            int longLeg = vertical + 2;
            int shortLeg = horizontal + 1;
            for (int leg = 0; leg < 4; leg++) {
                int length = switch (leg) {
                    case 0, 2 -> shortLeg;
                    case 1 -> vertical + 1;
                    default -> longLeg;
                };
                for (int step = 0; step < length; step++) {
                    x += dx[direction];
                    y += dy[direction];
                    if (battleNetLandHomeSquare(x, y, component)) {
                        return new int[] {x, y};
                    }
                }
                direction = (direction + 3) & 3;
            }
            horizontal += 2;
            vertical = longLeg;
            x -= dx[direction];
            y -= dy[direction];
        }
        return null;
    }

    private boolean battleNetLandHomeSquare(int x, int y,
            boolean[] component) {
        if (!map.contains(x, y) || !component[x + y * map.width()]) {
            return false;
        }
        MapField field = map.field(x, y);
        long blocked = TileFlag.BUILDING | TileFlag.LAND_UNIT
                | TileFlag.WALL | TileFlag.ROCKS | TileFlag.FOREST;
        return field.hasFlag(TileFlag.LAND_ALLOWED)
                && !field.hasFlag(TileFlag.UNPASSABLE | blocked);
    }

    /** Destination chosen by BNE naval behaviour six ({@code 0x427a10}). */
    BattleNetPatrolEndpoints battleNetNavalPatrolTarget(Unit ship) {
        boolean attackBehavior = ship.battleNetAiBehavior() == 2;
        // A queued attack group may have replaced native behaviour six before
        // this ready callback. Its callback is FUN_00427f60, and it retains
        // the behavior-two home selected by FUN_00426930.
        // FUN_00427a10 walks the owner's stable list for the first type with
        // native flag 0x200. Runtime type-table captures identify that type as
        // an oil tanker (0x208), not a surface attacker. FUN_00438770 then
        // finds the nearest shipyard or refinery whose footprint touches the
        // tanker's fixed water component. XOrc 7's first tanker at (24,6)
        // selects the shipyard at (6,18), so the remote battleship at (92,6)
        // opens west toward the same service base.
        //
        // Destroyers and submarines use the same constructor as capital ships.
        // The old destroyer-only branch inverted the endpoints (near = open-
        // water wiggle, far = self) and never looked up neutral oil platforms.
        // XHuman 8 destroyer 1480 keeps near = self and far = platform 41,85;
        // without that pair Java failed the near goal into Still at cycle 9
        // while native stayed on Patrol after the at-self endpoint swap.
        Unit tanker = null;
        if (!attackBehavior) {
            for (Unit candidate : playerUnits(ship.player())) {
                if (candidate.isAlive() && candidate.isOnMap()
                        && candidate.type().gathering()
                                .containsKey(UnitType.Resource.OIL)
                        && !candidate.type().gathering()
                                .containsKey(UnitType.Resource.GOLD)
                        && !candidate.type().gathering()
                                .containsKey(UnitType.Resource.WOOD)) {
                    tanker = candidate;
                    break;
                }
            }
        }
        int targetX = ship.tileX();
        int targetY = ship.tileY();
        if (tanker != null) {
            boolean[] component = battleNetConnectivityCell(tanker);
            Unit shoreBase = null;
            int bestDistance = Integer.MAX_VALUE;
            for (Unit candidate : playerUnits(ship.player())) {
                if (!candidate.isAlive() || !candidate.isOnMap()
                        || !isBattleNetNavalBase(candidate.type().ident())
                        || !battleNetFootprintTouchesComponent(
                                candidate, component)) {
                    continue;
                }
                int distance = tanker.distanceTo(candidate);
                // FUN_00438770 replaces the old candidate on equal distance.
                if (distance <= bestDistance) {
                    bestDistance = distance;
                    shoreBase = candidate;
                }
            }
            if (shoreBase != null) {
                if (BNE_IDLE_TRACE) {
                    System.err.printf("JBNENAVAL ship=%d profile=%d"
                                    + " tanker=%d base=%d"
                                    + " target=%d,%d%n",
                            ship.id(), battleNetAiProfileId(ship.player()),
                            tanker.id(), shoreBase.id(),
                            shoreBase.tileX(), shoreBase.tileY());
                }
                targetX = shoreBase.tileX();
                targetY = shoreBase.tileY();
            }
        }

        if (attackBehavior) {
            int attackX = ship.hasBattleNetAiHome()
                    ? ship.battleNetAiHomeX() : ship.tileX();
            int attackY = ship.hasBattleNetAiHome()
                    ? ship.battleNetAiHomeY() : ship.tileY();
            if (BNE_IDLE_TRACE) {
                System.err.printf("JBNENAVAL ship=%d profile=%d"
                                + " behavior=2 target=%d,%d%n",
                        ship.id(), battleNetAiProfileId(ship.player()),
                        attackX, attackY);
            }
            return new BattleNetPatrolEndpoints(attackX, attackY,
                    ship.tileX(), ship.tileY());
        }

        // Far endpoint order, measured from near (self without a tanker):
        // 1) closest owned oil platform (native flag 0x800),
        // 2) closest oil patch (native flag 0x200000),
        // 3) closest oil platform on any player,
        // else two async RNG draws around near.
        //
        // Owned platforms beat foreign ones, but patches beat foreign
        // platforms: XHuman 8 destroyer 1480 has far = neutral oil-patch
        // 41,85 (type 93 / p15) while an enemy platform at 67,55 is the
        // only live platform. Ranking any platform before all patches
        // aimed the patrol at 67,55, first-stepped NE to 36,80, and
        // diverged at fixture cycle 12; native route 02 03 02 lands on
        // 36,82 toward 41,85. XOrc 8 / XOrc 10 keep owned or sole
        // platforms as far when no closer owned platform or patch wins.
        Unit far = null;
        int farDistance = 0xffff;
        for (Unit candidate : playerUnits(ship.player())) {
            if (!candidate.isAlive() || !candidate.isOnMap()
                    || !harvest.isBattleNetOilPlatform(candidate.type().ident())) {
                continue;
            }
            int distance = candidate.distanceTo(targetX, targetY);
            if (distance < farDistance) {
                far = candidate;
                farDistance = distance;
            }
        }
        if (far == null) {
            for (Unit candidate : units) {
                if (!candidate.isAlive() || !candidate.isOnMap()
                        || !"unit-oil-patch".equals(candidate.type().ident())) {
                    continue;
                }
                int distance = candidate.distanceTo(targetX, targetY);
                if (distance < farDistance) {
                    far = candidate;
                    farDistance = distance;
                }
            }
        }
        if (far == null) {
            for (Unit candidate : units) {
                if (!candidate.isAlive() || !candidate.isOnMap()
                        || !harvest.isBattleNetOilPlatform(candidate.type().ident())) {
                    continue;
                }
                int distance = candidate.distanceTo(targetX, targetY);
                if (distance < farDistance) {
                    far = candidate;
                    farDistance = distance;
                }
            }
        }
        int backX;
        int backY;
        if (far != null) {
            backX = far.tileX();
            backY = far.tileY();
        } else {
            backX = Math.max(0, targetX - battleNetRand() % 5 - 2);
            backY = Math.max(0, targetY - battleNetRand() % 5 - 2);
        }
        if (BNE_IDLE_TRACE) {
            System.err.printf("JBNENAVAL ship=%d profile=%d"
                            + " behavior=6 tanker=%d target=%d,%d"
                            + " back=%d,%d%n",
                    ship.id(), battleNetAiProfileId(ship.player()),
                    tanker == null ? -1 : tanker.id(),
                    targetX, targetY, backX, backY);
        }
        return new BattleNetPatrolEndpoints(targetX, targetY, backX, backY);
    }

    record BattleNetPatrolEndpoints(
            int targetX, int targetY, int backX, int backY) {
    }

    private int battleNetAiProfileId(int player) {
        AiPlayer ai = ais.get(player);
        return ai == null ? -1 : ai.battleNetBuildProfileId();
    }

    /** Whether any square occupied by a building has one native component. */
    boolean battleNetFootprintTouchesComponent(Unit building,
            boolean[] component) {
        int right = Math.min(map.width(), building.tileX()
                + Math.max(1, building.type().tileWidth()));
        int bottom = Math.min(map.height(), building.tileY()
                + Math.max(1, building.type().tileHeight()));
        for (int y = Math.max(0, building.tileY()); y < bottom; y++) {
            for (int x = Math.max(0, building.tileX()); x < right; x++) {
                if (component[x + y * map.width()]) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isBattleNetNavalBase(String ident) {
        return isBattleNetShipyard(ident)
                || "unit-human-refinery".equals(ident)
                || "unit-orc-refinery".equals(ident);
    }

    /** The ship-repair target accepted by native type flag {@code 0x1000000}. */
    private static boolean isBattleNetShipyard(String ident) {
        return "unit-human-shipyard".equals(ident)
                || "unit-orc-shipyard".equals(ident);
    }

    /** The nearest free square against the closest hostile unit. */
    private int[] battleNetLandPatrolTarget(Unit unit) {
        Unit nearest = null;
        int distance = Integer.MAX_VALUE;
        for (Unit candidate : units) {
            if (!candidate.isAlive() || !candidate.isOnMap()
                    || !isEnemyPlayer(unit.player(), candidate.player())) {
                continue;
            }
            int candidateDistance = unit.distanceTo(candidate);
            if (candidateDistance < distance) {
                distance = candidateDistance;
                nearest = candidate;
            }
        }
        if (nearest == null) {
            return null;
        }
        for (int radius = 0; radius <= 2; radius++) {
            for (int y = nearest.tileY() - radius;
                    y <= nearest.tileY() + radius; y++) {
                for (int x = nearest.tileX() - radius;
                        x <= nearest.tileX() + radius; x++) {
                    if (map.contains(x, y) && canEnter(unit, x, y)) {
                        return new int[] {x, y};
                    }
                }
            }
        }
        return new int[] {nearest.tileX(), nearest.tileY()};
    }

    /** BNE's nearest eligible hall search at 0x439ce0. */
    Unit nearestBattleNetHall(Unit unit) {
        Unit nearest = null;
        int distance = Integer.MAX_VALUE;
        for (Unit candidate : units) {
            if (!candidate.isAlive() || candidate.player() != unit.player()
                    || !isBattleNetHall(candidate.type().ident())) {
                continue;
            }
            int candidateDistance = unit.distanceTo(candidate);
            if (candidateDistance < distance) {
                distance = candidateDistance;
                nearest = candidate;
            }
        }
        return nearest;
    }

    static boolean isBattleNetHall(String ident) {
        return "unit-town-hall".equals(ident) || "unit-keep".equals(ident)
                || "unit-castle".equals(ident) || "unit-great-hall".equals(ident)
                || "unit-stronghold".equals(ident) || "unit-fortress".equals(ident);
    }

    /**
     * One waiting answer against the explore order's patience.
     *
     * <p>{@code WaitingCycle++}, and the fifth in a row abandons the
     * destination: range back to nought, a fresh target drawn, the route
     * dropped.
     */
    private void bumpExploreWait(Unit unit) {
        int waited = unit.exploreWaitingCycle() + 1;
        if (waited == 5) {
            unit.setExploreWaitingCycle(0);
            unit.setMoveRange(0);
            int[] next = explorationTarget(unit);
            unit.setOrderTarget(next[0], next[1]);
            unit.clearPath();
        } else {
            unit.setExploreWaitingCycle(waited);
        }
    }

    /** Sends a unit to walk a beat between where it stands and a square. */
    public boolean orderPatrol(Unit unit, int toX, int toY) {
        if (unit == null || !unit.isAlive() || unit.type().speed() <= 0
                || !map.contains(toX, toY)) {
            return false;
        }
        unit.clearPath();
        unit.setPatrol(unit.tileX(), unit.tileY());
        unit.setOrderTarget(toX, toY);
        unit.setOrder(Unit.Order.PATROL);
        armBattleNetPatrolSequence(unit);
        return true;
    }

    /**
     * Keeps the Still cursor a capital-ship Patrol was promoted from, or
     * installs Move when that cursor was already gone.
     *
     * <p>Native 1511 keeps Still 2955 and resets the timer to 3 on the
     * promote visit. Wiping the cursor here used to leave every later
     * walkTowards on sequence -1, so the ship never saw the Move-body OP0
     * that opens Attack at fixture 58.
     */
    private void armBattleNetPatrolSequence(Unit unit) {
        if (battleNetSequence == null || unit == null || unit.type() == null
                || !unit.type().seaUnit()
                || !isBattleNetCapitalShip(unit.type().ident())) {
            return;
        }
        if (unit.battleNetSequenceOffset() >= 0) {
            unit.setBattleNetAnimationTimer(3);
            return;
        }
        int move = idle.battleNetSequenceStart(unit,
                BattleNetSequence.MOVE_ANIMATION);
        if (move >= 0) {
            unit.setBattleNetSequenceOffset(move);
            unit.setBattleNetAnimationTimer(3);
        }
    }

    /**
     * Whether this Patrol's binary sequence owns acquire and stride cadence.
     *
     * <p>Capital ships hold one double-step for the whole Move body and
     * scan only on opcode zero (XOrc 11 1511: west at fixture 5, Attack at
     * 58). Destroyers keep leftover-settle autoAttack -- applying this
     * cursor to 1542 would drop the fifteen-tick hold at fixture 40.
     */
    private boolean battleNetStandingPatrolSequence(Unit unit) {
        return battleNetSequence != null
                && unit != null
                && unit.type() != null
                && unit.battleNetDoubleStep()
                && unit.type().seaUnit()
                && isBattleNetCapitalShip(unit.type().ident())
                && unit.battleNetSequenceOffset() >= 0;
    }

    /**
     * Advances the Patrol sequence. Returns whether this visit is opcode
     * zero, which is the only visit that may scan or take a capital-ship
     * stride.
     */
    private boolean tickBattleNetPatrolSequence(Unit unit) {
        if (!battleNetStandingPatrolSequence(unit)) {
            return false;
        }
        BattleNetSequence.Tick tick = battleNetSequence.tick(
                unit.battleNetSequenceOffset(), unit.battleNetAnimationTimer());
        if (!tick.valid()) {
            return false;
        }
        unit.setBattleNetSequenceOffset(tick.offset());
        unit.setBattleNetAnimationTimer(tick.timer());
        return tick.actionMarker();
    }

    /**
     * Hostile scan at a standing-patrol opcode zero.
     *
     * <p>This is order 12, not the 15-cycle AttackMove queue. Native 1511
     * opens Attack at fixture 58 on the same visit the Move body returns
     * to OP0, still on 18,40, once the orc destroyer at 10,42 is inside
     * computer react 8. The first OP0 is still on 20,40 (dist 10) and
     * must miss so fixture 5 stays Patrol.
     */
    private boolean battleNetPatrolAcquire(Unit unit) {
        if (unit.order() != Unit.Order.PATROL
                || unit.type() == null
                || !unit.type().canAttack()
                || !unit.isAggressive()
                || unit.isDying()
                || !unit.isOnMap()
                || isBattleNetArmedTower(unit)
                || cycle <= 1) {
            return false;
        }
        int range = unit.type().reactRange(isPerson(unit.player()));
        if (range <= 0) {
            return false;
        }
        Unit target = targets.findBattleNetHostile(unit, range,
                unit.offeredTarget());
        return target != null && orderAttack(unit, target, false);
    }

    /**
     * Switches a just-stepped capital-ship Patrol onto the Move body past
     * the opening opcode zero, matching native 1511 sequence 2963 timer 1
     * after the west stride at fixture 5.
     */
    private void armBattleNetPatrolMoveBody(Unit unit) {
        if (battleNetSequence == null || unit == null || unit.type() == null) {
            return;
        }
        int moveStart = idle.battleNetSequenceStart(unit,
                BattleNetSequence.MOVE_ANIMATION);
        int attackStart = idle.battleNetSequenceStart(unit,
                BattleNetSequence.ATTACK_ANIMATION);
        int cursor = unit.battleNetSequenceOffset();
        if (moveStart >= 0 && cursor >= moveStart
                && (attackStart < 0 || cursor < attackStart)) {
            return;
        }
        if (moveStart < 0) {
            return;
        }
        BattleNetSequence.Tick open = battleNetSequence.tick(moveStart, 1);
        if (!open.valid()) {
            return;
        }
        unit.setBattleNetSequenceOffset(open.offset());
        unit.setBattleNetAnimationTimer(open.timer());
    }

    /**
     * Sends a unit off to reveal the map.
     *
     * <p>{@code CommandExplore} and {@code COrder::NewActionExplore}
     * The game the destination is drawn at
     * the order's creation, off the shared stream, before the first cycle of
     * it runs.
     */
    public boolean orderExplore(Unit unit) {
        if (unit == null || !unit.isAlive() || unit.type().speed() <= 0) {
            return false;
        }
        unit.clearPath();
        int[] target = explorationTarget(unit);
        unit.setOrderTarget(target[0], target[1]);
        unit.setMoveRange(0);
        unit.setExploreWaitingCycle(0);
        unit.setOrder(Unit.Order.EXPLORE);
        return true;
    }

    /**
     * Somewhere to go looking, drawn from the shared stream.
     *
     * <p>{@code GetExplorationTarget}: two
     * draws for a square, unconditionally, and up to three redraws hunting
     * for one the player has not explored -- taking the last pair whatever
     * it landed on. Both coordinates run 1 to size-1, never 0. Upstream
     * skips the redraws when the map has fog of war off; this implementation has no
     * map-level fog kill switch to read, and every scenario the harness
     * traces runs with fog on, so the three tries are unconditional here and
     * the difference cannot yet be observed.
     */
    private int[] explorationTarget(Unit unit) {
        return explorationTarget(unit, false);
    }

    private int[] explorationTarget(Unit unit, boolean loadTime) {
        if (RAND_TRACE_PATH != null) {
            randContext = "explore:" + unit.id();
        }
        int triesLeft = 3;
        int x = exploreRand(loadTime, map.width() - 1) + 1;
        int y = exploreRand(loadTime, map.height() - 1) + 1;
        while (triesLeft > 0) {
            if (!fog.isExplored(unit.player(), x, y)) {
                return new int[] {x, y};
            }
            x = exploreRand(loadTime, map.width() - 1) + 1;
            y = exploreRand(loadTime, map.height() - 1) + 1;
            --triesLeft;
        }
        return new int[] {x, y};
    }

    /**
     * {@code SyncRand(max)} off whichever sequence the moment belongs to:
     * upstream has one stream and a reseed, this implementation models the discarded
     * pre-reseed sequence as its own generator, and a target drawn mid-load
     * has to come from that one.
     */
    private int exploreRand(boolean loadTime, int bound) {
        if (bound <= 0) {
            return 0;
        }
        return loadTime ? Math.floorMod(loadRand(), bound) : syncRand(bound);
    }

    /**
     * Sends a laden worker home.
     *
     * <p>Queued like every other command, so the worker is counted as doing
     * what it was doing until the order is popped on the next cycle -- the
     * same shim {@link #orderHarvest} carries, and for the same reason: this
     * is issued from outside the worker's own step, by the AI's census or a
     * player's click. On campaigns/human-exp/levelx03h the AI's think at
     * cycle 247 sends a stranded peon's hundred gold home; upstream's state
     * at the end of 247 still reads Still, and this implementation's read the new
     * order a cycle early.
     */
    public boolean orderReturnGoods(Unit unit) {
        return orderReturnGoods(unit, false);
    }

    /**
     * @param fromPlayer {@code true} for a GiveOrder click: Still pays the
     *     player command wait before the hall walk. Skipping it leftover-
     *     landed Orc 1 at 53 instead of 56.
     */
    public boolean orderReturnGoods(Unit unit, boolean fromPlayer) {
        if (unit == null || !unit.isAlive()) {
            return false;
        }
        UnitType.Resource cargo = unit.heldResource() != null
                ? unit.heldResource() : unit.carrying();
        // NewActionReturnGoods at 0x00436ac0 installs Still when FindDeposit
        // answers none. It does not refuse an empty hand: dest 0,0 still
        // names the nearest reachable gold depot and the hull walks there.
        // A local unit with no friendly depot stays Still. Used to return
        // false on empty cargo, which made a send-home look rejected while
        // native was already on the hall walk.
        UnitType.Resource depotResource = cargo != null
                ? cargo : UnitType.Resource.GOLD;
        Unit depot = harvest.bestDepotByTravel(unit, depotResource, 1000);
        if (depot == null) {
            unit.setOrder(Unit.Order.STILL);
            return true;
        }
        if (cargo == null) {
            cargo = depotResource;
        }
        Unit.Order before = unit.order();
        // NewActionReturnGoods copies CUnit::CurrentResource, not the
        // resource named by the order being replaced. Those values differ
        // while a laden chopper has been redirected to a mine but has not
        // reached its door: COrder_Resource::CurrentResource is gold, while
        // CUnit::CurrentResource and the 28 held units are still wood. If a
        // build interrupts that walk, the later send-home must bank wood.
        // levelx04o keeps exactly that load from cycle 1838 through the long
        // build ending at 2507 and delivers it at 2714.
        unit.setCarrying(cargo);
        // Native GiveOrder 24 from Still on a no-gather actor writes
        // next_order 24 and keeps Still: Orc 1 grunt 1592 timer 4 through
        // fixture 8, Return-Goods at 9, inside at 79. Installing the walk
        // on the issue cycle first-progressed at 5 and never left 18,23.
        if (fromPlayer && before == Unit.Order.STILL && !unit.type().canGather()) {
            int[] waits = movement.playerCommandWaits(unit);
            int stillWait = waits[1] > 0 ? waits[1] : waits[0];
            unit.setOrderTarget(depot.tileX(), depot.tileY());
            unit.enqueueOrder(new Unit.QueuedOrder(
                    Unit.QueuedOrderKind.RETURN_GOODS,
                    depot.tileX(), depot.tileY(), depot, null, null));
            unit.setQueuedReplacementPending(true);
            unit.setBattleNetOrderDelay(stillWait + 1);
            return true;
        }
        unit.clearPath();
        unit.setOrder(Unit.Order.RETURN_GOODS);
        // Native pops 24 at fixture 9 with timer 3, dest-arms at 12.
        // The pop visit already spent one beat, so delay 2 dest-arms at 12.
        if (!fromPlayer && before == Unit.Order.STILL && !unit.type().canGather()) {
            unit.setBattleNetOrderDelay(2);
        }
        // A send-home is a fresh COrder_Resource -- NewActionReturnGoods,
        // so its wait ladder starts at nought
        // and its Resource union is value-initialized. In particular it does
        // not inherit either the old mine pointer or the old terrain Pos.
        unit.setResourceUnit(null);
        unit.setResourceTile(-1, -1);
        unit.setResourceWaitLadder(0);
        unit.rememberActionBeforeQueued(before);
        // Harvest used to skip this wait and walk into the mine three
        // cycles early. Empty send-home from Still has the same start:
        // native leftover-lands 26,21 at 56, Java without it at 53.
        if (fromPlayer && before == Unit.Order.STILL) {
            unit.setBattleNetOrderDelay(movement.playerCommandDelay(unit));
        }
        return true;
    }

    /**
     * Points a weapon at a square.
     *
     * <p>{@code COrder::NewActionAttack} accepts a tile as well as a unit.
     * That distinction is what lets a footman attack a wall: walls are map
     * terrain, so there is no unit for {@link #orderAttack} to name.
     *
     * <p>The button table still hides Attack Ground on units that cannot
     * bombard. The synchronized GiveOrder path does not: commanded BNE
     * fixtures {@code attack-ground-1/02} (Orc 1 peon 1594 at 30,18) and
     * {@code attack-ground-1/03} (grunt 1592 at 22,23) both install order
     * 17. Java used to refuse those packets -- no CanAttack, or melee on
     * grass -- so the explorer recorded rejected where native walked and
     * held ATTACK_GROUND. Buildings stay refused; they are not a GiveOrder
     * 17 actor.
     */
    public boolean orderAttackGround(Unit unit, int toX, int toY) {
        return orderAttackGround(unit, toX, toY, false);
    }

    /**
     * @param fromPlayer {@code true} for a GiveOrder click: Still keeps the
     *     current order and writes next_order 17 for the remaining Still wait
     */
    public boolean orderAttackGround(Unit unit, int toX, int toY, boolean fromPlayer) {
        if (unit == null || !unit.isAlive() || unit.type().building()
                || !map.contains(toX, toY)) {
            return false;
        }
        unit.setSavedOrder(null);
        if (unit.animation().unbreakable()) {
            // The same flush-on command boundary as unit and position
            // attacks: BNE finishes a committed shot/reload before promoting
            // the replacement. Immediate replacement reset script.bin while
            // the old Java attack animation kept running, which let repeated
            // clicks restart or overlap a siege volley.
            unit.clearQueuedOrders();
            unit.setPendingAttack(null, null, -1, -1);
            unit.enqueueOrder(new Unit.QueuedOrder(
                    Unit.QueuedOrderKind.ATTACK_GROUND,
                    toX, toY, null, null, null));
            unit.setQueuedReplacementPending(true);
            unit.rememberActionBeforeQueued(unit.order());
            return true;
        }
        projectiles.interruptPendingAttack(unit);
        construction.abandonPendingBuild(unit);
        // GiveOrder 17 on a melee unit out of range is a player Move to
        // the forest-projected dest. Native attack-ground-1/02 is order
        // 18 dest 28,18 at fixture 5; installing 17 walked due east to
        // 27,18 and never stood down.
        MissileType groundMissile = projectiles.missileFor(unit);
        boolean meleeGround = groundMissile == null || groundMissile.isNone();
        int range = Math.max(1, unit.type().maxAttackRange());
        int distance = Math.max(Math.abs(unit.tileX() - toX),
                Math.abs(unit.tileY() - toY));
        if (fromPlayer && meleeGround && distance > range
                && movement.leftoverLandedBesideForest(unit, toX, toY)) {
            int[] dest = movement.projectPlayerMovePoint(unit, toX, toY);
            boolean accepted = movement.orderCommandMove(unit, dest[0], dest[1]);
            if (accepted) {
                unit.setAttackGoal(toX, toY);
            }
            return accepted;
        }
        unit.clearPath();
        unit.setTarget(null);
        unit.setAttackGoal(toX, toY);
        unit.setOrderTarget(toX, toY);
        unit.setChasing(false);
        unit.setFighting(false);
        unit.setSwingAtAir(false);
        unit.setAutoTargeting(false);
        // Native GiveOrder 17 from Still writes next_order and keeps Still
        // for the remaining Still wait: Human 7 catapult 1519 wait 4 through
        // fixture 8, AttackGround at 9; Orc 8 1576 wait 3, AttackGround at 8.
        // Installing the label on the issue cycle made first progress 5.
        // The wait is the Still program's remaining quiet ticks, or the
        // Still restart when that program is already on its marker -- not
        // Move's extra three-visit action start.
        if (fromPlayer && unit.order() == Unit.Order.STILL) {
            int[] waits = movement.playerCommandWaits(unit);
            int stillWait = waits[1] > 0 ? waits[1] : waits[0];
            unit.enqueueOrder(new Unit.QueuedOrder(
                    Unit.QueuedOrderKind.ATTACK_GROUND,
                    toX, toY, null, null, null));
            unit.setQueuedReplacementPending(true);
            // The issue visit still decrements this delay, so add the beat
            // native spends writing next_order instead of counting down.
            unit.setBattleNetOrderDelay(stillWait + 1);
            return true;
        }
        unit.setOrder(Unit.Order.ATTACK_GROUND);
        return true;
    }

    /**
     * How long a march waits after widening its range.
     *
     * <p>{@code unit.Wait = 5},
     * which is the whole of what upstream does about an unreachable
     * destination besides widening.
     */
    static final int MARCH_WIDEN_WAIT = 5;

    /**
     * The eight ways a blocker can be shoved, in upstream's own order.
     *
     * <p>{@code static Vec2i dirs[8]}, which is not
     * the heading order the rest of the engine uses: it runs up the left
     * column, along the bottom and back up the right.
     */
    static final int[][] SHOVE_DIRS = {
        {-1, -1}, {-1, 0}, {-1, 1}, {0, 1}, {1, 1}, {1, 0}, {1, -1}, {0, -1},
    };

    /** How many blockers one shove considers before giving up looking. */
    static final int SHOVE_CANDIDATES = 16;

    /** How long a player waits between shoves. */
    static final int SHOVE_INTERVAL = 10;

    /**
     * How many waits a harvest walk answers before it buys a shove.
     *
     * <p>The {@code this->Range >= 5} threshold shared by the {@code PF_WAIT}
     * arms of {@code COrder_Resource}'s three walks
     *
     */
    static final int RESOURCE_WAIT_SHOVE = 5;

    /**
     * A computer player's answer to one of its units being unable to move.
     *
     * <p>{@code DoActionMove} calls {@code AiCanNotMove} on PF_UNREACHABLE for
     * any unit belonging to a player with an AI, and that asks {@code PlaceReachable} whether the destination
     * is reachable at all -- at a range of 255, so the answer is yes unless
     * the ground itself is cut off -- and takes the yes to mean "path probably
     * closed by unit here".
     *
     * <p>It costs numbers from the shared stream whether or not anybody moves,
     * which is why it cannot be left out of a port that wants the same
     * simulation. On {@code maps/demo/demo03} a grunt at 11,1 marching on 13,3
     * finds a friendly axethrower standing on it: upstream draws two numbers
     * shoving somebody, and this implementation drew none.
     */
    void aiCanNotMove(Unit unit, int goalX, int goalY, int goalWidth, int goalHeight) {
        if (!ais.containsKey(unit.player())) {
            return;
        }
        // PlaceReachable(unit, goalPos, gw, gh, 0, 255, false). A range of 255
        // means the unit is almost always inside the goal already, so this is
        // in practice "is that ground connected to anything at all" -- but it
        // is asked, and it is asked before the draws.
        PathFinder.Path reach = pathFinder.find(unit.tileX(), unit.tileY(),
                new PathFinder.Goal(goalX, goalY, Math.max(1, goalWidth),
                        Math.max(1, goalHeight), 0, 255),
                moverFor(unit));
        if (reach.result() == PathFinder.Result.UNREACHABLE) {
            return;
        }
        movement.moveUnitInTheWay(unit);
    }

    /**
     * Whether AutoSelectTarget may leave the unit's pathfinder output alone.
     *
     * <p>An in-range choice never calls DoActionMove in this execute, so
     * upstream does not update the unit-level PathFinderInput and the old
     * output survives behind the fight. An out-of-range choice normally does
     * update the input and recalculates, except when that input is already
     * aimed at the chosen unit: then its empty output is still current and
     * NextPathElement answers PF_WAIT. levelx12h's restored grunt has exactly
     * that state at cycle 346. Matching only on "there is output" preserved
     * every opening order's surrogate empty route and diverged at cycle 23;
     * matching the cached input's goal is the missing discriminator.
     */
    boolean preserveScannedRoute(Unit unit, Unit candidate,
            boolean scanOwnsUnitOutput) {
        if (!scanOwnsUnitOutput) {
            return false;
        }
        if (targets.inAttackRange(unit, candidate)) {
            return true;
        }
        boolean hasOutput = unit.isMoving() || unit.pathLength() > 0
                || unit.routeSpent();
        return hasOutput
                && unit.pathGoalX() == candidate.tileX()
                && unit.pathGoalY() == candidate.tileY();
    }

    /** Whether this is one of retail BNE's four armed tower types. */
    static boolean isBattleNetArmedTower(Unit unit) {
        int type = unit == null || unit.type() == null
                ? -1 : PudUnitTypes.code(unit.type().ident());
        return type >= 96 && type <= 99;
    }

    /**
     * Buildings that run native action 33 Still and its train counter.
     *
     * <p>Great-hall / stronghold / fortress auto-train peons on the hall
     * constructor cadence. Human town-halls also run action 33: XOrc 4 p2 and
     * XOrc 5 p3 debit a peasant (400 gold) at fixture cycle 13. Barracks
     * auto-train footman/grunt under the same action with a lower counter
     * limit. Human 13 barracks place with PUD data 0 and never debit; XHuman
     * 2 / XOrc 11 place with data 1 and debit 600 at the third OP0. XHuman 8
     * p3 also has data 1 but native train_fn refuses the spend -- gated
     * inside {@link AiPlayer#battleNetTryTrainSoldier}.
     */
    boolean battleNetBuildingCanAction33Train(Unit building) {
        if (building == null || building.type() == null || !building.type().building()) {
            return false;
        }
        String ident = building.type().ident();
        return "unit-town-hall".equals(ident)
                || "unit-keep".equals(ident) || "unit-castle".equals(ident)
                || "unit-great-hall".equals(ident) || "unit-stronghold".equals(ident)
                || "unit-fortress".equals(ident)
                || "unit-human-barracks".equals(ident)
                || "unit-orc-barracks".equals(ident)
                || "unit-human-shipyard".equals(ident)
                || "unit-orc-shipyard".equals(ident)
                || "unit-human-blacksmith".equals(ident)
                || "unit-orc-blacksmith".equals(ident)
                || "unit-elven-lumber-mill".equals(ident)
                || "unit-troll-lumber-mill".equals(ident)
                || "unit-human-watch-tower".equals(ident)
                || "unit-orc-watch-tower".equals(ident)
                || "unit-gryphon-aviary".equals(ident)
                || "unit-dragon-roost".equals(ident)
                || "unit-human-foundry".equals(ident)
                || "unit-orc-foundry".equals(ident)
                || "unit-temple-of-the-damned".equals(ident)
                || "unit-mage-tower".equals(ident);
    }

    /** Whether this action-33 building is a barracks (not a hall). */
    static boolean battleNetIsBarracks(Unit building) {
        if (building == null || building.type() == null) {
            return false;
        }
        String ident = building.type().ident();
        return "unit-human-barracks".equals(ident) || "unit-orc-barracks".equals(ident);
    }

    /** Whether this action-33 building is a shipyard. */
    static boolean battleNetIsShipyard(Unit building) {
        if (building == null || building.type() == null) {
            return false;
        }
        String ident = building.type().ident();
        return "unit-human-shipyard".equals(ident) || "unit-orc-shipyard".equals(ident);
    }

    /** True when another same-owner roost already has a flyer in production. */
    boolean battleNetSiblingRoostProducingFlyer(Unit roost) {
        for (Unit candidate : units) {
            if (candidate == roost || candidate.player() != roost.player()
                    || !battleNetIsFlyerRoost(candidate)
                    || candidate.producing() == null) {
                continue;
            }
            String prod = candidate.producing().ident();
            if ("unit-dragon".equals(prod) || "unit-gryphon-rider".equals(prod)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this action-33 building is a blacksmith. */
    static boolean battleNetIsBlacksmith(Unit building) {
        if (building == null || building.type() == null) {
            return false;
        }
        String ident = building.type().ident();
        return "unit-human-blacksmith".equals(ident)
                || "unit-orc-blacksmith".equals(ident);
    }

    /**
     * Human town-hall. Sealed XOrc 4/5 computer openings debit a peasant at
     * the third action-33 OP0 (fixture c3/c8/c13), same WAIT-4 cadence as a
     * blacksmith rather than the great-hall constructor pair.
     */
    static boolean battleNetIsTownHall(Unit building) {
        if (building == null || building.type() == null) {
            return false;
        }
        String ident = building.type().ident();
        // Human hall line peon trains (Orc 13 p3 castle debits a peasant at
        // fixture c16). Great-hall line uses the separate constructor cadence.
        return "unit-town-hall".equals(ident)
                || "unit-keep".equals(ident)
                || "unit-castle".equals(ident);
    }

    static boolean battleNetIsLumberMill(Unit building) {
        if (building == null || building.type() == null) {
            return false;
        }
        String ident = building.type().ident();
        return "unit-elven-lumber-mill".equals(ident)
                || "unit-troll-lumber-mill".equals(ident);
    }

    static boolean battleNetIsWatchTower(Unit building) {
        if (building == null || building.type() == null) {
            return false;
        }
        String ident = building.type().ident();
        return "unit-human-watch-tower".equals(ident)
                || "unit-orc-watch-tower".equals(ident);
    }

    static boolean battleNetIsFlyerRoost(Unit building) {
        if (building == null || building.type() == null) {
            return false;
        }
        String ident = building.type().ident();
        return "unit-gryphon-aviary".equals(ident)
                || "unit-dragon-roost".equals(ident);
    }

    /**
     * Barracks/shipyard/blacksmith/town-hall: WAIT-4 cadence without the
     * great-hall constructor pair. Blacksmith OP0s land c3/c8/c13 (xh11) or
     * c5/c10/c15 (xh10) depending on freeze; town-hall peasant trains on the
     * c3/c8/c13 series for XOrc 4/5.
     */
    static boolean battleNetIsFoundry(Unit building) {
        if (building == null || building.type() == null) {
            return false;
        }
        String ident = building.type().ident();
        return "unit-human-foundry".equals(ident)
                || "unit-orc-foundry".equals(ident);
    }

    static boolean battleNetIsTemple(Unit building) {
        if (building == null || building.type() == null) {
            return false;
        }
        String ident = building.type().ident();
        return "unit-temple-of-the-damned".equals(ident)
                || "unit-mage-tower".equals(ident);
    }

    static boolean battleNetIsLimit1Trainer(Unit building) {
        return battleNetIsBarracks(building) || battleNetIsShipyard(building)
                || battleNetIsBlacksmith(building)
                || battleNetIsTownHall(building)
                || battleNetIsLumberMill(building)
                || battleNetIsWatchTower(building)
                || battleNetIsFlyerRoost(building)
                || battleNetIsFoundry(building)
                || battleNetIsTemple(building);
    }

    /**
     * Debits the synchronized attack-variant draw for table-0x27 melee types
     * ({@code FUN_004234b0}) and arms the twenty-five-cycle re-seed loop.
     *
     * <p>Native runs this on the first in-range action callback and again on
     * every later attack animation loop entry. The pending flag is set at
     * order time for the first debit; the loop arm covers subsequent swings.
     */
    void consumeBattleNetPendingMeleeSyncRand(Unit unit) {
        if (!unit.battleNetPendingMeleeSyncRand()) {
            return;
        }
        Unit target = unit.target();
        // Dying and removed quarries are not live in-range work for
        // FUN_004234b0. XHuman 10 grunt 105 residual-settled beside footman
        // 108 after that footman was already DYING and paid table-0x27 while
        // native only ever drew for the two live footmen (c41/c44). Live
        // residual settles (Human 13 F36 wise-man+grunt) still debit below.
        if (target == null || !target.isAlive() || unit.isMoving()
                || !targets.inAttackRange(unit, target)) {
            return;
        }
        // Human 13 grunt 1485/115: after retarget onto adjacent wise-man at
        // fixture 25 native keeps a one-step route residual (index=1) through
        // fixture 43 and only spends FUN_004234b0 at F43. Java residual-
        // drained that leftover and paid SyncRand at world 43 (fixture 41),
        // two cycles early. Leave a single leftover heading unpaid until a
        // later visit clears it (native index→20 at F44). pathLen 0 and ≥2
        // residual settles keep the ordinary debit (F36 wise-man+grunt pair).
        if (actionMoveWalked && unit.chasing() && unit.pathLength() == 1) {
            if (BNE_PEND_TRACE) {
                System.err.printf("JBNEMELEESYNC event=defer-path1 cycle=%d "
                                + "unit=%d target=%d pathLen=1%n",
                        cycle, unit.id(), target.id());
            }
            return;
        }
        // Cavalry hit-response that just residual-settled this visit: open
        // Attack without FUN_004234b0. Human 13 knight 1500 settles at
        // fixture 37 with timer 3 and no SyncRand; debiting on settle was
        // the extra f37 draw after the wise-man+grunt f36 pair. Gate on
        // actionMoveWalked so standing fighters (catapult first-arm) keep
        // the ordinary settle debit.
        int typeCode = unit.type() == null ? -1
                : PudUnitTypes.code(unit.type().ident());
        boolean residualCavalryHit = actionMoveWalked
                && unit.offeredTarget() != null
                && (typeCode == 6 || typeCode == 7);
        if (!residualCavalryHit) {
            unit.setBattleNetPendingMeleeSyncRand(false);
            if (BNE_PEND_TRACE) {
                System.err.printf("JBNEMELEESYNC event=consume-first cycle=%d "
                                + "unit=%d type=%s order=%s seq=%d timer=%d "
                                + "target=%d ttype=%s at=%d,%d "
                                + "chasing=%d moving=%d walked=%d "
                                + "emptyReplan=%d pathLen=%d%n",
                        cycle, unit.id(),
                        unit.type() == null ? "?" : unit.type().ident(),
                        unit.order() == null ? "?" : unit.order().name(),
                        unit.battleNetSequenceOffset(),
                        unit.battleNetAnimationTimer(),
                        target.id(),
                        target.type() == null ? "?" : target.type().ident(),
                        target.tileX(), target.tileY(),
                        unit.chasing() ? 1 : 0, unit.isMoving() ? 1 : 0,
                        actionMoveWalked ? 1 : 0,
                        unit.battleNetChaseEmptyRouteReplan() ? 1 : 0,
                        unit.pathLength());
            }
            debitBattleNetMeleeSyncRand(unit);
        }
        // First in-range after a chase step must open the Attack program.
        if (battleNetSequence != null) {
            int attackStart = idle.battleNetSequenceStart(unit,
                    BattleNetSequence.ATTACK_ANIMATION);
            if (attackStart >= 0) {
                if (unit.battleNetSequenceOffset() > attackStart) {
                    return;
                }
                unit.setBattleNetSequenceOffset(attackStart);
                unit.setBattleNetAnimationTimer(3);
            }
        }
    }

    /**
     * Opens Attack after discarding a multi-step chase leftover into range.
     *
     * <p>Native Human 13 ogre 1510 residual-settles onto Attack offset 644
     * (post-OP0) at fixture 30 and lands opcode 10 at 37. Cold attackStart
     * timer 3 rewound that wind-up so the first melee waited until fixture 40.
     */
    void openBattleNetAttackAfterChaseResidual(Unit unit) {
        openBattleNetAttackAfterChaseResidual(unit, true);
    }

    /**
     * Opens Attack past OP0 after a chase residual settles in range.
     *
     * @param markMeleeLeftover when {@code true}, OP10 may land melee without a
     *                          presentation pend (Human 13 ogre 1510). Ranged
     *                          approach residual must pass {@code false}: the
     *                          melee mark made XHuman 12 axe 127 apply tower
     *                          damage on the OP10 cycle instead of after axe
     *                          flight (fixture 45).
     */
    void openBattleNetAttackAfterChaseResidual(Unit unit,
            boolean markMeleeLeftover) {
        if (battleNetSequence == null) {
            return;
        }
        int attackStart = idle.battleNetSequenceStart(unit,
                BattleNetSequence.ATTACK_ANIMATION);
        if (attackStart < 0) {
            return;
        }
        BattleNetSequence.Tick open = battleNetSequence.tick(attackStart, 1);
        if (open.valid()) {
            unit.setBattleNetSequenceOffset(open.offset());
            unit.setBattleNetAnimationTimer(open.timer());
        } else {
            unit.setBattleNetSequenceOffset(attackStart);
            unit.setBattleNetAnimationTimer(1);
        }
        if (markMeleeLeftover) {
            unit.setBattleNetMultiLeftoverMelee(true);
        } else {
            unit.setBattleNetRangedResidualOpen(true);
        }
    }

    /**
     * One SyncRand into the attack-variant stream and arm the next loop.
     *
     * <p>Implements {@code FUN_004234b0} plus the twenty-five-cycle Attack
     * sequence period sealed on Human 5 (standing 1531 at 6/31, chasing
     * 1528/1532 at 22/47).
     */
    void debitBattleNetMeleeSyncRand(Unit unit) {
        if (BNE_PEND_TRACE) {
            Unit target = unit.target();
            System.err.printf("JBNEMELEESYNC event=debit cycle=%d unit=%d "
                            + "type=%s order=%s seq=%d timer=%d remaining=%d "
                            + "target=%d ttype=%s seedBefore=%08x caller=%s%n",
                    cycle, unit.id(),
                    unit.type() == null ? "?" : unit.type().ident(),
                    unit.order() == null ? "?" : unit.order().name(),
                    unit.battleNetSequenceOffset(),
                    unit.battleNetAnimationTimer(),
                    unit.battleNetMeleeSyncRemaining(),
                    target == null ? -1 : target.id(),
                    target == null || target.type() == null
                            ? "?" : target.type().ident(),
                    randomSeed,
                    causalCaller());
        }
        syncRand();
        unit.setBattleNetMeleeSyncRemaining(25);
    }

    /**
     * Advances the table-0x27 attack-loop SyncRand arm while a unit is on its
     * Attack sequence. When the twenty-five-cycle period expires and the unit
     * is still on a live in-range target, re-seeds and re-arms.
     */
    void tickBattleNetMeleeSyncLoop(Unit unit) {
        int left = unit.battleNetMeleeSyncRemaining();
        if (left <= 0) {
            return;
        }
        left--;
        if (left == 0) {
            Unit target = unit.target();
            if (target != null && target.isAlive() && !unit.isMoving()
                    && targets.inAttackRange(unit, target)
                    && battleNetMeleeSyncRandType(unit)) {
                debitBattleNetMeleeSyncRand(unit);
                return;
            }
            // Target gone or out of range: drop the arm; a later re-entry
            // through pending-first-in-range will re-seed.
            unit.setBattleNetMeleeSyncRemaining(0);
            return;
        }
        unit.setBattleNetMeleeSyncRemaining(left);
    }

    /**
     * Lets Still neighbours scan after a unit steps onto a new tile.
     *
     * <p>Retail walks the fixed pool low-slot to high, so a mover is often
     * visited before the idle defender that will open on it. Java visits
     * reverse creation order, and a Still marker that already fired this
     * cycle cannot see the arrival until the next five-cycle beat. XHuman
     * 10's person archer at 84,94 must open the cycle the grunt first
     * stands at 80,91 -- otherwise the type-15 arrow is still in the air
     * at fixture 52, where retail has already stored the first HP drop.
     */
    void battleNetIdleAcquireAround(Unit mover) {
        if (mover == null || !mover.isAlive() || !mover.isOnMap() || cycle <= 1) {
            return;
        }
        List<Unit> pool = snapshot != null ? snapshot : units;
        for (Unit other : pool) {
            if (other == mover || other.order() != Unit.Order.STILL
                    || other.type() == null || !other.type().canAttack()
                    || !other.isAggressive() || other.isDying()
                    || !other.isOnMap()
                    || isBattleNetArmedTower(other)
                    || !isEnemyPlayer(other.player(), mover.player())) {
                continue;
            }
            int band = Math.max(1,
                    other.type().reactRange(isPerson(other.player())));
            if (Math.max(Math.abs(other.tileX() - mover.tileX()),
                    Math.abs(other.tileY() - mover.tileY())) > band) {
                continue;
            }
            battleNetAutoAttack(other);
        }
    }

    /**
     * Runs BNE's hostile-unit scan at a movable unit's native action marker.
     *
     * <p>{@code HandleEachCycle} calls {@code FUN_0040a830} after the idle
     * animation dispatcher.  A winner is handed directly to order 12, with
     * the target pointer and its current square retained; it is not ChonkCraft's
     * weak position attack queued behind the Still order.  This distinction
     * is already observable in the two unrecorded startup calls on Human 13:
     * only the five troops whose constructor timers expire have acquired the
     * nearby human formation at corpus cycle one, while their neighbours
     * remain idle until their own markers arrive.</p>
     */
    void battleNetAutoAttack(Unit unit) {
        // UNIT.Data / battleNetReadySuppressed blocks the ready-pass patrol
        // assignment. Person surface troops with Data still run the idle
        // hostile scan, but only when a candidate is already inside weapon
        // range: XHuman 12 archer 1450 opens action 16 on the footman at
        // 24,60 (range 4) while remaining at 28,59. Scanning the full person
        // reaction range made XHuman 4's ballista Attack at fixture cycle 1
        // while native stayed Still until cycle 15. Permanent-cloak combat
        // (XHuman 7 sub) keeps the ordinary reaction-range scan.
        if (unit.order() != Unit.Order.STILL || !unit.type().canAttack()
                || !unit.isAggressive()
                || unit.isDying() || !unit.isOnMap()
                || isBattleNetArmedTower(unit)) {
            // Armed towers acquire only through action 14. A neighbour's
            // step used to call this scanner and orderAttack, which parked
            // the emplacement on the presentation Attack wait-59 and pushed
            // XHuman 2's second volley past fixture 82.
            return;
        }
        boolean person = isPerson(unit.player());
        boolean dataGuard = unit.battleNetReadySuppressed() && person
                && !unit.type().permanentCloak();
        // The first of CreateGame's two warm-up calls happens before BNE has
        // enabled hostile acquisition. Constructor timers still advance, so
        // a unit whose first marker lands there has to wait for its next
        // five-call Still marker. Human 13's three timer-one troops acquire
        // on recorded cycle four; scanning here makes them move before cycle
        // one while the timer-two and timer-three troops are correctly hot.
        if (cycle <= 1) {
            return;
        }
        int range = unit.type().reactRange(person);
        if (range <= 0) {
            return;
        }
        if (dataGuard) {
            // Weapon-range only for Data-marked person surface troops.
            range = Math.max(1, unit.type().maxAttackRange());
        }
        Unit offered = unit.offeredTarget();
        Unit target = targets.findBattleNetHostile(unit, range, offered);
        if (target != null) {
            if (dataGuard && target != offered
                    && !targets.inAttackRange(unit, target)) {
                return;
            }
            if (orderAttack(unit, target, false)) {
                // Action 16 (stationary) for person idle scans and for any
                // auto-scan onto air (Human 9 destroyers vs balloon). Computer
                // land-vs-land idle acquisition is action 12 and chases
                // (XHuman 12 grunts 1441/1495 step under order 12 from c1).
                // A person's offered hit-response is action 12 (Human 13
                // knight 1500 after axethrower 1506's c20 hit).
                if (person && target != offered
                        || (target.type().airUnit()
                        && !unit.type().airUnit())) {
                    unit.setBattleNetStationaryAttack(true);
                }
                if (BNE_IDLE_TRACE) {
                    System.err.printf("JBNEAUTO cycle=%d unit=%d target=%d"
                                    + " type=%s at=%d,%d range=%d%n",
                            cycle, unit.id(), target.id(), target.type().ident(),
                            target.tileX(), target.tileY(), range);
                }
                // Face the acquisition goal for the delay window so an
                // equal-cost free-scan approach can keep that heading (Human
                // 13 knight 1500 holds face 7 toward axe 118,24, then NW onto
                // 119,25 after free-scanning the ogre at 120,24).
                unit.setHeading(movement.headingTowards(unit, target));
                // Keep the attack order visible for the delay window before
                // the first in-range swing or (action-16) Still drop.
                unit.setBattleNetOrderDelay(2);
            }
        }
    }

    /** The tile Y this search compares, against a list ordered by pixel Y. */
    int bandTileY(int index) {
        return battleNetSpatialUnits.get(index).tileY();
    }

    /**
     * BNE {@code 0x416b10}'s distance from an attacker to a target footprint.
     *
     * <p>The retail client does not use ChonkCraft's Euclidean footprint gap.
     * Buildings (native type flag {@code 0x20}) select the point on their
     * footprint nearest the attacker; movable units use their top-left map
     * coordinate even when ChonkCraft gives the corresponding sprite a larger
     * tile footprint. The larger axis delta is then the distance. The two
     * distinctions are both observable: an axethrower four diagonal tiles
     * from a guard tower considers that tower in range, while a 2x2 ChonkCraft
     * balloon is still a point target to BNE.</p>
     */
    int battleNetDistance(Unit attacker, Unit candidate) {
        int targetWidth = candidate.type().building()
                ? Math.max(1, candidate.type().tileWidth()) : 1;
        int targetHeight = candidate.type().building()
                ? Math.max(1, candidate.type().tileHeight()) : 1;
        int targetX = battleNetNearFootprintCoordinate(
                attacker.tileX(), candidate.tileX(), targetWidth);
        int targetY = battleNetNearFootprintCoordinate(
                attacker.tileY(), candidate.tileY(), targetHeight);
        return Math.max(Math.abs(targetX - attacker.tileX()),
                Math.abs(targetY - attacker.tileY()));
    }

    /** Reproduces one axis of BNE {@code 0x416b10}'s footprint adjustment. */
    static int battleNetNearFootprintCoordinate(int attacker,
            int target, int size) {
        if (target >= attacker) {
            return target;
        }
        int half = size >> 1;
        int center = target + half;
        if (center + half <= attacker) {
            return center + half - ((~size) & 1);
        }
        return center;
    }

    /** Runs the per-unit BNE AI callback reached from the idle marker. */
    void battleNetUnitReady(Unit unit) {
        AiPlayer ai = ais.get(unit.player());
        boolean assigned = ai != null && ai.battleNetUnitReady(this, unit);
        if (BNE_IDLE_TRACE) {
            System.err.printf("JBNEREADY cycle=%d unit=%d player=%d ai=%d"
                            + " assigned=%d order=%s%n",
                    cycle, unit.id(), unit.player(), ai == null ? 0 : 1,
                    assigned ? 1 : 0, unit.order());
        }
        if (!assigned) {
            return;
        }
        // This callback runs from the unit's own current Still action. BNE
        // pops the order before the cycle is sampled, rather than retaining
        // the one-cycle reporting shim used by an external player command.
        unit.setActionBeforeQueued(null);
        // A doubled tanker already overlapping its platform does not enter
        // on the ready marker itself. Orc 14 tanker 1575 proves the actual
        // split: action 23 on fixtures 6..8, action 25 on 9..11, hidden
        // action 26 on 12.  Keeping those as one six-visit delay entered one
        // cycle late and erased the very state boundary this model exists to
        // preserve. A tanker which still has water to cross retains the
        // ordinary two-visit movement delay.
        boolean tankerAtPlatform = unit.resourceUnit() != null
                && unit.type().gathering().containsKey(UnitType.Resource.OIL)
                && harvest.battleNetOilTankerReachedApproach(
                        unit, unit.resourceUnit());
        if (tankerAtPlatform) {
            unit.setBattleNetOilAction(Unit.BattleNetOilAction.TO_RESOURCE);
            unit.setBattleNetOilActionTicks(3);
            unit.setBattleNetOrderDelay(0);
        } else {
            unit.setBattleNetOrderDelay(2);
        }
    }

    /** Promotes the ready-pass order when BNE releases the initial Still. */
    void beginBattleNetPendingPatrol(Unit unit) {
        if (!unit.hasBattleNetPendingPatrol()) {
            return;
        }
        int x = unit.battleNetPendingPatrolX();
        int y = unit.battleNetPendingPatrolY();
        boolean hasBack = unit.hasBattleNetPendingPatrolBack();
        int backX = unit.battleNetPendingPatrolBackX();
        int backY = unit.battleNetPendingPatrolBackY();
        unit.clearBattleNetPendingPatrol();
        // FUN_004513d0 / FUN_00438320 rewrites naval order goals before the
        // action constructor runs. A destroyer's shore-base top-left becomes
        // the last blocked cell on the ray from that square toward the ship
        // (XOrc 11 slot 1519: 21,34 → 22,36; XOrc 8 destroyer 1430: refinery
        // 87,71 → 88,73 on the south footprint edge). Without the rewrite the
        // pathfinder invents an approach path onto free water and steps NW
        // while native fails the rewritten footprint goal and surfaces Still,
        // or (for a far shore base) packs a pure-NW corridor that commits
        // 96,92→94,90 while native's 88,73 route interleaves pure N.
        // Capital ships keep their authored near/far endpoints: applying the
        // same rewrite to XOrc 11's battleship 1511 stole its pure-west detour
        // at cycle 5.
        // Distance is not a gate. A far shipyard goal whose first ray step is
        // already open water returns itself (XOrc 11 destroyer at 4,18 keeps
        // 21,34). The old Chebyshev-6 limit left XOrc 8's shore-base patrol
        // on the building top-left and diverged at fixture 34.
        if (unit.type().seaUnit()
                && !isBattleNetCapitalShip(unit.type().ident())
                && !battleNetNavalRewriteOpenWater(x, y)) {
            int[] rewritten = battleNetNavalOrderPoint(unit, x, y);
            x = rewritten[0];
            y = rewritten[1];
        }
        if (orderPatrol(unit, x, y)) {
            if (hasBack) {
                unit.setPatrol(backX, backY);
            }
            // The new Patrol is now current, but its walking action consumes
            // two calls before the first logical tile step. A one-stride
            // open-water wiggle (XOrc 10 destroyers with no oil service base)
            // is queued in the ready pass two init ticks before fixture
            // cycle 1; five delayed visits put the double-step on fixture
            // cycle 5, matching native's hold through cycles 1-4. Ordinary
            // base-targeted ships keep the two-call delay. A rewritten
            // building-footprint goal one stride away (XOrc 11 22,36) must
            // keep delay 2 so the empty route fails on fixture cycle 7, not
            // after the open-water wiggle hold.
            int stride = battleNetMovementStride(unit);
            int chebyshev = Math.max(Math.abs(x - unit.tileX()),
                    Math.abs(y - unit.tileY()));
            boolean openWaterWiggle = stride > 1 && chebyshev > 0
                    && chebyshev <= stride
                    && battleNetNavalRewriteOpenWater(x, y);
            unit.setBattleNetOrderDelay(openWaterWiggle ? 5 : 2);
            // FUN_00452ef0 gives the new Patrol three animation calls
            // before its first opcode zero. Native 1511 keeps Still 2955
            // with timer 3 on the promote visit (fixture 2) and steps at 5.
        }
    }

    /**
     * Open water for the naval action-5 goal rewrite -- not coast, not land,
     * not building. Terrain only; unit occupancy is ignored the same way
     * {@link #battleNetTransportRewriteOpenWater} ignores it. Using footprint
     * free checks here used to pin XOrc 11 destroyer 1519's rewritten goal on
     * its own occupied square and leave it stuck on a self/self Patrol.
     */
    private boolean battleNetNavalRewriteOpenWater(int x, int y) {
        if (!map.contains(x, y)) {
            return false;
        }
        long flags = map.field(x, y).flags();
        return (flags & TileFlag.WATER_ALLOWED) != 0
                && (flags & TileFlag.COAST_ALLOWED) == 0
                && (flags & TileFlag.LAND_ALLOWED) == 0
                && (flags & TileFlag.BUILDING) == 0
                && (flags & TileFlag.UNPASSABLE) == 0;
    }

    /**
     * Open-water snap for a coast or blocked naval order point.
     *
     * <p>Prefers a tile one double-step from the ship that is closer to the
     * coast goal (XHuman 07 submarine 1511: from 20,52 onto 18,52). Falls
     * back to a spiral around the coast goal when no single stride lands.
     */
    private int[] battleNetNearestNavalOpenWater(Unit ship, int goalX, int goalY) {
        if (battleNetNavalRewriteOpenWater(goalX, goalY)) {
            return new int[] {goalX, goalY};
        }
        int stride = battleNetMovementStride(ship);
        int shipX = ship.tileX();
        int shipY = ship.tileY();
        int bestHeading = -1;
        int bestDist = Integer.MAX_VALUE;
        for (int dir = 0; dir < Direction.COUNT; dir++) {
            int nx = shipX + Direction.deltaX(dir) * stride;
            int ny = shipY + Direction.deltaY(dir) * stride;
            if (!battleNetNavalRewriteOpenWater(nx, ny)) {
                continue;
            }
            if (!canEnter(ship, nx, ny)) {
                continue;
            }
            int dist = Math.max(Math.abs(goalX - nx), Math.abs(goalY - ny));
            if (dist < bestDist) {
                bestDist = dist;
                bestHeading = dir;
            }
        }
        if (bestHeading >= 0) {
            return new int[] {
                    shipX + Direction.deltaX(bestHeading) * stride,
                    shipY + Direction.deltaY(bestHeading) * stride
            };
        }
        for (int radius = 1; radius <= 4; radius++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != radius) {
                        continue;
                    }
                    int nx = goalX + dx;
                    int ny = goalY + dy;
                    if (battleNetNavalRewriteOpenWater(nx, ny)) {
                        return new int[] {nx, ny};
                    }
                }
            }
        }
        return null;
    }

    /**
     * Rewrites a naval order point the way {@code FUN_004381d0} does.
     *
     * <p>Walks the Bresenham ray from the requested goal toward the ship.
     * While the current square is blocked for the ship it advances; when the
     * next square is free open water for the ship it keeps the previous
     * (blocked) square as the active goal. XOrc 11's destroyer at (22,38)
     * with shipyard goal (21,34) lands on (22,36) -- the south edge of the
     * 3-by-3 footprint -- which the pathfinder then fails, promoting Still
     * without the far-endpoint swap.</p>
     */
    private int[] battleNetNavalOrderPoint(Unit ship, int goalX, int goalY) {
        int shipX = ship.tileX();
        int shipY = ship.tileY();
        if (goalX == shipX && goalY == shipY) {
            return new int[] {goalX, goalY};
        }
        int x = goalX;
        int y = goalY;
        int prevX = goalX;
        int prevY = goalY;
        int rawDx = shipX - goalX;
        int rawDy = shipY - goalY;
        int absoluteX = Math.abs(rawDx);
        int absoluteY = Math.abs(rawDy);
        boolean xMajor = absoluteX >= absoluteY;
        int major = xMajor ? absoluteX : absoluteY;
        int minor = xMajor ? absoluteY : absoluteX;
        int majorSign = Integer.signum(xMajor ? rawDx : rawDy);
        int minorSign = Integer.signum(xMajor ? rawDy : rawDx);
        int error = major >> 1;
        if (error == 0) {
            error = 1;
        }
        while (x != shipX || y != shipY) {
            int minorStep = 0;
            error -= minor;
            if (error < 1) {
                minorStep = minorSign;
                error += major;
            }
            int stepDx = xMajor ? majorSign : minorStep;
            int stepDy = xMajor ? minorStep : majorSign;
            int nextX = x + stepDx;
            int nextY = y + stepDy;
            if (battleNetNavalRewriteOpenWater(nextX, nextY)) {
                return new int[] {prevX, prevY};
            }
            prevX = nextX;
            prevY = nextY;
            x = nextX;
            y = nextY;
        }
        return new int[] {prevX, prevY};
    }

    /** Promotes BNE's startup transport-to-hall resource action. */
    void beginBattleNetPendingTransport(Unit unit) {
        Unit target = unit.battleNetPendingTransportTarget();
        unit.setBattleNetPendingTransportTarget(null);
        if (target == null || !target.isAlive()) {
            return;
        }
        unit.clearPath();
        unit.setTarget(target);
        // GiveOrder 0x4513d0 stores the hall top-left; 0x438320/0x4381d0 then
        // rewrites order X/Y to the last impassable square before the first
        // free ship anchor on the hall→ship Bresenham ray (Orc 4: 17,37).
        // Bottom-right hall goals and last-water-only clips both missed that
        // step-back and either took the wrong first step or regressed h5.
        int[] orderPoint = battleNetTransportOrderPoint(unit, target);
        int goalX = orderPoint[0];
        int goalY = orderPoint[1];
        unit.setOrderTarget(goalX, goalY);
        // Large ships step on the even lattice. Double-step when the ship
        // already sits on that lattice, neither shore delta is a single tile
        // (Orc 4 |dx|=1, Human 4 |dy|=1), and the approach is either pure-
        // axis or Chebyshev >= 4. That last gate is fixture-grounded: Orc 5
        // (3,3) first-steps single NW to (47,115), while Human 12 (4,5)
        // double-steps NE to (70,32), Human 5 (7,5) double-steps NW to
        // (122,50), and XHuman 5 (2,0) double-steps pure east. Requiring the
        // hall bottom-right to be even as well used to clear Human 12's
        // fortress BR (88,17) and leave stride 1. The same predicate is
        // re-applied after each residual settle (see
        // {@link #battleNetTransportDoubleStep}) so a first double-step that
        // lands with major&lt;4 and a non-zero minor clears 0x1c&amp;2 before
        // the next commit -- Human 12 transport 1522 half-steps 70,32→71,31
        // at fixture 41 instead of a second double NE to 72,30.
        unit.setBattleNetDoubleStep(
                battleNetTransportDoubleStep(unit, goalX, goalY));
        if (System.getenv("CHONKCRAFT_TRACE_TRANSPORT") != null) {
            System.err.printf("JBNETRANSORDER unit=%d ship=%d,%d hall=%d@%d,%d"
                            + " goal=%d,%d double=%d%n",
                    unit.id(), unit.tileX(), unit.tileY(),
                    target.id(), target.tileX(), target.tileY(),
                    goalX, goalY, unit.battleNetDoubleStep() ? 1 : 0);
        }
        unit.setOrder(Unit.Order.HARVEST);
        unit.setBattleNetOrderDelay(2);
    }

    /**
     * Whether action 30 keeps the doubled movement-delta table for one
     * transport-to-hall approach from the ship's current tile.
     *
     * <p>Native {@code unit+0x1c & 2} is set at order time by the same
     * even-lattice / shore-delta / major-minor gate, then re-tested when a
     * residual settles before the next heading commits. Human 12 orc
     * transport 1522 double-steps 68,34→70,32 (major 5 from the order
     * point), drains residual through fixture 40 with the bit still set,
     * and at fixture 41 clears it (0x0a→0x08) and half-steps NE to 71,31
     * because the remaining (2,3) fails major≥4 and minor==0. Leaving the
     * bit sticky used to take a second double NE to 72,30.</p>
     */
    boolean battleNetTransportDoubleStep(Unit unit, int goalX, int goalY) {
        if (unit == null) {
            return false;
        }
        int deltaX = Math.abs(goalX - unit.tileX());
        int deltaY = Math.abs(goalY - unit.tileY());
        int major = Math.max(deltaX, deltaY);
        int minor = Math.min(deltaX, deltaY);
        return ((unit.tileX() | unit.tileY()) & 1) == 0
                && deltaX != 1 && deltaY != 1
                && (major >= 4 || minor == 0);
    }

    /** Orders a unit to hold its ground. */
    public void orderStandGround(Unit unit) {
        if (unit == null || !unit.isAlive()) {
            return;
        }
        projectiles.interruptPendingAttack(unit);
        unit.clearPath();
        unit.setTarget(null);
        unit.setFighting(false);
        unit.setOrder(Unit.Order.STAND_GROUND);
    }

    /**
     * Stands a sleeping unit up and breathes, which costs a random draw.
     *
     * <p>{@code COrder::IsWaiting} plays the Still animation for any unit
     * whose order is asleep, over the top of whatever the unit was doing and
     * without disturbing it. That is not
     * cosmetic: ChonkCraft builds Still out of {@code "wait 4"},
     * {@code "random-goto 99 no-rotate"}, {@code "random-rotate 1"} and
     * {@code "wait 1"} ({@code scripts/anim.legacy-declaration:31}), so a sleeping unit takes
     * one draw from the shared stream every five cycles and one turn on the
     * spot in a hundred.
     *
     * <p>It was found as a seed divergence rather than a visible one. On
     * {@code (2)2-players} upstream's peasant sleeps out the ten cycles its
     * blocked path cost it and draws at cycles 83 and 88 while it does;
     * this implementation's peasant slept without breathing, and every draw either
     * engine made afterwards was a different number.
     */
    private void sleepStanding(Unit unit) {
        Animation still = stillAnimation(unit);
        if (still == null) {
            return;
        }
        unit.animation().beginWait();
        unit.animation().switchTo(still);
        advance(unit);
    }

    /** Plays the animation belonging to a non-combat work order. */
    void stepWorkAnimation(Unit unit, AnimationSet.State state) {
        AnimationSet set = unit.type().animationSet();
        if (set == null) {
            return;
        }
        Animation animation = set.get(state);
        // Upstream lets an outside builder use its repair swing when a mod
        // does not declare a distinct Build state.
        if (animation == null && state == AnimationSet.State.BUILD) {
            animation = set.get(AnimationSet.State.REPAIR);
        }
        if (animation == null) {
            animation = set.getOrStill(state);
        }
        unit.animation().switchTo(animation);
        advance(unit);
    }

    /**
     * Advances one unit along its path.
     *
     * <p>Mirrors {@code DoActionMove}: when the pixel offset has decayed to
     * zero the unit is standing squarely on a tile and may begin the next
     * step, which moves it logically at once and offsets it visually
     * backwards. Otherwise the offset is drawn down by this cycle's movement.
     */
    /**
     * Ends the order a unit is under, and leaves it reported for this cycle.
     *
     * <p>Upstream's orders finish by setting a flag, not by being replaced:
     * {@code this->Finished = true} leaves {@code Orders[0]} exactly where it
     * is, and {@code CurrentAction()} goes on answering with it until
     * {@code HandleUnitAction} comes round on the next cycle and pops it. So a
     * walk that ends on cycle 46 is still a walk when that cycle is read, and
     * the unit is standing still only from 47.
     *
     * <p>And what it comes back to is a <em>new</em> still order, whose sleep
     * starts at nought ({@code COrder_Still::Sleep = 0},
     * {@code action/action_still.h:64}), so its first cycle of standing still
     * is a cycle of looking around. This implementation keeps its two scan counters on
     * the unit, where they ran down across the walk, so a unit that stopped
     * had to wait out whatever was left of a scan it had already had.
     *
     * <p>Both are worth a cycle each on
     * {@code maps/skirmish/(3)critter-attack}: two animals give up walking on
     * cycle 46 -- reading as moving there, not still -- and both draw for a
     * new wander on 47, three numbers each, where this implementation's drew nothing.
     */
    void finishOrder(Unit unit) {
        // Generic order endings own the same cleanup. Most calls have no
        // pending shot; keeping the rule at both order termination boundaries
        // prevents attack-ground, weak auto-target, and queued replacements
        // from leaking presentation-ahead missiles through a different exit.
        projectiles.interruptPendingAttack(unit);
        unit.rememberActionBeforeQueued(unit.order());
        unit.setOrder(Unit.Order.STILL);
        unit.setRandomMoveSleep(0);
        unit.setAttackScanSleep(0);
    }

    /** The unit id CHONKCRAFT_TRACE_MOVING watches, mirroring LEGACY_ENGINE_TRACE_MOVING. */
    static final String TRACE_MOVING = System.getenv("CHONKCRAFT_TRACE_MOVING");
    static final int TRACE_MOVING_ID =
            TRACE_MOVING == null ? -1 : Integer.parseInt(TRACE_MOVING.trim());

    /** The direction a spent route's phantom element points; see above. */
    static final int PHANTOM_HEADING = 0;

    /** {@code applyResidualDisplacementCorrection}, one axis of it. */
    static int residualToward(int value, int move) {
        if (value > 0) {
            return Math.max(0, value - move);
        }
        if (value < 0) {
            return Math.min(0, value + move);
        }
        return 0;
    }

    /**
     * Everything the planner needs to know about a unit.
     *
     * <p>Built in one place so that the planner and the mover cannot drift
     * apart: both now read the same {@code movementMask} and
     * {@code blockingFlags} off the unit itself, rather than the planner
     * deciding for itself what a building or a body means. They disagreed
     * about every flyer on the map while it did.
     */
    PathFinder.Mover moverFor(Unit unit) {
        return new PathFinder.Mover(
                unit.movementMask(),
                unit.blockingFlags(),
                unit.type().tileWidth(),
                unit.type().tileHeight(),
                occupancyFor(unit));
    }

    /**
     * What a unit makes of every square, for planning a route.
     *
     * <p>The map's flags say that a unit is on a tile; they cannot say whether
     * it is walking, whose it is, or whether it is this very unit. All three
     * change whether a route through it is sensible, so the planner is given a
     * way to ask.
     *
     * <p>Without this a standing unit cost a twentieth of a step, so routes
     * were planned straight through crowds; the walker then stopped dead at
     * the first body, re-planned, and got the identical route back. That loop
     * is what the hesitation was.
     */
    private PathFinder.Occupancy occupancyFor(Unit mover) {
        long blocking = mover.blockingFlags();
        return (x, y) -> {
            if (!map.contains(x, y)) {
                return PathFinder.Occupancy.CLEAR;
            }
            List<Unit> cached = unitCache.get(x + y * map.width());
            if (cached == null) {
                return PathFinder.Occupancy.CLEAR;
            }
            String occupancyTrace = System.getenv("CHONKCRAFT_TRACE_OCCUPANCY");
            boolean traceOccupancy = occupancyTrace != null
                    && mover.id() == Integer.parseInt(occupancyTrace);
            if (traceOccupancy) {
                System.err.printf("JOCCUPANCYDBG cycle=%d unit=%d tile=%d,%d"
                                + " flags=%x blocking=%x cache=",
                        cycle, mover.id(), x, y,
                        map.field(x, y).flags(), blocking);
                for (Unit cachedUnit : cached) {
                    System.err.printf("%d/%s/%s/%d,",
                            cachedUnit.id(), cachedUnit.type().ident(),
                            cachedUnit.order(), cachedUnit.walkHolding() ? 1 : 0);
                }
                System.err.println();
            }
            for (Unit other : cached) {
                if (!other.isOnMap()) {
                    continue;
                }
                // CUnitTypeFinder is the predicate behind the cache lookup,
                // and its two clauses reject Vanishes and select by MoveType.
                // Crucially, it does not ask whether that selected unit set
                // the field's occupancy bit. A zero-hit-point oil patch has
                // naval MoveType but marks only NoBuilding; when a tanker is
                // moored over it, the tanker's SeaUnit field flag enters the
                // callback and UnitCache returns the patch first. Upstream
                // therefore treats that square as a standing naval body for
                // UnitReachable, even though ordinary sailing can enter it.
                // Filtering on occupancyFlag here hid the patch and made an
                // AI tanker believe every depot was reachable.
                if (other.type().vanishes()
                        || other.type().moveType() != mover.type().moveType()) {
                    continue;
                }
                // The callback explicitly gives its own unit a zero cost.
                // Do this only after the same predicate as UnitCache's finder:
                // the first matching entry wins, so an earlier oil patch can
                // mask the tanker that follows it in the cache.
                if (other == mover) {
                    return PathFinder.Occupancy.CLEAR;
                }
                // CostMoveToCallBack_Default asks UnitCache for the first unit
                // on this movement layer and does not filter UnitAction::Die.
                // The dying unit's field flag is gone, but another occupant's
                // flag is enough to enter this branch and expose it.
                if (!other.isAlive() || other.isDying()) {
                    return PathFinder.Occupancy.STATIONARY;
                }
                // Only a unit that is actually getting somewhere counts as one
                // that will move out of the way. Upstream asks
                // {@code goal->Moving}, the
                // flag DoActionMove sets on the cycle a step is taken, holds
                // for the length of that step, and does not set when it
                // answers PF_WAIT -- which is {@link Unit#walkHolding}, the
                // same state the blocked-march test reads, not the drawing
                // offsets. The offsets land at nought between two steps of a
                // continuous walk and on the animation tail the sign
                // asymmetry keeps; upstream's flag holds through both, so a
                // walker mid-journey stays crossable to its neighbours' plans
                // for exactly as long as the binary keeps it so.
                if (other.walkHolding()) {
                    return PathFinder.Occupancy.MOVING;
                }
                // A standing body is a wall unless it is an enemy the mover
                // could clear out of the way itself, and upstream spells out
                // what that means -- all five clauses of it
                // The game an enemy, of an aggressive
                // mover, of a kind its weapon can target, not under unholy
                // armour, and visible as a goal. A peasant plans around the
                // grunt a footman would plan through.
                if (isEnemyPlayer(mover.player(), other.player())
                        && mover.isAggressive()
                        && targets.canTarget(mover, other)
                        && !other.hasBuff(Unit.Buff.UNHOLY_ARMOR)
                        && targets.isVisibleAsGoal(mover.player(), other)) {
                    return PathFinder.Occupancy.STATIONARY_ENEMY;
                }
                return PathFinder.Occupancy.STATIONARY;
            }
            return PathFinder.Occupancy.CLEAR;
        };
    }

    boolean canEnter(Unit unit, int x, int y) {
        int w = Math.max(1, unit.type().tileWidth());
        int h = Math.max(1, unit.type().tileHeight());
        // The unit's own occupancy is still set on its current square, so
        // clear it for the test and restore it afterwards.
        markOccupancy(unit, false);
        boolean ignoreBuilding = construction.builderWalksThroughBuildingBodies(unit);
        long blocking = ignoreBuilding
                ? construction.builderTraversalBlocking(unit)
                : unit.blockingFlags();
        boolean free = map.isFootprintFree(x, y, w, h, unit.movementMask(), blocking);
        if (free && ignoreBuilding) {
            for (int dy = 0; dy < h && free; dy++) {
                for (int dx = 0; dx < w; dx++) {
                    if (construction.solidBuildingOriginAt(x + dx, y + dy)) {
                        free = false;
                        break;
                    }
                }
            }
        }
        markOccupancy(unit, true);
        return free;
    }

    /** Tests action 30's grid anchor rather than ChonkCraft's rendered footprint. */
    boolean canEnterBattleNetTransportAnchor(Unit unit, int x, int y) {
        if (!map.contains(x, y)) {
            return false;
        }
        // BNE's large naval movers travel on a one- or two-tile anchor grid.
        // Their sprite footprint is not re-tested as a 2x2 top-left box for
        // each action-30 step.  That distinction is observable both at the
        // XHuman 5 shoreline and while Orc 14's transport leaves an authored
        // overlap with its shipyard.
        markOccupancy(unit, false);
        boolean free = map.isFootprintFree(x, y, 1, 1,
                unit.movementMask(), unit.blockingFlags());
        markOccupancy(unit, true);
        return free;
    }
}
