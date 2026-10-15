package com.src.minidoodle.integration.service.meeting;

import com.fasterxml.jackson.databind.JsonNode;
import com.src.minidoodle.db.model.Calendar;
import com.src.minidoodle.db.model.TimeSlot;
import com.src.minidoodle.db.model.User;
import com.src.minidoodle.dto.request.CreateMeetingRequest;
import com.src.minidoodle.enums.SlotStatus;
import com.src.minidoodle.integration.AbstractMultiInstanceIntegrationTest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.Builder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Exercises real HTTP traffic across two JVMs; latency is measured without machine-specific thresholds. */
@Tag("load")
class MeetingLoadIntegrationTest extends AbstractMultiInstanceIntegrationTest {
    private static final int WORKERS = 16;
    private static final int PAIRS = 100;
    private static final int CONTENDERS = 32;
    private final ConcurrentLinkedQueue<RequestSample> samples = new ConcurrentLinkedQueue<>();

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void concurrentTrafficAcrossTwoInstancesPreservesAtomicBookingsAndRestoresAvailability() throws Exception {
        List<UserPair> pairs = seedScaleFixture();
        assertThat(userRepository.count()).isEqualTo(200);
        assertThat(timeSlotRepository.count()).isEqualTo(5000);
        long startedAt = System.nanoTime();

        List<Callable<BookedMeeting>> bookings = new ArrayList<>();
        for (int index = 0; index < pairs.size(); index++) {
            int worker = index;
            UserPair pair = pairs.get(index);
            bookings.add(() -> bookAndRead(pair, worker));
        }
        List<BookedMeeting> meetings = runConcurrently(bookings);
        assertPersistenceState(PAIRS, PAIRS);

        List<Callable<Void>> cancellations = new ArrayList<>();
        for (int index = 0; index < meetings.size(); index++) {
            int worker = index;
            BookedMeeting meeting = meetings.get(index);
            cancellations.add(() -> {
                cancelAndRead(meeting, worker);
                return null;
            });
        }
        runConcurrently(cancellations);
        assertPersistenceState(PAIRS, 0);

        UserPair hotPair = pairs.getFirst();
        List<Callable<HttpResponse<String>>> contenders = new ArrayList<>();
        for (int index = 0; index < CONTENDERS; index++) {
            int worker = index;
            contenders.add(() -> request(instance(worker), "POST", "/meetings", meetingRequest(hotPair, 1), "contended-booking"));
        }
        List<HttpResponse<String>> outcomes = runConcurrently(contenders);
        assertThat(outcomes).extracting(HttpResponse::statusCode).containsOnly(201, 409);
        assertThat(outcomes.stream().filter(response -> response.statusCode() == 201).count()).isEqualTo(1);
        assertThat(outcomes.stream().filter(response -> response.statusCode() == 409).count()).isEqualTo(CONTENDERS - 1);
        for (HttpResponse<String> response : outcomes) {
            if (response.statusCode() == 409) {
                assertThat(json(response).path("code").asText()).isEqualTo("MEETING_CONFLICT");
            }
        }
        assertPersistenceState(PAIRS + 1, 1);
        HttpResponse<String> winner = outcomes.stream().filter(response -> response.statusCode() == 201).findFirst().orElseThrow();
        UUID winnerId = UUID.fromString(json(winner).path("id").asText());
        assertMeeting(json(winner), hotPair, 1, "ACTIVE");
        assertAvailability(secondInstance, hotPair, 1, true, false);
        expect(firstInstance, "DELETE", "/meetings/" + winnerId, null, "contended-cancellation", 204);
        assertAvailability(secondInstance, hotPair, 1, true, true);
        assertPersistenceState(PAIRS + 1, 0);
        assertThat(samples).hasSize(835);
        writeMeasurements(startedAt);
    }

    private BookedMeeting bookAndRead(UserPair pair, int worker) throws Exception {
        HttpResponse<String> created = expect(instance(worker), "POST", "/meetings", meetingRequest(pair, 0), "booking", 201);
        JsonNode meeting = json(created);
        assertMeeting(meeting, pair, 0, "ACTIVE");
        UUID meetingId = UUID.fromString(meeting.path("id").asText());
        HttpResponse<String> read = expect(instance(worker + 1), "GET", "/meetings/" + meetingId, null, "meeting-read", 200);
        assertThat(json(read)).isEqualTo(meeting);
        assertAvailability(instance(worker), pair, 0, false, false);
        assertAvailability(instance(worker + 1), pair, 0, true, false);
        return BookedMeeting.builder().id(meetingId).pair(pair).build();
    }

    private void cancelAndRead(BookedMeeting meeting, int worker) throws Exception {
        expect(instance(worker + 1), "DELETE", "/meetings/" + meeting.id(), null, "cancellation", 204);
        HttpResponse<String> read = expect(instance(worker), "GET", "/meetings/" + meeting.id(), null, "history-read", 200);
        assertMeeting(json(read), meeting.pair(), 0, "CANCELLED");
        assertAvailability(instance(worker), meeting.pair(), 0, true, true);
        expect(instance(worker), "DELETE", "/meetings/" + meeting.id(), null, "cancellation-replay", 204);
    }

    private CreateMeetingRequest meetingRequest(UserPair pair, int day) {
        return CreateMeetingRequest.builder().organizerId(pair.organizerId()).slotId(pair.sourceSlotIds().get(day))
                .title("Concurrent design review").description("HTTP load across two application processes")
                .participantIds(List.of(pair.participantId())).build();
    }

    private void assertMeeting(JsonNode meeting, UserPair pair, int day, String status) {
        assertThat(meeting.path("organizerId").asText()).isEqualTo(pair.organizerId().toString());
        assertThat(meeting.path("slotId").asText()).isEqualTo(pair.sourceSlotIds().get(day).toString());
        assertThat(meeting.path("title").asText()).isEqualTo("Concurrent design review");
        assertThat(meeting.path("description").asText()).isEqualTo("HTTP load across two application processes");
        assertThat(Instant.parse(meeting.path("startsAt").asText())).isEqualTo(boundary(day, 10));
        assertThat(Instant.parse(meeting.path("endsAt").asText())).isEqualTo(boundary(day, 11));
        List<String> participants = new ArrayList<>();
        meeting.path("participantIds").forEach(participant -> participants.add(participant.asText()));
        assertThat(participants).containsExactlyInAnyOrder(pair.organizerId().toString(), pair.participantId().toString());
        assertThat(meeting.path("status").asText()).isEqualTo(status);
    }

    private void assertAvailability(ApplicationInstance instance, UserPair pair, int day, boolean common, boolean free) throws Exception {
        String path = common ? "/availability?userIds=" + pair.organizerId() + "," + pair.participantId()
                : "/users/" + pair.participantId() + "/availability?";
        if (common) {
            path += "&";
        }
        path += "from=" + boundary(day, 9) + "&to=" + boundary(day, 12);
        HttpResponse<String> response = expect(instance, "GET", path, null, common ? "common-availability" : "personal-availability", 200);
        JsonNode availability = json(response);
        assertThat(availability.path(free ? "busy" : "free").size()).isZero();
        JsonNode intervals = availability.path(free ? "free" : "busy");
        assertThat(intervals.size()).isEqualTo(1);
        assertThat(Instant.parse(intervals.get(0).path("startsAt").asText())).isEqualTo(boundary(day, 10));
        assertThat(Instant.parse(intervals.get(0).path("endsAt").asText())).isEqualTo(boundary(day, 11));
    }

    private HttpResponse<String> expect(ApplicationInstance instance, String method, String path, Object body, String operation, int status) throws Exception {
        HttpResponse<String> response = request(instance, method, path, body, operation);
        assertThat(response.statusCode()).as("%s response: %s", operation, response.body()).isEqualTo(status);
        return response;
    }

    private HttpResponse<String> request(ApplicationInstance instance, String method, String path, Object body, String operation) throws Exception {
        long startedAt = System.nanoTime();
        HttpResponse<String> response = send(instance, method, path, body);
        samples.add(RequestSample.builder().operation(operation).instance(instance.applicationName())
                .status(response.statusCode()).elapsedNanos(System.nanoTime() - startedAt).build());
        return response;
    }

    /** Checks persisted rows after each phase, including failed contenders leaving no partial meeting behind. */
    private void assertPersistenceState(int totalMeetings, int activeMeetings) {
        assertThat(meetingRepository.count()).isEqualTo(totalMeetings);
        assertThat(meetingParticipantRepository.count()).isEqualTo(totalMeetings * 2L);
        assertThat(meetingReservationRepository.count()).isEqualTo(activeMeetings * 2L);
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM meetings WHERE cancelled_at IS NULL", Long.class)).isEqualTo((long) activeMeetings);
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM meetings m
                WHERE (SELECT COUNT(*) FROM meeting_participants p WHERE p.meeting_id = m.id) <> 2
                   OR NOT EXISTS (SELECT 1 FROM meeting_participants p
                                  WHERE p.meeting_id = m.id AND p.user_id = m.organizer_user_id)
                   OR (SELECT COUNT(*) FROM meeting_reservations r WHERE r.meeting_id = m.id)
                      <> CASE WHEN m.cancelled_at IS NULL THEN 2 ELSE 0 END
                """, Long.class)).as("complete membership and reservations for every committed meeting").isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM meeting_reservations r
                JOIN meetings m ON m.id = r.meeting_id
                JOIN calendars c ON c.id = r.calendar_id
                WHERE r.starts_at <> m.starts_at OR r.ends_at <> m.ends_at
                   OR NOT EXISTS (SELECT 1 FROM meeting_participants p
                                  WHERE p.meeting_id = m.id AND p.user_id = c.user_id)
                """, Long.class)).as("reservations must match meeting intervals and participants").isZero();
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM meeting_reservations a
                JOIN meeting_reservations b ON a.calendar_id = b.calendar_id AND a.meeting_id < b.meeting_id
                WHERE tstzrange(a.starts_at, a.ends_at, '[)') && tstzrange(b.starts_at, b.ends_at, '[)')
                """, Long.class)).as("no participant has overlapping active meetings").isZero();
    }

    private <T> List<T> runConcurrently(List<Callable<T>> actions) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (Callable<T> action : actions) {
                futures.add(executor.submit(() -> {
                    assertThat(start.await(10, TimeUnit.SECONDS)).as("workload start barrier").isTrue();
                    return action.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(90, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            start.countDown();
            futures.forEach(future -> future.cancel(true));
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).as("load workers terminate").isTrue();
        }
    }

    private List<UserPair> seedScaleFixture() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            List<User> users = new ArrayList<>();
            for (int index = 0; index < PAIRS * 2; index++) {
                users.add(User.builder().name("Load user " + index).email("load-" + index + "@example.com").timeZone("UTC").build());
            }
            userRepository.saveAllAndFlush(users);
            List<Calendar> calendars = users.stream().map(user -> Calendar.builder().user(user).build()).toList();
            calendarRepository.saveAllAndFlush(calendars);
            List<TimeSlot> slots = new ArrayList<>();
            for (Calendar calendar : calendars) {
                for (int day = 0; day < 25; day++) {
                    slots.add(TimeSlot.builder().calendar(calendar).startsAt(boundary(day, 10)).endsAt(boundary(day, 11)).status(SlotStatus.FREE).build());
                }
            }
            timeSlotRepository.saveAllAndFlush(slots);
            List<UserPair> pairs = new ArrayList<>();
            for (int pair = 0; pair < PAIRS; pair++) {
                int organizer = pair * 2;
                List<UUID> sourceIds = slots.subList(organizer * 25, (organizer + 1) * 25).stream().map(TimeSlot::getId).toList();
                pairs.add(UserPair.builder().organizerId(users.get(organizer).getId()).participantId(users.get(organizer + 1).getId()).sourceSlotIds(sourceIds).build());
            }
            return pairs;
        });
    }

    /** Produces comparable local measurements; fixture setup and process startup are outside the measured window. */
    private void writeMeasurements(long startedAt) throws Exception {
        double elapsedSeconds = (System.nanoTime() - startedAt) / 1_000_000_000.0;
        List<Long> elapsed = samples.stream().map(RequestSample::elapsedNanos).sorted().toList();
        Map<String, Object> measurements = new LinkedHashMap<>();
        measurements.put("instances", 2);
        measurements.put("workers", WORKERS);
        measurements.put("users", PAIRS * 2);
        measurements.put("slots", 5000);
        measurements.put("requests", samples.size());
        measurements.put("elapsedSeconds", elapsedSeconds);
        measurements.put("requestsPerSecond", samples.size() / elapsedSeconds);
        measurements.put("p95Milliseconds", elapsed.get((int) Math.ceil(elapsed.size() * 0.95) - 1) / 1_000_000.0);
        measurements.put("maxMilliseconds", elapsed.getLast() / 1_000_000.0);
        measurements.put("requestsByOperation", samples.stream().collect(Collectors.groupingBy(RequestSample::operation, TreeMap::new, Collectors.counting())));
        measurements.put("responsesByStatus", samples.stream().collect(Collectors.groupingBy(RequestSample::status, TreeMap::new, Collectors.counting())));
        measurements.put("requestsByInstance", samples.stream().collect(Collectors.groupingBy(RequestSample::instance, TreeMap::new, Collectors.counting())));
        Path report = Path.of("target", "load-test-results.json");
        Files.createDirectories(report.getParent());
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(report.toFile(), measurements);
    }

    private ApplicationInstance instance(int index) {
        return index % 2 == 0 ? firstInstance : secondInstance;
    }

    private static Instant boundary(int day, int hour) {
        return TEST_DATE.plusDays(day).atTime(hour, 0).toInstant(ZoneOffset.UTC);
    }

    @Builder
    private record UserPair(UUID organizerId, UUID participantId, List<UUID> sourceSlotIds) { }

    @Builder
    private record BookedMeeting(UUID id, UserPair pair) { }

    @Builder
    private record RequestSample(String operation, String instance, int status, long elapsedNanos) { }
}
