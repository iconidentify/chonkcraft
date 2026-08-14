package net.chonkbase.chonkcraft.engine.animation;

/**
 * Where a unit is inside its current animation.
 *
 * <p>Implements {@code CUnit::Anim}. Kept separate from the unit so the
 * stepping logic has one obvious owner.
 */
public final class AnimationState {

    private Animation current;
    private int index;
    private int wait;
    private boolean unbreakable;
    private boolean waiting;
    private Animation restCurrent;
    private int restIndex;
    private int restWait;
    private boolean restUnbreakable;

    /** The animation being run, or {@code null} if none has been set. */
    public Animation current() {
        return current;
    }

    /** Position in the instruction list. */
    public int index() {
        return index;
    }

    /** Cycles left on the current wait. */
    public int waitCycles() {
        return wait;
    }

    void setWaitCycles(int wait) {
        this.wait = wait;
    }

    void setIndex(int index) {
        this.index = index;
    }

    /**
     * Whether the unit is inside a stretch that must finish.
     *
     * <p>An attack swing is unbreakable, which is why ordering a unit away
     * mid-swing does not cancel the blow it has already started.
     */
    public boolean unbreakable() {
        return unbreakable;
    }

    void setUnbreakable(boolean unbreakable) {
        this.unbreakable = unbreakable;
    }

    /**
     * Drops the guard whatever the script said.
     *
     * <p>For death: {@code LetUnitDie} clears {@code Anim.Unbreakable}, and a
     * unit killed mid-swing that kept it would never run its death animation.
     */
    public void clearUnbreakable() {
        unbreakable = false;
    }

    /**
     * Clears only the selected animation.
     *
     * <p>Resource orders assign {@code unit.Anim.CurrAnim = nullptr} when a
     * worker is removed into a mine or depot. The other animation fields are
     * deliberately left alone; the next {@link #switchTo(Animation)} sees a
     * different animation and performs the ordinary restart.
     */
    public void clearCurrent() {
        current = null;
    }

    /** Whether the unit is asleep and playing Still in place of its own. */
    public boolean isWaiting() {
        return waiting;
    }

    /**
     * Puts the unit's own animation aside for a nap.
     *
     * <p>{@code COrder::IsWaiting} is
     * {@code unit.WaitBackup = unit.Anim} followed by
     * {@code UnitShowAnimation(unit, &unit.Type->Animations->Still)}: a unit
     * whose order is asleep is not frozen, it stands there breathing. Doing it
     * only once is upstream's own guard -- {@code if (!unit.Waiting)} -- and it
     * is what keeps the second cycle of a wait from backing up the Still
     * animation over the real one.
     */
    public void beginWait() {
        if (waiting) {
            return;
        }
        waiting = true;
        restCurrent = current;
        restIndex = index;
        restWait = wait;
        restUnbreakable = unbreakable;
    }

    /**
     * Picks the unit's own animation up where it left off.
     *
     * <p>{@code COrder::StopWaiting}.
     * Every order's {@code Execute} opens with {@code if (IsWaiting(unit))
     * return;} and then this, so the restore happens on the first cycle the
     * unit acts again.
     */
    public void endWait() {
        if (!waiting) {
            return;
        }
        current = restCurrent;
        index = restIndex;
        wait = restWait;
        unbreakable = restUnbreakable;
        restCurrent = null;
        waiting = false;
    }

    /**
     * Switches to a different animation, restarting it.
     *
     * @return whether the animation changed
     */
    public boolean switchTo(Animation animation) {
        if (animation == null || animation == current) {
            return false;
        }
        current = animation;
        index = 0;
        wait = 0;
        // UnitShowAnimation changes CurrAnim, Anim and Wait, but it does not
        // clear Anim.Unbreakable.  Usually that is
        // immaterial because changing an animation while it is committed is
        // forbidden.  IsWaiting is the deliberate exception: it backs the
        // committed animation up and plays Still over it while unit.Wait is
        // served.  The live Unbreakable bit therefore remains set and keeps
        // HandleUnitAction from popping a replacement order during the nap.
        //
        // human/level08h exposes the bit, not merely the picture.  An
        // axethrower's force order arrives during a walk; its one-cycle wait
        // temporarily selects Still.  Clearing the bit here popped the new
        // march eight cycles early and let its opening target scan run on the
        // final beat of the old step.
        if (!waiting) {
            unbreakable = false;
        }
        return true;
    }

    /**
     * Restores the live instruction cursor written by a ChonkCraft save.
     *
     * <p>Switching to the named animation is not enough for combat: reopening
     * a save in the middle of a siege reload must not replay the attack marker
     * or discard the unbreakable part of the volley.  The index is clamped so
     * an older save remains loadable if a later graphics pack shortens the
     * presentation program.</p>
     */
    public void restore(Animation animation, int index, int wait,
            boolean unbreakable) {
        current = animation;
        this.index = animation == null || animation.size() == 0
                ? 0 : Math.max(0, Math.min(index, animation.size() - 1));
        this.wait = Math.max(0, wait);
        this.unbreakable = unbreakable;
        waiting = false;
        restCurrent = null;
        restIndex = 0;
        restWait = 0;
        restUnbreakable = false;
    }
}
