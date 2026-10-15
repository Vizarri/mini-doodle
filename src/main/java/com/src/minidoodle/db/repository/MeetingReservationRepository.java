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

/**
 * Stores active meeting reservations separately from retained meeting membership.
 */
public interface MeetingReservationRepository extends JpaRepository<MeetingReservation, MeetingReservationId> {

    /**
     * Reads reservations intersecting a half-open window for all requested calendars in one query.
     * The range expression matches the exclusion constraint's GiST index; adjacent intervals do not overlap.
     *
     * @param calendarIds nonempty collection of calendar identifiers
     * @param from inclusive query start
     * @param to exclusive query end
     * @return full reservation intervals ordered by calendar, start time and meeting identifier
     */
    @Query(value = """
            SELECT * FROM meeting_reservations
            WHERE calendar_id IN (:calendarIds)
              AND tstzrange(starts_at, ends_at, '[)') && tstzrange(:from, :to, '[)')
            ORDER BY calendar_id, starts_at, meeting_id
            """, nativeQuery = true)
    List<MeetingReservation> findOverlapping(@Param("calendarIds") Collection<UUID> calendarIds, @Param("from") Instant from, @Param("to") Instant to);

    /**
     * Reads the reservations currently held by a meeting.
     *
     * @param meetingId meeting identifier
     * @return active reservations, or an empty list for a cancelled or missing meeting
     */
    List<MeetingReservation> findByIdMeetingId(UUID meetingId);

    /**
     * Releases reservations during cancellation without deleting meeting history or membership.
     * The caller must hold participant calendar locks for the enclosing transaction.
     *
     * @param meetingId meeting whose reservations are released
     */
    void deleteByIdMeetingId(UUID meetingId);

    /**
     * Inserts a known-new reservation without a merge existence query.
     * The booking transaction must already hold the calendar lock. PostgreSQL's exclusion constraint
     * provides an additional atomic safeguard against overlapping reservations.
     *
     * @param meetingId persisted meeting identifier
     * @param calendarId locked participant calendar identifier
     * @param startsAt inclusive reservation start
     * @param endsAt exclusive reservation end
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into meeting_reservations (meeting_id, calendar_id, starts_at, ends_at)
            values (:meetingId, :calendarId, :startsAt, :endsAt)
            """, nativeQuery = true)
    void insert(@Param("meetingId") UUID meetingId, @Param("calendarId") UUID calendarId, @Param("startsAt") Instant startsAt, @Param("endsAt") Instant endsAt);
}
