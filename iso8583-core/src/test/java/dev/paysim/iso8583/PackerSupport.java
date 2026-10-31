package dev.paysim.iso8583;

import dev.paysim.common.Money;
import dev.paysim.iso8583.spec.Encoding;
import dev.paysim.iso8583.spec.FieldSpec;
import dev.paysim.iso8583.spec.FieldType;
import dev.paysim.iso8583.spec.MessageSpec;
import dev.paysim.iso8583.spec.Specs;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;

/** Shared specs, sample messages and value generators for the packer tests. */
final class PackerSupport {

    static final MessageSpec ASCII = Specs.PAYSIM_ASCII_87;
    static final MessageSpec COMPACT = compact(ASCII);
    static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T14:30:15Z"), ZoneId.of("UTC"));
    static final byte[] PIN_BLOCK = {0x01, 0x23, 0x45, 0x67, (byte) 0x89, (byte) 0xAB, (byte) 0xCD, (byte) 0xEF};

    static final List<Mti> ALL_MTIS = List.of(Mti.AUTH_REQUEST, Mti.AUTH_RESPONSE, Mti.AUTH_ADVICE,
            Mti.FINANCIAL_REQUEST, Mti.FINANCIAL_RESPONSE, Mti.REVERSAL_REQUEST, Mti.REVERSAL_ADVICE,
            Mti.NETWORK_REQUEST, Mti.NETWORK_RESPONSE);

    private PackerSupport() {
    }

    /** Same fields as {@code base}, but BCD MTI, binary bitmap, BCD for N and Z, raw bytes for B. */
    static MessageSpec compact(MessageSpec base) {
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

    static IsoMessage.Builder sample0100(MessageSpec spec) {
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
                .set(52, PIN_BLOCK);
    }

    static IsoMessage.Builder sample0110(MessageSpec spec) {
        return sample0100(spec).build().responseBuilder()
                .authorizationCode("A1B2C3")
                .responseCode(ResponseCode.APPROVED);
    }

    static IsoMessage echo0800(MessageSpec spec) {
        return IsoMessage.builder(spec, Mti.NETWORK_REQUEST)
                .set(7, "1015042100")
                .stan(1)
                .networkManagementCode(NetworkManagementCode.ECHO_TEST)
                .build();
    }

    /** A value of exactly the field's maximum length that is valid for its type. */
    static IsoMessage.Builder setAtMaxLength(IsoMessage.Builder builder, FieldSpec f) {
        int n = f.maxLength();
        if (f.type() == FieldType.B) {
            byte[] bytes = new byte[n];
            for (int i = 0; i < n; i++) {
                bytes[i] = (byte) (0x11 * (i + 1) + f.number());
            }
            return builder.set(f.number(), bytes);
        }
        StringBuilder s = new StringBuilder(n);
        for (int i = 0; i < n; i++) {
            s.append(charFor(f, i));
        }
        return builder.set(f.number(), s.toString());
    }

    private static char charFor(FieldSpec f, int i) {
        char digit = (char) ('0' + (i + f.number()) % 10);
        return switch (f.type()) {
            case N -> digit;
            case XN -> i == 0 ? 'C' : digit;
            case Z -> i == 4 && f.maxLength() > 5 ? '=' : digit;
            case AN -> i % 3 == 0 ? (char) ('A' + (i + f.number()) % 26) : digit;
            case ANS -> i % 5 == 4 ? ' ' : (i % 3 == 0 ? (char) ('A' + (i + f.number()) % 26) : digit);
            case B -> throw new IllegalStateException("binary handled by caller");
        };
    }

    static IsoMessage fullMessage(MessageSpec spec, Mti mti) {
        IsoMessage.Builder builder = IsoMessage.builder(spec, mti);
        for (FieldSpec f : spec.fields()) {
            setAtMaxLength(builder, f);
        }
        return builder.build();
    }

    /** Buffer with {@code lead} filler bytes in front of and {@code tail} behind {@code wire}. */
    static byte[] framed(byte[] wire, int lead, int tail) {
        byte[] out = new byte[lead + wire.length + tail];
        Arrays.fill(out, (byte) 0xEE);
        System.arraycopy(wire, 0, out, lead, wire.length);
        return out;
    }
}
