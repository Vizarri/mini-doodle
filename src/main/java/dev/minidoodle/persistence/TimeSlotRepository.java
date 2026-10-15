package dev.minidoodle.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface TimeSlotRepository extends JpaRepository<TimeSlotEntity, UUID> {
    @Query(value = """
            SELECT * FROM time_slots
            WHERE calendar_id = :calendarId
              AND tstzrange(starts_at, ends_at, '[)') && tstzrange(:from, :to, '[)')
            ORDER BY starts_at, id
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<TimeSlotEntity> findWindow(@Param("calendarId") UUID calendarId,
                                   @Param("from") Instant from, @Param("to") Instant to,
                                   @Param("limit") int limit, @Param("offset") int offset);

    // Match the exclusion constraint expression so PostgreSQL can use its GiST index.
    @Query(value = """
            SELECT * FROM time_slots
            WHERE calendar_id IN (:calendarIds)
              AND tstzrange(starts_at, ends_at, '[)') && tstzrange(:from, :to, '[)')
            ORDER BY calendar_id, starts_at, id
            """, nativeQuery = true)
    List<TimeSlotEntity> findOverlapping(@Param("calendarIds") Collection<UUID> calendarIds,
                                       @Param("from") Instant from, @Param("to") Instant to);
}
