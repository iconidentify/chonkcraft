package net.chonkbase.chonkcraft.engine.upgrade;

import java.util.LinkedHashSet;
import java.util.Set;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/**
 * What one player has researched, and the stats that follow from it.
 *
 * <p>Kept per player rather than per unit because Warcraft II's upgrades are
 * army-wide: a newly trained footman fights at the same strength as a veteran
 * one, provided the same research is done.
 *
 * <p>This is also why unit stats have to be read through here rather than off
 * {@link UnitType} directly. The type holds the base figure and never changes;
 * the effective figure is the base plus whatever the owning player has
 * researched.
 */
public final class UpgradeState {

    private final Set<String> researched = new LinkedHashSet<>();
    private final UpgradeSet upgrades;

    public UpgradeState(UpgradeSet upgrades) {
        this.upgrades = upgrades;
    }

    /** Marks an upgrade as researched. */
    public void complete(String ident) {
        researched.add(ident);
    }

    /** Whether an upgrade has been researched. */
    public boolean has(String ident) {
        return researched.contains(ident);
    }

    /** Everything researched so far. */
    public Set<String> researched() {
        return researched;
    }

    /** The effective value of a stat for a unit type this player owns. */
    public int effective(UnitType type, Upgrade.Stat stat, int base) {
        int total = base;
        for (String ident : researched) {
            Upgrade upgrade = upgrades.get(ident);
            if (upgrade != null && upgrade.applies(type)) {
                total += upgrade.change(stat);
            }
        }
        return total;
    }

    /** Effective basic damage. */
    public int basicDamage(UnitType type) {
        return effective(type, Upgrade.Stat.BASIC_DAMAGE, type.basicDamage());
    }

    /** Effective piercing damage. */
    public int piercingDamage(UnitType type) {
        return effective(type, Upgrade.Stat.PIERCING_DAMAGE, type.piercingDamage());
    }

    /** Effective armour. */
    public int armor(UnitType type) {
        return effective(type, Upgrade.Stat.ARMOR, type.armor());
    }

    /** Effective sight range. */
    public int sightRange(UnitType type) {
        return effective(type, Upgrade.Stat.SIGHT_RANGE, type.sightRange());
    }
}
