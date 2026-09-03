package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.campaign.Mission;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Locks the caster-specific idle acquisition exposed at XHuman 2 cycle 512. */
class XHuman02DeathKnightIdleAttackRealDataTest {

    private static final int INITIALIZATION_TICKS = 2;

    @Test
    @DisplayName("XHuman 2's death knight attacks the passive barracks on cycle 512")
    void xhuman2DeathKnightAttacksThePassiveBarracksOnCycle512() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String map = "campaigns/human-exp/levelx02h";
        Mission mission = data.loadMission(map,
                GameData.personIn(data.campaignMap(map)), 1);
        Assumptions.assumeTrue(mission != null, "XHuman 2 is not in the pack");
        World world = mission.world();

        // Stable map-load identity: Java 43 pairs with native slot 1557.
        Unit deathKnight = unitById(world, 43);
        assertNotNull(deathKnight, "XHuman 2 has no native-slot-1557 twin");
        assertEquals("unit-death-knight", deathKnight.type().ident());
        assertEquals(5, deathKnight.player());

        for (int tick = 0; tick < INITIALIZATION_TICKS; tick++) {
            mission.tick();
        }
        while (fixtureCycle(world) < 511) {
            mission.tick();
        }

        assertEquals(Unit.Order.STILL, deathKnight.order());
        assertEquals(64, deathKnight.tileX());
        assertEquals(59, deathKnight.tileY());

        mission.tick();

        assertEquals(512, fixtureCycle(world));
        assertEquals(Unit.Order.ATTACK, deathKnight.order(),
                "native FUN_0040a830 promotes the caster-specific scan");
        Unit target = deathKnight.target();
        assertNotNull(target, "the native action-12 order retains its unit target");
        assertEquals("unit-human-barracks", target.type().ident());
        assertEquals(1, target.player());
        assertEquals(60, target.tileX());
        assertEquals(63, target.tileY());
        assertSame(target, deathKnight.target());

        while (fixtureCycle(world) < 515) {
            mission.tick();
        }
        assertEquals(63, deathKnight.tileX());
        assertEquals(60, deathKnight.tileY());
        assertEquals(3, deathKnight.pathLength(),
                "the first southwest chase step leaves S,S,S cached");
        assertTrue(deathKnight.isMoving(),
                "the logical southwest step still owes its pixel residual");

        while (fixtureCycle(world) < 532) {
            mission.tick();
        }
        assertEquals(63, deathKnight.tileX());
        assertEquals(60, deathKnight.tileY());
        assertEquals(3, deathKnight.offsetX());
        assertEquals(-3, deathKnight.offsetY());
        assertTrue(world.targets.inAttackRange(deathKnight, target),
                "the southwest tile has already entered death-knight range");

        mission.tick();

        assertEquals(533, fixtureCycle(world));
        assertEquals(63, deathKnight.tileX());
        assertEquals(60, deathKnight.tileY(),
                "native pays the last three pixels without consuming cached S");
        assertEquals(0, deathKnight.offsetX());
        assertEquals(0, deathKnight.offsetY());
        assertEquals(0, deathKnight.pathLength(),
                "Java's empty path represents native route index twenty");
        assertFalse(deathKnight.routeSpent());
        assertFalse(deathKnight.isMoving());
        assertFalse(deathKnight.chasing());
        assertTrue(deathKnight.fighting());
        assertSame(target, deathKnight.target());

        // The first touch-of-death shot is constructed at fixture 544. Native
        // type 10 drains its 129-pixel remaining distance at 12 pixels per
        // update, enters action 6 at fixture 555, animates there through 570,
        // and applies its nine damage only when it frees at 571. The generated
        // declaration's speed 16 plus a one-pass impact used to take the
        // barracks from 705 to 696 at fixture 554 instead.
        while (fixtureCycle(world) < 554) {
            mission.tick();
        }
        assertEquals(705, target.hitPoints(),
                "touch-of-death must still be in flight at the old Java impact cycle");

        while (fixtureCycle(world) < 570) {
            mission.tick();
        }
        assertEquals(703, target.hitPoints(),
                "the unrelated two-damage hit lands while native action 6 remains visible");

        mission.tick();
        assertEquals(571, fixtureCycle(world));
        assertEquals(694, target.hitPoints(),
                "touch-of-death damage belongs to the final action-6 visit");
    }

    private static int fixtureCycle(World world) {
        return Math.max(0, (int) world.cycle() - INITIALIZATION_TICKS);
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
