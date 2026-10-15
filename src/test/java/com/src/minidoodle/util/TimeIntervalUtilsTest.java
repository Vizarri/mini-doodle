package com.src.minidoodle.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.src.minidoodle.service.availability.model.TimeInterval;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class TimeIntervalUtilsTest {
    @Test
    void mergesOverlappingAndAdjacentIntervalsWithoutMutatingTheInput() {
        List<TimeInterval> input = List.of(interval(11, 12), interval(9, 10), interval(10, 11), interval(9, 11));

        assertThat(TimeIntervalUtils.merge(input)).containsExactly(interval(9, 12));
        assertThat(input).hasSize(4);
    }

    @Test
    void meetingInsideAnAvailableIntervalLeavesBothSidesFree() {
        assertThat(TimeIntervalUtils.subtract(List.of(interval(9, 12)), List.of(interval(10, 11))))
                .containsExactly(interval(9, 10), interval(11, 12));
    }

    @Test
    void subtractsMultipleMeetingsIncludingThoseCrossingAvailabilityBoundaries() {
        List<TimeInterval> free = List.of(interval(9, 12), interval(14, 18));
        List<TimeInterval> busy = List.of(interval(8, 10), interval(11, 15), interval(16, 17));

        assertThat(TimeIntervalUtils.subtract(free, busy))
                .containsExactly(interval(10, 11), interval(15, 16), interval(17, 18));
    }

    @Test
    void touchingMeetingsDoNotConsumeFreeTime() {
        assertThat(TimeIntervalUtils.subtract(List.of(interval(10, 11)),
                List.of(interval(9, 10), interval(11, 12)))).containsExactly(interval(10, 11));
    }

    @Test
    void fullyCoveredAvailabilityProducesNoFreeTime() {
        assertThat(TimeIntervalUtils.subtract(List.of(interval(9, 10), interval(11, 12)),
                List.of(interval(8, 13)))).isEmpty();
    }

    @Test
    void commonFreeTimeRequiresAnActualOverlapNotATouchingBoundary() {
        assertThat(TimeIntervalUtils.intersect(List.of(interval(9, 10), interval(11, 13)),
                List.of(interval(10, 11), interval(12, 14)))).containsExactly(interval(12, 13));
    }

    @Test
    void undeclaredTimeIsNeverIntroducedIntoFreeTime() {
        assertThat(TimeIntervalUtils.subtract(List.of(), List.of(interval(9, 10)))).isEmpty();
        assertThat(TimeIntervalUtils.intersect(List.of(interval(9, 10)), List.of())).isEmpty();
    }

    private static TimeInterval interval(int startHour, int endHour) {
        return new TimeInterval(Instant.parse("2030-01-01T%02d:00:00Z".formatted(startHour)),
                Instant.parse("2030-01-01T%02d:00:00Z".formatted(endHour)));
    }
}
