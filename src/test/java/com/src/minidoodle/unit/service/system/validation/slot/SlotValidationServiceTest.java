package com.src.minidoodle.unit.service.system.validation.slot;

import com.src.minidoodle.db.model.Calendar;
import com.src.minidoodle.db.model.MeetingReservation;
import com.src.minidoodle.db.model.TimeSlot;
import com.src.minidoodle.db.repository.MeetingReservationRepository;
import com.src.minidoodle.db.repository.TimeSlotRepository;
import com.src.minidoodle.dto.request.SlotUpsertRequest;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.enums.SlotStatus;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.service.system.validation.TimeRangeValidationService;
import com.src.minidoodle.service.system.validation.slot.SlotValidationService;
import com.src.minidoodle.service.system.validation.slot.ValidatedSlot;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** Exercises slot normalization, pagination and reservation guards before persistence mutations. */
@ExtendWith(MockitoExtension.class)
class SlotValidationServiceTest {
    private static final UUID CALENDAR_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SLOT_ID = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final Instant FROM = Instant.parse("2030-01-01T10:00:00Z");
    private static final Instant TO = Instant.parse("2030-01-01T11:00:00Z");

    @Mock
    private TimeSlotRepository timeSlotRepository;
    @Mock
    private MeetingReservationRepository meetingReservationRepository;
    private SlotValidationService service;

    @BeforeEach
    void setUp() {
        service = new SlotValidationService(new TimeRangeValidationService(), timeSlotRepository, meetingReservationRepository);
    }

    @ParameterizedTest
    @EnumSource(SlotStatus.class)
    void normalizesOffsetsAndPreservesDeclaredStatus(SlotStatus status) {
        SlotUpsertRequest request = SlotUpsertRequest.builder().startsAt("2030-01-01T12:00:00+02:00").endsAt("2030-01-01T06:00:00-05:00").status(status).build();

        ValidatedSlot result = service.validateAndNormalize(request);

        assertThat(result).isEqualTo(ValidatedSlot.builder().startsAt(FROM).endsAt(TO).status(status).build());
        verifyNoInteractions(timeSlotRepository, meetingReservationRepository);
    }

    @ParameterizedTest
    @CsvSource({"0, 0", "501, 0", "1, -1", "1, 100001"})
    void rejectsPaginationOutsideSupportedBounds(int limit, int offset) {
        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.validateQuery(FROM, TO, limit, offset)).satisfies(exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getCode()).isEqualTo(ApiErrorCode.BAD_REQUEST);
        });
        verifyNoInteractions(timeSlotRepository, meetingReservationRepository);
    }

    @ParameterizedTest
    @CsvSource({"1, 0", "500, 100000"})
    void acceptsInclusivePaginationBoundaries(int limit, int offset) {
        assertThatCode(() -> service.validateQuery(FROM, TO, limit, offset)).doesNotThrowAnyException();
    }

    @Test
    void reservedSlotIsRejectedUsingItsCurrentEntireInterval() {
        TimeSlot slot = slot(SLOT_ID);
        when(meetingReservationRepository.findOverlapping(List.of(CALENDAR_ID), FROM, TO)).thenReturn(List.of(MeetingReservation.builder().build()));

        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.validateNotReserved(slot)).satisfies(exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exception.getCode()).isEqualTo(ApiErrorCode.SLOT_RESERVED);
        });
        assertThat(slot.getStartsAt()).isEqualTo(FROM);
        assertThat(slot.getEndsAt()).isEqualTo(TO);
        verifyNoInteractions(timeSlotRepository);
    }

    @Test
    void unreservedSlotCanBeMutated() {
        when(meetingReservationRepository.findOverlapping(List.of(CALENDAR_ID), FROM, TO)).thenReturn(List.of());

        assertThatCode(() -> service.validateNotReserved(slot(SLOT_ID))).doesNotThrowAnyException();
    }

    @Test
    void updateIgnoresOnlyTheSlotBeingUpdated() {
        ValidatedSlot candidate = candidate();
        when(timeSlotRepository.findOverlapping(List.of(CALENDAR_ID), FROM, TO)).thenReturn(List.of(slot(SLOT_ID)));

        assertThatCode(() -> service.validateNoOverlap(CALENDAR_ID, SLOT_ID, candidate)).doesNotThrowAnyException();
        verify(timeSlotRepository).findOverlapping(List.of(CALENDAR_ID), FROM, TO);
    }

    @Test
    void updateStillRejectsAnotherOverlappingSlot() {
        UUID otherSlotId = UUID.fromString("00000000-0000-0000-0000-000000000003");
        when(timeSlotRepository.findOverlapping(List.of(CALENDAR_ID), FROM, TO)).thenReturn(List.of(slot(SLOT_ID), slot(otherSlotId)));

        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.validateNoOverlap(CALENDAR_ID, SLOT_ID, candidate())).satisfies(exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(exception.getCode()).isEqualTo(ApiErrorCode.SLOT_OVERLAP);
        });
    }

    @Test
    void createDoesNotExcludeExistingSlots() {
        when(timeSlotRepository.findOverlapping(List.of(CALENDAR_ID), FROM, TO)).thenReturn(List.of(slot(SLOT_ID)));

        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.validateNoOverlap(CALENDAR_ID, null, candidate()))
                .satisfies(exception -> assertThat(exception.getCode()).isEqualTo(ApiErrorCode.SLOT_OVERLAP));
    }

    @Test
    void noOverlapAllowsCreatingASlot() {
        when(timeSlotRepository.findOverlapping(List.of(CALENDAR_ID), FROM, TO)).thenReturn(List.of());

        assertThatCode(() -> service.validateNoOverlap(CALENDAR_ID, null, candidate())).doesNotThrowAnyException();
    }

    private static TimeSlot slot(UUID id) {
        return TimeSlot.builder().id(id).calendar(Calendar.builder().id(CALENDAR_ID).build()).startsAt(FROM).endsAt(TO).status(SlotStatus.FREE).build();
    }

    private static ValidatedSlot candidate() {
        return ValidatedSlot.builder().startsAt(FROM).endsAt(TO).status(SlotStatus.FREE).build();
    }
}
