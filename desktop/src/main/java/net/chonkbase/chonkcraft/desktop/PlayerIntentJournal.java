package net.chonkbase.chonkcraft.desktop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.chonkbase.chonkcraft.engine.network.GameCommand;

/** A bounded flight recorder for selections and submitted player orders. */
final class PlayerIntentJournal {

    static final int LIMIT = 512;

    record Entry(long cycle, String event, List<Integer> selectedUnitIds,
            GameCommand command, Boolean accepted) {}

    private final ArrayDeque<Entry> entries = new ArrayDeque<>(LIMIT);

    synchronized void selection(long cycle, List<Integer> selectedUnitIds) {
        add(new Entry(cycle, "selection", List.copyOf(selectedUnitIds), null, null));
    }

    CommandSink wrap(CommandSink destination, LongSupplier cycle,
            Supplier<List<Integer>> selectedUnitIds) {
        return new CommandSink() {
            @Override
            public void issue(GameCommand command) {
                long submittedAt = cycle.getAsLong();
                List<Integer> selection = selectedUnitIds.get();
                destination.issue(command);
                order(submittedAt, selection, command, null);
            }

            @Override
            public boolean issueAccepted(GameCommand command) {
                long submittedAt = cycle.getAsLong();
                List<Integer> selection = selectedUnitIds.get();
                boolean accepted = destination.issueAccepted(command);
                order(submittedAt, selection, command, accepted);
                return accepted;
            }
        };
    }

    private synchronized void order(long cycle, List<Integer> selectedUnitIds,
            GameCommand command, Boolean accepted) {
        add(new Entry(cycle, "order", List.copyOf(selectedUnitIds), command, accepted));
    }

    private void add(Entry entry) {
        while (entries.size() >= LIMIT) {
            entries.removeFirst();
        }
        entries.addLast(entry);
    }

    synchronized List<Entry> snapshot() {
        return List.copyOf(new ArrayList<>(entries));
    }
}
