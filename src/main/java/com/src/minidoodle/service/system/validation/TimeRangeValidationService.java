package com.src.minidoodle.service.system.validation;

import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

import static com.src.minidoodle.constant.ApiRequestLimits.MAX_RANGE_DAYS;

/**
 * Defines the common timestamp and interval contract for slot commands and availability queries.
 * Validation uses UTC instants so offset representations cannot bypass year or precision limits.
 */
@Service
public class TimeRangeValidationService {
    private static final Instant MIN_TIMESTAMP = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_TIMESTAMP_EXCLUSIVE = Instant.parse("+10000-01-01T00:00:00Z");

    /**
     * Parses an ISO date-time with an explicit UTC offset and converts it to an absolute instant.
     * Numeric epochs, offset-free local times and invalid dates are rejected by the standard ISO parser.
     * Interval bounds and storage precision are checked separately by {@link #validate(Instant, Instant)}.
     *
     * @param value timestamp text supplied by the request
     * @return the represented UTC instant
     * @throws ApiException if the value is missing or is not a valid ISO date-time with an offset
     */
    public Instant parseTimestamp(String value) {
        try {
            return OffsetDateTime.parse(value == null ? "" : value, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch (DateTimeParseException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_TIME_RANGE, "Timestamp must be an ISO 8601 date-time with an explicit UTC offset");
        }
    }

    /**
     * Enforces positive duration, the supported UTC year range, the maximum interval length and
     * PostgreSQL-compatible microsecond precision before values reach persistence queries.
     *
     * @param from inclusive start instant
     * @param to exclusive end instant
     * @throws ApiException if either boundary is missing or any interval rule is violated
     */
    public void validate(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_TIME_RANGE, "The interval start must precede its end");
        }
        // Check UTC instants, not the local years supplied with an offset.
        if (from.isBefore(MIN_TIMESTAMP) || !to.isBefore(MAX_TIMESTAMP_EXCLUSIVE)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_TIME_RANGE, "Timestamps must fall within UTC years 0001 through 9999", Map.of("min", MIN_TIMESTAMP.toString(), "maxExclusive", MAX_TIMESTAMP_EXCLUSIVE.toString()));
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(MAX_RANGE_DAYS)) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.TIME_RANGE_TOO_LARGE, "The interval exceeds the maximum duration", Map.of("maxDays", MAX_RANGE_DAYS));
        }
        if (from.getNano() % 1000 != 0 || to.getNano() % 1000 != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_TIME_RANGE, "Timestamps support at most microsecond precision");
        }
    }
}
