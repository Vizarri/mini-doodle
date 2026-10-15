package dev.minidoodle.common;

import dev.minidoodle.persistence.CalendarEntity;
import dev.minidoodle.persistence.CalendarRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Component
public class CalendarLocks {
    private final CalendarRepository calendars;

    public CalendarLocks(CalendarRepository calendars) { this.calendars = calendars; }

    /** All scheduling writes acquire these locks before reading slots or reservations. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, UUID> lockUsers(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty() || userIds.stream().anyMatch(id -> id == null)) {
            throw ApiException.badRequest("At least one valid user ID is required");
        }
        var distinct = userIds.stream().distinct().toList();
        if (distinct.size() > 50) throw ApiException.badRequest("At most 50 users are allowed");
        var locked = calendars.lockByUserIds(distinct);
        if (locked.size() != distinct.size()) throw ApiException.notFound("One or more users do not exist");
        Map<UUID, UUID> result = new LinkedHashMap<>();
        for (CalendarEntity calendar : locked) result.put(calendar.getUserId(), calendar.getId());
        return result;
    }
}
