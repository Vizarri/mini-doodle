package com.src.minidoodle.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;

@Schema(description = "Merged intervals clipped to the query window. Undeclared time is omitted from both lists.")
public record AvailabilityResponse(
        Instant from,
        Instant to,
        @Schema(description = "Effective free time; intersection across users for shared queries") List<TimeIntervalResponse> free,
        @Schema(description = "Explicit BUSY slots and active reservations; union across users") List<TimeIntervalResponse> busy) {
}
