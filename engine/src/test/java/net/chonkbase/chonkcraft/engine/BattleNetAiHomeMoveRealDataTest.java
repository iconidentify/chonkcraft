package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated AI regroup movement timing from retail BNE. */
class BattleNetAiHomeMoveRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an XHuman 8 grunt refills its final regroup route without PF_WAIT")
    void anXHuman8GruntRefillsItsFinalRegroupRouteWithoutPfWait() {
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

        Unit grunt = at(mission.world(), "unit-grunt", 13, 74);
        assertNotNull(grunt, "XHuman 8 has no startup grunt at 13,74");
        for (int cycle = 1; cycle <= 118; cycle++) {
            mission.tick();
        }
        assertEquals(Unit.Order.MOVE, grunt.order());
        assertEquals(6, grunt.tileX());
        assertEquals(72, grunt.tileY());

        mission.tick();
        assertEquals(Unit.Order.MOVE, grunt.order());
        assertEquals(5, grunt.tileX(),
                "native slot 1491 refills and steps southwest on fixture cycle 119");
        assertEquals(73, grunt.tileY());
        for (int cycle = 120; cycle <= 134; cycle++) {
            mission.tick();
            assertEquals(Unit.Order.MOVE, grunt.order(),
                    "native drains the final stride through fixture cycle " + cycle);
        }
        mission.tick();
        assertEquals(Unit.Order.STILL, grunt.order(),
                "native closes the regroup Move on fixture cycle 135");
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
