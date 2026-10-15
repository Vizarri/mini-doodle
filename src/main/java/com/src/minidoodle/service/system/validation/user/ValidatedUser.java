package com.src.minidoodle.service.system.validation.user;

import lombok.Builder;

/**
 * User input after normalization and zone validation, ready for persistence.
 * @param name trimmed display name
 * @param email trimmed lowercase email
 * @param timeZone validated Java zone ID, preserved as supplied
 */
@Builder
public record ValidatedUser(String name, String email, String timeZone) {
}
