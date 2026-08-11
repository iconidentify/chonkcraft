package net.chonkbase.chonkcraft.engine.script;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.missile.MissileClass;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import net.chonkbase.chonkcraft.engine.spell.Spell;
import net.chonkbase.chonkcraft.engine.spell.SpellCatalog;
import net.chonkbase.chonkcraft.engine.spell.SpellSet;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.upgrade.UpgradeState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Blizzard, Death and Decay and Raise Dead, which between them did nothing.
 *
 * <p>{@code DefineSpell} is a flat run of key and value, and this implementation strode
 * it two arguments at a time. {@code repeat-cast} takes no value -- upstream
 * writes {@code --i} for it -- so it swallowed
 * the {@code "target"} that came after it and every key from there on was read
 * one place out. The three spells that declare it came out with
 * {@code target=SELF}, no {@code depend-upgrade} and an empty effect list:
 * twenty-five mana bought a sound. The implementation's {@code area-bombardment} was
 * finished, tested and unreachable, those two spells being its only callers.
 *
 * <p>The declarations below are the shipped ones from
 * {@code chonkcraft/scripts/spells.legacy-declaration}, {@code repeat-cast} included. That is the
 * whole point: the suite already had these four spells, written out without
 * that one key, and passed throughout.
 */
class SpellScriptTest {

    // ------------------------------------------------------------- fixtures

    private static SpellSet spells() {
        return SpellCatalog.generated().spells();
    }

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static Player[] twoPlayers() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        return players;
    }

    private static AnimationSet still() {
        AnimationSet set = new AnimationSet("s");
        set.put(AnimationSet.State.STILL, Animation.parse("s", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.DEATH, Animation.parse("d", List.of("frame 50", "wait 1")));
        return set;
    }

    private static UnitType mage() {
        UnitType type = new UnitType("unit-mage");
        type.setName("mage");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setMana(255);
        type.setSpeed(8);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setAnimationSet(still());
        return type;
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setName("footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setAnimationSet(still());
        return type;
    }

    /** The blizzard shard: {@code Damage = Rand(10)} and a speed of four. */
    private static MissileType shard(String ident) {
        return new MissileType(ident, null, MissileClass.POINT_TO_POINT, 32, 32,
                1, 1, 16, 1, 1, 1, 0, null, null, false, 0, 0, false, null, 10, 4);
    }

    private static World world() {
        World world = new World(grass(40), twoPlayers());
        world.setSpells(spells());
        world.setMissileTypes(Map.of(
                "missile-blizzard", shard("missile-blizzard"),
                "missile-death-and-decay", shard("missile-death-and-decay")));
        return world;
    }

    // ---------------------------------------------------------------- tests

    @Test
    @DisplayName("a blizzard puts fifty-five shards in the air")
    void aBlizzardFalls() {
        World world = world();
        UpgradeState research = world.upgrades(0);
        research.complete("upgrade-blizzard");

        Unit caster = world.createUnit(mage(), 0, 10, 10);
        Unit victim = world.createUnit(footman(), 1, 18, 10);
        caster.setMana(255);
        assertTrue(world.missiles().isEmpty(), "the sky must start clear or this proves nothing");

        assertTrue(world.castSpell(caster, "spell-blizzard", victim),
                "the cast was refused: repeat-cast shifted target and depend-upgrade");
        // Five fields of eleven shards, straight out of the declaration. Read
        // one place out, the fields and shards counts were never seen at all.
        assertEquals(55, world.missiles().size(),
                "a blizzard is five fields of eleven shards");
        assertEquals(230, caster.mana(), "twenty-five mana buys the squall");
    }

    @Test
    @DisplayName("death and decay falls too, and takes no upgrade it was not given")
    void deathAndDecayFalls() {
        World world = world();
        Unit caster = world.createUnit(mage(), 0, 10, 10);
        Unit victim = world.createUnit(footman(), 1, 18, 10);
        caster.setMana(255);

        // The prerequisite is the half of the shift nobody would notice: with
        // depend-upgrade read one place out it was empty, so the spell was
        // castable by a death knight that had never researched it.
        assertFalse(world.castSpell(caster, "spell-death-and-decay", victim),
                "death and decay needs upgrade-death-and-decay and did not ask for it");
        world.upgrades(0).complete("upgrade-death-and-decay");
        assertTrue(world.castSpell(caster, "spell-death-and-decay", victim),
                "the cast was refused after the upgrade was researched");
        assertEquals(55, world.missiles().size(),
                "death and decay is five fields of eleven shards");
    }

    @Test
    @DisplayName("a spell that repeats still reads its own keys in order")
    void theKeysAfterRepeatCastLandWhereTheyBelong() {
        SpellSet spells = spells();
        for (String ident : List.of("spell-blizzard", "spell-death-and-decay",
                "spell-raise-dead")) {
            Spell spell = spells.get(ident);
            assertEquals(Spell.Target.POSITION, spell.target(),
                    ident + " is cast at a place, not at itself");
            assertFalse(spell.effects().isEmpty(), ident + " does nothing at all");
        }
        // Raise Dead's summon carries a valueless keyword of its own inside the
        // action, so both levels of the same fault are exercised here.
        Spell.Effect summon = spells.get("spell-raise-dead").effects().get(0);
        assertEquals(Spell.EffectKind.SUMMON, summon.kind(), "raise dead summons");
        assertEquals("unit-skeleton", summon.what(), "it raises a skeleton");
        assertEquals(3600, summon.number("time-to-live", 0), "the skeleton is temporary");
        assertTrue(summon.flag("require-corpse"), "and needs a body to raise");
    }

    @Test
    @DisplayName("a spell with no flag of its own is unaffected")
    void anOrdinarySpellStillParses() {
        // The control: fireball declares no valueless key, so it must read the
        // same before and after. A change to the walk that broke the ordinary
        // case would otherwise pass unnoticed.
        Spell fireball = spells().get("spell-fireball");
        assertEquals(Spell.Target.POSITION, fireball.target());
        assertEquals(100, fireball.manaCost());
        assertEquals("upgrade-fireball", fireball.dependUpgrade());
        assertEquals(1, fireball.effects().size());
        assertEquals(20, fireball.effects().get(0).amount(), "fireball does twenty");
    }
}
