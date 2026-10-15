package com.src.minidoodle.integration;

import com.src.minidoodle.db.repository.CalendarRepository;
import com.src.minidoodle.db.repository.MeetingParticipantRepository;
import com.src.minidoodle.db.repository.MeetingRepository;
import com.src.minidoodle.db.repository.MeetingReservationRepository;
import com.src.minidoodle.db.repository.TimeSlotRepository;
import com.src.minidoodle.db.repository.UserRepository;
import com.src.minidoodle.dto.request.CreateMeetingRequest;
import com.src.minidoodle.dto.request.CreateUserRequest;
import com.src.minidoodle.dto.request.SlotUpsertRequest;
import com.src.minidoodle.dto.response.MeetingResponse;
import com.src.minidoodle.dto.response.SlotResponse;
import com.src.minidoodle.dto.response.UserResponse;
import com.src.minidoodle.enums.SlotStatus;
import com.src.minidoodle.service.availability.AvailabilityService;
import com.src.minidoodle.service.meeting.MeetingService;
import com.src.minidoodle.service.slot.SlotService;
import com.src.minidoodle.service.user.UserService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Real PostgreSQL, production Flyway migrations and isolated business data for every test. */
@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers
@Tag("integration")
public abstract class AbstractIntegrationTest {
    protected static final LocalDate TEST_DATE = LocalDate.of(2035, 6, 1);

    @Container
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("mini_doodle_it")
            .withUsername("mini_doodle")
            .withPassword("mini_doodle");

    @Autowired protected JdbcTemplate jdbcTemplate;
    @Autowired protected PlatformTransactionManager transactionManager;
    @Autowired protected UserRepository userRepository;
    @Autowired protected CalendarRepository calendarRepository;
    @Autowired protected TimeSlotRepository timeSlotRepository;
    @Autowired protected MeetingRepository meetingRepository;
    @Autowired protected MeetingParticipantRepository meetingParticipantRepository;
    @Autowired protected MeetingReservationRepository meetingReservationRepository;
    @Autowired protected UserService userService;
    @Autowired protected SlotService slotService;
    @Autowired protected MeetingService meetingService;
    @Autowired protected AvailabilityService availabilityService;

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        properties.add("spring.datasource.username", POSTGRES::getUsername);
        properties.add("spring.datasource.password", POSTGRES::getPassword);
        properties.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        properties.add("spring.flyway.enabled", () -> "true");
    }

    @BeforeEach
    void cleanBusinessTables() {
        jdbcTemplate.execute("""
                TRUNCATE TABLE meeting_reservations, meeting_participants, meetings,
                               time_slots, calendars, users RESTART IDENTITY CASCADE
                """);
    }

    protected UserResponse createUser() {
        return userService.create(new CreateUserRequest("Integration user", UUID.randomUUID() + "@example.com", "UTC"));
    }

    protected SlotResponse createSlot(UUID userId, String from, String to, SlotStatus status) {
        return slotService.create(userId, slotRequest(from, to, status));
    }

    protected SlotUpsertRequest slotRequest(String from, String to, SlotStatus status) {
        return new SlotUpsertRequest(instant(from).atOffset(ZoneOffset.UTC), instant(to).atOffset(ZoneOffset.UTC), status);
    }

    protected CreateMeetingRequest meetingRequest(UUID organizerId, UUID slotId, List<UUID> participantIds) {
        return new CreateMeetingRequest(organizerId, slotId, "Design review", "Review the scheduling API", participantIds);
    }

    protected MeetingResponse createMeeting(UUID organizerId, UUID slotId, List<UUID> participantIds) {
        return meetingService.create(meetingRequest(organizerId, slotId, participantIds));
    }

    protected static Instant instant(String time) {
        return TEST_DATE.atTime(LocalTime.parse(time)).toInstant(ZoneOffset.UTC);
    }
}
