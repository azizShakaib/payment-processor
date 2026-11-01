package dev.paysim.link;

import dev.paysim.transport.LengthHeader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/** Prints a whole link session. Off by default: {@code -Dlink.demo=true}. */
class LinkDemoTest {

    @Test
    @EnabledIfSystemProperty(named = "link.demo", matches = "true")
    void printSession() throws Exception {
        LinkDemo.run(System.out, LengthHeader.BINARY_2);
    }
}
