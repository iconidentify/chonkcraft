package net.chonkbase.chonkcraft.engine;

import net.chonkbase.chonkcraft.engine.unit.Unit;

/**
 * Compatibility observer for deaths already scored by {@link World#kill}.
 *
 * <p>Scoring used to live here, outside the simulation. That made headless
 * parity and multiplayer omit the lethal event entirely. The observer remains
 * for callers which use {@link #counted()} but never mutates player state.
 */
public final class ScoreKeeper {

    private final World world;

    /** The dead units already observed, by identifier. */
    private final java.util.Set<Integer> counted = new java.util.HashSet<>();

    public ScoreKeeper(World world) {
        this.world = world;
    }

    /**
     * Looks over the field and remembers anything newly dead.
     *
     * <p>Called once per simulation advance. Calling it more often is
     * harmless -- a unit is only ever counted once -- and calling it less
     * often loses nothing either, because a corpse lingers for many cycles
     * before it is taken off the list.
     */
    public void update() {
        for (Unit unit : world.unitsSnapshot()) {
            if (!isDead(unit) || !counted.add(unit.id())) {
                continue;
            }
            // World.kill paid the event synchronously.  This set is only an
            // observation of how many corpses this compatibility object saw.
        }
    }

    /** How many deaths this compatibility observer has seen. */
    public int counted() {
        return counted.size();
    }

    private static boolean isDead(Unit unit) {
        return unit.order() == Unit.Order.DYING || unit.hitPoints() <= 0;
    }
}
