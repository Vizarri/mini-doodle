package com.src.minidoodle.service.availability;

import com.src.minidoodle.db.model.Calendar;
import com.src.minidoodle.db.model.MeetingReservation;
import com.src.minidoodle.db.model.TimeSlot;
import com.src.minidoodle.db.repository.CalendarRepository;
import com.src.minidoodle.db.repository.MeetingReservationRepository;
import com.src.minidoodle.db.repository.TimeSlotRepository;
import com.src.minidoodle.dto.response.AvailabilityResponse;
import com.src.minidoodle.dto.response.TimeIntervalResponse;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.enums.SlotStatus;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.service.availability.model.TimeInterval;
import com.src.minidoodle.service.system.validation.availability.AvailabilityQueryValidationService;
import com.src.minidoodle.util.TimeIntervalUtils;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Computes effective availability from declared slots and active reservations using three bulk reads.
 * Repeatable-read entry points keep those reads coherent; undeclared time is never inferred to be free.
 */
@Service
@RequiredArgsConstructor
public class AvailabilityService {
    private final CalendarRepository calendarRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final MeetingReservationRepository meetingReservationRepository;
    private final AvailabilityQueryValidationService availabilityQueryValidationService;

    /**
     * Subtracts meeting reservations from one user's declared free time and merges their busy intervals.
     *
     * @param userId calendar owner
     * @param from inclusive query boundary
     * @param to exclusive query boundary
     * @return effective free and busy intervals clipped to the requested window
     * @throws ApiException if the user is missing or the query window is invalid
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AvailabilityResponse personal(UUID userId, Instant from, Instant to) {
        return calculate(List.of(userId), from, to);
    }

    /**
     * Intersects effective free time and unions busy time across the selected users.
     * A period undeclared by any participant cannot become common free time.
     *
     * @param userIds users whose availability should be combined; duplicates are ignored
     * @param from inclusive query boundary
     * @param to exclusive query boundary
     * @return merged shared availability within the query window
     * @throws ApiException if users are missing or the user count or window is invalid
     */
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AvailabilityResponse common(List<UUID> userIds, Instant from, Instant to) {
        return calculate(userIds, from, to);
    }

    /**
     * Loads calendar IDs, overlapping slots and reservations once each, then evaluates intervals in memory.
     * The public transactional entry point supplies a coherent snapshot for all three reads.
     *
     * @param requestedUsers user identifiers before deduplication
     * @param from inclusive window boundary
     * @param to exclusive window boundary
     * @return clipped and merged interval sets; neither set includes undeclared time
     * @throws ApiException if query validation or user resolution fails
     */
    private AvailabilityResponse calculate(List<UUID> requestedUsers, Instant from, Instant to) {
        List<UUID> userIds = availabilityQueryValidationService.validateAndNormalize(requestedUsers, from, to);
        List<UUID> calendarIds = calendarRepository.findByUserIdIn(userIds).stream().map(Calendar::getId).toList();
        if (calendarIds.size() != userIds.size()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.USER_NOT_FOUND, "At least one user was not found");
        }
        Map<UUID, List<TimeInterval>> freeByCalendar = new HashMap<>();
        Map<UUID, List<TimeInterval>> busyByCalendar = new HashMap<>();
        calendarIds.forEach(id -> {
            freeByCalendar.put(id, new ArrayList<>());
            busyByCalendar.put(id, new ArrayList<>());
        });
        for (TimeSlot slot : timeSlotRepository.findOverlapping(calendarIds, from, to)) {
            (slot.getStatus() == SlotStatus.FREE ? freeByCalendar : busyByCalendar).get(slot.getCalendar().getId()).add(clip(slot.getStartsAt(), slot.getEndsAt(), from, to));
        }
        for (MeetingReservation reservation : meetingReservationRepository.findOverlapping(calendarIds, from, to)) {
            busyByCalendar.get(reservation.getId().getCalendarId()).add(clip(reservation.getStartsAt(), reservation.getEndsAt(), from, to));
        }
        List<TimeInterval> commonFree = null;
        List<TimeInterval> allBusy = new ArrayList<>();
        for (UUID calendarId : calendarIds) {
            List<TimeInterval> busy = busyByCalendar.get(calendarId);
            List<TimeInterval> free = TimeIntervalUtils.subtract(freeByCalendar.get(calendarId), busy);
            commonFree = commonFree == null ? free : TimeIntervalUtils.intersect(commonFree, free);
            allBusy.addAll(busy);
        }
        return AvailabilityResponse.builder()
                .from(from)
                .to(to)
                .free(toIntervals(commonFree))
                .busy(toIntervals(TimeIntervalUtils.merge(allBusy)))
                .build();
    }

    /**
     * Separates immutable interval arithmetic values from the external response contract.
     *
     * @param intervals sorted domain intervals
     * @return API interval records in the same order
     */
    private List<TimeIntervalResponse> toIntervals(List<TimeInterval> intervals) {
        return intervals.stream().map(interval -> TimeIntervalResponse.builder().startsAt(interval.startsAt()).endsAt(interval.endsAt()).build()).toList();
    }

    /**
     * Restricts an interval known to overlap the query window; repository predicates guarantee a nonempty result.
     *
     * @param start original inclusive start
     * @param end original exclusive end
     * @param from inclusive query boundary
     * @param to exclusive query boundary
     * @return intersection with the query window
     */
    private TimeInterval clip(Instant start, Instant end, Instant from, Instant to) {
        return TimeInterval.builder().startsAt(start.isBefore(from) ? from : start).endsAt(end.isAfter(to) ? to : end).build();
    }
}
