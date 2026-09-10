package net.chonkbase.chonkcraft.engine;

import java.util.LinkedHashSet;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;

/**
 * Dwarven and goblin demolition orders from retail BNE's player function 19.
 *
 * <p>The button at 0x436950 requests a target. Constructor 0x4366a0 selects
 * action 20 for an occupied/destructible target and action 21 for a ground
 * point. Both use the same 0x40ae50 explosion after their approach completes.
 * Treating this as a mana spell used to discard both races' button clicks.
 */
final class BattleNetDemolitionSystem {
    private static final String ABILITY = "spell-suicide-bomber";
    private static final int[][] NEIGHBOURS = {
        {1, 1}, {-1, 1}, {1, -1}, {-1, -1}, {0, 1}, {0, -1}, {1, 0}, {-1, 0}
    };
    private final World world;

    BattleNetDemolitionSystem(World world) {
        this.world = world;
    }

    boolean order(Unit unit, Unit target, int x, int y, boolean fromPlayer) {
        if (unit == null || !unit.isAlive() || !unit.isOnMap() || !unit.isDemolitionSquad()
                || !world.map.contains(x, y) || target == unit
                || target != null && (!target.isAlive() || !target.isOnMap())) {
            return false;
        }
        if (fromPlayer && world.orderReplacementMustWait(unit)) {
            unit.clearQueuedOrders();
            unit.setSavedOrder(null);
            unit.enqueueOrder(new Unit.QueuedOrder(Unit.QueuedOrderKind.CAST,
                    x, y, target, null, ABILITY));
            unit.setQueuedReplacementPending(true);
            unit.rememberActionBeforeQueued(unit.order());
            return true;
        }
        if (fromPlayer && unit.order() == Unit.Order.SPELL_CAST
                && ABILITY.equals(unit.castingSpell()) && unit.battleNetOrderDelay() > 0) {
            unit.clearQueuedOrders();
            unit.enqueueOrder(new Unit.QueuedOrder(Unit.QueuedOrderKind.CAST,
                    x, y, target, null, ABILITY));
            unit.setQueuedReplacementPending(true);
            return true;
        }
        if (fromPlayer && world.battleNetSequence != null && unit.order() == Unit.Order.STILL) {
            int queueWait = world.movement.playerCommandWaits(unit)[1];
            if (queueWait > 0) {
                unit.enqueueOrder(new Unit.QueuedOrder(Unit.QueuedOrderKind.CAST,
                        x, y, target, null, ABILITY));
                unit.setQueuedReplacementPending(true);
                unit.setBattleNetOrderDelay(queueWait + 1);
                return true;
            }
        }
        world.releaseBattleNetCombatOrderForPlayerReplacement(unit);
        unit.setBattleNetPlayerCommandMove(false);
        unit.clearPath();
        unit.setTarget(target);
        unit.setOrderTarget(x, y);
        unit.setCastingSpell(ABILITY);
        unit.setOrder(Unit.Order.SPELL_CAST);
        if (world.battleNetSequence != null) {
            unit.setBattleNetSequenceOffset(world.idle.battleNetSequenceStart(
                    unit, BattleNetSequence.ATTACK_ANIMATION));
            unit.setBattleNetAnimationTimer(3);
            unit.setBattleNetOrderDelay(fromPlayer ? 4 : 3);
        }
        return true;
    }

    void step(Unit unit) {
        if (unit.battleNetOrderDelay() > 0) {
            unit.setBattleNetOrderDelay(unit.battleNetOrderDelay() - 1);
            unit.setBattleNetAnimationTimer(Math.max(1, unit.battleNetAnimationTimer() - 1));
            if (unit.battleNetOrderDelay() > 0) return;
        }
        if (unit.queuedReplacementPending() && unit.hasQueuedOrders()
                && !world.orderReplacementMustWait(unit)) {
            unit.setOrder(Unit.Order.STILL);
            world.beginNextQueuedOrder(unit);
            return;
        }
        Unit target = unit.target();
        if (target != null && (!target.isAlive() || !target.isOnMap())) {
            world.finishOrder(unit);
            return;
        }
        if (unit.isMoving() || unit.residualX() != 0 || unit.residualY() != 0) {
            world.movement.walkPixels(unit);
            if (unit.isMoving() || unit.residualX() != 0 || unit.residualY() != 0) return;
            unit.setRouteSpent(false);
            unit.setWaitCycles(0);
        }
        int x = target == null ? unit.orderTargetX() : target.tileX();
        int y = target == null ? unit.orderTargetY() : target.tileY();
        var field = world.map.fieldOrNull(x, y);
        boolean beside = target != null || field != null && (field.isForest()
                || field.isWall() || field.hasFlag(TileFlag.ROCKS));
        int distance = target == null ? unit.distanceTo(x, y) : unit.distanceTo(target);
        if (distance <= (beside ? 1 : 0)) {
            detonate(unit);
            return;
        }
        if (unit.pathLength() == 0 || unit.pathGoalX() != x || unit.pathGoalY() != y) {
            unit.clearPath();
            PathFinder.Path path = world.findBattleNetPointPath(unit, x, y);
            if (path == null || path.result() != PathFinder.Result.FOUND || path.length() == 0) {
                world.finishOrder(unit);
                return;
            }
            unit.setPath(path);
            unit.setPathGoal(x, y);
        }
        world.combat.stepMoveTowardsTarget(unit);
    }

    /** A Move replacing demolition reconstructs Still with timer three at 0x452587. */
    boolean finishReplacement(Unit unit) {
        if (unit.order() != Unit.Order.SPELL_CAST || !ABILITY.equals(unit.castingSpell())
                || !unit.hasQueuedOrders() || unit.queuedOrders().getFirst().kind() != Unit.QueuedOrderKind.MOVE) {
            return false;
        }
        unit.setOrder(Unit.Order.STILL);
        unit.setActionBeforeQueued(null);
        unit.setWaitCycles(0);
        world.beginNextQueuedOrder(unit);
        if (unit.order() == Unit.Order.MOVE) {
            unit.setBattleNetOrderDelay(2);
            unit.setBattleNetSequenceOffset(world.idle.battleNetStillSequenceStart(unit));
            unit.setBattleNetAnimationTimer(3);
        }
        return true;
    }

    /**
     * Native 0x40af00 visits ground occupancy in eight cells, deduplicates
     * footprints, then hits for 200 twice if the first hit left an occupant.
     * There is no random damage roll and no blast from a squad killed by it.
     */
    void detonate(Unit unit) {
        int x = unit.tileX();
        int y = unit.tileY();
        clearTerrain(x, y);
        var struck = new LinkedHashSet<Unit>();
        for (int[] delta : NEIGHBOURS) {
            int tx = x + delta[0];
            int ty = y + delta[1];
            for (Unit candidate : world.unitsSnapshot()) {
                if (candidate == unit || !candidate.isAlive() || !candidate.isOnMap()
                        || candidate.type().airUnit() || !candidate.covers(tx, ty)
                        || !struck.add(candidate)) continue;
                world.hitDirectly(unit, candidate, 200);
                if (candidate.isAlive() && candidate.isOnMap()) world.hitDirectly(unit, candidate, 200);
            }
        }
        world.kill(unit);
    }

    /** BNE 0x44e270 clears terrain around the squad before damaging units. */
    private void clearTerrain(int x, int y) {
        for (int dx = -2; dx <= 2; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                int distance = Math.abs(dx) + Math.abs(dy);
                if (distance == 0 || distance > 2) continue;
                var field = world.map.fieldOrNull(x + dx, y + dy);
                if (field == null) continue;
                if (field.isForest()) world.map.clearWoodTile(x + dx, y + dy);
                else if (field.hasFlag(TileFlag.ROCKS)) world.map.clearRockTile(x + dx, y + dy);
                else if (field.isWall() && Math.abs(dx) <= 1 && Math.abs(dy) <= 1) {
                    world.clearTile(x + dx, y + dy);
                }
            }
        }
    }
}
