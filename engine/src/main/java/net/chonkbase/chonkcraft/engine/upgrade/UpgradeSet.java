package net.chonkbase.chonkcraft.engine.upgrade;

import java.util.LinkedHashMap;
import java.util.Map;

/** Every upgrade the game defines, by identifier. */
public final class UpgradeSet {

    private final Map<String, Upgrade> upgrades = new LinkedHashMap<>();

    public Upgrade get(String ident) {
        return upgrades.get(ident);
    }

    /** Finds or creates one, since a script defines costs and modifiers separately. */
    public Upgrade getOrCreate(String ident) {
        return upgrades.computeIfAbsent(ident, Upgrade::new);
    }

    public Map<String, Upgrade> all() {
        return upgrades;
    }

    public int size() {
        return upgrades.size();
    }
}
