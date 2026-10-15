package com.src.minidoodle.db.model;

import com.src.minidoodle.enums.SlotStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * User-declared availability for a half-open interval.
 * Meeting reservations are stored separately, so booking never rewrites the declared status.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "time_slots")
public class TimeSlot {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** Owning calendar whose coordination lock protects changes to this slot. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "calendar_id", nullable = false)
    private Calendar calendar;

    /** Inclusive beginning of the occupied or declared interval. */
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /** Exclusive end; a following interval may start at this instant. */
    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    /** Declared availability persisted as the stable FREE/BUSY enum name. */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 4)
    private SlotStatus status;

    /** Assigns a UUID before the first insert when the caller has not supplied one. */
    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
    }
}
