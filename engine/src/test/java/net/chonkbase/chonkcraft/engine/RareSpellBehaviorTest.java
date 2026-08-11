package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.spell.Spell;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Retail-backed referee tests for the four callback-shaped spell declarations. */
class RareSpellBehaviorTest {

    private static GameData load() {
        AssetSource source = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(source != null, "No authenticated BNE pack configured");
        return new GameData(source);
    }

    private static World armed(GameData data) {
        GameMap map = new GameMap(32, 32, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.setUnitTypes(data.unitTypes().types());
        world.setSpells(data.spells().spells());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        return world;
    }

    private static Unit caster(World world, GameData data, String type, int x, int y) {
        UnitType unitType = data.unitTypes().types().get(type);
        assertNotNull(unitType, type);
        Unit unit = world.createUnit(unitType, 0, x, y);
        unit.setMana(255);
        return unit;
    }

    @Test
    @DisplayName("the catalog has no callback-shaped no-op spell effects")
    void everyGeneratedEffectHasRuntimeBehavior() {
        for (Spell spell : load().spells().spells().all().values()) {
            for (Spell.Effect effect : spell.effects()) {
                assertTrue(effect.kind() != Spell.EffectKind.OTHER,
                        spell.ident() + " still has an unmodelled effect");
            }
        }
    }

    @Test
    @DisplayName("Eye of Kilrogg is position-targeted through the lockstep command")
    void eyeOfKilroggUsesSelectedPosition() {
        GameData data = load();
        World world = armed(data);
        Unit ogreMage = caster(world, data, "unit-ogre-mage", 5, 5);
        world.upgrades(0).complete("upgrade-eye-of-kilrogg");

        CommandApplier commands = new CommandApplier(world,
                List.copyOf(data.unitTypes().types().values()));
        commands.setSpells(data.spells().spells().all().keySet());
        int spell = commands.indexOfSpell("spell-eye-of-vision");

        assertTrue(commands.apply(GameCommand.castAt(0, ogreMage.id(), 9, 8, spell)));
        Unit eye = world.units().stream()
                .filter(unit -> "unit-eye-of-vision".equals(unit.type().ident()))
                .findFirst().orElseThrow();
        assertEquals(0, eye.player());
        assertEquals(9, eye.tileX());
        assertEquals(8, eye.tileY());
    }

    @Test
    @DisplayName("Polymorph converts the same unit slot into a neutral critter")
    void polymorphConvertsAndNeutralizes() {
        GameData data = load();
        World world = armed(data);
        Unit mage = caster(world, data, "unit-mage", 5, 5);
        Unit victim = world.createUnit(data.unitTypes().types().get("unit-footman"), 1, 7, 5);
        int id = victim.id();
        world.upgrades(0).complete("upgrade-polymorph");

        assertTrue(world.castSpell(mage, "spell-polymorph", victim));
        assertEquals(id, victim.id(), "retail converts the existing unit slot");
        assertEquals("unit-critter", victim.type().ident());
        assertEquals(World.NEUTRAL_PLAYER, victim.player());
        assertEquals(Unit.Order.STILL, victim.order());
    }

    @Test
    @DisplayName("Unholy Armor halves hit points and grants 500 protected cycles")
    void unholyArmorUsesRetailNumbers() {
        GameData data = load();
        World world = armed(data);
        Unit knight = caster(world, data, "unit-death-knight", 5, 5);
        Unit victim = world.createUnit(data.unitTypes().types().get("unit-footman"), 0, 7, 5);
        victim.setHitPoints(55);
        world.upgrades(0).complete("upgrade-unholy-armor");

        assertTrue(world.castSpell(knight, "spell-unholy-armor", victim));
        assertEquals(27, victim.hitPoints());
        assertEquals(500, victim.buff(Unit.Buff.UNHOLY_ARMOR));
    }

    @Test
    @DisplayName("Runes stay armed at all five declared positions")
    void runesPersistAndKeepTheirGeometry() {
        GameData data = load();
        World world = armed(data);
        Unit knight = caster(world, data, "unit-death-knight", 5, 5);
        world.upgrades(0).complete("upgrade-runes");

        assertTrue(world.castSpell(knight, "spell-runes", 10, 10));
        assertEquals(5, world.missiles().size());
        for (int cycle = 0; cycle < 100; cycle++) {
            world.tick();
        }
        var runes = world.missiles().stream()
                .filter(missile -> "missile-rune".equals(missile.type().ident()))
                .toList();
        assertEquals(5, runes.size(), "a 2,000-cycle rune must not expire as a four-frame flash");
        assertEquals(5, runes.stream()
                .map(missile -> missile.tileX() + "," + missile.tileY()).distinct().count());
    }

    @Test
    @DisplayName("Flame Shield orbits its target and pulses against adjacent units")
    void flameShieldPersistsOrbitsAndDamagesAroundTarget() {
        GameData data = load();
        World world = armed(data);
        Unit mage = caster(world, data, "unit-mage", 5, 5);
        Unit protectedUnit = world.createUnit(
                data.unitTypes().types().get("unit-footman"), 0, 8, 8);
        Unit adjacent = world.createUnit(
                data.unitTypes().types().get("unit-footman"), 0, 9, 8);
        world.upgrades(0).complete("upgrade-flame-shield");
        int protectedHp = protectedUnit.hitPoints();
        int adjacentHp = adjacent.hitPoints();

        assertTrue(world.castSpell(mage, "spell-flame-shield", protectedUnit));
        for (int cycle = 0; cycle < 24; cycle++) {
            world.tick();
        }
        assertEquals(5, world.missiles().stream()
                .filter(missile -> "missile-flame-shield".equals(missile.type().ident()))
                .count());
        assertEquals(protectedHp, protectedUnit.hitPoints(), "the ring excludes its center");
        assertTrue(adjacent.hitPoints() < adjacentHp, "the adjacent unit never felt a pulse");
    }

    @Test
    @DisplayName("Death Coil returns its declared damage as life")
    void deathCoilReturnsLife() {
        GameData data = load();
        World world = armed(data);
        Unit knight = caster(world, data, "unit-death-knight", 5, 5);
        Unit victim = world.createUnit(
                data.unitTypes().types().get("unit-footman"), 1, 8, 5);
        knight.setHitPoints(10);
        world.upgrades(0).complete("upgrade-death-coil");

        assertTrue(world.castSpell(knight, "spell-death-coil", victim));
        for (int cycle = 0; cycle < 100 && knight.hitPoints() == 10; cycle++) {
            world.tick();
        }
        assertEquals(Math.min(knight.type().hitPoints(), 60), knight.hitPoints());
    }

    @Test
    @DisplayName("Whirlwind remains live, roams, and deals periodic area damage")
    void whirlwindHasPersistentLifecycle() {
        GameData data = load();
        World world = armed(data);
        Unit knight = caster(world, data, "unit-death-knight", 5, 5);
        Unit nearby = world.createUnit(
                data.unitTypes().types().get("unit-footman"), 0, 10, 10);
        world.upgrades(0).complete("upgrade-whirlwind");
        int before = nearby.hitPoints();

        assertTrue(world.castSpell(knight, "spell-whirlwind", 10, 10));
        var whirlwind = world.missiles().stream()
                .filter(missile -> "missile-whirlwind".equals(missile.type().ident()))
                .findFirst().orElseThrow();
        double startX = whirlwind.x();
        double startY = whirlwind.y();
        for (int cycle = 0; cycle < 110; cycle++) {
            world.tick();
        }
        assertTrue(world.missiles().contains(whirlwind), "an 800-cycle storm expired as a flash");
        assertTrue(whirlwind.x() != startX || whirlwind.y() != startY,
                "the storm never chose and followed a roaming destination");
        assertTrue(nearby.hitPoints() < before, "the storm never produced a damage beat");
    }
}
