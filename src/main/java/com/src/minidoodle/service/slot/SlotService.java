package com.src.minidoodle.service.slot;

import com.src.minidoodle.db.model.TimeSlot;
import com.src.minidoodle.db.repository.CalendarRepository;
import com.src.minidoodle.db.repository.TimeSlotRepository;
import com.src.minidoodle.dto.request.SlotUpsertRequest;
import com.src.minidoodle.dto.response.SlotResponse;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.service.system.CalendarLockService;
import com.src.minidoodle.service.system.validation.slot.SlotValidationService;
import com.src.minidoodle.service.system.validation.slot.ValidatedSlot;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SlotService {
    private final CalendarLockService calendarLockService;
    private final CalendarRepository calendarRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final SlotValidationService slotValidationService;

    @Transactional
    public SlotResponse create(UUID userId, SlotUpsertRequest request) {
        ValidatedSlot validated = slotValidationService.validateAndNormalize(request);
        UUID calendarId = calendarLockService.lockUsers(List.of(userId)).get(userId);
        slotValidationService.validateNoOverlap(calendarId, null, validated);
        TimeSlot slot = new TimeSlot();
        slot.setCalendar(calendarRepository.getReferenceById(calendarId));
        apply(slot, validated);
        return toResponse(timeSlotRepository.saveAndFlush(slot));
    }

    @Transactional(readOnly = true)
    public List<SlotResponse> list(UUID userId, Instant from, Instant to, int limit, int offset) {
        slotValidationService.validateQuery(from, to, limit, offset);
        UUID calendarId = calendarRepository.findByUserId(userId)
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.USER_NOT_FOUND, "User not found")).getId();
        return timeSlotRepository.findWindow(calendarId, from, to, limit, offset).stream().map(this::toResponse).toList();
    }

    @Transactional
    public SlotResponse update(UUID userId, UUID slotId, SlotUpsertRequest request) {
        ValidatedSlot validated = slotValidationService.validateAndNormalize(request);
        UUID calendarId = calendarLockService.lockUsers(List.of(userId)).get(userId);
        TimeSlot slot = getOwnedSlot(calendarId, slotId);
        slotValidationService.validateNotReserved(slot);
        slotValidationService.validateNoOverlap(calendarId, slotId, validated);
        apply(slot, validated);
        return toResponse(timeSlotRepository.saveAndFlush(slot));
    }

    @Transactional
    public void delete(UUID userId, UUID slotId) {
        UUID calendarId = calendarLockService.lockUsers(List.of(userId)).get(userId);
        TimeSlot slot = getOwnedSlot(calendarId, slotId);
        slotValidationService.validateNotReserved(slot);
        timeSlotRepository.delete(slot);
        timeSlotRepository.flush();
    }

    private TimeSlot getOwnedSlot(UUID calendarId, UUID slotId) {
        return timeSlotRepository.findById(slotId).filter(slot -> slot.getCalendar().getId().equals(calendarId))
                .orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.SLOT_NOT_FOUND,
                        "Slot not found for this user"));
    }

    private void apply(TimeSlot slot, ValidatedSlot validated) {
        slot.setStartsAt(validated.startsAt());
        slot.setEndsAt(validated.endsAt());
        slot.setStatus(validated.status());
    }

    private SlotResponse toResponse(TimeSlot slot) {
        return new SlotResponse(slot.getId(), slot.getStartsAt(), slot.getEndsAt(), slot.getStatus());
    }
}
