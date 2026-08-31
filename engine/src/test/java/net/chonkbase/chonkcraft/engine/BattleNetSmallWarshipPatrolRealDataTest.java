package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.util.ArrayList;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.save.LoadGame;
import net.chonkbase.chonkcraft.engine.save.SaveGame;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated recurring naval-pass construction for small warships. */
class BattleNetSmallWarshipPatrolRealDataTest {

    private static final String MAP = "campaigns/orc-exp/levelx08o";
    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("XOrc 8's destroyer promotes the queued naval pass after landing")
    void xOrc8DestroyerPromotesQueuedNavalPassAfterLanding() throws Exception {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK");
        GameData data = new GameData(assets);
        Mission continuous = mission(data);
        Unit destroyer = at(continuous.world(), "unit-human-destroyer",
                2, 46, 120);
        assertNotNull(destroyer,
                "XOrc 8 has no player-two human destroyer at 46,120");

        tickThrough(continuous, 49);
        assertEquals(Unit.Order.PATROL, destroyer.order());
        assertEquals(50, destroyer.tileX());
        assertEquals(124, destroyer.tileY());
        assertTrue(destroyer.hasBattleNetPendingPatrol(),
                "the fixture-49 naval pass must queue the replacement Patrol");
        assertEquals(0, destroyer.pathLength(),
                "native writes route_index 20 while the old pixels keep draining");

        tickThrough(continuous, 67);
        assertEquals(50, destroyer.tileX(),
                "landing promotes the queue without consuming the east stride");
        assertEquals(124, destroyer.tileY());
        assertEquals(3129, destroyer.battleNetSequenceOffset());
        assertEquals(3, destroyer.battleNetAnimationTimer());

        tickThrough(continuous, 68);
        assertEquals(3129, destroyer.battleNetSequenceOffset());
        assertEquals(2, destroyer.battleNetAnimationTimer());

        StringWriter out = new StringWriter();
        SaveGame.writeWithTriggers(continuous.world(), MAP, "orc-exp", 8,
                continuous.triggers().savedState(), out);
        String script = out.toString();
        assertTrue(script.contains("battleNetSequenceOffset = 3129"),
                "the in-flight native Patrol constructor must be saved");

        Mission resumed = mission(data);
        for (Unit unit : new ArrayList<>(resumed.world().units())) {
            resumed.world().remove(unit);
        }
        LoadGame.apply(resumed.world(), script, data.unitTypes().types());
        resumed.triggers().restoreState(LoadGame.triggerState(script));
        Unit loaded = at(resumed.world(), "unit-human-destroyer",
                2, 50, 124);
        assertNotNull(loaded, "the destroyer was not restored at its saved anchor");
        assertEquals(3129, loaded.battleNetSequenceOffset());
        assertEquals(2, loaded.battleNetAnimationTimer());

        tickThrough(continuous, 69);
        tickThrough(resumed, 69);
        assertEquals(3129, destroyer.battleNetSequenceOffset());
        assertEquals(1, destroyer.battleNetAnimationTimer());
        assertEquals(destroyer.battleNetSequenceOffset(),
                loaded.battleNetSequenceOffset());
        assertEquals(destroyer.battleNetAnimationTimer(),
                loaded.battleNetAnimationTimer());

        tickThrough(continuous, 70);
        tickThrough(resumed, 70);
        assertEquals(52, destroyer.tileX(),
                "the completed 3,2,1 constructor releases east on fixture 70");
        assertEquals(124, destroyer.tileY());
        assertEquals(3137, destroyer.battleNetSequenceOffset());
        assertEquals(destroyer.tileX(), loaded.tileX(),
                "save/resume must release on the same native beat");
        assertEquals(destroyer.tileY(), loaded.tileY());
        assertEquals(destroyer.battleNetSequenceOffset(),
                loaded.battleNetSequenceOffset());
    }

    @Test
    @DisplayName("XOrc 8 preserves naval refusal provenance across a free stride")
    void xOrc8PreservesNavalRefusalProvenanceAcrossAFreeStride() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK");
        GameData data = new GameData(assets);
        Mission mission = mission(data);
        Unit destroyer = at(mission.world(), "unit-human-destroyer",
                2, 102, 92);
        assertNotNull(destroyer,
                "XOrc 8 has no player-two human destroyer at 102,92");

        tickThrough(mission, 38);
        Unit unpressuredBlocker = unitById(mission.world(), 168);
        assertNotNull(unpressuredBlocker,
                "XOrc 8 has no Java twin for native submarine 1432");
        assertEquals(0, unpressuredBlocker.battleNetCollisionCounter());
        assertEquals(0, unpressuredBlocker.battleNetRefusals(),
                "the opening submarine has no collision pressure");
        assertEquals(1, destroyer.pathLength(),
                "an unpressured temporary hull keeps the cached northwest tail");
        assertTrue(!destroyer.battleNetNavalPaidParkedRoute(),
                "the unpressured control must not arm a parked redraw");
        assertEquals(15, destroyer.battleNetAnimationTimer());

        tickThrough(mission, 53);
        assertEquals(100, destroyer.tileX());
        assertEquals(88, destroyer.tileY(),
                "the unpressured control commits its retained northwest tail");

        tickThrough(mission, 84);
        assertEquals(100, destroyer.tileX());
        assertEquals(88, destroyer.tileY());
        assertEquals(1, destroyer.battleNetRefusals(),
                "the first blocked cached heading remains sticky after c53's "
                        + "successful stride");

        tickThrough(mission, 85);
        assertEquals(2, destroyer.battleNetRefusals(),
                "the fresh route's first blocked probe advances the sticky count");
        assertEquals(1, destroyer.battleNetAnimationTimer(),
                "counts below eight retry on the one-cycle Move seam");

        tickThrough(mission, 90);
        assertEquals(7, destroyer.battleNetRefusals());
        assertEquals(100, destroyer.tileX());
        assertEquals(88, destroyer.tileY());

        tickThrough(mission, 91);
        assertEquals(8, destroyer.battleNetRefusals());
        assertEquals(15, destroyer.battleNetAnimationTimer(),
                "refusal eight opens the native fifteen-count cooperative band");

        tickThrough(mission, 100);
        assertEquals(100, destroyer.tileX(),
                "the destroyer must not move on Java's former early c100 beat");
        assertEquals(88, destroyer.tileY());
        assertEquals(6, destroyer.battleNetAnimationTimer());

        tickThrough(mission, 106);
        assertEquals(98, destroyer.tileX(),
                "timer-one wake consumes northwest on native fixture 106");
        assertEquals(86, destroyer.tileY());
        assertEquals(8, destroyer.battleNetRefusals(),
                "a successful step does not clear the native sticky nibble");
    }

    @Test
    @DisplayName("XOrc 8's landing destroyer promotes the cycle-230 naval pass")
    void xOrc8LandingDestroyerPromotesCycle230NavalPass() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK");
        GameData data = new GameData(assets);
        Mission mission = mission(data);
        Unit destroyer = unitById(mission.world(), 165);
        assertNotNull(destroyer,
                "XOrc 8 has no Java twin for native destroyer 1435");

        tickThrough(mission, 230);
        assertEquals(Unit.Order.PATROL, destroyer.order());
        assertEquals(88, destroyer.tileX());
        assertEquals(74, destroyer.tileY());

        tickThrough(mission, 231);
        assertEquals(Unit.Order.PATROL, destroyer.order(),
                "the landing callback promotes the newly queued naval pass");
        assertEquals(115, destroyer.orderTargetX());
        assertEquals(53, destroyer.orderTargetY());
        assertEquals(3129, destroyer.battleNetSequenceOffset());
        assertEquals(3, destroyer.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("XOrc 8's landing destroyer refuses its next occupied route head")
    void xOrc8LandingDestroyerRefusesNextOccupiedRouteHead() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK");
        GameData data = new GameData(assets);
        Mission mission = mission(data);
        Unit destroyer = unitById(mission.world(), 165);
        assertNotNull(destroyer,
                "XOrc 8 has no Java twin for native destroyer 1435");

        tickThrough(mission, 302);
        assertEquals(90, destroyer.tileX());
        assertEquals(76, destroyer.tileY());
        assertTrue(destroyer.isMoving(),
                "the first south-east stride still owes two pixels");
        assertEquals(6, destroyer.pathLength());
        assertEquals(9, destroyer.battleNetRefusals());

        tickThrough(mission, 303);
        assertEquals(90, destroyer.tileX(),
                "the residual-settle visit refuses east instead of redrawing south-east");
        assertEquals(76, destroyer.tileY());
        assertTrue(!destroyer.isMoving());
        assertEquals(0, destroyer.pathLength(),
                "native parks the occupied cached route at index twenty");
        assertEquals(10, destroyer.battleNetRefusals());
        assertEquals(14, destroyer.battleNetOrderDelay());
        assertEquals(15, destroyer.battleNetAnimationTimer());
        assertTrue(!destroyer.battleNetNavalPaidParkedRoute(),
                "a longer parked tail redraws from current occupancy, not a terminal face");

        tickThrough(mission, 318);
        assertEquals(92, destroyer.tileX(),
                "timer-one redraw releases the native south-east stride");
        assertEquals(78, destroyer.tileY());
        assertTrue(destroyer.isMoving());
        assertEquals(10, destroyer.battleNetRefusals(),
                "a successful stride keeps the sticky native refusal nibble");
    }

    @Test
    @DisplayName("XOrc 8's paid destroyer route releases north on cycle 232")
    void xOrc8PaidDestroyerRouteReleasesNorthOnCycle232() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK");
        GameData data = new GameData(assets);
        Mission mission = mission(data);
        Unit destroyer = unitById(mission.world(), 169);
        assertNotNull(destroyer,
                "XOrc 8 has no Java twin for native destroyer 1431");

        tickThrough(mission, 231);
        assertEquals(Unit.Order.PATROL, destroyer.order());
        assertEquals(94, destroyer.tileX());
        assertEquals(82, destroyer.tileY());

        tickThrough(mission, 232);
        assertEquals(Unit.Order.PATROL, destroyer.order());
        assertEquals(94, destroyer.tileX());
        assertEquals(80, destroyer.tileY(),
                "timer-one wakes and commits the cached north heading");
        assertEquals(3137, destroyer.battleNetSequenceOffset());
        assertEquals(1, destroyer.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("XOrc 8's paid destroyer route holds its consumed leftover on cycle 264")
    void xOrc8PaidDestroyerRouteHoldsItsConsumedLeftoverOnCycle264() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK");
        GameData data = new GameData(assets);
        Mission mission = mission(data);
        Unit destroyer = unitById(mission.world(), 169);
        assertNotNull(destroyer,
                "XOrc 8 has no Java twin for native destroyer 1431");

        tickThrough(mission, 263);
        assertEquals(Unit.Order.PATROL, destroyer.order());
        assertEquals(94, destroyer.tileX());
        assertEquals(80, destroyer.tileY());
        assertEquals(1, destroyer.pathLength(),
                "the paid wake's replacement route keeps northwest after north");
        assertEquals(1, destroyer.battleNetPathStepsTaken(),
                "north must remain recorded as a consumed route heading");
        assertEquals(1, destroyer.battleNetAnimationTimer(),
                "the north stride settles on the timer-one wake");

        tickThrough(mission, 264);
        assertEquals(94, destroyer.tileX(),
                "the allied submarine blocks the consumed northwest leftover");
        assertEquals(80, destroyer.tileY(),
                "native retains the anchor while arming the cooperative hold");
        assertEquals(0, destroyer.pathLength(),
                "native parks the consumed tail behind a pressured hull");
        assertEquals(0, destroyer.battleNetPathStepsTaken(),
                "the parked route no longer exposes consumed provenance");
        assertEquals(15, destroyer.battleNetAnimationTimer(),
                "the blocked leftover opens the native fifteen-count Move band");

        Unit pressuredBlocker = unitById(mission.world(), 167);
        assertNotNull(pressuredBlocker,
                "XOrc 8 has no Java twin for native submarine 1433");
        assertTrue(pressuredBlocker.battleNetCollisionCounter() > 0
                        || pressuredBlocker.battleNetRefusals() > 0,
                "the later submarine must carry collision pressure");
        assertTrue(destroyer.battleNetNavalPaidParkedRoute(),
                "the paid parked route must preserve pass-start occupancy");
        tickThrough(mission, 278);
        assertEquals(94, destroyer.tileX());
        assertEquals(80, destroyer.tileY());
        assertEquals(1, destroyer.battleNetAnimationTimer(),
                "the parked tail still pays the complete Move band");

        tickThrough(mission, 279);
        assertEquals(92, destroyer.tileX());
        assertEquals(82, destroyer.tileY(),
                "the paid wake redraws southwest instead of retrying northwest");
        assertEquals(6, destroyer.pathLength(),
                "southwest is consumed from the native seven-heading redraw");
        assertEquals(1, destroyer.battleNetPathStepsTaken());
        assertTrue(!destroyer.battleNetNavalPaidParkedRoute(),
                "the pass-start occupancy marker is one redraw wide");
    }

    private static Mission mission(GameData data) {
        Mission mission = data.loadMission(MAP,
                GameData.personIn(data.campaignMap(MAP)), 1);
        Assumptions.assumeTrue(mission != null, "XOrc 8 is not in the pack");
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

    private static Unit at(World world, String ident, int player, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.player() == player
                    && unit.tileX() == x && unit.tileY() == y
                    && unit.type() != null
                    && ident.equals(unit.type().ident())) {
                return unit;
            }
        }
        return null;
    }

    private static Unit unitById(World world, int id) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }
}
