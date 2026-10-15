package dev.minidoodle.availability;

import java.time.Instant;
import java.util.List;

public record AvailabilityView(Instant from, Instant to, List<TimeInterval> free, List<TimeInterval> busy) {
}
