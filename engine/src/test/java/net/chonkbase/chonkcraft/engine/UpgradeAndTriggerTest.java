package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.trigger.TriggerSystem;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import net.chonkbase.chonkcraft.engine.upgrade.Upgrade;
import org.junit.jupiter.api.Test;

/** Tests for researchable upgrades and campaign triggers. */
class UpgradeAndTriggerTest {

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

    private static AnimationSet fighter() {
        AnimationSet set = new AnimationSet("f");
        set.put(AnimationSet.State.STILL, Animation.parse("s", List.of("frame 0", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("a",
                List.of("frame 25", "attack", "wait 2")));
        set.put(AnimationSet.State.DEATH, Animation.parse("d", List.of("frame 50", "wait 1")));
        return set;
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setPiercingDamage(3);
        type.setArmor(2);
        type.setMaxAttackRange(1);
        type.setAnimationSet(fighter());
        return type;
    }

    private static UnitType barracks() {
        UnitType type = new UnitType("unit-human-barracks");
        type.setTileSize(3, 3);
        type.setHitPoints(800);
        type.setBuilding(true);
        return type;
    }

    /** A small native subset of the sealed retail technology catalog. */
    private static net.chonkbase.chonkcraft.engine.upgrade.UpgradeSet swordUpgrades() {
        var upgrades = new net.chonkbase.chonkcraft.engine.upgrade.UpgradeSet();
        Upgrade sword = upgrades.getOrCreate("upgrade-sword1");
        sword.changes().put(Upgrade.Stat.LEVEL, 1);
        sword.changes().put(Upgrade.Stat.PIERCING_DAMAGE, 2);
        sword.appliesTo().addAll(List.of("unit-footman", "unit-knight"));
        Upgrade shield = upgrades.getOrCreate("upgrade-human-shield1");
        shield.changes().put(Upgrade.Stat.LEVEL, 1);
        shield.changes().put(Upgrade.Stat.ARMOR, 2);
        shield.appliesTo().add("unit-footman");
        return upgrades;
    }

    // -------------------------------------------------------------- upgrades

    @Test
    void nativeModifiersCarryStatChangesAndTargets() {
        var upgrades = swordUpgrades();
        assertEquals(2, upgrades.size());

        Upgrade sword = upgrades.get("upgrade-sword1");
        assertEquals(2, sword.change(Upgrade.Stat.PIERCING_DAMAGE));
        assertEquals(1, sword.change(Upgrade.Stat.LEVEL));
        assertEquals(List.of("unit-footman", "unit-knight"), sword.appliesTo());
        assertTrue(sword.applies(footman()));
    }

    @Test
    void researchRaisesDamageForTheWholeArmy() {
        // Warcraft II's upgrades are army-wide, not per-unit veterancy, which
        // is why the effect lives on the player.
        World world = new World(grass(20), twoPlayers());
        world.setUpgrades(swordUpgrades());

        UnitType type = footman();
        assertEquals(3, world.upgrades(0).piercingDamage(type), "base piercing damage");

        world.upgrades(0).complete("upgrade-sword1");
        assertEquals(5, world.upgrades(0).piercingDamage(type), "sword1 adds two");
        // The other player has not researched it.
        assertEquals(3, world.upgrades(1).piercingDamage(type));
    }

    @Test
    void combatUsesTheUpgradedStats() {
        World world = new World(grass(20), twoPlayers());
        world.setUpgrades(swordUpgrades());

        UnitType type = footman();
        Unit attacker = world.createUnit(type, 0, 2, 2);
        Unit defender = world.createUnit(type, 1, 4, 2);

        // Nominal is max(6-2,1)+3 = 7.
        int before = maxBlow(world, attacker, defender);
        assertEquals(7, before);

        world.upgrades(0).complete("upgrade-sword1");
        assertEquals(9, maxBlow(world, attacker, defender), "sword1 should add two");

        // The defender's own shield research subtracts from it.
        world.upgrades(1).complete("upgrade-human-shield1");
        assertEquals(7, maxBlow(world, attacker, defender), "shield1 should take two back");
    }

    /**
     * The largest damage seen over many blows, which is the nominal figure.
     *
     * <p>Health is topped up between blows. Four hundred hits would otherwise
     * kill the target part way through, and a corpse takes no damage, so the
     * measurement would silently read zero.
     */
    private static int maxBlow(World world, Unit attacker, Unit defender) {
        int max = 0;
        for (int i = 0; i < 400; i++) {
            defender.setHitPoints(defender.type().hitPoints());
            int before = defender.hitPoints();
            world.hit(attacker, defender);
            max = Math.max(max, before - defender.hitPoints());
        }
        return max;
    }

    @Test
    void researchTakesTimeAndGold() {
        World world = new World(grass(20), twoPlayers());
        var upgrades = swordUpgrades();
        upgrades.get("upgrade-sword1").costs().put(Resource.TIME, 1);
        upgrades.get("upgrade-sword1").costs().put(Resource.GOLD, 800);
        world.setUpgrades(upgrades);
        world.player(0).set(Resource.GOLD, 1000);

        Unit barracks = world.createUnit(barracks(), 0, 5, 5);
        assertTrue(world.orderResearch(barracks, "upgrade-sword1"));
        assertEquals(200, world.player(0).get(Resource.GOLD), "paid up front");
        assertFalse(world.upgrades(0).has("upgrade-sword1"), "not finished yet");

        for (int cycle = 0; cycle < 1000 && !world.upgrades(0).has("upgrade-sword1"); cycle++) {
            world.tick();
        }
        assertTrue(world.upgrades(0).has("upgrade-sword1"), "research never completed");
    }

    @Test
    void researchOrderRemainsCurrentOnItsCompletingCycle() {
        World world = new World(grass(20), twoPlayers());
        var upgrades = swordUpgrades();
        upgrades.get("upgrade-sword1").costs().put(Resource.TIME, 1);
        world.setUpgrades(upgrades);
        world.player(0).set(Resource.GOLD, 5000);
        Unit barracks = world.createUnit(barracks(), 0, 5, 5);

        assertTrue(world.orderResearch(barracks, "upgrade-sword1"));
        world.tick();

        assertTrue(world.upgrades(0).has("upgrade-sword1"),
                "the upgrade is acquired on the completing execute");
        assertEquals("upgrade-sword1", barracks.researching(),
                "COrder_Research stays current until HandleUnitAction advances"
                        + " its Finished order on the following cycle");

        world.tick();
        assertEquals(null, barracks.researching());
    }

    @Test
    void researchConversionRunsOnTheSubjectsNextUnitTurn() {
        World world = new World(grass(20), twoPlayers());
        UnitType original = footman();
        UnitType converted = new UnitType("unit-paladin");
        converted.setTileSize(1, 1);
        converted.setHitPoints(90);
        converted.setSpeed(10);
        converted.setLandUnit(true);
        converted.setAnimationSet(fighter());
        net.chonkbase.chonkcraft.engine.upgrade.UpgradeSet conversions =
                new net.chonkbase.chonkcraft.engine.upgrade.UpgradeSet();
        Upgrade conversion = conversions.getOrCreate("upgrade-paladin");
        conversion.appliesTo().add(original.ident());
        conversion.setConvertTo(converted.ident());
        conversion.costs().put(Resource.TIME, 1);
        world.setUnitTypes(java.util.Map.of(
                original.ident(), original, converted.ident(), converted));
        world.setUpgrades(conversions);
        world.player(0).set(Resource.GOLD, 5000);

        // BNE's action table is reverse creation order. Create the researcher
        // first so the later subject acts before it and has already spent its
        // turn when research queues CriticalOrder.
        Unit researcher = world.createUnit(barracks(), 0, 8, 8);
        Unit subject = world.createUnit(original, 0, 2, 2);
        assertTrue(world.orderResearch(researcher, conversion.ident()));

        world.tick();
        assertEquals(original, subject.type(),
                "UpgradeAcquire transformed an earlier unit inside the researcher's turn");
        assertEquals(converted, subject.pendingTransform());

        world.tick();
        assertEquals(converted, subject.type(),
                "the queued CriticalOrder did not execute on the next unit turn");
        assertEquals(null, subject.pendingTransform());
    }

    @Test
    void researchingTwiceIsRefused() {
        World world = new World(grass(20), twoPlayers());
        world.setUpgrades(swordUpgrades());
        world.player(0).set(Resource.GOLD, 5000);

        Unit barracks = world.createUnit(barracks(), 0, 5, 5);
        world.upgrades(0).complete("upgrade-sword1");
        assertFalse(world.orderResearch(barracks, "upgrade-sword1"));
    }

    @Test
    void researchingSomethingUnknownIsRefused() {
        World world = new World(grass(20), twoPlayers());
        world.setUpgrades(swordUpgrades());
        Unit barracks = world.createUnit(barracks(), 0, 5, 5);
        assertFalse(world.orderResearch(barracks, "upgrade-that-does-not-exist"));
    }

    // -------------------------------------------------------------- triggers

    private record Mission(World world, TriggerSystem triggers) {}

    private static String q(String value) {
        return "Q" + java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static TriggerSystem.ProgramSpec program(String condition, String action) {
        return new TriggerSystem.ProgramSpec(condition, action);
    }

    private static Mission mission(TriggerSystem.ProgramSpec... programs) {
        World world = new World(grass(30), twoPlayers());
        return new Mission(world, new TriggerSystem(world, 0, List.of(programs)));
    }

    private static TriggerSystem.Outcome outcome(World world, String condition) {
        TriggerSystem triggers = new TriggerSystem(world, 0,
                List.of(program(condition, "VICTORY")));
        triggers.evaluate();
        return triggers.outcome();
    }

    @Test
    void aVictoryTriggerFiresWhenItsConditionHolds() {
        Mission mission = mission(program("THIS " + q("unit-human-barracks")
                + " UNIT_COUNT N1 GE", "VICTORY"));
        mission.world().createUnit(barracks(), 0, 5, 5);

        assertEquals(1, mission.triggers().triggerCount());
        assertEquals(TriggerSystem.Outcome.RUNNING, mission.triggers().outcome());
        mission.triggers().evaluate();
        assertEquals(TriggerSystem.Outcome.VICTORY, mission.triggers().outcome());
    }

    @Test
    void aDefeatTriggerFiresWhenEverythingIsLost() {
        Mission mission = mission(program("THIS TOTAL N0 EQ", "DEFEAT"));
        Unit last = mission.world().createUnit(footman(), 0, 5, 5);

        mission.triggers().evaluate();
        assertEquals(TriggerSystem.Outcome.RUNNING, mission.triggers().outcome());
        mission.world().kill(last);
        mission.triggers().evaluate();
        assertEquals(TriggerSystem.Outcome.DEFEAT, mission.triggers().outcome());
    }

    @Test
    void aTriggerFiresOnlyOnce() {
        Mission mission = mission(program("TRUE", "NOOP"));
        mission.triggers().evaluate();
        mission.triggers().evaluate();
        mission.triggers().evaluate();
        assertEquals(0, mission.triggers().triggerCount());
    }

    @Test
    void aBrokenTriggerCostsItselfNotTheMission() {
        Mission mission = mission(
                program("NO_SUCH_OPCODE", "VICTORY"),
                program("TRUE", "DEFEAT"));
        mission.triggers().evaluate();
        assertEquals(TriggerSystem.Outcome.DEFEAT, mission.triggers().outcome());
        assertFalse(mission.triggers().failures().isEmpty());
    }

    @Test
    void triggersEvaluateEveryCycleAsTheGameLoopDoes() {
        int cycles = World.CYCLES_PER_SECOND * 2;
        Mission mission = mission(program("TRUE", "DELAYED_VICTORY " + cycles + " 0"));
        for (int cycle = 1; cycle < cycles; cycle++) {
            mission.triggers().tick();
            assertEquals(TriggerSystem.Outcome.RUNNING, mission.triggers().outcome());
        }
        mission.triggers().tick();
        assertEquals(TriggerSystem.Outcome.VICTORY, mission.triggers().outcome());
    }

    @Test
    void nativeQueriesAnswerTheQuestionsCampaignsUse() {
        World world = new World(grass(30), twoPlayers());
        world.createUnit(footman(), 0, 5, 5);
        world.createUnit(footman(), 0, 6, 5);
        world.createUnit(barracks(), 0, 10, 10);
        world.createUnit(footman(), 1, 20, 20);

        assertEquals(TriggerSystem.Outcome.VICTORY,
                outcome(world, "N0 TOTAL N3 EQ"));
        assertEquals(TriggerSystem.Outcome.VICTORY,
                outcome(world, "N0 " + q("unit-footman") + " UNIT_COUNT N2 EQ"));
        assertEquals(TriggerSystem.Outcome.VICTORY,
                outcome(world, "N0 OPPONENTS N1 EQ"));
    }

    @Test
    void countsUnitsInAnArea() {
        World world = new World(grass(30), twoPlayers());
        world.createUnit(footman(), 0, 5, 5);
        world.createUnit(footman(), 0, 6, 6);
        world.createUnit(footman(), 0, 25, 25);
        String type = q("unit-footman");

        assertEquals(TriggerSystem.Outcome.VICTORY,
                outcome(world, "N0 " + type + " N0 N0 N10 N10 UNITS_AT N2 EQ"));
        assertEquals(TriggerSystem.Outcome.VICTORY,
                outcome(world, "N0 " + type + " N20 N20 N29 N29 UNITS_AT N1 EQ"));
    }
}
