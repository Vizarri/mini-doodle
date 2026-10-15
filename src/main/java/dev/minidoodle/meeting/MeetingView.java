package dev.minidoodle.meeting;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MeetingView(
        UUID id,
        UUID organizerId,
        UUID slotId,
        String title,
        String description,
        Instant startsAt,
        Instant endsAt,
        List<UUID> participantIds,
        String status) {
}
