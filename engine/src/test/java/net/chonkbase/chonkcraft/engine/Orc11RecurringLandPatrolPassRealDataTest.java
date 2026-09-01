package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Authenticated recurring land-assault order timing from retail BNE. */
class Orc11RecurringLandPatrolPassRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("orc 11 refreshes its moving land-assault patrol on fixture 99")
    void orc11RefreshesItsMovingLandAssaultPatrolOnFixture99() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc/level11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Orc 11 is not in the pack");
        World world = mission.world();

        // Fixture pairing: native 1558/1559 are Java 42/41. Retail gives all
        // four behavior-two land-assault members a replacement Patrol on its
        // fifty-cycle beat. The replacement is next_order at fixture 99,
        // promotes through Still construction at 101, and first-steps at 104.
        Unit knight = unitById(world, 42);
        Unit archer = unitById(world, 41);
        assertNotNull(knight, "Orc 11 has no Java unit 42 / native knight 1558");
        assertNotNull(archer, "Orc 11 has no Java unit 41 / native archer 1559");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 104) {
            mission.tick();
            int fixture = fixtureCycle(world);
            if (fixture == 99) {
                assertTrue(knight.hasBattleNetPendingPatrol(),
                        "the fifty-cycle pass queues the knight's replacement Patrol");
                assertTrue(archer.hasBattleNetPendingPatrol(),
                        "the same pass queues the archer's replacement Patrol");
            }
            if (fixture == 101) {
                assertEquals(Unit.Order.PATROL, knight.order());
                assertEquals(Unit.Order.PATROL, archer.order());
                assertEquals(114, knight.tileX(),
                        "Still construction holds native knight 1558 on fixture 101");
                assertEquals(40, knight.tileY());
                assertEquals(118, archer.tileX(),
                        "Still construction holds native archer 1559 on fixture 101");
                assertEquals(39, archer.tileY());
            }
        }

        assertEquals(115, knight.tileX(),
                "the refreshed knight Patrol first-steps on fixture 104");
        assertEquals(40, knight.tileY());
        assertEquals(119, archer.tileX(),
                "the refreshed archer Patrol first-steps on fixture 104");
        assertEquals(39, archer.tileY());

        while (fixtureCycle(world) < 140) {
            mission.tick();
        }
        assertEquals(118, knight.tileX(),
                "without another fifty-cycle pass, native knight 1558 keeps "
                        + "the fourth east heading through fixture 140");
        assertEquals(40, knight.tileY(),
                "route shape alone must not redirect the knight north-east");
    }

    @Test
    @DisplayName("orc 11's late patrol replacement waits behind its moving ally")
    void orc11sLatePatrolReplacementWaitsBehindItsMovingAlly() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc/level11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Orc 11 is not in the pack");
        World world = mission.world();

        Unit archer = unitById(world, 37);
        Unit knight = unitById(world, 42);
        assertNotNull(archer, "Orc 11 has no Java 37 / native 1563 archer");
        assertNotNull(knight, "Orc 11 has no Java 42 / native 1558 knight");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 110) {
            mission.tick();
        }
        assertEquals(0, knight.battleNetCollisionCounter(),
                "the moving lead knight is transparent to native route drawing");
        assertEquals(0, knight.battleNetRefusals(),
                "the replacement GiveOrder clears the old refusal nibble");
        assertEquals(114, archer.tileX());
        assertEquals(41, archer.tileY());
        assertNull(archer.target(),
                "the replacement Patrol releases the old action target");
        assertTrue(!archer.chasing(),
                "the replacement Patrol must not retain chase ownership");

        mission.tick();
        assertEquals(114, archer.tileX(),
                "the route points NE through the ally and refuses instead of rerouting east");
        assertEquals(41, archer.tileY());
        assertEquals(1, archer.battleNetCollisionCounter());
        assertEquals(1, archer.battleNetRefusals());
        assertEquals(20, archer.pathLength(),
                "native preserves the full patrol ray behind the refusal");
        assertEquals(15, archer.battleNetAnimationTimer());
    }

    @Test
    @DisplayName("orc 11's queued archer attack settles on retail Move cadence")
    void orc11QueuedArcherAttackSettlesOnRetailMoveCadence() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc/level11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Orc 11 is not in the pack");
        World world = mission.world();

        Unit archer = unitById(world, 41);
        assertNotNull(archer,
                "Orc 11 has no Java 41 / native slot 1559 archer");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 305) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, archer.order());
        assertEquals(1977, archer.battleNetSequenceOffset(),
                "the recurring replacement reconstructs the retail Still head");
        assertEquals(3, archer.battleNetAnimationTimer());

        while (fixtureCycle(world) < 313) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, archer.order());
        assertEquals(3831, archer.pixelX());
        assertEquals(983, archer.pixelY(),
                "the engines agree immediately before the cadence split");

        mission.tick();
        assertEquals(314, fixtureCycle(world));
        assertEquals(3829, archer.pixelX(),
                "script.bin moves two pixels on this Patrol body visit");
        assertEquals(981, archer.pixelY());
        assertEquals(Unit.Order.PATROL, archer.order());

        while (fixtureCycle(world) < 324) {
            mission.tick();
        }
        assertEquals(3808, archer.pixelX());
        assertEquals(960, archer.pixelY());
        assertEquals(Unit.Order.ATTACK, archer.order(),
                "the queued direct Attack promotes as the final pixels settle");
        assertNotNull(archer.target());

        while (fixtureCycle(world) < 327) {
            mission.tick();
        }
        assertEquals(119, archer.tileX());
        assertEquals(29, archer.tileY(),
                "Attack's timer-one handoff spends its first chase byte");
        assertEquals(3808, archer.pixelX());
        assertEquals(960, archer.pixelY(),
                "the logical north step opens cold on its commit visit");
        assertEquals(Unit.Order.ATTACK, archer.order());

        while (fixtureCycle(world) < 343) {
            mission.tick();
        }
        assertEquals(118, archer.tileX());
        assertEquals(28, archer.tileY(),
                "the transferred Patrol byte has no one-probe route park");
        assertEquals(3808, archer.pixelX());
        assertEquals(928, archer.pixelY());

        while (fixtureCycle(world) < 359) {
            mission.tick();
        }
        assertEquals(3776, archer.pixelX());
        assertEquals(896, archer.pixelY(),
                "direct Attack retains the Patrol-owned Move cadence");
        assertEquals(2039, archer.battleNetSequenceOffset());
        assertEquals(3, archer.battleNetAnimationTimer());

        mission.tick();
        assertEquals(360, fixtureCycle(world));
        assertEquals(2039, archer.battleNetSequenceOffset());
        assertEquals(2, archer.battleNetAnimationTimer(),
                "the direct Attack constructor counts down immediately");

        mission.tick();
        assertEquals(361, fixtureCycle(world));
        assertEquals(2039, archer.battleNetSequenceOffset());
        assertEquals(1, archer.battleNetAnimationTimer());

        mission.tick();
        assertEquals(362, fixtureCycle(world));
        assertEquals(117, archer.tileX());
        assertEquals(27, archer.tileY(),
                "Attack consumes the next chase byte after its 3,2,1 body");
    }

    @Test
    @DisplayName("orc 11's unqueued archer patrol keeps the retail Move body")
    void orc11UnqueuedArcherPatrolKeepsRetailMoveBody() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/orc/level11o";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "Orc 11 is not in the pack");
        World world = mission.world();

        Unit archer = unitById(world, 40);
        assertNotNull(archer,
                "Orc 11 has no Java 40 / native slot 1560 archer");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 307) {
            mission.tick();
        }
        assertEquals(Unit.Order.PATROL, archer.order());
        assertEquals(2, archer.battleNetAiBehavior());
        assertEquals(1977, archer.battleNetSequenceOffset());
        assertEquals(3, archer.battleNetAnimationTimer());

        while (fixtureCycle(world) < 310) {
            mission.tick();
        }
        assertEquals(120, archer.tileX());
        assertEquals(31, archer.tileY());
        assertEquals(1024, archer.pixelY(),
                "the logical north step opens cold on its commit visit");
        assertTrue(archer.battleNetMovePaceOffset() >= 0,
                "the Patrol stride arms the native Move pace at cold commit");
        assertNull(archer.pendingAttack(),
                "this Patrol OP0 has not banked an attack yet");

        mission.tick();
        assertEquals(311, fixtureCycle(world));
        assertEquals(1021, archer.pixelY(),
                "script.bin spends three northbound pixels after cold commit");

        while (fixtureCycle(world) < 326) {
            mission.tick();
        }
        assertEquals(120, archer.tileX());
        assertEquals(30, archer.tileY());
        assertEquals(992, archer.pixelY());
        assertEquals(Unit.Order.PATROL, archer.order(),
                "the Move-body OP0 banks Attack behind one more Patrol stride");
        assertNotNull(archer.pendingAttack());

        while (fixtureCycle(world) < 342) {
            mission.tick();
        }
        assertEquals(3840, archer.pixelX());
        assertEquals(960, archer.pixelY());
        assertEquals(Unit.Order.ATTACK, archer.order(),
                "the queued Attack pops when the second north stride settles");
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
