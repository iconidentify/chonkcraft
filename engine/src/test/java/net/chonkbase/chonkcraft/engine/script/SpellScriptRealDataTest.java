package net.chonkbase.chonkcraft.engine.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.spell.Spell;
import net.chonkbase.chonkcraft.engine.spell.SpellSet;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Every spell the game ships, against the question nobody asked of it.
 *
 * <p>Three of the twenty-two parsed to an empty effect list and the suite was
 * green: Blizzard, Death and Decay and Raise Dead spent their mana, played
 * their sound and stopped. There were tests over the spell book -- that both
 * races' lists load, that the mana costs are the shipped figures, that a
 * prerequisite names a real upgrade -- and not one of them asked whether a
 * spell did anything, so a spell that did nothing passed all of them.
 *
 * <p>The sweep here is counted as well as walked. A loop that checks whatever
 * it found and finds nothing passes perfectly, which is how three empty spells
 * survive a spell test in the first place.
 */
class SpellScriptRealDataTest {

    private static GameData load() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No authenticated BNE pack configured");
        return new GameData(assets);
    }

    @Test
    @DisplayName("no shipped spell does nothing at all")
    void everySpellHasSomethingToDo() {
        SpellSet spells = load().spells().spells();
        assertTrue(spells.size() >= 20,
                "only " + spells.size() + " spells loaded; the sweep below would prove nothing");

        List<String> idle = spells.all().values().stream()
                .filter(spell -> spell.effects().isEmpty())
                .map(Spell::ident)
                .toList();
        assertEquals(List.of(), idle,
                "these spells cost mana and do nothing: " + idle);
    }

    @Test
    @DisplayName("the three spells that repeat rain missiles on a place")
    void theRepeatingSpellsAreWholeAgain() {
        SpellSet spells = load().spells().spells();

        // Blizzard and Death and Decay are the only two callers of
        // area-bombardment in the game. With their keys read one place out the
        // verb was never seen, so a finished and tested implementation could
        // not be reached from anywhere.
        for (String ident : List.of("spell-blizzard", "spell-death-and-decay")) {
            Spell spell = spells.get(ident);
            assertNotNull(spell, ident + " is not in the spell book");
            assertEquals(Spell.Target.POSITION, spell.target(), ident + " is cast at a place");
            assertEquals(1, spell.effects().size(), ident + " has one action");
            Spell.Effect effect = spell.effects().get(0);
            assertEquals(Spell.EffectKind.AREA_BOMBARDMENT, effect.kind(),
                    ident + " rains missiles");
            assertEquals(5, effect.number("fields", 0), ident + " covers five fields");
            assertEquals(11, effect.number("shards", 0), ident + " drops eleven shards a field");
        }

        Spell raise = spells.get("spell-raise-dead");
        assertNotNull(raise, "spell-raise-dead is not in the spell book");
        assertEquals(Spell.Target.POSITION, raise.target(), "raise dead is cast at a place");
        assertFalse(raise.effects().isEmpty(), "raise dead raises nothing");
        assertEquals(Spell.EffectKind.SUMMON, raise.effects().get(0).kind(),
                "raise dead summons");
        assertEquals("unit-skeleton", raise.effects().get(0).what(),
                "raise dead raises skeletons");
    }

    @Test
    @DisplayName("a spell that names a prerequisite still names it")
    void thePrerequisitesSurviveTheFlag() {
        SpellSet spells = load().spells().spells();

        // depend-upgrade sits after repeat-cast in all three declarations, so
        // it was the first casualty of the shift: the three most expensive
        // spells in the game were castable the moment a caster existed.
        assertEquals("upgrade-blizzard", spells.get("spell-blizzard").dependUpgrade());
        assertEquals("upgrade-death-and-decay",
                spells.get("spell-death-and-decay").dependUpgrade());
        assertEquals("upgrade-raise-dead", spells.get("spell-raise-dead").dependUpgrade());
    }
}
