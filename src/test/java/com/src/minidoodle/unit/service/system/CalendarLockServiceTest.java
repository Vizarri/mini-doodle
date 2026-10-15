package com.src.minidoodle.unit.service.system;

import com.src.minidoodle.db.model.Calendar;
import com.src.minidoodle.db.model.User;
import com.src.minidoodle.db.repository.CalendarRepository;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.service.system.CalendarLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.entry;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Tests lock request validation and owner mapping; database locking is covered by integration tests. */
@ExtendWith(MockitoExtension.class)
class CalendarLockServiceTest {
    private static final UUID FIRST_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FIRST_CALENDAR_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID SECOND_CALENDAR_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");

    @Mock
    private CalendarRepository calendarRepository;
    private CalendarLockService service;

    @BeforeEach
    void setUp() {
        service = new CalendarLockService(calendarRepository);
    }

    @Test
    void deduplicatesRequestedUsersAndPreservesDatabaseCalendarOrder() {
        List<UUID> input = List.of(SECOND_USER_ID, FIRST_USER_ID, SECOND_USER_ID);
        when(calendarRepository.lockByUserIds(List.of(SECOND_USER_ID, FIRST_USER_ID))).thenReturn(List.of(
                calendar(FIRST_CALENDAR_ID, FIRST_USER_ID), calendar(SECOND_CALENDAR_ID, SECOND_USER_ID)));

        Map<UUID, UUID> result = service.lockUsers(input);

        assertThat(result).containsExactly(entry(FIRST_USER_ID, FIRST_CALENDAR_ID), entry(SECOND_USER_ID, SECOND_CALENDAR_ID));
        verify(calendarRepository).lockByUserIds(List.of(SECOND_USER_ID, FIRST_USER_ID));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidUsers")
    void rejectsMalformedInputBeforeAcquiringLocks(String scenario, Collection<UUID> userIds) {
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.lockUsers(userIds)).satisfies(exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getCode()).isEqualTo(ApiErrorCode.BAD_REQUEST);
        });
        verifyNoInteractions(calendarRepository);
    }

    @Test
    void countsDistinctUsersRatherThanDuplicateInternalLockRequests() {
        when(calendarRepository.lockByUserIds(List.of(FIRST_USER_ID))).thenReturn(List.of(calendar(FIRST_CALENDAR_ID, FIRST_USER_ID)));

        assertThat(service.lockUsers(Collections.nCopies(51, FIRST_USER_ID))).containsOnly(entry(FIRST_USER_ID, FIRST_CALENDAR_ID));
    }

    @Test
    void rejectsTooManyDistinctUsersBeforeAcquiringLocks() {
        List<UUID> userIds = IntStream.rangeClosed(1, 51).mapToObj(index -> UUID.fromString("00000000-0000-0000-0000-%012d".formatted(index))).toList();

        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.lockUsers(userIds)).satisfies(exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getCode()).isEqualTo(ApiErrorCode.PARTICIPANT_LIMIT_EXCEEDED);
        });
        verifyNoInteractions(calendarRepository);
    }

    @Test
    void missingCalendarRejectsTheWholeRequest() {
        List<UUID> userIds = List.of(FIRST_USER_ID, SECOND_USER_ID);
        when(calendarRepository.lockByUserIds(userIds)).thenReturn(List.of(calendar(FIRST_CALENDAR_ID, FIRST_USER_ID)));

        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.lockUsers(userIds)).satisfies(exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(exception.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
        });
    }

    private static Stream<Arguments> invalidUsers() {
        return Stream.of(
                Arguments.of("missing user IDs", null),
                Arguments.of("empty user IDs", List.of()),
                Arguments.of("null user ID", Arrays.asList(FIRST_USER_ID, null))
        );
    }

    private static Calendar calendar(UUID calendarId, UUID userId) {
        return Calendar.builder().id(calendarId).user(User.builder().id(userId).build()).build();
    }
}
