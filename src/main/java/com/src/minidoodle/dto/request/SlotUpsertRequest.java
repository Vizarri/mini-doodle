package com.src.minidoodle.dto.request;

import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import com.src.minidoodle.config.jackson.StrictOffsetDateTimeDeserializer;
import com.src.minidoodle.enums.SlotStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

import java.time.OffsetDateTime;

@Schema(description = "Complete slot definition. Required for both creation and PATCH; interval is half-open.")
public record SlotUpsertRequest(
        @NotNull @JsonDeserialize(using = StrictOffsetDateTimeDeserializer.class)
        @Schema(description = "Inclusive start with an explicit UTC offset; UTC years 0001 through 9999; at most microsecond precision",
                example = "2030-01-15T10:00:00Z") OffsetDateTime startsAt,
        @NotNull @JsonDeserialize(using = StrictOffsetDateTimeDeserializer.class)
        @Schema(description = "Exclusive end after start; UTC years 0001 through 9999; maximum duration 31 days",
                example = "2030-01-15T11:00:00Z") OffsetDateTime endsAt,
        @NotNull @Schema(description = "FREE or BUSY as a JSON string; declared availability before subtracting meeting reservations", example = "FREE")
        SlotStatus status) {
}
