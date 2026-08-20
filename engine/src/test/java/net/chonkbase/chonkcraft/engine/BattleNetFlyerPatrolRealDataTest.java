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

    private static Mission mission(String map) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
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
