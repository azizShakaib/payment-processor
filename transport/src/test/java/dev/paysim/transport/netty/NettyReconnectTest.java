package dev.paysim.transport.netty;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.paysim.transport.LengthHeader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class NettyReconnectTest {

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

    private static int freePort() throws Exception {
        try (ServerSocket s = new ServerSocket(0)) {
            return s.getLocalPort();
        }
    }

    private static Backoff fastBackoff() {
        return new Backoff(Duration.ofMillis(20), Duration.ofMillis(200));
    }

    private static NettyFrameServer echoServer(LengthHeader header, int port) throws Exception {
        NettyFrameServer s = NettyFrameServer.builder(header, (c, f) -> c.send(f)).port(port).build();
        s.start();
        return s;
    }

    private static void awaitTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within 5 s");
            }
            Thread.sleep(5);
        }
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void clientStartedBeforeServerKeepsRetryingThenConnects(LengthHeader header) throws Exception {
        int port = freePort();
        client = NettyFrameClient.builder("localhost", port, header, (c, f) -> { })
                .backoff(fastBackoff()).build();
        client.start();

        awaitTrue(() -> client.connectAttempts() >= 3);
        assertThat(client.connected()).isNotDone();
        assertThat(client.state()).isIn(NettyFrameClient.State.BACKING_OFF, NettyFrameClient.State.CONNECTING);

        server = echoServer(header, port);
        client.connected().get(5, TimeUnit.SECONDS);
        assertThat(client.state()).isEqualTo(NettyFrameClient.State.CONNECTED);
    }

    @ParameterizedTest
    @EnumSource(LengthHeader.class)
    void clientReconnectsWhenServerRestartsOnSamePort(LengthHeader header) throws Exception {
        BlockingQueue<String> events = new LinkedBlockingQueue<>();
        BlockingQueue<byte[]> replies = new LinkedBlockingQueue<>();
        server = echoServer(header, 0);
        int port = server.port();
        client = NettyFrameClient.builder("localhost", port, header, (c, f) -> replies.add(f))
                .backoff(fastBackoff())
                .listener(new ConnectionListener() {
                    @Override
                    public void onConnected(FrameConnection connection) {
                        events.add("connected");
                    }

                    @Override
                    public void onDisconnected(FrameConnection connection) {
                        events.add("disconnected");
                    }
                }).build();
        client.start();
        assertThat(events.poll(5, TimeUnit.SECONDS)).isEqualTo("connected");

        server.close();
        assertThat(events.poll(5, TimeUnit.SECONDS)).isEqualTo("disconnected");

        server = echoServer(header, port);
        assertThat(events.poll(5, TimeUnit.SECONDS)).isEqualTo("connected");

        byte[] payload = "after restart".getBytes(StandardCharsets.US_ASCII);
        awaitTrue(() -> client.state() == NettyFrameClient.State.CONNECTED);
        client.send(payload).get(5, TimeUnit.SECONDS);
        assertThat(replies.poll(5, TimeUnit.SECONDS)).isEqualTo(payload);
    }

    @Test
    void sendWhileDisconnectedFailsFast() throws Exception {
        client = NettyFrameClient.builder("localhost", freePort(), LengthHeader.BINARY_2, (c, f) -> { })
                .backoff(fastBackoff()).build();

        // Before start, and while retrying: both fail at once, nothing queued.
        assertThatThrownBy(() -> client.send(new byte[1]).get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(NotConnectedException.class);
        client.start();
        awaitTrue(() -> client.connectAttempts() >= 2);
        assertThatThrownBy(() -> client.send(new byte[1]).get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(NotConnectedException.class);
    }

    @Test
    void sendAfterServerDropsFailsFastWithoutQueueing() throws Exception {
        server = echoServer(LengthHeader.BINARY_2, 0);
        client = NettyFrameClient.builder("localhost", server.port(), LengthHeader.BINARY_2, (c, f) -> { })
                .backoff(new Backoff(Duration.ofSeconds(5), Duration.ofSeconds(5))).build();
        client.start();
        client.connected().get(5, TimeUnit.SECONDS);
        server.close();
        awaitTrue(() -> client.state() == NettyFrameClient.State.BACKING_OFF);

        assertThatThrownBy(() -> client.send(new byte[1]).get(1, TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class).hasCauseInstanceOf(NotConnectedException.class);
    }

    @Test
    void closeStopsRetrying() throws Exception {
        client = NettyFrameClient.builder("localhost", freePort(), LengthHeader.BINARY_2, (c, f) -> { })
                .backoff(fastBackoff()).build();
        client.start();
        awaitTrue(() -> client.connectAttempts() >= 2);

        client.close();
        int attemptsAtClose = client.connectAttempts();
        Thread.sleep(500); // longer than the max backoff (200 ms): measuring that nothing happens

        assertThat(client.connectAttempts()).isEqualTo(attemptsAtClose);
        assertThat(client.state()).isEqualTo(NettyFrameClient.State.CLOSED);
    }

    @Test
    void closeIsIdempotentAndWorksBeforeStart() {
        client = NettyFrameClient.builder("localhost", 1, LengthHeader.BINARY_2, (c, f) -> { }).build();
        client.close();
        client.close();
        assertThat(client.state()).isEqualTo(NettyFrameClient.State.CLOSED);
    }

    @Test
    void backoffResetsAfterSuccessfulConnect() throws Exception {
        // A backoff with a long initial delay: if it were not reset on connect,
        // the reconnect after the restart would wait up to many seconds.
        server = echoServer(LengthHeader.BINARY_2, 0);
        int port = server.port();
        client = NettyFrameClient.builder("localhost", port, LengthHeader.BINARY_2, (c, f) -> { })
                .backoff(new Backoff(Duration.ofMillis(20), Duration.ofSeconds(60))).build();
        client.start();
        client.connected().get(5, TimeUnit.SECONDS);
        for (int round = 0; round < 3; round++) {
            server.close();
            awaitTrue(() -> client.state() != NettyFrameClient.State.CONNECTED);
            server = echoServer(LengthHeader.BINARY_2, port);
            awaitTrue(() -> client.state() == NettyFrameClient.State.CONNECTED);
        }
    }
}
