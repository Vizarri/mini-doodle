package com.src.minidoodle.integration.db.repository;

import com.src.minidoodle.db.model.Meeting;
import com.src.minidoodle.db.model.MeetingReservation;
import com.src.minidoodle.db.model.MeetingReservationId;
import com.src.minidoodle.dto.response.UserResponse;
import com.src.minidoodle.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Protects active reservation exclusion when native inserts bypass scheduling service checks. */
class MeetingReservationRepositoryIntegrationTest extends AbstractIntegrationTest {
    @Test
    void databaseRejectsOverlappingReservationsForDifferentMeetingsInTheSameCalendar() {
        UserResponse user = createUser();
        UUID calendarId = calendarRepository.findByUserId(user.id()).orElseThrow().getId();
        Meeting first = meeting(user.id(), "09:00", "10:00");
        Meeting overlapping = meeting(user.id(), "09:30", "10:30");
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                meetingReservationRepository.insert(first.getId(), calendarId, first.getStartsAt(), first.getEndsAt()));

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                meetingReservationRepository.insert(overlapping.getId(), calendarId, overlapping.getStartsAt(), overlapping.getEndsAt())))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class, failure -> {
                    assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(PSQLException.class, databaseFailure -> {
                        assertThat(databaseFailure.getSQLState()).isEqualTo("23P01");
                        assertThat(databaseFailure.getServerErrorMessage()).isNotNull();
                        assertThat(databaseFailure.getServerErrorMessage().getConstraint()).isEqualTo("meeting_reservations_no_overlap");
                    });
                });
        assertThat(meetingReservationRepository.count()).isEqualTo(1);
    }

    @Test
    void databaseAcceptsAdjacentReservationsAndTheSameMeetingInDifferentCalendars() {
        UserResponse first = createUser();
        UserResponse second = createUser();
        UUID firstCalendarId = calendarRepository.findByUserId(first.id()).orElseThrow().getId();
        UUID secondCalendarId = calendarRepository.findByUserId(second.id()).orElseThrow().getId();
        Meeting earlier = meeting(first.id(), "09:00", "10:00");
        Meeting adjacent = meeting(first.id(), "10:00", "11:00");

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            meetingReservationRepository.insert(earlier.getId(), firstCalendarId, earlier.getStartsAt(), earlier.getEndsAt());
            meetingReservationRepository.insert(adjacent.getId(), firstCalendarId, adjacent.getStartsAt(), adjacent.getEndsAt());
            meetingReservationRepository.insert(earlier.getId(), secondCalendarId, earlier.getStartsAt(), earlier.getEndsAt());
        });

        assertThat(meetingReservationRepository.count()).isEqualTo(3);
        assertThat(meetingReservationRepository.findByIdMeetingId(earlier.getId())).extracting(MeetingReservation::getId)
                .containsExactlyInAnyOrder(MeetingReservationId.builder().meetingId(earlier.getId()).calendarId(firstCalendarId).build(),
                        MeetingReservationId.builder().meetingId(earlier.getId()).calendarId(secondCalendarId).build());
    }

    private Meeting meeting(UUID organizerId, String from, String to) {
        return meetingRepository.saveAndFlush(Meeting.builder().organizer(userRepository.findById(organizerId).orElseThrow())
                .title("Repository constraint fixture").startsAt(instant(from)).endsAt(instant(to)).build());
    }
}
