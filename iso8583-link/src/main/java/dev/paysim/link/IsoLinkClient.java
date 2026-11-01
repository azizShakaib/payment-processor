package dev.paysim.link;

import dev.paysim.common.Stan;
import dev.paysim.iso8583.IsoMessage;
import dev.paysim.iso8583.IsoPrinter;
import dev.paysim.iso8583.Mti;
import dev.paysim.iso8583.MessageClass;
import dev.paysim.iso8583.NetworkManagementCode;
import dev.paysim.iso8583.ResponseCode;
import dev.paysim.iso8583.spec.MessageSpec;
import dev.paysim.transport.LengthHeader;
import dev.paysim.transport.netty.Backoff;
import dev.paysim.transport.netty.ConnectionListener;
import dev.paysim.transport.netty.FrameConnection;
import dev.paysim.transport.FramingException;
import dev.paysim.transport.netty.IdleKind;
import dev.paysim.transport.netty.NettyFrameClient;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The acquirer/switch end of an ISO 8583 link: a {@link NettyFrameClient} that
 * signs on by itself, keeps the link alive with echo tests, matches responses
 * to requests, and answers network-management requests from the far side.
 *
 * <pre>
 *   DISCONNECTED -- TCP up --> CONNECTED -- 0800/001 answered 00 --> SIGNED_ON
 *        ^                         |  (no / bad answer: close)            |
 *        +---- link drops (any state, reconnect with backoff) ----------+
 *                                  any state -- close() --> CLOSED
 * </pre>
 *
 * <h2>Why nothing here waits with {@code get()} or {@code join()}</h2>
 * Frames and connection callbacks of one connection run one at a time on that
 * connection's serial queue. The response to our sign-on (or echo) arrives as a
 * frame, so it can only be delivered by that same queue. If {@code onConnected}
 * sent the sign-on and then blocked waiting for the answer, the queue would be
 * stuck inside {@code onConnected}, the answer would sit behind it, and the wait
 * could only end by timing out: a self-inflicted deadlock. So callbacks only
 * <em>start</em> an exchange and attach a continuation
 * ({@code whenComplete}) that runs when the answer (or the timeout) arrives.
 * Requests from the far side are run on their own virtual threads for the same
 * reason. The one blocking wait is the best-effort sign-off in {@link #close()},
 * which runs on the caller's thread; do not call {@code close()} from inside a
 * callback.
 *
 * <p>All state changes are reported to the {@link LinkListener} in order.
 */
public final class IsoLinkClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(IsoLinkClient.class.getName());
    private static final Duration SIGN_OFF_TIMEOUT = Duration.ofSeconds(1);

    public static Builder builder(String host, int port, LengthHeader header, MessageSpec spec) {
        return new Builder(host, port, header, spec);
    }

    public static final class Builder {
        private final String host;
        private final int port;
        private final LengthHeader header;
        private final MessageSpec spec;
        private Clock clock = Clock.systemUTC();
        private Stan stan = new Stan();
        private Duration requestTimeout = Duration.ofSeconds(10);
        private Duration signOnTimeout = Duration.ofSeconds(5);
        private Duration echoInterval = Duration.ofSeconds(30);
        private Duration echoTimeout = Duration.ofSeconds(5);
        private int maxEchoFailures = 2;
        private Backoff backoff = new Backoff();
        private IsoRequestHandler handler = request -> LinkSupport.reply(request, ResponseCode.INVALID_TRANSACTION);
        private LinkListener listener = new LinkListener() {
        };

        private Builder(String host, int port, LengthHeader header, MessageSpec spec) {
            this.host = Objects.requireNonNull(host);
            this.port = port;
            this.header = Objects.requireNonNull(header);
            this.spec = Objects.requireNonNull(spec);
        }

        public Builder clock(Clock clock) {
            this.clock = Objects.requireNonNull(clock);
            return this;
        }

        /** Source of field 11 for the link's own messages. Share it with your application requests: see {@link IsoLinkClient#stan()}. */
        public Builder stan(Stan stan) {
            this.stan = Objects.requireNonNull(stan);
            return this;
        }

        /** Default timeout of {@link IsoLinkClient#request(IsoMessage)}. */
        public Builder requestTimeout(Duration requestTimeout) {
            this.requestTimeout = requestTimeout;
            return this;
        }

        public Builder signOnTimeout(Duration signOnTimeout) {
            this.signOnTimeout = signOnTimeout;
            return this;
        }

        /** Send an echo test after this much silence in either direction; zero turns keep-alive off. */
        public Builder echoInterval(Duration echoInterval) {
            this.echoInterval = echoInterval;
            return this;
        }

        public Builder echoTimeout(Duration echoTimeout) {
            this.echoTimeout = echoTimeout;
            return this;
        }

        /** Consecutive unanswered echoes after which the link is dropped and re-established. */
        public Builder maxEchoFailures(int maxEchoFailures) {
            this.maxEchoFailures = maxEchoFailures;
            return this;
        }

        public Builder backoff(Backoff backoff) {
            this.backoff = Objects.requireNonNull(backoff);
            return this;
        }

        /** For requests the far side initiates. The default answers 39=12 (invalid transaction). */
        public Builder handler(IsoRequestHandler handler) {
            this.handler = Objects.requireNonNull(handler);
            return this;
        }

        public Builder listener(LinkListener listener) {
            this.listener = Objects.requireNonNull(listener);
            return this;
        }

        public IsoLinkClient build() {
            return new IsoLinkClient(this);
        }
    }

    private final MessageSpec spec;
    private final Clock clock;
    private final Stan stan;
    private final Duration requestTimeout;
    private final Duration signOnTimeout;
    private final Duration echoTimeout;
    private final Duration echoInterval;
    private final int maxEchoFailures;
    private final IsoRequestHandler handler;
    private final LinkListener listener;
    private final PendingRequests pending = new PendingRequests();
    private final ExecutorService handlerExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Object stateLock = new Object();
    private final AtomicInteger echoFailures = new AtomicInteger();
    private final AtomicBoolean echoInFlight = new AtomicBoolean();
    private final NettyFrameClient transport;
    private LinkState state = LinkState.DISCONNECTED;
    private volatile FrameConnection current;

    private IsoLinkClient(Builder b) {
        this.spec = b.spec;
        this.clock = b.clock;
        this.stan = b.stan;
        this.requestTimeout = b.requestTimeout;
        this.signOnTimeout = b.signOnTimeout;
        this.echoTimeout = b.echoTimeout;
        this.echoInterval = b.echoInterval;
        this.maxEchoFailures = b.maxEchoFailures;
        this.handler = b.handler;
        this.listener = b.listener;
        this.transport = NettyFrameClient.builder(b.host, b.port, b.header, this::onFrame)
                .readerIdle(b.echoInterval)
                .writerIdle(b.echoInterval)
                .backoff(b.backoff)
                .listener(new ConnectionListener() {
                    @Override
                    public void onConnected(FrameConnection connection) {
                        linkUp(connection);
                    }

                    @Override
                    public void onDisconnected(FrameConnection connection) {
                        linkDown(connection);
                    }

                    @Override
                    public void onIdle(FrameConnection connection, IdleKind kind) {
                        linkIdle(connection);
                    }

                    @Override
                    public void onFramingError(FrameConnection connection, FramingException error) {
                        LOG.warning(() -> "framing error: " + error.getClass().getSimpleName());
                    }
                })
                .build();
    }

    /** Starts connecting in the background and returns at once. */
    public IsoLinkClient start() {
        transport.start();
        return this;
    }

    public LinkState state() {
        synchronized (stateLock) {
            return state;
        }
    }

    /** The STAN source for the link's own messages; draw application STANs from it too, so they never collide. */
    public Stan stan() {
        return stan;
    }

    /** Number of requests in flight (sign-on, echo and application requests). */
    public int inFlight() {
        return pending.inFlight();
    }

    /** Waits until the link is in the given state; true if it got there in time. */
    public boolean awaitState(LinkState wanted, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (stateLock) {
            while (state != wanted) {
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return false;
                }
                TimeUnit.NANOSECONDS.timedWait(stateLock, left);
            }
            return true;
        }
    }

    /** Sends a request with the default timeout. See {@link #request(IsoMessage, Duration)}. */
    public CompletableFuture<IsoMessage> request(IsoMessage request) {
        return request(request, requestTimeout);
    }

    /**
     * Sends a request and returns the future of its response. Fails fast, with
     * {@link LinkNotReadyException}, unless the link is signed on: nothing is
     * queued. Otherwise the future completes with the matching response, or
     * exceptionally with {@link TimeoutException} (outcome unknown), with
     * {@link LinkDroppedException} (link lost while waiting; outcome unknown),
     * with {@link IllegalStateException} for a duplicate key in flight, or with
     * the send error.
     */
    public CompletableFuture<IsoMessage> request(IsoMessage request, Duration timeout) {
        FrameConnection c = current;
        LinkState s = state();
        if (s != LinkState.SIGNED_ON || c == null) {
            return CompletableFuture.failedFuture(new LinkNotReadyException("link is " + s + ", not SIGNED_ON"));
        }
        CompletableFuture<IsoMessage> future;
        try {
            future = pending.register(request, timeout);
        } catch (IllegalStateException e) {
            return CompletableFuture.failedFuture(e);
        }
        sendOrFail(c, request, future);
        return future;
    }

    private void sendOrFail(FrameConnection c, IsoMessage message, CompletableFuture<IsoMessage> future) {
        try {
            LinkSupport.send(c, message, LOG).whenComplete((v, e) -> {
                if (e != null) {
                    future.completeExceptionally(e);
                }
            });
        } catch (RuntimeException e) {
            future.completeExceptionally(e);
        }
    }

    // ---- connection life cycle (all non-blocking; see class Javadoc) ----

    private void linkUp(FrameConnection c) {
        current = c;
        echoFailures.set(0);
        echoInFlight.set(false);
        transition(null, LinkState.CONNECTED);
        IsoMessage signOn = network(NetworkManagementCode.SIGN_ON);
        CompletableFuture<IsoMessage> answer;
        try {
            answer = pending.register(signOn, signOnTimeout);
        } catch (IllegalStateException e) {
            c.close();
            return;
        }
        answer.whenComplete((response, error) -> {
            if (error == null && LinkSupport.approved(response)) {
                if (current == c) {
                    transition(LinkState.CONNECTED, LinkState.SIGNED_ON);
                }
            } else {
                LOG.fine(() -> "sign-on failed: " + (error != null ? error.getClass().getSimpleName() : "refused"));
                c.close(); // NettyFrameClient reconnects with backoff; sign-on repeats
            }
        });
        sendOrFail(c, signOn, answer);
    }

    private void linkDown(FrameConnection c) {
        if (current != c) {
            return;
        }
        current = null;
        transition(null, LinkState.DISCONNECTED);
        pending.failAll(new LinkDroppedException("link dropped; outcome of in-flight requests unknown"));
    }

    private void linkIdle(FrameConnection c) {
        if (state() != LinkState.SIGNED_ON || current != c || !echoInFlight.compareAndSet(false, true)) {
            return; // not ready, stale connection, or an echo is already out (reader and writer idle both fire)
        }
        IsoMessage echo = network(NetworkManagementCode.ECHO_TEST);
        CompletableFuture<IsoMessage> answer;
        try {
            answer = pending.register(echo, echoTimeout);
        } catch (IllegalStateException e) {
            echoInFlight.set(false);
            return;
        }
        answer.whenComplete((response, error) -> {
            echoInFlight.set(false);
            if (error == null && LinkSupport.approved(response)) {
                echoFailures.set(0);
            } else if (echoFailures.incrementAndGet() >= maxEchoFailures) {
                LOG.fine(() -> "echo failed " + maxEchoFailures + " times; dropping the link");
                c.close();
            }
        });
        sendOrFail(c, echo, answer);
    }

    // ---- incoming frames ----

    private void onFrame(FrameConnection c, byte[] frame) {
        IsoMessage m = LinkSupport.unpack(spec, frame, listener, LOG);
        if (m == null) {
            return;
        }
        LOG.fine(() -> "recv " + IsoPrinter.format(m));
        if (m.mti().isResponse()) {
            if (!pending.complete(m)) {
                LOG.fine(() -> "unmatched response " + MatchKey.of(m));
                listener.onUnmatchedResponse(m);
            }
        } else if (m.mti().messageClass() == MessageClass.NETWORK_MANAGEMENT) {
            LinkSupport.sendQuietly(c, LinkSupport.reply(m, ResponseCode.APPROVED), LOG);
        } else {
            try {
                handlerExecutor.execute(() -> {
                    IsoMessage response = LinkSupport.runHandler(handler, m, LOG);
                    if (response != null) {
                        LinkSupport.sendQuietly(c, response, LOG);
                    }
                });
            } catch (RejectedExecutionException e) {
                LOG.fine("closing; request dropped");
            }
        }
    }

    // ---- helpers ----

    private IsoMessage network(NetworkManagementCode code) {
        return IsoMessage.builder(spec, Mti.NETWORK_REQUEST)
                .transmissionDateTime(clock)
                .stan(stan.next())
                .networkManagementCode(code)
                .build();
    }

    /** Sets the state (only if currently {@code expected}, when given) and tells the listener. */
    private void transition(LinkState expected, LinkState to) {
        synchronized (stateLock) {
            if (state == LinkState.CLOSED || state == to || (expected != null && state != expected)) {
                return;
            }
            LinkState from = state;
            state = to;
            try {
                listener.onStateChange(from, to);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "listener failed: " + e.getClass().getName());
            }
            stateLock.notifyAll();
        }
    }

    /**
     * Best-effort sign-off (0800/002, waits at most one second) if signed on,
     * then stops reconnecting and closes the link. Pending requests fail with
     * {@link LinkDroppedException}. Blocks briefly: do not call from a callback.
     */
    @Override
    public void close() {
        FrameConnection c = current;
        if (state() == LinkState.CLOSED) {
            return;
        }
        if (state() == LinkState.SIGNED_ON && c != null) {
            IsoMessage signOff = network(NetworkManagementCode.SIGN_OFF);
            try {
                CompletableFuture<IsoMessage> answer = pending.register(signOff, SIGN_OFF_TIMEOUT);
                sendOrFail(c, signOff, answer);
                answer.get(SIGN_OFF_TIMEOUT.toMillis() + 200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException | TimeoutException | RuntimeException e) {
                LOG.fine(() -> "sign-off not confirmed: " + e.getClass().getSimpleName());
            }
        }
        synchronized (stateLock) {
            if (state != LinkState.CLOSED) {
                LinkState from = state;
                state = LinkState.CLOSED;
                try {
                    listener.onStateChange(from, LinkState.CLOSED);
                } catch (RuntimeException e) {
                    LOG.log(Level.WARNING, "listener failed: " + e.getClass().getName());
                }
                stateLock.notifyAll();
            }
        }
        transport.close();
        pending.failAll(new LinkDroppedException("link closed"));
        handlerExecutor.shutdown();
    }
}
