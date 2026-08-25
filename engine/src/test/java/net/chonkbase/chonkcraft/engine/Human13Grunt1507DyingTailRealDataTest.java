package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Locks the half-spent melee tail which settles after its quarry enters Die. */
class Human13Grunt1507DyingTailRealDataTest {

    private static final int INITIALIZATION_TICKS = 2;

    @Test
    void secondPaidStrideSettlesIntoStillWithoutConsumingTheSouthTail() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level13h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();
        Unit grunt = unitById(world, 93);
        Unit knight = unitById(world, 107);
        assertNotNull(grunt, "Human 13 must contain native grunt 1507 / Java 93");
        assertNotNull(knight, "Human 13 must contain native knight 1493 / Java 107");

        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        for (int fixture = 1; fixture <= 228; fixture++) {
            mission.tick();
        }

        assertSame(knight, grunt.target());
        assertEquals(true, knight.isDying());
        assertEquals(Unit.Order.ATTACK, grunt.order());
        assertEquals(123, grunt.tileX());
        assertEquals(28, grunt.tileY());
        assertEquals(-2, grunt.offsetX());
        assertEquals(-2, grunt.offsetY());
        assertEquals(2, grunt.pathLength(),
                "two bytes remain after the paid pair of south-east strides");
        assertEquals(4, grunt.battleNetPathInitialLength());
        assertEquals(2, grunt.battleNetPathStepsTaken());
        assertEquals(0, grunt.battleNetCollisionCounter());
        assertEquals(0, grunt.battleNetRefusals());
        assertEquals(true, grunt.battleNetAttackWrapDestArmPending());
        assertEquals(false, grunt.battleNetChaseReplanResidualHold(),
                "this is a direct paid tail, not a queued retarget residual");
        int reactRange = Math.max(
                grunt.type().reactRange(world.isPerson(grunt.player())),
                Math.max(1, grunt.type().maxAttackRange()));
        assertNull(world.targets.findBattleNetHostile(grunt, reactRange, null),
                "no live successor can inherit this dying quarry's paid tail");

        mission.tick();
        assertEquals(Unit.Order.STILL, grunt.order(),
                "the dying quarry releases Attack when the second residual settles");
        assertNull(grunt.target());
        assertEquals(123, grunt.tileX(),
                "the cached south heading must not commit after the quarry dies");
        assertEquals(28, grunt.tileY());
        assertEquals(0, grunt.offsetX());
        assertEquals(0, grunt.offsetY());
        assertEquals(2, grunt.pathLength(),
                "EndActionAttack leaves the two-byte PathFinderOutput tail intact");
    }

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }
}
