package dev.paysim.transport.netty;

import dev.paysim.transport.LengthHeader;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * A length-prefixed TCP server on Netty. Message-agnostic: it hands your
 * {@link FrameHandler} whole {@code byte[]} frames and knows nothing about
 * ISO 8583.
 *
 * <p><b>Threading rule:</b> the Netty event loop only moves bytes. It never runs
 * application code. {@code onFrame} and every {@link ConnectionListener}
 * callback run on the executor (default: one virtual thread per task), through a
 * per-connection serial queue: frames from one connection are handled one at a
 * time in arrival order, while different connections run in parallel. A slow or
 * blocking handler therefore delays only its own connection.
 */
public final class NettyFrameServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(NettyFrameServer.class.getName());

    public static Builder builder(LengthHeader header, FrameHandler handler) {
        return new Builder(header, handler);
    }

    public static final class Builder {
        private final LengthHeader header;
        private final FrameHandler handler;
        private int port;
        private int maxFrameLength;
        private Duration readerIdle = Duration.ZERO;
        private Duration writerIdle = Duration.ZERO;
        private ConnectionListener listener = new ConnectionListener() {
        };
        private Executor executor;

        private Builder(LengthHeader header, FrameHandler handler) {
            this.header = Objects.requireNonNull(header);
            this.handler = Objects.requireNonNull(handler);
            this.maxFrameLength = header.maxLength();
        }

        /** Port to bind; 0 (default) picks a free one. */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        public Builder maxFrameLength(int maxFrameLength) {
            this.maxFrameLength = maxFrameLength;
            return this;
        }

        /** Fire {@code onIdle(READER)} after this long without reading; zero = off. */
        public Builder readerIdle(Duration readerIdle) {
            this.readerIdle = readerIdle;
            return this;
        }

        /** Fire {@code onIdle(WRITER)} after this long without writing; zero = off. */
        public Builder writerIdle(Duration writerIdle) {
            this.writerIdle = writerIdle;
            return this;
        }

        public Builder listener(ConnectionListener listener) {
            this.listener = Objects.requireNonNull(listener);
            return this;
        }

        /** Where handlers run. Default: a virtual-thread-per-task executor owned (and closed) by the server. */
        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        public NettyFrameServer build() {
            return new NettyFrameServer(this);
        }
    }

    private final Builder config;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private EventLoopGroup boss;
    private EventLoopGroup workers;
    private Channel serverChannel;

    private NettyFrameServer(Builder b) {
        this.config = b;
        if (b.maxFrameLength < 0 || b.maxFrameLength > b.header.maxLength()) {
            throw new IllegalArgumentException("maxFrameLength must be between 0 and " + b.header.maxLength());
        }
        if (b.executor == null) {
            this.ownedExecutor = Executors.newVirtualThreadPerTaskExecutor();
            this.executor = ownedExecutor;
        } else {
            this.ownedExecutor = null;
            this.executor = b.executor;
        }
    }

    /** Binds and starts accepting. Returns the bound port. */
    public synchronized int start() throws InterruptedException {
        if (serverChannel != null) {
            throw new IllegalStateException("already started");
        }
        boss = new NioEventLoopGroup(1, new DefaultThreadFactory("frame-server-boss", true));
        workers = new NioEventLoopGroup(0, new DefaultThreadFactory("frame-server-io", true));
        try {
            serverChannel = new ServerBootstrap()
                    .group(boss, workers)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_REUSEADDR, true)
                    .option(ChannelOption.SO_BACKLOG, 1024) // absorb bursts of simultaneous connects
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            FrameChannelHandler.install(ch.pipeline(), config.header, config.maxFrameLength,
                                    config.readerIdle, config.writerIdle,
                                    new FrameChannelHandler(config.handler, config.listener, executor,
                                            FrameChannelHandler.Observer.NONE));
                        }
                    })
                    .bind(config.port).sync().channel();
        } catch (InterruptedException | RuntimeException e) {
            shutdownGroups();
            throw e;
        }
        int bound = port();
        LOG.info(() -> "Frame server listening on port " + bound + " with " + config.header + " headers");
        return bound;
    }

    /** The bound port (valid after {@link #start()}). */
    public synchronized int port() {
        if (serverChannel == null) {
            throw new IllegalStateException("not started");
        }
        return ((InetSocketAddress) serverChannel.localAddress()).getPort();
    }

    /** Stops accepting, closes all connections, stops the event loops and waits for them. */
    @Override
    public synchronized void close() {
        if (serverChannel != null) {
            serverChannel.close().awaitUninterruptibly();
            serverChannel = null;
        }
        shutdownGroups();
        if (ownedExecutor != null) {
            ownedExecutor.shutdown();
            try {
                if (!ownedExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
                    ownedExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                ownedExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private void shutdownGroups() {
        if (workers != null) {
            workers.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly();
            workers = null;
        }
        if (boss != null) {
            boss.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly();
            boss = null;
        }
    }
}
