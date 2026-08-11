package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.spell.Spell;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A mage leaves the tower with a third of a mana bar, not a full one.
 *
 * <p>{@code DefineVariables("Mana", {Max = 255, Value = 84...})} in
 * {@code scripts/spells.legacy-declaration} declares a starting value distinct from the
 * maximum, and copies the whole variable onto a new unit,
 * so a caster is born on the {@code Value}. This implementation read the {@code Max} for
 * both, and a freshly trained mage could cast Polymorph -- 190 mana -- on the
 * spot.
 *
 * <p>The declaration reaching the roster at all was the other half of it. The
 * roster loader ran {@code scripts/units.legacy-declaration} alone, so {@code DefineVariables}
 * was bound in one interpreter and called in another and never executed;
 * {@code GameData.unitTypes} now runs the spell script first, as
 * {@code legacyEngine.legacy-declaration} does.
 */
class CasterStartingManaTest {

    private static GameData load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        return new GameData(install);
    }

    @Test
    @DisplayName("a newly made mage holds the mana the scripts declare, not its maximum")
    void aFreshMageStartsPartFull() {
        GameData data = load();
        UnitType mage = data.unitTypes().types().get("unit-mage");
        assertNotNull(mage, "no mage in the roster; this test proves nothing");
        assertEquals(255, mage.mana(), "the pool the shipped scripts declare");

        Unit fresh = new Unit(1, mage, 0, 10, 10);
        assertEquals(84, fresh.mana(),
                "a mage is trained holding the Value its variable declares, not its Max");
        assertTrue(fresh.mana() < mage.mana(),
                "and the bar is therefore not full when the mage appears");
    }

    /**
     * The behaviour the number decides, rather than the number.
     *
     * <p>Polymorph is the reason this matters: it costs more than a caster
     * starts with, so it is the first spell a mage has to wait for. A full bar
     * made it available the moment the mage stepped out of the tower.
     */
    @Test
    @DisplayName("a freshly trained mage cannot cast Polymorph until it has regenerated")
    void aFreshMageCannotCastItsExpensiveSpell() {
        GameData data = load();
        UnitType mage = data.unitTypes().types().get("unit-mage");
        Assumptions.assumeTrue(mage != null && mage.mana() > 0, "no caster in the roster");
        Spell polymorph = data.spells().spells().get("spell-polymorph");
        Assumptions.assumeTrue(polymorph != null, "no polymorph in the spell set");
        assertTrue(polymorph.manaCost() > 84,
                "polymorph costs " + polymorph.manaCost() + "; it has to be dearer than a"
                        + " mage's starting pool or this proves nothing");

        Unit fresh = new Unit(2, mage, 0, 10, 10);
        assertFalse(fresh.mana() >= polymorph.manaCost(),
                "a mage that has just been trained can pay for Polymorph, which is the bug:"
                        + " it holds " + fresh.mana() + " and the spell costs "
                        + polymorph.manaCost());
    }

    /**
     * A type with one figure for its pool and no second one keeps starting
     * full. Every hand-built fixture in the suite is that shape, and so is the
     * map editor; the fallback is what stops them all starting empty.
     */
    @Test
    @DisplayName("a type that declares no starting value starts on its maximum")
    void anUndeclaredStartIsAFullPool() {
        UnitType type = new UnitType("unit-test-caster");
        type.setMana(200);
        assertEquals(200, type.manaStart());
        assertEquals(200, new Unit(3, type, 0, 0, 0).mana());
    }
}
