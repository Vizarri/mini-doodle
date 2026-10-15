package com.src.minidoodle.constant;

/** Shared limits for request DTOs and service-level validation. */
public final class ApiRequestLimits {
    public static final int MAX_RANGE_DAYS = 31;
    public static final int MAX_PARTICIPANTS = 50;
    public static final int DEFAULT_SLOT_LIMIT = 100;
    public static final int MAX_SLOT_LIMIT = 500;
    public static final int MAX_SLOT_OFFSET = 100000;
    public static final int MAX_USER_NAME_LENGTH = 100;
    public static final int MAX_EMAIL_LENGTH = 254;
    public static final int MAX_TIME_ZONE_LENGTH = 100;
    public static final int MAX_MEETING_TITLE_LENGTH = 200;
    public static final int MAX_MEETING_DESCRIPTION_LENGTH = 4000;

    private ApiRequestLimits() {}
}
