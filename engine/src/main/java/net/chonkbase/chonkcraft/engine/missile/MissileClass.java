package net.chonkbase.chonkcraft.engine.missile;

/**
 * What kind of thing a projectile is, as {@code Class} in
 * {@code scripts/missiles.legacy-declaration} names it.
 *
 * <p>Implements {@code MissileClass} in {@code src/include/missile.h} and the
 * name table. Upstream gives each
 * class its own {@code Action}, and the differences are both in how the thing
 * moves and in how its animation runs:
 *
 * <ul>
 *   <li>The travelling classes -- point-to-point, point-to-point-with-hit,
 *       point-to-point-bounce and parabolic -- all advance one animation step
 *       per cycle while they are in the air, then hit. Every projectile the
 *       shipped data actually fires is one of these, which is why
 *       {@link Missile#step()} needs only the one progression.</li>
 *   <li>{@link #STAY} does not move at all: it stands where it was made and
 *       runs its animation once. The explosions and impact flashes are these.</li>
 *   <li>{@link #CYCLE_ONCE} runs its animation forwards then backwards and
 *       vanishes. It is the green cross that acknowledges an order.</li>
 *   <li>{@link #HIT} draws no sprite at all: upstream's {@code DrawMissile}
 *       special-cases it and writes the damage figure as text. It has no
 *       {@code File}, so it draws as nothing here.</li>
 * </ul>
 *
 * <p>The stationary classes are all impact effects, and nothing in this implementation
 * creates one yet, so their timing is recorded here rather than implemented.
 */
public enum MissileClass {

    NONE("missile-class-none"),
    POINT_TO_POINT("missile-class-point-to-point"),
    POINT_TO_POINT_WITH_HIT("missile-class-point-to-point-with-hit"),
    POINT_TO_POINT_CYCLE_ONCE("missile-class-point-to-point-cycle-once"),
    POINT_TO_POINT_BOUNCE("missile-class-point-to-point-bounce"),
    STAY("missile-class-stay"),
    CYCLE_ONCE("missile-class-cycle-once"),
    FIRE("missile-class-fire"),
    HIT("missile-class-hit"),
    PARABOLIC("missile-class-parabolic"),
    LAND_MINE("missile-class-land-mine"),
    WHIRLWIND("missile-class-whirlwind"),
    FLAME_SHIELD("missile-class-flame-shield"),
    DEATH_COIL("missile-class-death-coil"),
    TRACER("missile-class-tracer"),
    CLIP_TO_TARGET("missile-class-clip-to-target"),
    CONTINUOUS("missile-class-continious"),
    STRAIGHT_FLY("missile-class-straight-fly");

    private final String ident;

    MissileClass(String ident) {
        this.ident = ident;
    }

    /** The name the scripts write. */
    public String ident() {
        return ident;
    }

    /**
     * The class a script named, or {@link #POINT_TO_POINT} for anything
     * unrecognised.
     *
     * <p>Upstream's parser rejects an unknown name outright. Falling back is
     * kinder here: a missile whose class this implementation does not know still flies
     * and is still drawn, rather than taking the whole script down.
     */
    public static MissileClass of(String ident) {
        if (ident != null) {
            for (MissileClass value : values()) {
                if (value.ident.equals(ident)) {
                    return value;
                }
            }
        }
        return POINT_TO_POINT;
    }

    /** Whether this kind crosses the ground rather than standing where it was made. */
    public boolean travels() {
        return switch (this) {
            case POINT_TO_POINT, POINT_TO_POINT_WITH_HIT, POINT_TO_POINT_CYCLE_ONCE,
                    POINT_TO_POINT_BOUNCE, PARABOLIC, TRACER, STRAIGHT_FLY,
                    CLIP_TO_TARGET, DEATH_COIL, HIT -> true;
            default -> false;
        };
    }
}
