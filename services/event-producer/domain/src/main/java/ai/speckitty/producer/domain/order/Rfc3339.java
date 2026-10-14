package ai.speckitty.producer.domain.order;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The RFC 3339 {@code date-time} reader of the total order: exact instants, nine fractional digits at most. */
public final class Rfc3339 {

    private static final Pattern SHAPE = Pattern.compile(
            "([0-9]{4})-([0-9]{2})-([0-9]{2})[Tt]([0-9]{2}):([0-9]{2}):([0-9]{2})(?:\\.([0-9]{1,9}))?([Zz]|[+-][0-9]{2}:[0-9]{2})");
    private static final int NANO_DIGITS = 9;
    private static final int LEAP_SECOND = 60;
    private static final int LAST_PLAIN_SECOND = 59;
    private static final int SECONDS_PER_HOUR = 3600;
    private static final int SECONDS_PER_MINUTE = 60;
    private static final int MAX_OFFSET_HOUR = 23;
    private static final int MAX_OFFSET_MINUTE = 59;

    private Rfc3339() {}

    /** The instant {@code text} denotes, empty when it is not a string or not an accepted RFC 3339 timestamp. */
    public static Optional<Instant> parse(Object text) {
        if (!(text instanceof String value)) {
            return Optional.empty();
        }
        Matcher m = SHAPE.matcher(value);
        if (!m.matches()) {
            return Optional.empty();
        }
        int year = Integer.parseInt(m.group(1));
        int second = Integer.parseInt(m.group(6));
        if (year < 1 || second > LEAP_SECOND) {
            return Optional.empty();
        }
        long offsetSeconds = 0;
        String offset = m.group(8);
        if (offset.charAt(0) == '+' || offset.charAt(0) == '-') {
            int hours = Integer.parseInt(offset.substring(1, 3));
            int minutes = Integer.parseInt(offset.substring(4, 6));
            if (hours > MAX_OFFSET_HOUR || minutes > MAX_OFFSET_MINUTE) {
                return Optional.empty();
            }
            offsetSeconds = (long) (hours * SECONDS_PER_HOUR + minutes * SECONDS_PER_MINUTE) * (offset.charAt(0) == '-' ? -1 : 1);
        }
        LocalDateTime local;
        try {
            local = LocalDateTime.of(
                    year,
                    Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)),
                    Integer.parseInt(m.group(5)),
                    Math.min(second, LAST_PLAIN_SECOND));
        } catch (DateTimeException e) {
            return Optional.empty();
        }
        String fraction = m.group(7);
        int nanos = fraction == null ? 0 : Integer.parseInt((fraction + "000000000").substring(0, NANO_DIGITS));
        long epochSecond = local.toEpochSecond(ZoneOffset.UTC) + (second - Math.min(second, LAST_PLAIN_SECOND)) - offsetSeconds;
        return Optional.of(Instant.ofEpochSecond(epochSecond, nanos));
    }
}
