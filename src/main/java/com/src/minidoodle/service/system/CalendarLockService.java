package com.src.minidoodle.service.system;

import com.src.minidoodle.db.model.Calendar;
import com.src.minidoodle.db.repository.CalendarRepository;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.exception.ApiException;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static com.src.minidoodle.constant.ApiRequestLimits.MAX_PARTICIPANTS;

/** All scheduling writers coordinate through the same ordered PostgreSQL row locks. */
@Service
@RequiredArgsConstructor
public class CalendarLockService {
    private final CalendarRepository calendarRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public Map<UUID, UUID> lockUsers(Collection<UUID> userIds) {
        if (userIds == null || userIds.isEmpty() || userIds.stream().anyMatch(Objects::isNull)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, "Valid user IDs are required");
        }
        var distinct = userIds.stream().distinct().toList();
        if (distinct.size() > MAX_PARTICIPANTS) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.PARTICIPANT_LIMIT_EXCEEDED, "At most 50 users are allowed");
        }
        var locked = calendarRepository.lockByUserIds(distinct);
        if (locked.size() != distinct.size()) {
            throw new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.USER_NOT_FOUND, "One or more users do not exist");
        }
        Map<UUID, UUID> result = new LinkedHashMap<>();
        for (Calendar calendar : locked) result.put(calendar.getUser().getId(), calendar.getId());
        return result;
    }
}
