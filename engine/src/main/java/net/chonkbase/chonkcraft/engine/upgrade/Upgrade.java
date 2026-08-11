package net.chonkbase.chonkcraft.engine.upgrade;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * A researchable improvement, and what it does to the units it applies to.
 *
 * <p>Implements {@code CUpgrade} and {@code CUpgradeModifier} from
 * {@code src/include/upgrade_structs.h}.
 *
 * <p>Warcraft II's upgrades are additive and permanent: researching
 * {@code upgrade-sword1} adds two piercing damage to every footman that
 * player owns and every one they train afterwards. There is no per-unit
 * veterancy, which is why the effect lives on the player rather than on the
 * unit.
 */
public final class Upgrade {

    /** What a modifier can change, mapped to the stat it names in the script. */
    public enum Stat {
        BASIC_DAMAGE,
        PIERCING_DAMAGE,
        ARMOR,
        SIGHT_RANGE,
        ATTACK_RANGE,
        HIT_POINTS,
        SPEED,
        LEVEL;

        /** Resolves a script's stat name, or {@code null}. */
        public static Stat byName(String name) {
            return switch (name) {
                case "BasicDamage" -> BASIC_DAMAGE;
                case "PiercingDamage" -> PIERCING_DAMAGE;
                case "Armor" -> ARMOR;
                case "SightRange" -> SIGHT_RANGE;
                case "AttackRange", "MaxAttackRange" -> ATTACK_RANGE;
                case "HitPoints" -> HIT_POINTS;
                case "Speed" -> SPEED;
                case "Level" -> LEVEL;
                default -> null;
            };
        }
    }

    private final String ident;
    private final Map<UnitType.Resource, Integer> costs = new EnumMap<>(UnitType.Resource.class);
    private final Map<Stat, Integer> changes = new LinkedHashMap<>();
    private final List<String> appliesTo = new ArrayList<>();

    public Upgrade(String ident) {
        this.ident = ident;
    }

    public String ident() {
        return ident;
    }

    /** What it costs to research. */
    public Map<UnitType.Resource, Integer> costs() {
        return costs;
    }

    /** The stat changes it applies, each a signed amount. */
    public Map<Stat, Integer> changes() {
        return changes;
    }

    /** The unit type identifiers it applies to. */
    public List<String> appliesTo() {
        return appliesTo;
    }

    /**
     * The type its subjects become, or null for a plain stat upgrade.
     *
     * <p>{@code CUpgradeModifier::ConvertTo}. One shipped upgrade uses it:
     * {@code upgrade-paladin} is {@code {"apply-to", "unit-knight"},
     * {"convert-to", "unit-paladin"}} ({@code human/upgrade.legacy-declaration:189-190}),
     * so researching it turns every knight the player owns into a paladin,
     * there and then. This was parsed as an unknown stat and dropped, so a
     * player's knights stayed knights however holy they were sworn.
     */
    public String convertTo() {
        return convertTo;
    }

    public void setConvertTo(String ident) {
        this.convertTo = ident;
    }

    private String convertTo;

    /** Whether this upgrade touches a given unit type. */
    public boolean applies(UnitType type) {
        return appliesTo.contains(type.ident());
    }

    /** The change to one stat, or zero. */
    public int change(Stat stat) {
        return changes.getOrDefault(stat, 0);
    }

    @Override
    public String toString() {
        return ident + changes;
    }
}
