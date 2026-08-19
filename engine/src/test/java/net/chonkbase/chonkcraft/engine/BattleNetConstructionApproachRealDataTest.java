package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated construction routes from the sealed retail campaign corpus. */
class BattleNetConstructionApproachRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an Orc 10 farm builder routes around the castle body")
    void anOrc10FarmBuilderRoutesAroundTheCastleBody() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/orc/level10o",
                GameData.personIn(data.campaignMap("campaigns/orc/level10o")), 1);
        Assumptions.assumeTrue(mission != null, "Orc 10 is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Unit builder = at(mission.world(), 43, 6);
        assertNotNull(builder, "Orc 10 has no farm builder at 43,6");
        assertEquals(Unit.Order.BUILD, builder.order(),
                "the sealed unit 1578 / Java unit 22 pairing is not the active builder");

        // Sealed fixture retail-orc-10-idle: native action 28 lays route
        // 5,4,5,6,6,6,7 to fixed point 35,6 and logically takes its first SW
        // step on fixture cycle 2. The west cell begins the castle's blocked
        // corridor; treating every building body as transparent takes W.
        mission.tick();
        mission.tick();
        assertEquals(42, builder.tileX(), "native's first build-route step is southwest");
        assertEquals(7, builder.tileY(),
                "the builder walked through the castle body instead of around it");
    }

    private static Unit at(World world, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.tileX() == x
                    && unit.tileY() == y && unit.type() != null
                    && "unit-peasant".equals(unit.type().ident())) {
                return unit;
            }
        }
        return null;
    }
}
