package dev.paysim.iso8583;

import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.ZoneOffset;
import java.util.Objects;

/**
 * Date and time formats of fields 7, 12 and 13. Field 7 (transmission date
 * and time, MMddHHmmss) is GMT by definition so every party stamps messages
 * on one clock; fields 12 and 13 are the terminal's local wall-clock time and
 * date, which is what the cardholder's receipt shows. None of the three
 * carries a year, so parsing yields a {@link MonthDay}, never a full date.
 */
public final class IsoDateTimes {

    /** Field 7 as a value: month, day and time, no year, always UTC. */
    public record TransmissionDateTime(MonthDay date, LocalTime time) {

        public TransmissionDateTime {
            Objects.requireNonNull(date, "date must not be null");
            Objects.requireNonNull(time, "time must not be null");
        }
    }

    private IsoDateTimes() {
    }

    /** Field 7: the clock's instant in UTC as {@code MMddHHmmss}, whatever the JVM's zone is. */
    public static String transmissionDateTime(Clock clock) {
        Objects.requireNonNull(clock, "clock must not be null");
        LocalDateTime utc = LocalDateTime.now(clock.withZone(ZoneOffset.UTC));
        return localDate(utc) + localTime(utc);
    }

    /** Field 12: {@code HHmmss} of the given local time. */
    public static String localTime(LocalDateTime local) {
        Objects.requireNonNull(local, "local must not be null");
        return two(local.getHour()) + two(local.getMinute()) + two(local.getSecond());
    }

    /** Field 13: {@code MMdd} of the given local date. */
    public static String localDate(LocalDateTime local) {
        Objects.requireNonNull(local, "local must not be null");
        return two(local.getMonthValue()) + two(local.getDayOfMonth());
    }

    public static TransmissionDateTime parseTransmissionDateTime(String field7) {
        digits(field7, 10, "field 7");
        return new TransmissionDateTime(monthDay(field7.substring(0, 4)), time(field7.substring(4)));
    }

    public static LocalTime parseLocalTime(String field12) {
        digits(field12, 6, "field 12");
        return time(field12);
    }

    public static MonthDay parseLocalDate(String field13) {
        digits(field13, 4, "field 13");
        return monthDay(field13);
    }

    private static MonthDay monthDay(String mmdd) {
        int month = Integer.parseInt(mmdd.substring(0, 2));
        int day = Integer.parseInt(mmdd.substring(2, 4));
        try {
            return MonthDay.of(month, day);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("not a valid month and day", e);
        }
    }

    private static LocalTime time(String hhmmss) {
        int hour = Integer.parseInt(hhmmss.substring(0, 2));
        int minute = Integer.parseInt(hhmmss.substring(2, 4));
        int second = Integer.parseInt(hhmmss.substring(4, 6));
        try {
            return LocalTime.of(hour, minute, second);
        } catch (DateTimeException e) {
            throw new IllegalArgumentException("not a valid time of day", e);
        }
    }

    private static void digits(String value, int length, String label) {
        Objects.requireNonNull(value, label + " must not be null");
        if (value.length() != length) {
            throw new IllegalArgumentException(
                    label + " must be " + length + " digits, got length " + value.length());
        }
        for (int i = 0; i < length; i++) {
            char c = value.charAt(i);
            if (c < '0' || c > '9') {
                throw new IllegalArgumentException(label + " contains a non-digit at index " + i);
            }
        }
    }

    private static String two(int value) {
        return value < 10 ? "0" + value : Integer.toString(value);
    }
}
