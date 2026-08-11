package net.chonkbase.chonkcraft.matchmaking;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.CreateGameRequest;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.ErrorResponse;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.GameList;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.HostStateRequest;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.JoinGameRequest;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.Seat;

/** Small synchronous API used from the desktop's background networking thread. */
public final class MatchmakingClient {

    public static final URI DEFAULT_SERVICE = URI.create(
            System.getProperty("chonkcraft.matchmaker.url", "https://match.chonkbase.net"));

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(8);

    private final URI service;
    private final HttpClient http;
    private final ObjectMapper json;

    public MatchmakingClient() {
        this(DEFAULT_SERVICE);
    }

    public MatchmakingClient(URI service) {
        this(service, HttpClient.newBuilder().connectTimeout(REQUEST_TIMEOUT).build(),
                new ObjectMapper());
    }

    MatchmakingClient(URI service, HttpClient http, ObjectMapper json) {
        this.service = service;
        this.http = http;
        this.json = json;
    }

    public URI service() {
        return service;
    }

    public GameList games() throws IOException, InterruptedException {
        return games(MatchmakingProtocol.gameBuild());
    }

    public GameList games(String compatibleBuild) throws IOException, InterruptedException {
        String build = URLEncoder.encode(compatibleBuild, StandardCharsets.UTF_8);
        return send(HttpRequest.newBuilder(service.resolve("/v1/games?build=" + build)).GET().build(),
                GameList.class);
    }

    public Seat create(CreateGameRequest request) throws IOException, InterruptedException {
        return send(jsonRequest("/v1/games", "POST", request, null), Seat.class);
    }

    public Seat join(String code, JoinGameRequest request) throws IOException, InterruptedException {
        String normalized = RoomCode.normalize(code);
        return send(jsonRequest("/v1/games/" + normalized + "/join", "POST", request, null),
                Seat.class);
    }

    public void update(String code, String hostToken, HostStateRequest request)
            throws IOException, InterruptedException {
        send(jsonRequest("/v1/games/" + RoomCode.normalize(code), "PATCH", request, hostToken),
                Void.class);
    }

    public void close(String code, String hostToken) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(
                        service.resolve("/v1/games/" + RoomCode.normalize(code)))
                .timeout(REQUEST_TIMEOUT)
                .header("Authorization", "Bearer " + hostToken)
                .DELETE()
                .build();
        send(request, Void.class);
    }

    private HttpRequest jsonRequest(String path, String method, Object body, String bearer)
            throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(service.resolve(path))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json");
        if (bearer != null) {
            request.header("Authorization", "Bearer " + bearer);
        }
        return request.method(method, HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(body)))
                .build();
    }

    private <T> T send(HttpRequest request, Class<T> responseType)
            throws IOException, InterruptedException {
        HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() / 100 != 2) {
            ErrorResponse error;
            try {
                error = json.readValue(response.body(), ErrorResponse.class);
            } catch (RuntimeException | IOException malformed) {
                error = new ErrorResponse("http_" + response.statusCode(),
                        "The game service returned HTTP " + response.statusCode() + ".");
            }
            throw new MatchmakingException(response.statusCode(), error.code(), error.message());
        }
        if (responseType == Void.class || response.body().length == 0) {
            return null;
        }
        return json.readValue(response.body(), responseType);
    }
}
