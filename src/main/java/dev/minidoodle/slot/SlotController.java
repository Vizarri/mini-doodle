package dev.minidoodle.slot;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@Validated
@RequestMapping("/users/{userId}/slots")
public class SlotController {
    private final SlotService service;
    public SlotController(SlotService service) { this.service = service; }

    @PostMapping
    ResponseEntity<SlotResponse> create(@PathVariable UUID userId, @Valid @RequestBody SlotRequest request) {
        SlotResponse slot = service.create(userId, request);
        return ResponseEntity.created(URI.create("/users/" + userId + "/slots/" + slot.id())).body(slot);
    }

    @GetMapping
    List<SlotResponse> list(@PathVariable UUID userId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) OffsetDateTime to,
            @RequestParam(defaultValue = "100") @Min(1) @Max(500) int limit,
            @RequestParam(defaultValue = "0") @Min(0) @Max(100000) int offset) {
        return service.list(userId, from.toInstant(), to.toInstant(), limit, offset);
    }

    @PatchMapping("/{slotId}")
    SlotResponse update(@PathVariable UUID userId, @PathVariable UUID slotId,
                        @Valid @RequestBody SlotRequest request) {
        return service.update(userId, slotId, request);
    }

    @DeleteMapping("/{slotId}")
    ResponseEntity<Void> delete(@PathVariable UUID userId, @PathVariable UUID slotId) {
        service.delete(userId, slotId);
        return ResponseEntity.noContent().build();
    }

    public enum Status { FREE, BUSY }
    public record SlotRequest(@NotNull OffsetDateTime startsAt, @NotNull OffsetDateTime endsAt,
                              @NotNull Status status) {}
    public record SlotResponse(UUID id, Instant startsAt, Instant endsAt, Status status) {}
}
