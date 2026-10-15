package dev.minidoodle.common;

import java.time.Duration;
import java.time.Instant;

public final class TimeRanges {
    public static final Duration MAX_RANGE = Duration.ofDays(31);
    private TimeRanges() {}

    public static void validate(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw ApiException.badRequest("startsAt/from must be earlier than endsAt/to");
        }
        if (Duration.between(from, to).compareTo(MAX_RANGE) > 0) {
            throw ApiException.badRequest("A time interval cannot exceed 31 days");
        }
        if (from.getNano() % 1000 != 0 || to.getNano() % 1000 != 0) {
            throw ApiException.badRequest("Timestamps support at most microsecond precision");
        }
    }
}
