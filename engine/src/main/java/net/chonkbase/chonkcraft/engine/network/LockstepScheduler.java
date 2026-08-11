package net.chonkbase.chonkcraft.engine.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Holds commands until every player's have arrived, then releases them.
 *
 * <p>Implements the scheduling.
 *
 * <p>The rules that make lockstep work, and each of them matters:
 *
 * <ul>
 *   <li>Commands are batched into <em>net cycles</em> rather than sent per
 *       game cycle, so the wire carries a few packets a second instead of
 *       thirty.
 *   <li>A command issued now executes {@code lag} net cycles from now, on
 *       every machine including the one that issued it. Without that delay the
 *       issuing machine would act before the others heard, and they would
 *       diverge immediately.
 *   <li>A player with nothing to say still sends an empty command, because
 *       silence is indistinguishable from a lost packet.
 *   <li>The simulation does not advance past a net cycle until every player's
 *       commands for it are in. That stall is the price of determinism, and is
 *       why a laggy player pauses everyone.
 * </ul>
 */
public final class LockstepScheduler {

    /** Game cycles per net cycle. Commands are collected in these batches. */
    public static final int DEFAULT_CYCLES_PER_UPDATE = 5;

    /**
     * How many net cycles ahead a command is scheduled.
     *
     * <p>Upstream's default, chosen so a command has time to cross the network
     * before the cycle it belongs to comes up.
     */
    public static final int DEFAULT_LAG = 2;

    private final int playerCount;
    private final int cyclesPerUpdate;
    private final int lag;

    /** Commands received, keyed by net cycle then by player. */
    private final Map<Long, Map<Integer, List<GameCommand>>> pending = new LinkedHashMap<>();

    /** Which players are still in the game. */
    private final boolean[] active;

    private long netCycle;

    public LockstepScheduler(int playerCount) {
        this(playerCount, DEFAULT_CYCLES_PER_UPDATE, DEFAULT_LAG);
    }

    public LockstepScheduler(int playerCount, int cyclesPerUpdate, int lag) {
        if (playerCount <= 0) {
            throw new IllegalArgumentException("a game needs at least one player");
        }
        if (cyclesPerUpdate <= 0 || lag < 0) {
            throw new IllegalArgumentException("bad scheduling parameters");
        }
        this.playerCount = playerCount;
        this.cyclesPerUpdate = cyclesPerUpdate;
        this.lag = lag;
        this.active = new boolean[playerCount];
        java.util.Arrays.fill(active, true);
    }

    public int cyclesPerUpdate() {
        return cyclesPerUpdate;
    }

    public int lag() {
        return lag;
    }

    /** The net cycle the simulation is waiting to execute. */
    public long currentNetCycle() {
        return netCycle;
    }

    /** The net cycle a command issued now will execute on. */
    public long scheduledNetCycle() {
        return netCycle + lag;
    }

    /** Whether a game cycle is the start of a net cycle. */
    public boolean isNetCycleBoundary(long gameCycle) {
        return gameCycle % cyclesPerUpdate == 0;
    }

    /** Whether a player is still in the game. */
    public boolean isActive(int player) {
        return player >= 0 && player < playerCount && active[player];
    }

    /**
     * Records a player's commands for a net cycle.
     *
     * <p>An empty list is meaningful and must still be submitted: it says the
     * player is alive and had nothing to order.
     */
    public void submit(long forNetCycle, int player, List<GameCommand> commands) {
        if (!isActive(player)) {
            return;
        }
        if (forNetCycle < netCycle) {
            // A resend of a cycle already executed. Filing it would put an
            // entry in the map that nothing will ever release, and a long game
            // resends often enough that the map grows for the life of the
            // match. It is by definition too late to matter: the cycle it
            // belongs to has been played.
            return;
        }
        pending.computeIfAbsent(forNetCycle, ignored -> new LinkedHashMap<>())
                .put(player, List.copyOf(commands));
    }

    /** Convenience for a player with nothing to order. */
    public void submitNothing(long forNetCycle, int player) {
        submit(forNetCycle, player, List.of());
    }

    /** Whether this player's batch for a cycle is already present. */
    public boolean hasSubmission(long forNetCycle, int player) {
        Map<Integer, List<GameCommand>> arrived = pending.get(forNetCycle);
        return arrived != null && arrived.containsKey(player);
    }

    /** Whether every active player has reported for the current net cycle. */
    public boolean isReady() {
        Map<Integer, List<GameCommand>> arrived = pending.get(netCycle);
        for (int player = 0; player < playerCount; player++) {
            if (active[player] && (arrived == null || !arrived.containsKey(player))) {
                return false;
            }
        }
        return true;
    }

    /** Which players have not reported yet, for diagnostics. */
    public List<Integer> waitingOn() {
        Map<Integer, List<GameCommand>> arrived = pending.get(netCycle);
        List<Integer> waiting = new ArrayList<>();
        for (int player = 0; player < playerCount; player++) {
            if (active[player] && (arrived == null || !arrived.containsKey(player))) {
                waiting.add(player);
            }
        }
        return waiting;
    }

    /**
     * Releases the current net cycle's commands and advances.
     *
     * <p>Ordered by player, then by the order each player issued them, so
     * every machine applies the same commands in the same sequence. Two
     * players ordering the same square on the same cycle must resolve
     * identically everywhere, and player order is what guarantees it.
     *
     * @throws IllegalStateException if called before {@link #isReady}
     */
    public List<GameCommand> release() {
        if (!isReady()) {
            throw new IllegalStateException("net cycle " + netCycle + " is waiting on " + waitingOn());
        }
        Map<Integer, List<GameCommand>> arrived = pending.remove(netCycle);
        netCycle++;

        if (arrived == null) {
            return List.of();
        }
        List<GameCommand> ordered = new ArrayList<>();
        for (int player = 0; player < playerCount; player++) {
            List<GameCommand> theirs = arrived.get(player);
            if (theirs != null) {
                ordered.addAll(theirs);
            }
        }
        // A future QUIT must not make its player disappear early. They remain
        // part of every preceding cycle and become inactive only when the
        // agreed departure command is actually released.
        for (GameCommand command : ordered) {
            if (command.kind() == GameCommand.Kind.QUIT
                    && command.player() >= 0 && command.player() < playerCount) {
                active[command.player()] = false;
            }
        }
        return Collections.unmodifiableList(ordered);
    }

    /** How many net cycles have commands waiting. */
    public int bufferedCycles() {
        return pending.size();
    }
}
