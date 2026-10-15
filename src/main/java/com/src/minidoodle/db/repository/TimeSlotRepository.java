package com.src.minidoodle.db.repository;

import com.src.minidoodle.db.model.TimeSlot;
import com.src.minidoodle.db.repository.projection.SlotAvailabilityView;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * Reads declared availability using half-open PostgreSQL ranges and the matching GiST index.
 */
public interface TimeSlotRepository extends JpaRepository<TimeSlot, UUID> {

    /**
     * Reads free interval values directly, bypassing entities cached in the persistence context.
     * Booking callers acquire calendar locks before this query so validation sees committed changes
     * under the write transaction's read-committed isolation without refreshing each slot separately.
     *
     * @param calendarIds nonempty collection of locked participant calendar identifiers
     * @param from inclusive query start
     * @param to exclusive query end
     * @return free interval values ordered by calendar, start time and slot identifier
     */
    @Query(value = """
            select calendar_id as "calendarId", starts_at as "startsAt", ends_at as "endsAt"
            from time_slots
            where calendar_id in (:calendarIds)
              and status = 'FREE'
              and tstzrange(starts_at, ends_at, '[)') && tstzrange(:from, :to, '[)')
            order by calendar_id, starts_at, id
            """, nativeQuery = true)
    List<SlotAvailabilityView> findFreeAvailability(@Param("calendarIds") Collection<UUID> calendarIds, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Reads one page of slots overlapping the query window, without clipping their intervals.
     *
     * @param calendarId owner calendar identifier
     * @param from inclusive query start
     * @param to exclusive query end
     * @param limit maximum number of rows to return
     * @param offset number of matching rows to skip
     * @return matching slots ordered by start time and identifier
     */
    @Query(value = """
            SELECT * FROM time_slots
            WHERE calendar_id = :calendarId
              AND tstzrange(starts_at, ends_at, '[)') && tstzrange(:from, :to, '[)')
            ORDER BY starts_at, id
            LIMIT :limit OFFSET :offset
            """, nativeQuery = true)
    List<TimeSlot> findWindow(@Param("calendarId") UUID calendarId, @Param("from") Instant from, @Param("to") Instant to, @Param("limit") int limit, @Param("offset") int offset);

    /**
     * Reads slots for several calendars in one query using the exclusion constraint's range expression.
     * Matching that expression lets PostgreSQL use its GiST index for interval overlap checks.
     *
     * @param calendarIds nonempty collection of calendar identifiers
     * @param from inclusive query start
     * @param to exclusive query end
     * @return full slot intervals ordered by calendar, start time and identifier
     */
    @Query(value = """
            SELECT * FROM time_slots
            WHERE calendar_id IN (:calendarIds)
              AND tstzrange(starts_at, ends_at, '[)') && tstzrange(:from, :to, '[)')
            ORDER BY calendar_id, starts_at, id
            """, nativeQuery = true)
    List<TimeSlot> findOverlapping(@Param("calendarIds") Collection<UUID> calendarIds, @Param("from") Instant from, @Param("to") Instant to);
}
