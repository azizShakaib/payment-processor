package dev.paysim.transport.netty;

import dev.paysim.transport.LengthHeader;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.util.concurrent.DefaultThreadFactory;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;

/**
 * A length-prefixed TCP client that keeps its link up. After any failed
 * attempt or disconnect it reconnects, waiting a {@link Backoff} delay between
 * attempts, until {@link #close()}. The backoff resets after a successful connect.
 *
 * <pre>
 *   NEW -> CONNECTING -> CONNECTED -> (link drops) -> BACKING_OFF -> CONNECTING ...
 *                 \-> (attempt fails) -> BACKING_OFF            any state -> CLOSED
 * </pre>
 *
 * <p>Threading is the same as {@link NettyFrameServer}: the event loop never
 * runs application code; callbacks go through a per-connection serial queue on
 * the executor. {@link #send} does not queue: with no live link it fails fast
 * with {@link NotConnectedException}, so callers (and, later, the switch's
 * timeout and reversal logic) always know whether a message left.
 */
public final class NettyFrameClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(NettyFrameClient.class.getName());

    public enum State { NEW, CONNECTING, CONNECTED, BACKING_OFF, CLOSED }

    public static Builder builder(String host, int port, LengthHeader header, FrameHandler handler) {
        return new Builder(host, port, header, handler);
    }

    public static final class Builder {
        private final String host;
        private final int port;
        private final LengthHeader header;
        private final FrameHandler handler;
        private int maxFrameLength;
        private Duration readerIdle = Duration.ZERO;
        private Duration writerIdle = Duration.ZERO;
        private ConnectionListener listener = new ConnectionListener() {
        };
        private Backoff backoff = new Backoff();
        private Duration connectTimeout = Duration.ofSeconds(5);
        private Executor executor;

        private Builder(String host, int port, LengthHeader header, FrameHandler handler) {
            this.host = Objects.requireNonNull(host);
            this.port = port;
            this.header = Objects.requireNonNull(header);
            this.handler = Objects.requireNonNull(handler);
            this.maxFrameLength = header.maxLength();
        }

        public Builder maxFrameLength(int maxFrameLength) {
            this.maxFrameLength = maxFrameLength;
            return this;
        }

        public Builder readerIdle(Duration readerIdle) {
            this.readerIdle = readerIdle;
            return this;
        }

        public Builder writerIdle(Duration writerIdle) {
            this.writerIdle = writerIdle;
            return this;
        }

        public Builder listener(ConnectionListener listener) {
            this.listener = Objects.requireNonNull(listener);
            return this;
        }

        public Builder backoff(Backoff backoff) {
            this.backoff = Objects.requireNonNull(backoff);
            return this;
        }

        public Builder connectTimeout(Duration connectTimeout) {
            this.connectTimeout = connectTimeout;
            return this;
        }

        public Builder executor(Executor executor) {
            this.executor = executor;
            return this;
        }

        public NettyFrameClient build() {
            return new NettyFrameClient(this);
        }
    }

    private final Builder config;
    private final Executor executor;
    private final ExecutorService ownedExecutor;
    private final Object lock = new Object();
    private final AtomicInteger attempts = new AtomicInteger();
    private final CompletableFuture<FrameConnection> connected = new CompletableFuture<>();
    private EventLoopGroup group;
    private Bootstrap bootstrap;
    private State state = State.NEW;
    private FrameConnection current;
    private ScheduledFuture<?> pending;

    private NettyFrameClient(Builder b) {
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

    /** Begins connecting in the background and returns at once. */
    public NettyFrameClient start() {
        synchronized (lock) {
            if (state != State.NEW) {
                throw new IllegalStateException("already started: " + state);
            }
            state = State.CONNECTING;
            group = new NioEventLoopGroup(1, new DefaultThreadFactory("frame-client-io", true));
            FrameChannelHandler.Observer observer = new FrameChannelHandler.Observer() {
                @Override
                public void onActive(FrameConnection connection) {
                    linkUp(connection);
                }

                @Override
                public void onInactive(FrameConnection connection) {
                    linkDown(connection);
                }
            };
            bootstrap = new Bootstrap()
                    .group(group)
                    .channel(NioSocketChannel.class)
                    .option(ChannelOption.TCP_NODELAY, true)
                    .option(ChannelOption.SO_KEEPALIVE, true)
                    .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) config.connectTimeout.toMillis())
                    .handler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            FrameChannelHandler.install(ch.pipeline(), config.header, config.maxFrameLength,
                                    config.readerIdle, config.writerIdle,
                                    new FrameChannelHandler(config.handler, config.listener, executor, observer));
                        }
                    });
        }
        connect();
        return this;
    }

    private void connect() {
        synchronized (lock) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.CONNECTING;
        }
        int n = attempts.incrementAndGet();
        LOG.fine(() -> "Connect attempt " + n + " to " + config.host + ":" + config.port);
        try {
            bootstrap.connect(config.host, config.port).addListener(f -> {
                if (!f.isSuccess()) {
                    LOG.fine(() -> "Connect attempt " + n + " failed: " + f.cause().getClass().getSimpleName());
                    scheduleReconnect();
                }
            });
        } catch (RejectedExecutionException e) {
            LOG.fine("Event loop shut down; not connecting");
        }
    }

    private void linkUp(FrameConnection connection) {
        synchronized (lock) {
            if (state == State.CLOSED) {
                connection.close();
                return;
            }
            state = State.CONNECTED;
            current = connection;
            config.backoff.reset();
        }
        connected.complete(connection);
    }

    private void linkDown(FrameConnection connection) {
        synchronized (lock) {
            if (current == connection) {
                current = null;
            }
        }
        scheduleReconnect();
    }

    private void scheduleReconnect() {
        synchronized (lock) {
            if (state == State.CLOSED) {
                return;
            }
            state = State.BACKING_OFF;
            Duration delay = config.backoff.next();
            LOG.fine(() -> "Reconnecting in " + delay.toMillis() + " ms");
            try {
                pending = group.schedule(this::connect, delay.toMillis(), TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                LOG.fine("Event loop shut down; not reconnecting");
            }
        }
    }

    /** Completes with the first connection that comes up (later reconnects do not re-complete it). */
    public CompletableFuture<FrameConnection> connected() {
        return connected;
    }

    public State state() {
        synchronized (lock) {
            return state;
        }
    }

    /** Number of connect attempts started so far, successful or not. */
    public int connectAttempts() {
        return attempts.get();
    }

    /**
     * Sends one frame on the current link. With no live link the returned future
     * is already failed with {@link NotConnectedException}; nothing is queued.
     */
    public CompletableFuture<Void> send(byte[] frame) {
        FrameConnection c;
        synchronized (lock) {
            c = current;
        }
        if (c == null || !c.isActive()) {
            return CompletableFuture.failedFuture(new NotConnectedException(
                    "not connected to " + config.host + ":" + config.port + " (state " + state() + ")"));
        }
        return c.send(frame);
    }

    /** Stops reconnecting, closes the link, stops the event loop and waits for it. */
    @Override
    public void close() {
        EventLoopGroup g;
        synchronized (lock) {
            if (state == State.CLOSED) {
                return;
            }
            boolean started = state != State.NEW;
            state = State.CLOSED;
            if (pending != null) {
                pending.cancel(false);
            }
            if (current != null) {
                current.close();
            }
            g = started ? group : null;
        }
        if (g != null) {
            g.shutdownGracefully(0, 2, TimeUnit.SECONDS).awaitUninterruptibly();
        }
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
}
