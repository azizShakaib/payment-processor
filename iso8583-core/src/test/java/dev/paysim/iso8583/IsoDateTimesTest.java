package dev.paysim.iso8583;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.paysim.iso8583.IsoDateTimes.TransmissionDateTime;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class IsoDateTimesTest {

    private static final Instant INSTANT = Instant.parse("2026-10-31T14:30:15Z");

    @Test
    void transmissionDateTimeIsUtcEvenWhenTheClockZoneIsNot() {
        Clock tokyo = Clock.fixed(INSTANT, ZoneId.of("Asia/Tokyo"));
        Clock newYork = Clock.fixed(INSTANT, ZoneId.of("America/New_York"));

        assertThat(IsoDateTimes.transmissionDateTime(tokyo)).isEqualTo("1031143015");
        assertThat(IsoDateTimes.transmissionDateTime(newYork)).isEqualTo("1031143015");
    }

    @Test
    void transmissionDateTimeCrossesMidnightInUtcNotLocal() {
        Clock clock = Clock.fixed(Instant.parse("2026-12-31T23:59:59Z"), ZoneId.of("Asia/Tokyo"));
        assertThat(IsoDateTimes.transmissionDateTime(clock)).isEqualTo("1231235959");
    }

    @Test
    void zeroPadsSingleDigits() {
        Clock clock = Clock.fixed(Instant.parse("2026-01-02T03:04:05Z"), ZoneId.of("UTC"));
        assertThat(IsoDateTimes.transmissionDateTime(clock)).isEqualTo("0102030405");
    }

    @Test
    void localTimeAndDate() {
        LocalDateTime local = LocalDateTime.of(2026, 10, 31, 10, 30, 15);
        assertThat(IsoDateTimes.localTime(local)).isEqualTo("103015");
        assertThat(IsoDateTimes.localDate(local)).isEqualTo("1031");
        LocalDateTime early = LocalDateTime.of(2026, 3, 4, 5, 6, 7);
        assertThat(IsoDateTimes.localTime(early)).isEqualTo("050607");
        assertThat(IsoDateTimes.localDate(early)).isEqualTo("0304");
    }

    @Test
    void parsesBack() {
        assertThat(IsoDateTimes.parseTransmissionDateTime("1031143015"))
                .isEqualTo(new TransmissionDateTime(MonthDay.of(10, 31), LocalTime.of(14, 30, 15)));
        assertThat(IsoDateTimes.parseLocalTime("103015")).isEqualTo(LocalTime.of(10, 30, 15));
        assertThat(IsoDateTimes.parseLocalDate("1031")).isEqualTo(MonthDay.of(10, 31));
        assertThat(IsoDateTimes.parseLocalDate("0229")).isEqualTo(MonthDay.of(2, 29));
    }

    @Test
    void rejectsMalformedAndImpossibleValues() {
        assertThatThrownBy(() -> IsoDateTimes.parseLocalTime("1030")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IsoDateTimes.parseLocalTime("10x015")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IsoDateTimes.parseLocalTime("246000")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IsoDateTimes.parseLocalDate("1331")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IsoDateTimes.parseLocalDate("0230")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IsoDateTimes.parseTransmissionDateTime("103114301"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> IsoDateTimes.parseTransmissionDateTime(null))
                .isInstanceOf(NullPointerException.class);
    }
}
