package com.src.minidoodle.integration.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.src.minidoodle.dto.response.SlotResponse;
import com.src.minidoodle.dto.response.UserResponse;
import com.src.minidoodle.enums.SlotStatus;
import com.src.minidoodle.integration.AbstractIntegrationTest;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Exercises request shape limits through HTTP before any scheduling data can be changed. */
@AutoConfigureMockMvc
class RequestValidationIntegrationTest extends AbstractIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void createdUserCanBeReadThroughItsLocationWithNormalizedFields() throws Exception {
        Map<String, String> body = Map.of("name", "  Ada  ", "email", "ADA@EXAMPLE.COM", "timeZone", "Europe/Berlin");

        MockHttpServletResponse created = mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated()).andReturn().getResponse();

        String location = created.getHeader(HttpHeaders.LOCATION);
        assertThat(location).startsWith("/users/");
        mockMvc.perform(get(location)).andExpect(status().isOk()).andExpect(jsonPath("$.name").value("Ada"))
                .andExpect(jsonPath("$.email").value("ada@example.com")).andExpect(jsonPath("$.timeZone").value("Europe/Berlin"))
                .andExpect(jsonPath("$.calendar").doesNotExist()).andExpect(jsonPath("$.calendarId").doesNotExist());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidMeetingFields")
    void invalidMeetingFieldsDoNotCreateAnyMeetingRows(String scenario, String field, Object value) throws Exception {
        UserResponse organizer = createUser();
        SlotResponse source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("organizerId", organizer.id());
        body.put("slotId", source.id());
        body.put("title", "Planning");
        body.put("description", "Notes");
        body.put("participantIds", List.of());
        body.put(field, value);

        mockMvc.perform(post("/meetings").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields").isMap());

        assertThat(meetingRepository.count()).isZero();
        assertThat(meetingParticipantRepository.count()).isZero();
        assertThat(meetingReservationRepository.count()).isZero();
        assertThat(slotService.list(organizer.id(), instant("09:00"), instant("12:00"), 10, 0)).containsExactly(source);
    }

    private static Stream<Arguments> invalidMeetingFields() {
        return Stream.of(
                Arguments.of("missing organizer", "organizerId", null),
                Arguments.of("missing source", "slotId", null),
                Arguments.of("missing title", "title", null),
                Arguments.of("blank title", "title", " \t "),
                Arguments.of("title exceeds 200 characters", "title", "t".repeat(201)),
                Arguments.of("description exceeds 4000 characters", "description", "d".repeat(4001)),
                Arguments.of("missing participants", "participantIds", null),
                Arguments.of("null participant", "participantIds", Arrays.asList((UUID) null)),
                Arguments.of("participant input exceeds 50 IDs", "participantIds", Collections.nCopies(51, UUID.randomUUID())));
    }

    @Test
    void maximumTitleAndDescriptionLengthsArePersistedAndReturned() throws Exception {
        UserResponse organizer = createUser();
        SlotResponse source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);
        String title = "t".repeat(200);
        String description = "d".repeat(4000);
        Map<String, Object> body = Map.of("organizerId", organizer.id(), "slotId", source.id(), "title", title,
                "description", description, "participantIds", List.of());

        String response = mockMvc.perform(post("/meetings").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.title").value(title))
                .andExpect(jsonPath("$.description").value(description)).andReturn().getResponse().getContentAsString();

        JsonNode created = objectMapper.readTree(response);
        UUID meetingId = UUID.fromString(created.path("id").asText());
        assertThat(meetingService.get(meetingId).title()).isEqualTo(title);
        assertThat(meetingService.get(meetingId).description()).isEqualTo(description);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("missingSlotFields")
    void incompleteSlotDefinitionCannotCreateASlot(String field, Object value) throws Exception {
        UserResponse owner = createUser();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("startsAt", instant("10:00").toString());
        body.put("endsAt", instant("11:00").toString());
        body.put("status", "FREE");
        body.put(field, value);

        mockMvc.perform(post("/users/" + owner.id() + "/slots").contentType(MediaType.APPLICATION_JSON).content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.details.fields['" + field + "']").isNotEmpty());

        assertThat(timeSlotRepository.count()).isZero();
    }

    private static Stream<Arguments> missingSlotFields() {
        return Stream.of(Arguments.of("startsAt", null), Arguments.of("startsAt", " "), Arguments.of("endsAt", null),
                Arguments.of("endsAt", " "), Arguments.of("status", null));
    }
}
