package dev.paysim.link;

import dev.paysim.iso8583.IsoMessage;
import dev.paysim.iso8583.IsoPrinter;
import dev.paysim.iso8583.MessageClass;
import dev.paysim.iso8583.NetworkManagementCode;
import dev.paysim.iso8583.ResponseCode;
import dev.paysim.iso8583.spec.MessageSpec;
import dev.paysim.transport.LengthHeader;
import dev.paysim.transport.netty.ConnectionListener;
import dev.paysim.transport.netty.FrameConnection;
import dev.paysim.transport.FramingException;
import dev.paysim.transport.netty.IdleKind;
import dev.paysim.transport.netty.NettyFrameServer;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * The issuer/switch end of an ISO 8583 link: a {@link NettyFrameServer} that
 * tracks, per connection, whether the peer has signed on, answers the
 * network-management exchanges (sign-on 001, sign-off 002, echo 301), and hands
 * business requests to an {@link IsoRequestHandler}.
 *
 * <pre>
 *   per connection:   CONNECTED --0800/001--> SIGNED_ON --0800/002--> CONNECTED
 *   connection closed: any state --> DISCONNECTED
 * </pre>
 *
 * <p>A business request (anything but class 08xx) on a connection that has not
 * signed on is answered with 39=91 (issuer unavailable). That is this
 * simulator's choice; some hosts drop the message or the connection instead.
 * An answer at least tells the sender at once that the link is not usable.
 *
 * <p>Business requests run on their own virtual threads, not on the connection's
 * serial callback queue, so a slow handler delays neither echo tests nor other
 * requests on the same link, and responses may leave out of order.
 *
 * <p>Responses arriving at the server match nothing (the server sends no
 * requests): they are logged and reported to the listener.
 */
public final class IsoLinkServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(IsoLinkServer.class.getName());

    public static Builder builder(LengthHeader header, MessageSpec spec, IsoRequestHandler handler) {
        return new Builder(header, spec, handler);
    }

    public static final class Builder {
        private final LengthHeader header;
        private final MessageSpec spec;
        private final IsoRequestHandler handler;
        private int port;
        private Duration readerIdle = Duration.ZERO;
        private LinkListener listener = new LinkListener() {
        };
        private boolean answerEchoes = true;

        private Builder(LengthHeader header, MessageSpec spec, IsoRequestHandler handler) {
            this.header = Objects.requireNonNull(header);
            this.spec = Objects.requireNonNull(spec);
            this.handler = Objects.requireNonNull(handler);
        }

        /** Port to bind; 0 (default) picks a free one. */
        public Builder port(int port) {
            this.port = port;
            return this;
        }

        /** Drop links silent for this long (a live client echoes more often than this); zero = off. */
        public Builder readerIdle(Duration readerIdle) {
            this.readerIdle = readerIdle;
            return this;
        }

        /** Per-connection state changes of every client, in order. */
        public Builder listener(LinkListener listener) {
            this.listener = Objects.requireNonNull(listener);
            return this;
        }

        /** Test hook: false simulates a far side that stops answering echo tests. */
        public Builder answerEchoes(boolean answerEchoes) {
            this.answerEchoes = answerEchoes;
            return this;
        }

        public IsoLinkServer build() {
            return new IsoLinkServer(this);
        }
    }

    private static final class Link {
        private LinkState state = LinkState.DISCONNECTED;
    }

    private final MessageSpec spec;
    private final IsoRequestHandler handler;
    private final LinkListener listener;
    private final Map<FrameConnection, Link> links = new ConcurrentHashMap<>();
    private final ExecutorService handlerExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final Object stateLock = new Object();
    private final NettyFrameServer transport;
    private volatile boolean answerEchoes;

    private IsoLinkServer(Builder b) {
        this.spec = b.spec;
        this.handler = b.handler;
        this.listener = b.listener;
        this.answerEchoes = b.answerEchoes;
        this.transport = NettyFrameServer.builder(b.header, this::onFrame)
                .port(b.port)
                .readerIdle(b.readerIdle)
                .listener(new ConnectionListener() {
                    @Override
                    public void onConnected(FrameConnection connection) {
                        Link link = new Link();
                        links.put(connection, link);
                        transition(link, LinkState.CONNECTED);
                    }

                    @Override
                    public void onDisconnected(FrameConnection connection) {
                        Link link = links.remove(connection);
                        if (link != null) {
                            transition(link, LinkState.DISCONNECTED);
                        }
                    }

                    @Override
                    public void onIdle(FrameConnection connection, IdleKind kind) {
                        if (kind == IdleKind.READER) {
                            LOG.fine("link silent for too long; closing it");
                            connection.close();
                        }
                    }

                    @Override
                    public void onFramingError(FrameConnection connection, FramingException error) {
                        LOG.warning(() -> "framing error: " + error.getClass().getSimpleName());
                    }
                })
                .build();
    }

    /** Binds and starts accepting. Returns the bound port. */
    public int start() throws InterruptedException {
        return transport.start();
    }

    public int port() {
        return transport.port();
    }

    /** Test hook: change whether echo tests are answered, on a running server. */
    public void answerEchoes(boolean answerEchoes) {
        this.answerEchoes = answerEchoes;
    }

    /** Connections currently open, signed on or not. */
    public int connectionCount() {
        return links.size();
    }

    /** Connections currently signed on. */
    public int signedOnCount() {
        synchronized (stateLock) {
            return (int) links.values().stream().filter(l -> l.state == LinkState.SIGNED_ON).count();
        }
    }

    private void onFrame(FrameConnection c, byte[] frame) {
        Link link = links.get(c);
        IsoMessage m = LinkSupport.unpack(spec, frame, listener, LOG);
        if (m == null || link == null) {
            return;
        }
        LOG.fine(() -> "recv " + IsoPrinter.format(m));
        if (m.mti().isResponse()) {
            LOG.fine(() -> "unmatched response " + MatchKey.of(m));
            listener.onUnmatchedResponse(m);
        } else if (m.mti().messageClass() == MessageClass.NETWORK_MANAGEMENT) {
            networkManagement(c, link, m);
        } else {
            business(c, link, m);
        }
    }

    private void networkManagement(FrameConnection c, Link link, IsoMessage m) {
        var code = m.networkManagementCode();
        if (code.isEmpty()) {
            LinkSupport.sendQuietly(c, LinkSupport.reply(m, ResponseCode.FORMAT_ERROR), LOG);
            return;
        }
        NetworkManagementCode kind = code.get();
        switch (kind) {
            case SIGN_ON -> {
                transition(link, LinkState.SIGNED_ON); // before the reply, so the peer's next request finds us ready
                LinkSupport.sendQuietly(c, LinkSupport.reply(m, ResponseCode.APPROVED), LOG);
            }
            case SIGN_OFF -> {
                transition(link, LinkState.CONNECTED);
                LinkSupport.sendQuietly(c, LinkSupport.reply(m, ResponseCode.APPROVED), LOG);
            }
            case ECHO_TEST -> {
                if (answerEchoes) {
                    LinkSupport.sendQuietly(c, LinkSupport.reply(m, ResponseCode.APPROVED), LOG);
                } else {
                    LOG.fine("echo test ignored (answerEchoes=false)");
                }
            }
        }
    }

    private void business(FrameConnection c, Link link, IsoMessage m) {
        if (stateOf(link) != LinkState.SIGNED_ON) {
            LOG.info(() -> "business request " + MatchKey.of(m) + " on a link that is not signed on; answering 91");
            LinkSupport.sendQuietly(c, LinkSupport.reply(m, ResponseCode.ISSUER_UNAVAILABLE), LOG);
            return;
        }
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

    private LinkState stateOf(Link link) {
        synchronized (stateLock) {
            return link.state;
        }
    }

    private void transition(Link link, LinkState to) {
        synchronized (stateLock) {
            if (link.state == to) {
                return;
            }
            LinkState from = link.state;
            link.state = to;
            try {
                listener.onStateChange(from, to);
            } catch (RuntimeException e) {
                LOG.log(Level.WARNING, "listener failed: " + e.getClass().getName());
            }
        }
    }

    /** Stops accepting, closes every connection and the handler threads. */
    @Override
    public void close() {
        transport.close();
        handlerExecutor.shutdown();
    }
}
