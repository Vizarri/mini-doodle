package com.src.minidoodle.service.system.validation.meeting;

import com.src.minidoodle.db.repository.MeetingReservationRepository;
import com.src.minidoodle.db.repository.TimeSlotRepository;
import com.src.minidoodle.db.repository.projection.SlotAvailabilityView;
import com.src.minidoodle.dto.request.CreateMeetingRequest;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.service.availability.model.TimeInterval;
import com.src.minidoodle.util.TimeIntervalUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

import static com.src.minidoodle.constant.ApiRequestLimits.MAX_PARTICIPANTS;

/**
 * Validates meeting membership and effective availability without creating persistence rows.
 * Coverage checks operate on scalar projections to avoid stale managed slots and per-slot refresh queries.
 */
@Service
@RequiredArgsConstructor
public class MeetingValidationService {
    private final TimeSlotRepository timeSlotRepository;
    private final MeetingReservationRepository meetingReservationRepository;

    /**
     * Includes the organizer, removes duplicate users and enforces both raw-input and final membership limits.
     * Sorting makes the response deterministic; database calendar ordering determines the actual lock order.
     *
     * @param request meeting input containing an organizer and a non-null participant array
     * @return immutable distinct participant IDs, including the organizer, in UUID order
     * @throws ApiException if identifiers are missing or the participant limit is exceeded
     */
    public List<UUID> normalizeParticipants(CreateMeetingRequest request) {
        if (request.organizerId() == null || request.participantIds() == null
                || request.participantIds().stream().anyMatch(Objects::isNull)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, "Valid participant IDs are required");
        }
        var participants = new TreeSet<>(request.participantIds());
        participants.add(request.organizerId());
        if (request.participantIds().size() > MAX_PARTICIPANTS || participants.size() > MAX_PARTICIPANTS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.PARTICIPANT_LIMIT_EXCEEDED, "A meeting may have at most 50 participants, including its organizer");
        }
        return List.copyOf(participants);
    }

    /**
     * Requires complete declared free coverage and no overlapping reservation for every participant.
     * Adjacent free slots are merged before the coverage check. The caller must hold all participant calendar
     * locks in an active write transaction; those locks keep the checked state stable until booking commits.
     *
     * @param calendarIds calendars already resolved and locked by the booking service
     * @param from inclusive meeting start from the validated source slot
     * @param to exclusive meeting end from the validated source slot
     * @throws ApiException if any participant lacks full coverage or already has an overlapping meeting
     */
    public void validateAvailability(List<UUID> calendarIds, Instant from, Instant to) {
        Map<UUID, List<TimeInterval>> freeByCalendar = new HashMap<>();
        calendarIds.forEach(id -> freeByCalendar.put(id, new ArrayList<>()));
        // Scalar projections bypass stale managed slots without one refresh query per interval.
        for (SlotAvailabilityView slot : timeSlotRepository.findFreeAvailability(calendarIds, from, to)) {
            freeByCalendar.get(slot.getCalendarId()).add(TimeInterval.builder().startsAt(slot.getStartsAt()).endsAt(slot.getEndsAt()).build());
        }
        for (List<TimeInterval> free : freeByCalendar.values()) {
            boolean covered = TimeIntervalUtils.merge(free).stream().anyMatch(interval -> !interval.startsAt().isAfter(from) && !interval.endsAt().isBefore(to));
            if (!covered) {
                throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.AVAILABILITY_REQUIRED, "Every participant must have free time covering the entire meeting");
            }
        }
        if (!meetingReservationRepository.findOverlapping(calendarIds, from, to).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.MEETING_CONFLICT, "At least one participant already has a meeting in this interval");
        }
    }
}
