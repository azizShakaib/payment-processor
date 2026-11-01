package dev.paysim.link;

/** Where a link is in its life. Business traffic flows only in {@link #SIGNED_ON}. */
public enum LinkState {
    /** No TCP connection (not yet connected, dropped, or backing off before a retry). */
    DISCONNECTED,
    /** TCP is up; the sign-on exchange has not completed. */
    CONNECTED,
    /** The far side accepted our sign-on (0810 with 39=00): requests may be sent. */
    SIGNED_ON,
    /** Closed on purpose; terminal. */
    CLOSED
}
