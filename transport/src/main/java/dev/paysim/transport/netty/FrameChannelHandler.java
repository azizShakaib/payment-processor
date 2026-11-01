package dev.paysim.transport.netty;

import dev.paysim.transport.FramingException;
import dev.paysim.transport.LengthHeader;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.handler.timeout.IdleStateHandler;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The last handler in every pipeline (server and client). It runs on the
 * event loop but does no application work: it only turns Netty events into
 * tasks on the connection's {@link SerialExecutor}, which runs them on the
 * application executor. It never logs frame contents, only lengths and peers.
 */
final class FrameChannelHandler extends ChannelInboundHandlerAdapter {

    private static final Logger LOG = Logger.getLogger(FrameChannelHandler.class.getName());

    /** Synchronous hooks on the event loop, for the client's own state machine. */
    interface Observer {
        Observer NONE = new Observer() {
        };

        default void onActive(FrameConnection connection) {
        }

        default void onInactive(FrameConnection connection) {
        }
    }

    private final FrameHandler handler;
    private final ConnectionListener listener;
    private final Executor executor;
    private final Observer observer;
    private FrameConnection connection;
    private SerialExecutor serial;

    FrameChannelHandler(FrameHandler handler, ConnectionListener listener, Executor executor, Observer observer) {
        this.handler = handler;
        this.listener = listener;
        this.executor = executor;
        this.observer = observer;
    }

    /** Adds idle detection, decoder, encoder and this handler, in that order. */
    static void install(ChannelPipeline pipeline, LengthHeader header, int maxFrameLength,
            Duration readerIdle, Duration writerIdle, FrameChannelHandler last) {
        if (!readerIdle.isZero() || !writerIdle.isZero()) {
            pipeline.addLast(new IdleStateHandler(
                    readerIdle.toMillis(), writerIdle.toMillis(), 0, TimeUnit.MILLISECONDS));
        }
        pipeline.addLast(new LengthHeaderFrameDecoder(header, maxFrameLength));
        pipeline.addLast(new LengthHeaderFrameEncoder(header, maxFrameLength));
        pipeline.addLast(last);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        connection = new FrameConnection(ctx.channel());
        serial = new SerialExecutor(executor);
        FrameConnection c = connection;
        LOG.fine(() -> "Connected: " + c.remote());
        observer.onActive(c);
        serial.execute(() -> listener.onConnected(c));
        ctx.fireChannelActive();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (connection != null) {
            FrameConnection c = connection;
            LOG.fine(() -> "Disconnected: " + c.remote());
            observer.onInactive(c);
            serial.execute(() -> listener.onDisconnected(c));
        }
        ctx.fireChannelInactive();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof byte[] frame && connection != null) {
            FrameConnection c = connection;
            LOG.finer(() -> "Frame of " + frame.length + " bytes from " + c.remote());
            serial.execute(() -> handler.onFrame(c, frame));
        }
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent idle && connection != null) {
            IdleKind kind = idle.state() == IdleState.READER_IDLE ? IdleKind.READER : IdleKind.WRITER;
            FrameConnection c = connection;
            serial.execute(() -> listener.onIdle(c, kind));
        } else {
            super.userEventTriggered(ctx, evt);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Throwable root = cause instanceof DecoderException && cause.getCause() != null ? cause.getCause() : cause;
        Object peer = ctx.channel().remoteAddress();
        if (root instanceof FramingException fe) {
            LOG.warning("Framing error from " + peer + ", closing: " + fe.getMessage());
            if (connection != null) {
                FrameConnection c = connection;
                serial.execute(() -> listener.onFramingError(c, fe));
            }
        } else if (root instanceof IOException) {
            LOG.fine(() -> "I/O error from " + peer + ": " + root.getClass().getSimpleName());
        } else {
            LOG.log(Level.WARNING, "Unexpected error from " + peer + ": " + root.getClass().getName());
        }
        ctx.close();
    }
}
