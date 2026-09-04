package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.pathfinder.BattleNetPathFinder;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A first-collision live Attack residual writes Move-start/15 on settle.
 *
 * <p>Authenticated campaign-1800 fixture {@code retail-xhuman-12-idle}:
 * grunt 1479 / Java 121 finishes its third heading of a twenty-byte chase at
 * fixture 386 with seventeen live route bytes still cached. Collision
 * generation is still one, even though two earlier refusals remain. Native
 * is already Move-start 2482/15 on that visit and consumes cached SE at
 * fixture 401. Treating the leftover refusal count as a refill stage first
 * exposed Move-start/1 and spent the heading one callback late.</p>
 */
class XHuman12LiveRouteAttackMoveSettleRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a live attack residual writes move-start fifteen on the settle visit")
    void aLiveAttackResidualWritesMoveStartFifteenOnTheSettleVisit() {
        Mission mission = mission("campaigns/human-exp/levelx12h");
        Unit grunt = byId(mission.world(), 121);
        assertNotNull(grunt, "XHuman 12 has no Java twin for native grunt 1479");

        tickThrough(mission, 385);
        assertEquals(Unit.Order.ATTACK, grunt.order());
        assertEquals(33, grunt.tileX());
        assertEquals(39, grunt.tileY());
        assertEquals(17, grunt.pathLength(),
                "the third heading still leaves seventeen live chase bytes");
        assertEquals(3, grunt.battleNetPathStepsTaken());
        assertEquals(BattleNetPathFinder.MAX_PATH,
                grunt.battleNetPathInitialLength());
        assertFalse(grunt.routeSpent(),
                "the cached SE-led tail is still a live residual");
        assertEquals(Direction.fromDelta(1, 1), grunt.peekHeading(),
                "the next cached heading is native's southeast byte");
        assertNotNull(grunt.target(), "the chase still names its footman");
        assertEquals("unit-footman", grunt.target().type().ident(),
                "the chase remains a mobile footman quarry");

        tickThrough(mission, 386);
        assertEquals(33, grunt.tileX(),
                "the settle visit stays on 33,39");
        assertEquals(39, grunt.tileY());
        assertEquals(1056, grunt.pixelX());
        assertEquals(1248, grunt.pixelY());
        assertEquals(17, grunt.pathLength(),
                "native keeps the approved seventeen-byte tail");
        assertFalse(grunt.routeSpent(),
                "the settle visit does not spend the live seventeen-byte tail");
        assertEquals(1, grunt.battleNetCollisionCounter(),
                "this settle is still native collision generation one");
        assertEquals(2, grunt.battleNetRefusals(),
                "two earlier refusals are not a later collision generation");
        assertEquals(moveStart(mission.world(), grunt),
                grunt.battleNetSequenceOffset(),
                "the refusal parks native Move construction");
        assertEquals(15, grunt.battleNetAnimationTimer(),
                "a live residual writes Move-start/15 on the settle visit");
        assertEquals(14, grunt.battleNetOrderDelay(),
                "timer fifteen leaves fourteen quiet scheduler visits");

        tickThrough(mission, 400);
        assertEquals(33, grunt.tileX(),
                "the complete Move band holds the grunt through timer one");
        assertEquals(39, grunt.tileY());
        assertEquals(1, grunt.battleNetAnimationTimer(),
                "native is still Move-start/1 on fixture 400");

        tickThrough(mission, 401);
        assertEquals(34, grunt.tileX(),
                "native consumes cached SE at fixture 401");
        assertEquals(40, grunt.tileY());
        assertEquals(1056, grunt.pixelX(),
                "the heading spends before its first southeast pixels");
        assertEquals(1248, grunt.pixelY());
        assertEquals(Direction.fromDelta(1, 1), grunt.lastStepHeading());
        assertEquals(16, grunt.pathLength(),
                "sixteen live headings remain after the SE step");
    }

    @Test
    @DisplayName("a shorter live residual still writes move-start fifteen immediately")
    void aShorterLiveResidualStillWritesMoveStartFifteenImmediately() {
        Mission mission = mission("campaigns/human-exp/levelx12h");
        Unit grunt = byId(mission.world(), 137);
        assertNotNull(grunt, "XHuman 12 has no Java twin for native grunt 1463");

        tickThrough(mission, 123);
        assertEquals(Unit.Order.ATTACK, grunt.order(),
                "the held-out chase remains an Attack order");
        assertEquals(22, grunt.tileX(),
                "grunt 1463 stays on its formation square");
        assertEquals(57, grunt.tileY(),
                "grunt 1463 stays on its formation square");
        assertEquals(5, grunt.pathLength(),
                "grunt 1463 keeps five remaining headings at fixture 123");
        assertEquals(2, grunt.battleNetPathStepsTaken(),
                "two headings of the seven-byte chase have already spent");
        assertFalse(grunt.routeSpent(),
                "the five-byte tail is still a live residual");
        assertNotNull(grunt.target(), "the held-out chase still names its knight");
        assertEquals("unit-knight", grunt.target().type().ident(),
                "the held-out quarry is still a mobile knight");
        assertEquals(moveStart(mission.world(), grunt),
                grunt.battleNetSequenceOffset(),
                "the held-out refusal parks native Move construction");
        assertEquals(15, grunt.battleNetAnimationTimer(),
                "the shorter live residual also writes Move-start/15 now");
    }

    private static Mission mission(String map) {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
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

    private static int moveStart(World world, Unit unit) {
        return world.idle.battleNetSequenceStart(unit,
                BattleNetSequence.MOVE_ANIMATION);
    }

    private static Unit byId(World world, int id) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }
}
