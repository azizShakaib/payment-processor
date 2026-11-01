package dev.paysim.link;

import dev.paysim.iso8583.IsoMessage;

/**
 * Observer hooks for a link, mostly for tests and metrics. All default to no-ops.
 * They run on library threads and must return quickly and not block.
 * State changes are reported in order.
 */
public interface LinkListener {

    default void onStateChange(LinkState from, LinkState to) {
    }

    /** A response arrived that matches no request in flight (late after a timeout, or unsolicited). */
    default void onUnmatchedResponse(IsoMessage response) {
    }

    /** A frame did not parse as ISO 8583. The link stays open. Only the size and reason are given, never content. */
    default void onUnparseableFrame(int length, String reason) {
    }
}
