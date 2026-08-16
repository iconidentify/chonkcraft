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

    /** The physical input that began a player transaction. */
    record Gesture(String origin, String detail, int screenX, int screenY,
            int tileX, int tileY, String modifiers, Integer targetId,
            String targetShape) {}

    /**
     * A pre-wire UI decision, such as refusing to train because the bank
     * cannot pay. Those clicks never become a {@link GameCommand}.
     */
    record Decision(long transactionId, long cycle, boolean accepted,
            String family, boolean queued, String reason) {}

    /** Voice or status acknowledgement attached to one intent. */
    record Feedback(long intentId, long transactionId, long cycle,
            boolean acknowledged, String mode, String detail) {}

    record Entry(long id, long transactionId, long cycle, String event,
            List<Integer> selectedUnitIds, GameCommand command, Boolean accepted,
            Gesture gesture, Integer fanoutOrdinal) {}

    /** The causal result of one submitted unit command at the latest observed cycle. */
    record Outcome(long intentId, long transactionId, long submittedCycle,
            int unitId, String command,
            Boolean accepted, Long firstProgressCycle, Long terminalCycle,
            String terminalReason, int tileX, int tileY, int offsetX, int offsetY,
            String order,
            Integer targetId, int hitPoints, int carried, boolean alive,
            boolean onMap, int missileCount) {}

    private record State(int tileX, int tileY, int offsetX, int offsetY,
            String order, Integer targetId, int hitPoints, int carried,
            boolean alive, boolean onMap, String pendingBuild,
            int buildTileX, int buildTileY, Integer worksiteId,
            String worksiteType, int worksiteTileX, int worksiteTileY,
            String producing, int trainingJobs,
            String researching, String upgrading, int missileCount) {}

    /** The physical obstacle represented by a clicked movement point. */
    private record Goal(boolean blocked, int minX, int minY, int maxX, int maxY) {
        boolean touches(Unit unit) {
            if (!blocked || unit == null) {
                return false;
            }
            int unitMaxX = unit.tileX() + Math.max(1, unit.type().tileWidth()) - 1;
            int unitMaxY = unit.tileY() + Math.max(1, unit.type().tileHeight()) - 1;
            int gapX = rectangleGap(unit.tileX(), unitMaxX, minX, maxX);
            int gapY = rectangleGap(unit.tileY(), unitMaxY, minY, maxY);
            // Flyers walk a doubled lattice, so Human 13's occupied daemon
            // stands still two tiles short of the click (86,2 vs 86,4).
            int reach = unit.type() != null && unit.type().airUnit() ? 2 : 1;
            return Math.max(gapX, gapY) <= reach;
        }

        private static int rectangleGap(int aMin, int aMax, int bMin, int bMax) {
            if (aMax < bMin) {
                return bMin - aMax;
            }
            if (bMax < aMin) {
                return aMin - bMax;
            }
            return 0;
        }
    }

    private static final class Tracking {
        private final long intentId;
        private final long transactionId;
        private final long submittedCycle;
        private final GameCommand command;
        private final Boolean accepted;
        private final State submitted;
        private final State targetSubmitted;
        private final Goal goal;
        private Long firstProgressCycle;
        private Long terminalCycle;
        private String terminalReason;
        private State latest;

        private Tracking(long intentId, long transactionId, long submittedCycle,
                GameCommand command,
                Boolean accepted, State submitted, State targetSubmitted, Goal goal) {
            this.intentId = intentId;
            this.transactionId = transactionId;
            this.submittedCycle = submittedCycle;
            this.command = command;
            this.accepted = accepted;
            this.submitted = submitted;
            this.targetSubmitted = targetSubmitted;
            this.goal = goal;
            this.latest = submitted;
        }
    }

    private final ArrayDeque<Entry> entries = new ArrayDeque<>(LIMIT);
    private final ArrayDeque<Tracking> outcomes = new ArrayDeque<>(LIMIT);
    private final ArrayDeque<Decision> decisions = new ArrayDeque<>(LIMIT);
    private final ArrayDeque<Feedback> feedbacks = new ArrayDeque<>(LIMIT);
    private long nextIntentId = 1;
    private Long activeTransactionId;
    private long lastOrderIntentId;
    private int nextFanoutOrdinal;

    /**
     * Starts one physical player transaction.
     *
     * <p>The commands produced by a nine-unit right click are nine wire
     * records but one player action. Keeping that common id is what lets the
     * BNE comparison prove ordered group fan-out rather than nine unrelated
     * single-unit commands.
     */
    synchronized long beginGesture(long cycle, String origin, String detail,
            int screenX, int screenY, int tileX, int tileY, String modifiers,
            Integer targetId, String targetShape, List<Integer> selectedUnitIds) {
        long id = nextIntentId++;
        activeTransactionId = id;
        lastOrderIntentId = 0;
        nextFanoutOrdinal = 0;
        add(new Entry(id, id, cycle, "gesture", List.copyOf(selectedUnitIds),
                null, null, new Gesture(origin, detail, screenX, screenY,
                        tileX, tileY, modifiers, targetId, targetShape),
                null));
        return id;
    }

    /** Ends the named gesture without disturbing a newer nested transaction. */
    synchronized void endGesture(long id) {
        if (activeTransactionId != null && activeTransactionId == id) {
            activeTransactionId = null;
        }
    }

    /** The transaction still waiting for an aimed click, or {@code null}. */
    synchronized Long activeTransaction() {
        return activeTransactionId;
    }

    /**
     * Records a UI decision that never became a wire command.
     *
     * <p>DoClicked_Train / Research / UpgradeTo / Build notify and return
     * before SendCommand when the bank or the food table refuses. The
     * physical lane needs that refusal on the same transaction as the
     * button or hotkey, not a later invented wire row.
     */
    synchronized void recordDecision(long cycle, boolean accepted, String family,
            boolean queued, String reason) {
        if (activeTransactionId == null || family == null || family.isBlank()) {
            return;
        }
        while (decisions.size() >= LIMIT) {
            decisions.removeFirst();
        }
        decisions.addLast(new Decision(activeTransactionId, cycle, accepted,
                family, queued, reason == null ? "" : reason));
    }

    /**
     * Records the voice or status acknowledgement for the open transaction.
     *
     * <p>Retail {@code Notify} is how CheckCosts and CheckLimits talk. A
     * missing acknowledgement would make a pre-wire refusal look like the
     * click vanished.
     */
    synchronized void recordFeedback(long cycle, boolean acknowledged, String mode,
            String detail) {
        if (activeTransactionId == null) {
            return;
        }
        while (feedbacks.size() >= LIMIT) {
            feedbacks.removeFirst();
        }
        feedbacks.addLast(new Feedback(activeTransactionId, activeTransactionId,
                cycle, acknowledged, mode, detail));
    }

    /**
     * Attaches acknowledgement to the most recent order in the open
     * transaction. Retail plays one voice for a group and keeps the rest
     * silent; the receipt has to name which intent spoke.
     */
    synchronized void recordLastOrderFeedback(long cycle, boolean acknowledged,
            String mode, String detail) {
        if (activeTransactionId == null || lastOrderIntentId <= 0) {
            recordFeedback(cycle, acknowledged, mode, detail);
            return;
        }
        while (feedbacks.size() >= LIMIT) {
            feedbacks.removeFirst();
        }
        feedbacks.addLast(new Feedback(lastOrderIntentId, activeTransactionId,
                cycle, acknowledged, mode, detail == null ? "" : detail));
    }

    /**
     * Records that the open gesture fanned out at least one accepted order.
     */
    synchronized void recordAcceptedFanout(long cycle, boolean queued) {
        if (activeTransactionId == null) {
            return;
        }
        String family = null;
        for (Entry entry : entries) {
            if (entry.transactionId() == activeTransactionId
                    && "order".equals(entry.event()) && entry.command() != null) {
                family = entry.command().kind().name().toLowerCase(java.util.Locale.ROOT)
                        .replace('_', '-');
                break;
            }
        }
        if (family != null) {
            recordDecision(cycle, true, family, queued, "give-order");
        }
    }

    synchronized void selection(long cycle, List<Integer> selectedUnitIds) {
        long id = nextIntentId++;
        long transactionId = activeTransactionId == null ? id : activeTransactionId;
        add(new Entry(id, transactionId, cycle, "selection",
                List.copyOf(selectedUnitIds), null, null, null, null));
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
                Goal goal = goalOf(world, command);
                destination.issue(command);
                State delivered = stateOf(world, command.unitId());
                order(submittedAt, selection, command, null,
                        submitted, targetSubmitted, delivered, goal, world);
            }

            @Override
            public boolean issueAccepted(GameCommand command) {
                long submittedAt = cycle.getAsLong();
                List<Integer> selection = selectedUnitIds.get();
                State submitted = stateOf(world, command.unitId());
                State targetSubmitted = stateOf(world, command.targetId());
                Goal goal = goalOf(world, command);
                boolean accepted = destination.issueAccepted(command);
                State delivered = stateOf(world, command.unitId());
                order(submittedAt, selection, command, accepted,
                        submitted, targetSubmitted, delivered, goal, world);
                return accepted;
            }
        };
    }

    private synchronized void order(long cycle, List<Integer> selectedUnitIds,
            GameCommand command, Boolean accepted, State submitted,
            State targetSubmitted, State delivered, Goal goal, World world) {
        long id = nextIntentId++;
        long transactionId = activeTransactionId == null ? id : activeTransactionId;
        lastOrderIntentId = id;
        int fanout = nextFanoutOrdinal++;
        add(new Entry(id, transactionId, cycle, "order", List.copyOf(selectedUnitIds),
                command, accepted, null, fanout));
        if (submitted == null || command.unitId() == 0) {
            return;
        }
        Unit unit = find(world, command.unitId());
        // Stop behind dest-arm leftover is next_order, not a replacement.
        // Native stop-1/00 keeps the Move settled when leftover lands.
        boolean leftoverStop = command.kind() == GameCommand.Kind.STOP
                && unit != null
                && (unit.battleNetStopAfterLeftover() || unit.isMoving());
        if (!leftoverStop) {
            for (Tracking previous : outcomes) {
                if (previous.terminalCycle == null
                        && previous.command.unitId() == command.unitId()) {
                    previous.terminalCycle = cycle;
                    previous.terminalReason = "superseded";
                }
            }
        }
        Tracking tracking = new Tracking(id, transactionId, cycle, command, accepted,
                submitted, targetSubmitted, goal);
        tracking.latest = delivered == null ? submitted : delivered;
        if (Boolean.FALSE.equals(accepted)) {
            tracking.terminalCycle = cycle;
            tracking.terminalReason = "rejected";
        } else {
            State targetNow = stateOf(world, command.targetId());
            // Native stand-ground first-progress is the Still-to-hold pop on
            // the issue visit. Fulfilled used to fire from delivered state
            // without recording that change, so progress_delay stayed empty.
            if (tracking.firstProgressCycle == null
                    && progressed(command, submitted, tracking.latest,
                            targetSubmitted, targetNow)) {
                tracking.firstProgressCycle = cycle;
            }
            String terminal = terminalReason(tracking, tracking.latest, targetNow,
                    cycle, world, unit);
            if (terminal != null) {
                tracking.terminalCycle = cycle;
                tracking.terminalReason = terminal;
            }
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
            State now = unit == null ? absent() : state(unit, world);
            tracking.latest = now;
            Unit target = find(world, tracking.command.targetId());
            State targetNow = target == null ? null : state(target, world);
            if (tracking.firstProgressCycle == null
                    && progressed(tracking.command, tracking.submitted, now,
                            tracking.targetSubmitted, targetNow)) {
                tracking.firstProgressCycle = cycle;
            }
            String terminal = terminalReason(tracking, now, targetNow, cycle,
                    world, unit);
            if (terminal != null) {
                tracking.terminalCycle = cycle;
                tracking.terminalReason = terminal;
            }
        }
    }

    private static boolean progressed(GameCommand command, State before, State now,
            State targetBefore, State targetNow) {
        if (before == null || now == null) {
            return before != now;
        }
        // Still leftover bobs every cycle. Native first_progress is the first
        // walk pixel, not that bob -- counting it made every attack and patrol
        // look several cycles early. Human 1's footman also updates its tile
        // field at cycle 9 to 22,6 while IX/IY stay 672,160 (offset -32);
        // that pop is not movement.
        int beforePixelX = before.tileX * Unit.TILE_PIXELS + before.offsetX;
        int beforePixelY = before.tileY * Unit.TILE_PIXELS + before.offsetY;
        int nowPixelX = now.tileX * Unit.TILE_PIXELS + now.offsetX;
        int nowPixelY = now.tileY * Unit.TILE_PIXELS + now.offsetY;
        boolean moved = (beforePixelX != nowPixelX || beforePixelY != nowPixelY)
                && !"STILL".equals(now.order);
        boolean targetChanged = targetBefore != null && targetNow != null
                && (targetBefore.hitPoints != targetNow.hitPoints
                        || targetBefore.alive != targetNow.alive
                        || targetBefore.onMap != targetNow.onMap);
        boolean productionChanged = before.trainingJobs != now.trainingJobs
                || !java.util.Objects.equals(before.producing, now.producing)
                || !java.util.Objects.equals(before.researching, now.researching)
                || !java.util.Objects.equals(before.upgrading, now.upgrading);
        boolean foundationCreated = before.worksiteId == null
                && now.worksiteId != null && now.worksiteType != null
                && now.worksiteTileX == command.x()
                && now.worksiteTileY == command.y();
        return switch (command.kind()) {
            case MOVE, ATTACK_MOVE, PATROL, EXPLORE, FOLLOW, DEFEND -> moved;
            case ATTACK, ATTACK_GROUND, CAST -> moved || targetChanged
                    || !java.util.Objects.equals(before.order, now.order);
            case HARVEST, RETURN_GOODS -> moved || before.carried != now.carried
                    || !java.util.Objects.equals(before.order, now.order);
            // Native first_progress for GiveOrder 27 from Still is the
            // Still-to-Repair (or Still-to-Move) pop. Counting every order
            // change made leftover harvest repair-1/04 first progress 5
            // while native stays Harvest + next_order 27 until 6.
            case REPAIR -> moved
                    || "STILL".equals(before.order)
                    && !java.util.Objects.equals(before.order, now.order);
            case BUILD -> moved || foundationCreated;
            case TRAIN, RESEARCH, UPGRADE_TO -> productionChanged;
            case BOARD, UNLOAD, UNLOAD_ONE -> moved
                    || before.onMap != now.onMap || before.worksiteId != now.worksiteId;
            case STOP, STAND_GROUND -> !java.util.Objects.equals(before.order, now.order);
            default -> moved || targetChanged || productionChanged
                    || before.hitPoints != now.hitPoints
                    || before.carried != now.carried
                    || before.alive != now.alive || before.onMap != now.onMap;
        };
    }

    private static String terminalReason(Tracking tracking, State now,
            State targetNow, long cycle, World world, Unit unit) {
        // Some successful BNE actions deliberately take their actor off-map:
        // builders enter foundations and passengers enter transports.  Judge
        // the requested objective before applying the generic availability
        // terminal or a successful action is mislabeled as a disappearance.
        if (fulfilled(tracking, now, targetNow, unit)) {
            return switch (tracking.command.kind()) {
                case MOVE, ATTACK_MOVE, PATROL, EXPLORE, FOLLOW, DEFEND -> "settled";
                default -> "fulfilled";
            };
        }
        if (!now.alive || !now.onMap) {
            return "unit-unavailable";
        }
        if (tracking.targetSubmitted != null
                && (targetNow == null || !targetNow.alive || !targetNow.onMap)) {
            return "target-unavailable";
        }
        if (cycle - tracking.submittedCycle >= OUTCOME_WINDOW) {
            return tracking.firstProgressCycle == null
                    ? "acknowledged-no-progress" : "window-complete";
        }
        return null;
    }

    private static boolean fulfilled(Tracking tracking, State now,
            State targetNow, Unit unit) {
        GameCommand command = tracking.command;
        State before = tracking.submitted;
        return switch (command.kind()) {
            case MOVE, ATTACK_MOVE, PATROL, EXPLORE, FOLLOW ->
                    movementSettled(tracking, now, unit);
            // Still-on-Still is not a completed Stop. Native stop-1/04 stays
            // Still with no first progress and finishes the window as
            // acknowledged-no-progress. Used to fulfill on the issue cycle.
            case STOP -> "STILL".equals(now.order)
                    && tracking.firstProgressCycle != null;
            case STAND_GROUND -> "STAND_GROUND".equals(now.order);
            case DEFEND -> "DEFEND".equals(now.order);
            case BUILD -> now.worksiteId != null
                    && now.worksiteType != null
                    && now.worksiteTileX == command.x()
                    && now.worksiteTileY == command.y();
            case TRAIN -> now.trainingJobs > before.trainingJobs
                    || !java.util.Objects.equals(now.producing, before.producing);
            case RESEARCH -> !java.util.Objects.equals(now.researching,
                    before.researching);
            case UPGRADE_TO -> !java.util.Objects.equals(now.upgrading,
                    before.upgrading);
            case ATTACK, CAST -> targetChanged(
                    tracking.targetSubmitted, targetNow);
            // GiveOrder 17 into forest leftover-lands Still. Native
            // attack-ground-1/02 fulfills at 43; requiring a damaged
            // target left Java window-complete on the same Still square.
            case ATTACK_GROUND -> targetChanged(
                    tracking.targetSubmitted, targetNow)
                    || "STILL".equals(now.order)
                    && tracking.firstProgressCycle != null;
            case HARVEST -> now.carried != before.carried;
            case REPAIR -> "STILL".equals(now.order)
                    && tracking.firstProgressCycle != null;
            case RETURN_GOODS -> before.carried > 0 && now.carried == 0;
            case BOARD -> !now.onMap;
            case UNLOAD, UNLOAD_ONE -> tracking.firstProgressCycle != null
                    && "STILL".equals(now.order);
            default -> false;
        };
    }

    /**
     * A walk is over when the unit stands still after making progress, or
     * is already on the edge of a blocked click. The native fixture
     * adapter uses the same Still-after-progress rule; requiring the dest
     * tile made nineteen Human walks wait the whole window while native
     * had already stood still one square short.
     */
    private static boolean movementSettled(Tracking tracking, State now,
            Unit unit) {
        if (!"STILL".equals(now.order)) {
            return false;
        }
        if (tracking.goal != null && tracking.goal.blocked()
                && tracking.goal.touches(unit)) {
            return true;
        }
        return tracking.firstProgressCycle != null
                || now.tileX == tracking.command.x()
                && now.tileY == tracking.command.y();
    }

    private static boolean targetChanged(State before, State now) {
        return before != null && (now == null
                || before.hitPoints != now.hitPoints
                || before.alive != now.alive || before.onMap != now.onMap);
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

    private static Goal goalOf(World world, GameCommand command) {
        Unit mover = find(world, command.unitId());
        if (mover == null || !movement(command.kind())
                || !world.map().contains(command.x(), command.y())) {
            return null;
        }
        boolean free = world.map().isFootprintFree(command.x(), command.y(),
                Math.max(1, mover.type().tileWidth()),
                Math.max(1, mover.type().tileHeight()), mover.movementMask(),
                mover.blockingFlags());
        if (free) {
            return new Goal(false, command.x(), command.y(), command.x(), command.y());
        }
        for (Unit occupant : world.unitsSnapshot()) {
            if (occupant == mover || !occupant.isAlive() || !occupant.isOnMap()) {
                continue;
            }
            int maxX = occupant.tileX() + Math.max(1, occupant.type().tileWidth()) - 1;
            int maxY = occupant.tileY() + Math.max(1, occupant.type().tileHeight()) - 1;
            if (command.x() >= occupant.tileX() && command.x() <= maxX
                    && command.y() >= occupant.tileY() && command.y() <= maxY) {
                return new Goal(true, occupant.tileX(), occupant.tileY(), maxX, maxY);
            }
        }
        return new Goal(true, command.x(), command.y(), command.x(), command.y());
    }

    private static boolean movement(GameCommand.Kind kind) {
        return switch (kind) {
            case MOVE, ATTACK_MOVE, PATROL, EXPLORE, FOLLOW, DEFEND -> true;
            default -> false;
        };
    }

    private static State state(Unit unit, World world) {
        Unit worksite = unit.worksite();
        return new State(unit.tileX(), unit.tileY(), unit.offsetX(), unit.offsetY(),
                unit.order() == null ? null : unit.order().name(),
                unit.target() == null ? null : unit.target().id(),
                unit.hitPoints(), unit.carried(), living(unit), unit.isOnMap(),
                unit.pendingBuild() == null ? null : unit.pendingBuild().ident(),
                unit.buildTileX(), unit.buildTileY(),
                worksite == null ? null : worksite.id(),
                worksite == null ? null : worksite.type().ident(),
                worksite == null ? -1 : worksite.tileX(),
                worksite == null ? -1 : worksite.tileY(),
                unit.producing() == null ? null : unit.producing().ident(),
                unit.trainingJobCount(), unit.researching(),
                unit.upgradingTo() == null ? null : unit.upgradingTo().ident(),
                world.missiles().size());
    }

    /**
     * Whether the hull is still a living unit. {@link Unit#isAlive()} treats
     * a worker who walked into a mine as dead because the unit is removed;
     * native still reports that peon alive and only off the map.
     */
    private static boolean living(Unit unit) {
        return unit.hitPoints() > 0 && unit.order() != Unit.Order.DYING;
    }

    private static State stateOf(World world, int id) {
        Unit unit = find(world, id);
        return unit == null ? null : state(unit, world);
    }

    private static State absent() {
        return new State(-1, -1, 0, 0, null, null, 0, 0, false, false,
                null, -1, -1, null, null, -1, -1, null, 0, null, null, 0);
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

    synchronized List<Decision> decisionSnapshot() {
        return List.copyOf(new ArrayList<>(decisions));
    }

    synchronized List<Feedback> feedbackSnapshot() {
        return List.copyOf(new ArrayList<>(feedbacks));
    }

    synchronized List<Outcome> outcomeSnapshot() {
        List<Outcome> result = new ArrayList<>(outcomes.size());
        for (Tracking tracking : outcomes) {
            State latest = tracking.latest == null ? absent() : tracking.latest;
            result.add(new Outcome(tracking.intentId, tracking.transactionId,
                    tracking.submittedCycle,
                    tracking.command.unitId(), tracking.command.kind().name(),
                    tracking.accepted, tracking.firstProgressCycle,
                    tracking.terminalCycle, tracking.terminalReason,
                    latest.tileX, latest.tileY, latest.offsetX, latest.offsetY,
                    latest.order, latest.targetId,
                    latest.hitPoints, latest.carried, latest.alive, latest.onMap,
                    latest.missileCount));
        }
        return List.copyOf(result);
    }
}
