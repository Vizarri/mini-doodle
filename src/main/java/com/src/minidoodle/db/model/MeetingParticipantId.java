package com.src.minidoodle.db.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/**
 * Composite membership key: a user can occur only once in a meeting.
 * Its fields also supply the identifiers for the entity's {@code @MapsId} associations.
 */
@Embeddable
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class MeetingParticipantId implements Serializable {
    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;
}
