package net.chonkbase.chonkcraft.engine.spell;

import net.chonkbase.chonkcraft.engine.generated.GeneratedSpells;

/** The native, JAR-resident spell catalog. */
public final class SpellCatalog {

    private final SpellSet spells;

    private SpellCatalog(SpellSet spells) {
        this.spells = spells;
    }

    public static SpellCatalog generated() {
        SpellSet spells = new SpellSet();
        for (GeneratedSpells.Row row : GeneratedSpells.ROWS) {
            Spell spell = spells.getOrCreate(row.ident());
            spell.setName(row.name());
            spell.setManaCost(row.manaCost());
            spell.setRange(row.range());
            spell.setTarget(Spell.Target.valueOf(row.target()));
            spell.setSoundWhenCast(row.soundWhenCast());
            spell.setDependUpgrade(row.dependUpgrade());
            spell.setOrganicOnly(row.organicOnly());
            spell.setAllowBuildings(row.allowBuildings());
            spell.setAutoCastable(row.autoCastable());
            for (GeneratedSpells.EffectRow effect : row.effects()) {
                spell.effects().add(new Spell.Effect(
                        effectKind(row.ident(), effect),
                        effect.what(), effect.amount(), effect.args()));
            }
        }
        return new SpellCatalog(spells);
    }

    public SpellSet spells() {
        return spells;
    }

    /**
     * Gives the four callback-shaped retail actions an explicit runtime verb.
     *
     * <p>The immutable catalog preserves their old declaration as {@code OTHER}:
     * two Eye of Kilrogg entries share one callback, Polymorph carries its
     * conversion arguments, and Unholy Armor names its callback only by spell.
     * Leaving that word in the executable effect stream made all four silently
     * spend mana and do nothing. Resolve the declaration once here so World
     * never needs a spell-name switch or a catch-all callback interpreter.
     */
    private static Spell.EffectKind effectKind(
            String spellIdent, GeneratedSpells.EffectRow effect) {
        if (!"OTHER".equals(effect.kind())) {
            return Spell.EffectKind.valueOf(effect.kind());
        }
        if (spellIdent.startsWith("spell-eye-of-vision")) {
            return Spell.EffectKind.EYE_OF_KILROGG;
        }
        if ("spell-polymorph".equals(spellIdent)) {
            return Spell.EffectKind.POLYMORPH;
        }
        if ("spell-unholy-armor".equals(spellIdent)) {
            return Spell.EffectKind.UNHOLY_ARMOR;
        }
        return Spell.EffectKind.OTHER;
    }
}
