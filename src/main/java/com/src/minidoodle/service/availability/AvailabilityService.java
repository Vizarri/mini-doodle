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

@Service
@RequiredArgsConstructor
public class AvailabilityService {
    private final CalendarRepository calendarRepository;
    private final TimeSlotRepository timeSlotRepository;
    private final MeetingReservationRepository meetingReservationRepository;
    private final AvailabilityQueryValidationService availabilityQueryValidationService;

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AvailabilityResponse personal(UUID userId, Instant from, Instant to) {
        return calculate(List.of(userId), from, to);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AvailabilityResponse common(List<UUID> userIds, Instant from, Instant to) {
        return calculate(userIds, from, to);
    }

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
            (slot.getStatus() == SlotStatus.FREE ? freeByCalendar : busyByCalendar).get(slot.getCalendar().getId())
                    .add(clip(slot.getStartsAt(), slot.getEndsAt(), from, to));
        }
        for (MeetingReservation reservation : meetingReservationRepository.findOverlapping(calendarIds, from, to)) {
            busyByCalendar.get(reservation.getId().getCalendarId())
                    .add(clip(reservation.getStartsAt(), reservation.getEndsAt(), from, to));
        }
        List<TimeInterval> commonFree = null;
        List<TimeInterval> allBusy = new ArrayList<>();
        for (UUID calendarId : calendarIds) {
            List<TimeInterval> busy = busyByCalendar.get(calendarId);
            List<TimeInterval> free = TimeIntervalUtils.subtract(freeByCalendar.get(calendarId), busy);
            commonFree = commonFree == null ? free : TimeIntervalUtils.intersect(commonFree, free);
            allBusy.addAll(busy);
        }
        return new AvailabilityResponse(from, to, toIntervals(commonFree), toIntervals(TimeIntervalUtils.merge(allBusy)));
    }

    private List<TimeIntervalResponse> toIntervals(List<TimeInterval> intervals) {
        return intervals.stream().map(interval -> new TimeIntervalResponse(interval.startsAt(), interval.endsAt())).toList();
    }

    private TimeInterval clip(Instant start, Instant end, Instant from, Instant to) {
        return new TimeInterval(start.isBefore(from) ? from : start, end.isAfter(to) ? to : end);
    }
}
