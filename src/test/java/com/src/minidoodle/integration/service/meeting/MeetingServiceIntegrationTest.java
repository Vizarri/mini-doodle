package com.src.minidoodle.integration.service.meeting;

import com.src.minidoodle.dto.request.CreateMeetingRequest;
import com.src.minidoodle.dto.response.MeetingResponse;
import com.src.minidoodle.dto.response.SlotResponse;
import com.src.minidoodle.dto.response.UserResponse;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.enums.MeetingStatus;
import com.src.minidoodle.enums.SlotStatus;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.integration.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Verifies meeting ownership, atomic persistence and immutable history using the real database. */
class MeetingServiceIntegrationTest extends AbstractIntegrationTest {
    @ParameterizedTest(name = "{0} source cannot be booked")
    @EnumSource(InvalidSource.class)
    void invalidSourceCannotCreateMeetingMetadataMembershipOrReservations(InvalidSource invalidSource) {
        UserResponse organizer = createUser();
        UserResponse other = createUser();
        UUID ownerId = invalidSource == InvalidSource.FOREIGN ? other.id() : organizer.id();
        SlotStatus sourceStatus = invalidSource == InvalidSource.BUSY ? SlotStatus.BUSY : SlotStatus.FREE;
        SlotResponse source = createSlot(ownerId, "10:00", "11:00", sourceStatus);
        UUID sourceId = invalidSource == InvalidSource.MISSING ? UUID.randomUUID() : source.id();
        HttpStatus expectedStatus = invalidSource == InvalidSource.BUSY ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND;
        ApiErrorCode expectedCode = invalidSource == InvalidSource.BUSY ? ApiErrorCode.MEETING_CONFLICT : ApiErrorCode.SLOT_NOT_FOUND;

        assertApiFailure(() -> createMeeting(organizer.id(), sourceId, List.of()), expectedStatus, expectedCode);

        assertNoMeetingRows();
        assertThat(slotService.list(ownerId, instant("09:00"), instant("12:00"), 10, 0)).containsExactly(source);
    }

    @Test
    void organizerAndDuplicateParticipantsProduceOneMembershipAndReservationPerUser() {
        UserResponse organizer = createUser();
        UserResponse participant = createUser();
        SlotResponse source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);
        createSlot(participant.id(), "09:00", "12:00", SlotStatus.FREE);
        CreateMeetingRequest request = CreateMeetingRequest.builder()
                .organizerId(organizer.id())
                .slotId(source.id())
                .title("  Sprint planning  ")
                .description("  Notes are preserved  ")
                .participantIds(List.of(participant.id(), organizer.id(), participant.id(), organizer.id()))
                .build();

        MeetingResponse created = meetingService.create(request);

        List<UUID> expectedParticipants = List.of(organizer.id(), participant.id()).stream().sorted().toList();
        assertThat(created.participantIds()).containsExactlyElementsOf(expectedParticipants);
        assertThat(created.title()).isEqualTo("Sprint planning");
        assertThat(created.description()).isEqualTo(request.description());
        assertThat(created.status()).isEqualTo(MeetingStatus.ACTIVE);
        assertThat(meetingService.get(created.id())).isEqualTo(created);
        assertThat(meetingRepository.count()).isEqualTo(1);
        assertThat(meetingParticipantRepository.findByIdMeetingId(created.id())).extracting(participantRow -> participantRow.getId().getUserId()).containsExactlyInAnyOrderElementsOf(expectedParticipants);
        assertThat(meetingReservationRepository.findByIdMeetingId(created.id())).hasSize(2).allSatisfy(reservation -> {
            assertThat(reservation.getStartsAt()).isEqualTo(source.startsAt());
            assertThat(reservation.getEndsAt()).isEqualTo(source.endsAt());
        });
    }

    @Test
    void bookingAnAlreadyReservedSourceDoesNotAddOrChangeAnyMeetingRows() {
        UserResponse organizer = createUser();
        SlotResponse source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);
        MeetingResponse existing = createMeeting(organizer.id(), source.id(), List.of());

        assertApiFailure(() -> createMeeting(organizer.id(), source.id(), List.of()), HttpStatus.CONFLICT, ApiErrorCode.MEETING_CONFLICT);

        assertThat(meetingRepository.count()).isEqualTo(1);
        assertThat(meetingParticipantRepository.count()).isEqualTo(1);
        assertThat(meetingReservationRepository.count()).isEqualTo(1);
        assertThat(meetingService.get(existing.id())).isEqualTo(existing);
        assertThat(slotService.list(organizer.id(), instant("09:00"), instant("12:00"), 10, 0)).containsExactly(source);
    }

    @Test
    void failedReservationInsertRollsBackMetadataAndPreviouslyInsertedParticipantsAndReservations() {
        UserResponse organizer = createUser();
        UserResponse participant = createUser();
        SlotResponse source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);
        createSlot(participant.id(), "09:00", "12:00", SlotStatus.FREE);
        UUID lastParticipantId = List.of(organizer.id(), participant.id()).stream().max(UUID::compareTo).orElseThrow();
        UUID rejectedCalendarId = calendarRepository.findByUserId(lastParticipantId).orElseThrow().getId();
        // Reject the second reservation, after the first reservation and both memberships have been inserted.
        jdbcTemplate.execute("ALTER TABLE meeting_reservations ADD CONSTRAINT integration_reject_reservation CHECK (calendar_id <> '" + rejectedCalendarId + "'::uuid)");
        try {
            assertThatThrownBy(() -> createMeeting(organizer.id(), source.id(), List.of(participant.id()))).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.execute("ALTER TABLE meeting_reservations DROP CONSTRAINT integration_reject_reservation");
        }

        assertNoMeetingRows();
        assertThat(timeSlotRepository.count()).isEqualTo(2);
        MeetingResponse retried = createMeeting(organizer.id(), source.id(), List.of(participant.id()));
        assertThat(meetingService.get(retried.id())).isEqualTo(retried);
        assertThat(meetingParticipantRepository.count()).isEqualTo(2);
        assertThat(meetingReservationRepository.count()).isEqualTo(2);
    }

    @Test
    void cancelledMeetingKeepsOriginalDatesWhenItsSourceIsMovedAndBookedAgain() {
        UserResponse organizer = createUser();
        UserResponse participant = createUser();
        SlotResponse source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);
        createSlot(participant.id(), "09:00", "14:00", SlotStatus.FREE);
        MeetingResponse original = createMeeting(organizer.id(), source.id(), List.of(participant.id()));
        meetingService.cancel(original.id());

        SlotResponse moved = slotService.update(organizer.id(), source.id(), slotRequest("12:00", "13:00", SlotStatus.FREE));
        MeetingResponse replacement = createMeeting(organizer.id(), moved.id(), List.of(participant.id()));

        MeetingResponse retained = meetingService.get(original.id());
        assertThat(retained).isEqualTo(MeetingResponse.builder()
                .id(original.id())
                .organizerId(original.organizerId())
                .slotId(original.slotId())
                .title(original.title())
                .description(original.description())
                .startsAt(original.startsAt())
                .endsAt(original.endsAt())
                .participantIds(original.participantIds())
                .status(MeetingStatus.CANCELLED)
                .build());
        assertThat(replacement.startsAt()).isEqualTo(instant("12:00"));
        assertThat(replacement.endsAt()).isEqualTo(instant("13:00"));
        assertThat(replacement.status()).isEqualTo(MeetingStatus.ACTIVE);
        assertThat(meetingRepository.count()).isEqualTo(2);
        assertThat(meetingParticipantRepository.count()).isEqualTo(4);
        assertThat(meetingReservationRepository.findByIdMeetingId(original.id())).isEmpty();
        assertThat(meetingReservationRepository.findByIdMeetingId(replacement.id())).hasSize(2).allSatisfy(reservation -> {
            assertThat(reservation.getStartsAt()).isEqualTo(moved.startsAt());
            assertThat(reservation.getEndsAt()).isEqualTo(moved.endsAt());
        });
    }

    @ParameterizedTest(name = "{0} reports a missing meeting without affecting an existing booking")
    @EnumSource(MeetingLookup.class)
    void missingMeetingDoesNotChangeAnUnrelatedBooking(MeetingLookup lookup) {
        UserResponse organizer = createUser();
        SlotResponse source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);
        MeetingResponse existing = createMeeting(organizer.id(), source.id(), List.of());
        UUID missingId = UUID.randomUUID();

        assertApiFailure(() -> {
            switch (lookup) {
                case GET -> meetingService.get(missingId);
                case CANCEL -> meetingService.cancel(missingId);
            }
        }, HttpStatus.NOT_FOUND, ApiErrorCode.MEETING_NOT_FOUND);

        assertThat(meetingService.get(existing.id())).isEqualTo(existing);
        assertThat(meetingRepository.count()).isEqualTo(1);
        assertThat(meetingParticipantRepository.count()).isEqualTo(1);
        assertThat(meetingReservationRepository.count()).isEqualTo(1);
    }

    private void assertNoMeetingRows() {
        assertThat(meetingRepository.count()).isZero();
        assertThat(meetingParticipantRepository.count()).isZero();
        assertThat(meetingReservationRepository.count()).isZero();
    }

    private void assertApiFailure(Runnable action, HttpStatus status, ApiErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, failure -> {
            assertThat(failure.getStatus()).isEqualTo(status);
            assertThat(failure.getCode()).isEqualTo(code);
        });
    }

    enum InvalidSource { MISSING, FOREIGN, BUSY }
    enum MeetingLookup { GET, CANCEL }
}
