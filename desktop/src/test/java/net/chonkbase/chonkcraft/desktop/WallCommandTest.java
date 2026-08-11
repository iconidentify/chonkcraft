package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.source.InstallSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.MapField;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.CommandApplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Giving the tile half of an attack order from the interface.
 *
 * <p>A wall is terrain rather than a unit, so neither the right-click path nor
 * the attack cursor found anything under the pointer. The engine could damage
 * a wall tile once commanded directly, but the interface turned the same
 * click into a move order and a melee army stopped at the wall without ever
 * swinging. Upstream's tile form of {@code CommandAttack} is the missing
 * bridge.
 */
class WallCommandTest {

    private record Rig(GameScreen screen, World world, Unit footman, List<GameCommand> sent) {}

    private static Rig rig() {
        InstallSource install = InstallSource.fromEnvironment();
        Assumptions.assumeTrue(install != null,
                "No Warcraft II installation configured. Set -Dwc2.install.dir=/path/to/game.");
        GameData data = new GameData(install);

        GameMap map = new GameMap(32, 32, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        MapField wall = map.field(12, 10);
        wall.setFlags(TileFlag.LAND_ALLOWED | TileFlag.WALL | TileFlag.HUMAN
                | TileFlag.UNPASSABLE);
        wall.setValue(GameMap.WALL_HIT_POINTS);

        World world = new World(map);
        world.setUnitTypes(data.unitTypes().types());
        world.setMissileTypes(data.missiles().types());
        Unit footman = world.createUnit(data.unitTypes().types().get("unit-footman"), 0, 10, 10);
        assertNotNull(footman, "the fixture could not place its footman");

        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        List<GameCommand> sent = new ArrayList<>();
        CommandSink sink = command -> {
            sent.add(command);
            applier.apply(command);
        };
        GameScreen screen = new GameScreen(world, data,
                new java.awt.image.BufferedImage(64, 64,
                        java.awt.image.BufferedImage.TYPE_INT_RGB),
                null, "summer", 0, 640, 480, null, null, null, applier,
                sink, List.of(), "human");
        screen.setSize(640, 480);
        screen.setLayout((net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout) null);
        screen.setGameScale(1);
        screen.selectForTest(footman);
        return new Rig(screen, world, footman, sent);
    }

    @Test
    @DisplayName("right-clicking a wall sends the tile form of attack")
    void aWallClickIsAnAttackCommand() {
        Rig rig = rig();

        assertEquals("", rig.screen().rightClickForTest(rig.footman(), 12, 10),
                "BNE acknowledges an accepted attack with voice and target feedback, not"
                        + " a lowercase debug verb in the status strip");
        assertEquals(1, rig.sent().size(), "the wall click sent more than one order");
        assertEquals(GameCommand.Kind.ATTACK_GROUND, rig.sent().getFirst().kind(),
                "the wall click was turned into a move rather than a tile attack");
        assertEquals(Unit.Order.ATTACK_GROUND, rig.footman().order(),
                "the command reached the wire but not the simulation");
    }

    @Test
    @DisplayName("a wall under an armed unit gets the enemy cursor")
    void aWallLooksAttackable() {
        Rig rig = rig();

        assertEquals(GameCursors.Kind.ENEMY,
                rig.screen().kindAtForTest(12 * 32 + 1, 10 * 32 + 1),
                "the pointer called a wall plain ground even though the footman can attack it");
    }

    @Test
    @DisplayName("holding Shift marks a map command for the order queue")
    void aShiftedClickReachesTheWireAsQueued() {
        Rig rig = rig();

        assertEquals("", rig.screen().rightClickForTest(rig.footman(), 20, 10, true));

        assertEquals(1, rig.sent().size());
        assertEquals(GameCommand.Kind.MOVE, rig.sent().getFirst().kind());
        assertEquals(true, rig.sent().getFirst().queued());
    }

    @Test
    @DisplayName("right-clicking a friendly unit follows instead of its old square")
    void aFriendlyClickIsAFollowCommand() {
        Rig rig = rig();
        Unit friend = rig.world().createUnit(rig.footman().type(), 0, 16, 10);
        assertNotNull(friend);

        assertEquals("", rig.screen().rightClickForTest(rig.footman(), 16, 10));

        assertEquals(1, rig.sent().size());
        assertEquals(GameCommand.Kind.FOLLOW, rig.sent().getFirst().kind());
        assertEquals(friend.id(), rig.sent().getFirst().targetId());
        assertEquals(Unit.Order.FOLLOW, rig.footman().order());
    }
}
