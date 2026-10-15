package com.src.minidoodle.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;

@Builder
@Schema(description = "Consistent API error. Use code for programmatic handling; message is human-readable.")
public record ApiError(
        @Schema(example = "400") int status,
        @Schema(example = "VALIDATION_FAILED") String code,
        @Schema(example = "Validation failed") String message,
        @Schema(description = "Request path and optional structured validation details") Object details) {
}
