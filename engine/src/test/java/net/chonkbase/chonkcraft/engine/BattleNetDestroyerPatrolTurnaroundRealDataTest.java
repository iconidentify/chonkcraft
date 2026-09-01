package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated off-lattice destroyer Patrol turnaround from retail BNE. */
class BattleNetDestroyerPatrolTurnaroundRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an XHuman 5 destroyer rewrites its platform endpoint before the return leg")
    void anXHuman5DestroyerRewritesItsPlatformEndpointBeforeTheReturnLeg() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx05h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Unit destroyer = at(mission.world(),
                "unit-orc-destroyer", 100, 88);
        assertNotNull(destroyer,
                "XHuman 5 has no startup destroyer at 100,88");
        for (int cycle = 1; cycle <= 256; cycle++) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, destroyer.order());
        assertEquals(101, destroyer.orderTargetX(),
                "native publishes the oil platform's west column");
        assertEquals(87, destroyer.orderTargetY(),
                "native rewrites platform top-left 101,85 to its south edge");
        assertEquals(102, destroyer.patrolX());
        assertEquals(98, destroyer.patrolY());

        for (int cycle = 257; cycle <= 322; cycle++) {
            mission.tick();
        }
        assertEquals(102, destroyer.tileX());
        assertEquals(94, destroyer.tileY());
        mission.tick();
        assertEquals(100, destroyer.tileX(),
                "native consumes northwest as the third cached heading");
        assertEquals(92, destroyer.tileY());
        assertEquals(Unit.Order.PATROL, destroyer.order());

        for (int cycle = 324; cycle <= 418; cycle++) {
            mission.tick();
        }
        assertEquals(100, destroyer.tileX());
        assertEquals(88, destroyer.tileY());
        assertEquals(1, destroyer.pathLength(),
                "one north overshoot remains beside the odd platform edge");
        mission.tick();
        assertEquals(100, destroyer.tileX(),
                "native parks the final north byte instead of overshooting");
        assertEquals(88, destroyer.tileY());
        assertEquals(102, destroyer.orderTargetX(),
                "fixture 419 turns the Patrol back toward its near endpoint");
        assertEquals(98, destroyer.orderTargetY());
        assertEquals(0, destroyer.pathLength());
        assertEquals(3129, destroyer.battleNetSequenceOffset());
        assertEquals(3, destroyer.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("an XHuman 8 destroyer turns around at its off-lattice far endpoint")
    void anXHuman8DestroyerTurnsAroundAtItsOffLatticeFarEndpoint() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx08h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, map + " is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Unit destroyer = at(mission.world(), "unit-orc-destroyer", 34, 82);
        assertNotNull(destroyer, "XHuman 8 has no startup destroyer at 34,82");
        for (int cycle = 1; cycle <= 107; cycle++) {
            mission.tick();
        }
        assertEquals(40, destroyer.tileX());
        assertEquals(84, destroyer.tileY());
        assertEquals(Unit.Order.PATROL, destroyer.order());

        mission.tick();
        assertEquals(34, destroyer.orderTargetX(),
                "native reverses toward the near endpoint on fixture cycle 108");
        assertEquals(82, destroyer.orderTargetY());
        mission.tick();
        mission.tick();
        assertEquals(40, destroyer.tileX(),
                "native holds for its three-call turnaround constructor");
        mission.tick();
        assertEquals(Unit.Order.PATROL, destroyer.order());
        assertEquals(38, destroyer.tileX(),
                "native slot 1480 takes its doubled west stride on cycle 111");
        assertEquals(84, destroyer.tileY());
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
