package com.src.minidoodle.dto.response;

import com.src.minidoodle.enums.SlotStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.UUID;

@Schema(description = "Persisted declared slot. Active meeting reservations do not change its declared status.")
public record SlotResponse(UUID id, Instant startsAt, Instant endsAt, SlotStatus status) {
}
