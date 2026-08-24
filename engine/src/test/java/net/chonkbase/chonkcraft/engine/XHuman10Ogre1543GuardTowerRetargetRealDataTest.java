package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * XHuman 10's ogre 1543 settles a paid chase residual next to guard tower
 * 1537 and replaces its old footman quarry on fixture 154.
 *
 * <p>The authenticated 2.02b executable exposes fresh Attack construction
 * {@code 643/3,2,1}, then parks on {@code 643/23}. It does not enter the
 * attack body or damage the tower on fixture 162.
 */
class XHuman10Ogre1543GuardTowerRetargetRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 10 ogre 1543 pays the melee approach hold after retargeting the guard tower")
    void xhuman10Ogre1543PaysTheMeleeApproachHoldAfterRetargetingTheGuardTower() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human-exp/levelx10h", 1, 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        Unit ogre = unitById(world, 57);
        Unit tower = unitById(world, 63);
        assertNotNull(ogre, "XHuman 10 has no native-slot-1543 ogre");
        assertNotNull(tower, "XHuman 10 has no native-slot-1537 guard tower");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 154) {
            mission.tick();
        }

        assertEquals(98, ogre.tileX());
        assertEquals(57, ogre.tileY());
        assertSame(tower, ogre.target(),
                "the residual-settle scan must select the adjacent guard tower");
        assertEquals(Unit.Order.ATTACK, ogre.order());
        assertEquals(643, ogre.battleNetSequenceOffset(),
                "native restarts ogre Attack construction on the retarget visit");
        assertEquals(3, ogre.battleNetAnimationTimer(),
                "native exposes construction timer three at fixture 154");

        mission.tick();
        assertEquals(2, ogre.battleNetAnimationTimer());
        mission.tick();
        assertEquals(1, ogre.battleNetAnimationTimer());
        mission.tick();
        assertEquals(643, ogre.battleNetSequenceOffset());
        assertEquals(23, ogre.battleNetAnimationTimer(),
                "the paid constructor must enter the native melee approach hold");

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 162) {
            mission.tick();
        }
        assertEquals(130, tower.hitPoints(),
                "ogre 1543 must not land Java's phantom fixture-162 hit");
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
