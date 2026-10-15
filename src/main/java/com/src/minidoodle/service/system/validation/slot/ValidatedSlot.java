package com.src.minidoodle.service.system.validation.slot;

import com.src.minidoodle.enums.SlotStatus;
import lombok.Builder;

import java.time.Instant;

/**
 * Validated slot input with explicit offsets normalized to absolute UTC instants.
 * @param startsAt inclusive boundary within the supported date and precision limits
 * @param endsAt exclusive boundary after start and at most 31 days later
 * @param status declared FREE or BUSY state
 */
@Builder
public record ValidatedSlot(Instant startsAt, Instant endsAt, SlotStatus status) {
}
