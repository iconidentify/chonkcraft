package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** XHuman 10's axethrower retargets while its east stride settles. */
class XHuman10AxethrowerRetargetResidualRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("xhuman 10 axethrower spends the replacement east step at 55")
    void xhuman10AxethrowerRetargetsWithoutAFalseBlockedWait() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx10h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 10 is not in the pack");
        World world = mission.world();

        Unit axethrower = world.unitsSnapshot().stream()
                .filter(unit -> unit.player() == 2
                        && unit.type() != null
                        && "unit-axethrower".equals(unit.type().ident())
                        && unit.tileX() == 76 && unit.tileY() == 87)
                .findFirst().orElse(null);
        assertNotNull(axethrower,
                "XHuman 10 must contain native axethrower 1496 / Java 104");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }

        advanceToFixture(mission, world, 23);
        assertEquals(830, axethrower.battleNetSequenceOffset(),
                "fixture 23 must still be on the axethrower Move OP0");
        assertEquals(1, axethrower.battleNetAnimationTimer(),
                "fixture 23 exposes the timer-one occupied-step probe");

        mission.tick();
        assertEquals(830, axethrower.battleNetSequenceOffset(),
                "a refused OP0 step must restart at Move, not retain its advanced cursor");
        assertEquals(15, axethrower.battleNetAnimationTimer(),
                "native arms the complete refusal band on fixture 24");

        advanceToFixture(mission, world, 54);
        assertPosition(axethrower, 77, 87, -2, 0,
                "fixture 54 still owes the last two pixels of the first east stride");
        Unit oldTarget = axethrower.target();
        assertNotNull(oldTarget, "the running attack chase must retain its first target");

        mission.tick();
        assertPosition(axethrower, 78, 87, -32, 0,
                "fixture 55 must spend the replacement east heading instead of parking");
        assertNotNull(axethrower.target(),
                "the replacement chase must retain its newly selected target");
        assertNotSame(oldTarget, axethrower.target(),
                "fixture 55 changes quarry while committing the replacement route");
        assertEquals(0, axethrower.battleNetOrderDelay(),
                "a settled ranged residual does not pay the torn-live-route hold");

        advanceToFixture(mission, world, 70);
        assertPosition(axethrower, 78, 87, -2, 0,
                "fixture 70 must still be draining the second east stride");

        mission.tick();
        assertPosition(axethrower, 78, 87, 0, 0,
                "fixture 71 settles in firing range instead of entering a blocked wait");

        advanceToFixture(mission, world, 77);
        assertEquals(887, axethrower.battleNetSequenceOffset(),
                "the exhausted chase residual must reopen at Attack OP0");
        assertEquals(63, axethrower.battleNetAnimationTimer(),
                "the replacement quarry owns a fresh native ranged period");

        advanceToFixture(mission, world, 131);
        assertEquals(887, axethrower.battleNetSequenceOffset(),
                "the ranged hold must not launch a phantom axe at fixture 131");
        assertEquals(9, axethrower.battleNetAnimationTimer(),
                "native is still nine visits from the next OP0 at fixture 131");
    }

    private static void advanceToFixture(Mission mission, World world,
            int fixture) {
        while (world.cycle() - BNE_INITIALIZATION_TICKS < fixture) {
            mission.tick();
        }
    }

    private static void assertPosition(Unit unit, int x, int y,
            int offsetX, int offsetY, String message) {
        assertEquals(x, unit.tileX(), message + " (x)");
        assertEquals(y, unit.tileY(), message + " (y)");
        assertEquals(offsetX, unit.offsetX(), message + " (offset x)");
        assertEquals(offsetY, unit.offsetY(), message + " (offset y)");
    }
}
