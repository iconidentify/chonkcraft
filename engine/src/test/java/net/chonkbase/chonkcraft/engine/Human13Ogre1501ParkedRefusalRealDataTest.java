package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Locks a paid-tail hard refusal to its freshly written native wall face. */
class Human13Ogre1501ParkedRefusalRealDataTest {

    private static final int INITIALIZATION_TICKS = 2;

    @Test
    void paidTailRefillKeepsItsFreshEastHeadAtFixture178() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level13h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();
        Unit ogre = unitById(world, 99);
        Unit knight = unitById(world, 107);
        assertNotNull(ogre, "Human 13 must contain native ogre 1501 / Java 99");
        assertNotNull(knight, "Human 13 must contain native knight 1493 / Java 107");

        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int fixture = 1; fixture <= 178; fixture++) {
            mission.tick();
        }

        assertSame(knight, ogre.target(),
                "the paid-tail refill remains a chase of knight 1493");
        assertEquals(123, ogre.tileX(),
                "native consumes the fresh east route head on fixture 178");
        assertEquals(27, ogre.tileY(),
                "the parked southeast refusal must not overwrite east with south");
        assertEquals(Direction.fromDelta(1, 0), ogre.lastStepHeading(),
                "the fresh native wall face starts east");
        assertEquals(4, ogre.pathLength(),
                "east consumes one byte from the native five-heading refill");
    }

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }
}
