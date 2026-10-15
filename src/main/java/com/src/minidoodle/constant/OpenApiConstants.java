package com.src.minidoodle.constant;

/** Shared OpenAPI response codes and descriptions. */
public final class OpenApiConstants {
    public static final String HTTP_200 = "200";
    public static final String HTTP_201 = "201";
    public static final String HTTP_204 = "204";
    public static final String HTTP_400 = "400";
    public static final String HTTP_404 = "404";
    public static final String HTTP_409 = "409";
    public static final String HTTP_500 = "500";
    public static final String DESC_INVALID_DATA = "Invalid request parameters or body";
    public static final String DESC_NOT_FOUND = "Requested resource was not found";
    public static final String DESC_CONFLICT = "The change conflicts with existing data or a concurrent change";
    public static final String DESC_INTERNAL_ERROR = "Unexpected server error";

    private OpenApiConstants() {}
}
