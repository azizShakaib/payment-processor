package dev.paysim.transport;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The same framing rules, now over real sockets. These are the "done when"
 * checks for Phase 0: split sends, glued sends, many clients at once, and a
 * misbehaving client that must not take the server down.
 */
class EchoServerTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);
    private final FrameCodec codec = new FrameCodec(LengthHeader.BINARY_2);
    private EchoServer server;

    @BeforeEach
    void startServer() throws IOException {
        server = new EchoServer(0, codec).start();
    }

    @AfterEach
    void stopServer() throws IOException {
        server.close();
    }

    @Test
    void echoesManyFramesOverOneConnection() throws IOException {
        try (FrameClient client = new FrameClient("localhost", server.port(), codec, TIMEOUT)) {
            for (int i = 0; i < 100; i++) {
                byte[] payload = ("message-" + i).getBytes(StandardCharsets.US_ASCII);
                assertThat(client.sendAndReceive(payload)).isEqualTo(payload);
            }
        }
        assertThat(server.framesEchoed()).isEqualTo(100);
    }

    @Test
    void reassemblesAFrameSentInDelayedPieces() throws Exception {
        byte[] payload = "split across three TCP segments".getBytes(StandardCharsets.US_ASCII);
        byte[] frame = frame(payload);

        try (Socket socket = rawSocket()) {
            OutputStream out = socket.getOutputStream();
            // Half the header, then the rest of the header plus some payload, then the remainder.
            out.write(frame, 0, 1);
            out.flush();
            Thread.sleep(50);
            out.write(frame, 1, 10);
            out.flush();
            Thread.sleep(50);
            out.write(frame, 11, frame.length - 11);
            out.flush();

            assertThat(codec.read(socket.getInputStream())).isEqualTo(payload);
        }
    }

    @Test
    void separatesFramesGluedIntoOneWrite() throws IOException {
        byte[] a = "first".getBytes(StandardCharsets.US_ASCII);
        byte[] b = "second".getBytes(StandardCharsets.US_ASCII);
        byte[] c = "third".getBytes(StandardCharsets.US_ASCII);
        byte[] glued = concat(frame(a), frame(b), frame(c));

        try (Socket socket = rawSocket()) {
            socket.getOutputStream().write(glued);
            socket.getOutputStream().flush();

            InputStream in = socket.getInputStream();
            assertThat(codec.read(in)).isEqualTo(a);
            assertThat(codec.read(in)).isEqualTo(b);
            assertThat(codec.read(in)).isEqualTo(c);
        }
    }

    @Test
    void servesManyClientsConcurrently() throws Exception {
        int clients = 50;
        int framesEach = 20;
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            List<Callable<Boolean>> tasks = new ArrayList<>();
            for (int c = 0; c < clients; c++) {
                int id = c;
                tasks.add(() -> {
                    try (FrameClient client = new FrameClient("localhost", server.port(), codec, TIMEOUT)) {
                        for (int i = 0; i < framesEach; i++) {
                            byte[] payload = ("client-" + id + "-frame-" + i).getBytes(StandardCharsets.US_ASCII);
                            if (!java.util.Arrays.equals(client.sendAndReceive(payload), payload)) {
                                return false;
                            }
                        }
                        return true;
                    }
                });
            }
            for (Future<Boolean> result : pool.invokeAll(tasks)) {
                assertThat(result.get()).isTrue();
            }
        }
        assertThat(server.framesEchoed()).isEqualTo((long) clients * framesEach);
    }

    @Test
    void survivesAClientThatDiesMidFrame() throws IOException {
        try (Socket broken = rawSocket()) {
            // Claims 100 bytes, sends 3, then hangs up.
            broken.getOutputStream().write(new byte[] {0x00, 0x64, 'a', 'b', 'c'});
            broken.getOutputStream().flush();
        }

        try (FrameClient healthy = new FrameClient("localhost", server.port(), codec, TIMEOUT)) {
            byte[] payload = "still alive".getBytes(StandardCharsets.US_ASCII);
            assertThat(healthy.sendAndReceive(payload)).isEqualTo(payload);
        }
    }

    private Socket rawSocket() throws IOException {
        Socket socket = new Socket("localhost", server.port());
        socket.setSoTimeout((int) TIMEOUT.toMillis());
        socket.setTcpNoDelay(true);
        return socket;
    }

    private byte[] frame(byte[] payload) throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        codec.write(out, payload);
        return out.toByteArray();
    }

    private static byte[] concat(byte[]... parts) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }
}
