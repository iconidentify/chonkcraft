package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Native XHuman 2 route refill at the opening ogres' first route boundary. */
class Xhuman02OgreRouteRefillRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 2 ogres refill their move route on cycle 93")
    void xhuman2OgresRefillTheirMoveRouteOnCycle93() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx02h", 0);
        Assumptions.assumeTrue(mission != null, "XHuman 2 is not in the pack");
        World world = mission.world();

        // Stable map-load identities paired to native slots 1549 and 1547.
        Unit eastOgre = unitById(world, 51);
        Unit northOgre = unitById(world, 53);
        assertNotNull(eastOgre, "XHuman 2 has no opening ogre 51");
        assertNotNull(northOgre, "XHuman 2 has no opening ogre 53");
        assertEquals("unit-ogre", eastOgre.type().ident());
        assertEquals("unit-ogre", northOgre.type().ident());

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 93) {
            mission.tick();
        }

        assertEquals(Unit.Order.MOVE, eastOgre.order(),
                "native slot 1549 refills Move instead of acquiring a nearby target");
        assertEquals(63, eastOgre.tileX());
        assertEquals(61, eastOgre.tileY());
        assertEquals(Unit.Order.MOVE, northOgre.order(),
                "native slot 1547 refills Move instead of acquiring a nearby target");
        assertEquals(64, northOgre.tileX());
        assertEquals(62, northOgre.tileY());
    }

    @Test
    @DisplayName("xhuman 2's east ogre keeps the cached west chase tail at cycle 232")
    void xhuman2EastOgreKeepsCachedWestChaseTailAtCycle232() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx02h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 2 is not in the pack");
        World world = mission.world();
        Unit ogre = unitById(world, 51);
        assertNotNull(ogre, "XHuman 2 has no Java twin for native ogre 1549");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 231) {
            mission.tick();
        }
        assertEquals(Unit.Order.ATTACK, ogre.order());
        assertEquals(66, ogre.tileX());
        assertEquals(59, ogre.tileY());

        mission.tick();
        assertEquals(Unit.Order.ATTACK, ogre.order());
        assertEquals(65, ogre.tileX());
        assertEquals(59, ogre.tileY(),
                "native's fourth cached chase byte is west, not south-west");
    }

    @Test
    @DisplayName("xhuman 2's east ogre takes the fresh southwest route head at cycle 352")
    void xhuman2EastOgreTakesFreshSouthwestRouteHeadAtCycle352() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx02h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 2 is not in the pack");
        World world = mission.world();
        Unit ogre = unitById(world, 51);
        assertNotNull(ogre, "XHuman 2 has no Java twin for native ogre 1549");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 351) {
            mission.tick();
        }
        assertEquals(Unit.Order.ATTACK, ogre.order());
        assertEquals(60, ogre.tileX());
        assertEquals(66, ogre.tileY());
        assertEquals(0, ogre.pathLength(),
                "the nine-byte offered-target route is exhausted before refill");

        mission.tick();
        assertEquals(Unit.Order.ATTACK, ogre.order());
        assertEquals(59, ogre.tileX());
        assertEquals(67, ogre.tileY(),
                "native consumes the fresh southwest head, not the old west face");
        assertEquals(2, ogre.pathLength(),
                "the fresh SW,SW,S route retains its final two headings");
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
