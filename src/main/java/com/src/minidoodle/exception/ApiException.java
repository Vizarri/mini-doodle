package com.src.minidoodle.exception;

import com.src.minidoodle.enums.ApiErrorCode;
import lombok.Getter;
import org.springframework.http.HttpStatus;

/**
 * Carries an expected application failure to the HTTP error handler.
 * Messages and details are exposed to clients; unchecked propagation also rolls back service transactions.
 */
@Getter
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final ApiErrorCode code;
    private final Object details;

    /**
     * Creates an application error without additional context.
     *
     * @param status HTTP status returned to the client
     * @param code stable machine-readable error code
     * @param message client-safe explanation of the failure
     */
    public ApiException(HttpStatus status, ApiErrorCode code, String message) {
        this(status, code, message, null);
    }

    /**
     * Creates an application error with optional structured context.
     *
     * @param status HTTP status returned to the client
     * @param code stable machine-readable error code
     * @param message client-safe explanation of the failure
     * @param details optional client-safe context; maps are merged into the response details
     */
    public ApiException(HttpStatus status, ApiErrorCode code, String message, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details;
    }
}
