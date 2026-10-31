package dev.paysim.iso8583.spec;

import static dev.paysim.iso8583.spec.Encoding.ASCII;
import static dev.paysim.iso8583.spec.FieldType.AN;
import static dev.paysim.iso8583.spec.FieldType.ANS;
import static dev.paysim.iso8583.spec.FieldType.B;
import static dev.paysim.iso8583.spec.FieldType.N;
import static dev.paysim.iso8583.spec.FieldType.XN;
import static dev.paysim.iso8583.spec.FieldType.Z;
import static dev.paysim.iso8583.spec.LengthKind.FIXED;
import static dev.paysim.iso8583.spec.LengthKind.LLLVAR;
import static dev.paysim.iso8583.spec.LengthKind.LLVAR;

/** Ready-made message specs. */
public final class Specs {

    /**
     * The simulator's default dialect: ISO 8583:1987 with ASCII MTI, hex-text
     * bitmaps and ASCII fields, covering the data elements card flows use.
     */
    public static final MessageSpec PAYSIM_ASCII_87 = MessageSpec.builder("paysim-ascii-87")
            .mtiEncoding(ASCII)
            .bitmapEncoding(ASCII)
            .field(2, "Primary account number", N, LLVAR, 19, ASCII)
            .field(3, "Processing code", N, FIXED, 6, ASCII)
            .field(4, "Amount, transaction", N, FIXED, 12, ASCII)
            .field(7, "Transmission date and time", N, FIXED, 10, ASCII)
            .field(11, "System trace audit number", N, FIXED, 6, ASCII)
            .field(12, "Time, local transaction", N, FIXED, 6, ASCII)
            .field(13, "Date, local transaction", N, FIXED, 4, ASCII)
            .field(14, "Date, expiration", N, FIXED, 4, ASCII)
            .field(18, "Merchant category code", N, FIXED, 4, ASCII)
            .field(22, "POS entry mode", N, FIXED, 3, ASCII)
            .field(23, "Card sequence number", N, FIXED, 3, ASCII)
            .field(25, "POS condition code", N, FIXED, 2, ASCII)
            .field(28, "Amount, transaction fee", XN, FIXED, 9, ASCII)
            .field(32, "Acquiring institution ID", N, LLVAR, 11, ASCII)
            .field(35, "Track 2 data", Z, LLVAR, 37, ASCII)
            .field(37, "Retrieval reference number", AN, FIXED, 12, ASCII)
            .field(38, "Authorization ID response", AN, FIXED, 6, ASCII)
            .field(39, "Response code", AN, FIXED, 2, ASCII)
            .field(41, "Card acceptor terminal ID", ANS, FIXED, 8, ASCII)
            .field(42, "Card acceptor ID code", ANS, FIXED, 15, ASCII)
            .field(43, "Card acceptor name/location", ANS, FIXED, 40, ASCII)
            .field(45, "Track 1 data", ANS, LLVAR, 76, ASCII)
            .field(49, "Currency code, transaction", N, FIXED, 3, ASCII)
            .field(52, "PIN data", B, FIXED, 8, ASCII)
            .field(54, "Additional amounts", AN, LLLVAR, 120, ASCII)
            .field(55, "ICC data", B, LLLVAR, 255, ASCII)
            .field(64, "Message authentication code", B, FIXED, 8, ASCII)
            .field(70, "Network management information code", N, FIXED, 3, ASCII)
            .field(90, "Original data elements", N, FIXED, 42, ASCII)
            .field(95, "Replacement amounts", AN, FIXED, 42, ASCII)
            .field(128, "Message authentication code", B, FIXED, 8, ASCII)
            .build();

    private Specs() {
    }
}
