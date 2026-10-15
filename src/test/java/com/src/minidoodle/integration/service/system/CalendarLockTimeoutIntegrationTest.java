package com.src.minidoodle.integration.service.system;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.src.minidoodle.dto.request.CreateMeetingRequest;
import com.src.minidoodle.dto.response.SlotResponse;
import com.src.minidoodle.dto.response.UserResponse;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.enums.SlotStatus;
import com.src.minidoodle.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies session lock limits, the real PostgreSQL timeout and complete HTTP transaction rollback. */
@AutoConfigureMockMvc
@SpringBootTest(properties = "DB_LOCK_TIMEOUT=200ms")
class CalendarLockTimeoutIntegrationTest extends AbstractIntegrationTest {
    @Autowired private DataSource dataSource;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void everyPhysicalPoolConnectionReceivesTheConfiguredLockTimeout() throws SQLException {
        try (Connection first = dataSource.getConnection(); Connection second = dataSource.getConnection(); Connection third = dataSource.getConnection()) {
            assertThat(List.of(backendId(first), backendId(second), backendId(third))).doesNotHaveDuplicates();
            for (Connection connection : List.of(first, second, third)) {
                try (Statement statement = connection.createStatement(); ResultSet setting = statement.executeQuery("SHOW lock_timeout")) {
                    assertThat(setting.next()).isTrue();
                    assertThat(setting.getString(1)).isEqualTo("200ms");
                }
            }
        }
    }

    @Test
    void timedOutBookingReturnsConflictRollsBackItsLocksAndCanBeRetried() throws Exception {
        UserResponse organizer = createUser();
        UserResponse participant = createUser();
        SlotResponse source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);
        SlotResponse participantSlot = createSlot(participant.id(), "09:00", "12:00", SlotStatus.FREE);
        List<UUID> calendarIds = jdbcTemplate.queryForList("SELECT id FROM calendars WHERE user_id IN (?, ?) ORDER BY id", UUID.class, organizer.id(), participant.id());
        CreateMeetingRequest request = meetingRequest(organizer.id(), source.id(), List.of(participant.id()));
        String requestJson = objectMapper.writeValueAsString(request);

        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try {
                // The booking can lock the first calendar before it waits on the second one.
                lockCalendar(holder, calendarIds.getLast());
                Future<MvcResult> pending = executor.submit(() -> mockMvc.perform(post("/meetings").contentType(MediaType.APPLICATION_JSON).content(requestJson))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value(ApiErrorCode.CONCURRENT_MODIFICATION.name()))
                        .andReturn());
                MvcResult failed = pending.get(3, TimeUnit.SECONDS);

                assertThat(failed.getResolvedException()).isInstanceOf(PessimisticLockingFailureException.class);
                assertThat(sqlState(failed.getResolvedException())).isEqualTo("55P03");
                assertThat(meetingRepository.count()).isZero();
                assertThat(meetingParticipantRepository.count()).isZero();
                assertThat(meetingReservationRepository.count()).isZero();
                assertThat(slotService.list(organizer.id(), instant("08:00"), instant("13:00"), 100, 0)).containsExactly(source);
                assertThat(slotService.list(participant.id(), instant("08:00"), instant("13:00"), 100, 0)).containsExactly(participantSlot);
                assertOnlyHolderRemainsLocked(calendarIds);
            } finally {
                holder.rollback();
            }
        } finally {
            // Releasing the holder first also unblocks JDBC if the configured timeout regresses.
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).as("timed-out request worker terminates").isTrue();
        }

        mockMvc.perform(post("/meetings").contentType(MediaType.APPLICATION_JSON).content(requestJson))
                .andExpect(status().isCreated());
        assertThat(meetingRepository.count()).isEqualTo(1);
        assertThat(meetingParticipantRepository.count()).isEqualTo(2);
        assertThat(meetingReservationRepository.count()).isEqualTo(2);
    }

    /** Confirms rollback released the earlier lock while leaving the competing transaction untouched. */
    private void assertOnlyHolderRemainsLocked(List<UUID> calendarIds) throws SQLException {
        try (Connection probe = dataSource.getConnection()) {
            probe.setAutoCommit(false);
            try {
                lockCalendar(probe, calendarIds.getFirst());
                assertThatExceptionOfType(SQLException.class).isThrownBy(() -> lockCalendar(probe, calendarIds.getLast()))
                        .satisfies(exception -> assertThat(exception.getSQLState()).isEqualTo("55P03"));
            } finally {
                probe.rollback();
            }
        }
    }

    /** Uses NOWAIT only for deterministic test probes; application requests use the configured wait limit. */
    private void lockCalendar(Connection connection, UUID calendarId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT id FROM calendars WHERE id = ? FOR NO KEY UPDATE NOWAIT")) {
            statement.setObject(1, calendarId);
            try (ResultSet calendar = statement.executeQuery()) {
                assertThat(calendar.next()).isTrue();
            }
        }
    }

    /** Identifies separate PostgreSQL sessions without relying on pool implementation details. */
    private int backendId(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet backend = statement.executeQuery("SELECT pg_backend_pid()")) {
            assertThat(backend.next()).isTrue();
            return backend.getInt(1);
        }
    }

    /** Reads the database failure itself, so the regression does not depend on wall-clock timing assertions. */
    private String sqlState(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException.getSQLState();
            }
        }
        throw new AssertionError("Expected a PostgreSQL lock timeout cause", failure);
    }
}
