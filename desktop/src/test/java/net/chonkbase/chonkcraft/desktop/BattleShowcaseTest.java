package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class BattleShowcaseTest {

    @Test
    @DisplayName("the showcase deploys two full armies and they engage")
    void theShowcaseDeploysTwoFullArmiesAndTheyEngage() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);
        String mapName = BattleShowcase.defaultMapName(assets);
        assertTrue(mapName.toLowerCase().contains("garden"),
                "the retail Garden of War should be the showcase stage");
        PudMap source = PudReader.read(assets.map(mapName));
        World world = new World(
                GameMap.from(source, data.loadTileset(source.tileset()).tileset()),
                Player.forSoloGame(source));
        data.configureWorld(world, source);

        BattleShowcase.Result result = BattleShowcase.deploy(
                world, data.unitTypes().types(), 120);
        assertEquals(120, result.deployed());
        assertEquals(60, result.humanUnits());
        assertEquals(60, result.orcUnits());
        assertEquals(world.map().width() / 2, result.centreX());
        assertEquals(world.map().height() / 2, result.centreY());
        assertEquals(BattleShowcase.HUMAN_FIELD_TYPES,
                deployedTypes(result, 0));
        assertEquals(BattleShowcase.ORC_FIELD_TYPES,
                deployedTypes(result, 1));
        long attackers = result.units().stream()
                .filter(unit -> unit.type().canAttack())
                .count();
        long explicitlyAimed = result.units().stream()
                .filter(unit -> unit.type().canAttack())
                .filter(unit -> unit.target() != null)
                .count();
        assertEquals(attackers, explicitlyAimed,
                "every combatant starts with a concrete enemy, not a vague destination");

        int oneTile = world.map().field(0, 0).tile();
        for (int y = 0; y < world.map().height(); y++) {
            for (int x = 0; x < world.map().width(); x++) {
                var field = world.map().field(x, y);
                assertEquals(oneTile, field.tile(), "arena picture at " + x + "," + y);
                assertTrue(field.isLandPassable(), "open ground at " + x + "," + y);
                assertFalse(field.hasFlag(TileFlag.FOREST | TileFlag.ROCKS
                                | TileFlag.WALL | TileFlag.UNPASSABLE),
                        "no terrain barrier at " + x + "," + y);
            }
        }

        BattleShowcase.Director director = new BattleShowcase.Director(world, result);
        int redirected = 0;
        for (int cycle = 0; cycle < 3_600; cycle++) {
            world.tick();
            redirected += director.update().redirected();
        }
        long hurtOrDead = result.units().stream()
                .filter(unit -> !unit.isAlive()
                        || unit.hitPoints() < unit.type().hitPoints())
                .count();
        assertTrue(hurtOrDead > 0,
                "the showcase must exercise combat rather than draw a static formation");
        assertTrue(redirected > 0,
                "disengaged survivors must be re-engaged instead of silently stalling");
    }

    private static Set<String> deployedTypes(BattleShowcase.Result result, int player) {
        return result.units().stream()
                .filter(unit -> unit.player() == player)
                .map(unit -> unit.type().ident())
                .collect(java.util.stream.Collectors.toSet());
    }
}
