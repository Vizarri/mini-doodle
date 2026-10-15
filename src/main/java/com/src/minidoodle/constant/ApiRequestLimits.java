package com.src.minidoodle.constant;

/**
 * Request bounds shared by DTO constraints, controller parameters and service validation.
 * Window and participant limits bound the work performed by availability and booking requests.
 */
public final class ApiRequestLimits {
    /** Maximum elapsed duration of a declared slot or query window. */
    public static final int MAX_RANGE_DAYS = 31;
    /** Bounds submitted user IDs and distinct meeting participants, including the organizer. */
    public static final int MAX_PARTICIPANTS = 50;
    public static final int DEFAULT_SLOT_LIMIT = 100;
    public static final int MAX_SLOT_LIMIT = 500;
    /** Caps rows skipped by offset pagination to prevent unbounded database scanning. */
    public static final int MAX_SLOT_OFFSET = 100000;
    public static final int MAX_USER_NAME_LENGTH = 100;
    public static final int MAX_EMAIL_LENGTH = 254;
    public static final int MAX_TIME_ZONE_LENGTH = 100;
    public static final int MAX_MEETING_TITLE_LENGTH = 200;
    public static final int MAX_MEETING_DESCRIPTION_LENGTH = 4000;

    private ApiRequestLimits() {}
}
