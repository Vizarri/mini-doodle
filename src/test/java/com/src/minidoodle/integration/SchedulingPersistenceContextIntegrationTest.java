package com.src.minidoodle.integration;

import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.enums.SlotStatus;
import com.src.minidoodle.exception.ApiException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Calendar locks must be followed by fresh reads, even inside an existing persistence context. */
class SchedulingPersistenceContextIntegrationTest extends AbstractIntegrationTest {
    @Test
    void bookingRejectsASourceThatBecameBusyAfterItWasCached() {
        var organizer = createUser();
        var source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);

        assertApiFailure(() -> outerTransaction().executeWithoutResult(status -> {
            var cached = timeSlotRepository.findById(source.id()).orElseThrow();
            assertThat(cached.getStatus()).isEqualTo(SlotStatus.FREE);
            independentTransaction().executeWithoutResult(inner -> slotService.update(
                    organizer.id(), source.id(), slotRequest("10:00", "11:00", SlotStatus.BUSY)));
            createMeeting(organizer.id(), source.id(), List.of());
        }), HttpStatus.CONFLICT, ApiErrorCode.MEETING_CONFLICT);

        assertThat(meetingRepository.count()).isZero();
        assertThat(meetingReservationRepository.count()).isZero();
        assertThat(timeSlotRepository.findById(source.id()).orElseThrow().getStatus()).isEqualTo(SlotStatus.BUSY);
    }

    @Test
    void bookingRejectsAParticipantSlotThatBecameBusyAfterItWasCached() {
        var organizer = createUser();
        var participant = createUser();
        var source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);
        var participantSlot = createSlot(participant.id(), "09:00", "12:00", SlotStatus.FREE);

        assertApiFailure(() -> outerTransaction().executeWithoutResult(status -> {
            var cached = timeSlotRepository.findById(participantSlot.id()).orElseThrow();
            assertThat(cached.getStatus()).isEqualTo(SlotStatus.FREE);
            independentTransaction().executeWithoutResult(inner -> slotService.update(
                    participant.id(), participantSlot.id(), slotRequest("09:00", "12:00", SlotStatus.BUSY)));
            createMeeting(organizer.id(), source.id(), List.of(participant.id()));
        }), HttpStatus.CONFLICT, ApiErrorCode.AVAILABILITY_REQUIRED);

        assertThat(meetingRepository.count()).isZero();
        assertThat(meetingReservationRepository.count()).isZero();
    }

    @Test
    void deletingACachedSlotChecksReservationsAtItsCurrentCommittedTime() {
        var organizer = createUser();
        var source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);

        assertApiFailure(() -> outerTransaction().executeWithoutResult(status -> {
            var cached = timeSlotRepository.findById(source.id()).orElseThrow();
            assertThat(cached.getStartsAt()).isEqualTo(instant("10:00"));
            independentTransaction().executeWithoutResult(inner -> {
                slotService.update(organizer.id(), source.id(), slotRequest("11:00", "12:00", SlotStatus.FREE));
                createMeeting(organizer.id(), source.id(), List.of());
            });
            slotService.delete(organizer.id(), source.id());
        }), HttpStatus.CONFLICT, ApiErrorCode.SLOT_RESERVED);

        assertThat(timeSlotRepository.findById(source.id())).hasValueSatisfying(slot -> {
            assertThat(slot.getStartsAt()).isEqualTo(instant("11:00"));
            assertThat(slot.getEndsAt()).isEqualTo(instant("12:00"));
        });
        assertThat(meetingRepository.count()).isEqualTo(1);
        assertThat(meetingReservationRepository.findAll()).singleElement().satisfies(reservation -> {
            assertThat(reservation.getStartsAt()).isEqualTo(instant("11:00"));
            assertThat(reservation.getEndsAt()).isEqualTo(instant("12:00"));
        });
    }

    @ParameterizedTest(name = "{0} reports a cached but deleted source as not found")
    @EnumSource(StaleSlotOperation.class)
    void deletedCachedSourceIsReportedAsMissing(StaleSlotOperation operation) {
        var organizer = createUser();
        var source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);

        assertApiFailure(() -> outerTransaction().executeWithoutResult(status -> {
            assertThat(timeSlotRepository.findById(source.id())).isPresent();
            independentTransaction().executeWithoutResult(inner -> slotService.delete(organizer.id(), source.id()));
            switch (operation) {
                case BOOK -> createMeeting(organizer.id(), source.id(), List.of());
                case UPDATE -> slotService.update(organizer.id(), source.id(), slotRequest("11:00", "12:00", SlotStatus.FREE));
            }
        }), HttpStatus.NOT_FOUND, ApiErrorCode.SLOT_NOT_FOUND);

        assertThat(timeSlotRepository.findById(source.id())).isEmpty();
        assertThat(meetingRepository.count()).isZero();
        assertThat(meetingReservationRepository.count()).isZero();
    }

    private TransactionTemplate outerTransaction() {
        return new TransactionTemplate(transactionManager);
    }

    private TransactionTemplate independentTransaction() {
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return transaction;
    }

    private void assertApiFailure(Runnable action, HttpStatus status, ApiErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(ApiException.class, failure -> {
            assertThat(failure.getStatus()).isEqualTo(status);
            assertThat(failure.getCode()).isEqualTo(code);
        });
    }

    enum StaleSlotOperation { BOOK, UPDATE }
}
