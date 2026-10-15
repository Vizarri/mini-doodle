package com.src.minidoodle.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Public user identity without exposing the internal calendar entity.
 * @param id persistent user identifier
 * @param name normalized display name
 * @param email unique normalized lowercase email
 * @param timeZone validated presentation zone ID
 */
@Builder
@Schema(description = "User identity and presentation preferences.")
public record UserResponse(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(example = "Alice", requiredMode = REQUIRED) String name,
        @Schema(example = "alice@example.com", requiredMode = REQUIRED) String email,
        @Schema(example = "Europe/Berlin", requiredMode = REQUIRED) String timeZone) {
}
