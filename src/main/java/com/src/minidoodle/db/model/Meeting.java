package com.src.minidoodle.db.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * Meeting details and original interval, retained after cancellation.
 * Membership is stored in {@link MeetingParticipant}; only active occupancy uses {@link MeetingReservation}.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "meetings")
public class Meeting {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;

    /** Owner of the source slot, automatically included in meeting membership. */
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "organizer_user_id", nullable = false)
    private User organizer;

    /** Original slot; the database clears this association if it is deleted after cancellation. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "source_slot_id")
    private TimeSlot sourceSlot;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 4000)
    private String description;

    /** Inclusive beginning of the occupied or declared interval. */
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /** Exclusive end; a following interval may start at this instant. */
    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;

    /** Null while active; set once cancellation releases all reservations. */
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    /** Creation instant assigned by the persistence lifecycle and never updated. */
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Assigns the UUID and creation timestamp before the first insert. */
    @PrePersist
    void onCreate() {
        if (id == null) {
            id = UUID.randomUUID();
        }
        createdAt = Instant.now();
    }
}
