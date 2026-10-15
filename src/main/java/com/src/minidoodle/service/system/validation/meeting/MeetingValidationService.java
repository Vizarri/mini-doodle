package com.src.minidoodle.service.system.validation.meeting;

import com.src.minidoodle.db.model.TimeSlot;
import com.src.minidoodle.db.repository.MeetingReservationRepository;
import com.src.minidoodle.db.repository.TimeSlotRepository;
import com.src.minidoodle.dto.request.CreateMeetingRequest;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.enums.SlotStatus;
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

@Service
@RequiredArgsConstructor
public class MeetingValidationService {
    private final TimeSlotRepository timeSlotRepository;
    private final MeetingReservationRepository meetingReservationRepository;

    public List<UUID> normalizeParticipants(CreateMeetingRequest request) {
        if (request.organizerId() == null || request.participantIds() == null
                || request.participantIds().stream().anyMatch(Objects::isNull)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, "Valid participant IDs are required");
        }
        var participants = new TreeSet<>(request.participantIds());
        participants.add(request.organizerId());
        if (request.participantIds().size() > MAX_PARTICIPANTS || participants.size() > MAX_PARTICIPANTS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.PARTICIPANT_LIMIT_EXCEEDED,
                    "A meeting may have at most 50 participants, including its organizer");
        }
        return List.copyOf(participants);
    }

    /** The caller holds all participant calendar locks before checking mutable availability. */
    public void validateAvailability(List<UUID> calendarIds, Instant from, Instant to) {
        Map<UUID, List<TimeInterval>> freeByCalendar = new HashMap<>();
        calendarIds.forEach(id -> freeByCalendar.put(id, new ArrayList<>()));
        for (TimeSlot slot : timeSlotRepository.findOverlapping(calendarIds, from, to)) {
            if (slot.getStatus() == SlotStatus.FREE) {
                freeByCalendar.get(slot.getCalendar().getId()).add(new TimeInterval(slot.getStartsAt(), slot.getEndsAt()));
            }
        }
        for (List<TimeInterval> free : freeByCalendar.values()) {
            boolean covered = TimeIntervalUtils.merge(free).stream()
                    .anyMatch(interval -> !interval.startsAt().isAfter(from) && !interval.endsAt().isBefore(to));
            if (!covered) {
                throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.AVAILABILITY_REQUIRED,
                        "Every participant must have free time covering the entire meeting");
            }
        }
        if (!meetingReservationRepository.findOverlapping(calendarIds, from, to).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.MEETING_CONFLICT,
                    "At least one participant already has a meeting in this interval");
        }
    }
}
