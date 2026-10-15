package com.src.minidoodle.controller;

import com.src.minidoodle.dto.request.CreateMeetingRequest;
import com.src.minidoodle.dto.response.ApiError;
import com.src.minidoodle.dto.response.MeetingResponse;
import com.src.minidoodle.service.meeting.MeetingService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

import static com.src.minidoodle.constant.ApiPaths.MEETINGS;
import static com.src.minidoodle.constant.ApiPaths.MEETING_ID;
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

@RestController
@RequiredArgsConstructor
@RequestMapping(value = MEETINGS, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Meetings", description = "Atomic booking and cancellation across participant calendars")
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
public class MeetingController {
    private final MeetingService meetingService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "meetingCreate", summary = "Book a meeting", description = "Books the complete source slot for all participants or rejects the entire operation. Organizer is included automatically.")
    @ApiResponse(responseCode = HTTP_201, description = "Meeting created",
            content = @Content(schema = @Schema(implementation = MeetingResponse.class)))
    public ResponseEntity<MeetingResponse> create(@Valid @RequestBody CreateMeetingRequest request) {
        MeetingResponse meeting = meetingService.create(request);
        return ResponseEntity.created(URI.create(MEETINGS + "/" + meeting.id())).body(meeting);
    }

    @GetMapping(MEETING_ID)
    @Operation(operationId = "meetingGet", summary = "Get meeting details", description = "Includes retained membership for cancelled meetings.")
    @ApiResponse(responseCode = HTTP_200, description = "Meeting details",
            content = @Content(schema = @Schema(implementation = MeetingResponse.class)))
    public MeetingResponse get(@PathVariable UUID meetingId) {
        return meetingService.get(meetingId);
    }

    @DeleteMapping(MEETING_ID)
    @Operation(operationId = "meetingCancel", summary = "Cancel a meeting", description = "Releases reservations while retaining meeting details. Repeated cancellation is idempotent.")
    @ApiResponse(responseCode = HTTP_204, description = "Meeting cancelled", content = @Content)
    public ResponseEntity<Void> cancel(@PathVariable UUID meetingId) {
        meetingService.cancel(meetingId);
        return ResponseEntity.noContent().build();
    }
}
