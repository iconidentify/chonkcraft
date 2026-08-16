package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PlayerIntentJournalTest {

    @Test
    @DisplayName("a pre-wire refusal stays on the open transaction")
    void aPreWireRefusalStaysOnTheOpenTransaction() {
        PlayerIntentJournal journal = new PlayerIntentJournal();
        long transaction = journal.beginGesture(42, "command-panel", "train-unit",
                600, 400, -1, -1, "plain", 7, "building", List.of(7));
        journal.recordDecision(42, false, "train", false,
                "Not enough gold...mine more gold.");
        journal.recordFeedback(42, true, "voice",
                "Not enough gold...mine more gold.");
        journal.endGesture(transaction);

        List<PlayerIntentJournal.Decision> decisions = journal.decisionSnapshot();
        List<PlayerIntentJournal.Feedback> feedback = journal.feedbackSnapshot();
        assertEquals(1, decisions.size(), "the refused train must stay on the journal");
        assertEquals(transaction, decisions.getFirst().transactionId());
        assertEquals("train", decisions.getFirst().family());
        assertEquals(false, decisions.getFirst().accepted());
        assertEquals(false, decisions.getFirst().queued());
        assertEquals("Not enough gold...mine more gold.", decisions.getFirst().reason());
        assertEquals(1, feedback.size(), "retail Notify is the acknowledgement");
        assertEquals(true, feedback.getFirst().acknowledged());
        assertEquals("voice", feedback.getFirst().mode());
    }

    @Test
    @DisplayName("one group click keeps every fanned-out order in one transaction")
    void oneGroupClickKeepsEveryFannedOutOrderInOneTransaction() {
        PlayerIntentJournal journal = new PlayerIntentJournal();
        World world = world();
        java.util.ArrayList<GameCommand> delivered = new java.util.ArrayList<>();
        CommandSink recording = journal.wrap(delivered::add, world::cycle,
                () -> List.of(7, 9), world);

        long transaction = journal.beginGesture(42, "field", "right-click",
                320, 240, 12, 13, "shift", null, "open-ground", List.of(7, 9));
        try {
            recording.issue(GameCommand.move(0, 7, 12, 13).withQueued(true));
            recording.issue(GameCommand.move(0, 9, 12, 13).withQueued(true));
        } finally {
            journal.endGesture(transaction);
        }

        List<PlayerIntentJournal.Entry> entries = journal.snapshot();
        assertEquals(3, entries.size(), "the gesture and two orders must all be retained");
        assertEquals("gesture", entries.get(0).event());
        assertEquals("field", entries.get(0).gesture().origin());
        assertEquals("shift", entries.get(0).gesture().modifiers());
        assertEquals(transaction, entries.get(1).transactionId());
        assertEquals(transaction, entries.get(2).transactionId());
        assertTrue(entries.get(1).command().queued());
        assertTrue(entries.get(2).command().queued());
        assertEquals(0, entries.get(1).fanoutOrdinal(),
                "the first selected unit is fan-out ordinal zero");
        assertEquals(1, entries.get(2).fanoutOrdinal(),
                "the second selected unit is fan-out ordinal one");
    }

    @Test
    @DisplayName("a group click journals one voice and silent followers")
    void aGroupClickJournalsOneVoiceAndSilentFollowers() {
        PlayerIntentJournal journal = new PlayerIntentJournal();
        World world = world();
        CommandSink recording = journal.wrap(command -> { }, world::cycle,
                () -> List.of(7, 9), world);
        long transaction = journal.beginGesture(10, "field", "right-click",
                320, 240, 12, 13, "plain", null, "open-ground", List.of(7, 9));
        try {
            recording.issue(GameCommand.move(0, 7, 12, 13));
            journal.recordLastOrderFeedback(10, true, "voice", "ack");
            recording.issue(GameCommand.move(0, 9, 12, 13));
            journal.recordLastOrderFeedback(10, true, "silent", "group-suppressed");
            journal.recordAcceptedFanout(10, false);
        } finally {
            journal.endGesture(transaction);
        }
        List<PlayerIntentJournal.Feedback> feedback = journal.feedbackSnapshot();
        assertEquals(2, feedback.size(), "each fanned-out order keeps its acknowledgement");
        assertEquals("voice", feedback.get(0).mode());
        assertEquals(journal.snapshot().get(1).id(), feedback.get(0).intentId(),
                "the voice belongs to the first order, not the gesture");
        assertEquals("silent", feedback.get(1).mode());
        assertEquals("move", journal.decisionSnapshot().getFirst().family());
        assertEquals(true, journal.decisionSnapshot().getFirst().accepted());
    }

    @Test
    void recordsSelectionCommandAndAcceptanceWithoutChangingDelivery() {
        PlayerIntentJournal journal = new PlayerIntentJournal();
        AtomicLong cycle = new AtomicLong(42);
        java.util.ArrayList<GameCommand> delivered = new java.util.ArrayList<>();
        CommandSink destination = new CommandSink() {
            @Override
            public void issue(GameCommand command) {
                delivered.add(command);
            }

            @Override
            public boolean issueAccepted(GameCommand command) {
                delivered.add(command);
                return command.unitId() != 9;
            }
        };
        World world = world();
        CommandSink recording = journal.wrap(destination, cycle::get,
                () -> List.of(7, 9), world);
        journal.selection(cycle.get(), List.of(7, 9));
        recording.issueAccepted(GameCommand.move(0, 7, 12, 13));
        recording.issueAccepted(GameCommand.move(0, 9, 12, 13));

        assertEquals(2, delivered.size());
        List<PlayerIntentJournal.Entry> entries = journal.snapshot();
        assertEquals("selection", entries.get(0).event());
        assertEquals(List.of(7, 9), entries.get(1).selectedUnitIds());
        assertEquals(Boolean.TRUE, entries.get(1).accepted());
        assertEquals(Boolean.FALSE, entries.get(2).accepted());
    }

    @Test
    void remainsBoundedDuringLongPlay() {
        PlayerIntentJournal journal = new PlayerIntentJournal();
        for (int cycle = 0; cycle < PlayerIntentJournal.LIMIT + 40; cycle++) {
            journal.selection(cycle, List.of(cycle));
        }
        assertEquals(PlayerIntentJournal.LIMIT, journal.snapshot().size());
        assertTrue(journal.snapshot().get(0).cycle() > 0);
    }

    @Test
    void recordsProgressAndSettlementAfterAnAcceptedOrder() {
        World world = world();
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        Unit unit = world.createUnit(type, 0, 2, 2);
        PlayerIntentJournal journal = new PlayerIntentJournal();
        CommandSink sink = journal.wrap(CommandSink.local(
                new net.chonkbase.chonkcraft.engine.network.CommandApplier(
                        world, List.of(type))), world::cycle,
                () -> List.of(unit.id()), world);

        assertTrue(sink.issueAccepted(GameCommand.move(0, unit.id(), 4, 2)));
        for (int cycle = 0; cycle < 100; cycle++) {
            world.tick();
            journal.observe(world.cycle(), world);
        }

        PlayerIntentJournal.Outcome result = journal.outcomeSnapshot().getFirst();
        assertTrue(result.firstProgressCycle() != null,
                "movement must become visible downstream of acceptance");
        assertEquals("settled", result.terminalReason());
        assertEquals(4, result.tileX());
        assertEquals(2, result.tileY());
    }

    @Test
    void classifiesAnAcceptedCommandThatNeverChangesItsUnit() {
        World world = world();
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        Unit unit = world.createUnit(type, 0, 2, 2);
        PlayerIntentJournal journal = new PlayerIntentJournal();
        CommandSink sink = journal.wrap(command -> { }, world::cycle,
                () -> List.of(unit.id()), world);

        sink.issueAccepted(GameCommand.move(0, unit.id(), 4, 2));
        journal.observe(PlayerIntentJournal.OUTCOME_WINDOW, world);

        PlayerIntentJournal.Outcome result = journal.outcomeSnapshot().getFirst();
        assertEquals("acknowledged-no-progress", result.terminalReason());
        assertEquals(null, result.firstProgressCycle());
    }

    @Test
    @DisplayName("an already-touching blocked click settles on the issue cycle")
    void anAlreadyTouchingBlockedClickSettlesOnTheIssueCycle() {
        World world = world();
        UnitType workerType = movable("unit-peon");
        UnitType mineType = building("unit-gold-mine", 3, 3);
        Unit worker = world.createUnit(workerType, 0, 5, 6);
        world.createUnit(mineType, 15, 6, 6);
        PlayerIntentJournal journal = new PlayerIntentJournal();
        CommandSink sink = journal.wrap(command -> { }, world::cycle,
                () -> List.of(worker.id()), world);

        sink.issueAccepted(GameCommand.move(0, worker.id(), 7, 7));
        PlayerIntentJournal.Outcome outcome = journal.outcomeSnapshot().getFirst();
        assertEquals("settled", outcome.terminalReason(),
                "standing on the mine's edge must close the click immediately");
        assertEquals(Long.valueOf(0), outcome.terminalCycle(),
                "the native adapter snapshots that Still on the issue cycle");
    }

    @Test
    @DisplayName("a worker who walks to mend stands still as fulfilled")
    void aWorkerWhoWalksToMendStandsStillAsFulfilled() {
        World world = world();
        UnitType workerType = movable("unit-peon");
        workerType.setRepairRange(1);
        UnitType hallType = building("unit-great-hall", 4, 4);
        Unit worker = world.createUnit(workerType, 0, 1, 1);
        Unit hall = world.createUnit(hallType, 0, 4, 4);
        hall.setHitPoints(200);
        PlayerIntentJournal journal = new PlayerIntentJournal();
        CommandSink sink = journal.wrap(command -> {
            worker.setOrder(Unit.Order.REPAIR);
            worker.setTarget(hall);
        }, world::cycle, () -> List.of(worker.id()), world);

        sink.issueAccepted(GameCommand.repair(0, worker.id(), hall.id()));
        worker.setTile(3, 4);
        worker.setOrder(Unit.Order.STILL);
        journal.observe(8, world);

        PlayerIntentJournal.Outcome outcome = journal.outcomeSnapshot().getFirst();
        assertEquals("fulfilled", outcome.terminalReason(),
                "walking to the hall and standing still must fulfill the mend");
    }

    @Test
    void alreadyTouchingABlockedMoveGoalIsARealSettlement() {
        World world = world();
        UnitType workerType = movable("unit-peon");
        UnitType mineType = building("unit-gold-mine", 3, 3);
        Unit worker = world.createUnit(workerType, 0, 5, 6);
        world.createUnit(mineType, 15, 6, 6);
        PlayerIntentJournal journal = new PlayerIntentJournal();
        CommandSink sink = journal.wrap(command -> { }, world::cycle,
                () -> List.of(worker.id()), world);

        sink.issueAccepted(GameCommand.move(0, worker.id(), 7, 7));
        journal.observe(1, world);

        PlayerIntentJournal.Outcome outcome = journal.outcomeSnapshot().getFirst();
        assertEquals(null, outcome.firstProgressCycle(),
                "the worker was already at the valid edge");
        assertEquals("settled", outcome.terminalReason(),
                "BNE accepts a point inside the mine and settles at its edge");
    }

    @Test
    void commandLabelAloneIsNotPhysicalProgress() {
        World world = world();
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        Unit unit = world.createUnit(type, 0, 2, 2);
        PlayerIntentJournal journal = new PlayerIntentJournal();
        CommandSink sink = journal.wrap(CommandSink.local(
                new net.chonkbase.chonkcraft.engine.network.CommandApplier(
                        world, List.of(type))), world::cycle,
                () -> List.of(unit.id()), world);

        assertTrue(sink.issueAccepted(GameCommand.move(0, unit.id(), 4, 2)));
        journal.observe(world.cycle(), world);

        assertEquals(null, journal.outcomeSnapshot().getFirst().firstProgressCycle(),
                "accepting a MOVE label is not proof the unit moved");
        for (int tick = 0; tick < 20
                && journal.outcomeSnapshot().getFirst().firstProgressCycle() == null;
                tick++) {
            world.tick();
            journal.observe(world.cycle(), world);
        }
        assertTrue(journal.outcomeSnapshot().getFirst().firstProgressCycle() != null,
                "the first changed pixel is physical progress");
    }

    @Test
    void acceptedBuildLabelWithoutAFoundationIsReportedAsNoProgress() {
        World world = world();
        UnitType workerType = movable("unit-peasant");
        UnitType farm = building("unit-farm", 2, 2);
        Unit worker = world.createUnit(workerType, 0, 2, 2);
        PlayerIntentJournal journal = new PlayerIntentJournal();
        CommandSink sink = journal.wrap(command -> {
            worker.setOrder(Unit.Order.BUILD);
            worker.setPendingBuild(farm);
            worker.setBuildTile(command.x(), command.y());
        }, world::cycle, () -> List.of(worker.id()), world);

        sink.issueAccepted(GameCommand.build(0, worker.id(), 1, 6, 6));
        journal.observe(PlayerIntentJournal.OUTCOME_WINDOW, world);

        PlayerIntentJournal.Outcome outcome = journal.outcomeSnapshot().getFirst();
        assertEquals(null, outcome.firstProgressCycle(),
                "a Java order label must not masquerade as a visible build");
        assertEquals("acknowledged-no-progress", outcome.terminalReason());
    }

    @Test
    void buildIsFulfilledOnlyByItsRequestedFoundation() {
        World world = world();
        UnitType workerType = movable("unit-peasant");
        UnitType farm = building("unit-farm", 2, 2);
        Unit worker = world.createUnit(workerType, 0, 2, 2);
        PlayerIntentJournal journal = new PlayerIntentJournal();
        CommandSink sink = journal.wrap(command -> {
            worker.setOrder(Unit.Order.BUILD);
            worker.setPendingBuild(farm);
            worker.setBuildTile(command.x(), command.y());
        }, world::cycle, () -> List.of(worker.id()), world);

        sink.issueAccepted(GameCommand.build(0, worker.id(), 1, 6, 6));
        Unit unrelated = world.createUnit(farm, 0, 8, 8);
        worker.setWorksite(unrelated);
        journal.observe(1, world);
        assertEquals(null, journal.outcomeSnapshot().getFirst().terminalReason(),
                "a different foundation cannot fulfill this click");

        Unit requested = world.createUnit(farm, 0, 6, 6);
        worker.setWorksite(requested);
        journal.observe(2, world);
        PlayerIntentJournal.Outcome outcome = journal.outcomeSnapshot().getFirst();
        assertEquals("fulfilled", outcome.terminalReason());
        assertEquals(Long.valueOf(2), outcome.firstProgressCycle());
    }

    @Test
    @DisplayName("still leftover bob is not a walk")
    void stillLeftoverBobIsNotAWalk() {
        World world = world();
        UnitType type = movable("unit-footman");
        Unit unit = world.createUnit(type, 0, 2, 2);
        PlayerIntentJournal journal = new PlayerIntentJournal();
        CommandSink sink = journal.wrap(command -> { }, world::cycle,
                () -> List.of(unit.id()), world);

        sink.issueAccepted(GameCommand.move(0, unit.id(), 6, 2));
        unit.setOffset(4, 0);
        journal.observe(1, world);

        assertEquals(null, journal.outcomeSnapshot().getFirst().firstProgressCycle(),
                "a Still leftover bob must not count as the first walk pixel");
    }

    @Test
    @DisplayName("a worker inside a mine is still alive")
    void aWorkerInsideAMineIsStillAlive() {
        World world = world();
        UnitType workerType = movable("unit-peon");
        UnitType mineType = building("unit-gold-mine", 3, 3);
        Unit worker = world.createUnit(workerType, 0, 5, 6);
        Unit mine = world.createUnit(mineType, 15, 6, 6);
        PlayerIntentJournal journal = new PlayerIntentJournal();
        CommandSink sink = journal.wrap(command -> {
            worker.setOrder(Unit.Order.HARVEST);
            worker.setWorksite(mine);
            worker.setRemoved(true);
        }, world::cycle, () -> List.of(worker.id()), world);

        sink.issueAccepted(GameCommand.harvest(0, worker.id(), 7, 7));
        journal.observe(1, world);

        PlayerIntentJournal.Outcome outcome = journal.outcomeSnapshot().getFirst();
        assertTrue(outcome.alive(),
                "a peon who walked into the mine is off the map, not dead");
        assertTrue(!outcome.onMap(),
                "going inside a mine must take the hull off the map");
    }

    @Test
    void aReplacementOrderCannotDonateItsProgressToTheOldOne() {
        World world = world();
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        Unit unit = world.createUnit(type, 0, 2, 2);
        PlayerIntentJournal journal = new PlayerIntentJournal();
        CommandSink sink = journal.wrap(CommandSink.local(
                new net.chonkbase.chonkcraft.engine.network.CommandApplier(
                        world, List.of(type))), world::cycle,
                () -> List.of(unit.id()), world);

        assertTrue(sink.issueAccepted(GameCommand.move(0, unit.id(), 4, 2)));
        assertTrue(sink.issueAccepted(GameCommand.move(0, unit.id(), 7, 2)));
        for (int cycle = 0; cycle < 10; cycle++) {
            world.tick();
            journal.observe(world.cycle(), world);
        }

        List<PlayerIntentJournal.Outcome> outcomes = journal.outcomeSnapshot();
        assertEquals("superseded", outcomes.get(0).terminalReason());
        assertEquals(Long.valueOf(0), outcomes.get(0).terminalCycle());
        assertTrue(outcomes.get(1).firstProgressCycle() != null);
    }

    @Test
    @DisplayName("a stop click on a standing unit is acknowledged without progress")
    void aStopClickOnAStandingUnitIsAcknowledgedWithoutProgress() {
        World world = world();
        UnitType type = movable("unit-peon");
        Unit unit = world.createUnit(type, 0, 2, 2);
        assertEquals(Unit.Order.STILL, unit.order(),
                "the peon must already be standing");
        PlayerIntentJournal journal = new PlayerIntentJournal();
        CommandSink sink = journal.wrap(CommandSink.local(
                new net.chonkbase.chonkcraft.engine.network.CommandApplier(
                        world, List.of(type))), world::cycle,
                () -> List.of(unit.id()), world);

        assertTrue(sink.issueAccepted(GameCommand.stop(0, unit.id())),
                "GiveOrder Stop on a standing peon must be accepted");
        journal.observe(0, world);
        assertEquals(null, journal.outcomeSnapshot().getFirst().terminalReason(),
                "retail does not fulfill a Still-on-Still Stop on the issue cycle");

        journal.observe(PlayerIntentJournal.OUTCOME_WINDOW, world);
        PlayerIntentJournal.Outcome outcome = journal.outcomeSnapshot().getFirst();
        assertEquals("acknowledged-no-progress", outcome.terminalReason(),
                "a Stop that never leaves Still is acknowledged without progress, not "
                        + outcome.terminalReason());
        assertEquals(null, outcome.firstProgressCycle(),
                "Still-on-Still has no order change to count as progress");
    }

    @Test
    @DisplayName("a leftover stop settles the walk when the leftover lands")
    void aLeftoverStopSettlesTheWalkWhenTheLeftoverLands() {
        World world = world();
        UnitType type = movable("unit-footman");
        Unit unit = world.createUnit(type, 0, 2, 2);
        PlayerIntentJournal journal = new PlayerIntentJournal();
        CommandSink sink = journal.wrap(CommandSink.local(
                new net.chonkbase.chonkcraft.engine.network.CommandApplier(
                        world, List.of(type))), world::cycle,
                () -> List.of(unit.id()), world);

        assertTrue(sink.issueAccepted(GameCommand.move(0, unit.id(), 8, 2)));
        boolean leftover = false;
        for (int cycle = 0; cycle < 40; cycle++) {
            world.tick();
            journal.observe(world.cycle(), world);
            if (unit.isMoving() || unit.offsetX() != 0 || unit.offsetY() != 0) {
                leftover = true;
                break;
            }
        }
        assertTrue(leftover,
                "the walk must still have leftover pixels when Stop lands");
        assertTrue(sink.issueAccepted(GameCommand.stop(0, unit.id())));
        for (int cycle = 0; cycle < 40; cycle++) {
            world.tick();
            journal.observe(world.cycle(), world);
        }

        List<PlayerIntentJournal.Outcome> outcomes = journal.outcomeSnapshot();
        assertEquals("settled", outcomes.get(0).terminalReason(),
                "retail leftover Stop settles the walk when leftover lands, not "
                        + outcomes.get(0).terminalReason());
        assertEquals("fulfilled", outcomes.get(1).terminalReason(),
                "the Stop itself fulfills when leftover lands, not "
                        + outcomes.get(1).terminalReason());
        assertEquals(outcomes.get(0).terminalCycle(), outcomes.get(1).terminalCycle(),
                "the walk and the Stop finish together when leftover lands");
    }

    private static World world() {
        GameMap map = new GameMap(12, 12, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return new World(map);
    }

    private static UnitType movable(String ident) {
        UnitType type = new UnitType(ident);
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        return type;
    }

    private static UnitType building(String ident, int width, int height) {
        UnitType type = new UnitType(ident);
        type.setTileSize(width, height);
        type.setHitPoints(400);
        type.setBuilding(true);
        return type;
    }
}
