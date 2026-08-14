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
import org.junit.jupiter.api.Test;

class PlayerIntentJournalTest {

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
    void capturesTheStateBeforeImmediateLocalDelivery() {
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

        assertEquals(Long.valueOf(0),
                journal.outcomeSnapshot().getFirst().firstProgressCycle(),
                "the order transition itself is the first causal progress");
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

    private static World world() {
        GameMap map = new GameMap(12, 12, new Tileset());
        for (int y = 0; y < map.height(); y++) {
            for (int x = 0; x < map.width(); x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return new World(map);
    }
}
