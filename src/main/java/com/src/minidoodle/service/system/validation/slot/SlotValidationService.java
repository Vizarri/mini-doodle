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

@Service
@RequiredArgsConstructor
public class SlotValidationService {
    private final TimeRangeValidationService timeRangeValidationService;
    private final TimeSlotRepository timeSlotRepository;
    private final MeetingReservationRepository meetingReservationRepository;

    public ValidatedSlot validateAndNormalize(SlotUpsertRequest request) {
        Instant startsAt = request.startsAt().toInstant();
        Instant endsAt = request.endsAt().toInstant();
        timeRangeValidationService.validate(startsAt, endsAt);
        return new ValidatedSlot(startsAt, endsAt, request.status());
    }

    public void validateQuery(Instant from, Instant to, int limit, int offset) {
        timeRangeValidationService.validate(from, to);
        if (limit < 1 || limit > MAX_SLOT_LIMIT || offset < 0 || offset > MAX_SLOT_OFFSET) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, "Invalid pagination limits");
        }
    }

    /** Must be called after acquiring the owning calendar's coordination lock. */
    public void validateNotReserved(TimeSlot slot) {
        if (!meetingReservationRepository.findOverlapping(List.of(slot.getCalendar().getId()),
                slot.getStartsAt(), slot.getEndsAt()).isEmpty()) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.SLOT_RESERVED,
                    "Cancel the meeting before modifying or deleting its covering slot");
        }
    }

    /** Validate before mutating a managed entity: repository queries may auto-flush it. */
    public void validateNoOverlap(UUID calendarId, UUID excludedSlotId, ValidatedSlot candidate) {
        boolean overlap = timeSlotRepository.findOverlapping(List.of(calendarId), candidate.startsAt(), candidate.endsAt())
                .stream().anyMatch(slot -> !Objects.equals(slot.getId(), excludedSlotId));
        if (overlap) {
            throw new ApiException(HttpStatus.CONFLICT, ApiErrorCode.SLOT_OVERLAP,
                    "Slots in the same calendar cannot overlap");
        }
    }
}
