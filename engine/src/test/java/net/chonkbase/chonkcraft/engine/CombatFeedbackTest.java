package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
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
 * What a player sees and hears when a shot lands, a building falls, or a unit
 * dies.
 *
 * <p>Everything here was parsed from the shipped data and read by nothing. The
 * recurring shape of the fault is a field with no callers: {@code ImpactMissile}
 * and {@code ImpactSound} on fifteen missile types, {@code NumBounces} on three,
 * {@code Indestructible} on eight unit types. A test that asked whether the
 * value had been parsed would have passed against every one of them, so nothing
 * here asks that -- each test drives the simulation and looks at what came out.
 */
class CombatFeedbackTest {

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

    /** A world whose first three slots are people at war with each other. */
    private static World threeSided(int size) {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 3 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        World world = new World(grass(size), players);
        for (int a = 0; a < 3; a++) {
            for (int b = 0; b < 3; b++) {
                world.setAllied(a, b, false);
            }
        }
        return world;
    }

    private static AnimationSet walker() {
        AnimationSet set = new AnimationSet("walker");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.DEATH, Animation.parse("death",
                List.of("frame 50", "wait 20")));
        return set;
    }

    private static UnitType soldier(String ident) {
        UnitType type = new UnitType(ident);
        type.setName(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(20);
        type.setMaxAttackRange(6);
        type.setAnimationSet(walker());
        return type;
    }

    /**
     * A target that cannot be killed by what is under test.
     *
     * <p>The hit points go on the type rather than on the unit, because
     * {@code Unit.setHitPoints} clamps to the type's maximum -- a fixture that
     * set the unit alone would silently be a sixty-point footman and every
     * "still standing" assertion would be trivially true.
     */
    private static UnitType post(String ident) {
        UnitType type = soldier(ident);
        type.setHitPoints(100_000);
        type.setSpeed(0);
        type.setCanAttack(false);
        return type;
    }

    /** A shot that carries a named explosion and a named noise, as most do. */
    private static MissileType shot(String ident, String impactMissile, String impactSound,
            int range, int bounces, MissileClass kind) {
        int frames = kind == MissileClass.PARABOLIC ? 15 : 1;
        int directions = kind == MissileClass.PARABOLIC ? 8 : 1;
        return new MissileType(ident, null, kind, 32, 32, frames, directions, 16, 1, range, 1, 0,
                impactMissile, impactSound, false, bounces, 0, false, null, 0);
    }

    /** A stationary explosion, which is what every impact missile in the game is. */
    private static MissileType flash(String ident) {
        return new MissileType(ident, null, MissileClass.STAY, 32, 32, 8, 1, 16, 1, 1, 1, 0,
                null, null, false, 0, 0, false, null, 0);
    }

    private static List<String> namedSounds(World world) {
        List<String> heard = new ArrayList<>();
        for (World.SoundEvent event : world.drainSoundEvents()) {
            if (event.named()) {
                heard.add(event.event());
            }
        }
        return heard;
    }

    // ------------------------------------------- finding 1: impact feedback

    @Test
    @DisplayName("a nonfatal hit raises a travelling damage figure")
    void aHitRaisesItsDamageFigure() {
        MissileType figure = new MissileType("missile-hit", null, MissileClass.HIT,
                15, 15, 1, 1, 1, 1, 16, 1, 150,
                null, null, false, 0, 0, false);
        World world = new World(grass(30));
        world.setMissileTypes(Map.of("missile-hit", figure));
        world.setDamageMissile("missile-hit");
        Unit attacker = world.createUnit(soldier("unit-footman"), 0, 10, 10);
        Unit victim = world.createUnit(post("unit-target"), 1, 12, 10);
        int before = victim.hitPoints();

        world.hit(attacker, victim);

        assertEquals(1, world.missiles().size(), "the hit produced no damage figure");
        Missile damage = world.missiles().get(0);
        assertEquals(MissileClass.HIT, damage.type().missileClass());
        assertEquals(victim.hitPoints() - before, damage.damage(),
                "the figure should carry the negative damage that is drawn");
        assertEquals(12 * 32 + 16, Math.round(damage.x()));
        assertEquals(10 * 32 + 16, Math.round(damage.y()));

        // The first action on any point-to-point missile measures the journey
        // and takes none of it -- MissileInitMove's even pass sets CurrentStep
        // to nought, works out TotalStep and returns
        // and a damage figure is one
        // of those, because {@code MissileHit::Action} is a call to
        // {@code PointToPointMissile}. So it is the second tick that lifts it,
        // and this used to expect the first.
        world.tick();
        assertTrue(world.missiles().contains(damage),
                "the damage figure vanished before a frame could display it");
        assertEquals(10 * 32 + 16, Math.round(damage.y()),
                "the first action should have measured the rise rather than made it");

        world.tick();
        assertTrue(damage.y() < 10 * 32 + 16, "the damage figure should float upward");
    }

    @Test
    @DisplayName("a shot that lands leaves its explosion where it landed")
    void aLandedShotLeavesItsImpactMissile() {
        World world = new World(grass(30));
        world.setMissileTypes(Map.of(
                "missile-bolt", shot("missile-bolt", "missile-impact", "explosion",
                        1, 0, MissileClass.POINT_TO_POINT),
                "missile-impact", flash("missile-impact")));

        UnitType ballista = soldier("unit-ballista");
        ballista.setMissile("missile-bolt");
        Unit siege = world.createUnit(ballista, 0, 4, 10);
        Unit victim = world.createUnit(post("unit-footman"), 1, 12, 10);

        world.hit(siege, victim);
        Missile impact = flyUntilImpact(world, "missile-impact");
        assertNotNull(impact,
                "the bolt vanished on arrival: ImpactMissile is parsed and never read");

        // Retail's remaining-distance loop moves while remaining is >= 0.
        // This exact 256-pixel flight therefore advances one final 16-pixel
        // speed step before action 6; the impact picture belongs where the
        // projectile actually freed, not back at its LegacyEngine aim point.
        assertEquals(12 * 32 + 32, Math.round(impact.x()),
                "the explosion is not at retail's post-zero impact point");
        assertEquals(10 * 32 + 16, Math.round(impact.y()));

        // And it is harmless: a crater with no owner computes no damage.
        int standing = victim.hitPoints();
        for (int cycle = 0; cycle < 60; cycle++) {
            world.tick();
        }
        assertEquals(standing, victim.hitPoints(),
                "the impact explosion did damage of its own, which upstream's null "
                        + "SourceUnit is there to prevent");
    }

    @Test
    @DisplayName("a shot that lands is heard landing")
    void aLandedShotRaisesItsImpactSound() {
        World world = new World(grass(30));
        world.setMissileTypes(Map.of(
                "missile-bolt", shot("missile-bolt", null, "explosion",
                        1, 0, MissileClass.POINT_TO_POINT)));

        UnitType ballista = soldier("unit-ballista");
        ballista.setMissile("missile-bolt");
        Unit siege = world.createUnit(ballista, 0, 4, 10);
        Unit victim = world.createUnit(post("unit-footman"), 1, 12, 10);

        world.hit(siege, victim);
        world.drainSoundEvents();

        List<String> heard = new ArrayList<>();
        for (int cycle = 0; cycle < 60 && !heard.contains("explosion"); cycle++) {
            world.tick();
            heard.addAll(namedSounds(world));
        }
        assertTrue(heard.contains("explosion"),
                "nothing was heard where the bolt landed; heard " + heard);
    }

    @Test
    @DisplayName("a missile with a FiredSound is heard leaving")
    void aFiredSoundIsPlayedAtLaunch() {
        MissileType noisy = new MissileType("missile-critter-explosion", null,
                MissileClass.POINT_TO_POINT, 32, 32, 1, 1, 16, 1, 1, 1, 0,
                null, null, false, 0, 0, false, "explosion", 0);
        World world = new World(grass(30));
        world.setMissileTypes(Map.of("missile-critter-explosion", noisy));

        UnitType thrower = soldier("unit-thrower");
        thrower.setMissile("missile-critter-explosion");
        Unit source = world.createUnit(thrower, 0, 4, 10);
        Unit victim = world.createUnit(post("unit-footman"), 1, 12, 10);

        world.drainSoundEvents();
        world.hit(source, victim);
        assertTrue(namedSounds(world).contains("explosion"),
                "FiredSound was neither parsed nor played");
    }

    // ------------------------------------------------ finding 4: NumBounces

    @Test
    @DisplayName("retail dragon breath does not inherit ChonkCraft onward bounces")
    void battleNetDragonBreathDoesNotReuseChonkCraftNumBounces() {
        World world = new World(grass(40));
        world.setMissileTypes(Map.of(
                "missile-dragon-breath", shot("missile-dragon-breath", null, null,
                        2, 3, MissileClass.POINT_TO_POINT_BOUNCE)));

        UnitType dragon = soldier("unit-dragon");
        dragon.setMissile("missile-dragon-breath");
        dragon.setMaxAttackRange(20);
        Unit flier = world.createUnit(dragon, 0, 4, 20);

        // A line of enemies a tile and a half apart along the flight path, so
        // each detonation catches a different one. Upstream extends the
        // destination by forty-eight pixels a hop, so the second lands between
        // the sixteenth and seventeenth columns and the third past that.
        Unit first = world.createUnit(post("unit-a"), 1, 16, 20);
        Unit second = world.createUnit(post("unit-b"), 1, 18, 20);
        Unit third = world.createUnit(post("unit-c"), 1, 19, 20);
        int whole = first.hitPoints();

        world.hit(flier, first);
        for (int cycle = 0; cycle < 200; cycle++) {
            world.tick();
        }

        assertTrue(first.hitPoints() < whole, "the shot never hit what it was aimed at");
        assertEquals(whole, second.hitPoints(),
                "the BNE projectile action grew a LegacyEngine onward-bounce arm");
        assertEquals(whole, third.hitPoints(),
                "the BNE projectile action travelled onward a third time");
    }

    @Test
    @DisplayName("a missile with no bounces still hits exactly once")
    void anOrdinaryMissileDoesNotBounce() {
        World world = new World(grass(40));
        world.setMissileTypes(Map.of(
                "missile-arrow", shot("missile-arrow", null, null,
                        1, 0, MissileClass.POINT_TO_POINT)));

        UnitType archer = soldier("unit-archer");
        archer.setMissile("missile-arrow");
        archer.setMaxAttackRange(20);
        Unit shooter = world.createUnit(archer, 0, 4, 20);
        Unit victim = world.createUnit(post("unit-a"), 1, 16, 20);

        world.hit(shooter, victim);
        int hits = 0;
        int previous = victim.hitPoints();
        for (int cycle = 0; cycle < 200; cycle++) {
            world.tick();
            if (victim.hitPoints() != previous) {
                hits++;
                previous = victim.hitPoints();
            }
        }
        assertEquals(1, hits, "an arrow landed more than once");
    }

    // ------------------------------- finding 7: the death explosion's place

    @Test
    @DisplayName("a four-by-four building blows up in its middle, not on its corner")
    void aBuildingsDeathExplosionIsCentred() {
        World world = new World(grass(30));
        world.setMissileTypes(Map.of("missile-explosion", flash("missile-explosion")));

        UnitType hall = soldier("unit-town-hall");
        hall.setTileSize(4, 4);
        hall.setBuilding(true);
        hall.setSpeed(0);
        hall.setExplosion("missile-explosion");
        hall.setAnimationSet(new AnimationSet("building"));
        Unit building = world.createUnit(hall, 0, 10, 10);

        world.kill(building);
        assertEquals(1, world.missiles().size());
        Missile boom = world.missiles().get(0);
        // GetMapPixelPosCenter: the corner plus half the whole footprint.
        assertEquals(10 * 32 + 64, Math.round(boom.x()),
                "the explosion sits a tile and a half left of the building");
        assertEquals(10 * 32 + 64, Math.round(boom.y()),
                "the explosion sits a tile and a half above the building");
    }

    // ------------------------------------------------- finding 8: the arc

    @Test
    @DisplayName("a catapult shot shows retail's rise and fall frames")
    void aParabolicShotUsesTheRetailArcFrames() {
        World world = new World(grass(40));
        world.setMissileTypes(Map.of(
                "missile-catapult-rock", shot("missile-catapult-rock", null, null,
                        1, 0, MissileClass.PARABOLIC)));

        UnitType catapult = soldier("unit-catapult");
        catapult.setMissile("missile-catapult-rock");
        catapult.setMaxAttackRange(20);
        Unit siege = world.createUnit(catapult, 0, 4, 20);
        Unit victim = world.createUnit(post("unit-a"), 1, 20, 20);
        int whole = victim.hitPoints();

        world.hit(siege, victim);
        Set<Integer> frames = new java.util.LinkedHashSet<>();
        for (int cycle = 0; cycle < 200; cycle++) {
            world.tick();
            for (Missile missile : world.missiles()) {
                frames.add(missile.frame());
            }
        }
        assertTrue(frames.containsAll(Set.of(0, 1, 2)),
                "the boulder never displayed retail's low/middle/high arc frames: " + frames);
        assertTrue(victim.hitPoints() < whole, "an arcing shot still has to land on its target");
    }

    // -------------------------------------- finding 9: rubble without delay

    @Test
    @DisplayName("a building with no death animation goes straight to its rubble")
    void aDestroyedBuildingDoesNotStandAround() {
        World world = new World(grass(30));
        UnitType rubble = soldier("unit-destroyed-4x4-place");
        rubble.setTileSize(4, 4);
        rubble.setBuilding(true);
        rubble.setSpeed(0);
        AnimationSet decay = new AnimationSet("destroyed-place");
        decay.put(AnimationSet.State.DEATH, Animation.parse("death",
                List.of("frame 0", "wait 600")));
        rubble.setAnimationSet(decay);

        UnitType hall = soldier("unit-town-hall");
        hall.setTileSize(4, 4);
        hall.setBuilding(true);
        hall.setSpeed(0);
        hall.setCorpse("unit-destroyed-4x4-place");
        // animations-building declares Still, Research, Train and Upgrade and
        // no Death, which is the whole reason upstream swaps the building for
        // its rubble on the cycle it dies.
        hall.setAnimationSet(new AnimationSet("building"));
        world.setUnitTypes(Map.of("unit-destroyed-4x4-place", rubble, "unit-town-hall", hall));

        Unit building = world.createUnit(hall, 0, 10, 10);
        world.kill(building);
        world.tick();

        assertTrue(world.unitsSnapshot().stream()
                        .anyMatch(unit -> "unit-destroyed-4x4-place".equals(unit.type().ident())),
                "the keep stood there undamaged behind its own explosion; upstream's "
                        + "COrder_Die::Execute swaps it for rubble on the same cycle");
    }

    @Test
    @DisplayName("a soldier still plays its death animation out")
    void aUnitWithADeathAnimationStillRunsIt() {
        World world = new World(grass(30));
        Unit footman = world.createUnit(soldier("unit-footman"), 0, 10, 10);
        world.kill(footman);
        assertTrue(footman.deathTimer() > 1,
                "removing the one-second floor must not cut short a real death animation");
    }

    // --------------------------------- finding 10: the under-attack warning

    @Test
    @DisplayName("a second front is announced at once; a grinding siege is not")
    void theHelpCryFollowsUpstreamsRule() {
        World world = threeSided(80);
        UnitType post = soldier("unit-post");
        post.setSpeed(0);
        post.setCanAttack(false);
        post.setHitPoints(100_000);

        Unit attacker = world.createUnit(soldier("unit-grunt"), 1, 40, 40);
        Unit here = world.createUnit(post, 0, 41, 40);
        Unit farAway = world.createUnit(post, 0, 60, 60);
        here.setHitPoints(100_000);
        farAway.setHitPoints(100_000);

        world.drainSoundEvents();
        world.hit(attacker, here);
        assertEquals(1, helpCries(world), "the first blow should be announced");

        // The same unit under sustained fire says nothing more: its own
        // two-second gag, which the implementation did not have at all.
        for (int i = 0; i < 5; i++) {
            world.hit(attacker, here);
        }
        assertEquals(0, helpCries(world), "one skirmish drowned out everything else");

        // Somewhere else entirely, well past fourteen tiles. Upstream warns
        // about that two seconds later; the implementation's flat twenty-second gag per
        // player swallowed it.
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
        }
        world.drainSoundEvents();
        world.hit(attacker, farAway);
        assertEquals(1, helpCries(world),
                "an attack on the other side of the map was never announced");
    }

    @Test
    @DisplayName("written attack notices are not throttled with the voice")
    void writtenAttackNoticeSurvivesVoiceThrottle() {
        World world = threeSided(80);
        UnitType post = post("unit-watch-tower");
        Unit attacker = world.createUnit(soldier("unit-grunt"), 1, 40, 40);
        Unit target = world.createUnit(post, 0, 41, 40);

        world.drainSoundEvents();
        world.hit(attacker, target);
        assertEquals(1, world.drainAttackNotices().size());
        assertEquals(1, helpCries(world));

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
        }
        world.drainSoundEvents();
        world.hit(attacker, target);

        assertEquals(1, world.drainAttackNotices().size(),
                "the written notice was swallowed by the voice throttle");
        assertEquals(0, helpCries(world),
                "the same nearby fight should not repeat its spoken warning");
    }

    private static int helpCries(World world) {
        int cries = 0;
        for (World.SoundEvent event : world.drainSoundEvents()) {
            if (!event.named() && "help".equals(event.event())) {
                cries++;
            }
        }
        return cries;
    }

    // ------------------------------------------- finding 11: Indestructible

    @Test
    @DisplayName("a Circle of Power cannot be shot down")
    void indestructibleThingsTakeNoDamage() {
        World world = new World(grass(30));
        UnitType circle = soldier("unit-circle-of-power");
        circle.setSpeed(0);
        circle.setCanAttack(false);
        circle.setIndestructible(true);
        Unit objective = world.createUnit(circle, 0, 12, 10);
        Unit attacker = world.createUnit(soldier("unit-grunt"), 1, 11, 10);

        int before = objective.hitPoints();
        for (int blow = 0; blow < 50; blow++) {
            world.hit(attacker, objective);
        }
        assertEquals(before, objective.hitPoints(),
                "a scripted objective was destructible; HitUnit returns for "
                        + "BoolFlag[INDESTRUCTIBLE_INDEX] before it does anything at all");
        assertEquals(0, helpCries(world), "and it does not cry for help either");
    }

    // ------------------- finding 13: the missile's own declared damage

    @Test
    @DisplayName("a missile that declares its own damage overrules the firer's stats")
    void aMissileTypesOwnDamageIsUsed() {
        World world = new World(grass(30));
        // Damage = Rand(10): nought to nine, rolled per unit struck. A blizzard
        // shard and a death-and-decay cloud are the only two, and both parsed
        // as zero because Rand was an unbound script function.
        MissileType shard = new MissileType("missile-blizzard", null,
                MissileClass.POINT_TO_POINT, 32, 32, 1, 1, 16, 1, 1, 1, 0,
                null, null, false, 0, 0, false, null, 10);
        world.setMissileTypes(Map.of("missile-blizzard", shard));

        UnitType mage = soldier("unit-mage");
        mage.setMissile("missile-blizzard");
        // A basic damage figure high enough that using it instead would be
        // unmistakable.
        mage.setBasicDamage(500);
        Unit caster = world.createUnit(mage, 0, 4, 10);
        Unit victim = world.createUnit(post("unit-footman"), 1, 12, 10);

        int worst = 0;
        for (int shot = 0; shot < 60; shot++) {
            int before = victim.hitPoints();
            world.hit(caster, victim);
            for (int cycle = 0; cycle < 60 && victim.hitPoints() == before; cycle++) {
                world.tick();
            }
            worst = Math.max(worst, before - victim.hitPoints());
        }
        assertTrue(worst > 0, "the shard did nothing at all, which is Rand(10) parsing as zero");
        assertTrue(worst <= 9,
                "a shard did " + worst + " damage: it is using the mage's stats rather "
                        + "than its own declared Rand(10)");
    }

    // --------------------------------------------------- credit for a kill

    @Test
    @DisplayName("a kill is banked by whoever made it, not by every enemy")
    void onlyTheKillerScores() {
        World world = threeSided(30);
        UnitType footman = soldier("unit-footman");
        footman.setPoints(50);

        Unit killer = world.createUnit(footman, 1, 10, 10);
        Unit victim = world.createUnit(footman, 2, 11, 10);
        // Player nought takes no part and is hostile to both, which is exactly
        // the case a two-sided fixture cannot tell apart -- and all fifty-two
        // campaign missions are two sided, which is how this survived.
        world.createUnit(footman, 0, 20, 20);

        while (victim.isAlive()) {
            world.hit(killer, victim);
        }
        // The score is banked by ScoreKeeper, which is the one place that does
        // it: it carries the seen-once set that stops a corpse being paid for
        // on every cycle it lingers, and the kill and razing tallies that go
        // with the points. The world's job is only to record who did it.
        new ScoreKeeper(world).update();

        assertEquals(50, world.player(1).score(), "the side that made the kill banks it");
        assertEquals(0, world.player(0).score(),
                "a bystander was paid for a fight it took no part in");
        assertEquals(0, world.player(2).score(), "and the side that lost it scores nothing");
        assertEquals(1, victim.killedBy(), "the dead unit should remember who did it");
    }

    @Test
    @DisplayName("a death nobody caused credits nobody")
    void anUncausedDeathScoresForNoOne() {
        World world = threeSided(30);
        UnitType footman = soldier("unit-footman");
        footman.setPoints(50);
        Unit lost = world.createUnit(footman, 2, 11, 10);

        // A cancelled building, a summon timing out, a script clearing the
        // board. Upstream's HitUnit only scores under "if (attacker)".
        world.kill(lost);
        new ScoreKeeper(world).update();

        for (int player = 0; player < 3; player++) {
            assertEquals(0, world.player(player).score(),
                    "player " + player + " was paid for a death nobody caused");
        }
        assertEquals(-1, lost.killedBy());
    }

    @Test
    @DisplayName("killing an ally scores nothing")
    void anAllyIsNotWorthPoints() {
        World world = threeSided(30);
        world.setAllied(1, 2, true);
        world.setAllied(2, 1, true);
        UnitType footman = soldier("unit-footman");
        footman.setPoints(50);

        Unit killer = world.createUnit(footman, 1, 10, 10);
        Unit victim = world.createUnit(footman, 2, 11, 10);
        while (victim.isAlive()) {
            world.hit(killer, victim);
        }
        assertEquals(0, world.player(1).score(),
                "HitUnit only scores when the target IsEnemy of the attacker");
    }

    @Test
    @DisplayName("a killing blow leaves the last living hit points on the dying unit")
    void aKillingBlowLeavesTheLastLivingHitPointsOnTheDyingUnit() {
        // retail-xhuman-10-idle footman 1492: native DYING at fixture 42 with
        // HP 60 (last living value); Java zeroed the field on the lethal
        // subtract. Same report is open for XHuman 2 footman 1548@43.
        World world = threeSided(30);
        UnitType footman = soldier("unit-footman");
        footman.setMaxAttackRange(1);
        footman.setBasicDamage(100);
        footman.setPiercingDamage(0);
        Unit killer = world.createUnit(footman, 0, 10, 10);
        Unit victim = world.createUnit(footman, 1, 11, 10);
        assertTrue(killer != null && victim != null, "units must place");
        int living = victim.hitPoints();
        assertTrue(living > 0, "victim starts alive");

        // Direct combat damage path (melee applyDamage), not projectile hit.
        world.combat.applyDamage(killer, victim, 1);

        assertEquals(Unit.Order.DYING, victim.order(),
                "lethal hit must put the victim into DYING");
        assertEquals(living, victim.hitPoints(),
                "DYING must keep the last living hit-point count; native "
                        + "1492 reports 60 not 0 through the corpse");
        assertFalse(victim.isAlive(),
                "order DYING must make the victim unalive even with HP left");
    }

    // ----------------------------------------------------------- utilities

    /** Runs the world until a missile of the named type appears, or gives up. */
    private static Missile flyUntilImpact(World world, String ident) {
        for (int cycle = 0; cycle < 120; cycle++) {
            world.tick();
            for (Missile missile : world.missiles()) {
                if (missile.type().ident().equals(ident)) {
                    return missile;
                }
            }
        }
        return null;
    }
}
