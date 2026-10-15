package com.src.minidoodle.dto.response;

import com.src.minidoodle.enums.SlotStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Stored slot definition; listing returns its complete interval without clipping.
 * @param id persistent slot identifier
 * @param startsAt inclusive declared boundary in UTC
 * @param endsAt exclusive declared boundary in UTC
 * @param status declared state, unchanged by active meeting reservations
 */
@Builder
@Schema(description = "Persisted declared slot. Active meeting reservations do not change its declared status.")
public record SlotResponse(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(description = "Inclusive start in UTC", requiredMode = REQUIRED) Instant startsAt,
        @Schema(description = "Exclusive end in UTC", requiredMode = REQUIRED) Instant endsAt,
        @Schema(requiredMode = REQUIRED) SlotStatus status) {
}
