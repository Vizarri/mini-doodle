package dev.minidoodle.slot;

import dev.minidoodle.common.ApiException;
import dev.minidoodle.common.CalendarLocks;
import dev.minidoodle.common.TimeRanges;
import dev.minidoodle.persistence.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class SlotService {
    private final CalendarLocks locks;
    private final CalendarRepository calendars;
    private final TimeSlotRepository slots;
    private final MeetingReservationRepository reservations;

    public SlotService(CalendarLocks locks, CalendarRepository calendars, TimeSlotRepository slots,
                       MeetingReservationRepository reservations) {
        this.locks = locks;
        this.calendars = calendars;
        this.slots = slots;
        this.reservations = reservations;
    }

    @Transactional
    public SlotController.SlotResponse create(UUID userId, SlotController.SlotRequest request) {
        TimeRanges.validate(request.startsAt().toInstant(), request.endsAt().toInstant());
        UUID calendarId = locks.lockUsers(List.of(userId)).get(userId);
        TimeSlotEntity slot = new TimeSlotEntity();
        slot.setId(UUID.randomUUID());
        slot.setCalendarId(calendarId);
        apply(slot, request);
        rejectOverlaps(slot);
        return response(slots.saveAndFlush(slot));
    }

    @Transactional(readOnly = true)
    public List<SlotController.SlotResponse> list(UUID userId, Instant from, Instant to, int limit, int offset) {
        TimeRanges.validate(from, to);
        UUID calendarId = calendars.findByUserId(userId)
                .orElseThrow(() -> ApiException.notFound("User not found")).getId();
        return slots.findWindow(calendarId, from, to, limit, offset).stream().map(this::response).toList();
    }

    @Transactional
    public SlotController.SlotResponse update(UUID userId, UUID slotId, SlotController.SlotRequest request) {
        TimeRanges.validate(request.startsAt().toInstant(), request.endsAt().toInstant());
        UUID calendarId = locks.lockUsers(List.of(userId)).get(userId);
        TimeSlotEntity slot = ownedSlot(calendarId, slotId);
        rejectReserved(slot);
        // Validate the candidate before modifying the managed entity (queries can auto-flush).
        TimeSlotEntity candidate = new TimeSlotEntity();
        candidate.setId(slot.getId());
        candidate.setCalendarId(calendarId);
        apply(candidate, request);
        rejectOverlaps(candidate);
        apply(slot, request);
        return response(slots.saveAndFlush(slot));
    }

    @Transactional
    public void delete(UUID userId, UUID slotId) {
        UUID calendarId = locks.lockUsers(List.of(userId)).get(userId);
        TimeSlotEntity slot = ownedSlot(calendarId, slotId);
        rejectReserved(slot);
        slots.delete(slot);
        slots.flush();
    }

    private TimeSlotEntity ownedSlot(UUID calendarId, UUID slotId) {
        return slots.findById(slotId).filter(slot -> slot.getCalendarId().equals(calendarId))
                .orElseThrow(() -> ApiException.notFound("Slot not found for this user"));
    }

    private void rejectReserved(TimeSlotEntity slot) {
        if (!reservations.findOverlapping(List.of(slot.getCalendarId()), slot.getStartsAt(), slot.getEndsAt()).isEmpty()) {
            throw ApiException.conflict("Cancel the meeting before modifying or deleting its covering slot");
        }
    }

    private void rejectOverlaps(TimeSlotEntity candidate) {
        boolean overlap = slots.findOverlapping(List.of(candidate.getCalendarId()), candidate.getStartsAt(), candidate.getEndsAt())
                .stream().anyMatch(slot -> !slot.getId().equals(candidate.getId()));
        if (overlap) throw ApiException.conflict("Slots in the same calendar cannot overlap");
    }

    private void apply(TimeSlotEntity slot, SlotController.SlotRequest request) {
        slot.setStartsAt(request.startsAt().toInstant());
        slot.setEndsAt(request.endsAt().toInstant());
        slot.setStatus(request.status().name());
    }

    private SlotController.SlotResponse response(TimeSlotEntity slot) {
        return new SlotController.SlotResponse(slot.getId(), slot.getStartsAt(), slot.getEndsAt(),
                SlotController.Status.valueOf(slot.getStatus()));
    }
}
