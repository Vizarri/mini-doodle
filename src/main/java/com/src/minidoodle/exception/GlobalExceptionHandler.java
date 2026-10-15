package com.src.minidoodle.exception;

import com.src.minidoodle.dto.response.ApiError;
import com.src.minidoodle.enums.ApiErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/** Converts domain, validation and persistence failures into one documented API contract. */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException exception, HttpServletRequest request) {
        return response(exception.getStatus(), exception.getCode(), exception.getMessage(), exception.getDetails(), request);
    }

    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> handleBinding(BindException exception, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        exception.getBindingResult().getGlobalErrors().forEach(error ->
                fields.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
        return validation(fields, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException exception,
                                                              HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation -> {
            String path = violation.getPropertyPath().toString();
            fields.putIfAbsent(path.substring(path.lastIndexOf('.') + 1), violation.getMessage());
        });
        return validation(fields, request);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException exception,
                                                           HttpServletRequest request) {
        if (exception.isForReturnValue()) {
            log.error("Invalid controller return value for path {}", request.getRequestURI(), exception);
            return internalError(request);
        }
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getParameterValidationResults().forEach(result -> {
            String parameter = Objects.requireNonNullElse(result.getMethodParameter().getParameterName(), "request");
            String message = result.getResolvableErrors().stream().map(error -> error.getDefaultMessage())
                    .filter(Objects::nonNull).collect(Collectors.joining("; "));
            fields.putIfAbsent(parameter, message);
        });
        return validation(fields, request);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMalformedJson(HttpMessageNotReadableException exception,
                                                        HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ApiErrorCode.MALFORMED_JSON,
                "Malformed request body. Timestamps must be strings with an explicit UTC offset.", null, request);
    }

    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST,
                "Invalid or missing request parameter. Timestamps require an explicit UTC offset.", null, request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException exception,
                                                       HttpServletRequest request) {
        String constraint = constraintName(exception);
        if ("users_email_unique".equals(constraint)) {
            return response(HttpStatus.CONFLICT, ApiErrorCode.EMAIL_ALREADY_EXISTS,
                    "A user with this email already exists", null, request);
        }
        if ("time_slots_no_overlap".equals(constraint)) {
            return response(HttpStatus.CONFLICT, ApiErrorCode.SLOT_OVERLAP,
                    "Slots in the same calendar cannot overlap", null, request);
        }
        if ("meeting_reservations_no_overlap".equals(constraint)) {
            return response(HttpStatus.CONFLICT, ApiErrorCode.MEETING_CONFLICT,
                    "At least one participant already has a meeting in this interval", null, request);
        }
        // An unknown integrity failure can indicate a bug; do not mislabel it as a user conflict.
        log.error("Unexpected data integrity failure for path {}", request.getRequestURI(), exception);
        return internalError(request);
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleConcurrentModification(PessimisticLockingFailureException exception,
                                                                 HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, ApiErrorCode.CONCURRENT_MODIFICATION,
                "A concurrent change could not complete. Retry the request.", null, request);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND, "Resource was not found", null, request);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMethod(HttpRequestMethodNotSupportedException exception,
                                                           HttpServletRequest request) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, ApiErrorCode.BAD_REQUEST,
                "HTTP method is not supported for this resource", null, request);
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception,
                                                              HttpServletRequest request) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ApiErrorCode.BAD_REQUEST,
                "Request body must use application/json", null, request);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> handleUnacceptableMediaType(HttpMediaTypeNotAcceptableException exception,
                                                               HttpServletRequest request) {
        return response(HttpStatus.NOT_ACCEPTABLE, ApiErrorCode.BAD_REQUEST,
                "This resource produces application/json", null, request);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception for path {}", request.getRequestURI(), exception);
        return internalError(request);
    }

    private ResponseEntity<ApiError> validation(Map<String, String> fields, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED,
                "Validation failed", Map.of("fields", fields), request);
    }

    private ResponseEntity<ApiError> internalError(HttpServletRequest request) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR,
                "Internal server error", null, request);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, ApiErrorCode code, String message,
                                               Object suppliedDetails, HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (suppliedDetails instanceof Map<?, ?> map) {
            map.forEach((key, value) -> details.put(String.valueOf(key), value));
        } else if (suppliedDetails != null) {
            details.put("context", suppliedDetails);
        }
        details.put("path", request.getRequestURI());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(new ApiError(status.value(), code.name(), message, details));
    }

    private String constraintName(Throwable exception) {
        for (Throwable current = exception; current != null; current = current.getCause()) {
            if (current instanceof org.hibernate.exception.ConstraintViolationException constraint) {
                return constraint.getConstraintName();
            }
        }
        return null;
    }
}
