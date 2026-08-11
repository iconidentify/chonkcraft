package net.chonkbase.chonkcraft.engine.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.spell.Spell;
import net.chonkbase.chonkcraft.engine.spell.SpellSet;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The per-action half of {@code IsButtonAllowed}.
 *
 * <p>These are the rules that no script states. {@code scripts/buttons.legacy-declaration}
 * declares the cancel button for every unit in the game with
 * {@code ForUnit = {"*"}}, and it is this check alone that keeps it out of the
 * corner of every panel until there is something to cancel.
 */
class ButtonAvailabilityTest {

    private static World world() {
        GameMap map = new GameMap(30, 30, new Tileset());
        for (int y = 0; y < 30; y++) {
            for (int x = 0; x < 30; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        world.player(0).set(Resource.GOLD, 5000);
        world.player(0).set(Resource.WOOD, 5000);
        return world;
    }

    private static UnitType hall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        type.setSupply(20);
        type.costs().put(Resource.TIME, 1);
        return type;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setDemand(1);
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 400);
        return type;
    }

    private static UnitButton button(String action, String value) {
        return new UnitButton(9, 0, "icon-" + action, action, value, null, List.of(),
                null, "ESC", "CANCEL", List.of("*"));
    }

    private static ButtonAvailability check(World world, Unit unit) {
        return new ButtonAvailability(world, unit, null, false);
    }

    @Test
    @DisplayName("an idle building offers no cancel")
    void nothingToCancel() {
        World world = world();
        Unit building = world.createUnit(hall(), 0, 4, 4);
        ButtonAvailability check = check(world, building);

        assertFalse(check.test(button("cancel-train-unit", null)));
        assertFalse(check.test(button("cancel-upgrade", null)));
        assertFalse(check.test(button("cancel-build", null)));
    }

    @Test
    @DisplayName("a building training something offers another job when queues are enabled")
    void trainingOffersAnotherQueuedOrder() {
        World world = world();
        world.setTrainingQueueEnabled(true);
        Unit building = world.createUnit(hall(), 0, 4, 4);
        world.recalculateSupply();
        assertTrue(world.orderTrain(building, peasant()));
        ButtonAvailability check = check(world, building);

        assertTrue(check.test(button("cancel-train-unit", null)));
        assertTrue(check.test(button("train-unit", "unit-peasant")),
                "ChonkCraft enables the training queue");
    }

    @Test
    @DisplayName("a building going up offers the cancel")
    void constructionOffersCancel() {
        World world = world();
        Unit site = world.createUnit(hall(), 0, 4, 4);
        site.setOrder(Unit.Order.UNDER_CONSTRUCTION);

        assertTrue(check(world, site).test(button("cancel-build", null)));
        assertFalse(check(world, site).test(button("cancel-train-unit", null)));
    }

    @Test
    @DisplayName("only a siege engine can be told to hit empty ground")
    void attackGroundNeedsTheFlag() {
        World world = world();
        UnitType footmanType = peasant();
        footmanType.setCanAttack(true);
        Unit footman = world.createUnit(footmanType, 0, 4, 4);

        assertTrue(check(world, footman).test(button("attack", null)));
        assertFalse(check(world, footman).test(button("attack-ground", null)),
                "attacking and attacking a patch of dirt are different questions");

        UnitType ballistaType = peasant();
        ballistaType.setCanAttack(true);
        ballistaType.setGroundAttack(true);
        Unit ballista = world.createUnit(ballistaType, 0, 8, 8);
        assertTrue(check(world, ballista).test(button("attack-ground", null)));
    }

    @Test
    @DisplayName("an unresearched spell is not castable")
    void spellsWaitOnTheirUpgrade() {
        World world = world();
        SpellSet spells = new SpellSet();
        Spell blizzard = spells.getOrCreate("spell-blizzard");
        blizzard.setDependUpgrade("upgrade-blizzard");
        Spell haste = spells.getOrCreate("spell-haste");
        world.setSpells(spells);

        Unit mage = world.createUnit(peasant(), 0, 4, 4);
        assertFalse(check(world, mage).test(button("cast-spell", "spell-blizzard")));
        assertTrue(check(world, mage).test(button("cast-spell", "spell-haste")),
                "a spell with no dependency is always available");

        world.upgrades(0).complete("upgrade-blizzard");
        assertTrue(check(world, mage).test(button("cast-spell", "spell-blizzard")));
    }

    @Test
    @DisplayName("an empty transport has nothing to unload")
    void unloadNeedsCargo() {
        World world = world();
        UnitType shipType = peasant();
        shipType.setMaxOnBoard(6);
        shipType.canTransport_().add("organic");
        Unit ship = world.createUnit(shipType, 0, 4, 4);

        assertFalse(check(world, ship).test(button("unload", null)));

        Unit passenger = world.createUnit(peasant(), 0, 6, 6);
        ship.cargo().add(passenger);
        assertTrue(check(world, ship).test(button("unload", null)));
    }
}
