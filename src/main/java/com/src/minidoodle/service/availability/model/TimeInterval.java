package com.src.minidoodle.service.availability.model;

import java.time.Instant;
import java.util.Objects;

/** A half-open interval: startsAt is included, endsAt is excluded. */
public record TimeInterval(Instant startsAt, Instant endsAt) {
    public TimeInterval {
        Objects.requireNonNull(startsAt);
        Objects.requireNonNull(endsAt);
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("Interval start must precede its end");
        }
    }
}
