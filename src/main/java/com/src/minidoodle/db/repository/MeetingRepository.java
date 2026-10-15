package com.src.minidoodle.db.repository;

import com.src.minidoodle.db.model.Meeting;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

/**
 * Persists meeting history and provides the row lock used during cancellation.
 */
public interface MeetingRepository extends JpaRepository<Meeting, UUID> {

    /**
     * Locks a meeting until transaction completion. Callers acquire participant calendar locks first
     * to preserve the global lock order. A managed meeting loaded earlier still requires a refresh.
     *
     * @param id meeting identifier
     * @return the locked meeting, or empty when it does not exist
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT m FROM Meeting m WHERE m.id = :id")
    Optional<Meeting> findLockedById(@Param("id") UUID id);
}
