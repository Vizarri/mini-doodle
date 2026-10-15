package com.src.minidoodle.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.src.minidoodle.enums.ApiErrorCode;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import java.util.stream.Stream;
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
        assertError(duplicate, 409, ApiErrorCode.EMAIL_ALREADY_EXISTS);
        assertError(post("/users", Map.of("name", "Ada", "email", UUID.randomUUID() + "@example.com",
                "timeZone", "Mars/Olympus")), 400, ApiErrorCode.INVALID_TIME_ZONE);
    }

    @Test
    void validationAndMalformedJsonHaveStableMachineReadableErrors() {
        var invalid = post("/users", Map.of("name", "", "email", "invalid-email", "timeZone", "UTC"));
        assertError(invalid, 400, ApiErrorCode.VALIDATION_FAILED);
        assertThat(body(invalid).path("details").path("fields").has("name")).isTrue();
        assertThat(body(invalid).path("details").path("fields").has("email")).isTrue();

        var unknownField = post("/users", Map.of("name", "Ada", "email", "ada@example.com", "timeZone", "UTC", "unexpected", true));
        assertError(unknownField, 400, ApiErrorCode.MALFORMED_JSON);
    }

    @Test
    void slotsSupportCrudAndAdjacentIntervalsButRejectOverlap() {
        String user = user();
        JsonNode first = slot(user, "09:00", "10:00", "FREE");
        JsonNode second = slot(user, "10:00", "11:00", "FREE");
        assertError(post("/users/" + user + "/slots", slotBody("09:30", "10:30", "FREE")), 409, ApiErrorCode.SLOT_OVERLAP);

        var updated = request(HttpMethod.PATCH, slotPath(user, first), slotBody("08:30", "10:00", "BUSY"));
        assertThat(updated.getStatusCode().value()).isEqualTo(200);
        assertThat(body(updated).path("status").asText()).isEqualTo("BUSY");
        assertInstant(body(updated).path("startsAt"), at("08:30"));
        assertError(request(HttpMethod.PATCH, slotPath(user, first), slotBody("09:00", "10:30", "BUSY")), 409, ApiErrorCode.SLOT_OVERLAP);

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
        assertError(post("/users/" + user + "/slots", slotBody("09:00", "09:00", "FREE")), 400, ApiErrorCode.INVALID_TIME_RANGE);
        assertError(post("/users/" + user + "/slots", slotBody("10:00", "09:00", "FREE")), 400, ApiErrorCode.INVALID_TIME_RANGE);
        assertError(post("/users/" + user + "/slots", Map.of("startsAt", "2035-06-01T09:00:00",
                "endsAt", at("10:00"), "status", "FREE")), 400, ApiErrorCode.INVALID_TIME_RANGE);

        var created = post("/users/" + user + "/slots", Map.of("startsAt", "2035-06-01T11:00:00+02:00",
                "endsAt", "2035-06-01T12:00:00+02:00", "status", "FREE"));
        assertThat(created.getStatusCode().value()).isEqualTo(201);
        assertInstant(body(created).path("startsAt"), at("09:00"));
        assertError(get("/users/" + user + "/availability?from=" + at("11:00") + "&to=" + at("09:00")), 400, ApiErrorCode.INVALID_TIME_RANGE);
        assertError(get("/users/" + user + "/slots?from=" + at("08:00") + "&to=" + at("12:00") + "&limit=501"), 400, ApiErrorCode.VALIDATION_FAILED);
    }

    @Test
    void numericAndQuotedEpochTimestampsAreRejected() {
        String user = user();
        long startEpoch = OffsetDateTime.parse(at("09:00")).toEpochSecond();
        long endEpoch = OffsetDateTime.parse(at("10:00")).toEpochSecond();
        for (Object start : List.of(startEpoch, startEpoch + 0.5, Long.toString(startEpoch), Double.toString(startEpoch + 0.5))) {
            assertError(post("/users/" + user + "/slots", Map.of(
                    "startsAt", start, "endsAt", at("10:00"), "status", "FREE")), 400, ApiErrorCode.INVALID_TIME_RANGE);
        }
        for (Object end : List.of(endEpoch, endEpoch + 0.5, Long.toString(endEpoch), Double.toString(endEpoch + 0.5))) {
            assertError(post("/users/" + user + "/slots", Map.of(
                    "startsAt", at("09:00"), "endsAt", end, "status", "FREE")), 400, ApiErrorCode.INVALID_TIME_RANGE);
        }
    }

    @ParameterizedTest(name = "numeric enum value {0} cannot create or modify a slot")
    @MethodSource("numericSlotStatuses")
    void numericSlotStatusesAreRejectedWithoutMutation(Object status) {
        String user = user();
        JsonNode original = slot(user, "10:00", "11:00", "FREE");
        Map<String, Object> invalid = Map.of("startsAt", at("12:00"), "endsAt", at("13:00"), "status", status);

        assertError(post("/users/" + user + "/slots", invalid), 400, ApiErrorCode.MALFORMED_JSON);
        assertError(request(HttpMethod.PATCH, slotPath(user, original), invalid), 400, ApiErrorCode.MALFORMED_JSON);
        assertOnlyOriginalSlot(user, original);
    }

    private static Stream<Arguments> numericSlotStatuses() {
        return Stream.of(Arguments.of(0), Arguments.of(1), Arguments.of("0"), Arguments.of("1"));
    }

    @ParameterizedTest(name = "{0} is rejected by commands and every window query")
    @MethodSource("unsupportedUtcRanges")
    void unsupportedUtcRangesAreRejectedEverywhere(String scenario, String from, String to) {
        String user = user();
        JsonNode original = slot(user, "10:00", "11:00", "FREE");
        Map<String, String> invalid = Map.of("startsAt", from, "endsAt", to, "status", "FREE");

        assertError(post("/users/" + user + "/slots", invalid), 400, ApiErrorCode.INVALID_TIME_RANGE);
        assertError(request(HttpMethod.PATCH, slotPath(user, original), invalid), 400, ApiErrorCode.INVALID_TIME_RANGE);
        for (String path : List.of("/users/" + user + "/slots", "/users/" + user + "/availability",
                "/availability?userIds=" + user)) {
            assertError(getWindow(path, from, to), 400, ApiErrorCode.INVALID_TIME_RANGE);
        }
        assertOnlyOriginalSlot(user, original);
    }

    private static Stream<Arguments> unsupportedUtcRanges() {
        return Stream.of(
                Arguments.of("year above the database range", "+300000-01-01T10:00:00Z", "+300000-01-01T11:00:00Z"),
                Arguments.of("year zero", "0000-01-01T10:00:00Z", "0000-01-01T11:00:00Z"),
                Arguments.of("offset moves the start below UTC year one", "0001-01-01T00:00:00+01:00", "0001-01-01T02:00:00+01:00"),
                Arguments.of("offset moves the interval above UTC year 9999", "9999-12-31T22:00:00-02:00", "9999-12-31T23:00:00-02:00"),
                Arguments.of("end equals the exclusive UTC upper bound", "9999-12-31T23:00:00Z", "+10000-01-01T00:00:00Z"));
    }

    @ParameterizedTest(name = "{0} remains usable for persisted slots and window queries")
    @MethodSource("supportedUtcBoundaryRanges")
    void supportedUtcBoundaryRangesRoundTrip(String scenario, String from, String to) {
        String user = user();
        var created = post("/users/" + user + "/slots", Map.of("startsAt", from, "endsAt", to, "status", "FREE"));
        assertThat(created.getStatusCode().value()).as("create at boundary: %s", created.getBody()).isEqualTo(201);
        assertInstant(body(created).path("startsAt"), from);
        assertInstant(body(created).path("endsAt"), to);
        var changed = request(HttpMethod.PATCH, slotPath(user, body(created)),
                Map.of("startsAt", from, "endsAt", to, "status", "BUSY"));
        assertThat(changed.getStatusCode().value()).as("update at boundary: %s", changed.getBody()).isEqualTo(200);

        var listed = getWindow("/users/" + user + "/slots", from, to);
        assertThat(listed.getStatusCode().value()).isEqualTo(200);
        assertThat(body(listed).size()).isEqualTo(1);
        assertThat(body(listed).get(0).path("status").asText()).isEqualTo("BUSY");
        assertInstant(body(listed).get(0).path("startsAt"), from);
        assertInstant(body(listed).get(0).path("endsAt"), to);
        for (String path : List.of("/users/" + user + "/availability", "/availability?userIds=" + user)) {
            var available = getWindow(path, from, to);
            assertThat(available.getStatusCode().value()).as("boundary query: %s", available.getBody()).isEqualTo(200);
            assertInstant(body(available).path("from"), from);
            assertInstant(body(available).path("to"), to);
            assertThat(body(available).path("free").size()).isZero();
            JsonNode busy = body(available).path("busy");
            assertThat(busy.size()).isEqualTo(1);
            assertInstant(busy.get(0).path("startsAt"), from);
            assertInstant(busy.get(0).path("endsAt"), to);
        }
    }

    private static Stream<Arguments> supportedUtcBoundaryRanges() {
        return Stream.of(
                Arguments.of("inclusive UTC lower bound", "0001-01-01T00:00:00Z", "0001-01-01T01:00:00Z"),
                Arguments.of("last two supported microseconds", "9999-12-31T23:59:59.999998Z", "9999-12-31T23:59:59.999999Z"),
                Arguments.of("local year 10000 normalizes into UTC year 9999", "+10000-01-01T00:00:00+01:00", "+10000-01-01T00:59:59.999999+01:00"));
    }

    @Test
    void boundedRequestsRejectExcessiveTimeRangesAndParticipantCounts() {
        String organizer = user();
        JsonNode source = slot(organizer, "10:00", "11:00", "FREE");
        List<String> tooManyParticipants = java.util.stream.IntStream.range(0, 50)
                .mapToObj(ignored -> UUID.randomUUID().toString()).toList();
        // The organizer counts toward the limit, even when omitted from participantIds.
        assertError(post("/meetings", meetingBody(organizer, source, tooManyParticipants)), 400, ApiErrorCode.PARTICIPANT_LIMIT_EXCEEDED);
        String tooManyUsers = String.join(",", tooManyParticipants) + "," + organizer;
        assertError(get("/availability?userIds=" + tooManyUsers + "&from=" + at("09:00") + "&to=" + at("12:00")), 400, ApiErrorCode.BAD_REQUEST);

        assertError(post("/users/" + organizer + "/slots", Map.of("startsAt", "2035-07-01T00:00:00Z",
                "endsAt", "2035-08-02T00:00:00Z", "status", "FREE")), 400, ApiErrorCode.TIME_RANGE_TOO_LARGE);
        String oversizedRange = "?from=2035-06-01T00:00:00Z&to=2035-07-03T00:00:00Z";
        assertError(get("/users/" + organizer + "/availability" + oversizedRange), 400, ApiErrorCode.TIME_RANGE_TOO_LARGE);
        assertError(get("/users/" + organizer + "/slots" + oversizedRange), 400, ApiErrorCode.TIME_RANGE_TOO_LARGE);
        assertThat(get("/users/" + organizer + "/availability?from=2035-06-01T00:00:00Z&to=2035-07-02T00:00:00Z")
                .getStatusCode().value()).isEqualTo(200);
    }

    @Test
    void meetingReservesOnlyItsIntervalWithinALongerParticipantSlot() {
        String organizer = user();
        String participant = user();
        JsonNode source = slot(organizer, "10:00", "11:00", "FREE");
        JsonNode covering = slot(participant, "09:00", "12:00", "FREE");

        JsonNode meeting = book(organizer, source, List.of(participant));
        assertThat(strings(meeting.path("participantIds"))).containsExactlyInAnyOrder(organizer, participant);
        assertThat(meeting.path("status").asText()).isEqualTo("ACTIVE");
        assertInstant(meeting.path("startsAt"), at("10:00"));
        assertInstant(meeting.path("endsAt"), at("11:00"));
        assertIntervals(availability(participant).path("free"), "09:00", "10:00", "11:00", "12:00");
        assertIntervals(availability(participant).path("busy"), "10:00", "11:00");
        assertIntervals(availability(organizer).path("free"));

        assertError(request(HttpMethod.PATCH, slotPath(organizer, source), slotBody("10:00", "11:00", "BUSY")), 409, ApiErrorCode.SLOT_RESERVED);
        assertError(request(HttpMethod.DELETE, slotPath(organizer, source), null), 409, ApiErrorCode.SLOT_RESERVED);
        assertError(request(HttpMethod.PATCH, slotPath(participant, covering), slotBody("09:00", "12:00", "BUSY")),
                409, ApiErrorCode.SLOT_RESERVED);
        assertError(request(HttpMethod.DELETE, slotPath(participant, covering), null), 409, ApiErrorCode.SLOT_RESERVED);
    }

    @Test
    void adjacentFreeSlotsTogetherCoverAMeeting() {
        String organizer = user();
        String participant = user();
        JsonNode source = slot(organizer, "09:00", "11:00", "FREE");
        JsonNode firstCovering = slot(participant, "09:00", "10:00", "FREE");
        JsonNode secondCovering = slot(participant, "10:00", "11:00", "FREE");

        book(organizer, source, List.of(participant));
        assertIntervals(availability(participant).path("free"));
        assertError(request(HttpMethod.DELETE, slotPath(participant, firstCovering), null), 409, ApiErrorCode.SLOT_RESERVED);
        assertError(request(HttpMethod.PATCH, slotPath(participant, secondCovering), slotBody("10:00", "11:00", "BUSY")),
                409, ApiErrorCode.SLOT_RESERVED);
        assertIntervals(availability(participant).path("busy"), "09:00", "11:00");
    }

    @Test
    void gapsInDeclaredAvailabilityPreventBooking() {
        String organizer = user();
        String participant = user();
        JsonNode source = slot(organizer, "09:00", "11:00", "FREE");
        slot(participant, "09:00", "10:00", "FREE");
        slot(participant, "10:30", "11:00", "FREE");

        assertError(post("/meetings", meetingBody(organizer, source, List.of(participant))), 409, ApiErrorCode.AVAILABILITY_REQUIRED);
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

        assertError(post("/meetings", meetingBody(organizer, source, List.of(freeParticipant, busyParticipant))), 409, ApiErrorCode.AVAILABILITY_REQUIRED);
        assertIntervals(availability(organizer).path("free"), "10:00", "11:00");
        assertIntervals(availability(freeParticipant).path("free"), "09:00", "12:00");
        book(organizer, source, List.of(freeParticipant));
    }

    @Test
    void undeclaredTimeIsNotAvailableAndUnknownParticipantsAreNotFound() {
        String organizer = user();
        String participant = user();
        JsonNode source = slot(organizer, "10:00", "11:00", "FREE");
        assertError(post("/meetings", meetingBody(organizer, source, List.of(participant))), 409, ApiErrorCode.AVAILABILITY_REQUIRED);
        assertError(post("/meetings", meetingBody(organizer, source, List.of(UUID.randomUUID().toString()))), 404, ApiErrorCode.USER_NOT_FOUND);
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
        return performRequest(MockMvcRequestBuilders.request(method, path), body);
    }

    private ResponseEntity<JsonNode> getWindow(String path, String from, String to) {
        return performRequest(MockMvcRequestBuilders.get(path).queryParam("from", from).queryParam("to", to), null);
    }

    private ResponseEntity<JsonNode> performRequest(MockHttpServletRequestBuilder request, Object body) {
        try {
            request.accept(MediaType.APPLICATION_JSON);
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
            throw new AssertionError("HTTP request failed", exception);
        }
    }

    private void assertOnlyOriginalSlot(String user, JsonNode original) {
        var listed = getWindow("/users/" + user + "/slots", at("08:00"), at("14:00"));
        assertThat(listed.getStatusCode().value()).isEqualTo(200);
        assertThat(body(listed).size()).isEqualTo(1);
        assertThat(body(listed).get(0)).isEqualTo(original);
        assertThat(timeSlotRepository.count()).as("a rejected command must not insert another interval").isEqualTo(1);
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

    private static void assertError(ResponseEntity<JsonNode> response, int status, ApiErrorCode code) {
        assertThat(response.getStatusCode().value()).as("error response: %s", response.getBody()).isEqualTo(status);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(body(response).path("status").asInt()).isEqualTo(status);
        assertThat(body(response).path("code").asText()).isEqualTo(code.name());
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
