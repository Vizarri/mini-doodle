package com.src.minidoodle.unit.exception;

import com.src.minidoodle.dto.response.ApiError;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.exception.GlobalExceptionHandler;
import java.lang.reflect.Method;
import java.sql.SQLException;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.MethodValidationResult;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

import static org.assertj.core.api.Assertions.assertThat;

/** Protects public error codes and keeps persistence and unexpected failure details private. */
class GlobalExceptionHandlerTest {
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("POST", "/meetings");

    @Test
    void applicationErrorPreservesStructuredDetailsAndUsesTheActualRequestPath() {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("participantId", "missing-user");
        details.put("path", "/incorrect-path");
        ApiException failure = new ApiException(HttpStatus.NOT_FOUND, ApiErrorCode.USER_NOT_FOUND, "Participant missing", details);

        ApiError error = assertError(handler.handleApi(failure, request), HttpStatus.NOT_FOUND, ApiErrorCode.USER_NOT_FOUND);

        assertThat(error.message()).isEqualTo("Participant missing");
        assertThat(error.details()).containsEntry("participantId", "missing-user").containsEntry("path", "/meetings");
        assertThat(details).containsEntry("path", "/incorrect-path");
    }

    @Test
    void applicationErrorWrapsUnstructuredContext() {
        ApiException failure = new ApiException(HttpStatus.CONFLICT, ApiErrorCode.MEETING_CONFLICT, "Conflict", "participant is busy");

        ApiError error = assertError(handler.handleApi(failure, request), HttpStatus.CONFLICT, ApiErrorCode.MEETING_CONFLICT);

        assertThat(error.details()).containsExactlyInAnyOrderEntriesOf(Map.of("context", "participant is busy", "path", "/meetings"));
    }

    @Test
    void validationReportsTheFirstMessageForEachFieldAndGlobalObjectError() {
        BindException failure = new BindException(new Object(), "meeting");
        failure.getBindingResult().addError(new FieldError("meeting", "title", "Title is required"));
        failure.getBindingResult().addError(new FieldError("meeting", "title", "Title is too long"));
        failure.reject("invalid", "Invalid meeting");

        ApiError error = assertError(handler.handleBinding(failure, request), HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED);

        assertThat(error.details().get("fields")).isEqualTo(Map.of("title", "Title is required", "meeting", "Invalid meeting"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void methodArgumentValidationJoinsMessagesAndHandlesMissingParameterNames(boolean discoverName) throws Exception {
        Method method = getClass().getDeclaredMethod("validatedEndpoint", int.class);
        MethodParameter parameter = new MethodParameter(method, 0);
        if (discoverName) {
            parameter.initParameterNameDiscovery(new DefaultParameterNameDiscoverer());
        }
        ParameterValidationResult result = new ParameterValidationResult(parameter, -1, List.of(
                new DefaultMessageSourceResolvable(null, null, "Must be positive"),
                new DefaultMessageSourceResolvable(null, null, null),
                new DefaultMessageSourceResolvable(null, null, "Must be within the limit")));
        HandlerMethodValidationException failure = new HandlerMethodValidationException(MethodValidationResult.create(this, method, List.of(result)));

        ApiError error = assertError(handler.handleMethodValidation(failure, request), HttpStatus.BAD_REQUEST, ApiErrorCode.VALIDATION_FAILED);

        assertThat(error.details().get("fields")).isEqualTo(Map.of(discoverName ? "limit" : "request", "Must be positive; Must be within the limit"));
    }

    @Test
    void invalidControllerReturnValueIsAServerFailureRatherThanAnInputError() throws Exception {
        Method method = getClass().getDeclaredMethod("validatedEndpoint", int.class);
        ParameterValidationResult result = new ParameterValidationResult(new MethodParameter(method, -1), -1,
                List.of(new DefaultMessageSourceResolvable(null, null, "private return-value details")));
        HandlerMethodValidationException failure = new HandlerMethodValidationException(MethodValidationResult.create(this, method, List.of(result)));

        ApiError error = assertError(handler.handleMethodValidation(failure, request), HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR);

        assertThat(error.message()).isEqualTo("Internal server error");
        assertThat(error.details()).containsOnlyKeys("path");
    }

    @ParameterizedTest
    @CsvSource({"users_email_unique, EMAIL_ALREADY_EXISTS", "time_slots_no_overlap, SLOT_OVERLAP", "meeting_reservations_no_overlap, MEETING_CONFLICT"})
    void recognizedNestedConstraintsReturnTheSpecificConflict(String constraint, ApiErrorCode expectedCode) {
        org.hibernate.exception.ConstraintViolationException violation = new org.hibernate.exception.ConstraintViolationException(
                "sensitive database details", new SQLException("sensitive SQL values"), constraint);
        DataIntegrityViolationException failure = new DataIntegrityViolationException("insert failed", new IllegalStateException(violation));

        ApiError error = assertError(handler.handleDataIntegrity(failure, request), HttpStatus.CONFLICT, expectedCode);

        assertThat(error.message()).doesNotContain("sensitive", "SQL", "insert failed");
        assertThat(error.details()).containsOnlyKeys("path");
    }

    @Test
    void unknownConstraintIsAServerFailureWithoutDatabaseDetails() {
        org.hibernate.exception.ConstraintViolationException violation = new org.hibernate.exception.ConstraintViolationException(
                "secret constraint data", new SQLException("secret query"), "unexpected_foreign_key");

        ApiError error = assertError(handler.handleDataIntegrity(new DataIntegrityViolationException("secret insert", violation), request),
                HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR);

        assertThat(error.message()).isEqualTo("Internal server error");
        assertThat(error.details()).containsOnlyKeys("path");
    }

    @Test
    void integrityFailureWithoutAHibernateConstraintIsAServerFailure() {
        ApiError error = assertError(handler.handleDataIntegrity(new DataIntegrityViolationException("secret column name"), request),
                HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR);

        assertThat(error.message()).isEqualTo("Internal server error");
        assertThat(error.details()).containsOnlyKeys("path");
    }

    @Test
    void lockFailureReturnsARetryableConflictWithoutLockInternals() {
        ApiError error = assertError(handler.handleConcurrentModification(new PessimisticLockingFailureException("secret transaction"), request),
                HttpStatus.CONFLICT, ApiErrorCode.CONCURRENT_MODIFICATION);

        assertThat(error.message()).contains("Retry").doesNotContain("secret");
        assertThat(error.details()).containsOnlyKeys("path");
    }

    @Test
    void unexpectedFailureDoesNotExposeExceptionMessagesOrStackTraces() {
        ApiError error = assertError(handler.handleUnexpected(new IllegalStateException("password=private"), request),
                HttpStatus.INTERNAL_SERVER_ERROR, ApiErrorCode.INTERNAL_ERROR);

        assertThat(error.message()).isEqualTo("Internal server error");
        assertThat(error.details()).containsExactlyInAnyOrderEntriesOf(Map.of("path", "/meetings"));
    }

    private int validatedEndpoint(int limit) {
        return limit;
    }

    private ApiError assertError(ResponseEntity<ApiError> response, HttpStatus status, ApiErrorCode code) {
        assertThat(response.getStatusCode()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).isNotNull();
        ApiError error = response.getBody();
        assertThat(error.status()).isEqualTo(status.value());
        assertThat(error.code()).isEqualTo(code.name());
        assertThat(error.details()).containsEntry("path", "/meetings");
        return error;
    }
}
