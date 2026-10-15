package dev.minidoodle.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MeetingReservationRepository extends JpaRepository<MeetingReservationEntity, MeetingReservationId> {
    @Query(value = """
            SELECT * FROM meeting_reservations
            WHERE calendar_id IN (:calendarIds)
              AND tstzrange(starts_at, ends_at, '[)') && tstzrange(:from, :to, '[)')
            ORDER BY calendar_id, starts_at, meeting_id
            """, nativeQuery = true)
    List<MeetingReservationEntity> findOverlapping(@Param("calendarIds") Collection<UUID> calendarIds,
                                                 @Param("from") Instant from, @Param("to") Instant to);

    List<MeetingReservationEntity> findByIdMeetingId(UUID meetingId);

    void deleteByIdMeetingId(UUID meetingId);
}
