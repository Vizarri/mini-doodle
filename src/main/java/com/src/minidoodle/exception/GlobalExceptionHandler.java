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

/**
 * Converts application, validation and persistence failures into the shared JSON error contract.
 * Known conflicts retain stable codes; unexpected failures are logged without exposing internal details.
 */
@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {
    /**
     * Preserves an application failure's status, code and client-safe details.
     *
     * @param exception expected application failure
     * @param request HTTP request supplying the response path
     * @return the corresponding application error response
     */
    @ExceptionHandler(ApiException.class)
    public ResponseEntity<ApiError> handleApi(ApiException exception, HttpServletRequest request) {
        return response(exception.getStatus(), exception.getCode(), exception.getMessage(), exception.getDetails(), request);
    }

    /**
     * Reports request binding and bean validation failures by field.
     *
     * @param exception binding or validation failure
     * @param request HTTP request supplying the response path
     * @return a 400 VALIDATION_FAILED response with details.fields
     */
    @ExceptionHandler(BindException.class)
    public ResponseEntity<ApiError> handleBinding(BindException exception, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                fields.putIfAbsent(error.getField(), error.getDefaultMessage()));
        exception.getBindingResult().getGlobalErrors().forEach(error ->
                fields.putIfAbsent(error.getObjectName(), error.getDefaultMessage()));
        return validation(fields, request);
    }

    /**
     * Reports constraint violations raised by validated controller parameters.
     *
     * @param exception parameter constraint violations
     * @param request HTTP request supplying the response path
     * @return a 400 VALIDATION_FAILED response with details.fields
     */
    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> handleConstraintViolation(ConstraintViolationException exception, HttpServletRequest request) {
        Map<String, String> fields = new LinkedHashMap<>();
        exception.getConstraintViolations().forEach(violation -> {
            String path = violation.getPropertyPath().toString();
            fields.putIfAbsent(path.substring(path.lastIndexOf('.') + 1), violation.getMessage());
        });
        return validation(fields, request);
    }

    /**
     * Reports invalid method arguments while treating invalid return values as server failures.
     *
     * @param exception controller method validation failure
     * @param request HTTP request supplying the response path
     * @return a 400 field-validation response, or 500 for an invalid return value
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handleMethodValidation(HandlerMethodValidationException exception, HttpServletRequest request) {
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

    /**
     * Rejects malformed JSON and values that cannot be deserialized into the request contract.
     *
     * @param exception request-body decoding failure
     * @param request HTTP request supplying the response path
     * @return a 400 MALFORMED_JSON response
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> handleMalformedJson(HttpMessageNotReadableException exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ApiErrorCode.MALFORMED_JSON, "Malformed request body.", null, request);
    }

    /**
     * Reports missing query parameters and values with invalid types.
     *
     * @param exception parameter binding failure
     * @param request HTTP request supplying the response path
     * @return a 400 BAD_REQUEST response
     */
    @ExceptionHandler({MethodArgumentTypeMismatchException.class, MissingServletRequestParameterException.class})
    public ResponseEntity<ApiError> handleBadRequest(Exception exception, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ApiErrorCode.BAD_REQUEST, "Invalid or missing request parameter. Timestamps require an explicit UTC offset.", null, request);
    }

    /**
     * Maps recognized database constraints to conflicts and hides unexpected integrity failures.
     *
     * @param exception database integrity failure
     * @param request HTTP request supplying the response path
     * @return a 409 response for a known conflict, or 500 for an unrecognized constraint
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> handleDataIntegrity(DataIntegrityViolationException exception, HttpServletRequest request) {
        String constraint = constraintName(exception);
        if ("users_email_unique".equals(constraint)) {
            return response(HttpStatus.CONFLICT, ApiErrorCode.EMAIL_ALREADY_EXISTS, "A user with this email already exists", null, request);
        }
        if ("time_slots_no_overlap".equals(constraint)) {
            return response(HttpStatus.CONFLICT, ApiErrorCode.SLOT_OVERLAP, "Slots in the same calendar cannot overlap", null, request);
        }
        if ("meeting_reservations_no_overlap".equals(constraint)) {
            return response(HttpStatus.CONFLICT, ApiErrorCode.MEETING_CONFLICT, "At least one participant already has a meeting in this interval", null, request);
        }
        // An unknown integrity failure can indicate a bug; do not mislabel it as a user conflict.
        log.error("Unexpected data integrity failure for path {}", request.getRequestURI(), exception);
        return internalError(request);
    }

    /**
     * Reports a locking failure so the client can retry the complete request.
     *
     * @param exception failed lock acquisition or concurrency conflict
     * @param request HTTP request supplying the response path
     * @return a 409 CONCURRENT_MODIFICATION response
     */
    @ExceptionHandler(PessimisticLockingFailureException.class)
    public ResponseEntity<ApiError> handleConcurrentModification(PessimisticLockingFailureException exception, HttpServletRequest request) {
        return response(HttpStatus.CONFLICT, ApiErrorCode.CONCURRENT_MODIFICATION, "A concurrent change could not complete. Retry the request.", null, request);
    }

    /**
     * Normalizes unknown routes to the shared error shape.
     *
     * @param exception missing resource failure
     * @param request HTTP request supplying the response path
     * @return a 404 NOT_FOUND response
     */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_FOUND, ApiErrorCode.NOT_FOUND, "Resource was not found", null, request);
    }

    /**
     * Reports methods that are not supported by an existing resource.
     *
     * @param exception unsupported HTTP method failure
     * @param request HTTP request supplying the response path
     * @return a 405 BAD_REQUEST response
     */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMethod(HttpRequestMethodNotSupportedException exception, HttpServletRequest request) {
        return response(HttpStatus.METHOD_NOT_ALLOWED, ApiErrorCode.BAD_REQUEST, "HTTP method is not supported for this resource", null, request);
    }

    /**
     * Reports a request body sent with an unsupported content type.
     *
     * @param exception unsupported request content type
     * @param request HTTP request supplying the response path
     * @return a 415 BAD_REQUEST response
     */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException exception, HttpServletRequest request) {
        return response(HttpStatus.UNSUPPORTED_MEDIA_TYPE, ApiErrorCode.BAD_REQUEST, "Request body must use application/json", null, request);
    }

    /**
     * Reports an Accept header that excludes the resource's JSON representation.
     *
     * @param exception unacceptable response media type
     * @param request HTTP request supplying the response path
     * @return a 406 BAD_REQUEST response
     */
    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> handleUnacceptableMediaType(HttpMediaTypeNotAcceptableException exception, HttpServletRequest request) {
        return response(HttpStatus.NOT_ACCEPTABLE, ApiErrorCode.BAD_REQUEST, "This resource produces application/json", null, request);
    }

    /**
     * Logs an unexpected failure and returns a generic response without exception details.
     *
     * @param exception unhandled application failure
     * @param request HTTP request supplying the response path
     * @return a 500 INTERNAL_ERROR response
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception exception, HttpServletRequest request) {
        log.error("Unhandled exception for path {}", request.getRequestURI(), exception);
        return internalError(request);
    }

    private ResponseEntity<ApiError> validation(Map<String, String> fields, HttpServletRequest request) {
        return response(HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED, "Validation failed", Map.of("fields", fields), request);
    }

    private ResponseEntity<ApiError> internalError(HttpServletRequest request) {
        return response(HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR, "Internal server error", null, request);
    }

    private ResponseEntity<ApiError> response(HttpStatus status, ApiErrorCode code, String message, Object suppliedDetails, HttpServletRequest request) {
        Map<String, Object> details = new LinkedHashMap<>();
        if (suppliedDetails instanceof Map<?, ?> map) {
            map.forEach((key, value) -> details.put(String.valueOf(key), value));
        } else if (suppliedDetails != null) {
            details.put("context", suppliedDetails);
        }
        details.put("path", request.getRequestURI());
        return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.builder().status(status.value()).code(code.name()).message(message).details(details).build());
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
