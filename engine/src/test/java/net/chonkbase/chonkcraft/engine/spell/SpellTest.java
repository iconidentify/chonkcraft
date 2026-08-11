package net.chonkbase.chonkcraft.engine.spell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Test;

/** Tests for spell definitions, mana, and casting. */
class SpellTest {

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
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setMana(255);
        type.setSpeed(8);
        type.setLandUnit(true);
        type.setAnimationSet(still());
        return type;
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setAnimationSet(still());
        return type;
    }

    private static UnitType tower() {
        UnitType type = new UnitType("unit-human-watch-tower");
        type.setTileSize(2, 2);
        type.setHitPoints(100);
        type.setBuilding(true);
        type.setAnimationSet(still());
        return type;
    }

    /** The two real definitions used by this focused fixture. */
    private static SpellSet spells() {
        SpellSet source = SpellCatalog.generated().spells();
        SpellSet focused = new SpellSet();
        focused.all().put("spell-healing", source.get("spell-healing"));
        focused.all().put("spell-exorcism", source.get("spell-exorcism"));
        focused.get("spell-exorcism").setDependUpgrade("");
        return focused;
    }

    /**
     * A world with the spells loaded and their prerequisites researched.
     *
     * <p>Healing declares depend-upgrade, so without the research every cast
     * is correctly refused. The one test that exercises that rule builds its
     * own world instead.
     */
    private static World world() {
        World world = new World(grass(30), twoPlayers());
        world.setSpells(spells());
        world.upgrades(0).complete("upgrade-healing");
        world.upgrades(1).complete("upgrade-healing");
        return world;
    }

    // ------------------------------------------------------------- parsing

    @Test
    void spellsParseTheirCostRangeAndTarget() {
        SpellSet set = spells();
        assertEquals(2, set.size());

        Spell healing = set.get("spell-healing");
        assertEquals("Healing", healing.name());
        assertEquals(6, healing.manaCost());
        assertEquals(6, healing.range());
        assertEquals(Spell.Target.UNIT, healing.target());
        assertEquals("upgrade-healing", healing.dependUpgrade());
    }

    @Test
    void spellsParseTheirEffects() {
        Spell healing = spells().get("spell-healing");
        Spell.Effect effect = healing.effects().stream()
                .filter(candidate -> candidate.kind() == Spell.EffectKind.ADJUST_VITALS)
                .findFirst().orElseThrow();
        assertEquals(Spell.EffectKind.ADJUST_VITALS, effect.kind());
        assertEquals("hit-points", effect.what());
        assertEquals(1, effect.amount());
    }

    @Test
    void spellsParseTheirConditions() {
        Spell healing = spells().get("spell-healing");
        assertTrue(healing.organicOnly(), "healing only reaches living things");
        assertFalse(healing.allowBuildings(), "and not buildings");
    }

    // ---------------------------------------------------------------- mana

    @Test
    void castersAreTrainedWithAFullPool() {
        // A mage walks out of the tower ready to cast.
        World world = world();
        Unit mage = world.createUnit(mage(), 0, 5, 5);
        assertEquals(255, mage.mana());
        assertTrue(mage.isCaster());
    }

    @Test
    void nonCastersHaveNoMana() {
        World world = world();
        Unit soldier = world.createUnit(footman(), 0, 5, 5);
        assertEquals(0, soldier.mana());
        assertFalse(soldier.isCaster());
    }

    @Test
    void manaRegeneratesSlowly() {
        // The pace of mana is what stops a mage casting continuously.
        World world = world();
        Unit mage = world.createUnit(mage(), 0, 5, 5);
        mage.setMana(0);

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 4; cycle++) {
            world.tick();
        }
        assertTrue(mage.mana() > 0, "mana should recover");
        assertTrue(mage.mana() <= 3, "but slowly, got " + mage.mana());
    }

    @Test
    void manaNeverExceedsThePool() {
        World world = world();
        Unit mage = world.createUnit(mage(), 0, 5, 5);
        mage.setMana(mage.type().mana());

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 10; cycle++) {
            world.tick();
        }
        assertEquals(255, mage.mana());
    }

    // -------------------------------------------------------------- casting

    @Test
    void healingRestoresHitPointsAndSpendsMana() {
        World world = world();
        Unit mage = world.createUnit(mage(), 0, 5, 5);
        Unit wounded = world.createUnit(footman(), 0, 6, 5);
        wounded.setHitPoints(20);

        assertTrue(world.castSpell(mage, "spell-healing", wounded));
        assertEquals(60, wounded.hitPoints(), "healing repeats until the wound is closed");
        assertEquals(15, mage.mana(), "and pays six mana for each restored hit point");
    }

    @Test
    void exorcismHarmsAndCanKill() {
        World world = world();
        Unit mage = world.createUnit(mage(), 0, 5, 5);
        Unit victim = world.createUnit(footman(), 1, 8, 5);
        victim.setHitPoints(1);

        assertTrue(world.castSpell(mage, "spell-exorcism", victim));
        assertFalse(victim.isAlive(), "a killing blow should kill");
    }

    @Test
    void castingWithoutManaIsRefused() {
        World world = world();
        Unit mage = world.createUnit(mage(), 0, 5, 5);
        Unit wounded = world.createUnit(footman(), 0, 6, 5);
        wounded.setHitPoints(20);
        mage.setMana(2);

        assertFalse(world.castSpell(mage, "spell-healing", wounded));
        assertEquals(20, wounded.hitPoints(), "nothing should have happened");
        assertEquals(2, mage.mana(), "and nothing should have been spent");
    }

    @Test
    void castingOutOfRangeIsRefused() {
        World world = world();
        Unit mage = world.createUnit(mage(), 0, 2, 2);
        Unit wounded = world.createUnit(footman(), 0, 25, 25);
        wounded.setHitPoints(20);

        assertFalse(world.castSpell(mage, "spell-healing", wounded));
        assertEquals(255, mage.mana(), "a refused cast costs nothing");
    }

    @Test
    void healingWillNotTargetABuilding() {
        World world = world();
        Unit mage = world.createUnit(mage(), 0, 5, 5);
        Unit tower = world.createUnit(tower(), 0, 7, 5);
        tower.setHitPoints(50);

        assertFalse(world.castSpell(mage, "spell-healing", tower));
        assertEquals(50, tower.hitPoints());
    }

    @Test
    void aNonCasterCannotCast() {
        World world = world();
        Unit soldier = world.createUnit(footman(), 0, 5, 5);
        Unit other = world.createUnit(footman(), 0, 6, 5);
        assertFalse(world.castSpell(soldier, "spell-healing", other));
    }

    @Test
    void anUnknownSpellIsRefused() {
        World world = world();
        Unit mage = world.createUnit(mage(), 0, 5, 5);
        assertFalse(world.castSpell(mage, "spell-that-does-not-exist", mage));
    }

    @Test
    void aSpellNeedingResearchIsRefusedUntilItIsDone() {
        // spell-healing declares depend-upgrade, so it must be researched.
        // This world deliberately skips the research the helper does.
        World world = new World(grass(30), twoPlayers());
        world.setSpells(spells());
        Unit mage = world.createUnit(mage(), 0, 5, 5);
        Unit wounded = world.createUnit(footman(), 0, 6, 5);
        wounded.setHitPoints(20);

        // Not researched yet: the world's upgrade state does not have it.
        assertFalse(world.upgrades(0).has("upgrade-healing"));
        assertFalse(world.castSpell(mage, "spell-healing", wounded));

        world.upgrades(0).complete("upgrade-healing");
        assertTrue(world.castSpell(mage, "spell-healing", wounded));
    }

    @Test
    void castingAtADeadTargetIsRefused() {
        World world = world();
        Unit mage = world.createUnit(mage(), 0, 5, 5);
        Unit victim = world.createUnit(footman(), 1, 7, 5);
        world.kill(victim);
        assertFalse(world.castSpell(mage, "spell-exorcism", victim));
    }
}
