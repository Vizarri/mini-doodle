package com.src.minidoodle.util;

import com.src.minidoodle.service.availability.model.TimeInterval;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

public final class TimeIntervalUtils {
    private TimeIntervalUtils() {
    }

    public static List<TimeInterval> merge(Collection<TimeInterval> intervals) {
        List<TimeInterval> sorted = intervals.stream()
                .sorted(Comparator.comparing(TimeInterval::startsAt).thenComparing(TimeInterval::endsAt)).toList();
        List<TimeInterval> merged = new ArrayList<>();
        for (TimeInterval next : sorted) {
            if (merged.isEmpty() || next.startsAt().isAfter(merged.getLast().endsAt())) {
                merged.add(next);
            } else {
                TimeInterval previous = merged.removeLast();
                merged.add(new TimeInterval(previous.startsAt(), max(previous.endsAt(), next.endsAt())));
            }
        }
        return List.copyOf(merged);
    }

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
                    result.add(new TimeInterval(cursor, blocker.startsAt()));
                }
                cursor = max(cursor, blocker.endsAt());
                if (!cursor.isBefore(source.endsAt())) {
                    break;
                }
            }
            if (cursor.isBefore(source.endsAt())) {
                result.add(new TimeInterval(cursor, source.endsAt()));
            }
        }
        return List.copyOf(result);
    }

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
                result.add(new TimeInterval(start, end));
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
