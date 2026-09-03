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

/** Authenticated small-warship map-Patrol refusal ladder from retail BNE. */
class BattleNetNavalMapPatrolRefusalRealDataTest {

    private static final String MAP = "campaigns/human-exp/levelx07h";
    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("XHuman 7 destroyers give up their blocked map patrol on fixture 72")
    void xHuman7DestroyersGiveUpBlockedMapPatrolOnFixture72()
            throws Exception {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK");
        GameData data = new GameData(assets);
        Mission continuous = mission(data);
        Unit north = at(continuous.world(), 6, 28, 26);
        Unit south = at(continuous.world(), 6, 28, 28);
        assertNotNull(north, "XHuman 7 has no northern startup destroyer");
        assertNotNull(south, "XHuman 7 has no southern startup destroyer");

        tickThrough(continuous, 42);
        assertEquals(Unit.Order.PATROL, north.order());
        assertEquals(Unit.Order.PATROL, south.order());
        assertEquals(8, north.battleNetRefusals(),
                "the two opening refusals synchronize the northern hull");
        assertEquals(8, south.battleNetRefusals());
        assertEquals(14, north.waitCycles());
        assertEquals(14, south.waitCycles(),
                "refusal eight owns BNE's first fifteen-count band");

        StringWriter out = new StringWriter();
        SaveGame.writeWithTriggers(continuous.world(), MAP, "human-exp", 7,
                continuous.triggers().savedState(), out);
        String script = out.toString();
        assertTrue(script.contains("battleNetRefusals = 8"),
                "the live native refusal ladder must be saved");

        Mission resumed = mission(data);
        for (Unit unit : new ArrayList<>(resumed.world().units())) {
            resumed.world().remove(unit);
        }
        LoadGame.apply(resumed.world(), script, data.unitTypes().types());
        resumed.triggers().restoreState(LoadGame.triggerState(script));
        Unit loadedNorth = at(resumed.world(), 6, 26, 26);
        Unit loadedSouth = at(resumed.world(), 6, 26, 28);
        assertNotNull(loadedNorth);
        assertNotNull(loadedSouth);
        assertEquals(8, loadedNorth.battleNetRefusals());
        assertEquals(8, loadedSouth.battleNetRefusals());

        tickThrough(continuous, 57);
        tickThrough(resumed, 57);
        assertRefusalBand(north, south, 9);
        assertRefusalBand(loadedNorth, loadedSouth, 9);

        tickThrough(continuous, 71);
        tickThrough(resumed, 71);
        assertEquals(Unit.Order.PATROL, north.order());
        assertEquals(Unit.Order.PATROL, south.order());
        assertEquals(Unit.Order.PATROL, loadedNorth.order());
        assertEquals(Unit.Order.PATROL, loadedSouth.order());

        tickThrough(continuous, 72);
        tickThrough(resumed, 72);
        assertStoodDown(north, south);
        assertStoodDown(loadedNorth, loadedSouth);
    }

    @Test
    @DisplayName("XHuman 7 relaunches a moved destroyer from its stable naval home")
    void xHuman7RelaunchesMovedDestroyerFromStableNavalHome() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK");
        Mission mission = mission(new GameData(assets));
        Unit north = at(mission.world(), 6, 28, 26);
        assertNotNull(north, "XHuman 7 has no northern startup destroyer");
        assertEquals(22, north.battleNetAiHomeX(),
                "behavior-six home is the shipyard selected at ready time");
        assertEquals(27, north.battleNetAiHomeY());

        tickThrough(mission, 102);
        assertEquals(Unit.Order.STILL, north.order());
        assertEquals(26, north.tileX());
        assertEquals(26, north.tileY());

        tickThrough(mission, 103);
        assertEquals(Unit.Order.PATROL, north.order(),
                "the recurring naval pass promotes on native fixture 103");
        assertEquals(23, north.orderTargetX(),
                "the shore point is recomputed from stable home and new hull position");
        assertEquals(27, north.orderTargetY());

        tickThrough(mission, 105);
        assertEquals(26, north.tileX(),
                "the Patrol constructor holds through fixture 105");
        assertEquals(26, north.tileY());

        tickThrough(mission, 106);
        assertEquals(Unit.Order.PATROL, north.order());
        assertEquals(24, north.tileX(),
                "native slot 1570 takes its doubled northwest stride on 106");
        assertEquals(24, north.tileY());
        assertEquals(64, north.offsetX(),
                "logical NW commits while the rendered hull starts at the old anchor");
        assertEquals(64, north.offsetY());
    }

    @Test
    @DisplayName("XHuman 7's saturated destroyer redraws around another destroyer")
    void xHuman7SaturatedDestroyerRedrawsAroundSurfaceHull() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK");
        Mission mission = mission(new GameData(assets));
        Unit north = at(mission.world(), 6, 28, 26);
        assertNotNull(north, "XHuman 7 has no northern startup destroyer");

        tickThrough(mission, 387);
        assertEquals(34, north.tileX(),
                "a surface blocker does not turn refusal ten into a fresh band");
        assertEquals(28, north.tileY());
        assertEquals(10, north.battleNetRefusals(),
                "the cold redraw still advances the sticky refusal generation");
    }

    private static void assertRefusalBand(Unit north, Unit south,
            int refusals) {
        assertEquals(refusals, north.battleNetRefusals());
        assertEquals(refusals, south.battleNetRefusals());
        assertEquals(14, north.waitCycles());
        assertEquals(14, south.waitCycles());
        assertEquals(Unit.Order.PATROL, north.order());
        assertEquals(Unit.Order.PATROL, south.order());
    }

    private static void assertStoodDown(Unit north, Unit south) {
        assertEquals(Unit.Order.STILL, north.order());
        assertEquals(Unit.Order.STILL, south.order());
        assertEquals(9, north.battleNetRefusals(),
                "native's refusal nibble remains sticky after Patrol ends");
        assertEquals(9, south.battleNetRefusals());
        assertEquals(26, north.tileX());
        assertEquals(26, north.tileY());
        assertEquals(26, south.tileX());
        assertEquals(28, south.tileY());
    }

    private static Mission mission(GameData data) {
        Mission mission = data.loadMission(MAP,
                GameData.personIn(data.campaignMap(MAP)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 7 is not in the pack");
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

    private static Unit at(World world, int player, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.player() == player
                    && unit.tileX() == x && unit.tileY() == y
                    && unit.type() != null
                    && "unit-orc-destroyer".equals(unit.type().ident())) {
                return unit;
            }
        }
        return null;
    }
}
