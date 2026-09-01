package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The gold queue outside Orc 8's mine, against the sealed native capture.
 *
 * <p>Peasant 1504 (Java 96) leaves the mine at fixture 205 and stalls one
 * square north of the exit column while ally 1501 (Java 99) occupies the only
 * south square. Retail answers every blocked retry with a refusal generation:
 * seven quiet Move-start visits, then the fifteen-count cooperative band
 * armed at fixture 240, served to expiry even though the blocker steps away
 * at 253 -- the laden hauler takes the freed square at fixture 255, not the
 * moment it opens. This implementation used to hold nothing at all and walked
 * in the same cycle the blocker left, two cycles ahead of retail.
 */
class Orc08MineExitRefuseHoldRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("the laden hauler serves its refusal hold and takes the freed square on expiry")
    void aLadenReturnerServesItsCooperativeHoldBeforeTakingTheFreedMineExitSquare() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc/level08o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Orc 8 is not in the pack");
        World world = mission.world();
        Unit hauler = unitById(world, 96);
        Unit blocker = unitById(world, 99);
        assertNotNull(hauler,
                "Orc 8 has no Java unit 96 / native peasant 1504");
        assertNotNull(blocker,
                "Orc 8 has no Java unit 99 / native peasant 1501");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 252) {
            mission.tick();
        }
        assertEquals(123, hauler.tileX(),
                "the hauler waits north of the exit column at fixture 252");
        assertEquals(86, hauler.tileY(),
                "the hauler waits north of the exit column at fixture 252");
        assertEquals(123, blocker.tileX(),
                "the ally still holds the only south square at fixture 252");
        assertEquals(87, blocker.tileY(),
                "the ally still holds the only south square at fixture 252");
        assertEquals(false, hauler.battleNetResourceHitRestoreIdle(),
                "an ordinary mine queue must not inherit resource-hit provenance");

        mission.tick();
        assertEquals(253, fixtureCycle(world));
        assertEquals(122, blocker.tileX(),
                "the blocker steps aside on fixture 253 as retail does");
        assertEquals(88, blocker.tileY(),
                "the blocker steps aside on fixture 253 as retail does");
        assertEquals(123, hauler.tileX(),
                "the hauler must not take the square the moment it opens");
        assertEquals(86, hauler.tileY(),
                "the hauler must not take the square the moment it opens");

        mission.tick();
        assertEquals(254, fixtureCycle(world));
        assertEquals(123, hauler.tileX(),
                "retail serves the cooperative band out; the hauler holds through fixture 254");
        assertEquals(86, hauler.tileY(),
                "retail serves the cooperative band out; the hauler holds through fixture 254");

        mission.tick();
        assertEquals(255, fixtureCycle(world));
        assertEquals(123, hauler.tileX(),
                "the hauler takes the exit square on the expiry visit, fixture 255");
        assertEquals(87, hauler.tileY(),
                "the hauler takes the exit square on the expiry visit, fixture 255");
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
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
