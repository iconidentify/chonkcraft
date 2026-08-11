package net.chonkbase.chonkcraft.engine.network;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The lobby, over real sockets.
 *
 * <p>Loopback rather than a fake transport, because the things that go wrong
 * here are wire things: a packet arriving twice, a reply going to the wrong
 * address, a client that never hears the start. A test with the two halves
 * wired together directly proves the bookkeeping and none of that.
 */
class GameLobbyTest {

    /** Ports well away from the game's own, so a running game cannot join in. */
    private static final int BASE_PORT = 7301;

    /** How long to keep polling before giving up on something happening. */
    private static final long PATIENCE_MILLIS = 4_000L;

    @Test
    @DisplayName("A silent host is reported as a firewall problem, not map progress")
    void aSilentHostGetsAnActionableDiagnosis() {
        long opened = 10_000L;
        long beforeWarning = opened + GameLobby.CONNECTION_WARNING_MILLIS - 1;
        long atWarning = opened + GameLobby.CONNECTION_WARNING_MILLIS;

        assertEquals("", GameLobby.connectionWarning(
                -1, false, opened, opened, 7100, beforeWarning));
        assertEquals("No compatible reply on UDP 7100. Check the host firewall and update both games.",
                GameLobby.connectionWarning(-1, false, opened, opened, 7100, atWarning));
        assertEquals("Map transfer stalled on UDP 7100. Check the host firewall.",
                GameLobby.connectionWarning(1, false, opened, opened, 7100, atWarning));
        assertEquals("", GameLobby.connectionWarning(
                1, true, opened, opened, 7100, atWarning));
    }

    /**
     * Drives every lobby given until the condition holds.
     *
     * <p>The lobbies only make progress when polled, so waiting on a condition
     * means polling while waiting. A plain sleep would deadlock every time.
     */
    private static void pollUntil(String what, BooleanSupplier condition, GameLobby... lobbies)
            throws Exception {
        long deadline = System.currentTimeMillis() + PATIENCE_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            for (GameLobby lobby : lobbies) {
                lobby.poll();
            }
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(5);
        }
        throw new AssertionError("timed out waiting: " + what);
    }

    @Test
    @DisplayName("Joiners are seated in order and everyone is told")
    void joinersAreSeated() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        try (GameLobby host = GameLobby.host("Chris", "garden.pud", 8, BASE_PORT);
                GameLobby first = GameLobby.join("Ann", local, BASE_PORT);
                GameLobby second = GameLobby.join("Bob", local, BASE_PORT)) {

            pollUntil("both joiners seated",
                    () -> host.humanCount() == 3, host, first, second);

            // The host keeps slot zero; the joiners take the next two, in the
            // order they were let in.
            assertEquals(0, host.state().localSlot());
            assertTrue(first.state().localSlot() > 0, "the first joiner got a slot");
            assertTrue(second.state().localSlot() > 0, "the second joiner got a slot");
            assertNotEquals(first.state().localSlot(), second.state().localSlot(),
                    "two players in one slot is the whole thing this must not do");

            // And each of them learns the whole lobby, not only their own line.
            pollUntil("the map reached the joiners",
                    () -> "garden.pud".equals(first.state().map())
                            && "garden.pud".equals(second.state().map()),
                    host, first, second);
            pollUntil("the joiners see three people",
                    () -> countHumans(first) == 3 && countHumans(second) == 3,
                    host, first, second);
            assertEquals("Chris", first.state().slots().get(0).name(),
                    "a joiner should see who is hosting");
        }
    }

    private static int countHumans(GameLobby lobby) {
        return (int) lobby.state().slots().stream()
                .filter(s -> s.occupant() == GameLobby.Occupant.HUMAN)
                .count();
    }

    @Test
    @DisplayName("A different gameplay build is refused before it receives a slot or map")
    void aDifferentBuildCannotEnterADirectGame() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        byte[] map = mapBytes(148_000, 29);
        try (GameLobby host = GameLobby.hostWithBuild("Host", "garden.pud", map, 4,
                    BASE_PORT + 20, "20260810.2");
                GameLobby stale = GameLobby.joinWithBuild("Old client", local, BASE_PORT + 20,
                        ignored -> null, "20260809.14")) {
            pollUntil("the stale build was explicitly refused", stale::updateRequired,
                    host, stale);

            assertEquals(1, host.humanCount(), "a stale client consumed a player slot");
            assertEquals(-1, stale.state().localSlot(), "a stale client was seated");
            assertFalse(stale.state().mapReady(), "map transfer began before compatibility");
            assertEquals("20260810.2", stale.state().requiredBuild());
            assertEquals("20260809.14", stale.state().localBuild());
            assertTrue(stale.state().mapProblem().contains("Quit to the launcher"));
        }
    }

    @Test
    @DisplayName("An exact gameplay build is admitted after a stale peer was refused")
    void anExactBuildStillEnters() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        try (GameLobby host = GameLobby.hostWithBuild("Host", "garden.pud", null, 4,
                    BASE_PORT + 21, "20260810.2");
                GameLobby stale = GameLobby.joinWithBuild("Old", local, BASE_PORT + 21,
                        ignored -> null, "20260809.14");
                GameLobby current = GameLobby.joinWithBuild("Current", local, BASE_PORT + 21,
                        ignored -> null, "20260810.2")) {
            pollUntil("current admitted and stale refused",
                    () -> host.humanCount() == 2 && stale.updateRequired(),
                    host, stale, current);
            assertTrue(current.state().localSlot() > 0);
            assertEquals(2, host.humanCount());
        }
    }

    @Test
    @DisplayName("A client speaking the retired lobby format cannot consume a seat")
    void anOlderWireProtocolIsNotAdmitted() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        try (GameLobby host = GameLobby.hostWithBuild("Host", "garden.pud", null, 4,
                    BASE_PORT + 22, "20260810.2");
                DatagramSocket oldClient = new DatagramSocket()) {
            byte[] name = "Old client".getBytes(StandardCharsets.UTF_8);
            ByteBuffer oldJoin = ByteBuffer.allocate(32);
            oldJoin.putInt(0x57474C59);
            oldJoin.putShort((short) 4);
            oldJoin.put((byte) 1);
            oldJoin.put((byte) name.length);
            oldJoin.put(name);
            byte[] packet = java.util.Arrays.copyOf(oldJoin.array(), oldJoin.position());
            oldClient.send(new DatagramPacket(packet, packet.length, local, BASE_PORT + 22));

            long deadline = System.currentTimeMillis() + 300;
            while (System.currentTimeMillis() < deadline) {
                host.poll();
                Thread.sleep(5);
            }
            assertEquals(1, host.humanCount(), "a retired wire format received a slot");
            assertTrue(host.peers().isEmpty(), "a retired wire endpoint was retained");
        }
    }

    private static byte[] mapBytes(int length, int salt) {
        byte[] bytes = new byte[length];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (i * 31 + salt);
        }
        return bytes;
    }

    @Test
    @DisplayName("The host's changes reach everyone")
    void hostDecidesAndEveryoneIsTold() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        try (GameLobby host = GameLobby.host("Chris", "garden.pud", 8, BASE_PORT + 1);
                GameLobby client = GameLobby.join("Ann", local, BASE_PORT + 1)) {

            pollUntil("seated", () -> host.humanCount() == 2, host, client);
            int seat = client.state().localSlot();

            assertTrue(host.setOccupant(3, GameLobby.Occupant.COMPUTER));
            assertTrue(host.setOccupant(4, GameLobby.Occupant.CLOSED));
            assertTrue(host.setRace(seat, "orc"));

            pollUntil("the client saw the changes",
                    () -> client.state().slots().size() > 4
                            && client.state().slots().get(3).occupant()
                                    == GameLobby.Occupant.COMPUTER
                            && client.state().slots().get(4).occupant()
                                    == GameLobby.Occupant.CLOSED
                            && "orc".equals(client.state().slots().get(seat).race()),
                    host, client);

            // A slot somebody is sitting in is not the host's to overwrite:
            // turning a player into a computer without turning them out first
            // would leave them in a game they are no longer in.
            assertFalse(host.setOccupant(seat, GameLobby.Occupant.COMPUTER),
                    "a seated player must be kicked, not painted over");
        }
    }

    @Test
    @DisplayName("A player can be moved to another slot, colour and all")
    void playersCanBeMoved() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        try (GameLobby host = GameLobby.host("Chris", "garden.pud", 8, BASE_PORT + 2);
                GameLobby client = GameLobby.join("Ann", local, BASE_PORT + 2)) {

            pollUntil("seated", () -> host.humanCount() == 2, host, client);
            int from = client.state().localSlot();
            int to = 6;

            assertTrue(host.move(from, to), "the host should be able to reseat a player");
            assertEquals(GameLobby.Occupant.OPEN,
                    host.state().slots().get(from).occupant(), "the old slot opens");
            assertEquals("Ann", host.state().slots().get(to).name());

            pollUntil("the client learned where it now sits",
                    () -> client.state().localSlot() == to, host, client);

            // And the host still knows where to reach them, which is the part
            // that would silently break the game rather than the lobby.
            assertTrue(host.peers().containsKey(to),
                    "moving a player must move their address with them");
            assertFalse(host.peers().containsKey(from));
        }
    }

    @Test
    @DisplayName("Clients retain the host identity when the host changes slot")
    void aMovedHostIsStillTheTrustedRelay() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        try (GameLobby host = GameLobby.host("Chris", "garden.pud", 8, BASE_PORT + 13);
                GameLobby client = GameLobby.join("Ann", local, BASE_PORT + 13)) {
            pollUntil("seated", () -> host.humanCount() == 2, host, client);

            assertTrue(host.move(0, 6));
            pollUntil("the client learned the host's new slot",
                    () -> client.state().hostSlot() == 6, host, client);

            assertEquals(6, host.state().hostSlot());
            assertTrue(client.peers().containsKey(6));
            assertFalse(client.peers().containsKey(0));
            assertEquals(client.relayAddress(), client.peers().get(6));
        }
    }

    @Test
    @DisplayName("A full lobby says so instead of leaving a joiner waiting")
    void aFullLobbyTurnsPeopleAway() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        // Two slots, both taken by the time the third asks.
        try (GameLobby host = GameLobby.host("Chris", "small.pud", 2, BASE_PORT + 3);
                GameLobby first = GameLobby.join("Ann", local, BASE_PORT + 3);
                GameLobby late = GameLobby.join("Bob", local, BASE_PORT + 3)) {

            pollUntil("the lobby filled", () -> host.humanCount() == 2, host, first);
            pollUntil("the late joiner was turned away",
                    late::wasRejectedAsFull, host, late);
            assertEquals(2, host.humanCount(), "nobody was squeezed in");
        }
    }

    @Test
    @DisplayName("Leaving frees the slot at once")
    void leavingFreesASlot() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        try (GameLobby host = GameLobby.host("Chris", "garden.pud", 8, BASE_PORT + 4)) {
            int seat;
            try (GameLobby client = GameLobby.join("Ann", local, BASE_PORT + 4)) {
                pollUntil("seated", () -> host.humanCount() == 2, host, client);
                seat = client.state().localSlot();
                client.leave();
            }
            pollUntil("the slot opened again",
                    () -> host.state().slots().get(seat).occupant()
                            == GameLobby.Occupant.OPEN, host);
            assertEquals(1, host.humanCount());
        }
    }

    @Test
    @DisplayName("Kicking turns somebody out")
    void kickingWorks() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        try (GameLobby host = GameLobby.host("Chris", "garden.pud", 8, BASE_PORT + 5);
                GameLobby client = GameLobby.join("Ann", local, BASE_PORT + 5)) {
            pollUntil("seated", () -> host.humanCount() == 2, host, client);
            int seat = client.state().localSlot();
            assertTrue(host.kick(seat));
            assertEquals(GameLobby.Occupant.OPEN, host.state().slots().get(seat).occupant());
            assertFalse(host.peers().containsKey(seat), "and forgets where they were");
            assertFalse(host.kick(0), "the host cannot kick itself");
        }
    }

    @Test
    @DisplayName("Start reaches every client")
    void startReachesEveryone() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        try (GameLobby host = GameLobby.host("Chris", "garden.pud", 8, BASE_PORT + 6);
                GameLobby first = GameLobby.join("Ann", local, BASE_PORT + 6);
                GameLobby second = GameLobby.join("Bob", local, BASE_PORT + 6)) {

            pollUntil("everyone seated", () -> host.humanCount() == 3, host, first, second);
            assertFalse(first.isStarted());

            host.start();
            pollUntil("both clients heard the start",
                    () -> first.isStarted() && second.isStarted(), first, second);
            assertTrue(host.isStarted());
        }
    }

    @Test
    @DisplayName("Each side ends up knowing where to send its commands")
    void everyoneKnowsWhereToSend() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        try (GameLobby host = GameLobby.host("Chris", "garden.pud", 8, BASE_PORT + 7);
                GameLobby first = GameLobby.join("Ann", local, BASE_PORT + 7);
                GameLobby second = GameLobby.join("Bob", local, BASE_PORT + 7)) {

            pollUntil("everyone seated", () -> host.humanCount() == 3, host, first, second);

            // The host knows both clients: it relays, so it has to.
            assertEquals(2, host.peers().size(), "the host must be able to reach both clients");

            // A client knows only the host, and that is the design rather than
            // a gap. Two players behind different routers can each reach the
            // host without being able to reach each other.
            assertEquals(1, first.peers().size());
            assertTrue(first.peers().containsKey(0), "and the one it knows is the host");
        }
    }

    @Test
    @DisplayName("A missing map arrives from the host before play")
    void aMissingMapIsTransferred() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        byte[] hosted = mapBytes(125_000, 17);
        try (GameLobby host = GameLobby.host(
                    "Chris", "host-only.pud", hosted, 8, BASE_PORT + 8);
                GameLobby client = GameLobby.join(
                    "Ann", local, BASE_PORT + 8, name -> null)) {

            pollUntil("the client received the whole map",
                    () -> client.state().mapReady() && host.state().allPlayersReady(),
                    host, client);

            assertArrayEquals(hosted, client.mapBytes(),
                    "the client did not receive the exact map the host selected");
            assertEquals(100, client.state().mapPercent(),
                    "a completed transfer still looked incomplete in the lobby");
            assertTrue(client.mapWasTransferred(),
                    "a client with no local copy claimed it used one");
        }
    }

    @Test
    @DisplayName("A different map with the same name is replaced")
    void aMismatchedMapIsTransferred() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        byte[] hosted = mapBytes(91_000, 29);
        byte[] stale = mapBytes(91_000, 30);
        try (GameLobby host = GameLobby.host(
                    "Chris", "same-name.pud", hosted, 8, BASE_PORT + 9);
                GameLobby client = GameLobby.join(
                    "Ann", local, BASE_PORT + 9, name -> stale)) {

            pollUntil("the mismatched copy was replaced",
                    () -> client.state().mapReady() && host.state().allPlayersReady(),
                    host, client);

            assertArrayEquals(hosted, client.mapBytes(),
                    "the filename matched but the client kept different map bytes");
            assertTrue(client.mapWasTransferred(),
                    "a mismatched local copy bypassed the host's transfer");
        }
    }

    @Test
    @DisplayName("An identical local map starts without a transfer")
    void anIdenticalMapIsUsedLocally() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        byte[] hosted = mapBytes(72_000, 41);
        try (GameLobby host = GameLobby.host(
                    "Chris", "shared.pud", hosted, 8, BASE_PORT + 10);
                GameLobby client = GameLobby.join(
                    "Ann", local, BASE_PORT + 10, name -> hosted.clone())) {

            pollUntil("both sides verified the shared map",
                    () -> client.state().mapReady() && host.state().allPlayersReady(),
                    host, client);

            assertArrayEquals(hosted, client.mapBytes(),
                    "the verified local copy changed before play");
            assertFalse(client.mapWasTransferred(),
                    "matching bytes were needlessly downloaded again");
        }
    }

    @Test
    @DisplayName("The host cannot start while a map is still arriving")
    void startWaitsForMapSynchronization() throws Exception {
        InetAddress local = InetAddress.getLoopbackAddress();
        byte[] hosted = mapBytes(180_000, 53);
        try (GameLobby host = GameLobby.host(
                    "Chris", "large.pud", hosted, 8, BASE_PORT + 11);
                GameLobby client = GameLobby.join(
                    "Ann", local, BASE_PORT + 11, name -> null)) {

            pollUntil("the client was seated", () -> host.humanCount() == 2, host, client);
            assertFalse(host.state().allPlayersReady(),
                    "a seated client with no map was treated as ready");

            host.start();
            assertFalse(host.isStarted(),
                    "the game began while one machine still lacked its map");

            pollUntil("the map became ready",
                    () -> host.state().allPlayersReady() && client.state().mapReady(),
                    host, client);
            host.start();
            pollUntil("the ready client heard the start", client::isStarted, host, client);
            assertTrue(host.isStarted(), "the host stayed blocked after every map matched");
        }
    }
}
