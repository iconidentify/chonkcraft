package net.chonkbase.chonkcraft.engine.network;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.engine.World;

/**
 * Drives a world in step with other machines.
 *
 * <p>This is the piece that joins the scheduler, the transport and the
 * simulation: it decides when the world may advance, which is the whole of
 * lockstep. The rule is simple and unforgiving. At a net cycle boundary the
 * world stops until every active player's commands for that cycle have
 * arrived. It cannot run ahead "just a little", because a single cycle of
 * divergence is permanent.
 *
 * <p>Commands the local player issues are not applied when issued. They are
 * broadcast for a cycle {@code lag} in the future and applied then, on every
 * machine including this one. That symmetry is what keeps the machines
 * identical, and it is why a networked game feels slightly less responsive
 * than a single-player one.
 */
public final class NetworkGame implements AutoCloseable {

    /**
     * A read-only copy of each command batch after lockstep releases it.
     *
     * <p>The sink runs after the commands have been applied and the resulting
     * world hash has been computed. It cannot admit a command, change its
     * order, or hold the simulation up waiting for another machine. Desktop
     * clients use this seam for a local flight recorder; headless peers and
     * tests pay nothing unless they install one.
     */
    public interface CycleSink {
        /** Records one completed lockstep boundary. */
        void released(long netCycle, long worldCycle, List<GameCommand> commands,
                long syncHash);

        /** Records the last state visible when this machine leaves the game. */
        default void finished(long netCycle, long worldCycle, long syncHash) {
        }
    }

    private static final CycleSink NO_CYCLE_SINK =
            (netCycle, worldCycle, commands, syncHash) -> { };

    /** What a call to {@link #update} did. */
    public enum Step {
        /** The world advanced a cycle. */
        ADVANCED,
        /** Waiting for another player's commands. */
        WAITING,
        /** The relay/host has gone; this match cannot continue safely. */
        HOST_LEFT,
        /** A machine reported a different world; the game is desynchronised. */
        DESYNC
    }

    /** A synchronized departure, suitable for a player-visible notification. */
    public record DepartureEvent(int player, String playerName,
            GameCommand.DepartureReason reason, int controlMask, boolean hostLeft) {}

    /** A side-band player message. It never participates in world simulation. */
    public record ChatEvent(int player, String playerName, int recipientMask, String text,
            boolean local) {}

    /** One human who is still connected to this match. */
    public record PlayerPresence(int player, String name, boolean local, boolean host,
            boolean allied) {}

    private final World world;
    private final NetworkSession session;
    private final LockstepScheduler scheduler;
    private final CommandApplier applier;
    private final int localPlayer;
    private CycleSink cycleSink = NO_CYCLE_SINK;

    /** Commands the local player has issued but not yet sent. */
    private final List<GameCommand> outgoing = new ArrayList<>();

    /** Sync hashes other machines reported, by net cycle. */
    private final java.util.Map<Long, java.util.Map<Integer, Long>> reportedHashes =
            new java.util.HashMap<>();

    /** Our own hash at each net cycle, kept to compare against theirs. */
    private final java.util.Map<Long, Long> ownHashes = new java.util.HashMap<>();

    private long desyncCycle = -1;
    private int desyncPlayer = -1;
    private int hostPlayer = -1;
    private boolean hostLost;
    private boolean leaveSent;
    private Map<Integer, String> playerNames = Map.of();
    private final ArrayDeque<DepartureEvent> departures = new ArrayDeque<>();
    private final ArrayDeque<ChatEvent> chatEvents = new ArrayDeque<>();
    private final Map<Integer, Long> lastChatId = new java.util.HashMap<>();
    private final Map<Integer, ArrayDeque<Long>> chatTimes = new java.util.HashMap<>();
    private long nextChatId = 1;

    private static final int CHAT_BURST = 6;
    private static final long CHAT_WINDOW_MILLIS = 5_000L;

    /**
     * The last cycle whose batch has been sent.
     *
     * <p>update() is called from the frame loop, so it hits a net cycle
     * boundary many times over while waiting for a slow peer. Broadcasting on
     * every one of those floods the socket with duplicates of the same batch,
     * which is how the first version of this managed twenty-five cycles in two
     * minutes: the packets that mattered were being dropped behind thousands
     * that did not.
     */
    private long lastBroadcastCycle = -1;

    /**
     * Batches already sent, kept so they can be sent again.
     *
     * <p>UDP loses packets, and a lost batch stalls every machine forever
     * because the cycle it belonged to can never complete. This is not a rare
     * case: at startup one peer is listening before the other, so its first
     * batches go to a socket that does not exist yet. Upstream resends old
     * commands when packets are missing, and so does this.
     */
    private final java.util.Map<Long, List<GameCommand>> sentBatches = new java.util.HashMap<>();

    /** When the last resend went out, so waiting does not become flooding. */
    private long lastResendAt;

    /** How long to wait before assuming a batch was lost. */
    private static final long RESEND_INTERVAL_MILLIS = 50;

    /**
     * How many past cycles of batches to keep for resending.
     *
     * <p>Bounds how far a peer may fall behind and still be rescued. Beyond
     * this the game genuinely cannot continue, which is the honest outcome:
     * a player that far adrift has lost the connection, not hit a hiccup.
     */
    private static final int RESEND_WINDOW = 64;

    public NetworkGame(World world, NetworkSession session, LockstepScheduler scheduler,
            CommandApplier applier, int localPlayer) {
        this.world = world;
        this.session = session;
        this.scheduler = scheduler;
        this.applier = applier;
        this.localPlayer = localPlayer;
    }

    /**
     * Primes the first few net cycles so the game can start at all.
     *
     * <p>Commands are scheduled {@code lag} cycles ahead, so nothing is ever
     * submitted for cycles zero through {@code lag - 1}. Without priming them
     * every machine waits at cycle zero for commands that by construction can
     * never arrive, and the game deadlocks before its first tick. Upstream
     * does the same thing at startup, pushing empty sync messages into the
     * queues for exactly that range.
     *
     * <p>Every player is primed, not just this one: the same reasoning applies
     * to all of them, and every machine primes identically, so the batches
     * agree without needing to be sent.
     */
    public void start() {
        long now = System.currentTimeMillis();
        for (long cycle = 0; cycle < scheduler.lag(); cycle++) {
            for (int player = 0; player < world.players().length; player++) {
                if (scheduler.isActive(player)) {
                    // LobbySetup may already have placed a bootstrap QUIT at
                    // cycle zero for a slot with no network peer. Priming
                    // must not replace that command with an empty batch.
                    if (!scheduler.hasSubmission(cycle, player)) {
                        scheduler.submitNothing(cycle, player);
                    }
                }
            }
        }
        for (int player = 0; player < world.players().length; player++) {
            if (scheduler.isActive(player)) {
                lastHeardAt.put(player, now);
            }
        }
    }

    /**
     * Whether this machine passes other players' batches on.
     *
     * <p>True on the host and nowhere else. It is the host that everybody can
     * reach, so it is the host that carries traffic between players who
     * cannot reach each other.
     */
    private boolean relaying;

    public void setRelaying(boolean relaying) {
        this.relaying = relaying;
    }

    /** Configures the one machine allowed to adjudicate peer timeouts. */
    public void setHostPlayer(int hostPlayer) {
        this.hostPlayer = hostPlayer;
    }

    /** Installs a passive observer of completed lockstep cycles. */
    public void setCycleSink(CycleSink cycleSink) {
        this.cycleSink = java.util.Objects.requireNonNull(cycleSink, "cycleSink");
    }

    /** Names captured from the settled lobby, used only in notifications. */
    public void setPlayerNames(Map<Integer, String> playerNames) {
        this.playerNames = playerNames == null ? Map.of() : Map.copyOf(playerNames);
    }

    /** Synchronized departures not yet consumed by the desktop. */
    public List<DepartureEvent> drainDepartureEvents() {
        List<DepartureEvent> result = new ArrayList<>(departures);
        departures.clear();
        return List.copyOf(result);
    }

    /** Player messages not yet consumed by the desktop. */
    public List<ChatEvent> drainChatEvents() {
        List<ChatEvent> result = new ArrayList<>(chatEvents);
        chatEvents.clear();
        return List.copyOf(result);
    }

    /**
     * Sends text immediately, beside lockstep rather than through it.
     *
     * @return false when it is empty, has no recipients, or exceeds the short
     *         anti-spam burst allowed in one match
     */
    public boolean sendChat(int recipientMask, String text) {
        String safe = NetworkSession.sanitizeChat(text);
        int recipients = recipientMask & connectedHumanMask() & ~(1 << localPlayer);
        if (safe.isEmpty() || recipients == 0 || !withinChatRate(localPlayer)) {
            return false;
        }
        long id = nextChatId++;
        try {
            session.broadcastChat(id, recipients, safe, relaying);
        } catch (IOException unableToSend) {
            return false;
        }
        chatEvents.add(new ChatEvent(localPlayer, playerName(localPlayer), recipients,
                safe, true));
        return true;
    }

    /** All connected opponents, matching BNE's default Messages selection. */
    public int everyoneChatMask() {
        return connectedHumanMask() & ~(1 << localPlayer);
    }

    /** Connected mutual allies only. */
    public int alliesChatMask() {
        int mask = 0;
        for (int player : playerNames.keySet()) {
            if (player != localPlayer && scheduler.isActive(player)
                    && world.isAllied(localPlayer, player)
                    && world.isAllied(player, localPlayer)) {
                mask |= 1 << player;
            }
        }
        return mask;
    }

    /** The authoritative in-match roster, in player/colour order. */
    public List<PlayerPresence> connectedPlayers() {
        List<PlayerPresence> present = new ArrayList<>();
        for (Map.Entry<Integer, String> entry : playerNames.entrySet()) {
            int player = entry.getKey();
            if (!scheduler.isActive(player)) {
                continue;
            }
            present.add(new PlayerPresence(player, playerName(player), player == localPlayer,
                    player == hostPlayer, player == localPlayer
                            || (world.isAllied(localPlayer, player)
                                    && world.isAllied(player, localPlayer))));
        }
        present.sort(java.util.Comparator.comparingInt(PlayerPresence::player));
        return List.copyOf(present);
    }

    private int connectedHumanMask() {
        int mask = 0;
        for (int player : playerNames.keySet()) {
            if (scheduler.isActive(player)) {
                mask |= 1 << player;
            }
        }
        return mask;
    }

    private boolean withinChatRate(int player) {
        long now = System.currentTimeMillis();
        ArrayDeque<Long> recent = chatTimes.computeIfAbsent(player, ignored -> new ArrayDeque<>());
        while (!recent.isEmpty() && now - recent.peekFirst() >= CHAT_WINDOW_MILLIS) {
            recent.removeFirst();
        }
        if (recent.size() >= CHAT_BURST) {
            return false;
        }
        recent.addLast(now);
        return true;
    }

    public boolean isRelaying() {
        return relaying;
    }

    /** Queues a command to go out with the next batch. */
    public void issue(GameCommand command) {
        if (command.player() != localPlayer) {
            throw new IllegalArgumentException("local player " + localPlayer
                    + " cannot issue a command as player " + command.player());
        }
        outgoing.add(command);
    }

    /** The net cycle at which a divergence was found, or {@code -1}. */
    public long desyncCycle() {
        return desyncCycle;
    }

    /** Which machine disagreed, or {@code -1}. */
    public int desyncPlayer() {
        return desyncPlayer;
    }

    public LockstepScheduler scheduler() {
        return scheduler;
    }

    /**
     * Advances the game by at most one cycle.
     *
     * <p>Call this as often as the frame loop likes: it returns
     * {@link Step#WAITING} rather than blocking, so the renderer keeps
     * drawing and the window keeps responding while a slow player catches up.
     */
    public Step update() throws IOException {
        receive();

        if (hostLost) {
            return Step.HOST_LEFT;
        }

        if (desyncCycle >= 0) {
            return Step.DESYNC;
        }

        // Only a net cycle boundary needs the other machines. Between
        // boundaries the world runs freely on commands already agreed.
        if (!scheduler.isNetCycleBoundary(world.cycle())) {
            world.tick();
            return Step.ADVANCED;
        }

        // Send this cycle's batch before waiting on anybody else's, or two
        // machines each waiting for the other would deadlock. Once only,
        // however many times we are called while waiting.
        if (lastBroadcastCycle != scheduler.currentNetCycle()) {
            lastBroadcastCycle = scheduler.currentNetCycle();
            // A failed send must not leave the loop. The batch stays in
            // sentBatches and resendIfStalled will offer it again, so the
            // recoverable outcome is a stall of a few tens of milliseconds --
            // where the alternative, on the one occasion this actually
            // happened, was the render thread dying and the window going
            // still with nothing to say for itself.
            try {
                broadcast();
            } catch (RuntimeException unsendable) {
                System.err.println("could not send the batch for net cycle "
                        + scheduler.scheduledNetCycle() + ": " + unsendable);
            }
        }

        if (!scheduler.isReady()) {
            resendIfStalled();
            dropSilentPlayers();
            return Step.WAITING;
        }

        long netCycle = scheduler.currentNetCycle();
        List<GameCommand> released = scheduler.release();
        applier.applyAll(released);
        recordDepartures(released);
        world.tick();

        long hash = SyncHash.of(world);
        ownHashes.put(netCycle, hash);
        // Keep a window rather than discarding everything we have passed. A
        // peer behind us still needs the batches we have already used, and
        // purging by our own progress is what leaves it stuck forever: we move
        // on, it waits for a cycle we can no longer resend, and neither side
        // can recover.
        sentBatches.keySet().removeIf(cycle -> cycle < netCycle - RESEND_WINDOW);
        ownHashes.keySet().removeIf(cycle -> cycle < netCycle - RESEND_WINDOW);
        reportedHashes.keySet().removeIf(cycle -> cycle < netCycle - RESEND_WINDOW);
        checkHashes(netCycle, hash);
        try {
            cycleSink.released(netCycle, world.cycle(), released, hash);
        } catch (RuntimeException failed) {
            // A recorder is evidence about a match, never part of the match.
            // Letting one failed disk write escape here used to be the exact
            // shape of the network freeze this observer is meant to diagnose:
            // the simulation callback died and the window stopped moving.
            System.err.println("multiplayer cycle recorder stopped: " + failed);
            cycleSink = NO_CYCLE_SINK;
        }
        return desyncCycle >= 0 ? Step.DESYNC : Step.ADVANCED;
    }

    /**
     * Sends the local player's commands for the cycle they will run on.
     *
     * <p>At most a packet's worth. Box-selecting a large army and giving it
     * one order used to build a batch too big for a datagram; the session
     * threw, the exception escaped {@code update()}, and the frame loop's
     * thread died -- the window stopped drawing and the game froze with no
     * message. Upstream never gets there: {@code NetworkSendCommands} fills
     * one packet and leaves the remainder in {@code CommandsIn} for the next
     * cycle.
     *
     * <p>The remainder is kept in issue order, so a hundred units ordered at
     * once move on two consecutive net cycles rather than losing the tail. The
     * delay is a third of a second at worst and is the same on every machine,
     * because every machine holds the same queue.
     */
    private void broadcast() throws IOException {
        long forCycle = scheduler.scheduledNetCycle();
        int take = Math.min(outgoing.size(), NetworkSession.MAX_COMMANDS_PER_BATCH);
        List<GameCommand> batch = List.copyOf(outgoing.subList(0, take));
        outgoing.subList(0, take).clear();

        // Submit locally as well as sending: this machine is a player too, and
        // its own commands go through the same delay as everyone else's.
        scheduler.submit(forCycle, localPlayer, batch);
        sentBatches.put(forCycle, batch);
        session.broadcast(forCycle, lastCompletedCycle(), currentHash(), batch);
    }

    /** How many of this machine's commands have not gone out yet. */
    public int queuedCommandCount() {
        return outgoing.size();
    }

    /**
     * Resends recent batches while stalled.
     *
     * <p>The peer we are waiting for may be waiting for us: if our batch was
     * the one lost, nothing will move until we send it again. Resending
     * everything still in hand is cheap, a few dozen bytes, and it is the only
     * way out of the stall short of a reliable transport, which would cost
     * more than it saves.
     */
    private void resendIfStalled() throws IOException {
        long now = System.currentTimeMillis();
        if (now - lastResendAt < RESEND_INTERVAL_MILLIS) {
            return;
        }
        lastResendAt = now;

        // Everything still in hand, not just what is ahead of us: a stalled
        // peer is by definition behind, so the batch it is missing is one we
        // have already used.
        for (java.util.Map.Entry<Long, List<GameCommand>> entry : sentBatches.entrySet()) {
            session.broadcast(entry.getKey(), lastCompletedCycle(), currentHash(), entry.getValue());
        }
    }

    /**
     * How long a player may say nothing before the game goes on without them.
     *
     * <p>Upstream's {@code CNetworkParameter::Instance.timeoutInS}, 45 seconds
     *
     */
    public static final long DEFAULT_TIMEOUT_MILLIS = 45_000;

    private long timeoutMillis = DEFAULT_TIMEOUT_MILLIS;

    /** When each player was last heard from. Zero until the game starts. */
    private final java.util.Map<Integer, Long> lastHeardAt = new java.util.HashMap<>();

    /** Players this machine force-quit for going silent, in the order it did. */
    private final List<Integer> dropped = new ArrayList<>();

    /** Shortens the timeout, for tests and for a host that wants a tighter one. */
    public void setTimeoutMillis(long timeoutMillis) {
        this.timeoutMillis = timeoutMillis;
    }

    public long timeoutMillis() {
        return timeoutMillis;
    }

    /** Who this machine gave up on, in the order it did. */
    public List<Integer> droppedPlayers() {
        return List.copyOf(dropped);
    }

    /** Who the current net cycle is still waiting for. */
    public List<Integer> waitingOn() {
        return scheduler.waitingOn();
    }

    /**
     * How long a player has left before the game goes on without them, in
     * milliseconds, or {@code -1} if nobody is waiting on them.
     *
     * <p>For the status line. Upstream counts down out loud from three seconds
     * in -- <em>"Waiting for player %s: %d:%02d"</em> -- and a player watching
     * a frozen game deserves to know whether it is going to recover.
     */
    public long millisBeforeDropping(int player) {
        Long heard = lastHeardAt.get(player);
        if (heard == null || !scheduler.waitingOn().contains(player)) {
            return -1;
        }
        return Math.max(0, timeoutMillis - (System.currentTimeMillis() - heard));
    }

    /**
     * Force-quits a player who has stopped speaking.
     *
     * <p>Without this one player closing their laptop hung the match for
     * everybody: {@code active[player]} was cleared only by an explicit
     * {@code QUIT}, so a machine that simply stopped was waited for forever,
     * and the survivors got as far as the lag window and then stood still. A
     * three-machine probe measured exactly that -- both survivors stopped at
     * cycle 115 and never moved again.
     *
     * <p>{@code CheckPlayerThatTimeOut} does the same upstream
     * The game it injects the quit on the
     * absent player's behalf and carries on.
     *
     * <p>The quit is submitted for the net cycle everybody is stalled on, not
     * for one derived from the clock. That is what keeps it deterministic:
     * lockstep means every surviving machine is stopped on the same cycle, so
     * they all inject the same command at the same place in the sequence even
     * though their timers expire at different instants.
     */
    private void dropSilentPlayers() throws IOException {
        long now = System.currentTimeMillis();
        for (int player : scheduler.waitingOn()) {
            if (player == localPlayer) {
                continue;
            }
            long heard = lastHeardAt.computeIfAbsent(player, ignored -> now);
            if (now - heard < timeoutMillis) {
                continue;
            }
            // BNE's timeout path is host/network-service adjudicated. A
            // client cannot independently decide that a third player is gone:
            // different local timers would create different command streams.
            if (!relaying) {
                if (player == hostPlayer) {
                    hostLost = true;
                    departures.add(new DepartureEvent(player, playerName(player),
                            GameCommand.DepartureReason.TIMEOUT, 0, true));
                }
                continue;
            }
            dropped.add(player);
            GameCommand quit = GameCommand.quit(player, teammateControlMask(player),
                    GameCommand.DepartureReason.TIMEOUT);
            List<GameCommand> batch = List.of(quit);
            long cycle = scheduler.currentNetCycle();
            scheduler.submit(cycle, player, batch);
            // The host's decision is itself a lockstep packet. Sending it as
            // the absent slot lets every survivor release the same stalled
            // cycle; clients accept that claim only from their trusted relay.
            session.broadcastAs(player, cycle, lastCompletedCycle(), currentHash(), batch);
        }
    }

    /** Active mutual allies receive command authority over the departed slot. */
    private int teammateControlMask(int departed) {
        int mask = 0;
        for (int player = 0; player < world.players().length; player++) {
            if (player != departed && scheduler.isActive(player)
                    && world.isAllied(departed, player)
                    && world.isAllied(player, departed)) {
                mask |= 1 << player;
            }
        }
        return mask;
    }

    private void recordDepartures(List<GameCommand> commands) {
        for (GameCommand command : commands) {
            if (command.kind() != GameCommand.Kind.QUIT) {
                continue;
            }
            session.removePeer(command.player());
            if (command.departureReason() == GameCommand.DepartureReason.BOOTSTRAP) {
                continue;
            }
            boolean wasHost = command.player() == hostPlayer;
            departures.add(new DepartureEvent(command.player(), playerName(command.player()),
                    command.departureReason(), command.departureControlMask(), wasHost));
            if (wasHost && localPlayer != hostPlayer) {
                hostLost = true;
            }
        }
    }

    private String playerName(int player) {
        String name = playerNames.get(player);
        return name == null || name.isBlank() ? "Player " + (player + 1) : name;
    }

    /**
     * Announces an intentional departure before the socket is closed.
     *
     * <p>Five copies mirror the lobby START handshake: this is UDP and the
     * first packet may be the one lost. If all are lost, the host's ordinary
     * 45-second timeout remains the recovery path.
     */
    public void leave() {
        if (leaveSent) {
            return;
        }
        leaveSent = true;
        GameCommand quit = GameCommand.quit(localPlayer, teammateControlMask(localPlayer),
                GameCommand.DepartureReason.LEFT);
        try {
            for (int repeat = 0; repeat < 5; repeat++) {
                session.broadcast(scheduler.scheduledNetCycle(), lastCompletedCycle(),
                        currentHash(), List.of(quit));
            }
        } catch (IOException | RuntimeException unableToAnnounce) {
            // Closing must still succeed. The host timeout is the fallback.
            System.err.println("could not announce departure: " + unableToAnnounce.getMessage());
        }
    }

    /** The most recently completed net cycle, or {@code -1} at the start. */
    private long lastCompletedCycle() {
        return scheduler.currentNetCycle() - 1;
    }

    /** The hash of that cycle, or zero if none has finished. */
    private long currentHash() {
        return ownHashes.getOrDefault(lastCompletedCycle(), 0L);
    }

    /** Takes in whatever has arrived and files it by cycle. */
    private void receive() {
        for (NetworkSession.Batch batch : session.poll()) {
            if (batch.player() == localPlayer) {
                // Our own packet looping back; already submitted.
                continue;
            }
            if (!scheduler.isActive(batch.player())) {
                // A delayed datagram from a departed peer cannot revive it,
                // update liveness, or be amplified by the host relay.
                continue;
            }
            if (relaying) {
                // The host passes on what it is sent, unchanged. Eight players
                // talking directly to each other is twenty-eight links and
                // twenty-eight ways for a cycle to stall; everyone talking to
                // the host is eight, and only the host has to know where
                // anybody is. Forwarded as the bytes that arrived rather than
                // decoded and re-encoded: a relay that re-encodes is a relay
                // that can change what it forwards.
                try {
                    session.relay(batch);
                } catch (IOException unreachable) {
                    // One client that cannot be reached must not stop the
                    // others' commands being passed on. The lockstep layer
                    // already handles a player that has gone quiet.
                    System.err.println("relay failed for player "
                            + batch.player() + ": " + unreachable.getMessage());
                }
            }
            // Anything at all from a machine proves it is alive, including a
            // resend of a batch we already have. Silence is the only thing
            // that counts against a player.
            lastHeardAt.put(batch.player(), System.currentTimeMillis());
            scheduler.submit(batch.netCycle(), batch.player(), batch.commands());
            // Filed under the cycle the sender said its hash describes, not
            // one derived from the batch's own cycle: the two differ by the
            // lag, and by more again once a batch has been resent.
            if (batch.syncHash() != 0L) {
                reportedHashes
                        .computeIfAbsent(batch.hashCycle(), ignored -> new java.util.HashMap<>())
                        .put(batch.player(), batch.syncHash());
                Long ourHash = ownHashes.get(batch.hashCycle());
                if (ourHash != null) {
                    checkHashes(batch.hashCycle(), ourHash);
                }
            }
        }
        receiveChats();
    }

    private void receiveChats() {
        for (NetworkSession.ChatPacket chat : session.drainChats()) {
            if (!scheduler.isActive(chat.player()) || !playerNames.containsKey(chat.player())) {
                continue;
            }
            long prior = lastChatId.getOrDefault(chat.player(), 0L);
            if (chat.id() <= prior) {
                continue;
            }
            lastChatId.put(chat.player(), chat.id());
            if (!withinChatRate(chat.player())) {
                continue;
            }
            if (relaying) {
                try {
                    session.relay(chat);
                } catch (IOException unreachable) {
                    System.err.println("chat relay failed for player " + chat.player()
                            + ": " + unreachable.getMessage());
                }
            }
            if ((chat.recipientMask() & (1 << localPlayer)) != 0) {
                chatEvents.add(new ChatEvent(chat.player(), playerName(chat.player()),
                        chat.recipientMask(), chat.text(), false));
            }
        }
    }

    /**
     * Compares hashes for a cycle.
     *
     * <p>Finding a divergence does not fix anything. It stops the game at the
     * earliest cycle where the machines disagreed, which is the only moment
     * from which the cause can be worked out; carrying on would bury it under
     * thousands of cycles of consequences.
     */
    private void checkHashes(long netCycle, long ourHash) {
        java.util.Map<Integer, Long> theirs = reportedHashes.remove(netCycle);
        if (theirs == null) {
            return;
        }
        for (java.util.Map.Entry<Integer, Long> entry : theirs.entrySet()) {
            // Zero means the sender had not finished a cycle yet.
            if (entry.getValue() != 0L && entry.getValue() != ourHash) {
                desyncCycle = netCycle;
                desyncPlayer = entry.getKey();
                return;
            }
        }
    }

    @Override
    public void close() {
        leave();
        session.close();
        try {
            cycleSink.finished(lastCompletedCycle(), world.cycle(), SyncHash.of(world));
        } catch (RuntimeException failed) {
            System.err.println("multiplayer cycle recorder could not finish: " + failed);
        } finally {
            cycleSink = NO_CYCLE_SINK;
        }
    }
}
