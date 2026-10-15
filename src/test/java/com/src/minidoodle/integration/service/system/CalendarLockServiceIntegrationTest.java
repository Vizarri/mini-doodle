package com.src.minidoodle.integration.service.system;

import com.src.minidoodle.db.model.Calendar;
import com.src.minidoodle.db.model.User;
import com.src.minidoodle.dto.response.UserResponse;
import com.src.minidoodle.integration.AbstractIntegrationTest;
import com.src.minidoodle.service.system.CalendarLockService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.entry;

/** Verifies transaction participation and PostgreSQL UUID lock ordering through the actual Spring proxy. */
class CalendarLockServiceIntegrationTest extends AbstractIntegrationTest {
    @Autowired
    private CalendarLockService calendarLockService;

    @Test
    void locksRequireAnExistingTransaction() {
        UserResponse user = createUser();

        assertThatExceptionOfType(IllegalTransactionStateException.class).isThrownBy(() -> calendarLockService.lockUsers(List.of(user.id())));

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(calendarRepository.count()).isEqualTo(1);
        assertThat(timeSlotRepository.count()).isZero();
        assertThat(meetingRepository.count()).isZero();
        assertThat(calendarRepository.findByUserId(user.id())).isPresent();
    }

    @Test
    void locksFollowDatabaseUuidOrderRatherThanInputOrJavaSignedOrder() {
        UUID lowerCalendarId = UUID.fromString("7fffffff-ffff-ffff-ffff-ffffffffffff");
        UUID higherCalendarId = UUID.fromString("80000000-0000-0000-0000-000000000000");
        Calendar lowerCalendar = createCalendar(lowerCalendarId);
        Calendar higherCalendar = createCalendar(higherCalendarId);
        UUID lowerUserId = lowerCalendar.getUser().getId();
        UUID higherUserId = higherCalendar.getUser().getId();
        assertThat(higherCalendarId.compareTo(lowerCalendarId)).isNegative();

        Map<UUID, UUID> locked = new TransactionTemplate(transactionManager).execute(status -> calendarLockService.lockUsers(List.of(higherUserId, lowerUserId, higherUserId)));

        assertThat(locked).containsExactly(entry(lowerUserId, lowerCalendarId), entry(higherUserId, higherCalendarId));
        assertThat(calendarRepository.count()).isEqualTo(2);
    }

    private Calendar createCalendar(UUID calendarId) {
        User user = userRepository.saveAndFlush(User.builder().name("Lock ordering user").email(UUID.randomUUID() + "@example.com").timeZone("UTC").build());
        return calendarRepository.saveAndFlush(Calendar.builder().id(calendarId).user(user).build());
    }
}
