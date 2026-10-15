package com.src.minidoodle.db.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.util.UUID;

/**
 * Persistent user identity and presentation preferences.
 * A user owns one internal calendar; email is normalized before persistence.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "users")
public class User {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    /** Normalized lowercase email; uniqueness is enforced by the database. */
    @Column(nullable = false, length = 254, unique = true)
    private String email;

    /** Valid Java zone ID for presentation; stored slot boundaries remain absolute instants. */
    @Column(name = "time_zone", nullable = false, length = 100)
    private String timeZone;

    /** Assigns a UUID before the first insert when the caller has not supplied one. */
    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
