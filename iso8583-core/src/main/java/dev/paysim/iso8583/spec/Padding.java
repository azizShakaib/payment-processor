package dev.paysim.iso8583.spec;

/** How a fixed-length value shorter than its field is filled out. */
public enum Padding {

    /** No padding; the value must already be the right length. */
    NONE,
    /** Zeros on the left, the convention for numeric fields such as amounts. */
    LEFT_ZEROS,
    /** Spaces on the right, the convention for alphanumeric fields such as names. */
    RIGHT_SPACES
}
