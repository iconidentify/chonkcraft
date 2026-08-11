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
                        Spell.EffectKind.valueOf(effect.kind()),
                        effect.what(), effect.amount(), effect.args()));
            }
        }
        return new SpellCatalog(spells);
    }

    public SpellSet spells() {
        return spells;
    }
}
