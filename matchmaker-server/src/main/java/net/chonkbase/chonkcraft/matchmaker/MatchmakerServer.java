package net.chonkbase.chonkcraft.matchmaker;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateHandler;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/** An embeddable service used by production Main and real two-client tests alike. */
public final class MatchmakerServer implements AutoCloseable {

    private final int requestedPort;
    private final RoomDirectory rooms;
    private final ObjectMapper json = new ObjectMapper();
    private final RequestRateLimiter rateLimiter = new RequestRateLimiter();
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private Channel server;

    public MatchmakerServer(int port, URI relayUri, URI inviteBase) {
        requestedPort = port;
        rooms = new RoomDirectory(relayUri, inviteBase);
    }

    public synchronized void start() throws InterruptedException {
        if (server != null) {
            return;
        }
        boss = new NioEventLoopGroup(1);
        workers = new NioEventLoopGroup();
        try {
            server = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel channel) {
                            channel.pipeline()
                                    .addLast("http", new HttpServerCodec())
                                    .addLast("aggregate", new HttpObjectAggregator(16 * 1024))
                                    .addLast("idle", new IdleStateHandler(90, 0, 0))
                                    .addLast("routes", new MatchmakingHttpHandler(rooms, json,
                                            rateLimiter))
                                    .addLast("websocket", new WebSocketServerProtocolHandler(
                                            WebSocketServerProtocolConfig.newBuilder()
                                                    .websocketPath("/relay")
                                                    .subprotocols("chonkcraft-relay-v1")
                                                    .allowExtensions(false)
                                                    .maxFramePayloadLength(1_300)
                                                    .checkStartsWith(false)
                                                    .build()))
                                    .addLast("relay", new RelayFrameHandler(rooms));
                        }
                    })
                    .bind(requestedPort).sync().channel();
            server.eventLoop().scheduleAtFixedRate(rooms::prune, 15, 15, TimeUnit.SECONDS);
        } catch (InterruptedException | RuntimeException failure) {
            close();
            throw failure;
        }
    }

    public synchronized int port() {
        if (server == null) {
            throw new IllegalStateException("server has not started");
        }
        return ((java.net.InetSocketAddress) server.localAddress()).getPort();
    }

    public synchronized void await() throws InterruptedException {
        if (server == null) {
            throw new IllegalStateException("server has not started");
        }
        server.closeFuture().sync();
    }

    @Override
    public synchronized void close() {
        if (server != null) {
            server.close().syncUninterruptibly();
            server = null;
        }
        if (workers != null) {
            workers.shutdownGracefully().syncUninterruptibly();
            workers = null;
        }
        if (boss != null) {
            boss.shutdownGracefully().syncUninterruptibly();
            boss = null;
        }
    }
}
