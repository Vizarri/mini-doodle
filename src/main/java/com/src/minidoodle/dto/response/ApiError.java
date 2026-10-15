package com.src.minidoodle.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

import java.util.Map;

import static io.swagger.v3.oas.annotations.media.Schema.RequiredMode.REQUIRED;

/**
 * Error envelope shared by HTTP binding, domain validation and persistence failures.
 * @param status HTTP status code matching the response status
 * @param code stable machine-readable error identifier
 * @param message explanation intended for a human reader, not programmatic branching
 * @param details request path and optional structured fields or context
 */
@Builder
@Schema(description = "Consistent API error. Use code for programmatic handling; message is human-readable.")
public record ApiError(
        @Schema(example = "400", requiredMode = REQUIRED) int status,
        @Schema(example = "VALIDATION_FAILED", requiredMode = REQUIRED) String code,
        @Schema(example = "Validation failed", requiredMode = REQUIRED) String message,
        @Schema(description = "Object containing the request path and optional validation details", types = {"object"}, requiredMode = REQUIRED) Map<String, Object> details) {
}
