package net.chonkbase.chonkcraft.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
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
        CommandSink recording = journal.wrap(destination, cycle::get, () -> List.of(7, 9));
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
}
