package dev.minidoodle.user;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/users")
public class UserController {
    private final UserService service;
    public UserController(UserService service) { this.service = service; }

    @PostMapping
    ResponseEntity<UserResponse> create(@Valid @RequestBody CreateUser request) {
        UserResponse user = service.create(request);
        return ResponseEntity.created(URI.create("/users/" + user.id())).body(user);
    }

    @GetMapping("/{userId}")
    UserResponse get(@PathVariable UUID userId) { return service.get(userId); }

    public record CreateUser(@NotBlank @Size(max = 100) String name,
                             @NotBlank @Email @Size(max = 254) String email,
                             @NotBlank @Size(max = 100) String timeZone) {}
    public record UserResponse(UUID id, String name, String email, String timeZone) {}
}
