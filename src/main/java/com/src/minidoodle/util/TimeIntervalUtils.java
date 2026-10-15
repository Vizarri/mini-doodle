package com.src.minidoodle.util;

import com.src.minidoodle.service.availability.model.TimeInterval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

/**
 * Set operations on immutable half-open intervals. Inputs may be unordered and overlapping;
 * normalization merges both overlap and adjacency without modifying the supplied collections.
 * Results contain ordered, nonempty intervals and never introduce undeclared time.
 */
public final class TimeIntervalUtils {
    private TimeIntervalUtils() {
    }

    /**
     * Sorts intervals by start and end, then extends the current range while the next range overlaps or touches it.
     * Sorting costs {@code O(n log n)} and the merge pass is linear in the number of input intervals.
     *
     * @param intervals non-null collection of valid intervals in any order
     * @return immutable ordered ranges with no overlap or adjacent boundaries
     */
    public static List<TimeInterval> merge(Collection<TimeInterval> intervals) {
        List<TimeInterval> sorted = intervals.stream().sorted(Comparator.comparing(TimeInterval::startsAt).thenComparing(TimeInterval::endsAt)).toList();
        List<TimeInterval> merged = new ArrayList<>();
        for (TimeInterval next : sorted) {
            if (merged.isEmpty() || next.startsAt().isAfter(merged.getLast().endsAt())) {
                merged.add(next);
            } else {
                TimeInterval previous = merged.removeLast();
                merged.add(TimeInterval.builder().startsAt(previous.startsAt()).endsAt(max(previous.endsAt(), next.endsAt())).build());
            }
        }
        return List.copyOf(merged);
    }

    /**
     * Removes occupied time from available time after normalizing both collections.
     * A cursor emits only uncovered fragments while advancing past relevant blockers; blockers ending at a
     * free interval's start or beginning at its end do not intersect it. The scan is linear after sorting.
     *
     * @param available declared or otherwise established free intervals
     * @param occupied intervals to remove, including those crossing availability boundaries
     * @return immutable remaining free fragments in chronological order
     */
    public static List<TimeInterval> subtract(Collection<TimeInterval> available, Collection<TimeInterval> occupied) {
        List<TimeInterval> free = merge(available);
        List<TimeInterval> busy = merge(occupied);
        List<TimeInterval> result = new ArrayList<>();
        int firstRelevant = 0;
        for (TimeInterval source : free) {
            while (firstRelevant < busy.size() && !busy.get(firstRelevant).endsAt().isAfter(source.startsAt())) {
                firstRelevant++;
            }
            Instant cursor = source.startsAt();
            for (int i = firstRelevant; i < busy.size() && busy.get(i).startsAt().isBefore(source.endsAt()); i++) {
                TimeInterval blocker = busy.get(i);
                if (blocker.startsAt().isAfter(cursor)) {
                    result.add(TimeInterval.builder().startsAt(cursor).endsAt(blocker.startsAt()).build());
                }
                cursor = max(cursor, blocker.endsAt());
                if (!cursor.isBefore(source.endsAt())) {
                    break;
                }
            }
            if (cursor.isBefore(source.endsAt())) {
                result.add(TimeInterval.builder().startsAt(cursor).endsAt(source.endsAt()).build());
            }
        }
        return List.copyOf(result);
    }

    /**
     * Finds common time with a two-pointer scan over normalized inputs, advancing whichever interval ends first.
     * Equal start/end boundaries yield no interval because half-open intervals must have positive duration.
     *
     * @param left first collection of available intervals
     * @param right second collection of available intervals
     * @return immutable chronological intersection, empty when either input has no common time
     */
    public static List<TimeInterval> intersect(Collection<TimeInterval> left, Collection<TimeInterval> right) {
        List<TimeInterval> a = merge(left);
        List<TimeInterval> b = merge(right);
        List<TimeInterval> result = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < a.size() && j < b.size()) {
            Instant start = max(a.get(i).startsAt(), b.get(j).startsAt());
            Instant end = min(a.get(i).endsAt(), b.get(j).endsAt());
            if (start.isBefore(end)) {
                result.add(TimeInterval.builder().startsAt(start).endsAt(end).build());
            }
            if (a.get(i).endsAt().isBefore(b.get(j).endsAt())) {
                i++;
            } else {
                j++;
            }
        }
        return List.copyOf(result);
    }

    private static Instant min(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    private static Instant max(Instant a, Instant b) {
        return a.isAfter(b) ? a : b;
    }
}
