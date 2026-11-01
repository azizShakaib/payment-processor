package dev.paysim.link;

import dev.paysim.iso8583.IsoMessage;

/**
 * The application's side of a link: turns a request the far side initiated
 * into a response. Typical: the issuer's authorization logic.
 *
 * <p>Runs on its own virtual thread per request (not on the connection's serial
 * callback queue), so it may block, and a slow request does not hold up the
 * echo tests or other requests on the same link. Consequently responses may
 * leave in a different order than requests arrived, which matching by
 * {@link MatchKey} makes harmless.
 */
@FunctionalInterface
public interface IsoRequestHandler {

    /** Returns the response, or null to send nothing. An exception makes the link reply 39=96. */
    IsoMessage handle(IsoMessage request) throws Exception;
}
