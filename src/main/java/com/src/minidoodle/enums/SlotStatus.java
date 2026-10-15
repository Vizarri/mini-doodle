package com.src.minidoodle.enums;

/** User-declared availability, independent of active meeting reservations. */
public enum SlotStatus {
    /** Declared available time; active reservations may still make part of this interval busy. */
    FREE,
    /** Time the user has explicitly made unavailable for booking. */
    BUSY
}
