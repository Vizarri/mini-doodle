package com.src.minidoodle.integration.service.meeting;

import com.src.minidoodle.dto.response.AvailabilityResponse;
import com.src.minidoodle.dto.response.MeetingResponse;
import com.src.minidoodle.dto.response.SlotResponse;
import com.src.minidoodle.dto.response.TimeIntervalResponse;
import com.src.minidoodle.dto.response.UserResponse;
import com.src.minidoodle.enums.ApiErrorCode;
import com.src.minidoodle.enums.MeetingStatus;
import com.src.minidoodle.enums.SlotStatus;
import com.src.minidoodle.exception.ApiException;
import com.src.minidoodle.integration.AbstractIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;

/** Uses observed PostgreSQL lock contention, rather than relying on thread scheduling. */
class MeetingConcurrencyIntegrationTest extends AbstractIntegrationTest {
    @Test
    void bookingWaitsForTheWinningTransactionAndRejectsItsCommittedReservation() throws Exception {
        UserResponse firstOrganizer = createUser();
        UserResponse secondOrganizer = createUser();
        UserResponse participant = createUser();
        SlotResponse firstSource = createSlot(firstOrganizer.id(), "10:00", "11:00", SlotStatus.FREE);
        SlotResponse secondSource = createSlot(secondOrganizer.id(), "10:00", "11:00", SlotStatus.FREE);
        createSlot(participant.id(), "09:00", "12:00", SlotStatus.FREE);

        RaceResult<MeetingResponse, MeetingResponse> race = withBlockedContender(
                () -> createMeeting(firstOrganizer.id(), firstSource.id(), List.of(participant.id())),
                () -> createMeeting(secondOrganizer.id(), secondSource.id(), List.of(participant.id())));

        assertConflict(race.contender(), ApiErrorCode.MEETING_CONFLICT);
        assertThat(meetingRepository.count()).isEqualTo(1);
        assertThat(meetingParticipantRepository.count()).isEqualTo(2);
        assertThat(meetingReservationRepository.count()).isEqualTo(2);
        assertThat(availabilityService.personal(secondOrganizer.id(), instant("08:00"), instant("13:00")).busy()).isEmpty();
    }

    @Test
    void bookingRechecksAvailabilityAfterACompetingSlotUpdateCommits() throws Exception {
        UserResponse organizer = createUser();
        UserResponse participant = createUser();
        SlotResponse source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);
        SlotResponse participantSlot = createSlot(participant.id(), "09:00", "12:00", SlotStatus.FREE);

        RaceResult<SlotResponse, MeetingResponse> race = withBlockedContender(
                () -> slotService.update(participant.id(), participantSlot.id(), slotRequest("09:00", "12:00", SlotStatus.BUSY)),
                () -> createMeeting(organizer.id(), source.id(), List.of(participant.id())));

        assertConflict(race.contender(), ApiErrorCode.AVAILABILITY_REQUIRED);
        assertThat(meetingRepository.count()).isZero();
        assertThat(meetingReservationRepository.count()).isZero();
        assertThat(slotService.list(participant.id(), instant("08:00"), instant("13:00"), 100, 0))
                .singleElement().satisfies(slot -> assertThat(slot.status()).isEqualTo(SlotStatus.BUSY));
    }

    @ParameterizedTest(name = "{0} waits for booking, then preserves the reserved participant slot")
    @EnumSource(ParticipantSlotMutation.class)
    void participantSlotMutationRejectsTheWinningBookingsReservation(ParticipantSlotMutation mutation) throws Exception {
        UserResponse organizer = createUser();
        UserResponse participant = createUser();
        SlotResponse source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);
        // UPDATE covers a longer slot; DELETE covers one part of adjacent availability.
        List<SlotResponse> participantSlots = mutation == ParticipantSlotMutation.UPDATE
                ? List.of(createSlot(participant.id(), "09:00", "12:00", SlotStatus.FREE))
                : List.of(createSlot(participant.id(), "09:00", "10:30", SlotStatus.FREE),
                          createSlot(participant.id(), "10:30", "12:00", SlotStatus.FREE));
        SlotResponse target = participantSlots.getFirst();

        RaceResult<MeetingResponse, Void> race = withBlockedContender(
                () -> createMeeting(organizer.id(), source.id(), List.of(participant.id())),
                () -> {
                    if (mutation == ParticipantSlotMutation.UPDATE) {
                        slotService.update(participant.id(), target.id(), slotRequest("09:00", "12:00", SlotStatus.BUSY));
                    } else {
                        slotService.delete(participant.id(), target.id());
                    }
                    return null;
                });

        assertConflict(race.contender(), ApiErrorCode.SLOT_RESERVED);
        assertThat(meetingService.get(race.holder().id()).status()).isEqualTo(MeetingStatus.ACTIVE);
        assertThat(meetingRepository.count()).isEqualTo(1);
        assertThat(meetingParticipantRepository.count()).isEqualTo(2);
        assertThat(meetingReservationRepository.count()).isEqualTo(2);
        assertThat(slotService.list(participant.id(), instant("08:00"), instant("13:00"), 100, 0))
                .containsExactlyElementsOf(participantSlots);
        AvailabilityResponse availability = availabilityService.personal(participant.id(), instant("08:00"), instant("13:00"));
        assertThat(availability.free()).containsExactly(
                TimeIntervalResponse.builder().startsAt(instant("09:00")).endsAt(instant("10:00")).build(),
                TimeIntervalResponse.builder().startsAt(instant("11:00")).endsAt(instant("12:00")).build());
        assertThat(availability.busy()).containsExactly(TimeIntervalResponse.builder().startsAt(instant("10:00")).endsAt(instant("11:00")).build());
    }

    @Test
    void fiftyParticipantsCanBookAndCancelWithoutLosingAvailabilityOrMembership() {
        UserResponse organizer = createUser();
        SlotResponse source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);
        List<UUID> participants = new ArrayList<>();
        for (int i = 0; i < 49; i++) {
            UserResponse participant = createUser();
            createSlot(participant.id(), "09:00", "12:00", SlotStatus.FREE);
            participants.add(participant.id());
        }
        List<UUID> everyone = new ArrayList<>(participants);
        everyone.add(organizer.id());

        MeetingResponse meeting = createMeeting(organizer.id(), source.id(), participants);

        assertThat(meeting.participantIds()).containsExactlyInAnyOrderElementsOf(everyone);
        assertThat(meeting.status()).isEqualTo(MeetingStatus.ACTIVE);
        assertThat(meetingRepository.count()).isEqualTo(1);
        assertThat(meetingParticipantRepository.count()).isEqualTo(50);
        assertThat(meetingReservationRepository.count()).isEqualTo(50);
        AvailabilityResponse occupied = availabilityService.common(everyone, instant("08:00"), instant("13:00"));
        assertThat(occupied.free()).isEmpty();
        assertThat(occupied.busy()).containsExactly(TimeIntervalResponse.builder().startsAt(instant("10:00")).endsAt(instant("11:00")).build());

        meetingService.cancel(meeting.id());

        MeetingResponse cancelled = meetingService.get(meeting.id());
        assertThat(cancelled.status()).isEqualTo(MeetingStatus.CANCELLED);
        assertThat(cancelled.participantIds()).containsExactlyInAnyOrderElementsOf(everyone);
        assertThat(meetingRepository.count()).isEqualTo(1);
        assertThat(meetingParticipantRepository.count()).isEqualTo(50);
        assertThat(meetingReservationRepository.count()).isZero();
        assertThat(timeSlotRepository.count()).isEqualTo(50);
        AvailabilityResponse restored = availabilityService.common(everyone, instant("08:00"), instant("13:00"));
        assertThat(restored.free()).containsExactly(TimeIntervalResponse.builder().startsAt(instant("10:00")).endsAt(instant("11:00")).build());
        assertThat(restored.busy()).isEmpty();
    }

    @Test
    void concurrentCancellationIsIdempotentAndItsReplayPreservesANewerMeeting() throws Exception {
        UserResponse organizer = createUser();
        UserResponse participant = createUser();
        SlotResponse source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);
        createSlot(participant.id(), "09:00", "12:00", SlotStatus.FREE);
        MeetingResponse first = createMeeting(organizer.id(), source.id(), List.of(participant.id()));

        RaceResult<Void, Void> race = withBlockedContender(
                () -> { meetingService.cancel(first.id()); return null; },
                () -> { meetingService.cancel(first.id()); return null; });

        assertThat(race.contender().failure()).isNull();
        assertThat(meetingService.get(first.id()).status()).isEqualTo(MeetingStatus.CANCELLED);
        assertThat(meetingReservationRepository.count()).isZero();
        MeetingResponse second = createMeeting(organizer.id(), source.id(), List.of(participant.id()));
        meetingService.cancel(first.id());
        assertThat(meetingService.get(second.id()).status()).isEqualTo(MeetingStatus.ACTIVE);
        assertThat(meetingReservationRepository.count()).isEqualTo(2);
        assertThat(meetingParticipantRepository.count()).isEqualTo(4);
    }

    private void assertConflict(Outcome<?> outcome, ApiErrorCode code) {
        assertThat(outcome.failure()).isInstanceOfSatisfying(ApiException.class, failure -> {
            assertThat(failure.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(failure.getCode()).isEqualTo(code);
        });
    }

    private <T, U> RaceResult<T, U> withBlockedContender(Supplier<T> holderAction, Supplier<U> contenderAction)
            throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch holderReady = new CountDownLatch(1);
        CountDownLatch releaseHolder = new CountDownLatch(1);
        String suffix = UUID.randomUUID().toString();
        String holderName = "mini-doodle-holder-" + suffix;
        String contenderName = "mini-doodle-contender-" + suffix;
        Future<T> holder = null;
        Future<Outcome<U>> contender = null;
        try {
            holder = executor.submit(() -> new TransactionTemplate(transactionManager).execute(status -> {
                setApplicationName(holderName);
                T result = holderAction.get();
                holderReady.countDown();
                await(releaseHolder, "release of the first transaction");
                return result;
            }));
            assertThat(holderReady.await(10, TimeUnit.SECONDS)).as("first operation must finish before its commit").isTrue();
            contender = executor.submit(() -> capture(() -> new TransactionTemplate(transactionManager).execute(status -> {
                setApplicationName(contenderName);
                return contenderAction.get();
            })));
            awaitCalendarLock(contenderName, contender);
            releaseHolder.countDown();
            return new RaceResult<>(holder.get(10, TimeUnit.SECONDS), contender.get(10, TimeUnit.SECONDS));
        } finally {
            releaseHolder.countDown();
            cancelIfRunning(holder);
            cancelIfRunning(contender);
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }

    private void awaitCalendarLock(String applicationName, Future<?> contender) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            Long waiting = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM pg_stat_activity
                    WHERE application_name = ? AND wait_event_type = 'Lock'
                      AND cardinality(pg_blocking_pids(pid)) > 0
                      AND LOWER(query) LIKE '%calendars%'
                    """, Long.class, applicationName);
            if (waiting != null && waiting > 0) return;
            assertThat(contender.isDone()).as("contender must wait on the calendar coordination lock").isFalse();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
            if (Thread.currentThread().isInterrupted()) throw new IllegalStateException("Interrupted while observing contention");
        }
        throw new AssertionError("Contender never blocked on the held calendar lock");
    }

    private void setApplicationName(String name) {
        jdbcTemplate.queryForObject("SELECT set_config('application_name', ?, true)", String.class, name);
    }

    private static void await(CountDownLatch latch, String operation) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Timed out waiting for " + operation);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted waiting for " + operation, exception);
        }
    }

    private static <T> Outcome<T> capture(Supplier<T> action) {
        try { return new Outcome<>(action.get(), null); }
        catch (RuntimeException failure) { return new Outcome<>(null, failure); }
    }

    private static void cancelIfRunning(Future<?> future) {
        if (future != null && !future.isDone()) future.cancel(true);
    }

    enum ParticipantSlotMutation { UPDATE, DELETE }

    private record Outcome<T>(T value, RuntimeException failure) { }
    private record RaceResult<T, U>(T holder, Outcome<U> contender) { }
}
