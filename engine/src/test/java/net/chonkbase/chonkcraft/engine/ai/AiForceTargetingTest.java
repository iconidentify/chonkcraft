package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A force the AI has declared fills up, and then goes somewhere.
 *
 * <p>Three faults, all of which read from outside as a defensive personality.
 *
 * <p>A force only ever enlisted units whose order was {@code STILL}, and
 * {@code checkUnits} runs first in {@code AiEachSecond}'s order and sends every
 * idle gatherer off to harvest. By the time the force manager looked there was
 * nothing standing still left to take. {@code hum-08-peasant} declares
 * {@code AiForce(1, {AiWorker(), 7})} and then {@code AiAttackWithForce(1)};
 * after 300 simulated seconds seven peasants were alive and not one had been
 * enlisted, so the eighth human mission's siege by seven peasants did not
 * happen. {@code AiForceManager::Assign} never looks at what a unit is doing.
 *
 * <p>The order was then given and its answer thrown away.
 * {@code World.orderAttack} refuses a target this force's weapons cannot hit
 * and nothing looked, so a force whose nearest enemy was a flyer stood still
 * for the rest of the mission.
 *
 * <p>And the target was chosen by raw distance with no thought for whether the
 * AI could see it. {@code COrder_Attack::AutoSelectTarget} drops a goal the
 * unit's side cannot see, so an attack aimed at something fifty tiles off in
 * unexplored ground was cancelled on the very next cycle and the force dropped
 * back to standing -- and the AI, thinking once a second, gave the identical
 * order again. Measured on {@code level06h} player 5: 2392 attacking cycles
 * against 299 mobilized seconds, exactly one cycle of attacking per second for
 * five minutes, and twenty-one of the thirty-four mobilized slots showed the
 * same ratio of 1.00. Upstream's {@code AiForce::Attack} never aims at a unit:
 * it issues {@code CommandAttack(unit, GoalPos, nullptr)} and the force walks
 * across the map and fights what it finds.
 */
class AiForceTargetingTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static AnimationSet fighter() {
        AnimationSet set = new AnimationSet("f");
        set.put(AnimationSet.State.STILL, Animation.parse("s", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("m",
                List.of("frame 0", "move 16", "wait 1", "frame 5", "move 16", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("a",
                List.of("frame 25", "attack", "wait 2")));
        set.put(AnimationSet.State.DEATH, Animation.parse("d", List.of("frame 50", "wait 1")));
        return set;
    }

    private static UnitType peasant() {
        UnitType type = new UnitType("unit-peasant");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setDemand(1);
        type.setSightRange(4);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(3);
        type.setMaxAttackRange(1);
        type.setAnimationSet(fighter());
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 400);
        ResourceInfo gold = new ResourceInfo(Resource.GOLD);
        gold.setCapacity(100);
        gold.setWaitAtResource(2);
        gold.setWaitAtDepot(2);
        type.gathering().put(Resource.GOLD, gold);
        return type;
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setDemand(1);
        type.setSightRange(4);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        // The real footman's reaction ranges, which the attack-march's own
        // acquisition scans by; a fixture without them never notices the
        // neighbour it was launched at.
        type.setReactRangePerson(4);
        type.setReactRangeComputer(6);
        type.setAnimationSet(fighter());
        return type;
    }

    /** Something only an anti-air weapon may be aimed at. */
    private static UnitType dragon() {
        UnitType type = new UnitType("unit-dragon");
        type.setTileSize(1, 1);
        type.setHitPoints(100);
        type.setSpeed(10);
        type.setAirUnit(true);
        type.setSightRange(6);
        type.setAnimationSet(fighter());
        return type;
    }

    private static UnitType townHall() {
        UnitType type = new UnitType("unit-town-hall");
        type.setTileSize(4, 4);
        type.setHitPoints(1200);
        type.setBuilding(true);
        type.setSupply(20);
        type.setSightRange(4);
        type.stores().add(Resource.GOLD);
        return type;
    }

    private static UnitType goldMine() {
        UnitType type = new UnitType("unit-gold-mine");
        type.setTileSize(3, 3);
        type.setHitPoints(100_000);
        type.setBuilding(true);
        // GivesResource = "gold", CanHarvest = true (scripts/units.legacy-declaration:288).
        type.setGivesResource(UnitType.Resource.GOLD);
        type.setCanHarvest(true);
        return type;
    }

    /** A computer, an enemy person, and the neutral slot the mines belong to. */
    private static Player[] computerVersusPerson() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            PudMap.PlayerType type = switch (i) {
                case 0 -> PudMap.PlayerType.COMPUTER;
                case 1 -> PudMap.PlayerType.PERSON;
                case World.NEUTRAL_PLAYER -> PudMap.PlayerType.NEUTRAL;
                default -> PudMap.PlayerType.NOBODY;
            };
            players[i] = new Player(i, type, PudMap.Race.HUMAN);
        }
        return players;
    }

    private static World world(int size) {
        World world = new World(grass(size), computerVersusPerson());
        world.establishDiplomacy();
        return world;
    }

    @Test
    @DisplayName("force enlistment follows the owner's swap-removed unit table")
    void aForceEnlistsInPlayerUnitTableOrderAfterADeath() {
        World world = world(32);
        world.createUnit(peasant(), 0, 2, 2);
        Unit removedFromMiddle = world.createUnit(peasant(), 0, 3, 2);
        UnitType soldierType = footman();
        Unit olderSoldier = world.createUnit(soldierType, 0, 4, 2);
        Unit lastSoldier = world.createUnit(soldierType, 0, 5, 2);
        world.kill(removedFromMiddle);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        AiForce force = ai.force(1);
        force.setWanted(Map.of(olderSoldier.type(), 1));
        ai.enlistNow(world, force);

        assertSame(lastSoldier, force.members().getFirst(),
                "CPlayer::RemoveUnit swaps the final pointer into the hole, so that"
                        + " soldier is visited before the older one");
    }

    @Test
    @DisplayName("a trainee joins its waiting force before the next AI thought")
    void aTrainedUnitIsEnlistedAtCompletion() {
        World world = world(32);
        Unit trainer = world.createUnit(townHall(), 0, 8, 8);
        UnitType soldier = footman();
        soldier.costs().put(Resource.TIME, 1);
        world.recalculateSupply();

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        AiForce force = ai.force(2);
        force.setWanted(Map.of(soldier, 1));

        int before = world.units().size();
        assertTrue(world.orderTrain(trainer, soldier));
        for (int cycle = 0; cycle < AiPlayer.FIRST_THINK_CYCLE
                && world.units().size() == before; cycle++) {
            world.tick();
        }

        assertEquals(before + 1, world.units().size(),
                "the fixture did not finish training before the first AI thought");
        Unit trained = world.units().getLast();
        assertSame(trained, force.members().getFirst(),
                "AiTrainingComplete deferred Force.Assign to the next force-manager pass");
        assertTrue(force.isComplete(),
                "AiWaitForce must observe the delivered unit on that next thought");
    }

    @Test
    @DisplayName("an attacked force member calls its goal-less marching siblings for help")
    void aMarchingForceAnswersAHitOnOneOfItsMembers() {
        World world = world(40);
        UnitType soldier = footman();
        Unit brother = world.createUnit(soldier, 0, 8, 8);
        Unit defender = world.createUnit(soldier, 0, 9, 8);
        Unit attacker = world.createUnit(soldier, 1, 12, 8);
        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        AiForce force = ai.force(1);
        force.restore(Map.of(soldier, 2), List.of(brother, defender),
                AiForce.State.ATTACKING, false);
        assertTrue(world.orderAttackMove(brother, 30, 30));
        brother.setWaitCycles(8);

        ai.helpMe(world, attacker, defender);

        assertEquals(Unit.Order.ATTACK, brother.order(),
                "the goal-less attack order was not eligible to help its force-mate");
        assertSame(attacker, brother.target());
        assertEquals(Unit.Order.ATTACK_MOVE, brother.savedOrder(),
                "AiHelpMe saves a position attack behind the temporary unit attack");
        assertEquals(attacker.tileX(), brother.savedAttackMoveX());
        assertEquals(attacker.tileY(), brother.savedAttackMoveY());
        world.tick();
        assertEquals(0, brother.waitCycles(),
                "popping the help order retained the interrupted march's PF_WAIT");
    }

    @Test
    @DisplayName("a complete scripted attack force still waits for its attack step")
    void aReadyAttackForceDoesNotMobilizeAsBaseDefence() {
        World world = world(40);
        UnitType soldier = footman();
        Unit waiting = world.createUnit(soldier, 0, 8, 8);
        Unit defender = world.createUnit(soldier, 0, 20, 20);
        Unit attacker = world.createUnit(soldier, 1, 22, 20);
        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        AiForce force = ai.force(1);
        force.restore(Map.of(soldier, 1), List.of(waiting),
                AiForce.State.READY, false);

        ai.helpMe(world, attacker, defender);

        assertEquals(Unit.Order.STILL, waiting.order(),
                "Completed is separate from AiForceAttackingState upstream: the named"
                        + " attack force remains Waiting and AiHelpMe must leave it home"
                        + " until the script calls AiAttackWithForce");
        assertEquals(AiForce.State.READY, force.state());
        assertFalse(force.defending());
    }

    @Test
    @DisplayName("explicit script-force assignment keeps upstream's GroupId indexing")
    void scriptForceTwoDoesNotCallItsListedSiblingsThroughGroupThree() {
        World world = world(40);
        UnitType soldier = footman();
        Unit brother = world.createUnit(soldier, 0, 8, 8);
        Unit defender = world.createUnit(soldier, 0, 9, 8);
        Unit attacker = world.createUnit(soldier, 1, 12, 8);
        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);

        // Allocate script force one first, so force two is upstream internal
        // slot one. The explicit Assign call nevertheless writes GroupId 3;
        // AiHelpMe indexes empty internal slot two with it.
        ai.force(1).setWanted(Map.of(dragon(), 1));
        AiForce forceTwo = ai.force(2);
        forceTwo.setWanted(Map.of(soldier, 2));
        ai.enlistNow(world, forceTwo);
        assertEquals(List.of(brother, defender), forceTwo.members());

        ai.helpMe(world, attacker, defender);

        assertEquals(Unit.Order.STILL, brother.order(),
                "membership in UnitTypeBuilt's force vector is not the lookup AiHelpMe"
                        + " performs: it follows the explicitly assigned GroupId, including"
                        + " the shipped script-number/internal-slot mismatch");
    }

    @Test
    @DisplayName("the force flood can settle on a dying building still in the tile cache")
    void aDyingBuildingRemainsAForceFloodTarget() {
        World world = world(48);
        Unit seeker = world.createUnit(footman(), 0, 5, 10);
        UnitType rubble = new UnitType("unit-destroyed-4x4-place");
        rubble.setTileSize(4, 4);
        rubble.setBuilding(true);
        rubble.setHitPoints(255);
        rubble.setVanishes(true);
        UnitType doomedType = townHall();
        doomedType.setCorpse(rubble.ident());
        world.setUnitTypes(Map.of(rubble.ident(), rubble));
        Unit dying = world.createUnit(doomedType, 1, 10, 10);
        world.createUnit(townHall(), 1, 30, 10);
        world.kill(dying);
        world.tick();

        assertEquals(rubble, dying.type(),
                "the fixture never reached the destroyed-place cache transition");

        assertSame(dying, world.findEnemyByFlood(seeker, true),
                "EnemyUnitFinder filtered a dying UnitCache entry that Select returns");
        assertNull(world.findRallyPoint(seeker, seeker.distanceTo(dying)),
                "AiEnemyUnitsInDistance filtered the same cache entry and invented"
                        + " a quiet rally point beside the destroyed building");
    }

    @Test
    @DisplayName("a force of seven workers fills even though every one of them is at the mine")
    void aForceOfWorkersFillsWhileTheyAreHarvesting() {
        World world = world(40);
        world.createUnit(townHall(), 0, 2, 2);
        world.createUnit(goldMine(), World.NEUTRAL_PLAYER, 10, 10);
        world.createUnit(townHall(), 1, 30, 30);
        UnitType worker = peasant();
        for (int i = 0; i < 7; i++) {
            world.createUnit(worker, 0, 4 + i, 8);
        }

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        AiForce force = ai.force(1);
        force.setWanted(Map.of(worker, 7));

        // One second, so checkUnits has sent them all to the mine first --
        // which is the whole of the bug.
        for (int cycle = 0; cycle <= World.CYCLES_PER_SECOND; cycle++) {
            world.tick();
        }
        long harvesting = world.units().stream()
                .filter(u -> u.player() == 0 && u.order() == Unit.Order.HARVEST)
                .count();
        assertTrue(harvesting > 0,
                "the fixture must have the workers gathering before the force manager runs,"
                        + " or it proves nothing about the order the two managers run in");

        // What the bug actually was, watched directly: a worker being taken
        // into the force while it is gathering. Counting the force at the end
        // instead measured everything except that -- a member that walks into
        // the mine is off the map and prune drops it, a force that has set off
        // stops enlisting, and both of those move the final tally around for
        // reasons that have nothing to do with whether a harvesting worker can
        // be enlisted at all.
        java.util.Set<Unit> seen = new java.util.HashSet<>();
        int enlistedWhileGathering = 0;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 10; cycle++) {
            world.tick();
            for (Unit member : force.members()) {
                if (seen.add(member) && member.order() == Unit.Order.HARVEST) {
                    enlistedWhileGathering++;
                }
            }
        }

        assertEquals(7, seen.size(),
                "the force never filled: it only ever enlisted units whose order was STILL,"
                        + " and checkUnits had already sent every one of them to the gold mine."
                        + " That is hum-08-peasant's seven-peasant siege never leaving home.");
        assertTrue(enlistedWhileGathering > 0,
                "every one of the seven was enlisted while it happened to be standing still,"
                        + " so this proves nothing about the gathering ones -- which are the"
                        + " ones hum-08-peasant has");
    }

    @Test
    @DisplayName("a force marches at an enemy it cannot see and swings at one it can")
    void anUnseenEnemyIsMarchedAtAndASeenOneIsAttacked() {
        World world = world(60);
        UnitType soldier = footman();
        world.createUnit(townHall(), 0, 2, 2);
        Unit far = world.createUnit(soldier, 1, 50, 50);
        Unit first = world.createUnit(soldier, 0, 6, 6);
        Unit second = world.createUnit(soldier, 0, 7, 6);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        AiForce force = ai.force(0);
        force.setWanted(Map.of(soldier, 2));
        // One thought fills the force from the standing units; the script's
        // own handoff is what launches it -- upstream's manager moves only
        // Defending and Attacking forces (AiForceManager::Update,
        // The game ), and a filled force without the word
        // stands as a home guard forever.
        for (int fill = 0; fill < World.CYCLES_PER_SECOND + 1; fill++) {
            world.tick();
        }
        ai.handOffForAttack(0);

        assertFalse(world.isVisibleTo(0, far.tileX(), far.tileY()),
                "the enemy must start outside the AI's sight or this proves nothing");

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
        }

        // The old behaviour: an attack order at an unseen goal, cancelled by
        // AutoSelectTarget on the next cycle, leaving the unit standing.
        assertNotEquals(Unit.Order.STILL, first.order(),
                "the force was left standing: an attack aimed at a unit fifty tiles away in"
                        + " unexplored ground is dropped by AutoSelectTarget within a cycle,"
                        + " every second, forever. Upstream aims at a position instead.");
        assertTrue(first.tileX() > 6 || first.tileY() > 6,
                "and it never actually went anywhere: it was at " + first.tileX() + ","
                        + first.tileY() + " after three seconds of marching");

    }

    /**
     * The control for the test above: the same force, an enemy it can see.
     *
     * <p>Without this pair the marching test passes on anything that moves a
     * unit. Marching is the answer to an unseen goal and only to that; a
     * visible one is fought where it stands, which is the behaviour the
     * campaign's working slots already had and which must not be traded away.
     */
    @Test
    @DisplayName("an enemy standing in plain sight is fought, not walked towards")
    void aSeenEnemyIsAttacked() {
        World world = world(40);
        UnitType soldier = footman();
        world.createUnit(townHall(), 0, 2, 2);
        Unit first = world.createUnit(soldier, 0, 6, 6);
        Unit second = world.createUnit(soldier, 0, 7, 6);
        // Seen through a watcher's eyes, not the soldiers' own: the members
        // carry the real footman's reaction ranges now, so an enemy close
        // enough for them to see is an enemy they engage by themselves
        // before the script can hand the force off -- and this test is
        // about the launch. The watcher has eyes and no axe.
        UnitType watcher = new UnitType("unit-scout");
        watcher.setTileSize(1, 1);
        watcher.setHitPoints(30);
        watcher.setSpeed(10);
        watcher.setLandUnit(true);
        watcher.setSightRange(9);
        world.createUnit(watcher, 0, 16, 6);
        Unit near = world.createUnit(soldier, 1, 20, 6);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        AiForce force = ai.force(0);
        force.setWanted(Map.of(soldier, 2));
        // One thought fills the force from the standing units; the script's
        // own handoff is what launches it -- upstream's manager moves only
        // Defending and Attacking forces (AiForceManager::Update,
        // The game ), and a filled force without the word
        // stands as a home guard forever.
        for (int fill = 0; fill < World.CYCLES_PER_SECOND + 1; fill++) {
            world.tick();
        }
        ai.handOffForAttack(0);

        assertTrue(world.isVisibleTo(0, near.tileX(), near.tileY()),
                "the enemy must start inside the AI's sight or this is the unseen case again");

        // Watched every cycle rather than read at the end, and the difference
        // is the whole test. These units are built with no reaction range on
        // purpose -- that is what stops them acquiring the enemy through their
        // own idle scan and leaves the force manager as the only thing that
        // can give them an order -- and a unit with no reaction range drops an
        // attack order on the cycle after it is given, because autoSelectTarget
        // keeps a goal only within max(reactRange, reach). So the order exists
        // for exactly one cycle, and reading first.target() after sixty cycles
        // only saw it because the AI thought on cycle 60 and the fixture ticked
        // to 60. Staggering the players across the second, as GameLogicLoop
        // does, moves the thought to cycle 7 and the assertion sees nothing --
        // with no behaviour changed at all.
        Unit.Order firstOrderGiven = null;
        Unit engaged = null;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 2; cycle++) {
            world.tick();
            if (firstOrderGiven == null && first.order() != Unit.Order.STILL) {
                firstOrderGiven = first.order();
            }
            if (engaged == null) {
                // The rally route may put either equally aggressive member
                // at the front. The behaviour under test belongs to the
                // force's attack-march, not to the lower permanent id:
                // whichever reaches the enemy first must acquire it.
                engaged = first.target() != null ? first.target() : second.target();
            }
        }

        // The launch is CommandAttack at the enemy's ground for every member
        // -- the attack-march -- and the march's own first
        // execute acquires the neighbour it can already see and hit. What
        // this rules out is the old plain walk that strolled past a visible
        // enemy with its weapon slung.
        assertEquals(Unit.Order.ATTACK_MOVE, firstOrderGiven,
                "the first order this unit was given must be the attack-march at the"
                        + " enemy's ground, which is how AiForce::Attack launches every"
                        + " member");
        assertEquals(near, engaged,
                "an enemy the AI can see and stand beside must be acquired where it"
                        + " stands by the march's own targeting");
    }

    /**
     * The launch splits the force by temperament.
     *
     * <p>{@code AiForce::Attack} walks its members
     * twice: once to find the first aggressive unit and call it the leader,
     * then once to hand out orders -- {@code CommandAttack} at the goal for
     * the aggressive, {@code CommandDefend} on the leader for a coward
     * following an army, and a plain {@code CommandMove} for a coward with no
     * leader to follow. A peasant is {@code Coward} in the shipped data, so
     * level08h's siege by seven peasants walks to the human town and only
     * fights when struck; this implementation used to march everyone with the attack
     * order, and the trace showed every peasant wearing an attack upstream
     * never gives one.
     */
    @Test
    @DisplayName("a mixed force launches its soldier attacking and its coward following")
    void aCowardLaunchesFollowingItsAggressiveLeader() {
        World world = world(40);
        UnitType soldier = footman();
        UnitType coward = peasant();
        coward.setCoward(true);
        world.createUnit(townHall(), 0, 2, 2);
        Unit armed = world.createUnit(soldier, 0, 6, 6);
        Unit timid = world.createUnit(coward, 0, 7, 6);
        world.createUnit(townHall(), 1, 30, 30);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        AiForce force = ai.force(0);
        force.setWanted(Map.of(soldier, 1, coward, 1));
        // One thought fills the force from the standing units; the script's
        // own handoff is what launches it -- upstream's manager moves only
        // Defending and Attacking forces (AiForceManager::Update,
        // The game ), and a filled force without the word
        // stands as a home guard forever.
        for (int fill = 0; fill < World.CYCLES_PER_SECOND + 1; fill++) {
            world.tick();
        }
        ai.handOffForAttack(0);

        assertTrue(armed.isAggressive() && !timid.isAggressive(),
                "the fixture needs one aggressive member and one coward, or the"
                        + " launch has nothing to split");

        Unit.Order armedFirst = null;
        Unit.Order timidFirst = null;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
            if (armedFirst == null && armed.order() != Unit.Order.STILL) {
                armedFirst = armed.order();
            }
            if (timidFirst == null && timid.order() != Unit.Order.STILL) {
                timidFirst = timid.order();
            }
        }

        assertEquals(Unit.Order.ATTACK_MOVE, armedFirst,
                "the aggressive member must be launched with the attack-march");
        assertEquals(Unit.Order.FOLLOW, timidFirst,
                "a coward with an aggressive leader must be sent to follow it,"
                        + " not to attack: CommandDefend on the leader is upstream's"
                        + " order for it");
    }

    @Test
    @DisplayName("a force launch staggers each group of five members")
    void aForceLaunchStaggersEachGroupOfFiveMembers() {
        World world = world(48);
        UnitType soldier = footman();
        world.createUnit(townHall(), 1, 42, 42);
        List<Unit> members = new java.util.ArrayList<>();
        for (int i = 0; i < 6; i++) {
            members.add(world.createUnit(soldier, 0, 4 + i, 4));
        }

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        AiForce carrier = new AiForce(100);
        carrier.restore(Map.of(soldier, 6), members, AiForce.State.READY, false);
        ai.forces().add(carrier);

        ai.think(world);

        assertEquals(0, members.get(4).waitCycles(),
                "the first five members should launch together");
        assertEquals(1, members.get(5).waitCycles(),
                "AiForce::Attack assigns unit.Wait = memberIndex / 5 before"
                        + " issuing the command");
    }

    /** The no-leader half: a force of cowards makes a plain walk. */
    @Test
    @DisplayName("a force of nothing but cowards walks to the goal unarmed")
    void aForceOfCowardsWalksInsteadOfAttacking() {
        World world = world(40);
        UnitType coward = peasant();
        coward.setCoward(true);
        world.createUnit(townHall(), 0, 2, 2);
        Unit first = world.createUnit(coward, 0, 6, 6);
        Unit second = world.createUnit(coward, 0, 7, 6);
        world.createUnit(townHall(), 1, 30, 30);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        AiForce force = ai.force(0);
        force.setWanted(Map.of(coward, 2));
        // One thought fills the force from the standing units; the script's
        // own handoff is what launches it -- upstream's manager moves only
        // Defending and Attacking forces (AiForceManager::Update,
        // The game ), and a filled force without the word
        // stands as a home guard forever.
        for (int fill = 0; fill < World.CYCLES_PER_SECOND + 1; fill++) {
            world.tick();
        }
        ai.handOffForAttack(0);

        Unit.Order firstGiven = null;
        Unit.Order secondGiven = null;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
            if (firstGiven == null && first.order() != Unit.Order.STILL) {
                firstGiven = first.order();
            }
            if (secondGiven == null && second.order() != Unit.Order.STILL) {
                secondGiven = second.order();
            }
        }

        assertEquals(Unit.Order.MOVE, firstGiven,
                "a coward with no aggressive leader is given a plain move at the"
                        + " goal -- level08h's seven peasants walk to the siege");
        assertEquals(Unit.Order.MOVE, secondGiven,
                "and so is every other coward in the force");
    }

    @Test
    @DisplayName("a force whose nearest enemy it cannot hit goes for the next one")
    void aForceSkipsATargetItsWeaponsCannotReach() {
        World world = world(40);
        UnitType soldier = footman();
        world.createUnit(townHall(), 0, 2, 2);
        Unit first = world.createUnit(soldier, 0, 6, 6);
        Unit second = world.createUnit(soldier, 0, 7, 6);
        // The nearest thing is a dragon overhead, which a footman may not be
        // aimed at: orderAttack refuses it and used to be asked once and no
        // more. The town hall behind it is a legal target.
        Unit flyer = world.createUnit(dragon(), 1, 8, 6);
        Unit hall = world.createUnit(townHall(), 1, 10, 6);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        AiForce force = ai.force(0);
        force.setWanted(Map.of(soldier, 2));
        assertTrue(first.distanceTo(flyer) < first.distanceTo(hall),
                "the untouchable target must be the nearer one or this proves nothing");
        assertFalse(world.orderAttack(first, flyer),
                "a footman must not be able to be aimed at a flyer, or the fixture is wrong");
        first.setOrder(Unit.Order.STILL);
        first.setTarget(null);

        // One thought fills the force from the standing units; the script's
        // own handoff is what launches it -- upstream's manager moves only
        // Defending and Attacking forces (AiForceManager::Update,
        // The game ), and a filled force without the word
        // stands as a home guard forever.
        for (int fill = 0; fill < World.CYCLES_PER_SECOND + 1; fill++) {
            world.tick();
        }
        ai.handOffForAttack(0);

        // The target it was actually given, caught on the cycle it was given.
        // With no reaction range on these types an attack order does not
        // survive to the end of the window -- see aSeenEnemyIsAttacked.
        Unit aimedAt = null;
        boolean secondWasSent = false;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
            if (aimedAt == null && first.target() != null) {
                aimedAt = first.target();
            }
            secondWasSent = secondWasSent || second.target() == hall
                    || second.order() != Unit.Order.STILL;
        }

        assertEquals(hall, aimedAt,
                "the force stopped at the first candidate its weapons refused. orderAttack"
                        + " answers whether it took the order and nothing was reading it, so a"
                        + " force whose closest enemy was a flyer never attacked anything.");
        assertTrue(ai.forces().stream().anyMatch(f -> f.state() == AiForce.State.ATTACKING),
                "and the force never set off -- the handed-off army carries the"
                        + " attack, the script's own slot going back to gathering");
        assertTrue(secondWasSent,
                "the rest of the force was left behind");
    }

    @Test
    @DisplayName("only three attacking forces spend pathing work in one AI thought")
    void aFourthAttackForceWaitsForTheNextPathingBudget() {
        World world = world(80);
        UnitType soldier = footman();
        world.createUnit(townHall(), 1, 70, 70);
        List<Unit> army = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            army.add(world.createUnit(soldier, 0, 4 + i, 4));
        }

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        for (int wave = 0; wave < 4; wave++) {
            AiForce carrier = new AiForce(100 + wave);
            carrier.restore(Map.of(soldier, 2),
                    army.subList(wave * 2, wave * 2 + 2),
                    AiForce.State.ATTACKING, false);
            carrier.setGoal(70, 70);
            ai.forces().add(carrier);
        }
        for (Unit member : army.subList(0, 6)) {
            member.setOrder(Unit.Order.ATTACK_MOVE);
        }
        assertTrue(army.subList(6, 8).stream().allMatch(
                member -> member.order() == Unit.Order.STILL),
                "the fourth force must be idle so an erroneous update visibly re-sends it");

        ai.think(world);

        assertTrue(ai.forces().subList(0, 3).stream().allMatch(
                force -> force.state() == AiForce.State.ATTACKING),
                "the first three active attacks did not consume the manager's budget");
        assertTrue(ai.forces().get(3).members().stream().allMatch(
                member -> member.order() == Unit.Order.STILL),
                "AiForceManager starts maxPathing at two and returns after updating three"
                        + " attacking forces. Updating the fourth re-sent levelx12h's idle"
                        + " grunt on cycle 519, where upstream leaves it standing.");
    }
}
