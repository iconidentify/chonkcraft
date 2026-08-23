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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol;
import net.chonkbase.chonkcraft.matchmaking.RelayDatagramSocket;

/**
 * Everyone agreeing what game they are about to play.
 *
 * <p>The hard part of a lockstep game is not the sockets, it is that every
 * machine must start from the same world: the same map, the same roster in the
 * same order, and the same account of who is playing which slot. One
 * disagreement about any of that produces a desync on the first cycle, and one
 * that looks like a network fault rather than a setup mistake. So the
 * agreement is reached here, once, and handed to the game already settled.
 *
 * <p>A filename is not that agreement: one client may not have it, and two maps
 * with the same filename may contain different terrain. The host therefore
 * advertises the byte length and SHA-256 of its selection. A client uses its
 * local copy only when that identity matches, otherwise it receives retryable
 * chunks from the host and proves the completed digest before Start can work.
 *
 * <p>The host decides and everyone else is told. There is no negotiation: a
 * client sends "I would like to play" and receives the whole state of the
 * lobby in reply, including which slot it was given. That is what makes the
 * state consistent -- with peers agreeing among themselves, two clients can
 * believe different things and neither is wrong.
 *
 * <p>The same socket carries the lobby and then the game. Handing it over
 * rather than closing and rebinding avoids a window where the implementation is free and
 * something else can take it, and means a joiner that reached the lobby can
 * reach the game.
 */
public final class GameLobby implements Closeable {

    /** Marks a packet as ours and pins the wire format. */
    private static final int MAGIC = 0x57474C59; // "WGLY"

    // Version 6 carries the exact game build plus the authoritative game
    // template. A different build is not allowed to receive a slot, map bytes
    // or START: deterministic peers must run identical gameplay code.
    private static final int VERSION = 6;

    private static final int MAX_PACKET_BYTES = 1200;

    /** A map is carried below the path MTU, one independently retryable piece at a time. */
    private static final int MAP_CHUNK_BYTES = 1000;

    /** Refuse an advertised allocation large enough to turn one packet into a memory attack. */
    private static final int MAX_MAP_BYTES = 4 * 1024 * 1024;

    /** Missing pieces asked for in one packet. */
    private static final int MAP_REQUEST_BATCH = 24;

    /** A transfer retries faster than the ordinary lobby heartbeat. */
    private static final long MAP_REQUEST_MILLIS = 100L;

    /** How often a client asks again, and the host repeats the state. */
    public static final long REPEAT_MILLIS = 400L;

    /** How long a client may go unheard before its slot is freed. */
    public static final long SILENT_MILLIS = 8_000L;

    /** How long silence is allowed to look like ordinary connection setup. */
    static final long CONNECTION_WARNING_MILLIS = 4_000L;

    private static final byte JOIN = 1;
    private static final byte STATE = 2;
    private static final byte START = 3;
    private static final byte LEAVE = 4;
    private static final byte FULL = 5;
    private static final byte MAP_REQUEST = 6;
    private static final byte MAP_DATA = 7;
    private static final byte MAP_READY = 8;
    private static final byte BUILD_MISMATCH = 9;

    /** What is in a slot. */
    public enum Occupant {
        /** Waiting for somebody. */
        OPEN,
        /** A person, here. */
        HUMAN,
        /** The machine. */
        COMPUTER,
        /** Nobody, and nobody may. */
        CLOSED
    }

    /** The BNE game templates currently exposed by ChonkCraft's lobby. */
    public enum GameTemplate {
        /** Ordinary opponents; in-game diplomacy can add alliances later. */
        MELEE("Melee"),
        /** Players whose fixed starts are in the same vertical area form a team. */
        TOP_VS_BOTTOM("Top vs Bottom");

        private final String caption;

        GameTemplate(String caption) {
            this.caption = caption;
        }

        public String caption() {
            return caption;
        }

        public GameTemplate next() {
            GameTemplate[] templates = values();
            return templates[(ordinal() + 1) % templates.length];
        }
    }

    /**
     * One line of the lobby.
     *
     * @param index    the player slot, which is also the colour
     * @param occupant who holds it
     * @param name     what they are called, empty unless a person holds it
     * @param race     "human" or "orc"
     */
    public record Slot(int index, Occupant occupant, String name, String race) {

        /** Whether this slot participates in the simulated game. */
        public boolean isPlaying() {
            return occupant == Occupant.HUMAN || occupant == Occupant.COMPUTER;
        }

        /**
         * Whether a separate network peer sends command batches for this slot.
         *
         * <p>Computer players participate in the simulation, but their AI is
         * run deterministically by every peer. Treating them as network peers
         * makes lockstep wait for packets no computer process can send.
         */
        public boolean sendsNetworkCommands() {
            return occupant == Occupant.HUMAN;
        }
    }

    /** Everything a screen needs to draw the lobby. */
    public record State(String map, List<Slot> slots, GameTemplate gameTemplate,
            int localSlot, int hostSlot, boolean started, boolean mapReady, int mapPercent,
            boolean allPlayersReady, String mapProblem, String localBuild, String requiredBuild) {

        /** Whether admission stopped because this process cannot simulate the same game. */
        public boolean updateRequired() {
            return requiredBuild != null && !requiredBuild.isEmpty();
        }
    }

    /** Finds a client's local copy of the map the host named, or null when it has none. */
    @FunctionalInterface
    public interface MapProvider {
        byte[] map(String name) throws IOException;
    }

    private final DatagramSocket socket;
    private final boolean hosting;
    private final boolean relayed;
    private final MapProvider mapProvider;
    private final long openedAt;
    private final String gameBuild;

    private String map;
    private String localName;
    private GameTemplate gameTemplate = GameTemplate.MELEE;

    /** The slots, index 0 to capacity - 1. */
    private final List<Slot> slots = new ArrayList<>();

    /** Which slot this machine holds, or -1 before the host has said. */
    private int localSlot;

    private boolean started;

    /** Set when the host has no room, so a joiner can be told rather than wait. */
    private boolean rejectedAsFull;

    /** Host only: where each occupied slot is, and when it last spoke. */
    private final Map<Integer, SocketAddress> addresses = new LinkedHashMap<>();
    private final Map<Integer, Long> lastHeard = new LinkedHashMap<>();
    private final Map<Integer, Boolean> mapsReady = new LinkedHashMap<>();

    /** Client only: where the host is. */
    private SocketAddress hostAddress;

    /** Client only: the slot occupied by the host in the latest state. */
    private int remoteHostSlot;

    private long lastSent;

    private long lastMapRequest;

    /** Client only: when map reception last began or advanced. */
    private long lastMapProgressAt;

    /** The exact bytes every machine will parse, local when equal and transferred otherwise. */
    private byte[] mapBytes;

    /** The identity advertised by the host. */
    private byte[] mapHash = new byte[0];
    private int mapLength = -1;

    /** Client-only assembly state. */
    private byte[] incomingMap;
    private BitSet receivedMapChunks;
    private int receivedChunkCount;
    private boolean mapChecked;
    private boolean mapWasTransferred;
    private boolean mapReady;
    private boolean allPlayersReady;
    private boolean startReceived;
    private String mapProblem = "";
    private String requiredBuild = "";

    /** Whether the socket has been handed to the game. */
    private boolean released;

    private GameLobby(DatagramSocket socket, boolean hosting, MapProvider mapProvider,
            String gameBuild) {
        this.socket = socket;
        this.hosting = hosting;
        this.relayed = socket instanceof RelayDatagramSocket;
        this.mapProvider = mapProvider;
        this.gameBuild = MatchmakingProtocol.normalizeBuild(gameBuild);
        this.openedAt = System.currentTimeMillis();
        this.lastMapProgressAt = openedAt;
    }

    /**
     * Opens a lobby that others can join.
     *
     * <p>This overload carries no map payload and exists for protocol-only
     * callers. A playable lobby uses the overload with {@code mapBytes}.
     *
     * @param capacity how many slots the map has
     * @param port     the implementation to listen on, which is also the game's
     */
    public static GameLobby host(String name, String map, int capacity, int port)
            throws IOException {
        return host(name, map, null, capacity, port);
    }

    /** Opens a lobby and makes the selected map available to every joiner. */
    public static GameLobby host(String name, String map, byte[] mapBytes, int capacity, int port)
            throws IOException {
        DatagramSocket socket = new DatagramSocket(port);
        return hostOn(name, map, mapBytes, capacity, socket, MatchmakingProtocol.gameBuild());
    }

    /** Test seam for proving two independently packaged game builds cannot mix. */
    static GameLobby hostWithBuild(String name, String map, byte[] mapBytes, int capacity,
            int port, String build) throws IOException {
        return hostOn(name, map, mapBytes, capacity, new DatagramSocket(port), build);
    }

    /** Hosts through the central relay while preserving the ordinary lobby wire protocol. */
    public static GameLobby hostRelayed(String name, String map, byte[] mapBytes, int capacity,
            RelayDatagramSocket socket) throws IOException {
        return hostOn(name, map, mapBytes, capacity, socket, MatchmakingProtocol.gameBuild());
    }

    private static GameLobby hostOn(String name, String map, byte[] mapBytes, int capacity,
            DatagramSocket socket, String build) throws IOException {
        socket.setSoTimeout(1);
        GameLobby lobby = new GameLobby(socket, true, null, build);
        lobby.map = map == null ? "" : map;
        lobby.localName = name == null ? "Host" : name;
        if (mapBytes != null) {
            if (mapBytes.length > MAX_MAP_BYTES) {
                socket.close();
                throw new IOException("map is larger than " + MAX_MAP_BYTES + " bytes");
            }
            lobby.mapBytes = mapBytes.clone();
            lobby.mapLength = mapBytes.length;
            lobby.mapHash = hash(mapBytes);
        }
        lobby.mapReady = true;
        int room = Math.max(2, Math.min(8, capacity));
        for (int i = 0; i < room; i++) {
            lobby.slots.add(new Slot(i, Occupant.OPEN, "", "human"));
        }
        lobby.localSlot = 0;
        lobby.slots.set(0, new Slot(0, Occupant.HUMAN, lobby.localName, "human"));
        return lobby;
    }

    /**
     * Opens a lobby that asks to join another.
     *
     * <p>Binds any free port rather than the game's: a joiner does not need a
     * known port, because it speaks first and the host replies to wherever it
     * came from. Insisting on a fixed one is what stops two copies playing each
     * other on one machine.
     */
    public static GameLobby join(String name, InetAddress host, int port) throws IOException {
        return join(name, host, port, ignored -> null);
    }

    /** Opens a lobby and checks the host's map against maps available on this machine. */
    public static GameLobby join(String name, InetAddress host, int port, MapProvider maps)
            throws IOException {
        DatagramSocket socket = new DatagramSocket();
        return joinOn(name, new InetSocketAddress(host, port), socket, maps,
                MatchmakingProtocol.gameBuild());
    }

    /** Test seam for proving a stale direct client is rejected before seating. */
    static GameLobby joinWithBuild(String name, InetAddress host, int port, MapProvider maps,
            String build) throws IOException {
        return joinOn(name, new InetSocketAddress(host, port), new DatagramSocket(), maps, build);
    }

    /** Joins the host endpoint assigned by the central relay. */
    public static GameLobby joinRelayed(String name, RelayDatagramSocket socket, int hostEndpoint,
            MapProvider maps) throws IOException {
        return joinOn(name, RelayDatagramSocket.addressOf(hostEndpoint), socket, maps,
                MatchmakingProtocol.gameBuild());
    }

    private static GameLobby joinOn(String name, SocketAddress host, DatagramSocket socket,
            MapProvider maps, String build) throws IOException {
        socket.setSoTimeout(1);
        GameLobby lobby = new GameLobby(socket, false, maps == null ? ignored -> null : maps,
                build);
        lobby.localName = name == null ? "Player" : name;
        lobby.hostAddress = host;
        lobby.localSlot = -1;
        lobby.map = "";
        return lobby;
    }

    public boolean isHost() {
        return hosting;
    }

    public int localPort() {
        return socket.getLocalPort();
    }

    /** The port another machine must reach for this lobby conversation. */
    public int connectionPort() {
        return hostPort();
    }

    /** What the lobby looks like now. */
    public synchronized State state() {
        String problem = mapProblem.isEmpty()
                ? connectionWarning(localSlot, mapReady, openedAt, lastMapProgressAt,
                        hostPort(), System.currentTimeMillis(), relayed)
                : mapProblem;
        return new State(map, List.copyOf(slots), gameTemplate, localSlot,
                hosting ? localSlot : remoteHostSlot, started,
                mapReady, mapPercent(), hosting ? everyHumanHasMap() : allPlayersReady,
                problem, gameBuild, requiredBuild);
    }

    /**
     * Turns a silent UDP conversation into an actionable diagnosis.
     *
     * <p>Without this distinction, a client that has never received even the
     * host's lobby state claims to be receiving a map at zero percent forever.
     * A Linux firewall rejecting the lobby port is the common real-world case,
     * but retries continue because a delayed or repaired connection can still
     * recover without leaving the screen.
     */
    static String connectionWarning(int localSlot, boolean mapReady, long openedAt,
            long lastMapProgressAt, int port, long now) {
        return connectionWarning(localSlot, mapReady, openedAt, lastMapProgressAt, port, now,
                false);
    }

    private static String connectionWarning(int localSlot, boolean mapReady, long openedAt,
            long lastMapProgressAt, int port, long now, boolean relayed) {
        if (mapReady) {
            return "";
        }
        if (localSlot < 0 && now - openedAt >= CONNECTION_WARNING_MILLIS) {
            return relayed ? "No reply from the online host. Reconnecting through the relay..."
                    : "No compatible reply on UDP " + port
                            + ". Check the host firewall and update both games.";
        }
        if (localSlot >= 0 && now - lastMapProgressAt >= CONNECTION_WARNING_MILLIS) {
            return relayed ? "Online map transfer stalled. Reconnecting through the relay..."
                    : "Map transfer stalled on UDP " + port + ". Check the host firewall.";
        }
        return "";
    }

    private int hostPort() {
        return hostAddress instanceof InetSocketAddress address ? address.getPort() : localPort();
    }

    /** The verified bytes this machine will use when the game begins. */
    public synchronized byte[] mapBytes() {
        return mapReady && mapBytes != null ? mapBytes.clone() : null;
    }

    /** Whether these bytes had to cross the network rather than matching a local copy. */
    public synchronized boolean mapWasTransferred() {
        return mapWasTransferred;
    }

    private int mapPercent() {
        if (mapReady) {
            return 100;
        }
        int chunks = mapChunkCount();
        return chunks == 0 ? 0 : Math.min(99, receivedChunkCount * 100 / chunks);
    }

    public synchronized boolean isStarted() {
        return started;
    }

    /** Hides a relayed room before the first game packet, without exposing game state. */
    public void markOnlineRoomStarted() {
        if (socket instanceof RelayDatagramSocket relay) {
            relay.markRoomStarted();
        }
    }

    /** Whether the host turned this joiner away for want of a slot. */
    public synchronized boolean wasRejectedAsFull() {
        return rejectedAsFull;
    }

    /** Whether the host refused this process because its gameplay build differs. */
    public synchronized boolean updateRequired() {
        return !requiredBuild.isEmpty();
    }

    /** How many people are actually here. */
    public synchronized int humanCount() {
        return (int) slots.stream().filter(s -> s.occupant() == Occupant.HUMAN).count();
    }

    public synchronized int capacity() {
        return slots.size();
    }

    /**
     * Carries the conversation forward.
     *
     * <p>Called from a screen's frame loop: it sends whatever is due and takes
     * in whatever has arrived, and never blocks for longer than the socket's
     * one millisecond timeout.
     */
    public void poll() {
        receive();
        long now = System.currentTimeMillis();
        boolean ordinaryDue;
        boolean mapRequestDue;
        synchronized (this) {
            if (hosting) {
                dropTheSilent(now);
            }
            ordinaryDue = now - lastSent >= REPEAT_MILLIS;
            if (ordinaryDue) {
                lastSent = now;
            }
            mapRequestDue = !hosting && !mapReady && incomingMap != null
                    && now - lastMapRequest >= MAP_REQUEST_MILLIS;
            if (mapRequestDue) {
                lastMapRequest = now;
            }
        }
        if (hosting) {
            if (ordinaryDue) {
                sendStateToAll();
            }
        } else {
            if (ordinaryDue && !updateRequired()) {
                sendJoin();
                if (mapReady) {
                    sendMapReady();
                }
            }
            if (mapRequestDue) {
                sendMapRequest();
            }
        }
    }

    // ---- host side ----------------------------------------------------

    /** Sets what a slot holds. Refuses to disturb a slot a person is in. */
    public synchronized boolean setOccupant(int index, Occupant occupant) {
        if (!hosting || index < 0 || index >= slots.size()) {
            return false;
        }
        if (slots.get(index).occupant() == Occupant.HUMAN) {
            return false;
        }
        String name = occupant == Occupant.COMPUTER ? "Computer" : "";
        slots.set(index, new Slot(index, occupant, name, slots.get(index).race()));
        lastSent = 0;
        return true;
    }

    /** Sets a slot's side. The host may set anyone's, which is how the original works. */
    public synchronized boolean setRace(int index, String race) {
        if (!hosting || index < 0 || index >= slots.size()) {
            return false;
        }
        String wanted = "orc".equalsIgnoreCase(race) ? "orc" : "human";
        Slot slot = slots.get(index);
        slots.set(index, new Slot(index, slot.occupant(), slot.name(), wanted));
        lastSent = 0;
        return true;
    }

    /** Selects the synchronized BNE game template. Only the creator decides it. */
    public synchronized boolean setGameTemplate(GameTemplate template) {
        if (!hosting || template == null) {
            return false;
        }
        gameTemplate = template;
        lastSent = 0;
        return true;
    }

    /**
     * Moves whoever is in one slot to another.
     *
     * <p>Slot is colour and starting position both, so this is not cosmetic:
     * it is how a host puts two players on the same side of a map.
     */
    public synchronized boolean move(int from, int to) {
        if (!hosting || from == to || outside(from) || outside(to)) {
            return false;
        }
        Slot moving = slots.get(from);
        if (moving.occupant() == Occupant.OPEN || moving.occupant() == Occupant.CLOSED) {
            return false;
        }
        if (slots.get(to).occupant() != Occupant.OPEN) {
            return false;
        }
        slots.set(to, new Slot(to, moving.occupant(), moving.name(), moving.race()));
        slots.set(from, new Slot(from, Occupant.OPEN, "", moving.race()));
        SocketAddress where = addresses.remove(from);
        if (where != null) {
            addresses.put(to, where);
            Long heard = lastHeard.remove(from);
            lastHeard.put(to, heard == null ? System.currentTimeMillis() : heard);
            Boolean ready = mapsReady.remove(from);
            mapsReady.put(to, Boolean.TRUE.equals(ready));
        }
        if (localSlot == from) {
            localSlot = to;
        }
        lastSent = 0;
        return true;
    }

    /** Turns somebody out. Their slot opens again. */
    public synchronized boolean kick(int index) {
        if (!hosting || index < 0 || index >= slots.size() || index == localSlot) {
            return false;
        }
        if (slots.get(index).occupant() != Occupant.HUMAN) {
            return false;
        }
        slots.set(index, new Slot(index, Occupant.OPEN, "", slots.get(index).race()));
        addresses.remove(index);
        lastHeard.remove(index);
        mapsReady.remove(index);
        lastSent = 0;
        return true;
    }

    private boolean outside(int index) {
        return index < 0 || index >= slots.size();
    }

    /**
     * Tells everyone to begin.
     *
     * <p>Sent several times rather than once. A lost start packet leaves one
     * player sitting in a lobby watching everyone else play, and there is no
     * later message that would put it right.
     */
    public void start() throws IOException {
        List<Map.Entry<Integer, SocketAddress>> targets;
        synchronized (this) {
            if (!hosting || started) {
                return;
            }
            if (!everyHumanHasMap()) {
                return;
            }
            started = true;
            targets = List.copyOf(addresses.entrySet());
        }
        for (int repeat = 0; repeat < 5; repeat++) {
            for (Map.Entry<Integer, SocketAddress> target : targets) {
                byte[] packet = encodeStart(target.getKey());
                socket.send(new DatagramPacket(packet, packet.length, target.getValue()));
            }
        }
    }

    /** Frees a slot whose holder has stopped speaking. */
    private void dropTheSilent(long now) {
        for (Map.Entry<Integer, Long> entry : List.copyOf(lastHeard.entrySet())) {
            if (now - entry.getValue() <= SILENT_MILLIS) {
                continue;
            }
            int index = entry.getKey();
            lastHeard.remove(index);
            addresses.remove(index);
            mapsReady.remove(index);
            if (index > 0 && index < slots.size()) {
                slots.set(index, new Slot(index, Occupant.OPEN, "", slots.get(index).race()));
            }
            lastSent = 0;
        }
    }

    private void sendStateToAll() {
        List<Map.Entry<Integer, SocketAddress>> targets;
        synchronized (this) {
            targets = List.copyOf(addresses.entrySet());
        }
        for (Map.Entry<Integer, SocketAddress> target : targets) {
            try {
                byte[] packet = encodeState(target.getKey());
                socket.send(new DatagramPacket(packet, packet.length, target.getValue()));
            } catch (IOException ignored) {
                // One unreachable client is not a reason to stop telling the
                // others what is going on.
            }
        }
    }

    private void sendJoin() {
        try {
            byte[] name = trimmed(localName);
            byte[] build = encoded(gameBuild, 64);
            ByteBuffer buffer = ByteBuffer.allocate(MAX_PACKET_BYTES);
            buffer.putInt(MAGIC);
            buffer.putShort((short) VERSION);
            buffer.put(JOIN);
            buffer.put((byte) build.length);
            buffer.put(build);
            buffer.put((byte) name.length);
            buffer.put(name);
            byte[] bytes = trim(buffer);
            socket.send(new DatagramPacket(bytes, bytes.length, hostAddress));
        } catch (IOException ignored) {
            // The host may not be up yet; asking again is the whole plan.
        }
    }

    /** Repeats proof of the verified map until the host reflects that everyone is ready. */
    private void sendMapReady() {
        if (hostAddress == null || mapHash.length != 32) {
            return;
        }
        try {
            ByteBuffer buffer = packet(MAP_READY);
            buffer.put(mapHash);
            byte[] bytes = trim(buffer);
            socket.send(new DatagramPacket(bytes, bytes.length, hostAddress));
        } catch (IOException ignored) {
            // The next heartbeat repeats it.
        }
    }

    /** Asks for a window of pieces still missing from the host's map. */
    private void sendMapRequest() {
        if (hostAddress == null || incomingMap == null || receivedMapChunks == null) {
            return;
        }
        try {
            ByteBuffer buffer = packet(MAP_REQUEST);
            int countAt = buffer.position();
            buffer.put((byte) 0);
            int count = 0;
            for (int chunk = receivedMapChunks.nextClearBit(0);
                    chunk < mapChunkCount() && count < MAP_REQUEST_BATCH;
                    chunk = receivedMapChunks.nextClearBit(chunk + 1)) {
                buffer.putInt(chunk);
                count++;
            }
            buffer.put(countAt, (byte) count);
            byte[] bytes = trim(buffer);
            socket.send(new DatagramPacket(bytes, bytes.length, hostAddress));
        } catch (IOException ignored) {
            // Missing pieces stay missing and are named again on the next request.
        }
    }

    /** Says goodbye, so a slot opens at once rather than after the silence. */
    public void leave() {
        if (hosting || hostAddress == null) {
            return;
        }
        try {
            ByteBuffer buffer = ByteBuffer.allocate(16);
            buffer.putInt(MAGIC);
            buffer.putShort((short) VERSION);
            buffer.put(LEAVE);
            byte[] bytes = trim(buffer);
            socket.send(new DatagramPacket(bytes, bytes.length, hostAddress));
        } catch (IOException ignored) {
            // Going quiet has the same effect, only slower.
        }
    }

    // ---- the wire -----------------------------------------------------

    private synchronized byte[] encodeState(int forSlot) {
        ByteBuffer buffer = packet(STATE);
        putState(buffer, forSlot);
        return trim(buffer);
    }

    /** Writes the final lobby agreement behind either a STATE or START header. */
    private synchronized void putState(ByteBuffer buffer, int forSlot) {
        buffer.put((byte) forSlot);
        buffer.put((byte) localSlot);
        putString(buffer, gameBuild, 64);
        byte[] mapName = encoded(map, 200);
        buffer.put((byte) mapName.length);
        buffer.put(mapName);
        buffer.putInt(mapLength);
        buffer.put(mapHash.length == 32 ? mapHash : new byte[32]);
        buffer.put((byte) (everyHumanHasMap() ? 1 : 0));
        buffer.put((byte) gameTemplate.ordinal());
        buffer.put((byte) slots.size());
        for (Slot slot : slots) {
            buffer.put((byte) slot.occupant().ordinal());
            buffer.put((byte) ("orc".equals(slot.race()) ? 1 : 0));
            byte[] name = trimmed(slot.name());
            buffer.put((byte) name.length);
            buffer.put(name);
        }
    }

    private synchronized byte[] encodeStart(int forSlot) {
        ByteBuffer buffer = packet(START);
        // START is the commit record, not merely a bell. Carrying the final
        // slot/race snapshot prevents a fast host from starting before its
        // last ordinary STATE datagram reaches a client.
        putState(buffer, forSlot);
        return trim(buffer);
    }

    private static ByteBuffer packet(byte kind) {
        ByteBuffer buffer = ByteBuffer.allocate(MAX_PACKET_BYTES);
        buffer.putInt(MAGIC);
        buffer.putShort((short) VERSION);
        buffer.put(kind);
        return buffer;
    }

    private static byte[] trim(ByteBuffer buffer) {
        byte[] bytes = new byte[buffer.position()];
        System.arraycopy(buffer.array(), 0, bytes, 0, bytes.length);
        return bytes;
    }

    private static byte[] trimmed(String text) {
        return encoded(text, 40);
    }

    private static byte[] encoded(String text, int maximum) {
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        if (bytes.length <= maximum) {
            return bytes;
        }
        byte[] cut = new byte[maximum];
        System.arraycopy(bytes, 0, cut, 0, maximum);
        return cut;
    }

    private void receive() {
        byte[] buffer = new byte[MAX_PACKET_BYTES];
        while (!released) {
            DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
            try {
                socket.receive(packet);
            } catch (SocketTimeoutException e) {
                return;
            } catch (IOException e) {
                return;
            }
            try {
                handle(packet);
            } catch (RuntimeException malformed) {
                // Anything at all can arrive on an open UDP port, and a lobby
                // should not fall over because something else found it.
                continue;
            }
        }
    }

    private void handle(DatagramPacket packet) {
        ByteBuffer in = ByteBuffer.wrap(packet.getData(), packet.getOffset(), packet.getLength());
        if (in.remaining() < 7 || in.getInt() != MAGIC) {
            return;
        }
        int heardVersion = in.getShort() & 0xFFFF;
        byte kind = in.get();
        if (heardVersion != VERSION) {
            if (hosting && kind == JOIN) {
                sendBuildMismatch(packet.getSocketAddress());
            }
            return;
        }
        if (hosting) {
            switch (kind) {
                case JOIN -> takeJoin(packet.getSocketAddress(), readString(in), readString(in));
                case LEAVE -> takeLeave(packet.getSocketAddress());
                case MAP_REQUEST -> takeMapRequest(packet.getSocketAddress(), in);
                case MAP_READY -> takeMapReady(packet.getSocketAddress(), in);
                default -> { }
            }
            return;
        }
        if (hostAddress == null || !hostAddress.equals(packet.getSocketAddress())) {
            return;
        }
        switch (kind) {
            case STATE -> takeState(in);
            case START -> takeStart(in);
            case FULL -> takeFull();
            case MAP_DATA -> takeMapData(in);
            case BUILD_MISMATCH -> takeBuildMismatch(in);
            default -> { }
        }
    }

    private void takeJoin(SocketAddress from, String build, String name) {
        if (!compatibleBuild(build)) {
            sendBuildMismatch(from);
            return;
        }
        boolean full = false;
        synchronized (this) {
            Integer existing = slotOf(from);
            if (existing != null) {
                // Already seated. Their repeat is what tells us they are still
                // there, so it is not wasted.
                lastHeard.put(existing, System.currentTimeMillis());
            } else {
                int free = firstOpen();
                if (free < 0) {
                    full = true;
                } else {
                    slots.set(free, new Slot(free, Occupant.HUMAN,
                            name == null || name.isBlank() ? "Player" : name,
                            slots.get(free).race()));
                    addresses.put(free, from);
                    lastHeard.put(free, System.currentTimeMillis());
                    mapsReady.put(free, mapLength < 0);
                    lastSent = 0;
                }
            }
        }
        try {
            if (full) {
                ByteBuffer buffer = ByteBuffer.allocate(16);
                buffer.putInt(MAGIC);
                buffer.putShort((short) VERSION);
                buffer.put(FULL);
                byte[] bytes = trim(buffer);
                socket.send(new DatagramPacket(bytes, bytes.length, from));
                return;
            }
            Integer seat;
            synchronized (this) {
                seat = slotOf(from);
            }
            if (seat != null) {
                byte[] bytes = encodeState(seat);
                socket.send(new DatagramPacket(bytes, bytes.length, from));
            }
        } catch (IOException ignored) {
            // They will ask again.
        }
    }

    /** Refuses before allocating a seat, and tells a current client exactly why. */
    private void sendBuildMismatch(SocketAddress to) {
        try {
            ByteBuffer buffer = packet(BUILD_MISMATCH);
            putString(buffer, gameBuild, 64);
            byte[] bytes = trim(buffer);
            socket.send(new DatagramPacket(bytes, bytes.length, to));
        } catch (IOException ignored) {
            // A current client repeats JOIN; a legacy client remains unable to
            // enter even if it cannot understand the explanation.
        }
    }

    private synchronized void takeLeave(SocketAddress from) {
        Integer seat = slotOf(from);
        if (seat == null) {
            return;
        }
        addresses.remove(seat);
        lastHeard.remove(seat);
        mapsReady.remove(seat);
        slots.set(seat, new Slot(seat, Occupant.OPEN, "", slots.get(seat).race()));
        lastSent = 0;
    }

    /** Sends only pieces a client says it still lacks; repeated requests make UDP reliable. */
    private void takeMapRequest(SocketAddress from, ByteBuffer in) {
        byte[] source;
        synchronized (this) {
            if (slotOf(from) == null || mapBytes == null || in.remaining() < 1) {
                return;
            }
            source = mapBytes;
        }
        int count = Math.min(in.get() & 0xFF, MAP_REQUEST_BATCH);
        for (int i = 0; i < count && in.remaining() >= Integer.BYTES; i++) {
            int chunk = in.getInt();
            long offsetLong = (long) chunk * MAP_CHUNK_BYTES;
            if (chunk < 0 || offsetLong >= source.length) {
                continue;
            }
            int offset = (int) offsetLong;
            int length = Math.min(MAP_CHUNK_BYTES, source.length - offset);
            ByteBuffer out = packet(MAP_DATA);
            out.putInt(chunk);
            out.putShort((short) length);
            out.put(source, offset, length);
            byte[] bytes = trim(out);
            try {
                socket.send(new DatagramPacket(bytes, bytes.length, from));
            } catch (IOException ignored) {
                // The client names this same piece again if it did not arrive.
            }
        }
    }

    /** Records a client's proof that it holds the host's exact bytes. */
    private synchronized void takeMapReady(SocketAddress from, ByteBuffer in) {
        Integer seat = slotOf(from);
        if (seat == null || mapHash.length != 32 || in.remaining() < 32) {
            return;
        }
        byte[] heard = new byte[32];
        in.get(heard);
        if (MessageDigest.isEqual(mapHash, heard)) {
            mapsReady.put(seat, true);
            lastHeard.put(seat, System.currentTimeMillis());
            lastSent = 0;
        }
    }

    private synchronized Integer slotOf(SocketAddress address) {
        for (Map.Entry<Integer, SocketAddress> entry : addresses.entrySet()) {
            if (entry.getValue().equals(address)) {
                return entry.getKey();
            }
        }
        return null;
    }

    private synchronized int firstOpen() {
        for (Slot slot : slots) {
            if (slot.occupant() == Occupant.OPEN) {
                return slot.index();
            }
        }
        return -1;
    }

    private synchronized void takeState(ByteBuffer in) {
        int mySlot = in.get() & 0xFF;
        int heardHostSlot = in.get() & 0xFF;
        String heardBuild = readString(in);
        if (!compatibleBuild(heardBuild)) {
            recordBuildMismatch(heardBuild);
            return;
        }
        String heardMap = readString(in);
        if (in.remaining() < Integer.BYTES + 32 + 3) {
            return;
        }
        int heardLength = in.getInt();
        byte[] heardHash = new byte[32];
        in.get(heardHash);
        boolean heardAllReady = in.get() != 0;
        int templateOrdinal = in.get() & 0xFF;
        GameTemplate heardTemplate = templateOrdinal < GameTemplate.values().length
                ? GameTemplate.values()[templateOrdinal]
                : GameTemplate.MELEE;
        int count = in.get() & 0xFF;
        List<Slot> heard = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int occupant = in.get() & 0xFF;
            String race = (in.get() & 0xFF) == 1 ? "orc" : "human";
            String name = readString(in);
            Occupant kind = occupant < Occupant.values().length
                    ? Occupant.values()[occupant]
                    : Occupant.OPEN;
            heard.add(new Slot(i, kind, name == null ? "" : name, race));
        }
        localSlot = mySlot;
        remoteHostSlot = heardHostSlot;
        map = heardMap == null ? "" : heardMap;
        gameTemplate = heardTemplate;
        slots.clear();
        slots.addAll(heard);
        allPlayersReady = heardAllReady;
        prepareMap(map, heardLength, heardHash);
    }

    private synchronized void takeStart(ByteBuffer in) {
        takeState(in);
        startReceived = true;
        started = mapReady;
    }

    private synchronized void takeFull() {
        rejectedAsFull = true;
    }

    private synchronized void takeBuildMismatch(ByteBuffer in) {
        recordBuildMismatch(readString(in));
    }

    private boolean compatibleBuild(String heard) {
        return heard != null && !heard.isBlank()
                && gameBuild.equals(MatchmakingProtocol.normalizeBuild(heard));
    }

    private void recordBuildMismatch(String heard) {
        requiredBuild = heard == null || heard.isBlank()
                ? "another version" : MatchmakingProtocol.normalizeBuild(heard);
        mapProblem = "This game requires ChonkCraft " + requiredBuild + "; you have "
                + gameBuild + ". Quit to the launcher to update.";
    }

    /** Chooses an equal local copy or prepares to receive the host's exact bytes. */
    private void prepareMap(String name, int length, byte[] expectedHash) {
        if (mapChecked && length == mapLength && Arrays.equals(expectedHash, mapHash)) {
            return;
        }
        mapChecked = true;
        mapLength = length;
        mapHash = expectedHash.clone();
        mapBytes = null;
        incomingMap = null;
        receivedMapChunks = null;
        receivedChunkCount = 0;
        mapWasTransferred = false;
        mapReady = false;
        mapProblem = "";

        // The overload without bytes remains useful for protocol-only tests.
        // A real game always advertises a length and digest.
        if (length < 0) {
            mapReady = true;
            started = startReceived;
            return;
        }
        if (length > MAX_MAP_BYTES) {
            mapProblem = "The host's map is too large to receive.";
            return;
        }

        byte[] local = null;
        try {
            local = mapProvider == null ? null : mapProvider.map(name);
        } catch (IOException | RuntimeException unreadable) {
            mapProblem = "The local copy could not be read; receiving the host's copy.";
        }
        if (local != null && local.length == length
                && MessageDigest.isEqual(hash(local), expectedHash)) {
            mapBytes = local.clone();
            mapReady = true;
            mapProblem = "";
            lastSent = 0;
            started = startReceived;
            return;
        }
        if (length == 0) {
            byte[] empty = new byte[0];
            if (MessageDigest.isEqual(hash(empty), expectedHash)) {
                mapBytes = empty;
                mapReady = true;
                mapWasTransferred = true;
                lastSent = 0;
                started = startReceived;
            }
            return;
        }
        incomingMap = new byte[length];
        receivedMapChunks = new BitSet(mapChunkCount());
        lastMapProgressAt = System.currentTimeMillis();
        lastMapRequest = 0;
    }

    /** Accepts one piece once; duplicates are harmless and missing pieces are requested again. */
    private synchronized void takeMapData(ByteBuffer in) {
        if (incomingMap == null || receivedMapChunks == null
                || in.remaining() < Integer.BYTES + Short.BYTES) {
            return;
        }
        int chunk = in.getInt();
        int length = in.getShort() & 0xFFFF;
        long offsetLong = (long) chunk * MAP_CHUNK_BYTES;
        if (chunk < 0 || offsetLong >= incomingMap.length || length > MAP_CHUNK_BYTES
                || in.remaining() < length) {
            return;
        }
        int offset = (int) offsetLong;
        int expected = Math.min(MAP_CHUNK_BYTES, incomingMap.length - offset);
        if (length != expected) {
            return;
        }
        if (receivedMapChunks.get(chunk)) {
            return;
        }
        in.get(incomingMap, offset, length);
        receivedMapChunks.set(chunk);
        receivedChunkCount++;
        lastMapProgressAt = System.currentTimeMillis();
        if (receivedChunkCount != mapChunkCount()) {
            return;
        }
        if (!MessageDigest.isEqual(hash(incomingMap), mapHash)) {
            // A damaged datagram cannot normally reach here because UDP has a
            // checksum, but resetting is safer than beginning from bytes the
            // host did not name.
            receivedMapChunks.clear();
            receivedChunkCount = 0;
            mapProblem = "The map did not verify; receiving it again.";
            lastMapRequest = 0;
            return;
        }
        mapBytes = incomingMap;
        incomingMap = null;
        receivedMapChunks = null;
        mapWasTransferred = true;
        mapReady = true;
        mapProblem = "";
        lastSent = 0;
        started = startReceived;
    }

    private int mapChunkCount() {
        return mapLength <= 0 ? 0 : (mapLength + MAP_CHUNK_BYTES - 1) / MAP_CHUNK_BYTES;
    }

    /** Host-side readiness: every seated person has proved the advertised digest. */
    private boolean everyHumanHasMap() {
        if (mapLength < 0) {
            return true;
        }
        for (Slot slot : slots) {
            if (slot.index() != localSlot && slot.occupant() == Occupant.HUMAN
                    && !Boolean.TRUE.equals(mapsReady.get(slot.index()))) {
                return false;
            }
        }
        return true;
    }

    private static byte[] hash(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static String readString(ByteBuffer in) {
        if (in.remaining() < 1) {
            return "";
        }
        int length = in.get() & 0xFF;
        if (in.remaining() < length) {
            return "";
        }
        byte[] bytes = new byte[length];
        in.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void putString(ByteBuffer out, String text, int maximum) {
        byte[] bytes = encoded(text, maximum);
        out.put((byte) bytes.length);
        out.put(bytes);
    }

    // ---- handover -----------------------------------------------------

    /**
     * Where every other player is, for the game that follows.
     *
     * <p>The host knows everybody, because everybody spoke to it. A client
     * knows only the host, and that is deliberate rather than a shortcoming:
     * the host relays, so the host is the only address a client ever needs and
     * the only one it could be sure of anyway. Two players behind different
     * routers can each reach the host without being able to reach each other.
     */
    public synchronized Map<Integer, SocketAddress> peers() {
        if (!hosting) {
            return hostAddress == null ? Map.of() : Map.of(remoteHostSlot, hostAddress);
        }
        return new LinkedHashMap<>(addresses);
    }

    /** The host relay address a client authenticated during the lobby. */
    public synchronized SocketAddress relayAddress() {
        return hosting ? null : hostAddress;
    }

    /**
     * Hands the socket to the game and stops using it.
     *
     * <p>The alternative -- closing it and letting the game bind the same port
     * again -- leaves a window in which the implementation is free, and on a machine
     * running two copies the second one takes it.
     */
    public synchronized DatagramSocket releaseSocket() {
        released = true;
        return socket;
    }

    @Override
    public void close() {
        if (!released) {
            socket.close();
        }
    }
}
