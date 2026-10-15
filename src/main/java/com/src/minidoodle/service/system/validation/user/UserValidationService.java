package com.src.minidoodle.service.system.validation.user;

import com.src.minidoodle.dto.request.CreateUserRequest;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Locale;

@Service
public class UserValidationService {
    public ValidatedUser validateAndNormalize(CreateUserRequest request) {
        try {
            ZoneId.of(request.timeZone());
        } catch (DateTimeException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, ApiErrorCode.INVALID_TIME_ZONE, "Unknown time zone");
        }
        return new ValidatedUser(request.name().strip(), request.email().strip().toLowerCase(Locale.ROOT),
                request.timeZone());
    }
}
