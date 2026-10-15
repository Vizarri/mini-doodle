package com.src.minidoodle.db.model;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.NoArgsConstructor;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;

/**
 * Active meeting occupancy in one participant's calendar.
 * Cancellation deletes this row; a PostgreSQL exclusion constraint prevents overlapping reservations.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "meeting_reservations")
public class MeetingReservation {

    @EmbeddedId
    private MeetingReservationId id;

    /** Meeting owning this row; its identifier is also part of the composite key. */
    @MapsId("meetingId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    /** Calendar whose free time is consumed by this active reservation. */
    @MapsId("calendarId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "calendar_id", nullable = false)
    private Calendar calendar;

    /** Inclusive beginning of the occupied or declared interval. */
    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    /** Exclusive end; a following interval may start at this instant. */
    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;
}
