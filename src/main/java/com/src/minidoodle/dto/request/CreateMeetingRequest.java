package com.src.minidoodle.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.util.List;
import java.util.UUID;

import static com.src.minidoodle.constant.ApiRequestLimits.MAX_MEETING_DESCRIPTION_LENGTH;
import static com.src.minidoodle.constant.ApiRequestLimits.MAX_MEETING_TITLE_LENGTH;
import static com.src.minidoodle.constant.ApiRequestLimits.MAX_PARTICIPANTS;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.NOT_REQUIRED;
import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Requests an atomic booking of the organizer's entire selected slot.
 * @param organizerId owner of the source slot, automatically added to the participant set
 * @param slotId free source slot defining the meeting boundaries
 * @param title nonblank title, trimmed before persistence
 * @param description optional details, preserved as supplied
 * @param participantIds additional users; duplicates are ignored and an empty list is allowed
 */
@Builder
@Schema(description = "Book the organizer's selected free slot atomically for at most 50 total participants.")
public record CreateMeetingRequest(
        @NotNull @Schema(description = "Organizer, included automatically", requiredMode = REQUIRED) UUID organizerId,
        @NotNull @Schema(description = "FREE slot owned by the organizer", requiredMode = REQUIRED) UUID slotId,
        @NotBlank @Size(max = MAX_MEETING_TITLE_LENGTH)
        @Schema(example = "Design review", requiredMode = REQUIRED) String title,
        @Size(max = MAX_MEETING_DESCRIPTION_LENGTH)
        @Schema(description = "Optional meeting details", nullable = true, requiredMode = NOT_REQUIRED) String description,
        @NotNull @Size(max = MAX_PARTICIPANTS)
        @Schema(description = "Additional participant IDs; empty is allowed; total including organizer cannot exceed 50", requiredMode = REQUIRED)
        List<@NotNull UUID> participantIds) {
}
