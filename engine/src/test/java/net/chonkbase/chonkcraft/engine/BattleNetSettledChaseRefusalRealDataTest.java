package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** A settled melee chase refusal keeps native Move-program ownership. */
class BattleNetSettledChaseRefusalRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an xhuman 10 grunt advances after one settled refusal band")
    void anXhuman10GruntAdvancesAfterOneSettledRefusalBand() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h",
                GameData.personIn(
                        data.campaignMap("campaigns/human-exp/levelx10h")),
                1);
        Assumptions.assumeTrue(mission != null,
                "XHuman 10 is not in the pack");
        World world = mission.world();
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit grunt = atTile(world, "unit-grunt", 2, 76, 88);
        assertNotNull(grunt, "XHuman 10 has no focus grunt on 76,88");

        while (fixtureCycle(world) < 57) {
            mission.tick();
        }

        assertEquals(Unit.Order.ATTACK, grunt.order(),
                "the native chase remains an Attack order");
        assertEquals(79, grunt.tileX(),
                "native wakes from one refusal band and steps east at cycle 57");
        assertEquals(88, grunt.tileY(),
                "the native refusal wake must not detour off the attack row");
        assertEquals(2, grunt.pathLength(),
                "the wake keeps BNE's two-heading E,SE replacement tail");
        assertEquals(Direction.fromDelta(1, 0), grunt.peekHeading(),
                "the cached replacement route continues east");
        assertNotNull(grunt.target(),
                "the wake must name the replacement knight");
        assertEquals(83, grunt.target().tileX(),
                "the replacement target is BNE's knight on 83,89");
        assertEquals(89, grunt.target().tileY(),
                "the replacement target is BNE's knight on 83,89");
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - 2;
    }

    private static Unit atTile(
            World world, String ident, int player, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.type() != null
                    && ident.equals(unit.type().ident())
                    && unit.player() == player
                    && unit.tileX() == x && unit.tileY() == y) {
                return unit;
            }
        }
        return null;
    }
}
