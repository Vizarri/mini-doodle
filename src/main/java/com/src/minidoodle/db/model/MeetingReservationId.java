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
 * Composite reservation key: a meeting occupies each participant calendar once.
 * Cross-meeting overlap is enforced separately by the database range constraint.
 */
@Embeddable
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class MeetingReservationId implements Serializable {
    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "calendar_id", nullable = false)
    private UUID calendarId;
}
