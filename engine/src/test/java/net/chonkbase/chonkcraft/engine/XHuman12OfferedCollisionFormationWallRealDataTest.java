package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.animation.BattleNetSequence;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.map.Direction;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks offered-target collision ownership in a paid formation redraw. */
class XHuman12OfferedCollisionFormationWallRealDataTest {

    private static final int BNE_INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("an offered collision-one chaser remains solid to a paid tower redraw")
    void offeredCollisionOneChaserRemainsAFormationWall() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx12h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 12 is not in the pack");
        World world = mission.world();

        Unit router = unitById(world, 88);
        Unit offeredCollisionWall = unitById(world, 84);
        Unit offeredWithoutCollision = unitById(world, 80);
        Unit collisionWithoutOffer = unitById(world, 90);
        Unit tower = unitById(world, 115);
        assertNotNull(router, "XHuman 12 has no native-slot-1512 grunt");
        assertNotNull(offeredCollisionWall,
                "XHuman 12 has no native-slot-1516 formation grunt");
        assertNotNull(offeredWithoutCollision,
                "XHuman 12 has no native-slot-1520 control grunt");
        assertNotNull(collisionWithoutOffer,
                "XHuman 12 has no native-slot-1510 control grunt");
        assertNotNull(tower, "XHuman 12 has no guard-tower target");

        for (int tick = 0; tick < BNE_INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 257) {
            mission.tick();
        }

        // Native raw fixture 257 distinguishes all three moving tower
        // chasers. Slot 1516 alone has both unit+0x1d == 0x10 and the tower
        // pointer at COrder_Attack+0x54. Slot 1510 has collision one but a
        // null offer; slot 1520 has the offer but collision zero.
        assertEquals(tower, offeredCollisionWall.target());
        assertEquals(tower, offeredCollisionWall.offeredTarget());
        assertEquals(1, offeredCollisionWall.battleNetCollisionCounter());
        assertEquals(tower, offeredWithoutCollision.offeredTarget());
        assertEquals(0, offeredWithoutCollision.battleNetCollisionCounter());
        assertEquals(1, collisionWithoutOffer.battleNetCollisionCounter());
        assertNull(collisionWithoutOffer.offeredTarget());

        assertEquals(tower, router.target());
        assertEquals(12, router.pathLength());
        int[] nativeRoute = {
            Direction.fromDelta(1, -1),
            Direction.fromDelta(1, 1),
            Direction.fromDelta(1, 1),
            Direction.fromDelta(1, 1),
            Direction.fromDelta(0, 1),
            Direction.fromDelta(-1, 1),
            Direction.fromDelta(-1, 1),
            Direction.fromDelta(-1, 0),
            Direction.fromDelta(-1, 0),
            Direction.fromDelta(-1, 0),
            Direction.fromDelta(-1, -1),
            Direction.fromDelta(1, -1)
        };
        for (int depth = 0; depth < nativeRoute.length; depth++) {
            assertEquals(nativeRoute[depth], router.peekHeadingAtDepth(depth),
                    "native fixture-257 heading at depth " + depth);
        }

        while (fixtureCycle(world) < 323) {
            mission.tick();
        }
        assertEquals(42, router.tileX());
        assertEquals(40, router.tileY(),
                "the fourth southeast byte must refuse instead of moving south");
        assertEquals(9, router.pathLength(),
                "the refused southeast byte remains at the route head");
        assertEquals(Direction.fromDelta(1, 1), router.peekHeading());
        assertEquals(2, router.battleNetCollisionCounter());
        assertEquals(world.idle.battleNetSequenceStart(router,
                        BattleNetSequence.MOVE_ANIMATION),
                router.battleNetSequenceOffset());
        assertEquals(15, router.battleNetAnimationTimer());
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
