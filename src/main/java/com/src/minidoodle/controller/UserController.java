package com.src.minidoodle.controller;

import com.src.minidoodle.dto.request.CreateUserRequest;
import com.src.minidoodle.dto.response.ApiError;
import com.src.minidoodle.dto.response.UserResponse;
import com.src.minidoodle.service.user.UserService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

import static com.src.minidoodle.constant.ApiPaths.USERS;
import static com.src.minidoodle.constant.ApiPaths.USER_ID;
import static com.src.minidoodle.constant.OpenApiConstants.DESC_CONFLICT;
import static com.src.minidoodle.constant.OpenApiConstants.DESC_INTERNAL_ERROR;
import static com.src.minidoodle.constant.OpenApiConstants.DESC_INVALID_DATA;
import static com.src.minidoodle.constant.OpenApiConstants.DESC_NOT_FOUND;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_200;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_201;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_400;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_404;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_409;
import static com.src.minidoodle.constant.OpenApiConstants.HTTP_500;

@RestController
@RequiredArgsConstructor
@RequestMapping(value = USERS, produces = MediaType.APPLICATION_JSON_VALUE)
@Tag(name = "Users", description = "User identities and automatically created internal calendars")
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
public class UserController {
    private final UserService userService;

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(operationId = "userCreate", summary = "Create a user", description = "Creates a user and one internal calendar. Email must be unique.")
    @ApiResponse(responseCode = HTTP_201, description = "User created",
            content = @Content(schema = @Schema(implementation = UserResponse.class)))
    public ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUserRequest request) {
        UserResponse user = userService.create(request);
        return ResponseEntity.created(URI.create(USERS + "/" + user.id())).body(user);
    }

    @GetMapping(USER_ID)
    @Operation(operationId = "userGet", summary = "Get user details")
    @ApiResponse(responseCode = HTTP_200, description = "User details",
            content = @Content(schema = @Schema(implementation = UserResponse.class)))
    public UserResponse get(@PathVariable UUID userId) {
        return userService.get(userId);
    }
}
