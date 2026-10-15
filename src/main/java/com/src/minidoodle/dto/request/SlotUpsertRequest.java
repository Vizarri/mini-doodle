package com.src.minidoodle.dto.request;

import com.src.minidoodle.enums.SlotStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Builder;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Complete slot definition used for creation and replacement through PATCH.
 * @param startsAt inclusive start with an explicit offset, normalized to UTC for validation
 * @param endsAt exclusive end after start; the interval cannot exceed 31 days
 * @param status declared availability before meeting reservations are applied
 */
@Builder
@Schema(description = "Complete half-open slot definition; both boundaries must fall in UTC years 0001 through 9999 at microsecond precision.")
public record SlotUpsertRequest(
        @NotBlank @Schema(type = "string", format = "date-time", description = "Inclusive start with an explicit offset", example = "2030-01-15T10:00:00Z", requiredMode = REQUIRED)
        String startsAt,
        @NotBlank @Schema(type = "string", format = "date-time", description = "Exclusive end with an explicit offset; maximum duration 31 days", example = "2030-01-15T11:00:00Z", requiredMode = REQUIRED)
        String endsAt,
        @NotNull @Schema(description = "FREE or BUSY as a JSON string", example = "FREE", requiredMode = REQUIRED) SlotStatus status) {
}
