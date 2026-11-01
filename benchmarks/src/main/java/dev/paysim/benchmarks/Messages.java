package dev.paysim.benchmarks;

import dev.paysim.common.Money;
import dev.paysim.iso8583.IsoMessage;
import dev.paysim.iso8583.Mti;
import dev.paysim.iso8583.NetworkManagementCode;
import dev.paysim.iso8583.spec.Encoding;
import dev.paysim.iso8583.spec.FieldSpec;
import dev.paysim.iso8583.spec.MessageSpec;
import dev.paysim.iso8583.spec.Specs;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;

/** Fixed fixtures shared by the benchmarks: two dialects and the sample messages. */
public final class Messages {

    public static final MessageSpec ASCII = Specs.PAYSIM_ASCII_87;
    public static final MessageSpec COMPACT = compact(ASCII);

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T14:30:15Z"), ZoneId.of("UTC"));
    private static final byte[] PIN_BLOCK =
            {0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF};

    private Messages() {
    }

    /** Same fields as {@code base}, but BCD MTI, binary bitmap, BCD for N and Z, raw bytes for B. */
    public static MessageSpec compact(MessageSpec base) {
        MessageSpec.Builder builder = MessageSpec.builder(base.name() + "-compact")
                .mtiEncoding(Encoding.BCD)
                .bitmapEncoding(Encoding.BINARY);
        for (FieldSpec f : base.fields()) {
            Encoding encoding = switch (f.type()) {
                case N, Z -> Encoding.BCD;
                case B -> Encoding.BINARY;
                default -> Encoding.ASCII;
            };
            builder.field(new FieldSpec(f.number(), f.name(), f.type(), f.lengthKind(), f.maxLength(),
                    encoding, f.padding()));
        }
        return builder.build();
    }

    public static IsoMessage auth0100(MessageSpec spec) {
        return IsoMessage.builder(spec, Mti.AUTH_REQUEST)
                .pan("4111111111111111")
                .processingCode("000000")
                .amount(Money.of(1500, "USD"))
                .transmissionDateTime(CLOCK)
                .stan(123)
                .localDateTime(LocalDateTime.parse("2026-10-31T10:30:15"))
                .set(14, "2512")
                .set(22, "051")
                .set(25, "00")
                .set(35, "4111111111111111=2512101")
                .terminalId("TERM0001")
                .merchantId("MERCHANT0000001")
                .set(52, PIN_BLOCK)
                .build();
    }

    public static IsoMessage echo0800(MessageSpec spec) {
        return IsoMessage.builder(spec, Mti.NETWORK_REQUEST)
                .set(7, "1015042100")
                .stan(1)
                .networkManagementCode(NetworkManagementCode.ECHO_TEST)
                .build();
    }
}
