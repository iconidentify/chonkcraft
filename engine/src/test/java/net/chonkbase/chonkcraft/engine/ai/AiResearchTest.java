package net.chonkbase.chonkcraft.engine.ai;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import net.chonkbase.chonkcraft.engine.upgrade.Upgrade;
import net.chonkbase.chonkcraft.engine.upgrade.UpgradeSet;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A computer player researches its upgrades and improves its buildings.
 *
 * <p>{@code AiResearch} and {@code AiUpgradeTo} were both bound to a function
 * that returned false and did nothing at all. The shipped personalities call
 * the first 69 times and the second 58, so across the whole campaign no
 * computer player had ever researched a sword, an arrow or a shield, and no
 * watch tower had ever become a guard tower. Every AI army fought all
 * fifty-two missions with the weapons and the armour it was born with.
 *
 * <p>Measured over the campaign with {@code AiProbe}, five simulated minutes a
 * mission. Before either was bound: 0 upgrades researched across 114 computer
 * slots. With them bound but a standing request re-bought every second: 164
 * upgrades, paid for 305 times. With the single-research rule upstream applies
 * in the same place: 172 upgrades, paid for 178 times. The figure that moved
 * most is the one nobody was counting.
 *
 * <p>Why nobody noticed is the part worth keeping. This project's tripwire for
 * a script call that nothing implements is {@code unboundScriptFunctions}, and
 * a call bound to a no-op is, by that test's definition, bound. A no-op
 * binding is strictly worse than a missing one, because a missing one is
 * counted.
 *
 * <p>The retired ChonkCraft bindings originally supplied these requests. The
 * authenticated retail {@code ai.bin} interpreter now calls the native
 * {@link AiPlayer} operations directly, so these tests pin the manager-facing
 * boundary that both the bytecode interpreter and saved AI state use.
 */
class AiResearchTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static UnitType blacksmith() {
        UnitType type = new UnitType("unit-human-blacksmith");
        type.setTileSize(3, 3);
        type.setHitPoints(775);
        type.setBuilding(true);
        type.setSightRange(3);
        return type;
    }

    private static UnitType watchTower() {
        UnitType type = new UnitType("unit-human-watch-tower");
        type.setTileSize(2, 2);
        type.setHitPoints(100);
        type.setBuilding(true);
        type.setSightRange(9);
        return type;
    }

    private static UnitType guardTower() {
        UnitType type = new UnitType("unit-human-guard-tower");
        type.setTileSize(2, 2);
        type.setHitPoints(130);
        type.setBuilding(true);
        type.setSightRange(9);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.costs().put(Resource.TIME, 1);
        type.costs().put(Resource.GOLD, 500);
        return type;
    }

    private static Player[] onePlayer() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i == 0 ? PudMap.PlayerType.COMPUTER : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        return players;
    }

    private static int count(World world, String ident) {
        int found = 0;
        for (Unit unit : world.units()) {
            if (unit.isAlive() && unit.type().ident().equals(ident)) {
                found++;
            }
        }
        return found;
    }

    @Test
    @DisplayName("a sword upgrade the script asked for is actually forged")
    void anUpgradeTheScriptAskedForIsResearched() {
        World world = new World(grass(30), onePlayer());
        world.player(0).set(Resource.GOLD, 5000);
        UpgradeSet upgrades = new UpgradeSet();
        Upgrade sword = upgrades.getOrCreate("upgrade-sword1");
        sword.costs().put(Resource.TIME, 1);
        sword.costs().put(Resource.GOLD, 800);
        world.setUpgrades(upgrades);
        UnitType smith = blacksmith();
        world.createUnit(smith, 0, 5, 5);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        assertFalse(world.upgrades(0).has("upgrade-sword1"),
                "the fixture must start without the upgrade or it proves nothing");
        ai.research("upgrade-sword1");

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 30; cycle++) {
            world.tick();
        }

        assertTrue(world.upgrades(0).has("upgrade-sword1"),
                "AiResearch did nothing: it was bound to a function that returned false and"
                        + " dropped its argument, so no computer player in the campaign had ever"
                        + " researched anything");
        assertTrue(world.player(0).get(Resource.GOLD) < 5000,
                "and it was never paid for");
    }

    @Test
    @DisplayName("a watch tower the script asked to improve becomes a guard tower")
    void aTowerTheScriptAskedToImproveIsUpgraded() {
        World world = new World(grass(30), onePlayer());
        world.player(0).set(Resource.GOLD, 5000);
        world.setUpgrades(new UpgradeSet());
        UnitType tower = watchTower();
        UnitType better = guardTower();
        world.createUnit(tower, 0, 5, 5);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        assertEquals(1, count(world, "unit-human-watch-tower"),
                "the fixture starts with one watch tower");
        assertEquals(0, count(world, "unit-human-guard-tower"),
                "and no guard tower, or it proves nothing");

        ai.upgradeTo(tower, better);

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 30; cycle++) {
            world.tick();
        }

        assertEquals(1, count(world, "unit-human-guard-tower"),
                "AiUpgradeTo did nothing: 21 of the shipped personalities' calls ask for a"
                        + " guard tower and 14 for a cannon tower, and every one of them was"
                        + " answered false and dropped");
        assertEquals(0, count(world, "unit-human-watch-tower"),
                "the watch tower should have become the guard tower where it stood, not been"
                        + " left beside a new one");
    }

    /**
     * The control that stops the fix from being "upgrade whatever is idle".
     *
     * <p>{@code World.orderUpgradeTo} does not check that the building it is
     * handed may become the type it is handed: it charges the cost and
     * transforms it. An AI that guessed its source building would turn a
     * blacksmith into a guard tower, which is a worse bug than the one being
     * fixed.
     */
    @Test
    @DisplayName("nothing but a watch tower is turned into a guard tower")
    void onlyTheRightBuildingIsUpgraded() {
        World world = new World(grass(30), onePlayer());
        world.player(0).set(Resource.GOLD, 5000);
        world.setUpgrades(new UpgradeSet());
        UnitType smith = blacksmith();
        UnitType better = guardTower();
        world.createUnit(smith, 0, 5, 5);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.upgradeTo(watchTower(), better);

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 30; cycle++) {
            world.tick();
        }

        assertEquals(1, count(world, "unit-human-blacksmith"),
                "the blacksmith was turned into a guard tower: the AI has no watch tower to"
                        + " improve and must leave everything else alone");
        assertEquals(0, count(world, "unit-human-guard-tower"),
                "and no guard tower should exist");
        assertEquals(5000, world.player(0).get(Resource.GOLD),
                "and nothing should have been charged for it");
    }

    /**
     * The leak that kept every computer player poor.
     *
     * <p>A research request is a <em>standing</em> one: it is asked again every
     * second until the upgrade is had. An upgrade takes forty seconds. And
     * {@code World.orderResearch} charges the full price to whichever idle
     * building accepts it. So a side with more than one spare building paid for
     * the same sword once a second for the whole forty, and the AI that had
     * just been taught to research spent everything it owned researching one
     * thing.
     *
     * <p>Upstream does not have this because every research button in Warcraft
     * II is {@code Allowed = "check-single-research"} and
     * {@code AiAddResearchRequest} honours it: "check if we're already
     * researching it. if so, ignore this request."
     */
    @Test
    @DisplayName("an upgrade is paid for once, not once a second while it is being made")
    void anUpgradeIsPaidForOnce() {
        World world = new World(grass(40), onePlayer());
        world.player(0).set(Resource.GOLD, 5000);
        UpgradeSet upgrades = new UpgradeSet();
        Upgrade sword = upgrades.getOrCreate("upgrade-sword1");
        // Twenty seconds of work, so the request is asked again nineteen times
        // while the first blacksmith is busy with it.
        sword.costs().put(Resource.TIME, 100);
        sword.costs().put(Resource.GOLD, 800);
        world.setUpgrades(upgrades);
        UnitType smith = blacksmith();
        // Four of them: the second and later ones are what the repeat request
        // lands on, and with one building the fault cannot show at all.
        world.createUnit(smith, 0, 5, 5);
        world.createUnit(smith, 0, 10, 5);
        world.createUnit(smith, 0, 15, 5);
        world.createUnit(smith, 0, 20, 5);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.research("upgrade-sword1");

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 40; cycle++) {
            world.tick();
        }

        assertTrue(world.upgrades(0).has("upgrade-sword1"), "the sword was never forged");
        assertEquals(4200, world.player(0).get(Resource.GOLD),
                "the same upgrade was bought more than once: a standing research request is"
                        + " asked again every second, and every idle building it was offered to"
                        + " paid the full 800 gold for it");
    }

    /**
     * What a mission forbids, the computer player does not get either.
     *
     * <p>{@code AiCheckUnits} asks for a research only while
     * {@code UpgradeIdAllowed(player, id) == 'A'}. The campaign scripts forbid
     * everything and then allow a list -- {@code level10h_c.sms} is fifty lines
     * of it -- and several of them ask their own AI for a tier the same file
     * has forbidden.
     */
    @Test
    @DisplayName("an upgrade the mission forbids is not researched")
    void aForbiddenUpgradeIsNotResearched() {
        World world = new World(grass(30), onePlayer());
        world.player(0).set(Resource.GOLD, 5000);
        UpgradeSet upgrades = new UpgradeSet();
        Upgrade sword = upgrades.getOrCreate("upgrade-sword1");
        sword.costs().put(Resource.TIME, 1);
        sword.costs().put(Resource.GOLD, 800);
        world.setUpgrades(upgrades);
        var allowed = new net.chonkbase.chonkcraft.engine.upgrade.AllowState();
        allowed.define("upgrade-sword1", "FFFFFFFFFFFFFFFF");
        world.setAllowed(allowed);
        UnitType smith = blacksmith();
        world.createUnit(smith, 0, 5, 5);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.research("upgrade-sword1");

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 30; cycle++) {
            world.tick();
        }

        assertFalse(world.upgrades(0).has("upgrade-sword1"),
                "the mission forbade this upgrade and the AI researched it anyway");
        assertEquals(5000, world.player(0).get(Resource.GOLD),
                "and paid for it");
    }

    /**
     * One guard tower out of one standing request occurrence, not one a second.
     *
     * <p>{@code AiCheckUnits} works out {@code 1 - unit_types_count[wanted]}
     * for each occurrence and only asks while that is positive. The request
     * never expires, so without the count a side with four watch towers
     * improved another one every second at five hundred gold a time until it
     * ran out of towers. A separate parity fixture still needs to pin the
     * upstream vector's duplicate-occurrence behavior.
     */
    @Test
    @DisplayName("a standing upgrade request improves one building, not every one of them")
    void anUpgradeRequestImprovesOneBuilding() {
        World world = new World(grass(40), onePlayer());
        world.player(0).set(Resource.GOLD, 5000);
        world.setUpgrades(new UpgradeSet());
        UnitType tower = watchTower();
        UnitType better = guardTower();
        world.createUnit(tower, 0, 5, 5);
        world.createUnit(tower, 0, 10, 5);
        world.createUnit(tower, 0, 15, 5);
        world.createUnit(tower, 0, 20, 5);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.upgradeTo(tower, better);

        for (int cycle = 0; cycle < World.CYCLES_PER_SECOND * 30; cycle++) {
            world.tick();
        }

        assertEquals(1, count(world, "unit-human-guard-tower"),
                "the whole row of watch towers was improved off one request");
        assertEquals(3, count(world, "unit-human-watch-tower"),
                "and the other three should still be watch towers");
        assertEquals(4500, world.player(0).get(Resource.GOLD),
                "one guard tower costs 500 gold and only one was asked for");
    }

    /**
     * Upstream stores every {@code AiUpgradeTo} call in its vector.
     *
     * <p>The first occurrence is consumed by the guard tower already owned;
     * the shared {@code AiCheckUnits} counter then makes the second and third
     * occurrences request two more targets in this same thought. A map keyed
     * by source type collapses all three calls and starts neither upgrade.
     */
    @Test
    @DisplayName("duplicate upgrade requests count beyond an existing target")
    void duplicateUpgradeRequestsPreserveTheirCardinality() {
        World world = new World(grass(40), onePlayer());
        world.player(0).set(Resource.GOLD, 5000);
        world.setUpgrades(new UpgradeSet());
        UnitType tower = watchTower();
        UnitType better = guardTower();
        better.costs().put(Resource.TIME, 100);
        world.createUnit(better, 0, 5, 5);
        Unit first = world.createUnit(tower, 0, 10, 5);
        Unit second = world.createUnit(tower, 0, 15, 5);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.upgradeTo(tower, better);
        ai.upgradeTo(tower, better);
        ai.upgradeTo(tower, better);

        assertEquals(3, ai.upgradeToRequests().size(),
                "the ordered vector collapsed duplicate script calls");
        ai.think(world);

        assertSame(better, first.upgradingTo(),
                "the second occurrence did not request the second guard tower");
        assertSame(better, second.upgradingTo(),
                "the third occurrence did not request the third guard tower");
        assertEquals(4000, world.player(0).get(Resource.GOLD),
                "two additional guard towers should cost 500 gold each");
    }

    /** Upstream's active-count-bounded source lookup stops before a later idle tower. */
    @Test
    @DisplayName("an unfinished source consumes the upgrade lookup's active count bound")
    void unfinishedSourceBoundsTheUpgradeCandidateWalk() {
        World world = new World(grass(40), onePlayer());
        world.player(0).set(Resource.GOLD, 5000);
        world.setUpgrades(new UpgradeSet());
        UnitType tower = watchTower();
        UnitType better = guardTower();
        better.costs().put(Resource.TIME, 100);
        world.createUnit(better, 0, 5, 5);
        Unit unfinished = world.createUnit(tower, 0, 8, 5);
        unfinished.setOrder(Unit.Order.UNDER_CONSTRUCTION);
        Unit first = world.createUnit(tower, 0, 12, 5);
        Unit hidden = world.createUnit(tower, 0, 16, 5);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.upgradeTo(tower, better);
        ai.upgradeTo(tower, better);
        ai.upgradeTo(tower, better);
        ai.think(world);

        assertSame(better, first.upgradingTo(),
                "the first usable source should answer the second occurrence");
        assertNull(hidden.upgradingTo(),
                "FindPlayerUnitsByType should stop before the later idle source after"
                        + " the unfinished source consumed an active-count slot");
        assertEquals(4500, world.player(0).get(Resource.GOLD),
                "only the source visible inside the bounded lookup should be charged");
    }

    @Test
    @DisplayName("a building finishing this cycle is not yet idle for AI work")
    void aJustFinishedBuildingCannotResearchUntilItsBuiltOrderPops() {
        World world = new World(grass(30), onePlayer());
        world.player(0).set(Resource.GOLD, 1000);
        world.player(0).set(Resource.WOOD, 1000);
        UpgradeSet upgrades = new UpgradeSet();
        Upgrade axe = upgrades.getOrCreate("upgrade-axe1");
        axe.costs().put(Resource.TIME, 100);
        axe.costs().put(Resource.GOLD, 300);
        axe.costs().put(Resource.WOOD, 300);
        world.setUpgrades(upgrades);
        Unit smith = world.createUnit(blacksmith(), 0, 5, 5);
        // Finish() has made the actual order Still, but HandleUnitAction does
        // not remove the completed Built order until the next unit cycle.
        smith.setOrder(Unit.Order.STILL);
        smith.rememberActionBeforeQueued(Unit.Order.UNDER_CONSTRUCTION);

        AiPlayer ai = world.enableAi(0);
        ai.setUsePlan(false);
        ai.research("upgrade-axe1");
        ai.think(world);

        assertNull(smith.researching(),
                "CUnit::IsIdle sees the still-current Built order and must refuse the AI"
                        + " request on the construction-completion cycle");
        assertEquals(1000, world.player(0).get(Resource.GOLD));
        assertEquals(1000, world.player(0).get(Resource.WOOD));

        smith.setActionBeforeQueued(null);
        ai.think(world);

        assertEquals("upgrade-axe1", smith.researching(),
                "the same standing request should start once Built has popped to Still");
        assertEquals(700, world.player(0).get(Resource.GOLD));
        assertEquals(700, world.player(0).get(Resource.WOOD));
    }

}
