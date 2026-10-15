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
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

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

    @Transactional
    public MeetingResponse create(CreateMeetingRequest request) {
        List<UUID> participantIds = meetingValidationService.normalizeParticipants(request);
        Map<UUID, UUID> calendars = calendarLockService.lockUsers(participantIds);
        // Load mutable source state only after obtaining all coordination locks.
        TimeSlot source = timeSlotRepository.findById(request.slotId())
                .filter(slot -> slot.getCalendar().getId().equals(calendars.get(request.organizerId())))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.SLOT_NOT_FOUND,
                        "Source slot was not found in the organizer's calendar"));
        if (source.getStatus() != SlotStatus.FREE) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.MEETING_CONFLICT, "The source slot must be free");
        }
        meetingValidationService.validateAvailability(List.copyOf(calendars.values()), source.getStartsAt(), source.getEndsAt());
        Meeting meeting = new Meeting();
        meeting.setOrganizer(userRepository.getReferenceById(request.organizerId()));
        meeting.setSourceSlot(source);
        meeting.setTitle(request.title().strip());
        meeting.setDescription(request.description());
        meeting.setStartsAt(source.getStartsAt());
        meeting.setEndsAt(source.getEndsAt());
        meeting = meetingRepository.saveAndFlush(meeting);
        for (UUID userId : participantIds) {
            meetingParticipantRepository.insert(meeting.getId(), userId);
            meetingReservationRepository.insert(meeting.getId(), calendars.get(userId), source.getStartsAt(), source.getEndsAt());
        }
        return toResponse(meeting, participantIds);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MeetingResponse get(UUID meetingId) {
        Meeting meeting = meetingRepository.findById(meetingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.MEETING_NOT_FOUND, "Meeting not found"));
        return toResponse(meeting, getParticipantIds(meetingId));
    }

    @Transactional
    public void cancel(UUID meetingId) {
        // Immutable membership is safe to read before locking; do not preload mutable meeting state.
        List<UUID> participantIds = getParticipantIds(meetingId);
        if (participantIds.isEmpty()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.MEETING_NOT_FOUND, "Meeting not found");
        }
        calendarLockService.lockUsers(participantIds);
        Meeting meeting = meetingRepository.findLockedById(meetingId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.MEETING_NOT_FOUND, "Meeting not found"));
        if (meeting.getCancelledAt() != null) return;
        meetingReservationRepository.deleteByIdMeetingId(meetingId);
        meeting.setCancelledAt(Instant.now());
    }

    private List<UUID> getParticipantIds(UUID meetingId) {
        return meetingParticipantRepository.findByIdMeetingId(meetingId).stream()
                .map(participant -> participant.getId().getUserId()).sorted().toList();
    }

    private MeetingResponse toResponse(Meeting meeting, List<UUID> participantIds) {
        return new MeetingResponse(meeting.getId(), meeting.getOrganizer().getId(),
                meeting.getSourceSlot() == null ? null : meeting.getSourceSlot().getId(),
                meeting.getTitle(), meeting.getDescription(), meeting.getStartsAt(), meeting.getEndsAt(), participantIds,
                meeting.getCancelledAt() == null ? MeetingStatus.ACTIVE : MeetingStatus.CANCELLED);
    }
}
