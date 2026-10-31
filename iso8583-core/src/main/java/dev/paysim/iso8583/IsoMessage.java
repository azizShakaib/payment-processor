package dev.paysim.iso8583;

import dev.paysim.common.Money;
import dev.paysim.iso8583.IsoDateTimes.TransmissionDateTime;
import dev.paysim.iso8583.codec.FieldCodec;
import dev.paysim.iso8583.spec.MessageSpec;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * An ISO 8583 message in memory: an {@link Mti} plus the data elements that
 * are present, each already checked against the {@link MessageSpec}. Immutable;
 * change one through {@link #toBuilder()}. There is no wire format here
 * (packing comes in a later step), and {@link #toString()} lists field
 * numbers only, never values, because values can be card numbers.
 *
 * <p>Equality compares the spec by identity (specs are built once and shared,
 * so the same dialect is the same object), then the MTI and every field's
 * content.
 */
public final class IsoMessage {

    private static final int MAX_FIELD = 128;

    /**
     * Fields a response copies from its request. 2, 3, 4, 7, 11, 12, 13, 32,
     * 37, 41, 42 and 49 let the requester match the answer (STAN, terminal,
     * acquirer, RRN) and show the same amount and time on the receipt; 70 says
     * which network function is being answered; 90 names the original
     * transaction in a reversal. The responder adds 38 and 39 itself.
     */
    private static final int[] ECHO_FIELDS = {2, 3, 4, 7, 11, 12, 13, 32, 37, 41, 42, 49, 70, 90};

    private final MessageSpec spec;
    private final Mti mti;
    private final FieldValue[] values;

    private IsoMessage(MessageSpec spec, Mti mti, FieldValue[] values) {
        this.spec = spec;
        this.mti = mti;
        this.values = values;
    }

    public static Builder builder(MessageSpec spec, Mti mti) {
        return new Builder(spec, mti);
    }

    public Builder toBuilder() {
        Builder builder = new Builder(spec, mti);
        System.arraycopy(values, 0, builder.values, 0, values.length);
        return builder;
    }

    /**
     * Starts the answer to this message: the MTI becomes {@link Mti#responseMti()}
     * and the echo fields present in this message are copied. The responder
     * still adds 38 (authorization code) and 39 (response code).
     *
     * @throws IllegalStateException if this message is itself a response
     */
    public Builder responseBuilder() {
        Builder builder = new Builder(spec, mti.responseMti());
        for (int field : ECHO_FIELDS) {
            builder.values[field] = values[field];
        }
        return builder;
    }

    public MessageSpec spec() {
        return spec;
    }

    public Mti mti() {
        return mti;
    }

    public boolean has(int field) {
        return field >= 0 && field <= MAX_FIELD && values[field] != null;
    }

    public Optional<FieldValue> get(int field) {
        return has(field) ? Optional.of(values[field]) : Optional.empty();
    }

    /** The field's text; empty if absent. */
    public Optional<String> text(int field) {
        return get(field).map(value -> {
            if (value instanceof FieldValue.Text text) {
                return text.value();
            }
            throw new IllegalStateException("field " + field + " is binary, use bytes()");
        });
    }

    /** A copy of the field's bytes; empty if absent. */
    public Optional<byte[]> bytes(int field) {
        return get(field).map(value -> {
            if (value instanceof FieldValue.Binary binary) {
                return binary.value();
            }
            throw new IllegalStateException("field " + field + " is text, use text()");
        });
    }

    /** The bitmap derived from the fields present. */
    public Bitmap bitmap() {
        return Bitmap.of(fields());
    }

    /** Present field numbers in ascending order. */
    public int[] fields() {
        int count = 0;
        for (int field = 2; field <= MAX_FIELD; field++) {
            if (values[field] != null) {
                count++;
            }
        }
        int[] result = new int[count];
        int n = 0;
        for (int field = 2; field <= MAX_FIELD; field++) {
            if (values[field] != null) {
                result[n++] = field;
            }
        }
        return result;
    }

    /** Field 2. This is the clear card number: mask it with {@code Pan.mask} before logging. */
    public Optional<String> pan() {
        return text(2);
    }

    public Optional<String> processingCode() {
        return text(3);
    }

    /**
     * Fields 4 and 49 as {@link Money}.
     *
     * @throws IllegalStateException if field 4 is present but field 49 is not
     */
    public Optional<Money> amount() {
        Optional<String> minor = text(4);
        if (minor.isEmpty()) {
            return Optional.empty();
        }
        Optional<String> currency = text(49);
        if (currency.isEmpty()) {
            throw new IllegalStateException("field 4 is present but field 49 (currency) is missing");
        }
        return Optional.of(Money.ofNumeric(Long.parseLong(minor.get()), Integer.parseInt(currency.get())));
    }

    public OptionalInt stan() {
        return text(11).map(s -> OptionalInt.of(Integer.parseInt(s))).orElse(OptionalInt.empty());
    }

    public Optional<TransmissionDateTime> transmissionDateTime() {
        return text(7).map(IsoDateTimes::parseTransmissionDateTime);
    }

    public Optional<LocalTime> localTime() {
        return text(12).map(IsoDateTimes::parseLocalTime);
    }

    public Optional<MonthDay> localDate() {
        return text(13).map(IsoDateTimes::parseLocalDate);
    }

    public Optional<String> rrn() {
        return text(37);
    }

    public Optional<String> authorizationCode() {
        return text(38);
    }

    /** Empty if absent or not a code this simulator knows; the raw value stays available via {@code text(39)}. */
    public Optional<ResponseCode> responseCode() {
        return text(39).flatMap(ResponseCode::fromCode);
    }

    /** Field 41 without its trailing padding spaces. */
    public Optional<String> terminalId() {
        return text(41).map(IsoMessage::stripPadding);
    }

    /** Field 42 without its trailing padding spaces. */
    public Optional<String> merchantId() {
        return text(42).map(IsoMessage::stripPadding);
    }

    public Optional<NetworkManagementCode> networkManagementCode() {
        return text(70).flatMap(NetworkManagementCode::fromCode);
    }

    private static String stripPadding(String s) {
        int end = s.length();
        while (end > 0 && s.charAt(end - 1) == ' ') {
            end--;
        }
        return s.substring(0, end);
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof IsoMessage other
                && spec == other.spec && mti.equals(other.mti) && Arrays.equals(values, other.values);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * System.identityHashCode(spec) + mti.hashCode()) + Arrays.hashCode(values);
    }

    /** {@code IsoMessage[0100 fields=[2, 3, 4]]}: field numbers only, never values. */
    @Override
    public String toString() {
        return "IsoMessage[" + mti + " fields=" + Arrays.toString(fields()) + "]";
    }

    /** Collects fields, validating each one as it is set. Reusable; each build is independent. */
    public static final class Builder {

        private final MessageSpec spec;
        private Mti mti;
        private final FieldValue[] values = new FieldValue[MAX_FIELD + 1];

        private Builder(MessageSpec spec, Mti mti) {
            this.spec = Objects.requireNonNull(spec, "spec must not be null");
            this.mti = Objects.requireNonNull(mti, "mti must not be null");
        }

        public Builder mti(Mti mti) {
            this.mti = Objects.requireNonNull(mti, "mti must not be null");
            return this;
        }

        public Builder set(int field, String text) {
            return set(field, FieldValue.text(text));
        }

        public Builder set(int field, byte[] bytes) {
            Objects.requireNonNull(bytes, "bytes must not be null");
            return set(field, FieldValue.binary(bytes));
        }

        /**
         * @throws IllegalArgumentException if the spec does not define the field
         * @throws IsoPackException if the value breaks the field's rules
         */
        public Builder set(int field, FieldValue value) {
            Objects.requireNonNull(value, "value must not be null");
            FieldCodec.validate(spec.field(field), value);
            values[field] = value;
            return this;
        }

        public Builder remove(int field) {
            spec.field(field);
            values[field] = null;
            return this;
        }

        public Builder pan(String pan) {
            return set(2, pan);
        }

        public Builder processingCode(String processingCode) {
            return set(3, processingCode);
        }

        /** Sets field 4 (12 digits of minor units) and field 49 (numeric currency). */
        public Builder amount(Money amount) {
            Objects.requireNonNull(amount, "amount must not be null");
            if (amount.minorUnits() < 0) {
                throw new IllegalArgumentException("amount must not be negative, field 4 carries no sign");
            }
            set(4, String.format("%012d", amount.minorUnits()));
            return set(49, String.format("%03d", amount.numericCode()));
        }

        /** Field 7, in UTC whatever the clock's zone. */
        public Builder transmissionDateTime(Clock clock) {
            return set(7, IsoDateTimes.transmissionDateTime(clock));
        }

        /** Field 11, six digits, zero-padded. */
        public Builder stan(int stan) {
            if (stan < 0 || stan > 999_999) {
                throw new IllegalArgumentException("STAN must be 0-999999, got " + stan);
            }
            return set(11, String.format("%06d", stan));
        }

        /** Fields 12 (HHmmss) and 13 (MMdd) from the terminal's local time. */
        public Builder localDateTime(LocalDateTime local) {
            set(12, IsoDateTimes.localTime(local));
            return set(13, IsoDateTimes.localDate(local));
        }

        public Builder rrn(String rrn) {
            return set(37, rrn);
        }

        public Builder authorizationCode(String authorizationCode) {
            return set(38, authorizationCode);
        }

        public Builder responseCode(ResponseCode code) {
            Objects.requireNonNull(code, "code must not be null");
            return set(39, code.code());
        }

        public Builder terminalId(String terminalId) {
            return set(41, terminalId);
        }

        public Builder merchantId(String merchantId) {
            return set(42, merchantId);
        }

        public Builder networkManagementCode(NetworkManagementCode code) {
            Objects.requireNonNull(code, "code must not be null");
            return set(70, code.code());
        }

        public IsoMessage build() {
            return new IsoMessage(spec, mti, values.clone());
        }
    }
}
