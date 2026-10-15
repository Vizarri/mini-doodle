package dev.minidoodle.meeting;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/meetings")
public class MeetingController {
    private final MeetingService service;

    public MeetingController(MeetingService service) {
        this.service = service;
    }

    @PostMapping
    public ResponseEntity<MeetingView> create(@Valid @RequestBody CreateMeeting request) {
        MeetingView meeting = service.create(request);
        return ResponseEntity.created(URI.create("/meetings/" + meeting.id())).body(meeting);
    }

    @GetMapping("/{meetingId}")
    public MeetingView get(@PathVariable UUID meetingId) {
        return service.get(meetingId);
    }

    @DeleteMapping("/{meetingId}")
    public ResponseEntity<Void> cancel(@PathVariable UUID meetingId) {
        service.cancel(meetingId);
        return ResponseEntity.noContent().build();
    }

    public record CreateMeeting(
            @NotNull UUID organizerId,
            @NotNull UUID slotId,
            @NotBlank @Size(max = 200) String title,
            @Size(max = 4000) String description,
            @NotNull @Size(max = 50) List<@NotNull UUID> participantIds) {
    }
}
