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
        return scene(data, world, 0);
    }

    private static Scene scene(GameData data, World world, int person) {
        world.fog().revealAll(person);

        CommandApplier applier = new CommandApplier(world,
                new ArrayList<>(data.unitTypes().types().values()));
        data.configureCommands(applier);
        CommandPanel commandPanel = new CommandPanel(world, data, data.userInterface("summer"),
                data.upgrades().dependencies(), person, "summer", "human",
                data.unitTypes().types(), data.uiLayout("human", 800, 600));
        GameScreen screen = new GameScreen(world, data,
                new BufferedImage(SIZE * Unit.TILE_PIXELS, SIZE * Unit.TILE_PIXELS,
                        BufferedImage.TYPE_INT_RGB),
                data.loadTileset(PudMap.Tileset.FOREST).palette(), "summer", person,
                800, 600, new net.chonkbase.chonkcraft.engine.sound.GameAudio(data.sounds()),
                null, commandPanel, applier, CommandSink.local(applier), List.of(), "human");
        screen.setSize(800, 600);
        screen.setLayout((net.chonkbase.chonkcraft.engine.ui.UiLayout.Layout) null);
        screen.setGameScale(1);
        return new Scene(screen, world, data);
    }

    @Test
    @DisplayName("a laden worker's resource clicks replace the delivery walk without converting its gold")
    void aLadenWorkersResourceClicksReplaceTheDeliveryWalkWithoutConvertingItsGold() {
        AssetSource source = AssetSource.fromEnvironment();
        Assumptions.assumeTrue(source != null,
                "No authenticated BNE asset pack configured (-Dchonkcraft.pack). ");
        GameData data = new GameData(source);
        String map = "campaigns/orc/level01o";
        int person = GameData.personIn(data.campaignMap(map));
        var mission = data.loadMission(map, person, 1);
        Scene scene = scene(data, mission.world(), person);
        Unit worker = mission.world().unitAt(25, 18);
        Unit mine = mission.world().unitAt(26, 13);
        assertNotNull(worker, "Orc 1 must provide the captured peon");
        assertNotNull(mine, "Orc 1 must provide the captured gold mine");
        mission.tick();
        mission.tick();
        scene.screen().selectForTest(worker);
        for (int cycle = 1; cycle <= 1800; cycle++) {
            if (cycle == 5 || cycle == 251) {
                scene.screen().selectForTest(worker);
                scene.screen().fieldRightClickForTest(26, 13, mine);
            }
            if (cycle == 250 || cycle == 270) {
                scene.screen().selectForTest(worker);
                scene.screen().fieldRightClickForTest(28, 18, null);
            }
            mission.tick();
            if (cycle >= 249) {
                assertEquals(100, worker.carried(), "the new route must retain the full gold load at " + cycle);
                assertEquals(UnitType.Resource.GOLD, worker.heldResource(),
                        "clicking trees cannot turn carried gold into wood at " + cycle);
                assertEquals(1000, mission.world().player(person).get(UnitType.Resource.GOLD),
                        "the cancelled delivery must not deposit gold at " + cycle);
            }
            // cargo-switch-v2-gold-20260909: the loaded c250/c251 clicks
            // leave action 24 at c259, then start the new stride at c262.
            if (cycle == 259) {
                assertEquals(Unit.Order.MOVE, worker.order(),
                        "Move must replace the delivery when its committed stride finishes");
                assertEquals(List.of(24, 16, 768, 512),
                        List.of(worker.tileX(), worker.tileY(), worker.pixelX(), worker.pixelY()),
                        "the worker must finish the old stride before changing route");
            }
            if (cycle == 262) {
                assertEquals(List.of(25, 15, 768, 512),
                        List.of(worker.tileX(), worker.tileY(), worker.pixelX(), worker.pixelY()),
                        "the new move must begin on the native constructor callback");
            }
        }
        assertEquals(Unit.Order.STILL, worker.order(),
                "the worker must finish the final move without restarting its cancelled delivery");
        var orders = scene.screen().intentEntriesForTest().stream()
                .filter(entry -> "order".equals(entry.event())).toList();
        assertEquals(List.of(GameCommand.Kind.HARVEST, GameCommand.Kind.MOVE,
                        GameCommand.Kind.MOVE, GameCommand.Kind.MOVE),
                orders.stream().map(entry -> entry.command().kind()).toList(),
                "native resource clicks choose Harvest only before the worker has a load");
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
    @DisplayName("switching a loaded worker between wood and gold preserves the load on screen")
    void switchingALoadedWorkerBetweenWoodAndGoldPreservesTheLoadOnScreen() throws Exception {
        for (String worker : List.of("peon", "peasant")) {
            for (UnitType.Resource cargo : List.of(UnitType.Resource.WOOD, UnitType.Resource.GOLD)) {
                Scene scene = scene();
                Unit unit = make(scene, "unit-" + worker, 0, 5, 5);
                Unit mine = make(scene, "unit-gold-mine", 15, 20, 5);
                scene.world().map().field(20, 12).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
                unit.setCarrying(cargo == UnitType.Resource.WOOD
                        ? UnitType.Resource.GOLD : UnitType.Resource.WOOD);
                unit.setHeldResource(cargo);
                unit.setCarried(100);
                scene.screen().selectForTest(unit);
                String race = worker.equals("peon") ? "orc" : "human";
                String sprite = race + "/units/" + worker + "_with_"
                        + (cargo == UnitType.Resource.WOOD ? "wood" : "gold") + ".png";
                var drawSheet = GameScreen.class.getDeclaredMethod("workerSprite", Unit.class, UnitType.class);
                drawSheet.setAccessible(true);
                // BNE 0x43697e preserves the lower six cargo flag bits while
                // replacing the gathering job in the upper two. The field
                // used to select its loaded sheet from the new job instead.
                for (int click = 0; click < 6; click++) {
                    boolean gold = click % 2 == 0;
                    scene.screen().fieldRightClickForTest(20, gold ? 5 : 12, gold ? mine : null);
                    assertEquals(sprite, drawSheet.invoke(scene.screen(), unit, unit.type()),
                            worker + " must keep displaying its original load after resource click " + click);
                    assertEquals(100, unit.carried(), "a click cannot create or consume cargo");
                    var available = new net.chonkbase.chonkcraft.engine.ui.ButtonAvailability(
                            scene.world(), unit, null, false);
                    var resourceButtons = scene.data().userInterface("summer").buttons().all().stream()
                            .filter(button -> button.appliesTo(unit.type().ident()))
                            .filter(button -> "return-goods".equals(button.action())).toList();
                    assertEquals(1, resourceButtons.size(), "each worker race must define one delivery button");
                    assertTrue(available.test(resourceButtons.getFirst()),
                            "a loaded worker must retain its delivery command after a resource click");
                }
                var orders = scene.screen().intentEntriesForTest().stream()
                        .filter(entry -> "order".equals(entry.event()))
                        .toList();
                assertEquals(6, orders.size(), "every resource click must produce one worker command");
                for (var order : orders) {
                    assertEquals(GameCommand.Kind.MOVE, order.command().kind(),
                            "BNE moves a loaded worker on a resource click without assigning Harvest");
                    assertTrue(order.accepted(), "the loaded worker must accept each resource click");
                }
            }
        }
    }

    @Test
    @DisplayName("unfinished chopping cannot be delivered and a new harvesting job clears it")
    void unfinishedChoppingCannotBeDeliveredAndANewHarvestingJobClearsIt() throws Exception {
        for (String worker : List.of("peon", "peasant")) {
            Scene scene = scene();
            Unit unit = make(scene, "unit-" + worker, 0, 5, 5);
            Unit mine = make(scene, "unit-gold-mine", 15, 20, 5);
            scene.world().map().field(6, 5).setFlags(TileFlag.FOREST | TileFlag.UNPASSABLE);
            scene.world().map().field(6, 5).setValue(100);
            scene.screen().selectForTest(unit);
            scene.screen().fieldRightClickForTest(6, 5, null);
            for (int cycle = 0; cycle < 250 && unit.carried() == 0; cycle++) scene.world().tick();
            assertTrue(unit.carried() > 0 && unit.carried() < 100,
                    "the worker must have made chopping progress without completing a load");
            var sprite = GameScreen.class.getDeclaredMethod("workerSprite", Unit.class, UnitType.class);
            sprite.setAccessible(true);
            String race = worker.equals("peon") ? "orc" : "human";
            assertEquals(race + "/units/" + worker + ".png",
                    sprite.invoke(scene.screen(), unit, unit.type()),
                    "unfinished chopping must use the empty worker sheet");
            var available = new net.chonkbase.chonkcraft.engine.ui.ButtonAvailability(
                    scene.world(), unit, null, false);
            var resourceButtons = scene.data().userInterface("summer").buttons().all().stream()
                    .filter(button -> button.appliesTo(unit.type().ident()))
                    .filter(button -> "return-goods".equals(button.action())).toList();
            assertEquals(1, resourceButtons.size(), "each worker race must define one delivery button");
            assertFalse(available.test(resourceButtons.getFirst()),
                    "unfinished chopping must not offer a delivery command that will be refused");
            CommandApplier applier = new CommandApplier(scene.world(),
                    new ArrayList<>(scene.data().unitTypes().types().values()));
            scene.data().configureCommands(applier);
            Unit.PendingOrderState pending = unit.snapshotPendingOrders();
            assertFalse(applier.apply(GameCommand.returnGoods(0, unit.id())),
                    "native Return Goods requires a completed load, not chopping progress");
            assertEquals(pending, unit.snapshotPendingOrders(),
                    "the refused return must preserve the worker's harvesting order");
            scene.screen().fieldRightClickForTest(mine.tileX(), mine.tileY(), mine);
            assertEquals(0, unit.carried(),
                    "native 0x436960 clears unfinished chopping when the new Harvest is accepted");
            var orders = scene.screen().intentEntriesForTest().stream()
                    .filter(entry -> "order".equals(entry.event())).toList();
            assertEquals(2, orders.size(), "both empty worker resource clicks must issue a command");
            assertEquals(GameCommand.Kind.HARVEST, orders.getLast().command().kind(),
                    "a worker without a completed load must accept the new mining job");
        }
    }

    @Test
    @DisplayName("a wood carrier reassigned to gold can still deliver wood to a lumber mill")
    void aWoodCarrierReassignedToGoldCanStillDeliverWoodToALumberMill() {
        for (String worker : List.of("peon", "peasant")) {
            Scene scene = scene();
            Unit unit = make(scene, "unit-" + worker, 0, 5, 5);
            Unit mine = make(scene, "unit-gold-mine", 15, 20, 5);
            Unit mill = make(scene, worker.equals("peon")
                    ? "unit-troll-lumber-mill" : "unit-elven-lumber-mill", 0, 5, 12);
            unit.setCarrying(UnitType.Resource.WOOD);
            unit.setHeldResource(UnitType.Resource.WOOD);
            unit.setCarried(100);
            scene.screen().selectForTest(unit);
            int wood = scene.world().player(0).get(UnitType.Resource.WOOD);
            int gold = scene.world().player(0).get(UnitType.Resource.GOLD);
            scene.screen().fieldRightClickForTest(mine.tileX(), mine.tileY(), mine);
            scene.screen().fieldRightClickForTest(mill.tileX(), mill.tileY(), mill);
            for (int cycle = 0; cycle < 900 && unit.carried() > 0; cycle++) scene.world().tick();
            assertEquals(0, unit.carried(), "the lumber mill click must complete a delivery");
            assertEquals(wood + 100, scene.world().player(0).get(UnitType.Resource.WOOD),
                    "the reassigned worker must bank its original wood exactly once");
            assertEquals(gold, scene.world().player(0).get(UnitType.Resource.GOLD),
                    "switching the worker's job must never turn delivered wood into gold");
        }
    }

    @Test
    @DisplayName("return goods sends the loaded worker home and preserves its empty companions' orders")
    void returnGoodsSendsOnlyTheLoadedWorkerHome() {
        Scene scene = scene();
        make(scene, "unit-town-hall", 0, 4, 18);
        Unit loaded = make(scene, "unit-peasant", 0, 5, 5);
        Unit empty = make(scene, "unit-peasant", 0, 7, 5);
        Unit soldier = make(scene, "unit-footman", 0, 9, 5);
        scene.screen().selectForTest(List.of(loaded, empty, soldier));
        scene.screen().fieldRightClickForTest(14, 5, null);
        loaded.setCarrying(UnitType.Resource.GOLD);
        loaded.setHeldResource(UnitType.Resource.GOLD);
        loaded.setCarried(100);
        Unit.Order emptyOrder = empty.order();
        Unit.Order soldierOrder = soldier.order();
        Unit.PendingOrderState emptyPending = empty.snapshotPendingOrders();
        Unit.PendingOrderState soldierPending = soldier.snapshotPendingOrders();
        var button = scene.data().userInterface("summer").buttons().all().stream()
                .filter(b -> "return-goods".equals(b.action()) && b.appliesTo("unit-peasant"))
                .findFirst().orElseThrow();
        int gold = scene.world().player(0).get(UnitType.Resource.GOLD);

        // Native 0x475f80 filters each selected recipient before GiveOrder:
        // type flags & 0x300 and cargo flag +0x75 & 0x20 are both required.
        scene.screen().press(button, false);

        assertEquals(emptyOrder, empty.order(), "the empty peasant must retain its walk");
        assertEquals(soldierOrder, soldier.order(), "a footman must never be sent into a depot");
        assertEquals(emptyPending, empty.snapshotPendingOrders(),
                "the empty peasant's pending move must survive the group button");
        assertEquals(soldierPending, soldier.snapshotPendingOrders(),
                "the soldier's pending move must survive the group button");
        for (int cycle = 0; cycle < 900 && loaded.carried() > 0; cycle++) {
            scene.world().tick();
        }
        assertEquals(0, loaded.carried(), "the eligible worker must complete the delivery");
        assertEquals(gold + 100, scene.world().player(0).get(UnitType.Resource.GOLD),
                "the group button must bank the loaded worker's hundred gold exactly once");
    }

    @Test
    @DisplayName("attack ground leaves the selected infantry and worker on their previous route")
    void attackGroundLeavesTheSelectedInfantryAndWorkerOnTheirPreviousRoute() {
        Scene scene = scene();
        Unit ballista = make(scene, "unit-ballista", 0, 5, 5);
        Unit footman = make(scene, "unit-footman", 0, 8, 5);
        Unit peasant = make(scene, "unit-peasant", 0, 10, 5);
        scene.screen().selectForTest(List.of(ballista, footman, peasant));
        scene.screen().fieldRightClickForTest(16, 6, null);
        Unit.PendingOrderState footmanPending = footman.snapshotPendingOrders();
        Unit.PendingOrderState peasantPending = peasant.snapshotPendingOrders();
        Unit.Order footmanOrder = footman.order();
        Unit.Order peasantOrder = peasant.order();
        var button = scene.data().userInterface("summer").buttons().all().stream()
                .filter(b -> "attack-ground".equals(b.action()) && b.appliesTo("unit-ballista"))
                .findFirst().orElseThrow();
        scene.screen().press(button, false);
        var click = new java.awt.event.MouseEvent(scene.screen(),
                java.awt.event.MouseEvent.MOUSE_PRESSED, 0,
                java.awt.event.InputEvent.BUTTON1_DOWN_MASK,
                13 * Unit.TILE_PIXELS + 16, 10 * Unit.TILE_PIXELS + 16,
                1, false, java.awt.event.MouseEvent.BUTTON1);
        for (var listener : scene.screen().getMouseListeners()) {
            listener.mousePressed(click);
        }
        assertEquals(footmanOrder, footman.order(), "the footman must keep its previous walk");
        assertEquals(peasantOrder, peasant.order(), "the worker must keep its previous walk");
        assertEquals(footmanPending, footman.snapshotPendingOrders(),
                "the ground shot must not replace the footman's pending move");
        assertEquals(peasantPending, peasant.snapshotPendingOrders(),
                "the ground shot must not replace the peasant's pending move");
        boolean shot = false;
        for (int cycle = 0; cycle < 400 && !shot; cycle++) {
            scene.world().tick();
            shot = !scene.world().missiles().isEmpty();
        }
        assertTrue(shot, "the eligible ballista must fire at the clicked ground");
    }

    @Test
    @DisplayName("a footman's wall click uses ordinary attack and damages the wall")
    void aFootmansWallClickUsesOrdinaryAttackAndDamagesTheWall() {
        Scene scene = scene();
        Unit footman = make(scene, "unit-footman", 0, 10, 10);
        var wall = scene.world().map().field(12, 10);
        wall.setFlags(TileFlag.LAND_ALLOWED | TileFlag.WALL | TileFlag.UNPASSABLE);
        wall.setValue(GameMap.WALL_HIT_POINTS);
        scene.screen().selectForTest(footman);
        scene.screen().fieldRightClickForTest(12, 10, null);
        boolean damaged = false;
        for (int cycle = 0; cycle < 500 && !damaged; cycle++) {
            scene.world().tick();
            damaged = !wall.isWall() || wall.value() < GameMap.WALL_HIT_POINTS;
        }
        assertTrue(damaged,
                "a wall attack must remain available to infantry when artillery orders are refused");
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
        // This is a command-delivery/combat fixture, not a construction-rate
        // fixture. Keep its authored 80-HP frame from entering the ordinary
        // construction completion path: a hand-made site has no progress
        // goal, so its first Built callback would otherwise treat progress
        // zero as complete and refill it to the farm's full 400 HP.
        site.setBattleNetOrderDelay(10_000);
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
        for (int cycle = 0; cycle < 2_400 && site.isAlive(); cycle++) {
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
        assertTrue(destroyed, () -> "a mixed BNE squad could not destroy the "
                + "construction site; hp=" + site.hitPoints() + " squad="
                + squad.stream().map(unit -> unit.id() + ":"
                        + unit.type().ident() + "@" + unit.tileX() + ","
                        + unit.tileY() + "/" + unit.order() + "/target="
                        + (unit.target() == null ? -1 : unit.target().id()))
                        .toList());
        assertEquals(squad.size(), engaged.size(),
                "one or more queued group members never entered Attack");
        assertTrue(siteDeathAnnounced,
                "combat removed the construction site without its BNE building-death event");
        assertFalse(builder.isAlive() && builder.removed(),
                "destroying the site left a live builder trapped outside the map");
    }

    private static boolean hasAcceptedAttack(Unit unit, Unit target) {
        if (unit.order() == Unit.Order.ATTACK && unit.target() == target) {
            return true;
        }
        return unit.queuedOrders().stream().anyMatch(order ->
                order.kind() == Unit.QueuedOrderKind.ATTACK && order.target() == target);
    }

    @Test
    @DisplayName("a field right-click journals the gesture, voice and one move")
    void aFieldRightClickJournalsTheGestureVoiceAndOneMove() {
        Scene scene = scene();
        Unit footman = make(scene, "unit-footman", 0, 5, 5);
        scene.screen().selectForTest(List.of(footman));
        scene.screen().fieldRightClickForTest(12, 8, null);

        List<PlayerIntentJournal.Entry> entries = scene.screen().intentEntriesForTest();
        PlayerIntentJournal.Entry gesture = entries.stream()
                .filter(entry -> "gesture".equals(entry.event()))
                .findFirst().orElseThrow();
        assertEquals("field", gesture.gesture().origin(),
                "the mouse handler starts a field transaction before fan-out");
        assertEquals("open-ground", gesture.gesture().targetShape(),
                "empty land is open-ground after the click, not before it");
        PlayerIntentJournal.Entry order = entries.stream()
                .filter(entry -> "order".equals(entry.event()))
                .findFirst().orElseThrow();
        assertEquals(0, order.fanoutOrdinal());
        assertEquals(GameCommand.Kind.MOVE, order.command().kind());
        List<PlayerIntentJournal.Feedback> feedback =
                scene.screen().intentFeedbackForTest();
        assertEquals(1, feedback.size(), "one selected footman keeps the voice");
        assertEquals("voice", feedback.getFirst().mode());
        assertEquals(order.id(), feedback.getFirst().intentId());
        assertEquals("move", scene.screen().intentDecisionsForTest().getFirst().family());
    }

    @Test
    @DisplayName("a field right-click does not promise that a cannon tower will move")
    void aFieldRightClickDoesNotMoveAnImmobileBuilding() {
        Scene scene = scene();
        Unit tower = make(scene, "unit-human-cannon-tower", 0, 5, 5);
        scene.screen().selectForTest(List.of(tower));

        scene.screen().fieldRightClickForTest(12, 8, null);

        assertTrue(scene.screen().intentEntriesForTest().stream()
                        .noneMatch(entry -> entry.command() != null),
                "an immobile building must not emit a Move packet");
        assertTrue(scene.screen().intentFeedbackForTest().isEmpty(),
                "the tower must not acknowledge an impossible order");
        assertTrue(scene.screen().intentDecisionsForTest().isEmpty(),
                "an ignored building click is not an accepted command");
        assertEquals(Unit.Order.STILL, tower.order());
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

        scene.screen().fieldRightClickForTest(24, 24, null);
        List<Integer> recipients = scene.screen().intentEntriesForTest().stream()
                .filter(entry -> entry.command() != null
                        && entry.command().kind() == GameCommand.Kind.MOVE)
                .map(entry -> entry.command().unitId()).toList();
        assertEquals(expected, recipients,
                "one click was not delivered in the ordered selection's exact order");
        for (Unit unit : requested.subList(0, 9)) {
            assertTrue(unit.order() == Unit.Order.MOVE
                            || unit.hasQueuedOrders()
                            || unit.queuedReplacementPending(),
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
