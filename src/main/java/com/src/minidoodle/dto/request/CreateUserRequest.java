package com.src.minidoodle.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import static com.src.minidoodle.constant.ApiRequestLimits.MAX_EMAIL_LENGTH;
import static com.src.minidoodle.constant.ApiRequestLimits.MAX_TIME_ZONE_LENGTH;
import static com.src.minidoodle.constant.ApiRequestLimits.MAX_USER_NAME_LENGTH;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Creates a user together with the user's internal calendar.
 * @param name nonblank display name, trimmed before persistence
 * @param email unique email, trimmed and normalized to lowercase
 * @param timeZone valid Java zone ID used for presentation of absolute timestamps
 */
@Builder
@Schema(description = "Create a user and their internal calendar.")
public record CreateUserRequest(
        @NotBlank @Size(max = MAX_USER_NAME_LENGTH)
        @Schema(description = "Display name", example = "Alice", requiredMode = REQUIRED) String name,
        @NotBlank @Email @Size(max = MAX_EMAIL_LENGTH)
        @Schema(description = "Unique email, normalized to lowercase", example = "alice@example.com", requiredMode = REQUIRED) String email,
        @NotBlank @Size(max = MAX_TIME_ZONE_LENGTH)
        @Schema(description = "Java zone ID", example = "Europe/Berlin", requiredMode = REQUIRED) String timeZone) {
}
