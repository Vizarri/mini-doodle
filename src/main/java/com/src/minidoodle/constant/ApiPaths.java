package com.src.minidoodle.constant;

/**
 * Compile-time route fragments shared by controllers and resource Location headers.
 * Scheduling resources belong to users; calendars remain an internal domain concept.
 */
public final class ApiPaths {
    public static final String USERS = "/users";
    public static final String USER_ID = "/{userId}";
    public static final String USER_SLOTS = USERS + USER_ID + "/slots";
    public static final String SLOT_ID = "/{slotId}";
    public static final String USER_AVAILABILITY = USERS + USER_ID + "/availability";
    public static final String MEETINGS = "/meetings";
    public static final String MEETING_ID = "/{meetingId}";
    public static final String AVAILABILITY = "/availability";

    private ApiPaths() {}
}
