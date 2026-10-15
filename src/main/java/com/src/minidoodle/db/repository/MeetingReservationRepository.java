package com.src.minidoodle.db.repository;

import com.src.minidoodle.db.model.MeetingReservation;
import com.src.minidoodle.db.model.MeetingReservationId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MeetingReservationRepository extends JpaRepository<MeetingReservation, MeetingReservationId> {
    @Query(value = """
            SELECT * FROM meeting_reservations
            WHERE calendar_id IN (:calendarIds)
              AND tstzrange(starts_at, ends_at, '[)') && tstzrange(:from, :to, '[)')
            ORDER BY calendar_id, starts_at, meeting_id
            """, nativeQuery = true)
    List<MeetingReservation> findOverlapping(@Param("calendarIds") Collection<UUID> calendarIds,
                                              @Param("from") Instant from, @Param("to") Instant to);

    List<MeetingReservation> findByIdMeetingId(UUID meetingId);

    void deleteByIdMeetingId(UUID meetingId);

    /** Inserts a known-new reservation; PostgreSQL enforces the overlap constraint atomically. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into meeting_reservations (meeting_id, calendar_id, starts_at, ends_at)
            values (:meetingId, :calendarId, :startsAt, :endsAt)
            """, nativeQuery = true)
    void insert(@Param("meetingId") UUID meetingId, @Param("calendarId") UUID calendarId,
                @Param("startsAt") Instant startsAt, @Param("endsAt") Instant endsAt);
}
