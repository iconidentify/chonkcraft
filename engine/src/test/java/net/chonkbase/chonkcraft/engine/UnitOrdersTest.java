package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.pathfinder.PathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The orders a unit can hold, beyond moving and fighting.
 *
 * <p>These existed as buttons on the command panel long before they existed as
 * behaviour: pressing patrol set a status line and nothing else. Each of these
 * checks the order does the thing its icon promises.
 */
class UnitOrdersTest {

    private static final String MAP = "campaigns/human/level02h";

    private record Fixture(GameData data, Map<String, UnitType> types, int x, int y) {}

    private static Fixture load() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");

        GameData data = new GameData(install);
        Assumptions.assumeTrue(data.campaignMap(MAP) != null, "no campaign map available");
        int[] open = findOpenArea(world(data));
        Assumptions.assumeTrue(open != null, "no open ground on this map");
        return new Fixture(data, data.unitTypes().types(), open[0], open[1]);
    }

    private static World world(GameData data) {
        PudMap pud = data.campaignMap(MAP);
        World world = new World(GameMap.from(pud, data.loadTileset(pud.tileset()).tileset()),
                Player.from(pud));
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        return world;
    }

    private static int[] findOpenArea(World world) {
        for (int y = 3; y < world.map().height() - 3; y++) {
            for (int x = 3; x < world.map().width() - 16; x++) {
                boolean clear = true;
                for (int i = 0; i < 16 && clear; i++) {
                    for (int d = -1; d <= 1; d++) {
                        if (!world.map().field(x + i, y + d).isLandPassable()) {
                            clear = false;
                            break;
                        }
                    }
                }
                if (clear) {
                    return new int[] {x, y};
                }
            }
        }
        return null;
    }

    private static void run(World world, int cycles) {
        for (int i = 0; i < cycles; i++) {
            world.tick();
        }
    }

    @Test
    @DisplayName("a patrol walks to the far end and comes back")
    void patrolWalksItsBeat() {
        Fixture fixture = load();
        World world = world(fixture.data());
        world.fog().revealAll(0);
        Unit unit = world.createUnit(fixture.types().get("unit-footman"), 0,
                fixture.x(), fixture.y());

        assertTrue(world.orderPatrol(unit, fixture.x() + 8, fixture.y()));
        int furthest = fixture.x();
        int backTo = Integer.MAX_VALUE;
        for (int i = 0; i < 2500; i++) {
            world.tick();
            furthest = Math.max(furthest, unit.tileX());
            if (furthest >= fixture.x() + 7) {
                backTo = Math.min(backTo, unit.tileX());
            }
        }
        assertTrue(furthest >= fixture.x() + 7, "it never reached the far end");
        assertTrue(backTo < fixture.x() + 7, "it reached the far end and stayed there");
    }

    @Test
    @DisplayName("a patrol resumes its beat after an automatic fight")
    void patrolResumesAfterAFight() {
        Fixture fixture = load();
        World world = world(fixture.data());
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        Unit patrol = world.createUnit(fixture.types().get("unit-footman"), 0,
                fixture.x(), fixture.y());
        Unit enemy = world.createUnit(fixture.types().get("unit-grunt"), 1,
                fixture.x() + 3, fixture.y());

        assertTrue(world.orderPatrol(patrol, fixture.x() + 12, fixture.y()));
        for (int cycle = 0; cycle < 200 && patrol.order() != Unit.Order.ATTACK_MOVE; cycle++) {
            world.tick();
        }
        // The acquisition is the square, not the unit: AutoAttack commands
        // CommandAttack(unit, goal->tilePos, nullptr) -- an attack-move that
        // picks its own target as it goes -- and the patrol is interrupted
        // by exactly that order.
        assertEquals(Unit.Order.ATTACK_MOVE, patrol.order(),
                "the patrol never noticed the enemy");

        // Killing it does not free the chaser that same cycle. A unit walking
        // to its target is in upstream's MOVE_TO_TARGET state, and
        // COrder_Attack::MoveToTarget runs DoActionMove and then returns while
        // Anim.Unbreakable is set: the goal is not looked at again until the
        // step in the air has landed. A footman's move animation is sixteen
        // cycles, so that is how long upstream takes to notice too.
        world.kill(enemy);
        world.tick();
        assertEquals(Unit.Order.ATTACK_MOVE, patrol.order(),
                "the chaser dropped its order mid-step, where upstream finishes the step first");

        for (int cycle = 0; cycle < 60 && patrol.order() != Unit.Order.PATROL; cycle++) {
            world.tick();
        }
        assertEquals(Unit.Order.PATROL, patrol.order(),
                "the automatic attack discarded the patrol it interrupted");
    }

    @Test
    @DisplayName("an attack releases a target that is no longer an enemy")
    void attackReleasesAChangedOwner() {
        Fixture fixture = load();
        World world = world(fixture.data());
        world.fog().revealAll(0);
        Unit attacker = world.createUnit(fixture.types().get("unit-footman"), 0,
                fixture.x(), fixture.y());
        Unit target = world.createUnit(fixture.types().get("unit-grunt"), 1,
                fixture.x() + 2, fixture.y());

        assertTrue(world.orderAttack(attacker, target));
        target.setPlayer(0);
        world.tick();

        assertEquals(Unit.Order.STILL, attacker.order());
        assertEquals(null, attacker.target());
    }

    @Test
    @DisplayName("an attack releases a target that leaves sight")
    void attackReleasesAnInvisibleTarget() {
        Fixture fixture = load();
        World world = world(fixture.data());
        Unit attacker = world.createUnit(fixture.types().get("unit-footman"), 0,
                fixture.x(), fixture.y());
        Unit target = world.createUnit(fixture.types().get("unit-grunt"), 1,
                fixture.x() + 2, fixture.y());

        assertTrue(world.isVisibleTo(0, target));
        assertTrue(world.orderAttack(attacker, target));
        target.setTile(fixture.x() + 14, fixture.y());
        // The bare teleport moves no bookkeeping, and visibility is the
        // unit's own watcher count rather than the fog under it -- a real
        // step recounts as it lands. The global recount stands in for that
        // here, and it is also what drains the phantom watcher a seen unit
        // keeps: going under fog is a counted transition, not a tile read.
        world.recountSeen();
        assertTrue(!world.isVisibleTo(0, target), "the target did not leave sight");
        world.tick();

        assertEquals(Unit.Order.STILL, attacker.order());
        assertEquals(null, attacker.target());
    }

    @Test
    @DisplayName("following tracks where a friendly unit goes")
    void followTracksAMovingUnit() {
        Fixture fixture = load();
        World world = world(fixture.data());
        Unit follower = world.createUnit(fixture.types().get("unit-footman"), 0,
                fixture.x(), fixture.y());
        Unit leader = world.createUnit(fixture.types().get("unit-footman"), 0,
                fixture.x() + 2, fixture.y());

        assertTrue(world.orderFollow(follower, leader));
        assertTrue(world.orderMove(leader, fixture.x() + 14, fixture.y()));
        run(world, 2500);

        assertEquals(fixture.x() + 14, leader.tileX());
        assertTrue(follower.distanceTo(leader) <= 1,
                "the follower stopped at the leader's old square");
        assertEquals(Unit.Order.FOLLOW, follower.order());
    }

    @Test
    @DisplayName("the shipped critter wanders off its starting square")
    void crittersMoveRandomly() {
        Fixture fixture = load();
        World world = world(fixture.data());
        Unit critter = world.createUnit(fixture.types().get("unit-critter"), 15,
                fixture.x(), fixture.y());
        int startX = critter.tileX();
        int startY = critter.tileY();

        boolean moved = false;
        for (int cycle = 0; cycle < 3000 && !moved; cycle++) {
            world.tick();
            moved = critter.tileX() != startX || critter.tileY() != startY;
        }

        assertTrue(moved, "RandomMovementProbability was parsed but never affected the unit");
    }

    @Test
    @DisplayName("a worker mends a damaged building, and pays for it")
    void repairMends() {
        Fixture fixture = load();
        World world = world(fixture.data());
        world.fog().revealAll(0);
        Unit hall = world.createUnit(fixture.types().get("unit-town-hall"), 0,
                fixture.x() + 6, fixture.y());
        hall.setHitPoints(200);
        Unit worker = world.createUnit(fixture.types().get("unit-peasant"), 0,
                fixture.x(), fixture.y());
        world.player(0).set(UnitType.Resource.GOLD, 9999);
        world.player(0).set(UnitType.Resource.WOOD, 9999);

        assertTrue(world.orderRepair(worker, hall));
        run(world, 2500);
        assertTrue(hall.hitPoints() > 200, "the building was not mended");
        assertTrue(hall.hitPoints() <= hall.type().hitPoints(), "it was mended past full");
    }

    @Test
    @DisplayName("a mend click on a standing hall is still a repair")
    void repairOnAWholeHallIsStillAnOrder() {
        Fixture fixture = load();
        World world = world(fixture.data());
        Unit worker = world.createUnit(fixture.types().get("unit-peasant"), 0,
                fixture.x(), fixture.y());
        Unit whole = world.createUnit(fixture.types().get("unit-town-hall"), 0,
                fixture.x() + 6, fixture.y());
        assertTrue(world.orderRepair(worker, whole),
                "a click to mend a standing hall used to be refused before the peon left the square");
        assertEquals(Unit.Order.REPAIR, worker.order(),
                "the peasant must walk to the whole hall, not stay put");
    }

    @Test
    @DisplayName("a mend click on a soldier is a walk, not a repair")
    void repairOnASoldierBecomesAWalk() {
        Fixture fixture = load();
        World world = world(fixture.data());
        Unit worker = world.createUnit(fixture.types().get("unit-peasant"), 0,
                fixture.x(), fixture.y());
        Unit soldier = world.createUnit(fixture.types().get("unit-footman"), 0,
                fixture.x() + 4, fixture.y());
        assertTrue(world.orderRepair(worker, soldier),
                "the click still applies when the target is not a building");
        assertEquals(Unit.Order.MOVE, worker.order(),
                "a peasant sent to mend a footman walks there instead");
    }

    @Test
    @DisplayName("a repair order is refused for a hostile building")
    void repairIsRefusedWhenHostile() {
        Fixture fixture = load();
        World world = world(fixture.data());
        Unit worker = world.createUnit(fixture.types().get("unit-peasant"), 0,
                fixture.x(), fixture.y());
        // Not merely foreign: the rule is ownership or alliance,
        // so what stays refused is a building whose owner is no ally of ours.
        Unit theirs = world.createUnit(fixture.types().get("unit-farm"), 1,
                fixture.x() + 10, fixture.y());
        theirs.setHitPoints(10);
        assertTrue(!world.isAllied(0, 1),
                "the fixture's two slots are allied, so this refusal would prove nothing");
        assertTrue(!world.orderRepair(worker, theirs), "that is no ally's building");
    }

    /**
     * An ally's damaged building can be repaired.
     *
     * <p>{@code DoRightButton}'s repair branch reads
     * {@code dest->Player == unit.Player || unit.IsAllied(*dest)}
     * {@code World.orderRepair} refused everything
     * but the worker's own player, so the interface issued the order --
     * RightClickTableTest has pinned the command since the presentation lane
     * -- and the world dropped it with nothing said: a campaign ally's
     * burning hall could not be helped however many peasants stood by.
     */
    @Test
    @DisplayName("an ally's damaged building can be mended")
    void anAlliedBuildingCanBeMended() {
        Fixture fixture = load();
        World world = world(fixture.data());
        world.fog().revealAll(0);
        world.setAllied(0, 1, true);
        world.setAllied(1, 0, true);
        Unit worker = world.createUnit(fixture.types().get("unit-peasant"), 0,
                fixture.x(), fixture.y());
        Unit theirs = world.createUnit(fixture.types().get("unit-farm"), 1,
                fixture.x() + 3, fixture.y());
        theirs.setHitPoints(10);
        world.player(0).set(UnitType.Resource.GOLD, 9999);
        world.player(0).set(UnitType.Resource.WOOD, 9999);

        assertTrue(world.orderRepair(worker, theirs),
                "the repair order on an ally's building was refused: orderRepair asks"
                        + " for the worker's own player where upstream asks own-or-allied");
        run(world, 2500);
        assertTrue(theirs.hitPoints() > 10,
                "the order was accepted and the ally's farm was never mended: it sits at "
                        + theirs.hitPoints() + " of " + theirs.type().hitPoints());
    }

    /**
     * Control-follow can follow an enemy.
     *
     * <p>Upstream's comment is the specification: "Control + right click on
     * unit is follow anything", and neither
     * {@code SendCommandFollow} nor {@code CommandFollow} asks whose the
     * target is. {@code orderFollow} refused a target the follower was at war
     * with, so shadowing an enemy scout was a click that did nothing.
     */
    @Test
    @DisplayName("a unit can be sent to follow an enemy")
    void anEnemyCanBeFollowed() {
        Fixture fixture = load();
        World world = world(fixture.data());
        world.fog().revealAll(0);
        assertTrue(world.isEnemyPlayer(0, 1),
                "the fixture's two slots are not at war, so following their unit"
                        + " proves nothing about following an enemy");
        Unit follower = world.createUnit(fixture.types().get("unit-footman"), 0,
                fixture.x(), fixture.y());
        // A peasant, because it is a coward: it will not turn the shadow into
        // a brawl, which keeps the measurement about following.
        Unit quarry = world.createUnit(fixture.types().get("unit-peasant"), 1,
                fixture.x() + 6, fixture.y());

        assertTrue(world.orderFollow(follower, quarry),
                "the follow order on an enemy was refused: control-follow is"
                        + " \"follow anything\" and the world second-guessed the click");
        run(world, 1200);
        assertTrue(follower.distanceTo(quarry) <= 2,
                "the follower never closed on the enemy it was sent after; it stands "
                        + follower.distanceTo(quarry) + " squares away");
    }

    @Test
    @DisplayName("an explorer reveals ground nobody has seen")
    void exploreRevealsTheMap() {
        Fixture fixture = load();
        World world = world(fixture.data());
        Unit scout = world.createUnit(fixture.types().get("unit-footman"), 0,
                fixture.x(), fixture.y());
        int before = explored(world);

        assertTrue(world.orderExplore(scout));
        run(world, 2500);
        assertTrue(explored(world) > before,
                "it explored nothing: " + before + " tiles before and after");
    }

    @Test
    @DisplayName("an explorer's successful step clears its consecutive-wait count")
    void anExplorerStepClearsOldWaitingAnswers() {
        Fixture fixture = load();
        World world = world(fixture.data());
        Unit scout = world.createUnit(fixture.types().get("unit-footman"), 0,
                fixture.x(), fixture.y());
        assertTrue(world.orderExplore(scout));
        scout.setOrderTarget(fixture.x() + 12, fixture.y());
        scout.setPath(new PathFinder.Path(PathFinder.Result.FOUND,
                new int[] {Direction.fromDelta(1, 0)}));
        scout.setExploreWaitingCycle(4);

        world.tick();

        assertEquals(fixture.x() + 1, scout.tileX(), "the fixture did not take its step");
        assertEquals(0, scout.exploreWaitingCycle(),
                "COrder_Explore resets WaitingCycle on PF_MOVE; four earlier blocked"
                        + " answers must not survive a successful step and turn the route's"
                        + " eventual empty-buffer wait into a fifth refusal");
    }

    private static int explored(World world) {
        int seen = 0;
        for (int y = 0; y < world.map().height(); y++) {
            for (int x = 0; x < world.map().width(); x++) {
                if (world.fog().isExplored(0, x, y)) {
                    seen++;
                }
            }
        }
        return seen;
    }

    @Test
    @DisplayName("a laden worker sent home banks its load")
    void returnGoodsBanksTheLoad() {
        Fixture fixture = load();
        World world = world(fixture.data());
        world.fog().revealAll(0);
        world.createUnit(fixture.types().get("unit-town-hall"), 0,
                fixture.x() + 5, fixture.y());
        Unit worker = world.createUnit(fixture.types().get("unit-peasant"), 0,
                fixture.x(), fixture.y());
        worker.setCarrying(UnitType.Resource.GOLD);
        worker.setCarried(100);
        int before = world.player(0).get(UnitType.Resource.GOLD);

        assertTrue(world.orderReturnGoods(worker));
        run(world, 2500);
        assertEquals(before + 100, world.player(0).get(UnitType.Resource.GOLD));
        assertEquals(0, worker.carried(), "it banked the load but kept it too");
    }

    @Test
    @DisplayName("a send-home order does not inherit the old harvest location")
    void returnGoodsStartsWithAnEmptyResourceMemory() {
        Fixture fixture = load();
        World world = world(fixture.data());
        Unit worker = world.createUnit(fixture.types().get("unit-peasant"), 0,
                fixture.x(), fixture.y());
        Unit oldMine = world.createUnit(fixture.types().get("unit-gold-mine"), 15,
                fixture.x() + 8, fixture.y());
        worker.setCarrying(UnitType.Resource.WOOD);
        worker.setHeldResource(UnitType.Resource.WOOD);
        worker.setCarried(20);
        worker.setResourceUnit(oldMine);
        worker.setResourceTile(fixture.x() + 3, fixture.y() + 4);

        assertTrue(world.orderReturnGoods(worker));

        // NewActionReturnGoods constructs a fresh COrder_Resource. Its union
        // is value-initialized: Resource.Mine is null and Resource.Pos is
        // {-1,-1}; only CurrentResource and DoneHarvesting are copied from
        // the worker. Reusing the prior order's wood square makes a partial
        // load sent home resume chopping there after it banks.
        assertNull(worker.resourceUnit());
        assertEquals(-1, worker.resourceTileX());
        assertEquals(-1, worker.resourceTileY());
    }

    @Test
    @DisplayName("a send-home that finds no more wood finishes one cycle after leaving the depot")
    void exhaustedReturnGoodsKeepsItsResourceOrderOnTheDropOutCycle() {
        Fixture fixture = load();
        World world = world(fixture.data());
        world.fog().revealAll(0);
        world.createUnit(fixture.types().get("unit-town-hall"), 0,
                fixture.x() + 5, fixture.y());
        Unit worker = world.createUnit(fixture.types().get("unit-peasant"), 0,
                fixture.x(), fixture.y());
        worker.setCarrying(UnitType.Resource.WOOD);
        worker.setHeldResource(UnitType.Resource.WOOD);
        worker.setCarried(20);

        assertTrue(world.orderReturnGoods(worker));
        boolean wentInside = false;
        for (int cycle = 0; cycle < 2500; cycle++) {
            world.tick();
            wentInside |= worker.removed();
            if (wentInside && worker.isOnMap()) {
                break;
            }
        }

        assertTrue(wentInside && worker.isOnMap(), "the worker never completed its depot visit");
        assertEquals(Unit.Order.HARVEST, worker.order(),
                "WaitInDepot replaced its finished resource order on the drop-out cycle");
        assertTrue(worker.orderFinished(),
                "the failed wood search did not mark the resource order finished");
        world.tick();
        assertEquals(Unit.Order.STILL, worker.order(),
                "HandleUnitAction did not retire the finished resource order next cycle");
    }

    @Test
    @DisplayName("an empty send-home without a depot stands still")
    void returnGoodsWithoutADepotStandsStill() {
        Fixture fixture = load();
        World world = world(fixture.data());
        Unit worker = world.createUnit(fixture.types().get("unit-peasant"), 0,
                fixture.x(), fixture.y());
        assertTrue(world.orderReturnGoods(worker),
                "GiveOrder table 24 still applies when FindDeposit answers none");
        assertEquals(Unit.Order.STILL, worker.order(),
                "native installs Still rather than a hall walk");
    }

    @Test
    @DisplayName("a siege weapon shells a square that holds a building")
    void attackGroundHitsTheSquare() {
        Fixture fixture = load();
        World world = world(fixture.data());
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        Unit ballista = world.createUnit(fixture.types().get("unit-ballista"), 0,
                fixture.x(), fixture.y());
        // A building, because anything that can walk will walk out from under
        // the shot: a grunt auto-attacks and closes while the bolt is in the
        // air, which is correct behaviour and makes for a useless probe.
        Unit farm = world.createUnit(fixture.types().get("unit-farm"), 1,
                fixture.x() + 6, fixture.y());
        int before = farm.hitPoints();

        assertTrue(world.orderAttackGround(ballista, fixture.x() + 6, fixture.y()));
        run(world, 600);
        assertTrue(farm.hitPoints() < before, "the square was never shelled");
    }

    @Test
    @DisplayName("only a unit with a projectile can shell a square")
    void attackGroundNeedsAMissile() {
        Fixture fixture = load();
        World world = world(fixture.data());
        Unit footman = world.createUnit(fixture.types().get("unit-footman"), 0,
                fixture.x(), fixture.y());
        // A footman strikes rather than throwing, so it has nothing to lob.
        assertTrue(!world.orderAttackGround(footman, fixture.x() + 4, fixture.y()));
    }
}
