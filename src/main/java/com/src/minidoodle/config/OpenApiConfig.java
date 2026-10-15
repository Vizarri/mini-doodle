package com.src.minidoodle.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

/**
 * Declares service metadata for the generated OpenAPI document and Swagger UI.
 * Endpoint operations and request/response schemas are documented on controllers and DTOs.
 */
@Configuration
@OpenAPIDefinition(info = @Info(title = "Mini Doodle API", version = "1.0", description = "Persisted availability and atomic meeting booking. Trusted simulation without authentication. "
        + "Timestamps require explicit UTC offsets and must fall within UTC years 0001 through 9999. Intervals are half-open and limited to 31 days."))
public class OpenApiConfig {
}
