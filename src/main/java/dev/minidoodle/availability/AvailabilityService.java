package dev.minidoodle.availability;

import dev.minidoodle.common.ApiException;
import dev.minidoodle.common.TimeRanges;
import dev.minidoodle.persistence.CalendarEntity;
import dev.minidoodle.persistence.CalendarRepository;
import dev.minidoodle.persistence.MeetingReservationEntity;
import dev.minidoodle.persistence.MeetingReservationRepository;
import dev.minidoodle.persistence.TimeSlotEntity;
import dev.minidoodle.persistence.TimeSlotRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AvailabilityService {
    private final CalendarRepository calendars;
    private final TimeSlotRepository slots;
    private final MeetingReservationRepository reservations;

    public AvailabilityService(CalendarRepository calendars, TimeSlotRepository slots,
                               MeetingReservationRepository reservations) {
        this.calendars = calendars;
        this.slots = slots;
        this.reservations = reservations;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AvailabilityView personal(UUID userId, Instant from, Instant to) {
        return calculate(List.of(userId), from, to);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public AvailabilityView common(List<UUID> userIds, Instant from, Instant to) {
        return calculate(userIds, from, to);
    }

    private AvailabilityView calculate(List<UUID> requestedUsers, Instant from, Instant to) {
        TimeRanges.validate(from, to);
        if (requestedUsers.isEmpty() || requestedUsers.size() > 50 || requestedUsers.stream().anyMatch(java.util.Objects::isNull)) {
            throw ApiException.badRequest("Specify between 1 and 50 user IDs");
        }
        Set<UUID> userIds = new LinkedHashSet<>(requestedUsers);
        List<UUID> calendarIds = calendars.findByUserIdIn(userIds).stream().map(CalendarEntity::getId).toList();
        if (calendarIds.size() != userIds.size()) {
            throw ApiException.notFound("At least one user was not found");
        }
        Map<UUID, List<TimeInterval>> freeByCalendar = new HashMap<>();
        Map<UUID, List<TimeInterval>> busyByCalendar = new HashMap<>();
        calendarIds.forEach(id -> {
            freeByCalendar.put(id, new ArrayList<>());
            busyByCalendar.put(id, new ArrayList<>());
        });
        for (TimeSlotEntity slot : slots.findOverlapping(calendarIds, from, to)) {
            (slot.getStatus().equals("FREE") ? freeByCalendar : busyByCalendar).get(slot.getCalendarId())
                    .add(clip(slot.getStartsAt(), slot.getEndsAt(), from, to));
        }
        for (MeetingReservationEntity reservation : reservations.findOverlapping(calendarIds, from, to)) {
            busyByCalendar.get(reservation.getId().getCalendarId())
                    .add(clip(reservation.getStartsAt(), reservation.getEndsAt(), from, to));
        }
        List<TimeInterval> commonFree = null;
        List<TimeInterval> allBusy = new ArrayList<>();
        for (UUID calendarId : calendarIds) {
            List<TimeInterval> busy = busyByCalendar.get(calendarId);
            List<TimeInterval> free = IntervalOperations.subtract(freeByCalendar.get(calendarId), busy);
            commonFree = commonFree == null ? free : IntervalOperations.intersect(commonFree, free);
            allBusy.addAll(busy);
        }
        return new AvailabilityView(from, to, List.copyOf(commonFree), IntervalOperations.merge(allBusy));
    }

    private static TimeInterval clip(Instant start, Instant end, Instant from, Instant to) {
        return new TimeInterval(start.isBefore(from) ? from : start, end.isAfter(to) ? to : end);
    }
}
