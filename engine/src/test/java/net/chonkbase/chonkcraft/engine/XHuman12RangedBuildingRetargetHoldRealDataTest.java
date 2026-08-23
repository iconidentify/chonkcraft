package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated ranged approach hold after an in-range building retarget. */
class XHuman12RangedBuildingRetargetHoldRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a routeless ranged retarget onto a building holds attack start")
    void aRoutelessRangedRetargetOntoABuildingHoldsAttackStart() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        // Java 79 is native slot 1521: the axe that finishes its approach at
        // 37,37 and retargets the guard tower on 39,41. Authenticated BNE
        // stays on Attack@887 with timer 63 from fixture 42 onward; it does
        // not construct the projectile Java used to launch at fixture 52.
        Unit axe = unitById(world, 79);
        assertNotNull(axe, "XHuman 12 has no native-slot-1521 axethrower");
        assertEquals("unit-axethrower", axe.type().ident());

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        Unit towerAt42 = null;
        while (fixtureCycle(world) < 52) {
            mission.tick();
            if (fixtureCycle(world) == 42) {
                towerAt42 = axe.target();
                assertNotNull(towerAt42,
                        "the settled axe must retain its acquired quarry");
                assertEquals("unit-human-guard-tower",
                        towerAt42.type().ident());
                assertEquals(887, axe.battleNetSequenceOffset(),
                        "native keeps the ranged attack cursor on OP0");
                assertEquals(63, axe.battleNetAnimationTimer(),
                        "the post-approach building retarget owns the native hold");
            }
        }

        assertSame(towerAt42, axe.target(),
                "the hold remains committed to the acquired tower");
        assertEquals(0, missilesFrom(world, axe),
                "the OP0 hold must not construct the fixture-52 phantom axe");
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static int missilesFrom(World world, Unit source) {
        int count = 0;
        for (Missile missile : world.missiles()) {
            if (missile.source() == source) {
                count++;
            }
        }
        return count;
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
