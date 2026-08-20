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
}
