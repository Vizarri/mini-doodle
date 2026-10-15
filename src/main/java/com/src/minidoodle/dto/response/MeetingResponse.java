package com.src.minidoodle.dto.response;

import com.src.minidoodle.enums.MeetingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Schema(description = "Meeting details and membership retained after cancellation.")
public record MeetingResponse(
        UUID id,
        UUID organizerId,
        @Schema(description = "Original slot; null if it was deleted after cancellation", nullable = true) UUID slotId,
        String title,
        String description,
        Instant startsAt,
        Instant endsAt,
        @Schema(description = "Includes the organizer") List<UUID> participantIds,
        MeetingStatus status) {
}
