package com.src.minidoodle.unit.util;

import static org.assertj.core.api.Assertions.assertThat;

import com.src.minidoodle.service.availability.model.TimeInterval;
import com.src.minidoodle.util.TimeIntervalUtils;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Checks normalization and half-open interval arithmetic independently of persistence. */
class TimeIntervalUtilsTest {
    @Test
    void mergesOverlappingAndAdjacentIntervalsWithoutMutatingTheInput() {
        List<TimeInterval> input = List.of(interval(11, 12), interval(9, 10), interval(10, 11), interval(9, 11));

        assertThat(TimeIntervalUtils.merge(input)).containsExactly(interval(9, 12));
        assertThat(input).containsExactly(interval(11, 12), interval(9, 10), interval(10, 11), interval(9, 11));
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

    @Test
    void emptyCollectionsProduceEmptyIntervalSets() {
        assertThat(TimeIntervalUtils.merge(List.of())).isEmpty();
        assertThat(TimeIntervalUtils.subtract(List.of(), List.of())).isEmpty();
        assertThat(TimeIntervalUtils.intersect(List.of(), List.of(interval(9, 10)))).isEmpty();
    }

    @Test
    void normalizationKeepsGapsAndDoesNotShrinkRangesForNestedOrDuplicateIntervals() {
        List<TimeInterval> input = List.of(interval(14, 16), interval(9, 12), interval(10, 11), interval(9, 10), interval(9, 12));

        assertThat(TimeIntervalUtils.merge(input)).containsExactly(interval(9, 12), interval(14, 16));
    }

    @Test
    void noBlockersLeaveNormalizedAvailabilityUnchanged() {
        assertThat(TimeIntervalUtils.subtract(List.of(interval(11, 12), interval(9, 10), interval(10, 11)), List.of()))
                .containsExactly(interval(9, 12));
    }

    @Test
    void blockersEntirelyOutsideAvailabilityLeaveItUnchanged() {
        assertThat(TimeIntervalUtils.subtract(List.of(interval(10, 11)), List.of(interval(7, 8), interval(8, 9), interval(12, 13))))
                .containsExactly(interval(10, 11));
    }

    @Test
    void overlappingAndAdjacentBlockersDoNotDuplicateRemainingFreeFragments() {
        List<TimeInterval> busy = List.of(interval(16, 17), interval(11, 13), interval(10, 12), interval(13, 14));

        assertThat(TimeIntervalUtils.subtract(List.of(interval(8, 18)), busy))
                .containsExactly(interval(8, 10), interval(14, 16), interval(17, 18));
    }

    @Test
    void intersectionsWithEqualEndBoundariesAdvanceWithoutRepeatingIntervals() {
        List<TimeInterval> first = List.of(interval(13, 15), interval(9, 11));
        List<TimeInterval> second = List.of(interval(14, 15), interval(10, 11));

        assertThat(TimeIntervalUtils.intersect(first, second)).containsExactly(interval(10, 11), interval(14, 15));
        assertThat(TimeIntervalUtils.intersect(second, first)).containsExactly(interval(10, 11), interval(14, 15));
    }

    private static TimeInterval interval(int startHour, int endHour) {
        return TimeInterval.builder().startsAt(Instant.parse("2030-01-01T%02d:00:00Z".formatted(startHour))).endsAt(Instant.parse("2030-01-01T%02d:00:00Z".formatted(endHour))).build();
    }
}
