package dev.paysim.transport.netty;

import dev.paysim.common.Hex;
import dev.paysim.transport.LengthHeader;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * The Phase 0 echo demo over Netty: a {@link NettyFrameServer} that echoes and a
 * {@link NettyFrameClient} that sends the 0800 echo test and prints the reply.
 * Run with {@code ./mvnw -q -pl transport -am compile exec:java
 * -Dexec.mainClass=dev.paysim.transport.netty.NettyEchoDemo -Dexec.args=ASCII_4}.
 * Hex output is demo output only: this hand-made message carries no card data.
 */
public final class NettyEchoDemo {

    private NettyEchoDemo() {
    }

    public static void main(String[] args) throws Exception {
        LengthHeader header = args.length > 0 ? LengthHeader.valueOf(args[0]) : LengthHeader.BINARY_2;
        String message = "0800" + "8220000000000000" + "0400000000000000" + "1015042100" + "000001" + "301";
        byte[] payload = message.getBytes(StandardCharsets.US_ASCII);
        BlockingQueue<byte[]> replies = new LinkedBlockingQueue<>();

        try (NettyFrameServer server = NettyFrameServer.builder(header, (conn, frame) -> conn.send(frame)).build()) {
            server.start();
            try (NettyFrameClient client = NettyFrameClient
                    .builder("localhost", server.port(), header, (conn, frame) -> replies.add(frame)).build()) {
                client.start();
                client.connected().get(5, TimeUnit.SECONDS);
                System.out.println("Header style: " + header + ", header bytes: "
                        + Hex.encode(header.encode(payload.length)));
                client.send(payload).get(5, TimeUnit.SECONDS);
                byte[] reply = replies.poll(5, TimeUnit.SECONDS);
                System.out.print("Echoed " + payload.length + " bytes:\n" + Hex.dump(reply));
            }
        }
    }
}
