package com.src.minidoodle.integration.exception;

import com.src.minidoodle.integration.AbstractIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Verifies that Spring MVC binding and protocol failures use the documented JSON envelope. */
@AutoConfigureMockMvc
class GlobalExceptionHandlerIntegrationTest extends AbstractIntegrationTest {
    @Autowired private MockMvc mockMvc;

    @Test
    void missingRouteReturnsThePublicNotFoundError() throws Exception {
        assertError(mockMvc.perform(get("/missing-resource").accept(MediaType.APPLICATION_JSON)), 404, "NOT_FOUND", "/missing-resource");
    }

    @Test
    void unsupportedMethodReturnsThePublicErrorEnvelope() throws Exception {
        assertError(mockMvc.perform(put("/users").accept(MediaType.APPLICATION_JSON)), 405, "BAD_REQUEST", "/users");
    }

    @Test
    void unsupportedRequestMediaTypeReturnsThePublicErrorEnvelope() throws Exception {
        assertError(mockMvc.perform(post("/users").contentType(MediaType.TEXT_PLAIN).content("invalid").accept(MediaType.APPLICATION_JSON)),
                415, "BAD_REQUEST", "/users");
    }

    @Test
    void unacceptableResponseMediaTypeReturnsThePublicErrorEnvelope() throws Exception {
        String path = "/users/" + UUID.randomUUID();

        assertError(mockMvc.perform(get(path).accept(MediaType.APPLICATION_XML)), 406, "BAD_REQUEST", path);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{", "[]", "null"})
    void missingOrMalformedBodyReturnsAMalformedJsonError(String body) throws Exception {
        assertError(mockMvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(body).accept(MediaType.APPLICATION_JSON)),
                400, "MALFORMED_JSON", "/users");
    }

    @Test
    void malformedPathIdentifierReturnsABindingError() throws Exception {
        assertError(mockMvc.perform(get("/users/not-a-uuid").accept(MediaType.APPLICATION_JSON)), 400, "BAD_REQUEST", "/users/not-a-uuid");
    }

    @Test
    void missingWindowParameterReturnsABindingError() throws Exception {
        String path = "/users/" + UUID.randomUUID() + "/availability";

        assertError(mockMvc.perform(get(path).param("from", instant("09:00").toString()).accept(MediaType.APPLICATION_JSON)),
                400, "BAD_REQUEST", path);
    }

    @ParameterizedTest
    @CsvSource({"limit, 0", "limit, -1", "offset, -1", "offset, 100001"})
    void paginationBeanValidationReportsTheInvalidField(String field, String value) throws Exception {
        String path = "/users/" + UUID.randomUUID() + "/slots";

        assertError(mockMvc.perform(get(path).param("from", instant("09:00").toString()).param("to", instant("10:00").toString())
                .param(field, value).accept(MediaType.APPLICATION_JSON)), 400, "VALIDATION_FAILED", path)
                .andExpect(jsonPath("$.details.fields." + field).isNotEmpty());
    }

    @Test
    void unknownDomainResourcesReturnSpecificNotFoundErrors() throws Exception {
        String userPath = "/users/" + UUID.randomUUID();
        String meetingPath = "/meetings/" + UUID.randomUUID();

        assertError(mockMvc.perform(get(userPath).accept(MediaType.APPLICATION_JSON)), 404, "USER_NOT_FOUND", userPath);
        assertError(mockMvc.perform(get(meetingPath).accept(MediaType.APPLICATION_JSON)), 404, "MEETING_NOT_FOUND", meetingPath);
        assertError(mockMvc.perform(delete(meetingPath).accept(MediaType.APPLICATION_JSON)), 404, "MEETING_NOT_FOUND", meetingPath);
    }

    private ResultActions assertError(ResultActions response, int expectedStatus, String code, String path) throws Exception {
        return response.andExpect(status().is(expectedStatus)).andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.status").value(expectedStatus)).andExpect(jsonPath("$.code").value(code))
                .andExpect(jsonPath("$.message").isNotEmpty()).andExpect(jsonPath("$.details.path").value(path));
    }
}
