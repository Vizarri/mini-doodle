package com.src.minidoodle.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.List;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Effective availability clipped to a requested window and merged into nonoverlapping intervals.
 * @param from inclusive query boundary in UTC
 * @param to exclusive query boundary in UTC
 * @param free free intervals, intersected across users for a shared query; never null
 * @param busy declared busy time and active reservations, unioned across users; never null
 */
@Builder
@Schema(description = "Merged intervals clipped to the query window. Undeclared time is omitted from both lists.")
public record AvailabilityResponse(
        @Schema(description = "Inclusive query boundary in UTC", requiredMode = REQUIRED) Instant from,
        @Schema(description = "Exclusive query boundary in UTC", requiredMode = REQUIRED) Instant to,
        @Schema(description = "Effective free time; intersection for shared queries; may be empty", requiredMode = REQUIRED) List<TimeIntervalResponse> free,
        @Schema(description = "Declared BUSY slots and active reservations; union across users; may be empty", requiredMode = REQUIRED) List<TimeIntervalResponse> busy) {
}
