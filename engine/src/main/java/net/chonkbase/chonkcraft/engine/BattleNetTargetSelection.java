package net.chonkbase.chonkcraft.engine;

import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import net.chonkbase.chonkcraft.data.map.PudUnitTypes;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.upgrade.UpgradeState;

/**
 * What a unit is allowed to shoot at, and which of those it picks.
 *
 * <p>Implements {@code AutoSelectTarget}, {@code TargetPriorityCalculate}, and
 * retail BNE's own selector at {@code 0x409ff0} with the scorer at
 * {@code 0x40a4b0} behind it. Every method here answers a question and
 * changes nothing: whether a unit may be shot at, what it is worth, and which
 * candidate in a scan wins. Aiming a unit at the answer is still
 * {@link World}'s, because that writes an order.
 */
final class BattleNetTargetSelection {

    private final World world;

    BattleNetTargetSelection(World world) {
        this.world = world;
    }


    /** Whether a unit's weapon can reach the kind of thing a target is. */
    static boolean canTarget(Unit unit, Unit target) {
        UnitType type = unit.type();
        if (target.type().airUnit()) {
            return type.canTargetAir();
        }
        if (target.type().seaUnit()) {
            return type.canTargetSea();
        }
        return type.canTargetLand();
    }


    /**
     * Whether a player can see a unit, their own eyes or an ally's.
     *
     * <p>Implements {@code CUnit::IsVisible}
     * and the per-tile count {@code UnitCountSeen} keeps for it. Upstream is two lines: the viewer's own
     * count over the unit's footprint, then the same count for everybody the
     * viewer has vision from. There is no third clause.
     *
     * <p>This used to open with {@code if (isAllied(player, unit.player()))
     * return true}, and an alliance is not shared vision. {@code CPlayer::Init}
     * makes a person allied with every rescue-passive and rescue-active slot on
     * the map and never calls {@code ShareVisionWith} -- only the team game
     * types do that -- so on a campaign map the ally's units are exactly as
     * hidden as anyone else's. What the player saw instead: on the fifth human
     * mission the red humans' peasants were drawn wherever they walked, under
     * the half-alpha veil of ground the player had scouted and left, mining and
     * carrying and turning to face the trees, while the orcs a hundred squares
     * away behaved and stayed hidden. Seventeen of the fifty-two missions have
     * a rescuable slot; across them 178 units were drawn through fog, 99 of
     * them mobile, and no COMPUTER or NEUTRAL unit ever was -- the alliance
     * clause was the only route.
     *
     * <p>Nothing carries the owner either, for the same reason upstream does
     * not: a unit stands inside its own sight, so its owner's count is already
     * non-zero. Measured over all fifty-two missions at ten seconds in, all
     * 1,119 units the person owned were lit by that person's own fog, so
     * dropping the shortcut moves nothing the shipped campaigns contain. The
     * bound is a type with a sight range of zero -- there are sixteen, all of
     * them corpses, craters, start locations and the circle of power -- which
     * would now need one of its owner's other units nearby to be drawn, which
     * is what upstream does with it too.
     *
     * <p>Permanent cloak replaces ordinary sight with detector coverage, but
     * not for the owner: upstream guards that branch with
     * {@code unit.Player != &Players[p]}, which is what keeps a submarine
     * visible to the player driving it.
     */
    /**
     * Whether a unit may be chosen as a target by this player.
     *
     * <p>{@code CUnit::IsVisibleAsGoal} ({@code include/unit.h:228-246}), and
     * its middle clause is the point: {@code player.Type == PlayerComputer &&
     * !PERMANENTCLOAK} answers yes before the fog is ever consulted. The
     * computer is allowed to cheat -- upstream wrote it into the visibility
     * test itself -- and this implementation's acquisition asked the honest
     * {@code isVisibleTo} instead, so on campaigns/human/level13h player 4's
     * destroyer at 62,78 never saw the human destroyer fifteen squares away
     * in the dark that upstream's opens fire on from the second cycle. A
     * rescue-active player is not a computer in this sense and keeps honest
     * eyes, which the wise-man's own hunt happens to prove.
     */
    boolean isVisibleAsGoal(int player, Unit unit) {
        if (unit == null || !unit.isAlive() || !unit.isOnMap()) {
            return false;
        }
        if (unit.hasBuff(Unit.Buff.INVISIBLE)) {
            return false;
        }
        if (unit.type().revealer()) {
            return false;
        }
        Player owner = world.player(player);
        if (owner != null
                && owner.type() == net.chonkbase.chonkcraft.data.map.PudMap.PlayerType.COMPUTER
                && !unit.type().permanentCloak()) {
            return true;
        }
        if (world.isVisibleTo(player, unit)) {
            return true;
        }
        // The final IsVisibleAsGoal arm: a building marked
        // VisibleUnderFog remains a real action goal after it leaves sight,
        // provided this player has seen this particular unit and has not
        // subsequently seen it destroyed. Dead units are removed outright
        // in this implementation, so the alive/on-map guard above is the Destroyed bit.
        return unit.type().visibleUnderFog() && unit.wasSeenBy(player);
    }


    /**
     * {@code AiEnemyUnitsInDistance}: any enemy cache entry in the box.
     *
     * <p>The upstream predicate is only {@code unit->IsEnemy(player)}.
     * {@code Select} supplies entries from {@code CMapField::UnitCache}; it
     * does not add an alive/action filter. Consequently the destroyed-place
     * body which {@link #findEnemyByFlood} can choose also prevents the AI
     * from calling its immediate neighbourhood a quiet rally point.
     */
    boolean enemyWithin(int player, int x, int y, int range) {
        for (int by = Math.max(0, y - range); by <= Math.min(world.map.height() - 1, y + range); by++) {
            for (int bx = Math.max(0, x - range); bx <= Math.min(world.map.width() - 1, x + range); bx++) {
                List<Unit> cached = world.unitCache.get(bx + by * world.map.width());
                if (cached == null) {
                    continue;
                }
                for (Unit other : cached) {
                    if (other.isOnMap()
                            && world.isEnemyPlayer(player, other.player())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }


    /**
     * Whether an attack may still pursue the goal it was given.
     *
     * <p>The sight clause is {@code IsVisibleAsGoal}, as
     * {@code CheckIfGoalValid} asks it --
     * so a computer player's grip does not slip when a neighbour's step
     * carries the fog off its target mid-cycle. On campaigns/human/level13h
     * that was a knight and a destroyer both dropping freshly given orders
     * on the map's second cycle: the knight's ogre went dark the instant a
     * fellow knight stepped away with the sight that lit it.
     */
    boolean validAttackTarget(Unit unit, Unit target) {
        return target != null && target.isAlive() && !target.isDying()
                && target.isOnMap()
                && world.isEnemyPlayer(unit.player(), target.player())
                && canTarget(unit, target)
                && (!unit.attackRequiresVisibility()
                        || isVisibleAsGoal(unit.player(), target));
    }


    /**
     * Whether a target is neither too far to hit nor too close.
     *
     * <p>{@code InAttackRange}. The lower bound is the half nobody read.
     */
    boolean inAttackRange(Unit unit, Unit target) {
        int distance = world.attackDistance(unit, target);
        return distance >= unit.type().minAttackRange()
                && distance <= Math.max(1, unit.type().maxAttackRange());
    }


    /**
     * The best thing to attack between two ranges, or null.
     *
     * <p>{@code ComputeCost} decides, and the rejections decide as much as the
     * arithmetic. The one that matters most is reachability: without it a unit
     * locks onto an enemy across a river, walks to the shore, fails, drops to
     * still, and picks the same unreachable target again next cycle, for ever.
     *
     * <p>Reachability is asked of the candidates in cost order rather than of
     * all of them, and the answer is the same -- upstream scores an
     * unreachable target at {@code INT_MAX}, so the winner is the cheapest
     * reachable one either way -- for one route search instead of a dozen.
     *
     * <p>An indestructible unit is refused here as well as when the blow
     * lands. Upstream locks it out twice over: {@code ComputeCost} returns
     * {@code INT_MAX} for one,
     * {@code BestRangeTargetFinder} keeps it out of the splash search
     * ({@code :840}), and {@code AiForce} does the same. The implementation read {@code indestructible()} only in
     * {@code applyDamage} and {@code hitDirectly}, so an army could pick one,
     * walk to it and swing at it for the rest of the game for no damage. That
     * has never been seen and it is luck rather than design: all 29 shipped
     * types that declare the flag also declare {@code HitPoints = 0}, so
     * {@code isAlive()} is already false for every one of them, and 22 of the
     * 29 are the dead-vision markers this loop skips as revealers anyway.
     */
    /**
     * Whether a unit's tiles fall in the box another unit searches.
     *
     * <p>{@code SelectAroundUnit}'s rectangle: from the searcher's top-left
     * corner less the range to its bottom-right corner plus the range, against
     * the whole of the other unit's footprint.
     */
    static boolean withinBox(Unit unit, Unit other, int range) {
        int left = unit.tileX() - range;
        int top = unit.tileY() - range;
        int right = unit.tileX() + Math.max(1, unit.type().tileWidth()) - 1 + range;
        int bottom = unit.tileY() + Math.max(1, unit.type().tileHeight()) - 1 + range;
        int otherRight = other.tileX() + Math.max(1, other.type().tileWidth()) - 1;
        int otherBottom = other.tileY() + Math.max(1, other.type().tileHeight()) - 1;
        return otherRight >= left && other.tileX() <= right
                && otherBottom >= top && other.tileY() <= bottom;
    }


    /**
     * One candidate's whole bill, highest wins.
     *
     * <p>Implements {@code TargetPriorityCalculate}
     * the target chooser the shipped data
     * actually uses. It is a bitfield, not a sum, and the order of the bits
     * is the order of the arguments that decide a fight: being attacked by
     * the candidate beats everything, a candidate that could hit back beats
     * one that cannot, then the type's own priority, then closeness -- and
     * closeness is the A* route's length with standing enemies as walls,
     * not the straight line -- and last, how hurt it is. A candidate out of
     * reach and out of range is no candidate at all, and one merely far away
     * -- reaction range for the ranged, half again for the melee -- keeps
     * only its threat bit, shifted down among the tie-breakers.
     *
     * <p>On campaigns/human/level13h this is the whole of the cycle-2
     * family. The wise-man's cycle-1 scan runs before the mob's attack
     * orders have popped, so nothing yet *targets* him and an ogre wins on
     * type priority; his own order re-selects on cycle 2, by which time
     * axethrower #94 four tiles away visibly hunts him, and its attacked-by
     * bit outranks everything the ogre had. The implementation scored by the other
     * branch's arithmetic and kept the ogre.
     *
     * <p>Not carried: the wall clause, because this implementation's walls are
     * terrain rather than units; and {@code ALWAYSTHREAT} and
     * {@code AiPriorityTarget}, which no shipped type declares.
     */
    int targetPriority(Unit attacker, Unit dest) {
        if (!world.isEnemyPlayer(attacker.player(), dest.player())
                || !isVisibleAsGoal(attacker.player(), dest)
                || !canTarget(attacker, dest)) {
            return Integer.MIN_VALUE;
        }
        if (dest.type().indestructible() || dest.hasBuff(Unit.Buff.UNHOLY_ARMOR)) {
            return Integer.MIN_VALUE;
        }
        int attackRange = Math.max(1, attacker.type().maxAttackRange());
        int minAttackRange = attacker.type().minAttackRange();
        int pathLength = world.calcPathLengthToUnit(attacker, dest, minAttackRange, attackRange);
        int distance = attacker.distanceTo(dest);
        boolean inRange = inAttackRange(attacker, dest);
        if (!inRange && ((distance > minAttackRange && pathLength < 0)
                || !attacker.canMove())) {
            return Integer.MIN_VALUE;
        }
        int priority = 0;
        // "Check if target attacks us (or has us as goal for any action)."
        if (dest.target() == attacker && inAttackRange(dest, attacker)) {
            priority |= World.AT_ATTACKED_BY_FACTOR;
        }
        if (canTarget(dest, attacker)) {
            priority |= World.AT_THREAT_FACTOR;
        }
        // "To reduce melee units roaming when a lot of them fight in small
        // areas": full priority only for the easily reached or the already
        // hostile.
        int reactionRange = attacker.type().reactRange(world.isPerson(attacker.player()));
        int maxDistance = attackRange > 1 ? reactionRange : (reactionRange * 3) >> 1;
        boolean farAway = (priority & World.AT_ATTACKED_BY_FACTOR) == 0
                && pathLength + 1 > maxDistance;
        if (farAway || distance < minAttackRange) {
            priority >>= World.AT_FARAWAY_REDUCE_OFFSET;
        } else {
            priority |= dest.type().priority() << World.AT_PRIORITY_OFFSET;
        }
        int clamped = pathLength > 255 || pathLength < 0 ? 255 : pathLength;
        priority |= (255 - clamped) << World.AT_DISTANCE_OFFSET;
        priority |= 100 - dest.hitPoints() * 100 / Math.max(1, dest.type().hitPoints());
        if (World.TRACE_COST_UNIT >= 0 && attacker.id() == World.TRACE_COST_UNIT) {
            System.err.printf("PICKDBG cycle=%d attacker=%d candidate=%d %s at %d,%d"
                    + " prio=%x pathLength=%d distance=%d goal=%d attacks=%b%n",
                    world.cycle, attacker.id(), dest.id(), dest.type().ident(),
                    dest.tileX(), dest.tileY(), priority, pathLength, distance,
                    dest.target() == null ? -1 : dest.target().id(),
                    dest.target() == attacker && inAttackRange(dest, attacker));
        }
        return priority;
    }


    Unit findHostile(Unit unit, int minRange, int range) {
        // A splash weapon looks further than its reaction range, because what
        // it is choosing is a place to land rather than a body to hit.
        // {@code AttackUnitsInDistance} selects
        // over {@code Missile->Range + range - 1} when the missile splashes at
        // all and the two together come to less than fifteen, and only then
        // hands the result to the finder that scores them.
        //
        // On demo03 that is a catapult at 21,4 and the peasant at 9,2 that
        // every unit on the map wants: twelve squares apart, against a
        // reaction range of eleven and a splash of two, so upstream searches
        // twelve and finds it and this implementation searched eleven and did not.
        MissileType weapon = world.projectiles.missileFor(unit);
        int scan = range;
        if (weapon != null && weapon.range() > 1 && range + weapon.range() < 15) {
            scan = weapon.range() + range - 1;
        }
        // The candidates arrive in the box scan's own order -- Select walks
        // the tile cache row by row and column by column
        // ({@code unit/unit_find.h:286-294}), so the northernmost candidate
        // is asked first and the westernmost breaks the row -- and the order
        // is not cosmetic: the finder keeps the first best it meets, so a
        // tie between two identical grunts goes to the one the scan reached
        // first. This walked the world's unit list instead, which is
        // creation order; on campaigns/human-exp/levelx12h the guard tower's
        // two tied grunts sat in opposite orders in the two walks, and the
        // tower shot the wrong one.
        List<Unit> candidates = null;
        int found = 0;
        java.util.HashSet<Unit> seen = new java.util.HashSet<>();
        int left = unit.tileX() - scan;
        int top = unit.tileY() - scan;
        int right = unit.tileX() + Math.max(1, unit.type().tileWidth()) - 1 + scan;
        int bottom = unit.tileY() + Math.max(1, unit.type().tileHeight()) - 1 + scan;
        List<Unit> ordered = new ArrayList<>();
        for (int y = Math.max(0, top); y <= Math.min(world.map.height() - 1, bottom); y++) {
            for (int x = Math.max(0, left); x <= Math.min(world.map.width() - 1, right); x++) {
                List<Unit> cached = world.unitCache.get(x + y * world.map.width());
                if (cached == null) {
                    continue;
                }
                for (Unit other : cached) {
                    if (seen.add(other)) {
                        ordered.add(other);
                    }
                }
            }
        }
        String costTraceEarly = System.getenv("CHONKCRAFT_TRACE_COST");
        boolean tracingWalk = costTraceEarly != null
                && unit.id() == Integer.parseInt(costTraceEarly);
        for (Unit other : ordered) {
            if (tracingWalk) {
                System.err.printf("JPICKDBG cycle=%d attacker=%d saw=%d %s at %d,%d"
                                + " alive=%b onmap=%b reveal=%b vanish=%b indestruct=%b"
                                + " invisible=%b enemy=%b targetable=%b visible=%b%n",
                        world.cycle, unit.id(), other.id(), other.type().ident(),
                        other.tileX(), other.tileY(), other.isAlive(), other.isOnMap(),
                        other.type().revealer(), other.type().vanishes(),
                        other.type().indestructible(), other.hasBuff(Unit.Buff.INVISIBLE),
                        world.isEnemyPlayer(unit.player(), other.player()), canTarget(unit, other),
                        isVisibleAsGoal(unit.player(), other));
            }
            if (other == unit || !other.isAlive() || other.isDying() || !other.isOnMap()) {
                continue;
            }
            if (other.type().revealer() || other.type().vanishes()
                    || other.type().indestructible()
                    // ComputeCost gives an UnholyArmour'd target INT_MAX in the
                    // same condition that gives an indestructible one INT_MAX
                    // so nothing picks it while the spell
                    // holds -- which is the point of casting it on the unit
                    // being focused down.
                    || other.hasBuff(Unit.Buff.UNHOLY_ARMOR)
                    // And an invisible unit is not a target either:
                    // The game skips one, and it is what the spell is
                    // for.
                    || other.hasBuff(Unit.Buff.INVISIBLE)) {
                continue;
            }
            if (!world.isEnemyPlayer(unit.player(), other.player()) || !canTarget(unit, other)) {
                continue;
            }
            // Only what the owner may aim at -- and for a computer owner
            // that is everything: IsVisibleAsGoal answers yes for a computer
            // player before the fog is consulted. The honest isVisibleTo
            // here kept this implementation's AI from noticing what upstream's shoots.
            if (!isVisibleAsGoal(unit.player(), other)) {
                continue;
            }
            // Selected by a box, not by a circle. {@code SelectAroundUnit}
            // ({@code unit/unit_find.h:286-294}) takes everything whose tiles
            // fall between {@code tilePos - range} and
            // {@code tilePos + typeSize + range}, and only the finders that
            // score what it collected measure a real distance. This implementation
            // filtered on the distance itself, which is
            // {@code MapDistanceBetweenTypes} and so Euclidean: an axethrower
            // at 16,6 is seven squares from the peasant at 9,2 across the box
            // and eight by the hypotenuse, so upstream sees it with a reaction
            // range of seven and this implementation did not.
            int distance = unit.distanceTo(other);
            if (distance < minRange || !withinBox(unit, other, scan)) {
                continue;
            }
            if (candidates == null) {
                candidates = new ArrayList<>();
            }
            candidates.add(other);
            found++;
        }
        if (candidates == null) {
            return null;
        }
        // A weapon that splashes chooses differently: what matters is not the
        // best single target but the best *place to land*, which means
        // weighing what else is standing there. Upstream switches finder for
        // exactly this, and without it a catapult on auto-attack will happily
        // pick the enemy standing in the middle of your own men.
        //
        // But only under the old scoring. With the shipped
        // SimplifiedAutoTargeting, BestRangeTargetFinder::Find skips the
        // FillBadGood table outright and its Compute is
        // TargetPriorityCalculate like everyone else's
        // The game the splash branch keeps
        // only its wider scan box. On campaigns/human-exp/levelx10h the
        // ballista at 88,90 weighs five grunts and takes the tie-winner at
        // 78,89 by priority, where this implementation's splash arithmetic aimed two
        // squares up the line and marched the wrong way from its first step.
        MissileType shot = world.projectiles.missileFor(unit);
        if (!world.simplifiedAutoTargeting && shot != null && shot.splashes()) {
            // The splash finder rejects what it cannot get at, exactly as the
            // plain one below does. {@code FillBadGood::Compute} counts a
            // candidate only when "d <= attackrange || (d <= range &&
            // UnitReachable(*attacker, dest, attackrange, false))"
            // which is the same test
            // {@code BestTargetFinder::Compute} opens with; this implementation asked it
            // of the ordinary finder and not of this one.
            //
            // It is what a warship does about a peasant standing on dry land.
            // On demo02 five destroyers sit six to ten squares off a beach
            // with four squares of gun and no way to sail closer, and upstream
            // leaves every one of them standing.
            int gunReach = Math.max(1, unit.type().maxAttackRange());
            List<Unit> shootable = new ArrayList<>(candidates.size());
            for (Unit other : candidates) {
                if (unit.distanceTo(other) <= gunReach || world.isReachable(unit, other)) {
                    shootable.add(other);
                }
            }
            Unit best = shootable.isEmpty() ? null : bestSplashTarget(unit, shootable, shot);
            if (best != null) {
                return best;
            }
        }
        if (world.simplifiedAutoTargeting) {
            // BestTargetFinder::Find with the shipped setting: the highest
            // bill wins, strictly -- the first candidate keeps a tie
            // and a candidate whose bill is
            // INT_MIN never beats the empty hand.
            String costTrace = System.getenv("CHONKCRAFT_TRACE_COST");
            boolean tracingCost = costTrace != null
                    && unit.id() == Integer.parseInt(costTrace);
            Unit best = null;
            int bestPriority = Integer.MIN_VALUE;
            for (int i = 0; i < found; i++) {
                Unit other = candidates.get(i);
                int priority = targetPriority(unit, other);
                if (tracingCost) {
                    // The implementation-side twin of TargetPriorityCalculate's
                    // PICKDBG print in tools/legacyEngine-trace.patch: every
                    // candidate's whole word, in the walk's own order.
                    System.err.printf(
                            "JPICKDBG cycle=%d attacker=%d candidate=%d %s at %d,%d prio=%x%n",
                            world.cycle, unit.id(), other.id(), other.type().ident(),
                            other.tileX(), other.tileY(), priority);
                }
                if (priority > bestPriority) {
                    best = other;
                    bestPriority = priority;
                }
            }
            return best;
        }
        long[] costs = new long[found];
        for (int i = 0; i < found; i++) {
            Unit other = candidates.get(i);
            costs[i] = targetCost(unit, other, unit.distanceTo(other));
        }
        // Cheapest first, then walk until one can actually be got at.
        Integer[] order = new Integer[found];
        for (int i = 0; i < found; i++) {
            order[i] = i;
        }
        java.util.Arrays.sort(order, (a, b) -> Long.compare(costs[a], costs[b]));
        int reach = Math.max(1, unit.type().maxAttackRange());
        for (int index : order) {
            Unit other = candidates.get(index);
            if (unit.distanceTo(other) <= reach || world.isReachable(unit, other)) {
                return other;
            }
        }
        return null;
    }


    /**
     * Where a splash weapon should aim.
     *
     * <p>Implements {@code BestRangeTargetFinder}. For
     * each candidate it sums what the blast would be worth against the enemies
     * around it and what it would cost against your own men and any neutrals,
     * the square itself counting full and its neighbours divided by the
     * splash factor, and takes the best ratio of the two.
     *
     * <p>The friendly cost is the sharper half: it is weighted by whether the
     * shot would *kill* what it caught, so a catapult will fire into a crowd
     * of your footmen at full health and refuse the same shot when they are
     * hurt.
     */
    Unit bestSplashTarget(Unit attacker, List<Unit> candidates, MissileType shot) {
        int blast = Math.max(1, shot.splashFactor());
        int damage = estimatedDamage(attacker);
        Unit best = null;
        long bestCost = Long.MIN_VALUE;

        for (Unit target : candidates) {
            long bad = 0;
            long good = 0;
            for (Unit near : world.units) {
                if (!near.isAlive() || near.isDying() || !near.isOnMap()) {
                    continue;
                }
                int gap = near.distanceTo(target);
                if (gap > 1) {
                    continue;
                }
                // The square itself counts full; its neighbours are divided by
                // the splash factor, as the three-by-three sum does upstream.
                int share = gap == 0 ? 1 : blast;
                if (world.isEnemyPlayer(attacker.player(), near.player())) {
                    bad += splashValueOf(attacker, near, damage) / share;
                } else if (near != attacker) {
                    good += friendlyFireCostOf(near, damage) / share;
                }
            }
            // Small nicks among your own men are not worth refusing a shot
            // for, which is what the floor of twenty is doing.
            long cost = bad / Math.max(good, 20);
            if (cost > bestCost) {
                bestCost = cost;
                best = target;
            }
        }
        return best;
    }


    /** Roughly what one blow from this unit takes off, before armour. */
    int estimatedDamage(Unit attacker) {
        UpgradeState state = world.upgrades(attacker.player());
        return Math.max(1, state.basicDamage(attacker.type())
                + state.piercingDamage(attacker.type()));
    }


    /** What catching an enemy in the blast is worth. */
    long splashValueOf(Unit attacker, Unit enemy, int damage) {
        UnitType type = enemy.type();
        int footprint = Math.max(1, type.tileWidth());
        long value = (long) type.priority() * World.PRIORITY_FACTOR;
        // Preferring a kill to a scratch: what would be left of it, floored at
        // the damage so everything we are sure to kill scores alike.
        int remaining = enemy.hitPoints() - 2 * damage;
        remaining = Math.max(-damage, Math.min(0, remaining));
        value += -(long) remaining * World.HEALTH_FACTOR;
        if (canTarget(enemy, attacker)) {
            value += World.CANATTACK_BONUS;
        }
        return Math.max(1, value / ((long) footprint * footprint));
    }


    /** What catching one of your own, or a neutral, in the blast costs. */
    long friendlyFireCostOf(Unit friend, int damage) {
        UnitType type = friend.type();
        int footprint = Math.max(1, type.tileWidth());
        long cost = (long) World.HEALTH_FACTOR * (2L * damage - friend.hitPoints())
                / ((long) footprint * footprint);
        return Math.max(1, cost);
    }


    /**
     * What it costs to attack something; the cheapest is chosen.
     *
     * <p>{@code ComputeCost}, weights and all. Nearest
     * is not the right answer and this is why: a catapult two squares further
     * off than a peasant is the thing that will kill you, and every unit type
     * carries a Priority saying so -- a catapult is 200, a footman 60, a
     * peasant 50. The implementation picked whatever was closest and let the siege engine
     * fire.
     *
     * <p>Also weighed: a wounded target is worth finishing, something already
     * in range is worth far more than something that has to be walked to, and
     * anything that can shoot back is worth hitting first.
     */
    long targetCost(Unit attacker, Unit target, int distance) {
        // An aircraft that cannot fight is the last thing worth chasing: a
        // scouting balloon should not pull an army off a battle. Upstream
        // scores it INT_MAX / 2, a rejection everything real beats rather than
        // an outright refusal.
        if (target.type().airUnit() && !target.isAggressive()) {
            return World.PASSING_FLYER;
        }
        long cost = 0;
        cost -= (long) target.type().priority() * World.PRIORITY_FACTOR;
        int maxHitPoints = Math.max(1, target.type().hitPoints());
        cost += (long) target.hitPoints() * 100 / maxHitPoints * World.HEALTH_FACTOR;

        int reach = Math.max(1, attacker.type().maxAttackRange());
        // Too close counts as out of range, not in it: a catapult gets no
        // bonus for a footman leaning on it, because it cannot fire at one.
        if (distance <= reach && distance >= attacker.type().minAttackRange()) {
            cost += (long) distance * World.INRANGE_FACTOR - World.INRANGE_BONUS;
        } else {
            cost += (long) distance * World.DISTANCE_FACTOR;
        }
        if (canTarget(target, attacker)) {
            cost -= World.CANATTACK_BONUS;
        }
        return cost;
    }


    /**
     * Chooses BNE's highest-scoring idle target ({@code 0x409ff0/0x40a4b0}).
     *
     * <p>The retail score predates ChonkCraft's TargetPriorityCalculate bitfield.
     * It starts with the target type's byte priority, subtracts one quarter
     * of the squared map distance, and adds a large categorical bonus for a
     * unit which can fight back. A threat already inside weapon range beats
     * one still outside it; an in-range aircraft gets the highest category
     * ({@code 0x30000}), while in-range ground combatants -- mobile or
     * combat-building -- share {@code 0x20000}. Passive buildings stay at
     * priority-minus-distance only. Equal scores retain the first unit in
     * the native persistent screen-Y order ({@code DAT_004bf1d8}), not a
     * row-major map scan.</p>
     */
    Unit findBattleNetHostile(Unit attacker, int range, Unit incumbent) {
        Unit locked = world.playerSiegeBuildingTargetLock(attacker);
        if (locked != null) {
            // Playable desktop games keep ownership of a direct building
            // click.  BNE 2.02 instead reaches 0x409ff0 from the moving siege
            // callback and may publish a nearby mobile target at 0x437920.
            // The world option is deliberately off in parity fixtures.
            return locked;
        }
        // 0x40a19f returns empty-handed before the incumbent is ever priced,
        // so a band that holds nothing beats a target already in hand.
        int[] band = battleNetBandWindow(attacker, range);
        if (band == null) {
            return null;
        }
        int incumbentScore = battleNetTargetScore(attacker, incumbent);
        Unit best = incumbentScore > 0 ? incumbent : null;
        int bestScore = incumbentScore;
        boolean tracing = System.getenv("CHONKCRAFT_TRACE_BNE_TARGET") != null;

        for (int index = band[0]; index <= band[1]; index++) {
            Unit candidate = world.battleNetSpatialUnits.get(index);
            if (candidate == attacker
                    || !candidate.isAlive() || candidate.isDying()
                    || !candidate.isOnMap()
                    || candidate.type().revealer()
                    || candidate.type().vanishes()
                    || candidate.type().indestructible()
                    || candidate.hasBuff(Unit.Buff.UNHOLY_ARMOR)
                    || candidate.hasBuff(Unit.Buff.INVISIBLE)
                    || !world.isEnemyPlayer(attacker.player(), candidate.player())
                    || !canTarget(attacker, candidate)
                    || !isVisibleAsGoal(attacker.player(), candidate)
                    || !withinBattleNetBox(attacker, candidate, range)) {
                continue;
            }
            int score = battleNetTargetScore(attacker, candidate);
            if (score <= 0) {
                continue;
            }
            if (tracing) {
                System.err.printf("JBNETARGET cycle=%d attacker=%d"
                                + " candidate=%d type=%s at=%d,%d"
                                + " distance=%d score=%x%n",
                        world.cycle, attacker.id(), candidate.id(),
                        candidate.type().ident(), candidate.tileX(),
                        candidate.tileY(),
                        world.battleNetDistance(attacker, candidate), score);
            }
            // Strict greater only -- equal scores keep the first spatial hit.
            if (score > bestScore) {
                best = candidate;
                bestScore = score;
            }
        }
        return best;
    }


    /**
     * The slice of the screen-Y index one reaction scan is allowed to see.
     *
     * <p>Implements {@code FUN_0040a2b0} in {@code Warcraft II BNE.exe}
     * 2.02b, reached from the target search {@code FUN_00409ff0}. It binary
     * searches {@link #battleNetSpatialUnits} for the entries whose
     * <em>tile</em> Y lies within {@code [top - 3, bottom]} of the reaction
     * rectangle -- three squares of slack for a footprint taller than one --
     * and only those entries are ever offered to the box test.</p>
     *
     * <p>The list it searches is ordered by <em>pixel</em> Y, which is a
     * different number for any unit part-way between two squares. A unit
     * that has already snapped its tile northward therefore sits behind one
     * whose tile Y has left the band, and this search stops at that
     * inversion instead of walking past it. Walking the whole list finds
     * targets native cannot see: Human 13's ogre in native slot 1519 stands
     * still at fixture 29 with a knight six squares off and takes that same
     * knight, on the same square at the same 84 hit points, at 34, because
     * at 29 an axethrower at pixel Y 803 and tile Y 26 stands in front of
     * the knight at pixel Y 822 and tile Y 25, and by 34 the knight has
     * fallen to pixel Y 809 and sorts ahead of it.</p>
     *
     * @return the first and last index, both inclusive, or null when the
     *     band holds nothing
     */
    int[] battleNetBandWindow(Unit attacker, int range) {
        int count = world.battleNetSpatialUnits.size();
        if (count <= 0) {
            return null;
        }
        int height = attacker.type().airUnit()
                ? 1 : Math.max(1, attacker.type().tileHeight());
        int bandLow = Math.max(0, attacker.tileY() - range - 3);
        int bandHigh = attacker.tileY() + height - 1 + range;
        int low = 0;
        int high = count - 1;
        if (bandLow > world.bandTileY(high) || bandHigh < world.bandTileY(low)) {
            return null;
        }
        int total = low + high;
        int middle = total / 2;
        while (true) {
            middle = total / 2;
            int tileY = world.bandTileY(middle);
            if (tileY < bandLow && middle + 1 < count
                    && world.bandTileY(middle + 1) >= bandLow) {
                break;
            }
            if (low == high) {
                break;
            }
            // Native halves the summed bounds rather than stepping past the
            // probe, so neither end can overshoot the other.
            if (tileY >= bandLow) {
                high = (total - 1) / 2;
            } else {
                low = (total + 1) / 2;
            }
            total = low + high;
        }
        int start = middle;
        if (world.bandTileY(start) < bandLow) {
            start++;
        }
        if (start >= count || world.bandTileY(start) > bandHigh) {
            return null;
        }
        low = start;
        high = count - 1;
        total = low + high;
        while (true) {
            middle = total / 2;
            int tileY = world.bandTileY(middle);
            if (tileY > bandHigh && middle > 0
                    && world.bandTileY(middle - 1) <= bandHigh) {
                break;
            }
            if (low == high) {
                break;
            }
            if (tileY <= bandHigh) {
                low = (total + 1) / 2;
            } else {
                high = (total - 1) / 2;
            }
            total = low + high;
        }
        int end = middle;
        if (world.bandTileY(end) > bandHigh) {
            end--;
        }
        if (System.getenv("CHONKCRAFT_TRACE_BNE_BAND") != null) {
            StringBuilder edge = new StringBuilder();
            for (int index = Math.max(0, start); index < count
                    && index <= end + 3; index++) {
                Unit entry = world.battleNetSpatialUnits.get(index);
                edge.append(String.format(" [%d]%d@%d/%d", index, entry.id(),
                        entry.tileY(), World.battleNetScreenY(entry)));
            }
            System.err.printf("JBNEBAND cycle=%d unit=%d band=%d..%d "
                            + "window=%d..%d count=%d%s%n", world.cycle, attacker.id(),
                    bandLow, bandHigh, start, end, count, edge);
        }
        return end < start ? null : new int[] {start, end};
    }


    /** One candidate's native {@code 0x40a4b0} score, or zero if invalid. */
    int battleNetTargetScore(Unit attacker, Unit candidate) {
        if (candidate == null || candidate == attacker
                || !candidate.isAlive() || candidate.isDying()
                || !candidate.isOnMap()
                || candidate.type().revealer()
                || candidate.type().vanishes()
                || candidate.type().indestructible()
                || candidate.hasBuff(Unit.Buff.UNHOLY_ARMOR)
                || candidate.hasBuff(Unit.Buff.INVISIBLE)
                || !world.isEnemyPlayer(attacker.player(), candidate.player())
                || !canTarget(attacker, candidate)
                || !isVisibleAsGoal(attacker.player(), candidate)) {
            return 0;
        }
        int distance = world.battleNetDistance(attacker, candidate);
        if (distance < Math.max(0, attacker.type().minAttackRange())) {
            return 0;
        }
        int score = battleNetTargetPriority(candidate);
        score -= distance * distance >> 2;
        if (candidate.type().canAttack()) {
            if (distance > Math.max(1, attacker.type().maxAttackRange())) {
                score += 0x10000;
            } else if (candidate.type().airUnit()) {
                score += 0x30000;
            } else {
                // In-range ground combatants share 0x20000 whether mobile or
                // combat-building. The old 0x30000 for buildings made XHuman
                // 2 catapult 45 choose guard-tower 54 over footman 52; native
                // rock from 56,63 (rem 157 at c10) aims the footman and frees
                // at fixture 35. Passive buildings stay at priority-distance
                // only so they do not outrank in-range fighters.
                score += 0x20000;
            }
        }
        return Math.max(1, score);
    }


    /** Returns BNE's UDTA priority without mutating ChonkCraft's shared type. */
    int battleNetTargetPriority(Unit candidate) {
        int code = PudUnitTypes.code(candidate.type().ident());
        if (world.battleNetUnitPriorities != null
                && code >= 0 && code < world.battleNetUnitPriorities.length) {
            return world.battleNetUnitPriorities[code] & 0xff;
        }
        return candidate.type().priority() & 0xff;
    }


    /** BNE's scan rectangle, including its one-square flying-unit extent. */
    static boolean withinBattleNetBox(Unit unit, Unit other, int range) {
        int unitWidth = unit.type().airUnit()
                ? 1 : Math.max(1, unit.type().tileWidth());
        int unitHeight = unit.type().airUnit()
                ? 1 : Math.max(1, unit.type().tileHeight());
        int otherWidth = other.type().airUnit()
                ? 1 : Math.max(1, other.type().tileWidth());
        int otherHeight = other.type().airUnit()
                ? 1 : Math.max(1, other.type().tileHeight());
        int left = unit.tileX() - range;
        int top = unit.tileY() - range;
        int right = unit.tileX() + unitWidth - 1 + range;
        int bottom = unit.tileY() + unitHeight - 1 + range;
        int otherRight = other.tileX() + otherWidth - 1;
        int otherBottom = other.tileY() + otherHeight - 1;
        return otherRight >= left && other.tileX() <= right
                && otherBottom >= top && other.tileY() <= bottom;
    }
}
