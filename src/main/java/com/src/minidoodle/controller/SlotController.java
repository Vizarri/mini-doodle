package com.src.minidoodle.controller;

import com.src.minidoodle.dto.request.SlotUpsertRequest;
import com.src.minidoodle.dto.response.ApiError;
import com.src.minidoodle.dto.response.SlotResponse;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.service.slot.SlotService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static com.src.minidoodle.constant.ApiPaths.USERS;
import static com.src.minidoodle.constant.ApiPaths.USER_SLOTS;
import static com.src.minidoodle.constant.ApiPaths.SLOT_ID;
import static com.src.minidoodle.constant.ApiRequestLimits.DEFAULT_SLOT_LIMIT;
import static com.src.minidoodle.constant.ApiRequestLimits.MAX_SLOT_LIMIT;
import static com.src.minidoodle.constant.ApiRequestLimits.MAX_SLOT_OFFSET;
import static com.src.minidoodle.constant.OpenApiConstants.DESC_CONFLICT;
import static com.src.minidoodle.constant.OpenApiConstants.DESC_INTERNAL_ERROR;
import static com.src.minidoodle.constant.OpenApiConstants.DESC_INVALID_DATA;
import static com.src.minidoodle.constant.OpenApiConstants.DESC_NOT_FOUND;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_200;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_201;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_204;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_400;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_404;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_409;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_500;

/**
 * Manages declared availability; active reservations protect slots from modification or deletion.
 */
@RestController
@RequiredArgsConstructor
@Validated
@RequestMapping(value = USER_SLOTS, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Slots", description = "Persisted user-declared availability")
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
public class SlotController {
    private final SlotService slotService;

    /**
     * Adds a declared free or busy interval; adjacent intervals are allowed.
     *
     * @param userId owner of the slot
     * @param request complete slot interval and declared status
     * @return the created slot with a 201 status and resource Location header
     * @throws ApiException if the user is missing, the interval is invalid or it overlaps another slot
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "slotCreate", summary = "Create a free or busy slot", description = "Slots must not overlap. Adjacent half-open intervals are allowed.")
    @ApiResponse(responseCode = HTTP_201, description = "Slot created",
            content = @Content(schema = @Schema(implementation = SlotResponse.class)))
    public ResponseEntity<SlotResponse> create(@PathVariable UUID userId, @Valid @RequestBody SlotUpsertRequest request) {
        SlotResponse slot = slotService.create(userId, request);
        return ResponseEntity.created(URI.create(USERS + "/" + userId + "/slots/" + slot.id())).body(slot);
    }

    /**
     * Lists complete slot intervals overlapping the half-open query window.
     *
     * @param userId owner of the slots
     * @param from inclusive window start with an explicit UTC offset
     * @param to exclusive window end with an explicit UTC offset
     * @param limit maximum number of slots to return
     * @param offset number of matching slots to skip
     * @return matching slots ordered by start time and identifier, without clipping their boundaries
     * @throws ApiException if the user is missing or query bounds or pagination are invalid
     */
    @GetMapping
    @Operation(operationId = "slotList", summary = "List declared slots", description = "Returns complete intervals overlapping the window, ordered by start and ID. Maximum window: 31 days.")
    @ApiResponse(responseCode = HTTP_200, description = "Declared slots",
            content = @Content(array = @ArraySchema(schema = @Schema(implementation = SlotResponse.class))))
    public List<SlotResponse> list(@PathVariable UUID userId,
            @Parameter(description = "Inclusive window start with explicit UTC offset", example = "2030-01-15T09:00:00Z")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @Parameter(description = "Exclusive window end with explicit UTC offset", example = "2030-01-15T12:00:00Z")
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "" + DEFAULT_SLOT_LIMIT) @Min(1) @Max(MAX_SLOT_LIMIT) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_SLOT_OFFSET) int offset) {
        return slotService.list(userId, from.toInstant(), to.toInstant(), limit, offset);
    }

    /**
     * Replaces all mutable fields of an unreserved slot.
     *
     * @param userId owner of the slot
     * @param slotId slot identifier
     * @param request complete replacement interval and declared status
     * @return the updated slot
     * @throws ApiException if ownership is invalid, the slot is reserved or the replacement interval is invalid or overlaps
     */
    @PatchMapping(value = SLOT_ID, consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "slotUpdate", summary = "Update a slot", description = "Requires all slot fields. A slot overlapping an active reservation cannot be changed.")
    @ApiResponse(responseCode = HTTP_200, description = "Updated slot",
            content = @Content(schema = @Schema(implementation = SlotResponse.class)))
    public SlotResponse update(@PathVariable UUID userId, @PathVariable UUID slotId, @Valid @RequestBody SlotUpsertRequest request) {
        return slotService.update(userId, slotId, request);
    }

    /**
     * Removes a slot only when no active reservation overlaps it.
     *
     * @param userId owner of the slot
     * @param slotId slot identifier
     * @return an empty 204 response after deletion
     * @throws ApiException if ownership is invalid or the slot overlaps an active reservation
     */
    @DeleteMapping(SLOT_ID)
    @Operation(operationId = "slotDelete", summary = "Delete an unreserved slot")
    @ApiResponse(responseCode = HTTP_204, description = "Slot deleted", content = @Content)
    public ResponseEntity<Void> delete(@PathVariable UUID userId, @PathVariable UUID slotId) {
        slotService.delete(userId, slotId);
        return ResponseEntity.noContent().build();
    }
}
