package net.chonkbase.chonkcraft.engine.spell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Loads the real spell definitions from scripts/spells.legacy-declaration. */
class SpellRealDataTest {

    private static GameData gameData() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null, "No authenticated BNE pack configured");
        return new GameData(assets);
    }

    @Test
    @DisplayName("both races' spell books load")
    void bothSpellBooksLoad() {
        SpellSet spells = gameData().spells().spells();
        assertTrue(spells.size() >= 15, "expected the full spell list, got " + spells.size());

        for (String ident : List.of(
                "spell-healing", "spell-exorcism", "spell-holy-vision", "spell-blizzard",
                "spell-haste", "spell-slow", "spell-bloodlust", "spell-invisibility",
                "spell-polymorph", "spell-unholy-armor")) {
            assertTrue(spells.get(ident) != null, "missing " + ident);
        }
    }

    @Test
    @DisplayName("spells carry the costs and ranges the data declares")
    void spellsCarryTheirRealNumbers() {
        SpellSet spells = gameData().spells().spells();

        // The shipped figures, not invented expectations.
        assertEquals(6, spells.get("spell-healing").manaCost());
        assertEquals(6, spells.get("spell-healing").range());
        assertEquals(4, spells.get("spell-exorcism").manaCost());
        assertEquals(10, spells.get("spell-exorcism").range());
        assertEquals(200, spells.get("spell-invisibility").manaCost());
        assertEquals(50, spells.get("spell-bloodlust").manaCost());
    }

    @Test
    @DisplayName("healing heals and exorcism harms")
    void theSignOfAnEffectMatchesItsPurpose() {
        SpellSet spells = gameData().spells().spells();

        // The sign is what separates a heal from a curse, and it comes
        // straight from the data rather than from the spell's name.
        int healing = vitalsChange(spells.get("spell-healing"));
        int exorcism = vitalsChange(spells.get("spell-exorcism"));
        assertTrue(healing > 0, "healing should restore hit points, got " + healing);
        assertTrue(exorcism < 0, "exorcism should take them, got " + exorcism);
    }

    private static int vitalsChange(Spell spell) {
        for (Spell.Effect effect : spell.effects()) {
            if (effect.kind() == Spell.EffectKind.ADJUST_VITALS
                    && "hit-points".equals(effect.what())) {
                return effect.amount();
            }
        }
        return 0;
    }

    @Test
    @DisplayName("every spell has a cost or is free on purpose")
    void everySpellIsWellFormed() {
        SpellSet spells = gameData().spells().spells();

        for (Spell spell : spells.all().values()) {
            assertTrue(spell.manaCost() >= 0, spell.ident() + " has a negative mana cost");
            assertTrue(spell.range() >= 0, spell.ident() + " has a negative range");
            // A unit-targeted spell with no range could never be cast.
            if (spell.target() == Spell.Target.UNIT) {
                assertTrue(spell.range() > 0,
                        spell.ident() + " targets a unit but has no range");
            }
        }
    }

    @Test
    @DisplayName("spells naming a prerequisite name a real upgrade")
    void prerequisitesAreRealUpgrades() {
        GameData data = gameData();
        var upgrades = data.upgrades().upgrades();

        for (Spell spell : data.spells().spells().all().values()) {
            if (spell.dependUpgrade().isEmpty()) {
                continue;
            }
            assertTrue(upgrades.get(spell.dependUpgrade()) != null,
                    spell.ident() + " requires '" + spell.dependUpgrade()
                            + "', which is not an upgrade");
        }
    }

    @Test
    @DisplayName("the whole catalog is native and carries no interpreter tables")
    void catalogNeedsNoScriptTreeOrLuaValues() {
        GameData data = gameData();
        SpellSet spells = data.spells().spells();
        assertEquals(22, spells.size());

        for (Spell spell : spells.all().values()) {
            for (Spell.Effect effect : spell.effects()) {
                assertNative(effect.args(), spell.ident());
            }
        }
    }

    private static void assertNative(Object value, String ident) {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            return;
        }
        if (value instanceof java.util.Map<?, ?> map) {
            map.forEach((key, nested) -> assertNative(nested, ident));
        } else if (value instanceof Iterable<?> iterable) {
            iterable.forEach(nested -> assertNative(nested, ident));
        } else {
            org.junit.jupiter.api.Assertions.fail(
                    ident + " retained a non-native value: " + value.getClass().getName());
        }
    }
}
