package com.src.minidoodle.service.system.validation.slot;

import com.src.minidoodle.db.model.TimeSlot;
import com.src.minidoodle.db.repository.MeetingReservationRepository;
import com.src.minidoodle.db.repository.TimeSlotRepository;
import com.src.minidoodle.dto.request.SlotUpsertRequest;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.service.system.validation.TimeRangeValidationService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static com.src.minidoodle.constant.ApiRequestLimits.MAX_SLOT_LIMIT;
import static com.src.minidoodle.constant.ApiRequestLimits.MAX_SLOT_OFFSET;

/**
 * Validates slot input and reservation invariants without mutating managed entities.
 * Database-dependent guards must run inside the caller's transaction after the calendar lock is acquired.
 */
@Service
@RequiredArgsConstructor
public class SlotValidationService {
    private final TimeRangeValidationService timeRangeValidationService;
    private final TimeSlotRepository timeSlotRepository;
    private final MeetingReservationRepository meetingReservationRepository;

    /**
     * Parses explicit-offset timestamps and validates the resulting absolute interval before persistence.
     *
     * @param request complete slot definition that has passed DTO shape validation
     * @return UTC boundaries and declared status ready for entity creation or update
     * @throws ApiException if timestamp syntax, range, duration, or precision is unsupported
     */
    public ValidatedSlot validateAndNormalize(SlotUpsertRequest request) {
        Instant startsAt = timeRangeValidationService.parseTimestamp(request.startsAt());
        Instant endsAt = timeRangeValidationService.parseTimestamp(request.endsAt());
        timeRangeValidationService.validate(startsAt, endsAt);
        return ValidatedSlot.builder().startsAt(startsAt).endsAt(endsAt).status(request.status()).build();
    }

    /**
     * Enforces both time-window and database pagination limits without silently clamping client values.
     *
     * @param from inclusive query boundary
     * @param to exclusive query boundary
     * @param limit requested maximum page size
     * @param offset requested number of ordered rows to skip
     * @throws ApiException if the interval or pagination limits are invalid
     */
    public void validateQuery(Instant from, Instant to, int limit, int offset) {
        timeRangeValidationService.validate(from, to);
        if (limit < 1 || limit > MAX_SLOT_LIMIT || offset < 0 || offset > MAX_SLOT_OFFSET) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, "Invalid pagination limits");
        }
    }

    /**
     * Rejects mutation of any slot intersecting an active reservation, including a participant's longer slot.
     * The caller must hold the owning calendar lock and supply current slot boundaries.
     *
     * @param slot refreshed slot whose declared interval would be changed or deleted
     * @throws ApiException if an active meeting reserves any part of the slot
     */
    public void validateNotReserved(TimeSlot slot) {
        if (!meetingReservationRepository.findOverlapping(List.of(slot.getCalendar().getId()), slot.getStartsAt(), slot.getEndsAt()).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.SLOT_RESERVED, "Cancel the meeting before modifying or deleting its covering slot");
        }
    }

    /**
     * Checks a proposed interval against the calendar's other slots while allowing touching boundaries.
     * The caller must hold the calendar lock and invoke this guard before changing a managed entity,
     * because repository queries may automatically flush pending changes.
     *
     * @param calendarId calendar whose coordination lock is held
     * @param excludedSlotId slot being updated, or {@code null} when creating a new slot
     * @param candidate validated proposed boundaries and status
     * @throws ApiException if another slot overlaps the candidate interval
     */
    public void validateNoOverlap(UUID calendarId, UUID excludedSlotId, ValidatedSlot candidate) {
        boolean overlap = timeSlotRepository.findOverlapping(List.of(calendarId), candidate.startsAt(), candidate.endsAt()).stream().anyMatch(slot -> !Objects.equals(slot.getId(), excludedSlotId));
        if (overlap) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.SLOT_OVERLAP, "Slots in the same calendar cannot overlap");
        }
    }
}
