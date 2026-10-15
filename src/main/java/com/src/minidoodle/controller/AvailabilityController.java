package com.src.minidoodle.controller;

import com.src.minidoodle.dto.response.ApiError;
import com.src.minidoodle.dto.response.AvailabilityResponse;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.service.availability.AvailabilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.src.minidoodle.constant.ApiPaths.AVAILABILITY;
import static com.src.minidoodle.constant.ApiPaths.USER_AVAILABILITY;
import static com.src.minidoodle.constant.OpenApiConstants.DESC_CONFLICT;
import static com.src.minidoodle.constant.OpenApiConstants.DESC_INTERNAL_ERROR;
import static com.src.minidoodle.constant.OpenApiConstants.DESC_INVALID_DATA;
import static com.src.minidoodle.constant.OpenApiConstants.DESC_NOT_FOUND;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_200;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_400;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_404;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_409;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_500;

/**
 * Exposes effective availability within bounded windows, omitting undeclared time.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping(produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Availability", description = "Effective free and busy time within a bounded window")
@ApiResponses({
        @ApiResponse(responseCode = HTTP_400, description = DESC_INVALID_DATA,
                content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = HTTP_404, description = DESC_NOT_FOUND,
                content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = HTTP_409, description = DESC_CONFLICT,
                content = @Content(schema = @Schema(implementation = ApiError.class))),
        @ApiResponse(responseCode = HTTP_500, description = DESC_INTERNAL_ERROR,
                content = @Content(schema = @Schema(implementation = ApiError.class)))
})
public class AvailabilityController {
    private final AvailabilityService availabilityService;

    /**
     * Subtracts active reservations from declared free time and merges busy intervals.
     *
     * @param userId user whose availability is requested
     * @param from inclusive window start with an explicit UTC offset
     * @param to exclusive window end with an explicit UTC offset
     * @return merged free and busy intervals clipped to the requested window
     * @throws ApiException if the user is missing or the query window is invalid
     */
    @GetMapping(USER_AVAILABILITY)
    @Operation(operationId = "availabilityPersonal", summary = "Get personal availability", description = "Subtracts reservations from declared free time, merges adjacent intervals and clips to the window. Undeclared time is omitted.")
    @ApiResponse(responseCode = HTTP_200, description = "Personal availability",
            content = @Content(schema = @Schema(implementation = AvailabilityResponse.class)))
    public AvailabilityResponse personal(@PathVariable UUID userId,
            @Parameter(description = "Inclusive start; maximum window 31 days", example = "2030-01-15T09:00:00Z")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "Exclusive end; explicit UTC offset required", example = "2030-01-15T12:00:00Z")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return availabilityService.personal(userId, from.toInstant(), to.toInstant());
    }

    /**
     * Intersects effective free time and unions busy time across the requested users.
     *
     * @param userIds identifiers of the users whose schedules are combined
     * @param from inclusive window start with an explicit UTC offset
     * @param to exclusive window end with an explicit UTC offset
     * @return shared free time and combined busy time clipped to the requested window
     * @throws ApiException if any user is missing or the user count or query window is invalid
     */
    @GetMapping(AVAILABILITY)
    @Operation(operationId = "availabilityCommon", summary = "Get shared availability", description = "Intersects free time and unions busy time across users. Undeclared time does not become free.")
    @ApiResponse(responseCode = HTTP_200, description = "Shared availability",
            content = @Content(schema = @Schema(implementation = AvailabilityResponse.class)))
    public AvailabilityResponse common(
            @Parameter(description = "Comma-separated user UUIDs; between 1 and 50 IDs") @RequestParam List<UUID> userIds,
            @Parameter(description = "Inclusive start; maximum window 31 days", example = "2030-01-15T09:00:00Z")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "Exclusive end; explicit UTC offset required", example = "2030-01-15T12:00:00Z")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to) {
        return availabilityService.common(userIds, from.toInstant(), to.toInstant());
    }
}
