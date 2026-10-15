package com.src.minidoodle.enums;

/**
 * Stable machine-readable failure categories exposed in {@code ApiError.code}.
 * Clients use these values for branching; human-readable messages may change independently.
 */
public enum ApiErrorCode {
    BAD_REQUEST,
    VALIDATION_FAILED,
    MALFORMED_JSON,
    NOT_FOUND,
    USER_NOT_FOUND,
    SLOT_NOT_FOUND,
    MEETING_NOT_FOUND,
    EMAIL_ALREADY_EXISTS,
    SLOT_OVERLAP,
    SLOT_RESERVED,
    MEETING_CONFLICT,
    AVAILABILITY_REQUIRED,
    INVALID_TIME_RANGE,
    TIME_RANGE_TOO_LARGE,
    INVALID_TIME_ZONE,
    PARTICIPANT_LIMIT_EXCEEDED,
    CONCURRENT_MODIFICATION,
    INTERNAL_ERROR,
    CONFLICT
}
