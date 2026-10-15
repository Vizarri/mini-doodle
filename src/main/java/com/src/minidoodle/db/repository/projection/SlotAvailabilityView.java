package com.src.minidoodle.db.repository.projection;

import java.time.Instant;
import java.util.UUID;

/**
 * Scalar slot boundaries used to validate bookings without reusing stale managed entities.
 * Only slots declared free are projected; active reservations are checked separately.
 */
public interface SlotAvailabilityView {

    /**
     * Identifies the calendar that owns this free interval.
     *
     * @return owner calendar identifier
     */
    UUID getCalendarId();

    /**
     * Provides the inclusive boundary of the declared free interval.
     *
     * @return inclusive interval start
     */
    Instant getStartsAt();

    /**
     * Provides the exclusive boundary of the declared free interval.
     *
     * @return exclusive interval end
     */
    Instant getEndsAt();
}
