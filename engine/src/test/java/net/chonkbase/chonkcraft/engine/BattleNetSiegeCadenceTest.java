package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.missile.Missile;
import net.chonkbase.chonkcraft.engine.save.LoadGame;
import net.chonkbase.chonkcraft.engine.save.SaveGame;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Exact repeated-shot coverage for BNE's two player-controlled siege engines. */
class BattleNetSiegeCadenceTest {

    private record Fixture(GameData data, World world) {}

    private static Fixture fixture() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "BNE ChonkPack required for script.bin siege cadence");
        GameData data = new GameData(assets);
        return new Fixture(data, world(data));
    }

    private static World world(GameData data) {
        GameMap map = new GameMap(48, 48, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        map.recordLoadedTerrain();
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    i == 0 ? PudMap.Race.ORC : PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.establishDiplomacy();
        data.configureWorld(world, PudMap.Tileset.FOREST);
        world.fog().revealAll(0);
        world.fog().revealAll(1);
        return world;
    }

    private static Unit place(Fixture fixture, String ident, int player, int x, int y) {
        Unit unit = fixture.world().createUnit(
                fixture.data().unitTypes().types().get(ident), player, x, y);
        assertNotNull(unit, "could not place " + ident);
        return unit;
    }

    /** Cycles on which a projectile crosses BNE's constructor boundary. */
    private static List<Integer> shotCycles(World world, String missileIdent, int cycles) {
        List<Integer> result = new ArrayList<>();
        Set<Missile> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.addAll(world.missiles());
        for (int cycle = 1; cycle <= cycles; cycle++) {
            world.tick();
            for (Missile missile : world.missiles()) {
                if (missileIdent.equals(missile.type().ident())
                        && world.savedProjectileStartCycle(missile) >= 0
                        && seen.add(missile)) {
                    result.add(cycle);
                }
            }
        }
        return result;
    }

    @Test
    @DisplayName("catapult ground fire uses its 200-cycle BNE program, not the world clock")
    void catapultGroundFireHasRetailCadence() {
        Fixture fixture = fixture();
        for (int ignored = 0; ignored < 7; ignored++) {
            fixture.world().tick();
        }
        Unit catapult = place(fixture, "unit-catapult", 0, 10, 10);
        catapult.setDirection(net.chonkbase.chonkcraft.engine.missile.Missile
                .directionToHeading(6, 0));
        catapult.setPendingRotation(0);

        assertTrue(fixture.world().orderAttackGround(catapult, 16, 10));
        List<Integer> shots = shotCycles(
                fixture.world(), "missile-catapult-rock", 430);

        assertEquals(List.of(4, 204, 404), shots,
                "attack-ground must not wait for/divide the global 30-cycle clock");
        assertNotNull(catapult.animation().current(),
                "the shortcut fired without selecting the attack animation");
        assertTrue(catapult.animation().current().name().contains("Attack"));
    }

    @Test
    @DisplayName("ordinary catapult targeting and attack-ground share the retail firing beat")
    void unitAndGroundTargetsShareTheNativeProgram() {
        Fixture fixture = fixture();
        Unit catapult = place(fixture, "unit-catapult", 0, 10, 10);
        Unit target = place(fixture, "unit-footman", 1, 16, 10);
        catapult.setDirection(net.chonkbase.chonkcraft.engine.missile.Missile
                .directionToHeading(6, 0));
        catapult.setPendingRotation(0);

        assertTrue(fixture.world().orderAttack(catapult, target));
        assertEquals(List.of(4, 204), shotCycles(
                fixture.world(), "missile-catapult-rock", 220));
    }

    @Test
    @DisplayName("attack-ground stores siege damage and resolves retail splash")
    void attackGroundCrossesTheNativeDamageConstructor() {
        Fixture fixture = fixture();
        Unit catapult = place(fixture, "unit-catapult", 0, 10, 10);
        Unit target = place(fixture, "unit-footman", 1, 16, 10);
        catapult.setDirection(net.chonkbase.chonkcraft.engine.missile.Missile
                .directionToHeading(6, 0));
        catapult.setPendingRotation(0);
        int before = target.hitPoints();

        assertTrue(fixture.world().orderAttackGround(catapult, 16, 10));
        for (int cycle = 0; cycle < 100 && target.hitPoints() == before; cycle++) {
            fixture.world().tick();
        }
        assertTrue(target.hitPoints() < before,
                "the coordinate shot crossed opcode ten but carried zero damage");
    }

    @Test
    @DisplayName("ballista ground fire retains its native wind-up and 200-cycle reload")
    void ballistaGroundFireHasRetailWindupAndCadence() {
        Fixture fixture = fixture();
        Unit ballista = place(fixture, "unit-ballista", 0, 10, 10);
        ballista.setDirection(net.chonkbase.chonkcraft.engine.missile.Missile
                .directionToHeading(6, 0));
        ballista.setPendingRotation(0);

        assertTrue(fixture.world().orderAttackGround(ballista, 16, 10));
        List<Integer> shots = shotCycles(
                fixture.world(), "missile-ballista-bolt", 450);

        assertEquals(List.of(29, 229, 429), shots,
                "the ballista lost its 25-cycle wind-up or 200-cycle reload");
    }

    @Test
    @DisplayName("a siege engine completes its slow turn before attack-ground opcode ten")
    void attackGroundWaitsForTheRetailSiegeTurn() {
        // Human 07 1519 opens Attack while still face 7 and constructs at
        // fixture 13. The old R>=30 cursor gate delayed that rock to 44.
        // A west-facing ground click therefore uses the same program beat
        // as a pre-aligned one; the script snaps facing, it does not hold
        // the native cursor at -1.
        Fixture fixture = fixture();
        Unit catapult = place(fixture, "unit-catapult", 0, 10, 10);
        catapult.setDirection(net.chonkbase.chonkcraft.engine.missile.Missile
                .directionToHeading(-6, 0));
        catapult.setPendingRotation(0);

        assertTrue(fixture.world().orderAttackGround(catapult, 16, 10));
        List<Integer> shots = shotCycles(
                fixture.world(), "missile-catapult-rock", 80);

        assertEquals(List.of(4), shots,
                "attack-ground must open the Attack program on the first "
                        + "visit the way Human 07 does, not wait out Anim.Rotate");
    }

    @Test
    @DisplayName("retargeting cannot restart a catapult inside its committed reload")
    void retargetWaitsBehindTheUnbreakableReload() {
        Fixture fixture = fixture();
        Unit catapult = place(fixture, "unit-catapult", 0, 10, 10);
        catapult.setDirection(net.chonkbase.chonkcraft.engine.missile.Missile
                .directionToHeading(6, 0));
        catapult.setPendingRotation(0);
        assertTrue(fixture.world().orderAttackGround(catapult, 16, 10));
        assertEquals(List.of(4), shotCycles(
                fixture.world(), "missile-catapult-rock", 5));

        assertTrue(catapult.animation().unbreakable(), "fixture is not inside reload");
        assertTrue(fixture.world().orderAttackGround(catapult, 10, 16));
        assertEquals(16, catapult.orderTargetX(),
                "the replacement overwrote the committed volley immediately");
        assertEquals(10, catapult.orderTargetY());
        assertTrue(catapult.queuedReplacementPending());
        assertFalse(catapult.queuedOrders().isEmpty());

        List<Integer> later = shotCycles(
                fixture.world(), "missile-catapult-rock", 260);
        assertEquals(1, later.size(),
                "retargeting restarted or duplicated a shot during the reload: " + later);
        assertEquals(10, catapult.orderTargetX());
        assertEquals(16, catapult.orderTargetY());
    }

    @Test
    @DisplayName("saving during attack-ground reload preserves its next firing beat")
    void attackGroundReloadRoundTrips() throws Exception {
        Fixture fixture = fixture();
        Unit catapult = place(fixture, "unit-catapult", 0, 10, 10);
        catapult.setDirection(net.chonkbase.chonkcraft.engine.missile.Missile
                .directionToHeading(6, 0));
        catapult.setPendingRotation(0);
        assertTrue(fixture.world().orderAttackGround(catapult, 16, 10));
        shotCycles(fixture.world(), "missile-catapult-rock", 70);

        StringWriter out = new StringWriter();
        SaveGame.write(fixture.world(), "test-map", null, 0, out);
        World loaded = world(fixture.data());
        LoadGame.apply(loaded, out.toString(), fixture.data().unitTypes().types());
        Unit loadedCatapult = loaded.units().stream()
                .filter(unit -> "unit-catapult".equals(unit.type().ident()))
                .findFirst().orElse(null);
        assertNotNull(loadedCatapult);
        assertEquals(Unit.Order.ATTACK_GROUND, loadedCatapult.order());
        assertEquals(catapult.orderTargetX(), loadedCatapult.orderTargetX());
        assertEquals(catapult.orderTargetY(), loadedCatapult.orderTargetY());
        assertEquals(catapult.battleNetSequenceOffset(),
                loadedCatapult.battleNetSequenceOffset());
        assertEquals(catapult.battleNetAnimationTimer(),
                loadedCatapult.battleNetAnimationTimer());
        assertEquals(catapult.animation().index(), loadedCatapult.animation().index());
        assertEquals(catapult.animation().waitCycles(),
                loadedCatapult.animation().waitCycles());

        assertEquals(shotCycles(fixture.world(), "missile-catapult-rock", 180),
                shotCycles(loaded, "missile-catapult-rock", 180),
                "reload resumed on a different shot cycle after loading");
    }
}
