package com.src.minidoodle.service.system.validation.availability;

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

import static com.src.minidoodle.constant.ApiRequestLimits.MAX_PARTICIPANTS;

/** Applies bounded query rules before availability services perform any database reads. */
@Service
@RequiredArgsConstructor
public class AvailabilityQueryValidationService {
    private final TimeRangeValidationService timeRangeValidationService;

    /**
     * Validates the query window and supplied ID count, then removes duplicate users while preserving order.
     * The input-count check runs before deduplication so repeated IDs cannot bypass the request-size limit.
     *
     * @param userIds requested user identifiers
     * @param from inclusive query boundary
     * @param to exclusive query boundary
     * @return immutable distinct user identifiers in encounter order
     * @throws ApiException if the range or user ID collection is invalid
     */
    public List<UUID> validateAndNormalize(List<UUID> userIds, Instant from, Instant to) {
        timeRangeValidationService.validate(from, to);
        if (userIds == null || userIds.isEmpty() || userIds.size() > MAX_PARTICIPANTS
                || userIds.stream().anyMatch(Objects::isNull)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, "Specify between 1 and 50 user IDs");
        }
        return userIds.stream().distinct().toList();
    }
}
