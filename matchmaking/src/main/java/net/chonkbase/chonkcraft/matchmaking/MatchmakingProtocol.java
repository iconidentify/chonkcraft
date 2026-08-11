package net.chonkbase.chonkcraft.matchmaking;

import java.util.List;

/** JSON records shared by the native client and the room service. */
public final class MatchmakingProtocol {

    /** Increment only for an incompatible API or relay envelope. */
    public static final int VERSION = 1;

    /** The game wire build this client speaks. Overridable by release packaging. */
    public static String gameBuild() {
        return normalizeBuild(System.getProperty("chonkcraft.network.build",
                System.getProperty("chonkcraft.version", "Development")));
    }

    /** A bounded, non-empty identity safe to carry in lobby and service packets. */
    public static String normalizeBuild(String build) {
        String cleaned = build == null ? "" : build.trim();
        if (cleaned.isEmpty()) {
            cleaned = "Development";
        }
        return cleaned.length() <= 64 ? cleaned : cleaned.substring(0, 64);
    }

    private MatchmakingProtocol() {
    }

    public enum Visibility {
        PUBLIC,
        PRIVATE
    }

    public enum Phase {
        WAITING,
        STARTING,
        PLAYING
    }

    public record CreateGameRequest(String name, String map, String mapHash, int capacity,
            Visibility visibility, String build) {
    }

    public record JoinGameRequest(String playerName, String build) {
    }

    public record HostStateRequest(int players, int capacity, Phase phase) {
    }

    public record GameListing(String code, String name, String map, int players, int capacity,
            String build, Phase phase, long createdAt) {

        public boolean hasRoom() {
            return phase == Phase.WAITING && players < capacity;
        }
    }

    public record GameList(List<GameListing> games, long refreshedAt) {
        public GameList {
            games = games == null ? List.of() : List.copyOf(games);
        }
    }

    public record Seat(GameListing game, String hostToken, String relayUri, String relayTicket,
            int endpointId, int hostEndpointId, String inviteUrl, Visibility visibility) {
    }

    public record ErrorResponse(String code, String message) {
    }
}
