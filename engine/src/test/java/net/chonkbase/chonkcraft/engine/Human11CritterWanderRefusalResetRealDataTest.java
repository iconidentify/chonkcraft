package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated replacement-wander refusal generations from retail BNE. */
class Human11CritterWanderRefusalResetRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("Human 11's replacement critter wander starts a fresh refusal ladder")
    void replacementWanderRetiresTheCompletedTargetsRefusalGeneration() {
        Mission mission = mission();
        Unit critter = unitById(mission.world(), 102);
        assertNotNull(critter,
                "Human 11 has no Java twin for native critter slot 1498");

        tickThrough(mission, 257);
        assertEquals(Unit.Order.MOVE, critter.order());
        assertEquals(75, critter.orderTargetX());
        assertEquals(50, critter.orderTargetY());
        assertEquals(0, critter.battleNetRefusals(),
                "the first occupied wander begins without refusal debt");

        tickThrough(mission, 260);
        assertEquals(1, critter.battleNetRefusals());
        tickThrough(mission, 266);
        assertEquals(7, critter.battleNetRefusals(),
                "ordinary retries must remain in the same generation");
        tickThrough(mission, 267);
        assertEquals(8, critter.battleNetRefusals());
        assertEquals(14, critter.waitCycles(),
                "the eighth refusal opens the paid Move band");

        tickThrough(mission, 281);
        assertEquals(8, critter.battleNetRefusals());
        assertEquals(0, critter.waitCycles());
        assertEquals(75, critter.orderTargetX());
        assertEquals(50, critter.orderTargetY());

        tickThrough(mission, 282);
        assertEquals(Unit.Order.MOVE, critter.order());
        assertEquals(75, critter.orderTargetX());
        assertEquals(49, critter.orderTargetY(),
                "the idle marker replaces the completed east wander with northeast");
        assertEquals(0, critter.battleNetRefusals(),
                "retail clears unit+0x1d when the new wander is installed");
        assertEquals(0, critter.waitCycles());

        tickThrough(mission, 285);
        assertEquals(1, critter.battleNetRefusals(),
                "the replacement target owns its first refusal");
        assertEquals(0, critter.waitCycles());
        tickThrough(mission, 291);
        assertEquals(7, critter.battleNetRefusals());
        tickThrough(mission, 292);
        assertEquals(8, critter.battleNetRefusals());
        assertEquals(14, critter.waitCycles(),
                "only the replacement target's eighth refusal opens its band");

        tickThrough(mission, 300);
        assertEquals(Unit.Order.MOVE, critter.order(),
                "native remains in the replacement wander's paid band");
        assertEquals(75, critter.orderTargetX());
        assertEquals(49, critter.orderTargetY());

        tickThrough(mission, 307);
        assertEquals(Unit.Order.MOVE, critter.order());
        assertEquals(75, critter.tileX(),
                "the replacement wander commits northeast after the band");
        assertEquals(49, critter.tileY());
    }

    private static Mission mission() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level11h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 11 is not in the pack");
        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        return mission;
    }

    private static void tickThrough(Mission mission, int fixtureCycle) {
        while (mission.world().cycle() - BNE_INITIALIZATION_TICKS < fixtureCycle) {
            mission.tick();
        }
    }

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }
}
