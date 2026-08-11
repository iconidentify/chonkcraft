package net.chonkbase.chonkcraft.engine.animation;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The animations one kind of unit has: still, move, attack, death and so on.
 *
 * <p>Implements {@code CAnimations}. Named sets are shared between unit types,
 * which is why they are declared separately and referenced by name.
 */
public final class AnimationSet {

    /** The states a unit can animate in. */
    public enum State {
        STILL,
        MOVE,
        ATTACK,
        DEATH,
        REPAIR,
        HARVEST,
        BUILD,
        TRAIN,
        RESEARCH,
        UPGRADE;

        /** Resolves a key from a {@code DefineAnimations} table, or {@code null}. */
        public static State byKey(String key) {
            for (State state : values()) {
                if (state.name().equalsIgnoreCase(key)) {
                    return state;
                }
            }
            // Harvest animations are declared per resource, as Harvest_wood
            // and the like; they all animate the same way.
            if (key.toLowerCase(java.util.Locale.ROOT).startsWith("harvest")) {
                return HARVEST;
            }
            return null;
        }
    }

    private final String name;
    private final Map<State, Animation> animations = new LinkedHashMap<>();

    public AnimationSet(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    public void put(State state, Animation animation) {
        animations.put(state, animation);
    }

    /** The animation for a state, or {@code null} if this set has none. */
    public Animation get(State state) {
        return animations.get(state);
    }

    /**
     * The animation for a state, falling back to {@link State#STILL}.
     *
     * <p>Not every set defines every state: most buildings have no move
     * animation, and standing still is always a sensible substitute.
     */
    public Animation getOrStill(State state) {
        Animation animation = animations.get(state);
        return animation != null ? animation : animations.get(State.STILL);
    }

    /** Which states this set defines. */
    public java.util.Set<State> states() {
        return animations.keySet();
    }

    @Override
    public String toString() {
        return name + animations.keySet();
    }
}
