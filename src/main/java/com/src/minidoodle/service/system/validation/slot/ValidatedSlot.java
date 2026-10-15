package com.src.minidoodle.service.system.validation.slot;

import com.src.minidoodle.enums.SlotStatus;
import java.time.Instant;

public record ValidatedSlot(Instant startsAt, Instant endsAt, SlotStatus status) {}
