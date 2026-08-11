package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Test;

/** Permanent cloak, detector coverage, and reactions to an unseen attacker. */
class CloakingTest {

    private static World world() {
        GameMap map = new GameMap(40, 40, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return new World(map);
    }

    private static UnitType fighter(String ident) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(100);
        type.setSpeed(10);
        type.setSightRange(8);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setMaxAttackRange(4);
        type.setReactRangePerson(10);
        type.setReactRangeComputer(10);
        type.setBasicDamage(4);
        type.setPriority(50);
        return type;
    }

    private static UnitType submarine() {
        UnitType type = fighter("unit-human-submarine");
        type.setPermanentCloak(true);
        return type;
    }

    private static UnitType detector() {
        UnitType type = fighter("unit-human-watch-tower");
        type.setCanAttack(false);
        type.setDetectCloak(true);
        return type;
    }

    @Test
    void detectorCoverageControlsVisibilityAndLeavesWithTheDetector() {
        World world = world();
        world.createUnit(fighter("unit-scout"), 0, 10, 10);
        Unit submarine = world.createUnit(submarine(), 1, 14, 10);
        // The tile walks skip the cloaked, so an owner's count of its own
        // submarine only exists once something recounts it over the settled
        // fog -- which any real step does the moment the boat moves, and
        // which UpdateFogOfWarChange does wholesale. A freshly placed,
        // never-moved submarine is invisible even to its owner upstream too.
        world.recountSeen();

        assertTrue(world.fog().isVisible(0, submarine.tileX(), submarine.tileY()),
                "the fixture needs ordinary sight on the target square");
        assertTrue(world.isVisibleTo(1, submarine), "an owner should see its own submarine");
        assertFalse(world.isVisibleTo(0, submarine),
                "ordinary sight should not expose permanent cloak");

        Unit mine = world.createUnit(detector(), 0, 13, 12);
        assertTrue(world.isVisibleTo(0, submarine), "detector coverage should expose the submarine");

        world.kill(mine);
        assertFalse(world.isVisibleTo(0, submarine),
                "a dead detector left permanent coverage behind");

        // An alliance on its own reveals nothing. This used to assert the
        // opposite, and player 2 owns no unit anywhere on this map, so the
        // only thing that could ever have made it true was the ally clause
        // that used to open World.isVisibleTo -- the same clause that drew a
        // rescued village's peasants through the fog on seventeen missions.
        // Upstream counts VisCloak per player and CUnit::IsVisible adds only
        // the players the viewer has vision from.
        Unit detector = world.createUnit(detector(), 3, 13, 12);
        assertNotNull(detector, "the second detector was not placed");
        world.setAllied(2, 1, true);
        assertFalse(world.isVisibleTo(2, submarine),
                "an alliance is not shared vision and must not expose a submarine");

        // Shared vision is, and it carries the detector's coverage with it.
        world.setSharedVision(2, 3, true);
        assertTrue(world.isVisibleTo(2, submarine),
                "shared vision with a detector's owner did not expose the submarine");
    }

    @Test
    void automaticTargetingNeedsDetectorCoverageForASubmarine() {
        World world = world();
        Unit hunter = world.createUnit(fighter("unit-destroyer"), 0, 10, 10);
        Unit submarine = world.createUnit(submarine(), 1, 14, 10);

        // The submarine's square is ordinarily visible, but the unit is not.
        assertTrue(world.fog().isVisible(0, submarine.tileX(), submarine.tileY()));
        world.tick();
        assertNull(hunter.target(), "ordinary sight exposed a permanently cloaked unit");

        world.createUnit(detector(), 0, 13, 12);
        for (int cycle = 0; cycle < 20 && hunter.target() == null; cycle++) {
            world.tick();
        }
        assertSame(submarine, hunter.target(), "detector coverage did not expose the submarine");
    }

    @Test
    void armedUnitRunsFromAnUndetectedCloakedAttacker() {
        World world = world();
        Unit attacker = world.createUnit(submarine(), 1, 15, 15);
        Unit victim = world.createUnit(fighter("unit-destroyer"), 0, 16, 15);

        world.hit(attacker, victim);
        // The run is commanded with EFlushMode::Off, so it waits behind
        // whatever the unit was doing and becomes current on the next cycle.
        world.tick();

        // The flight of an armed unit is an attack-move, not a plain walk:
        // HitUnit_RunAway commands CommandAttack at the flee square for
        // IsAggressive(), so the destroyer fights
        // whatever it meets on the way out. On levelx07h that one label is
        // the whole first divergence at cycle 47.
        assertEquals(Unit.Order.ATTACK_MOVE, victim.order(),
                "an armed victim flees fighting -- upstream's run is an attack-move");
        assertNull(victim.target(), "fleeing should not acquire the cloaked attacker");
    }
}
