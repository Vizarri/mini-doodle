package com.src.minidoodle.integration.service.availability;

import com.src.minidoodle.dto.response.AvailabilityResponse;
import com.src.minidoodle.dto.response.SlotResponse;
import com.src.minidoodle.dto.response.TimeIntervalResponse;
import com.src.minidoodle.dto.response.UserResponse;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.enums.SlotStatus;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.integration.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** Verifies effective interval sets, user selection and query boundaries against real PostgreSQL. */
class AvailabilityServiceIntegrationTest extends AbstractIntegrationTest {
    @Test
    void repeatedUserIdsProducePersonalAvailabilityWithoutIncludingOtherCalendars() {
        UserResponse selected = createUser();
        UserResponse outside = createUser();
        createSlot(selected.id(), "08:00", "12:00", SlotStatus.FREE);
        createSlot(selected.id(), "12:00", "13:00", SlotStatus.BUSY);
        createSlot(outside.id(), "08:00", "14:00", SlotStatus.BUSY);

        AvailabilityResponse response = availabilityService.common(List.of(selected.id(), selected.id()), instant("09:00"), instant("14:00"));

        assertThat(response).isEqualTo(availabilityService.personal(selected.id(), instant("09:00"), instant("14:00")));
        assertThat(response.from()).isEqualTo(instant("09:00"));
        assertThat(response.to()).isEqualTo(instant("14:00"));
        assertThat(response.free()).extracting(TimeIntervalResponse::startsAt, TimeIntervalResponse::endsAt)
                .containsExactly(tuple(instant("09:00"), instant("12:00")));
        assertThat(response.busy()).extracting(TimeIntervalResponse::startsAt, TimeIntervalResponse::endsAt)
                .containsExactly(tuple(instant("12:00"), instant("13:00")));
    }

    @Test
    void undeclaredCalendarPreventsCommonFreeTimeWithoutHidingAnotherUsersBusyTime() {
        UserResponse declared = createUser();
        UserResponse undeclared = createUser();
        createSlot(declared.id(), "09:00", "10:00", SlotStatus.FREE);
        createSlot(declared.id(), "10:00", "11:00", SlotStatus.BUSY);

        AvailabilityResponse personal = availabilityService.personal(undeclared.id(), instant("08:00"), instant("12:00"));
        AvailabilityResponse shared = availabilityService.common(List.of(declared.id(), undeclared.id()), instant("08:00"), instant("12:00"));

        assertThat(personal.free()).isEmpty();
        assertThat(personal.busy()).isEmpty();
        assertThat(shared.free()).isEmpty();
        assertThat(shared.busy()).extracting(TimeIntervalResponse::startsAt, TimeIntervalResponse::endsAt)
                .containsExactly(tuple(instant("10:00"), instant("11:00")));
    }

    @Test
    void disjointDeclaredAvailabilityDoesNotBecomeCommonFreeTime() {
        UserResponse first = createUser();
        UserResponse second = createUser();
        createSlot(first.id(), "09:00", "10:00", SlotStatus.FREE);
        createSlot(second.id(), "10:00", "11:00", SlotStatus.FREE);

        AvailabilityResponse response = availabilityService.common(List.of(first.id(), second.id()), instant("08:00"), instant("12:00"));

        assertThat(response.free()).isEmpty();
        assertThat(response.busy()).isEmpty();
    }

    @Test
    void busyUnionCombinesReservationsAndOverlappingUserSlotsAndClipsBothWindowEdges() {
        UserResponse first = createUser();
        UserResponse second = createUser();
        UserResponse organizer = createUser();
        createSlot(first.id(), "08:00", "12:00", SlotStatus.FREE);
        createSlot(first.id(), "12:00", "13:00", SlotStatus.BUSY);
        createSlot(second.id(), "08:00", "10:30", SlotStatus.FREE);
        createSlot(second.id(), "10:30", "14:00", SlotStatus.BUSY);
        SlotResponse source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);
        createMeeting(organizer.id(), source.id(), List.of(first.id()));

        AvailabilityResponse response = availabilityService.common(List.of(first.id(), second.id()), instant("09:30"), instant("12:30"));

        assertThat(response.free()).extracting(TimeIntervalResponse::startsAt, TimeIntervalResponse::endsAt)
                .containsExactly(tuple(instant("09:30"), instant("10:00")));
        assertThat(response.busy()).extracting(TimeIntervalResponse::startsAt, TimeIntervalResponse::endsAt)
                .containsExactly(tuple(instant("10:00"), instant("12:30")));
        AvailabilityResponse insideReservation = availabilityService.personal(first.id(), instant("10:15"), instant("10:45"));
        assertThat(insideReservation.free()).isEmpty();
        assertThat(insideReservation.busy()).extracting(TimeIntervalResponse::startsAt, TimeIntervalResponse::endsAt)
                .containsExactly(tuple(instant("10:15"), instant("10:45")));
    }

    @Test
    void slotsAndReservationsTouchingQueryBoundariesDoNotOccupyTheWindow() {
        UserResponse user = createUser();
        SlotResponse earlier = createSlot(user.id(), "09:00", "10:00", SlotStatus.FREE);
        createSlot(user.id(), "10:00", "11:00", SlotStatus.FREE);
        SlotResponse later = createSlot(user.id(), "11:00", "12:00", SlotStatus.FREE);
        createMeeting(user.id(), earlier.id(), List.of());
        createMeeting(user.id(), later.id(), List.of());

        AvailabilityResponse response = availabilityService.personal(user.id(), instant("10:00"), instant("11:00"));

        assertThat(response.free()).extracting(TimeIntervalResponse::startsAt, TimeIntervalResponse::endsAt)
                .containsExactly(tuple(instant("10:00"), instant("11:00")));
        assertThat(response.busy()).isEmpty();
    }

    @Test
    void missingUserFailsTheWholeQueryInsteadOfReturningPartialAvailability() {
        UserResponse user = createUser();
        createSlot(user.id(), "09:00", "12:00", SlotStatus.FREE);

        assertThatThrownBy(() -> availabilityService.common(List.of(user.id(), UUID.randomUUID()), instant("09:00"), instant("12:00")))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
                    assertThat(exception.getCode()).isEqualTo(ApiErrorCode.USER_NOT_FOUND);
                });
    }
}
