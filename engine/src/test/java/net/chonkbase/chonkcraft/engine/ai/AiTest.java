package net.chonkbase.chonkcraft.engine.ai;

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
import net.chonkbase.chonkcraft.engine.unit.ResourceInfo;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.Test;

/** Tests for the computer player's managers. */
class AiTest {

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
        type.setAnimationSet(fighter());
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 600);
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
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 1200);
        return type;
    }

    private static UnitType farm() {
        UnitType type = new UnitType("unit-farm");
        type.setTileSize(2, 2);
        type.setHitPoints(400);
        type.setBuilding(true);
        type.setSupply(4);
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 500);
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

    /** Two active players, both computer-controlled. */
    private static Player[] twoComputers() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 2 ? PudMap.PlayerType.COMPUTER : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        return players;
    }

    // ---------------------------------------------------------------- forces

    @Test
    void aForceKnowsWhatItStillNeeds() {
        AiForce force = new AiForce(0);
        UnitType soldier = footman();
        force.setWanted(Map.of(soldier, 3));

        assertEquals(3, force.shortfall(soldier));
        assertFalse(force.isComplete());

        World world = new World(grass(20), twoComputers());
        for (int i = 0; i < 3; i++) {
            force.members().add(world.createUnit(soldier, 0, 2 + i, 2));
        }
        assertEquals(0, force.shortfall(soldier));
        assertTrue(force.isComplete());
    }

    @Test
    void aForcePrunesItsDead() {
        World world = new World(grass(20), twoComputers());
        AiForce force = new AiForce(0);
        UnitType soldier = footman();
        force.setWanted(Map.of(soldier, 2));

        Unit first = world.createUnit(soldier, 0, 2, 2);
        Unit second = world.createUnit(soldier, 0, 3, 2);
        force.members().add(first);
        force.members().add(second);
        assertEquals(2, force.size());

        world.kill(first);
        assertEquals(1, force.size(), "the dead should be dropped");
        assertEquals(1, force.shortfall(soldier), "and asked for again");
    }

    @Test
    void aForceKeepsALivingWorkerWhileItIsInsideAMine() {
        World world = new World(grass(20), twoComputers());
        AiForce force = new AiForce(0);
        UnitType workerType = peasant();
        force.setWanted(Map.of(workerType, 1));
        Unit worker = world.createUnit(workerType, 0, 3, 2);
        force.members().add(worker);

        worker.setRemoved(true);

        assertEquals(1, force.size(),
                "Removed means inside a mine as well as gone; AiForce prunes"
                        + " only members whose IsAlive is false");
        assertEquals(0, force.shortfall(workerType),
                "the living miner still fills the force's shopping list");
    }

    // -------------------------------------------------------------- managers

    @Test
    void anIdleWorkerIsSentToTheMine() {
        World world = new World(grass(40), twoComputers());
        world.createUnit(townHall(), 0, 2, 2);
        world.createUnit(goldMine(), 15, 20, 20);
        Unit worker = world.createUnit(peasant(), 0, 8, 8);
        world.enableAi(0);

        assertEquals(Unit.Order.STILL, worker.order());
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
        }
        assertEquals(Unit.Order.HARVEST, worker.order(), "the AI should put its worker to work");
    }

    @Test
    void theAiSpendsItsGoldOnWhatItAskedFor() {
        World world = new World(grass(40), twoComputers());
        world.player(0).set(Resource.GOLD, 5000);
        Unit hall = world.createUnit(townHall(), 0, 2, 2);
        world.recalculateSupply();

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.need(footman(), 1);

        int before = world.units().size();
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 10; cycle++) {
            world.tick();
            if (world.units().size() > before) {
                break;
            }
        }
        assertEquals(before + 1, world.units().size(), "the AI never trained anything");
        assertTrue(world.player(0).get(Resource.GOLD) < 5000, "and never paid for it");
        assertTrue(hall.isAlive());
    }

    @Test
    void battleNetProfileDoesNotRunTheGenericAiScheduler() {
        World world = new World(grass(40), twoComputers());
        world.player(0).set(Resource.GOLD, 5000);
        Unit hall = world.createUnit(townHall(), 0, 2, 2);
        world.recalculateSupply();

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.need(footman(), 1);
        byte[] profile = new byte[128];
        profile[0] = 100;
        profile[100] = 120;
        profile[102] = 122;
        profile[104] = 2; // WAIT 100: a live, quiescent retail program.
        profile[105] = 100;
        profile[120] = (byte) 0xff;
        ai.setBattleNetBuildProfile(profile, 0);

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 2; cycle++) {
            world.tick();
        }

        assertEquals(5000, world.player(0).get(Resource.GOLD));
        assertEquals(null, hall.producing(),
                "retail BNE is driven by ai.bin, not ChonkCraft's AiEachSecond queue");
    }

    @Test
    void incompleteBattleNetProfileStillOwnsTheAiScheduler() {
        World world = new World(grass(40), twoComputers());
        world.player(0).set(Resource.GOLD, 5000);
        Unit hall = world.createUnit(townHall(), 0, 2, 2);
        world.recalculateSupply();

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.need(footman(), 1);
        ai.setBattleNetBuildProfile(null, 9);

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 2; cycle++) {
            world.tick();
        }

        assertEquals(5000, world.player(0).get(Resource.GOLD));
        assertEquals(null, hall.producing(),
                "an undecoded retail profile must not fall through to a second AI personality");
    }

    @Test
    void theAiPutsUpABuildingItAskedFor() {
        World world = new World(grass(40), twoComputers());
        world.player(0).set(Resource.GOLD, 5000);
        world.createUnit(townHall(), 0, 2, 2);
        world.createUnit(peasant(), 0, 8, 8);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.need(farm(), 1);

        boolean built = false;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 60 && !built; cycle++) {
            world.tick();
            built = world.units().stream()
                    .anyMatch(u -> u.type().ident().equals("unit-farm")
                            && u.order() == Unit.Order.STILL);
        }
        assertTrue(built, "the AI never finished its farm");
    }

    @Test
    void aCompleteForceIsSentAtTheEnemy() {
        World world = new World(grass(40), twoComputers());
        // Two computers are allies, by CPlayer::Init and by this implementation's own
        // establishDiplomacy, and the force manager now asks: upstream's
        // EnemyUnitFinder skips anything IsEnemy says is not one. Without this
        // the fixture is a computer looking for somebody to attack on a map
        // where everybody is on its side, which is not the case being tested.
        world.setAllied(0, 1, false);
        world.setAllied(1, 0, false);
        world.createUnit(townHall(), 0, 2, 2);
        Unit enemy = world.createUnit(townHall(), 1, 30, 30);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        UnitType soldier = footman();
        AiForce force = ai.force(0);
        force.setWanted(Map.of(soldier, 2));

        Unit first = world.createUnit(soldier, 0, 6, 6);
        Unit second = world.createUnit(soldier, 0, 7, 6);

        // The force fills, and the script's word launches it: upstream's
        // manager moves only Defending and Attacking forces
        // and the attack
        // rides the handed-off army.
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 2; cycle++) {
            world.tick();
        }
        ai.handOffForAttack(0);
        boolean setOff = false;
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3 && !setOff; cycle++) {
            world.tick();
            setOff = ai.forces().stream().anyMatch(f -> f.state() == AiForce.State.ATTACKING);
        }
        assertTrue(setOff, "the force never attacked");
        // Sent, rather than specifically swinging. The fixture gives its units
        // no sight range, so nothing on this map is visible to anybody, and a
        // force aimed at an enemy it cannot see marches at it instead --
        // upstream's AiForce::Attack aims at a position for exactly that
        // reason. AiForceTargetingTest covers which of the two it picks.
        assertTrue(first.order() != Unit.Order.STILL || second.order() != Unit.Order.STILL,
                "no member was sent anywhere");
        assertTrue(enemy.isAlive());
    }

    @Test
    void anAiWithNothingLeftDoesNothing() {
        World world = new World(grass(20), twoComputers());
        world.enableAi(0);
        // No units at all: the managers must not throw.
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
        }
        assertEquals(0, world.units().size());
    }

    @Test
    void onlyComputerSlotsGetAnAi() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            PudMap.PlayerType type = switch (i) {
                case 0 -> PudMap.PlayerType.PERSON;
                case 1, 2 -> PudMap.PlayerType.COMPUTER;
                default -> PudMap.PlayerType.NOBODY;
            };
            players[i] = new Player(i, type, PudMap.Race.HUMAN);
        }
        World world = new World(grass(20), players);

        assertEquals(2, world.enableAiForComputerPlayers());
        assertTrue(world.ais().containsKey(1));
        assertTrue(world.ais().containsKey(2));
        assertFalse(world.ais().containsKey(0), "the human should not be played for");
    }

    @Test
    void theStandingPlanKeepsSupplyAhead() {
        World world = new World(grass(40), twoComputers());
        world.player(0).set(Resource.GOLD, 10_000);
        world.createUnit(townHall(), 0, 2, 2);
        world.createUnit(peasant(), 0, 8, 8);
        world.createUnit(farm(), 0, 12, 2);
        world.recalculateSupply();

        AiPlayer ai = world.enableAi(0);
        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 3; cycle++) {
            world.tick();
        }
        // With a hall and a farm it has plenty of room, so it should be
        // spending on workers rather than more farms.
        assertTrue(world.player(0).supply() > world.player(0).demand(),
                "supply " + world.player(0).supply() + " demand " + world.player(0).demand());
    }
}
