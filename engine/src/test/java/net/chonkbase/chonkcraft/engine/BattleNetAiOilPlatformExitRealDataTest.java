package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated AI oil-platform exit placement from retail BNE. */
class BattleNetAiOilPlatformExitRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an XHuman 8 tanker leaves its platform on BNE's even anchor grid")
    void anXHuman8TankerLeavesItsPlatformOnTheEvenAnchorGrid() {
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

        for (int cycle = 1; cycle < 258; cycle++) {
            mission.tick();
        }
        mission.tick();

        Unit tanker = at(mission.world(), "unit-orc-oil-tanker", 66, 58);
        assertNotNull(tanker,
                "native slot 1538 surfaces south of its platform on fixture cycle 258");
        assertEquals(Unit.Order.STILL, tanker.order(),
                "the platform exit exposes the naval ready boundary");
        assertTrue(tanker.battleNetDoubleStep(),
                "the contained tanker must retain native unit+0x1c bit 1");
        assertEquals(0, (tanker.tileX() | tanker.tileY()) & 1,
                "native retains its doubled-grid bit and rejects odd dropout anchors");

        for (int cycle = 259; cycle <= 282; cycle++) {
            mission.tick();
            assertEquals(Unit.Order.STILL, tanker.order(),
                    "the naval ready window must hold through fixture cycle " + cycle);
            assertEquals(66, tanker.tileX());
            assertEquals(58, tanker.tileY());
        }
        mission.tick();
        assertEquals(Unit.Order.RETURN_GOODS, tanker.order(),
                "native promotes queued action 24 on fixture cycle 283");
        mission.tick();
        mission.tick();
        mission.tick();
        assertEquals(64, tanker.tileX(),
                "native takes its first doubled west stride on fixture cycle 286");
        assertEquals(58, tanker.tileY());

        for (int cycle = 287; cycle < 318; cycle++) {
            mission.tick();
            assertEquals(64, tanker.tileX(),
                    "the west stride must drain through fixture cycle " + cycle);
            assertEquals(58, tanker.tileY());
        }
        mission.tick();
        assertEquals(62, tanker.tileX(),
                "native takes the refinery wall route's northwest stride on cycle 318");
        assertEquals(56, tanker.tileY(),
                "the marked refinery skirt must beat the straight blocked-goal prefix");
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
