package com.src.minidoodle.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI miniDoodleOpenApi() {
        return new OpenAPI().info(new Info()
                .title("Mini Doodle API")
                .version("1.0")
                .description("Persisted availability and atomic meeting booking. Trusted simulation without authentication. "
                        + "Timestamps require explicit UTC offsets and must fall within UTC years 0001 through 9999. "
                        + "Intervals are half-open and limited to 31 days."));
    }
}
