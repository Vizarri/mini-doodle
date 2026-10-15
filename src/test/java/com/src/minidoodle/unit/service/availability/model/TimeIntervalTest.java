package com.src.minidoodle.unit.service.availability.model;

import com.src.minidoodle.service.availability.model.TimeInterval;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks the nonempty, non-null interval invariant shared by all availability calculations. */
class TimeIntervalTest {
    @Test
    void preservesValidBoundariesWithoutRounding() {
        Instant start = Instant.parse("2035-06-01T10:00:00.000001Z");
        Instant end = start.plusNanos(1);

        TimeInterval interval = TimeInterval.builder().startsAt(start).endsAt(end).build();

        assertThat(interval.startsAt()).isEqualTo(start);
        assertThat(interval.endsAt()).isEqualTo(end);
    }

    @Test
    void rejectsMissingStart() {
        assertThatThrownBy(() -> TimeInterval.builder().endsAt(Instant.parse("2035-06-01T11:00:00Z")).build())
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsMissingEnd() {
        assertThatThrownBy(() -> TimeInterval.builder().startsAt(Instant.parse("2035-06-01T10:00:00Z")).build())
                .isInstanceOf(NullPointerException.class);
    }

    @ParameterizedTest
    @CsvSource({"10:00:00,10:00:00", "11:00:00,10:00:00"})
    void rejectsEmptyAndReversedIntervals(String start, String end) {
        assertThatThrownBy(() -> TimeInterval.builder().startsAt(Instant.parse("2035-06-01T" + start + "Z")).endsAt(Instant.parse("2035-06-01T" + end + "Z")).build())
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Interval start must precede its end");
    }
}
