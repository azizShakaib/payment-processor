package dev.paysim.iso8583.spec;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A wire dialect: how the MTI and bitmap are encoded and which data elements
 * exist. Two networks speaking "ISO 8583" differ exactly in these choices.
 * Immutable once built.
 */
public final class MessageSpec {

    private final String name;
    private final Encoding mtiEncoding;
    private final Encoding bitmapEncoding;
    private final FieldSpec[] byNumber;
    private final List<FieldSpec> ordered;

    private MessageSpec(Builder builder) {
        this.name = builder.name;
        this.mtiEncoding = builder.mtiEncoding;
        this.bitmapEncoding = builder.bitmapEncoding;
        this.byNumber = builder.byNumber.clone();
        List<FieldSpec> list = new ArrayList<>();
        for (FieldSpec spec : byNumber) {
            if (spec != null) {
                list.add(spec);
            }
        }
        this.ordered = List.copyOf(list);
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public String name() {
        return name;
    }

    public Encoding mtiEncoding() {
        return mtiEncoding;
    }

    public Encoding bitmapEncoding() {
        return bitmapEncoding;
    }

    public boolean has(int number) {
        return number >= 0 && number < byNumber.length && byNumber[number] != null;
    }

    public FieldSpec field(int number) {
        if (!has(number)) {
            throw new IllegalArgumentException("field " + number + " is not defined in spec " + name);
        }
        return byNumber[number];
    }

    /** All defined fields, ordered by field number; unmodifiable. */
    public List<FieldSpec> fields() {
        return ordered;
    }

    @Override
    public String toString() {
        return name + " (" + ordered.size() + " fields)";
    }

    /** Collects encodings and field specs, then validates and freezes them. */
    public static final class Builder {

        private final String name;
        private Encoding mtiEncoding = Encoding.ASCII;
        private Encoding bitmapEncoding = Encoding.ASCII;
        private final FieldSpec[] byNumber = new FieldSpec[FieldSpec.MAX_NUMBER + 1];

        private Builder(String name) {
            this.name = Objects.requireNonNull(name, "spec name must not be null");
            if (name.isBlank()) {
                throw new IllegalArgumentException("spec name must not be blank");
            }
        }

        public Builder mtiEncoding(Encoding encoding) {
            Objects.requireNonNull(encoding, "mtiEncoding must not be null");
            if (encoding != Encoding.ASCII && encoding != Encoding.BCD) {
                throw new IllegalArgumentException(
                        "mtiEncoding must be ASCII or BCD in spec " + name + ", got " + encoding);
            }
            this.mtiEncoding = encoding;
            return this;
        }

        public Builder bitmapEncoding(Encoding encoding) {
            Objects.requireNonNull(encoding, "bitmapEncoding must not be null");
            if (encoding != Encoding.ASCII && encoding != Encoding.BINARY) {
                throw new IllegalArgumentException(
                        "bitmapEncoding must be ASCII (hex) or BINARY in spec " + name + ", got " + encoding);
            }
            this.bitmapEncoding = encoding;
            return this;
        }

        public Builder field(FieldSpec spec) {
            Objects.requireNonNull(spec, "field spec must not be null");
            if (byNumber[spec.number()] != null) {
                throw new IllegalArgumentException(
                        "field " + spec.number() + " is defined twice in spec " + name);
            }
            byNumber[spec.number()] = spec;
            return this;
        }

        public Builder field(int number, String fieldName, FieldType type, LengthKind lengthKind,
                             int maxLength, Encoding encoding) {
            return field(FieldSpec.of(number, fieldName, type, lengthKind, maxLength, encoding));
        }

        public MessageSpec build() {
            return new MessageSpec(this);
        }
    }
}
