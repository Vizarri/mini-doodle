package com.src.minidoodle.db.repository;

import com.src.minidoodle.db.model.Meeting;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

/**
 * Persists meeting history; schedule mutations are coordinated through participant calendar locks.
 */
public interface MeetingRepository extends JpaRepository<Meeting, UUID> {
}
