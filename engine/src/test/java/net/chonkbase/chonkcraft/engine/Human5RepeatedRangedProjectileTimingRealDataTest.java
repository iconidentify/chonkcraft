package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated repeated-ranged constructor timing from retail Human 5. */
class Human5RepeatedRangedProjectileTimingRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("a repeated Human 5 axe waits for opcode ten before construction")
    void repeatedHuman5AxeWaitsForOpcodeTenBeforeConstruction() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human/level05h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Human 5 is not in the pack");
        World world = mission.world();

        Unit thrower = unitById(world, 66);
        Unit critter = unitById(world, 2);
        assertNotNull(thrower,
                "Human 5 has no Java unit 66 / native axethrower 1534");
        assertNotNull(critter,
                "Human 5 has no Java unit 2 / native critter 1598");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 534) {
            mission.tick();
        }

        assertEquals(900, thrower.battleNetSequenceOffset(),
                "presentation reaches the opcode-ten cursor three visits early");
        assertEquals(3, thrower.battleNetAnimationTimer(),
                "the authoritative sequence still owns its 3,2,1 wait");
        assertFalse(thrower.battleNetRangedResidualOpen(),
                "this is a settled repeated swing, not a ranged arrival");
        assertFalse(hasConstructedShot(world, thrower),
                "presentation alone must not spend the projectile constructor");
        assertEquals(0x1cd68942, world.battleNetRandomSeed(),
                "fixture 534 must leave the asynchronous stream at native's seed");

        while (fixtureCycle(world) < 536) {
            mission.tick();
        }
        assertEquals(Unit.Order.STILL, critter.order(),
                "the later critter must retain native's no-wander draw");
        assertEquals(0x93c347b9, world.battleNetRandomSeed(),
                "the critter marker must consume the same native ordinal");

        mission.tick();
        assertEquals(537, fixtureCycle(world));
        Missile shot = constructedShot(world, thrower);
        assertNotNull(shot,
                "opcode ten constructs the ninth repeated axe on fixture 537");
        assertEquals(537,
                world.savedProjectileStartCycle(shot)
                        - BNE_INITIALIZATION_TICKS,
                "constructor timing must retain the native 65-cycle period");
        assertEquals(5, shot.damage(),
                "fixture 537 owns native's five-damage constructor result");
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static boolean hasConstructedShot(World world, Unit source) {
        return constructedShot(world, source) != null;
    }

    private static Missile constructedShot(World world, Unit source) {
        for (Missile missile : world.missiles()) {
            if (missile.source() == source
                    && world.battleNetProjectileConstructed(missile)) {
                return missile;
            }
        }
        return null;
    }

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }
}
