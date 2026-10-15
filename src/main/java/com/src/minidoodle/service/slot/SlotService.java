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
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Manages declared availability while preserving active meeting reservations.
 * Mutations acquire the owning calendar's coordination lock before reading mutable slot state.
 */
@Service
@RequiredArgsConstructor
public class SlotService {
    private final CalendarLockService calendarLockService;
    private final CalendarRepository calendarRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final SlotValidationService slotValidationService;
    private final EntityManager entityManager;

    /**
     * Declares a non-overlapping interval in the user's calendar. Adjacent intervals are allowed.
     *
     * @param userId owner of the new slot
     * @param request complete slot definition that has passed request-shape validation
     * @return persisted slot with its generated identifier
     * @throws ApiException if the user is missing, the interval is invalid, or another slot overlaps
     */
    @Transactional
    public SlotResponse create(UUID userId, SlotUpsertRequest request) {
        ValidatedSlot validated = slotValidationService.validateAndNormalize(request);
        UUID calendarId = calendarLockService.lockUsers(List.of(userId)).get(userId);
        slotValidationService.validateNoOverlap(calendarId, null, validated);
        TimeSlot slot = TimeSlot.builder()
                .calendar(calendarRepository.getReferenceById(calendarId))
                .startsAt(validated.startsAt())
                .endsAt(validated.endsAt())
                .status(validated.status())
                .build();
        return toResponse(timeSlotRepository.saveAndFlush(slot));
    }

    /**
     * Reads one database page of slots intersecting the half-open query window.
     * Returned intervals keep their stored boundaries; only availability queries clip them.
     *
     * @param userId calendar owner
     * @param from inclusive query boundary
     * @param to exclusive query boundary
     * @param limit maximum items to return
     * @param offset items to skip in start-time and identifier order
     * @return ordered declared slots, including their original status
     * @throws ApiException if the user, time range, or pagination parameters are invalid
     */
    @Transactional(readOnly = true)
    public List<SlotResponse> list(UUID userId, Instant from, Instant to, int limit, int offset) {
        slotValidationService.validateQuery(from, to, limit, offset);
        UUID calendarId = calendarRepository.findByUserId(userId).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.USER_NOT_FOUND, "User not found")).getId();
        return timeSlotRepository.findWindow(calendarId, from, to, limit, offset).stream().map(this::toResponse).toList();
    }

    /**
     * Replaces a slot's full definition after checking reservations and overlap under the calendar lock.
     * Validation precedes managed-entity changes because later queries may trigger an automatic flush.
     *
     * @param userId expected calendar owner
     * @param slotId slot to replace
     * @param request complete replacement definition
     * @return updated declared slot
     * @throws ApiException if ownership, interval, reservation, or overlap checks fail
     */
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

    /**
     * Deletes an unreserved slot. Historical cancelled meetings keep their details and lose the source reference.
     *
     * @param userId expected calendar owner
     * @param slotId slot to remove
     * @throws ApiException if the slot is missing, belongs to another user, or overlaps an active reservation
     */
    @Transactional
    public void delete(UUID userId, UUID slotId) {
        UUID calendarId = calendarLockService.lockUsers(List.of(userId)).get(userId);
        TimeSlot slot = getOwnedSlot(calendarId, slotId);
        slotValidationService.validateNotReserved(slot);
        timeSlotRepository.delete(slot);
        timeSlotRepository.flush();
    }

    /**
     * Reloads mutable state after the caller has locked the calendar. Refresh is necessary when an outer
     * transaction already holds an older managed copy; a repository lookup alone can reuse that copy.
     *
     * @param calendarId calendar whose coordination lock is held
     * @param slotId requested slot
     * @return refreshed managed slot belonging to that calendar
     * @throws ApiException if the slot was deleted or belongs to a different calendar
     */
    private TimeSlot getOwnedSlot(UUID calendarId, UUID slotId) {
        TimeSlot slot = timeSlotRepository.findById(slotId).orElseThrow(this::slotNotFound);
        // REQUIRED may join a transaction that loaded this slot before the calendar lock.
        try {
            entityManager.refresh(slot);
        } catch (EntityNotFoundException exception) {
            throw slotNotFound();
        }
        if (!slot.getCalendar().getId().equals(calendarId)) {
            throw slotNotFound();
        }
        return slot;
    }

    /**
     * Uses one error for missing and foreign slots so ownership failures share the public lookup contract.
     *
     * @return domain exception representing an unsuccessful slot lookup
     */
    private ApiException slotNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.SLOT_NOT_FOUND, "Slot not found for this user");
    }

    /**
     * Applies validated fields to the managed entity after reservation and overlap guards have passed.
     *
     * @param slot managed slot to update
     * @param validated normalized replacement values
     */
    private void apply(TimeSlot slot, ValidatedSlot validated) {
        slot.setStartsAt(validated.startsAt());
        slot.setEndsAt(validated.endsAt());
        slot.setStatus(validated.status());
    }

    /**
     * Maps declared availability without incorporating meeting reservations.
     *
     * @param slot persisted slot
     * @return declared slot representation
     */
    private SlotResponse toResponse(TimeSlot slot) {
        return SlotResponse.builder().id(slot.getId()).startsAt(slot.getStartsAt()).endsAt(slot.getEndsAt()).status(slot.getStatus()).build();
    }
}
