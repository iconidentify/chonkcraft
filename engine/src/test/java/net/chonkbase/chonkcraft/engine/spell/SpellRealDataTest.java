package net.chonkbase.chonkcraft.engine.spell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
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
    @DisplayName("real exorcism finds undead beside a living target without harming that target")
    void realExorcismFindsUndeadBesideALivingTarget() {
        var data = gameData();
        var map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        var world = new World(map);
        data.configureWorld(world, PudMap.Tileset.FOREST);
        world.upgrades(0).complete("upgrade-exorcism");
        var paladin = world.createUnit(data.unitTypes().types().get("unit-paladin"), 0, 5, 10);
        var mage = world.createUnit(data.unitTypes().types().get("unit-mage"), 1, 10, 10);
        var skeleton = world.createUnit(data.unitTypes().types().get("unit-skeleton"), 1, 11, 10);
        int livingHealth = mage.hitPoints();
        int undeadHealth = skeleton.hitPoints();
        paladin.setMana(20);
        assertTrue(world.orderCast(paladin, "spell-exorcism", mage), "the real paladin must cast at the selected tile");
        assertEquals(livingHealth, mage.hitPoints(), "mana capacity does not make the mage undead");
        assertEquals(undeadHealth - 5, skeleton.hitPoints(), "the neighbouring skeleton takes five damage");
        assertEquals(0, paladin.mana(), "the real spell charges four mana per point of damage");
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
