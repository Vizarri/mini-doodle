package com.src.minidoodle.integration.service.availability;

import com.src.minidoodle.db.model.Calendar;
import com.src.minidoodle.db.model.TimeSlot;
import com.src.minidoodle.db.model.User;
import com.src.minidoodle.dto.response.AvailabilityResponse;
import com.src.minidoodle.dto.response.TimeIntervalResponse;
import com.src.minidoodle.enums.SlotStatus;
import com.src.minidoodle.integration.AbstractIntegrationTest;
import jakarta.persistence.EntityManagerFactory;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Checks real results and query growth on the dataset size requested in the challenge. */
@TestPropertySource(properties = {
        "spring.jpa.properties.hibernate.generate_statistics=true",
        "logging.level.org.hibernate.engine.internal.StatisticalLoggingSessionEventListener=OFF"
})
class AvailabilityScaleIntegrationTest extends AbstractIntegrationTest {
    @Autowired private EntityManagerFactory entityManagerFactory;

    @Test
    void twoHundredUsersAndFiveThousandSlotsStillUseBoundedAvailabilityQueries() {
        ScaleFixture fixture = seedScaleFixture();
        assertThat(userRepository.count()).isEqualTo(200);
        assertThat(timeSlotRepository.count()).isEqualTo(5000);
        createMeeting(fixture.organizerId(), fixture.sourceSlotId(), List.of(fixture.userIds().get(1)));

        AvailabilityResponse personal = withBoundedQueries(() -> availabilityService.personal(
                fixture.userIds().get(1), instant("08:00"), instant("13:00")));
        assertSplitFreeTime(personal);
        AvailabilityResponse shared = withBoundedQueries(() -> availabilityService.common(
                fixture.userIds().subList(1, 51), instant("08:00"), instant("13:00")));
        assertSplitFreeTime(shared);
        AvailabilityResponse declaredBusy = withBoundedQueries(() -> availabilityService.personal(
                fixture.userIds().get(199), instant("08:00"), instant("13:00")));
        assertThat(declaredBusy.free()).isEmpty();
        assertThat(declaredBusy.busy()).extracting(TimeIntervalResponse::startsAt, TimeIntervalResponse::endsAt)
                .containsExactly(tuple(instant("09:00"), instant("12:00")));
    }

    private AvailabilityResponse withBoundedQueries(Supplier<AvailabilityResponse> query) {
        Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        AvailabilityResponse result = query.get();
        assertThat(statistics.getPrepareStatementCount())
                .as("availability must batch calendar, slot and reservation reads, independent of user count")
                .isBetween(1L, 3L);
        assertThat(statistics.getEntityFetchCount()).as("no extra lazy entity fetches").isZero();
        assertThat(statistics.getCollectionFetchCount()).as("no per-user collection loads").isZero();
        return result;
    }

    private void assertSplitFreeTime(AvailabilityResponse response) {
        assertThat(response.free()).extracting(TimeIntervalResponse::startsAt, TimeIntervalResponse::endsAt)
                .containsExactly(tuple(instant("09:00"), instant("10:00")),
                        tuple(instant("11:00"), instant("12:00")));
        assertThat(response.busy()).extracting(TimeIntervalResponse::startsAt, TimeIntervalResponse::endsAt)
                .containsExactly(tuple(instant("10:00"), instant("11:00")));
    }

    private ScaleFixture seedScaleFixture() {
        return new TransactionTemplate(transactionManager).execute(status -> {
            List<User> users = new ArrayList<>();
            for (int i = 0; i < 200; i++) {
                users.add(User.builder().name("Scale user " + i).email("scale-" + i + "@example.com").timeZone("UTC").build());
            }
            userRepository.saveAllAndFlush(users);
            List<Calendar> calendars = users.stream().map(user -> Calendar.builder().user(user).build()).toList();
            calendarRepository.saveAllAndFlush(calendars);
            List<TimeSlot> slots = new ArrayList<>();
            for (int user = 0; user < users.size(); user++) {
                for (int day = 0; day < 25; day++) {
                    boolean source = user == 0 && day == 0;
                    slots.add(TimeSlot.builder()
                            .calendar(calendars.get(user))
                            .startsAt(TEST_DATE.plusDays(day).atTime(source ? 10 : 9, 0).toInstant(ZoneOffset.UTC))
                            .endsAt(TEST_DATE.plusDays(day).atTime(source ? 11 : 12, 0).toInstant(ZoneOffset.UTC))
                            .status(user == 199 && day == 0 ? SlotStatus.BUSY : SlotStatus.FREE)
                            .build());
                }
            }
            timeSlotRepository.saveAllAndFlush(slots);
            return new ScaleFixture(users.getFirst().getId(), slots.getFirst().getId(),
                    users.stream().map(User::getId).toList());
        });
    }

    private record ScaleFixture(UUID organizerId, UUID sourceSlotId, List<UUID> userIds) { }
}
