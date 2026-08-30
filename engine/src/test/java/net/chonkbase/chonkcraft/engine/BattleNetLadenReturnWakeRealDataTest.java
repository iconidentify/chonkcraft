package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Laden land returns consume a free cached head on Move timer one. */
class BattleNetLadenReturnWakeRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("XHuman 7 consumes the free cached return head on timer one")
    void xhuman7ConsumesTheFreeCachedReturnHeadOnTimerOne() {
        Mission mission = mission("campaigns/human-exp/levelx07h");
        World world = mission.world();
        Unit peon = unitById(world, 149);
        assertNotNull(peon, "XHuman 7 has no native-slot-1451 return peon");

        advanceToFixture(mission, world, 285);
        assertPosition(peon, 110, 106,
                "Move timer one still retains the cached northeast head");
        assertEquals(1, peon.battleNetCollisionCounter());
        assertEquals(2, peon.pathLength());

        mission.tick();
        assertPosition(peon, 111, 105,
                "timer one consumes the now-free northeast head");
        assertEquals(1, peon.battleNetCollisionCounter(),
                "a successful cached retry does not add a refusal");
    }

    @Test
    @DisplayName("Orc 5 independently consumes the free cached return head")
    void orc5IndependentlyConsumesTheFreeCachedReturnHead() {
        Mission mission = mission("campaigns/orc/level05o");
        World world = mission.world();
        Unit peasant = unitById(world, 71);
        assertNotNull(peasant, "Orc 5 has no native-slot-1529 return peasant");

        advanceToFixture(mission, world, 288);
        assertPosition(peasant, 32, 101,
                "Move timer one still retains the cached southeast head");
        assertEquals(1, peasant.battleNetCollisionCounter());
        assertEquals(2, peasant.pathLength());

        mission.tick();
        assertPosition(peasant, 33, 102,
                "timer one consumes the now-free southeast head");
        assertEquals(1, peasant.battleNetCollisionCounter(),
                "a successful cached retry does not add a refusal");
    }

    @Test
    @DisplayName("XOrc 6's uninterrupted return does not move one cycle early")
    void xorc6UninterruptedReturnDoesNotMoveOneCycleEarly() {
        Mission mission = mission("campaigns/orc-exp/levelx06o");
        World world = mission.world();
        Unit peasant = unitById(world, 85);
        assertNotNull(peasant, "XOrc 6 has no native-slot-1515 return peasant");

        advanceToFixture(mission, world, 273);
        assertPosition(peasant, 7, 85,
                "the first diagonal residual still owns fixture 273");

        mission.tick();
        assertFalse(peasant.tileX() == 7 && peasant.tileY() == 85,
                "the uninterrupted cached route advances on fixture 274");
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

    private static void advanceToFixture(Mission mission, World world,
            int fixture) {
        while (world.cycle() - BNE_INITIALIZATION_TICKS < fixture) {
            mission.tick();
        }
    }

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }

    private static void assertPosition(Unit unit, int x, int y,
            String message) {
        assertEquals(x, unit.tileX(), message + " (x)");
        assertEquals(y, unit.tileY(), message + " (y)");
    }
}
