package com.src.minidoodle.service.meeting;

import com.src.minidoodle.db.model.Meeting;
import com.src.minidoodle.db.model.TimeSlot;
import com.src.minidoodle.db.repository.MeetingParticipantRepository;
import com.src.minidoodle.db.repository.MeetingRepository;
import com.src.minidoodle.db.repository.MeetingReservationRepository;
import com.src.minidoodle.db.repository.TimeSlotRepository;
import com.src.minidoodle.db.repository.UserRepository;
import com.src.minidoodle.dto.request.CreateMeetingRequest;
import com.src.minidoodle.dto.response.MeetingResponse;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.enums.MeetingStatus;
import com.src.minidoodle.enums.SlotStatus;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.service.system.CalendarLockService;
import com.src.minidoodle.service.system.validation.meeting.MeetingValidationService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Coordinates atomic booking and cancellation across participant calendars.
 * Calendar locks serialize schedule mutations and precede reads of mutable scheduling state.
 */
@Service
@RequiredArgsConstructor
public class MeetingService {
    private final MeetingRepository meetingRepository;
    private final MeetingParticipantRepository meetingParticipantRepository;
    private final MeetingReservationRepository meetingReservationRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final UserRepository userRepository;
    private final CalendarLockService calendarLockService;
    private final MeetingValidationService meetingValidationService;
    private final EntityManager entityManager;

    /**
     * Books the organizer's entire source slot after checking every participant under coordination locks.
     * The source is refreshed to discard stale managed state; participant coverage uses fresh scalar reads.
     * Meeting metadata, membership and reservations commit together or all roll back.
     *
     * @param request validated meeting details, source slot and participant identifiers
     * @return active meeting including its organizer in the deduplicated participant list
     * @throws ApiException if users or the source are missing, limits are exceeded, or availability conflicts
     */
    @Transactional
    public MeetingResponse create(CreateMeetingRequest request) {
        List<UUID> participantIds = meetingValidationService.normalizeParticipants(request);
        Map<UUID, UUID> calendars = calendarLockService.lockUsers(participantIds);
        TimeSlot source = timeSlotRepository.findById(request.slotId()).orElseThrow(this::sourceSlotNotFound);
        // REQUIRED may join an outer transaction with a source cached before acquiring the locks.
        try {
            entityManager.refresh(source);
        } catch (EntityNotFoundException exception) {
            throw sourceSlotNotFound();
        }
        if (!source.getCalendar().getId().equals(calendars.get(request.organizerId()))) {
            throw sourceSlotNotFound();
        }
        if (source.getStatus() != SlotStatus.FREE) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.MEETING_CONFLICT, "The source slot must be free");
        }
        meetingValidationService.validateAvailability(List.copyOf(calendars.values()), source.getStartsAt(), source.getEndsAt());
        Meeting meeting = Meeting.builder()
                .organizer(userRepository.getReferenceById(request.organizerId()))
                .sourceSlot(source)
                .title(request.title().strip())
                .description(request.description())
                .startsAt(source.getStartsAt())
                .endsAt(source.getEndsAt())
                .build();
        meeting = meetingRepository.saveAndFlush(meeting);
        for (UUID userId : participantIds) {
            meetingParticipantRepository.insert(meeting.getId(), userId);
            meetingReservationRepository.insert(meeting.getId(), calendars.get(userId), source.getStartsAt(), source.getEndsAt());
        }
        return toResponse(meeting, participantIds);
    }

    /**
     * Reads meeting metadata and retained membership from a repeatable-read snapshot.
     * Cancelled meetings remain readable even after their original slot is deleted.
     *
     * @param meetingId active or cancelled meeting identifier
     * @return meeting details and original participants
     * @throws ApiException if the meeting does not exist
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MeetingResponse get(UUID meetingId) {
        Meeting meeting = meetingRepository.findById(meetingId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.MEETING_NOT_FOUND, "Meeting not found"));
        return toResponse(meeting, getParticipantIds(meetingId));
    }

    /**
     * Releases only this meeting's reservations while preserving metadata and membership.
     * Immutable participant IDs may be read before locking; mutable meeting state is loaded and refreshed
     * after all calendars are locked. Repeating cancellation cannot release a newer meeting's reservations.
     *
     * @param meetingId meeting to cancel
     * @throws ApiException if the meeting does not exist
     */
    @Transactional
    public void cancel(UUID meetingId) {
        // Immutable membership is safe to read before locking; do not preload mutable meeting state.
        List<UUID> participantIds = getParticipantIds(meetingId);
        if (participantIds.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.MEETING_NOT_FOUND, "Meeting not found");
        }
        calendarLockService.lockUsers(participantIds);
        Meeting meeting = meetingRepository.findById(meetingId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.MEETING_NOT_FOUND, "Meeting not found"));
        // REQUIRED may join an outer transaction with a meeting cached before acquiring the locks.
        entityManager.refresh(meeting);
        if (meeting.getCancelledAt() != null) {
            return;
        }
        meetingReservationRepository.deleteByIdMeetingId(meetingId);
        meeting.setCancelledAt(Instant.now());
    }

    /**
     * Represents both a missing source and a source outside the organizer's calendar.
     *
     * @return domain exception with the public slot lookup error code
     */
    private ApiException sourceSlotNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.SLOT_NOT_FOUND, "Source slot was not found in the organizer's calendar");
    }

    /**
     * Reads immutable membership independently of active reservations, which disappear on cancellation.
     *
     * @param meetingId meeting identifier
     * @return participant IDs in deterministic UUID order, or an empty list for a missing meeting
     */
    private List<UUID> getParticipantIds(UUID meetingId) {
        return meetingParticipantRepository.findByIdMeetingId(meetingId).stream().map(participant -> participant.getId().getUserId()).sorted().toList();
    }

    /**
     * Maps scalar metadata and relationship identifiers without initializing related entity graphs.
     *
     * @param meeting persisted meeting
     * @param participantIds retained participant identifiers
     * @return public details with status derived from the cancellation timestamp
     */
    private MeetingResponse toResponse(Meeting meeting, List<UUID> participantIds) {
        return MeetingResponse.builder()
                .id(meeting.getId())
                .organizerId(meeting.getOrganizer().getId())
                .slotId(meeting.getSourceSlot() == null ? null : meeting.getSourceSlot().getId())
                .title(meeting.getTitle())
                .description(meeting.getDescription())
                .startsAt(meeting.getStartsAt())
                .endsAt(meeting.getEndsAt())
                .participantIds(participantIds)
                .status(meeting.getCancelledAt() == null ? MeetingStatus.ACTIVE : MeetingStatus.CANCELLED)
                .build();
    }
}
