package net.chonkbase.chonkcraft.engine.missile;

import net.chonkbase.chonkcraft.engine.unit.Unit;

/**
 * A projectile on its way somewhere.
 *
 * <p>Implements the in-flight half of {@code Missile}, reduced to the classes Warcraft II actually
 * uses: something leaves a unit, crosses the ground at a speed, and does
 * damage where it lands.
 *
 * <p>Travel time is the point. An arrow that arrives on the frame it was
 * loosed makes an archer a melee unit with reach; one that takes half a second
 * lets its target die first, walk out from under it, or be healed before it
 * arrives, which is how Warcraft II's ranged combat actually plays.
 */
public final class Missile {

    /** Flattened sprite frames read by BNE's parabolic action at 0x00410260. */
    private static final int[] BATTLE_NET_PARABOLIC_FRAMES = {0, 5, 10, 5, 0};

    /**
     * What kind of thing this is.
     *
     * <p>Not final, and volatile, because of one class: a fire on a burning
     * building swaps between {@code missile-small-fire} and
     * {@code missile-big-fire} as the building's health crosses fifty per
     * cent, without the fire ever being a different missile. Upstream does the
     * same, reassigning {@code Type} in {@code MissileFire::Action}. Volatile
     * because the renderer reads the type from the event thread to know which
     * sheet to cut, and it must never see a frame index from one sheet against
     * the dimensions of the other.
     */
    private volatile MissileType type;

    private final Unit source;
    private final Unit target;

    /**
     * Which fire goes with which health, for {@link MissileClass#FIRE} only.
     *
     * <p>Upstream reaches for a global from inside {@code MissileFire::Action};
     * carrying it means a fire can be stepped without any ambient state, which
     * is what lets a test hand one a table of its own.
     */
    private final BurningBuildingFrames burningBuildings;

    /**
     * Where it is, in pixels, so it can move less than a tile a cycle.
     *
     * <p>The centre of the sprite, which is where a unit fires from and where
     * the shot is aimed. Upstream keeps the top-left corner instead and
     * subtracts half the frame at launch; the renderer here does that
     * subtraction when it draws.
     *
     * <p>Volatile because the renderer reads these from the event thread while
     * the simulation writes them from its own, and a torn double would put a
     * missile somewhere neither thread ever placed it.
     */
    private volatile double x;
    private volatile double y;

    /**
     * Where the current leg started, in pixels.
     *
     * <p>Not final because of the bouncing class: {@code
     * MissilePointToPointBounce::Action} sets {@code source = position} before
     * each further hop, so every bounce is measured from where the last one
     * landed rather than from the muzzle.
     */
    private double fromX;
    private double fromY;

    /** Where the current leg ends, in pixels. */
    private double toX;
    private double toY;

    /**
     * How far along the current leg it is, and how long that leg is, in pixels.
     *
     * <p>Upstream's {@code CurrentStep} and {@code TotalStep}. Kept rather than
     * moved incrementally because the parabolic class needs the fraction of the
     * journey covered to know how high the shot should be, and because a bounce
     * resets both.
     */
    private double travelled;
    private double total;

    /** How many further hops a bouncing missile has already taken. */
    private int bounces;

    /**
     * Whether an impact is owed for this cycle.
     *
     * <p>Separate from {@link #arrived} because of bouncing: a dragon's breath
     * detonates and then carries on, so "it hit something" and "it is finished
     * with" stopped being the same event.
     */
    private boolean hit;

    /**
     * Cycles to wait before it starts.
     *
     * <p>{@code Missile::Delay}, which is how a blizzard is a squall rather
     * than a single flash: {@code Spell_AreaBombardment::Cast} staggers the
     * eleven shards of each field so they arrive one after another.
     */
    private int delay;

    /**
     * Damage this particular shot carries, whoever fired it.
     *
     * <p>{@code Missile::Damage}, set by the spells and by nothing else.
     * {@code MissileHitsGoal} prefers it over the firer's stats, which is what
     * makes a fireball do its declared twenty however weak the mage is.
     */
    private int damage;

    /** Remaining simulation cycles for persistent spell effects; -1 is unbounded. */
    private int timeToLive = -1;

    /** A persistent effect reached one of its damage beats this update. */
    private boolean periodicHit;

    private boolean arrived;
    private int sleep;

    /**
     * Which quarter of a {@link MissileClass#CYCLE_ONCE} run it is in.
     *
     * <p>Upstream's {@code MissileCycleOnce::Action} is a four-state machine:
     * wait a cycle, run the animation forwards, wait another, run it
     * backwards, then die.
     */
    private int cycleState;

    /**
     * Whether the journey has been measured yet, which costs an action.
     *
     * <p>{@code Missile::State}'s low bit in {@code MissileInitMove}. See
     * {@link #step()}.
     */
    private boolean moveStarted;

    /** Which step of its animation is showing, not counting facings. */
    private volatile int frame;

    /**
     * Which of the sheet's facings it is drawn in.
     *
     * <p>Fixed at launch in BNE. Both retail constructors write the heading at
     * projectile {@code +0x0a}; point motion {@code 0x004101f0} never rewrites
     * it, and parabolic motion {@code 0x00410260} changes only the visual frame
     * at {@code +0x09}. The Bresenham step vector is motion state, not a new
     * facing: deriving a heading from each micro-step makes a shallow arrow
     * flicker between adjacent compass pictures.
     *
     * <p>Volatile for the reason the position is: the renderer reads it from
     * the event thread.
     */
    private volatile int direction;

    /**
     * Whether this shot uses retail BNE's integer flight model.
     *
     * <p>BNE does not walk a Euclidean fraction of the journey. The point-motion
     * action at {@code 0x004101f0} steps by the native speed table through the
     * direction/error state at {@code 0x00429fa0}, and stops when the remaining
     * max-axis distance at projectile {@code +0x20} goes negative. Ordinary
     * ChonkCraft profile shots keep the LegacyEngine travelled/total path.
     */
    private boolean battleNetMotion;

    /**
     * Whether FUN_0040fb10 constructor draws (damage + aim jitter) have
     * already been taken. Presentation can debit them mid-wait before OP10
     * while flight still arms only at the opcode-ten boundary.
     */
    private boolean battleNetConstructorDrawn;

    /**
     * BNE projectile action 6: remaining distance has gone negative, but the
     * hit is applied only after {@link #battleNetImpactWait} more projectile
     * passes, not on the crossing tick. Point-to-point arms wait 1 (next
     * pass frees): XHuman 2's tower arrow shows remaining -4 with target HP
     * still 90 at fixture cycle 10, then frees with HP 83 at cycle 11.
     * Type-13 catapult rocks arm wait 5: Human 13 slot 3 remaining goes
     * negative at fixture 30 while knight 1490 stays at 77 HP through 34,
     * and the slot frees with the HP drop only at fixture 35. Other
     * parabolic types (small-cannon) keep wait 1 -- a blanket five-pass
     * hold delayed XHuman 10 splash four cycles.
     */
    private boolean battleNetPendingImpact;

    /**
     * Remaining action-6 visits before free and damage. Armed with the
     * pending flag; each step decrements, free when it reaches zero.
     */
    private int battleNetImpactWait;

    /** Native pixels subtracted from remaining distance each BNE motion step. */
    private int battleNetSpeed;

    /** Bresenham error term: projectile direction state word 0. */
    private int battleNetError;

    /** Larger absolute axis of the flight after the constructor normalises it. */
    private int battleNetMajor;

    /** Smaller absolute axis of the flight. */
    private int battleNetMinor;

    /**
     * Direction flags from constructor setup {@code 0x00429f10}.
     *
     * <p>{@code 0x80} swaps the step outputs when X is the major axis,
     * {@code 0x40} means non-negative delta X, and {@code 0x20} means
     * non-negative delta Y.
     */
    private int battleNetFlags;

    /** Remaining flight distance in pixels: projectile offset {@code +0x20}. */
    private int battleNetRemaining;

    /** Parabolic height accumulator at native projectile offset {@code +0x24}. */
    private int battleNetArcProgress;

    /** One fifth of the initial flight, rounded up: native offset {@code +0x26}. */
    private int battleNetArcStride;

    public Missile(MissileType type, Unit source, Unit target,
            double fromX, double fromY, double toX, double toY) {
        this(type, source, target, fromX, fromY, toX, toY, BurningBuildingFrames.NONE);
    }

    private Missile(MissileType type, Unit source, Unit target,
            double fromX, double fromY, double toX, double toY,
            BurningBuildingFrames burningBuildings) {
        this.type = type;
        this.burningBuildings = burningBuildings;
        this.source = source;
        this.target = target;
        this.x = fromX;
        this.y = fromY;
        this.fromX = fromX;
        this.fromY = fromY;
        this.toX = toX;
        this.toY = toY;
        this.total = distance(toX - fromX, toY - fromY);
        // Taken once, from the whole flight rather than from what is left of
        // it. Upstream does the same in MissileInitMove, which passes the
        // launch point rather than the current position for a point-to-point
        // missile: an arrow does not turn to face its target as it closes, and
        // one that recomputed its heading each frame would swing wildly on the
        // last step, when what remains of the journey is a couple of pixels.
        this.direction = headingOf(toX - fromX, toY - fromY, type.headingCount());
    }

    /**
     * A fire on a damaged building.
     *
     * <p>{@code HitUnit_Burning}. It goes on the
     * building's middle raised by exactly one tile, whatever the building's
     * size: upstream's offset is a flat {@code (0, -PixelTileSize.y)} from
     * {@code GetMapPixelPosCenter()}, not a fraction of the footprint, so a
     * four-by-four keep burns in its upper half and a one-by-one tower burns
     * over its roof. That is deliberate -- the flame is meant to read as
     * coming off the top of the building rather than sitting in the middle of
     * it -- and it is what the position looks like on screen in the original.
     *
     * <p>It has no destination and no target: a fire is not thrown at
     * anything, it stands on the thing that is burning and does no damage of
     * its own.
     */
    public static Missile burning(MissileType type, Unit building,
            BurningBuildingFrames burningBuildings) {
        double x = fireX(building);
        double y = fireY(building);
        return new Missile(type, building, null, x, y, x, y, burningBuildings);
    }

    /** The middle of a building, horizontally, in pixels. */
    private static double fireX(Unit building) {
        return building.pixelX() + footprint(building, true) * Unit.TILE_PIXELS / 2.0;
    }

    /** One tile above the middle of a building, in pixels. */
    private static double fireY(Unit building) {
        return building.pixelY() + footprint(building, false) * Unit.TILE_PIXELS / 2.0
                - Unit.TILE_PIXELS;
    }

    private static int footprint(Unit unit, boolean horizontal) {
        if (unit.type() == null) {
            return 1;
        }
        return Math.max(1, horizontal ? unit.type().tileWidth() : unit.type().tileHeight());
    }

    public MissileType type() {
        return type;
    }

    public Unit source() {
        return source;
    }

    /** What it was aimed at, which may be dead by the time it lands. */
    public Unit target() {
        return target;
    }

    public double x() {
        return x;
    }

    public double y() {
        return y;
    }

    public int frame() {
        return frame;
    }

    /**
     * Whether it is finished with and should come off the list.
     *
     * <p>For anything thrown, that means it reached its destination and its
     * damage is owed. For the classes that never travel it means the animation
     * ran out, and for a {@link MissileClass#FIRE} it means the fire went out
     * -- none of which owes anybody damage, so the caller resolves an impact
     * only for the kinds that have one.
     */
    public boolean hasArrived() {
        return arrived;
    }

    /**
     * Where it is, as a tile.
     *
     * <p>From where the missile actually is rather than from where it was aimed
     * -- {@code Missile::MissileHit} works from {@code this->position}, and for
     * a bouncing missile the two stopped agreeing the moment it took its second
     * hop. On arrival they are the same value.
     */
    public int tileX() {
        return (int) Math.floor(x / 32);
    }

    public int tileY() {
        return (int) Math.floor(y / 32);
    }

    /**
     * Whether it detonated this cycle, clearing the flag.
     *
     * <p>The caller asks once a cycle and resolves the impact if the answer is
     * yes. A bouncing missile answers yes several times over its life; anything
     * else answers yes exactly once, on the cycle it also becomes
     * {@link #hasArrived() finished}.
     */
    public boolean consumeHit() {
        boolean owed = hit;
        hit = false;
        return owed;
    }

    /** How long before it starts moving; see the field's note. */
    public void setDelay(int delay) {
        this.delay = delay;
    }

    /** Damage this shot carries in its own right, or zero. */
    public int damage() {
        return damage;
    }

    public void setDamage(int damage) {
        this.damage = damage;
    }

    public int timeToLive() {
        return timeToLive;
    }

    public void setTimeToLive(int cycles) {
        timeToLive = cycles > 0 ? cycles : -1;
    }

    public boolean consumePeriodicHit() {
        boolean result = periodicHit;
        periodicHit = false;
        return result;
    }

    public void triggerImpact() {
        hit = true;
        arrived = true;
        timeToLive = 0;
    }

    public void redirect(double destinationX, double destinationY) {
        fromX = x;
        fromY = y;
        toX = destinationX;
        toY = destinationY;
        travelled = 0;
        total = distance(toX - fromX, toY - fromY);
        moveStarted = false;
        direction = headingOf(toX - fromX, toY - fromY, type.headingCount());
    }

    /**
     * Replaces the aim point before the mobile constructor measures remaining.
     *
     * <p>{@code FUN_0040fb10} reads the target's live pixel at the constructor
     * boundary. Presentation can allocate the missile one Attack frame earlier;
     * callers must refresh here so a walking target is not aimed where it stood
     * on that earlier frame.
     */
    public void setBattleNetAim(double aimX, double aimY) {
        toX = aimX;
        toY = aimY;
    }

    /**
     * Replaces the muzzle point before the mobile constructor measures remaining.
     *
     * <p>Same constructor-boundary rule as {@link #setBattleNetAim}: the firer's
     * raw pixel at OP10, not the presentation allocation frame.
     */
    public void setBattleNetMuzzle(double muzzleX, double muzzleY) {
        fromX = muzzleX;
        fromY = muzzleY;
        x = muzzleX;
        y = muzzleY;
    }

    /**
     * Applies retail mobile-constructor pixel offsets to the aim point.
     *
     * <p>{@code FUN_0040fb10} draws two async values and adds each as an
     * independent {@code -3..4} nudge on the aim axes before remaining
     * distance is measured. Skipping the geometry (while still burning the
     * draws) left exact tile-centre aims one pixel long on axes that divide
     * the native speed, so an axe that should free on fixture cycle 27 freed
     * on 28 and a farm stayed at 400 HP for one extra cycle.
     */
    public void applyBattleNetAimJitter(int offsetX, int offsetY) {
        toX += offsetX;
        toY += offsetY;
    }

    /**
     * Switches this shot onto retail BNE's integer flight model.
     *
     * <p>Implements the remaining-distance init in the fixed constructor
     * {@code 0x0040fdc0} and the mobile constructor {@code 0x0040fb10}, plus the
     * direction setup at {@code 0x00429f10}. Remaining distance is the larger
     * of the absolute pixel deltas, raised to {@code minFlight} when the type's
     * table at {@code 0x00494e6c} demands a longer trip. The ordinary ChonkCraft
     * init-move action is not part of this path: BNE's construction cycle is
     * skipped by the caller, and every later update spends one native speed
     * step.
     *
     * @param speed     native pixels per update from table {@code 0x00494e0c}
     * @param minFlight minimum remaining distance, already scaled by 32 from
     *                  the per-type factor at {@code 0x00494e6c}
     */
    public void enableBattleNetMotion(int speed, int minFlight) {
        battleNetMotion = true;
        battleNetSpeed = Math.max(1, speed);
        int sx = (int) Math.round(fromX);
        int sy = (int) Math.round(fromY);
        int tx = (int) Math.round(toX);
        int ty = (int) Math.round(toY);
        x = sx;
        y = sy;
        int dx = tx - sx;
        int dy = ty - sy;
        int absDx = Math.abs(dx);
        int absDy = Math.abs(dy);
        battleNetFlags = 0;
        if (dx >= 0) {
            battleNetFlags |= 0x40;
        }
        if (dy >= 0) {
            battleNetFlags |= 0x20;
        }
        if (absDx >= absDy) {
            battleNetFlags |= 0x80;
            battleNetMajor = absDx;
            battleNetMinor = absDy;
        } else {
            battleNetMajor = absDy;
            battleNetMinor = absDx;
        }
        battleNetError = battleNetMajor >> 1;
        if (battleNetError == 0) {
            battleNetError = 1;
        }
        battleNetRemaining = Math.max(absDx, absDy);
        if (battleNetRemaining < minFlight) {
            battleNetRemaining = minFlight;
        }
        // FUN_0040fb10 / FUN_0040fdc0: +0x24 starts at 0; +0x26 is
        // ceil(remaining / 5) via (remaining + 4) / 5. The parabolic motion
        // action uses these to decide whether the second async draw fires.
        battleNetArcProgress = 0;
        battleNetArcStride = (battleNetRemaining + 4) / 5;
        // BNE has no MissileInitMove even-state burn. The creation cycle itself
        // is the non-motion beat, enforced by World before it calls step.
        moveStarted = true;
        total = battleNetRemaining;
        travelled = 0;
    }

    /** Whether {@link #enableBattleNetMotion} has configured this shot. */
    public boolean battleNetMotion() {
        return battleNetMotion;
    }

    /** Whether constructor damage/jitter draws have already been taken. */
    public boolean battleNetConstructorDrawn() {
        return battleNetConstructorDrawn;
    }

    /** Marks constructor draws spent (presentation-ahead of OP10). */
    public void setBattleNetConstructorDrawn(boolean drawn) {
        battleNetConstructorDrawn = drawn;
    }

    /** Remaining BNE flight distance, or zero when not in that mode. */
    public int battleNetRemaining() {
        return battleNetMotion ? battleNetRemaining : 0;
    }

    /**
     * When true, the next flight step advances pixels without the usual
     * point-to-point/parabolic async draw (residual-open same-visit OP10).
     */
    public boolean battleNetSkipNextMotionDraw() {
        return battleNetSkipNextMotionDraw;
    }

    public void setBattleNetSkipNextMotionDraw(boolean skip) {
        battleNetSkipNextMotionDraw = skip;
    }

    private boolean battleNetSkipNextMotionDraw;

    /**
     * Fixed BNE projectile pool index (0..199). Native walks the pool low to
     * high each update; free/reuse of lower slots while long-lived rocks keep
     * mid slots is what places free splash after two live travelers on
     * XHuman 12 fixture 35. -1 means not yet allocated.
     */
    public int battleNetPoolSlot() {
        return battleNetPoolSlot;
    }

    public void setBattleNetPoolSlot(int slot) {
        battleNetPoolSlot = slot;
    }

    private int battleNetPoolSlot = -1;

    /**
     * Number of async draws the next native parabolic action consumes.
     *
     * <p>{@code FUN_00410260} always draws at {@code 0x004102c8}. The second
     * draw at {@code 0x00410311} is skipped once the post-step {@code +0x24}
     * accumulator reaches five times the constructor's {@code +0x26} stride.
     * XHuman 10's cannon shell hits that one-draw arm at fixture cycle 13;
     * always taking two left Java one draw ahead of native for the splash
     * rolls at cycle 14.
     */
    public int battleNetParabolicDrawsOnNextStep() {
        if (!battleNetMotion || battleNetPendingImpact) {
            return 0;
        }
        return battleNetArcProgress + battleNetSpeed < 5 * battleNetArcStride
                ? 2 : 1;
    }

    // ------------------------------------------------------ saving and loading

    /*
     * A shot in the air is simulation state like any other, and it was the last
     * thing a saved game could not carry across: the endpoints, how far along
     * the flight is and the launch delay were all private with no way in or
     * out. What follows is that state and nothing else -- enough for
     * engine/save to write a half-flown boulder down and put it back where it
     * was, rather than restarting it at the muzzle or dropping it.
     *
     * Upstream saves the same fields, in COrder's missile section: source,
     * destination, CurrentStep, TotalStep and Delay.
     */

    /** Where the flight began, in map pixels. */
    public double fromX() {
        return fromX;
    }

    public double fromY() {
        return fromY;
    }

    /** Where it is aimed, in map pixels. */
    public double toX() {
        return toX;
    }

    public double toY() {
        return toY;
    }

    /** How far along the flight it is: {@code CurrentStep}. */
    public double travelled() {
        return travelled;
    }

    /** How long before it starts moving: {@code Delay}. */
    public int delay() {
        return delay;
    }

    /**
     * Puts a reloaded missile back where it had got to.
     *
     * <p>Sets the distance covered and re-derives the position from it, which
     * is the same thing {@code step} does, so a shot resumes mid-flight rather
     * than jumping back to the muzzle for one frame. Clamped to the flight's
     * own length: a saved value longer than the journey would put the missile
     * past its target and it would never arrive.
     */
    public void restoreTravelled(double covered) {
        travelled = Math.max(0, Math.min(total, covered));
        // A shot that has covered ground has already measured its journey, so
        // it must not spend another action doing it again. One that has not is
        // indistinguishable from a fresh missile and keeps its init action,
        // which is what upstream's own State would say.
        moveStarted = travelled > 0;
        if (total > 0) {
            x = fromX + (toX - fromX) * (travelled / total);
            y = fromY + (toY - fromY) * (travelled / total);
            if (type.missileClass() == MissileClass.PARABOLIC) {
                applyArc();
            }
        }
    }

    /**
     * Every mutable field needed to resume a live missile on the same action.
     *
     * <p>This is deliberately a value object rather than a set of save-only
     * setters. A loader either restores the complete state machine or it does
     * not restore the missile; a half-restored BNE projectile can land on a
     * different cycle and consume a different random draw.
     */
    public record SavedState(
            double x, double y, double fromX, double fromY, double toX, double toY,
            double travelled, double total, int bounces, boolean hit, int delay,
            int damage, int timeToLive, boolean periodicHit,
            boolean arrived, int sleep, int cycleState, boolean moveStarted,
            int frame, int direction, boolean battleNetMotion,
            boolean battleNetConstructorDrawn, boolean battleNetPendingImpact,
            int battleNetImpactWait, int battleNetSpeed, int battleNetError,
            int battleNetMajor, int battleNetMinor, int battleNetFlags,
            int battleNetRemaining, int battleNetArcProgress, int battleNetArcStride,
            boolean battleNetSkipNextMotionDraw, int battleNetPoolSlot) {}

    /** A complete, immutable snapshot for {@code engine/save}. */
    public SavedState savedState() {
        return new SavedState(x, y, fromX, fromY, toX, toY, travelled, total,
                bounces, hit, delay, damage, timeToLive, periodicHit,
                arrived, sleep, cycleState, moveStarted,
                frame, direction, battleNetMotion, battleNetConstructorDrawn,
                battleNetPendingImpact, battleNetImpactWait, battleNetSpeed,
                battleNetError, battleNetMajor, battleNetMinor, battleNetFlags,
                battleNetRemaining, battleNetArcProgress, battleNetArcStride,
                battleNetSkipNextMotionDraw, battleNetPoolSlot);
    }

    /** Rebuilds a missile without replaying its launch or constructor. */
    public static Missile restore(MissileType type, Unit source, Unit target,
            SavedState state) {
        Missile missile = new Missile(type, source, target,
                state.fromX(), state.fromY(), state.toX(), state.toY());
        missile.x = state.x();
        missile.y = state.y();
        missile.fromX = state.fromX();
        missile.fromY = state.fromY();
        missile.toX = state.toX();
        missile.toY = state.toY();
        missile.travelled = state.travelled();
        missile.total = state.total();
        missile.bounces = state.bounces();
        missile.hit = state.hit();
        missile.delay = state.delay();
        missile.damage = state.damage();
        missile.timeToLive = state.timeToLive();
        missile.periodicHit = state.periodicHit();
        missile.arrived = state.arrived();
        missile.sleep = state.sleep();
        missile.cycleState = state.cycleState();
        missile.moveStarted = state.moveStarted();
        missile.frame = state.frame();
        missile.direction = state.direction();
        missile.battleNetMotion = state.battleNetMotion();
        missile.battleNetConstructorDrawn = state.battleNetConstructorDrawn();
        missile.battleNetPendingImpact = state.battleNetPendingImpact();
        missile.battleNetImpactWait = state.battleNetImpactWait();
        missile.battleNetSpeed = state.battleNetSpeed();
        missile.battleNetError = state.battleNetError();
        missile.battleNetMajor = state.battleNetMajor();
        missile.battleNetMinor = state.battleNetMinor();
        missile.battleNetFlags = state.battleNetFlags();
        missile.battleNetRemaining = state.battleNetRemaining();
        missile.battleNetArcProgress = state.battleNetArcProgress();
        missile.battleNetArcStride = state.battleNetArcStride();
        missile.battleNetSkipNextMotionDraw = state.battleNetSkipNextMotionDraw();
        missile.battleNetPoolSlot = state.battleNetPoolSlot();
        return missile;
    }

    /**
     * One cycle of whatever this missile does: {@code Missile::Action}.
     *
     * <p>Most of them move towards their target, and a missile with a sleep of
     * more than one moves on some cycles and not others, which is how the
     * scripts make a catapult boulder lumber and an arrow snap across. The
     * classes that go nowhere -- an explosion, an order marker, a building on
     * fire -- take a branch of their own first.
     */
    public void step() {
        if (arrived) {
            return;
        }
        if (timeToLive == 0) {
            arrived = true;
            return;
        }
        if (timeToLive > 0) {
            timeToLive--;
        }
        if (delay > 0) {
            // Missile::Action's first line. A shard of a blizzard spends its
            // delay sitting invisibly at its start point, which is what
            // staggers the eleven of them into a squall.
            delay--;
            return;
        }
        if (type.sleep() > 1 && ++sleep % type.sleep() != 0) {
            return;
        }
        if (type.missileClass() == MissileClass.CYCLE_ONCE) {
            // MissileCycleOnce::Action: forwards through the frames, then
            // backwards, then gone. This is the green cross that acknowledges
            // an order, so it is the one stationary class a player sees on
            // every click -- and falling through to the travel branch below
            // launched it where it landed, so it arrived on its first step and
            // never drew at all.
            stepCycle();
            return;
        }
        if (type.missileClass() == MissileClass.FIRE) {
            // A fire outlives its own animation: it burns until the building
            // under it is repaired or destroyed. Falling through to the branch
            // below would end it the moment its six frames had run, which is
            // under half a second.
            stepFire();
            return;
        }
        if (type.missileClass() == MissileClass.LAND_MINE) {
            frame = (frame + 1) % Math.max(1, type.animationSteps());
            return;
        }
        if (type.missileClass() == MissileClass.FLAME_SHIELD) {
            stepFlameShield();
            return;
        }
        if (type.missileClass() == MissileClass.WHIRLWIND) {
            stepWhirlwind();
            return;
        }
        if (!type.missileClass().travels()) {
            // It does not travel. MissileStay::Action advances the animation
            // and is finished when that animation wraps -- which is what makes
            // an explosion something a player can see. Treating it as a
            // journey meant it was launched where it landed, arrived on its
            // first step, and was gone before a single frame was drawn.
            if (++frame >= type.animationSteps()) {
                frame = Math.max(0, type.animationSteps() - 1);
                // A missile-class-stay is an explosion sitting where it was
                // made, and upstream's MissileStay::Action calls MissileHit
                // when its animation runs out. Almost all of them do nothing
                // with it -- an impact flash has no source and no damage --
                // but a death-and-decay cloud is one of these and its whole
                // effect is that hit.
                hit = true;
                arrived = true;
            }
            return;
        }
        if (battleNetMotion) {
            stepBattleNetMotion();
            return;
        }
        double speed = Math.max(1, type.speed());
        if (total <= 0) {
            // Fired at the square it already occupies. Upstream's
            // MissileInitMove returns with TotalStep zero and the missile hits
            // at once rather than dividing by nothing.
            hit = true;
            arrived = true;
            return;
        }
        // The first action on a missile measures the journey and takes none of
        // it. {@code MissileInitMove}
        // splits on {@code missile.State & 1}: on the even pass it sets
        // {@code CurrentStep} to nought, works out {@code TotalStep}, advances
        // the state and returns, and only from the next action does
        // {@code CurrentStep += Type->Speed} happen. Every point-to-point shot
        // in the game is therefore one cycle slower than its distance and
        // speed alone say.
        //
        // Without it this implementation's shots landed a cycle early, which is not
        // visible in a screenshot and is fatal to a lockstep comparison: on
        // demo02 two battleships fire on cycle 2 and upstream's first shell
        // reaches the peasant at 0,25 on cycle 13, where this implementation's killed it
        // on 12 -- the whole of that map's divergence, since a death is a
        // damage roll and a damage roll is a draw from the shared stream.
        if (!moveStarted) {
            moveStarted = true;
            return;
        }
        travelled = Math.min(total, travelled + speed);
        double previousX = x;
        double previousY = y;
        x = fromX + (toX - fromX) * (travelled / total);
        y = fromY + (toY - fromY) * (travelled / total);
        if (type.missileClass() == MissileClass.PARABOLIC) {
            applyArc();
            // The facing follows the arc, not the aim: MissileParabolic::Action
            // re-heads from the step it just took.
            direction = headingOf(x - previousX, y - previousY, type.headingCount());
        }
        if (travelled >= total) {
            hit = true;
            if (!bounceOnward()) {
                arrived = true;
            }
            return;
        }
        // One step of the animation per cycle it moves, wrapping: upstream's
        // NextMissileFrame(1, 0), which every class that travels calls. Over
        // the facings, not over the whole sheet -- a boulder has three steps
        // and five facings in fifteen frames, and treating those fifteen as a
        // fifteen-step animation would flick it through every heading on the
        // way to its target.
        frame = (frame + 1) % type.animationSteps();
    }

    /** One orbiting Flame Shield sprite; five staggered copies form the ring. */
    private void stepFlameShield() {
        frame = (frame + 1) % Math.max(1, type.animationSteps());
        if (target == null || !target.isAlive() || !target.isOnMap()) {
            if (timeToLive < 0 || timeToLive > 35) {
                timeToLive = Math.floorMod(timeToLive, 36);
            }
            return;
        }
        int phase = Math.floorMod(timeToLive, 36);
        double angle = phase * Math.PI * 2.0 / 36.0;
        double centreX = target.pixelX()
                + Math.max(1, target.type().tileWidth()) * Unit.TILE_PIXELS / 2.0;
        double centreY = target.pixelY()
                + Math.max(1, target.type().tileHeight()) * Unit.TILE_PIXELS / 2.0;
        x = centreX + Math.sin(angle) * Unit.TILE_PIXELS;
        y = centreY + Math.cos(angle) * Unit.TILE_PIXELS;
        periodicHit = (timeToLive & 7) == 0;
    }

    /** Animated point movement for a persistent, periodically damaging whirlwind. */
    private void stepWhirlwind() {
        frame = (frame + 1) % Math.max(1, type.animationSteps());
        periodicHit = timeToLive >= 0 && timeToLive % 10 == 0;
        if (total <= 0) {
            return;
        }
        travelled = Math.min(total, travelled + Math.max(1, type.speed()));
        x = fromX + (toX - fromX) * (travelled / total);
        y = fromY + (toY - fromY) * (travelled / total);
    }

    /**
     * One update of BNE's point-motion action at {@code 0x004101f0}.
     *
     * <p>Human 13's tower arrow used to land on fixture cycle 14 with ChonkCraft
     * Euclidean flight at speed 32. Native type 15 moves twelve pixels per
     * update through {@code 0x00429fa0}'s integer direction state and only
     * detonates once remaining distance at {@code +0x20} is negative, which is
     * why the same shot is still in the air through native cycle 13.
     */
    private void stepBattleNetMotion() {
        // Action 6 beat: remaining went negative on an earlier pass; free
        // only after the type-specific wait (1 for most shots, 5 for
        // missile-catapult-rock -- Human 13).
        if (battleNetPendingImpact) {
            if (--battleNetImpactWait > 0) {
                return;
            }
            battleNetPendingImpact = false;
            hit = true;
            arrived = true;
            return;
        }
        if (battleNetRemaining < 0) {
            armBattleNetImpact();
            return;
        }
        int outA = -1;
        int outB = 0;
        // 0x00429fa0: error -= minor; if error <= 0, step the secondary axis
        // and error += major; then apply the constructor's axis/sign flags.
        battleNetError -= battleNetMinor;
        if (battleNetError <= 0) {
            outB--;
            battleNetError += battleNetMajor;
        }
        if ((battleNetFlags & 0x80) != 0) {
            int swap = outA;
            outA = outB;
            outB = swap;
        }
        if ((battleNetFlags & 0x40) != 0) {
            outB = -outB;
        }
        if ((battleNetFlags & 0x20) != 0) {
            outA = -outA;
        }
        y = (int) y + battleNetSpeed * outA;
        x = (int) x + battleNetSpeed * outB;
        battleNetRemaining -= battleNetSpeed;
        if (type.missileClass() == MissileClass.PARABOLIC) {
            // Native +0x24 advances by speed even on the step that makes
            // remaining negative; the next action-6 visit takes zero motion
            // draws, but the following live parabolic shot's draw count still
            // needs this accumulator for its own stride test.
            battleNetArcProgress += battleNetSpeed;
            updateBattleNetParabolicFrame();
        }
        travelled = Math.min(total, travelled + battleNetSpeed);
        // BNE keeps constructor facing (+0x0a) for the complete flight. The
        // point and parabolic actions update position and (for the latter)
        // frame +0x09, but neither writes the facing byte.
        if (battleNetRemaining < 0) {
            // Native action 1/2 only arm action 6 here; the hit routine runs
            // after battleNetImpactWait more passes (most types: 1;
            // catapult-rock: 5 -- Human 13 knight 1490 HP holds through
            // fixture 34 while the rock's remaining is already -5).
            armBattleNetImpact();
            return;
        }
        if (type.missileClass() != MissileClass.PARABOLIC) {
            frame = (frame + 1) % Math.max(1, type.animationSteps());
        }
    }

    /**
     * Selects BNE's five visual phases for a parabolic projectile.
     *
     * <p>{@code 0x00410260} keeps the travelled accumulator at projectile
     * {@code +0x24}, divides it into the five {@code +0x26} bands and writes
     * flattened sprite frames {@code 0,5,10,5,0} from {@code 0x0049067c}.
     * The Java renderer stores the animation row separately from its five
     * facings, so those native frame numbers become rows {@code 0,1,2,1,0}.
     * Retail changes the picture of the tumbling rock; it does not subtract a
     * made-up height from the projectile's impact coordinates.
     */
    private void updateBattleNetParabolicFrame() {
        if (battleNetArcStride <= 0) {
            return;
        }
        int phase = battleNetArcProgress / battleNetArcStride;
        if (phase >= 5) {
            return;
        }
        frame = Math.min(type.animationSteps() - 1,
                BATTLE_NET_PARABOLIC_FRAMES[phase] / type.storedFacings());
    }

    /**
     * Arms action 6 with the type-specific free delay.
     *
     * <p>Most BNE shots free on the next projectile pass (wait 1). Type-13
     * catapult rocks stay live for five more passes: Human 13 slot 3 has
     * remaining -5 at fixture 30 while knight 1490 stays at 77 HP through
     * 34, and frees with the HP drop only at 35. Applying that five-pass
     * hold to every parabolic type (including small-cannon) delayed XHuman
     * 10 splash by four cycles and reopened grunt HP at fixture 14.
     */
    private void armBattleNetImpact() {
        battleNetPendingImpact = true;
        battleNetImpactWait = "missile-catapult-rock".equals(type.ident())
                ? 5
                : 1;
    }

    /** Whether the next BNE projectile step will resolve a landed impact. */
    public boolean battleNetPendingImpact() {
        return battleNetPendingImpact;
    }

    /** Remaining action-6 visits before free; 0 if not pending. */
    public int battleNetImpactWait() {
        return battleNetPendingImpact ? battleNetImpactWait : 0;
    }

    /**
     * Lifts a shot off the ground: {@code ParabolicMissile}.
     *
     * <p>A catapult boulder and a cannon shell are declared
     * {@code missile-class-parabolic} in the shipped data and were drawn
     * skimming the ground in a dead straight line, which made a siege engine
     * look like it was rolling rocks at people.
     *
     * <p>The curve is upstream's exactly, coefficients and all. {@code z} is
     * the height of the shot, negative because {@code k} is the negated
     * parabola coefficient; there is no Z axis in the renderer, so upstream
     * projects it onto the screen axes instead -- a very small nudge left and a
     * large one upward. The result is symmetric about the midpoint of the
     * flight and zero at both ends, so the shot still starts at the muzzle and
     * still lands exactly where it was aimed.
     */
    private void applyArc() {
        double z = travelled * (total - travelled) / -PARABOLA_COEFFICIENT;
        x += z * Z_PROJECTION_X / 64.0;
        y += z * Z_PROJECTION_Y / 64.0;
    }

    /** {@code MissileType::ParabolaCoefficient}, which no shipped missile overrides. */
    private static final double PARABOLA_COEFFICIENT = 2048.0;

    /** How much of the shot's height leans across the screen. */
    private static final double Z_PROJECTION_X = 4.0;

    /** How much of it shows as height. */
    private static final double Z_PROJECTION_Y = 1024.0;

    /**
     * Sends a bouncing missile on for another hop, if it has one left.
     *
     * <p>{@code MissilePointToPointBounce::Action}. Dragon breath and a
     * gryphon's hammer carry {@code NumBounces = 3} and a fireball
     * {@code NumBounces = 5}, and the field was parsed and never read: each of
     * them detonated once and stopped, so a dragon's ordinary attack -- not a
     * spell, its attack -- was doing a third of the damage it should against a
     * line of units.
     *
     * <p>Upstream extends the destination by three quarters of a tile's width
     * plus height along the same vector and re-runs the flight from where the
     * missile now stands, so each further detonation is a tile and a half
     * beyond the last, in the direction the shot was already travelling.
     *
     * @return whether it is carrying on rather than finishing here
     */
    private boolean bounceOnward() {
        if (type.missileClass() != MissileClass.POINT_TO_POINT_BOUNCE
                || bounces + 1 >= type.numBounces()) {
            return false;
        }
        double dx = toX - fromX;
        double dy = toY - fromY;
        int length = distance(dx, dy);
        if (length <= 0) {
            return false;
        }
        bounces++;
        fromX = x;
        fromY = y;
        toX += dx / length * BOUNCE_DISTANCE;
        toY += dy / length * BOUNCE_DISTANCE;
        travelled = 0;
        total = BOUNCE_DISTANCE;
        return true;
    }

    /**
     * How far a bounce carries, in pixels.
     *
     * <p>{@code (PixelTileSize.x + PixelTileSize.y) * 3 / 4} over
     * thirty-two-pixel tiles: a tile and a half.
     */
    private static final double BOUNCE_DISTANCE = (32 + 32) * 3 / 4.0;

    /**
     * One cycle of {@code MissileFire::Action}: a building burning.
     *
     * <p>Three things happen here and each of them is the reason for a
     * requirement of the feature.
     *
     * <p>It rides its building. Upstream leaves the position where
     * {@code HitUnit_Burning} put it and only nudges it when the sprite
     * changes size, which is safe there because a building cannot walk;
     * deriving it from the unit every cycle is the same answer for a building
     * standing still and the right one for a building that has been rebuilt
     * into something larger under the flames.
     *
     * <p>It re-picks its sprite when the animation wraps, not every cycle.
     * That is upstream's {@code NextMissileFrame} returning true, and it
     * matters for looks: swapping the sheet mid-animation would snap the
     * flames from one shape to another, whereas swapping on the wrap means the
     * fire grows at the start of a fresh loop.
     *
     * <p>It puts itself out when the table has no fire for the building's
     * health -- above three quarters, in the shipped data. This is the only
     * thing that ends a fire short of the building dying, so without it a
     * building repaired to full would burn for the rest of the game. It clears
     * the building's burning flag on the way out, which is what lets the next
     * blow light a fresh fire.
     */
    private void stepFire() {
        if (source == null || !source.isAlive()) {
            // A dead building's fire goes out with it. Upstream stops at the
            // TTL and lets the unit be freed; here the unit object outlives
            // its death animation, so the flag is cleared too rather than
            // leaving a corpse marked as burning.
            extinguish();
            return;
        }
        x = fireX(source);
        y = fireY(source);
        if (++frame < type.animationSteps()) {
            return;
        }
        frame = 0;
        MissileType next = burningBuildings.missileAt(healthPercent(source));
        if (next == null) {
            extinguish();
            return;
        }
        type = next;
    }

    /** The health of a unit as a whole percentage, as upstream computes it. */
    private static int healthPercent(Unit unit) {
        int max = unit.type() == null ? 0 : unit.type().hitPoints();
        if (max <= 0) {
            return 100;
        }
        return 100 * unit.hitPoints() / max;
    }

    /** Ends a fire and lets its building catch light again. */
    private void extinguish() {
        arrived = true;
        if (source != null) {
            source.setBurning(false);
        }
    }

    /** One cycle of {@code MissileCycleOnce::Action}. */
    private void stepCycle() {
        int last = Math.max(0, type.animationSteps() - 1);
        switch (cycleState) {
            case 0, 2 -> cycleState++;
            case 1 -> {
                if (++frame >= last) {
                    frame = last;
                    cycleState++;
                }
            }
            default -> {
                if (--frame <= 0) {
                    frame = 0;
                    arrived = true;
                }
            }
        }
    }

    /**
     * Which of the sheet's facings to draw.
     *
     * <p>Zero when the type has one facing: an explosion looks the same
     * whichever way it was thrown, and eleven of the shipped types are drawn
     * that way.
     *
     * <p>Otherwise a heading, counted from north and going clockwise, which is
     * the number the sheet is indexed by. The western half of the compass is
     * drawn by mirroring the eastern half, so the caller resolves this against
     * the type's stored facings rather than using it as a column outright.
     */
    public int direction() {
        return direction;
    }

    /**
     * A flight vector as one of the type's headings.
     *
     * <p>Implements {@code MissileNewHeadingFromXY}. Warcraft II measures a
     * heading in 256ths of a turn with north at zero, cuts the circle into
     * {@code directions} buckets and rounds to the nearest -- which is not the
     * same as rounding an angle, because the bucket boundaries sit half a
     * bucket off the compass points.
     */
    private static int headingOf(double dx, double dy, int directions) {
        if (directions <= 1 || (dx == 0 && dy == 0)) {
            return 0;
        }
        // Clockwise from north, so east is 64 and west is 192, matching
        // LookingN through LookingNW in src/include/unit.h.
        int heading = directionToHeading((int) Math.round(dx), (int) Math.round(dy));
        int perDirection = Math.max(1, 256 / directions);
        return ((heading + perDirection / 2) & 0xFF) / perDirection;
    }

    /**
     * A pixel delta as an angle in 256ths of a turn.
     *
     * <p>Implements {@code DirectionToHeading}
     * and the {@code myatan} table above it, integer for integer.
     *
     * <p>It replaced {@code Math.atan2}, which is not the same function and,
     * more to the point, is not required to give the same answer twice.
     * {@code StrictMath} would have fixed the second half of that and left the
     * first: upstream's table is a 2608-entry quantisation of
     * {@code atan(i / 64)} indexed by an integer-truncated ratio, so it does
     * not agree with a rounded true arctangent at every input -- and the
     * disagreements land exactly where they show, on the boundaries between
     * two drawn facings. Doing the arithmetic upstream's way gets both:
     * bit-identical on every machine because there is no floating point left in
     * it, and the same facing Warcraft II draws.
     *
     * <p>Public because the attack order aims with the same table:
     * {@code COrder_Attack::TurnToTarget} hands its tile delta to the very
     * {@code DirectionToHeading} this ports.
     */
    public static int directionToHeading(int dx, int dy) {
        //  Which quadrant.
        if (dx > 0) {
            if (dy < 0) { // Quadrant 1
                return myatan((dx * 64) / -dy);
            }
            // Quadrant 2
            return myatan((dy * 64) / dx) + 64;
        }
        if (dy > 0) { // Quadrant 3
            return myatan((dx * -64) / dy) + 64 * 2;
        }
        if (dx != 0) { // Quadrant 4
            return myatan((dy * -64) / -dx) + 64 * 3;
        }
        return 0;
    }

    /**
     * The arc tangent table {@code myatan} builds.
     *
     * <p>Built once from {@code atan(i / 64) * (64 * 4 / 6.2831853)} truncated
     * to a byte. The one double calculation left in the missile path, and it is
     * a constant: it runs at class initialisation over a fixed 2608 inputs and
     * produces the same 2608 bytes on any machine, because {@code StrictMath}
     * is what fills it.
     */
    private static final byte[] ATAN_TABLE = new byte[2608];

    static {
        for (int i = 0; i < ATAN_TABLE.length; i++) {
            ATAN_TABLE[i] = (byte) (StrictMath.atan(i / 64.0) * (64 * 4 / 6.2831853));
        }
    }

    /** {@code myatan}: the table, with upstream's clamp above its last entry. */
    private static int myatan(int value) {
        if (value >= ATAN_TABLE.length) {
            return 63;
        }
        return ATAN_TABLE[Math.max(0, value)];
    }

    /**
     * The length of a vector, in whole pixels.
     *
     * <p>{@code Distance} in {@code src/include/vec2i.h:162}, which is
     * {@code isqrt} of the squared distance -- an integer square root, floored.
     * {@code Math.hypot} was neither: it is correctly rounded to a double, and
     * the JVM specification does not require two implementations to agree on
     * the last bit. A missile's whole flight is divided by this number, so two
     * machines that disagreed about it would place every shot in the game a
     * fraction apart and drift.
     */
    static int distance(double dx, double dy) {
        long x = Math.round(dx);
        long y = Math.round(dy);
        return (int) isqrt(x * x + y * y);
    }

    /**
     * {@code isqrt}: the largest integer whose square is no greater.
     *
     * <p>The same shape as the four other copies in this implementation. The correction
     * loops are what make it exact, and exact is what makes it the same on
     * every machine -- {@code Math.sqrt} is one of the few methods the
     * specification does require to be correctly rounded, and the loops would
     * settle it even if it were not.
     */
    private static long isqrt(long value) {
        if (value <= 0) {
            return 0;
        }
        long root = (long) Math.sqrt((double) value);
        while (root > 0 && root * root > value) {
            root--;
        }
        while ((root + 1) * (root + 1) <= value) {
            root++;
        }
        return root;
    }
}
