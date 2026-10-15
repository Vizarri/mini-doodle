package com.src.minidoodle.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * One merged interval in an availability response.
 * @param startsAt inclusive boundary in UTC
 * @param endsAt exclusive boundary in UTC, strictly after startsAt
 */
@Builder
@Schema(description = "Half-open interval [startsAt, endsAt), expressed in UTC.")
public record TimeIntervalResponse(
        @Schema(description = "Inclusive start in UTC", requiredMode = REQUIRED) Instant startsAt,
        @Schema(description = "Exclusive end in UTC", requiredMode = REQUIRED) Instant endsAt) {
}
