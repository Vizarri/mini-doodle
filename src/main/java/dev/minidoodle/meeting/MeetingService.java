package dev.minidoodle.meeting;

import dev.minidoodle.availability.IntervalOperations;
import dev.minidoodle.availability.TimeInterval;
import dev.minidoodle.common.ApiException;
import dev.minidoodle.common.CalendarLocks;
import dev.minidoodle.persistence.MeetingEntity;
import dev.minidoodle.persistence.MeetingParticipantEntity;
import dev.minidoodle.persistence.MeetingParticipantId;
import dev.minidoodle.persistence.MeetingParticipantRepository;
import dev.minidoodle.persistence.MeetingRepository;
import dev.minidoodle.persistence.MeetingReservationEntity;
import dev.minidoodle.persistence.MeetingReservationId;
import dev.minidoodle.persistence.MeetingReservationRepository;
import dev.minidoodle.persistence.TimeSlotEntity;
import dev.minidoodle.persistence.TimeSlotRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeetingService {
    private final MeetingRepository meetings;
    private final MeetingParticipantRepository participants;
    private final MeetingReservationRepository reservations;
    private final TimeSlotRepository slots;
    private final CalendarLocks calendarLocks;

    public MeetingService(MeetingRepository meetings, MeetingParticipantRepository participants,
                          MeetingReservationRepository reservations, TimeSlotRepository slots,
                          CalendarLocks calendarLocks) {
        this.meetings = meetings;
        this.participants = participants;
        this.reservations = reservations;
        this.slots = slots;
        this.calendarLocks = calendarLocks;
    }

    @Transactional
    public MeetingView create(MeetingController.CreateMeeting request) {
        Set<UUID> participantIds = new TreeSet<>(request.participantIds());
        participantIds.add(request.organizerId());
        if (participantIds.size() > 50) {
            throw ApiException.badRequest("A meeting may have at most 50 participants, including its organizer");
        }
        Map<UUID, UUID> calendars = calendarLocks.lockUsers(participantIds);
        TimeSlotEntity source = slots.findById(request.slotId())
                .orElseThrow(() -> ApiException.notFound("Source slot was not found"));
        if (!source.getCalendarId().equals(calendars.get(request.organizerId()))) {
            throw ApiException.notFound("Source slot was not found in the organizer's calendar");
        }
        if (!source.getStatus().equals("FREE")) {
            throw ApiException.conflict("The source slot must be free");
        }
        List<UUID> calendarIds = List.copyOf(calendars.values());
        requireFreeCoverage(calendarIds, source.getStartsAt(), source.getEndsAt());
        if (!reservations.findOverlapping(calendarIds, source.getStartsAt(), source.getEndsAt()).isEmpty()) {
            throw ApiException.conflict("At least one participant already has a meeting in this interval");
        }
        UUID meetingId = UUID.randomUUID();
        MeetingEntity meeting = new MeetingEntity();
        meeting.setId(meetingId);
        meeting.setOrganizerUserId(request.organizerId());
        meeting.setSourceSlotId(request.slotId());
        meeting.setTitle(request.title().trim());
        meeting.setDescription(request.description());
        meeting.setStartsAt(source.getStartsAt());
        meeting.setEndsAt(source.getEndsAt());
        meeting.setCreatedAt(Instant.now());
        meetings.saveAndFlush(meeting);
        List<MeetingParticipantEntity> memberships = new ArrayList<>();
        List<MeetingReservationEntity> bookings = new ArrayList<>();
        for (UUID userId : participantIds) {
            MeetingParticipantEntity participant = new MeetingParticipantEntity();
            participant.setId(new MeetingParticipantId(meetingId, userId));
            memberships.add(participant);
            MeetingReservationEntity reservation = new MeetingReservationEntity();
            reservation.setId(new MeetingReservationId(meetingId, calendars.get(userId)));
            reservation.setStartsAt(source.getStartsAt());
            reservation.setEndsAt(source.getEndsAt());
            bookings.add(reservation);
        }
        participants.saveAll(memberships);
        reservations.saveAll(bookings);
        return view(meeting, List.copyOf(participantIds));
    }

    private void requireFreeCoverage(List<UUID> calendarIds, Instant from, Instant to) {
        Map<UUID, List<TimeInterval>> freeByCalendar = new HashMap<>();
        calendarIds.forEach(id -> freeByCalendar.put(id, new ArrayList<>()));
        for (TimeSlotEntity slot : slots.findOverlapping(calendarIds, from, to)) {
            if (slot.getStatus().equals("FREE")) {
                freeByCalendar.get(slot.getCalendarId()).add(new TimeInterval(slot.getStartsAt(), slot.getEndsAt()));
            }
        }
        for (List<TimeInterval> free : freeByCalendar.values()) {
            boolean covered = IntervalOperations.merge(free).stream()
                    .anyMatch(interval -> !interval.startsAt().isAfter(from) && !interval.endsAt().isBefore(to));
            if (!covered) {
                throw ApiException.conflict("Every participant must have free time covering the entire meeting");
            }
        }
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public MeetingView get(UUID meetingId) {
        MeetingEntity meeting = meetings.findById(meetingId)
                .orElseThrow(() -> ApiException.notFound("Meeting was not found"));
        return view(meeting, participantIds(meetingId));
    }

    @Transactional
    public void cancel(UUID meetingId) {
        // Membership is immutable and retained after cancellation. Read only the IDs
        // before calendar locks so a meeting entity cannot become stale while waiting.
        List<UUID> participantIds = participantIds(meetingId);
        if (participantIds.isEmpty()) {
            throw ApiException.notFound("Meeting was not found");
        }
        calendarLocks.lockUsers(participantIds);
        MeetingEntity meeting = meetings.findLockedById(meetingId)
                .orElseThrow(() -> ApiException.notFound("Meeting was not found"));
        if (meeting.getCancelledAt() != null) {
            return;
        }
        reservations.deleteByIdMeetingId(meetingId);
        meeting.setCancelledAt(Instant.now());
        meetings.save(meeting);
    }

    private List<UUID> participantIds(UUID meetingId) {
        return participants.findByIdMeetingId(meetingId).stream()
                .map(participant -> participant.getId().getUserId()).sorted().toList();
    }

    private static MeetingView view(MeetingEntity meeting, List<UUID> participantIds) {
        return new MeetingView(meeting.getId(), meeting.getOrganizerUserId(), meeting.getSourceSlotId(),
                meeting.getTitle(), meeting.getDescription(), meeting.getStartsAt(), meeting.getEndsAt(),
                participantIds, meeting.getCancelledAt() == null ? "ACTIVE" : "CANCELLED");
    }
}
