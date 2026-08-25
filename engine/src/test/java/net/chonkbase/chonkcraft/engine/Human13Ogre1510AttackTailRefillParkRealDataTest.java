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

/** Authenticated Human 13 route-index-twenty park after a paid Attack tail. */
class Human13Ogre1510AttackTailRefillParkRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("human 13 ogre 1510 parks its exhausted paid Attack tail for one visit")
    void human13Ogre1510ParksItsExhaustedPaidAttackTailForOneVisit() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        Mission mission = data.loadMission("campaigns/human/level13h", 1, 1);
        Assumptions.assumeTrue(mission != null, "Human 13 is not in the pack");
        World world = mission.world();

        // Native ogre 1510 / Java 90 chases native knight 1493 / Java 107.
        // Its paid five-byte replacement has one blocked NW byte left when the
        // SW pixel residual settles. Retail parks route index twenty on fixture
        // 211 and only draws/commits the replacement SE byte on fixture 212.
        Unit ogre = unitById(world, 90);
        Unit knight = unitById(world, 107);
        assertNotNull(ogre, "Human 13 has no native-slot-1510 ogre");
        assertNotNull(knight, "Human 13 has no native-slot-1493 knight");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (world.cycle() - BNE_INITIALIZATION_TICKS < 210) {
            mission.tick();
        }
        assertSame(knight, ogre.target());
        assertEquals(123, ogre.tileX());
        assertEquals(30, ogre.tileY());
        assertEquals(2, ogre.offsetX());
        assertEquals(-2, ogre.offsetY());
        assertEquals(1, ogre.pathLength(),
                "fixture 210 still exposes the cached blocked NW tail");

        mission.tick();
        assertEquals(123, ogre.tileX(),
                "fixture 211 must park route index twenty before NewPath");
        assertEquals(30, ogre.tileY());
        assertEquals(0, ogre.offsetX());
        assertEquals(0, ogre.offsetY());
        assertEquals(0, ogre.pathLength());
        assertEquals(586, ogre.battleNetSequenceOffset());
        assertEquals(1, ogre.battleNetAnimationTimer());

        mission.tick();
        assertEquals(124, ogre.tileX(),
                "fixture 212 must consume the replacement SE heading");
        assertEquals(31, ogre.tileY());
        assertEquals(589, ogre.battleNetSequenceOffset());
        assertEquals(1, ogre.battleNetAnimationTimer());

        while (world.cycle() - BNE_INITIALIZATION_TICKS < 223) {
            mission.tick();
        }
        assertSame(knight, ogre.target());
        assertEquals(true, knight.isDying());
        assertEquals(Unit.Order.ATTACK, ogre.order());
        assertEquals(124, ogre.tileX());
        assertEquals(31, ogre.tileY());
        assertEquals(-2, ogre.offsetX());
        assertEquals(-2, ogre.offsetY());
        assertEquals(4, ogre.pathLength());

        mission.tick();
        assertEquals(Unit.Order.STILL, ogre.order(),
                "the dying quarry releases Attack as the residual settles");
        assertEquals(124, ogre.tileX(),
                "the cached SW tail must not commit after the quarry dies");
        assertEquals(31, ogre.tileY());
        assertEquals(0, ogre.offsetX());
        assertEquals(0, ogre.offsetY());
        assertEquals(4, ogre.pathLength(),
                "EndActionAttack leaves PathFinderOutput on the unit");
        assertEquals(581, ogre.battleNetSequenceOffset());
        assertEquals(3, ogre.battleNetAnimationTimer());
    }

    private static Unit unitById(World world, int id) {
        return world.units().stream()
                .filter(unit -> unit.id() == id)
                .findFirst()
                .orElse(null);
    }
}
