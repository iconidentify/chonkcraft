package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.spell.Spell;
import net.chonkbase.chonkcraft.engine.spell.SpellSet;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Casting a spell at something across the field.
 *
 * <p>No spell in the game could be cast. Twenty-three buttons carried the
 * action, their availability was computed correctly, their mana cost was drawn
 * on the icon -- and pressing one printed a tooltip. `World.castSpell` had a
 * single caller, in the command applier, reachable only from a command nothing
 * ever built.
 *
 * <p>It was also only half a capability even once reached: it refuses a caster
 * that is out of range, which is right, but nothing walked the caster into
 * range, so a mage could only ever have hit something already beside it.
 *
 * <p>The test that let this survive asserted that every shipped button's
 * action string appeared in a set of handled actions. "cast-spell" was in the
 * set. It guarded the declaration and not the switch, which is the difference
 * between a list and a behaviour.
 */
class SpellCastingTest {

    private static World plain(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        return new World(map, players);
    }

    private static GameData load() {
        AssetSource source = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(source != null,
                "No Warcraft II data configured. Set CHONKCRAFT_ASSET_PACK or "
                        + "-Dwc2.install.dir=/path/to/game.");
        return new GameData(source);
    }

    private static World armed(GameData data) {
        World world = plain(48);
        world.setUnitTypes(data.unitTypes().types());
        world.setSpells(data.spells().spells());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        return world;
    }

    @Test
    @DisplayName("A caster walks into range and casts")
    void aCasterClosesAndCasts() {
        GameData data = load();
        World world = armed(data);
        UnitType mage = data.unitTypes().types().get("unit-mage");
        UnitType footman = data.unitTypes().types().get("unit-footman");
        assertNotNull(mage);

        // A spell that needs a target and belongs to this caster.
        Spell spell = null;
        for (Spell candidate : data.spells().spells().all().values()) {
            if (candidate.target() == Spell.Target.UNIT && candidate.range() > 0
                    && candidate.dependUpgrade().isEmpty()) {
                spell = candidate;
                break;
            }
        }
        Assumptions.assumeTrue(spell != null, "no targeted spell without a prerequisite");

        Unit caster = world.createUnit(mage, 0, 5, 20);
        Unit victim = world.createUnit(footman, 1, 40, 20);
        caster.setMana(mage.mana());
        int manaBefore = caster.mana();

        // Far out of range: the point of the order is that it closes first.
        assertTrue(caster.distanceTo(victim) > spell.range(),
                "the fixture must start out of range or it proves nothing");
        assertTrue(world.orderCast(caster, spell.ident(), victim), "the order was refused");

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 120; cycle++) {
            world.tick();
            if (caster.mana() < manaBefore) {
                break;
            }
        }
        assertTrue(caster.mana() < manaBefore,
                "the mage never cast: it ended at " + caster.tileX() + "," + caster.tileY()
                        + ", " + caster.distanceTo(victim) + " squares away, with "
                        + caster.mana() + " mana");
    }

    @Test
    @DisplayName("A spell that acts on its caster needs no target")
    void aSelfSpellCastsAtOnce() {
        World world = plain(24);
        SpellSet spellBook = new SpellSet();
        Spell self = spellBook.getOrCreate("spell-self-test");
        self.setTarget(Spell.Target.SELF);
        self.setManaCost(10);
        self.effects().add(new Spell.Effect(Spell.EffectKind.ADJUST_VARIABLE,
                "", 0, java.util.Map.of("Haste", 30)));
        world.setSpells(spellBook);

        UnitType casterType = new UnitType("unit-test-caster");
        casterType.setTileSize(1, 1);
        casterType.setHitPoints(60);
        casterType.setMana(100);
        casterType.setLandUnit(true);

        Unit caster = world.createUnit(casterType, 0, 10, 10);
        caster.setMana(casterType.mana());
        int before = caster.mana();
        assertTrue(world.orderCast(caster, self.ident(), null),
                "a self-cast should not need somebody to point at");
        assertTrue(caster.mana() < before, "the caster did not spend anything");
    }

    @Test
    @DisplayName("Casting costs mana and stops when there is none")
    void manaIsSpentAndRunsOut() {
        GameData data = load();
        World world = armed(data);
        UnitType mage = data.unitTypes().types().get("unit-mage");
        UnitType footman = data.unitTypes().types().get("unit-footman");

        Spell spell = null;
        for (Spell candidate : data.spells().spells().all().values()) {
            if (candidate.target() == Spell.Target.UNIT && candidate.manaCost() > 0
                    && candidate.dependUpgrade().isEmpty()) {
                spell = candidate;
                break;
            }
        }
        Assumptions.assumeTrue(spell != null, "no targeted spell with a cost");

        Unit caster = world.createUnit(mage, 0, 10, 10);
        Unit victim = world.createUnit(footman, 1, 11, 10);
        caster.setMana(0);
        assertFalse(world.castSpell(caster, spell.ident(), victim),
                "a caster with no mana cast anyway");

        caster.setMana(spell.manaCost());
        assertTrue(world.castSpell(caster, spell.ident(), victim));
        assertEquals(0, caster.mana(), "the cost was not taken");
    }

    @Test
    @DisplayName("Healing repeats up to the wound and the available mana")
    void healingUsesAsManyCastsAsManaAllows() {
        GameData data = load();
        World world = armed(data);
        Spell healing = data.spells().spells().get("spell-healing");
        Unit caster = world.createUnit(data.unitTypes().types().get("unit-paladin"), 0, 10, 10);
        Unit patient = world.createUnit(data.unitTypes().types().get("unit-footman"), 0, 11, 10);
        patient.setHitPoints(1);
        caster.setMana(60);
        world.upgrades(0).complete(healing.dependUpgrade());

        assertTrue(world.castSpell(caster, healing.ident(), patient));

        assertEquals(11, patient.hitPoints(),
                "ten affordable one-point casts should heal ten hit points");
        assertEquals(0, caster.mana(), "all ten casts should have been paid for");
    }

    /**
     * Every spell the shipped buttons offer must be one the engine can
     * actually cast. This is the check the old one should have been: it asks
     * whether the thing works, not whether its name appears in a list.
     */
    @Test
    @DisplayName("Every spell a button offers is one the engine knows")
    void everyOfferedSpellExists() {
        GameData data = load();
        var spells = data.spells().spells();
        int offered = 0;
        StringBuilder missing = new StringBuilder();
        for (var button : data.userInterface("summer").buttons().all()) {
            if (!"cast-spell".equals(button.action())) {
                continue;
            }
            offered++;
            if (button.value() == null || spells.get(button.value()) == null) {
                missing.append("\n  ").append(button.value());
            }
        }
        assertTrue(offered > 0, "no spell buttons at all, so this proves nothing");
        assertEquals(0, missing.length(),
                "buttons offer spells the engine does not have:" + missing);
    }
}
