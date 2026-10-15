package com.src.minidoodle.unit.service.system.validation.user;

import com.src.minidoodle.dto.request.CreateUserRequest;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.service.system.validation.user.UserValidationService;
import com.src.minidoodle.service.system.validation.user.ValidatedUser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;

import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Checks normalized identity and presentation zones independently of process locale. */
class UserValidationServiceTest {
    private final UserValidationService service = new UserValidationService();

    @Test
    @ResourceLock(Resources.LOCALE)
    void trimsUnicodeWhitespaceAndLowercasesEmailIndependentlyOfDefaultLocale() {
        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            CreateUserRequest request = CreateUserRequest.builder().name(" \u2002Alice\u2002 ").email(" IGNAT@EXAMPLE.COM ").timeZone("Europe/Berlin").build();

            ValidatedUser result = service.validateAndNormalize(request);

            assertThat(result.name()).isEqualTo("Alice");
            assertThat(result.email()).isEqualTo("ignat@example.com");
            assertThat(result.timeZone()).isEqualTo("Europe/Berlin");
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/Berlin", "UTC+05:30", "-04:00"})
    void preservesValidZoneIdentifiers(String zone) {
        CreateUserRequest request = CreateUserRequest.builder().name("Alice").email("alice@example.com").timeZone(zone).build();

        assertThat(service.validateAndNormalize(request).timeZone()).isEqualTo(zone);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Mars/Olympus", "Europe/Berlin ", "+19:00"})
    void rejectsUnknownOrMalformedZoneIdentifiers(String zone) {
        CreateUserRequest request = CreateUserRequest.builder().name("Alice").email("alice@example.com").timeZone(zone).build();

        assertThatExceptionOfType(ApiException.class).isThrownBy(() -> service.validateAndNormalize(request)).satisfies(exception -> {
            assertThat(exception.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(exception.getCode()).isEqualTo(ApiErrorCode.INVALID_TIME_ZONE);
        });
    }
}
