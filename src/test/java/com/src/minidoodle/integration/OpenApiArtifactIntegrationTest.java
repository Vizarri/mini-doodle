package com.src.minidoodle.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Validates the generated API contract and exports the same canonical document for consumers. */
@AutoConfigureMockMvc
@TestPropertySource(properties = "springdoc.cache.disabled=true")
class OpenApiArtifactIntegrationTest extends AbstractIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void generatesDeterministicOpenApiWithResolvableReferencesAndPublicContracts() throws Exception {
        String first = canonicalDocument(fetchApiDocs());
        assertThat(canonicalDocument(fetchApiDocs())).isEqualTo(first);
        JsonNode document = objectMapper.readTree(first);
        assertThat(document.path("openapi").asText()).startsWith("3.");
        assertThat(document.path("info").path("title").asText()).isNotBlank();
        assertThat(document.path("info").path("version").asText()).isNotBlank();

        List<Operation> operations = List.of(
                new Operation("/users", "post", "userCreate", "201"),
                new Operation("/users/{userId}", "get", "userGet", "200"),
                new Operation("/users/{userId}/slots", "post", "slotCreate", "201"),
                new Operation("/users/{userId}/slots", "get", "slotList", "200"),
                new Operation("/users/{userId}/slots/{slotId}", "patch", "slotUpdate", "200"),
                new Operation("/users/{userId}/slots/{slotId}", "delete", "slotDelete", "204"),
                new Operation("/meetings", "post", "meetingCreate", "201"),
                new Operation("/meetings/{meetingId}", "get", "meetingGet", "200"),
                new Operation("/meetings/{meetingId}", "delete", "meetingCancel", "204"),
                new Operation("/users/{userId}/availability", "get", "availabilityPersonal", "200"),
                new Operation("/availability", "get", "availabilityCommon", "200"));
        Set<String> operationIds = new HashSet<>();
        for (Operation expected : operations) {
            JsonNode operation = document.path("paths").path(expected.path()).path(expected.method());
            assertThat(operation.isMissingNode()).as("%s %s", expected.method(), expected.path()).isFalse();
            assertThat(operation.path("operationId").asText()).isEqualTo(expected.id());
            assertThat(operationIds.add(expected.id())).as("unique operationId").isTrue();
            assertThat(operation.path("responses").has(expected.successStatus())).isTrue();
            for (String code : List.of("400", "404", "409", "500")) {
                JsonNode errorSchema = operation.path("responses").path(code).path("content")
                        .path("application/json").path("schema");
                assertThat(errorSchema.path("$ref").asText())
                        .as("%s %s response %s", expected.method(), expected.path(), code)
                        .isEqualTo("#/components/schemas/ApiError");
            }
        }
        assertThat(fieldNames(document.path("paths"))).containsExactlyInAnyOrderElementsOf(
                operations.stream().map(Operation::path).collect(java.util.stream.Collectors.toSet()));
        JsonNode schemas = document.path("components").path("schemas");
        assertThat(schemas.has("Calendar")).as("calendar remains an internal domain entity").isFalse();
        assertRequired(schemas.path("CreateUserRequest"), "name", "email", "timeZone");
        assertRequired(schemas.path("SlotUpsertRequest"), "startsAt", "endsAt", "status");
        assertRequired(schemas.path("CreateMeetingRequest"), "organizerId", "slotId", "title", "participantIds");
        for (String field : List.of("startsAt", "endsAt")) {
            assertThat(schemas.path("SlotUpsertRequest").path("properties").path(field).path("format").asText())
                    .isEqualTo("date-time");
        }
        assertThat(fieldNames(schemas.path("ApiError").path("properties")))
                .containsExactlyInAnyOrder("status", "code", "message", "details");
        validateReferences(document, document);

        Path output = Path.of(System.getProperty("openapi.output.file", "target/openapi/openapi.json"));
        Files.createDirectories(output.getParent());
        Files.writeString(output, first, StandardCharsets.UTF_8);
        assertThat(Files.readString(output, StandardCharsets.UTF_8)).isEqualTo(first);
    }

    private String fetchApiDocs() throws Exception {
        return mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    private String canonicalDocument(String raw) throws Exception {
        return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(canonicalize(objectMapper.readTree(raw))) + "\n";
    }

    private JsonNode canonicalize(JsonNode node) {
        if (node.isObject()) {
            var result = JsonNodeFactory.instance.objectNode();
            var fields = new TreeMap<String, JsonNode>();
            node.properties().forEach(entry -> fields.put(entry.getKey(), entry.getValue()));
            fields.forEach((name, value) -> result.set(name, canonicalize(value)));
            return result;
        }
        if (node.isArray()) {
            var result = JsonNodeFactory.instance.arrayNode();
            node.forEach(value -> result.add(canonicalize(value)));
            return result;
        }
        return node.deepCopy();
    }

    private void validateReferences(JsonNode root, JsonNode current) {
        if (current.isObject()) {
            if (current.has("$ref")) {
                String reference = current.path("$ref").asText();
                assertThat(reference).startsWith("#/");
                assertThat(root.at(reference.substring(1)).isMissingNode()).as("reference %s resolves", reference).isFalse();
            }
            current.elements().forEachRemaining(child -> validateReferences(root, child));
        } else if (current.isArray()) {
            current.forEach(child -> validateReferences(root, child));
        }
    }

    private void assertRequired(JsonNode schema, String... properties) {
        Set<String> required = new HashSet<>();
        schema.path("required").forEach(value -> required.add(value.asText()));
        assertThat(required).containsExactlyInAnyOrder(properties);
    }

    private Set<String> fieldNames(JsonNode object) {
        Set<String> names = new HashSet<>();
        object.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private record Operation(String path, String method, String id, String successStatus) { }
}
