package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.missile.MissileClass;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * BNE projectile pool allocates the lowest free slot and the timed pass walks
 * ascending -- free/reuse of low slots while a long-lived rock keeps a mid
 * slot is what places free splash after live travelers.
 *
 * <p>Native single-player capacity 200 with ambient 0–2 never free. XHuman 10
 * free@42 is arrows 3/4/6 and rock 5; creation-order step put rock first and
 * spent the wrong splash ordinal. A traveler-count reorder fixed one map but
 * REGd others. A presentation-only placeholder owns no pool slot until its
 * native constructor, and pool free/reuse is the general rule.
 */
class BattleNetProjectilePoolOrderTest {

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static MissileType rock() {
        return new MissileType("missile-catapult-rock", null,
                MissileClass.PARABOLIC, 32, 32, 1, 1, 8, 1, 1, 0, 0, null, null,
                false, 0, 0, false, null, 0);
    }

    private static MissileType axe() {
        return new MissileType("missile-axe", null, MissileClass.POINT_TO_POINT,
                32, 32, 1, 1, 12, 1, 1, 0, 0, null, null, false, 0, 0, false,
                null, 0);
    }

    private static MissileType bigCannon() {
        return new MissileType("missile-big-cannon", null,
                MissileClass.POINT_TO_POINT, 16, 16, 20, 9, 16, 1, 2, 4, 50,
                "missile-cannon-tower-explosion", null, false, 0, 0, false,
                null, 0);
    }

    private static MissileType cannonExplosion() {
        return new MissileType("missile-cannon-tower-explosion", null,
                MissileClass.STAY, 32, 32, 4, 1, 16, 2, 1, 1, 50, null, null,
                false, 0, 0, false, null, 0);
    }

    private static UnitType catapult() {
        UnitType type = new UnitType("unit-catapult");
        type.setTileSize(1, 1);
        type.setBoxSize(32, 32);
        type.setHitPoints(110);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(25);
        type.setPiercingDamage(30);
        type.setMaxAttackRange(8);
        type.setMissile("missile-catapult-rock");
        return type;
    }

    private static UnitType thrower() {
        UnitType type = new UnitType("unit-axethrower");
        type.setTileSize(1, 1);
        type.setBoxSize(32, 32);
        type.setHitPoints(40);
        type.setLandUnit(true);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(3);
        type.setPiercingDamage(6);
        type.setMaxAttackRange(4);
        type.setMissile("missile-axe");
        return type;
    }

    private static UnitType footman() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setBoxSize(32, 32);
        type.setHitPoints(60);
        type.setLandUnit(true);
        type.setArmor(2);
        return type;
    }

    @Test
    @DisplayName("bne projectile slots allocate lowest free and free on remove")
    void bneProjectileSlotsAllocateLowestFreeAndFreeOnRemove() {
        World world = new World(grass(16));
        world.setMissileTypes(Map.of(
                "missile-catapult-rock", rock(),
                "missile-axe", axe()));
        world.restoreRandom(1, 0);
        Unit attacker = world.createUnit(catapult(), 0, 2, 2);
        Unit target = world.createUnit(footman(), 1, 8, 2);
        assertTrue(attacker != null && target != null, "units place");

        Missile first = world.projectiles.launch(attacker, target, rock());
        Missile second = world.projectiles.launch(attacker, target, axe());
        assertEquals(3, first.battleNetPoolSlot(),
                "first live shot takes the first free slot after ambient 0–2");
        assertEquals(4, second.battleNetPoolSlot(),
                "second live shot takes the next free ascending slot");

        world.missiles.remove(first);
        world.freeBattleNetProjectileSlot(first.battleNetPoolSlot());
        first.setBattleNetPoolSlot(-1);

        Missile third = world.projectiles.launch(attacker, target, axe());
        assertEquals(3, third.battleNetPoolSlot(),
                "a freed low slot is reused before higher free indices "
                        + "(XHuman 10 free@42 arrows 3/4/6 rock 5)");
    }

    @Test
    @DisplayName("a presentation placeholder takes its pool slot only at construction")
    void presentationPlaceholderDoesNotReserveAheadOfARealProjectile() {
        World world = new World(grass(16));
        world.setMissileTypes(Map.of(
                "missile-catapult-rock", rock(),
                "missile-axe", axe()));
        world.restoreRandom(1, 0);
        Unit firstAttacker = world.createUnit(catapult(), 0, 2, 2);
        Unit secondAttacker = world.createUnit(thrower(), 0, 2, 4);
        Unit target = world.createUnit(footman(), 1, 8, 2);
        assertTrue(firstAttacker != null && secondAttacker != null && target != null,
                "units place");

        Missile pendingRock = world.projectiles.launch(
                firstAttacker, target, rock());
        world.projectiles.queuePendingAttack(firstAttacker, pendingRock, 3);
        assertEquals(-1, pendingRock.battleNetPoolSlot(),
                "a pre-constructor presentation sprite reserved native state");

        Missile realAxe = world.projectiles.launch(secondAttacker, target, axe());
        world.prepareBattleNetProjectile(realAxe, true);
        assertEquals(3, realAxe.battleNetPoolSlot(),
                "the earlier real constructor did not take the lowest free slot");

        world.prepareBattleNetProjectile(pendingRock, true);
        assertEquals(4, pendingRock.battleNetPoolSlot(),
                "the later constructor did not follow the earlier real shot");
    }

    @Test
    @DisplayName("a BNE cannon constructor reserves its source effect pool entry")
    void cannonConstructorReservesTheFollowingNativePoolEntry() {
        World world = new World(grass(16));
        MissileType cannon = bigCannon();
        world.setMissileTypes(Map.of(
                "missile-big-cannon", cannon,
                "missile-cannon-tower-explosion", cannonExplosion()));
        world.restoreRandom(1, 0);
        Unit firstAttacker = world.createUnit(catapult(), 0, 2, 2);
        Unit secondAttacker = world.createUnit(catapult(), 0, 2, 4);
        Unit target = world.createUnit(footman(), 1, 8, 2);
        assertTrue(firstAttacker != null && secondAttacker != null
                && target != null, "units place");

        Missile first = world.projectiles.launch(firstAttacker, target, cannon);
        world.projectiles.queuePendingAttack(firstAttacker, first, 3);
        world.prepareBattleNetProjectile(first, true);
        Missile second = world.projectiles.launch(secondAttacker, target, cannon);
        world.projectiles.queuePendingAttack(secondAttacker, second, 3);
        world.prepareBattleNetProjectile(second, true);

        assertEquals(3, first.battleNetPoolSlot());
        assertTrue(world.battleNetProjectileSlots[4],
                "native type-25 cannon source effect owns the following slot");
        assertEquals(5, second.battleNetPoolSlot(),
                "the second real cannon shell follows the first source effect");
        assertTrue(world.battleNetProjectileSlots[6],
                "the second cannon source effect reserves its own pool entry");
    }

    @Test
    @DisplayName("step missiles walks ascending pool slots not creation order")
    void stepMissilesWalksAscendingPoolSlotsNotCreationOrder() {
        World world = new World(grass(16));
        world.setMissileTypes(Map.of(
                "missile-catapult-rock", rock(),
                "missile-axe", axe()));
        world.restoreRandom(1, 0);
        Unit attacker = world.createUnit(thrower(), 0, 2, 2);
        Unit target = world.createUnit(footman(), 1, 10, 2);
        assertTrue(attacker != null && target != null, "units place");

        // Simulate free/reuse: rock holds mid slot, later axes reclaim low.
        Missile early = world.projectiles.launch(attacker, target, axe());
        Missile rockShot = world.projectiles.launch(attacker, target, rock());
        Missile late = world.projectiles.launch(attacker, target, axe());
        assertEquals(3, early.battleNetPoolSlot());
        assertEquals(4, rockShot.battleNetPoolSlot());
        assertEquals(5, late.battleNetPoolSlot());

        world.missiles.remove(early);
        world.freeBattleNetProjectileSlot(early.battleNetPoolSlot());
        early.setBattleNetPoolSlot(-1);
        Missile reused = world.projectiles.launch(attacker, target, axe());
        assertEquals(3, reused.battleNetPoolSlot(),
                "reused low slot is below the long-lived rock");

        List<Integer> order = new ArrayList<>();
        List<Missile> flying = new ArrayList<>(world.missiles);
        flying.sort((a, b) -> Integer.compare(
                a.battleNetPoolSlot(), b.battleNetPoolSlot()));
        for (Missile m : flying) {
            order.add(m.battleNetPoolSlot());
        }
        assertEquals(List.of(3, 4, 5), order,
                "timed pass walks low slot to high so free rock is not first "
                        + "when later shots reclaimed lower free indices");
        assertTrue(order.indexOf(reused.battleNetPoolSlot())
                        < order.indexOf(rockShot.battleNetPoolSlot()),
                "reused axe (slot 3) steps before rock (slot 4) — free splash "
                        + "after live travelers without a traveler-count sort");
    }
}
