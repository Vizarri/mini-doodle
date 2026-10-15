package com.src.minidoodle.db.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

@Embeddable
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class MeetingReservationId implements Serializable {
    @Column(name = "meeting_id", nullable = false)
    private UUID meetingId;

    @Column(name = "calendar_id", nullable = false)
    private UUID calendarId;
}
