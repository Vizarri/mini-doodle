package com.src.minidoodle.integration.service.slot;

import com.src.minidoodle.dto.response.SlotResponse;
import com.src.minidoodle.dto.response.UserResponse;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.enums.SlotStatus;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.integration.AbstractIntegrationTest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Checks ownership, persisted pagination boundaries and atomic slot replacement against PostgreSQL. */
class SlotServiceIntegrationTest extends AbstractIntegrationTest {
    @Test
    void listingUsesStoredBoundariesAndOrderedPagesWithinOnlyTheOwnersCalendar() {
        UserResponse owner = createUser();
        UserResponse other = createUser();
        SlotResponse third = createSlot(owner.id(), "11:00", "12:00", SlotStatus.FREE);
        SlotResponse first = createSlot(owner.id(), "09:00", "10:00", SlotStatus.BUSY);
        SlotResponse second = createSlot(owner.id(), "10:00", "11:00", SlotStatus.FREE);
        createSlot(owner.id(), "08:00", "09:00", SlotStatus.FREE);
        createSlot(owner.id(), "12:00", "13:00", SlotStatus.FREE);
        createSlot(other.id(), "09:00", "12:00", SlotStatus.FREE);

        assertThat(slotService.list(owner.id(), instant("09:30"), instant("11:30"), 10, 0)).containsExactly(first, second, third);
        assertThat(slotService.list(owner.id(), instant("09:30"), instant("11:30"), 1, 0)).containsExactly(first);
        assertThat(slotService.list(owner.id(), instant("09:30"), instant("11:30"), 1, 1)).containsExactly(second);
        assertThat(slotService.list(owner.id(), instant("09:30"), instant("11:30"), 1, 2)).containsExactly(third);
        assertThat(slotService.list(owner.id(), instant("09:30"), instant("11:30"), 1, 3)).isEmpty();
        assertThat(slotService.list(owner.id(), instant("10:00"), instant("11:00"), 10, 0)).containsExactly(second);
        assertThat(slotService.list(owner.id(), instant("13:00"), instant("14:00"), 10, 0)).isEmpty();
    }

    @Test
    void overlappingReplacementLeavesAllPersistedFieldsUnchangedAndCanBeRetried() {
        UserResponse owner = createUser();
        SlotResponse original = createSlot(owner.id(), "09:00", "10:00", SlotStatus.FREE);
        SlotResponse adjacent = createSlot(owner.id(), "11:00", "12:00", SlotStatus.BUSY);

        assertApiFailure(() -> slotService.update(owner.id(), original.id(), slotRequest("09:30", "11:30", SlotStatus.BUSY)), HttpStatus.CONFLICT, ApiErrorCode.SLOT_OVERLAP);

        assertThat(slotService.list(owner.id(), instant("08:00"), instant("13:00"), 10, 0)).containsExactly(original, adjacent);
        SlotResponse replacement = slotService.update(owner.id(), original.id(), slotRequest("09:30", "11:00", SlotStatus.BUSY));
        assertThat(replacement).isEqualTo(SlotResponse.builder().id(original.id()).startsAt(instant("09:30")).endsAt(instant("11:00")).status(SlotStatus.BUSY).build());
        assertThat(slotService.list(owner.id(), instant("08:00"), instant("13:00"), 10, 0)).containsExactly(replacement, adjacent);
    }

    @ParameterizedTest(name = "{0} cannot mutate another user's slot")
    @EnumSource(SlotMutation.class)
    void foreignSlotIsReportedAsMissingAndItsDefinitionRemainsUnchanged(SlotMutation mutation) {
        UserResponse owner = createUser();
        UserResponse other = createUser();
        SlotResponse original = createSlot(owner.id(), "09:00", "10:00", SlotStatus.FREE);

        assertApiFailure(() -> mutate(mutation, other.id(), original.id()), HttpStatus.NOT_FOUND, ApiErrorCode.SLOT_NOT_FOUND);

        assertThat(slotService.list(owner.id(), instant("08:00"), instant("13:00"), 10, 0)).containsExactly(original);
        assertThat(slotService.list(other.id(), instant("08:00"), instant("13:00"), 10, 0)).isEmpty();
        assertThat(timeSlotRepository.count()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0} reports an absent slot as missing")
    @EnumSource(SlotMutation.class)
    void missingSlotDoesNotChangeTheOwnersExistingSlots(SlotMutation mutation) {
        UserResponse owner = createUser();
        SlotResponse original = createSlot(owner.id(), "09:00", "10:00", SlotStatus.FREE);

        assertApiFailure(() -> mutate(mutation, owner.id(), UUID.randomUUID()), HttpStatus.NOT_FOUND, ApiErrorCode.SLOT_NOT_FOUND);

        assertThat(slotService.list(owner.id(), instant("08:00"), instant("13:00"), 10, 0)).containsExactly(original);
    }

    @Test
    void meetingReservationsDoNotChangeTheListedDeclaredSlotStatus() {
        UserResponse owner = createUser();
        SlotResponse original = createSlot(owner.id(), "09:00", "10:00", SlotStatus.FREE);
        createMeeting(owner.id(), original.id(), List.of());

        assertThat(slotService.list(owner.id(), instant("09:00"), instant("10:00"), 10, 0)).containsExactly(original);
        assertThat(meetingReservationRepository.count()).isEqualTo(1);
    }

    private void mutate(SlotMutation mutation, UUID userId, UUID slotId) {
        switch (mutation) {
            case UPDATE -> slotService.update(userId, slotId, slotRequest("10:00", "11:00", SlotStatus.BUSY));
            case DELETE -> slotService.delete(userId, slotId);
        }
    }

    private void assertApiFailure(Runnable action, HttpStatus status, ApiErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, failure -> {
            assertThat(failure.getStatus()).isEqualTo(status);
            assertThat(failure.getCode()).isEqualTo(code);
        });
    }

    enum SlotMutation { UPDATE, DELETE }
}
