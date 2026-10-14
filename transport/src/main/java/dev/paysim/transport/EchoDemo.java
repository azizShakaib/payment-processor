package dev.paysim.transport;

import dev.paysim.common.Hex;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Runs the echo server and sends it the ISO 8583 echo test request from the
 * Phase 0 lesson. Run it with {@code ./gradlew :transport:run} or from your IDE;
 * pass {@code --args=ASCII_4} to try the ASCII length header.
 *
 * <pre>
 *   0800 8220000000000000 0400000000000000 1015042100 000001 301
 * </pre>
 */
public final class EchoDemo {

    private EchoDemo() {
    }

    public static void main(String[] args) throws Exception {
        LengthHeader header = args.length > 0 ? LengthHeader.valueOf(args[0]) : LengthHeader.BINARY_2;
        FrameCodec codec = new FrameCodec(header);

        String message = "0800" + "8220000000000000" + "0400000000000000" + "1015042100" + "000001" + "301";
        byte[] payload = message.getBytes(StandardCharsets.US_ASCII);

        try (EchoServer server = new EchoServer(0, codec).start();
                FrameClient client = new FrameClient("localhost", server.port(), codec, Duration.ofSeconds(5))) {

            System.out.println("Header style: " + header + " (" + header.size() + " bytes)");
            System.out.println("Length header on the wire: " + Hex.encode(header.encode(payload.length)));
            System.out.println("Sending " + payload.length + " payload bytes:");
            System.out.print(Hex.dump(payload));

            byte[] reply = client.sendAndReceive(payload);

            System.out.println("Received " + reply.length + " bytes back:");
            System.out.print(Hex.dump(reply));
            System.out.println("Match: " + new String(reply, StandardCharsets.US_ASCII).equals(message));
        }
    }
}
