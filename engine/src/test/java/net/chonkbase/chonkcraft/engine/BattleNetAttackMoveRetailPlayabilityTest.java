package net.chonkbase.chonkcraft.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Complete retail-roster attack-move loop driven through the wire seam. */
class BattleNetAttackMoveRetailPlayabilityTest {

    @Test
    @DisplayName("a retail footman interrupts its march, kills, and resumes")
    void retailFootmanCompletesAttackMove() {
        AssetSource assets = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(assets != null,
                "No asset pack/install. Set CHONKCRAFT_ASSET_PACK or wc2.install.dir");
        GameData data = new GameData(assets);

        GameMap map = new GameMap(48, 32, new Tileset());
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
        assertNotNull(footmanType, "retail roster has no footman");
        assertNotNull(gruntType, "retail roster has no grunt");
        Unit footman = world.createUnit(footmanType, 0, 4, 16);
        Unit grunt = world.createUnit(gruntType, 1, 18, 16);
        assertNotNull(footman);
        assertNotNull(grunt);
        grunt.setHitPoints(1);

        CommandApplier commands = new CommandApplier(
                world, new ArrayList<>(data.unitTypes().types().values()));
        commands.apply(GameCommand.attackMove(0, footman.id(), 44, 16));

        boolean acquiredBeforeDestination = false;
        boolean damaged = false;
        boolean resumed = false;
        int previousHealth = grunt.hitPoints();
        boolean previouslyAlive = grunt.isAlive();
        // Do not stop merely because the native-shaped order machine exposes
        // a transient Still boundary between the walk and its automatic
        // Attack. The referee owns the complete outcome, not one order byte.
        for (int cycle = 0; cycle < 5_000; cycle++) {
            world.tick();
            acquiredBeforeDestination |= footman.target() == grunt
                    && footman.tileX() < 44;
            // A lethal hit enters DYING while retaining the unit's final
            // positive hit-point value. The visible damage outcome is either
            // a lower value or the authoritative alive-to-dead transition.
            damaged |= grunt.hitPoints() < previousHealth
                    || (previouslyAlive && !grunt.isAlive());
            resumed |= !grunt.isAlive() && footman.tileX() > 24;
            previousHealth = grunt.hitPoints();
            previouslyAlive = grunt.isAlive();
            if (resumed && footman.tileX() > 40
                    && footman.order() == Unit.Order.STILL) {
                break;
            }
        }

        String finalState = "footman=" + footman.tileX() + "," + footman.tileY()
                + " hp=" + footman.hitPoints() + " alive=" + footman.isAlive()
                + " order=" + footman.order() + " action=" + footman.currentAction()
                + " target=" + (footman.target() == null ? "none" : footman.target().id())
                + "; grunt=" + grunt.tileX() + "," + grunt.tileY()
                + " hp=" + grunt.hitPoints() + " alive=" + grunt.isAlive()
                + " order=" + grunt.order() + " action=" + grunt.currentAction();
        assertTrue(acquiredBeforeDestination,
                "the retail footman reached the destination before detecting the grunt; "
                        + finalState);
        assertTrue(damaged, "the acquired retail grunt was never damaged; " + finalState);
        assertFalse(grunt.isAlive(), "the interrupted fight never resolved");
        assertTrue(resumed, "the footman did not resume its original march after the kill");
        assertTrue(footman.tileX() > 40,
                "the resumed attack-move did not complete its visible advance");
    }
}
