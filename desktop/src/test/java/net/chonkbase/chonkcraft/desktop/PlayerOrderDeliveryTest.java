package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
 * The player's click is a contract: every capable selected unit receives the
 * order, accepted orders remain installed, and a voice answers only an order
 * the simulation actually took.
 *
 * <p>The roster, animations, prices, command relations, and sounds all come
 * from the authenticated BNE asset source. The map is deliberately plain so
 * this gate measures command delivery rather than one campaign's geography.
 */
class PlayerOrderDeliveryTest {

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

    private static Unit make(Scene scene, String ident, int player, int x, int y) {
        UnitType type = scene.data().unitTypes().types().get(ident);
        assertNotNull(type, "BNE roster has " + ident);
        Unit unit = scene.world().createUnit(type, player, x, y);
        assertNotNull(unit, "could not place " + ident + " at " + x + ',' + y);
        return unit;
    }

    private static void select(Scene scene, Unit... units) {
        for (Unit existing : scene.world().unitsSnapshot()) {
            existing.setSelected(false);
        }
        for (Unit unit : units) {
            unit.setSelected(true);
        }
    }

    @Test
    @DisplayName("a selected peasant accepts a tree click and reaches the wood-work state")
    void treeClickBecomesDurableHarvestWork() {
        Scene scene = scene();
        Unit peasant = make(scene, "unit-peasant", 0, 5, 5);
        int treeX = 12;
        int treeY = 5;
        scene.world().map().field(treeX, treeY)
                .setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
        select(scene, peasant);

        long voices = scene.screen().soundChoicesForTest();
        scene.screen().commandSelectedForTest(treeX, treeY, null);

        assertEquals(Unit.Order.HARVEST, peasant.order(),
                "the click was acknowledged without installing BNE's resource order");
        assertEquals(1, scene.screen().soundChoicesForTest() - voices,
                "exactly the first unit that accepted the resource order should answer");

        boolean beganWork = false;
        for (int cycle = 0; cycle < 600; cycle++) {
            scene.world().tick();
            beganWork |= peasant.gatherClockStarted() || peasant.removed()
                    || peasant.carried() > 0;
            if (beganWork) {
                break;
            }
            assertEquals(Unit.Order.HARVEST, peasant.order(),
                    "the accepted tree order silently fell back to still at cycle "
                            + scene.world().cycle());
        }
        assertTrue(beganWork,
                "the peasant kept saying it was harvesting but never reached BNE's work state");
    }

    @Test
    @DisplayName("every selected fighter takes the same attack, including a construction site")
    void groupAttackDoesNotRotateOrDropMembers() {
        Scene scene = scene();
        Unit site = make(scene, "unit-farm", 1, 16, 12);
        site.setOrder(Unit.Order.UNDER_CONSTRUCTION);
        site.setHitPoints(80);
        Unit builder = make(scene, "unit-peon", 1, 15, 17);
        scene.world().restoreContained(builder, site, false, Unit.Order.STILL);

        List<Unit> squad = List.of(
                make(scene, "unit-footman", 0, 8, 10),
                make(scene, "unit-footman", 0, 8, 12),
                make(scene, "unit-archer", 0, 8, 14),
                make(scene, "unit-knight", 0, 10, 15));
        select(scene, squad.toArray(Unit[]::new));

        long voices = scene.screen().soundChoicesForTest();
        scene.screen().commandSelectedForTest(site.tileX(), site.tileY(), site);
        assertEquals(1, scene.screen().soundChoicesForTest() - voices,
                "one group command produced anything other than one acknowledgement");
        for (Unit unit : squad) {
            assertTrue(hasAcceptedAttack(unit, site),
                    unit.type().name() + " was left out of the selected-group attack");
        }

        boolean destroyed = false;
        boolean siteDeathAnnounced = false;
        List<Unit> engaged = new ArrayList<>();
        for (int cycle = 0; cycle < 1_200 && site.isAlive(); cycle++) {
            if (cycle > 0 && cycle % 60 == 0) {
                // Reissuing an attack in the middle of combat is ordinary
                // player input. It must replace the order for all four, not
                // advance through the selection one unit at a time.
                scene.screen().commandSelectedForTest(site.tileX(), site.tileY(), site);
            }
            for (Unit unit : squad) {
                if (unit.order() == Unit.Order.ATTACK && unit.target() == site
                        && !engaged.contains(unit)) {
                    engaged.add(unit);
                }
                assertTrue(engaged.contains(unit) || hasAcceptedAttack(unit, site),
                        unit.type().name() + " dropped a live commanded target at cycle "
                                + scene.world().cycle());
            }
            scene.world().tick();
            siteDeathAnnounced |= scene.world().drainSoundEvents().stream()
                    .anyMatch(event -> event.unit() == site
                            && !event.named() && "dead".equals(event.event()));
            destroyed = !site.isAlive();
        }
        assertTrue(destroyed, "a mixed BNE squad could not destroy the construction site");
        assertEquals(squad.size(), engaged.size(),
                "one or more queued group members never entered Attack");
        assertTrue(siteDeathAnnounced,
                "combat removed the construction site without its BNE building-death event");
        assertFalse(builder.removed(),
                "destroying the site left its builder trapped outside the map");
    }

    private static boolean hasAcceptedAttack(Unit unit, Unit target) {
        if (unit.order() == Unit.Order.ATTACK && unit.target() == target) {
            return true;
        }
        return unit.queuedOrders().stream().anyMatch(order ->
                order.kind() == Unit.QueuedOrderKind.ATTACK && order.target() == target);
    }

    @Test
    @DisplayName("retail's ordered nine-unit selection is the command fan-out order")
    void orderedSelectionIsBoundedAndDrivesEveryRecipient() {
        Scene scene = scene();
        List<Unit> made = new ArrayList<>();
        for (int index = 0; index < 11; index++) {
            made.add(make(scene, "unit-footman", 0,
                    3 + index % 4, 3 + index / 4));
        }
        List<Unit> requested = List.of(
                made.get(7), made.get(2), made.get(9), made.get(1), made.get(5),
                made.get(0), made.get(8), made.get(4), made.get(6), made.get(3), made.get(10));
        scene.screen().selectForTest(requested);

        List<Integer> expected = requested.subList(0, 9).stream().map(Unit::id).toList();
        assertEquals(expected, scene.screen().selectedIdsForTest(),
                "selection lost insertion order or exceeded the native nine-slot packet");

        scene.screen().commandSelectedForTest(24, 24, null);
        List<Integer> recipients = scene.screen().intentEntriesForTest().stream()
                .filter(entry -> entry.command() != null
                        && entry.command().kind() == GameCommand.Kind.MOVE)
                .map(entry -> entry.command().unitId()).toList();
        assertEquals(expected, recipients,
                "one click was not delivered in the ordered selection's exact order");
        for (Unit unit : requested.subList(0, 9)) {
            assertEquals(Unit.Order.MOVE, unit.order(),
                    unit.id() + " was selected but did not receive the move");
        }
        for (Unit unit : requested.subList(9, requested.size())) {
            assertFalse(unit.selected(), unit.id() + " survived beyond the nine-unit cap");
            assertEquals(Unit.Order.STILL, unit.order(),
                    unit.id() + " received a command outside the selection packet");
        }
    }

    @Test
    @DisplayName("a congested nine-unit move produces progress for every recipient")
    void congestedGroupMoveHasNoSilentNonParticipant() {
        Scene scene = scene();
        List<Unit> squad = new ArrayList<>();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                squad.add(make(scene, "unit-footman", 0,
                        4 + column, 4 + row));
            }
        }
        scene.screen().selectForTest(squad);
        java.util.Map<Integer, String> starts = new java.util.HashMap<>();
        for (Unit unit : squad) {
            starts.put(unit.id(), unit.tileX() + "," + unit.tileY());
        }

        scene.screen().commandSelectedForTest(24, 24, null);
        java.util.Set<Integer> progressed = new java.util.HashSet<>();
        for (int cycle = 0; cycle < 240 && progressed.size() < squad.size(); cycle++) {
            scene.world().tick();
            for (Unit unit : squad) {
                String now = unit.tileX() + "," + unit.tileY();
                if (!now.equals(starts.get(unit.id()))) {
                    progressed.add(unit.id());
                }
            }
        }
        assertEquals(squad.stream().map(Unit::id).collect(java.util.stream.Collectors.toSet()),
                progressed, "an accepted group order left a selected unit stationary");
    }
}
