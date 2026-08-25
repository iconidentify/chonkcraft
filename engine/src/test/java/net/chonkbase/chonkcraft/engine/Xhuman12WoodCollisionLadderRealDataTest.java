package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated wood collision ladder for XHuman 12's peon 1376. */
class Xhuman12WoodCollisionLadderRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 12's blocked wood residual redraws south on collision five")
    void xhuman12PeonRedrawsSouthAfterItsFiveVisitCollisionLadder() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();
        Unit peon = unitById(world, 224);
        assertNotNull(peon, "XHuman 12 has no Java unit 224 / native peon 1376");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 214) {
            mission.tick();
        }

        assertEquals(10, peon.tileX(),
                "the prior south stride is still draining on fixture 214");
        assertEquals(86, peon.tileY(),
                "the prior south stride is still draining on fixture 214");
        assertTrue(peon.isMoving(),
                "fixture 214 retains the final two residual pixels");

        mission.tick();
        assertEquals(215, fixtureCycle(world));
        assertFalse(peon.isMoving(), "the south residual settles on fixture 215");
        assertEquals(0, peon.pathLength(),
                "native parks the repeated south tail at route index twenty");
        assertEquals(1, peon.battleNetCollisionCounter(),
                "the retired approach starts a fresh collision generation");
        assertEquals(0, peon.battleNetRefusals(),
                "old Java refusal history does not enter the new corner ladder");
        assertTrue(peon.battleNetSaturatedWoodCornerLadder(),
                "the repeated cardinal residual owns the corner ladder");

        for (int fixture = 216; fixture <= 218; fixture++) {
            mission.tick();
            assertEquals(fixture, fixtureCycle(world));
            assertEquals(4, peon.pathLength(),
                    "native retains SW,SE,E,E behind route index twenty");
            assertEquals(fixture - 214,
                    peon.battleNetCollisionCounter(),
                    "each blocked corner visit advances one collision rung");
            assertEquals(Direction.fromDelta(-1, 1), peon.peekHeading(),
                    "the retained corner face remains south-west");
            assertEquals(fixture - 215,
                    peon.battleNetWoodCornerRefusalVisits(),
                    "the corner visit count tracks the retained native bytes");
        }

        mission.tick();
        assertEquals(219, fixtureCycle(world));
        assertEquals(0, peon.pathLength(),
                "collision five releases the stale route for redraw");
        assertEquals(5, peon.battleNetCollisionCounter(),
                "the complete native corner generation is collision five");
        assertEquals(4, peon.battleNetWoodCornerRefusalVisits(),
                "four retained visits follow the initial residual park");

        mission.tick();
        assertEquals(220, fixtureCycle(world));
        assertEquals(10, peon.tileX(),
                "the replacement first heading is cardinal south");
        assertEquals(87, peon.tileY(),
                "fixture 220 consumes native's replacement south heading");
        assertTrue(peon.isMoving(),
                "the committed south tile still owns its pixel residual");
        assertEquals(2, peon.pathLength(),
                "native retains south-east,east after consuming south");
        assertEquals(Direction.fromDelta(1, 1), peon.peekHeading(),
                "the next replacement byte is south-east");
        assertEquals(5, peon.battleNetCollisionCounter(),
                "the replacement route keeps its paid collision generation");
        assertFalse(peon.battleNetSaturatedWoodCornerLadder(),
                "the successful redraw consumes the transient ladder marker");
        assertEquals(-1, peon.battleNetWoodCornerRefusalHeading(),
                "the refused south-west wall face is retired");

        while (fixtureCycle(world) < 235) {
            mission.tick();
        }
        assertEquals(10, peon.tileX());
        assertEquals(87, peon.tileY());
        assertTrue(peon.isMoving(),
                "fixture 235 retains the final two south residual pixels");
        assertEquals(5, peon.battleNetCollisionCounter());

        mission.tick();
        assertEquals(236, fixtureCycle(world));
        assertFalse(peon.isMoving());
        assertEquals(2, peon.pathLength(),
                "collision six keeps southeast,east behind native route index one");
        assertEquals(Direction.fromDelta(1, 1), peon.peekHeading());
        assertEquals(6, peon.battleNetCollisionCounter());
        assertEquals(14, peon.battleNetOrderDelay());
        assertEquals(15, peon.battleNetAnimationTimer());

        for (int fixture = 237; fixture <= 250; fixture++) {
            mission.tick();
            assertEquals(fixture, fixtureCycle(world));
            assertEquals(250 - fixture, peon.battleNetOrderDelay());
            assertEquals(251 - fixture, peon.battleNetAnimationTimer());
        }
        assertEquals(10, peon.tileX());
        assertEquals(87, peon.tileY());
        assertEquals(2, peon.pathLength());

        mission.tick();
        assertEquals(251, fixtureCycle(world));
        assertEquals(11, peon.tileX());
        assertEquals(88, peon.tileY());
        assertEquals(Direction.fromDelta(1, 1), peon.lastStepHeading());
        assertEquals(1, peon.pathLength(),
                "the east tail remains after the accepted southeast step");
        assertEquals(6, peon.battleNetCollisionCounter(),
                "the accepted saturated route keeps native collision six");
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
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
