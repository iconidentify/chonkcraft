package net.chonkbase.chonkcraft.engine.network;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.ByteBuffer;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.data.map.PudMap;
import net.chonkbase.chonkcraft.engine.Player;
import net.chonkbase.chonkcraft.engine.World;
import net.chonkbase.chonkcraft.engine.animation.Animation;
import net.chonkbase.chonkcraft.engine.animation.AnimationSet;
import net.chonkbase.chonkcraft.engine.map.GameMap;
import net.chonkbase.chonkcraft.engine.map.TileFlag;
import net.chonkbase.chonkcraft.engine.map.Tileset;
import net.chonkbase.chonkcraft.engine.unit.Unit;
import net.chonkbase.chonkcraft.engine.unit.UnitType;
import net.chonkbase.chonkcraft.engine.unit.UnitType.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Tests for command scheduling, the sync hash, and determinism. */
class LockstepTest {

    // ------------------------------------------------------------- the wire

    @Test
    void aCommandSurvivesTheWire() {
        GameCommand original = GameCommand.build(3, 42, 7, 100, 200).withQueued(true);

        ByteBuffer buffer = ByteBuffer.allocate(GameCommand.WIRE_BYTES);
        original.writeTo(buffer);
        assertEquals(GameCommand.WIRE_BYTES, buffer.position(), "wrote the wrong number of bytes");

        buffer.flip();
        assertEquals(original, GameCommand.readFrom(buffer));
    }

    @Test
    void everyKindSurvivesTheWire() {
        List<GameCommand> commands = List.of(
                GameCommand.none(0),
                GameCommand.move(1, 10, 20, 30),
                GameCommand.attack(2, 11, 12),
                GameCommand.stop(3, 13),
                GameCommand.harvest(4, 14, 40, 50),
                GameCommand.build(5, 15, 3, 60, 70),
                GameCommand.train(6, 16, 4),
                GameCommand.follow(6, 16, 17),
                GameCommand.quit(7));

        ByteBuffer buffer = ByteBuffer.allocate(GameCommand.WIRE_BYTES * commands.size());
        commands.forEach(command -> command.writeTo(buffer));
        buffer.flip();
        for (GameCommand expected : commands) {
            assertEquals(expected, GameCommand.readFrom(buffer));
        }
    }

    // -------------------------------------------------------------- schedule

    @Test
    void aCycleWaitsForEveryPlayer() {
        LockstepScheduler scheduler = new LockstepScheduler(3);

        assertFalse(scheduler.isReady(), "nothing has arrived yet");
        scheduler.submitNothing(0, 0);
        scheduler.submitNothing(0, 1);
        assertFalse(scheduler.isReady(), "still waiting on player 2");
        assertEquals(List.of(2), scheduler.waitingOn());

        scheduler.submitNothing(0, 2);
        assertTrue(scheduler.isReady());
    }

    @Test
    void releasingBeforeEveryoneReportsIsAMistake() {
        LockstepScheduler scheduler = new LockstepScheduler(2);
        scheduler.submitNothing(0, 0);
        assertThrows(IllegalStateException.class, scheduler::release);
    }

    @Test
    void commandsComeOutInPlayerOrder() {
        // Two players ordering on the same cycle must resolve identically
        // everywhere, and player order is what guarantees it.
        LockstepScheduler scheduler = new LockstepScheduler(2);
        scheduler.submit(0, 1, List.of(GameCommand.move(1, 20, 5, 5)));
        scheduler.submit(0, 0, List.of(GameCommand.move(0, 10, 5, 5)));

        List<GameCommand> released = scheduler.release();
        assertEquals(2, released.size());
        assertEquals(0, released.get(0).player(), "player 0 goes first regardless of arrival order");
        assertEquals(1, released.get(1).player());
    }

    @Test
    void theCycleAdvancesOnRelease() {
        LockstepScheduler scheduler = new LockstepScheduler(1);
        assertEquals(0, scheduler.currentNetCycle());
        scheduler.submitNothing(0, 0);
        scheduler.release();
        assertEquals(1, scheduler.currentNetCycle());
    }

    @Test
    void commandsAreScheduledAheadByTheLag() {
        // A command issued now runs later on every machine including this one,
        // so the issuer does not act before the others have heard.
        LockstepScheduler scheduler = new LockstepScheduler(2, 5, 2);
        assertEquals(2, scheduler.scheduledNetCycle());

        scheduler.submit(scheduler.scheduledNetCycle(), 0, List.of(GameCommand.move(0, 1, 5, 5)));
        scheduler.submit(scheduler.scheduledNetCycle(), 1, List.of());

        // The next two cycles are empty; the command is not due yet.
        for (long cycle = 0; cycle < 2; cycle++) {
            scheduler.submitNothing(cycle, 0);
            scheduler.submitNothing(cycle, 1);
            assertEquals(List.of(), scheduler.release(), "cycle " + cycle + " should be empty");
        }
        assertEquals(1, scheduler.release().size(), "the command should land on cycle 2");
    }

    @Test
    void aQuittingPlayerStopsBeingWaitedFor() {
        LockstepScheduler scheduler = new LockstepScheduler(3);
        scheduler.submit(0, 2, List.of(GameCommand.quit(2)));
        scheduler.submitNothing(0, 0);
        scheduler.submitNothing(0, 1);
        scheduler.release();

        assertFalse(scheduler.isActive(2));
        // The remaining players alone are now enough.
        scheduler.submitNothing(1, 0);
        scheduler.submitNothing(1, 1);
        assertTrue(scheduler.isReady(), "a departed player must not stall the game");
    }

    @Test
    void aFutureQuitDoesNotRemoveItsPlayerEarly() {
        LockstepScheduler scheduler = new LockstepScheduler(2, 5, 2);
        scheduler.submit(2, 1, List.of(GameCommand.quit(1, 1,
                GameCommand.DepartureReason.LEFT)));

        assertTrue(scheduler.isActive(1), "a scheduled departure took effect before its cycle");
        scheduler.submitNothing(0, 0);
        assertFalse(scheduler.isReady(), "cycle zero stopped waiting for a player still present");
        scheduler.submitNothing(0, 1);
        scheduler.release();
        assertTrue(scheduler.isActive(1));
    }

    @Test
    void netCycleBoundariesFallOnTheUpdateInterval() {
        LockstepScheduler scheduler = new LockstepScheduler(2, 5, 2);
        assertTrue(scheduler.isNetCycleBoundary(0));
        assertFalse(scheduler.isNetCycleBoundary(3));
        assertTrue(scheduler.isNetCycleBoundary(5));
        assertTrue(scheduler.isNetCycleBoundary(30));
    }

    // ------------------------------------------------------------ determinism

    private static GameMap grass(int size) {
        GameMap map = new GameMap(size, size, new Tileset());
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                map.field(x, y).setFlags(TileFlag.LAND_ALLOWED);
            }
        }
        return map;
    }

    private static AnimationSet fighter() {
        AnimationSet set = new AnimationSet("f");
        set.put(AnimationSet.State.STILL, Animation.parse("s",
                List.of("frame 0", "wait 4", "random-goto 50 skip", "label skip", "wait 1")));
        set.put(AnimationSet.State.MOVE, Animation.parse("m",
                List.of("frame 0", "move 16", "wait 1", "frame 5", "move 16", "wait 1")));
        set.put(AnimationSet.State.ATTACK, Animation.parse("a",
                List.of("frame 25", "attack", "wait 2")));
        set.put(AnimationSet.State.DEATH, Animation.parse("d", List.of("frame 50", "wait 1")));
        return set;
    }

    private static UnitType soldier() {
        UnitType type = new UnitType("unit-footman");
        type.setTileSize(1, 1);
        type.setHitPoints(60);
        type.setSpeed(10);
        type.setLandUnit(true);
        type.setSightRange(4);
        type.setCanAttack(true);
        type.setCanTargetLand(true);
        type.setBasicDamage(6);
        type.setMaxAttackRange(1);
        type.setAnimationSet(fighter());
        return type;
    }

    private static Player[] twoPlayers() {
        Player[] players = new Player[Player.MAX];
        for (int i = 0; i < players.length; i++) {
            players[i] = new Player(i,
                    i < 2 ? PudMap.PlayerType.PERSON : PudMap.PlayerType.NOBODY,
                    PudMap.Race.HUMAN);
        }
        return players;
    }

    /** A world with two opposing squads, built identically every time. */
    private static World battlefield() {
        World world = new World(grass(40), twoPlayers());
        UnitType type = soldier();
        for (int i = 0; i < 4; i++) {
            world.createUnit(type, 0, 5 + i, 5);
            world.createUnit(type, 1, 25 + i, 25);
        }
        return world;
    }

    @Test
    void twoMachinesGivenTheSameCommandsStayIdentical() {
        // The whole premise of lockstep. If this fails, a network game
        // diverges silently and players see different outcomes.
        World first = battlefield();
        World second = battlefield();

        List<UnitType> roster = List.of(first.units().getFirst().type());
        CommandApplier firstApplier = new CommandApplier(first, roster);
        CommandApplier secondApplier = new CommandApplier(second, roster);

        // The same script of commands, applied to both.
        List<List<GameCommand>> script = new ArrayList<>();
        script.add(List.of(
                GameCommand.move(0, 1, 20, 20),
                GameCommand.move(1, 2, 15, 15)));
        script.add(List.of(GameCommand.attack(0, 3, 4)));
        script.add(List.of(GameCommand.move(1, 6, 8, 8)));

        for (int step = 0; step < 600; step++) {
            if (step < script.size() * 100 && step % 100 == 0) {
                List<GameCommand> batch = script.get(step / 100);
                firstApplier.applyAll(batch);
                secondApplier.applyAll(batch);
            }
            first.tick();
            second.tick();

            assertEquals(SyncHash.of(first), SyncHash.of(second),
                    "the two worlds diverged at step " + step);
        }
    }

    @Test
    @DisplayName("two real UDP peers stay identical through an 1800-cycle battle")
    void twoNetworkPeersStayInLockstepThroughALongBattle() throws Exception {
        World hostWorld = battlefield();
        World guestWorld = battlefield();
        UnitType hostType = hostWorld.units().getFirst().type();
        UnitType guestType = guestWorld.units().getFirst().type();

        try (NetworkSession hostSession = new NetworkSession(0, 0);
                NetworkSession guestSession = new NetworkSession(1, 0)) {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            hostSession.addPeer(1, loopback, guestSession.localPort());
            guestSession.addPeer(0, loopback, hostSession.localPort());
            NetworkGame host = new NetworkGame(hostWorld, hostSession,
                    new LockstepScheduler(2),
                    new CommandApplier(hostWorld, List.of(hostType)), 0);
            NetworkGame guest = new NetworkGame(guestWorld, guestSession,
                    new LockstepScheduler(2),
                    new CommandApplier(guestWorld, List.of(guestType)), 1);
            host.start();
            guest.start();

            java.util.Set<Long> issued = new java.util.HashSet<>();
            int attempts = 0;
            while ((hostWorld.cycle() < 1_800 || guestWorld.cycle() < 1_800)
                    && attempts++ < 10_000) {
                if (hostWorld.cycle() == guestWorld.cycle()
                        && issued.add(hostWorld.cycle())) {
                    long cycle = hostWorld.cycle();
                    if (cycle == 0) {
                        for (int id : List.of(1, 3, 5, 7)) {
                            host.issue(GameCommand.attackMove(0, id, 20, 20));
                        }
                        for (int id : List.of(2, 4, 6, 8)) {
                            guest.issue(GameCommand.attackMove(1, id, 20, 20));
                        }
                    } else if (cycle == 600) {
                        host.issue(GameCommand.move(0, 1, 30, 10));
                        guest.issue(GameCommand.move(1, 2, 10, 30));
                    } else if (cycle == 1_200) {
                        host.issue(GameCommand.attack(0, 3, 4));
                        guest.issue(GameCommand.attack(1, 4, 3));
                    }
                }

                NetworkGame.Step hostStep = host.update();
                NetworkGame.Step guestStep = guest.update();
                assertNotEquals(NetworkGame.Step.DESYNC, hostStep,
                        "host reported a desync at " + host.desyncCycle());
                assertNotEquals(NetworkGame.Step.DESYNC, guestStep,
                        "guest reported a desync at " + guest.desyncCycle());
                assertTrue(Math.abs(hostWorld.cycle() - guestWorld.cycle()) <= 1,
                        "one peer ran ahead: host=" + hostWorld.cycle()
                                + " guest=" + guestWorld.cycle());
                if (hostWorld.cycle() == guestWorld.cycle()) {
                    assertEquals(SyncHash.of(hostWorld), SyncHash.of(guestWorld),
                            "peer worlds differ at game cycle " + hostWorld.cycle());
                }
            }

            assertEquals(1_800, hostWorld.cycle(), "host did not finish the match");
            assertEquals(1_800, guestWorld.cycle(), "guest did not finish the match");
            assertEquals(SyncHash.of(hostWorld), SyncHash.of(guestWorld));
            assertEquals(-1, host.desyncCycle());
            assertEquals(-1, guest.desyncCycle());
        }
    }

    @Test
    @DisplayName("chat reaches the other peer without changing or waiting on the world")
    void chatIsSideBandAndAuthenticated() throws Exception {
        World hostWorld = battlefield();
        World guestWorld = battlefield();
        UnitType type = hostWorld.units().getFirst().type();
        try (NetworkSession hostSession = new NetworkSession(0, 0);
                NetworkSession guestSession = new NetworkSession(1, 0)) {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            hostSession.addPeer(1, loopback, guestSession.localPort());
            guestSession.addPeer(0, loopback, hostSession.localPort());
            NetworkGame host = new NetworkGame(hostWorld, hostSession,
                    new LockstepScheduler(2),
                    new CommandApplier(hostWorld, List.of(type)), 0);
            NetworkGame guest = new NetworkGame(guestWorld, guestSession,
                    new LockstepScheduler(2),
                    new CommandApplier(guestWorld, List.of(type)), 1);
            host.setPlayerNames(Map.of(0, "Chris", 1, "Alex"));
            guest.setPlayerNames(Map.of(0, "Chris", 1, "Alex"));
            host.setHostPlayer(0);
            guest.setHostPlayer(0);
            host.start();
            guest.start();
            assertTrue(guest.sendChat(1 << 0, "Ready?"));
            assertEquals("Ready?", guest.drainChatEvents().getFirst().text(),
                    "the sender needs an immediate local echo");

            NetworkGame.ChatEvent received = null;
            long deadline = System.currentTimeMillis() + 2_000;
            while (received == null && System.currentTimeMillis() < deadline) {
                host.update();
                guest.update();
                List<NetworkGame.ChatEvent> events = host.drainChatEvents();
                if (!events.isEmpty()) {
                    received = events.getFirst();
                }
            }
            assertEquals("Alex", received.playerName());
            assertEquals("Ready?", received.text());
            assertFalse(received.local());
            assertEquals(hostWorld.cycle(), guestWorld.cycle());
            assertEquals(SyncHash.of(hostWorld), SyncHash.of(guestWorld),
                    "chat put the two deterministic worlds out of step");
        }
    }

    @Test
    @DisplayName("one player cannot flood the match message line")
    void chatBurstIsBounded() throws Exception {
        World world = battlefield();
        UnitType type = world.units().getFirst().type();
        try (NetworkSession session = new NetworkSession(0, 0)) {
            session.addPeer(1, InetAddress.getLoopbackAddress(), 9);
            NetworkGame game = new NetworkGame(world, session, new LockstepScheduler(2),
                    new CommandApplier(world, List.of(type)), 0);
            game.setPlayerNames(Map.of(0, "Chris", 1, "Alex"));
            for (int i = 0; i < 6; i++) {
                assertTrue(game.sendChat(1 << 1, "line " + i));
            }
            assertFalse(game.sendChat(1 << 1, "line 7"));
            assertEquals(6, game.drainChatEvents().size());
        }
    }

    /** One datagram retained by the deterministic adverse-network proxy. */
    private record FaultPacket(byte[] bytes, int destinationPort, int releaseRound) {}

    /**
     * A real UDP hop that drops, reorders, duplicates and delays traffic.
     *
     * <p>Each direction gets the same reproducible fault schedule. Packet one
     * is lost, two is held until three has passed it, four is duplicated and
     * five waits five pump rounds. Everything after that is delivered. The
     * sessions see packets from an ordinary socket and have no test-only
     * transport seam through which they could accidentally bypass the wire.
     */
    private static final class AdverseUdpProxy implements AutoCloseable {
        private final DatagramSocket socket;
        private final int firstPort;
        private final int secondPort;
        private final Map<Integer, Integer> seen = new HashMap<>();
        private final Map<Integer, FaultPacket> held = new HashMap<>();
        private final List<FaultPacket> delayed = new ArrayList<>();
        private int round;
        private int dropped;
        private int reordered;
        private int duplicated;
        private int delayedCount;

        AdverseUdpProxy(int firstPort, int secondPort) throws Exception {
            this.firstPort = firstPort;
            this.secondPort = secondPort;
            socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            socket.setSoTimeout(1);
        }

        int localPort() {
            return socket.getLocalPort();
        }

        void pump() throws Exception {
            round++;
            while (true) {
                byte[] bytes = new byte[1_200];
                DatagramPacket packet = new DatagramPacket(bytes, bytes.length);
                try {
                    socket.receive(packet);
                } catch (SocketTimeoutException empty) {
                    break;
                }
                int source = packet.getPort();
                int destination = source == firstPort ? secondPort : firstPort;
                int ordinal = seen.merge(source, 1, Integer::sum);
                byte[] exact = java.util.Arrays.copyOf(packet.getData(), packet.getLength());
                FaultPacket frame = new FaultPacket(exact, destination, round);
                switch (ordinal) {
                    case 1 -> dropped++;
                    case 2 -> held.put(source, frame);
                    case 3 -> {
                        send(frame);
                        FaultPacket earlier = held.remove(source);
                        if (earlier != null) {
                            send(earlier);
                            reordered++;
                        }
                    }
                    case 4 -> {
                        send(frame);
                        send(frame);
                        duplicated++;
                    }
                    case 5 -> {
                        delayed.add(new FaultPacket(exact, destination, round + 5));
                        delayedCount++;
                    }
                    default -> send(frame);
                }
            }
            for (var iterator = delayed.iterator(); iterator.hasNext();) {
                FaultPacket frame = iterator.next();
                if (frame.releaseRound() <= round) {
                    send(frame);
                    iterator.remove();
                }
            }
        }

        private void send(FaultPacket frame) throws Exception {
            socket.send(new DatagramPacket(frame.bytes(), frame.bytes().length,
                    new InetSocketAddress(InetAddress.getLoopbackAddress(),
                            frame.destinationPort())));
        }

        @Override
        public void close() {
            socket.close();
        }
    }

    @Test
    @DisplayName("two UDP peers converge after 1800 cycles through loss delay duplication and reordering")
    void twoPeersRecoverFromAdverseUdpWithoutDiverging() throws Exception {
        World hostWorld = battlefield();
        World guestWorld = battlefield();
        UnitType hostType = hostWorld.units().getFirst().type();
        UnitType guestType = guestWorld.units().getFirst().type();

        try (NetworkSession hostSession = new NetworkSession(0, 0);
                NetworkSession guestSession = new NetworkSession(1, 0);
                AdverseUdpProxy proxy = new AdverseUdpProxy(
                        hostSession.localPort(), guestSession.localPort())) {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            hostSession.addPeer(1, loopback, proxy.localPort());
            guestSession.addPeer(0, loopback, proxy.localPort());
            NetworkGame host = new NetworkGame(hostWorld, hostSession,
                    new LockstepScheduler(2),
                    new CommandApplier(hostWorld, List.of(hostType)), 0);
            NetworkGame guest = new NetworkGame(guestWorld, guestSession,
                    new LockstepScheduler(2),
                    new CommandApplier(guestWorld, List.of(guestType)), 1);
            host.start();
            guest.start();

            java.util.Set<Long> issued = new java.util.HashSet<>();
            int attempts = 0;
            while ((hostWorld.cycle() < 1_800 || guestWorld.cycle() < 1_800)
                    && attempts++ < 50_000) {
                if (hostWorld.cycle() == guestWorld.cycle()
                        && issued.add(hostWorld.cycle())) {
                    long cycle = hostWorld.cycle();
                    if (cycle == 0) {
                        host.issue(GameCommand.attackMove(0, 1, 20, 20));
                        guest.issue(GameCommand.attackMove(1, 2, 20, 20));
                    } else if (cycle == 600) {
                        host.issue(GameCommand.move(0, 3, 30, 10));
                        guest.issue(GameCommand.move(1, 4, 10, 30));
                    } else if (cycle == 1_200) {
                        host.issue(GameCommand.attack(0, 5, 6));
                        guest.issue(GameCommand.attack(1, 6, 5));
                    }
                }

                proxy.pump();
                NetworkGame.Step hostStep = NetworkGame.Step.WAITING;
                if (hostWorld.cycle() < 1_800) {
                    hostStep = host.update();
                }
                proxy.pump();
                NetworkGame.Step guestStep = NetworkGame.Step.WAITING;
                if (guestWorld.cycle() < 1_800) {
                    guestStep = guest.update();
                }
                proxy.pump();
                assertNotEquals(NetworkGame.Step.DESYNC, hostStep);
                assertNotEquals(NetworkGame.Step.DESYNC, guestStep);
                // Asymmetric loss can leave one peer several batches ahead:
                // it already has both players' commands while the other is
                // waiting for a lost copy. That is safe provided the lagging
                // peer catches up through the same batches and hashes agree
                // whenever the simulation cycles align.
                if (hostWorld.cycle() == guestWorld.cycle()) {
                    assertEquals(SyncHash.of(hostWorld), SyncHash.of(guestWorld),
                            "adverse-network peers differ at " + hostWorld.cycle());
                }
                if (hostStep == NetworkGame.Step.WAITING
                        && guestStep == NetworkGame.Step.WAITING) {
                    Thread.sleep(1);
                }
            }

            assertEquals(1_800, hostWorld.cycle(), "host did not recover and finish");
            assertEquals(hostWorld.cycle(), guestWorld.cycle(),
                    "peers did not converge on the same 1800-cycle prefix");
            assertEquals(SyncHash.of(hostWorld), SyncHash.of(guestWorld));
            assertEquals(2, proxy.dropped, "both directions must actually lose a packet");
            assertEquals(2, proxy.reordered, "both directions must actually reorder packets");
            assertEquals(2, proxy.duplicated, "both directions must actually duplicate a packet");
            assertEquals(2, proxy.delayedCount, "both directions must actually delay a packet");
        }
    }

    @Test
    void theHashNoticesADivergence() {
        // A checksum that never fails is not a checksum.
        World first = battlefield();
        World second = battlefield();
        assertEquals(SyncHash.of(first), SyncHash.of(second), "they should start identical");

        // Nudge one of them.
        Unit unit = second.units().getFirst();
        unit.setHitPoints(unit.hitPoints() - 1);
        assertNotEquals(SyncHash.of(first), SyncHash.of(second), "a lost hit point should show");
    }

    @Test
    void theHashCoversPositionOrdersAndResources() {
        World world = battlefield();
        long start = SyncHash.of(world);

        Unit unit = world.units().getFirst();
        unit.setTile(unit.tileX() + 1, unit.tileY());
        long moved = SyncHash.of(world);
        assertNotEquals(start, moved, "position should be covered");

        world.player(0).add(Resource.GOLD, 100);
        assertNotEquals(moved, SyncHash.of(world), "the bank should be covered");
    }

    @Test
    void theHashCoversTheSynchronizedRandomStream() {
        World first = battlefield();
        World second = battlefield();
        assertEquals(SyncHash.of(first), SyncHash.of(second));

        first.syncRand();

        assertNotEquals(SyncHash.of(first), SyncHash.of(second),
                "different future random results must be reported as a desync");
    }

    @Test
    void aLatePeerHashIsComparedToHistory() throws Exception {
        World hostWorld = battlefield();
        World guestWorld = battlefield();
        UnitType type = hostWorld.units().getFirst().type();

        try (NetworkSession hostSession = new NetworkSession(0, 0);
                NetworkSession guestSession = new NetworkSession(1, 0)) {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            hostSession.addPeer(1, loopback, guestSession.localPort());
            guestSession.addPeer(0, loopback, hostSession.localPort());

            NetworkGame host = new NetworkGame(hostWorld, hostSession,
                    new LockstepScheduler(2, 1, 0),
                    new CommandApplier(hostWorld, List.of(type)), 0);
            NetworkGame guest = new NetworkGame(guestWorld, guestSession,
                    new LockstepScheduler(2, 1, 0),
                    new CommandApplier(guestWorld, List.of(type)), 1);

            assertEquals(NetworkGame.Step.WAITING, host.update());
            guestWorld.units().getFirst().setHitPoints(1);
            assertEventuallyAdvanced(guest);
            assertEventuallyAdvanced(host);

            // The host has already completed cycle zero. Its next batch
            // carries that old hash to the guest, which must compare it when
            // it arrives rather than waiting for cycle zero to complete again.
            assertEquals(NetworkGame.Step.WAITING, host.update());
            assertEventuallyDesynced(guest);
            assertEquals(0, guest.desyncCycle());
            assertEquals(0, guest.desyncPlayer());
        }
    }

    private static void assertEventuallyAdvanced(NetworkGame game) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            NetworkGame.Step step = game.update();
            if (step == NetworkGame.Step.ADVANCED) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("network game never advanced");
    }

    private static void assertEventuallyDesynced(NetworkGame game) throws Exception {
        for (int attempt = 0; attempt < 200; attempt++) {
            if (game.update() == NetworkGame.Step.DESYNC) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("late hash was never compared");
    }

    // --------------------------------------------------------- staying alive

    @Test
    @DisplayName("a large selection given one order does not kill the game")
    void anOversizedBatchGoesOutOverSeveralCycles() throws Exception {
        // Box-select two hundred units and give them one order. The batch was
        // too big for a datagram, NetworkSession threw, the exception escaped
        // update() into the frame loop, and the thread that draws the window
        // died: the game froze solid with no message and no way out. There is
        // no selection cap, so any player with a large army could reach it.
        World world = new World(grass(64), twoPlayers());
        UnitType type = soldier();
        List<Unit> army = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            army.add(world.createUnit(type, 0, 1 + i % 60, 1 + i / 60));
        }
        assertTrue(army.size() > NetworkSession.MAX_COMMANDS_PER_BATCH,
                "the fixture must exceed one packet or it proves nothing");

        // A peer that only listens, so this measures what actually left the
        // machine. Asserting on the local world alone would pass even when
        // every packet failed to go out, which is exactly the state the bug
        // left the game in.
        try (NetworkSession session = new NetworkSession(0, 0);
                NetworkSession listener = new NetworkSession(1, 0)) {
            session.addPeer(1, InetAddress.getLoopbackAddress(), listener.localPort());
            NetworkGame game = new NetworkGame(world, session,
                    new LockstepScheduler(1, 1, 0),
                    new CommandApplier(world, List.of(type)), 0);
            game.start();
            for (Unit unit : army) {
                game.issue(GameCommand.move(0, unit.id(), 40, 40));
            }

            int arrived = 0;
            java.util.Set<Integer> moved = new java.util.HashSet<>();
            for (int step = 0; step < 50; step++) {
                game.update();
                for (Unit unit : army) {
                    if (unit.order() == Unit.Order.MOVE) {
                        moved.add(unit.id());
                    }
                }
                for (NetworkSession.Batch batch : listener.poll()) {
                    arrived += batch.commands().size();
                }
            }
            assertEquals(0, game.queuedCommandCount(), "some orders never went out");
            assertEquals(200, arrived,
                    "orders past the packet limit never reached the other machine");
            assertEquals(200, moved.size(),
                    "orders past the packet limit were dropped rather than delayed");
        }
    }

    @Test
    @DisplayName("one packet holds exactly as many commands as the budget allows")
    void thePacketBudgetIsExactAndRefusesOneMore() throws Exception {
        try (NetworkSession session = new NetworkSession(0, 0)) {
            List<GameCommand> full = new ArrayList<>();
            for (int i = 0; i < NetworkSession.MAX_COMMANDS_PER_BATCH; i++) {
                full.add(GameCommand.move(0, i, 1, 1));
            }
            // No peers registered, so this is the encoder and its budget alone.
            session.broadcast(0, -1, 0, full);

            List<GameCommand> tooMany = new ArrayList<>(full);
            tooMany.add(GameCommand.move(0, 999, 1, 1));
            assertThrows(IllegalArgumentException.class,
                    () -> session.broadcast(0, -1, 0, tooMany),
                    "a batch over the budget must be refused, not silently truncated");
        }
    }

    @Test
    @DisplayName("a player who closes their laptop stops holding up the match")
    void aSilentPlayerIsForceQuitAndTheOthersCarryOn() throws Exception {
        // There was no timeout at all. active[player] was cleared only by an
        // explicit QUIT, so a machine that simply stopped speaking was waited
        // for forever: a three-machine probe measured the two survivors
        // getting one lag window further and then standing still for good.
        World world = battlefield();
        UnitType type = world.units().getFirst().type();

        try (NetworkSession session = new NetworkSession(0, 0)) {
            NetworkGame game = new NetworkGame(world, session,
                    new LockstepScheduler(2, 1, 2),
                    new CommandApplier(world, List.of(type)), 0);
            game.setRelaying(true);
            game.setHostPlayer(0);
            game.start();

            // Player 1 is never heard from. With upstream's own timeout the
            // game must wait, or the drop below would prove nothing about
            // the timeout being what did it.
            long stubbornUntil = System.currentTimeMillis() + 150;
            while (System.currentTimeMillis() < stubbornUntil) {
                game.update();
            }
            assertEquals(NetworkGame.Step.WAITING, game.update(),
                    "the game gave up on a peer far too early");
            assertEquals(List.of(1), game.waitingOn());
            assertTrue(game.millisBeforeDropping(1) > 0, "there is no countdown to report");

            game.setTimeoutMillis(50);
            long advanced = 0;
            long deadline = System.currentTimeMillis() + 5_000;
            while (advanced < 20 && System.currentTimeMillis() < deadline) {
                if (game.update() == NetworkGame.Step.ADVANCED) {
                    advanced++;
                }
            }
            assertEquals(List.of(1), game.droppedPlayers(),
                    "the silent player was never given up on");
            assertEquals(20, advanced, "the survivors never got past the cycle they were waiting on");
            assertFalse(game.scheduler().isActive(1), "the dropped player is still being waited for");
        }
    }

    @Test
    @DisplayName("a resend of a cycle already played is not filed away forever")
    void aLateResendIsDroppedRatherThanStored() {
        // reportedHashes and pending both grew for the life of a match: a
        // resend for a released cycle re-created an entry that nothing would
        // ever release. Nothing visible in a short game; a long one slowly
        // used more memory and walked a longer map every cycle.
        LockstepScheduler scheduler = new LockstepScheduler(2, 1, 0);
        scheduler.submitNothing(0, 0);
        scheduler.submitNothing(0, 1);
        scheduler.release();
        assertEquals(0, scheduler.bufferedCycles());

        scheduler.submit(0, 1, List.of(GameCommand.move(1, 5, 5, 5)));
        assertEquals(0, scheduler.bufferedCycles(),
                "a batch for a cycle already played was filed under a cycle nobody will read");
    }

    @Test
    void theHashIgnoresPresentation() {
        // Two machines with different window sizes or animation phases must
        // still agree, so only simulation state may be hashed.
        World world = battlefield();
        long before = SyncHash.of(world);

        world.units().getFirst().setSelected(true);
        world.units().getFirst().setFrame(25);
        assertEquals(before, SyncHash.of(world),
                "selection and animation frame are not simulation state");
    }

    // --------------------------------------------------------- applying them

    @Test
    void aCommandNamingSomeoneElsesUnitIsIgnored() {
        // Commands arrive from other machines, so they cannot be trusted.
        World world = battlefield();
        CommandApplier applier = new CommandApplier(world, List.of(soldier()));

        Unit mine = world.units().getFirst();
        assertEquals(0, mine.player());
        applier.apply(GameCommand.move(1, mine.id(), 30, 30));
        assertEquals(Unit.Order.STILL, mine.order(), "player 1 must not move player 0's unit");
    }

    @Test
    void aMutualAllyCanCommandADepartedSlotWithoutTakingOwnership() {
        World world = battlefield();
        world.setAllied(0, 1, true);
        world.setAllied(1, 0, true);
        CommandApplier applier = new CommandApplier(world, List.of(soldier()));
        Unit departed = world.units().getFirst();
        world.player(0).add(Resource.GOLD, 777);

        assertTrue(applier.apply(GameCommand.quit(0, 1 << 1,
                GameCommand.DepartureReason.LEFT)));
        assertTrue(applier.apply(GameCommand.move(1, departed.id(), 30, 30)));

        assertEquals(Unit.Order.MOVE, departed.order());
        assertEquals(0, departed.player(), "shared control must not recolour or convert the unit");
        assertTrue(world.sharesVisionWith(1, 0),
                "a controller cannot use forces hidden by the departed slot's fog");
        assertEquals(777, world.player(0).get(Resource.GOLD),
                "the departed player's economy must remain in its own slot");
        assertEquals(0, world.player(1).get(Resource.GOLD),
                "control must not merge teammates' resource banks");
    }

    @Test
    void aQuitWithoutAControlGrantLeavesTheArmyInertToOthers() {
        World world = battlefield();
        CommandApplier applier = new CommandApplier(world, List.of(soldier()));
        Unit departed = world.units().getFirst();

        applier.apply(GameCommand.quit(0, 0, GameCommand.DepartureReason.TIMEOUT));

        assertFalse(applier.apply(GameCommand.move(1, departed.id(), 30, 30)));
        assertEquals(Unit.Order.STILL, departed.order());
    }

    @Test
    void aHostAdjudicatedTimeoutReachesEverySurvivor() throws Exception {
        World hostWorld = battlefield();
        World guestWorld = battlefield();
        UnitType type = hostWorld.units().getFirst().type();
        try (NetworkSession hostSession = new NetworkSession(0, 0);
                NetworkSession guestSession = new NetworkSession(1, 0)) {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            hostSession.addPeer(1, loopback, guestSession.localPort());
            guestSession.addPeer(0, loopback, hostSession.localPort());
            guestSession.setTrustedRelay(new InetSocketAddress(loopback, hostSession.localPort()));

            NetworkGame host = new NetworkGame(hostWorld, hostSession,
                    new LockstepScheduler(3, 1, 0),
                    new CommandApplier(hostWorld, List.of(type)), 0);
            NetworkGame guest = new NetworkGame(guestWorld, guestSession,
                    new LockstepScheduler(3, 1, 0),
                    new CommandApplier(guestWorld, List.of(type)), 1);
            host.setRelaying(true);
            host.setHostPlayer(0);
            guest.setHostPlayer(0);
            host.setTimeoutMillis(25);
            guest.setTimeoutMillis(25);
            host.start();
            guest.start();

            long deadline = System.currentTimeMillis() + 2_000;
            while ((host.scheduler().isActive(2) || guest.scheduler().isActive(2))
                    && System.currentTimeMillis() < deadline) {
                host.update();
                guest.update();
                Thread.sleep(2);
            }

            assertEquals(List.of(2), host.droppedPlayers());
            assertFalse(host.scheduler().isActive(2));
            assertFalse(guest.scheduler().isActive(2));
            assertEquals(GameCommand.DepartureReason.TIMEOUT,
                    guest.drainDepartureEvents().getFirst().reason());
            assertEquals(SyncHash.of(hostWorld), SyncHash.of(guestWorld));
        }
    }

    @Test
    void anIntentionalClientExitIsAnnouncedWithoutWaitingForTimeout() throws Exception {
        World hostWorld = battlefield();
        World guestWorld = battlefield();
        UnitType type = hostWorld.units().getFirst().type();
        try (NetworkSession hostSession = new NetworkSession(0, 0);
                NetworkSession guestSession = new NetworkSession(1, 0)) {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            hostSession.addPeer(1, loopback, guestSession.localPort());
            guestSession.addPeer(0, loopback, hostSession.localPort());
            NetworkGame host = new NetworkGame(hostWorld, hostSession,
                    new LockstepScheduler(2, 1, 0),
                    new CommandApplier(hostWorld, List.of(type)), 0);
            NetworkGame guest = new NetworkGame(guestWorld, guestSession,
                    new LockstepScheduler(2, 1, 0),
                    new CommandApplier(guestWorld, List.of(type)), 1);
            host.setRelaying(true);
            host.setHostPlayer(0);
            guest.setHostPlayer(0);
            host.start();
            guest.start();

            guest.leave();
            long deadline = System.currentTimeMillis() + 1_000;
            while (host.scheduler().isActive(1) && System.currentTimeMillis() < deadline) {
                host.update();
                Thread.sleep(2);
            }

            assertFalse(host.scheduler().isActive(1));
            NetworkGame.DepartureEvent event = host.drainDepartureEvents().getFirst();
            assertEquals(GameCommand.DepartureReason.LEFT, event.reason());
            assertFalse(event.hostLeft());
            assertEquals(List.of(), host.droppedPlayers(),
                    "a graceful exit was mislabeled as a timeout");
        }
    }

    @Test
    void aHostExitEndsTheClientMatchClearly() throws Exception {
        World hostWorld = battlefield();
        World guestWorld = battlefield();
        UnitType type = hostWorld.units().getFirst().type();
        try (NetworkSession hostSession = new NetworkSession(0, 0);
                NetworkSession guestSession = new NetworkSession(1, 0)) {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            hostSession.addPeer(1, loopback, guestSession.localPort());
            guestSession.addPeer(0, loopback, hostSession.localPort());
            NetworkGame host = new NetworkGame(hostWorld, hostSession,
                    new LockstepScheduler(2, 1, 0),
                    new CommandApplier(hostWorld, List.of(type)), 0);
            NetworkGame guest = new NetworkGame(guestWorld, guestSession,
                    new LockstepScheduler(2, 1, 0),
                    new CommandApplier(guestWorld, List.of(type)), 1);
            host.setRelaying(true);
            host.setHostPlayer(0);
            guest.setHostPlayer(0);
            host.start();
            guest.start();

            host.leave();
            NetworkGame.Step step = NetworkGame.Step.WAITING;
            long deadline = System.currentTimeMillis() + 1_000;
            while (step != NetworkGame.Step.HOST_LEFT
                    && System.currentTimeMillis() < deadline) {
                step = guest.update();
                Thread.sleep(2);
            }

            assertEquals(NetworkGame.Step.HOST_LEFT, step);
            NetworkGame.DepartureEvent event = guest.drainDepartureEvents().getFirst();
            assertTrue(event.hostLeft());
            assertEquals(GameCommand.DepartureReason.LEFT, event.reason());
        }
    }

    @Test
    void aCommandNamingADeadUnitIsIgnored() {
        World world = battlefield();
        CommandApplier applier = new CommandApplier(world, List.of(soldier()));

        Unit unit = world.units().getFirst();
        int id = unit.id();
        world.kill(unit);
        // Must not throw; by the time a command crosses the network its unit
        // may well be gone.
        applier.apply(GameCommand.move(0, id, 30, 30));
    }

    @Test
    void aValidCommandIsObeyed() {
        World world = battlefield();
        CommandApplier applier = new CommandApplier(world, List.of(soldier()));

        Unit unit = world.units().getFirst();
        applier.apply(GameCommand.move(0, unit.id(), 20, 20));
        assertEquals(Unit.Order.MOVE, unit.order());
    }

    @Test
    void aQueuedMoveWaitsForTheFirstWaypoint() {
        World world = new World(grass(40), twoPlayers());
        UnitType type = soldier();
        Unit unit = world.createUnit(type, 0, 5, 5);
        CommandApplier applier = new CommandApplier(world, List.of(type));

        applier.apply(GameCommand.move(0, unit.id(), 25, 5));
        applier.apply(GameCommand.move(0, unit.id(), 5, 25).withQueued(true));

        boolean reachedFirst = false;
        for (int cycle = 0; cycle < 5000; cycle++) {
            world.tick();
            reachedFirst |= unit.tileX() == 25 && unit.tileY() == 5;
            if (reachedFirst && unit.order() == Unit.Order.STILL
                    && unit.tileX() == 5 && unit.tileY() == 25) {
                break;
            }
        }
        assertTrue(reachedFirst, "the second click replaced rather than queued behind the first");
        assertEquals(5, unit.tileX());
        assertEquals(25, unit.tileY());
    }

    @Test
    void unitTypesTravelAsStableIndices() {
        World world = battlefield();
        UnitType type = soldier();
        CommandApplier applier = new CommandApplier(world, List.of(type));

        assertEquals(0, applier.indexOf(type));
        assertEquals(type, applier.typeAt(0));
        assertEquals(null, applier.typeAt(99), "an out-of-range index must not throw");
    }
}
