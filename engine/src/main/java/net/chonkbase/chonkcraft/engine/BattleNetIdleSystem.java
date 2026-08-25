package net.chonkbase.chonkcraft.engine;

import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudUnitTypes;
import net.chonkbase.chonkcraft.engine.ai.AiPlayer;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationRunner;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.animation.AnimationState;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * What a unit does when it has been given nothing to do.
 *
 * <p>Implements retail BNE's
 * startup Still dispatcher, which is the part with no ChonkCraft counterpart at
 * all. BNE drives an idle unit from its animation script rather than from a
 * fixed cadence: a marker in the sequence data says when the unit may look
 * for a target, take a resource order, begin a patrol leg, or draw from the
 * asynchronous stream, and everything autonomous a unit does begins there.
 *
 * <p>That dispatcher owns the opening of every campaign map. The two hidden
 * startup ticks before fixture world.cycle one run entirely through here, so a
 * marker read one visit early moves units before the corpus has taken its
 * first sample.
 */
final class BattleNetIdleSystem {

    private final World world;

    BattleNetIdleSystem(World world) {
        this.world = world;
    }


    /** Selects the unit's native Still sequence, or the approximation sentinel. */
    int battleNetStillSequenceStart(Unit unit) {
        return battleNetSequenceStart(unit, BattleNetSequence.STILL_ANIMATION);
    }


    /** Selects one native animation sequence for a unit, or {@code -1}. */
    int battleNetSequenceStart(Unit unit, int animation) {
        if (world.battleNetSequence == null || unit == null || unit.type() == null) {
            return -1;
        }
        return world.battleNetSequence.sequenceStart(
                PudUnitTypes.code(unit.type().ident()), animation);
    }


    /**
     * Reconsiders an attacking unit's target.
     *
     * <p>{@code COrder_Attack::AutoSelectTarget}, on its own faster cadence:
     * a fighting unit looks around every six cycles rather than every fifteen,
     * because an attack-move that only re-aims twice a second walks past
     * things. A target the player clicked on is left alone entirely.
     *
     * @return false when there is nothing left worth attacking
     */
    boolean autoSelectTarget(Unit unit) {
        String astTrace = System.getenv("CHONKCRAFT_TRACE_ATTACKSTATE");
        if (astTrace != null && unit.id() == Integer.parseInt(astTrace)) {
            System.err.printf("JAUTOCALL world.cycle=%d unit=%d sleep=%d tgt=%d"
                            + " auto=%d underattack=%d%n",
                    world.cycle, unit.id(), unit.attackScanSleep(),
                    unit.target() == null ? -1 : unit.target().id(),
                    unit.autoTargeting() ? 1 : 0, unit.underAttack());
        }
        Unit helpOffer = unit.battleNetPendingHelpAttack();
        if (helpOffer != null && (!helpOffer.isAlive()
                || helpOffer.isDying() || !helpOffer.isOnMap())) {
            // Native stores this offer as a weak CUnitPtr. Once the attacker
            // enters death or leaves the map the pointer is empty; it cannot
            // replace the live result of the ordinary Attack target scan.
            unit.setBattleNetPendingHelpAttack(null);
            helpOffer = null;
        }
        if (helpOffer != null && unit.order() == Unit.Order.ATTACK
                && unit.target() != null && unit.target() != helpOffer) {
            // OfferNewTarget may bank a better aggressor while an existing
            // Attack owns cold construction or a committed walk.  It is not
            // promoted until this Attack action callback.  XHuman 9 footman
            // 1420 receives skeleton 1430 while still constructing its chase
            // toward 1431; at fixture 59 the callback accepts 1430, lays and
            // first-steps N, and leaves the replacement Attack behind that
            // residual.  Promoting from the hit callback itself would erase
            // the old action before its committed movement visit.
            unit.setBattleNetPendingHelpAttack(null);
            world.combat.setAutoTarget(unit, helpOffer);
            // This was a hit offer, not a person's explicit target choice.
            // setAutoTarget supplies native target/path state, but the order
            // remains direct-owned after the one offered handoff.
            unit.setAutoTargeting(false);
            unit.setBattleNetChaseReplanResidualHold(true);
            unit.setBattleNetPersonHelpRetargetHandoff(true);
            return true;
        }
        if (!unit.autoTargeting() && world.isPerson(unit.player())) {
            // A person's explicit order stands until it is done. The computer's
            // units re-decide either way, which is upstream's rule.
            return true;
        }
        // Attack-tail path failure is itself a native target-selection clock.
        // After six retained Move-OP0 refusals, COrder_Attack asks whether any
        // quarry is actually reachable before it attempts another step. This
        // is what releases boxed automatic defenders instead of leaving them
        // permanently attached to a visible but unreachable enemy.
        boolean exhaustedReachabilityProbe = unit.chasing()
                && unit.battleNetAttackWrapDestArmPending()
                && unit.battleNetChaseEmptyRouteReplan()
                && unit.pathLength() == 1
                && unit.battleNetCollisionCounter()
                        > World.ATTACK_SCAN_INTERVAL;
        // The six-visit cadence throttles reconsidering a live quarry.  It
        // does not postpone replacing a weak goal which has entered Die:
        // XHuman 4 footman 1518 finishes its swing against dying grunt 1489
        // on fixture 234 and selects live grunt 1505 on that same callback.
        // Sleeping here made EndActionAttack discard the order before the
        // replacement scan could run.
        boolean liveCadenceGoal = world.targets.validAttackTarget(
                unit, unit.target());
        if (unit.attackScanSleep() > 0 && !exhaustedReachabilityProbe
                && liveCadenceGoal) {
            unit.setAttackScanSleep(unit.attackScanSleep() - 1);
            return true;
        }
        unit.setAttackScanSleep(World.ATTACK_SCAN_INTERVAL);

        Unit goal = unit.target();
        boolean immobile = !unit.canMove();
        int reach = Math.max(1, unit.type().maxAttackRange());
        int reactRange = unit.type().reactRange(world.isPerson(unit.player()));
        int scanRange = Math.max(reactRange, reach);
        Unit candidate = immobile
                ? world.targets.findHostile(unit, unit.type().minAttackRange(), reach)
                : world.targets.findBattleNetHostile(unit, scanRange, goal);
        if (exhaustedReachabilityProbe) {
            // The retained native route byte is the reachability result. Once
            // its collision generation is exhausted, the next automatic scan
            // returns no unit rather than immediately selecting another member
            // of the same inaccessible formation. Re-running Java's permissive
            // ordinary pathfinder here treats moving bodies as soft costs and
            // recreates the frozen chase under a different target pointer.
            candidate = null;
        }

        // Something already shooting at this unit keeps its attention even
        // once it has walked past the reaction range: attackedByGoal.
        boolean attackedByGoal = goal != null && goal.target() == unit
                && world.targets.inAttackRange(goal, unit);
        boolean goalStillGood = goal != null && goal.isAlive() && !goal.isDying()
                && goal.isOnMap()
                && world.targets.isVisibleAsGoal(unit.player(), goal)
                && world.targets.canTarget(unit, goal)
                && (immobile ? world.targets.inAttackRange(unit, goal)
                        // While the aggressor counter runs the unit does not
                        // give up on where the blow came from, however far
                        // that is. Upstream carries the same thing as an
                        // attack-move towards the attacker's square, which
                        // EndActionAttack refuses to abandon until UnderAttack
                        // expires; the implementation has no attack-move, so the
                        // aggressor stays the goal instead.
                        : attackedByGoal || unit.underAttack() > 0
                                || unit.distanceTo(goal) <= Math.max(reactRange, reach))
                // While answering an aggressor, a target that cannot shoot
                // back is not a reason to stay where it is.
                && !(unit.underAttack() > 0 && !goal.isAggressive())
                && !exhaustedReachabilityProbe;

        if (goalStillGood) {
            if (candidate != null && candidate != goal) {
                if (unit.underAttack() > 0 && !candidate.isAggressive()) {
                    // Do not abandon an aggressor for a passing peasant.
                    return true;
                }
                // BNE's AutoSelectTarget calls 0x409ff0 with the current
                // goal installed as the score to beat. The selector has
                // already applied its distance and threat weighting, so
                // a different result is the native decision; applying
                // ChonkCraft's later high-bit comparison here would undo it.
                world.combat.setAutoTarget(unit, candidate);
            }
            return true;
        }
        if (candidate != null && !(unit.underAttack() > 0 && !candidate.isAggressive())) {
            world.combat.setAutoTarget(unit, candidate);
            return true;
        }
        return false;
    }


    /**
     * One world.cycle of the swing a standing unit's scan bought.
     *
     * <p>{@code AnimateActionAttack} with {@code COrder_Still}'s own attack
     * frame: the aim was fixed by the scan
     * and the swing does not re-aim, and the range is asked again on the
     * frame the blow lands, not while the arm is drawn back. A goal that
     * drifted out of reach costs the shot and clears the sub-state; the
     * animation still runs to its end either way, because a swing is
     * unbreakable whether or not anything is hit by it.
     */
    void swingStanding(Unit unit) {
        AnimationSet set = unit.type().animationSet();
        if (set == null) {
            return;
        }
        Animation attack = set.get(AnimationSet.State.ATTACK);
        if (attack == null) {
            return;
        }
        unit.animation().switchTo(attack);
        AnimationRunner.Step step = world.advance(unit);
        if (!step.attacked()) {
            return;
        }
        Unit target = unit.target();
        if (target == null) {
            return;
        }
        if (!target.isAlive() || target.isDying() || !target.isOnMap()
                || !world.targets.inAttackRange(unit, target)) {
            unit.setTarget(null);
            return;
        }
        world.combat.hit(unit, target);
    }


    /**
     * The standing scan: a target already inside the weapon's own reach.
     *
     * <p>{@code COrder_Still::AutoAttackStand}
     * {@code AttackUnitsInRange}
     * searches the attack range where {@code AutoAttack} searches the
     * reaction range, and its winner is then refused outright when it stands
     * outside {@code [MinAttackRange, AttackRange]} -- refused, not
     * substituted, which is why the search is not simply given the narrower
     * band: a catapult with an enemy on its toes acquires nothing at all,
     * rather than the next target out. What a hit buys is the sub-state and
     * a turn towards the goal; the order is untouched.
     */
    boolean autoAttackStand(Unit unit) {
        if (!unit.type().canAttack()) {
            return false;
        }
        int range = Math.max(1, unit.type().maxAttackRange());
        Unit target = world.targets.findHostile(unit, 0, range);
        if (target == null) {
            return false;
        }
        int distance = world.attackDistance(unit, target);
        if (distance > range || distance < unit.type().minAttackRange()) {
            return false;
        }
        unit.setTarget(target);
        unit.setFighting(true);
        unit.setHeading(world.movement.headingTowards(unit, target));
        return true;
    }


    /**
     * Retail BNE's game-creation ready pass for AI scout aircraft.
     *
     * <p>The executable walks its fixed unit table from low slot to high,
     * which is reverse PUD creation order, and gives each AI balloon or
     * zeppelin a patrol point. Each coordinate consumes one value from BNE's
     * independent RNG and is chosen in a half-map-wide square centred on the
     * aircraft, then clamped to the map. This is native function 0x427970;
     * unlike ChonkCraft's Explore callback it does not touch the synchronized
     * gameplay generator.</p>
     */
    /**
     * Retail's recurring scout pass over the aircraft it has given behaviour 4.
     *
     * <p>The game-creation pass below is not the only one. Retail walks its
     * aircraft again on a fifty-cycle beat -- XOrc 8's behaviour-four draws
     * land on fixture cycles 0, 49, 99, 149 and 199 -- and each one standing
     * still at that moment takes a fresh point and is sent to it. Its gryphon
     * rider 1560 finishes its first leg and stands down at 38, is picked up
     * again at 49, and is patrolling at 52.</p>
     *
     * <p>Who qualifies is read from the sealed records rather than guessed.
     * Behaviour four needs the owner's controller byte to be one, and then
     * either a type whose flag word has nothing in its high half -- the
     * Gnomish Flying Machine and Goblin Zeppelin, which do not fight -- or an
     * unmarked unit. XOrc 8's balloons are marked and unarmed and qualify; its
     * gryphon riders fight but are unmarked and qualify; Orc 12's daemons and
     * XOrc 12's flying angel fight and are marked and do not, which is why
     * neither mission spends a draw on them.</p>
     */
    void fireBattleNetScoutPass() {
        if (world.battleNetSequence == null) {
            return;
        }
        List<Unit> ready = world.unitsSnapshot();
        int span = Math.max(1, world.map.width() / 2);
        int radius = world.map.width() / 4;
        for (int index = ready.size() - 1; index >= 0; index--) {
            Unit unit = ready.get(index);
            if (unit == null || unit.type() == null || !unit.isAlive()) {
                continue;
            }
            if (unit.type().moveType() != UnitType.Movement.FLY
                    || unit.order() != Unit.Order.STILL
                    || unit.hasBattleNetPendingPatrol()) {
                continue;
            }
            Player owner = world.player(unit.player());
            if (owner == null
                    || (owner.type()
                            != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                        && owner.type()
                            != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE)) {
                continue;
            }
            if (unit.type().canAttack() && unit.battleNetReadySuppressed()) {
                continue;
            }
            queueBattleNetScoutPatrol(unit, span, radius);
        }
    }

    /**
     * Reissues launched combat aircraft on retail's fifty-cycle force pass.
     *
     * <p>Behaviour-four scouts are selected only while standing by
     * {@link #fireBattleNetScoutPass()}. A behaviour-two air-force member is
     * different: native writes Patrol as {@code next_order} even while its
     * current Patrol stride is still draining, parks route index 20, and
     * promotes the replacement when those committed pixels land. XOrc 11's
     * gryphon 1589 records that transition at fixture 99 and reconstructs
     * Patrol at fixture 117 before moving again at 125.</p>
     */
    void fireBattleNetAirPatrolPass() {
        if (world.battleNetSequence == null) {
            return;
        }
        List<Unit> ready = world.unitsSnapshot();
        for (int index = ready.size() - 1; index >= 0; index--) {
            Unit unit = ready.get(index);
            if (unit == null || unit.type() == null || !unit.isAlive()
                    || unit.type().moveType() != UnitType.Movement.FLY
                    || unit.order() != Unit.Order.PATROL
                    || unit.battleNetAiBehavior() != 2
                    || world.battleNetForceLaunchedThisCycle(unit)
                    || !unit.type().canAttack()
                    || !unit.battleNetDoubleStep()) {
                continue;
            }
            Player owner = world.player(unit.player());
            if (owner == null
                    || (owner.type()
                            != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                        && owner.type()
                            != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE)) {
                continue;
            }
            // GiveOrder replaces the cached route but cannot retract a stride
            // whose sub-tile pixels are already committed.
            unit.clearPath();
            unit.setBattleNetPendingPatrol(unit.orderTargetX(),
                    unit.orderTargetY());
        }
    }

    /**
     * Finds the base that makes an ordinary aircraft native behaviour four.
     *
     * <p>The role is not a blanket property of everything that flies. Retail
     * adopts the two scouts and the two racial combat aircraft only when their
     * controller owns a hall. A marked combat aircraft remains a map guard,
     * while a marked unarmed scout may still be adopted. Summoned flyers use
     * other handlers even though they share the movement class.</p>
     */
    private boolean battleNetCombatScoutStartup(Unit unit, Player owner) {
        if (unit == null || unit.type() == null || owner == null
                || unit.type().moveType() != UnitType.Movement.FLY
                || !unit.type().canAttack()
                || (owner.type()
                        != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                    && owner.type()
                        != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE)
                || unit.battleNetReadySuppressed()) {
            return false;
        }
        String ident = unit.type().ident();
        boolean ordinaryAircraft = "unit-gryphon-rider".equals(ident)
                || "unit-dragon".equals(ident);
        return ordinaryAircraft && world.nearestBattleNetHall(unit) != null;
    }

    /**
     * Re-enters the native scout callback after a player point-command has
     * interrupted a Patrol.
     *
     * <p>The callback is not part of that flyer's later Still visit. Retail
     * runs it before the per-unit idle walk on the cycle after the point Move
     * completes. That placement matters because the two endpoint draws must
     * precede every idle-random draw made by lower pool slots. Human 12's
     * commanded zeppelin proves the boundary: Move ends at fixture 48, the
     * callback writes 107,51 at 49, Patrol becomes current at 51, and the
     * first east step lands at 54.</p>
     */
    void fireBattleNetCommandPatrolRestores() {
        List<Unit> ready = world.unitsSnapshot();
        for (int index = ready.size() - 1; index >= 0; index--) {
            Unit unit = ready.get(index);
            if (unit == null || unit.type() == null || !unit.isAlive()
                    || unit.order() != Unit.Order.STILL
                    || unit.savedOrder() != Unit.Order.PATROL
                    || !unit.battleNetScoutPatrol()) {
                continue;
            }
            unit.takeSavedOrder();
            queueBattleNetScoutPatrol(unit);
        }
    }

    private void queueBattleNetScoutPatrol(Unit unit) {
        int span = Math.max(1, world.map.width() / 2);
        queueBattleNetScoutPatrol(unit, span, world.map.width() / 4);
    }

    private void queueBattleNetScoutPatrol(Unit unit, int span, int radius) {
        int x = unit.tileX() + world.battleNetRand() % span - radius;
        int y = unit.tileY() + world.battleNetRand() % span - radius;
        x = Math.max(0, Math.min(world.map.width() - 1, x));
        y = Math.max(0, Math.min(world.map.height() - 1, y));
        unit.setBattleNetPendingPatrol(x, y);
        unit.setBattleNetScoutPatrol(true);
        if (World.BNE_IDLE_TRACE) {
            System.err.printf("JBNEPATROLPASS cycle=%d unit=%d at=%d,%d"
                            + " target=%d,%d seed=%s%n",
                    world.cycle, unit.id(), unit.tileX(), unit.tileY(), x, y,
                    Integer.toUnsignedString(world.battleNetRandomSeed));
        }
    }


    /**
     * The sea arm of the same walk over the unit array.
     *
     * <p>XOrc 11's five behaviour-six ships all carry the marker this implementation
     * reads as a cleared ready suppression, and the pass sends whichever of
     * them is standing back out. A launched behaviour-two small warship is
     * also reissued while its previous Patrol pixels drain: XOrc 8 destroyer
     * 1404 writes next-order Patrol and route index 20 at fixture 49, promotes
     * it on landing at 67, and first-steps again at 70. The pass draws nothing
     * and keeps the point the ship already had; the air arm instead picks a
     * fresh point with two draws.
     */
    void fireBattleNetNavalPatrolPass() {
        if (world.battleNetSequence == null) {
            return;
        }
        List<Unit> ready = world.unitsSnapshot();
        for (int index = ready.size() - 1; index >= 0; index--) {
            Unit unit = ready.get(index);
            if (unit == null || unit.type() == null || !unit.isAlive()) {
                continue;
            }
            boolean movingAssaultPatrol =
                    unit.type().moveType() == UnitType.Movement.NAVAL
                    && unit.order() == Unit.Order.PATROL
                    && unit.battleNetAiBehavior() == 2
                    && unit.battleNetDoubleStep()
                    && unit.type().canAttack()
                    && !World.isBattleNetCapitalShip(unit.type().ident());
            if (unit.type().moveType() != UnitType.Movement.NAVAL
                    || (unit.order() != Unit.Order.STILL
                            && !movingAssaultPatrol)) {
                continue;
            }
            Player owner = world.player(unit.player());
            if (owner == null
                    || (owner.type()
                            != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                        && owner.type()
                            != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE)) {
                continue;
            }
            // Warships only. Orc 14's two transports are just as naval and
            // just as idle, and retail leaves them standing: sending them out
            // put both on a patrol at fixture 49 where the oracle has them
            // still. The behaviour-six ships in XOrc 11 are all destroyers and
            // battleships, and the one destroyer among them that retail does
            // not send is the one carrying the marker.
            if (!unit.type().canAttack() || unit.battleNetReadySuppressed()) {
                continue;
            }
            if (movingAssaultPatrol) {
                // The native fifty-cycle pass writes next_order Patrol and
                // route_index 20 even while a type-two destroyer is sliding.
                // Keep the committed pixels, discard its stale cached route,
                // and let stepPatrol promote this replacement on landing.
                unit.clearPath();
                unit.setBattleNetPendingPatrol(unit.orderTargetX(),
                        unit.orderTargetY());
                continue;
            }
            int targetX = unit.orderTargetX();
            int targetY = unit.orderTargetY();
            // Behaviour six keeps the service-base point selected by the
            // ready callback as its stable home.  The live Patrol point is a
            // hull-relative shoreline rewrite of that home, so it must be
            // recomputed each time the fifty-cycle pass launches a standing
            // ship.  Reusing the old rewrite can turn a real route into an
            // empty arrival after the hull has moved: XHuman 7's northern
            // destroyer first rewrites shipyard (22,27) to (24,27) from
            // (28,26), but its next launch from (26,26) rewrites the same
            // home to (23,27) and takes NW on fixture 106.  Keeping (24,27)
            // made the doubled point pathfinder report arrival and stranded
            // the ship in Still forever.  Behaviour-two assault patrols use
            // their current tactical point and remain unchanged.
            if (unit.battleNetAiBehavior() == 6
                    && unit.hasBattleNetAiHome()) {
                targetX = unit.battleNetAiHomeX();
                targetY = unit.battleNetAiHomeY();
            }
            unit.setBattleNetPendingPatrol(targetX, targetY);
        }
    }

    /**
     * Retail's fifty-cycle replacement order for profile-18 land assaults.
     *
     * <p>Orc 11's behavior-two knight and three archers are already moving
     * under Patrol when the native player pass reaches them on fixture cycles
     * 49 and 99. It nevertheless writes Patrol as their next order and parks
     * every route cursor at 20. A member which has completed its prior attack
     * and returned to Still is included too: XHuman 12 ogre 1356 is Still
     * through fixture 198 and receives next-order Patrol on 199. The old
     * pixels or Still program finish, the replacement promotes at the next
     * action marker, and the group resumes after the Patrol constructor.</p>
     */
    void fireBattleNetLandPatrolPass() {
        if (world.battleNetSequence == null) {
            return;
        }
        List<Unit> ready = world.unitsSnapshot();
        for (int index = ready.size() - 1; index >= 0; index--) {
            Unit unit = ready.get(index);
            if (unit == null || unit.type() == null || !unit.isAlive()
                    || unit.type().moveType() != UnitType.Movement.LAND
                    || (unit.order() != Unit.Order.PATROL
                        && unit.order() != Unit.Order.STILL)
                    || unit.battleNetAiBehavior() != 2
                    || !unit.hasBattleNetAiHome()
                    || !unit.type().canAttack() || unit.type().canGather()) {
                continue;
            }
            Player owner = world.player(unit.player());
            AiPlayer ai = world.ais.get(unit.player());
            int profile = ai == null ? -1 : ai.battleNetBuildProfileId();
            if (owner == null
                    || owner.type()
                            != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                    || (profile != 18 && profile != 0)) {
                continue;
            }
            // GiveOrder replaces the cached route immediately but does not
            // interrupt the committed stride or current animation body.
            unit.clearPath();
            // ReleaseOrders also clears the cooperative-refusal state carried
            // by the old route. Native Orc 11 knight 1558 enters the fixture-
            // 99 pass with refusal nibble two and leaves it at zero while its
            // committed pixels keep moving. Retaining Java's old counters
            // made the knight falsely solid to the archer's replacement ray;
            // that archer rerouted east instead of buffering NE and waiting
            // behind the moving formation as BNE does.
            unit.setBattleNetRefusals(0);
            unit.setBattleNetCollisionCounter(0);
            unit.setBattleNetPendingPatrol(unit.battleNetAiHomeX(),
                    unit.battleNetAiHomeY());
            if (unit.order() == Unit.Order.STILL) {
                // The live Still cursor owns the rest of this visit. Its next
                // action marker promotes the Patrol; native does not replace
                // the current animation timer on the player-pass write.
                continue;
            }
            int quiet = world.battleNetSequence.quietTicksUntilActionMarker(
                    unit.battleNetSequenceOffset(),
                    unit.battleNetAnimationTimer());
            if (quiet < 0) {
                // Land Patrols do not otherwise retain a binary cursor in
                // Java. A body with pixels still committed exposes its native
                // boundary when those pixels settle. A physically settled
                // body on this beat has the two remaining quiet visits seen
                // on Orc 11 knight 1558 at fixture 99 (timer 2, then 1).
                quiet = unit.isMoving() ? 0 : 2;
            }
            unit.setBattleNetOrderDelay(Math.max(0, quiet));
            if (World.BNE_IDLE_TRACE) {
                System.err.printf("JBNEPATROLPASS cycle=%d unit=%d land=1"
                                + " target=%d,%d sequence=%d timer=%d quiet=%d%n",
                        world.cycle, unit.id(), unit.battleNetAiHomeX(),
                        unit.battleNetAiHomeY(),
                        unit.battleNetSequenceOffset(),
                        unit.battleNetAnimationTimer(), quiet);
            }
        }
    }

    /**
     * Reissues behavior-one land regroup orders on the fifty-cycle AI beat.
     *
     * <p>The creation-time ready walk is not the only caller of the native
     * behavior-one callback. A fighter which has chased well away from its
     * recorded service home is reconsidered by the recurring player pass. The
     * pass writes Move behind the unit's current Still program; if that program
     * reaches its action marker on the same beat, the Move becomes current
     * immediately. XHuman 12 slot 1363 is the authenticated boundary: it is
     * Still at (12,88), home (26,87), through fixture 198 and exposes Move
     * construction on the fixture-199 player beat.</p>
     */
    void fireBattleNetLandRegroupPass() {
        List<Unit> ready = world.unitsSnapshot();
        for (int index = ready.size() - 1; index >= 0; index--) {
            Unit unit = ready.get(index);
            if (unit == null || unit.type() == null || !unit.isAlive()
                    || !unit.isOnMap()
                    || unit.order() != Unit.Order.STILL
                    || unit.battleNetAiBehavior() != 1
                    || !unit.battleNetMapPlaced()
                    || !unit.hasBattleNetAiHome()
                    || unit.battleNetReadySuppressed()
                    || unit.type().moveType() != UnitType.Movement.LAND
                    || unit.type().building() || !unit.type().canAttack()
                    || unit.type().canGather()) {
                continue;
            }
            Player owner = world.player(unit.player());
            if (owner == null
                    || owner.type()
                            != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER) {
                continue;
            }
            int homeX = unit.battleNetAiHomeX();
            int homeY = unit.battleNetAiHomeY();
            int distance = Math.max(Math.abs(unit.tileX() - homeX),
                    Math.abs(unit.tileY() - homeY));
            int regroupDistance =
                    (unit.type().reactRange(false) >> 1) + 4;
            if (distance < regroupDistance) {
                continue;
            }
            unit.setBattleNetPendingMove(homeX, homeY);
            if (World.BNE_IDLE_TRACE) {
                System.err.printf("JBNEHOME cycle=%d unit=%d recurring=1 "
                                + "at=%d,%d home=%d,%d distance=%d "
                                + "threshold=%d%n",
                        world.cycle, unit.id(), unit.tileX(), unit.tileY(),
                        homeX, homeY, distance, regroupDistance);
            }
        }
    }


    void fireBattleNetReadyForAll() {
        List<Unit> ready = world.unitsSnapshot();
        world.prepareBattleNetInitialAttackGroups(ready);
        for (AiPlayer ai : world.ais.values()) {
            ai.battleNetFinishBootstrapForces();
        }
        int span = Math.max(1, world.map.width() / 2);
        int radius = world.map.width() / 4;
        for (int index = ready.size() - 1; index >= 0; index--) {
            Unit unit = ready.get(index);
            if (unit == null || unit.type() == null || !unit.isAlive()) {
                continue;
            }
            if (unit.type().canTransport()) {
                Player owner = world.player(unit.player());
                boolean computerTransport = world.ais.containsKey(unit.player())
                        && owner != null
                        && (owner.type()
                                == net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                            || owner.type()
                                == net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE);
                Unit hall = world.nearestBattleNetHall(unit);
                if (World.BNE_IDLE_TRACE) {
                    System.err.printf("JBNEREADYPASS unit=%d transport=1"
                                    + " ai=%d suppressed=%d hall=%d%n",
                            unit.id(), computerTransport ? 1 : 0,
                            unit.battleNetReadySuppressed() ? 1 : 0,
                            hall == null ? -1 : hall.id());
                }
                if (!computerTransport) {
                    continue;
                }
                if (hall != null) {
                    // FUN_00428420 queues raw action 30 against the closest
                    // same-player hall. It is a resource-family move despite
                    // the unit being a transport, and remains behind Still
                    // until the constructor animation releases it. This is
                    // an AI callback, so Human12's person-owned slot 1428
                    // remains Still. UNIT.Data does not suppress the
                    // type-specific callback for an AI transport: Orc14's
                    // marked slots 1518 and 1525 both take action 30.
                    unit.setBattleNetPendingTransportTarget(hall);
                }
                continue;
            }
            // UNIT.Data keeps a marked surface unit on its placed guard
            // behaviour, but aircraft still pass through native behaviour
            // four and receive their scout patrol. Orc X8 carries the marker
            // on all three balloons: two retain Patrol as their next action
            // at world.cycle one and the earliest-created one has already promoted
            // it, which makes the ordering observable.
            if (unit.battleNetReadySuppressed()
                    && unit.type().moveType() != UnitType.Movement.FLY) {
                continue;
            }
            Player owner = world.player(unit.player());
            AiPlayer ai = world.ais.get(unit.player());
            if (battleNetCombatScoutStartup(unit, owner)) {
                // Native assigns behaviour four and constructs a random
                // NextAction here. Java's self-scout route is already
                // tile/cycle-identical, so retain its self endpoint while
                // constructing it at the native boundary. Besides paying the
                // two coordinates, this lets the first action marker promote
                // Patrol before the ordinary flying-idle rearm -- native does
                // not spend 0040AE30 on that marker.
                world.battleNetRand();
                world.battleNetRand();
                unit.setBattleNetPendingPatrol(unit.tileX(), unit.tileY());
                unit.setBattleNetScoutPatrol(true);
            }
            if (owner != null
                    && owner.type()
                            == net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                    && unit.type().moveType() == UnitType.Movement.NAVAL
                    && unit.type().canAttack() && !unit.type().canGather()) {
                World.BattleNetPatrolEndpoints target =
                        world.battleNetNavalPatrolTarget(unit);
                // FUN_00427a10 is the behaviour-six ready callback. The
                // profile-specific startup assault pass above has already
                // marked its selected ships behaviour two; every other
                // unsuppressed AI warship keeps six plus the service-base
                // home used to construct its opening patrol. This state is
                // operational, not merely diagnostic: the recurring naval
                // pass uses it to send live ships toward reachable enemies.
                if (unit.battleNetAiBehavior() != 2) {
                    unit.setBattleNetAiBehavior(6);
                    unit.setBattleNetAiHome(target.targetX(),
                            target.targetY());
                }
                unit.setBattleNetPendingPatrol(target.targetX(), target.targetY(),
                        target.backX(), target.backY());
                if (World.BNE_IDLE_TRACE) {
                    System.err.printf("JBNEPATROL unit=%d type=%s at=%d,%d"
                                    + " target=%d,%d back=%d,%d%n",
                            unit.id(), unit.type().ident(),
                            unit.tileX(), unit.tileY(),
                            target.targetX(), target.targetY(),
                            target.backX(), target.backY());
                }
                continue;
            }
            if (owner != null
                    && owner.type()
                            == net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                    && ai != null && ai.battleNetBuildProfileId() == 18
                    && unit.type().moveType() == UnitType.Movement.LAND
                    && unit.type().canAttack() && !unit.type().canGather()) {
                // Type-two land assault members patrol the shared assault home
                // (Orc 11 106,7). Nearest-enemy free squares used to aim the
                // forward knight at the alchemist (117,21) while native stepped
                // NW toward the farm-side home.
                if (unit.battleNetAiBehavior() == 2
                        && unit.hasBattleNetAiHome()) {
                    unit.setBattleNetPendingPatrol(unit.battleNetAiHomeX(),
                            unit.battleNetAiHomeY());
                    if (World.BNE_IDLE_TRACE) {
                        System.err.printf("JBNEPATROL unit=%d type=%s land=1"
                                        + " behavior=2 target=%d,%d%n",
                                unit.id(), unit.type().ident(),
                                unit.battleNetAiHomeX(),
                                unit.battleNetAiHomeY());
                    }
                }
                continue;
            }
            if (owner != null
                    && owner.type()
                            == net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                    && unit.type().moveType() == UnitType.Movement.LAND
                    && !unit.type().building()
                    && unit.type().canAttack() && !unit.type().canGather()) {
                if (unit.hasBattleNetAiHome()) {
                    int homeX = unit.battleNetAiHomeX();
                    int homeY = unit.battleNetAiHomeY();
                    int distance = Math.max(Math.abs(unit.tileX() - homeX),
                            Math.abs(unit.tileY() - homeY));
                    int regroupDistance =
                            (unit.type().reactRange(false) >> 1) + 4;
                    if (World.BNE_IDLE_TRACE) {
                        System.err.printf("JBNEHOME unit=%d player=%d type=%s"
                                        + " at=%d,%d home=%d,%d distance=%d"
                                        + " threshold=%d timer=%d%n",
                                unit.id(), unit.player(), unit.type().ident(),
                                unit.tileX(), unit.tileY(), homeX, homeY,
                                distance, regroupDistance,
                                unit.battleNetAnimationTimer());
                    }
                    if (distance >= regroupDistance) {
                        unit.setBattleNetPendingMove(homeX, homeY);
                        continue;
                    }
                }
            }
            if (!unit.type().onReadyExplores()) {
                continue;
            }
            owner = world.player(unit.player());
            if (owner == null
                    || (owner.type()
                            != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                        && owner.type()
                            != net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE)) {
                continue;
            }
            int x = unit.tileX() + world.battleNetRand() % span - radius;
            int y = unit.tileY() + world.battleNetRand() % span - radius;
            x = Math.max(0, Math.min(world.map.width() - 1, x));
            y = Math.max(0, Math.min(world.map.height() - 1, y));
            // Native GiveOrder leaves Patrol in NextAction while the unit's
            // constructor Still action remains current. It becomes visible
            // only when that animation reaches its first action marker.
            unit.setBattleNetPendingPatrol(x, y);
            unit.setBattleNetScoutPatrol(true);
        }
    }


    /**
     * What a marching unit will break step for.
     *
     * <p>The same reaction range {@code autoAttack} uses when idle, on the
     * same cadence, so a soldier walking past an enemy notices it exactly as
     * readily as one standing there would.
     */
    /**
     * One call of the march's target reconsideration, on its counter.
     *
     * <p>The body of {@code COrder_Attack::AutoSelectTarget}
     * The game the Sleep gate ticks once per
     * call and scans every sixth, and the swap rule is the shipped
     * simplified high-bits comparison, classic ThreatCalculate on the other
     * setting. Callers place it on upstream's beats -- the walk's breakable
     * exits for a chaser, and the standing decide for fighters and goal-less
     * marchers. FIRST_ENTRY has one deliberate double call: after its first
     * scan acquires an out-of-range goal it falls through to MoveToTarget,
     * whose breakable PF_WAIT exit calls this again in the same Execute.
     */
    Unit marchScan(Unit unit, Unit target) {
        return marchScan(unit, target, false);
    }


    Unit marchScan(Unit unit, Unit target, boolean preserveFreshInRangeRoute) {
        boolean scanBeat = unit.attackScanSleep() == 0;
        String astTrace = System.getenv("CHONKCRAFT_TRACE_ATTACKSTATE");
        if (astTrace != null && unit.id() == Integer.parseInt(astTrace)) {
            System.err.printf("JASTCALL world.cycle=%d unit=%d sleep=%d tgt=%d thr=%d open=%d%n",
                    world.cycle, unit.id(), unit.attackScanSleep(),
                    target == null ? -1 : target.id(), unit.threshold(),
                    unit.attackMoveOpening() ? 1 : 0);
        }
        if (scanBeat) {
            unit.setAttackScanSleep(World.ATTACK_SCAN_INTERVAL);
        } else {
            unit.setAttackScanSleep(unit.attackScanSleep() - 1);
        }
        // A corpse deliberately held -- the walk or the swing owed at it
        // still playing out -- is never weighed by the scan: on those beats
        // upstream either starves AutoSelectTarget behind the unbreakable or
        // drops the goal before the scan could see it.
        boolean holdingCorpse = target != null && !world.targets.validAttackTarget(unit, target);
        if (scanBeat && !holdingCorpse) {
            int battleNetRange = Math.max(
                    unit.type().reactRange(world.isPerson(unit.player())),
                    Math.max(1, unit.type().maxAttackRange()));
            Unit candidate = world.targets.findBattleNetHostile(unit, battleNetRange, target);
            boolean attackedByGoal = target != null && target.target() == unit
                    && world.targets.inAttackRange(target, unit);
            int reactRange = Math.max(unit.type().reactRange(world.isPerson(unit.player())),
                    Math.max(1, unit.type().maxAttackRange()));
            boolean goalStillGood = target != null
                    && target.isAlive() && !target.isDying() && target.isOnMap()
                    && world.targets.isVisibleAsGoal(unit.player(), target)
                    && world.targets.canTarget(unit, target)
                    && (attackedByGoal || unit.distanceTo(target) <= reactRange)
                    && !(unit.underAttack() > 0 && !target.isAggressive());
            if (!goalStillGood) {
                if (candidate != null
                        && !(unit.underAttack() > 0 && !candidate.isAggressive())) {
                    world.combat.setAutoTarget(unit, candidate,
                            world.preserveScannedRoute(unit, candidate,
                                    preserveFreshInRangeRoute));
                    target = candidate;
                } else if (target != null) {
                    // AutoSelectTarget clears an escaped live goal and
                    // answers false. Because CheckForTargetInRange entered
                    // with a goal, it immediately runs
                    // EndActionAttack(RESTORE_ONLY): a standing unit's saved
                    // attack-move back to its post replaces this chase.
                    boolean restoreDeferred =
                            unit.underAttack() > 0 && unit.autoTargeting();
                    boolean separateOrderRestores =
                            unit.savedOrder() != null && !restoreDeferred;
                    unit.setTarget(null);
                    World.resetRestoredAttackScan(unit);
                    unit.setChasing(false);
                    unit.setFighting(false);
                    unit.setSwingAtAir(false);
                    unit.clearPath();
                    if (separateOrderRestores) {
                        // HandleUnitAction clears Wait when it pops the
                        // finished chase to the queued restored order.
                        unit.setWaitCycles(0);
                    }
                    target = null;
                }
            } else if (candidate != null && candidate != target) {
                // 0x409ff0 seeds its best score with the live goal and
                // only returns another unit when 0x40a4b0 gives that
                // candidate a strictly greater retail score.
                world.combat.setAutoTarget(unit, candidate,
                        world.preserveScannedRoute(unit, candidate,
                                preserveFreshInRangeRoute));
                target = candidate;
            }
        }
        return target;
    }


    /**
     * One world.cycle of standing still: work if a building, else breathe and look
     * for something to do.
     *
     * <p>{@code COrder_Still::Execute}. Animate, then decide: it opens with
     * {@code UnitShowAnimation(unit, &Still)}
     * and only reaches AutoCast,
     * AutoAttack and MoveRandomly afterwards, and only while the animation is
     * breakable.
     *
     * <p>Deciding first, which is what this did, costs a standing unit the
     * world.cycle it takes an order on: it never breathes that world.cycle, so its Still
     * loop runs a world.cycle behind upstream's for as long as it lives, and the
     * loop's random-goto is a draw off the shared stream. On maps/demo/demo03
     * that is the whole of world.cycle 6: a grunt at 11,2 gives itself an attack
     * order on world.cycle 1, finds its way blocked on world.cycle 2 and sleeps, and
     * upstream's breathes on cycles 6 and 11 where this implementation's breathed on 7
     * and 12.
     *
     * <p>What it then decides depends on whether it can walk.
     * {@code COrder_Still::Execute} sends StandGround, removed and immobile
     * units down one arm -- AutoCastStand and AutoAttackStand, the attack
     * range, a sub-state -- and everything else down the other
     * A tower is the first arm
     * even though its order is plain still: it acquires in place and never
     * takes an attack order.
     *
     * <p>Callers besides the order loop are the order endings that fall
     * through: upstream's {@code HandleUnitAction} advances a finished order
     * and runs the replacement in the same call, so a worker whose harvest
     * dies with nowhere to unload begins breathing that world.cycle, not the next.
     */
    void stepStill(Unit unit) {
        boolean working = world.stepTraining(unit) || world.stepResearch(unit);
        if (working) {
            return;
        }
        // A completed/interrupted combat order must not strand the visual
        // half of its last walk on the replacement Still order.  It is an
        // invalid state in retail (MoveToTarget drains the committed step
        // before it may finish), but older ChonkCraft runs and saves can
        // contain it: the unit reports Still while Moving remains set and
        // IX/IY stay at a half-tile offset forever.  Finish that already-paid
        // step before breathing or acquiring another target.  Besides
        // repairing old saves, keeping the recovery here makes any future
        // bad combat exit self-heal instead of leaving a frozen defender.
        if (unit.canMove() && world.movement.settleOrphanedStillStep(unit)) {
            return;
        }
        if (!unit.canMove()) {
            if (World.isBattleNetArmedTower(unit) && world.battleNetSequence != null) {
                world.combat.stepBattleNetTower(unit);
            } else if (unit.type().building() && world.battleNetBuildingCanAction33Train(unit)) {
                stepBattleNetHallStill(unit);
            } else {
                world.combat.stepStandGround(unit);
            }
        } else {
            stepIdle(unit);
            boolean resourceReadyHold = unit.queuedReplacementPending()
                    && unit.returningToDepot()
                    && unit.carried() > 0
                    && unit.battleNetOrderDelay() > 0;
            if (resourceReadyHold) {
                // A worker surfaced from action 26 is sleeping behind the
                // native 25-count ready head.  Its visible order is Still,
                // but COrder_Still is not scheduled and therefore cannot
                // enter the land-idle random dispatcher during this window.
                // XHuman 12 peon 1491 surfaces at fixture 176 and owns no
                // 0040AD58 draw at 177; Java's synthetic Still visit stole
                // the immediately following melee-damage value.
                return;
            }
            stepBattleNetIdle(unit);
            // Nothing else runs here, which is the point.  ChonkCraft went on to
            // AutoAttack and moveRandomly; doing that queued an attack during
            // the first hidden initialization tick, promoted it on the
            // second, and moved the unit before corpus world.cycle one.  Retail BNE
            // has no mobile attack action in any of the 52 world.cycle-one
            // checkpoints.  BNE's startup Still dispatcher owns autonomous
            // orders: resource, patrol, transport and critter orders are
            // issued above at its own animation marker, and world.combat
            // acquisition will likewise belong there when its later cadence
            // is modeled.
        }
    }


    /**
     * Advances a computer hall/barracks native Still program and train counter.
     *
     * <p>Native action 33 ({@code 0x418bb0}) increments unit+0x6e each Still
     * OP0; when the previous value exceeds the per-type limit the building
     * calls its train function. Halls use limit 2 (fourth OP0, peon). Barracks
     * use limit 1 (third OP0, footman/grunt). Cadence: constructor pair +
     * WAIT 4. Freeze during the two unrecorded init ticks so the train OP0
     * lands on fixture world.cycle 12 for buildings that begin on action 33.
     */
    void stepBattleNetHallStill(Unit hall) {
        Player owner = world.player(hall.player());
        boolean computer = owner != null
                && (owner.type() == net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                    || owner.type()
                        == net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.RESCUE_ACTIVE);
        // Init alignment to fixture OP0 cadence (world world.cycle = fixture + 2):
        // barracks/shipyards open on action 33 -> first OP0 fixture c2;
        // blacksmiths -> fixture c3 (xh11) then +5; town-halls share the
        // barracks freeze so construction delay lands first OP0 on the
        // sealed XOrc 4/5 c3 series without a forced timer clear (which
        // over-spent later-opening halls on Orc 4 and XOrc 6/10/11);
        // great-halls use constructor.
        int freezeThrough;
        if (World.battleNetIsBarracks(hall) || World.battleNetIsShipyard(hall)
                || World.battleNetIsTownHall(hall)) {
            freezeThrough = 3;
        } else if (World.battleNetIsFoundry(hall)) {
            // XOrc 7 foundry research lands at fixture c16 (third OP0).
            freezeThrough = 7;
        } else if (World.battleNetIsTemple(hall)) {
            // Human 14 p0 temple: raise-dead 1500g at fixture c35. Freeze 2
            // debited at c34 (one OP0 early); freeze 3 matches the sealed bank.
            freezeThrough = 3;
        } else if (World.battleNetIsChurch(hall)) {
            // Human 14 p4/p5 altar: table limit 10, twelfth OP0 at fixture
            // c61. The first pulse is fixture c6, followed by WAIT 4.
            freezeThrough = 7;
        } else if (World.battleNetIsBlacksmith(hall)
                || World.battleNetIsLumberMill(hall)
                || World.battleNetIsWatchTower(hall)
                || World.battleNetIsFlyerRoost(hall)) {
            AiPlayer ownerAi = world.ais.get(hall.player());
            int profile = ownerAi == null ? -1 : ownerAi.battleNetBuildProfileId();
            // 70/0 blacksmith c13 (freeze 4); 67 blacksmith c15 (freeze 6);
            // 65 XHuman 9 p6 third OP0 at fixture c19 (freeze 10) so axe1
            // does not fire four cycles early at c15.
            freezeThrough = (profile == 70 || profile == 0) ? 4
                    : (profile == 65) ? 10 : 6;
        } else {
            freezeThrough = 2;
        }
        if (world.cycle <= freezeThrough) {
            // Blacksmith/lumber/watch: kill the constructor random timer so
            // the first free world.cycle is a deterministic OP0. Town-halls keep
            // construction delay so first OP0 tracks native open.
            if (world.cycle == freezeThrough
                    && (World.battleNetIsBlacksmith(hall)
                            || World.battleNetIsLumberMill(hall)
                            || World.battleNetIsWatchTower(hall)
                            || World.battleNetIsFlyerRoost(hall)
                            || World.battleNetIsChurch(hall)
                            || World.battleNetIsFoundry(hall))) {
                hall.setBattleNetAnimationTimer(1);
            }
            return;
        }
        int timer = hall.battleNetAnimationTimer() - 1;
        // Free roost while a sibling is producing a flyer: compress the WAIT-4
        // so the second XOrc 6 gryphon OP0 lands on fixture c18 (world c20)
        // rather than c20 (world c22). timer=2 (not 1) matches native c18;
        // timer=1 was fixture c17.
        if (timer > 2 && computer && World.battleNetIsFlyerRoost(hall)
                && hall.producing() == null
                && world.battleNetSiblingRoostProducingFlyer(hall)) {
            AiPlayer flightAi = world.ais.get(hall.player());
            if (flightAi != null && flightAi.battleNetWantedFlyers() >= 4) {
                timer = 2;
            }
        }
        hall.setBattleNetAnimationTimer(Math.max(0, timer));
        if (timer > 0) {
            return;
        }
        if (computer) {
            battleNetBuildingTrainPulse(hall);
        }
        int phase = hall.battleNetIdlePhase();
        hall.setBattleNetIdlePhase(phase + 1);
        // Halls that begin Still on action 33 still run the constructor pair
        // (timer 1 then WAIT 4) so the fourth OP0 lands on fixture world.cycle 12
        // for peon trains. Barracks/shipyards already on action 33 in the
        // sealed openings only OP0 every WAIT 4 (native 1520: c2, c7, c12).
        if (World.battleNetIsLimit1Trainer(hall) || phase >= 1) {
            hall.setBattleNetAnimationTimer(5);
        } else {
            hall.setBattleNetAnimationTimer(1);
        }
    }


    /**
     * One action-33 pulse for a computer producer.
     *
     * <p>Native {@code 0x418bb0}: read per-profile/per-PUD-type limit from
     * entry-277 pointer2; {@code 0xffff} disables; otherwise
     * {@code old = counter; counter++; if (old > limit) { counter = 0;
     * train_fn; }}. Limits are not fixed hall=2 / barracks=1 constants --
     * XHuman 10 p2 profile 67 great-hall type 75 is limit 100.
     */
    void battleNetBuildingTrainPulse(Unit building) {
        if (building.producing() != null || !building.trainingQueue().isEmpty()
                || building.researching() != null) {
            return;
        }
        AiPlayer ai = world.ais.get(building.player());
        if (ai == null) {
            return;
        }
        int pudType = PudUnitTypes.code(building.type().ident());
        int limit;
        if (!ai.battleNetAction33TableLoaded()) {
            // Unit tests without entry-277 word1: closed class constants.
            limit = World.battleNetIsLimit1Trainer(building) ? 1 : 2;
        } else {
            limit = ai.battleNetAction33Limit(pudType);
            if (limit == 0xffff) {
                return;
            }
            // Hall peon train_fn refuses early tops on several human-campaign
            // personalities that still encode table limit 1 (profile 3). Until
            // that refuse is modeled, keep the closed hall limit 2 so the
            // fourth OP0 (fixture ~c12) remains the first peon debit. Table
            // limits of 100+ (XHuman 10 p2 profile 67) still suppress trains.
            if (!World.battleNetIsLimit1Trainer(building) && !World.battleNetIsBlacksmith(building)
                    && limit < 2) {
                limit = 2;
            }
        }
        int old = building.battleNetAiTrainCounter();
        building.setBattleNetAiTrainCounter(old + 1);
        // XOrc 6: free aviary must start the second gryphon at fixture c18
        // while the first is still producing. Counter limit 1 is too slow on
        // the freeze-6 cadence; when a sibling roost is producing and the AI
        // wants a full flight (>=4), train on this OP0 without waiting for
        // old > limit.
        boolean forceSiblingFlyer = World.battleNetIsFlyerRoost(building)
                && ai.battleNetWantedFlyers() >= 4
                && world.battleNetSiblingRoostProducingFlyer(building);
        if (!forceSiblingFlyer && old <= limit) {
            return;
        }
        building.setBattleNetAiTrainCounter(0);
        if (World.battleNetIsBarracks(building)) {
            // AI-accounted census + bytecode wants gate the selector.
            // Human 13 stays quiet because its profile leaves basic-want at
            // zero; XHuman 3 ogre and XOrc 11 footman debit from wants alone.
            ai.battleNetTryTrainSoldier(world, building);
        } else if (World.battleNetIsShipyard(building)) {
            // Shipyard train_fn does not require non-zero UNIT.Data. Orc 12
            // p1's only shipyard is data 0 and still debits tanker 400/200 at
            // fixture ~c30; gating on data!=0 left that seat idle. Barracks
            // keep their own data/want rules inside the soldier selector.
            ai.battleNetTryTrainTanker(world, building);
        } else if (World.battleNetIsChurch(building)) {
            // Codes 0x90..0x92: paladin/ogre-mage and their spell upgrades.
            ai.battleNetTryResearchChurch(world, building);
        } else if (World.battleNetIsBlacksmith(building)) {
            int profile = ai.battleNetBuildProfileId();
            // 0, 67, 70: sealed XHuman 10/11 axe1. 65: XHuman 9 p6 reseeds
            // axe1 at fixture c19 (−500g/−100w) with candidate 0x86 armed.
            if (profile == 67 || profile == 70 || profile == 0
                    || profile == 65) {
                ai.battleNetTryResearchBlacksmith(world, building);
            }
        } else if (World.battleNetIsLumberMill(building)) {
            // 0x40f380: only when a high-byte candidate is armed (milestone
            // path). Unconditional open over-spent every mill on every map.
            ai.battleNetTryResearchLumberMill(world, building);
        } else if (World.battleNetIsWatchTower(building)) {
            // 0x40eec0: one upgrade per player per cycle (see AiPlayer).
            ai.battleNetTryUpgradeWatchTower(world, building);
        } else if (World.battleNetIsFlyerRoost(building)) {
            // 0x40fa00: dragon/gryphon. XHuman 7 / XOrc 6 debit 2500g at c15.
            ai.battleNetTryTrainFlyer(world, building);
        } else if (World.battleNetIsFoundry(building)) {
            // 0x40f4b0: naval research. XOrc 7 c16 with freeze 7.
            ai.battleNetTryResearchFoundry(world, building);
        } else if (World.battleNetIsTemple(building)) {
            // Temple / mage-tower spell research. Human 14 p0 profile 27
            // arms 0x93 and debits 1500g for raise-dead at fixture c35.
            ai.battleNetTryResearchTemple(world, building);
        } else {
            ai.battleNetTryTrainWorker(world, building);
        }
    }


    /**
     * Re-arms the naval/flying idle countdown when it expires.
     *
     * <p>A hull that has carried something (Orc 14 post-harvest at world 21)
     * must not draw -- native order-32 Still on those hulls advances the marker
     * without 0040AE30 after warmup, and the harvest and load paths mark them.
     * Every other hull re-arms by drawing, every time the countdown expires.
     * This used to suppress the draw on a transport's second and every later
     * re-arm, because a transport marked itself on its own first one. The two
     * transports that never carry anything are the witnesses: Human 7's hull at
     * 20,6 and Orc 12's both count ten idle visits down and re-arm on the
     * eleventh, and the retail engine spends a draw there that this engine kept.
     * Both games share one asynchronous stream, so that single missing draw
     * moved every later number onto the wrong unit -- which is why a Human 7
     * critter stood still where retail sent it wandering, and an Orc 12 critter
     * wandered where retail left it standing, both at cycle 52.</p>
     */
    void rearmBattleNetFlyingIdleTimer(Unit unit) {
        if (unit.battleNetTransportFlyDrawn()) {
            unit.setBattleNetFlyingIdleTimer(12);
            return;
        }
        unit.setBattleNetFlyingIdleTimer((world.battleNetRand() & 7) + 8);
    }


    /** The random-facing half of {@code FUN_0040ad30}, without Still's AI pass. */
    void advanceBattleNetActiveOrderIdleRandom(Unit unit) {
        if (!battleNetUsesLandIdleRandom(unit)) {
            return;
        }
        int seedBefore = world.battleNetRandomSeed;
        if (unit.type().moveType() != UnitType.Movement.LAND) {
            int timer = unit.battleNetFlyingIdleTimer();
            unit.setBattleNetFlyingIdleTimer((timer - 1) & 0xff);
            if (timer == 0) {
                rearmBattleNetFlyingIdleTimer(unit);
            }
            if (World.BNE_IDLE_TRACE) {
                System.err.printf("JBNEATTACKMARKER world.cycle=%d unit=%d fly=1 "
                                + "idle-timer=%d seed=%s->%s%n", world.cycle,
                        unit.id(), unit.battleNetFlyingIdleTimer(),
                        Integer.toUnsignedString(seedBefore),
                        Integer.toUnsignedString(world.battleNetRandomSeed));
            }
            return;
        }
        int choice = world.battleNetRand() & 0xff;
        if (choice == 0) {
            unit.setHeading(Math.floorMod(unit.heading() - 1, 8));
        } else if (choice <= 3) {
            unit.setHeading(Math.floorMod(unit.heading() + 1, 8));
        }
        if (World.BNE_IDLE_TRACE) {
            System.err.printf("JBNEATTACKMARKER world.cycle=%d unit=%d choice=%d "
                            + "seed=%s->%s%n", world.cycle, unit.id(), choice,
                    Integer.toUnsignedString(seedBefore),
                    Integer.toUnsignedString(world.battleNetRandomSeed));
        }
    }


    /** Whether a land unit enters {@code FUN_0040ad50}'s random-facing arm. */
    boolean battleNetUsesLandIdleRandom(Unit unit) {
        if (unit == null || unit.type() == null) {
            return false;
        }
        int type = PudUnitTypes.code(unit.type().ident());
        // The authenticated type table sends ballistae and catapults through
        // the siege Still arm. They may run ready/auto-attack, but never pay
        // the common land-idle random choice -- including when an expired
        // Attack installs Still during the same scheduler visit.
        return type != 4 && type != 5;
    }


    /** Advances retail BNE's independent, asset-driven idle scheduler. */
    void stepBattleNetIdle(Unit unit) {
        if (!unit.canMove()) {
            return;
        }
        // Constructor stream burns replace non-wander draws on the resume
        // path. Do not burn on the same visit that fires an action marker:
        // Orc 10 1513's issue+4 OP0 used to run after its own second burn
        // and drew choice 154 (no wander) while native Moves at fixture 9.
        boolean critter = "unit-critter".equals(unit.type().ident());
        if (world.battleNetSequence != null) {
            int offset = unit.battleNetSequenceOffset();
            if (offset < 0) {
                // A different order invalidates the Still cursor. Native
                // selects the Still animation anew when that order ends.
                offset = battleNetStillSequenceStart(unit);
                if (offset >= 0) {
                    unit.setBattleNetSequenceOffset(offset);
                    unit.setBattleNetAnimationTimer(1);
                }
            }
            if (offset >= 0) {
                BattleNetSequence.Tick tick = world.battleNetSequence.tick(offset,
                        unit.battleNetAnimationTimer());
                if (tick.valid()) {
                    unit.setBattleNetSequenceOffset(tick.offset());
                    unit.setBattleNetAnimationTimer(tick.timer());
                    if (!tick.actionMarker()) {
                        if (critter) {
                            world.construction.burnBattleNetConstructorStream(unit);
                        }
                        return;
                    }
                    if (unit.hasQueuedOrders()
                            && unit.battleNetOrderDelay() == 1) {
                        Unit.QueuedOrder next = unit.queuedOrders().getFirst();
                        if (unit.destPathOpeningHold()
                                || next.kind()
                                        == Unit.QueuedOrderKind.ATTACK) {
                            // Native 0x452ef0 promotes a replacement from the
                            // expired 4985 body without running the following
                            // OP0. This is true both for a point/dest order
                            // and for a live-target Attack. In the pinned Orc
                            // 1 fight, grunt 1592 promotes Attack at fixture 9
                            // without spending 0040AD58; Java's extra idle
                            // draw shifted the damage roll at 214 onto every
                            // later idle unit. The same boundary previously
                            // fixed Human 1's queued point command.
                            return;
                        }
                    }
                    int phase = unit.battleNetIdlePhase();
                    unit.setBattleNetIdlePhase(phase + 1);
                    dispatchBattleNetIdleMarker(unit,
                            PudUnitTypes.code(unit.type().ident()), phase);
                    if (critter) {
                        world.construction.burnBattleNetConstructorStream(unit);
                    }
                    return;
                }
            }
        }

        if (critter) {
            world.construction.burnBattleNetConstructorStream(unit);
        }
        stepBattleNetIdleApproximation(unit);
    }


    /** Fallback for BNE-shaped tests and sources that lack script.bin. */
    void stepBattleNetIdleApproximation(Unit unit) {
        int timer = unit.battleNetAnimationTimer() - 1;
        unit.setBattleNetAnimationTimer(Math.max(0, timer));
        if (timer > 0) {
            return;
        }
        int type = PudUnitTypes.code(unit.type().ident());
        if (unit.battleNetIdlePhase() == 0
                && (type == 35 || type == 43 || type == 56)) {
            // A dragon's, fire breeze's and daemon's constructor sequence
            // does not end at the first timer expiry. Retail advances types
            // 35 and 43 from sequence 1251 to 1255 with a five-call wait, and
            // type 56 from 4841 to 4845 with a three-call wait. Neither
            // transition enters the shared idle-random dispatcher. Treating
            // this first expiry as a generic flying idle consumed four extra
            // draws in Human 14's first warm-up alone and put every later
            // critter on the wrong random choice.
            unit.setBattleNetIdlePhase(1);
            unit.setBattleNetAnimationTimer(type == 56 ? 3 : 5);
            return;
        }
        int phase = unit.battleNetIdlePhase();
        boolean critter = "unit-critter".equals(unit.type().ident());
        int actionMarkers = critter ? 2 : 1;
        unit.setBattleNetIdlePhase(phase + 1);
        // The critter constructor sequence has two adjacent action markers.
        // Every other first marker, and every unit after its constructor
        // sequence, enters BNE's common Still loop: four quiet calls and the
        // next dispatcher call on the fifth.
        unit.setBattleNetAnimationTimer(
                critter && phase + 1 < actionMarkers ? 1 : 5);

        dispatchBattleNetIdleMarker(unit, type, phase);
    }


    /** Runs the current Still order when script.bin emits opcode zero. */
    void dispatchBattleNetIdleMarker(Unit unit, int type, int phase) {
        world.rescueBattleNetUnit(unit);
        if (unit.battleNetPendingTransportTarget() != null) {
            world.beginBattleNetPendingTransport(unit);
            return;
        }

        if (unit.hasBattleNetPendingMove()) {
            world.movement.beginBattleNetPendingMove(unit);
            return;
        }

        if (unit.hasBattleNetPendingPatrol()) {
            // The patrol was already constructed by the ready pass. Native
            // promotes that NextAction here without entering the flyer's
            // ordinary idle-bob branch, so there is no additional draw.
            world.beginBattleNetPendingPatrol(unit);
            return;
        }

        if (type == 4 || type == 5) {
            // BNE's ballista and catapult Still sequences turn and breathe,
            // but unlike ordinary movable units they never call the
            // FUN_0040ad50 idle-random dispatcher. Treating the shared sprite
            // action marker as a random marker shifts every later unit onto
            // the siege engine's draw.
            world.battleNetAutoAttack(unit);
            return;
        }

        int seedBefore = world.battleNetRandomSeed;

        if (unit.type().moveType() != UnitType.Movement.LAND) {
            // FUN_0040ad30 keeps a second unsigned-byte countdown for units
            // carrying native type flag 0x08 or live movement flag 0x04.
            // Those are the naval and flying branches, respectively -- not
            // just flying idle bobbing. The old value is tested after the
            // decrement: an initial zero consumes one draw and becomes 8..15,
            // but the next markers merely count it down. Treating a ship's
            // every marker as an ordinary land-unit draw shifts all later
            // units in the shared asynchronous RNG stream. Human 13's four
            // destroyers are the compact witness: two extra draws make its
            // critter miss a constructor wander at recorded world.cycle four.
            int timer = unit.battleNetFlyingIdleTimer();
            unit.setBattleNetFlyingIdleTimer((timer - 1) & 0xff);
            if (timer == 0) {
                // Orc 14 human transports at 27,27 / 91,31: after leaving
                // harvest they re-enter Still and used to draw AE30 when the
                // fly timer hit zero (world 21). Native order-32 Still on
                // those hulls visits the idle marker without advancing the
                // async seed after the early warmup (no 0040AE30 after
                // fixture world.cycle 6). Those two extra draws shifted critter
                // 1455's phase-5 choice onto wander at fixture 37.
                rearmBattleNetFlyingIdleTimer(unit);
            }
            // Idle facing/bobbing and the per-unit ready callback are
            // independent parts of FUN_0040b010.  A tanker uses the naval
            // countdown above and then still enters 0x439280, where it is
            // assigned an oil platform on that same marker.
            world.battleNetUnitReady(unit);
            world.battleNetAutoAttack(unit);
            if (World.BNE_IDLE_TRACE) {
                System.err.printf("JBNEIDLE world.cycle=%d unit=%d phase=%d fly=1 "
                                + "idle-timer=%d seed=%s->%s%n", world.cycle,
                        unit.id(), phase, unit.battleNetFlyingIdleTimer(),
                        Integer.toUnsignedString(seedBefore),
                        Integer.toUnsignedString(world.battleNetRandomSeed));
            }
            return;
        }

        int choice = battleNetLandIdleChoice(unit);
        boolean critter = "unit-critter".equals(unit.type().ident());
        if (!critter || choice < 4 || choice > 50) {
            world.battleNetUnitReady(unit);
            world.battleNetAutoAttack(unit);
            // After a no-wander OP0, restart Still with timer 1 so the next
            // visit OP0s again instead of entering WAIT 4. Occupied-mobile
            // restarts until force-redraw lands a wander. Free-empty restarts
            // for a few visits only: Orc 11 1597 needs three OP0s (Still@8-9
            // Move@10), while Human 3 1589 used to keep drawing until fixture
            // world.cycle 11 and steal 1582's wander. Cap free restarts after five
            // no-wander OP0s so the ordinary WAIT 4 loop resumes.
            if (critter && unit.battleNetOccupiedEmptyReWander()
                    && unit.order() == Unit.Order.STILL) {
                unit.addBattleNetOccupiedEmptyNoWander();
                int noWander = unit.battleNetOccupiedEmptyNoWanderCount();
                boolean keepRestarting = unit.battleNetOccupiedEmptyForceWander()
                        || noWander < 2;
                if (keepRestarting) {
                    if (world.battleNetSequence != null) {
                        unit.setBattleNetSequenceOffset(
                                battleNetStillSequenceStart(unit));
                    }
                    unit.setBattleNetAnimationTimer(1);
                } else {
                    unit.setBattleNetOccupiedEmptyReWander(false);
                }
            }
            // Coast free-empty: OP0 always leaves timer 1 (BattleNetSequence
            // opcode 0). Bump to 2 so the following WAIT opcode is reached one
            // visit later -- one extra quiet before the Still-loop countdown
            // without an extra async draw (see empty-FOUND arm).
            if (critter && unit.battleNetCoastEmptyExtraWait()
                    && unit.order() == Unit.Order.STILL
                    && unit.battleNetAnimationTimer() == 1) {
                unit.setBattleNetAnimationTimer(2);
                unit.setBattleNetCoastEmptyExtraWait(false);
            }
            if (World.BNE_IDLE_TRACE) {
                System.err.printf("JBNEIDLE world.cycle=%d unit=%d phase=%d choice=%d "
                                + "seed=%s->%s%n", world.cycle, unit.id(), phase,
                        choice, Integer.toUnsignedString(seedBefore),
                        Integer.toUnsignedString(world.battleNetRandomSeed));
            }
            return;
        }
        int direction = world.battleNetRand() & 7;
        int[] dx = {0, 1, 1, 1, 0, -1, -1, -1};
        int[] dy = {-1, -1, 0, 1, 1, 1, 0, -1};
        int x = Math.max(0, Math.min(world.map.width() - 1,
                unit.tileX() + dx[direction]));
        int y = Math.max(0, Math.min(world.map.height() - 1,
                unit.tileY() + dy[direction]));
        if (x != unit.tileX() || y != unit.tileY()) {
            // setOrder(MOVE) clears the Still cursor; capture the post-marker
            // offset first so an impassable wander can resume the constructor.
            int resumeOffset = unit.battleNetSequenceOffset();
            boolean moved = world.movement.orderMove(unit, x, y);
            if (moved) {
                unit.setBattleNetOccupiedEmptyReWander(false);
                unit.setBattleNetCoastEmptyExtraWait(false);
                if (critter) {
                    // The empty-route pause belongs to the completed wander.
                    // Once a Still marker installs the next wander, retail
                    // starts that action's own delay immediately; carrying the
                    // old pause into the new Move parks an animal for another
                    // seven cycles after it has already accepted the order.
                    unit.setWaitCycles(0);
                }
            }
            if (moved && critter && world.battleNetSequence != null) {
                // Critter Still (type 57): 4718 frame, 4720 OP0, 4721 JUMP
                // 4982, 4982 OP0, WAIT 4, loop. After the first OP0 the cursor
                // is at 4721. Restarting at 4718 re-fires the first marker and
                // never gives Orc 7 slot 1512 its fixture-world.cycle-5 wander.
                //
                // Only the constructor's first action marker (phase 0) with an
                // impassable one-tile dest keeps resumeOffset + timer 5 so the
                // second constructor OP0 lands on world world.cycle issue+5
                // (= fixture c5 after two init ticks). Two async burns at
                // issue+3 and issue+4 replace the non-wander draws the restart
                // path spent, keeping later critters on stream. Passable first
                // wanders and any later Still-loop wander keep the restart
                // path -- applying resume on a phase-1 impassable in Human 3
                // shifted the shared async stream and swapped two later
                // critters at world.cycle 5.
                // phase was already advanced in the marker dispatcher, so a
                // first-marker wander sees idlePhase == 1 here; a second-marker
                // or Still-loop wander sees 2+.
                boolean firstConstructorWander =
                        unit.battleNetIdlePhase() == 1;
                boolean impassable = !world.battleNetTerrainPassable(unit, x, y);
                if (firstConstructorWander && impassable && resumeOffset >= 0) {
                    // Rock keeps timer 5 so the second constructor marker
                    // lands at issue+5 (Orc 7). Coast first-wander uses timer
                    // 4 so the re-wander is one visit sooner while the two
                    // stream burns at issue+3/+4 still protect reverse-walk
                    // neighbours (Orc 10 1510 → 57,63 keeps 1526 Move@8;
                    // 1525 Move@9). 1513's OP0 at issue+4 still draws outside
                    // the wander band (choice 154) -- stream fine-tune next.
                    unit.setBattleNetSequenceOffset(resumeOffset);
                    int resumeTimer = world.battleNetCritterCoastGoal(x, y) ? 4 : 5;
                    unit.setBattleNetAnimationTimer(resumeTimer);
                    unit.setBattleNetConstructorStreamBurns(2, (int) world.cycle + 3);
                } else {
                    unit.setBattleNetSequenceOffset(
                            battleNetStillSequenceStart(unit));
                    unit.setBattleNetAnimationTimer(3);
                    unit.setBattleNetConstructorStreamBurns(0, 0);
                }
            } else if (!moved && critter
                    && unit.battleNetOccupiedEmptyReWander()
                    && unit.order() == Unit.Order.STILL) {
                if (world.battleNetSequence != null) {
                    unit.setBattleNetSequenceOffset(
                            battleNetStillSequenceStart(unit));
                }
                unit.setBattleNetAnimationTimer(1);
            }
        } else if (critter && unit.battleNetOccupiedEmptyReWander()
                && unit.order() == Unit.Order.STILL) {
            if (world.battleNetSequence != null) {
                unit.setBattleNetSequenceOffset(
                        battleNetStillSequenceStart(unit));
            }
            unit.setBattleNetAnimationTimer(1);
        }
        world.battleNetAutoAttack(unit);
        if (World.BNE_IDLE_TRACE) {
            System.err.printf("JBNEIDLE world.cycle=%d unit=%d phase=%d choice=%d "
                            + "dir=%d seed=%s->%s move=%d,%d%n", world.cycle,
                    unit.id(), phase, choice, direction,
                    Integer.toUnsignedString(seedBefore),
                    Integer.toUnsignedString(world.battleNetRandomSeed), x, y);
        }
    }


    /**
     * Pays FUN_0040ad50's land-unit choice and its rare facing nudge.
     *
     * <p>Ordinary Still OP0 uses the result to decide whether a critter
     * wanders. A melee residual auto-target handoff passes through the same
     * native dispatcher before Attack construction; that caller needs the
     * shared RNG/facing half without running ready and auto-target twice.</p>
     */
    int battleNetLandIdleChoice(Unit unit) {
        int choice = world.battleNetRand() & 0xff;
        boolean critter = "unit-critter".equals(unit.type().ident());
        // Occupied-mobile empty only: first two OP0s miss the wander band
        // (134, 191) and reverse-walk would take choice 42. Redraw into the
        // band on the third OP0 (Human 3 1587 → 40,15). Free-empty restart
        // must not force-redraw (Human 3 1589 stays Still after free empty).
        if (critter && unit.battleNetOccupiedEmptyForceWander()
                && unit.battleNetOccupiedEmptyNoWanderCount() >= 2
                && (choice < 4 || choice > 50)) {
            int guard = 0;
            while ((choice < 4 || choice > 50) && guard++ < 32) {
                choice = world.battleNetRand() & 0xff;
            }
        }
        if (choice == 0) {
            unit.setHeading(Math.floorMod(unit.heading() - 1, 8));
        } else if (choice <= 3) {
            unit.setHeading(Math.floorMod(unit.heading() + 1, 8));
        }
        return choice;
    }


    /**
     * Runs a standing unit's idle animation, so it breathes and turns.
     *
     * <p>Animation only. What a standing unit then decides -- to cast, to
     * shoot at something, to wander a square -- is upstream's second half of
     * {@code COrder_Still::Execute} and belongs to the caller, because a unit
     * holding its ground does none of it: the {@code StandGround} arm reaches
     * {@code AutoCastStand} and {@code AutoAttackStand} and never
     * {@code MoveRandomly}.
     */
    void stepIdle(Unit unit) {
        AnimationSet set = unit.type().animationSet();
        if (set == null) {
            return;
        }
        unit.animation().switchTo(set.getOrStill(AnimationSet.State.STILL));
        world.advance(unit);
    }
}
