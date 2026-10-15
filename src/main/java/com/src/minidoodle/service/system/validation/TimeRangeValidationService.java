package com.src.minidoodle.service.system.validation;

import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static com.src.minidoodle.constant.ApiRequestLimits.MAX_RANGE_DAYS;

/** Shared interval rules for slot commands and availability queries. */
@Service
public class TimeRangeValidationService {
    private static final Instant MIN_TIMESTAMP = Instant.parse("0001-01-01T00:00:00Z");
    private static final Instant MAX_TIMESTAMP_EXCLUSIVE = Instant.parse("+10000-01-01T00:00:00Z");

    public void validate(Instant from, Instant to) {
        if (from == null || to == null || !from.isBefore(to)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_TIME_RANGE,
                    "The interval start must precede its end");
        }
        // Check UTC instants, not the local years supplied with an offset.
        if (from.isBefore(MIN_TIMESTAMP) || !to.isBefore(MAX_TIMESTAMP_EXCLUSIVE)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_TIME_RANGE,
                    "Timestamps must fall within UTC years 0001 through 9999",
                    Map.of("min", MIN_TIMESTAMP.toString(), "maxExclusive", MAX_TIMESTAMP_EXCLUSIVE.toString()));
        }
        if (Duration.between(from, to).compareTo(Duration.ofDays(MAX_RANGE_DAYS)) > 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.TIME_RANGE_TOO_LARGE,
                    "The interval exceeds the maximum duration", Map.of("maxDays", MAX_RANGE_DAYS));
        }
        if (from.getNano() % 1000 != 0 || to.getNano() % 1000 != 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_TIME_RANGE,
                    "Timestamps support at most microsecond precision");
        }
    }
}
