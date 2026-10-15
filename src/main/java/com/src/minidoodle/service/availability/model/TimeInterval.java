package com.src.minidoodle.service.availability.model;

import lombok.Builder;

import java.time.Instant;
import java.util.Objects;

/**
 * Nonempty half-open interval used by availability merge, subtraction and intersection operations.
 * @param startsAt inclusive boundary
 * @param endsAt exclusive boundary, strictly after startsAt
 */
@Builder
public record TimeInterval(Instant startsAt, Instant endsAt) {

    /**
     * Enforces the interval invariant for direct construction and builder calls.
     * @throws NullPointerException if either boundary is null
     * @throws IllegalArgumentException if the interval is empty or reversed
     */
    public TimeInterval {
        Objects.requireNonNull(startsAt);
        Objects.requireNonNull(endsAt);
        if (!startsAt.isBefore(endsAt)) {
            throw new IllegalArgumentException("Interval start must precede its end");
        }
    }
}
