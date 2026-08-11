package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.missile.MissileClass;
import net.chonkbase.chonkcraft.engine.missile.MissileType;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a siege engine leaves behind when it dies, and what it does not.
 *
 * <p>{@code ExplodeWhenKilled} in the data is a missile's name, not a flag:
 * The game reads it with {@code DefinitionToString} into
 * {@code Explosion.Name}. The implementation coerced it to a bare true, so the fact
 * survived and the thing it named did not -- and nothing read even the fact.
 *
 * <p>It is cosmetic, which is the other half of what this pins down.
 * {@code LetUnitDie} calls {@code MakeMissile} without
 * a source unit and never sets one, and {@code Missile::MissileHit} returns
 * before its splash loop when there is no source, above the comment
 * "no owner - green-cross ...". The missile the data names carries no damage
 * of its own either. So a ballista kills with the shots it fires and not with
 * the crater, and the implementation's own {@code resolve} has the same guard -- which
 * is why passing no source here is the whole of what keeps a picture a
 * picture.
 */
class ExplosionTest {

    private static GameData gameData() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set -Dchonkcraft.pack=... or wc2.install.dir");
        return new GameData(assets);
    }

    @Test
    @DisplayName("The exploding types name the missile they leave")
    void theExplodingTypesNameTheirMissile() {
        GameData data = gameData();
        UnitType ballista = data.unitTypes().types().get("unit-ballista");
        assertNotNull(ballista, "the ballista is not in the roster");
        assertTrue(ballista.explodeWhenKilled(), "a ballista goes up when it dies");
        assertEquals("missile-explosion", ballista.explosion(),
                "and the data says which explosion, which used to be thrown away");

        MissileType explosion = data.missiles().types().get(ballista.explosion());
        assertNotNull(explosion, "no such missile: " + ballista.explosion());
        // The other half of "cosmetic": the missile carries no damage of its own.
        assertEquals(0, explosion.damage(), "the explosion does no damage of its own");

        UnitType footman = data.unitTypes().types().get("unit-footman");
        assertNotNull(footman);
        assertFalse(footman.explodeWhenKilled(), "an ordinary soldier just falls over");
        assertEquals("", footman.explosion());

        // Retail BNE 2.02b LetUnitDie (0x004514c0) explicitly compares the
        // type byte with 0x29 before entering the effect allocator at
        // 0x0040ff60. Zeppelin is type 0x29; Flying Machine is 0x28 and its
        // 0x82 flag byte does not take the generic bit-2 explosion arm.
        UnitType zeppelin = data.unitTypes().types().get("unit-zeppelin");
        UnitType balloon = data.unitTypes().types().get("unit-balloon");
        assertNotNull(zeppelin);
        assertNotNull(balloon);
        assertEquals("missile-explosion", zeppelin.explosion());
        assertEquals("", balloon.explosion(),
                "the Flying Machine intentionally takes BNE's vanish path");
    }

    // ------------------------------------------------------------ in the world

    private static MissileType explosion(int range) {
        return new MissileType("missile-explosion", "missiles/explosion.png",
                MissileClass.STAY, 64, 64, 20, 1, 16, 1, range, 1, 50,
                // The trailing flag is correctSplashDamage, added by the
                // combat parity work while this test was being written in
                // another branch. No missile Warcraft II ships sets it.
                null, null, false, 0, 0, false);
    }

    private static World world() {
        Tileset tileset = new Tileset();
        tileset.setTile(1, new Tileset.Tile(1, TileFlag.LAND_ALLOWED, 1, 0));
        GameMap map = new GameMap(20, 20, tileset);
        for (int y = 0; y < 20; y++) {
            for (int x = 0; x < 20; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return new World(map);
    }

    private static UnitType soldier(String ident) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        AnimationSet set = new AnimationSet("walker");
        set.put(AnimationSet.State.STILL, Animation.parse("still", List.of("frame 0", "wait 1")));
        type.setAnimationSet(set);
        return type;
    }

    @Test
    @DisplayName("A ballista's death puts an explosion on the map")
    void aSiegeEnginesDeathMakesTheMissile() {
        World world = world();
        world.setMissileTypes(Map.of("missile-explosion", explosion(1)));

        UnitType ballista = soldier("unit-ballista");
        ballista.setExplosion("missile-explosion");
        Unit unit = world.createUnit(ballista, 0, 8, 8);

        assertTrue(world.missiles().isEmpty());
        world.kill(unit);
        assertEquals(1, world.missiles().size(), "a ballista leaves a crater");
        assertNull(world.missiles().get(0).source(),
                "and no source, which is what keeps it from hurting anything");
    }

    @Test
    @DisplayName("The retail zeppelin leaves a visible death effect")
    void theRetailZeppelinDeathStaysVisibleAfterItsSpriteIsReleased() {
        GameData data = gameData();
        World world = world();
        world.setMissileTypes(data.missiles().types());
        UnitType aircraft = data.unitTypes().types().get("unit-zeppelin");
        Unit unit = world.createUnit(aircraft, 0, 8, 8);

        world.kill(unit);

        assertEquals(1, world.missiles().size(),
                "the Zeppelin disappeared without retail's effect record");
        assertEquals("missile-explosion", world.missiles().get(0).type().ident());
        world.tick();
        world.tick();
        assertFalse(world.missiles().isEmpty(),
                "the effect vanished with the Zeppelin sprite");
    }

    /**
     * The claim the combat audit made, held to. Standing next to a ballista
     * when it dies costs nothing, because the explosion has no owner and so
     * nothing computes damage from it.
     */
    @Test
    @DisplayName("The explosion hurts nobody standing in it")
    void theExplosionHurtsNobody() {
        World world = world();
        world.setMissileTypes(Map.of("missile-explosion", explosion(3)));

        UnitType ballista = soldier("unit-ballista");
        ballista.setExplosion("missile-explosion");
        Unit unit = world.createUnit(ballista, 0, 8, 8);

        Unit bystander = world.createUnit(soldier("unit-footman"), 1, 9, 8);
        int before = bystander.hitPoints();

        world.kill(unit);
        for (int cycle = 0; cycle < 200; cycle++) {
            world.tick();
        }
        assertEquals(before, bystander.hitPoints(),
                "an explosion with no owner does no damage, as MissileHit's early return says");
    }

    @Test
    @DisplayName("A type that leaves nothing leaves nothing")
    void anOrdinaryDeathLeavesNoMissile() {
        World world = world();
        world.setMissileTypes(Map.of("missile-explosion", explosion(1)));
        Unit unit = world.createUnit(soldier("unit-footman"), 0, 8, 8);
        world.kill(unit);
        assertTrue(world.missiles().isEmpty());
    }
}
