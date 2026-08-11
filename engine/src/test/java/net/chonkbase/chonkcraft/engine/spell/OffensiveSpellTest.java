package net.chonkbase.chonkcraft.engine.spell;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.missile.MissileClass;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The four offensive spells, which between them did nothing at all.
 *
 * <p>Fireball, Death Coil, Blizzard and Death and Decay spent their mana,
 * played their sound and stopped. Two of them parsed to a {@code SPAWN_MISSILE}
 * effect the world treated as an explicit no-op; the other two parsed to no
 * effects whatever, because {@code area-bombardment} was not a verb the parser
 * knew. Demolish was worse than useless: the parser read an action's arguments
 * by position, so {@code {"demolish", "range", 3, "damage", 400}} came out as
 * "heal by three within one tile" and a detonating sapper left everybody
 * standing and slightly healthier.
 *
 * <p>The declarations below are the shipped ones from
 * {@code chonkcraft/scripts/spells.legacy-declaration}, copied verbatim so that a change in how
 * they are read shows up here.
 */
class OffensiveSpellTest {

    // ------------------------------------------------------------- fixtures

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
        // A caster is an attacker in Warcraft II, and it matters here:
        // MissileHit filters its blast by the firer's own CanTarget, so a
        // fireball thrown by something that could target nothing would splash
        // over an empty list.
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setCanTargetAir(true);
        type.setAnimationSet(still());
        return type;
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setName("footman");
        type.setTileSize(1, 1);
        type.setHitPoints(600);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setAnimationSet(still());
        return type;
    }

    /** The native shipped catalog plus one focused test-only pain spell. */
    private static SpellSet spells() {
        SpellSet spells = SpellCatalog.generated().spells();
        for (String ident : List.of("spell-fireball", "spell-death-coil",
                "spell-blizzard", "spell-death-and-decay")) {
            spells.get(ident).setDependUpgrade("");
        }
        Spell pain = spells.getOrCreate("spell-test-pain");
        pain.setName("Pain");
        pain.setManaCost(1);
        pain.setRange(8);
        pain.setTarget(Spell.Target.UNIT);
        pain.effects().add(new Spell.Effect(Spell.EffectKind.ADJUST_VITALS,
                "hit-points", -10, Map.of("hit-points", -10, "max-multi-cast", 1)));
        return spells;
    }

    private static Map<String, MissileType> missiles() {
        return Map.of(
                "missile-fireball", travelling("missile-fireball", 2),
                "missile-death-coil", travelling("missile-death-coil", 1),
                // Damage = Rand(10) on both bombardment missiles, which is the
                // whole of what those two spells do.
                "missile-blizzard", rolled("missile-blizzard"),
                "missile-death-and-decay", rolled("missile-death-and-decay"),
                "missile-normal-spell", travelling("missile-normal-spell", 1));
    }

    private static MissileType travelling(String ident, int range) {
        return new MissileType(ident, null, MissileClass.POINT_TO_POINT, 32, 32,
                1, 1, 16, 1, range, 1, 0, null, null, false, 0, 0, false, null, 0);
    }

    /**
     * A bombardment shard: the shipped blizzard's numbers, stagger included.
     *
     * <p>{@code BlizzardSpeed} is four against a travel speed of sixteen, and
     * {@code Spell_AreaBombardment::Cast} measures the delay between shards by
     * it. Ignoring it packs all eleven into under a second.
     */
    private static MissileType rolled(String ident) {
        return new MissileType(ident, null, MissileClass.POINT_TO_POINT, 32, 32,
                1, 1, 16, 1, 1, 1, 0, null, null, false, 0, 0, false, null, 10, 4);
    }

    private static World world(int size) {
        World world = new World(grass(size), twoPlayers());
        world.setSpells(spells());
        world.setMissileTypes(missiles());
        return world;
    }

    /** Covers every random field that a five-by-five bombardment may choose. */
    private static List<Unit> bombardmentField(World world, int centreX, int centreY) {
        java.util.ArrayList<Unit> targets = new java.util.ArrayList<>();
        for (int y = centreY - 2; y <= centreY + 2; y++) {
            for (int x = centreX - 2; x <= centreX + 2; x++) {
                UnitType post = footman();
                post.setHitPoints(100_000);
                post.setSpeed(0);
                targets.add(world.createUnit(post, 1, x, y));
            }
        }
        return targets;
    }

    private static long totalHitPoints(List<Unit> units) {
        return units.stream().mapToLong(Unit::hitPoints).sum();
    }

    // ------------------------------------------------------------- fireball

    @Test
    @DisplayName("a fireball puts a missile in the air and does its declared damage")
    void fireballBurns() {
        World world = world(40);
        Unit caster = world.createUnit(mage(), 0, 10, 10);
        Unit victim = world.createUnit(footman(), 1, 15, 10);
        caster.setMana(255);
        int before = victim.hitPoints();

        assertTrue(world.castSpell(caster, "spell-fireball", victim), "the cast was refused");
        assertEquals(1, world.missiles().size(),
                "spawn-missile was an explicit no-op, so a hundred mana bought nothing");

        for (int cycle = 0; cycle < 60 && victim.hitPoints() == before; cycle++) {
            world.tick();
        }
        assertEquals(before - 20, victim.hitPoints(),
                "a fireball is worth its declared twenty whoever throws it");
    }

    @Test
    @DisplayName("a death coil is worth fifty, which is not the mage's melee damage")
    void deathCoilTakesItsDeclaredFifty() {
        World world = world(40);
        UnitType necrolyte = mage();
        // Basic damage the missile must not fall back to. Upstream's
        // MissileHitsGoal prefers the missile's own figure.
        necrolyte.setBasicDamage(3);
        Unit caster = world.createUnit(necrolyte, 0, 10, 10);
        Unit victim = world.createUnit(footman(), 1, 15, 10);
        caster.setMana(255);
        int before = victim.hitPoints();

        assertTrue(world.castSpell(caster, "spell-death-coil", victim));
        for (int cycle = 0; cycle < 60 && victim.hitPoints() == before; cycle++) {
            world.tick();
        }
        assertEquals(before - 50, victim.hitPoints());
    }

    @Test
    @DisplayName("direct spell damage uses BNE's offer-and-wait reaction")
    void directSpellDamageDoesNotRunTheChonkCraftCowardTail() {
        // Retail BNE routes direct spell damage through the same HitUnit
        // boundary as ordinary damage. That boundary offers the aggressor to
        // nearby defenders and commands nothing. The inherited LegacyEngine
        // HitUnit_RunAway tail used to remain attached only to adjust-vitals
        // and demolish, making peasants flee immediately from spell damage
        // even though they do not flee immediately from a sword blow.
        World world = world(30);
        Unit caster = world.createUnit(mage(), 0, 10, 10);
        UnitType coward = footman();
        coward.setCoward(true);
        Unit victim = world.createUnit(coward, 1, 11, 10);
        caster.setMana(20);
        int before = victim.hitPoints();

        assertTrue(world.castSpell(caster, "spell-test-pain", victim));
        assertEquals(before - 10, victim.hitPoints(), "the direct spell never landed");
        assertEquals(Unit.Order.STILL, victim.order(),
                "the spell hit issued an immediate reaction command");
        world.tick();
        assertFalse(victim.order() == Unit.Order.MOVE,
                "direct BNE spell damage ran the inherited coward flee tail");
        assertEquals(0, victim.underAttack(),
                "direct BNE spell damage armed LegacyEngine's aggressor lock");
    }

    // ------------------------------------------------------------- demolish

    @Test
    @DisplayName("a sapper's demolish kills what is round it, including the sapper")
    void demolishKillsAndTakesTheCasterWithIt() {
        World world = world(40);
        UnitType sapperType = mage();
        sapperType.setHitPoints(200);
        Unit sapper = world.createUnit(sapperType, 0, 20, 20);
        UnitType softer = footman();
        softer.setHitPoints(60);
        Unit victim = world.createUnit(softer, 1, 22, 20);
        Unit bystander = world.createUnit(footman(), 1, 30, 20);
        int untouched = bystander.hitPoints();

        assertTrue(world.castSpell(sapper, "spell-suicide-bomber", sapper),
                "demolish is a self-cast and needs no target");

        assertFalse(victim.isAlive(),
                "a footman two tiles from four hundred damage should be dead; the port "
                        + "read the action by position and healed it for three instead");
        assertFalse(sapper.isAlive(),
                "Spell_Demolish::Cast hits every non-flying unit in range, the caster "
                        + "included, which is how a demolition squad dies");
        assertEquals(untouched, bystander.hitPoints(),
                "ten tiles away is outside a range of three");
    }

    @Test
    @DisplayName("demolish opens a hole in a wall, a wood and rock")
    void demolishClearsTerrain() {
        World world = world(40);
        MapField wall = world.map().field(21, 20);
        wall.setTile(0x0800);
        wall.setFlags(TileFlag.LAND_ALLOWED | TileFlag.WALL | TileFlag.HUMAN
                | TileFlag.UNPASSABLE);
        wall.setValue(GameMap.WALL_HIT_POINTS);

        MapField trees = world.map().field(20, 22);
        trees.addFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        trees.setValue(GameMap.WOOD_PER_FOREST_TILE);

        MapField rocks = world.map().field(18, 20);
        rocks.addFlags(TileFlag.ROCKS | TileFlag.UNPASSABLE);

        Unit sapper = world.createUnit(mage(), 0, 20, 20);
        assertTrue(world.castSpell(sapper, "spell-suicide-bomber", sapper));

        assertFalse(wall.isWall(), "the wall is what a demolition squad is for");
        assertFalse(trees.isForest(), "and the trees go with it");
        assertFalse(rocks.hasFlag(TileFlag.ROCKS), "rock in the blast must become open ground");
        assertTrue(rocks.isLandPassable(), "the cleared rock chokepoint must be walkable");
    }

    @Test
    @DisplayName("demolish spares whatever is flying overhead")
    void demolishSparesFliers() {
        World world = world(40);
        UnitType dragonType = footman();
        dragonType.setAirUnit(true);
        Unit dragon = world.createUnit(dragonType, 1, 21, 20);
        int before = dragon.hitPoints();

        Unit sapper = world.createUnit(mage(), 0, 20, 20);
        assertTrue(world.castSpell(sapper, "spell-suicide-bomber", sapper));
        assertEquals(before, dragon.hitPoints(),
                "upstream skips EMovement::Fly outright: don't hit flying units");
    }

    // -------------------------------------------------------- bombardments

    @Test
    @DisplayName("a blizzard rains fifty-five shards and hurts what is under them")
    void blizzardFalls() {
        World world = world(60);
        Unit caster = world.createUnit(mage(), 0, 10, 30);
        caster.setMana(255);
        List<Unit> field = bombardmentField(world, 30, 30);
        Unit victim = field.get(12);
        long before = totalHitPoints(field);

        assertTrue(world.castSpell(caster, "spell-blizzard", victim),
                "blizzard parsed to no effects at all: area-bombardment was not a verb "
                        + "the parser knew");
        assertEquals(5 * 11, world.missiles().size(),
                "five fields of eleven shards apiece");

        for (int cycle = 0; cycle < 400; cycle++) {
            world.tick();
        }
        assertTrue(totalHitPoints(field) < before,
                "twenty-five mana of blizzard did nothing anywhere in its target area");
        assertTrue(world.missiles().isEmpty(), "and the squall cleared afterwards");
    }

    @Test
    @DisplayName("the shards of a field are staggered rather than landing together")
    void blizzardShardsAreDelayed() {
        World world = world(60);
        Unit caster = world.createUnit(mage(), 0, 10, 30);
        caster.setMana(255);
        Unit victim = world.createUnit(footman(), 1, 30, 30);

        assertTrue(world.castSpell(caster, "spell-blizzard", victim));
        int atCast = world.missiles().size();
        // Two ticks, not one. The first action on a point-to-point missile
        // measures its journey and takes none of it (MissileInitMove,
        // The game ), so after a single tick every
        // shard whose delay has expired is still sitting at its start point
        // and the stagger has nothing to show. The second is when the ones
        // that are away begin to move; the start offset is four tiles at speed
        // sixteen, so nothing can have arrived yet. What matters is that they
        // are not all in the same place.
        world.tick();
        world.tick();
        long distinctPositions = world.missiles().stream()
                .map(missile -> missile.x() + "," + missile.y())
                .distinct()
                .count();
        assertTrue(distinctPositions > 5,
                "all " + atCast + " shards moved together: Missile::Delay is what makes "
                        + "a blizzard a squall rather than one flash");

        // And it lasts. The stagger is measured by BlizzardSpeed, which is four
        // against a travel speed of sixteen, so the eleventh shard of a field
        // is still waiting well over a second after the cast.
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 2; cycle++) {
            world.tick();
        }
        assertFalse(world.missiles().isEmpty(),
                "the whole squall was over in two seconds: BlizzardSpeed is what spaces "
                        + "the shards out, and Speed is four times it");
    }

    @Test
    @DisplayName("death and decay also lands, and on its own missile")
    void deathAndDecayFalls() {
        World world = world(60);
        Unit caster = world.createUnit(mage(), 0, 10, 30);
        caster.setMana(255);
        List<Unit> field = bombardmentField(world, 30, 30);
        Unit victim = field.get(12);
        long before = totalHitPoints(field);

        assertTrue(world.castSpell(caster, "spell-death-and-decay", victim));
        assertEquals(5 * 11, world.missiles().size());
        for (Missile missile : world.missiles()) {
            assertEquals("missile-death-and-decay", missile.type().ident());
        }
        for (int cycle = 0; cycle < 400; cycle++) {
            world.tick();
        }
        assertTrue(totalHitPoints(field) < before);
    }

    // ---------------------------------------------------- what was misread

    @Test
    @DisplayName("an action's keywords are read by name, not by position")
    void argumentsAreNamed() {
        Spell demolish = spells().get("spell-suicide-bomber");
        assertNotNull(demolish);
        Spell.Effect blast = demolish.effects().getFirst();
        assertEquals(Spell.EffectKind.DEMOLISH, blast.kind());
        assertEquals(3, blast.number("range", -1),
                "range is the value of the range keyword, not of whichever slot it fell in");
        assertEquals(400, blast.number("damage", -1),
                "the damage figure sat at index four and was never read");

        // The keyword whose value happens to be another string: reading it as a
        // valueless flag would shift every pair after it.
        Spell fireball = spells().get("spell-fireball");
        Spell.Effect shot = fireball.effects().getFirst();
        assertEquals("missile-fireball", shot.text("missile", null));
        assertEquals(20, shot.number("damage", -1));

        // And the native adjust-vitals shape is untouched.
        Spell.Effect heal = spells().get("spell-healing").effects().getFirst();
        assertEquals(Spell.EffectKind.ADJUST_VITALS, heal.kind());
        assertEquals("hit-points", heal.what());
        assertEquals(1, heal.amount());
    }
}
