package net.chonkbase.chonkcraft.engine.spell;

import java.util.ArrayList;
import java.util.List;

/**
 * A castable spell.
 *
 * <p>Implements {@code SpellType} from {@code src/include/spells.h}, filled by
 * {@code DefineSpell}:
 *
 * <pre>
 *   DefineSpell("spell-healing",
 *     "showname", "Healing", "manacost", 6, "range", 6, "target", "unit",
 *     "action", {{"adjust-vitals", "hit-points", 1}},
 *     "condition", {"organic", "only", "Building", "false"},
 *     "sound-when-cast", "healing",
 *     "depend-upgrade", "upgrade-healing")
 * </pre>
 *
 * <p>Effects are a list rather than a single verb because most spells do
 * several things at once: healing restores hit points <em>and</em> spawns the
 * sparkle that tells you it worked.
 */
public final class Spell {

    /** What a spell can be aimed at. */
    public enum Target {
        /** No aiming; it affects the caster. */
        SELF,
        /** A specific unit. */
        UNIT,
        /** A map square. */
        POSITION;

        public static Target byName(String name) {
            return switch (name) {
                case "unit" -> UNIT;
                case "position" -> POSITION;
                default -> SELF;
            };
        }
    }

    /** What an effect does. */
    public enum EffectKind {
        /** Change hit points or mana by an amount. */
        ADJUST_VITALS,
        /** Set a temporary state such as bloodlust or invisibility. */
        ADJUST_VARIABLE,
        /** Summon a unit. */
        SUMMON,
        /** Launch a missile. */
        SPAWN_MISSILE,
        /** Damage everything in an area. */
        AREA_ADJUST_VITALS,
        /** Blow up everything within a radius, terrain included. */
        DEMOLISH,
        /** Rain missiles over a patch of ground. */
        AREA_BOMBARDMENT,
        /** Reveal terrain. */
        REVEAL,
        /** Create the flying Eye of Kilrogg at a map position. */
        EYE_OF_KILROGG,
        /** Turn a living unit into a neutral critter. */
        POLYMORPH,
        /** Halve a unit's life and make it invulnerable for 500 cycles. */
        UNHOLY_ARMOR,
        /** Anything the implementation does not model yet. */
        OTHER
    }

    /**
     * One thing a spell does.
     *
     * <p>The arguments are named, and that is the whole of why this record
     * carries a map. A spell action in {@code spells.legacy-declaration} is a verb followed by
     * keyword and value pairs, in whatever order the author wrote them, and the
     * port read {@code parts.get(1)} and {@code parts.get(2)} as though they
     * were fixed positions. For {@code {"demolish", "range", 3, "damage", 400}}
     * that gave {@code what="range"} and {@code amount=3}, the four hundred was
     * at index four and was never looked at, and the result was a demolition
     * squad that <em>healed</em> everything within a tile for three hit points.
     *
     * @param kind   what sort of effect
     * @param what   the stat or unit type it names, for the verbs whose first
     *               keyword is the subject of the sentence
     * @param amount that keyword's value
     * @param args   every keyword the action declared, by name
     */
    public record Effect(EffectKind kind, String what, int amount,
            java.util.Map<String, Object> args) {

        public Effect(EffectKind kind, String what, int amount) {
            this(kind, what, amount, java.util.Map.of());
        }

        /** A numeric argument, or {@code fallback} if the action omitted it. */
        public int number(String key, int fallback) {
            Object value = args.get(key);
            return value instanceof Number number ? number.intValue() : fallback;
        }

        /** A named argument, or {@code fallback} if the action omitted it. */
        public String text(String key, String fallback) {
            Object value = args.get(key);
            return value instanceof String text ? text : fallback;
        }

        /** Whether a valueless keyword such as {@code require-corpse} was written. */
        public boolean flag(String key) {
            return Boolean.TRUE.equals(args.get(key));
        }
    }

    private final String ident;
    private String name = "";
    private int manaCost;
    private int range;
    private Target target = Target.SELF;
    private String soundWhenCast = "";
    private String dependUpgrade = "";
    private final List<Effect> effects = new ArrayList<>();

    /** Whether the spell may only be aimed at organic units. */
    private boolean organicOnly;

    /** Whether it may be aimed at buildings. */
    private boolean allowBuildings = true;

    public Spell(String ident) {
        this.ident = ident;
    }

    public String ident() {
        return ident;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int manaCost() {
        return manaCost;
    }

    public void setManaCost(int manaCost) {
        this.manaCost = manaCost;
    }

    public int range() {
        return range;
    }

    public void setRange(int range) {
        this.range = range;
    }

    public Target target() {
        return target;
    }

    public void setTarget(Target target) {
        this.target = target;
    }

    public String soundWhenCast() {
        return soundWhenCast;
    }

    public void setSoundWhenCast(String soundWhenCast) {
        this.soundWhenCast = soundWhenCast;
    }

    /** The upgrade that must be researched first, or {@code ""}. */
    public String dependUpgrade() {
        return dependUpgrade;
    }

    public void setDependUpgrade(String dependUpgrade) {
        this.dependUpgrade = dependUpgrade;
    }

    public List<Effect> effects() {
        return effects;
    }

    public boolean organicOnly() {
        return organicOnly;
    }

    public void setOrganicOnly(boolean organicOnly) {
        this.organicOnly = organicOnly;
    }

    public boolean allowBuildings() {
        return allowBuildings;
    }

    public void setAllowBuildings(boolean allowBuildings) {
        this.allowBuildings = allowBuildings;
    }

    /**
     * Whether the player may set this spell to cast itself.
     *
     * <p>Only the spells whose declaration carries an {@code autocast} clause:
     * {@code DoClicked_SpellCast} refuses the toggle outright for the rest and
     * plays the error sound. Blizzard and death and decay are among the rest,
     * which is as it should be -- neither picks a target, and a mage laying
     * blizzards down of its own accord would be a menace to its own side.
     */
    private boolean autoCastable;

    public boolean autoCastable() {
        return autoCastable;
    }

    public void setAutoCastable(boolean autoCastable) {
        this.autoCastable = autoCastable;
    }

    @Override
    public String toString() {
        return ident + " (" + manaCost + " mana, range " + range + ")";
    }
}
