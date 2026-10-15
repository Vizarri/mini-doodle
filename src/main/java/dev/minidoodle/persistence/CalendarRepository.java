package dev.minidoodle.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CalendarRepository extends JpaRepository<CalendarEntity, UUID> {
    Optional<CalendarEntity> findByUserId(UUID userId);

    List<CalendarEntity> findByUserIdIn(Collection<UUID> userIds);

    // Every schedule mutation locks these rows in the same order to avoid deadlocks.
    @Query(value = "SELECT * FROM calendars WHERE user_id IN (:userIds) ORDER BY id FOR UPDATE", nativeQuery = true)
    List<CalendarEntity> lockByUserIds(@Param("userIds") Collection<UUID> userIds);
}
