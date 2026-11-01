package dev.paysim.link;

import dev.paysim.iso8583.IsoMessage;
import dev.paysim.iso8583.IsoPacker;
import dev.paysim.iso8583.IsoParseException;
import dev.paysim.iso8583.IsoPrinter;
import dev.paysim.iso8583.ResponseCode;
import dev.paysim.iso8583.spec.MessageSpec;
import dev.paysim.transport.netty.FrameConnection;

import java.util.concurrent.CompletableFuture;
import java.util.logging.Logger;

/**
 * Small helpers shared by {@link IsoLinkClient} and {@link IsoLinkServer}.
 * Logging rule: message contents only through {@link IsoPrinter} (masked),
 * only at FINE, never hex. Suppliers keep the ~10 microsecond formatting cost
 * off the hot path when FINE is off.
 */
final class LinkSupport {

    private LinkSupport() {
    }

    /** Parses a frame; on failure logs size and reason only, tells the listener and returns null. */
    static IsoMessage unpack(MessageSpec spec, byte[] frame, LinkListener listener, Logger log) {
        try {
            return IsoPacker.unpack(spec, frame);
        } catch (RuntimeException e) {
            // IsoParseException messages carry field, offset and reason, never content.
            // For any other exception only the class name is known to be safe.
            String reason = e instanceof IsoParseException ? e.getMessage() : e.getClass().getSimpleName();
            log.warning(() -> "unparseable frame: " + frame.length + " bytes; " + reason);
            listener.onUnparseableFrame(frame.length, reason);
            return null;
        }
    }

    /** True for a message whose field 39 is "00". */
    static boolean approved(IsoMessage m) {
        return m.text(39).filter(ResponseCode.APPROVED.code()::equals).isPresent();
    }

    static IsoMessage reply(IsoMessage request, ResponseCode code) {
        return request.responseBuilder().responseCode(code).build();
    }

    /** Packs and sends. Packing errors throw; send errors arrive through the future. */
    static CompletableFuture<Void> send(FrameConnection connection, IsoMessage message, Logger log) {
        byte[] frame = IsoPacker.pack(message);
        log.fine(() -> "send " + IsoPrinter.format(message));
        return connection.send(frame);
    }

    /** Sends a reply and swallows (with a FINE note) any failure: there is nobody left to tell. */
    static void sendQuietly(FrameConnection connection, IsoMessage message, Logger log) {
        try {
            send(connection, message, log).whenComplete((v, e) -> {
                if (e != null) {
                    log.fine(() -> "reply not sent: " + e.getClass().getSimpleName());
                }
            });
        } catch (RuntimeException e) {
            log.fine(() -> "reply not packed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Runs the application handler. Null means "send nothing"; an exception
     * becomes 39=96, and only the exception class is logged (its message could
     * contain card data).
     */
    static IsoMessage runHandler(IsoRequestHandler handler, IsoMessage request, Logger log) {
        try {
            return handler.handle(request);
        } catch (Exception e) {
            log.warning(() -> "request handler failed: " + e.getClass().getName());
            return reply(request, ResponseCode.SYSTEM_MALFUNCTION);
        }
    }
}
