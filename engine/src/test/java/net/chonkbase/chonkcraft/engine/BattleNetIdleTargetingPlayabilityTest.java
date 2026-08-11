package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Complete retail-roster idle-acquisition loop with a player-visible referee. */
class BattleNetIdleTargetingPlayabilityTest {

    @Test
    @DisplayName("an idle retail footman rejects air, acquires ground, and kills")
    void retailFootmanGuardsItsPost() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);

        GameMap map = new GameMap(40, 32, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        Player[] players = new Player[Player.MAX];
        for (int player = 0; player < players.length; player++) {
            players[player] = new Player(player,
                    player < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    player == 1 ? PudMap.Race.ORC : PudMap.Race.HUMAN);
        }
        World world = new World(map, players);
        world.establishDiplomacy();
        world.setUnitTypes(data.unitTypes().types());
        world.setUpgrades(data.upgrades().upgrades());
        world.setMissileTypes(data.missiles().types());
        world.fog().revealAll(0);
        world.fog().revealAll(1);

        UnitType footmanType = data.unitTypes().types().get("unit-footman");
        UnitType gruntType = data.unitTypes().types().get("unit-grunt");
        UnitType balloonType = data.unitTypes().types().get("unit-balloon");
        assertNotNull(footmanType, "retail roster has no footman");
        assertNotNull(gruntType, "retail roster has no grunt");
        assertNotNull(balloonType, "retail roster has no balloon");

        Unit footman = world.createUnit(footmanType, 0, 16, 16);
        Unit balloon = world.createUnit(balloonType, 1, 18, 16);
        assertNotNull(footman);
        assertNotNull(balloon);
        int postX = footman.tileX();
        int postY = footman.tileY();

        // Let several native idle markers pass. A footman cannot target air,
        // so the nearby hostile balloon must not wake it.
        for (int cycle = 0; cycle < 50; cycle++) {
            world.tick();
            assertTrue(footman.target() != balloon,
                    "the retail footman selected an ineligible air target");
        }
        assertNull(footman.target(), "the air-only contact woke the idle footman");

        Unit grunt = world.createUnit(gruntType, 1, 17, 16);
        assertNotNull(grunt);
        grunt.setHitPoints(1);
        boolean acquiredGround = false;
        boolean lethalHit = false;
        boolean previouslyAlive = true;
        for (int cycle = 0; cycle < 1_000 && grunt.isAlive(); cycle++) {
            world.tick();
            acquiredGround |= footman.target() == grunt;
            lethalHit |= previouslyAlive && !grunt.isAlive();
            previouslyAlive = grunt.isAlive();
        }

        assertTrue(acquiredGround, "the idle retail footman ignored adjacent ground combat");
        assertTrue(lethalHit, "the idle-acquired retail grunt was never killed");
        assertFalse(grunt.isAlive(), "the idle fight never resolved");
        assertTrue(footman.tileX() == postX && footman.tileY() == postY,
                "stationary idle defense abandoned the footman's post");
    }
}
