package dev.paysim.link;

import dev.paysim.common.Hex;
import dev.paysim.iso8583.IsoMessage;
import dev.paysim.iso8583.IsoPacker;
import dev.paysim.iso8583.Mti;
import dev.paysim.iso8583.NetworkManagementCode;
import dev.paysim.iso8583.ResponseCode;
import dev.paysim.transport.LengthHeader;
import dev.paysim.transport.netty.Backoff;

import java.io.PrintStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Starts a server and a client on localhost, lets the link idle long enough for
 * echo tests, sends one authorization, signs off, and prints the whole session
 * from the library's own FINE logs (masked by {@code IsoPrinter}).
 *
 * <p>Run it: {@code ./mvnw -q -pl iso8583-link -am test -Dtest=LinkDemoTest
 * -Dlink.demo=true -Dsurefire.failIfNoSpecifiedTests=false}
 */
public final class LinkDemo {

    private LinkDemo() {
    }

    public static void main(String[] args) throws Exception {
        run(System.out, LengthHeader.BINARY_2);
    }

    public static void run(PrintStream out, LengthHeader header) throws Exception {
        // 1. The sign-on frame itself, built with a fixed clock so the bytes are reproducible.
        Clock fixed = Clock.fixed(Instant.parse("2026-03-14T10:15:42Z"), ZoneOffset.UTC);
        IsoMessage signOn = IsoMessage.builder(TestMessages.SPEC, Mti.NETWORK_REQUEST)
                .transmissionDateTime(fixed).stan(1).networkManagementCode(NetworkManagementCode.SIGN_ON).build();
        byte[] payload = IsoPacker.pack(signOn);
        out.println("=== sign-on frame (" + header + ") ===");
        out.println("payload: " + payload.length + " bytes, header: " + Hex.encode(header.encode(payload.length)));
        out.println(Hex.dump(payload));
        out.println("ascii:   " + new String(payload, java.nio.charset.StandardCharsets.US_ASCII));

        // 2. A live session, narrated by the library's FINE logs.
        long t0 = System.nanoTime();
        Logger log = Logger.getLogger("dev.paysim.link");
        Handler printer = new Handler() {
            @Override
            public void publish(LogRecord r) {
                String role = r.getLoggerName().endsWith("Client") ? "client" : "server";
                String msg = r.getMessage();
                long ms = (System.nanoTime() - t0) / 1_000_000;
                synchronized (out) {
                    out.printf("%5d ms  %-6s  %s%n", ms, role, msg);
                }
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        printer.setLevel(Level.ALL);
        Level oldLevel = log.getLevel();
        boolean oldParents = log.getUseParentHandlers();
        log.setLevel(Level.FINE);
        log.setUseParentHandlers(false);
        log.addHandler(printer);

        LinkListener narrator = new LinkListener() {
            @Override
            public void onStateChange(LinkState from, LinkState to) {
                long ms = (System.nanoTime() - t0) / 1_000_000;
                synchronized (out) {
                    out.printf("%5d ms  state   %s -> %s%n", ms, from, to);
                }
            }
        };
        IsoRequestHandler approve = request -> request.responseBuilder()
                .authorizationCode("A1B2C3").responseCode(ResponseCode.APPROVED).build();
        out.println();
        out.println("=== live session ===");
        try (IsoLinkServer server = IsoLinkServer.builder(header, TestMessages.SPEC, approve).build()) {
            server.start();
            try (IsoLinkClient client = IsoLinkClient.builder("localhost", server.port(), header, TestMessages.SPEC)
                    .echoInterval(Duration.ofMillis(400))
                    .backoff(new Backoff(Duration.ofMillis(10), Duration.ofMillis(100)))
                    .listener(narrator).build().start()) {
                client.awaitState(LinkState.SIGNED_ON, Duration.ofSeconds(5));
                Thread.sleep(900); // idle: the client sends echo tests
                IsoMessage response = client.request(TestMessages.auth(client.stan().next(), 2599)).get();
                out.println("response code " + response.responseCode().orElseThrow()
                        + ", authorization code " + response.authorizationCode().orElse("-"));
            }
        } finally {
            log.removeHandler(printer);
            log.setLevel(oldLevel);
            log.setUseParentHandlers(oldParents);
        }
    }
}
