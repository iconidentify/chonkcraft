package net.chonkbase.chonkcraft.engine.missile;

/**
 * A kind of projectile, as {@code scripts/missiles.legacy-declaration} declares it.
 *
 * <p>Implements {@code MissileType}. The
 * shipped file defines 35 of these and this implementation read none of them until now,
 * which is why every attack landed instantly and a catapult hit exactly one
 * unit for single-target damage.
 *
 * @param ident         the name units refer to, such as {@code missile-arrow}
 * @param sprite        the sprite path, or null for an invisible missile
 * @param missileClass  the {@code Class} field, which decides how it flies and
 *                      how its animation runs; see {@link MissileClass}
 * @param frameWidth    one frame's width in the sheet
 * @param frameHeight   one frame's height
 * @param frames        how many frames the sheet holds in total, facings
 *                      included -- not how many steps the animation has
 * @param directions    the legacy direction-table size; directional Warcraft
 *                      II missiles declare nine entries for the eight compass
 *                      headings plus the repeated endpoint
 * @param speed         pixels travelled per step
 * @param sleep         cycles between steps; larger is slower
 * @param range         the splash radius in tiles, one meaning single target
 * @param splashFactor  how sharply damage falls off with distance
 * @param drawLevel     what it draws over
 * @param impactMissile a missile spawned where this one lands, or null
 * @param impactSound   a sound played on impact, or null
 * @param canHitOwner   whether it can damage the unit that fired it
 * @param numBounces    how many times it bounces onward, or zero
 * @param damage        a fixed damage value, or zero to use the firer's stats
 * @param correctSplashDamage {@code CorrectSphashDamage}: whether the splash is
 *                      confined to things that move the way the shot's own
 *                      target does, so a land explosion cannot catch aircraft
 * @param firedSound    a sound played where the shot leaves, or null
 * @param damageRandom  the bound of the {@code Rand(n)} term in {@code Damage},
 *                      or zero when the declared damage is a plain number; see
 *                      {@link #damageAt(int)}
 * @param blizzardSpeed the speed a bombardment staggers its shards by, or zero
 *                      to stagger them by {@code speed}; see {@link #shardSpeed()}
 */
public record MissileType(String ident, String sprite, MissileClass missileClass,
        int frameWidth, int frameHeight,
        int frames, int directions, int speed, int sleep, int range, int splashFactor,
        int drawLevel, String impactMissile, String impactSound, boolean canHitOwner,
        int numBounces, int damage, boolean correctSplashDamage,
        String firedSound, int damageRandom, int blizzardSpeed) {

    /**
     * The form every caller used before {@code FiredSound}, {@code Rand} damage
     * and {@code BlizzardSpeed} were read.
     *
     * <p>Kept so a test that builds a missile by hand does not have to name
     * three fields it is not testing.
     */
    public MissileType(String ident, String sprite, MissileClass missileClass,
            int frameWidth, int frameHeight,
            int frames, int directions, int speed, int sleep, int range, int splashFactor,
            int drawLevel, String impactMissile, String impactSound, boolean canHitOwner,
            int numBounces, int damage, boolean correctSplashDamage) {
        this(ident, sprite, missileClass, frameWidth, frameHeight, frames, directions,
                speed, sleep, range, splashFactor, drawLevel, impactMissile, impactSound,
                canHitOwner, numBounces, damage, correctSplashDamage, null, 0, 0);
    }

    /** As above, plus the two fields a combat test usually does want to name. */
    public MissileType(String ident, String sprite, MissileClass missileClass,
            int frameWidth, int frameHeight,
            int frames, int directions, int speed, int sleep, int range, int splashFactor,
            int drawLevel, String impactMissile, String impactSound, boolean canHitOwner,
            int numBounces, int damage, boolean correctSplashDamage,
            String firedSound, int damageRandom) {
        this(ident, sprite, missileClass, frameWidth, frameHeight, frames, directions,
                speed, sleep, range, splashFactor, drawLevel, impactMissile, impactSound,
                canHitOwner, numBounces, damage, correctSplashDamage, firedSound,
                damageRandom, 0);
    }

    /**
     * The speed a bombardment measures its stagger by.
     *
     * <p>{@code Spell_AreaBombardment::Cast} prefers {@code BlizzardSpeed} over
     * {@code Speed} for the shard delay and nothing else in the engine reads
     * it. Only {@code missile-blizzard} sets it, at four against its travel
     * speed of sixteen, so honouring it is the difference between eleven shards
     * arriving over three seconds and all of them arriving in under one --
     * between a squall and a flash.
     */
    public int shardSpeed() {
        return blizzardSpeed > 0 ? blizzardSpeed : Math.max(1, speed);
    }

    /**
     * Whether the type declares damage of its own at all.
     *
     * <p>{@code MissileHitsGoal} tests {@code missile.Type->Damage} first and
     * prefers it over both the spell's direct figure and the firer's stats, so
     * this is the question that decides which of the three applies. Only
     * {@code missile-blizzard} and {@code missile-death-and-decay} answer yes,
     * and both do it with {@code Rand(10)} -- which is why a plain
     * {@code damage != 0} test would have said no for exactly the two missiles
     * the field exists for.
     */
    public boolean declaresDamage() {
        return damage != 0 || damageRandom > 0;
    }

    /**
     * The damage this type declares, given a draw from the simulation's own
     * generator.
     *
     * <p>Upstream keeps {@code Damage} as a {@code NumberDesc} -- an
     * expression, not a number -- and evaluates it once per unit struck through
     * {@code CalculateDamage}. {@code Damage = Rand(10)} therefore means "nought
     * to nine, freshly rolled every time a shard lands", which is the whole of
     * what a blizzard does: fifty-five shards each rolling their own figure.
     * Parsing it as a number gave zero, so both bombardment spells did nothing
     * even once they were cast.
     *
     * @param draw a value from {@code World.syncRand(damageRandom())}, passed in
     *             rather than drawn here so the missile package stays free of
     *             the simulation's generator
     */
    public int damageAt(int draw) {
        return damage + draw;
    }

    /** The name a unit gives when it has no projectile and strikes directly. */
    public static final String NONE = "missile-none";

    /** Whether this is the do-nothing missile every melee unit names. */
    public boolean isNone() {
        return NONE.equals(ident) || speed <= 0;
    }

    /**
     * Whether a hit spreads to neighbours.
     *
     * <p>A range of one covers only the square struck, which is what most
     * arrows and axes do. Anything larger is a catapult or a cannon.
     */
    public boolean splashes() {
        return range > 1;
    }

    /**
     * How many facings the sheet actually stores.
     *
     * <p>Half the declared directions plus one. Warcraft II draws eight
     * headings from five pictures: north, north-east, east, south-east and
     * south are drawn, and the western three are those flipped. {@code Flip}
     * is on for every shipped missile, so every sheet is cut this way.
     *
     * <p>This is also the sheet's row width. {@code missiles/arrow.png} decodes
     * to 200 by 40 -- five 40-pixel frames on one row, one per stored facing.
     */
    public int storedFacings() {
        return headingCount() / 2 + 1;
    }

    /**
     * How many distinct headings a projectile may actually carry.
     *
     * <p>BNE's legacy declarations say {@code NumDirections = 9} for every
     * directional projectile.  Nine is the size of the direction lookup
     * table, whose final entry repeats north at the end of the circle; it is
     * not a ninth compass pose.  The executable writes an unsigned facing at
     * projectile {@code +0x0a}.  Across the authenticated 52-case corpus (984
     * projectile lifetimes, 638 with a non-zero aim), the field contains only
     * {@code 0..7} and never {@code 8}.  The five stored pictures are north,
     * north-east, east, south-east and south; the other three are mirrors.
     *
     * <p>Treating the declaration as nine headings made the sectors 28 units
     * wide instead of 32.  South, south-west and west could therefore select
     * the adjacent or duplicated sheet cell, which was especially visible on
     * arrows and tower fire.
     */
    public int headingCount() {
        if (directions <= 1) {
            return 1;
        }
        return directions == 9 ? 8 : directions;
    }

    /**
     * How many steps the animation runs for.
     *
     * <p>{@code Frames} counts every picture in the sheet, facings included,
     * so an arrow's five frames are one step in five directions and not a
     * five-step animation. Dividing gives the steps:
     * {@code missiles/catapult_rock.png} has fifteen frames over five facings,
     * so a boulder tumbles through three. An explosion declares one direction
     * and twenty frames, so it burns through all twenty.
     */
    public int animationSteps() {
        return Math.max(1, frames / storedFacings());
    }

    /**
     * How much to divide damage by for something {@code distance} tiles from
     * the point of impact.
     *
     * <p>The rule upstream uses: the distance times the type's splash factor,
     * except that the square hit directly always takes the full amount. A
     * factor of zero would divide by nothing, so it is treated as one.
     */
    public int falloffAt(int distance) {
        if (distance <= 0) {
            return 1;
        }
        return Math.max(1, distance * Math.max(1, splashFactor));
    }
}
