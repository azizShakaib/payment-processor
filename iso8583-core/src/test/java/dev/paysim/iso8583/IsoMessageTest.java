package dev.paysim.iso8583;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.paysim.common.Hex;
import dev.paysim.common.Money;
import dev.paysim.iso8583.IsoDateTimes.TransmissionDateTime;
import dev.paysim.iso8583.spec.MessageSpec;
import dev.paysim.iso8583.spec.Specs;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class IsoMessageTest {

    private static final MessageSpec SPEC = Specs.PAYSIM_ASCII_87;
    private static final String PAN = "4111111111111111";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-31T14:30:15Z"), ZoneId.of("Asia/Tokyo"));
    private static final byte[] PIN_BLOCK = Hex.decode("0123456789ABCDEF");

    private static IsoMessage.Builder sampleBuilder() {
        return IsoMessage.builder(SPEC, Mti.AUTH_REQUEST)
                .pan(PAN)
                .processingCode("000000")
                .amount(Money.of(1500, "USD"))
                .transmissionDateTime(CLOCK)
                .stan(123)
                .localDateTime(LocalDateTime.of(2026, 10, 31, 10, 30, 15))
                .set(14, "2512")
                .set(22, "051")
                .set(25, "00")
                .set(35, PAN + "=2512101")
                .terminalId("TERM0001")
                .merchantId("MERCHANT0000001")
                .set(52, PIN_BLOCK);
    }

    private static IsoMessage sample() {
        return sampleBuilder().build();
    }

    @Test
    void buildsAFullAuthorizationRequest() {
        IsoMessage message = sample();

        assertThat(message.mti()).isEqualTo(Mti.AUTH_REQUEST);
        assertThat(message.spec()).isSameAs(SPEC);
        assertThat(message.fields()).containsExactly(2, 3, 4, 7, 11, 12, 13, 14, 22, 25, 35, 41, 42, 49, 52);
        assertThat(message.text(2)).contains(PAN);
        assertThat(message.text(4)).contains("000000001500");
        assertThat(message.text(7)).contains("1031143015");
        assertThat(message.text(11)).contains("000123");
        assertThat(message.text(12)).contains("103015");
        assertThat(message.text(13)).contains("1031");
        assertThat(message.text(49)).contains("840");
        assertThat(message.bytes(52).orElseThrow()).containsExactly(PIN_BLOCK);
    }

    @Test
    void bitmapIsDerivedFromPresentFields() {
        IsoMessage message = sample();

        assertThat(message.bitmap()).isEqualTo(Bitmap.of(2, 3, 4, 7, 11, 12, 13, 14, 22, 25, 35, 41, 42, 49, 52));
        assertThat(Hex.encode(longToBytes(message.bitmap().primary()))).isEqualTo("723C048020C09000");
        assertThat(message.bitmap().hasSecondary()).isFalse();
    }

    @Test
    void bitmapGetsSecondaryForUpperFields() {
        IsoMessage message = IsoMessage.builder(SPEC, Mti.NETWORK_REQUEST)
                .networkManagementCode(NetworkManagementCode.ECHO_TEST).build();
        assertThat(message.bitmap().hasSecondary()).isTrue();
        assertThat(message.bitmap()).isEqualTo(Bitmap.of(70));
    }

    private static byte[] longToBytes(long value) {
        byte[] out = new byte[8];
        for (int i = 0; i < 8; i++) {
            out[i] = (byte) (value >>> (56 - 8 * i));
        }
        return out;
    }

    @Test
    void messageIsImmutableAfterBuild() {
        IsoMessage.Builder builder = sampleBuilder();
        IsoMessage message = builder.build();

        builder.pan("5500005555555559").remove(14).set(52, new byte[8]);

        assertThat(message.text(2)).contains(PAN);
        assertThat(message.has(14)).isTrue();
        assertThat(message.bytes(52).orElseThrow()).containsExactly(PIN_BLOCK);
        assertThat(builder.build()).isNotEqualTo(message);
    }

    @Test
    void byteArraysAreCopiedInAndOut() {
        byte[] input = PIN_BLOCK.clone();
        IsoMessage message = IsoMessage.builder(SPEC, Mti.AUTH_REQUEST).set(52, input).build();

        input[0] = 0x7F;
        byte[] out = message.bytes(52).orElseThrow();
        out[1] = 0x7F;

        assertThat(message.bytes(52).orElseThrow()).containsExactly(PIN_BLOCK);
        assertThat(((FieldValue.Binary) message.get(52).orElseThrow()).value()).containsExactly(PIN_BLOCK);
    }

    @Test
    void unknownFieldIsRejectedWithSpecName() {
        IsoMessage.Builder builder = IsoMessage.builder(SPEC, Mti.AUTH_REQUEST);
        assertThatThrownBy(() -> builder.set(5, "000000001500"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("field 5 is not defined in spec paysim-ascii-87");
        assertThatThrownBy(() -> builder.remove(5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidValuesFailWhereTheyAreSet() {
        IsoMessage.Builder builder = IsoMessage.builder(SPEC, Mti.AUTH_REQUEST);

        assertThatThrownBy(() -> builder.set(3, "12345A"))
                .isInstanceOfSatisfying(IsoPackException.class, e -> {
                    assertThat(e.field()).isEqualTo(3);
                    assertThat(e).hasMessageStartingWith("field 3:").hasMessageNotContaining("12345");
                });
        assertThatThrownBy(() -> builder.set(3, "1234567")).isInstanceOf(IsoPackException.class);
        assertThatThrownBy(() -> builder.set(52, "text")).isInstanceOf(IsoPackException.class);
        assertThatThrownBy(() -> builder.set(52, new byte[3])).isInstanceOf(IsoPackException.class);
        assertThatThrownBy(() -> builder.pan("4111x")).isInstanceOf(IsoPackException.class);
        assertThatThrownBy(() -> builder.terminalId("TOOLONGTERMINAL")).isInstanceOf(IsoPackException.class);
        assertThat(builder.build().fields()).isEmpty();
    }

    @Test
    void typedSettersValidateSizes() {
        IsoMessage.Builder builder = IsoMessage.builder(SPEC, Mti.AUTH_REQUEST);
        assertThatThrownBy(() -> builder.amount(Money.of(-1, "USD"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.amount(Money.of(1_000_000_000_000L, "USD")))
                .isInstanceOf(IsoPackException.class);
        assertThatThrownBy(() -> builder.stan(1_000_000)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> builder.stan(-1)).isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() -> builder.amount(Money.of(999_999_999_999L, "USD"))).doesNotThrowAnyException();
    }

    @Test
    void responseBuilderCopiesExactlyTheEchoFields() {
        IsoMessage.Builder request = IsoMessage.builder(SPEC, Mti.AUTH_REQUEST);
        int[] echo = {2, 3, 4, 7, 11, 12, 13, 32, 37, 41, 42, 49, 70, 90};
        for (int field : echo) {
            request.set(field, field == 2 ? PAN : filler(field));
        }
        // fields that must NOT be echoed
        request.set(14, "2512").set(22, "051").set(25, "00").set(35, PAN + "=2512101").set(52, PIN_BLOCK);

        IsoMessage response = request.build().responseBuilder().build();

        assertThat(response.mti()).isEqualTo(Mti.AUTH_RESPONSE);
        assertThat(response.fields()).containsExactly(echo);
        for (int field : echo) {
            assertThat(response.get(field)).isEqualTo(request.build().get(field));
        }
    }

    private static String filler(int field) {
        return switch (field) {
            case 3 -> "000000";
            case 4 -> "000000001500";
            case 7 -> "1031143015";
            case 11 -> "000123";
            case 12 -> "103015";
            case 13 -> "1031";
            case 32 -> "123456";
            case 37 -> "123456789012";
            case 41 -> "TERM0001";
            case 42 -> "MERCHANT0000001";
            case 49 -> "840";
            case 70 -> "301";
            case 90 -> "0".repeat(42);
            default -> throw new IllegalArgumentException("no filler for " + field);
        };
    }

    @Test
    void responseBuilderOnlyCopiesWhatIsPresentAndAddsResults() {
        IsoMessage response = sample().responseBuilder()
                .authorizationCode("A1B2C3")
                .responseCode(ResponseCode.APPROVED)
                .build();

        assertThat(response.mti()).isEqualTo(Mti.AUTH_RESPONSE);
        assertThat(response.fields()).containsExactly(2, 3, 4, 7, 11, 12, 13, 38, 39, 41, 42, 49);
        assertThat(response.authorizationCode()).contains("A1B2C3");
        assertThat(response.responseCode()).contains(ResponseCode.APPROVED);
    }

    @Test
    void responseBuilderMapsNetworkManagement() {
        IsoMessage request = IsoMessage.builder(SPEC, Mti.NETWORK_REQUEST)
                .transmissionDateTime(CLOCK).stan(7).networkManagementCode(NetworkManagementCode.ECHO_TEST).build();

        IsoMessage response = request.responseBuilder().build();

        assertThat(response.mti()).isEqualTo(Mti.NETWORK_RESPONSE);
        assertThat(response.fields()).containsExactly(7, 11, 70);
    }

    @Test
    void responseBuilderRejectsResponses() {
        IsoMessage response = sample().responseBuilder().build();
        assertThatThrownBy(response::responseBuilder).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void toBuilderRoundTrips() {
        IsoMessage message = sample();
        assertThat(message.toBuilder().build()).isEqualTo(message);
        IsoMessage changed = message.toBuilder().mti(Mti.AUTH_ADVICE).remove(52).build();
        assertThat(changed.mti()).isEqualTo(Mti.AUTH_ADVICE);
        assertThat(changed.has(52)).isFalse();
        assertThat(message.has(52)).isTrue();
    }

    @Test
    void equalsAndHashCode() {
        assertThat(sample()).isEqualTo(sample()).hasSameHashCodeAs(sample());
        assertThat(sample()).isNotEqualTo(sampleBuilder().set(25, "01").build());
        assertThat(sample()).isNotEqualTo(sampleBuilder().mti(Mti.AUTH_ADVICE).build());
        MessageSpec other = MessageSpec.builder("other").field(Specs.PAYSIM_ASCII_87.field(2)).build();
        assertThat(IsoMessage.builder(other, Mti.AUTH_REQUEST).pan(PAN).build())
                .isNotEqualTo(IsoMessage.builder(SPEC, Mti.AUTH_REQUEST).pan(PAN).build());
    }

    @Test
    void toStringShowsFieldNumbersNeverValues() {
        String text = sample().toString();

        assertThat(text).isEqualTo("IsoMessage[0100 fields=[2, 3, 4, 7, 11, 12, 13, 14, 22, 25, 35, 41, 42, 49, 52]]");
        assertThat(text).doesNotContain("4111").doesNotContain("1111").doesNotContain("0123")
                .doesNotContain("TERM").doesNotContain("MERCHANT");
        assertThat(IsoMessage.builder(SPEC, Mti.AUTH_REQUEST).build()).hasToString("IsoMessage[0100 fields=[]]");
    }

    @Test
    void typedAccessors() {
        IsoMessage message = sampleBuilder().rrn("123456789012").build();

        assertThat(message.pan()).contains(PAN);
        assertThat(message.processingCode()).contains("000000");
        assertThat(message.amount()).contains(Money.of(1500, "USD"));
        assertThat(message.stan()).isEqualTo(OptionalInt.of(123));
        assertThat(message.transmissionDateTime())
                .contains(new TransmissionDateTime(MonthDay.of(10, 31), LocalTime.of(14, 30, 15)));
        assertThat(message.localTime()).contains(LocalTime.of(10, 30, 15));
        assertThat(message.localDate()).contains(MonthDay.of(10, 31));
        assertThat(message.rrn()).contains("123456789012");
        assertThat(message.authorizationCode()).isEmpty();
        assertThat(message.responseCode()).isEmpty();
        assertThat(message.networkManagementCode()).isEmpty();
    }

    @Test
    void terminalAndMerchantIdsAreTrimmed() {
        // padded as unpack returns them: the accessor strips the padding, text() keeps it
        IsoMessage padded = IsoMessage.builder(SPEC, Mti.AUTH_REQUEST)
                .terminalId("T1      ").merchantId("SHOP           ").build();
        assertThat(padded.text(41)).contains("T1      ");
        assertThat(padded.terminalId()).contains("T1");
        assertThat(padded.text(42)).contains("SHOP           ");
        assertThat(padded.merchantId()).contains("SHOP");

        // unpadded as a sender sets them: the message keeps the value as given, padding is added at pack time
        IsoMessage bare = IsoMessage.builder(SPEC, Mti.AUTH_REQUEST).terminalId("T1").build();
        assertThat(bare.text(41)).contains("T1");
        assertThat(bare.terminalId()).contains("T1");
    }

    @Test
    void absentFieldsGiveEmptyOptionals() {
        IsoMessage message = IsoMessage.builder(SPEC, Mti.AUTH_REQUEST).build();

        assertThat(message.pan()).isEmpty();
        assertThat(message.amount()).isEmpty();
        assertThat(message.stan()).isEmpty();
        assertThat(message.transmissionDateTime()).isEmpty();
        assertThat(message.get(2)).isEmpty();
        assertThat(message.text(2)).isEqualTo(Optional.empty());
        assertThat(message.has(999)).isFalse();
        assertThat(message.has(-1)).isFalse();
    }

    @Test
    void amountNeedsCurrency() {
        IsoMessage message = IsoMessage.builder(SPEC, Mti.AUTH_REQUEST).set(4, "000000001500").build();
        assertThatThrownBy(message::amount).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("field 4").hasMessageContaining("field 49");
        IsoMessage onlyCurrency = IsoMessage.builder(SPEC, Mti.AUTH_REQUEST).set(49, "840").build();
        assertThat(onlyCurrency.amount()).isEmpty();
    }

    @Test
    void moneyRoundTripsForOtherCurrencies() {
        for (Money money : new Money[] {Money.of(1500, "JPY"), Money.of(1500, "BHD"), Money.of(0, "EUR")}) {
            IsoMessage message = IsoMessage.builder(SPEC, Mti.AUTH_REQUEST).amount(money).build();
            assertThat(message.amount()).contains(money);
        }
        IsoMessage euro = IsoMessage.builder(SPEC, Mti.AUTH_REQUEST).amount(Money.of(1, "EUR")).build();
        assertThat(euro.text(49)).contains("978");
    }

    @Test
    void unknownResponseCodeIsEmptyButRawIsAvailable() {
        IsoMessage message = IsoMessage.builder(SPEC, Mti.AUTH_RESPONSE).set(39, "99").build();
        assertThat(message.responseCode()).isEmpty();
        assertThat(message.text(39)).contains("99");
        IsoMessage declined = IsoMessage.builder(SPEC, Mti.AUTH_RESPONSE)
                .responseCode(ResponseCode.INSUFFICIENT_FUNDS).build();
        assertThat(declined.text(39)).contains("51");
        assertThat(declined.responseCode().orElseThrow().isApproved()).isFalse();
    }

    @Test
    void textOnBinaryFieldAndBytesOnTextFieldFail() {
        IsoMessage message = sample();
        assertThatThrownBy(() -> message.text(52)).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("field 52");
        assertThatThrownBy(() -> message.bytes(2)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void networkManagementCodeAccessor() {
        IsoMessage message = IsoMessage.builder(SPEC, Mti.NETWORK_REQUEST)
                .networkManagementCode(NetworkManagementCode.SIGN_ON).build();
        assertThat(message.networkManagementCode()).contains(NetworkManagementCode.SIGN_ON);
        assertThat(message.text(70)).contains("001");
    }
}
