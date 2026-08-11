package net.chonkbase.chonkcraft.engine.network;

import java.io.Closeable;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol;

/**
 * Finding games on the local network without being told where they are.
 *
 * <p>A host shouts every second on a fixed port; anyone looking listens on
 * that port and collects what it hears. That is the whole protocol, and its
 * plainness is the point: on a home network, where every machine is on one
 * flat segment, broadcast reaches everybody and needs no service, no library
 * and no configuration.
 *
 * <p>It has a real limit and it is better stated than discovered. Broadcast
 * does not cross a router, so it will not find a game on another subnet and it
 * will never find one over the internet. Typing an address by hand stays
 * available for exactly that reason -- discovery is a convenience laid over
 * manual connection, not a replacement for it.
 *
 * <p>Announcements go to every broadcast address the machine has rather than
 * to 255.255.255.255. A laptop on wireless with a virtual adapter or two has
 * several, and the global address reaches only whichever the routing table
 * happens to prefer.
 */
public final class GameDiscovery implements Closeable {

    /** The implementation hosts announce on. Fixed, because both sides must agree. */
    public static final int PORT = 7099;

    /** Marks an announcement as ours and pins its format. */
    private static final int MAGIC = 0x57474C42; // "WGLB"

    private static final int VERSION = 2;

    /** How often a host says it is there. */
    public static final long ANNOUNCE_INTERVAL_MILLIS = 1_000L;

    /**
     * How long a game stays listed after its last announcement.
     *
     * <p>Three announcements' worth. One missed packet on a busy wireless
     * network should not make a game flicker out of the list and back.
     */
    public static final long STALE_AFTER_MILLIS = 3_500L;

    private static final int MAX_PACKET_BYTES = 512;

    /**
     * A game somebody is hosting.
     *
     * @param name     what the host calls themselves
     * @param map      the map's file name, so a joiner can see what they are
     *                 joining before they commit to it
     * @param players  how many slots are taken
     * @param capacity how many there are
     * @param host     where to connect
     * @param port     likewise
     * @param heardAt  when this was last announced, for ageing it out
     */
    public record Game(String name, String map, int players, int capacity,
            String host, int port, long heardAt, String build) {

        /** Source-compatible convenience for UI fixtures that model this process's build. */
        public Game(String name, String map, int players, int capacity,
                String host, int port, long heardAt) {
            this(name, map, players, capacity, host, port, heardAt,
                    MatchmakingProtocol.gameBuild());
        }

        /** Whether the game still has room. */
        public boolean hasRoom() {
            return players < capacity;
        }

        /** Whether joining can produce a deterministic game with this process. */
        public boolean isCompatible() {
            return MatchmakingProtocol.gameBuild().equals(
                    MatchmakingProtocol.normalizeBuild(build));
        }

        @Override
        public String toString() {
            return name + " - " + map + " (" + players + "/" + capacity + ") at "
                    + host + ":" + port;
        }
    }

    /**
     * The implementation this instance uses.
     *
     * <p>Always {@link #PORT} in a real game -- both sides must agree on it or
     * discovery does not work at all. A test may ask for another, so that a
     * game genuinely running on the machine does not turn up in its results
     * and fail it, and so that two tests are not shouting at each other.
     */
    private final int port;

    private final DatagramSocket socket;
    private final AtomicBoolean closed = new AtomicBoolean();

    /** What has been heard, newest announcement per host and port. */
    private final Map<String, Game> found = new LinkedHashMap<>();

    private long lastAnnounced;

    /**
     * Opens a discovery socket.
     *
     * @param listening true to bind the shared port and hear announcements.
     *                  A host that is only announcing does not need it, and
     *                  binding it would stop a second copy of the game running
     *                  on the same machine.
     */
    public GameDiscovery(boolean listening) throws IOException {
        this(listening, PORT);
    }

    GameDiscovery(boolean listening, int port) throws IOException {
        this.port = port;
        if (listening) {
            socket = new DatagramSocket(null);
            socket.setReuseAddress(true);
            socket.bind(new InetSocketAddress(port));
        } else {
            socket = new DatagramSocket();
        }
        socket.setBroadcast(true);
        socket.setSoTimeout(1);
    }

    /**
     * Says a game is here, if it is time to say so again.
     *
     * <p>Rate limited internally so a caller can put this in the frame loop
     * without thinking about it.
     */
    public void announce(String name, String map, int players, int capacity, int gamePort) {
        long now = System.currentTimeMillis();
        if (now - lastAnnounced < ANNOUNCE_INTERVAL_MILLIS) {
            return;
        }
        lastAnnounced = now;

        byte[] nameBytes = trimmed(name);
        byte[] mapBytes = trimmed(map);
        byte[] buildBytes = trimmed(MatchmakingProtocol.gameBuild());
        ByteBuffer buffer = ByteBuffer.allocate(MAX_PACKET_BYTES);
        buffer.putInt(MAGIC);
        buffer.putShort((short) VERSION);
        buffer.putShort((short) gamePort);
        buffer.put((byte) players);
        buffer.put((byte) capacity);
        buffer.put((byte) buildBytes.length);
        buffer.put(buildBytes);
        buffer.put((byte) nameBytes.length);
        buffer.put(nameBytes);
        buffer.put((byte) mapBytes.length);
        buffer.put(mapBytes);

        byte[] bytes = new byte[buffer.position()];
        System.arraycopy(buffer.array(), 0, bytes, 0, bytes.length);
        for (InetAddress target : broadcastAddresses()) {
            try {
                socket.send(new DatagramPacket(bytes, bytes.length, target, port));
            } catch (IOException ignored) {
                // One adapter refusing is not a reason to stop trying the
                // others; a machine often has several and only one that works.
            }
        }
    }

    /**
     * Every game heard recently, oldest announcement first.
     *
     * <p>Polls the socket as a side effect, so a caller can simply ask for the
     * list each frame.
     */
    public List<Game> games() {
        receive();
        long now = System.currentTimeMillis();
        found.values().removeIf(game -> now - game.heardAt() > STALE_AFTER_MILLIS);
        return List.copyOf(found.values());
    }

    private void receive() {
        byte[] buffer = new byte[MAX_PACKET_BYTES];
        while (!closed.get()) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                return;
            } catch (IOException e) {
                return;
            }
            Game game = decode(packet);
            if (game != null) {
                found.put(game.host() + ":" + game.port(), game);
            }
        }
    }

    private static Game decode(DatagramPacket packet) {
        try {
            ByteBuffer in = ByteBuffer.wrap(packet.getData(), packet.getOffset(),
                    packet.getLength());
            if (in.remaining() < 13 || in.getInt() != MAGIC || in.getShort() != VERSION) {
                return null;
            }
            int gamePort = in.getShort() & 0xFFFF;
            int players = in.get() & 0xFF;
            int capacity = in.get() & 0xFF;
            String build = readString(in);
            String name = readString(in);
            String map = readString(in);
            if (build == null || name == null || map == null || capacity <= 0) {
                return null;
            }
            return new Game(name, map, players, capacity,
                    packet.getAddress().getHostAddress(), gamePort,
                    System.currentTimeMillis(), MatchmakingProtocol.normalizeBuild(build));
        } catch (RuntimeException malformed) {
            // Anything at all can arrive on an open UDP port. A game should
            // not fall over because something else on the network found it.
            return null;
        }
    }

    private static String readString(ByteBuffer in) {
        if (in.remaining() < 1) {
            return null;
        }
        int length = in.get() & 0xFF;
        if (in.remaining() < length) {
            return null;
        }
        byte[] bytes = new byte[length];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /** At most 200 bytes of a string, so an announcement stays in one packet. */
    private static byte[] trimmed(String text) {
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= 200) {
            return bytes;
        }
        byte[] cut = new byte[200];
        System.arraycopy(bytes, 0, cut, 0, 200);
        return cut;
    }

    /**
     * Every network this machine can broadcast on.
     *
     * <p>Rather than the global 255.255.255.255, which reaches whichever
     * interface the routing table prefers and no others. A laptop on wireless
     * with a virtual adapter has several, and the one the game is wanted on is
     * not reliably the preferred one.
     */
    static List<InetAddress> broadcastAddresses() {
        List<InetAddress> targets = new ArrayList<>();
        try {
            for (NetworkInterface adapter : java.util.Collections.list(
                    NetworkInterface.getNetworkInterfaces())) {
                if (!adapter.isUp() || adapter.isLoopback()) {
                    continue;
                }
                for (InterfaceAddress address : adapter.getInterfaceAddresses()) {
                    InetAddress broadcast = address.getBroadcast();
                    if (broadcast != null) {
                        targets.add(broadcast);
                    }
                }
            }
        } catch (IOException unavailable) {
            // Fall through to the loopback below.
        }
        if (targets.isEmpty()) {
            // No usable adapter: at least reach another copy on this machine,
            // which is what a developer testing two clients has.
            targets.add(InetAddress.getLoopbackAddress());
        }
        return targets;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            socket.close();
        }
    }
}
