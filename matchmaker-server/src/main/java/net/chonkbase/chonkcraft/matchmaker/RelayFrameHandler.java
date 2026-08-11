package net.chonkbase.chonkcraft.matchmaker;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.BinaryWebSocketFrame;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleStateEvent;
import net.chonkbase.chonkcraft.matchmaker.RoomDirectory.Binding;

/** Authenticated forwarding of opaque lobby and lockstep datagrams. */
final class RelayFrameHandler extends SimpleChannelInboundHandler<BinaryWebSocketFrame> {

    private final RoomDirectory rooms;
    private Binding binding;

    RelayFrameHandler(RoomDirectory rooms) {
        this.rooms = rooms;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext context, Object event) throws Exception {
        if (event instanceof IdleStateEvent) {
            context.close();
            return;
        }
        if (event instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            binding = context.channel().attr(MatchmakingHttpHandler.RELAY_BINDING).get();
            if (binding == null) {
                context.close();
                return;
            }
            try {
                rooms.attach(binding, context.channel());
            } catch (RoomDirectory.Refusal refused) {
                context.writeAndFlush(new CloseWebSocketFrame(1008, refused.getMessage()))
                        .addListener(io.netty.channel.ChannelFutureListener.CLOSE);
            }
        }
        super.userEventTriggered(context, event);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext context, BinaryWebSocketFrame frame) {
        if (binding == null || !frame.content().isReadable()) {
            return;
        }
        int index = frame.content().readerIndex();
        byte kind = frame.content().getByte(index);
        if (kind == 2 && frame.content().readableBytes() == 1) {
            rooms.markPlaying(binding);
            return;
        }
        if (kind == 3 && frame.content().readableBytes() == 1) {
            rooms.closeFromRelay(binding);
            return;
        }
        if (kind != 1 || frame.content().readableBytes() < 5) {
            return;
        }
        int target = frame.content().getInt(index + 1);
        rooms.forward(binding, target, frame.content().slice(index + 5,
                frame.content().readableBytes() - 5));
    }

    @Override
    public void channelInactive(ChannelHandlerContext context) throws Exception {
        if (binding != null) {
            rooms.detach(binding, context.channel());
        }
        super.channelInactive(context);
    }
}
