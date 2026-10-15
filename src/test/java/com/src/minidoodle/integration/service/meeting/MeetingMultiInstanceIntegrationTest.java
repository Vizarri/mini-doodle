package com.src.minidoodle.integration.service.meeting;

import com.src.minidoodle.dto.response.SlotResponse;
import com.src.minidoodle.dto.response.UserResponse;
import com.src.minidoodle.enums.SlotStatus;
import com.src.minidoodle.integration.AbstractMultiInstanceIntegrationTest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises HTTP races between separate JVMs and proves their coordination happens in the shared database. */
class MeetingMultiInstanceIntegrationTest extends AbstractMultiInstanceIntegrationTest {
    @Test
    void separateInstancesCannotBookOverlappingMeetingsForTheSameParticipant() throws Exception {
        UserResponse firstOrganizer = createUser();
        UserResponse secondOrganizer = createUser();
        UserResponse participant = createUser();
        SlotResponse firstSource = createSlot(firstOrganizer.id(), "10:00", "11:00", SlotStatus.FREE);
        SlotResponse secondSource = createSlot(secondOrganizer.id(), "10:00", "11:00", SlotStatus.FREE);
        createSlot(participant.id(), "09:00", "12:00", SlotStatus.FREE);

        List<HttpResponse<String>> responses = raceWhileCalendarIsHeld(participant.id(), "POST", "/meetings",
                meetingRequest(firstOrganizer.id(), firstSource.id(), List.of(participant.id())),
                meetingRequest(secondOrganizer.id(), secondSource.id(), List.of(participant.id())));

        assertThat(responses).extracting(HttpResponse::statusCode).containsExactlyInAnyOrder(201, 409);
        for (HttpResponse<String> response : responses) {
            if (response.statusCode() == 409) {
                assertThat(json(response).path("code").asText()).isEqualTo("MEETING_CONFLICT");
            } else {
                String meetingPath = "/meetings/" + json(response).path("id").asText();
                for (ApplicationInstance instance : List.of(firstInstance, secondInstance)) {
                    HttpResponse<String> fetched = send(instance, "GET", meetingPath, null);
                    assertThat(fetched.statusCode()).isEqualTo(200);
                    assertThat(json(fetched).path("status").asText()).isEqualTo("ACTIVE");
                    assertThat(json(fetched).path("participantIds").size()).isEqualTo(2);
                }
            }
        }
        assertThat(meetingRepository.count()).isEqualTo(1);
        assertThat(meetingParticipantRepository.count()).isEqualTo(2);
        assertThat(meetingReservationRepository.count()).isEqualTo(2);
    }

    @Test
    void separateInstancesCancelIdempotentlyAndCanImmediatelyReuseTheReleasedTime() throws Exception {
        UserResponse organizer = createUser();
        UserResponse participant = createUser();
        SlotResponse source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);
        createSlot(participant.id(), "09:00", "12:00", SlotStatus.FREE);
        HttpResponse<String> created = send(firstInstance, "POST", "/meetings", meetingRequest(organizer.id(), source.id(), List.of(participant.id())));
        assertThat(created.statusCode()).isEqualTo(201);
        String meetingPath = "/meetings/" + json(created).path("id").asText();

        List<HttpResponse<String>> cancelled = raceWhileCalendarIsHeld(participant.id(), "DELETE", meetingPath, null, null);

        assertThat(cancelled).extracting(HttpResponse::statusCode).containsExactly(204, 204);
        assertThat(meetingRepository.count()).isEqualTo(1);
        assertThat(meetingParticipantRepository.count()).isEqualTo(2);
        assertThat(meetingReservationRepository.count()).isZero();
        for (ApplicationInstance instance : List.of(firstInstance, secondInstance)) {
            HttpResponse<String> fetched = send(instance, "GET", meetingPath, null);
            assertThat(fetched.statusCode()).isEqualTo(200);
            assertThat(json(fetched).path("status").asText()).isEqualTo("CANCELLED");
            assertThat(json(fetched).path("participantIds").size()).isEqualTo(2);
        }

        HttpResponse<String> replacement = send(secondInstance, "POST", "/meetings", meetingRequest(organizer.id(), source.id(), List.of(participant.id())));
        assertThat(replacement.statusCode()).isEqualTo(201);
        assertThat(send(firstInstance, "DELETE", meetingPath, null).statusCode()).isEqualTo(204);
        String replacementPath = "/meetings/" + json(replacement).path("id").asText();
        assertThat(json(send(firstInstance, "GET", replacementPath, null)).path("status").asText()).isEqualTo("ACTIVE");
        assertThat(meetingRepository.count()).isEqualTo(2);
        assertThat(meetingParticipantRepository.count()).isEqualTo(4);
        assertThat(meetingReservationRepository.count()).isEqualTo(2);
    }

    /** Holds a real coordination row until both processes are observed waiting, then lets PostgreSQL order them. */
    private List<HttpResponse<String>> raceWhileCalendarIsHeld(UUID userId, String method, String path, Object firstBody, Object secondBody)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        List<Future<HttpResponse<String>>> pending = new ArrayList<>();
        try {
            try (Connection holder = Objects.requireNonNull(jdbcTemplate.getDataSource()).getConnection()) {
                holder.setAutoCommit(false);
                try {
                    try (PreparedStatement statement = holder.prepareStatement("SELECT id FROM calendars WHERE user_id = ? FOR NO KEY UPDATE")) {
                        statement.setObject(1, userId);
                        try (ResultSet calendar = statement.executeQuery()) {
                            assertThat(calendar.next()).isTrue();
                        }
                    }
                    pending.add(executor.submit(() -> send(firstInstance, method, path, firstBody)));
                    pending.add(executor.submit(() -> send(secondInstance, method, path, secondBody)));
                    long observationDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                    // The observer uses separate autocommit connections, so PostgreSQL statistics are not cached by the holder transaction.
                    awaitCalendarWait(firstInstance, pending.getFirst(), observationDeadline);
                    awaitCalendarWait(secondInstance, pending.getLast(), observationDeadline);
                } finally {
                    holder.rollback();
                }
            }
            return List.of(pending.getFirst().get(15, TimeUnit.SECONDS), pending.getLast().get(15, TimeUnit.SECONDS));
        } finally {
            try {
                // Cancelling the HTTP client does not stop the server transaction; drain it before another fixture truncates the tables.
                for (Future<?> request : pending) {
                    try {
                        request.get(15, TimeUnit.SECONDS);
                    } catch (ExecutionException exception) {
                        // A request failure is reported when collecting results or observing its lock wait.
                    }
                }
            } finally {
                executor.shutdownNow();
                assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
            }
        }
    }
}
