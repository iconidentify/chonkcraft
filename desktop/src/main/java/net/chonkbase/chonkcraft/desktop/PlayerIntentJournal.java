package net.chonkbase.chonkcraft.desktop;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.network.GameCommand;
import net.chonkbase.chonkcraft.engine.unit.Unit;

/** A bounded flight recorder for selections and submitted player orders. */
final class PlayerIntentJournal {

    static final int LIMIT = 512;
    static final long OUTCOME_WINDOW = 600;

    record Entry(long id, long cycle, String event, List<Integer> selectedUnitIds,
            GameCommand command, Boolean accepted) {}

    /** The causal result of one submitted unit command at the latest observed cycle. */
    record Outcome(long intentId, long submittedCycle, int unitId, String command,
            Boolean accepted, Long firstProgressCycle, Long terminalCycle,
            String terminalReason, int tileX, int tileY, String order,
            Integer targetId, int hitPoints, int carried, boolean alive,
            boolean onMap) {}

    private record State(int tileX, int tileY, int offsetX, int offsetY,
            String order, Integer targetId, int hitPoints, int carried,
            boolean alive, boolean onMap) {}

    private static final class Tracking {
        private final long intentId;
        private final long submittedCycle;
        private final GameCommand command;
        private final Boolean accepted;
        private final State submitted;
        private final State targetSubmitted;
        private Long firstProgressCycle;
        private Long terminalCycle;
        private String terminalReason;
        private State latest;

        private Tracking(long intentId, long submittedCycle, GameCommand command,
                Boolean accepted, State submitted, State targetSubmitted) {
            this.intentId = intentId;
            this.submittedCycle = submittedCycle;
            this.command = command;
            this.accepted = accepted;
            this.submitted = submitted;
            this.targetSubmitted = targetSubmitted;
            this.latest = submitted;
        }
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>(LIMIT);
    private final ArrayDeque<Tracking> outcomes = new ArrayDeque<>(LIMIT);
    private long nextIntentId = 1;

    synchronized void selection(long cycle, List<Integer> selectedUnitIds) {
        add(new Entry(nextIntentId++, cycle, "selection",
                List.copyOf(selectedUnitIds), null, null));
    }

    CommandSink wrap(CommandSink destination, LongSupplier cycle,
            Supplier<List<Integer>> selectedUnitIds, World world) {
        return new CommandSink() {
            @Override
            public void issue(GameCommand command) {
                long submittedAt = cycle.getAsLong();
                List<Integer> selection = selectedUnitIds.get();
                State submitted = stateOf(world, command.unitId());
                State targetSubmitted = stateOf(world, command.targetId());
                destination.issue(command);
                order(submittedAt, selection, command, null,
                        submitted, targetSubmitted);
            }

            @Override
            public boolean issueAccepted(GameCommand command) {
                long submittedAt = cycle.getAsLong();
                List<Integer> selection = selectedUnitIds.get();
                State submitted = stateOf(world, command.unitId());
                State targetSubmitted = stateOf(world, command.targetId());
                boolean accepted = destination.issueAccepted(command);
                order(submittedAt, selection, command, accepted,
                        submitted, targetSubmitted);
                return accepted;
            }
        };
    }

    private synchronized void order(long cycle, List<Integer> selectedUnitIds,
            GameCommand command, Boolean accepted, State submitted,
            State targetSubmitted) {
        long id = nextIntentId++;
        add(new Entry(id, cycle, "order", List.copyOf(selectedUnitIds), command, accepted));
        if (submitted == null || command.unitId() == 0) {
            return;
        }
        for (Tracking previous : outcomes) {
            if (previous.terminalCycle == null
                    && previous.command.unitId() == command.unitId()) {
                previous.terminalCycle = cycle;
                previous.terminalReason = "superseded";
            }
        }
        Tracking tracking = new Tracking(id, cycle, command, accepted,
                submitted, targetSubmitted);
        if (Boolean.FALSE.equals(accepted)) {
            tracking.terminalCycle = cycle;
            tracking.terminalReason = "rejected";
        }
        while (outcomes.size() >= LIMIT) {
            outcomes.removeFirst();
        }
        outcomes.addLast(tracking);
    }

    /** Observes command fulfillment after a simulation cycle has completed. */
    synchronized void observe(long cycle, World world) {
        Iterator<Tracking> iterator = outcomes.iterator();
        while (iterator.hasNext()) {
            Tracking tracking = iterator.next();
            if (tracking.terminalCycle != null) {
                continue;
            }
            Unit unit = find(world, tracking.command.unitId());
            State now = unit == null ? absent() : state(unit);
            tracking.latest = now;
            Unit target = find(world, tracking.command.targetId());
            State targetNow = target == null ? null : state(target);
            if (tracking.firstProgressCycle == null
                    && progressed(tracking.submitted, now,
                            tracking.targetSubmitted, targetNow)) {
                tracking.firstProgressCycle = cycle;
            }
            String terminal = terminalReason(tracking, now, targetNow, cycle);
            if (terminal != null) {
                tracking.terminalCycle = cycle;
                tracking.terminalReason = terminal;
            }
        }
    }

    private static boolean progressed(State before, State now,
            State targetBefore, State targetNow) {
        if (before == null || now == null) {
            return before != now;
        }
        if (before.tileX != now.tileX || before.tileY != now.tileY
                || before.offsetX != now.offsetX || before.offsetY != now.offsetY
                || !java.util.Objects.equals(before.order, now.order)
                || !java.util.Objects.equals(before.targetId, now.targetId)
                || before.hitPoints != now.hitPoints || before.carried != now.carried
                || before.alive != now.alive || before.onMap != now.onMap) {
            return true;
        }
        return targetBefore != null && targetNow != null
                && (targetBefore.hitPoints != targetNow.hitPoints
                        || targetBefore.alive != targetNow.alive
                        || targetBefore.onMap != targetNow.onMap);
    }

    private static String terminalReason(Tracking tracking, State now,
            State targetNow, long cycle) {
        if (!now.alive || !now.onMap) {
            return "unit-unavailable";
        }
        if (tracking.targetSubmitted != null
                && (targetNow == null || !targetNow.alive || !targetNow.onMap)) {
            return "target-unavailable";
        }
        if (tracking.firstProgressCycle != null && "STILL".equals(now.order)) {
            return "settled";
        }
        if (cycle - tracking.submittedCycle >= OUTCOME_WINDOW) {
            return tracking.firstProgressCycle == null
                    ? "acknowledged-no-progress" : "window-complete";
        }
        return null;
    }

    private static Unit find(World world, int id) {
        if (id <= 0) {
            return null;
        }
        for (Unit unit : world.unitsSnapshot()) {
            if (unit.id() == id) {
                return unit;
            }
        }
        return null;
    }

    private static State state(Unit unit) {
        return new State(unit.tileX(), unit.tileY(), unit.offsetX(), unit.offsetY(),
                unit.order() == null ? null : unit.order().name(),
                unit.target() == null ? null : unit.target().id(),
                unit.hitPoints(), unit.carried(), unit.isAlive(), unit.isOnMap());
    }

    private static State stateOf(World world, int id) {
        Unit unit = find(world, id);
        return unit == null ? null : state(unit);
    }

    private static State absent() {
        return new State(-1, -1, 0, 0, null, null, 0, 0, false, false);
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

    synchronized List<Outcome> outcomeSnapshot() {
        List<Outcome> result = new ArrayList<>(outcomes.size());
        for (Tracking tracking : outcomes) {
            State latest = tracking.latest == null ? absent() : tracking.latest;
            result.add(new Outcome(tracking.intentId, tracking.submittedCycle,
                    tracking.command.unitId(), tracking.command.kind().name(),
                    tracking.accepted, tracking.firstProgressCycle,
                    tracking.terminalCycle, tracking.terminalReason,
                    latest.tileX, latest.tileY, latest.order, latest.targetId,
                    latest.hitPoints, latest.carried, latest.alive, latest.onMap));
        }
        return List.copyOf(result);
    }
}
