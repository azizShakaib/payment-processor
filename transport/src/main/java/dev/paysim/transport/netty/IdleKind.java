package dev.paysim.transport.netty;

/** Which direction went quiet. */
public enum IdleKind {
    /** Nothing was received for the configured time. */
    READER,
    /** Nothing was sent for the configured time. */
    WRITER
}
