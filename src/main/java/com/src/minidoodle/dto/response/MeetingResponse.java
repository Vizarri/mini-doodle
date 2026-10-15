package com.src.minidoodle.dto.response;

import com.src.minidoodle.enums.MeetingStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Meeting details and membership, also available after cancellation.
 * @param id persistent meeting identifier
 * @param organizerId organizer included in the participant list
 * @param slotId original source slot, or null if deleted after cancellation
 * @param title stored meeting title
 * @param description optional details, returned as null when omitted
 * @param startsAt inclusive meeting boundary in UTC
 * @param endsAt exclusive meeting boundary in UTC
 * @param participantIds distinct participating users, including the organizer
 * @param status whether reservations are still active or have been cancelled
 */
@Builder
@Schema(description = "Meeting details and membership retained after cancellation.")
public record MeetingResponse(
        @Schema(requiredMode = REQUIRED) UUID id,
        @Schema(description = "Organizer, also present in participantIds", requiredMode = REQUIRED) UUID organizerId,
        @Schema(description = "Original slot; null after its deletion following cancellation", types = {"string", "null"}, format = "uuid", requiredMode = REQUIRED) UUID slotId,
        @Schema(requiredMode = REQUIRED) String title,
        @Schema(description = "Optional details; null when omitted", types = {"string", "null"}, requiredMode = REQUIRED) String description,
        @Schema(description = "Inclusive start in UTC", requiredMode = REQUIRED) Instant startsAt,
        @Schema(description = "Exclusive end in UTC", requiredMode = REQUIRED) Instant endsAt,
        @Schema(description = "Distinct participant IDs including the organizer", requiredMode = REQUIRED) List<UUID> participantIds,
        @Schema(requiredMode = REQUIRED) MeetingStatus status) {
}
