package com.src.minidoodle.enums;

/** Public meeting state derived from whether the persisted cancellation timestamp is set. */
public enum MeetingStatus {
    /** The meeting holds a reservation in every participant calendar. */
    ACTIVE,
    /** Reservations are released while meeting details and membership remain available. */
    CANCELLED
}
