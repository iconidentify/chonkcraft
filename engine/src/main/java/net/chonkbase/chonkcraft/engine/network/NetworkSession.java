package net.chonkbase.chonkcraft.engine.network;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Carries command batches between machines over UDP.
 *
 * <p>UDP because that is what LegacyEngine uses and what the traffic wants: a
 * batch is worthless once its cycle has passed, so TCP's retransmission would
 * hold up the newer batches behind a stale one. Loss is handled at the
 * lockstep layer instead, by refusing to advance until a cycle is complete and
 * asking again if it does not arrive.
 *
 * <p>Every packet carries its net cycle and the sender's sync hash, so a
 * divergence is caught at the earliest cycle where the two disagree rather
 * than whenever somebody notices the game looks wrong.
 */
public final class NetworkSession implements Closeable {

    /** Marks a packet as ours and pins the wire format. */
    private static final int MAGIC = 0x57475553; // "WGUS"
    private static final int VERSION = 2;

    /** Side-band player conversation, deliberately outside the lockstep stream. */
    public static final int CHAT_MAGIC = 0x43484354; // "CHCT"
    private static final int CHAT_VERSION = 1;
    private static final int CHAT_HEADER_BYTES = 4 + 2 + 1 + 1 + 8 + 2 + 2;

    /** Bounded both for the old game's compact message line and for hostile input. */
    public static final int MAX_CHAT_UTF8_BYTES = 384;

    private static final int HEADER_BYTES = 4 + 2 + 1 + 1 + 8 + 8 + 8 + 2;
    private static final int MAX_PACKET_BYTES = 1200;

    /**
     * Maximum receive work one simulation tick may perform.
     *
     * <p>Draining until the socket observes a quiet millisecond lets a resend
     * storm monopolise the game thread indefinitely. That presents as a black
     * or frozen client even though the process and network are still alive.
     * Packets beyond this budget remain in the socket for the next tick; the
     * lockstep resend path already recovers ordinary UDP loss.
     */
    static final int MAX_DATAGRAMS_PER_POLL = 128;

    /**
     * How many commands fit in one packet.
     *
     * <p>Derived rather than written down, and that is the point. It used to
     * be discovered only by overflowing: box-selecting a large army and giving
     * it one order built a batch too big for a datagram,
     * {@link #broadcast} threw out of the game loop, and the thread that draws
     * the window died -- the game froze solid with no message and no way out.
     * The measured limit was 72 when the fault was found and 68 by the time it
     * was fixed, because {@code GameCommand} had grown a byte in between. A
     * number kept in a comment would have been wrong again by now.
     *
     * <p>{@code MaxNetworkCommands} upstream, used the same way: fill a packet
     * to the limit and leave the rest for the next cycle
     *
     * Nothing is dropped and nothing throws.
     */
    public static final int MAX_COMMANDS_PER_BATCH =
            (MAX_PACKET_BYTES - HEADER_BYTES) / GameCommand.WIRE_BYTES;

    /**
     * A batch that arrived.
     *
     * @param netCycle  the cycle these commands run on
     * @param hashCycle the cycle {@code syncHash} describes. Carried
     *                  explicitly rather than inferred, because a batch is
     *                  scheduled several cycles ahead while its hash describes
     *                  a cycle already finished, and resending a batch later
     *                  moves the two further apart still. Inferring it is an
     *                  off-by-lag error that surfaces as a false desync.
     * @param raw       the bytes it arrived in, kept so the host can pass it
     *                  on without decoding and re-encoding it. A relay that
     *                  re-encodes is a relay that can change what it forwards.
     */
    public record Batch(long netCycle, int player, long hashCycle, long syncHash,
            List<GameCommand> commands, byte[] raw) {}

    /**
     * A player message carried beside, never inside, deterministic commands.
     *
     * @param recipientMask bit N means player N should see it
     * @param id monotonically increasing for one sender, used to discard UDP repeats
     */
    public record ChatPacket(int player, long id, int recipientMask, String text, byte[] raw) {}

    private final DatagramSocket socket;
    private final int localPlayer;
    private final Map<Integer, SocketAddress> peers = new LinkedHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ArrayDeque<ChatPacket> chats = new ArrayDeque<>();

    /**
     * The host address a client trusts to relay another slot's packet.
     *
     * <p>Without this check any UDP sender could put another player's number
     * in the header and issue orders or manufacture a disconnect for them.
     * A host accepts a claimed player only from that player's registered
     * address; a client additionally accepts it from this one relay.
     */
    private SocketAddress trustedRelay;

    /**
     * @param localPlayer which slot this machine plays
     * @param bindPort    the implementation to listen on, or 0 for any
     */
    public NetworkSession(int localPlayer, int bindPort) throws IOException {
        this(localPlayer, new DatagramSocket(bindPort));
    }

    /**
     * Takes over a socket somebody else opened.
     *
     * <p>The lobby uses this to hand the game the socket it did its own
     * talking on. Closing the lobby's socket and binding the same port again
     * leaves a window where the implementation is free, and on a machine running two
     * copies of the game the other one takes it.
     */
    public NetworkSession(int localPlayer, DatagramSocket socket) throws IOException {
        this.localPlayer = localPlayer;
        this.socket = socket;
        // Short timeout so a receive loop can poll without blocking the tick.
        this.socket.setSoTimeout(1);
    }

    /** The implementation actually bound, useful when 0 was requested. */
    public int localPort() {
        return socket.getLocalPort();
    }

    public int localPlayer() {
        return localPlayer;
    }

    /** Registers where a player can be reached. */
    public void addPeer(int player, InetAddress address, int port) {
        addPeer(player, new InetSocketAddress(address, port));
    }

    /** Registers a peer supplied by either the native UDP or central relay transport. */
    public void addPeer(int player, SocketAddress address) {
        peers.put(player, address);
    }

    /** The peers registered so far. */
    public Map<Integer, SocketAddress> peers() {
        return peers;
    }

    /** Allows packets for other slots only when they came through this host. */
    public void setTrustedRelay(SocketAddress trustedRelay) {
        this.trustedRelay = trustedRelay;
    }

    /**
     * Sends a batch to every peer.
     *
     * <p>Sent even when the command list is empty: silence and a lost packet
     * look the same to the other side, so a player with nothing to say has to
     * say so.
     */
    public void broadcast(long netCycle, long hashCycle, long syncHash, List<GameCommand> commands)
            throws IOException {
        broadcastAs(localPlayer, netCycle, hashCycle, syncHash, commands);
    }

    /**
     * Sends a conversation packet independently of the simulation clock.
     *
     * <p>Three identical datagrams make an ordinary isolated UDP loss invisible;
     * the receiver identifies them by sender and id. Chat must never hold the
     * simulation hostage, so it has no lockstep acknowledgement or wait state.
     */
    public void broadcastChat(long id, int recipientMask, String text) throws IOException {
        broadcastChat(id, recipientMask, text, false);
    }

    /**
     * @param directToRecipients true on the host, which has every destination;
     *        false on a client, which must reach the host even when the host is
     *        not one of the selected readers
     */
    void broadcastChat(long id, int recipientMask, String text, boolean directToRecipients)
            throws IOException {
        String safe = sanitizeChat(text);
        if (safe.isEmpty() || recipientMask == 0) {
            return;
        }
        byte[] utf8 = safe.getBytes(StandardCharsets.UTF_8);
        ByteBuffer buffer = ByteBuffer.allocate(CHAT_HEADER_BYTES + utf8.length);
        buffer.putInt(CHAT_MAGIC);
        buffer.putShort((short) CHAT_VERSION);
        buffer.put((byte) localPlayer);
        buffer.put((byte) 0);
        buffer.putLong(id);
        buffer.putShort((short) recipientMask);
        buffer.putShort((short) utf8.length);
        buffer.put(utf8);
        byte[] bytes = buffer.array();
        for (int repeat = 0; repeat < 3; repeat++) {
            for (Map.Entry<Integer, SocketAddress> peer : peers.entrySet()) {
                if (!directToRecipients || (recipientMask & (1 << peer.getKey())) != 0) {
                    socket.send(new DatagramPacket(bytes, bytes.length, peer.getValue()));
                }
            }
        }
    }

    /** Removes controls, normalises whitespace, and enforces the UTF-8 wire budget. */
    public static String sanitizeChat(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder clean = new StringBuilder();
        boolean space = false;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint)) {
                if (codePoint == '\t' || codePoint == '\n' || codePoint == '\r') {
                    space = clean.length() > 0;
                }
                continue;
            }
            if (Character.isWhitespace(codePoint)) {
                space = clean.length() > 0;
                continue;
            }
            if (space) {
                clean.append(' ');
                space = false;
            }
            clean.appendCodePoint(codePoint);
            while (clean.toString().getBytes(StandardCharsets.UTF_8).length
                    > MAX_CHAT_UTF8_BYTES) {
                clean.setLength(clean.offsetByCodePoints(clean.length(), -1));
                return clean.toString().stripTrailing();
            }
        }
        return clean.toString().strip();
    }

    /**
     * Sends a host-adjudicated batch on behalf of a silent player.
     *
     * <p>Package-private by design: only the lockstep host may synthesize the
     * absent player's QUIT. Ordinary callers always use {@link #broadcast}.
     */
    void broadcastAs(int player, long netCycle, long hashCycle, long syncHash,
            List<GameCommand> commands) throws IOException {
        // An invariant the caller now guarantees: NetworkGame takes at most
        // MAX_COMMANDS_PER_BATCH off its queue and leaves the rest for the
        // next net cycle. This stays because a silent truncation here would
        // apply half a player's order on one machine and all of it on
        // another, which is a desync rather than a dropped click.
        if (commands.size() > MAX_COMMANDS_PER_BATCH) {
            throw new IllegalArgumentException(
                    "batch of " + commands.size() + " commands exceeds the packet budget of "
                            + MAX_COMMANDS_PER_BATCH);
        }
        int size = HEADER_BYTES + commands.size() * GameCommand.WIRE_BYTES;

        ByteBuffer buffer = ByteBuffer.allocate(size);
        buffer.putInt(MAGIC);
        buffer.putShort((short) VERSION);
        buffer.put((byte) player);
        buffer.put((byte) 0); // reserved, keeps the header aligned
        buffer.putLong(netCycle);
        buffer.putLong(hashCycle);
        buffer.putLong(syncHash);
        buffer.putShort((short) commands.size());
        for (GameCommand command : commands) {
            command.writeTo(buffer);
        }

        byte[] bytes = buffer.array();
        for (SocketAddress peer : peers.values()) {
            socket.send(new DatagramPacket(bytes, bytes.length, peer));
        }
    }

    /**
     * Collects whatever has arrived, without blocking.
     *
     * <p>Malformed and foreign packets are dropped silently. Anything can
     * arrive on a UDP port, and a game should not fall over because something
     * else on the network found it.
     */
    public List<Batch> poll() {
        List<Batch> received = new ArrayList<>();
        byte[] buffer = new byte[MAX_PACKET_BYTES];

        for (int datagrams = 0;
                datagrams < MAX_DATAGRAMS_PER_POLL && !closed.get();
                datagrams++) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                break;
            } catch (IOException e) {
                break;
            }
            ChatPacket chat = decodeChat(packet);
            if (chat != null && trustedSource(chat.player(), packet.getSocketAddress())) {
                chats.add(chat);
                continue;
            }
            Batch batch = decode(packet);
            if (batch != null && !trustedSource(batch.player(), packet.getSocketAddress())) {
                batch = null;
            }
            if (batch != null) {
                received.add(batch);
            }
        }
        return received;
    }

    /** Conversation packets collected during the latest transport polls. */
    public List<ChatPacket> drainChats() {
        List<ChatPacket> received = new ArrayList<>(chats);
        chats.clear();
        return List.copyOf(received);
    }

    private boolean trustedSource(int claimedPlayer, SocketAddress source) {
        // A transport-only listener used by diagnostics has no topology and
        // therefore cannot authenticate. Real games always register either a
        // peer or a relay during lobby handoff.
        if (peers.isEmpty() && trustedRelay == null) {
            return true;
        }
        SocketAddress direct = peers.get(claimedPlayer);
        return source.equals(direct) || source.equals(trustedRelay);
    }

    private static Batch decode(DatagramPacket packet) {
        if (packet.getLength() < HEADER_BYTES) {
            return null;
        }
        ByteBuffer in = ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength());
        if (in.getInt() != MAGIC || in.getShort() != VERSION) {
            return null;
        }
        int player = in.get() & 0xFF;
        in.get(); // reserved
        long netCycle = in.getLong();
        long hashCycle = in.getLong();
        long syncHash = in.getLong();
        int count = in.getShort() & 0xFFFF;

        if (in.remaining() < count * GameCommand.WIRE_BYTES) {
            // Truncated: better to drop it and let the cycle time out than to
            // apply half a batch and desync.
            return null;
        }
        List<GameCommand> commands = new ArrayList<>(count);
        try {
            for (int i = 0; i < count; i++) {
                GameCommand command = GameCommand.readFrom(in);
                if (command.player() != player) {
                    // The authenticated packet owner may command a departed
                    // ally through the authority mask, but it may never claim
                    // to be that ally inside the command itself.
                    return null;
                }
                commands.add(command);
            }
        } catch (RuntimeException e) {
            return null;
        }
        byte[] raw = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), raw, 0, packet.getLength());
        return new Batch(netCycle, player, hashCycle, syncHash, commands, raw);
    }

    private static ChatPacket decodeChat(DatagramPacket packet) {
        if (packet.getLength() < CHAT_HEADER_BYTES) {
            return null;
        }
        ByteBuffer in = ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength());
        if (in.getInt() != CHAT_MAGIC || in.getShort() != CHAT_VERSION) {
            return null;
        }
        int player = in.get() & 0xFF;
        in.get();
        long id = in.getLong();
        int recipientMask = in.getShort() & 0xFFFF;
        int length = in.getShort() & 0xFFFF;
        if (id <= 0 || recipientMask == 0 || length == 0
                || length > MAX_CHAT_UTF8_BYTES || in.remaining() != length) {
            return null;
        }
        byte[] bytes = new byte[length];
        in.get(bytes);
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, decoded.getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        String safe = sanitizeChat(decoded);
        if (safe.isEmpty() || !safe.equals(decoded)) {
            return null;
        }
        byte[] raw = new byte[packet.getLength()];
        System.arraycopy(packet.getData(), packet.getOffset(), raw, 0, packet.getLength());
        return new ChatPacket(player, id, recipientMask, safe, raw);
    }

    /**
     * Passes a batch on to everyone except the player it came from.
     *
     * <p>What makes the host a relay. Eight players talking to each other
     * directly is twenty-eight links, each one a place to stall or diverge;
     * eight players talking to a host is eight, and the host is the only
     * machine that has to know where anybody is. The cost is that the game
     * ends when the host leaves, which is the bargain every game of this era
     * made.
     *
     * <p>The bytes go out exactly as they came in. Decoding and re-encoding
     * would let the host alter what it forwards, and a relay that can rewrite
     * its traffic is a relay that can desync the table without anyone being
     * able to tell.
     */
    public void relay(Batch batch) throws IOException {
        for (Map.Entry<Integer, SocketAddress> peer : peers.entrySet()) {
            if (peer.getKey() == batch.player()) {
                continue;
            }
            socket.send(new DatagramPacket(batch.raw(), batch.raw().length, peer.getValue()));
        }
    }

    /** Passes an authenticated player message only to players selected by its sender. */
    public void relay(ChatPacket chat) throws IOException {
        for (Map.Entry<Integer, SocketAddress> peer : peers.entrySet()) {
            if (peer.getKey() == chat.player()
                    || (chat.recipientMask() & (1 << peer.getKey())) == 0) {
                continue;
            }
            socket.send(new DatagramPacket(chat.raw(), chat.raw().length, peer.getValue()));
        }
    }

    /** Forgets a peer, when they have gone. */
    public void removePeer(int player) {
        peers.remove(player);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            socket.close();
        }
    }
}
