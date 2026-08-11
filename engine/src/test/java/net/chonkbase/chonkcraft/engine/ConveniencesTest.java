package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Rally points, alliances, shared vision and autocast.
 *
 * <p>None of these changes what a unit can do; all of them change how much of
 * the game a player has to do by hand.
 */
class ConveniencesTest {

    private static final String MAP = "campaigns/human/level02h";
    private static final int OX = 6;
    private static final int OY = 16;

    private record Fixture(GameData data, Map<String, UnitType> types) {}

    private static Fixture load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        GameData data = new GameData(install);
        Assumptions.assumeTrue(data.campaignMap(MAP) != null, "no campaign map available");
        return new Fixture(data, data.unitTypes().types());
    }

    private static World world(GameData data) {
        PudMap pud = data.campaignMap(MAP);
        World world = new World(GameMap.from(pud, data.loadTileset(pud.tileset()).tileset()),
                Player.from(pud));
        world.setUpgrades(data.upgrades().upgrades());
        world.setSpells(data.spells().spells());
        world.setMissileTypes(data.missiles().types());
        return world;
    }

    @Test
    @DisplayName("a barracks sends what it trains to its rally point")
    void rallyPointsSendNewUnits() {
        Fixture fixture = load();
        World world = world(fixture.data());
        world.fog().revealAll(0);

        Unit barracks = world.createUnit(fixture.types().get("unit-human-barracks"), 0, OX, OY);
        assertTrue(world.setRallyPoint(barracks, OX + 8, OY));

        // Real supply: orderTrain recomputes it, so setting a number by hand
        // is overwritten before it is read.
        world.createUnit(fixture.types().get("unit-town-hall"), 0, OX + 3, OY + 3);
        for (int i = 0; i < 3; i++) {
            world.createUnit(fixture.types().get("unit-farm"), 0, OX + 3 + i * 3, OY + 7);
        }
        world.player(0).set(UnitType.Resource.GOLD, 9999);
        world.player(0).set(UnitType.Resource.WOOD, 9999);
        world.recalculateSupply();

        assertTrue(world.orderTrain(barracks, fixture.types().get("unit-footman")));
        Unit trained = null;
        for (int i = 0; i < 4000 && trained == null; i++) {
            world.tick();
            for (Unit unit : world.units()) {
                if (unit.type() != null && "unit-footman".equals(unit.type().ident())) {
                    trained = unit;
                }
            }
        }
        assertNotNull(trained, "nothing was trained");

        int startX = trained.tileX();
        for (int i = 0; i < 2000; i++) {
            world.tick();
        }
        assertTrue(trained.tileX() > startX,
                "it stood in the doorway instead of walking to the rally point");
    }

    @Test
    @DisplayName("allies are not enemies, in the direction the alliance was declared")
    void alliancesHoldFire() {
        Fixture fixture = load();
        World world = world(fixture.data());
        assertTrue(world.isEnemyPlayer(0, 1), "players start hostile");

        // Declared one way at a time, because that is how it is offered: until
        // it is returned, only one side holds fire.
        world.setAllied(0, 1, true);
        assertFalse(world.isEnemyPlayer(0, 1));
        assertTrue(world.isEnemyPlayer(1, 0), "the offer has not been returned");

        world.setAllied(1, 0, true);
        assertFalse(world.isEnemyPlayer(1, 0));
        assertTrue(world.isAllied(0, 0), "a player is always their own ally");
    }

    @Test
    @DisplayName("an ally's sight is shared once it is offered")
    void visionIsShared() {
        Fixture fixture = load();
        World world = world(fixture.data());
        world.createUnit(fixture.types().get("unit-footman"), 1, 30, 20);

        assertFalse(world.isVisibleTo(0, 30, 20), "nothing is shared yet");
        world.setSharedVision(0, 1, true);
        assertTrue(world.isVisibleTo(0, 30, 20), "the ally's ground stayed dark");
        // The fog itself is untouched: sharing is asked, not merged, so each
        // player's own reference counts stay correct.
        assertFalse(world.fog().isVisible(0, 30, 20));
    }

    @Test
    @DisplayName("an allied unit is not attacked on sight")
    void alliesAreNotTargets() {
        Fixture fixture = load();
        World world = world(fixture.data());
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        world.setAllied(0, 1, true);
        world.setAllied(1, 0, true);

        Unit ours = world.createUnit(fixture.types().get("unit-footman"), 0, OX, OY);
        Unit theirs = world.createUnit(fixture.types().get("unit-grunt"), 1, OX + 1, OY);
        for (int i = 0; i < 300; i++) {
            world.tick();
        }
        assertEquals(ours.type().hitPoints(), ours.hitPoints(), "an ally attacked us");
        assertEquals(theirs.type().hitPoints(), theirs.hitPoints(), "we attacked an ally");
    }

    @Test
    @DisplayName("a caster with autocast spends its own mana")
    void autocastFires() {
        Fixture fixture = load();
        World world = world(fixture.data());
        world.fog().revealAll(0);
        world.fog().revealAll(1);

        Unit mage = world.createUnit(fixture.types().get("unit-mage"), 0, OX, OY);
        // Mana is a unit variable rather than a plain field: the unit files
        // enable it and spells.legacy-declaration says how big the pool is.
        assertTrue(mage.isCaster(), "a mage should have a mana pool");
        assertTrue(mage.mana() > 0);
        // Filled deliberately. A mage is trained on the 84 its variable
        // declares and Fireball costs 100, so a fresh one cannot cast at all
        // until it has regenerated for half a minute -- see
        // CasterStartingManaTest. What is under test here is that autocasting
        // spends the pool, not how full it starts.
        mage.setMana(mage.type().mana());

        Unit victim = world.createUnit(fixture.types().get("unit-grunt"), 1, OX + 3, OY);
        assertNotNull(victim);
        // Fireball is gated on research, in the game and here.
        world.upgrades(0).complete("upgrade-fireball");
        assertTrue(world.setAutoCast(mage, "spell-fireball"));

        int before = mage.mana();
        for (int i = 0; i < 400; i++) {
            world.tick();
        }
        assertTrue(mage.mana() < before, "it never cast: mana is still " + mage.mana());
    }

    @Test
    @DisplayName("autocast is off unless it is asked for, and refuses nonsense")
    void autocastIsDeliberate() {
        Fixture fixture = load();
        World world = world(fixture.data());
        Unit mage = world.createUnit(fixture.types().get("unit-mage"), 0, OX, OY);
        assertEquals(null, mage.autoCast(), "a caster does not spend its pool unasked");

        assertFalse(world.setAutoCast(mage, "spell-that-does-not-exist"));
        Unit footman = world.createUnit(fixture.types().get("unit-footman"), 0, OX + 2, OY);
        assertFalse(world.setAutoCast(footman, "spell-fireball"),
                "a footman has nothing to cast with");
    }
}
