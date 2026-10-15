package dev.minidoodle.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

@Entity
@Table(name = "meeting_reservations")
@Getter
@Setter
@NoArgsConstructor
public class MeetingReservationEntity extends AssignedIdEntity<MeetingReservationId> {
    @EmbeddedId
    private MeetingReservationId id;

    @Column(name = "starts_at", nullable = false)
    private Instant startsAt;

    @Column(name = "ends_at", nullable = false)
    private Instant endsAt;
}
