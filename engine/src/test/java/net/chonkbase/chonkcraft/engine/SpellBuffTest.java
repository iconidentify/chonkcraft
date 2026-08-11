package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.save.LoadGame;
import net.chonkbase.chonkcraft.engine.save.SaveGame;
import net.chonkbase.chonkcraft.engine.spell.Spell;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The five spells that cost mana and did nothing.
 *
 * <p>Bloodlust, Haste, Slow, Invisibility and Unholy Armour are all written in
 * scripts/spells.legacy-declaration as {@code {"adjust-variable", {Haste = 1000...}}}, and
 * this implementation's {@code World} had them in a switch arm that read, in full,
 * {@code case ADJUST_VARIABLE, REVEAL, OTHER -> { }}. So each of the five
 * checked its range, spent its mana, played its {@code sound-when-cast}, threw
 * its missile, and had no effect on anything. That is three of the mage's six
 * spells, the ogre-mage's signature, and the death knight's hundred-mana
 * defensive.
 *
 * <p>Two separate faults kept it that way, which is why fixing the switch arm
 * alone would not have been enough. The action is written as a keyed table
 * rather than as keyword and value pairs, and {@code SpellScript.readArguments}
 * walks only the String elements of the action list -- so even once the arm
 * did something, the values it needed had never been parsed and every spell
 * would have adjusted nothing by nought.
 *
 * <p>Every test here drives the spell rather than the state where it can, and
 * measures the thing a player would notice: how hard a blow lands, how long a
 * walk takes, whether damage arrives at all. A test that asked whether the
 * timer had been set would have passed the moment {@link Unit#setBuff} existed
 * and said nothing about whether any of it reached the game.
 */
class SpellBuffTest {

    /** Blows struck per measurement; damage is randomised per blow. */
    private static final int BLOWS = 400;

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
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No authenticated BNE pack configured");
        return new GameData(assets);
    }

    private static World armed(GameData data) {
        World world = plain(48);
        world.setUnitTypes(data.unitTypes().types());
        world.setSpells(data.spells().spells());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        return world;
    }

    /**
     * Total damage a footman lands over {@link #BLOWS} blows.
     *
     * <p>The target is healed back to full between blows so that a run cannot
     * end early by killing it, and so that the figure is the sum of what was
     * dealt rather than the one number a corpse can report.
     */
    private static int damageOver(World world, Unit attacker, Unit target) {
        int max = target.type().hitPoints();
        int total = 0;
        for (int blow = 0; blow < BLOWS; blow++) {
            target.setHitPoints(max);
            world.hit(attacker, target);
            total += max - target.hitPoints();
        }
        return total;
    }

    @Test
    @DisplayName("a bloodlusted grunt hits about twice as hard, and the spell is what does it")
    void aBloodlustedAttackerHitsHarder() {
        GameData data = load();
        World world = armed(data);
        UnitType footman = data.unitTypes().types().get("unit-footman");
        assertNotNull(footman, "unit-footman should be in the shipped data");

        Unit plainAttacker = world.createUnit(footman, 0, 5, 5);
        Unit plainTarget = world.createUnit(footman, 1, 6, 5);
        int ordinary = damageOver(world, plainAttacker, plainTarget);

        Unit lusted = world.createUnit(footman, 0, 5, 20);
        Unit victim = world.createUnit(footman, 1, 6, 20);
        lusted.setBuff(Unit.Buff.BLOODLUST, 1000);
        int bloodlusted = damageOver(world, lusted, victim);

        assertTrue(ordinary > 0, "the fixture must land blows at all; it dealt " + ordinary);
        // Not exactly twice: CalculateDamageStats doubles basic and piercing
        // damage *before* taking off the target's armour, and then subtracts a
        // random amount up to half. Against a footman's own armour the doubled
        // figure comes out well above 1.5x and comfortably under 2.5x, and
        // pinning a band rather than a ratio keeps this from being a test of
        // the random stream.
        double ratio = (double) bloodlusted / ordinary;
        assertTrue(ratio > 1.5 && ratio < 2.5, String.format(
                "a bloodlusted footman dealt %d over %d blows against %d without the spell,"
                        + " a ratio of %.2f; Bloodlust doubles basic and piercing damage in"
                        + " CalculateDamageStats (missile.cpp:271-274)",
                bloodlusted, BLOWS, ordinary, ratio));
    }

    @Test
    @DisplayName("unholy armour makes a unit take no damage at all")
    void unholyArmourMakesAUnitInvulnerable() {
        GameData data = load();
        World world = armed(data);
        UnitType footman = data.unitTypes().types().get("unit-footman");

        Unit attacker = world.createUnit(footman, 0, 5, 5);
        Unit target = world.createUnit(footman, 1, 6, 5);

        // The control first, or "it took no damage" proves nothing: a fixture
        // whose attacker cannot reach its target passes this test perfectly.
        int unprotected = damageOver(world, attacker, target);
        assertTrue(unprotected > 0,
                "the fixture must be able to hurt the target; it dealt " + unprotected);

        target.setBuff(Unit.Buff.UNHOLY_ARMOR, 1000);
        int protectedTotal = damageOver(world, attacker, target);
        assertEquals(0, protectedTotal,
                "a unit under Unholy Armour is invulnerable -- HitUnit returns before it"
                        + " applies anything (unit.cpp:2971) -- and this one took "
                        + protectedTotal);
    }

    @Test
    @DisplayName("haste quickens a walk and slow drags it out")
    void hasteAndSlowChangeHowLongAWalkTakes() {
        GameData data = load();
        UnitType footman = data.unitTypes().types().get("unit-footman");

        int ordinary = cyclesToWalk(data, footman, null);
        int hasted = cyclesToWalk(data, footman, Unit.Buff.HASTE);
        int slowed = cyclesToWalk(data, footman, Unit.Buff.SLOW);

        assertTrue(ordinary > 0 && ordinary < 3000,
                "the fixture must actually complete the walk; it took " + ordinary + " cycles");
        assertTrue(hasted < ordinary, String.format(
                "a hasted footman took %d cycles to cross the same ground an ordinary one"
                        + " crossed in %d. CAnimation_Wait::Action halves the animation wait"
                        + " (animation_wait.cpp:50), which quickens everything a unit does",
                hasted, ordinary));
        assertTrue(slowed > ordinary, String.format(
                "a slowed footman took %d cycles against an ordinary %d; Slow doubles the"
                        + " same wait (animation_wait.cpp:47)", slowed, ordinary));
    }

    /** How many cycles a footman needs to walk twenty squares. */
    private static int cyclesToWalk(GameData data, UnitType type, Unit.Buff buff) {
        World world = armed(data);
        Unit walker = world.createUnit(type, 0, 4, 4);
        if (buff != null) {
            // Long enough to outlast the walk, so the measurement is of the
            // spell and not of the moment it expired.
            walker.setBuff(buff, 100_000);
        }
        assertTrue(world.orderMove(walker, 24, 4), "the walk order was refused");
        for (int cycle = 1; cycle <= 3000; cycle++) {
            world.tick();
            if (walker.tileX() == 24 && walker.tileY() == 4) {
                return cycle;
            }
        }
        return 0;
    }

    @Test
    @DisplayName("a spell runs out on its own, one cycle at a time")
    void aBuffRunsItselfOut() {
        GameData data = load();
        World world = armed(data);
        UnitType footman = data.unitTypes().types().get("unit-footman");
        Unit unit = world.createUnit(footman, 0, 5, 5);

        unit.setBuff(Unit.Buff.BLOODLUST, 30);
        assertTrue(unit.hasBuff(Unit.Buff.BLOODLUST), "the fixture must start with the spell on");

        for (int cycle = 0; cycle < 30; cycle++) {
            world.tick();
        }
        assertEquals(0, unit.buff(Unit.Buff.BLOODLUST),
                "HandleBuffsEachCycle runs every timed effect down by one every cycle"
                        + " (actions.cpp:361-366), so thirty cycles should have spent thirty");
    }

    @Test
    @DisplayName("casting anything drops the caster's own invisibility")
    void castingAnythingUncloaksTheCaster() {
        GameData data = load();
        World world = armed(data);
        UnitType ogre = data.unitTypes().types().get("unit-ogre-mage");
        assertNotNull(ogre, "unit-ogre-mage should be in the shipped data");

        // A real spell at a real target rather than a self-cast: this data has
        // no self-targeted spell that costs mana, and the rule under test is
        // about the caster whatever it aimed at.
        for (String ident : data.upgrades().upgrades().all().keySet()) {
            world.upgrades(0).complete(ident);
        }

        Unit caster = world.createUnit(ogre, 0, 5, 5);
        Unit friend = world.createUnit(ogre, 0, 6, 5);
        caster.setMana(ogre.mana());
        caster.setBuff(Unit.Buff.INVISIBLE, 2000);
        assertTrue(caster.hasBuff(Unit.Buff.INVISIBLE),
                "the fixture must start invisible or it proves nothing");

        assertTrue(world.castSpell(caster, "spell-bloodlust", friend), "the cast was refused");
        assertEquals(0, caster.buff(Unit.Buff.INVISIBLE),
                "SpellCast's first line clears the caster's invisibility -- \"unit is invisible"
                        + " until attacks\", spells.cpp:507 -- and this caster is still hidden");
    }

    @Test
    @DisplayName("a spell in force survives a save and a reload")
    void aSpellInForceSurvivesASave() throws Exception {
        GameData data = load();
        World world = armed(data);
        UnitType footman = data.unitTypes().types().get("unit-footman");
        Unit before = world.createUnit(footman, 0, 5, 5);
        before.setBuff(Unit.Buff.BLOODLUST, 640);
        before.setBuff(Unit.Buff.UNHOLY_ARMOR, 120);

        java.io.StringWriter out = new java.io.StringWriter();
        SaveGame.write(world, "test-map", null, 0, out);

        World reloaded = armed(data);
        LoadGame.apply(reloaded, out.toString(), data.unitTypes().types());

        Unit after = null;
        for (Unit candidate : reloaded.unitsSnapshot()) {
            if (candidate.type() == footman) {
                after = candidate;
                break;
            }
        }
        assertNotNull(after, "the footman did not come back at all");
        assertEquals(640, after.buff(Unit.Buff.BLOODLUST),
                "a bloodlusted army reloaded with the spell spent and gone");
        assertEquals(120, after.buff(Unit.Buff.UNHOLY_ARMOR),
                "the Unholy Armour did not survive the save either");
        assertEquals(0, after.buff(Unit.Buff.HASTE),
                "a spell that was never cast should not arrive out of a save");
    }

    @Test
    @DisplayName("the spell book's own numbers reach the unit")
    void theSpellBookSetsTheTimerItDeclares() {
        GameData data = load();
        World world = armed(data);

        // Read out of the shipped data rather than written here, so this
        // cannot pass against a spell book that says something else.
        Spell bloodlust = data.spells().spells().all().get("spell-bloodlust");
        Assumptions.assumeTrue(bloodlust != null, "spell-bloodlust is not in this data");
        int declared = 0;
        for (Spell.Effect effect : bloodlust.effects()) {
            if (effect.kind() == Spell.EffectKind.ADJUST_VARIABLE
                    && effect.args().get("Bloodlust") instanceof Number number) {
                declared = number.intValue();
            }
        }
        // The parse is the half of this that was missing: readArguments walked
        // past the keyed table entirely, so this figure was nought and every
        // one of the five spells adjusted nothing.
        assertTrue(declared > 0,
                "spell-bloodlust declares {\"adjust-variable\", {Bloodlust = 1000}} at"
                        + " scripts/spells.legacy-declaration:273 and the parse produced " + declared);

        UnitType ogre = data.unitTypes().types().get("unit-ogre-mage");
        Assumptions.assumeTrue(ogre != null, "unit-ogre-mage is not in this data");
        Unit caster = world.createUnit(ogre, 0, 5, 5);
        caster.setMana(ogre.mana());
        Unit friend = world.createUnit(ogre, 0, 6, 5);
        for (String ident : data.upgrades().upgrades().all().keySet()) {
            world.upgrades(0).complete(ident);
        }

        assertTrue(world.castSpell(caster, "spell-bloodlust", friend), "the cast was refused");
        assertEquals(declared, friend.buff(Unit.Buff.BLOODLUST),
                "the spell should put its own declared number of cycles onto the target");
    }
}
