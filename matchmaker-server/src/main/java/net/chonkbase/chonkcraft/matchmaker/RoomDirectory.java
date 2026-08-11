package net.chonkbase.chonkcraft.matchmaker;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import java.net.URI;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.CreateGameRequest;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.GameListing;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.HostStateRequest;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.JoinGameRequest;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.Phase;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.Seat;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.Visibility;
import net.chonkbase.chonkcraft.matchmaking.RoomCode;

/** In-memory lifecycle for short-lived lobbies; game state never enters this service. */
final class RoomDirectory {

    static final long HOST_SILENCE_MILLIS = 90_000;
    static final long RECONNECT_MILLIS = 30_000;
    static final long UNUSED_TICKET_MILLIS = 20_000;
    static final int MAX_RELAY_PAYLOAD = 1_200;

    enum Failure {
        NOT_FOUND,
        FULL,
        VERSION_MISMATCH,
        FORBIDDEN,
        CLOSED,
        INVALID
    }

    static final class Refusal extends Exception {
        private final Failure failure;

        Refusal(Failure failure, String message) {
            super(message);
            this.failure = failure;
        }

        Failure failure() {
            return failure;
        }
    }

    record Binding(String roomCode, int endpointId, String ticket) {
    }

    private static final class Participant {
        private final int endpointId;
        private final String ticket;
        private final String name;
        private final long reservedAt;
        private Channel channel;
        private long lastSeen;

        private Participant(int endpointId, String ticket, String name, long now) {
            this.endpointId = endpointId;
            this.ticket = ticket;
            this.name = name;
            this.reservedAt = now;
            this.lastSeen = now;
        }
    }

    private static final class Room {
        private final String code;
        private final String name;
        private final String map;
        private final String mapHash;
        private final String build;
        private final Visibility visibility;
        private final String hostToken;
        private final long createdAt;
        private final Map<Integer, Participant> participants = new LinkedHashMap<>();
        private int nextEndpoint = 1;
        private int capacity;
        private int advertisedPlayers = 1;
        private Phase phase = Phase.WAITING;
        private long hostSeen;

        private Room(String code, CreateGameRequest request, String hostToken, long now) {
            this.code = code;
            this.name = clean(request.name(), "Game", 40);
            this.map = clean(request.map(), "Unknown map", 100);
            this.mapHash = clean(request.mapHash(), "", 128);
            this.build = clean(request.build(), "dev", 64);
            this.visibility = request.visibility() == null ? Visibility.PUBLIC : request.visibility();
            this.hostToken = hostToken;
            this.capacity = Math.max(2, Math.min(8, request.capacity()));
            this.createdAt = now;
            this.hostSeen = now;
        }

        private GameListing listing() {
            int connectedOrReserved = participants.size();
            int players = Math.min(capacity, Math.max(advertisedPlayers, connectedOrReserved));
            return new GameListing(code, name, map, players, capacity, build, phase, createdAt);
        }
    }

    private final SecureRandom random;
    private final Clock clock;
    private final URI relayUri;
    private final URI inviteBase;
    private final Map<String, Room> rooms = new LinkedHashMap<>();
    private final Map<String, Binding> tickets = new LinkedHashMap<>();

    RoomDirectory(URI relayUri, URI inviteBase) {
        this(new SecureRandom(), Clock.systemUTC(), relayUri, inviteBase);
    }

    RoomDirectory(SecureRandom random, Clock clock, URI relayUri, URI inviteBase) {
        this.random = Objects.requireNonNull(random);
        this.clock = Objects.requireNonNull(clock);
        this.relayUri = Objects.requireNonNull(relayUri);
        this.inviteBase = Objects.requireNonNull(inviteBase);
    }

    synchronized Seat create(CreateGameRequest request) throws Refusal {
        prune();
        validateCreate(request);
        String code = uniqueCode();
        long now = clock.millis();
        String hostToken = token();
        Room room = new Room(code, request, hostToken, now);
        Participant host = participant(0, clean(request.name(), "Host", 40), now);
        room.participants.put(0, host);
        rooms.put(code, room);
        tickets.put(host.ticket, new Binding(code, 0, host.ticket));
        return seat(room, host, hostToken);
    }

    synchronized Seat join(String rawCode, JoinGameRequest request) throws Refusal {
        prune();
        String code;
        try {
            code = RoomCode.normalize(rawCode);
        } catch (IllegalArgumentException badCode) {
            throw new Refusal(Failure.INVALID, badCode.getMessage());
        }
        Room room = rooms.get(code);
        if (room == null) {
            throw new Refusal(Failure.NOT_FOUND, "No waiting game has that code.");
        }
        String joiningBuild = clean(request.build(), "dev", 64);
        if (!room.build.equals(joiningBuild)) {
            throw new Refusal(Failure.VERSION_MISMATCH,
                    "That game requires ChonkCraft " + room.build + "; you have "
                            + joiningBuild + ". Quit to the launcher to update.");
        }
        if (room.phase != Phase.WAITING) {
            throw new Refusal(Failure.CLOSED, "That game has already started.");
        }
        if (room.participants.size() >= room.capacity || room.nextEndpoint >= 1_000) {
            throw new Refusal(Failure.FULL, "That game is full.");
        }
        long now = clock.millis();
        Participant participant = participant(room.nextEndpoint++,
                clean(request.playerName(), "Player", 40), now);
        room.participants.put(participant.endpointId, participant);
        tickets.put(participant.ticket, new Binding(code, participant.endpointId,
                participant.ticket));
        return seat(room, participant, null);
    }

    synchronized List<GameListing> list(String build) {
        prune();
        String compatible = clean(build, "dev", 64);
        return rooms.values().stream()
                .filter(room -> room.visibility == Visibility.PUBLIC)
                .filter(room -> room.phase == Phase.WAITING)
                .filter(room -> room.build.equals(compatible))
                .map(Room::listing)
                .sorted(Comparator.comparingLong(GameListing::createdAt).reversed())
                .toList();
    }

    synchronized void update(String rawCode, String hostToken, HostStateRequest state)
            throws Refusal {
        Room room = authorizedHost(rawCode, hostToken);
        room.hostSeen = clock.millis();
        room.capacity = Math.max(2, Math.min(8, state.capacity()));
        room.advertisedPlayers = Math.max(1, Math.min(room.capacity, state.players()));
        room.phase = state.phase() == null ? Phase.WAITING : state.phase();
    }

    synchronized void close(String rawCode, String hostToken) throws Refusal {
        Room room = authorizedHost(rawCode, hostToken);
        remove(room);
    }

    synchronized Binding authorizeRelay(String ticket) throws Refusal {
        prune();
        Binding binding = tickets.get(ticket);
        if (binding == null) {
            throw new Refusal(Failure.FORBIDDEN, "The relay ticket is invalid or expired.");
        }
        Room room = rooms.get(binding.roomCode());
        if (room == null || !room.participants.containsKey(binding.endpointId())) {
            throw new Refusal(Failure.FORBIDDEN, "The relay ticket is invalid or expired.");
        }
        return binding;
    }

    synchronized void attach(Binding binding, Channel channel) throws Refusal {
        Binding current = authorizeRelay(binding.ticket());
        if (!current.equals(binding)) {
            throw new Refusal(Failure.FORBIDDEN, "The relay ticket is invalid or expired.");
        }
        Room room = rooms.get(binding.roomCode());
        Participant participant = room.participants.get(binding.endpointId());
        Channel previous = participant.channel;
        participant.channel = channel;
        participant.lastSeen = clock.millis();
        if (binding.endpointId() == 0) {
            room.hostSeen = participant.lastSeen;
        }
        if (previous != null && previous != channel) {
            previous.close();
        }
    }

    synchronized void detach(Binding binding, Channel channel) {
        Room room = rooms.get(binding.roomCode());
        if (room == null) {
            return;
        }
        Participant participant = room.participants.get(binding.endpointId());
        if (participant != null && participant.channel == channel) {
            participant.channel = null;
            participant.lastSeen = clock.millis();
        }
    }

    synchronized boolean forward(Binding sender, int targetId, ByteBuf payload) {
        Room room = rooms.get(sender.roomCode());
        if (room == null || payload.readableBytes() > MAX_RELAY_PAYLOAD) {
            return false;
        }
        Participant source = room.participants.get(sender.endpointId());
        Participant target = room.participants.get(targetId);
        if (source == null || target == null || source.channel == null || target.channel == null) {
            return false;
        }
        // Clients speak only to the authoritative host. The host may forward to any seat.
        if (sender.endpointId() != 0 && targetId != 0) {
            return false;
        }
        long now = clock.millis();
        source.lastSeen = now;
        if (sender.endpointId() == 0) {
            room.hostSeen = now;
        }
        ByteBuf envelope = Unpooled.buffer(5 + payload.readableBytes());
        envelope.writeByte(1);
        envelope.writeInt(sender.endpointId());
        envelope.writeBytes(payload, payload.readerIndex(), payload.readableBytes());
        target.channel.writeAndFlush(new BinaryWebSocketFrame(envelope));
        return true;
    }

    synchronized void markPlaying(Binding sender) {
        if (sender.endpointId() != 0) {
            return;
        }
        Room room = rooms.get(sender.roomCode());
        if (room != null) {
            room.phase = Phase.PLAYING;
            room.hostSeen = clock.millis();
        }
    }

    synchronized void closeFromRelay(Binding sender) {
        if (sender.endpointId() != 0) {
            return;
        }
        Room room = rooms.get(sender.roomCode());
        if (room != null) {
            remove(room);
        }
    }

    synchronized void prune() {
        long now = clock.millis();
        for (Room room : new ArrayList<>(rooms.values())) {
            if (now - room.hostSeen > HOST_SILENCE_MILLIS) {
                remove(room);
                continue;
            }
            for (Participant participant : new ArrayList<>(room.participants.values())) {
                if (participant.endpointId == 0 || participant.channel != null) {
                    continue;
                }
                long limit = participant.lastSeen == participant.reservedAt
                        ? UNUSED_TICKET_MILLIS : RECONNECT_MILLIS;
                if (now - participant.lastSeen > limit) {
                    room.participants.remove(participant.endpointId);
                    tickets.remove(participant.ticket);
                }
            }
        }
    }

    private Room authorizedHost(String rawCode, String hostToken) throws Refusal {
        String code;
        try {
            code = RoomCode.normalize(rawCode);
        } catch (IllegalArgumentException invalid) {
            throw new Refusal(Failure.NOT_FOUND, "No waiting game has that code.");
        }
        Room room = rooms.get(code);
        if (room == null) {
            throw new Refusal(Failure.NOT_FOUND, "No waiting game has that code.");
        }
        if (!constantTimeEquals(room.hostToken, hostToken)) {
            throw new Refusal(Failure.FORBIDDEN, "Only the host can change that game.");
        }
        return room;
    }

    private Seat seat(Room room, Participant participant, String hostToken) {
        return new Seat(room.listing(), hostToken, relayUri.toString(), participant.ticket,
                participant.endpointId, 0, inviteBase.resolve(room.code).toString(),
                room.visibility);
    }

    private Participant participant(int id, String name, long now) {
        return new Participant(id, token(), name, now);
    }

    private String uniqueCode() throws Refusal {
        for (int attempt = 0; attempt < 100; attempt++) {
            String code = RoomCode.generate(random);
            if (!rooms.containsKey(code)) {
                return code;
            }
        }
        throw new Refusal(Failure.CLOSED, "Could not allocate a game code. Try again.");
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private void remove(Room room) {
        rooms.remove(room.code);
        for (Participant participant : room.participants.values()) {
            tickets.remove(participant.ticket);
            if (participant.channel != null) {
                participant.channel.close();
            }
        }
    }

    private static void validateCreate(CreateGameRequest request) throws Refusal {
        if (request == null || request.capacity() < 2 || request.capacity() > 8) {
            throw new Refusal(Failure.INVALID, "A game must have between two and eight slots.");
        }
        if (request.build() == null || request.build().isBlank()) {
            throw new Refusal(Failure.INVALID, "The game build is required.");
        }
    }

    private static String clean(String value, String fallback, int maximum) {
        String cleaned = value == null ? "" : value.strip().replaceAll("[\\p{Cntrl}]", "");
        if (cleaned.isEmpty()) {
            cleaned = fallback;
        }
        return cleaned.length() <= maximum ? cleaned : cleaned.substring(0, maximum);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (expected == null || actual == null || expected.length() != actual.length()) {
            return false;
        }
        int difference = 0;
        for (int i = 0; i < expected.length(); i++) {
            difference |= expected.charAt(i) ^ actual.charAt(i);
        }
        return difference == 0;
    }
}
