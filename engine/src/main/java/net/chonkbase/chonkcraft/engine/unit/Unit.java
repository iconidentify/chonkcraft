package net.chonkbase.chonkcraft.engine.unit;

import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;

/**
 * One unit on the map.
 *
 * <p>Implements the state {@code CUnit} carries during play, as distinct from
 * {@link UnitType}, which is the shared template.
 *
 * <p>Position works the way LegacyEngine does it, which is worth stating because
 * it reads backwards at first. When a unit steps to the next tile it is moved
 * to that tile <em>immediately</em>, and its pixel offset is set to a full
 * tile in the opposite direction so it still draws in the old place. The
 * offset then decays to zero over the following cycles. So the logical
 * position is always where the unit is going, and the offset is how far it
 * has yet to visually catch up. Collision and pathfinding therefore see the
 * destination reserved for the whole of the step, which is what stops two
 * units walking into the same square.
 */
public final class Unit {

    // The setters below are public because World, which lives one package up,
    // is the only thing that may call them: a unit's tile, offset, path and
    // order are owned by the simulation, not by the unit. Nothing else should
    // touch them.


    /** Pixels along a tile edge. */
    public static final int TILE_PIXELS = 32;

    private final int id;
    /**
     * What this unit is.
     *
     * <p>Not final, because a building can become another kind of building: a
     * Town Hall becomes a Keep in place, keeping its position, its damage and
     * its identity. Only {@code World.transformInto} should change it, and only
     * with the map bookkeeping unwound and redone around the change.
     */
    private UnitType type;
    private int player;

    private int tileX;
    private int tileY;

    /** Pixel offset from the tile, negative while catching up to a step. */
    private int offsetX;
    private int offsetY;

    private int hitPoints;

    /** Spell points. Only casters have any. */
    private int mana;

    /**
     * Which way this unit faces, as an angle in 256ths.
     *
     * <p>{@code CUnit::Direction}, and it is not a facing.
     * {@code UnitHeadingFromDeltaXY} sets it to {@code DirectionToHeading} of
     * the step exactly, and
     * {@code CUnit::Init} draws it as a whole byte, {@code (SyncRand() >> 8) &
     * 0xFF}. The eight-way facing the sprite sheet
     * wants is worked out from it at drawing time and never stored in its
     * place.
     *
     * <p>Storing the facing instead loses up to fifteen 256ths, which sounds
     * like nothing and is not: a siege engine's Move animation opens with
     * {@code if-var R >= 60 turn} and stands still for thirty cycles if that
     * holds, so a turn upstream sees as fifty-five and a port rounds up to
     * sixty-four is thirty cycles of standing in one engine and none in the
     * other. On {@code maps/demo/demo03} that was a ballista taking sixty-two
     * cycles to cross one square against upstream's thirty-two.
     *
     * <p>128 is south, which is the way units are built.
     */
    private int direction = 128;

    /**
     * The sight range this unit's vision was last added to the fog with.
     *
     * <p>Sight is reference counted, so it has to be taken away at exactly the
     * range it was granted at. Recomputing the range at removal time looks
     * equivalent and is not: research a sight upgrade between a unit's last
     * move and its next one and the two figures differ, leaving a ring of
     * squares lit that nothing can ever put out.
     */
    private int markedSightRange;

    /** Instance-specific sight set by an animation, or -1 for the type value. */
    private int sightRangeOverride = -1;

    /**
     * The animation's current base frame: the first index of a sheet row.
     * {@link SpriteFrame} turns it plus the heading into a sheet index.
     */
    private int frame;

    private final net.chonkbase.chonkcraft.engine.animation.AnimationState animation =
            new net.chonkbase.chonkcraft.engine.animation.AnimationState();

    /** Cycles to wait before this unit acts again. */
    private int wait;

    /** The remaining route, as headings, next step last. */
    private int[] path = new int[0];
    private int pathLength;

    /**
     * The far end of a patrol beat.
     *
     * <p>Kept separate from {@code orderTarget} because a patrolling unit
     * needs both ends at once: the one it is walking to, and the one it will
     * turn round and walk back to.
     */
    /**
     * Where a building sends what it produces, or {@code -1} for nowhere.
     *
     * <p>Without one a new soldier stands in the doorway of the barracks that
     * made it, and a player who is training an army has to collect it by hand
     * a unit at a time.
     */
    /**
     * A spell this unit casts on its own when something worth casting at
     * comes into range, or null.
     *
     * <p>Off by default, as in the original: a mage that spends its pool
     * without being asked is a mage that has nothing left when it matters.
     */
    private String autoCast;

    private int rallyX = -1;
    private int rallyY = -1;

    private int patrolX = -1;
    private int patrolY = -1;

    /**
     * Where an attack-move is headed: {@code COrder_Attack::attackMovePos}.
     *
     * <p>Its own pair rather than {@code orderTarget}, for the reason upstream
     * keeps its own too. A march that stops to fight re-plans a route at the
     * thing it is fighting, and every re-plan that goes through
     * {@code World.orderMove} rewrites {@code orderTarget}; the destination
     * was quietly replaced by the square where the first skirmish happened,
     * and the unit stopped there instead of carrying on.
     */
    private int attackMoveX = -1;
    private int attackMoveY = -1;

    /**
     * {@code COrder_Attack::goalPos}, retained separately from its weak goal.
     *
     * <p>The target pointer can become null when the target is released, but
     * the order still compares this last position with {@code attackMovePos}
     * before deciding whether to resume the march or finish it.
     */
    private int attackGoalX = -1;
    private int attackGoalY = -1;

    private int orderTargetX = -1;
    private int orderTargetY = -1;
    private Order order = Order.STILL;
    private Order savedOrder;
    private int savedAttackMoveX = -1;
    private int savedAttackMoveY = -1;
    private int savedMoveRange;
    private int savedAttackScanSleep;
    private boolean savedAttackMoveOpening;
    private Unit target;

    /**
     * The type held by upstream's one-shot {@code CriticalOrder}.
     *
     * <p>Research conversions are commanded while another unit is executing.
     * They therefore run when this unit next reaches HandleUnitAction, rather
     * than changing every member of the army inside the researcher's turn.
     */
    private UnitType pendingTransform;

    public UnitType pendingTransform() {
        return pendingTransform;
    }

    public void setPendingTransform(UnitType type) {
        pendingTransform = type;
    }

    /** Orders appended with Shift, waiting behind the current one. */
    private final java.util.ArrayDeque<QueuedOrder> queuedOrders = new java.util.ArrayDeque<>();

    /** The kinds of order that can wait in a unit's command queue. */
    public enum QueuedOrderKind {
        MOVE,
        ATTACK,
        HARVEST,
        BUILD,
        CAST,
        PATROL,
        REPAIR,
        EXPLORE,
        RETURN_GOODS,
        STAND_GROUND,
        ATTACK_GROUND,
        ATTACK_MOVE,
        BOARD,
        FOLLOW,
        DEFEND
    }

    /**
     * A command with its script and network indices resolved to simulation
     * objects, ready to become the current order later.
     */
    public record QueuedOrder(QueuedOrderKind kind, int x, int y, Unit target,
            UnitType type, String value) {
    }

    /**
     * The six timed spell effects, in upstream's own order.
     *
     * <p>{@code HandleBuffsEachCycle} names exactly
     * these six and runs each one down by one every cycle:
     *
     * <pre>
     * const int SpellEffects[] = {BLOODLUST_INDEX, HASTE_INDEX, SLOW_INDEX,
     *                             INVISIBLE_INDEX, UNHOLYARMOR_INDEX, POISON_INDEX};
     * </pre>
     *
     * <p>Five of the six are the whole of five Warcraft II spells, and this
     * port had none of them: {@code Spell.EffectKind.ADJUST_VARIABLE} was a
     * no-op in {@code World}, so Bloodlust, Haste, Slow, Invisibility and
     * Unholy Armour each cost their mana, played their sound, threw their
     * missile and did nothing at all. Bloodlust is the ogre-mage's whole
     * reason to exist and three of the mage's six spells are on this list.
     *
     * <p>Poison is here because upstream decrements it here, and for no other
     * reason: no ChonkCraft spell adjusts it and no shipped unit type declares
     * {@code PoisonDrain}, so nothing in this data can ever set it. Carrying
     * it costs one array slot and keeps the implementation's list the same list as
     * upstream's, which is worth more than the slot.
     */
    public enum Buff {
        BLOODLUST,
        HASTE,
        SLOW,
        INVISIBLE,
        UNHOLY_ARMOR,
        POISON
    }

    /** Cycles left of each {@link Buff}, indexed by ordinal. */
    private final int[] buffs = new int[Buff.values().length];

    /** Cycles left of a timed spell effect, or nought if it is not on. */
    public int buff(Buff buff) {
        return buffs[buff.ordinal()];
    }

    /** Whether a timed spell effect is currently on this unit. */
    public boolean hasBuff(Buff buff) {
        return buffs[buff.ordinal()] > 0;
    }

    /**
     * Sets a timed spell effect, in cycles.
     *
     * <p>Assignment rather than accumulation, which is what upstream's
     * {@code adjust-variable} does by default: {@code Spell_AdjustVariable}
     * writes the value unless the action declares {@code AddValue}, and no
     * ChonkCraft spell declares one. So casting Bloodlust on an already
     * bloodlusted grunt restarts its thousand cycles rather than giving it two
     * thousand, and Haste's {@code {Haste = 1000, Slow = 0}} clears Slow in
     * the same breath -- which is why the pair are written together in
     * scripts/spells.legacy-declaration:238 and :256.
     */
    public void setBuff(Buff buff, int cycles) {
        buffs[buff.ordinal()] = Math.max(0, cycles);
    }

    /**
     * Runs every timed effect down by one cycle.
     *
     * <p>{@code HandleBuffsEachCycle} sets {@code Increase = -1} and calls
     * {@code IncreaseVariable}, which clamps at nought. Called for every unit
     * every cycle wherever it is and whatever it is doing, including one that
     * is removed inside a transport, because that is what upstream does: a
     * passenger's Bloodlust runs out while it is aboard.
     */
    public void decayBuffs() {
        for (int i = 0; i < buffs.length; i++) {
            if (buffs[i] > 0) {
                buffs[i]--;
            }
        }
    }

    /** Target stickiness and aggressor stickiness; see the accessors. */
    private int threshold;
    private int underAttack;

    /** Cycles left before the next auto-target scan. */
    private int attackScanSleep;

    /** Cycles left before another random-walk roll. */
    private int randomMoveSleep;

    /** Retail BNE's independent initial/action animation countdown. */
    private int battleNetAnimationTimer;

    /** Current byte offset in retail BNE's {@code Rez\\script.bin}. */
    private int battleNetSequenceOffset = -1;

    /** Number of BNE idle action markers this unit has crossed. */
    private int battleNetIdlePhase;

    /** Whether an armed BNE tower has entered terminal idle action 14. */
    private boolean battleNetTowerActive;

    /**
     * Retail BNE's separate flying-idle countdown (native byte {@code 0x0d}).
     * This advances only when a Still animation marker dispatches the unit's
     * idle action; it is not the animation timer itself.
     */
    private int battleNetFlyingIdleTimer;

    /** BNE action calls remaining before a newly queued walk advances a tile. */
    private int battleNetOrderDelay;
    private boolean battleNetPlayerCommandMove;

    /**
     * This player Move is the walking half of retail GiveOrder 17.
     *
     * <p>A melee attack-ground click into unenterable terrain first installs
     * Move and promotes back to Attack Ground after its final leftover lands.
     * Ordinary Move orders must never make that promotion merely because an
     * older attack left an attack-goal coordinate behind.</p>
     */
    private boolean battleNetAttackGroundMove;

    /**
     * A Stop click arrived while dest-arm leftover pixels were still
     * draining. Native keeps Move until those pixels land.
     */
    private boolean battleNetStopAfterLeftover;

    /**
     * How many free stepPatrol visits a self-patrol combat flyer has spent
     * at its start tile. XOrc 8 gryphon 1550 first-steps after eight holds
     * (ready c5, step c13).
     */
    private int battleNetSelfPatrolHolds;

    /**
     * Whether this combat flyer's preferred-neighbour self-patrol scout has
     * ended (no free preferred/neighbour stride). Native then leaves Still
     * until a later idle marker queues a half-map patrol as next_order
     * (XOrc 8 1560: Still through fixture 51, next_order Patrol@49, current
     * Patrol@52 toward 0,17) rather than re-arming self-patrol as the live
     * order on the first Still OP0 (which used to show Patrol at fixture 44).
     */
    private boolean battleNetFlyerScoutExhausted;
    private boolean battleNetScoutPatrol;

    /**
     * Whether retail BNE currently uses its doubled movement-delta table.
     *
     * <p>This is native unit flag {@code 0x1c & 2}, not a permanent property
     * of a two-by-two unit. Large movers begin with it set, but individual
     * action handlers may clear it. In particular, raw action 30
     * ({@code 0x437c80}) clears it when either the transport or its hall goal
     * is not aligned to the even-coordinate grid.</p>
     */
    private boolean battleNetDoubleStep;

    /**
     * Cursor into the retail Move sequence that paces residual pixel drain.
     *
     * <p>ChonkCraft Move waits diverge from {@code script.bin} for some 2x2
     * movers (gnomish submarine irregular wait-1 stretches). While a step's
     * residual drains, this cursor walks the native Move body so holds match
     * retail. Separate from {@link #battleNetSequenceOffset} when that offset
     * is owned by Still/Attack or chase OP0.</p>
     */
    private int battleNetMovePaceOffset = -1;

    /** Native unsigned-byte timer for {@link #battleNetMovePaceOffset}. */
    private int battleNetMovePaceTimer;

    /**
     * Patrol queued by retail BNE's game-creation ready pass.
     *
     * <p>This cannot use the ordinary command queue: the Java order loop pops
     * that queue as soon as it sees {@link Order#STILL}, while BNE leaves this
     * order behind the unit's initial Still action until its constructor
     * animation reaches an action marker.</p>
     */
    private int battleNetPendingPatrolX = -1;
    private int battleNetPendingPatrolY = -1;
    private int battleNetPendingPatrolBackX = -1;
    private int battleNetPendingPatrolBackY = -1;

    /** Hall-side regroup queued by a retail BNE campaign AI profile. */
    private int battleNetPendingMoveX = -1;
    private int battleNetPendingMoveY = -1;
    private int battleNetAiHomeX = -1;
    private int battleNetAiHomeY = -1;

    /** Retail BNE's native AI behavior byte at unit offset {@code 0x5e}. */
    private int battleNetAiBehavior;

    /**
     * After an impassable constructor wander, keep the Still program past the
     * first action marker and burn this many async draws so later critters
     * stay on the stream the restart-at-sequence-start path used to produce.
     */
    private int battleNetConstructorStreamBurns;

    /** World cycle at which {@link #battleNetConstructorStreamBurns} may start. */
    private int battleNetConstructorBurnAfterCycle;

    /**
     * Retail BNE construction HP remainder pool. Each build boost adds
     * {@code full - foundation} and pays {@code pool / buildTime} hit points
     * (XOrc 10 farm: +3, +4, +3, … from the 360/100 accumulator).
     */
    private int battleNetConstructionHpPool;

    /**
     * Melee table {@code 0x27}: first in-range Attack marker seeds unit+0xb
     * via SyncRand ({@code 0x4234b0}). Cleared after the draw or when the unit
     * chases out of range first.
     */
    private boolean battleNetPendingMeleeSyncRand;

    /**
     * Cycles until the next table-0x27 attack-loop SyncRand re-seed
     * ({@code 0x4234b0}). Retail re-writes unit+0xb every attack animation
     * loop (twenty-five cycles): Human 5 standing grunt 1531 draws at
     * fixture 6 then 31; chasers 1528/1532 draw at 22 then 47. Zero means
     * no loop arm is live.
     */
    private int battleNetMeleeSyncRemaining;

    /** Native BNE substates used by an oil tanker's resource order. */
    public enum BattleNetOilAction {
        IDLE(2),
        TO_RESOURCE(23),
        TO_DEPOT(24),
        FINAL_APPROACH(25),
        INSIDE_RESOURCE(26);

        private final int rawAction;

        BattleNetOilAction(int rawAction) {
            this.rawAction = rawAction;
        }

        public int rawAction() {
            return rawAction;
        }
    }

    /** Current native oil-resource substate. */
    private BattleNetOilAction battleNetOilAction = BattleNetOilAction.TO_RESOURCE;

    /** Visits remaining in the current timed oil substate. */
    private int battleNetOilActionTicks;

    /**
     * Whether action 23 began beside its platform. Distant tankers must hold
     * the final boarding seat before they may promote to action 25.
     */
    private boolean battleNetOilStartedAdjacent;

    /**
     * Gold HARVEST path was planned with more than one step. When the route
     * later has a single leftover heading that misses the approach point,
     * action 23 must promote to action 25 without walking that leftover
     * (XHuman 9 peon 1550). Fresh one-step plans must not take this arm.
     */
    private boolean battleNetGoldLongApproach;

    /**
     * Gold free-prefix path: ray stopped short of the blocked mine approach
     * and only the clear tip was stored. When that tip residual settles,
     * replan without the emptied-buffer PF_WAIT 10 (Orc 12 peon 1525:
     * free SW,NW onto 85,41 while approach is 83,41; native continues west
     * on the settle cycle). Full MAX_PATH segments still pay the wait.
     */
    private boolean battleNetGoldFreePrefix;

    /**
     * Heading count when {@link #battleNetGoldFreePrefix} was marked. Short
     * free tips (under four) discard an ally-blocked leftover and replan;
     * longer free-prefix segments soft-hold progressive leftovers.
     */
    private int battleNetGoldFreePrefixLength;

    /**
     * Whether leftover-land on an empty send-home paid action 25 and may
     * dest-arm onto {@code 0x41f430}. Laden doorsteps leave this clear so
     * they still serve the empty ten.
     */
    private boolean battleNetResourceApproachStaged;

    /** Whether action 25 must replace a tail inherited from action 23. */
    private boolean battleNetResourceApproachRedirect;

    /** Hall queued as a transport's retail BNE startup destination. */
    private Unit battleNetPendingTransportTarget;

    /**
     * BNE spatial-help ({@code FUN_0040a9d0}) defers Attack onto nextAction
     * rather than replacing Still in the same visit. The aggressor is held
     * here until the unit's next action promotes it, so fixture cycle N still
     * reports Still when the neighbour was hit in that cycle's projectile
     * pass (XHuman 12 grunt 1481: next=12 at c13, action=12 at c14).
     */
    private Unit battleNetPendingHelpAttack;

    /** Whether pending help came from a person ally struck within two tiles. */
    private boolean battleNetPendingCloseHitHelp;

    /**
     * Whether the PUD's auxiliary word suppressed BNE's general ready pass.
     *
     * <p>Retail's PUD loader treats a non-zero {@code UNIT.Data} value on a
     * movable unit as an AI placement marker: it sets bit 1 at native unit
     * offset {@code 0x5f}.  The later game-creation walk tests that bit before
     * calling the surface-unit arm of {@code 0x427130}, so a marked ship keeps
     * its placed guard behaviour instead of receiving the ordinary naval
     * patrol callback. Aircraft still enter native behaviour four and receive
     * their scout patrol even when the marker is present.
     * ChonkCraft calls the word unused for non-resource units, but BNE campaign
     * maps rely on it heavily.</p>
     */
    private boolean battleNetReadySuppressed;

    /**
     * Whether this unit came from an authored PUD placement.
     *
     * <p>Native's recurring behavior-one regroup pass walks the campaign
     * roster. Runtime constructions and trainees have their own AI admission
     * callbacks and must not inherit that map-roster pass merely because they
     * belong to a computer player.</p>
     */
    private boolean battleNetMapPlaced;

    /**
     * Whether the current Attack is native action 16 (stationary auto-scan).
     *
     * <p>BNE idle acquisition uses action 16 when the auxiliary order
     * reference is clear: it may fire in place but never takes the chase
     * transition of action 12. Out of weapon range it queues Still instead
     * of walking. Set by the idle scan; cleared on commanded attacks and
     * when the order ends.</p>
     */
    private boolean battleNetStationaryAttack;

    /**
     * After non-building empty-FOUND Still, keep OP0ing until a wander lands.
     *
     * <p>Restart Still with timer 1 after each no-wander so WAIT 4 cannot
     * skip the re-wander beat (Orc 11 1597 Still@8-9 Move@10). Building
     * footprint empties leave this clear.</p>
     */
    private boolean battleNetOccupiedEmptyReWander;

    /**
     * When set with {@link #battleNetOccupiedEmptyReWander}, the third OP0
     * redraws into the wander band. Used only for occupied-mobile empties
     * where reverse-walk would steal the band choice (Human 3 1587 → 40,15).
     * Free-empty restarts must not force-redraw (Human 3 1589 stays Still).
     */
    private boolean battleNetOccupiedEmptyForceWander;

    /**
     * No-wander OP0 count while {@link #battleNetOccupiedEmptyReWander} is set.
     */
    private int battleNetOccupiedEmptyNoWanderCount;

    /**
     * After coast free-empty first-constructor Still, stretch the following
     * Still-loop WAIT by one quiet visit (Orc 10 1510: native first loop OP0
     * at fixture 13, not 12). Cleared after the stretch or on re-wander.
     */
    private boolean battleNetCoastEmptyExtraWait;

    /**
     * Native action-33 train counter at unit+0x6e. Computer halls increment
     * this on each Still OP0; when the previous value exceeds the type limit
     * (2 for great-hall / town-hall peon trains) the hall starts a reserved
     * worker train and the counter resets.
     */
    private int battleNetAiTrainCounter;

    /**
     * PUD UNIT.Data word carried onto the live unit. Non-zero arms barracks
     * action-33 footman/grunt auto-train (native unit+0x5c style); Human 13
     * barracks place with data 0 and never auto-train while XHuman 2 / XOrc 11
     * barracks place with data 1 and debit 600 gold at cycle 12.
     */
    private int battleNetPudData;

    /** Whether the current attack order was chosen rather than commanded. */
    /**
     * Where an attack order has got to, as upstream's {@code COrder_Attack}
     * keeps it.
     *
     * <p> names the states and
     * {@code COrder_Attack::Execute} switches on them. The two that matter
     * here are the chase and the swing, because they order their work
     * differently: {@code MoveToTarget} runs {@code DoActionMove} first and
     * only then looks at whether the goal is still worth having, while
     * {@code AttackTarget} runs the animation first and returns while it is
     * unbreakable. Merging them loses that, and it is a cycle of every chase.
     */
    public boolean chasing() {
        return chasing;
    }

    public void setChasing(boolean chasing) {
        this.chasing = chasing;
    }

    private boolean chasing;

    /**
     * Whether an attack-move order still owes its FIRST_ENTRY/AUTO_TARGETING
     * scan before pathfinder output may drive it.
     *
     * <p>Transient order state. PathFinderOutput belongs to the unit and can
     * survive RestoreOrder, so the presence of a cached route cannot stand in
     * for this bit: a restored order must scan first while keeping that route.
     */
    public boolean attackMoveOpening() {
        return attackMoveOpening;
    }

    public void setAttackMoveOpening(boolean attackMoveOpening) {
        this.attackMoveOpening = attackMoveOpening;
    }

    private boolean attackMoveOpening;

    /**
     * GiveOrder 8 dest-path dest-arm hold. The dest walk waits this out
     * after install; leftover chase delays must not use it.
     */
    public boolean destPathOpeningHold() {
        return destPathOpeningHold;
    }

    public void setDestPathOpeningHold(boolean destPathOpeningHold) {
        this.destPathOpeningHold = destPathOpeningHold;
    }

    private boolean destPathOpeningHold;

    /**
     * How far from its destination a move order will settle, and it grows.
     *
     * <p>{@code COrder_Move::Range} upstream. It starts at nought and
     * {@code COrder_Move::Execute} increments it every time the search answers
     * PF_UNREACHABLE, so an order onto ground
     * the unit cannot occupy is not refused: it settles for one square away,
     * then two, until the goal covers somewhere it can stand. It terminates
     * without a cap because once the range reaches the distance to the unit's
     * own square the search answers PF_REACHED where it stands.
     */
    public int moveRange() {
        return moveRange;
    }

    public void setMoveRange(int moveRange) {
        this.moveRange = moveRange;
    }

    private int moveRange;

    private boolean autoTargeting;

    /**
     * Whether losing sight invalidates the current attack target.
     *
     * <p>Every target selected through normal play starts visible, and
     * upstream releases it when that stops being true. Keeping the initial
     * state explicit also lets headless engine callers create an omniscient
     * scripted order without pretending the target was clicked through fog.
     */
    private boolean attackRequiresVisibility;

    private boolean selected;
    private boolean removed;

    /**
     * Released from play but retained in the global table by a live UnitPtr.
     * LegacyEngine calls this {@code Destroyed}; such a unit is neither traced
     * nor acted, but its slot is not swap-removed until the final reference
     * lets go.
     */
    private boolean destroyed;

    /** Cycles left of the death animation before the corpse is cleared. */
    private int deathTimer;

    /** How much of {@link #heldResource} the worker is holding. */
    private int carried;

    /** What the order told the worker to go and gather. */
    private UnitType.Resource carrying;

    /**
     * What kind of load the worker actually has in hand, or {@code null} when
     * empty-handed. Distinct from {@link #carrying}: a wood chopper reassigned
     * to gold still holds its part-felled wood for the whole walk to the mine,
     * and only loses it the moment gathering starts on the other resource.
     *
     * <p>Implements {@code CUnit::CurrentResource} in {@code src/unit/unit.h},
     * which {@code COrder_Resource::StartGathering} compares against the
     * order's own resource and, on a change, clears together with the load
     *
     */
    private UnitType.Resource heldResource;

    /** The mine or forest square being worked. */
    private Unit resourceUnit;
    /** The depot this resource order last used: {@code COrder_Resource::Depot}. */
    private Unit resourceDepot;
    /** The current return-leg weak goal, separate from {@code Depot}. */
    private Unit returnDepotGoal;
    private int resourceTileX = -1;
    private int resourceTileY = -1;

    /** Whether the worker is on its way back to unload. */
    private boolean returningToDepot;

    /**
     * How many waits this worker's harvest walk has answered, towards the
     * fifth that buys a shove.
     *
     * <p>{@code COrder_Resource::Range}, which the resource order never uses
     * as a range -- its route asks are hardcoded to nought-to-one -- and
     * borrows as a counter instead: the {@code PF_WAIT} arms of its three
     * walks step it up, and at five it resets and calls {@code AiCanNotMove}
     * Fresh with each resource order,
     * as {@code ActionResourceInit}'s {@code this->Range = 0} makes it.
     */
    private int resourceWaitLadder;

    /** Consecutive {@code PF_UNREACHABLE} states on this resource leg. */
    private int resourceUnreachableTries;

    /** Calls to {@code DoActionMove} on the current resource-order leg. */
    private int resourceMoveCycles;

    /**
     * How many of each player's watchers see this unit.
     *
     * <p>{@code CUnit::VisCount}. Not derivable from the fog under the unit,
     * and the difference is load-bearing: {@code UnitCountSeen}'s
     * went-out-of-fog arm adds one beyond what the tiles say
     * so a unit seen once outlives its last
     * real watcher by one sight change. Goal visibility reads this, not the
     * tiles.
     */
    private final int[] visCount = new int[16];
    /** Players that have ever seen this unit: {@code CUnit::Seen.ByPlayer}. */
    private int seenByPlayers;

    public int visCount(int player) {
        return visCount[player];
    }

    public void setVisCount(int player, int count) {
        visCount[player] = count;
    }

    /** A guarded step, as the {@code _TileSeen} walk moves it. */
    public void bumpVisCount(int player, int delta) {
        visCount[player] += delta;
        if (visCount[player] < 0) {
            visCount[player] = 0;
        }
    }

    public boolean wasSeenBy(int player) {
        return (seenByPlayers & (1 << player)) != 0;
    }

    public void markSeenBy(int player) {
        seenByPlayers |= 1 << player;
    }

    public int seenByPlayers() {
        return seenByPlayers;
    }

    public void setSeenByPlayers(int players) {
        seenByPlayers = players;
    }

    /**
     * Whether the current order has said its last word.
     *
     * <p>{@code COrder::Finished}. An order that gives up mid-cycle stays the
     * unit's current order to the end of that cycle -- the state everyone
     * else reads -- and the next cycle's advance replaces it. A port that
     * swapped orders the moment one died showed the change a cycle before
     * upstream did.
     */
    private boolean orderFinished;

    public boolean orderFinished() {
        return orderFinished;
    }

    public void setOrderFinished(boolean orderFinished) {
        this.orderFinished = orderFinished;
    }

    /** Construction or training progress, against {@link #progressGoal}. */
    private int progress;
    private int progressGoal;

    /** What a building is training. */
    private UnitType producing;

    /** Further paid training jobs, in the order they were requested. */
    private final java.util.ArrayDeque<UnitType> trainingQueue = new java.util.ArrayDeque<>();

    /** The six production slots declared by ChonkCraft's training panel. */
    public static final int MAX_TRAINING_JOBS = 6;

    /** What this worker is on its way to build. */
    private UnitType pendingBuild;

    /** The upgrade a building is researching, or {@code null}. */
    private String researching;

    /** The site a builder is walking to. */
    private int buildTileX = -1;
    private int buildTileY = -1;

    /**
     * The exact square inside {@link #buildTileX}/{@link #buildTileY} that
     * retail BNE asks its pathfinder to enter.
     *
     * <p>The foundation rectangle and movement goal are separate in BNE's
     * unit state ({@code +0x80} and {@code +0x84}). Keeping both matters: a
     * worker approaching a two-by-two farm from the south-east walks toward
     * its south-east square, not toward the foundation's top-left corner.</p>
     */
    private int buildGoalX = -1;
    private int buildGoalY = -1;

    /** The building a worker is inside, or the worker inside a building. */
    private Unit worksite;

    /** Units aboard this transport. */
    private final java.util.List<Unit> cargo = new java.util.ArrayList<>();

    /** The transport carrying this unit, or {@code null}. */
    private Unit carrier;

    /** What a unit is currently doing. */
    public enum Order {
        /** Standing. */
        STILL,
        /** Walking to {@code orderTarget}. */
        MOVE,
        /** Closing on {@code target} and hitting it. */
        ATTACK,
        /** Playing out the death animation before being removed. */
        DYING,
        /** Walking to a resource, taking a load, and carrying it back. */
        HARVEST,
        /** Walking to a build site to start work. */
        BUILD,
        /** A building under construction, not yet usable. */
        UNDER_CONSTRUCTION,
        /** Walking a beat between two points, fighting what it meets. */
        PATROL,
        /** Mending a damaged building or ship. */
        REPAIR,
        /** Wandering towards ground nobody has seen. */
        EXPLORE,
        /** Walking to a transport in order to get aboard it. */
        BOARD,
        /** Closing on a target in order to cast a spell at it. */
        SPELL_CAST,
        /** Carrying a load back to the nearest depot. */
        RETURN_GOODS,
        /** Shooting at a square rather than at anything in particular. */
        ATTACK_GROUND,
        /**
         * Advancing on a place, fighting whatever comes into reach.
         *
         * <p>{@code COrder::NewActionAttack(attacker, dest)}: upstream's attack order
         * accepts a position as well as a unit, sets {@code attackMovePos} and
         * starts in {@code AUTO_TARGETING}. It is the order
         * {@code AiForce::Attack} gives every aggressive unit in a force --
         * {@code CommandAttack(unit, GoalPos, nullptr)} -- and the one a
         * right click on open ground gives a soldier.
         *
         * <p>The implementation had only {@link #MOVE}, so a marching army walked past
         * what it should have engaged and the AI re-aimed every unit once a
         * second to cover for it. It is a member of the attack family, not the
         * move family, which is what makes it retaliate: see
         * {@code World.attackBack}.
         */
        ATTACK_MOVE,
        /**
         * Holding position, striking what comes into reach.
         *
         * <p>Distinct from {@link #STILL} because a unit standing ground
         * never takes a step: a line told to hold stays a line rather than
         * dissolving into a chase.
         */
        STAND_GROUND,
        /** Keeping up with a friendly unit as it moves. */
        FOLLOW,
        /**
         * Putting cargo ashore.
         *
         * <p>{@code COrder_Unload}. Unloading is an
         * order rather than an instant, because the place a boat happens to be
         * floating is almost never a place its passengers can step onto. The
         * order finds a stretch of coast, sails to it, and only then lets
         * anybody off.
         */
        UNLOAD,
        /**
         * Keeping up with a friendly unit and fighting what threatens it.
         *
         * <p>BNE's Alt-right-click. Distinct from {@link #FOLLOW}, which
         * never draws a weapon, and from {@link #STAND_GROUND}, which never
         * leaves its tile.
         */
        DEFEND
    }

    /** Looking for a drop zone. {@code FIND_DROPZONE_STATE}. */
    public static final int UNLOAD_FIND_DROPZONE = 0;
    /** Sailing to the one it found. {@code MOVE_TO_DROPZONE_STATE}. */
    public static final int UNLOAD_MOVE_TO_DROPZONE = 1;
    /** Putting them ashore. {@code UNLOAD_STATE}. */
    public static final int UNLOAD_LEAVING = 2;

    private int unloadState;
    private int unloadRetries;

    /** Where the unload order has got to. */
    public int unloadState() {
        return unloadState;
    }

    public void setUnloadState(int state) {
        this.unloadState = state;
    }

    /**
     * How many times the unload order has failed to get anywhere.
     *
     * <p>{@code COrder_Unload::Retries}. It is what stops a boat wedged in a
     * lagoon from searching the map every cycle forever, and reaching the
     * limit is how the order ends -- successfully or not.
     */
    public int unloadRetries() {
        return unloadRetries;
    }

    public void setUnloadRetries(int retries) {
        this.unloadRetries = retries;
    }

    public Unit(int id, UnitType type, int player, int tileX, int tileY) {
        this.id = id;
        this.type = type;
        this.player = player;
        this.tileX = tileX;
        this.tileY = tileY;
        this.hitPoints = type.hitPoints();
        // A caster starts on the starting value its variable declares, not on
        // its maximum. See UnitType.manaStart.
        this.mana = type.manaStart();
    }

    public int id() {
        return id;
    }

    /**
     * Becomes another kind of unit.
     *
     * <p>Deliberately blunt: it changes the field and nothing else. Sight,
     * occupancy and supply all depend on the type and none of them are touched
     * here, so this is only safe from {@code World.transformInto}, which
     * unwinds them first and puts them back after.
     */
    public void becomeType(UnitType wanted) {
        if (wanted != null) {
            this.type = wanted;
        }
    }

    /**
     * The spell this unit is on its way to cast, or null.
     *
     * <p>Casting is a journey. Upstream's spell order walks the caster into
     * range and only then casts, and re-plans if the target moves; casting
     * instantly and refusing when out of range would mean a mage could only
     * ever hit something already standing next to it.
     */
    private String castingSpell;

    public String castingSpell() {
        return castingSpell;
    }

    public void setCastingSpell(String ident) {
        this.castingSpell = ident;
    }

    /** What this building is turning into, or null. */
    private UnitType upgradingTo;

    public UnitType upgradingTo() {
        return upgradingTo;
    }

    public void setUpgradingTo(UnitType wanted) {
        this.upgradingTo = wanted;
    }

    public UnitType type() {
        return type;
    }

    public int player() {
        return player;
    }

    /**
     * Whose this unit was before it was rescued, or -1.
     *
     * <p>A rescued unit keeps a memory of the slot it was freed from. The
     * campaign asks about it directly -- nine missions are won by getting
     * rescued units to a circle of power -- and upstream also uses it to draw
     * such a unit in its original colour.
     */
    private int rescuedFrom = -1;

    public int rescuedFrom() {
        return rescuedFrom;
    }

    public boolean wasRescued() {
        return rescuedFrom >= 0;
    }

    public void setRescuedFrom(int player) {
        this.rescuedFrom = player;
    }

    public void setPlayer(int player) {
        this.player = player;
    }

    public int tileX() {
        return tileX;
    }

    public int tileY() {
        return tileY;
    }

    public void setTile(int x, int y) {
        this.tileX = x;
        this.tileY = y;
    }

    public int offsetX() {
        return offsetX;
    }

    public int offsetY() {
        return offsetY;
    }

    public void setOffset(int x, int y) {
        this.offsetX = x;
        this.offsetY = y;
    }

    /** Pixel position for drawing: the tile, plus however far it has to catch up. */
    public int pixelX() {
        return tileX * TILE_PIXELS + offsetX;
    }

    public int pixelY() {
        return tileY * TILE_PIXELS + offsetY;
    }

    public int hitPoints() {
        return hitPoints;
    }

    public void setHitPoints(int hitPoints) {
        this.hitPoints = Math.max(0, Math.min(hitPoints, type.hitPoints()));
    }

    /**
     * How much ore or oil a deposit still holds.
     *
     * <p>Its own field rather than the unit's hit points, which is where this
     * used to live. Hit points are clamped to the type's maximum, and that is
     * right for damage and wrong for a deposit: a gold mine could never hold
     * more than the type's default however much the map said it held, and an
     * oil patch -- whose type has no hit points at all, because nothing can
     * attack one -- was clamped to nothing and gave a tanker nothing back.
     * Upstream keeps ResourcesHeld separately for exactly this reason.
     */
    private int resourcesHeld;

    /**
     * The most this deposit has ever held, which is what the amount left is
     * shown against.
     *
     * <p>The {@code GiveResource} decoration is a fraction and needs a
     * denominator. Upstream keeps it in
     * {@code Variable[GIVERESOURCE_INDEX].Max}, set beside the value when the
     * map places the deposit and left
     * standing while mining runs the value down
     * This implementation sets and drains a deposit
     * through the one setter, so the high-water mark is kept here instead:
     * for every real flow -- the type default at creation, the map's own
     * figure over it, then only withdrawals -- the high-water mark <em>is</em>
     * the placed amount. The one divergence is a save: upstream writes Max
     * and this implementation restores only the amount left, so a reloaded mine shows a
     * full bar over what remains rather than a part bar over what it started
     * with. Cosmetic, and it corrects itself the moment a load is taken.
     */
    private int resourcesHeldPeak;

    public int resourcesHeld() {
        return resourcesHeld;
    }

    public int resourcesHeldPeak() {
        return resourcesHeldPeak;
    }

    public void setResourcesHeld(int amount) {
        this.resourcesHeld = Math.max(0, amount);
        this.resourcesHeldPeak = Math.max(this.resourcesHeldPeak, this.resourcesHeld);
    }

    /** Whether the unit is on the map and not yet dead. */
    /** Spell points held. */
    public int mana() {
        return mana;
    }

    public void setMana(int mana) {
        this.mana = Math.max(0, Math.min(mana, type.mana()));
    }

    /** Whether this unit can cast at all. */
    public boolean isCaster() {
        return type.mana() > 0;
    }

    public boolean isAlive() {
        return hitPoints > 0 && !removed && order != Order.DYING;
    }

    /**
     * Whether there is something here for a player to point at.
     *
     * <p>This is upstream's {@code CUnit::IsAlive} exactly -- {@code
     * !Destroyed && CurrentAction() != UnitAction::Die} -- and
     * the point of it is the clause it does <em>not</em> have. {@link
     * #isAlive()} adds {@code hitPoints > 0}, and {@link #setHitPoints} clamps
     * to the type's maximum, so a type declared {@code HitPoints = 0} can
     * never have any and is therefore invisible to anything that asks {@code
     * isAlive()} first.
     *
     * <p>Three shipped types are declared that way: {@code unit-oil-patch},
     * {@code unit-circle-of-power} and {@code unit-pile-circle}. Measured over
     * the fifty-two campaign maps, all 204 gold mines answered a click at
     * their own centre and none of the 105 oil patches or 10 circles of power
     * did. In Warcraft II you click an oil patch to read how much oil is left
     * in it, and the circle of power is the objective of the Dark Portal
     * missions -- so the two things a player most needs to point at on those
     * maps were the two things that could not be pointed at.
     *
     * <p>Deliberately <em>not</em> a widening of {@code isAlive()}, which has
     * 211 callers in {@code World} alone and whose extra clause is load-
     * bearing everywhere else: a thing with no hit points must go on being
     * untargetable, unharvestable and unrepairable. This is read by the two
     * lookup methods and by nothing else, which is the whole of the
     * difference between "what is standing here" and "what can I do to it".
     */
    public boolean isPointable() {
        return !removed && order != Order.DYING;
    }

    /**
     * Where the current path was heading.
     *
     * <p>Kept apart from {@code orderTarget}, which only some orders set. A
     * worker walking to a mine, a soldier closing on an enemy and a passenger
     * walking to a boat all have a path and no order target, and a re-plan
     * that consults the wrong one asks for a route to nowhere.
     */
    private int pathGoalX = -1;

    private int pathGoalY = -1;

    public int pathGoalX() {
        return pathGoalX;
    }

    public int pathGoalY() {
        return pathGoalY;
    }

    public void setPathGoal(int x, int y) {
        this.pathGoalX = x;
        this.pathGoalY = y;
    }

    /** Whether this unit's footprint includes a square. */
    public boolean covers(int x, int y) {
        int width = type == null ? 1 : Math.max(1, type.tileWidth());
        int height = type == null ? 1 : Math.max(1, type.tileHeight());
        return x >= tileX && x < tileX + width && y >= tileY && y < tileY + height;
    }

    /** Whether the unit still occupies space, which a dying one does not. */
    public boolean isOnMap() {
        return !removed;
    }

    /**
     * The facing to draw, banded from {@link #direction}.
     *
     * <p>{@code UnitUpdateHeading}'s own arithmetic: the angle is rounded to
     * the nearest facing rather than truncated to the one below it.
     */
    public int heading() {
        int facings = type == null ? 8 : Math.max(1, type.numDirections());
        int step = 256 / facings;
        return ((direction + step / 2) & 0xFF) / step % facings;
    }

    /** The angle in 256ths, which is what upstream stores. */
    public int direction() {
        return direction;
    }

    /** Sets the angle directly, as a birth draw or a save restore does. */
    public void setDirection(int direction) {
        this.direction = direction & 0xFF;
    }

    /**
     * How far from a transport this unit will currently settle for.
     *
     * <p>Upstream's {@code COrder_Board::Range}. It starts at one -- boarding
     * needs adjacency -- and grows by one each time the approach cannot find a
     * route, so a passenger that cannot reach a boat yet walks as close as it
     * can and waits there instead of giving up where it stands.
     *
     * <p>It resets to one the moment the unit actually moves, which is
     * {@code MoveToTransporter}'s own rule and not an optimisation: without it
     * a passenger keeps the widened range it earned while stuck, is satisfied
     * standing well short of the boat, and the two "circle each other" --
     * which is the comment upstream leaves at that line.
     */
    private int boardRange = 1;

    /** @see #boardRange */
    public int boardRange() {
        return boardRange;
    }

    public void setBoardRange(int boardRange) {
        this.boardRange = boardRange;
    }

    /** @see #markedSightRange */
    public int markedSightRange() {
        return markedSightRange;
    }

    public void setMarkedSightRange(int markedSightRange) {
        this.markedSightRange = markedSightRange;
    }

    public int sightRangeOverride() {
        return sightRangeOverride;
    }

    public void setSightRangeOverride(int sightRangeOverride) {
        this.sightRangeOverride = sightRangeOverride;
    }

    /** Faces a whole facing, which is the angle at the middle of that band. */
    public void setHeading(int heading) {
        int facings = type == null ? 8 : Math.max(1, type.numDirections());
        this.direction = (heading * (256 / facings)) & 0xFF;
    }

    public int frame() {
        return frame;
    }

    public void setFrame(int frame) {
        this.frame = frame;
    }

    public int waitCycles() {
        return wait;
    }

    /**
     * A target this unit picked for itself and has not acted on yet.
     *
     * <p>Upstream's commands are queued, not applied: {@code ReleaseOrders}
     * does not remove {@code Orders[0]}, it shrinks the list to it and marks
     * it finished, and {@code GetNextOrder}
     * pushes the new order at {@code Orders[1]}.
     * {@code HandleUnitAction} pops to it only on the cycle after
     * So a unit that notices an enemy
     * during its own step is still doing what it was doing for the rest of
     * that cycle, and starts the attack on the next one.
     */
    /**
     * Whether the building this worker is walking to has been paid for yet.
     *
     * <p>The cost goes out when the foundation goes down, not when the order
     * is given, so an order abandoned on the way
     * there has nothing to refund.
     */
    public boolean buildPaid() {
        return buildPaid;
    }

    public void setBuildPaid(boolean paid) {
        this.buildPaid = paid;
    }

    private boolean buildPaid;

    /**
     * Whether the builder has already reached its site and served the pause
     * upstream serves before a building goes up. See {@link #buildPaid}.
     */
    public boolean buildReached() {
        return buildReached;
    }

    public void setBuildReached(boolean reached) {
        this.buildReached = reached;
    }

    private boolean buildReached;

    /**
     * Whether this build order has ever tried to walk anywhere.
     *
     * <p>A builder that was standing in its site when the order arrived pays
     * none of the pause a builder that walked there pays. See
     * {@link #buildReached}.
     */
    public boolean buildWalked() {
        return buildWalked;
    }

    public void setBuildWalked(boolean walked) {
        this.buildWalked = walked;
    }

    private boolean buildWalked;

    public Unit pendingAttack() {
        return pendingAttack;
    }

    /**
     * The order a build command interrupted, while the interruption waits.
     *
     * <p>A command does not break an unbreakable animation. Upstream's flush
     * shrinks the queue to the running order and marks it finished.
     * {@code HandleUnitAction} only pops the queue once
     * {@code !unit.Anim.Unbreakable}
     * -- so a peon told to build mid-step finishes the step under its old
     * order first, and the trace shows the old order for those cycles. Null
     * when no build command is waiting on an animation.
     */
    public Order buildLatchedFrom() {
        return buildLatchedFrom;
    }

    public void setBuildLatchedFrom(Order from) {
        this.buildLatchedFrom = from;
    }

    private Order buildLatchedFrom;

    /**
     * Whether an attack order is in its fight state.
     *
     * <p>{@code ATTACK_TARGET} in {@code COrder_Attack::State}: entered when
     * the walk ends inside {@code InAttackRange} or a strike begins, and
     * left when the range check in the attack animation's breakable tail
     * finds the quarry gone.
     * Transient combat state, deliberately not persisted: a save mid-swing
     * reloads into the chase, exactly as a save cannot hold an animation
     * frame.
     */
    public boolean fighting() {
        return fighting;
    }

    public void setFighting(boolean fighting) {
        this.fighting = fighting;
    }

    private boolean fighting;

    /**
     * Whether the current swing began against a quarry out of reach.
     *
     * <p>{@code AttackTarget} plays the attack animation before its range
     * check, so a fight entered with the quarry already gone buys one full
     * swing at empty air -- level13h's knight, twenty-five cycles of it --
     * whose attack tick must not land, unlike the committed swing a kited
     * target still takes.
     */
    public boolean swingAtAir() {
        return swingAtAir;
    }

    public void setSwingAtAir(boolean swingAtAir) {
        this.swingAtAir = swingAtAir;
    }

    private boolean swingAtAir;

    /**
     * A target a blow has offered this unit, awaiting its next targeting pass.
     *
     * <p>{@code COrder_Attack::offeredTarget}: {@code HitUnit_AttackBack}
     * does not retarget a unit already fighting -- it banks the attacker
     * through {@code OfferNewTarget}, weighed against any standing offer,
     * and {@code AutoSelectTarget} weighs the bank against its own scan on
     * the order's cadence. The
     * struck ogre on level13h keeps chasing its wise-man through the
     * knight's blow because the offer waits here instead of turning it.
     */
    public Unit offeredTarget() {
        return offeredTarget;
    }

    public void setOfferedTarget(Unit offered) {
        this.offeredTarget = offered;
    }

    private Unit offeredTarget;

    /**
     * The action this unit is still counted as performing, which is not always
     * the order it is executing.
     *
     * <p>Upstream keeps the two apart and this implementation had them as one thing. A
     * command issued while a unit's own order is running does not replace that
     * order: {@code ReleaseOrders} shrinks the queue to it and marks it
     * finished, and the new order waits at {@code Orders[1]}
     * {@code CurrentAction()} therefore
     * still answers with the old one until {@code HandleUnitAction} pops, on
     * the following cycle. A critter that
     * decides to wander is doing what a critter does -- standing still -- for
     * the rest of the cycle it decided in, and starts walking on the next.
     *
     * <p>Nought means there is nothing queued and {@link #order} is the
     * answer.
     */
    public Order currentAction() {
        return actionBeforeQueued == null ? order() : actionBeforeQueued;
    }

    /**
     * Whether a command this cycle left the pre-command label in force.
     *
     * <p>The label normally lives to the unit's next turn. An unbreakable
     * animation keeps it for as many turns as the committed step or swing
     * needs, because upstream cannot pop the finished order until
     * {@code Anim.Unbreakable} lets go. While it stands, what the unit reports
     * is what it was doing before the command, which is upstream's
     * queued-order convention.
     */
    public boolean reportsActionBeforeQueued() {
        return actionBeforeQueued != null;
    }

    public void setActionBeforeQueued(Order action) {
        this.actionBeforeQueued = action;
        if (action == null) {
            actionBeforeQueuedReleaseDelay = 3;
        }
    }

    /**
     * Records what this unit was doing before the first queued command of the
     * cycle, and only the first.
     *
     * <p>A second command in the same cycle empties the queue and pushes its
     * own order behind {@code Orders[0]}, which is still the order the unit
     * was running when the first command arrived -- so {@code CurrentAction}
     * answers with that one however many commands land. The AI issues two on
     * its opening thought, sending a worker to gather and then asking it to
     * build, and taking the second one's "before" would report the gathering.
     */
    public void rememberActionBeforeQueued(Order action) {
        rememberActionBeforeQueued(action, 3);
    }

    /**
     * Records the old current-action label and the new order's cold-action
     * boundary. The label pops when the combined command delay reaches this
     * value; the rest of the delay belongs to the replacement itself.
     */
    public void rememberActionBeforeQueued(Order action, int releaseDelay) {
        if (actionBeforeQueued == null) {
            actionBeforeQueued = action;
            actionBeforeQueuedReleaseDelay = Math.max(0, releaseDelay);
        }
    }

    public int actionBeforeQueuedReleaseDelay() {
        return actionBeforeQueuedReleaseDelay;
    }

    private Order actionBeforeQueued;
    private int actionBeforeQueuedReleaseDelay = 3;

    public void setPendingAttack(Unit target, Order from, int x, int y) {
        this.pendingAttack = target;
        this.pendingAttackFrom = from;
        this.pendingAttackX = x;
        this.pendingAttackY = y;
    }

    /** What this unit was doing when it queued the attack. */
    public Order pendingAttackFrom() {
        return pendingAttackFrom;
    }

    /** The weak goal captured when the automatic attack command was queued. */
    public int pendingAttackX() {
        return pendingAttackX;
    }

    /** The weak goal captured when the automatic attack command was queued. */
    public int pendingAttackY() {
        return pendingAttackY;
    }

    private Unit pendingAttack;

    private Order pendingAttackFrom;

    private int pendingAttackX = -1;

    private int pendingAttackY = -1;

    /**
     * Upstream's blocked-path counter.
     *
     * <p>{@code PathFinderOutput::Fast}, capped by {@code MAX_FAST}, ten
     * ({@code src/include/pathfinder.h:108,117}). In this LegacyEngine revision
     * it cycles {@code 0,10,9...,1,0} while cached elements are refused but
     * never itself causes a re-plan: both refusal arms set the result to
     * {@code PF_WAIT} (zero), making the following
     * {@code Fast == 0 && result != 0} expiry condition unreachable
     *
     */
    public int pathWaitBudget() {
        return pathWaitBudget;
    }

    public void setPathWaitBudget(int budget) {
        this.pathWaitBudget = budget;
    }

    private int pathWaitBudget;

    /**
     * BNE movement refusal collision counter ({@code unit+0x1c} high nibble).
     *
     * <p>Native {@code FUN_004379e0} increments on every refused step candidate.
     * Counters below 8 replan with wait 1 (or preserve route with wait 15 for
     * a cooperative mover); 8 through 14 force replan with wait 15; the
     * fifteenth clears the counter. XHuman 12 peon 1554 stays on the wait-1
     * replan seam and takes SE at fixture 12, while neighbour 1553 has already
     * climbed to the wait-15 band and stays put.
     */
    public int battleNetCollisionCounter() {
        return battleNetCollisionCounter;
    }

    public void setBattleNetCollisionCounter(int counter) {
        this.battleNetCollisionCounter = Math.max(0, counter);
    }

    private int battleNetCollisionCounter;

    /**
     * How many times in a row this unit has been refused its next step.
     *
     * <p>Implements the high nibble of {@code word[unit + 0x1c]} in
     * {@code fcn.004379e0} of Warcraft II Battle.net Edition 2.02b. It is
     * bumped once per refusal at {@code 0x00437a0d} and read back at
     * {@code 0x00437a20} to pick the band: one through seven park the route
     * and act again next cycle, eight through fourteen give the route up and
     * sleep fifteen, and the fifteenth clears the count and takes the next
     * order.
     *
     * <p>It is sticky within the current movement/refusal generation.
     * {@code 0x00437a9c} clears a saturated count at fifteen, while native's
     * active-order handoff at {@code 0x00438410} parks the old route and clears
     * the same nibble before a newly constructed Attack body. It is not
     * cleared merely by taking a step, laying a route, or arriving. Retail's
     * peon 1521 in Orc 12 is still carrying eight twenty-four cycles after it
     * started walking again; XHuman 12 grunt 1504 instead changes four to zero
     * exactly when the handoff installs Attack at fixture 87.
     *
     * <p>This is deliberately not {@link #battleNetCollisionCounter}, which
     * this implementation also uses to choose which wait band a refusal takes and
     * resets in a dozen places. Raising that one instead put XHuman 10's
     * axethrower 1478 a cycle late.
     */
    public int battleNetRefusals() {
        return battleNetRefusals;
    }

    public void setBattleNetRefusals(int refusals) {
        this.battleNetRefusals = Math.max(0, refusals);
    }

    private int battleNetRefusals;

    public void setWaitCycles(int wait) {
        this.wait = wait;
    }

    public Order order() {
        return order;
    }

    public void setOrder(Order order) {
        Order previous = this.order;
        this.order = order;
        boolean capitalPatrolCursor = type != null && type.seaUnit()
                && ("unit-battleship".equals(type.ident())
                        || "unit-ogre-juggernaught".equals(type.ident()))
                && (order == Order.PATROL
                        || (order == Order.MOVE && battleNetBorrowedMoveForStep
                                && previous == Order.PATROL));
        boolean flyerPatrolStrideCursor = type != null
                && type.moveType() == UnitType.Movement.FLY
                && type.canAttack() && battleNetDoubleStep
                && order == Order.MOVE && battleNetBorrowedMoveForStep
                && previous == Order.PATROL;
        boolean nativeSequencedPatrol = capitalPatrolCursor
                || flyerPatrolStrideCursor;
        if (order != Order.STILL && !nativeSequencedPatrol) {
            // Still keeps its cursor so the idle dispatcher can fire.
            // Capital-ship Patrol used to wipe that same cursor, so XOrc 11's
            // battleship lost the Still program at promote and never reached
            // the Move-body OP0 that opens Attack at fixture 58. walkTowards
            // borrows MOVE for that one stride and must not wipe it either.
            // Armed doubled flyers also carry the cursor across the borrowed
            // MOVE used for a Patrol stride. Other Patrol (unarmed scouts,
            // destroyers) still wipe -- keeping those cursors shifted Human
            // 12's async scout dest off 107,51.
            battleNetSequenceOffset = -1;
        }
    }

    /** The autonomous order to resume after an opportunistic fight. */
    public Order savedOrder() {
        return savedOrder;
    }

    public void setSavedOrder(Order savedOrder) {
        this.savedOrder = savedOrder;
        savedMoveRange = 0;
        savedAttackScanSleep = 0;
        savedAttackMoveOpening = savedOrder == Order.ATTACK_MOVE;
        if (savedOrder != Order.ATTACK_MOVE) {
            savedAttackMoveX = -1;
            savedAttackMoveY = -1;
        }
    }

    /** Takes and clears the order an automatic attack interrupted. */
    public Order takeSavedOrder() {
        Order saved = savedOrder;
        savedOrder = null;
        savedAttackMoveX = -1;
        savedAttackMoveY = -1;
        savedMoveRange = 0;
        savedAttackScanSleep = 0;
        savedAttackMoveOpening = false;
        return saved;
    }

    /** Destination carried by a saved attack order, independent of the live one. */
    public int savedAttackMoveX() {
        return savedAttackMoveX;
    }

    public int savedAttackMoveY() {
        return savedAttackMoveY;
    }

    public void setSavedAttackMove(int x, int y) {
        savedAttackMoveX = x;
        savedAttackMoveY = y;
    }

    /** Path range carried by the cloned saved attack order. */
    public int savedMoveRange() {
        return savedMoveRange;
    }

    public void setSavedMoveRange(int range) {
        savedMoveRange = range;
    }

    public int savedAttackScanSleep() {
        return savedAttackScanSleep;
    }

    public void setSavedAttackScanSleep(int sleep) {
        savedAttackScanSleep = sleep;
    }

    public boolean savedAttackMoveOpening() {
        return savedAttackMoveOpening;
    }

    public void setSavedAttackMoveOpening(boolean opening) {
        savedAttackMoveOpening = opening;
    }

    public String autoCast() {
        return autoCast;
    }

    public void setAutoCast(String autoCast) {
        this.autoCast = autoCast;
    }

    public int rallyX() {
        return rallyX;
    }

    public int rallyY() {
        return rallyY;
    }

    public boolean hasRallyPoint() {
        return rallyX >= 0 && rallyY >= 0;
    }

    public void setRallyPoint(int x, int y) {
        this.rallyX = x;
        this.rallyY = y;
    }

    public void clearRallyPoint() {
        this.rallyX = -1;
        this.rallyY = -1;
    }

    public int patrolX() {
        return patrolX;
    }

    public int patrolY() {
        return patrolY;
    }

    public void setPatrol(int x, int y) {
        this.patrolX = x;
        this.patrolY = y;
    }

    /** Where an attack-move is headed; see {@link #attackMoveX}. */
    public int attackMoveX() {
        return attackMoveX;
    }

    public int attackMoveY() {
        return attackMoveY;
    }

    public void setAttackMove(int x, int y) {
        this.attackMoveX = x;
        this.attackMoveY = y;
    }

    public int attackGoalX() {
        return attackGoalX;
    }

    public int attackGoalY() {
        return attackGoalY;
    }

    public void setAttackGoal(int x, int y) {
        this.attackGoalX = x;
        this.attackGoalY = y;
    }

    public int orderTargetX() {
        return orderTargetX;
    }

    public int orderTargetY() {
        return orderTargetY;
    }

    public void setOrderTarget(int x, int y) {
        this.orderTargetX = x;
        this.orderTargetY = y;
    }

    public void enqueueOrder(QueuedOrder queued) {
        if (queued != null) {
            queuedOrders.addLast(queued);
        }
    }

    public QueuedOrder pollQueuedOrder() {
        return queuedOrders.pollFirst();
    }

    /**
     * Whether the head of the queue is the flush replacement for the order
     * still executing under an unbreakable animation, rather than a shifted
     * command following the replacement already installed in {@link #order}.
     */
    public boolean queuedReplacementPending() {
        return queuedReplacementPending;
    }

    public void setQueuedReplacementPending(boolean pending) {
        this.queuedReplacementPending = pending;
    }

    private boolean queuedReplacementPending;

    public java.util.List<QueuedOrder> queuedOrders() {
        return java.util.List.copyOf(queuedOrders);
    }

    /**
     * The part of an order lifecycle which a fresh player command may flush.
     *
     * <p>The command boundary has to clear this state before installing a
     * replacement because an unbreakable animation can put that replacement
     * back into the same queue.  A refused command, however, must be a true
     * no-op.  Keeping the snapshot here makes that transaction exact instead
     * of asking the network layer to reconstruct the private resume fields.
     */
    public record PendingOrderState(java.util.List<QueuedOrder> queuedOrders,
            boolean queuedReplacementPending, Order savedOrder,
            int savedAttackMoveX, int savedAttackMoveY, int savedMoveRange,
            int savedAttackScanSleep, boolean savedAttackMoveOpening) { }

    public PendingOrderState snapshotPendingOrders() {
        return new PendingOrderState(java.util.List.copyOf(queuedOrders),
                queuedReplacementPending, savedOrder, savedAttackMoveX,
                savedAttackMoveY, savedMoveRange, savedAttackScanSleep,
                savedAttackMoveOpening);
    }

    public void restorePendingOrders(PendingOrderState state) {
        queuedOrders.clear();
        queuedOrders.addAll(state.queuedOrders());
        queuedReplacementPending = state.queuedReplacementPending();
        savedOrder = state.savedOrder();
        savedAttackMoveX = state.savedAttackMoveX();
        savedAttackMoveY = state.savedAttackMoveY();
        savedMoveRange = state.savedMoveRange();
        savedAttackScanSleep = state.savedAttackScanSleep();
        savedAttackMoveOpening = state.savedAttackMoveOpening();
    }

    public boolean hasQueuedOrders() {
        return !queuedOrders.isEmpty();
    }

    public void clearQueuedOrders() {
        queuedOrders.clear();
        queuedReplacementPending = false;
    }

    public boolean selected() {
        return selected;
    }

    public void setSelected(boolean selected) {
        this.selected = selected;
    }

    public boolean removed() {
        return removed;
    }

    public void setRemoved(boolean removed) {
        this.removed = removed;
    }

    public boolean destroyed() {
        return destroyed;
    }

    public void setDestroyed(boolean destroyed) {
        this.destroyed = destroyed;
    }

    /** Construction or training progress. */
    public int progress() {
        return progress;
    }

    public void setProgress(int progress) {
        this.progress = progress;
    }

    /** The progress value at which the work finishes. */
    public int progressGoal() {
        return progressGoal;
    }

    public void setProgressGoal(int progressGoal) {
        this.progressGoal = progressGoal;
    }

    /** Progress as a fraction, for a progress bar. */
    public double progressFraction() {
        return progressGoal <= 0 ? 0 : Math.min(1.0d, (double) progress / progressGoal);
    }

    /** What is being built or trained here. */
    public UnitType producing() {
        return producing;
    }

    /** The upgrade being researched here, or {@code null}. */
    public String researching() {
        return researching;
    }

    public void setResearching(String researching) {
        this.researching = researching;
    }

    public void setProducing(UnitType producing) {
        this.producing = producing;
    }

    public void enqueueTraining(UnitType type) {
        if (type != null) {
            trainingQueue.addLast(type);
        }
    }

    public UnitType pollTraining() {
        return trainingQueue.pollFirst();
    }

    public java.util.List<UnitType> trainingQueue() {
        return java.util.List.copyOf(trainingQueue);
    }

    /** Current production plus the jobs waiting behind it. */
    public int trainingJobCount() {
        return (producing == null ? 0 : 1) + trainingQueue.size();
    }

    public void clearTrainingQueue() {
        trainingQueue.clear();
    }

    /** What this worker has paid for and is walking to build. */
    public UnitType pendingBuild() {
        return pendingBuild;
    }

    public void setPendingBuild(UnitType pendingBuild) {
        this.pendingBuild = pendingBuild;
        this.buildTries = 0;
        this.buildRouteTries = 0;
        this.battleNetAiBuildTerminalRetry = false;
    }

    /**
     * An AI build replacement inherited an already-terminal native path.
     *
     * <p>Retail leaves the empty route buffer attached when the worker's
     * ready callback immediately reissues the same queued construction. The
     * following Build action therefore returns to ready without another point
     * search, even if local occupancy changed during its constructor delay.</p>
     */
    public boolean battleNetAiBuildTerminalRetry() {
        return battleNetAiBuildTerminalRetry;
    }

    public void setBattleNetAiBuildTerminalRetry(boolean retry) {
        battleNetAiBuildTerminalRetry = retry;
    }

    private boolean battleNetAiBuildTerminalRetry;

    /**
     * How many times this worker's walk to its site has answered unreachable.
     *
     * <p>{@code COrder_Build::MoveToLocation}'s other counter: the walk's
     * {@code PF_UNREACHABLE} arm does {@code this->State++} and retries each
     * quarter second until {@code State} reaches ten
     * The game a separate ledger from
     * {@link #buildTries}, which counts the ground being taken once the
     * walk has arrived.
     */
    public int buildRouteTries() {
        return buildRouteTries;
    }

    public void setBuildRouteTries(int tries) {
        this.buildRouteTries = tries;
    }

    private int buildRouteTries;

    /**
     * How many times this worker has found its building site blocked.
     *
     * <p>{@code COrder_Build} counts it in its own {@code State}: the order
     * arrives at {@code State_NearOfLocation}, 11, and every refusal from
     * {@code CheckCanBuild} does {@code this->State++} with
     * {@code unit.Wait = 10} -- "to keep the load low, retry each 10 cycles"
     * The order gives up only when
     * {@code State} reaches {@code State_StartBuilding_Failed}, 20. So a
     * worker whose ground is taken waits ninety cycles for it to clear before
     * it abandons the job.
     */
    public int buildTries() {
        return buildTries;
    }

    public void setBuildTries(int tries) {
        this.buildTries = tries;
    }

    private int buildTries;

    public int buildTileX() {
        return buildTileX;
    }

    public int buildTileY() {
        return buildTileY;
    }

    public void setBuildTile(int x, int y) {
        this.buildTileX = x;
        this.buildTileY = y;
    }

    public int buildGoalX() {
        return buildGoalX;
    }

    public int buildGoalY() {
        return buildGoalY;
    }

    public void setBuildGoal(int x, int y) {
        this.buildGoalX = x;
        this.buildGoalY = y;
    }

    /** The building a worker is inside, or the worker inside a building. */
    public Unit worksite() {
        return worksite;
    }

    public void setWorksite(Unit worksite) {
        this.worksite = worksite;
    }

    /**
     * The cycle this unit was last hit on, or nought if it never has been.
     *
     * <p>{@code CUnit::Attacked}. Its only job is the two-second gag on the
     * under-attack cue in {@code HitUnit_LastAttack}: a unit being shot is hit
     * several times a second, and without a per-unit memory of the last blow
     * one skirmish drowns out every other.
     */
    public long attackedCycle() {
        return attackedCycle;
    }

    public void setAttackedCycle(long cycle) {
        this.attackedCycle = cycle;
    }

    private long attackedCycle;

    /**
     * The player whose unit struck the killing blow, or {@code -1}.
     *
     * <p>Recorded because {@code HitUnit_IncreaseScoreForKill} credits the
     * attacker and nobody else, and the implementation had no way to say who that was.
     * Minus one covers every death nobody caused: a building cancelled, a
     * summoned creature timing out, a unit lost with the transport carrying it.
     */
    public int killedBy() {
        return killedBy;
    }

    public void setKilledBy(int player) {
        this.killedBy = player;
    }

    private int killedBy = -1;

    /** Units aboard this transport, in boarding order. */
    public java.util.List<Unit> cargo() {
        return cargo;
    }

    /** The transport carrying this unit, or {@code null}. */
    public Unit carrier() {
        return carrier;
    }

    public void setCarrier(Unit carrier) {
        this.carrier = carrier;
    }

    /** Whether this unit is aboard something. */
    public boolean isAboard() {
        return carrier != null;
    }

    /** Whether this transport has room. */
    public boolean hasRoom() {
        return cargo.size() < type.maxOnBoard();
    }

    /** How much the worker is carrying. */
    public int carried() {
        return carried;
    }

    public void setCarried(int carried) {
        this.carried = carried;
    }

    /** What the worker is carrying, or {@code null}. */
    public UnitType.Resource carrying() {
        return carrying;
    }

    public void setCarrying(UnitType.Resource carrying) {
        this.carrying = carrying;
    }

    /** What kind of load is actually in hand, or {@code null}. */
    public UnitType.Resource heldResource() {
        return heldResource;
    }

    public void setHeldResource(UnitType.Resource heldResource) {
        this.heldResource = heldResource;
    }

    /** The mine being worked, or {@code null} when the resource is terrain. */
    public Unit resourceUnit() {
        return resourceUnit;
    }

    public void setResourceUnit(Unit resourceUnit) {
        if (this.resourceUnit != resourceUnit) {
            clearBattleNetWoodCornerRefusal();
        }
        this.resourceUnit = resourceUnit;
    }

    public Unit resourceDepot() {
        return resourceDepot;
    }

    public void setResourceDepot(Unit resourceDepot) {
        this.resourceDepot = resourceDepot;
    }

    public Unit returnDepotGoal() {
        return returnDepotGoal;
    }

    public void setReturnDepotGoal(Unit returnDepotGoal) {
        this.returnDepotGoal = returnDepotGoal;
    }

    public int resourceTileX() {
        return resourceTileX;
    }

    public int resourceTileY() {
        return resourceTileY;
    }

    public void setResourceTile(int x, int y) {
        if (resourceTileX != x || resourceTileY != y) {
            clearBattleNetWoodCornerRefusal();
        }
        this.resourceTileX = x;
        this.resourceTileY = y;
    }

    /**
     * The wood order point last written for this harvest walk -- native
     * unit+0x84, which leftover dest-arm compares against.
     */
    public int battleNetWoodOrderX() {
        return battleNetWoodOrderX;
    }

    public int battleNetWoodOrderY() {
        return battleNetWoodOrderY;
    }

    public void setBattleNetWoodOrder(int x, int y) {
        battleNetWoodOrderX = x;
        battleNetWoodOrderY = y;
    }

    private int battleNetWoodOrderX = -1;
    private int battleNetWoodOrderY = -1;

    /** Whether the worker is heading back to unload. */
    public boolean returningToDepot() {
        return returningToDepot;
    }

    public void setReturningToDepot(boolean returningToDepot) {
        if (this.returningToDepot != returningToDepot) {
            resourceUnreachableTries = 0;
            resourceMoveCycles = 0;
        }
        this.returningToDepot = returningToDepot;
    }

    /** How many waits the harvest walk has answered towards its shove. */
    public int resourceWaitLadder() {
        return resourceWaitLadder;
    }

    public void setResourceWaitLadder(int resourceWaitLadder) {
        this.resourceWaitLadder = resourceWaitLadder;
    }

    public int resourceUnreachableTries() {
        return resourceUnreachableTries;
    }

    public void setResourceUnreachableTries(int tries) {
        this.resourceUnreachableTries = tries;
    }

    public int resourceMoveCycles() {
        return resourceMoveCycles;
    }

    public void countResourceMoveCycle() {
        resourceMoveCycles++;
    }

    public void setResourceMoveCycles(int cycles) {
        resourceMoveCycles = Math.max(0, cycles);
    }

    /** What this unit is attacking, or {@code null}. */
    public Unit target() {
        return target;
    }

    public void setTarget(Unit target) {
        if (this.target != target) {
            // A post-swing route refill is evidence about one particular
            // quarry. It cannot survive AutoSelectTarget replacing that
            // quarry: the replacement's first approach is an ordinary chase
            // with the native eight-pixel arrival band.
            battleNetAttackWaitRefillResidual = false;
            battleNetMovingQuarryResidual = false;
            battleNetWrappedCollisionRetryPark = false;
            battleNetStageSixCardinalProbePark = false;
            battleNetParkedRefusalHeading = -1;
            battleNetSaturatedCardinalRetryLoop = false;
            battleNetRangedCloseHitHelpWallFace = false;
            battleNetColdNoProgressRefusalLoop = false;
            battleNetPaidLongResidualRefill = false;
        }
        this.target = target;
        if (target == null) {
            attackRequiresVisibility = false;
        } else {
            // A populated attack goal is no longer the goal-less
            // FIRST_ENTRY represented by attackMoveOpening. Production code
            // clears it before AutoSelectTarget installs the goal; doing it
            // here also keeps reconstructed mid-order state unambiguous.
            attackMoveOpening = false;
        }
    }

    /**
     * How long this unit may not change target for.
     *
     * <p>{@code CUnit::Threshold}. Set to thirty cycles when a target is
     * picked automatically, counted down once a cycle, and consulted before
     * anything is allowed to re-aim the unit. Without it a soldier trading
     * blows with another soldier turns to face every peasant that wanders
     * past, and nobody ever finishes a fight.
     */
    public int threshold() {
        return threshold;
    }

    public void setThreshold(int threshold) {
        this.threshold = Math.max(0, threshold);
    }

    /**
     * How long this unit stays interested in whoever is shooting at it.
     *
     * <p>{@code CUnit::UnderAttack}. Set when the unit answers a blow, and
     * while it runs the unit refuses to swap its aggressor for a target that
     * cannot fight back. This is what stops a footman under fire from a
     * ballista breaking off to chase a peasant.
     */
    public int underAttack() {
        return underAttack;
    }

    public void setUnderAttack(int underAttack) {
        this.underAttack = Math.max(0, underAttack);
    }

    /**
     * Cycles left before this unit next looks for something to attack.
     *
     * <p>{@code COrder_Still::Sleep} and {@code COrder_Attack::Sleep}.
     * Upstream scans every fifteen cycles when idle and every six while
     * fighting; this implementation scanned every cycle over every unit on the map.
     */
    public int attackScanSleep() {
        return attackScanSleep;
    }

    public void setAttackScanSleep(int cycles) {
        this.attackScanSleep = Math.max(0, cycles);
    }

    public int randomMoveSleep() {
        return randomMoveSleep;
    }

    public void setRandomMoveSleep(int cycles) {
        this.randomMoveSleep = Math.max(0, cycles);
    }

    public int battleNetAnimationTimer() {
        return battleNetAnimationTimer;
    }

    public void setBattleNetAnimationTimer(int timer) {
        battleNetAnimationTimer = Math.max(0, timer) & 0xff;
    }

    public int battleNetSequenceOffset() {
        return battleNetSequenceOffset;
    }

    public void setBattleNetSequenceOffset(int offset) {
        battleNetSequenceOffset = Math.max(-1, offset);
    }

    /**
     * Whether this cycle's BNE move-sequence tick raised opcode zero so a
     * chase may take its next path heading.
     */
    public boolean battleNetChaseStepReady() {
        return battleNetChaseStepReady;
    }

    public void setBattleNetChaseStepReady(boolean ready) {
        battleNetChaseStepReady = ready;
    }

    private boolean battleNetChaseStepReady;

    /**
     * Residual of the first step after a chase replan that tore up a live
     * route must end with Attack animation four timer 3 before the next
     * heading (Human 13 ogre 1482). Free-approach continuous paths never set
     * this.
     */
    public boolean battleNetChaseReplanResidualHold() {
        return battleNetChaseReplanResidualHold;
    }

    public void setBattleNetChaseReplanResidualHold(boolean hold) {
        battleNetChaseReplanResidualHold = hold;
    }

    private boolean battleNetChaseReplanResidualHold;

    /**
     * A mobile retarget accepted from saturated collision pressure.
     *
     * <p>The replacement commits immediately, but the remaining approved
     * route stays owned by that paid generation through Attack construction
     * and its following Move refusal band.</p>
     */
    public boolean battleNetSaturatedRetargetRouteBand() {
        return battleNetSaturatedRetargetRouteBand;
    }

    public void setBattleNetSaturatedRetargetRouteBand(boolean band) {
        battleNetSaturatedRetargetRouteBand = band;
    }

    private boolean battleNetSaturatedRetargetRouteBand;

    /**
     * Whether a melee retarget route has paid its first residual Attack hold
     * and still owes retail's route-index-20 movement visit.
     */
    public boolean battleNetRetargetResidualRoutePark() {
        return battleNetRetargetResidualRoutePark;
    }

    public void setBattleNetRetargetResidualRoutePark(boolean park) {
        battleNetRetargetResidualRoutePark = park;
        if (!park) {
            battleNetLongPaidWrapTimerOneSeen = false;
        }
    }

    private boolean battleNetRetargetResidualRoutePark;

    /**
     * A retained four-byte attack-wrap tail has exposed Move timer one once
     * and owes its route-index-twenty park on the following callback.
     */
    public boolean battleNetLongPaidWrapTimerOneSeen() {
        return battleNetLongPaidWrapTimerOneSeen;
    }

    public void setBattleNetLongPaidWrapTimerOneSeen(boolean seen) {
        battleNetLongPaidWrapTimerOneSeen = seen;
    }

    private boolean battleNetLongPaidWrapTimerOneSeen;

    /**
     * Raw headings retained behind a paid wrap's parked route cursor, in
     * next-to-last consumption order.
     */
    public void parkBattleNetLongPaidWrapTail() {
        int retained = Math.max(0, pathLength - 1);
        battleNetLongPaidWrapParkedTail = new int[retained];
        for (int depth = 0; depth < retained; depth++) {
            battleNetLongPaidWrapParkedTail[depth] =
                    peekHeadingAtDepth(depth + 1);
        }
    }

    public int battleNetLongPaidWrapParkedTailLength() {
        return battleNetLongPaidWrapParkedTail == null
                ? 0 : battleNetLongPaidWrapParkedTail.length;
    }

    /** Whether native route bytes are still owned by a parked paid cursor. */
    public boolean hasBattleNetLongPaidWrapParkedRoute() {
        return battleNetLongPaidWrapParkedTail != null;
    }

    /** Marks a paid cursor whose following visit must redraw, not continue. */
    public void markBattleNetLongPaidWrapParkedRoute() {
        battleNetLongPaidWrapParkedTail = new int[0];
    }

    public void clearBattleNetLongPaidWrapParkedRoute() {
        battleNetLongPaidWrapParkedTail = null;
    }

    public int battleNetLongPaidWrapParkedTailHeading(int depth) {
        if (battleNetLongPaidWrapParkedTail == null
                || depth < 0
                || depth >= battleNetLongPaidWrapParkedTail.length) {
            return -1;
        }
        return battleNetLongPaidWrapParkedTail[depth];
    }

    public void setBattleNetLongPaidWrapParkedTail(int[] headings) {
        battleNetLongPaidWrapParkedTail = headings == null
                ? null : headings.clone();
    }

    public PathFinder.Path takeBattleNetLongPaidWrapParkedTail() {
        if (battleNetLongPaidWrapParkedTail == null
                || battleNetLongPaidWrapParkedTail.length == 0) {
            return null;
        }
        int[] stack = new int[battleNetLongPaidWrapParkedTail.length];
        for (int depth = 0; depth < stack.length; depth++) {
            stack[stack.length - 1 - depth] =
                    battleNetLongPaidWrapParkedTail[depth];
        }
        // Keep an empty marker through the first movement probe. It is also
        // the scheduler-visible proof that a later native pool slot will
        // vacate its current cell on this same simulation cycle.
        battleNetLongPaidWrapParkedTail = new int[0];
        return new PathFinder.Path(PathFinder.Result.FOUND, stack);
    }

    private int[] battleNetLongPaidWrapParkedTail;

    /**
     * The stale route behind a melee retarget was parked after its Attack
     * constructor, and the next Move visit is drawing the replacement. If
     * that replacement's first byte is cooperatively blocked, it begins a
     * fresh fifteen-count band rather than being mistaken for the second
     * refusal of the discarded residual. A saturated building replacement can
     * retain this transaction through its bounded naked formation retries.
     */
    public boolean battleNetRetargetResidualParkRefill() {
        return battleNetRetargetResidualParkRefill;
    }

    public void setBattleNetRetargetResidualParkRefill(boolean refill) {
        battleNetRetargetResidualParkRefill = refill;
        if (!refill) {
            battleNetRetargetResidualParkSteps = 0;
        }
    }

    private boolean battleNetRetargetResidualParkRefill;

    /**
     * Number of headings consumed from the route which armed
     * {@link #battleNetRetargetResidualParkRefill()}.
     *
     * <p>Parking writes native route index 20, which is represented by
     * {@link #clearPath()} here.  That necessarily erases
     * {@code initialLength - pathLength}, but the following Move visit still
     * branches on that generation.  Carry the count beside the park marker
     * until its one refill visit consumes it.</p>
     */
    public int battleNetRetargetResidualParkSteps() {
        return battleNetRetargetResidualParkSteps;
    }

    public void setBattleNetRetargetResidualParkSteps(int steps) {
        battleNetRetargetResidualParkSteps = Math.max(0, steps);
    }

    private int battleNetRetargetResidualParkSteps;

    /**
     * Refused compass byte retained by a native route-index-twenty park.
     *
     * <p>Java represents that parked cursor as an empty path. Keep the byte
     * separately for the immediately following route draw so wall following
     * continues from the refused face instead of restarting cold.</p>
     */
    public int battleNetParkedRefusalHeading() {
        return battleNetParkedRefusalHeading;
    }

    public void setBattleNetParkedRefusalHeading(int heading) {
        battleNetParkedRefusalHeading = heading >= 0
                && heading < Direction.COUNT ? heading : -1;
    }

    private int battleNetParkedRefusalHeading = -1;

    /**
     * Opposite wall-face byte retained by a saturated shared-buffer route.
     *
     * <p>At high collision/refusal pressure native's two obstacle probes can
     * share the same route buffer. The first opening byte commits while this
     * second byte remains behind the parked route cursor.</p>
     */
    public int battleNetSaturatedWallFacePairHeading() {
        return battleNetSaturatedWallFacePairHeading;
    }

    public void setBattleNetSaturatedWallFacePairHeading(int heading) {
        battleNetSaturatedWallFacePairHeading = heading >= 0
                && heading < Direction.COUNT ? heading : -1;
        if (battleNetSaturatedWallFacePairHeading < 0) {
            battleNetSaturatedWallFacePairParked = false;
        }
    }

    private int battleNetSaturatedWallFacePairHeading = -1;

    public boolean battleNetSaturatedWallFacePairParked() {
        return battleNetSaturatedWallFacePairParked;
    }

    public void setBattleNetSaturatedWallFacePairParked(boolean parked) {
        battleNetSaturatedWallFacePairParked = parked
                && battleNetSaturatedWallFacePairHeading >= 0;
    }

    private boolean battleNetSaturatedWallFacePairParked;

    /**
     * A full Move refusal band that hands an out-of-range chase through
     * Attack construction 3,2,1 before its cached route is parked.
     *
     * <p>XHuman 12 grunt 1496 reaches Move-start/timer one with five south
     * headings cached behind a cooperative mover. Retail displays Attack
     * animation four for three visits, then returns to Move-start and parks
     * route index 20. Without this handoff Java immediately replans onto the
     * free south-east square and walks into the battle one visit later.</p>
     */
    public boolean battleNetBlockedChaseAttackConstruction() {
        return battleNetBlockedChaseAttackConstruction;
    }

    public void setBattleNetBlockedChaseAttackConstruction(boolean hold) {
        battleNetBlockedChaseAttackConstruction = hold;
    }

    private boolean battleNetBlockedChaseAttackConstruction;

    /**
     * Empty-route retarget first step still owes its residual settle hold.
     *
     * <p>XHuman 12 grunt 1507 retargets after its old route exhausts and takes
     * N at fixture 36. Whether N came from a free-compass detour or directly
     * from the replacement route, native holds Attack-four / delay 2 when its
     * residual settles so E lands at 55. The flag survives that first step,
     * then empty-route replan same-cycle steps after the hold.</p>
     */
    public boolean battleNetEmptyRouteFreeDetourHold() {
        return battleNetEmptyRouteFreeDetourHold;
    }

    public void setBattleNetEmptyRouteFreeDetourHold(boolean hold) {
        battleNetEmptyRouteFreeDetourHold = hold;
    }

    private boolean battleNetEmptyRouteFreeDetourHold;

    /**
     * A refused route used one free-compass heading after its cooperative
     * wait. When that detour drains, retail parks route index 20 for one visit
     * before replanning rather than consuming the stale cached tail (XHuman
     * 12 grunt 1494, fixtures 37..54, and ogre 1527, fixtures 47..60).
     */
    public boolean battleNetNearlyFullFreeDetour() {
        return battleNetNearlyFullFreeDetour;
    }

    public void setBattleNetNearlyFullFreeDetour(boolean detour) {
        battleNetNearlyFullFreeDetour = detour;
    }

    private boolean battleNetNearlyFullFreeDetour;

    /**
     * A nearly-full melee chase buffer hard-parked after its first residual
     * and must serve retail's bounded direct-face collision retries before a
     * complete replacement route is drawn.
     */
    public boolean battleNetSaturatedResidualFaceRetry() {
        return battleNetSaturatedResidualFaceRetry;
    }

    public void setBattleNetSaturatedResidualFaceRetry(boolean retry) {
        battleNetSaturatedResidualFaceRetry = retry;
    }

    private boolean battleNetSaturatedResidualFaceRetry;

    /**
     * A saturated cardinal chase tail entered Attack construction directly
     * from its residual-settle Still callback.
     *
     * <p>When the first construction timer one remains blocked, retail
     * repeats that Still callback and opens one more 3,2,1 construction. The
     * retry consumes this bit. A visually equal route state first observed on
     * a later callback instead returns to Move, so the originating settle
     * visit must remain explicit provenance.</p>
     */
    public boolean battleNetSaturatedCardinalRetryLoop() {
        return battleNetSaturatedCardinalRetryLoop;
    }

    public void setBattleNetSaturatedCardinalRetryLoop(boolean retry) {
        battleNetSaturatedCardinalRetryLoop = retry;
    }

    private boolean battleNetSaturatedCardinalRetryLoop;

    /**
     * A one-heading melee retry was the visit which wrapped the native
     * collision nibble from fourteen to zero.
     *
     * <p>The following replacement byte is not a fresh cooperative refusal.
     * Retail exposes Move-start timers two and one, parks the route cursor at
     * twenty, then returns through the active-order idle callback. Keeping this
     * one-generation provenance separate prevents that byte from buying a new
     * fifteen-count refusal band.</p>
     */
    public boolean battleNetWrappedCollisionRetryPark() {
        return battleNetWrappedCollisionRetryPark;
    }

    public void setBattleNetWrappedCollisionRetryPark(boolean park) {
        battleNetWrappedCollisionRetryPark = park;
    }

    private boolean battleNetWrappedCollisionRetryPark;

    /**
     * A one-byte route admitted by Attack-refusal recovery has settled. Its
     * subsequent stage-six Move probes test only the refreshed direct compass
     * face until one is accepted; they do not start a full wall escape.
     */
    public boolean battleNetDirectRefusalRecoveryProbe() {
        return battleNetDirectRefusalRecoveryProbe;
    }

    public void setBattleNetDirectRefusalRecoveryProbe(boolean direct) {
        battleNetDirectRefusalRecoveryProbe = direct;
    }

    private boolean battleNetDirectRefusalRecoveryProbe;

    /**
     * A duplicate-cardinal route written by the final hard-refusal Move probe.
     *
     * <p>Retail keeps only the direct byte behind route index twenty and
     * revisits that same blocked face on every Move callback.  Java normally
     * rotates a refused route head around the blocker, so retain the native
     * parked-cursor provenance until the direct square opens or the refusal
     * handoff ends.</p>
     */
    public boolean battleNetStageSixCardinalProbePark() {
        return battleNetStageSixCardinalProbePark;
    }

    public void setBattleNetStageSixCardinalProbePark(boolean parked) {
        battleNetStageSixCardinalProbePark = parked;
    }

    private boolean battleNetStageSixCardinalProbePark;

    /**
     * The direct recovery probe came from a paid one-step approach that
     * finished at range two after repeated formation pressure. One rejected
     * direct face returns its next construction handoff to the complete wall
     * route writer instead of repeating the direct probe indefinitely.
     */
    public boolean battleNetSaturatedNearRecoveryFullRoute() {
        return battleNetSaturatedNearRecoveryFullRoute;
    }

    public void setBattleNetSaturatedNearRecoveryFullRoute(boolean fullRoute) {
        battleNetSaturatedNearRecoveryFullRoute = fullRoute;
    }

    private boolean battleNetSaturatedNearRecoveryFullRoute;

    /**
     * The bounded direct-recovery ladder installed a complete replacement
     * route and is paying its Move band. Its wake parks that retained route
     * for one callback instead of spending the first heading immediately.
     */
    public boolean battleNetDirectRefusalReplacementBand() {
        return battleNetDirectRefusalReplacementBand;
    }

    public void setBattleNetDirectRefusalReplacementBand(boolean band) {
        battleNetDirectRefusalReplacementBand = band;
    }

    private boolean battleNetDirectRefusalReplacementBand;

    /**
     * Refusal generation retained across the native active-order boundary.
     * The packed collision/refusal projections clear there; this separate
     * provenance decides whether later accepted residuals retry a direct face
     * or begin another cold wall search.
     */
    public int battleNetDirectRecoveryGeneration() {
        return battleNetDirectRecoveryGeneration;
    }

    public void setBattleNetDirectRecoveryGeneration(int generation) {
        battleNetDirectRecoveryGeneration = Math.max(0, generation);
    }

    private int battleNetDirectRecoveryGeneration;

    /**
     * A detached heading or paid bounded prefix has been approved but has not
     * committed yet. The next committed step promotes this to
     * {@link #battleNetNearlyFullFreeDetour()} so its stale surrogate tail
     * parks only after the approved heading's pixels drain.
     */
    public boolean battleNetMoveFreeDetourPending() {
        return battleNetMoveFreeDetourPending;
    }

    public void setBattleNetMoveFreeDetourPending(boolean pending) {
        battleNetMoveFreeDetourPending = pending;
    }

    private boolean battleNetMoveFreeDetourPending;

    /**
     * Multi-step leftover residual opened Attack at post-OP0; the next melee
     * OP10 may land damage without a presentation pend (Human 13 ogre 1510).
     */
    public boolean battleNetMultiLeftoverMelee() {
        return battleNetMultiLeftoverMelee;
    }

    public void setBattleNetMultiLeftoverMelee(boolean open) {
        battleNetMultiLeftoverMelee = open;
    }

    private boolean battleNetMultiLeftoverMelee;

    /**
     * Ranged chase residual opened Attack past OP0. Presentation must not
     * collapse the Attack wait into the same visit as OP10: that armed axe
     * 127's flight one cycle early on XHuman 12, spent an extra parabolic
     * stepMissiles draw at world 34, and REG'd catapult splash on tower 1370
     * (hp 82 vs 92 at fixture 35).
     */
    public boolean battleNetRangedResidualOpen() {
        return battleNetRangedResidualOpen;
    }

    public void setBattleNetRangedResidualOpen(boolean open) {
        battleNetRangedResidualOpen = open;
    }

    private boolean battleNetRangedResidualOpen;

    /**
     * Residual settle cleared an already-empty route (last step spent, pathn 0)
     * without multi-leftover open. Presentation may collapse the pre-OP10 wait
     * so a late OP0 still lands the blow on native's process cycle (Human 13
     * grunt 1507 / Java 93 at fixture 46). Path leftover cold settles must not
     * set this -- knight 1500 needs the full OP10 wait (fixture 50).
     */
    public boolean battleNetResidualEmptyRouteSettle() {
        return battleNetResidualEmptyRouteSettle;
    }

    public void setBattleNetResidualEmptyRouteSettle(boolean v) {
        battleNetResidualEmptyRouteSettle = v;
    }

    private boolean battleNetResidualEmptyRouteSettle;

    /**
     * How many mid-route residual settles this wood route has already paid.
     * Native marks route_index 20 (Orc 5 peons at fixture 38) before the third
     * and later free leftover steps; the first residual settle still commits
     * same-cycle (fixture 22). Cleared with the path.
     */
    public int battleNetWoodResidualSettles() {
        return battleNetWoodResidualSettles;
    }

    public void setBattleNetWoodResidualSettles(int settles) {
        battleNetWoodResidualSettles = Math.max(0, settles);
    }

    private int battleNetWoodResidualSettles;

    /**
     * One quiet visit is armed after a second-or-later resource residual
     * settle (native route_index 20). Gold uses it for an allied refusal;
     * terrain wood also carries it from a blocked-corner shortcut to the
     * rewritten route's first residual, which is then parked and replanned.
     */
    public boolean battleNetWoodRouteIndex20() {
        return battleNetWoodRouteIndex20;
    }

    public void setBattleNetWoodRouteIndex20(boolean armed) {
        battleNetWoodRouteIndex20 = armed;
    }

    private boolean battleNetWoodRouteIndex20;

    /**
     * A repeated cardinal wood residual retired stale refusal history and is
     * paying the fresh blocked-diagonal collision ladder before its wall-face
     * redraw. XHuman 12 peon 1376 parks south on fixture 215, counts the
     * blocked south-west corner through collision five, then redraws south on
     * fixture 220.
     */
    public boolean battleNetSaturatedWoodCornerLadder() {
        return battleNetSaturatedWoodCornerLadder;
    }

    public void setBattleNetSaturatedWoodCornerLadder(boolean armed) {
        battleNetSaturatedWoodCornerLadder = armed;
    }

    private boolean battleNetSaturatedWoodCornerLadder;

    /**
     * Far multi-step residual refuse hold (Orc 12 peon 1521). Armed on the
     * residual-settle refuse onto a cooperative gold ally when a free closer
     * neighbour also exists; keeps FUN_004379e0 coll bands 1..7 (timer 1)
     * then coll>=8 (fourteen remaining quiet visits + replan) on later OP0s.
     * Standing jams free-detour; cooperative corridor soft-waits do not arm.
     */
    public boolean battleNetFarMultiStepResidualRefuse() {
        return battleNetFarMultiStepResidualRefuse;
    }

    public void setBattleNetFarMultiStepResidualRefuse(boolean armed) {
        battleNetFarMultiStepResidualRefuse = armed;
    }

    private boolean battleNetFarMultiStepResidualRefuse;

    /**
     * Gold soft-wait of 14 was armed on a residual-settle refuse. Free-wake
     * when the planned next cell clears (XHuman 7 peon 1446); ordinary mid-
     * path soft-waits must count out fully (XHuman 9/10/12 peons).
     */
    public boolean battleNetGoldSoftWaitFreeWake() {
        return battleNetGoldSoftWaitFreeWake;
    }

    public void setBattleNetGoldSoftWaitFreeWake(boolean armed) {
        battleNetGoldSoftWaitFreeWake = armed;
    }

    private boolean battleNetGoldSoftWaitFreeWake;

    /**
     * Attack program was cold-restarted from a live Move cursor while already
     * in weapon range. The next in-range OP0 stalls on the attack-start
     * offset rather than walking into OP10: ranged units seal timer 63
     * (Human 13 axes 1483 and 1505), melee units seal the Attack body wait
     * minus one (Human 13 ogre 1491).
     */
    public boolean battleNetAttackResumeFromMove() {
        return battleNetAttackResumeFromMove;
    }

    public void setBattleNetAttackResumeFromMove(boolean resume) {
        battleNetAttackResumeFromMove = resume;
    }

    private boolean battleNetAttackResumeFromMove;

    /**
     * Opening Attack OP0 fired while the target was out of weapon range
     * (approach). Combined with {@link #battleNetAttackResumeFromMove}, this
     * selects the Human 13 axe 1483 pre-fire stall without blocking first
     * in-range swings after a pure Move→Attack cold start.
     */
    public boolean battleNetAttackOp0OutOfRange() {
        return battleNetAttackOp0OutOfRange;
    }

    public void setBattleNetAttackOp0OutOfRange(boolean out) {
        battleNetAttackOp0OutOfRange = out;
    }

    private boolean battleNetAttackOp0OutOfRange;

    /**
     * Native action handoff after a refused attack approach.
     *
     * <p>Stage 1 waits for Move's complete refusal band to expire, stage 2
     * pays Attack-start construction 3,2,1, and stage 3 lets the following
     * Move visit select and cache a replacement route without consuming its
     * first heading. XHuman 10 knight 1493 is the authenticated witness.</p>
     */
    public int battleNetAttackRefusalRecoveryStage() {
        return battleNetAttackRefusalRecoveryStage;
    }

    public void setBattleNetAttackRefusalRecoveryStage(int stage) {
        // Stages four through six are the hard-refusal twin of the original
        // cooperative handoff.  They retain native ownership across the
        // fifteen-count Move band, Attack construction, and the one Move
        // probe which either takes the newly-free heading or returns to
        // Attack construction.  SaveGame already persists this integer, so
        // keeping the phases here also prevents a mid-jam reload from
        // turning a blocked combatant into a permanently frozen one. Stages
        // seven through twelve are the expired moving-quarry twins: first-band
        // tail, optional second Move band, Attack construction, and the
        // single-band entrance to and exit from the committed melee body hold,
        // followed by its one fresh route constructor. Stage thirteen retains
        // a just-surfaced laden quarry through its already-open Attack 3,2,1
        // before the adjacent replacement scan starts a second constructor.
        battleNetAttackRefusalRecoveryStage = Math.max(0, Math.min(13, stage));
        if (battleNetAttackRefusalRecoveryStage != 6) {
            battleNetStageSixCardinalProbePark = false;
        }
    }

    private int battleNetAttackRefusalRecoveryStage;

    /**
     * A hard-refusal stage-six probe was accepted after Attack construction
     * completed. Its live chase residual returns past OP0 instead of buying a
     * second constructor when it settles in range.
     */
    public boolean battleNetPaidRefusalRecoveryApproach() {
        return battleNetPaidRefusalRecoveryApproach;
    }

    public void setBattleNetPaidRefusalRecoveryApproach(boolean paid) {
        battleNetPaidRefusalRecoveryApproach = paid;
    }

    private boolean battleNetPaidRefusalRecoveryApproach;

    /**
     * Melee Attack tail wrap named an out-of-range quarry after the old one
     * died. Construction 3,2,1 stays on Attack start; the next OP0 dest-arms
     * leftover instead of walking into windup or Still.
     */
    public boolean battleNetAttackWrapDestArmPending() {
        return battleNetAttackWrapDestArmPending;
    }

    public void setBattleNetAttackWrapDestArmPending(boolean pending) {
        battleNetAttackWrapDestArmPending = pending;
    }

    private boolean battleNetAttackWrapDestArmPending;

    /**
     * Action-16 out-of-range recovery already spent its one-visit hold
     * (XHuman 2 footman 1548). Cleared when a new attack order arms.
     */
    public boolean battleNetStationaryRecoveryHeld() {
        return battleNetStationaryRecoveryHeld;
    }

    public void setBattleNetStationaryRecoveryHeld(boolean held) {
        battleNetStationaryRecoveryHeld = held;
    }

    private boolean battleNetStationaryRecoveryHeld;

    /**
     * Person spatial-help just promoted this unit; the first chase path may
     * prefer an equal-cost goal-axis diagonal onto a lead mid-Move brother
     * (XHuman 10 knight 1493). Cleared when that first path is installed.
     */
    public boolean battleNetPersonHelpFirstChase() {
        return battleNetPersonHelpFirstChase;
    }

    public void setBattleNetPersonHelpFirstChase(boolean first) {
        battleNetPersonHelpFirstChase = first;
    }

    private boolean battleNetPersonHelpFirstChase;

    /**
     * This attack was promoted from a person's lethal-splash help offer.
     * Retained through the first chase so its later command-to-auto handoff
     * can pay native's Attack construction delay exactly once. After a person
     * shoreline spatial-help route ends, the same order-owned bit spans the
     * single Move-OP0 to Still-constructor visit; it is cleared there.
     */
    public boolean battleNetPersonSplashHelpAttack() {
        return battleNetPersonSplashHelpAttack;
    }

    public void setBattleNetPersonSplashHelpAttack(boolean helpAttack) {
        battleNetPersonSplashHelpAttack = helpAttack;
    }

    private boolean battleNetPersonSplashHelpAttack;

    /**
     * The settled splash-help chase is paying Attack start 3,2,1 before its
     * first automatic retarget (XHuman 10 knight 1480, fixtures 61--63).
     */
    public boolean battleNetPersonHelpRetargetHandoff() {
        return battleNetPersonHelpRetargetHandoff;
    }

    public void setBattleNetPersonHelpRetargetHandoff(boolean handoff) {
        battleNetPersonHelpRetargetHandoff = handoff;
    }

    private boolean battleNetPersonHelpRetargetHandoff;

    /**
     * A person's standing land defender promoted a native HitUnit offer and
     * still owes AutoSelectTarget on the queued Attack's timer-one handoff.
     * Kept separate from the generic construction handoff because settled
     * chase retargets use that constructor without owning a hit-help scan.
     */
    public boolean battleNetPersonHitHelpAutoSelectHandoff() {
        return battleNetPersonHitHelpAutoSelectHandoff;
    }

    public void setBattleNetPersonHitHelpAutoSelectHandoff(boolean handoff) {
        battleNetPersonHitHelpAutoSelectHandoff = handoff;
    }

    private boolean battleNetPersonHitHelpAutoSelectHandoff;

    /**
     * A queued {@code 0x0040a9d0} spatial hit-help order is pending or paying
     * its opening Attack construction. The timer-one handoff owns the first
     * native compass byte toward the aggressor. Person naval HitUnit also
     * uses this provenance for its land shoreline defenders.
     */
    public boolean battleNetSpatialHitHelpHandoff() {
        return battleNetSpatialHitHelpHandoff;
    }

    public void setBattleNetSpatialHitHelpHandoff(boolean handoff) {
        battleNetSpatialHitHelpHandoff = handoff;
    }

    private boolean battleNetSpatialHitHelpHandoff;

    /**
     * A person's standing melee defender accepted close HitUnit help from a
     * ranged aggressor. Retail keeps the first successful clockwise wall face
     * for that first chase only (XHuman 12 footman 1477); ordinary spatial
     * help and direct melee HitUnit offers use their existing handoffs.
     */
    public boolean battleNetRangedCloseHitHelpWallFace() {
        return battleNetRangedCloseHitHelpWallFace;
    }

    public void setBattleNetRangedCloseHitHelpWallFace(boolean retain) {
        battleNetRangedCloseHitHelpWallFace = retain;
    }

    private boolean battleNetRangedCloseHitHelpWallFace;

    /**
     * A sea patrol's queued position attack is paying Attack construction
     * before its first chase stride. The timer-one visit may enter a lane a
     * later native-slot patrol ship vacates in the same scheduler cycle.
     */
    public boolean battleNetNavalPatrolAttackConstruction() {
        return battleNetNavalPatrolAttackConstruction;
    }

    public void setBattleNetNavalPatrolAttackConstruction(boolean active) {
        battleNetNavalPatrolAttackConstruction = active;
    }

    private boolean battleNetNavalPatrolAttackConstruction;

    public boolean battleNetNavalPatrolAttackTimerOneReady() {
        return battleNetNavalPatrolAttackTimerOneReady;
    }

    public void setBattleNetNavalPatrolAttackTimerOneReady(boolean ready) {
        battleNetNavalPatrolAttackTimerOneReady = ready;
    }

    private boolean battleNetNavalPatrolAttackTimerOneReady;

    /**
     * A behavior-two land Patrol has handed its committed opening stride to
     * a queued direct Attack. Retail lets Attack own the residual-settle
     * visit, pays its 3,2,1 constructor, then parks the Patrol route at index
     * twenty before the chase may draw a replacement.
     */
    public boolean battleNetLandPatrolAttackConstruction() {
        return battleNetLandPatrolAttackConstruction;
    }

    public void setBattleNetLandPatrolAttackConstruction(boolean active) {
        battleNetLandPatrolAttackConstruction = active;
    }

    private boolean battleNetLandPatrolAttackConstruction;

    /** The first direct-Attack route after a land Patrol handoff is pending. */
    public boolean battleNetLandPatrolAttackRoutePending() {
        return battleNetLandPatrolAttackRoutePending;
    }

    public void setBattleNetLandPatrolAttackRoutePending(boolean pending) {
        battleNetLandPatrolAttackRoutePending = pending;
    }

    private boolean battleNetLandPatrolAttackRoutePending;

    /** A residual route park returns through active-order idle next visit. */
    public boolean battleNetResidualEmptyApproachIdlePending() {
        return battleNetResidualEmptyApproachIdlePending;
    }

    public void setBattleNetResidualEmptyApproachIdlePending(
            boolean pending) {
        battleNetResidualEmptyApproachIdlePending = pending;
    }

    private boolean battleNetResidualEmptyApproachIdlePending;

    /**
     * walkTowards temporarily sets order to MOVE for stepMove. Residual empty-
     * route Still-promotion must not arm on borrowed Move (XHuman 2 peon 1530
     * free-prefix replan delayed three cycles by Attack construction delay 2).
     */
    public boolean battleNetBorrowedMoveForStep() {
        return battleNetBorrowedMoveForStep;
    }

    public void setBattleNetBorrowedMoveForStep(boolean borrowed) {
        battleNetBorrowedMoveForStep = borrowed;
    }

    private boolean battleNetBorrowedMoveForStep;

    /**
     * This borrowed Move step belongs to GiveOrder 27, so residual follows
     * script.bin rather than the ChonkCraft Move wait.
     */
    public boolean battleNetRepairStride() {
        return battleNetRepairStride;
    }

    public void setBattleNetRepairStride(boolean repairStride) {
        battleNetRepairStride = repairStride;
    }

    private boolean battleNetRepairStride;

    /**
     * Approach+resume OP0 hold is active (timer 63 on attackStart). Presentation
     * must not queue a projectile until the stall ends.
     */
    public boolean battleNetAttackResumeHoldActive() {
        return battleNetAttackResumeHoldActive;
    }

    public void setBattleNetAttackResumeHoldActive(boolean active) {
        battleNetAttackResumeHoldActive = active;
    }

    private boolean battleNetAttackResumeHoldActive;

    /**
     * Remaining wall-clock cadence for a mobile ranged attack.
     *
     * <p>BNE keeps its attack wait byte counting while a thrower is retargeting
     * and walking.  On arrival the remaining value becomes the Attack-start
     * hold; restarting the full animation-body wait makes ranged units stand
     * idle for an extra chase-length before they fire.</p>
     */
    public int battleNetRangedAttackCadenceRemaining() {
        return battleNetRangedAttackCadenceRemaining;
    }

    public void setBattleNetRangedAttackCadenceRemaining(int remaining) {
        battleNetRangedAttackCadenceRemaining = Math.max(0, remaining);
    }

    private int battleNetRangedAttackCadenceRemaining;

    /**
     * Ranged free-scan armed the approach+resume flags that will seal timer 63.
     * Cleared when the hold ends or is cancelled.
     */
    public boolean battleNetRangedFreeScanHoldPending() {
        return battleNetRangedFreeScanHoldPending;
    }

    public void setBattleNetRangedFreeScanHoldPending(boolean pending) {
        battleNetRangedFreeScanHoldPending = pending;
    }

    private boolean battleNetRangedFreeScanHoldPending;

    /**
     * Timer-63 hold came from a ranged free-scan retarget (XHuman 10 archer 98),
     * not a pure approach+resume. Projectile free-cycle order only reorders when
     * this is set -- approach holds alone REGd Human 13 knight splash.
     */
    public boolean battleNetRangedFreeScanHoldActive() {
        return battleNetRangedFreeScanHoldActive;
    }

    public void setBattleNetRangedFreeScanHoldActive(boolean active) {
        battleNetRangedFreeScanHoldActive = active;
    }

    private boolean battleNetRangedFreeScanHoldActive;

    /**
     * Took damage while the Attack sequence cursor sat on attack-start OP0.
     * The next OP0 fire stays on attackStart with timer
     * {@code bodyWaitSum - 1} instead of entering windup (Human 13 knight
     * 1490 after catapult splash: timer 23, no OP10 through fixture 44).
     */
    public boolean battleNetAttackOp0Damaged() {
        return battleNetAttackOp0Damaged;
    }

    public void setBattleNetAttackOp0Damaged(boolean damaged) {
        battleNetAttackOp0Damaged = damaged;
    }

    private boolean battleNetAttackOp0Damaged;

    /**
     * Transport has already taken its construction-time AE30 fly-timer draw.
     * Later Still-loop re-arms must not draw (Orc 14 post-harvest transports).
     */
    public boolean battleNetTransportFlyDrawn() {
        return battleNetTransportFlyDrawn;
    }

    public void setBattleNetTransportFlyDrawn(boolean drawn) {
        battleNetTransportFlyDrawn = drawn;
    }

    private boolean battleNetTransportFlyDrawn;

    /**
     * Sequence OP10 already applied this swing's melee; presentation must not
     * land a second blow (double async rolls after multi-leftover open).
     */
    public boolean battleNetSequenceMeleeLanded() {
        return battleNetSequenceMeleeLanded;
    }

    public void setBattleNetSequenceMeleeLanded(boolean landed) {
        battleNetSequenceMeleeLanded = landed;
    }

    private boolean battleNetSequenceMeleeLanded;

    /**
     * This chase leg was primed from a standstill, so it owes no pace on the
     * cycle it commits.
     *
     * <p>Human 13 ogre 1482 pauses on 124,32 for fixtures 31 to 33 and steps
     * at 34. Retail leaves it drawn at 3968,1024 for that cycle and starts the
     * glide at 35; this implementation walked it on the commit, drew 3965,1021, and put
     * every later pixel one cycle early, so it arrived at 45 instead of 46 and
     * wounded the wise man at 52 where retail does it at 53.</p>
     */
    public boolean battleNetChaseLegOpensCold() {
        return battleNetChaseLegOpensCold;
    }

    public void setBattleNetChaseLegOpensCold(boolean cold) {
        battleNetChaseLegOpensCold = cold;
    }

    private boolean battleNetChaseLegOpensCold;

    /**
     * An exhausted AttackTarget swing rebuilt its chase route on that same
     * visit. The resulting borrowed Move leftover remains owned by that
     * refill until all of its pixels are spent.
     */
    public boolean battleNetAttackWaitRefillResidual() {
        return battleNetAttackWaitRefillResidual;
    }

    public void setBattleNetAttackWaitRefillResidual(boolean residual) {
        battleNetAttackWaitRefillResidual = residual;
    }

    private boolean battleNetAttackWaitRefillResidual;

    /** A moving quarry keeps the current chase residual Attack-owned. */
    public boolean battleNetMovingQuarryResidual() {
        return battleNetMovingQuarryResidual;
    }

    public void setBattleNetMovingQuarryResidual(boolean residual) {
        battleNetMovingQuarryResidual = residual;
    }

    private boolean battleNetMovingQuarryResidual;

    /**
     * Combat chase just rebuilt from an empty/exhausted route (not a mid-route
     * retarget). Soft-cleared first steps must free-detour rather than wait
     * fourteen (XHuman 12 grunt 1507).
     */
    public boolean battleNetChaseEmptyRouteReplan() {
        return battleNetChaseEmptyRouteReplan;
    }

    public void setBattleNetChaseEmptyRouteReplan(boolean replan) {
        battleNetChaseEmptyRouteReplan = replan;
    }

    private boolean battleNetChaseEmptyRouteReplan;

    /** A paid long chase residual parked for its next-callback wall refill. */
    public boolean battleNetPaidLongResidualRefill() {
        return battleNetPaidLongResidualRefill;
    }

    public void setBattleNetPaidLongResidualRefill(boolean refill) {
        battleNetPaidLongResidualRefill = refill;
    }

    private boolean battleNetPaidLongResidualRefill;

    /** A cold mobile-quarry handoff keeps retrying while no free step closes. */
    public boolean battleNetColdNoProgressRefusalLoop() {
        return battleNetColdNoProgressRefusalLoop;
    }

    public void setBattleNetColdNoProgressRefusalLoop(boolean retry) {
        battleNetColdNoProgressRefusalLoop = retry;
    }

    private boolean battleNetColdNoProgressRefusalLoop;

    public int battleNetIdlePhase() {
        return battleNetIdlePhase;
    }

    public void setBattleNetIdlePhase(int phase) {
        battleNetIdlePhase = Math.max(0, phase);
    }

    public boolean battleNetTowerActive() {
        return battleNetTowerActive;
    }

    public void setBattleNetTowerActive(boolean active) {
        battleNetTowerActive = active;
    }

    public int battleNetFlyingIdleTimer() {
        return battleNetFlyingIdleTimer;
    }

    public void setBattleNetFlyingIdleTimer(int timer) {
        battleNetFlyingIdleTimer = Math.max(0, timer) & 0xff;
    }

    public int battleNetOrderDelay() {
        return battleNetOrderDelay;
    }

    public boolean battleNetPlayerCommandMove() {
        return battleNetPlayerCommandMove;
    }

    public void setBattleNetPlayerCommandMove(boolean playerCommandMove) {
        battleNetPlayerCommandMove = playerCommandMove;
    }

    public boolean battleNetAttackGroundMove() {
        return battleNetAttackGroundMove;
    }

    public void setBattleNetAttackGroundMove(boolean attackGroundMove) {
        battleNetAttackGroundMove = attackGroundMove;
    }

    public boolean battleNetStopAfterLeftover() {
        return battleNetStopAfterLeftover;
    }

    public void setBattleNetStopAfterLeftover(boolean stopAfterLeftover) {
        battleNetStopAfterLeftover = stopAfterLeftover;
    }

    /**
     * The cycle this worker last handed a build job back, or a long time ago.
     *
     * <p>Retail's stand-down after a hand-back is carried on the unit's own
     * timer at {@code unit+0x07}, which counts down for a unit standing still.
     * This implementation's {@code battleNetOrderDelay} is only decremented inside the
     * harvest and combat actions, so a Still worker's would never expire.
     */
    public long battleNetBuildHandBackCycle() {
        return battleNetBuildHandBackCycle;
    }

    public void setBattleNetBuildHandBackCycle(long cycle) {
        this.battleNetBuildHandBackCycle = cycle;
    }

    private long battleNetBuildHandBackCycle = Long.MIN_VALUE / 2;

    public void setBattleNetOrderDelay(int delay) {
        battleNetOrderDelay = Math.max(0, delay);
        if (battleNetOrderDelay == 0) {
            battleNetRefusalHold = false;
        }
    }

    public boolean battleNetRefusalHold() {
        return battleNetRefusalHold;
    }

    public void setBattleNetRefusalHold(boolean hold) {
        battleNetRefusalHold = hold;
    }

    private boolean battleNetRefusalHold;

    public int battleNetConstructionHpPool() {
        return battleNetConstructionHpPool;
    }

    public void setBattleNetConstructionHpPool(int pool) {
        battleNetConstructionHpPool = Math.max(0, pool);
    }

    public int battleNetSelfPatrolHolds() {
        return battleNetSelfPatrolHolds;
    }

    public void setBattleNetSelfPatrolHolds(int holds) {
        battleNetSelfPatrolHolds = Math.max(0, holds);
    }

    public boolean battleNetFlyerScoutExhausted() {
        return battleNetFlyerScoutExhausted;
    }

    public void setBattleNetFlyerScoutExhausted(boolean exhausted) {
        battleNetFlyerScoutExhausted = exhausted;
    }

    /**
     * Whether the live Patrol is a one-shot scout dest, not a player beat.
     *
     * <p>Human 12's balloon-class flyers receive a half-map point from the
     * ready/scout pass. Native goes Still when residual settles on that
     * square (zeppelin 1570: 50,4 Still at fixture 63). Treating it as an
     * ordinary two-point Patrol used to swap back toward 46,10 and leave
     * at 66.</p>
     */
    public boolean battleNetScoutPatrol() {
        return battleNetScoutPatrol;
    }

    public void setBattleNetScoutPatrol(boolean scoutPatrol) {
        battleNetScoutPatrol = scoutPatrol;
    }

    public boolean battleNetDoubleStep() {
        return battleNetDoubleStep;
    }

    public void setBattleNetDoubleStep(boolean doubleStep) {
        battleNetDoubleStep = doubleStep;
    }

    public int battleNetMovePaceOffset() {
        return battleNetMovePaceOffset;
    }

    public void setBattleNetMovePaceOffset(int offset) {
        battleNetMovePaceOffset = offset;
    }

    public int battleNetMovePaceTimer() {
        return battleNetMovePaceTimer;
    }

    public void setBattleNetMovePaceTimer(int timer) {
        battleNetMovePaceTimer = Math.max(0, timer);
    }

    public boolean hasBattleNetPendingPatrol() {
        return battleNetPendingPatrolX >= 0 && battleNetPendingPatrolY >= 0;
    }

    public int battleNetPendingPatrolX() {
        return battleNetPendingPatrolX;
    }

    public int battleNetPendingPatrolY() {
        return battleNetPendingPatrolY;
    }

    public void setBattleNetPendingPatrol(int x, int y) {
        setBattleNetPendingPatrol(x, y, -1, -1);
    }

    public void setBattleNetPendingPatrol(int x, int y,
            int backX, int backY) {
        battleNetPendingPatrolX = x;
        battleNetPendingPatrolY = y;
        battleNetPendingPatrolBackX = backX;
        battleNetPendingPatrolBackY = backY;
    }

    public boolean hasBattleNetPendingPatrolBack() {
        return battleNetPendingPatrolBackX >= 0
                && battleNetPendingPatrolBackY >= 0;
    }

    public int battleNetPendingPatrolBackX() {
        return battleNetPendingPatrolBackX;
    }

    public int battleNetPendingPatrolBackY() {
        return battleNetPendingPatrolBackY;
    }

    public void clearBattleNetPendingPatrol() {
        battleNetPendingPatrolX = -1;
        battleNetPendingPatrolY = -1;
        battleNetPendingPatrolBackX = -1;
        battleNetPendingPatrolBackY = -1;
    }

    public boolean hasBattleNetPendingMove() {
        return battleNetPendingMoveX >= 0 && battleNetPendingMoveY >= 0;
    }

    public int battleNetPendingMoveX() {
        return battleNetPendingMoveX;
    }

    public int battleNetPendingMoveY() {
        return battleNetPendingMoveY;
    }

    public void setBattleNetPendingMove(int x, int y) {
        battleNetPendingMoveX = x;
        battleNetPendingMoveY = y;
    }

    public void clearBattleNetPendingMove() {
        battleNetPendingMoveX = -1;
        battleNetPendingMoveY = -1;
    }

    public boolean hasBattleNetAiHome() {
        return battleNetAiHomeX >= 0 && battleNetAiHomeY >= 0;
    }

    public int battleNetAiHomeX() {
        return battleNetAiHomeX;
    }

    public int battleNetAiHomeY() {
        return battleNetAiHomeY;
    }

    public void setBattleNetAiHome(int x, int y) {
        battleNetAiHomeX = x;
        battleNetAiHomeY = y;
    }

    public int battleNetAiBehavior() {
        return battleNetAiBehavior;
    }

    public void setBattleNetAiBehavior(int behavior) {
        battleNetAiBehavior = Math.max(0, behavior) & 0xff;
    }

    public int battleNetConstructorStreamBurns() {
        return battleNetConstructorStreamBurns;
    }

    public void setBattleNetConstructorStreamBurns(int burns, int afterCycle) {
        battleNetConstructorStreamBurns = Math.max(0, burns);
        battleNetConstructorBurnAfterCycle = afterCycle;
    }

    public int battleNetConstructorBurnAfterCycle() {
        return battleNetConstructorBurnAfterCycle;
    }

    public boolean battleNetPendingMeleeSyncRand() {
        return battleNetPendingMeleeSyncRand;
    }

    public void setBattleNetPendingMeleeSyncRand(boolean pending) {
        battleNetPendingMeleeSyncRand = pending;
    }

    public int battleNetMeleeSyncRemaining() {
        return battleNetMeleeSyncRemaining;
    }

    public void setBattleNetMeleeSyncRemaining(int cycles) {
        battleNetMeleeSyncRemaining = Math.max(0, cycles);
    }

    public boolean battleNetOilStartedAdjacent() {
        return battleNetOilStartedAdjacent;
    }

    public void setBattleNetOilStartedAdjacent(boolean adjacent) {
        battleNetOilStartedAdjacent = adjacent;
    }

    public BattleNetOilAction battleNetOilAction() {
        return battleNetOilAction;
    }

    public void setBattleNetOilAction(BattleNetOilAction action) {
        battleNetOilAction = action == null
                ? BattleNetOilAction.TO_RESOURCE : action;
    }

    public int battleNetOilActionTicks() {
        return battleNetOilActionTicks;
    }

    public void setBattleNetOilActionTicks(int ticks) {
        battleNetOilActionTicks = Math.max(0, ticks);
    }

    public boolean battleNetGoldLongApproach() {
        return battleNetGoldLongApproach;
    }

    public void setBattleNetGoldLongApproach(boolean longApproach) {
        battleNetGoldLongApproach = longApproach;
    }

    public boolean battleNetGoldFreePrefix() {
        return battleNetGoldFreePrefix;
    }

    public void setBattleNetGoldFreePrefix(boolean freePrefix) {
        battleNetGoldFreePrefix = freePrefix;
        if (!freePrefix) {
            battleNetGoldFreePrefixLength = 0;
        }
    }

    public int battleNetGoldFreePrefixLength() {
        return battleNetGoldFreePrefixLength;
    }

    public void setBattleNetGoldFreePrefixLength(int length) {
        battleNetGoldFreePrefixLength = Math.max(0, length);
    }

    public boolean battleNetResourceApproachStaged() {
        return battleNetResourceApproachStaged;
    }

    public void setBattleNetResourceApproachStaged(boolean staged) {
        battleNetResourceApproachStaged = staged;
    }

    public boolean battleNetResourceApproachRedirect() {
        return battleNetResourceApproachRedirect;
    }

    public void setBattleNetResourceApproachRedirect(boolean redirect) {
        battleNetResourceApproachRedirect = redirect;
    }

    public Unit battleNetPendingTransportTarget() {
        return battleNetPendingTransportTarget;
    }

    public void setBattleNetPendingTransportTarget(Unit target) {
        battleNetPendingTransportTarget = target;
    }

    public Unit battleNetPendingHelpAttack() {
        return battleNetPendingHelpAttack;
    }

    public void setBattleNetPendingHelpAttack(Unit target) {
        battleNetPendingHelpAttack = target;
    }

    public boolean battleNetPendingCloseHitHelp() {
        return battleNetPendingCloseHitHelp;
    }

    public void setBattleNetPendingCloseHitHelp(boolean pending) {
        battleNetPendingCloseHitHelp = pending;
    }
    public boolean battleNetReadySuppressed() {
        return battleNetReadySuppressed;
    }

    public void setBattleNetReadySuppressed(boolean suppressed) {
        battleNetReadySuppressed = suppressed;
    }

    public boolean battleNetMapPlaced() {
        return battleNetMapPlaced;
    }

    public void setBattleNetMapPlaced(boolean mapPlaced) {
        battleNetMapPlaced = mapPlaced;
    }

    public boolean battleNetStationaryAttack() {
        return battleNetStationaryAttack;
    }

    public void setBattleNetStationaryAttack(boolean stationary) {
        battleNetStationaryAttack = stationary;
    }

    public boolean battleNetOccupiedEmptyReWander() {
        return battleNetOccupiedEmptyReWander;
    }

    public void setBattleNetOccupiedEmptyReWander(boolean pending) {
        battleNetOccupiedEmptyReWander = pending;
        if (!pending) {
            battleNetOccupiedEmptyNoWanderCount = 0;
            battleNetOccupiedEmptyForceWander = false;
        }
    }

    public boolean battleNetOccupiedEmptyForceWander() {
        return battleNetOccupiedEmptyForceWander;
    }

    public void setBattleNetOccupiedEmptyForceWander(boolean force) {
        battleNetOccupiedEmptyForceWander = force;
    }

    public int battleNetOccupiedEmptyNoWanderCount() {
        return battleNetOccupiedEmptyNoWanderCount;
    }

    public void addBattleNetOccupiedEmptyNoWander() {
        battleNetOccupiedEmptyNoWanderCount++;
    }

    public boolean battleNetCoastEmptyExtraWait() {
        return battleNetCoastEmptyExtraWait;
    }

    public void setBattleNetCoastEmptyExtraWait(boolean extraWait) {
        battleNetCoastEmptyExtraWait = extraWait;
    }

    public int battleNetAiTrainCounter() {
        return battleNetAiTrainCounter;
    }

    public void setBattleNetAiTrainCounter(int counter) {
        battleNetAiTrainCounter = Math.max(0, counter);
    }

    public int battleNetPudData() {
        return battleNetPudData;
    }

    public void setBattleNetPudData(int data) {
        battleNetPudData = data;
    }

    /**
     * Whether the current attack order was the unit's own idea.
     *
     * <p>{@code COrder_Attack::IsAutoTargeting}. A target the unit chose for
     * itself may be swapped for a better one; a target the player clicked on
     * may not.
     */
    public boolean autoTargeting() {
        return autoTargeting;
    }

    public void setAutoTargeting(boolean autoTargeting) {
        this.autoTargeting = autoTargeting;
    }

    public boolean attackRequiresVisibility() {
        return attackRequiresVisibility;
    }

    public void setAttackRequiresVisibility(boolean required) {
        attackRequiresVisibility = required;
    }

    /**
     * Whether this unit answers a blow rather than running from one.
     *
     * <p>{@code CUnit::IsAggressive}: armed and not a coward. The coward flag
     * is what makes a peasant flee where a footman turns and fights, and it
     * was parsed and read by nothing.
     */
    public boolean isAggressive() {
        return type != null && type.canAttack() && !type.coward();
    }

    /**
     * How far this unit still has to turn, in 256ths, signed.
     *
     * <p>{@code CUnit::Anim.Rotate}. A step does not turn a unit at once:
     * {@code UnitHeadingFromDeltaXY} works
     * out the shorter of the two ways round, stores it here and sets
     * {@code Direction} to the new heading, and
     * {@code UnitShowAnimationScaled} then walks this back towards nought by
     * {@code RotationSpeed} every cycle.
     *
     * <p>Only two shipped types can see it, and they are the two that turn
     * slowly: the ballista's and catapult's Move animation opens
     * {@code "if-var R >= 60 turn"} and waits thirty cycles when it does.
     */
    public int pendingRotation() {
        return pendingRotation;
    }

    public void setPendingRotation(int pendingRotation) {
        this.pendingRotation = pendingRotation;
    }

    /**
     * Turns towards a new heading, the long way round or the short.
     *
     * <p>Upstream's arithmetic exactly, in unsigned bytes: the two distances
     * round the circle are compared and the smaller wins, with the half-circle
     * case going anticlockwise.
     */
    public void turnTo(int newHeading) {
        int facings = type == null ? 8 : Math.max(1, type.numDirections());
        // Where a step of that heading points, in 256ths. For the eight unit
        // deltas {@code DirectionToHeading} answers exactly this -- north is
        // nought, east 64, south 128, west 192, and the diagonals halfway
        // between -- so the target angle is exact even though the facing it
        // came from is not.
        turnToAngle((newHeading * (256 / facings)) & 0xFF);
    }

    /**
     * Turns to an angle, the shorter way round, and remembers how far it was.
     *
     * <p>{@code UnitHeadingFromDeltaXY}. It
     * does not snap the unit round: it works out the shorter of the two ways,
     * stores that in {@code Anim.Rotate} for the animations that ask about it,
     * and sets the direction. The angle it turns <em>from</em> is the exact one
     * the unit was holding, which is why {@link #direction} is kept whole.
     */
    public void turnToAngle(int angle) {
        int to = angle & 0xFF;
        int diffLeft = (direction - to) & 0xFF;
        int diffRight = (to - direction) & 0xFF;
        if (diffLeft <= diffRight) {
            pendingRotation = diffLeft == 128 ? -128 : -diffLeft;
        } else {
            pendingRotation = diffRight;
        }
        direction = to;
    }

    private int pendingRotation;

    /**
     * The pixels a step's drain overshot nought by, signed, per axis.
     *
     * <p>Upstream's displacement pair is primed with {@code +=} at every
     * step's commit, so a drain that crosses nought
     * carries its overshoot into the next step's length -- which is how a
     * later step's last pixel can land exactly on the move animation's wrap
     * cycle and miss the decide window. This implementation's drawn offset clamps to
     * nought at the crossing; the cut lives here and folds back in at the
     * next prime, invisible to the renderer and to every offset-reading
     * gate in between.
     */
    private int residualX;
    private int residualY;

    /**
     * The route element the walk last consumed, which its pixels follow.
     *
     * <p>Upstream's {@code Length} falls at the consult, not at the step, so
     * {@code Path[Length-1]} names the element just walked for the whole of
     * its drain, and the pixel block follows it every cycle
     * A consult that finds the route
     * empty reads the byte before the array -- the struct's Length field,
     * nought, north -- and the drift after a spent route follows that
     * phantom.
     */
    private int lastStepHeading = 8;

    public int lastStepHeading() {
        return lastStepHeading;
    }

    public void setLastStepHeading(int heading) {
        this.lastStepHeading = heading;
    }

    /**
     * The heading a discarded leftover would have taken, while it lasts.
     *
     * <p>Retail keeps the twenty route bytes at record offset 48 and moves the
     * cursor at 126; a unit that has stopped walking a route is still holding
     * it, and {@code 0x0044fa20} answers "where is this unit going" out of the
     * bytes the cursor has not reached. This implementation empties the route instead
     * when a resource order comes into range, and then cannot answer that
     * question at all: XHuman 10's peon 1596 stands on 57,4 from fixture 9 to
     * 24 with retail's record holding {@code NW,N} on cursor 1, and this
     * port's holding nothing, so peon 1590 behind it never gets retail's
     * fifteen-cycle wait.
     *
     * <p>Deviation: retail keeps the whole remainder and this keeps only its
     * first heading. The difference is bounded to a reader that wants more
     * than the next square, and the one reader there is --
     * {@code World.battleNetCooperativeBlocker}, which is
     * {@code 0x0044fa20} -- wants exactly the next square.
     */
    private int battleNetSpentHeading = -1;

    public int battleNetSpentHeading() {
        return battleNetSpentHeading;
    }

    public void setBattleNetSpentHeading(int heading) {
        this.battleNetSpentHeading = heading;
    }

    public int residualX() {
        return residualX;
    }

    public int residualY() {
        return residualY;
    }

    public void setResidual(int x, int y) {
        this.residualX = x;
        this.residualY = y;
    }

    /** Whether this unit can take a step at all. */
    public boolean canMove() {
        return type != null && !type.building() && type.speed() > 0;
    }

    /** Cycles left of the death animation. */
    public int deathTimer() {
        return deathTimer;
    }

    public void setDeathTimer(int deathTimer) {
        this.deathTimer = deathTimer;
    }

    /** Whether the unit still has hit points. Dying units do not. */
    public boolean isDying() {
        return order == Order.DYING;
    }

    /**
     * Whether a fire is already burning on this building.
     *
     * <p>{@code CUnit::Burning}. It exists to stop a building accumulating
     * fires: {@code HitUnit} lights one only when the building is not already
     * alight, and without that guard every single blow landing on a keep would
     * stack another flame on the same spot -- a dozen of them within a second
     * of a catapult opening up, all drawn on top of each other and every one
     * of them stepping its own animation.
     *
     * <p>Cleared by the fire itself when it goes out, which is the other half
     * of the pair: a building repaired back to full stops burning and must be
     * able to catch light again the next time it is hit.
     */
    private boolean burning;

    public boolean isBurning() {
        return burning;
    }

    public void setBurning(boolean burning) {
        this.burning = burning;
    }

    /**
     * Chebyshev distance in tiles to another unit, measured between
     * footprints rather than corners so a large building is reachable from
     * any of its edges.
     */
    public int distanceTo(Unit other) {
        return distanceBetween(type, tileX, tileY, other.type, other.tileX, other.tileY);
    }

    /**
     * The same measurement between two types at two places.
     *
     * <p>{@code MapDistanceBetweenTypes} takes types
     * and positions rather than units for the same reason this does: a
     * building rule is asked about a site before anything has been put on it,
     * so there is no unit to measure from.
     */
    public static int distanceBetween(UnitType a, int aX, int aY, UnitType b, int bX, int bY) {
        int dx = gap(aX, Math.max(1, a.tileWidth()), bX, Math.max(1, b.tileWidth()));
        int dy = gap(aY, Math.max(1, a.tileHeight()), bY, Math.max(1, b.tileHeight()));
        // Euclidean, floored -- MapDistanceBetweenTypes ends
        // "return isqrt(dy * dy + dx * dx)". This was the larger of the two
        // gaps, which is the same answer along a row or a column and wrong
        // everywhere else: a gap of five and five is five that way and seven
        // this way. Every diagonal engagement in the game inherited it, so a
        // range-four archer reached about a dozen squares it should have had
        // to walk into, and a range-eight catapult covered nearly half as much
        // ground again as it should. The implementation's own fog of war already
        // measured sight this way, so vision and combat disagreed with each
        // other inside the same engine.
        return isqrt(dx * dx + dy * dy);
    }

    /**
     * Distance in tiles from this unit's footprint to a square.
     *
     * <p>{@code CUnit::MapDistanceTo(const Vec2i &)}. The unit's own size
     * counts and the square's does not, which is the difference between a
     * catapult stone landing dead centre on a four-by-four Town Hall and one
     * landing on the corner: measured from the top-left tile the hall is two
     * or three squares from the impact and is skipped by a splash of radius
     * two, and measured from the footprint it is nought and takes the blow
     * whole.
     */
    public int distanceTo(int x, int y) {
        int width = Math.max(1, type.tileWidth());
        int height = Math.max(1, type.tileHeight());
        int dx = x <= tileX ? tileX - x : Math.max(0, x - tileX - width + 1);
        int dy = y <= tileY ? tileY - y : Math.max(0, y - tileY - height + 1);
        return isqrt(dx * dx + dy * dy);
    }

    /**
     * The whole part of a square root.
     *
     * <p>Upstream's own: distances are compared against
     * integer ranges, so the rounding has to match or a unit at the very edge
     * of its reach disagrees with the C++ about whether it can fire.
     */
    private static int isqrt(int value) {
        if (value <= 0) {
            return 0;
        }
        int root = (int) Math.sqrt(value);
        // Guard the boundary rather than trusting the double: for a large
        // perfect square the conversion can land a unit either side.
        while (root * root > value) {
            root--;
        }
        while ((root + 1) * (root + 1) <= value) {
            root++;
        }
        return root;
    }

    private static int gap(int aStart, int aSize, int bStart, int bSize) {
        if (aStart + aSize <= bStart) {
            return bStart - (aStart + aSize) + 1;
        }
        if (bStart + bSize <= aStart) {
            return aStart - (bStart + bSize) + 1;
        }
        return 0;
    }

    /** Where this unit is inside its current animation. */
    public net.chonkbase.chonkcraft.engine.animation.AnimationState animation() {
        return animation;
    }

    /** The sheet index and mirroring to draw this cycle. */
    public SpriteFrame.Resolved spriteFrame() {
        return SpriteFrame.resolve(frame, heading(), type.numDirections());
    }

    /** Whether this unit is part-way through a step. */
    public boolean isMoving() {
        return offsetX != 0 || offsetY != 0;
    }

    /** How many path steps remain. */
    public int pathLength() {
        return pathLength;
    }

    /**
     * How many path headings this route started with. Used with
     * {@link #pathLength()} to count steps already taken
     * ({@code initial - remaining}).
     */
    public int battleNetPathInitialLength() {
        return battleNetPathInitialLength;
    }

    /**
     * Headings already consumed from the current route
     * ({@code initialLength - pathLength}). Human 13 axe 1495 residual-opens
     * after two tile steps; axe 1483 holds after one.
     */
    public int battleNetPathStepsTaken() {
        int taken = battleNetPathInitialLength - pathLength;
        return Math.max(0, taken);
    }

    private int battleNetPathInitialLength;

    /**
     * Carries an already-consumed native route prefix into a replacement
     * Java route.
     *
     * <p>BNE can retain the route-buffer cursor while an Attack handoff
     * replaces the logical quarry. Java sometimes has to redraw the actual
     * headings because its occupancy projection differs, but the consumed
     * prefix still decides whether the eventual arrival is a first-step hold
     * or a multi-step residual open. Keep that cursor provenance without
     * changing any remaining heading.
     */
    public void carryBattleNetPathStepsTaken(int consumedPrefix) {
        if (consumedPrefix > 0 && pathLength > 0) {
            battleNetPathInitialLength += consumedPrefix;
        }
    }

    public void setPath(PathFinder.Path found) {
        int[] headings = found.headings();
        // The stored route holds at most twenty-eight steps:
        // {@code PathFinderOutput::MAX_PATH_LENGTH} is 28 and
        // {@code AStarSavePath} saves "as much of the path as we can"
        // A longer answer walks its
        // first twenty-eight, spends the route, serves the ten-cycle pause
        // and asks again -- the segment beat every long walk shows.
        // level13o's gryphon breaks segment at 54,36 and steps again at 688
        // upstream, where a port that kept the whole route held its beat.
        // Element nought is the route's final step, so the first
        // twenty-eight steps are the array's last twenty-eight entries.
        if (headings.length > 28) {
            int[] kept = new int[28];
            System.arraycopy(headings, headings.length - 28, kept, 0, 28);
            headings = kept;
        }
        this.path = headings;
        this.pathLength = headings.length;
        this.battleNetPathInitialLength = headings.length;
        this.routeSpent = false;
        // A fresh route replaces whatever the last one had left, exactly as
        // the twenty bytes at offset 48 are overwritten.
        this.battleNetSpentHeading = -1;
        // One-heading chase leftovers need a fresh soft-wait count so the
        // SE generation after SSSS does not inherit counter 1 and replan
        // without waiting (XHuman 12 grunt 1503). Resetting every setPath
        // REGed human-13's seed at fixture 42.
        // Preserve multi-refuse collision (counter >= 2) across a one-heading
        // free-compass rescue: XHuman 12 grunt 90 soft-waited ~35 cycles at
        // 33,39 then SE onto 34,40; zeroing left c0 so free-scan 1516 wall-
        // follow soft-cleared that cell while native 0x4501c0 nibble-refuse
        // kept face-two from stepping W (BTS face2 step1).
        if (headings.length == 1 && this.battleNetCollisionCounter < 2) {
            this.battleNetCollisionCounter = 0;
        }
        // Caller marks free-prefix / long-approach after setPath when known.
        this.battleNetGoldFreePrefix = false;
        this.battleNetGoldFreePrefixLength = 0;
        this.battleNetWoodResidualSettles = 0;
        this.battleNetWoodRouteIndex20 = false;
        this.battleNetMoveFreeDetourPending = false;
    }

    public void clearPath() {
        this.path = new int[0];
        this.pathLength = 0;
        this.battleNetPathInitialLength = 0;
        this.routeSpent = false;
        this.battleNetSpentHeading = -1;
        this.battleNetGoldLongApproach = false;
        this.battleNetGoldFreePrefix = false;
        this.battleNetGoldFreePrefixLength = 0;
        this.battleNetWoodResidualSettles = 0;
        this.battleNetWoodRouteIndex20 = false;
        this.battleNetMoveFreeDetourPending = false;
    }

    /**
     * A long gold free-prefix ended in an occupied cardinal tail.
     *
     * <p>The parked tail and every replacement route are one continuous
     * {@code FUN_004379e0} refusal generation.  The marker survives route
     * clears until the eighth refusal arms the complete fifteen-count band.
     */
    public boolean battleNetGoldCardinalTailRefusal() {
        return battleNetGoldCardinalTailRefusal;
    }

    public void setBattleNetGoldCardinalTailRefusal(boolean armed) {
        this.battleNetGoldCardinalTailRefusal = armed;
    }

    private boolean battleNetGoldCardinalTailRefusal;

    /**
     * Whether this unit has just walked the last step of a stored route.
     *
     * <p>{@code NextPathElement} decrements {@code output.Length} at the top of
     * every call that reuses its cached route and only then reads a direction
     * so the call after the last
     * step finds {@code Length} nought and returns {@code result =
     * output.Length} -- nought, which is {@code PF_WAIT}. Printed from the
     * running binary rather than argued from the source, because at that point
     * the direction is read from {@code Path[-1]} and the answer does not
     * depend on what that byte says:
     *
     * <pre>
     * NEXTELEM cycle=18 unit=3 at 10,2 len=0 overflow=0 idx=-1 heading=0
     *          dir=0,-1 canbeat=0
     * </pre>
     */
    public boolean routeSpent() {
        return routeSpent;
    }

    public void setRouteSpent(boolean spent) {
        this.routeSpent = spent;
    }

    private boolean routeSpent;

    /**
     * The standing-in-place gather's period clock, in cycles.
     *
     * <p>Implements {@code COrder_Resource::TimeToHarvest}: wound to the
     * resource's wait when gathering starts, down one every cycle, and the
     * take lands when it runs below nought, rewinding by the wait with no
     * fencepost.
     */
    public int timeToHarvest() {
        return timeToHarvest;
    }

    public void setTimeToHarvest(int cycles) {
        this.timeToHarvest = cycles;
    }

    private int timeToHarvest;

    /**
     * Whether the load is full and the chopper is only waiting for its swing
     * to finish: {@code COrder_Resource::DoneHarvesting}. The leave happens
     * on the first breakable cycle after.
     */
    public boolean chopDone() {
        return chopDone;
    }

    public void setChopDone(boolean done) {
        this.chopDone = done;
    }

    private boolean chopDone;

    /**
     * Whether the standing-in-place gather has begun -- the implementation's marker for
     * upstream's {@code SUB_GATHER_RESOURCE} state. It drops whenever the
     * worker walks or is re-ordered, so the next arrival winds
     * {@link #timeToHarvest} afresh.
     */
    public boolean gatherClockStarted() {
        return gatherClockStarted;
    }

    public void setGatherClockStarted(boolean started) {
        this.gatherClockStarted = started;
        if (!started) {
            // Leaving the stand also drops the work-swing SyncRand arm so a
            // later claim does not inherit a stale countdown. The walk-claim
            // flag is NOT cleared here: walkToWood resets the gather clock
            // every step, and wiping the claim there lost the arrival draw.
            battleNetWoodSyncRemaining = 0;
        }
    }

    private boolean gatherClockStarted;

    /**
     * Cycles until the next BNE terrain-harvest work-swing {@code SyncRand}
     * ({@code 0x423550} at sequence 2660). Zero means no arm is live.
     *
     * <p>Retail re-seeds unit+0xb on every entry to the 2660 work opcode: the
     * first swing after the three-cycle 2657 staging wait, and every later
     * animation loop (twenty-five cycles). Walking arrivals also draw once
     * when StartGathering claims the tree (2657); standing starts skip that
     * claim draw and only fire at 2660.
     */
    public int battleNetWoodSyncRemaining() {
        return battleNetWoodSyncRemaining;
    }

    public void setBattleNetWoodSyncRemaining(int cycles) {
        this.battleNetWoodSyncRemaining = Math.max(0, cycles);
    }

    private int battleNetWoodSyncRemaining;

    /**
     * Set when a woodcutter still held a path (or was mid-step) on the
     * range-one arrival that precedes StartGathering. Cleared when the
     * gather clock starts and consumes the claim draw.
     */
    public boolean battleNetWoodWalkClaim() {
        return battleNetWoodWalkClaim;
    }

    public void setBattleNetWoodWalkClaim(boolean walkClaim) {
        this.battleNetWoodWalkClaim = walkClaim;
    }

    private boolean battleNetWoodWalkClaim;

    /**
     * A wood order selected by UnitReady after another resource route failed.
     * Native sends this order through its terrain path action even when the
     * selected tree is already adjacent; a plain player/ready wood order may
     * start as a standing chop.
     */
    private boolean battleNetWoodReadyPathRequired;

    public boolean battleNetWoodReadyPathRequired() {
        return battleNetWoodReadyPathRequired;
    }

    public void setBattleNetWoodReadyPathRequired(boolean required) {
        battleNetWoodReadyPathRequired = required;
    }

    /**
     * A terrain-harvest route's terminal residual was refused by an allied
     * worker. The resource action owns one 3,2,1 construction before retrying
     * the stored wall face; the heading identifies that refused face.
     */
    public int battleNetWoodTerminalRefusalHeading() {
        return battleNetWoodTerminalRefusalHeading;
    }

    public void setBattleNetWoodTerminalRefusalHeading(int heading) {
        battleNetWoodTerminalRefusalHeading = heading >= 0
                && heading < Direction.COUNT ? heading : -1;
    }

    private int battleNetWoodTerminalRefusalHeading = -1;

    /**
     * A one-byte terrain-resource route whose diagonal head is repeatedly
     * refused by an allied body that the route writer had temporarily made
     * passable. Retail parks the cached face for three Move visits, then makes
     * that face solid to the next wall-follow query. The heading identifies
     * the face and {@code visits} is the number of parked visits already paid.
     */
    public int battleNetWoodCornerRefusalHeading() {
        return battleNetWoodCornerRefusalHeading;
    }

    public void setBattleNetWoodCornerRefusalHeading(int heading) {
        battleNetWoodCornerRefusalHeading = heading >= 0
                && heading < Direction.COUNT ? heading : -1;
        if (battleNetWoodCornerRefusalHeading < 0) {
            battleNetWoodCornerRefusalVisits = 0;
        }
    }

    private int battleNetWoodCornerRefusalHeading = -1;

    public int battleNetWoodCornerRefusalVisits() {
        return battleNetWoodCornerRefusalVisits;
    }

    public void setBattleNetWoodCornerRefusalVisits(int visits) {
        battleNetWoodCornerRefusalVisits = Math.max(0, visits);
    }

    private int battleNetWoodCornerRefusalVisits;

    public void clearBattleNetWoodCornerRefusal() {
        battleNetWoodCornerRefusalHeading = -1;
        battleNetWoodCornerRefusalVisits = 0;
    }

    /**
     * A harvest command that landed mid-swing and waits for the animation to
     * let go.
     *
     * <p>Upstream never applies a command to a unit inside an unbreakable
     * stretch: the flush marks the old order finished and the pop -- with
     * the whole of {@code HandleUnitAction} -- sits behind
     * {@code if (!unit.Anim.Unbreakable)}, while
     * the finished order keeps executing. A chopper stolen for gold at
     * cycle 157 of campaigns/orc/level12o swings on, takes its own tree's
     * wood once more at 170, and only walks for the mine on the swing's one
     * breakable cycle. The pair is the target square of the deferred
     * harvest; minus one means nothing is deferred.
     */
    public int pendingHarvestX() {
        return pendingHarvestX;
    }

    public int pendingHarvestY() {
        return pendingHarvestY;
    }

    public void setPendingHarvest(int tileX, int tileY) {
        this.pendingHarvestX = tileX;
        this.pendingHarvestY = tileY;
    }

    private int pendingHarvestX = -1;
    private int pendingHarvestY = -1;

    /**
     * Whether a step this unit took still owns it.
     *
     * <p>{@code unit.Moving}, which is a state and not a displacement: set on
     * the cycle a step is taken and cleared by
     * {@code (!unit.Anim.Unbreakable && !unit.IX && !unit.IY)}
     * so it outlives the drawing
     * offset by the tail of the walk animation's unbreakable section. Printed
     * from the real binary on {@code (3)critter-attack}: the blocker's last
     * two pixels drain during cycle 521 and {@code Moving} reads 1 until the
     * animation lets go during 524. The drawing offset alone cannot say that,
     * and a unit that never stepped runs its Move animation while blocked, so
     * neither {@code isMoving()} nor the animation is this flag.
     */
    public boolean walkHolding() {
        return walkHolding;
    }

    public void setWalkHolding(boolean holding) {
        this.walkHolding = holding;
    }

    private boolean walkHolding;

    /**
     * Whether the last walked beat completed its step's drain.
     *
     * <p>Set beside the let-go -- the {@code Anim.Unbreakable} read that
     * clears {@code Data.Move.Moving} --
     * and cleared at the next commit. Upstream runs its dead-goal check and
     * target scan on that very beat; this implementation's move scripts stay
     * unbreakable through the walk's own advance and go breakable with the
     * next cycle's dispatcher, so the check that upstream makes at the
     * drain-end belongs here to the boundary that follows it, and this flag
     * is how the boundary knows it is that boundary and not one a refusal
     * wait left behind.
     */
    private boolean stepDrained;

    public boolean stepDrained() {
        return stepDrained;
    }

    public void setStepDrained(boolean drained) {
        this.stepDrained = drained;
    }

    /**
     * How many waiting answers an explore order has swallowed.
     *
     * <p>{@code COrder_Explore::WaitingCycle}: the fifth consecutive wait
     * gives the destination up and draws a fresh one
     * Order state rather than
     * unit state upstream, carried here the way {@code moveRange} is.
     */
    public int exploreWaitingCycle() {
        return exploreWaitingCycle;
    }

    public void setExploreWaitingCycle(int cycles) {
        this.exploreWaitingCycle = cycles;
    }

    private int exploreWaitingCycle;

    /** The next heading to step in, without consuming it. */
    public int peekHeading() {
        return path[pathLength - 1];
    }

    /** The cached heading after the next one, without consuming either. */
    public int peekHeadingAfterNext() {
        if (pathLength < 2) {
            throw new IllegalStateException("no second path heading to inspect");
        }
        return path[pathLength - 2];
    }

    /**
     * Heading at stack depth without consuming. Depth 0 is {@link #peekHeading()};
     * depth 1 is {@link #peekHeadingAfterNext()}. Returns -1 when depth is out of
     * range.
     */
    public int peekHeadingAtDepth(int depth) {
        if (depth < 0 || depth >= pathLength) {
            return -1;
        }
        return path[pathLength - 1 - depth];
    }

    /** Rewrites the next heading without consuming the route. */
    public void replacePeekHeading(int heading) {
        if (pathLength <= 0) {
            throw new IllegalStateException("no path heading to replace");
        }
        path[pathLength - 1] = heading;
    }

    /** Rewrites the cached heading after the next one without consuming it. */
    public void replacePeekHeadingAfterNext(int heading) {
        if (pathLength < 2) {
            throw new IllegalStateException("no second path heading to replace");
        }
        path[pathLength - 2] = heading;
    }

    /** Consumes the next heading. */
    public int popHeading() {
        int heading = path[--pathLength];
        if (pathLength == 0) {
            routeSpent = true;
        }
        return heading;
    }

    /**
     * The terrain flag this unit needs under it.
     *
     * <p>Air units need nothing: a gryphon crosses water, forest and mountain
     * alike, so its mask is every kind of ground. Giving them the land mask
     * instead, as this first did, quietly grounded the entire air force.
     */
    public long movementMask() {
        return movementMaskFor(type);
    }

    /**
     * The same, for a type with no unit yet.
     *
     * <p>A building about to finish training has to know where the thing it is
     * making could stand before there is anything to ask.
     */
    public static long movementMaskFor(UnitType type) {
        // Nothing stops a non-solid unit, which upstream writes as a mask of
        // nought: UpdateUnitStats gives a NonSolid type that is not a building
        // MovementMask = 0, and UnitTypeCanBeAt skips the
        // mask test for one outright -- "if (!type.BoolFlag[NONSOLID_INDEX]
        // .value && Map.Field(...)->CheckMask(mask))". This
        // port carries the mask the other way up, as the ground a unit will
        // accept rather than the ground that stops it, so a mask of nought
        // would refuse every square instead of allowing every square; the
        // equivalent is all three kinds of ground at once, which is what a
        // flyer gets and for the same reason.
        //
        // The flag was read by markOccupancy and by nothing else. The twenty-
        // two dead-vision markers are the whole shipped family, and they are
        // spawned on the square a unit has just died on, so their footprint
        // always did fit where they were put and nothing was ever seen to go
        // wrong.
        if (type.nonSolid() && !type.building()) {
            return TileFlag.LAND_ALLOWED | TileFlag.WATER_ALLOWED | TileFlag.COAST_ALLOWED;
        }
        // A shore building stands on the water's edge, not on the land.
        //
        // The game a type with SHOREBUILDING set has its
        // movement mask rebuilt to block MapFieldLandAllowed, leaving coast
        // and water as the squares it may occupy. That is what makes a
        // shipyard something you place along a beach rather than anywhere you
        // like. Tested before the movement kind because upstream's override is
        // applied after the switch and wins over it.
        //
        // The flag was read out of the scripts and then consulted by nothing,
        // so every shore building was placed by the plain land rule: on human
        // mission five a shipyard could go on 1,331 squares, all of them
        // inland, and none of them touching the sea it exists to launch ships
        // into.
        if (type.shoreBuilding()) {
            return TileFlag.WATER_ALLOWED | TileFlag.COAST_ALLOWED;
        }
        UnitType.Movement moves = type.moveType();
        if (moves == UnitType.Movement.FLY) {
            return TileFlag.LAND_ALLOWED | TileFlag.WATER_ALLOWED | TileFlag.COAST_ALLOWED;
        }
        if (moves == UnitType.Movement.NAVAL) {
            // A transport, and only a transport, may enter coast squares.
            //
            // The game naval types get a movement mask
            // containing MapFieldCoastAllowed, except that
            // {@code if (type.CanTransport())} branch, which leaves it out. The
            // flag's own declaration says so in as many words -- "Coast
            // (transporter) units allowed", {@code tileset.h:67}.
            //
            // This is the entire beaching mechanic, and without it the game is
            // not merely a little wrong. A Warcraft II coastline is a ribbon of
            // coast squares one to three wide between the water and the land.
            // Land units are blocked by coast, ships are blocked by coast, so
            // with the carve-out missing there is no square in the world where
            // a boat and a soldier can stand next to each other. Transports
            // could be loaded -- passengers walk to the boat, and the boat can
            // sit in open water a couple of squares out -- and then never
            // unloaded anywhere, on any map. Measured on human mission five:
            // not one of the 591 water squares along its coast could put a
            // passenger ashore.
            return type.canTransport()
                    ? TileFlag.WATER_ALLOWED | TileFlag.COAST_ALLOWED
                    : TileFlag.WATER_ALLOWED;
        }
        return TileFlag.LAND_ALLOWED;
    }

    /** The occupancy flag this unit sets on the squares it covers. */
    public long occupancyFlag() {
        if (type.building()) {
            // A building with no hit points at all is ground you walk on, not
            // a wall. UpdateUnitStats labels it: "A little chaos, buildings
            // without HP can be entered. The oil-patch is a very special
            // case." Catalog nought hit points give MapFieldNoBuilding.
            // Overlay copies raise oil-patch and circle-of-power to 1 so
            // they survive isAlive, which used to mark them BUILDING and
            // turn every patch into a reef. Native batch-2/03 dest-arms a
            // destroyer onto 112,118 on the patch. Used to swallow that
            // click as already-touching occupied dest.
            return type.hitPoints() == 0
                    || "unit-oil-patch".equals(type.ident())
                    || "unit-circle-of-power".equals(type.ident())
                    ? TileFlag.NO_BUILDING : TileFlag.BUILDING;
        }
        if (type.airUnit()) {
            return TileFlag.AIR_UNIT;
        }
        return type.seaUnit() ? TileFlag.SEA_UNIT : TileFlag.LAND_UNIT;
    }

    /** Occupancy that blocks this unit from entering a square. */
    public long blockingFlags() {
        return blockingFlagsFor(type);
    }

    /**
     * The same, for a type with no unit yet.
     *
     * <p>Asks {@link UnitType#moveType()} rather than the {@code SeaUnit} and
     * {@code AirUnit} flags, so that the two halves of the same question --
     * what ground will take this unit, and what standing on that ground turns
     * it away -- cannot be answered off two different keys. No shipped type
     * that ever moves is affected: the ten whose {@code Type} disagrees with
     * their flags are the three buildings that stand in the sea and seven
     * corpses and vision markers, none of which takes a step.
     */
    public static long blockingFlagsFor(UnitType type) {
        UnitType.Movement moves = type.moveType();
        if (moves == UnitType.Movement.FLY) {
            return TileFlag.AIR_UNIT;
        }
        // A shore building is in the way of ships as well as of soldiers,
        // which is upstream's LandUnit | SeaUnit | Building for the same case.
        if (type.shoreBuilding()) {
            return TileFlag.BUILDING | TileFlag.SEA_UNIT | TileFlag.LAND_UNIT;
        }
        return TileFlag.BUILDING
                | (moves == UnitType.Movement.NAVAL ? TileFlag.SEA_UNIT : TileFlag.LAND_UNIT);
    }

    @Override
    public String toString() {
        return type.ident() + "#" + id + " at " + tileX + "," + tileY;
    }
}
