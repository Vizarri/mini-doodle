package com.src.minidoodle.db.repository;

import com.src.minidoodle.db.model.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

/**
 * Persists user profiles with email uniqueness enforced by PostgreSQL.
 */
public interface UserRepository extends JpaRepository<User, UUID> {

    /**
     * Looks up an email already normalized by the application.
     *
     * @param email trimmed, lowercase email address
     * @return the matching user, or empty when the email is not registered
     */
    Optional<User> findByEmail(String email);
}
