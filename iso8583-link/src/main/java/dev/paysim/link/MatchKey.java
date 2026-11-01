package dev.paysim.link;

import dev.paysim.iso8583.IsoMessage;
import dev.paysim.iso8583.MessageClass;

/**
 * How a response finds its request on a multiplexed link: the message class
 * (so a 0110 matches a 0100 but never a 0800 with the same STAN) plus the
 * fields every responder echoes: 11 (STAN), 7 (transmission date and time),
 * 32 (acquiring institution) and 41 (terminal). A missing field is the empty
 * string, so messages that never carry, say, field 41 still match.
 *
 * <p>STAN alone is not enough: it is only six digits, wraps at 999999 and is
 * unique per sender, not per network. Adding the timestamp, the institution and
 * the terminal makes a late response for an old transaction very unlikely to
 * collide with a new one. {@code toString} shows the class and STAN only.
 */
public record MatchKey(MessageClass messageClass, String stan, String dateTime, String acquirer, String terminal) {

    public static MatchKey of(IsoMessage m) {
        return new MatchKey(m.mti().messageClass(),
                m.text(11).orElse(""),
                m.text(7).orElse(""),
                m.text(32).orElse(""),
                m.text(41).orElse(""));
    }

    @Override
    public String toString() {
        return "MatchKey[" + messageClass + " stan=" + stan + "]";
    }
}
