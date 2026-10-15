package com.src.minidoodle.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

/** HTTP contracts exercise the real controller, validation, service and database stack. */
@AutoConfigureMockMvc
class HttpContractIntegrationTest extends AbstractIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void usersValidateTheirTimeZoneAndRejectDuplicateEmails() {
        String email = UUID.randomUUID() + "@example.com";
        var created = post("/users", Map.of("name", "Ada", "email", email, "timeZone", "Europe/Berlin"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertThat(body(created).path("timeZone").asText()).isEqualTo("Europe/Berlin");
        assertThat(body(created).path("id").asText()).isNotBlank();

        var duplicate = post("/users", Map.of("name", "Ada", "email", email, "timeZone", "UTC"));
        assertError(duplicate, 409);
        assertThat(body(duplicate).path("code").asText()).isEqualTo("EMAIL_ALREADY_EXISTS");
        assertError(post("/users", Map.of("name", "Ada", "email", UUID.randomUUID() + "@example.com",
                "timeZone", "Mars/Olympus")), 400);
    }

    @Test
    void validationAndMalformedJsonHaveStableMachineReadableErrors() {
        var invalid = post("/users", Map.of("name", "", "email", "invalid-email", "timeZone", "UTC"));
        assertError(invalid, 400);
        assertThat(body(invalid).path("code").asText()).isEqualTo("VALIDATION_FAILED");
        assertThat(body(invalid).path("details").path("fields").has("name")).isTrue();
        assertThat(body(invalid).path("details").path("fields").has("email")).isTrue();

        var unknownField = post("/users", Map.of("name", "Ada", "email", "ada@example.com", "timeZone", "UTC", "unexpected", true));
        assertError(unknownField, 400);
        assertThat(body(unknownField).path("code").asText()).isEqualTo("MALFORMED_JSON");
    }

    @Test
    void slotsSupportCrudAndAdjacentIntervalsButRejectOverlap() {
        String user = user();
        JsonNode first = slot(user, "09:00", "10:00", "FREE");
        JsonNode second = slot(user, "10:00", "11:00", "FREE");
        assertError(post("/users/" + user + "/slots", slotBody("09:30", "10:30", "FREE")), 409);

        var updated = request(HttpMethod.PATCH, slotPath(user, first), slotBody("08:30", "10:00", "BUSY"));
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(body(updated).path("status").asText()).isEqualTo("BUSY");
        assertInstant(body(updated).path("startsAt"), at("08:30"));
        assertError(request(HttpMethod.PATCH, slotPath(user, first), slotBody("09:00", "10:30", "BUSY")), 409);

        var listed = get("/users/" + user + "/slots?from=" + at("08:00") + "&to=" + at("12:00") + "&limit=1&offset=1");
        assertThat(listed.getStatusCode().value()).isEqualTo(200);
        assertThat(body(listed).size()).isEqualTo(1);
        assertThat(body(listed).get(0).path("id").asText()).isEqualTo(id(second));

        assertThat(request(HttpMethod.DELETE, slotPath(user, first), null).getStatusCode().value()).isEqualTo(204);
        assertThat(body(get("/users/" + user + "/slots?from=" + at("08:00") + "&to=" + at("12:00"))).size()).isEqualTo(1);
    }

    @Test
    void slotsRequirePositiveDurationAndExplicitTimeOffsets() {
        String user = user();
        assertError(post("/users/" + user + "/slots", slotBody("09:00", "09:00", "FREE")), 400);
        assertError(post("/users/" + user + "/slots", slotBody("10:00", "09:00", "FREE")), 400);
        assertError(post("/users/" + user + "/slots", Map.of("startsAt", "2035-06-01T09:00:00",
                "endsAt", at("10:00"), "status", "FREE")), 400);

        var created = post("/users/" + user + "/slots", Map.of("startsAt", "2035-06-01T11:00:00+02:00",
                "endsAt", "2035-06-01T12:00:00+02:00", "status", "FREE"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertInstant(body(created).path("startsAt"), at("09:00"));
        assertError(get("/users/" + user + "/availability?from=" + at("11:00") + "&to=" + at("09:00")), 400);
        assertError(get("/users/" + user + "/slots?from=" + at("08:00") + "&to=" + at("12:00") + "&limit=501"), 400);
    }

    @Test
    void numericAndQuotedEpochTimestampsAreRejected() {
        String user = user();
        long startEpoch = OffsetDateTime.parse(at("09:00")).toEpochSecond();
        long endEpoch = OffsetDateTime.parse(at("10:00")).toEpochSecond();
        for (Object start : List.of(startEpoch, Long.toString(startEpoch))) {
            assertError(post("/users/" + user + "/slots", Map.of(
                    "startsAt", start, "endsAt", at("10:00"), "status", "FREE")), 400);
        }
        for (Object end : List.of(endEpoch, Long.toString(endEpoch))) {
            assertError(post("/users/" + user + "/slots", Map.of(
                    "startsAt", at("09:00"), "endsAt", end, "status", "FREE")), 400);
        }
    }

    @Test
    void boundedRequestsRejectExcessiveTimeRangesAndParticipantCounts() {
        String organizer = user();
        JsonNode source = slot(organizer, "10:00", "11:00", "FREE");
        List<String> tooManyParticipants = java.util.stream.IntStream.range(0, 50)
                .mapToObj(ignored -> UUID.randomUUID().toString()).toList();
        // The organizer counts toward the limit, even when omitted from participantIds.
        assertError(post("/meetings", meetingBody(organizer, source, tooManyParticipants)), 400);
        String tooManyUsers = String.join(",", tooManyParticipants) + "," + organizer;
        assertError(get("/availability?userIds=" + tooManyUsers + "&from=" + at("09:00") + "&to=" + at("12:00")), 400);

        assertError(post("/users/" + organizer + "/slots", Map.of("startsAt", "2035-07-01T00:00:00Z",
                "endsAt", "2035-08-02T00:00:00Z", "status", "FREE")), 400);
        String oversizedRange = "?from=2035-06-01T00:00:00Z&to=2035-07-03T00:00:00Z";
        assertError(get("/users/" + organizer + "/availability" + oversizedRange), 400);
        assertError(get("/users/" + organizer + "/slots" + oversizedRange), 400);
        assertThat(get("/users/" + organizer + "/availability?from=2035-06-01T00:00:00Z&to=2035-07-02T00:00:00Z")
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void meetingReservesOnlyItsIntervalWithinALongerParticipantSlot() {
        String organizer = user();
        String participant = user();
        JsonNode source = slot(organizer, "10:00", "11:00", "FREE");
        slot(participant, "09:00", "12:00", "FREE");

        JsonNode meeting = book(organizer, source, List.of(participant));
        assertThat(strings(meeting.path("participantIds"))).containsExactlyInAnyOrder(organizer, participant);
        assertThat(meeting.path("status").asText()).isEqualTo("ACTIVE");
        assertInstant(meeting.path("startsAt"), at("10:00"));
        assertInstant(meeting.path("endsAt"), at("11:00"));
        assertIntervals(availability(participant).path("free"), "09:00", "10:00", "11:00", "12:00");
        assertIntervals(availability(participant).path("busy"), "10:00", "11:00");
        assertIntervals(availability(organizer).path("free"));

        assertError(request(HttpMethod.PATCH, slotPath(organizer, source), slotBody("10:00", "11:00", "BUSY")), 409);
        assertError(request(HttpMethod.DELETE, slotPath(organizer, source), null), 409);
    }

    @Test
    void adjacentFreeSlotsTogetherCoverAMeeting() {
        String organizer = user();
        String participant = user();
        JsonNode source = slot(organizer, "09:00", "11:00", "FREE");
        slot(participant, "09:00", "10:00", "FREE");
        slot(participant, "10:00", "11:00", "FREE");

        book(organizer, source, List.of(participant));
        assertIntervals(availability(participant).path("free"));
        assertIntervals(availability(participant).path("busy"), "09:00", "11:00");
    }

    @Test
    void gapsInDeclaredAvailabilityPreventBooking() {
        String organizer = user();
        String participant = user();
        JsonNode source = slot(organizer, "09:00", "11:00", "FREE");
        slot(participant, "09:00", "10:00", "FREE");
        slot(participant, "10:30", "11:00", "FREE");

        assertError(post("/meetings", meetingBody(organizer, source, List.of(participant))), 409);
        assertIntervals(availability(participant).path("free"), "09:00", "10:00", "10:30", "11:00");
    }

    @Test
    void backToBackMeetingsDoNotConflictAndBusyIntervalsAreMerged() {
        String firstOrganizer = user();
        String secondOrganizer = user();
        String participant = user();
        JsonNode firstSource = slot(firstOrganizer, "09:00", "10:00", "FREE");
        JsonNode secondSource = slot(secondOrganizer, "10:00", "11:00", "FREE");
        slot(participant, "09:00", "12:00", "FREE");

        book(firstOrganizer, firstSource, List.of(participant));
        book(secondOrganizer, secondSource, List.of(participant));
        assertIntervals(availability(participant).path("free"), "11:00", "12:00");
        assertIntervals(availability(participant).path("busy"), "09:00", "11:00");
    }

    @Test
    void unavailableParticipantRejectsTheEntireBookingWithoutPartialReservations() {
        String organizer = user();
        String freeParticipant = user();
        String busyParticipant = user();
        JsonNode source = slot(organizer, "10:00", "11:00", "FREE");
        slot(freeParticipant, "09:00", "12:00", "FREE");
        slot(busyParticipant, "10:00", "11:00", "BUSY");

        assertError(post("/meetings", meetingBody(organizer, source, List.of(freeParticipant, busyParticipant))), 409);
        assertIntervals(availability(organizer).path("free"), "10:00", "11:00");
        assertIntervals(availability(freeParticipant).path("free"), "09:00", "12:00");
        book(organizer, source, List.of(freeParticipant));
    }

    @Test
    void undeclaredTimeIsNotAvailableAndUnknownParticipantsAreNotFound() {
        String organizer = user();
        String participant = user();
        JsonNode source = slot(organizer, "10:00", "11:00", "FREE");
        assertError(post("/meetings", meetingBody(organizer, source, List.of(participant))), 409);
        assertError(post("/meetings", meetingBody(organizer, source, List.of(UUID.randomUUID().toString()))), 404);
        assertIntervals(availability(participant).path("free"));
        assertIntervals(availability(participant).path("busy"));
    }

    @Test
    void cancellationRestoresAvailabilityAndRetainsMeetingDetails() {
        String organizer = user();
        String participant = user();
        JsonNode source = slot(organizer, "10:00", "11:00", "FREE");
        slot(participant, "09:00", "12:00", "FREE");
        JsonNode meeting = book(organizer, source, List.of(participant));
        String path = "/meetings/" + id(meeting);

        assertThat(request(HttpMethod.DELETE, path, null).getStatusCode().value()).isEqualTo(204);
        assertThat(request(HttpMethod.DELETE, path, null).getStatusCode().value()).isEqualTo(204);
        JsonNode cancelled = body(get(path));
        assertThat(cancelled.path("status").asText()).isEqualTo("CANCELLED");
        assertThat(strings(cancelled.path("participantIds"))).containsExactlyInAnyOrder(organizer, participant);
        assertThat(cancelled.path("title").asText()).isEqualTo("Design review");
        assertIntervals(availability(participant).path("free"), "09:00", "12:00");
        assertIntervals(availability(participant).path("busy"));
        book(organizer, source, List.of(participant));
    }

    @Test
    void commonAvailabilityIntersectsFreeTimeAndClipsToTheRequestedWindow() {
        String first = user();
        String second = user();
        slot(first, "08:00", "10:00", "FREE");
        slot(first, "10:00", "11:00", "FREE");
        slot(first, "11:00", "12:00", "BUSY");
        slot(second, "09:00", "12:00", "FREE");

        var response = get("/availability?userIds=" + first + "," + second
                + "&from=" + at("09:30") + "&to=" + at("11:30"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertIntervals(body(response).path("free"), "09:30", "11:00");
        assertIntervals(body(response).path("busy"), "11:00", "11:30");
    }

    private String user() {
        var response = post("/users", Map.of("name", "Test user", "email", UUID.randomUUID() + "@example.com", "timeZone", "UTC"));
        assertThat(response.getStatusCode().value()).as("create user: %s", response.getBody()).isEqualTo(201);
        return id(body(response));
    }

    private JsonNode slot(String user, String start, String end, String status) {
        var response = post("/users/" + user + "/slots", slotBody(start, end, status));
        assertThat(response.getStatusCode().value()).as("create slot: %s", response.getBody()).isEqualTo(201);
        return body(response);
    }

    private JsonNode book(String organizer, JsonNode source, List<String> participants) {
        var response = post("/meetings", meetingBody(organizer, source, participants));
        assertThat(response.getStatusCode().value()).as("book meeting: %s", response.getBody()).isEqualTo(201);
        return body(response);
    }

    private JsonNode availability(String user) {
        var response = get("/users/" + user + "/availability?from=" + at("08:00") + "&to=" + at("13:00"));
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return body(response);
    }

    private ResponseEntity<JsonNode> post(String path, Object body) {
        return request(HttpMethod.POST, path, body);
    }

    private ResponseEntity<JsonNode> get(String path) {
        return request(HttpMethod.GET, path, null);
    }

    private ResponseEntity<JsonNode> request(HttpMethod method, String path, Object body) {
        try {
            var request = MockMvcRequestBuilders.request(method, path).accept(MediaType.APPLICATION_JSON);
            if (body != null) {
                request.contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body));
            }
            var response = mockMvc.perform(request).andReturn().getResponse();
            HttpHeaders headers = new HttpHeaders();
            response.getHeaderNames().forEach(name -> headers.put(name, new ArrayList<>(response.getHeaders(name))));
            JsonNode content = response.getContentAsByteArray().length == 0
                    ? null : objectMapper.readTree(response.getContentAsByteArray());
            return ResponseEntity.status(response.getStatus()).headers(headers).body(content);
        } catch (Exception exception) {
            throw new AssertionError("HTTP request failed: " + method + " " + path, exception);
        }
    }

    private static Map<String, Object> meetingBody(String organizer, JsonNode source, List<String> participants) {
        return Map.of("organizerId", organizer, "slotId", id(source), "title", "Design review",
                "description", "Review the scheduling API", "participantIds", participants);
    }

    private static Map<String, String> slotBody(String start, String end, String status) {
        return Map.of("startsAt", at(start), "endsAt", at(end), "status", status);
    }

    private static String slotPath(String user, JsonNode slot) {
        return "/users/" + user + "/slots/" + id(slot);
    }

    private static String id(JsonNode node) {
        return node.path("id").asText();
    }

    private static String at(String time) {
        return "2035-06-01T" + time + ":00Z";
    }

    private static JsonNode body(ResponseEntity<JsonNode> response) {
        assertThat(response.getBody()).as("HTTP %s response body", response.getStatusCode()).isNotNull();
        return response.getBody();
    }

    private static void assertError(ResponseEntity<JsonNode> response, int status) {
        assertThat(response.getStatusCode().value()).as("error response: %s", response.getBody()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(body(response).path("status").asInt()).isEqualTo(status);
        assertThat(body(response).path("code").asText()).isNotBlank();
        assertThat(body(response).path("message").asText()).isNotBlank();
        assertThat(body(response).path("details").path("path").asText()).startsWith("/");
    }

    private static void assertIntervals(JsonNode intervals, String... bounds) {
        assertThat(intervals.isArray()).as("interval list: %s", intervals).isTrue();
        assertThat(intervals.size()).isEqualTo(bounds.length / 2);
        for (int i = 0; i < bounds.length / 2; i++) {
            assertInstant(intervals.get(i).path("startsAt"), at(bounds[2 * i]));
            assertInstant(intervals.get(i).path("endsAt"), at(bounds[2 * i + 1]));
        }
    }

    private static void assertInstant(JsonNode actual, String expected) {
        assertThat(OffsetDateTime.parse(actual.asText()).toInstant()).isEqualTo(OffsetDateTime.parse(expected).toInstant());
    }

    private static List<String> strings(JsonNode nodes) {
        List<String> result = new ArrayList<>();
        nodes.forEach(node -> result.add(node.asText()));
        return result;
    }

}
