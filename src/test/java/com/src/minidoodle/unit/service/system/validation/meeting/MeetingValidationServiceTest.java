package com.src.minidoodle.unit.service.system.validation.meeting;

import com.src.minidoodle.db.model.MeetingReservation;
import com.src.minidoodle.db.repository.MeetingReservationRepository;
import com.src.minidoodle.db.repository.TimeSlotRepository;
import com.src.minidoodle.db.repository.projection.SlotAvailabilityView;
import com.src.minidoodle.dto.request.CreateMeetingRequest;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.service.system.validation.meeting.MeetingValidationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Checks participant normalization and complete free coverage before any meeting is persisted. */
@ExtendWith(MockitoExtension.class)
class MeetingValidationServiceTest {
    private static final UUID ORGANIZER_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID FIRST_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID THIRD_USER_ID = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID FIRST_CALENDAR_ID = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID SECOND_CALENDAR_ID = UUID.fromString("00000000-0000-0000-0000-000000000012");
    private static final Instant FROM = Instant.parse("2030-01-01T10:00:00Z");
    private static final Instant TO = Instant.parse("2030-01-01T11:00:00Z");

    @Mock
    private TimeSlotRepository timeSlotRepository;
    @Mock
    private MeetingReservationRepository meetingReservationRepository;
    private MeetingValidationService service;

    @BeforeEach
    void setUp() {
        service = new MeetingValidationService(timeSlotRepository, meetingReservationRepository);
    }

    @Test
    void includesOrganizerDeduplicatesAndSortsWithoutChangingInput() {
        List<UUID> participants = new ArrayList<>(List.of(THIRD_USER_ID, FIRST_USER_ID, THIRD_USER_ID));

        List<UUID> result = service.normalizeParticipants(request(ORGANIZER_ID, participants));

        assertThat(result).containsExactly(FIRST_USER_ID, ORGANIZER_ID, THIRD_USER_ID);
        assertThat(participants).containsExactly(THIRD_USER_ID, FIRST_USER_ID, THIRD_USER_ID);
        assertThatExceptionOfType(UnsupportedOperationException.class).isThrownBy(() -> result.add(UUID.randomUUID()));
        verifyNoInteractions(timeSlotRepository, meetingReservationRepository);
    }

    @Test
    void organizerCanBookWithoutAdditionalParticipants() {
        assertThat(service.normalizeParticipants(request(ORGANIZER_ID, List.of()))).containsExactly(ORGANIZER_ID);
    }

    @Test
    void organizerAlreadyPresentCountsOnlyOnceAtTheLimit() {
        List<UUID> participants = IntStream.rangeClosed(1, 50).mapToObj(index -> UUID.fromString("00000000-0000-0000-0000-%012d".formatted(index))).toList();

        assertThat(service.normalizeParticipants(request(ORGANIZER_ID, participants))).hasSize(50).containsOnlyOnce(ORGANIZER_ID);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingParticipants")
    void rejectsMissingParticipantIdentifiers(String scenario, UUID organizerId, List<UUID> participants) {
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.normalizeParticipants(request(organizerId, participants))).satisfies(exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getCode()).isEqualTo(ApiErrorCode.BAD_REQUEST);
        });
        verifyNoInteractions(timeSlotRepository, meetingReservationRepository);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("excessiveParticipants")
    void enforcesRawInputAndFinalDistinctMembershipLimits(String scenario, List<UUID> participants) {
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.normalizeParticipants(request(ORGANIZER_ID, participants))).satisfies(exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getCode()).isEqualTo(ApiErrorCode.PARTICIPANT_LIMIT_EXCEEDED);
        });
    }

    @Test
    void acceptsUnorderedAdjacentCoverageForEveryCalendar() {
        List<UUID> calendarIds = List.of(FIRST_CALENDAR_ID, SECOND_CALENDAR_ID);
        Instant middle = Instant.parse("2030-01-01T10:30:00Z");
        List<SlotAvailabilityView> free = List.of(
                availability(FIRST_CALENDAR_ID, middle, TO),
                availability(SECOND_CALENDAR_ID, FROM.minusSeconds(60), TO.plusSeconds(60)),
                availability(FIRST_CALENDAR_ID, FROM, middle));
        when(timeSlotRepository.findFreeAvailability(calendarIds, FROM, TO)).thenReturn(free);
        when(meetingReservationRepository.findOverlapping(calendarIds, FROM, TO)).thenReturn(List.of());

        assertThatCode(() -> service.validateAvailability(calendarIds, FROM, TO)).doesNotThrowAnyException();
        verify(meetingReservationRepository).findOverlapping(calendarIds, FROM, TO);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("incompleteCoverage")
    void rejectsMissingOrPartialCoverageBeforeCheckingReservations(String scenario, List<List<Instant>> bounds) {
        List<UUID> calendarIds = List.of(FIRST_CALENDAR_ID);
        List<SlotAvailabilityView> free = bounds.stream().map(bound -> availability(FIRST_CALENDAR_ID, bound.getFirst(), bound.getLast())).toList();
        when(timeSlotRepository.findFreeAvailability(calendarIds, FROM, TO)).thenReturn(free);

        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.validateAvailability(calendarIds, FROM, TO)).satisfies(exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exception.getCode()).isEqualTo(ApiErrorCode.AVAILABILITY_REQUIRED);
        });
        verifyNoInteractions(meetingReservationRepository);
    }

    @Test
    void eachParticipantNeedsCoverageRatherThanOnlyTheCombinedUnion() {
        List<UUID> calendarIds = List.of(FIRST_CALENDAR_ID, SECOND_CALENDAR_ID);
        Instant middle = Instant.parse("2030-01-01T10:30:00Z");
        List<SlotAvailabilityView> free = List.of(availability(FIRST_CALENDAR_ID, FROM, middle), availability(SECOND_CALENDAR_ID, middle, TO));
        when(timeSlotRepository.findFreeAvailability(calendarIds, FROM, TO)).thenReturn(free);

        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.validateAvailability(calendarIds, FROM, TO))
                .satisfies(exception -> assertThat(exception.getCode()).isEqualTo(ApiErrorCode.AVAILABILITY_REQUIRED));
        verifyNoInteractions(meetingReservationRepository);
    }

    @Test
    void existingReservationRejectsOtherwiseCompleteAvailability() {
        List<UUID> calendarIds = List.of(FIRST_CALENDAR_ID);
        List<SlotAvailabilityView> free = List.of(availability(FIRST_CALENDAR_ID, FROM, TO));
        when(timeSlotRepository.findFreeAvailability(calendarIds, FROM, TO)).thenReturn(free);
        when(meetingReservationRepository.findOverlapping(calendarIds, FROM, TO)).thenReturn(List.of(MeetingReservation.builder().build()));

        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.validateAvailability(calendarIds, FROM, TO)).satisfies(exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exception.getCode()).isEqualTo(ApiErrorCode.MEETING_CONFLICT);
        });
    }

    private static Stream<Arguments> missingParticipants() {
        return Stream.of(
                Arguments.of("missing organizer", null, List.of(FIRST_USER_ID)),
                Arguments.of("missing participants", ORGANIZER_ID, null),
                Arguments.of("null participant", ORGANIZER_ID, Arrays.asList(FIRST_USER_ID, null))
        );
    }

    private static Stream<Arguments> excessiveParticipants() {
        List<UUID> fiftyAdditionalUsers = IntStream.rangeClosed(100, 149).mapToObj(index -> UUID.fromString("00000000-0000-0000-0000-%012d".formatted(index))).toList();
        return Stream.of(
                Arguments.of("51 repeated IDs still exceed the input limit", Collections.nCopies(51, ORGANIZER_ID)),
                Arguments.of("50 additional users plus organizer exceed the total limit", fiftyAdditionalUsers)
        );
    }

    private static Stream<Arguments> incompleteCoverage() {
        Instant middle = Instant.parse("2030-01-01T10:30:00Z");
        return Stream.of(
                Arguments.of("undeclared time", List.of()),
                Arguments.of("late start", List.of(List.of(FROM.plusSeconds(1), TO))),
                Arguments.of("early end", List.of(List.of(FROM, TO.minusSeconds(1)))),
                Arguments.of("gap between free slots", List.of(List.of(FROM, middle), List.of(middle.plusSeconds(1), TO)))
        );
    }

    private static CreateMeetingRequest request(UUID organizerId, List<UUID> participantIds) {
        return CreateMeetingRequest.builder().organizerId(organizerId).participantIds(participantIds).build();
    }

    private static SlotAvailabilityView availability(UUID calendarId, Instant from, Instant to) {
        SlotAvailabilityView projection = mock(SlotAvailabilityView.class);
        when(projection.getCalendarId()).thenReturn(calendarId);
        when(projection.getStartsAt()).thenReturn(from);
        when(projection.getEndsAt()).thenReturn(to);
        return projection;
    }
}
