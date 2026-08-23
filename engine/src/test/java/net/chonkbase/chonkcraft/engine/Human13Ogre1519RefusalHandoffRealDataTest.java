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
 * Human 13's eastern ogre resumes a blocked replacement chase once.
 *
 * <p>Native slot 1519 / Java unit 81 drains its old wise-man route on
 * fixture 148, selects knight 1493, and refuses the occupied south heading.
 * Retail pays Attack 643/3,2,1 once, exposes Move 586 for two visits, then
 * commits the open southeast detour on fixture 154. Re-arming the generic
 * blocked-chase constructor after that paid handoff inserted a second 3,2,1
 * pause and left a live attacker visibly frozen through fixture 155.</p>
 */
class Human13Ogre1519RefusalHandoffRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("human 13's eastern ogre pays one blocked-retarget constructor and resumes on fixture 154")
    void easternOgrePaysOneBlockedRetargetConstructorAndResumesOn154() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No Warcraft II installation configured (-Dwc2.install.dir). ");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();

        Unit ogre = unitAt(world, "unit-ogre", 123, 19);
        assertNotNull(ogre, "Human 13 has no eastern ogre on 123,19");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        Unit knight = null;
        for (int fixture = 1; fixture <= 154; fixture++) {
            mission.tick();
            if (fixture == 148) {
                knight = ogre.target();
                assertNotNull(knight, "fixture 148 must select the southern knight");
                assertEquals("unit-knight", knight.type().ident());
                assertEquals(121, knight.tileX());
                assertEquals(30, knight.tileY());
                assertEquals(122, ogre.tileX());
                assertEquals(28, ogre.tileY());
            } else if (fixture == 149) {
                assertEquals(643, ogre.battleNetSequenceOffset());
                assertEquals(3, ogre.battleNetAnimationTimer());
            } else if (fixture == 150) {
                assertEquals(643, ogre.battleNetSequenceOffset());
                assertEquals(2, ogre.battleNetAnimationTimer());
            } else if (fixture == 151) {
                assertEquals(643, ogre.battleNetSequenceOffset());
                assertEquals(1, ogre.battleNetAnimationTimer());
            } else if (fixture == 152 || fixture == 153) {
                assertEquals(586, ogre.battleNetSequenceOffset(),
                        "the paid constructor must hand ownership back to Move");
                assertEquals(122, ogre.tileX(),
                        "the empty cursor remains parked before its redraw");
                assertEquals(28, ogre.tileY(),
                        "the empty cursor remains parked before its redraw");
            }
        }

        assertSame(knight, ogre.target(),
                "the resumed detour must still chase the selected knight");
        assertEquals(123, ogre.tileX(),
                "native commits the southeast detour on fixture 154");
        assertEquals(29, ogre.tileY(),
                "native commits the southeast detour on fixture 154");
        assertEquals(1, ogre.pathLength(),
                "the south-west tail remains after the southeast step");
    }

    private static Unit unitAt(World world, String ident, int x, int y) {
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.isAlive() && unit.isOnMap() && unit.type() != null
                    && ident.equals(unit.type().ident())
                    && unit.tileX() == x && unit.tileY() == y) {
                return unit;
            }
        }
        return null;
    }
}
