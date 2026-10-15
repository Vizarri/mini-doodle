package com.src.minidoodle.unit.service.system.validation;

import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.service.system.validation.TimeRangeValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Verifies timestamp parsing and exact storage, UTC-year and elapsed-duration boundaries. */
class TimeRangeValidationServiceTest {
    private static final Instant START = Instant.parse("2030-01-01T10:00:00Z");
    private final TimeRangeValidationService service = new TimeRangeValidationService();

    @ParameterizedTest
    @CsvSource({
            "2030-01-01T12:30:00+02:30, 2030-01-01T10:00:00Z",
            "2029-12-31T22:00:00-12:00, 2030-01-01T10:00:00Z",
            "2030-01-01T10:00:00.123456Z, 2030-01-01T10:00:00.123456Z"
    })
    void parsesOffsetsAsAbsoluteInstants(String input, String expected) {
        assertThat(service.parseTimestamp(input)).isEqualTo(Instant.parse(expected));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"2030-01-01T10:00:00", "1893492000", "2030-02-30T10:00:00Z", " 2030-01-01T10:00:00Z"})
    void rejectsMissingOffsetAndInvalidTimestampSyntax(String input) {
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.parseTimestamp(input)).satisfies(exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getCode()).isEqualTo(ApiErrorCode.INVALID_TIME_RANGE);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidIntervals")
    void rejectsInvalidBoundaries(String scenario, Instant from, Instant to) {
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.validate(from, to)).satisfies(exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getCode()).isEqualTo(ApiErrorCode.INVALID_TIME_RANGE);
        });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("supportedIntervals")
    void acceptsExactUtcAndPrecisionBoundaries(String scenario, Instant from, Instant to) {
        assertThatCode(() -> service.validate(from, to)).doesNotThrowAnyException();
    }

    @Test
    void durationLimitIsElapsedTimeWithAnInclusiveMaximum() {
        Instant maximumEnd = START.plus(31, ChronoUnit.DAYS);

        assertThatCode(() -> service.validate(START, maximumEnd)).doesNotThrowAnyException();
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.validate(START, maximumEnd.plus(1, ChronoUnit.MICROS))).satisfies(exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getCode()).isEqualTo(ApiErrorCode.TIME_RANGE_TOO_LARGE);
            assertThat(exception.getDetails()).isEqualTo(Map.of("maxDays", 31));
        });
    }

    private static Stream<Arguments> invalidIntervals() {
        Instant end = START.plus(1, ChronoUnit.HOURS);
        return Stream.of(
                Arguments.of("missing start", null, end),
                Arguments.of("missing end", START, null),
                Arguments.of("equal boundaries", START, START),
                Arguments.of("reversed boundaries", end, START),
                Arguments.of("start before UTC year 1", Instant.parse("0000-12-31T23:59:59.999999Z"), Instant.parse("0001-01-01T00:00:00Z")),
                Arguments.of("end at UTC year 10000", Instant.parse("9999-12-31T23:59:59Z"), Instant.parse("+10000-01-01T00:00:00Z")),
                Arguments.of("sub-microsecond start", START.plusNanos(1), end),
                Arguments.of("sub-microsecond end", START, end.plusNanos(1))
        );
    }

    private static Stream<Arguments> supportedIntervals() {
        return Stream.of(
                Arguments.of("first supported UTC instant", Instant.parse("0001-01-01T00:00:00Z"), Instant.parse("0001-01-01T00:00:00.000001Z")),
                Arguments.of("last supported UTC instant", Instant.parse("9999-12-31T23:59:59.999998Z"), Instant.parse("9999-12-31T23:59:59.999999Z")),
                Arguments.of("one microsecond", START, START.plus(1, ChronoUnit.MICROS))
        );
    }
}
