package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import net.chonkbase.chonkcraft.engine.upgrade.UpgradeSet;
import org.junit.jupiter.api.Test;

/** Behavioural coverage for the animation selected by each kind of work. */
class WorkAnimationTest {

    private static final int BUILD_FRAME = 91;
    private static final int REPAIR_FRAME = 92;
    private static final int TRAIN_FRAME = 93;
    private static final int RESEARCH_FRAME = 94;
    private static final int UPGRADE_FRAME = 95;

    private static World world() {
        GameMap map = new GameMap(20, 20, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        world.player(0).set(Resource.GOLD, 10_000);
        world.player(0).set(Resource.WOOD, 10_000);
        return world;
    }

    private static AnimationSet workAnimations() {
        AnimationSet set = new AnimationSet("work");
        put(set, AnimationSet.State.STILL, 10);
        put(set, AnimationSet.State.BUILD, BUILD_FRAME);
        put(set, AnimationSet.State.REPAIR, REPAIR_FRAME);
        put(set, AnimationSet.State.TRAIN, TRAIN_FRAME);
        put(set, AnimationSet.State.RESEARCH, RESEARCH_FRAME);
        put(set, AnimationSet.State.UPGRADE, UPGRADE_FRAME);
        return set;
    }

    private static void put(AnimationSet set, AnimationSet.State state, int frame) {
        set.put(state, Animation.parse(state.name().toLowerCase(),
                List.of("sound work-" + state.name().toLowerCase(),
                        "frame " + frame, "wait 2")));
    }

    private static UnitType building(String ident) {
        UnitType type = new UnitType(ident);
        type.setTileSize(2, 2);
        type.setHitPoints(500);
        type.setBuilding(true);
        type.setSupply(10);
        type.setAnimationSet(workAnimations());
        type.costs().put(Resource.TIME, 20);
        return type;
    }

    private static UnitType worker() {
        UnitType type = new UnitType("unit-worker");
        type.setTileSize(1, 1);
        type.setHitPoints(30);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setRepairRange(1);
        type.setRepairHp(4);
        type.setAnimationSet(workAnimations());
        return type;
    }

    private static void assertWorkFrame(World world, Unit unit,
            AnimationSet.State state, int frame) {
        assertEquals(frame, unit.frame());
        assertEquals(state.name().toLowerCase(), unit.animation().current().name());
        assertTrue(world.drainSoundEvents().stream()
                .anyMatch(event -> event.named()
                        && ("work-" + state.name().toLowerCase()).equals(event.event())));
    }

    @Test
    void constructionPlaysBuildAnimation() {
        World world = world();
        Unit site = world.createUnit(building("unit-site"), 0, 5, 5);
        site.setOrder(Unit.Order.UNDER_CONSTRUCTION);
        site.setProgress(0);
        site.setProgressGoal(12_000);
        site.setHitPoints(1);

        world.tick();

        assertWorkFrame(world, site, AnimationSet.State.BUILD, BUILD_FRAME);
    }

    @Test
    void repairPlaysRepairAnimationAndItsSound() {
        World world = world();
        Unit target = world.createUnit(building("unit-target"), 0, 6, 5);
        target.setHitPoints(100);
        Unit repairer = world.createUnit(worker(), 0, 5, 5);
        assertTrue(world.orderRepair(repairer, target));

        world.tick();

        assertWorkFrame(world, repairer, AnimationSet.State.REPAIR, REPAIR_FRAME);
    }

    @Test
    void trainingPlaysTrainAnimation() {
        World world = world();
        Unit trainer = world.createUnit(building("unit-trainer"), 0, 5, 5);
        UnitType trainee = worker();
        trainee.setDemand(1);
        trainee.costs().put(Resource.TIME, 20);
        assertTrue(world.orderTrain(trainer, trainee));

        world.tick();

        assertWorkFrame(world, trainer, AnimationSet.State.TRAIN, TRAIN_FRAME);
    }

    @Test
    void aUnitBornDuringTheActionWalkCountsDemandBeforeTheCycleEnds() {
        World world = world();
        Unit trainer = world.createUnit(building("unit-trainer"), 0, 5, 5);
        UnitType trainee = worker();
        trainee.setDemand(1);
        trainee.costs().put(Resource.TIME, 1);
        world.recalculateSupply();
        assertEquals(0, world.player(0).demand());
        assertTrue(world.orderTrain(trainer, trainee));

        for (int cycle = 0; cycle < 5 && world.units().size() == 1; cycle++) {
            world.tick();
        }

        assertEquals(2, world.units().size(), "the fixture's trainee was never born");
        assertEquals(1, world.player(0).demand(),
                "the pending birth was omitted from the native-style demand counter");
    }

    @Test
    void researchPlaysResearchAnimation() {
        World world = world();
        UpgradeSet upgrades = new UpgradeSet();
        upgrades.getOrCreate("upgrade-test").costs().put(Resource.TIME, 20);
        world.setUpgrades(upgrades);
        Unit laboratory = world.createUnit(building("unit-laboratory"), 0, 5, 5);
        assertTrue(world.orderResearch(laboratory, "upgrade-test"));

        world.tick();

        assertWorkFrame(world, laboratory, AnimationSet.State.RESEARCH, RESEARCH_FRAME);
    }

    @Test
    void upgradingPlaysUpgradeAnimation() {
        World world = world();
        Unit hall = world.createUnit(building("unit-hall"), 0, 5, 5);
        UnitType keep = building("unit-keep");
        assertTrue(world.orderUpgradeTo(hall, keep));

        world.tick();

        assertWorkFrame(world, hall, AnimationSet.State.UPGRADE, UPGRADE_FRAME);
    }
}
