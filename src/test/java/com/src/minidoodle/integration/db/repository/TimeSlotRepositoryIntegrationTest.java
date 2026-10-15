package com.src.minidoodle.integration.db.repository;

import com.src.minidoodle.db.model.Calendar;
import com.src.minidoodle.db.model.TimeSlot;
import com.src.minidoodle.dto.response.UserResponse;
import com.src.minidoodle.enums.SlotStatus;
import com.src.minidoodle.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Protects the PostgreSQL slot exclusion constraint independently of service validation and locks. */
class TimeSlotRepositoryIntegrationTest extends AbstractIntegrationTest {
    @Test
    void databaseRejectsOverlappingSlotsInTheSameCalendarRegardlessOfStatus() {
        UserResponse user = createUser();
        Calendar calendar = calendarRepository.findByUserId(user.id()).orElseThrow();
        timeSlotRepository.saveAndFlush(slot(calendar, "09:00", "11:00", SlotStatus.FREE));

        assertThatThrownBy(() -> timeSlotRepository.saveAndFlush(slot(calendar, "10:00", "12:00", SlotStatus.BUSY)))
                .isInstanceOfSatisfying(DataIntegrityViolationException.class, failure -> {
                    assertThat(failure.getMostSpecificCause()).isInstanceOfSatisfying(PSQLException.class, databaseFailure -> {
                        assertThat(databaseFailure.getSQLState()).isEqualTo("23P01");
                        assertThat(databaseFailure.getServerErrorMessage()).isNotNull();
                        assertThat(databaseFailure.getServerErrorMessage().getConstraint()).isEqualTo("time_slots_no_overlap");
                    });
                });
        assertThat(timeSlotRepository.count()).isEqualTo(1);
    }

    @Test
    void databaseAcceptsAdjacentSlotsAndTheSameTimeInDifferentCalendars() {
        UserResponse first = createUser();
        UserResponse second = createUser();
        Calendar firstCalendar = calendarRepository.findByUserId(first.id()).orElseThrow();
        Calendar secondCalendar = calendarRepository.findByUserId(second.id()).orElseThrow();
        TimeSlot earlier = slot(firstCalendar, "09:00", "10:00", SlotStatus.FREE);
        TimeSlot adjacent = slot(firstCalendar, "10:00", "11:00", SlotStatus.BUSY);
        TimeSlot otherCalendar = slot(secondCalendar, "09:00", "10:00", SlotStatus.FREE);

        timeSlotRepository.saveAllAndFlush(List.of(earlier, adjacent, otherCalendar));

        assertThat(timeSlotRepository.findAll()).extracting(TimeSlot::getId)
                .containsExactlyInAnyOrder(earlier.getId(), adjacent.getId(), otherCalendar.getId());
    }

    private TimeSlot slot(Calendar calendar, String from, String to, SlotStatus status) {
        return TimeSlot.builder().calendar(calendar).startsAt(instant(from)).endsAt(instant(to)).status(status).build();
    }
}
