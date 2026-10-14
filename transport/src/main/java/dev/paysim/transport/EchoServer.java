package dev.paysim.transport;

import dev.paysim.common.Hex;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;

/**
 * Blocking TCP server that sends every frame straight back.
 *
 * <p>One virtual thread per connection: the code reads like simple blocking
 * I/O, but thousands of idle connections cost almost nothing. Phase 1
 * replaces this with Netty, and comparing the two is part of the lesson.
 *
 * <p>Payment links are long-lived: a client connects once and sends many
 * messages over the same socket, so the per-connection loop runs until the
 * peer hangs up.
 */
public final class EchoServer implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(EchoServer.class.getName());

    private final FrameCodec codec;
    private final ServerSocket serverSocket;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicLong framesEchoed = new AtomicLong();
    private volatile boolean running;

    /** Binds immediately. Port 0 picks a free port; read it back with {@link #port()}. */
    public EchoServer(int port, FrameCodec codec) throws IOException {
        this.codec = codec;
        this.serverSocket = new ServerSocket();
        this.serverSocket.setReuseAddress(true);
        this.serverSocket.bind(new InetSocketAddress(port));
    }

    public int port() {
        return serverSocket.getLocalPort();
    }

    public long framesEchoed() {
        return framesEchoed.get();
    }

    /** Starts accepting on a background thread and returns at once. */
    public EchoServer start() {
        running = true;
        Thread.ofPlatform().name("echo-acceptor").daemon().start(this::acceptLoop);
        LOG.info(() -> "Echo server listening on port " + port() + " with " + codec.header() + " headers");
        return this;
    }

    private void acceptLoop() {
        while (running) {
            try {
                Socket socket = serverSocket.accept();
                workers.submit(() -> handle(socket));
            } catch (SocketException e) {
                if (running) {
                    LOG.warning("Accept failed: " + e.getMessage());
                }
            } catch (IOException e) {
                LOG.warning("Accept failed: " + e.getMessage());
            }
        }
    }

    private void handle(Socket socket) {
        String peer = socket.getRemoteSocketAddress().toString();
        LOG.fine(() -> "Connected: " + peer);
        try (socket) {
            // Payment messages are small; disable Nagle so replies are not held back.
            socket.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(socket.getInputStream());
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            byte[] payload;
            while ((payload = codec.read(in)) != null) {
                byte[] frame = payload;
                LOG.finer(() -> "From " + peer + ":\n" + Hex.dump(frame));
                codec.write(out, payload);
                framesEchoed.incrementAndGet();
            }
            LOG.fine(() -> "Disconnected cleanly: " + peer);
        } catch (FramingException e) {
            // The stream is out of sync; we cannot find the next message, so drop the link.
            LOG.warning("Framing error from " + peer + ", closing: " + e.getMessage());
        } catch (IOException e) {
            LOG.warning("I/O error from " + peer + ": " + e.getMessage());
        }
    }

    @Override
    public void close() throws IOException {
        running = false;
        serverSocket.close();
        workers.shutdownNow();
    }
}
