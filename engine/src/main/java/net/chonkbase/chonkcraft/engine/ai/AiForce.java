package net.chonkbase.chonkcraft.engine.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * A group of units the AI is assembling for a purpose.
 *
 * <p>Implements {@code AiForce} from {@code src/ai}. Warcraft II's computer
 * players do not command units one at a time; they declare a shopping list,
 * wait for it to fill, and then send the whole thing somewhere. That is what a
 * force is, and it is why the AI scripts read as {@code AiForce(1, {...})},
 * {@code AiWaitForce(1)}, {@code AiAttackWithForce(1)}.
 */
public final class AiForce {

    /** Where a force is in its life. */
    public enum State {
        /** Being filled: units are still being trained into it. */
        GATHERING,
        /** Full and waiting for orders. */
        READY,
        /**
         * Launched, but marching to the rally first.
         *
         * <p>{@code AiForceAttackingState::GoingToRallyPoint}: the launch
         * aims the force at a quiet square near its leader, not at the
         * enemy, and the force stands there until everyone is within five
         * squares of it or the sixty-thought wait runs out
         *
         */
        GOING_TO_RALLY,
        /** Sent at the enemy. */
        ATTACKING
    }

    private final int index;
    private final Map<UnitType, Integer> wanted = new LinkedHashMap<>();
    private final List<Unit> members = new ArrayList<>();
    private State state = State.GATHERING;

    /** Whether the force defends its base rather than attacking out. */
    private boolean defending;

    /** Where the force was sent -- the rally first, the enemy after. */
    private int goalX = -1;
    private int goalY = -1;

    /**
     * Thoughts left to wait at the rally: {@code WaitOnRallyPoint}, sixty
     * at every launch ({@code AI_WAIT_ON_RALLY_POINT}, {@code ai_local.h:116}),
     * counted down once a thought while anyone stands within five of it.
     */
    private int waitOnRallyPoint = 60;

    public int goalX() {
        return goalX;
    }

    public int goalY() {
        return goalY;
    }

    public void setGoal(int x, int y) {
        this.goalX = x;
        this.goalY = y;
    }

    public int waitOnRallyPoint() {
        return waitOnRallyPoint;
    }

    public void resetWaitOnRallyPoint() {
        waitOnRallyPoint = 60;
    }

    public void tickWaitOnRallyPoint() {
        if (waitOnRallyPoint > 0) {
            waitOnRallyPoint--;
        }
    }

    public AiForce(int index) {
        this.index = index;
    }

    public int index() {
        return index;
    }

    /** How many of each type this force wants. */
    public Map<UnitType, Integer> wanted() {
        return wanted;
    }

    /** The units currently in it, dead ones pruned by the manager. */
    public List<Unit> members() {
        return members;
    }

    public State state() {
        return state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public boolean defending() {
        return defending;
    }

    public void setDefending(boolean defending) {
        this.defending = defending;
    }

    /** Replaces the shopping list, resetting the force. */
    public void setWanted(Map<UnitType, Integer> types) {
        wanted.clear();
        wanted.putAll(types);
        state = State.GATHERING;
    }

    /**
     * Empties the force, as {@code AiForce::Reset} does.
     *
     * <p>The members always go; the shopping list goes only when asked, which
     * is the difference between {@code AiForce(1, {...}, true)} and
     * {@code AiForce(1, {...})}. The role is deliberately left alone: upstream
     * reads it out and puts it back around the reset, because a force declared
     * to defend keeps defending when its list is rewritten.
     */
    public void reset(boolean types) {
        members.clear();
        if (types) {
            wanted.clear();
        }
        state = State.GATHERING;
    }

    /**
     * Adds or changes one line of the shopping list.
     *
     * <p>The no-reset half of {@code CclAiForce}: an existing type has its
     * count replaced -- not added to -- and a count of nought strikes it off
     * the list altogether.
     */
    public void want(UnitType type, int count) {
        if (count <= 0) {
            wanted.remove(type);
        } else {
            wanted.put(type, count);
        }
    }

    /** How many of a type are still needed. */
    public int shortfall(UnitType type) {
        int have = 0;
        for (Unit member : members) {
            if (aliveInForce(member) && member.type() == type) {
                have++;
            }
        }
        return Math.max(0, wanted.getOrDefault(type, 0) - have);
    }

    /** Whether every type on the list is present. */
    public boolean isComplete() {
        for (UnitType type : wanted.keySet()) {
            if (shortfall(type) > 0) {
                return false;
            }
        }
        return !wanted.isEmpty();
    }

    /**
     * Drops dead members.
     *
     * <p>{@code CUnit::IsAlive} does not ask {@code Removed}: a living worker
     * inside a mine remains in its force and still counts towards its wanted
     * type. Java's general {@code isAlive()} deliberately does ask it because
     * most world selectors need an on-map target, so force membership must use
     * the narrower upstream predicate directly.
     */
    public void prune() {
        members.removeIf(unit -> !aliveInForce(unit));
    }

    private static boolean aliveInForce(Unit unit) {
        return unit.hitPoints() > 0 && !unit.isDying();
    }

    /**
     * The identifiers of its members, in the order the force holds them.
     *
     * <p>For the saved game, which addresses units by id because a force has
     * to name units written later in the file.
     *
     * <p>Deliberately not sorted. The order is the force's own and it is
     * observable: {@link #members()} hands its first unit to the AI as the one
     * that picks the target and the rest follow it, so a force reloaded in a
     * different order attacks a different thing. It is stable across runs
     * because the only thing that ever appends to it walks the world's unit
     * list, which is itself ordered.
     */
    public List<Integer> memberIds() {
        List<Integer> ids = new ArrayList<>(members.size());
        for (Unit member : members) {
            ids.add(member.id());
        }
        return ids;
    }

    /**
     * The shopping list keyed by type identifier, in identifier order.
     *
     * <p>Sorted, unlike the members, because nothing reads this list in order:
     * it is asked "how many of these do you still want". Writing it sorted
     * keeps two saves of the same game byte-identical.
     */
    public java.util.SortedMap<String, Integer> wantedByIdent() {
        java.util.SortedMap<String, Integer> byIdent = new java.util.TreeMap<>();
        for (Map.Entry<UnitType, Integer> entry : wanted.entrySet()) {
            byIdent.put(entry.getKey().ident(), entry.getValue());
        }
        return byIdent;
    }

    /**
     * Puts a saved force back as it stood.
     *
     * <p>One call rather than four setters, and that is the point of it:
     * {@link #setWanted} resets the state to {@code GATHERING} because a script
     * changing the shopping list is starting the force again, so a reader that
     * set the list and then the state would work while one that set them the
     * other way round would silently land every reloaded force back in
     * gathering -- which is precisely the "AI forgot it was mid-assault" this
     * exists to prevent.
     *
     * @param types    the shopping list, by type
     * @param units    the members, in force order, already resolved from ids
     * @param state    where the force had got to
     * @param defends  whether it guards the base rather than attacking out
     */
    public void restore(Map<UnitType, Integer> types, List<Unit> units,
            State state, boolean defends) {
        restore(types, units, state, defends, -1, -1, 60);
    }

    /** Restores the complete live force, including an attack already underway. */
    public void restore(Map<UnitType, Integer> types, List<Unit> units,
            State state, boolean defends, int goalX, int goalY,
            int waitOnRallyPoint) {
        wanted.clear();
        if (types != null) {
            wanted.putAll(types);
        }
        members.clear();
        if (units != null) {
            for (Unit unit : units) {
                if (unit != null) {
                    members.add(unit);
                }
            }
        }
        this.state = state == null ? State.GATHERING : state;
        this.defending = defends;
        this.goalX = goalX;
        this.goalY = goalY;
        this.waitOnRallyPoint = Math.max(0, waitOnRallyPoint);
    }

    /** How many living units are in the force. */
    public int size() {
        prune();
        return members.size();
    }

    @Override
    public String toString() {
        return "force " + index + " " + state + " " + members.size() + "/" + wanted;
    }
}
