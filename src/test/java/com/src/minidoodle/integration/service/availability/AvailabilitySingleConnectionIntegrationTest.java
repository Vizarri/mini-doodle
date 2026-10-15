package com.src.minidoodle.integration.service.availability;

import com.src.minidoodle.dto.response.AvailabilityResponse;
import com.src.minidoodle.dto.response.MeetingResponse;
import com.src.minidoodle.dto.response.SlotResponse;
import com.src.minidoodle.dto.response.TimeIntervalResponse;
import com.src.minidoodle.dto.response.UserResponse;
import com.src.minidoodle.enums.SlotStatus;
import com.src.minidoodle.integration.AbstractIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

/** Detects accidental nested connection acquisition inside the scheduling transaction. */
@TestPropertySource(properties = {
        "spring.datasource.hikari.maximum-pool-size=1",
        "spring.datasource.hikari.minimum-idle=0",
        "spring.datasource.hikari.connection-timeout=250"
})
class AvailabilitySingleConnectionIntegrationTest extends AbstractIntegrationTest {
    @Autowired private DataSource dataSource;

    @DynamicPropertySource
    static void dedicatedFlywayConnection(DynamicPropertyRegistry properties) {
        properties.add("spring.flyway.url", POSTGRES::getJdbcUrl);
        properties.add("spring.flyway.user", POSTGRES::getUsername);
        properties.add("spring.flyway.password", POSTGRES::getPassword);
    }

    @Test
    void bookingAvailabilityAndCancellationCompleteWithOneDatabaseConnection() {
        assertThat(dataSource).isInstanceOf(HikariDataSource.class);
        assertThat(((HikariDataSource) dataSource).getMaximumPoolSize()).isOne();
        UserResponse organizer = createUser();
        UserResponse participant = createUser();
        SlotResponse source = createSlot(organizer.id(), "10:00", "11:00", SlotStatus.FREE);
        createSlot(participant.id(), "09:00", "12:00", SlotStatus.FREE);
        MeetingResponse meeting = createMeeting(organizer.id(), source.id(), List.of(participant.id()));

        AvailabilityResponse personal = availabilityService.personal(participant.id(), instant("08:00"), instant("13:00"));
        assertThat(personal.free()).extracting(TimeIntervalResponse::startsAt, TimeIntervalResponse::endsAt)
                .containsExactly(tuple(instant("09:00"), instant("10:00")),
                        tuple(instant("11:00"), instant("12:00")));
        AvailabilityResponse shared = availabilityService.common(List.of(organizer.id(), participant.id()), instant("08:00"), instant("13:00"));
        assertThat(shared.free()).isEmpty();
        assertThat(shared.busy()).extracting(TimeIntervalResponse::startsAt, TimeIntervalResponse::endsAt)
                .containsExactly(tuple(instant("10:00"), instant("11:00")));

        meetingService.cancel(meeting.id());
        AvailabilityResponse restored = availabilityService.personal(participant.id(), instant("08:00"), instant("13:00"));
        assertThat(restored.free()).extracting(TimeIntervalResponse::startsAt, TimeIntervalResponse::endsAt)
                .containsExactly(tuple(instant("09:00"), instant("12:00")));
        assertThat(restored.busy()).isEmpty();
    }
}
