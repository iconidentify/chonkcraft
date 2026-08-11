package net.chonkbase.chonkcraft.engine.spell;

import java.util.LinkedHashMap;
import java.util.Map;

/** Every spell the game defines, by identifier. */
public final class SpellSet {

    private final Map<String, Spell> spells = new LinkedHashMap<>();

    public Spell get(String ident) {
        return spells.get(ident);
    }

    /**
     * Finds or creates one.
     *
     * <p>A few spells are declared twice, once per race, and the second
     * declaration refines the first rather than replacing it.
     */
    public Spell getOrCreate(String ident) {
        return spells.computeIfAbsent(ident, Spell::new);
    }

    public Map<String, Spell> all() {
        return spells;
    }

    public int size() {
        return spells.size();
    }
}
