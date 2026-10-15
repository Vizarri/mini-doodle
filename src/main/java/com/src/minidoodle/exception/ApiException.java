package com.src.minidoodle.exception;

import com.src.minidoodle.enums.ApiErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/** Application exception translated into the stable API error contract. */
@Getter
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final ApiErrorCode code;
    private final Object details;

    public ApiException(HttpStatus status, ApiErrorCode code, String message) {
        this(status, code, message, null);
    }

    public ApiException(HttpStatus status, ApiErrorCode code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }
}
