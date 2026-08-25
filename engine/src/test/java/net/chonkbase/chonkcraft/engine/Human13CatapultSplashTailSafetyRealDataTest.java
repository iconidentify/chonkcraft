package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks the crowded splash refusal shared by mobile and stationary siege. */
class Human13CatapultSplashTailSafetyRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("human 13's mobile catapults refuse the crowded tail target")
    void mobileCatapultsTakeStillPulseBeforeReacquiringCrowdedKnight() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();
        Unit northCatapult = unitById(world, 112);
        Unit southCatapult = unitById(world, 121);
        Unit crowdedKnight = unitById(world, 107);
        Unit frontierCritter = unitById(world, 125);
        assertNotNull(northCatapult, "Human 13 has no native-slot-1488 catapult");
        assertNotNull(southCatapult, "Human 13 has no native-slot-1479 catapult");
        assertNotNull(crowdedKnight, "Human 13 has no western target knight");
        assertNotNull(frontierCritter,
                "Human 13 has no native-slot-1475 frontier critter");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 203) {
            mission.tick();
        }
        assertCatapult(northCatapult, Unit.Order.ATTACK, 540, 1);
        assertCatapult(southCatapult, Unit.Order.ATTACK, 540, 1);

        for (int timer = 3; timer >= 1; timer--) {
            mission.tick();
            assertCatapult(northCatapult, Unit.Order.STILL, 413, timer);
            assertCatapult(southCatapult, Unit.Order.STILL, 413, timer);
            assertNull(northCatapult.target(),
                    "the refused splash target must not survive the Still handoff");
            assertNull(southCatapult.target(),
                    "the refused splash target must not survive the Still handoff");
        }

        mission.tick();
        assertEquals(207, fixtureCycle(world));
        assertSame(crowdedKnight, northCatapult.target());
        assertSame(crowdedKnight, southCatapult.target());
        assertCatapult(northCatapult, Unit.Order.ATTACK, 503, 3);
        assertCatapult(southCatapult, Unit.Order.ATTACK, 503, 3);
        assertEquals(112, northCatapult.tileX());
        assertEquals(31, northCatapult.tileY());
        assertEquals(118, southCatapult.tileX());
        assertEquals(36, southCatapult.tileY());

        mission.tick();
        assertCatapult(southCatapult, Unit.Order.ATTACK, 503, 2);
        mission.tick();
        assertCatapult(southCatapult, Unit.Order.ATTACK, 503, 1);
        mission.tick();
        assertEquals(210, fixtureCycle(world));
        assertCatapult(southCatapult, Unit.Order.STILL, 413, 3);
        assertNull(southCatapult.target(),
                "the live crowded replacement is refused again at its OP0");

        while (fixtureCycle(world) < 216) {
            mission.tick();
        }
        assertCatapult(southCatapult, Unit.Order.STILL, 413, 3);
        assertEquals(0x7dfce682, world.battleNetRandomSeed(),
                "the expired siege Attack must not steal the next land-idle draw");

        mission.tick();
        assertEquals(217, fixtureCycle(world));
        assertEquals(Unit.Order.MOVE, frontierCritter.order(),
                "native's next shared draw sends the frontier critter west");
        assertEquals(108, frontierCritter.orderTargetX());
        assertEquals(42, frontierCritter.orderTargetY());
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

    private static void assertCatapult(Unit catapult, Unit.Order order,
            int sequence, int timer) {
        assertEquals(order, catapult.order());
        assertEquals(sequence, catapult.battleNetSequenceOffset());
        assertEquals(timer, catapult.battleNetAnimationTimer());
    }
}
