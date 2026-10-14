package dev.paysim.transport;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Turns a TCP byte stream into whole messages and back.
 *
 * <p>Wire format: {@code [length header][payload]}, repeated.
 *
 * <p>The key rule: a single {@code InputStream.read(buf)} may return fewer
 * bytes than asked for, or bytes belonging to two messages. So we always read
 * the exact header size, then the exact payload size, using
 * {@link DataInputStream#readFully(byte[])}, which loops until it has them all.
 */
public final class FrameCodec {

    private final LengthHeader header;
    private final int maxFrameLength;

    public FrameCodec(LengthHeader header) {
        this(header, header.maxLength());
    }

    /**
     * @param maxFrameLength reject frames above this size even if the header
     *     could describe them. Protects memory from a corrupt or hostile peer.
     */
    public FrameCodec(LengthHeader header, int maxFrameLength) {
        if (maxFrameLength < 0 || maxFrameLength > header.maxLength()) {
            throw new IllegalArgumentException("maxFrameLength must be between 0 and " + header.maxLength());
        }
        this.header = header;
        this.maxFrameLength = maxFrameLength;
    }

    public LengthHeader header() {
        return header;
    }

    /**
     * Writes one frame as a single buffer, so header and payload leave in one
     * write call. Two separate writes would work too, but would cost an extra
     * system call and can trigger Nagle delays.
     */
    public void write(OutputStream out, byte[] payload) throws IOException {
        if (payload.length > maxFrameLength) {
            throw new FramingException("Payload of %d bytes exceeds max frame length %d"
                    .formatted(payload.length, maxFrameLength));
        }
        byte[] head = header.encode(payload.length);
        byte[] frame = new byte[head.length + payload.length];
        System.arraycopy(head, 0, frame, 0, head.length);
        System.arraycopy(payload, 0, frame, head.length, payload.length);
        out.write(frame);
        out.flush();
    }

    /**
     * Reads exactly one frame.
     *
     * @return the payload, or {@code null} if the peer closed the connection
     *     cleanly between frames
     * @throws FramingException if the peer closed mid-frame or sent a bad header
     */
    public byte[] read(InputStream in) throws IOException {
        DataInputStream data = in instanceof DataInputStream d ? d : new DataInputStream(in);

        // Peek at the first header byte so we can tell a clean close
        // (no bytes at all) from a broken one (some bytes, then EOF).
        int first = data.read();
        if (first == -1) {
            return null;
        }

        byte[] head = new byte[header.size()];
        head[0] = (byte) first;
        try {
            data.readFully(head, 1, head.length - 1);
        } catch (EOFException e) {
            throw new FramingException("Connection closed inside the length header");
        }

        int length = header.decode(head);
        if (length > maxFrameLength) {
            throw new FramingException("Frame of %d bytes exceeds max frame length %d"
                    .formatted(length, maxFrameLength));
        }

        byte[] payload = new byte[length];
        try {
            data.readFully(payload);
        } catch (EOFException e) {
            throw new FramingException("Connection closed inside a %d-byte payload".formatted(length));
        }
        return payload;
    }
}
