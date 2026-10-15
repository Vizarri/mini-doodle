package com.src.minidoodle.db.model;

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

/**
 * Persistent meeting membership, including the organizer.
 * Unlike a reservation, this row remains after cancellation so meeting history stays complete.
 */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "meeting_participants")
public class MeetingParticipant {

    @EmbeddedId
    private MeetingParticipantId id;

    /** Meeting owning this row; its identifier is also part of the composite key. */
    @MapsId("meetingId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    /** Participating user, retained even after the meeting is cancelled. */
    @MapsId("userId")
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;
}
