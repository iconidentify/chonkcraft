package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.data.source.AssetSource;
import net.chonkbase.chonkcraft.engine.GameData;
import net.chonkbase.chonkcraft.engine.World;
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

/**
 * An open-ground group click must name each soldier's dest the way
 * {@code DoRightButton} does, not the raw clicked square.
 */
class DestSpreadTest {

    private static final int SIZE = 32;

    private record Scene(GameScreen screen, World world, GameData data) {}

    private static Scene scene() {
        AssetSource source = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(source != null,
                "No authenticated BNE asset pack configured (-Dchonkcraft.pack). ");
        GameData data = new GameData(source);
        GameMap map = new GameMap(SIZE, SIZE, new Tileset());
        for (int y = 0; y < SIZE; y++) {
            for (int x = 0; x < SIZE; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        World world = new World(map);
        data.configureWorld(world, PudMap.Tileset.FOREST);
        world.fog().revealAll(0);

        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(applier);
        GameScreen screen = new GameScreen(world, data,
                new BufferedImage(SIZE * Unit.TILE_PIXELS, SIZE * Unit.TILE_PIXELS,
                        BufferedImage.TYPE_INT_RGB),
                data.loadTileset(PudMap.Tileset.FOREST).palette(), "summer", 0,
                800, 600, new net.chonkbase.chonkcraft.engine.sound.GameAudio(data.sounds()),
                null, null, applier, CommandSink.local(applier), List.of(), "human");
        screen.setSize(800, 600);
        screen.setLayout((net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout) null);
        screen.setGameScale(1);
        return new Scene(screen, world, data);
    }

    private static Unit make(Scene scene, String ident, int x, int y) {
        UnitType type = scene.data().unitTypes().types().get(ident);
        assertNotNull(type, "BNE roster has " + ident);
        Unit unit = scene.world().createUnit(type, 0, x, y);
        assertNotNull(unit, "could not place " + ident + " at " + x + ',' + y);
        return unit;
    }

    private static int[] destOf(Scene scene, Unit unit) {
        for (PlayerIntentJournal.Entry entry : scene.screen().intentEntriesForTest()) {
            GameCommand command = entry.command();
            if (command != null && command.kind() == GameCommand.Kind.MOVE
                    && command.unitId() == unit.id()) {
                return new int[] { command.x(), command.y() };
            }
        }
        throw new AssertionError("no move was journalled for the soldier at "
                + unit.tileX() + ',' + unit.tileY());
    }

    @Test
    @DisplayName("two nearby footmen spread an open-ground click onto neighbouring dests")
    void twoNearbyFootmenSpreadAnOpenGroundClickOntoNeighbouringDests() {
        Scene scene = scene();
        Unit first = make(scene, "unit-footman", 21, 5);
        Unit second = make(scene, "unit-footman", 17, 7);
        scene.screen().selectForTest(List.of(first, second));
        scene.screen().fieldRightClickForTest(25, 28, null);

        int[] firstDest = destOf(scene, first);
        int[] secondDest = destOf(scene, second);
        assertEquals(25, firstDest[0],
                "the first footman must keep the clicked column");
        assertEquals(27, firstDest[1],
                "the first footman must walk to 25,27, not stack on the click");
        assertEquals(25, secondDest[0],
                "the second footman must keep the clicked column");
        assertEquals(29, secondDest[1],
                "the second footman must walk to 25,29, not stack on the click");
    }

    @Test
    @DisplayName("a group whose box is wider than three tiles keeps the clicked square")
    void aGroupWhoseBoxIsWiderThanThreeTilesKeepsTheClickedSquare() {
        Scene scene = scene();
        Unit first = make(scene, "unit-footman", 21, 5);
        Unit second = make(scene, "unit-footman", 17, 7);
        Unit third = make(scene, "unit-footman", 10, 13);
        scene.screen().selectForTest(List.of(first, second, third));
        scene.screen().fieldRightClickForTest(25, 28, null);

        for (Unit unit : List.of(first, second, third)) {
            int[] dest = destOf(scene, unit);
            assertEquals(25, dest[0],
                    "a wide group must keep the clicked column for every soldier");
            assertEquals(28, dest[1],
                    "a wide group must keep the clicked row for every soldier");
        }
    }

    @Test
    @DisplayName("two soldiers whose column is short dest-spread only along that column")
    void twoSoldiersWhoseColumnIsShortDestSpreadOnlyAlongThatColumn() {
        Scene scene = scene();
        Unit first = make(scene, "unit-grunt", 28, 24);
        Unit second = make(scene, "unit-grunt", 18, 23);
        scene.screen().selectForTest(List.of(first, second));
        scene.screen().fieldRightClickForTest(23, 20, null);

        int[] firstDest = destOf(scene, first);
        int[] secondDest = destOf(scene, second);
        assertEquals(23, firstDest[0],
                "a wide pair must keep the clicked column");
        assertEquals(21, firstDest[1],
                "the first grunt's dest is his row plus click minus the pair mean");
        assertEquals(23, secondDest[0],
                "a wide pair must keep the clicked column");
        assertEquals(20, secondDest[1],
                "the second grunt's dest is his row plus click minus the pair mean");
    }

    @Test
    @DisplayName("a click inside the selected box does not dest-spread")
    void aClickInsideTheSelectedBoxDoesNotDestSpread() {
        Scene scene = scene();
        Unit first = make(scene, "unit-footman", 10, 10);
        Unit second = make(scene, "unit-footman", 10, 12);
        scene.screen().selectForTest(List.of(first, second));
        scene.screen().fieldRightClickForTest(10, 11, null);

        for (Unit unit : List.of(first, second)) {
            int[] dest = destOf(scene, unit);
            assertEquals(10, dest[0],
                    "an inside click must keep the clicked column");
            assertEquals(11, dest[1],
                    "an inside click must keep the clicked row");
        }
    }
}
