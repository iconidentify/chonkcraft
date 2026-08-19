package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated construction-foundation health from retail BNE. */
class BattleNetConstructionFoundationRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an XHuman 8 troll lumber mill starts at the forty-HP foundation cap")
    void anXHuman8TrollLumberMillStartsAtTheFortyHpFoundationCap() {
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

        for (int cycle = 1; cycle < 163; cycle++) {
            mission.tick();
        }
        assertNull(at(mission.world(), "unit-troll-lumber-mill", 47, 60),
                "the AI foundation must not appear before fixture cycle 163");

        mission.tick();
        Unit mill = at(mission.world(), "unit-troll-lumber-mill", 47, 60);
        assertNotNull(mill, "native slot 1472 starts its mill on fixture cycle 163");
        assertEquals(Unit.Order.UNDER_CONSTRUCTION, mill.order());
        assertEquals(40, mill.hitPoints(),
                "BNE caps a fresh foundation at forty HP, below this mill's ten percent");

        for (int cycle = 164; cycle < 174; cycle++) {
            mission.tick();
            assertEquals(40, mill.hitPoints(),
                    "the foundation must hold through fixture cycle " + cycle);
        }
        mission.tick();
        assertEquals(43, mill.hitPoints(),
                "native performs its first construction boost on fixture cycle 174");
        for (int cycle = 175; cycle < 186; cycle++) {
            mission.tick();
            assertEquals(43, mill.hitPoints(),
                    "the first boost must hold through fixture cycle " + cycle);
        }
        mission.tick();
        assertEquals(47, mill.hitPoints(),
                "native performs its second construction boost on fixture cycle 186");
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
