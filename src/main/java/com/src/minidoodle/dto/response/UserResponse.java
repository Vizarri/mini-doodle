package com.src.minidoodle.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.UUID;

@Schema(description = "User identity and presentation preferences.")
public record UserResponse(UUID id, String name, String email, String timeZone) {
}
