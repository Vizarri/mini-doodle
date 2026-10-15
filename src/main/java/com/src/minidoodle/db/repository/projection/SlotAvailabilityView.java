package com.src.minidoodle.db.repository.projection;

import java.time.Instant;
import java.util.UUID;

/** Scalar booking-validation data deliberately bypasses the managed entity cache. */
public interface SlotAvailabilityView {
    UUID getCalendarId();

    Instant getStartsAt();

    Instant getEndsAt();
}
