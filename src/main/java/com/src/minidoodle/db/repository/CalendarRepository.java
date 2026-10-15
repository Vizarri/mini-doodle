package com.src.minidoodle.db.repository;

import com.src.minidoodle.db.model.Calendar;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CalendarRepository extends JpaRepository<Calendar, UUID> {

    @Query("select calendar from Calendar calendar where calendar.user.id = :userId")
    Optional<Calendar> findByUserId(@Param("userId") UUID userId);

    @Query("select calendar from Calendar calendar where calendar.user.id in :userIds")
    List<Calendar> findByUserIdIn(@Param("userIds") Collection<UUID> userIds);

    /** Serializes calendar writers in a stable order without blocking child-row foreign-key checks. */
    @Query(value = """
            select *
            from calendars
            where user_id in (:userIds)
            order by id
            for no key update
            """, nativeQuery = true)
    List<Calendar> lockByUserIds(@Param("userIds") Collection<UUID> userIds);
}
