package net.chonkbase.chonkcraft.engine.upgrade;

import java.util.Locale;
import net.chonkbase.chonkcraft.engine.generated.GeneratedDependencies;
import net.chonkbase.chonkcraft.engine.generated.GeneratedUpgrades;
import net.chonkbase.chonkcraft.engine.unit.UnitType;

/** The native, immutable-at-load technology definitions used by the game. */
public final class UpgradeCatalog {

    private final UpgradeSet upgrades;
    private final DependencyRules dependencies;

    private UpgradeCatalog(UpgradeSet upgrades, DependencyRules dependencies) {
        this.upgrades = upgrades;
        this.dependencies = dependencies;
    }

    /** Reconstructs the runtime DTOs from the committed declarative tables. */
    public static UpgradeCatalog generated() {
        UpgradeSet upgrades = new UpgradeSet();
        for (GeneratedUpgrades.Row row : GeneratedUpgrades.ROWS) {
            Upgrade upgrade = upgrades.getOrCreate(row.ident());
            for (var entry : row.costs().entrySet()) {
                UnitType.Resource resource = UnitType.Resource.byName(entry.getKey());
                if (resource != null) {
                    upgrade.costs().put(resource, entry.getValue());
                }
            }
            for (var entry : row.changes().entrySet()) {
                Upgrade.Stat stat = Upgrade.Stat.valueOf(
                        entry.getKey().toUpperCase(Locale.ROOT));
                upgrade.changes().put(stat, entry.getValue());
            }
            upgrade.appliesTo().addAll(row.appliesTo());
            upgrade.setConvertTo(row.convertTo());
        }

        DependencyRules dependencies = new DependencyRules();
        for (var entry : GeneratedDependencies.RULES.entrySet()) {
            dependencies.define(entry.getKey(), entry.getValue());
        }
        return new UpgradeCatalog(upgrades, dependencies);
    }

    public UpgradeSet upgrades() {
        return upgrades;
    }

    public DependencyRules dependencies() {
        return dependencies;
    }
}
