package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.map.PudReader;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
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
                "idle survivors must be re-engaged instead of silently stalling");
    }
}
