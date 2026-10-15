package com.src.minidoodle.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import static com.src.minidoodle.constant.ApiRequestLimits.MAX_USER_NAME_LENGTH;
import static com.src.minidoodle.constant.ApiRequestLimits.MAX_EMAIL_LENGTH;
import static com.src.minidoodle.constant.ApiRequestLimits.MAX_TIME_ZONE_LENGTH;

@Schema(description = "Create a user and their internal calendar.")
public record CreateUserRequest(
        @NotBlank @Size(max = MAX_USER_NAME_LENGTH)
        @Schema(description = "Display name", example = "Alice") String name,
        @NotBlank @Email @Size(max = MAX_EMAIL_LENGTH)
        @Schema(description = "Unique email, normalized to lowercase", example = "alice@example.com") String email,
        @NotBlank @Size(max = MAX_TIME_ZONE_LENGTH)
        @Schema(description = "Java ZoneId for client presentation", example = "Europe/Berlin") String timeZone) {
}
