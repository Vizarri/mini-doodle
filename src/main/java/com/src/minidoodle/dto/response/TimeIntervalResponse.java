package com.src.minidoodle.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import java.time.Instant;

@Schema(description = "Half-open interval [startsAt, endsAt), expressed in UTC.")
public record TimeIntervalResponse(Instant startsAt, Instant endsAt) {
}
