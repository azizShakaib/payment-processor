package dev.paysim.transport;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.Duration;

/**
 * Minimal blocking client: one connection, send a frame, wait for the reply.
 *
 * <p>Sequential request and response is fine for Phase 0. Real links send
 * many requests without waiting and match replies by STAN and RRN; that
 * comes in Phase 3.
 */
public final class FrameClient implements AutoCloseable {

    private final FrameCodec codec;
    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;

    public FrameClient(String host, int port, FrameCodec codec, Duration timeout) throws IOException {
        this.codec = codec;
        this.socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), (int) timeout.toMillis());
        socket.setSoTimeout((int) timeout.toMillis());
        socket.setTcpNoDelay(true);
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());
    }

    public void send(byte[] payload) throws IOException {
        codec.write(out, payload);
    }

    /** Blocks until a full frame arrives or the socket timeout expires. */
    public byte[] receive() throws IOException {
        byte[] payload = codec.read(in);
        if (payload == null) {
            throw new IOException("Server closed the connection");
        }
        return payload;
    }

    public byte[] sendAndReceive(byte[] payload) throws IOException {
        send(payload);
        return receive();
    }

    @Override
    public void close() throws IOException {
        socket.close();
    }
}
