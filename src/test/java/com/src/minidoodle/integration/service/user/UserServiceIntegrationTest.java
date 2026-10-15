package com.src.minidoodle.integration.service.user;

import com.src.minidoodle.dto.request.CreateUserRequest;
import com.src.minidoodle.dto.response.UserResponse;
import com.src.minidoodle.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks normalized identity persistence and atomic creation of each user's internal calendar. */
class UserServiceIntegrationTest extends AbstractIntegrationTest {
    @Test
    void normalizedUserCanBeReadBackWithExactlyOneInternalCalendar() {
        UserResponse created = userService.create(CreateUserRequest.builder().name("  Ada Lovelace  ")
                .email("ADA@EXAMPLE.COM").timeZone("Europe/Berlin").build());

        assertThat(userService.get(created.id())).isEqualTo(created);
        assertThat(created.name()).isEqualTo("Ada Lovelace");
        assertThat(created.email()).isEqualTo("ada@example.com");
        assertThat(created.timeZone()).isEqualTo("Europe/Berlin");
        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(calendarRepository.count()).isEqualTo(1);
        assertThat(calendarRepository.findByUserId(created.id())).isPresent();
    }

    @Test
    void calendarInsertFailureRollsBackTheAlreadyInsertedUser() {
        CreateUserRequest request = CreateUserRequest.builder().name("Ada").email("ada@example.com").timeZone("UTC").build();
        jdbcTemplate.execute("ALTER TABLE calendars ADD CONSTRAINT integration_reject_calendar CHECK (false)");
        try {
            assertThatThrownBy(() -> userService.create(request)).isInstanceOf(DataIntegrityViolationException.class);
        } finally {
            jdbcTemplate.execute("ALTER TABLE calendars DROP CONSTRAINT integration_reject_calendar");
        }

        assertThat(userRepository.count()).isZero();
        assertThat(calendarRepository.count()).isZero();
        UserResponse retried = userService.create(request);
        assertThat(userService.get(retried.id())).isEqualTo(retried);
        assertThat(calendarRepository.findByUserId(retried.id())).isPresent();
    }

    @Test
    void normalizedEmailConflictDoesNotCreateAnOrphanUserOrCalendar() {
        UserResponse original = userService.create(CreateUserRequest.builder().name("Ada").email("ada@example.com").timeZone("UTC").build());

        assertThatThrownBy(() -> userService.create(CreateUserRequest.builder().name("Other user")
                .email("  ADA@EXAMPLE.COM  ").timeZone("UTC").build())).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(userRepository.count()).isEqualTo(1);
        assertThat(calendarRepository.count()).isEqualTo(1);
        assertThat(userService.get(original.id())).isEqualTo(original);
        assertThat(calendarRepository.findByUserId(original.id())).isPresent();
    }
}
