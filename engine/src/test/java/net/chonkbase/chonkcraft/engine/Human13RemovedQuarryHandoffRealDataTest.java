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

/** Authenticated weak-target ownership when Human 13's knight is removed. */
class Human13RemovedQuarryHandoffRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("human 13 releases a parked melee chase but drains a committed siege stride")
    void human13RemovedQuarryKeepsOnlyTheCommittedSiegeStride() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 0, 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();

        Unit catapult = unitById(world, 112);
        Unit ogre = unitById(world, 118);
        Unit knight = unitById(world, 107);
        assertNotNull(catapult, "Human 13 has no native-slot-1488 catapult");
        assertEquals(2, catapult.type().minAttackRange(),
                "the BNE catapult carries the positive siege dead zone");
        assertNotNull(ogre, "Human 13 has no native-slot-1482 ogre");
        assertNotNull(knight, "Human 13 has no native-slot-1493 knight");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 214) {
            mission.tick();
        }
        assertEquals(Unit.Order.ATTACK, catapult.order());
        assertEquals(Unit.Order.ATTACK, ogre.order());
        assertSame(knight, catapult.target());
        assertSame(knight, ogre.target());

        mission.tick();
        assertEquals(Unit.Order.ATTACK, catapult.order(),
                "the removed quarry does not cancel an already committed siege stride");
        assertEquals(Unit.Order.ATTACK, ogre.order(),
                "the parked melee chase releases on its following action visit");

        mission.tick();
        assertEquals(Unit.Order.ATTACK, catapult.order(),
                "native keeps the weak siege goal through the Move body");
        assertSame(knight, catapult.target());
        assertEquals(113, catapult.tileX());
        assertEquals(30, catapult.tileY());
        assertEquals(Unit.Order.STILL, ogre.order(),
                "the parked melee chase must end instead of stepping at the removed quarry");
        assertEquals(123, ogre.tileX());
        assertEquals(31, ogre.tileY());

        while (fixtureCycle(world) < 241) {
            mission.tick();
        }
        assertEquals(Unit.Order.ATTACK, catapult.order(),
                "the siege residual keeps Attack until its final pixels settle");
        assertSame(knight, catapult.target(),
                "the dying quarry pointer remains owned by the committed stride");

        mission.tick();
        assertEquals(242, fixtureCycle(world));
        assertNull(catapult.target(),
                "the settled siege stride releases its dying quarry");
        assertEquals(Unit.Order.STILL, catapult.order(),
                "settlement validates the corpse before reopening Attack");
        assertEquals(113, catapult.tileX());
        assertEquals(30, catapult.tileY());
        assertEquals(0, catapult.offsetX(),
                "fixture 242 reaches the exact native pixel anchor");
        assertEquals(0, catapult.offsetY());
        assertEquals(0, catapult.pathLength(),
                "native parks the committed route at index twenty");
        assertEquals(413, catapult.battleNetSequenceOffset(),
                "retail installs the catapult Still constructor");
        assertEquals(3, catapult.battleNetAnimationTimer());
    }

    private static int fixtureCycle(World world) {
        return (int) world.cycle() - BNE_INITIALIZATION_TICKS;
    }

    private static Unit unitById(World world, int id) {
        return world.unitsSnapshot().stream()
                .filter(unit -> unit.id() == id)
                .findFirst().orElse(null);
    }
}
