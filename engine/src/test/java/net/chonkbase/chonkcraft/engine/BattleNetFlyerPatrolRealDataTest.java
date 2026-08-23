package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated armed-flyer Patrol construction and first-step timing. */
class BattleNetFlyerPatrolRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("Orc 5's balloon does not inherit a naval near-point delay")
    void orc5BalloonUsesTheAircraftPatrolConstructorOverWater() {
        Mission mission = mission("campaigns/orc/level05o");
        Unit balloon = at(mission.world(), "unit-balloon", 50, 86);
        assertNotNull(balloon, "Orc 5 has no southern balloon at 50,86");

        tickThrough(mission, 99);
        assertEquals(Unit.Order.PATROL, balloon.order());
        assertEquals(58, balloon.tileX());
        assertEquals(80, balloon.tileY(),
                "the recurring scout pass promotes Patrol on fixture 99");

        tickThrough(mission, 101);
        assertEquals(58, balloon.tileX());
        assertEquals(80, balloon.tileY(),
                "the ordinary two quiet visits own fixtures 100 and 101");

        tickThrough(mission, 102);
        assertEquals(58, balloon.tileX());
        assertEquals(78, balloon.tileY(),
                "air movement must ignore the naval open-water wiggle band");
    }

    @Test
    @DisplayName("XOrc 8's corner gryphon waits for Patrol construction and detours")
    void xOrc8CornerGryphonWaitsForPatrolConstructionAndDetours() {
        Mission mission = mission("campaigns/orc-exp/levelx08o");
        Unit rider = at(mission.world(), "unit-gryphon-rider", 2, 4);
        assertNotNull(rider, "XOrc 8 has no corner gryphon at 2,4");

        tickThrough(mission, 52);
        assertEquals(Unit.Order.PATROL, rider.order());
        assertEquals(0, rider.tileX());
        assertEquals(6, rider.tileY());
        assertEquals(2233, rider.battleNetSequenceOffset());
        assertEquals(3, rider.battleNetAnimationTimer());

        tickThrough(mission, 59);
        assertEquals(0, rider.tileX(),
                "the native Still body holds the blocked south stride");
        assertEquals(6, rider.tileY());
        assertEquals(2237, rider.battleNetSequenceOffset());
        assertEquals(1, rider.battleNetAnimationTimer());

        tickThrough(mission, 60);
        assertEquals(Unit.Order.PATROL, rider.order());
        assertEquals(2, rider.tileX(),
                "native first-steps south-east around the allied flyer");
        assertEquals(8, rider.tileY());
        assertEquals(2259, rider.battleNetSequenceOffset());
        assertEquals(1, rider.battleNetAnimationTimer());

        tickThrough(mission, 108);
        assertEquals(Unit.Order.PATROL, rider.order(),
                "the far scout leg must survive its first residual settle");
        assertEquals(0, rider.tileX());
        assertEquals(12, rider.tileY(),
                "the retained south-west byte returns the far rider to line");
    }

    @Test
    @DisplayName("XOrc 8's recurring flyer sweep spends the native cycle-99 draws")
    void xOrc8RecurringFlyerSweepUsesTheNativeAsyncBoundary() {
        Mission mission = mission("campaigns/orc-exp/levelx08o");
        Unit rider = at(mission.world(), "unit-gryphon-rider", 4, 6);
        Unit edgeRider = at(mission.world(), "unit-gryphon-rider", 8, 0);
        assertNotNull(rider, "XOrc 8 has no native slot-1550 gryphon at 4,6");
        assertNotNull(edgeRider,
                "XOrc 8 has no native slot-1581 gryphon at 8,0");

        tickThrough(mission, 98);
        assertEquals(248433422L,
                Integer.toUnsignedLong(mission.world().battleNetRandomSeed()),
                "native finishes fixture 98 on the coordinate pair's input seed");

        tickThrough(mission, 99);
        assertEquals(Unit.Order.STILL, rider.order());
        assertEquals(0, rider.battleNetPendingPatrolX());
        assertEquals(12, rider.battleNetPendingPatrolY(),
                "native's 922/3300 draw pair selects (0,12)");

        tickThrough(mission, 108);
        assertEquals(Unit.Order.PATROL, rider.order());
        assertEquals(2, rider.tileX());
        assertEquals(10, rider.tileY(),
                "the west-edge route detours south-east toward the native point");

        tickThrough(mission, 132);
        assertEquals(Unit.Order.STILL, rider.order(),
                "the one-shot scout leg ends when its last detour pixels settle");
        assertEquals(2, rider.tileX());
        assertEquals(10, rider.tileY());
        assertEquals(2233, rider.battleNetSequenceOffset());
        assertEquals(1, rider.battleNetAnimationTimer());

        tickThrough(mission, 149);
        assertEquals(Unit.Order.STILL, rider.order());
        assertEquals(0, rider.battleNetPendingPatrolX());
        assertEquals(16, rider.battleNetPendingPatrolY(),
                "the next native fifty-cycle beat queues the next scout leg");

        tickThrough(mission, 158);
        assertEquals(Unit.Order.PATROL, rider.order());
        assertEquals(2, rider.tileX());
        assertEquals(12, rider.tileY());
        assertEquals(Unit.Order.STILL, edgeRider.order(),
                "an exact-anchor recurring point ends after construction OP0");
        assertEquals(0, edgeRider.tileX());
        assertEquals(0, edgeRider.tileY());
        assertEquals(2233, edgeRider.battleNetSequenceOffset());
        assertEquals(2, edgeRider.battleNetAnimationTimer(),
                "native seals 2233/3 at fixture 157 and counts down at 158");
    }

    @Test
    @DisplayName("XOrc 11's launched gryphon reconstructs Patrol before its far march")
    void xOrc11LaunchedGryphonReconstructsPatrolBeforeItsFarMarch() {
        Mission mission = mission("campaigns/orc-exp/levelx11o");
        Unit rider = at(mission.world(), "unit-gryphon-rider", 42, 4);
        assertNotNull(rider, "XOrc 11 has no north-edge gryphon at 42,4");

        tickThrough(mission, 60);
        assertEquals(42, rider.tileX());
        assertEquals(8, rider.tileY());
        assertEquals(2, rider.battleNetAiBehavior(),
                "the replacement patrol belongs to a launched AI force");

        tickThrough(mission, 61);
        assertEquals(Unit.Order.PATROL, rider.order());
        assertEquals(42, rider.tileX(),
                "residual settle reconstructs Still instead of spending SW");
        assertEquals(8, rider.tileY());
        assertEquals(2233, rider.battleNetSequenceOffset());
        assertEquals(3, rider.battleNetAnimationTimer());

        tickThrough(mission, 68);
        assertEquals(42, rider.tileX(),
                "Patrol construction holds through its final wait");
        assertEquals(8, rider.tileY());
        assertEquals(2237, rider.battleNetSequenceOffset());
        assertEquals(1, rider.battleNetAnimationTimer());

        tickThrough(mission, 69);
        assertEquals(40, rider.tileX(),
                "the completed constructor releases the south-west route");
        assertEquals(10, rider.tileY());
        assertEquals(2259, rider.battleNetSequenceOffset());
        assertEquals(1, rider.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("XOrc 11's moving gryphon accepts the recurring air Patrol replacement")
    void xOrc11MovingGryphonAcceptsTheRecurringAirPatrolReplacement() {
        Mission mission = mission("campaigns/orc-exp/levelx11o");
        Unit rider = at(mission.world(), "unit-gryphon-rider", 42, 4);
        assertNotNull(rider, "XOrc 11 has no north-edge gryphon at 42,4");

        tickThrough(mission, 99);
        assertEquals(Unit.Order.PATROL, rider.order());
        assertEquals(38, rider.tileX());
        assertEquals(12, rider.tileY());
        assertEquals(true, rider.hasBattleNetPendingPatrol(),
                "the fifty-cycle air pass writes Patrol as next_order");
        assertEquals(0, rider.pathLength(),
                "the replacement parks the old Patrol route at index 20");

        tickThrough(mission, 117);
        assertEquals(38, rider.tileX(),
                "committed flight pixels land before replacement construction");
        assertEquals(12, rider.tileY());
        assertEquals(Unit.Order.PATROL, rider.order());
        assertEquals(false, rider.hasBattleNetPendingPatrol());
        assertEquals(2233, rider.battleNetSequenceOffset());
        assertEquals(3, rider.battleNetAnimationTimer(),
                "the landing callback reconstructs native Patrol Still");

        tickThrough(mission, 120);
        assertEquals(2237, rider.battleNetSequenceOffset());
        assertEquals(5, rider.battleNetAnimationTimer());
        tickThrough(mission, 124);
        assertEquals(38, rider.tileX());
        assertEquals(12, rider.tileY());
        assertEquals(1, rider.battleNetAnimationTimer());

        tickThrough(mission, 125);
        assertEquals(36, rider.tileX(),
                "the completed constructor releases BNE's south-west stride");
        assertEquals(14, rider.tileY());
    }

    @Test
    @DisplayName("XOrc 11's adopted gryphon spends the native startup scout pair")
    void xOrc11AdoptedGryphonSpendsNativeStartupScoutPair() {
        Mission mission = unwarmedMission("campaigns/orc-exp/levelx11o");
        Unit rider = at(mission.world(), "unit-gryphon-rider", 42, 4);
        assertNotNull(rider, "XOrc 11 has no north-edge gryphon at 42,4");

        assertEquals(3267316865L,
                Integer.toUnsignedLong(mission.world().battleNetRandomSeed()),
                "native spends exactly the 00427986/004279B3 coordinate pair");
    }

    private static Mission mission(String map) {
        Mission mission = unwarmedMission(map);
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        return mission;
    }

    private static Mission unwarmedMission(String map) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        return mission;
    }

    private static void tickThrough(Mission mission, int fixtureCycle) {
        while (mission.world().cycle() - BNE_INITIALIZATION_TICKS
                < fixtureCycle) {
            mission.tick();
        }
    }

    private static Unit at(World world, String ident, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.tileX() == x
                    && unit.tileY() == y && unit.type() != null
                    && ident.equals(unit.type().ident())) {
                return unit;
            }
        }
        return null;
    }
}
