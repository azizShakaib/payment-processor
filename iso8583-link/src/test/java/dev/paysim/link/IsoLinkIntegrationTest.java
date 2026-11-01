package dev.paysim.link;

import dev.paysim.iso8583.IsoMessage;
import dev.paysim.iso8583.IsoPacker;
import dev.paysim.iso8583.Mti;
import dev.paysim.iso8583.ResponseCode;
import dev.paysim.transport.LengthHeader;
import dev.paysim.transport.netty.Backoff;
import dev.paysim.transport.netty.FrameConnection;
import dev.paysim.transport.netty.NettyFrameClient;
import dev.paysim.transport.netty.NettyFrameServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

import static dev.paysim.link.TestMessages.SPEC;
import static dev.paysim.link.TestMessages.auth;
import static dev.paysim.link.TestMessages.networkRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Real sockets on localhost, short timeouts, generous waits. */
class IsoLinkIntegrationTest {

    private static final LengthHeader H = LengthHeader.BINARY_2;
    private static final Duration WAIT = Duration.ofSeconds(5);

    private final List<AutoCloseable> toClose = new ArrayList<>();

    @AfterEach
    void closeEverything() throws Exception {
        Collections.reverse(toClose);
        for (AutoCloseable c : toClose) {
            c.close();
        }
    }

    // ---- helpers ----

    /** Records everything a link reports. */
    static final class Events implements LinkListener {
        final List<String> states = new CopyOnWriteArrayList<>();
        final List<IsoMessage> unmatched = new CopyOnWriteArrayList<>();
        final List<String> unparseable = new CopyOnWriteArrayList<>();

        @Override
        public void onStateChange(LinkState from, LinkState to) {
            states.add(from + "->" + to);
        }

        @Override
        public void onUnmatchedResponse(IsoMessage response) {
            unmatched.add(response);
        }

        @Override
        public void onUnparseableFrame(int length, String reason) {
            unparseable.add(length + ": " + reason);
        }

        long count(String transition) {
            return states.stream().filter(transition::equals).count();
        }
    }

    private static final IsoRequestHandler APPROVE = request -> request.responseBuilder()
            .authorizationCode("123456").responseCode(ResponseCode.APPROVED).build();

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + WAIT);
            }
            Thread.sleep(5);
        }
    }

    private IsoLinkServer server(LengthHeader header, IsoRequestHandler handler, Events events, int port,
                                 boolean answerEchoes, Duration readerIdle) throws Exception {
        IsoLinkServer s = IsoLinkServer.builder(header, SPEC, handler)
                .port(port).listener(events).answerEchoes(answerEchoes).readerIdle(readerIdle).build();
        s.start();
        toClose.add(s);
        return s;
    }

    private IsoLinkServer server(IsoRequestHandler handler, Events events) throws Exception {
        return server(H, handler, events, 0, true, Duration.ZERO);
    }

    private IsoLinkClient.Builder clientBuilder(int port, LengthHeader header, Events events) {
        return IsoLinkClient.builder("localhost", port, header, SPEC)
                .listener(events)
                .echoInterval(Duration.ZERO)
                .backoff(new Backoff(Duration.ofMillis(10), Duration.ofMillis(100)));
    }

    private IsoLinkClient start(IsoLinkClient.Builder builder) throws Exception {
        IsoLinkClient c = builder.build().start();
        toClose.add(c);
        return c;
    }

    private IsoLinkClient signedOnClient(int port, LengthHeader header, Events events) throws Exception {
        IsoLinkClient c = start(clientBuilder(port, header, events));
        assertThat(c.awaitState(LinkState.SIGNED_ON, WAIT)).as("signed on").isTrue();
        return c;
    }

    /** A bare frame server standing in for a far side that we script by hand. */
    private NettyFrameServer rawServer(BiConsumer<FrameConnection, IsoMessage> onMessage) throws Exception {
        NettyFrameServer s = NettyFrameServer.builder(H, (c, frame) -> onMessage.accept(c, IsoPacker.unpack(SPEC, frame)))
                .build();
        s.start();
        toClose.add(s);
        return s;
    }

    private static void sendTo(FrameConnection c, IsoMessage m) {
        c.send(IsoPacker.pack(m));
    }

    private static IsoMessage answer(IsoMessage request, ResponseCode code) {
        return request.responseBuilder().responseCode(code).build();
    }

    /** A bare client that records every message it receives. */
    private record RawClient(NettyFrameClient client, LinkedBlockingQueue<IsoMessage> received) {
        IsoMessage next() throws InterruptedException {
            IsoMessage m = received.poll(WAIT.toMillis(), TimeUnit.MILLISECONDS);
            assertThat(m).as("a frame within " + WAIT).isNotNull();
            return m;
        }

        void send(IsoMessage m) throws Exception {
            client.send(IsoPacker.pack(m)).get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        }
    }

    private RawClient rawClient(int port) throws Exception {
        var received = new LinkedBlockingQueue<IsoMessage>();
        NettyFrameClient c = NettyFrameClient.builder("localhost", port, H,
                (conn, frame) -> received.add(IsoPacker.unpack(SPEC, frame))).build().start();
        toClose.add(c);
        c.connected().get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
        return new RawClient(c, received);
    }

    // ---- sign-on ----

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void clientSignsOnAutomatically(LengthHeader header) throws Exception {
        Events serverEvents = new Events();
        Events clientEvents = new Events();
        IsoLinkServer server = server(header, APPROVE, serverEvents, 0, true, Duration.ZERO);
        IsoLinkClient client = signedOnClient(server.port(), header, clientEvents);

        assertThat(client.state()).isEqualTo(LinkState.SIGNED_ON);
        assertThat(clientEvents.states).containsExactly("DISCONNECTED->CONNECTED", "CONNECTED->SIGNED_ON");
        await(() -> server.signedOnCount() == 1);
        assertThat(serverEvents.states).containsExactly("DISCONNECTED->CONNECTED", "CONNECTED->SIGNED_ON");
        assertThat(client.inFlight()).isZero();
    }

    @Test
    void refusedSignOnClosesTheLinkAndTriesAgain() throws Exception {
        AtomicInteger signOns = new AtomicInteger();
        NettyFrameServer raw = rawServer((c, m) -> {
            signOns.incrementAndGet();
            sendTo(c, answer(m, ResponseCode.DO_NOT_HONOR));
        });
        Events events = new Events();
        IsoLinkClient client = start(clientBuilder(raw.port(), H, events));

        await(() -> signOns.get() >= 2);
        assertThat(client.state()).isNotEqualTo(LinkState.SIGNED_ON);
        assertThat(events.count("CONNECTED->SIGNED_ON")).isZero();
    }

    @Test
    void signOnTimeoutClosesTheLink() throws Exception {
        AtomicInteger signOns = new AtomicInteger();
        NettyFrameServer raw = rawServer((c, m) -> signOns.incrementAndGet()); // never answers
        Events events = new Events();
        start(clientBuilder(raw.port(), H, events).signOnTimeout(Duration.ofMillis(100)));

        await(() -> signOns.get() >= 2);
        assertThat(events.count("CONNECTED->SIGNED_ON")).isZero();
    }

    // ---- echo / keep-alive ----

    @Test
    void echoIsSentOnIdleAndAnswered() throws Exception {
        List<String> codes = new CopyOnWriteArrayList<>();
        NettyFrameServer raw = rawServer((c, m) -> {
            codes.add(m.text(70).orElse("?"));
            sendTo(c, answer(m, ResponseCode.APPROVED));
        });
        Events events = new Events();
        IsoLinkClient client = start(clientBuilder(raw.port(), H, events).echoInterval(Duration.ofMillis(150)));

        await(() -> codes.stream().filter("301"::equals).count() >= 2);
        assertThat(codes.get(0)).isEqualTo("001");
        assertThat(client.state()).isEqualTo(LinkState.SIGNED_ON);
        assertThat(events.count("CONNECTED->SIGNED_ON")).isEqualTo(1); // answered echoes keep the link up
    }

    @Test
    void answeredEchoesKeepALinkAliveAgainstAReaderIdleServer() throws Exception {
        Events serverEvents = new Events();
        IsoLinkServer server = server(H, APPROVE, serverEvents, 0, true, Duration.ofMillis(400));
        Events clientEvents = new Events();
        IsoLinkClient client = start(clientBuilder(server.port(), H, clientEvents).echoInterval(Duration.ofMillis(100)));
        assertThat(client.awaitState(LinkState.SIGNED_ON, WAIT)).isTrue();

        Thread.sleep(1200); // measuring: three times the server's idle limit
        assertThat(client.state()).isEqualTo(LinkState.SIGNED_ON);
        assertThat(server.signedOnCount()).isEqualTo(1);
        assertThat(clientEvents.count("CONNECTED->SIGNED_ON")).isEqualTo(1);
    }

    @Test
    void aReaderIdleServerDropsASilentClient() throws Exception {
        Events serverEvents = new Events();
        IsoLinkServer server = server(H, APPROVE, serverEvents, 0, true, Duration.ofMillis(200));
        Events clientEvents = new Events();
        signedOnClient(server.port(), H, clientEvents); // client has echo off: it goes silent

        await(() -> serverEvents.count("SIGNED_ON->DISCONNECTED") == 1);
    }

    @Test
    void unansweredEchoesDropTheLinkAndItSignsOnAgain() throws Exception {
        Events serverEvents = new Events();
        IsoLinkServer server = server(H, APPROVE, serverEvents, 0, true, Duration.ZERO);
        Events clientEvents = new Events();
        IsoLinkClient client = start(clientBuilder(server.port(), H, clientEvents)
                .echoInterval(Duration.ofMillis(100)).echoTimeout(Duration.ofMillis(100)).maxEchoFailures(2));
        assertThat(client.awaitState(LinkState.SIGNED_ON, WAIT)).isTrue();

        server.answerEchoes(false);
        await(() -> clientEvents.count("SIGNED_ON->DISCONNECTED") == 1);
        server.answerEchoes(true);
        await(() -> clientEvents.count("CONNECTED->SIGNED_ON") == 2);
        assertThat(client.state()).isEqualTo(LinkState.SIGNED_ON);
    }

    @Test
    void theClientAnswersAnEchoSentByTheFarSide() throws Exception {
        LinkedBlockingQueue<IsoMessage> fromClient = new LinkedBlockingQueue<>();
        NettyFrameServer raw = rawServer((c, m) -> {
            fromClient.add(m);
            if (m.mti().equals(Mti.NETWORK_REQUEST) && "001".equals(m.text(70).orElse(""))) {
                sendTo(c, answer(m, ResponseCode.APPROVED));                    // let the client sign on
                sendTo(c, networkRequest(900_001, "301"));                      // then test it ourselves
            }
        });
        start(clientBuilder(raw.port(), H, new Events()));

        IsoMessage signOn = fromClient.poll(5, TimeUnit.SECONDS);
        assertThat(signOn).isNotNull();
        IsoMessage echoAnswer = fromClient.poll(5, TimeUnit.SECONDS);
        assertThat(echoAnswer).isNotNull();
        assertThat(echoAnswer.mti()).isEqualTo(Mti.NETWORK_RESPONSE);
        assertThat(echoAnswer.stan()).hasValue(900_001);
        assertThat(echoAnswer.responseCode()).contains(ResponseCode.APPROVED);
    }

    // ---- business traffic ----

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void authorizationRoundTrip(LengthHeader header) throws Exception {
        Events events = new Events();
        IsoLinkServer server = server(header, APPROVE, new Events(), 0, true, Duration.ZERO);
        IsoLinkClient client = signedOnClient(server.port(), header, events);

        IsoMessage response = client.request(auth(1234, 2599)).get(5, TimeUnit.SECONDS);

        assertThat(response.mti()).isEqualTo(Mti.AUTH_RESPONSE);
        assertThat(response.stan()).hasValue(1234);
        assertThat(response.amount().orElseThrow().minorUnits()).isEqualTo(2599);
        assertThat(response.responseCode()).contains(ResponseCode.APPROVED);
        assertThat(response.authorizationCode()).contains("123456");
        assertThat(client.inFlight()).isZero();
    }

    @Test
    void hundredConcurrentRequestsEachGetTheirOwnResponse() throws Exception {
        List<Integer> completionOrder = new CopyOnWriteArrayList<>();
        IsoRequestHandler slowRandom = request -> {
            Thread.sleep(ThreadLocalRandom.current().nextInt(0, 51));
            completionOrder.add(request.stan().orElseThrow());
            return APPROVE.handle(request);
        };
        IsoLinkServer server = server(slowRandom, new Events());
        IsoLinkClient client = signedOnClient(server.port(), H, new Events());

        int n = 100;
        List<CompletableFuture<IsoMessage>> futures = new ArrayList<>();
        for (int i = 1; i <= n; i++) {
            futures.add(client.request(auth(i, 1000 + i)));
        }
        for (int i = 1; i <= n; i++) {
            IsoMessage response = futures.get(i - 1).get(10, TimeUnit.SECONDS);
            assertThat(response.stan()).hasValue(i);
            assertThat(response.amount().orElseThrow().minorUnits()).isEqualTo(1000 + i);
        }
        assertThat(client.inFlight()).isZero();
        // the handlers finished in a different order than the requests were sent: that is what matching is for
        List<Integer> sorted = new ArrayList<>(completionOrder);
        Collections.sort(sorted);
        assertThat(completionOrder).hasSize(n).isNotEqualTo(sorted);
    }

    @Test
    void aSlowRequestTimesOutAndItsLateResponseIsReportedUnmatched() throws Exception {
        IsoRequestHandler slow = request -> {
            Thread.sleep(500);
            return APPROVE.handle(request);
        };
        IsoLinkServer server = server(slow, new Events());
        Events events = new Events();
        IsoLinkClient client = start(clientBuilder(server.port(), H, events).requestTimeout(Duration.ofMillis(150)));
        assertThat(client.awaitState(LinkState.SIGNED_ON, WAIT)).isTrue();

        CompletableFuture<IsoMessage> future = client.request(auth(77, 100));
        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(TimeoutException.class);
        assertThat(client.inFlight()).isZero();

        await(() -> !events.unmatched.isEmpty());
        assertThat(events.unmatched.get(0).stan()).hasValue(77);
        assertThat(client.state()).isEqualTo(LinkState.SIGNED_ON); // a timeout does not drop the link
    }

    @Test
    void requestBeforeSignOnFailsFast() throws Exception {
        NettyFrameServer raw = rawServer((c, m) -> { }); // never answers the sign-on
        IsoLinkClient client = start(clientBuilder(raw.port(), H, new Events()).signOnTimeout(Duration.ofSeconds(30)));
        assertThat(client.awaitState(LinkState.CONNECTED, WAIT)).isTrue();

        CompletableFuture<IsoMessage> future = client.request(auth(1, 100));

        assertThat(future).isCompletedExceptionally();
        assertThatThrownBy(future::get).hasCauseInstanceOf(LinkNotReadyException.class);
    }

    @Test
    void requestAfterTheServerWentAwayFailsFast() throws Exception {
        IsoLinkServer server = server(APPROVE, new Events());
        Events events = new Events();
        IsoLinkClient client = signedOnClient(server.port(), H, events);
        server.close();
        assertThat(client.awaitState(LinkState.DISCONNECTED, WAIT)).isTrue();

        CompletableFuture<IsoMessage> future = client.request(auth(1, 100));

        assertThatThrownBy(future::get).hasCauseInstanceOf(LinkNotReadyException.class);
    }

    @Test
    void duplicateStanInFlightFailsTheSecondRequest() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        IsoLinkServer server = server(request -> {
            release.await();
            return APPROVE.handle(request);
        }, new Events());
        IsoLinkClient client = signedOnClient(server.port(), H, new Events());

        IsoMessage request = auth(5, 100);
        CompletableFuture<IsoMessage> first = client.request(request);
        CompletableFuture<IsoMessage> second = client.request(request);

        assertThatThrownBy(second::get).hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate STAN in flight");
        release.countDown();
        assertThat(first.get(5, TimeUnit.SECONDS).responseCode()).contains(ResponseCode.APPROVED);
    }

    @Test
    void serverRestartOnTheSamePortFailsPendingRequestsAndSignsOnAgain() throws Exception {
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch neverReleased = new CountDownLatch(1);
        IsoLinkServer first = server(request -> {
            handlerEntered.countDown();
            neverReleased.await();
            return null;
        }, new Events());
        int port = first.port();
        Events events = new Events();
        IsoLinkClient client = signedOnClient(port, H, events);

        CompletableFuture<IsoMessage> pending = client.request(auth(9, 100));
        assertThat(handlerEntered.await(5, TimeUnit.SECONDS)).isTrue();
        first.close();

        assertThatThrownBy(() -> pending.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(LinkDroppedException.class);
        assertThat(client.inFlight()).isZero();
        await(() -> events.count("SIGNED_ON->DISCONNECTED") == 1);

        server(H, APPROVE, new Events(), port, true, Duration.ZERO);
        await(() -> events.count("CONNECTED->SIGNED_ON") == 2);
        assertThat(client.request(auth(10, 100)).get(5, TimeUnit.SECONDS).responseCode())
                .contains(ResponseCode.APPROVED);
        neverReleased.countDown();
    }

    // ---- server-side rules ----

    @Test
    void businessRequestOnAConnectionThatDidNotSignOnGets91() throws Exception {
        AtomicInteger handled = new AtomicInteger();
        IsoLinkServer server = server(request -> {
            handled.incrementAndGet();
            return APPROVE.handle(request);
        }, new Events());
        RawClient raw = rawClient(server.port());

        raw.send(auth(3, 500));

        IsoMessage response = raw.next();
        assertThat(response.mti()).isEqualTo(Mti.AUTH_RESPONSE);
        assertThat(response.responseCode()).contains(ResponseCode.ISSUER_UNAVAILABLE);
        assertThat(response.stan()).hasValue(3);
        assertThat(handled.get()).isZero();
    }

    @Test
    void afterSignOffBusinessRequestsAreRefusedAgain() throws Exception {
        IsoLinkServer server = server(APPROVE, new Events());
        RawClient raw = rawClient(server.port());
        raw.send(networkRequest(1, "001"));
        assertThat(raw.next().responseCode()).contains(ResponseCode.APPROVED);
        raw.send(auth(2, 100));
        assertThat(raw.next().responseCode()).contains(ResponseCode.APPROVED);

        raw.send(networkRequest(3, "002"));
        assertThat(raw.next().responseCode()).contains(ResponseCode.APPROVED);
        raw.send(auth(4, 100));
        assertThat(raw.next().responseCode()).contains(ResponseCode.ISSUER_UNAVAILABLE);
    }

    @Test
    void aHandlerThatThrowsGives96() throws Exception {
        IsoLinkServer server = server(request -> {
            throw new IllegalStateException("boom, with card data 4111111111111111");
        }, new Events());
        IsoLinkClient client = signedOnClient(server.port(), H, new Events());

        IsoMessage response = client.request(auth(8, 100)).get(5, TimeUnit.SECONDS);

        assertThat(response.responseCode()).contains(ResponseCode.SYSTEM_MALFUNCTION);
        assertThat(client.state()).isEqualTo(LinkState.SIGNED_ON);
    }

    @Test
    void aHandlerReturningNullSendsNothing() throws Exception {
        IsoLinkServer server = server(request -> null, new Events());
        IsoLinkClient client = start(clientBuilder(server.port(), H, new Events()).requestTimeout(Duration.ofMillis(200)));
        assertThat(client.awaitState(LinkState.SIGNED_ON, WAIT)).isTrue();

        CompletableFuture<IsoMessage> future = client.request(auth(8, 100));

        assertThatThrownBy(() -> future.get(5, TimeUnit.SECONDS)).hasCauseInstanceOf(TimeoutException.class);
    }

    @Test
    void anUnparseableFrameIsReportedAndTheLinkStaysOpen() throws Exception {
        Events serverEvents = new Events();
        IsoLinkServer server = server(H, APPROVE, serverEvents, 0, true, Duration.ZERO);
        RawClient raw = rawClient(server.port());

        raw.client().send("this is not an ISO 8583 message".getBytes()).get(5, TimeUnit.SECONDS);
        await(() -> serverEvents.unparseable.size() == 1);
        assertThat(serverEvents.unparseable.get(0)).startsWith("31: ");

        raw.send(networkRequest(1, "001")); // the link still works
        assertThat(raw.next().responseCode()).contains(ResponseCode.APPROVED);
    }

    @Test
    void aResponseNobodyAskedForIsReportedUnmatched() throws Exception {
        Events serverEvents = new Events();
        IsoLinkServer server = server(H, APPROVE, serverEvents, 0, true, Duration.ZERO);
        RawClient raw = rawClient(server.port());

        raw.send(auth(1, 100).responseBuilder().responseCode(ResponseCode.APPROVED).build());

        await(() -> serverEvents.unmatched.size() == 1);
    }

    // ---- closing ----

    @Test
    void closeSignsOffAndTheServerSeesTheLinkLeave() throws Exception {
        Events serverEvents = new Events();
        IsoLinkServer server = server(H, APPROVE, serverEvents, 0, true, Duration.ZERO);
        Events clientEvents = new Events();
        IsoLinkClient client = signedOnClient(server.port(), H, clientEvents);

        client.close();

        assertThat(client.state()).isEqualTo(LinkState.CLOSED);
        assertThat(clientEvents.states).endsWith("SIGNED_ON->CLOSED");
        await(() -> serverEvents.count("CONNECTED->DISCONNECTED") == 1);
        assertThat(serverEvents.states).containsExactly("DISCONNECTED->CONNECTED", "CONNECTED->SIGNED_ON",
                "SIGNED_ON->CONNECTED", "CONNECTED->DISCONNECTED"); // sign-off first, then the socket closed
        assertThat(server.connectionCount()).isZero();
        assertThat(client.request(auth(1, 1)).isCompletedExceptionally()).isTrue();
    }
}
