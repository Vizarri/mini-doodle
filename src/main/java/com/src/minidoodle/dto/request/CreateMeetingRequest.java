package com.src.minidoodle.dto.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

import static com.src.minidoodle.constant.ApiRequestLimits.MAX_MEETING_TITLE_LENGTH;
import static com.src.minidoodle.constant.ApiRequestLimits.MAX_MEETING_DESCRIPTION_LENGTH;
import static com.src.minidoodle.constant.ApiRequestLimits.MAX_PARTICIPANTS;

@Schema(description = "Book the organizer's entire selected free slot atomically for all participants.")
public record CreateMeetingRequest(
        @NotNull @Schema(description = "Organizer, included automatically among participants") UUID organizerId,
        @NotNull @Schema(description = "A FREE slot owned by the organizer") UUID slotId,
        @NotBlank @Size(max = MAX_MEETING_TITLE_LENGTH)
        @Schema(example = "Design review") String title,
        @Size(max = MAX_MEETING_DESCRIPTION_LENGTH)
        @Schema(description = "Optional meeting details", example = "Review the scheduling API") String description,
        @NotNull @Size(max = MAX_PARTICIPANTS)
        @Schema(description = "Additional participants; duplicates are ignored. Empty is allowed. Maximum 50 including organizer.")
        List<@NotNull UUID> participantIds) {
}
