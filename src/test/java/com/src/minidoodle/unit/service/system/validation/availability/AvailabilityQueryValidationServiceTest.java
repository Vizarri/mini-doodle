package com.src.minidoodle.unit.service.system.validation.availability;

import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.service.system.validation.TimeRangeValidationService;
import com.src.minidoodle.service.system.validation.availability.AvailabilityQueryValidationService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Verifies bounded request size and stable, immutable normalization of queried users. */
class AvailabilityQueryValidationServiceTest {
    private static final UUID FIRST_USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_USER = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant FROM = Instant.parse("2030-01-01T10:00:00Z");
    private static final Instant TO = Instant.parse("2030-01-01T11:00:00Z");
    private final AvailabilityQueryValidationService service = new AvailabilityQueryValidationService(new TimeRangeValidationService());

    @Test
    void deduplicatesUsersInEncounterOrderWithoutChangingInput() {
        List<UUID> input = new ArrayList<>(List.of(SECOND_USER, FIRST_USER, SECOND_USER));

        List<UUID> result = service.validateAndNormalize(input, FROM, TO);

        assertThat(result).containsExactly(SECOND_USER, FIRST_USER);
        assertThat(input).containsExactly(SECOND_USER, FIRST_USER, SECOND_USER);
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> result.add(UUID.randomUUID()));
    }

    @Test
    void maximumInputCountIsAcceptedBeforeDeduplication() {
        assertThat(service.validateAndNormalize(Collections.nCopies(50, FIRST_USER), FROM, TO)).containsExactly(FIRST_USER);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidUserIds")
    void rejectsInvalidUserListsIncludingExcessiveDuplicates(String scenario, List<UUID> userIds) {
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.validateAndNormalize(userIds, FROM, TO)).satisfies(exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getCode()).isEqualTo(ApiErrorCode.BAD_REQUEST);
        });
    }

    @Test
    void validatesWindowBeforeReturningNormalizedUsers() {
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.validateAndNormalize(List.of(FIRST_USER), TO, FROM))
                .satisfies(exception -> assertThat(exception.getCode()).isEqualTo(ApiErrorCode.INVALID_TIME_RANGE));
    }

    private static Stream<Arguments> invalidUserIds() {
        return Stream.of(
                Arguments.of("missing users", null),
                Arguments.of("empty users", List.of()),
                Arguments.of("null user", Arrays.asList(FIRST_USER, null)),
                Arguments.of("51 duplicate users", Collections.nCopies(51, FIRST_USER))
        );
    }
}
