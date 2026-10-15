package com.src.minidoodle.service.system.validation.user;

import com.src.minidoodle.dto.request.CreateUserRequest;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;

/** Normalizes user identity after DTO shape validation and validates the presentation time zone. */
@Service
public class UserValidationService {
    /**
     * Strips surrounding name/email whitespace and normalizes email case independently of the server locale.
     * The time zone is validated as a Java {@link ZoneId}; it does not reinterpret explicitly offset slot times.
     *
     * @param request user fields that have passed Jakarta Bean Validation
     * @return immutable normalized values ready for persistence
     * @throws ApiException if the requested time zone cannot be resolved
     */
    public ValidatedUser validateAndNormalize(CreateUserRequest request) {
        try {
            ZoneId.of(request.timeZone());
        } catch (DateTimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_TIME_ZONE, "Unknown time zone");
        }
        return ValidatedUser.builder()
                .name(request.name().strip())
                .email(request.email().strip().toLowerCase(Locale.ROOT))
                .timeZone(request.timeZone())
                .build();
    }
}
