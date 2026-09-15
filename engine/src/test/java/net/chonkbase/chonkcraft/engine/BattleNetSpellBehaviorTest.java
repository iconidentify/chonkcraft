package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudUnitTypes;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.spell.SpellCatalog;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Targeting and mana effects checked against BNE 2.02b, SHA-256 b0e914a9cb7d.
 *
 * <p>Controlled-input replays of 0x442830 used the initialized type flags from
 * Human 14 fixture ad0b3da9bf55: 1,100 combinations of type, caster mana and
 * victim mana. Replaying 0x4426a0 with tile sentinels established the complete
 * ordered area scan. Effects, sound and combat were explicit external stubs;
 * these tests exercise the corresponding game behavior, including real damage.
 */
class BattleNetSpellBehaviorTest {
    private static World world() {
        GameMap map = new GameMap(24, 24, new Tileset());
        for (int y = 0; y < 24; y++) {
            for (int x = 0; x < 24; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        world.setSpells(SpellCatalog.generated().spells());
        world.upgrades(0).complete("upgrade-exorcism");
        world.upgrades(0).complete("upgrade-healing");
        world.upgrades(0).complete("upgrade-invisibility");
        world.setAllied(0, 1, false);
        return world;
    }

    private static UnitType type(String ident, int mana) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(100);
        type.setLandUnit(true);
        type.setSpeed(10);
        type.setMana(mana);
        return type;
    }

    private static Unit caster(World world, int mana) {
        Unit caster = world.createUnit(type("unit-paladin", 255), 0, 5, 10);
        caster.setMana(mana);
        return caster;
    }

    @Test
    @DisplayName("exorcism damages every native undead type and leaves living mana users alone")
    void exorcismUsesUndeadBiologyInsteadOfMana() {
        for (int code = 0; code < PudUnitTypes.count(); code++) {
            String ident = PudUnitTypes.name(code);
            if (ident.isEmpty()) {
                continue;
            }
            for (int targetMana : new int[] {0, 200}) {
                World world = world();
                Unit caster = caster(world, 63);
                Unit target = world.createUnit(type(ident, 255), 1, 10, 10);
                target.setMana(targetMana);
                assertTrue(world.castSpell(caster, "spell-exorcism", 10, 10),
                        "a legal position cast must resolve for " + ident);
                boolean undead = code == 11 || code == 21 || code == 51 || code == 55;
                assertEquals(undead ? 85 : 100, target.hitPoints(),
                        ident + " eligibility must be independent of its current mana");
                assertEquals(undead ? 3 : 63, caster.mana(),
                        "only damage to undead spends four mana per hit point: " + ident);
            }
        }
    }

    @Test
    @DisplayName("exorcism reaches the radius-three corner and preserves allies and distant units")
    void exorcismResolvesAnAreaEvenWhenTheClickedTileIsLiving() {
        World world = world();
        Unit caster = caster(world, 80);
        Unit living = world.createUnit(type("unit-mage", 255), 1, 10, 10);
        Unit ally = world.createUnit(type("unit-skeleton", 0), 0, 10, 11);
        Unit edge = world.createUnit(type("unit-skeleton", 0), 1, 13, 13);
        Unit outside = world.createUnit(type("unit-death-knight", 255), 1, 14, 10);
        assertTrue(world.orderCast(caster, "spell-exorcism", living),
                "clicking a living unit must still aim the area spell at its tile");
        assertEquals(80, edge.hitPoints(), "the radius-three corner is inside the native scan");
        assertEquals(100, living.hitPoints(), "a living caster is not an exorcism victim");
        assertEquals(100, ally.hitPoints(), "BNE mode protects an allied undead unit");
        assertEquals(100, outside.hitPoints(), "the fourth tile is outside the area");
        assertEquals(0, caster.mana(), "the eligible corner spends the available mana");
    }

    @Test
    @DisplayName("exorcism pays victims in native south-before-north order and respects invulnerability")
    void exorcismPreservesScanOrderAndDamageProtection() {
        World world = world();
        Unit caster = caster(world, 28);
        Unit south = world.createUnit(type("unit-skeleton", 0), 1, 9, 11);
        south.setHitPoints(3);
        Unit north = world.createUnit(type("unit-death-knight", 255), 1, 9, 9);
        assertTrue(world.castSpell(caster, "spell-exorcism", 10, 10), "the area cast must resolve");
        assertFalse(south.isAlive(), "the south edge spends the first three damage points");
        assertEquals(96, north.hitPoints(), "the north edge receives the remaining four damage");
        assertEquals(0, caster.mana(), "both victims together spend seven times four mana");

        north.setBuff(Unit.Buff.UNHOLY_ARMOR, 100);
        caster.setMana(20);
        assertTrue(world.castSpell(caster, "spell-exorcism", 9, 9), "protected undead still accept the effect");
        assertEquals(96, north.hitPoints(), "native combat protection must block exorcism damage");
        assertEquals(0, caster.mana(), "native exorcism pays even when armor blocks the damage");
    }

    @Test
    @DisplayName("targeted self-cast refusal preserves mana and queued commands")
    void targetedSelfCastRefusalPreservesCommands() {
        for (String ident : List.of("spell-healing", "spell-invisibility")) {
            World world = world();
            Unit caster = caster(world, 255);
            caster.setHitPoints(50);
            CommandApplier commands = new CommandApplier(world, List.of(caster.type()));
            commands.setSpells(List.of(ident));
            assertTrue(commands.apply(GameCommand.move(0, caster.id(), 7, 10)), "the active move must start");
            assertTrue(commands.apply(GameCommand.move(0, caster.id(), 8, 10).withQueued(true)),
                    "the shifted waypoint must be banked");
            var pending = List.copyOf(caster.queuedOrders());
            Unit.Order order = caster.order();
            for (boolean queued : new boolean[] {false, true}) {
                assertFalse(commands.apply(GameCommand.cast(0, caster.id(), caster.id(), 0)
                        .withQueued(queued)), "native targeted self-cast must be refused: " + ident);
                assertEquals(pending, caster.queuedOrders(), "refusal must retain every waypoint");
                assertEquals(order, caster.order(), "refusal must retain the current order");
                assertEquals(255, caster.mana(), "refusal must spend no mana");
                assertEquals(50, caster.hitPoints(), "self healing must not resolve");
                assertFalse(caster.hasBuff(Unit.Buff.INVISIBLE), "self invisibility must not resolve");
            }
        }
    }
}
