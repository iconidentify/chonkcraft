package net.chonkbase.chonkcraft.matchmaker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaderValues;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.AttributeKey;
import java.nio.charset.StandardCharsets;
import java.io.IOException;
import java.util.List;
import net.chonkbase.chonkcraft.matchmaker.RoomDirectory.Binding;
import net.chonkbase.chonkcraft.matchmaker.RoomDirectory.Failure;
import net.chonkbase.chonkcraft.matchmaker.RoomDirectory.Refusal;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.CreateGameRequest;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.ErrorResponse;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.GameList;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.HostStateRequest;
import net.chonkbase.chonkcraft.matchmaking.MatchmakingProtocol.JoinGameRequest;

/** REST room directory plus the authenticated handoff to the binary relay. */
final class MatchmakingHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    static final AttributeKey<Binding> RELAY_BINDING = AttributeKey.valueOf("relay-binding");

    private static final int MAX_BODY_BYTES = 16 * 1024;

    private final RoomDirectory rooms;
    private final ObjectMapper json;
    private final RequestRateLimiter rateLimiter;

    MatchmakingHttpHandler(RoomDirectory rooms, ObjectMapper json) {
        this(rooms, json, new RequestRateLimiter());
    }

    MatchmakingHttpHandler(RoomDirectory rooms, ObjectMapper json,
            RequestRateLimiter rateLimiter) {
        this.rooms = rooms;
        this.json = json;
        this.rateLimiter = rateLimiter;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, FullHttpRequest request) {
        QueryStringDecoder uri = new QueryStringDecoder(request.uri());
        try {
            if ("/relay".equals(uri.path()) && isUpgrade(request)) {
                Binding binding = rooms.authorizeRelay(bearer(request));
                context.channel().attr(RELAY_BINDING).set(binding);
                context.fireChannelRead(request.retain());
                return;
            }
            if (request.method() == HttpMethod.GET && "/health".equals(uri.path())) {
                send(context, request, HttpResponseStatus.OK, new Health("ok", 1));
                return;
            }
            if (request.method() == HttpMethod.GET && "/v1/games".equals(uri.path())) {
                if (!allowed(context, request, "browse", 120)) {
                    tooMany(context, request);
                    return;
                }
                String build = first(uri, "build", "dev");
                send(context, request, HttpResponseStatus.OK,
                        new GameList(rooms.list(build), System.currentTimeMillis()));
                return;
            }
            if (request.method() == HttpMethod.POST && "/v1/games".equals(uri.path())) {
                if (!allowed(context, request, "create", 20)) {
                    tooMany(context, request);
                    return;
                }
                send(context, request, HttpResponseStatus.CREATED,
                        rooms.create(read(request, CreateGameRequest.class)));
                return;
            }
            String[] parts = uri.path().split("/");
            if (parts.length == 5 && "v1".equals(parts[1]) && "games".equals(parts[2])
                    && "join".equals(parts[4]) && request.method() == HttpMethod.POST) {
                if (!allowed(context, request, "join", 30)) {
                    tooMany(context, request);
                    return;
                }
                send(context, request, HttpResponseStatus.OK,
                        rooms.join(parts[3], read(request, JoinGameRequest.class)));
                return;
            }
            if (parts.length == 4 && "v1".equals(parts[1]) && "games".equals(parts[2])) {
                if (request.method() == HttpMethod.PATCH) {
                    rooms.update(parts[3], bearer(request),
                            read(request, HostStateRequest.class));
                    sendEmpty(context, request, HttpResponseStatus.NO_CONTENT);
                    return;
                }
                if (request.method() == HttpMethod.DELETE) {
                    rooms.close(parts[3], bearer(request));
                    sendEmpty(context, request, HttpResponseStatus.NO_CONTENT);
                    return;
                }
            }
            sendError(context, request, HttpResponseStatus.NOT_FOUND, "not_found",
                    "That matchmaking endpoint does not exist.");
        } catch (Refusal refusal) {
            sendError(context, request, status(refusal.failure()),
                    refusal.failure().name().toLowerCase(), refusal.getMessage());
        } catch (IOException malformed) {
            sendError(context, request, HttpResponseStatus.BAD_REQUEST, "invalid_json",
                    "The request was not valid JSON.");
        } catch (RuntimeException failure) {
            sendError(context, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "server_error", "The matchmaking service could not complete that request.");
        }
    }

    private boolean allowed(ChannelHandlerContext context, FullHttpRequest request, String lane,
            int limit) {
        // Production accepts traffic only from the cluster ingress (enforced by NetworkPolicy),
        // which replaces this header with the real client address. Without it every player would
        // share the ingress pod's single bucket. Local development falls back to the TCP peer.
        String forwarded = request.headers().get("X-Real-IP", "").strip();
        return forwarded.isEmpty()
                ? rateLimiter.allow(context.channel().remoteAddress(), lane, limit)
                : rateLimiter.allow(forwarded.substring(0, Math.min(64, forwarded.length())),
                        lane, limit);
    }

    private void tooMany(ChannelHandlerContext context, FullHttpRequest request) {
        sendError(context, request, HttpResponseStatus.TOO_MANY_REQUESTS, "rate_limited",
                "Too many requests. Wait a minute and try again.");
    }

    private <T> T read(FullHttpRequest request, Class<T> type) throws IOException {
        if (request.content().readableBytes() > MAX_BODY_BYTES) {
            throw new IllegalArgumentException("request body is too large");
        }
        byte[] body = new byte[request.content().readableBytes()];
        request.content().getBytes(request.content().readerIndex(), body);
        return json.readValue(body, type);
    }

    private static String bearer(FullHttpRequest request) throws Refusal {
        String authorization = request.headers().get(HttpHeaderNames.AUTHORIZATION, "");
        if (!authorization.startsWith("Bearer ") || authorization.length() <= 7) {
            throw new Refusal(Failure.FORBIDDEN, "A valid room credential is required.");
        }
        return authorization.substring(7);
    }

    private static boolean isUpgrade(FullHttpRequest request) {
        return "websocket".equalsIgnoreCase(request.headers().get(HttpHeaderNames.UPGRADE));
    }

    private static String first(QueryStringDecoder uri, String key, String fallback) {
        List<String> values = uri.parameters().get(key);
        return values == null || values.isEmpty() ? fallback : values.getFirst();
    }

    private void send(ChannelHandlerContext context, FullHttpRequest request,
            HttpResponseStatus status, Object body) {
        try {
            byte[] bytes = json.writeValueAsBytes(body);
            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status,
                    Unpooled.wrappedBuffer(bytes));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=utf-8");
            response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, bytes.length);
            finish(context, request, response);
        } catch (JsonProcessingException impossible) {
            sendError(context, request, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                    "server_error", "The response could not be encoded.");
        }
    }

    private void sendError(ChannelHandlerContext context, FullHttpRequest request,
            HttpResponseStatus status, String code, String message) {
        send(context, request, status, new ErrorResponse(code, message));
    }

    private static void sendEmpty(ChannelHandlerContext context, FullHttpRequest request,
            HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        finish(context, request, response);
    }

    private static void finish(ChannelHandlerContext context, FullHttpRequest request,
            FullHttpResponse response) {
        response.headers().set("X-Content-Type-Options", "nosniff");
        response.headers().set("Cache-Control", "no-store");
        boolean keepAlive = HttpUtil.isKeepAlive(request);
        if (keepAlive) {
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            context.writeAndFlush(response);
        } else {
            context.writeAndFlush(response).addListener(io.netty.channel.ChannelFutureListener.CLOSE);
        }
    }

    private static HttpResponseStatus status(Failure failure) {
        return switch (failure) {
            case INVALID -> HttpResponseStatus.BAD_REQUEST;
            case FORBIDDEN -> HttpResponseStatus.UNAUTHORIZED;
            case NOT_FOUND -> HttpResponseStatus.NOT_FOUND;
            case FULL, CLOSED -> HttpResponseStatus.CONFLICT;
            case VERSION_MISMATCH -> HttpResponseStatus.UPGRADE_REQUIRED;
        };
    }

    private record Health(String status, int protocol) {
    }
}
