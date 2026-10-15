package com.src.minidoodle.db.repository;

import com.src.minidoodle.db.model.Calendar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persists one calendar per user and provides the coordination locks for schedule mutations.
 */
public interface CalendarRepository extends JpaRepository<Calendar, UUID> {

    /**
     * Finds the internal calendar owned by a user.
     *
     * @param userId calendar owner identifier
     * @return the calendar, or empty when the user has no calendar
     */
    @Query("select calendar from Calendar calendar where calendar.user.id = :userId")
    Optional<Calendar> findByUserId(@Param("userId") UUID userId);

    /**
     * Loads calendars for a set of users in one query.
     *
     * @param userIds nonempty collection of owner identifiers
     * @return existing calendars; callers must detect missing requested users
     */
    @Query("select calendar from Calendar calendar where calendar.user.id in :userIds")
    List<Calendar> findByUserIdIn(@Param("userIds") Collection<UUID> userIds);

    /**
     * Locks calendars in identifier order to serialize schedule mutations without deadlocks.
     * The caller must hold a transaction and acquire these locks before reading mutable schedule state.
     * PostgreSQL {@code FOR NO KEY UPDATE} allows child-row foreign-key checks to proceed;
     * calendar identifiers and ownership are immutable. Locks remain held until transaction completion.
     *
     * @param userIds nonempty collection of calendar owner identifiers
     * @return existing calendars in lock acquisition order; missing users are not represented
     */
    @Query(value = """
            select *
            from calendars
            where user_id in (:userIds)
            order by id
            for no key update
            """, nativeQuery = true)
    List<Calendar> lockByUserIds(@Param("userIds") Collection<UUID> userIds);
}
