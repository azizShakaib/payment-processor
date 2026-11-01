package dev.paysim.transport.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.paysim.transport.FrameClient;
import dev.paysim.transport.FrameCodec;
import dev.paysim.transport.FramingException;
import dev.paysim.transport.LengthHeader;
import java.io.IOException;
import java.io.InputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NettyServerClientTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private static final byte[] ECHO = ("0800" + "8220000000000000" + "0400000000000000" + "1015042100"
            + "000001" + "301").getBytes(StandardCharsets.US_ASCII);

    private NettyFrameServer server;
    private NettyFrameClient client;

    @AfterEach
    void tearDown() {
        if (client != null) {
            client.close();
        }
        if (server != null) {
            server.close();
        }
    }

    private static FrameHandler echo() {
        return (conn, frame) -> conn.send(frame);
    }

    private static FrameClient blockingClient(int port, LengthHeader header) throws IOException {
        // The Phase 0 blocking client is a handy independent check of the wire format.
        return new FrameClient("localhost", port, new FrameCodec(header), TIMEOUT);
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void echoRoundTripWithNettyClient(LengthHeader header) throws Exception {
        server = NettyFrameServer.builder(header, echo()).build();
        server.start();
        BlockingQueue<byte[]> replies = new LinkedBlockingQueue<>();
        client = NettyFrameClient.builder("localhost", server.port(), header, (c, f) -> replies.add(f)).build();
        client.start();
        client.connected().get(5, TimeUnit.SECONDS);

        client.send(ECHO).get(5, TimeUnit.SECONDS);

        assertThat(replies.poll(5, TimeUnit.SECONDS)).isEqualTo(ECHO);
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void echoRoundTripWithBlockingClient(LengthHeader header) throws Exception {
        server = NettyFrameServer.builder(header, echo()).build();
        server.start();
        try (FrameClient c = blockingClient(server.port(), header)) {
            assertThat(c.sendAndReceive(ECHO)).isEqualTo(ECHO);
            assertThat(c.sendAndReceive(new byte[0])).isEmpty();
        }
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void fiftyConcurrentClientsGetTheirOwnPayloadsBack(LengthHeader header) throws Exception {
        server = NettyFrameServer.builder(header, echo()).build();
        server.start();
        int clients = 50;
        int perClient = 20;
        // Connect one after another: a burst of 50 simultaneous connects to a loopback listener
        // is occasionally reset on this Windows machine (the Phase 0 EchoServer suffers far more).
        // The traffic afterwards is fully concurrent, which is what this test is about.
        List<FrameClient> connections = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < clients; i++) {
                connections.add(blockingClient(server.port(), header));
            }
            List<CompletableFuture<Void>> runs = new ArrayList<>();
            for (int i = 0; i < clients; i++) {
                int id = i;
                FrameClient c = connections.get(i);
                runs.add(CompletableFuture.runAsync(() -> {
                    try {
                        for (int n = 0; n < perClient; n++) {
                            byte[] payload = ("client-" + id + "-msg-" + n).getBytes(StandardCharsets.US_ASCII);
                            assertThat(c.sendAndReceive(payload)).isEqualTo(payload);
                        }
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                }, pool));
            }
            CompletableFuture.allOf(runs.toArray(CompletableFuture[]::new)).get(20, TimeUnit.SECONDS);
        } finally {
            for (FrameClient c : connections) {
                c.close();
            }
        }
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void framesOfOneConnectionAreHandledInOrder(LengthHeader header) throws Exception {
        int count = 200;
        List<Integer> seen = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch all = new CountDownLatch(count);
        server = NettyFrameServer.builder(header, (conn, frame) -> {
            seen.add(ByteBuffer.wrap(frame).getInt());
            all.countDown();
        }).build();
        server.start();
        client = NettyFrameClient.builder("localhost", server.port(), header, (c, f) -> { }).build();
        client.start();
        client.connected().get(5, TimeUnit.SECONDS);

        for (int i = 0; i < count; i++) {
            client.send(ByteBuffer.allocate(4).putInt(i).array());
        }

        assertThat(all.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(seen).hasSize(count).isSorted();
        assertThat(seen.getFirst()).isZero();
        assertThat(seen.getLast()).isEqualTo(count - 1);
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void slowHandlerOnOneConnectionDoesNotDelayAnother(LengthHeader header) throws Exception {
        server = NettyFrameServer.builder(header, (conn, frame) -> {
            if (frame.length > 0 && frame[0] == 'A') {
                try {
                    Thread.sleep(500); // simulates a slow database call inside the handler
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            conn.send(frame);
        }).build();
        server.start();
        byte[] slow = "A-slow".getBytes(StandardCharsets.US_ASCII);
        byte[] fast = "B-fast".getBytes(StandardCharsets.US_ASCII);

        try (FrameClient a = blockingClient(server.port(), header);
                FrameClient b = blockingClient(server.port(), header)) {
            assertThat(b.sendAndReceive(fast)).isEqualTo(fast); // warm up B's connection
            long start = System.nanoTime();
            a.send(slow);
            // Give A's frame a head start so its handler is certainly blocking.
            Thread.sleep(50);
            long bStart = System.nanoTime();
            assertThat(b.sendAndReceive(fast)).isEqualTo(fast);
            long bMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - bStart);
            assertThat(a.receive()).isEqualTo(slow);
            long aMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(bMillis).as("B's echo while A's handler sleeps").isLessThan(300);
            assertThat(aMillis).as("A really was slow").isGreaterThanOrEqualTo(450);
        }
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void silentClientTriggersServerReaderIdle(LengthHeader header) throws Exception {
        BlockingQueue<IdleKind> idles = new LinkedBlockingQueue<>();
        server = NettyFrameServer.builder(header, echo())
                .readerIdle(Duration.ofMillis(200))
                .listener(new ConnectionListener() {
                    @Override
                    public void onIdle(FrameConnection connection, IdleKind kind) {
                        idles.add(kind);
                    }
                }).build();
        server.start();

        try (FrameClient silent = blockingClient(server.port(), header)) {
            assertThat(idles.poll(5, TimeUnit.SECONDS)).isEqualTo(IdleKind.READER);
            assertThat(silent).isNotNull(); // client stayed connected and silent throughout
        }
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void badHeaderNotifiesListenerClosesThatConnectionAndServerKeepsServing(LengthHeader header) throws Exception {
        // BINARY_2 cannot be "bad" (any two bytes are a length), so use an oversize frame there.
        int max = header == LengthHeader.ASCII_4 ? 9_999 : 100;
        BlockingQueue<FramingException> errors = new LinkedBlockingQueue<>();
        server = NettyFrameServer.builder(header, echo())
                .maxFrameLength(max)
                .listener(new ConnectionListener() {
                    @Override
                    public void onFramingError(FrameConnection connection, FramingException error) {
                        errors.add(error);
                    }
                }).build();
        server.start();
        byte[] bad = header == LengthHeader.ASCII_4
                ? "ab12garbage".getBytes(StandardCharsets.US_ASCII)
                : new byte[] {(byte) 0x01, (byte) 0x00}; // length 256 > 100

        try (Socket raw = new Socket("localhost", server.port());
                FrameClient good = blockingClient(server.port(), header)) {
            raw.setSoTimeout((int) TIMEOUT.toMillis());
            raw.getOutputStream().write(bad);
            raw.getOutputStream().flush();

            assertThat(errors.poll(5, TimeUnit.SECONDS)).isInstanceOf(FramingException.class);
            InputStream in = raw.getInputStream();
            assertThat(in.read()).as("server closed the bad connection").isEqualTo(-1);
            assertThat(good.sendAndReceive(ECHO)).isEqualTo(ECHO);
        }
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void connectionCloseMidFrameIsReportedToListener(LengthHeader header) throws Exception {
        BlockingQueue<FramingException> errors = new LinkedBlockingQueue<>();
        server = NettyFrameServer.builder(header, echo())
                .listener(new ConnectionListener() {
                    @Override
                    public void onFramingError(FrameConnection connection, FramingException error) {
                        errors.add(error);
                    }
                }).build();
        server.start();

        try (Socket raw = new Socket("localhost", server.port())) {
            raw.getOutputStream().write(header.encode(50));
            raw.getOutputStream().write(new byte[10]);
            raw.getOutputStream().flush();
        }

        assertThat(errors.poll(5, TimeUnit.SECONDS)).hasMessageContaining("mid-frame");
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void sendingOversizePayloadFailsTheFutureWithFramingException(LengthHeader header) throws Exception {
        server = NettyFrameServer.builder(header, echo()).build();
        server.start();
        client = NettyFrameClient.builder("localhost", server.port(), header, (c, f) -> { })
                .maxFrameLength(10).build();
        client.start();
        client.connected().get(5, TimeUnit.SECONDS);

        assertThatThrownBy(() -> client.send(new byte[11]).get(5, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(FramingException.class);
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void listenerSeesConnectedFramesThenDisconnectedInOrder(LengthHeader header) throws Exception {
        List<String> events = Collections.synchronizedList(new ArrayList<>());
        CountDownLatch disconnected = new CountDownLatch(1);
        server = NettyFrameServer.builder(header, (c, f) -> events.add("frame"))
                .listener(new ConnectionListener() {
                    @Override
                    public void onConnected(FrameConnection connection) {
                        events.add("connected");
                    }

                    @Override
                    public void onDisconnected(FrameConnection connection) {
                        events.add("disconnected");
                        disconnected.countDown();
                    }
                }).build();
        server.start();
        try (FrameClient c = blockingClient(server.port(), header)) {
            c.send(ECHO);
            c.send(ECHO);
        }
        assertThat(disconnected.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(events).containsExactly("connected", "frame", "frame", "disconnected");
    }
}
