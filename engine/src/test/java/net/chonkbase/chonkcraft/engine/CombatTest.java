package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.missile.MissileClass;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for attacking, damage, and death. */
class CombatTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    /** An animation set that swings on every second cycle. */
    private static AnimationSet fighterAnimations() {
        AnimationSet set = new AnimationSet("test-fighter");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("move",
                List.of("frame 0", "move 8", "wait 1", "frame 5", "move 8", "wait 1",
                        "frame 10", "move 8", "wait 1", "frame 15", "move 8", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack",
                List.of("unbreakable begin", "frame 25", "wait 1",
                        "frame 30", "attack", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.DEATH, Animation.parse("death",
                List.of("frame 50", "wait 1")));
        return set;
    }

    private static UnitType fighter(String ident, int damage, int armor, int hitPoints) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(hitPoints);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(damage);
        type.setPiercingDamage(0);
        type.setArmor(armor);
        type.setMaxAttackRange(1);
        type.setAnimationSet(fighterAnimations());
        return type;
    }

    // ---------------------------------------------------------------- damage

    @Test
    void damageFollowsTheWarcraftFormula() {
        // damage = max(basic - armor, 1) + piercing, then a random reduction
        // of up to just under half. With basic 10 and armor 2 the nominal
        // figure is 8, so a blow lands for 4 to 8.
        World world = new World(grass(10));
        Unit attacker = world.createUnit(fighter("a", 10, 0, 100), 0, 1, 1);
        Unit defender = world.createUnit(fighter("d", 0, 2, 10_000), 1, 3, 3);

        int minimum = Integer.MAX_VALUE;
        int maximum = 0;
        for (int blow = 0; blow < 400; blow++) {
            int before = defender.hitPoints();
            world.hit(attacker, defender);
            int dealt = before - defender.hitPoints();
            minimum = Math.min(minimum, dealt);
            maximum = Math.max(maximum, dealt);
        }
        assertEquals(8, maximum, "a full-strength blow should be basic minus armour");
        assertEquals(4, minimum, "the reduction should never take more than half");
    }

    @Test
    void armourNeverReducesABlowBelowOne() {
        World world = new World(grass(10));
        // Armour far above the attacker's damage.
        Unit attacker = world.createUnit(fighter("a", 2, 0, 100), 0, 1, 1);
        Unit defender = world.createUnit(fighter("d", 0, 50, 10_000), 1, 3, 3);

        for (int blow = 0; blow < 50; blow++) {
            int before = defender.hitPoints();
            world.hit(attacker, defender);
            assertTrue(before > defender.hitPoints(), "a blow should always do something");
        }
    }

    @Test
    void piercingDamageIsAddedAfterArmour() {
        World world = new World(grass(10));
        UnitType archerType = fighter("archer", 3, 0, 100);
        archerType.setPiercingDamage(6);
        Unit archer = world.createUnit(archerType, 0, 1, 1);
        Unit defender = world.createUnit(fighter("d", 0, 2, 10_000), 1, 3, 3);

        // max(3 - 2, 1) + 6 = 7 nominal.
        int maximum = 0;
        for (int blow = 0; blow < 400; blow++) {
            int before = defender.hitPoints();
            world.hit(archer, defender);
            maximum = Math.max(maximum, before - defender.hitPoints());
        }
        assertEquals(7, maximum);
    }

    @Test
    void damageIsDeterministic() {
        // Two worlds given the same orders must produce the same fight.
        int[] first = fightAndRecord();
        int[] second = fightAndRecord();
        org.junit.jupiter.api.Assertions.assertArrayEquals(first, second,
                "the same fight produced different results");
    }

    private static int[] fightAndRecord() {
        World world = new World(grass(10));
        Unit attacker = world.createUnit(fighter("a", 10, 0, 100), 0, 1, 1);
        Unit defender = world.createUnit(fighter("d", 0, 2, 10_000), 1, 3, 3);
        int[] hitPoints = new int[20];
        for (int blow = 0; blow < hitPoints.length; blow++) {
            world.hit(attacker, defender);
            hitPoints[blow] = defender.hitPoints();
        }
        return hitPoints;
    }

    // --------------------------------------------------------------- attacks

    @Test
    void aUnitClosesOnItsTargetThenFights() {
        World world = new World(grass(20));
        Unit attacker = world.createUnit(fighter("a", 6, 0, 60), 0, 2, 2);
        Unit defender = world.createUnit(fighter("d", 0, 2, 60), 1, 10, 2);

        assertTrue(world.orderAttack(attacker, defender));
        assertEquals(Unit.Order.ATTACK, attacker.order());

        int fullHealth = defender.hitPoints();
        boolean closed = false;
        for (int cycle = 0; cycle < 3000; cycle++) {
            world.tick();
            if (!closed && attacker.distanceTo(defender) <= 1) {
                closed = true;
            }
            if (defender.hitPoints() < fullHealth) {
                break;
            }
        }
        assertTrue(closed, "the attacker never reached its target");
        assertTrue(defender.hitPoints() < fullHealth, "the attacker never landed a blow");
    }

    @Test
    void aFreshAiAttackScansAtABreakableOldAttackAnimationBoundary() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            PudMap.PlayerType kind = i == 0 ? PudMap.PlayerType.COMPUTER
                    : i == 1 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY;
            players[i] = new Player(i, kind, PudMap.Race.HUMAN);
        }
        World world = new World(grass(20), players);
        world.establishDiplomacy();
        UnitType archerType = fighter("unit-archer", 6, 0, 40);
        archerType.setSightRange(8);
        archerType.setReactRangeComputer(8);
        Unit archer = world.createUnit(archerType, 0, 5, 5);
        UnitType oldType = fighter("unit-ogre", 6, 0, 90);
        oldType.setPriority(1);
        Unit oldGoal = world.createUnit(oldType, 1, 6, 5);
        UnitType betterType = fighter("unit-goblin-sappers", 6, 0, 40);
        betterType.setPriority(100);
        Unit better = world.createUnit(betterType, 1, 5, 6);
        better.setHitPoints(10);

        assertTrue(world.orderAttack(archer, oldGoal));
        // The old order finished its swing at index zero and a later command
        // installed this fresh attack before the animation object changed.
        archer.animation().switchTo(
                archer.type().animationSet().get(AnimationSet.State.ATTACK));
        assertFalse(archer.animation().unbreakable());

        world.tick();

        assertEquals(better, archer.target(),
                "the breakable old animation was treated as a committed swing and"
                        + " skipped FIRST_ENTRY's AutoSelectTarget");
    }

    @Test
    void aFightEndsWithADeath() {
        World world = new World(grass(20));
        Unit attacker = world.createUnit(fighter("a", 20, 0, 60), 0, 2, 2);
        Unit defender = world.createUnit(fighter("d", 0, 0, 40), 1, 4, 2);
        world.orderAttack(attacker, defender);

        for (int cycle = 0; cycle < 3000 && defender.isAlive(); cycle++) {
            world.tick();
        }
        assertFalse(defender.isAlive(), "the defender should be dead");
        // Killing frees the ground it stood on.
        assertFalse(world.map().field(4, 2).hasFlag(TileFlag.LAND_UNIT),
                "a dead unit should not still occupy its square");
        // The attacker goes back to standing.
        for (int cycle = 0; cycle < 100; cycle++) {
            world.tick();
        }
        assertEquals(Unit.Order.STILL, attacker.order());
    }

    @Test
    void aCorpseIsClearedAfterItsDeathAnimation() {
        World world = new World(grass(20));
        Unit victim = world.createUnit(fighter("d", 0, 0, 10), 1, 5, 5);
        world.kill(victim);

        assertEquals(Unit.Order.DYING, victim.order());
        assertTrue(world.units().contains(victim), "the corpse should linger briefly");

        for (int cycle = 0; cycle <= World.CYCLES_PER_SECOND + 2; cycle++) {
            world.tick();
        }
        assertFalse(world.units().contains(victim), "the corpse should have been cleared");
    }

    @Test
    void aUnitThatCannotAttackRefusesTheOrder() {
        World world = new World(grass(20));
        UnitType peasantType = fighter("peasant", 0, 0, 30);
        peasantType.setCanAttack(false);
        Unit peasant = world.createUnit(peasantType, 0, 2, 2);
        Unit enemy = world.createUnit(fighter("e", 5, 0, 30), 1, 4, 2);

        assertFalse(world.orderAttack(peasant, enemy));
        assertEquals(Unit.Order.STILL, peasant.order());
    }

    @Test
    void aGroundUnitWillNotBeOrderedOntoAnAircraft() {
        World world = new World(grass(20));
        UnitType footType = fighter("foot", 5, 0, 60);
        footType.setCanTargetAir(false);
        UnitType flyerType = fighter("flyer", 5, 0, 60);
        flyerType.setAirUnit(true);

        Unit footman = world.createUnit(footType, 0, 2, 2);
        Unit flyer = world.createUnit(flyerType, 1, 4, 2);
        assertFalse(world.orderAttack(footman, flyer));

        footType.setCanTargetAir(true);
        assertTrue(world.orderAttack(footman, flyer));
    }

    @Test
    void aUnitWillNotAttackItself() {
        World world = new World(grass(20));
        Unit unit = world.createUnit(fighter("a", 5, 0, 60), 0, 2, 2);
        assertFalse(world.orderAttack(unit, unit));
    }

    @Test
    void anAttackOrderEndsWhenTheTargetDies() {
        World world = new World(grass(20));
        Unit attacker = world.createUnit(fighter("a", 5, 0, 60), 0, 2, 2);
        Unit defender = world.createUnit(fighter("d", 0, 0, 60), 1, 3, 2);
        world.orderAttack(attacker, defender);
        world.tick();

        world.kill(defender);
        // A swing already begun is unbreakable and runs to its end before the
        // unit decides anything, target alive or not, so give the animation
        // the few cycles it needs rather than reading the order mid-blow.
        for (int cycle = 0; cycle < 8; cycle++) {
            world.tick();
        }
        assertEquals(Unit.Order.STILL, attacker.order());
        assertEquals(null, attacker.target());
    }

    // ----------------------------------------------------------- retaliation

    /** A soldier: armed, mobile, and not a coward. */
    private static UnitType soldier(String ident, int reach) {
        UnitType type = fighter(ident, 6, 0, 100);
        type.setMaxAttackRange(reach);
        type.setReactRangePerson(4);
        type.setReactRangeComputer(6);
        type.setSightRange(20);
        type.setPriority(60);
        return type;
    }

    private static World open(int size) {
        World world = new World(grass(size));
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        return world;
    }

    @Test
    @DisplayName("a struck person guard tower recruits only fighters in its two-tile band")
    void aStruckPersonGuardTowerRecruitsOnlyFightersInItsTwoTileBand() {
        // BNE 2.02b FUN_0040a9d0 runs for buildings too. The authenticated
        // XHuman 10 call enters its person-owned scan with band two, then
        // gives the tower's ogre aggressor to the idle footman below it.
        World world = open(30);
        Unit attacker = world.createUnit(soldier("ogre", 1), 0, 10, 7);
        UnitType towerType = soldier("guard-tower", 6);
        towerType.setBuilding(true);
        towerType.setTileSize(2, 2);
        towerType.setSpeed(0);
        Unit tower = world.createUnit(towerType, 1, 10, 10);
        Unit inBand = world.createUnit(soldier("footman-in-band", 1),
                1, 10, 13);
        Unit outsideBand = world.createUnit(soldier("footman-outside-band", 1),
                1, 10, 14);

        world.battleNetSpatialHitHelp(attacker, tower);

        assertSame(attacker, inBand.battleNetPendingHelpAttack(),
                "the building-origin hit help did not bank its nearby defender");
        assertNull(outsideBand.battleNetPendingHelpAttack(),
                "person building hit help escaped native's two-tile band");
    }

    @Test
    @DisplayName("a soldier shelled from beyond its own reaction range still answers")
    void aUnitShotFromOutsideItsReactionRangeFightsBack() {
        // The property, which has outlived two implementations of it: a
        // ballista at eight tiles shelling a footman that notices at four must
        // not be a free win. Sniping being unanswerable is what this guards.
        //
        // It used to be phrased on ChonkCraft's HitUnit_AttackBack -- an
        // ATTACK_MOVE at the attacker's square, flushed onto the victim during
        // the blow. Retail BNE does not do that, and this implementation follows retail:
        // the ordinary damage path calls FUN_0040a9d0, which installs the
        // aggressor in the struck unit's offer (+0x54) and commands nothing at
        // all, and the offer is what the unit's next standing scan turns into
        // a chase. See BattleNetCombatSystem.applyDamage and
        // World.battleNetSpatialHitHelp.
        //
        // So the answer is delayed rather than absent, and it arrives as an
        // ATTACK on the unit rather than an ATTACK_MOVE at its ground. Both
        // are stated here in the terms a player would use -- it eventually
        // fights back, and it fights back at the thing that shot it.
        World world = open(30);
        Unit sniper = world.createUnit(soldier("ballista", 8), 0, 4, 4);
        Unit victim = world.createUnit(soldier("footman", 1), 1, 12, 4);
        assertEquals(8, victim.distanceTo(sniper), "the fixture should be out of reaction range");
        assertTrue(victim.distanceTo(sniper) > victim.type().reactRangePerson(),
                "the fixture must start out of reaction range or it proves nothing");

        world.hit(sniper, victim);

        assertEquals(sniper, victim.offeredTarget(),
                "the blow left the victim no idea who hit it");
        assertEquals(Unit.Order.STILL, victim.order(),
                "the blow itself commanded something; retail's hit path installs"
                        + " the offer and orders nothing");

        Unit answered = null;
        for (int cycle = 0; cycle < 30 && answered == null; cycle++) {
            world.tick();
            if (victim.order() == Unit.Order.ATTACK) {
                answered = victim.target();
            }
        }
        assertEquals(sniper, answered,
                "the footman stood and took it: a standing unit's scan is what"
                        + " turns the offer into a fight, and out of reaction"
                        + " range the offer is the only way it can know");
    }

    /**
     * The same footman, never shot, to prove the measurement above is about
     * being hit rather than about anything a standing soldier does anyway.
     *
     * <p>The sniper sits eight squares off and in plain sight the whole time.
     * A standing scan reaches four, so without a blow to carry the offer there
     * is nothing to answer and the footman must still be standing at the end.
     */
    @Test
    @DisplayName("a soldier that is never shot at leaves a distant enemy alone")
    void anUnhitSoldierDoesNotGoLookingBeyondItsReactionRange() {
        World world = open(30);
        Unit sniper = world.createUnit(soldier("ballista", 8), 0, 4, 4);
        Unit victim = world.createUnit(soldier("footman", 1), 1, 12, 4);
        assertTrue(victim.distanceTo(sniper) > victim.type().reactRangePerson(),
                "the fixture must start out of reaction range or it proves nothing");

        for (int cycle = 0; cycle < 30; cycle++) {
            world.tick();
        }
        assertEquals(null, victim.offeredTarget(),
                "nothing struck this unit, so nothing should have been offered to it");
        assertEquals(Unit.Order.STILL, victim.order(),
                "the footman went after something eight squares away that had"
                        + " never touched it, so the test above would pass on a"
                        + " port that had no offer at all");
    }

    @Test
    void anOrdinaryBneHitDoesNotInvokeTheChonkCraftCowardTail() {
        // Retail's direct-hit FUN_0040a9d0 offers the attacker through the
        // spatial-help path and commands nothing. HitUnit_RunAway is a
        // LegacyEngine tail, still covered separately for the inherited direct
        // spell path, and must not run a second reaction policy here.
        World world = open(30);
        Unit soldier = world.createUnit(soldier("grunt", 1), 0, 10, 10);
        UnitType peasantType = soldier("peasant", 1);
        peasantType.setCoward(true);
        Unit peasant = world.createUnit(peasantType, 1, 11, 10);
        int before = peasant.hitPoints();

        world.hit(soldier, peasant);
        assertTrue(peasant.hitPoints() < before, "the classified hit never landed");
        assertEquals(Unit.Order.STILL, peasant.order(),
                "the BNE hit path issued an immediate reaction command");
        world.tick();
        assertFalse(peasant.order() == Unit.Order.MOVE,
                "ordinary BNE damage ran the inherited coward flee tail");
    }

    @Test
    void anOrdinaryBneHitDoesNotArmTheChonkCraftAggressorLock() {
        // Retail's ordinary damage reaction is the spatial-help offer. The
        // 128-cycle UnderAttack grip belongs to LegacyEngine HitUnit_AttackBack
        // and must not be layered on top of the BNE policy.
        World world = open(30);
        UnitType sniperType = soldier("ballista", 8);
        sniperType.setReactRangePerson(0);
        sniperType.setReactRangeComputer(0);
        Unit sniper = world.createUnit(sniperType, 0, 5, 10);
        Unit victim = world.createUnit(soldier("footman", 1), 1, 20, 10);
        int before = victim.hitPoints();

        world.hit(sniper, victim);
        assertTrue(victim.hitPoints() < before, "the classified hit never landed");
        assertEquals(0, victim.underAttack(),
                "ordinary BNE damage armed LegacyEngine's UnderAttack counter");

        for (int cycle = 0; cycle < 36; cycle++) {
            world.tick();
        }
        assertEquals(0, victim.threshold(), "the threshold should have lapsed by now");
        assertEquals(0, victim.underAttack(),
                "the removed aggressor lock appeared after the spatial offer");
    }

    // ------------------------------------------------------------- the swing

    /** A fighter whose swing takes long enough to interrupt, and cannot walk. */
    private static UnitType rooted(String ident) {
        UnitType type = soldier(ident, 1);
        AnimationSet set = new AnimationSet(ident);
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of("frame 0", "wait 1")));
        // A chase that gets nowhere, so the only way a blow can land is by
        // finishing the swing that had already begun.
        set.put(AnimationSet.State.MOVE, Animation.parse("move",
                List.of("frame 0", "move 0", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("attack",
                List.of("unbreakable begin", "frame 25", "wait 8",
                        "frame 30", "attack", "unbreakable end", "wait 1")));
        set.put(AnimationSet.State.DEATH, Animation.parse("death",
                List.of("frame 50", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    void aSwingAlreadyBegunLandsAfterItsTargetHasGone() {
        World world = open(30);
        Unit attacker = world.createUnit(rooted("footman"), 0, 10, 10);
        Unit runner = world.createUnit(soldier("peon", 1), 1, 11, 10);
        int before = runner.hitPoints();

        world.orderAttack(attacker, runner);
        world.tick();
        assertTrue(attacker.animation().unbreakable(), "the swing should have begun");

        // Out of reach before the blow lands. Upstream finishes the swing; the
        // port used to drop it and set off walking, which made kiting free.
        world.orderMove(runner, 20, 10);
        // How far away it was on the cycle the blow landed, which is the
        // question. Reading the distance at the end of the window asked a
        // different one and got the wrong answer for a while: the runner
        // leaves reach, is hit at three squares, and then turns round and
        // walks back in because being hit makes it fight back, so by the last
        // cycle it is adjacent again and the fixture looked like it had never
        // left.
        int distanceWhenHit = -1;
        for (int cycle = 0; cycle < 12; cycle++) {
            world.tick();
            if (distanceWhenHit < 0 && runner.hitPoints() < before) {
                distanceWhenHit = runner.distanceTo(attacker);
            }
        }
        assertTrue(runner.hitPoints() < before, "the swing was cancelled mid-blow");
        assertTrue(distanceWhenHit > 1,
                "the blow landed while the runner was still in reach, at " + distanceWhenHit
                        + " squares, so this says nothing about a swing outliving its target");
    }

    // ------------------------------------------------------- minimum range

    @Test
    void aSiegeEngineBacksAwayFromSomethingTooClose() {
        // MinAttackRange was parsed and read by nothing, so a catapult
        // bombarded a footman leaning on it and there was no reason to close.
        World world = open(30);
        UnitType catapultType = soldier("catapult", 8);
        catapultType.setMinAttackRange(3);
        Unit catapult = world.createUnit(catapultType, 0, 10, 10);
        // A post rather than a footman: a real one chases, and a catapult
        // being run down is Warcraft II working as intended, not the thing
        // under test here.
        UnitType postType = soldier("post", 1);
        postType.setCanAttack(false);
        postType.setSpeed(0);
        postType.setHitPoints(1000);
        Unit footman = world.createUnit(postType, 1, 11, 10);
        footman.setHitPoints(1000);
        int before = footman.hitPoints();

        world.orderAttack(catapult, footman);
        // Long enough for a swing to have landed had one been allowed.
        for (int cycle = 0; cycle < 6; cycle++) {
            world.tick();
        }
        assertEquals(before, footman.hitPoints(),
                "it fired at something inside its minimum range");

        for (int cycle = 0; cycle < 200; cycle++) {
            world.tick();
        }
        assertTrue(catapult.distanceTo(footman) >= 3,
                "it never backed off far enough to use its weapon");
    }

    // ---------------------------------------------------------------- splash

    private static MissileType splash(String ident, int range, int factor) {
        return new MissileType(ident, null, MissileClass.POINT_TO_POINT,
                32, 32, 1, 1, 8, 1, range, factor, 0, null, null, false, 0, 0, false);
    }

    @Test
    void splashIsMeasuredToTheFootprint() {
        // A four-by-four hall struck dead centre is nought squares from the
        // impact and takes the blow whole. Measured to its top-left tile it
        // reported two or three and a radius-two blast skipped it entirely,
        // which is why catapults barely scratched large buildings.
        World world = open(30);
        world.setMissileTypes(java.util.Map.of("missile-rock", splash("missile-rock", 2, 2)));

        UnitType siegeType = soldier("catapult", 8);
        siegeType.setMissile("missile-rock");
        Unit siege = world.createUnit(siegeType, 0, 8, 12);

        UnitType hallType = fighter("hall", 0, 0, 5000);
        hallType.setTileSize(4, 4);
        hallType.setBuilding(true);
        hallType.setSpeed(0);
        Unit hall = world.createUnit(hallType, 1, 12, 10);
        int before = hall.hitPoints();

        // Aimed at the middle of the footprint, two tiles in from its corner:
        // measured to the corner that is distance two and a radius-two blast
        // stops short, and measured to the footprint it is nought.
        assertTrue(world.orderAttackGround(siege, 14, 12));
        for (int cycle = 0; cycle < 120 && hall.hitPoints() == before; cycle++) {
            world.tick();
        }
        assertTrue(hall.hitPoints() < before, "the blast missed a building it landed on");
    }

    @Test
    void splashSpareWhatItsFirerCouldNeverTarget() {
        World world = open(30);
        world.setMissileTypes(java.util.Map.of("missile-rock", splash("missile-rock", 3, 2)));

        UnitType siegeType = soldier("catapult", 8);
        siegeType.setMissile("missile-rock");
        siegeType.setCanTargetAir(false);
        Unit siege = world.createUnit(siegeType, 0, 4, 12);

        Unit ground = world.createUnit(soldier("grunt", 1), 1, 12, 12);
        UnitType flyerType = soldier("dragon", 1);
        flyerType.setAirUnit(true);
        flyerType.setLandUnit(false);
        Unit flyer = world.createUnit(flyerType, 1, 13, 12);
        int flyerBefore = flyer.hitPoints();
        int groundBefore = ground.hitPoints();

        world.hit(siege, ground);
        for (int cycle = 0; cycle < 60 && ground.hitPoints() == groundBefore; cycle++) {
            world.tick();
        }
        assertTrue(ground.hitPoints() < groundBefore, "the rock missed what it was thrown at");
        assertEquals(flyerBefore, flyer.hitPoints(),
                "a rock splashed an aircraft the thrower could never have aimed at");
    }

    @Test
    void sidesAreTakenFromPlayerSlots() {
        World world = new World(grass(20));
        Unit mine = world.createUnit(fighter("a", 5, 0, 60), 0, 2, 2);
        Unit theirs = world.createUnit(fighter("b", 5, 0, 60), 1, 4, 2);
        Unit alsoMine = world.createUnit(fighter("c", 5, 0, 60), 0, 6, 2);

        assertTrue(World.isEnemy(mine, theirs));
        assertFalse(World.isEnemy(mine, alsoMine));
    }
}
