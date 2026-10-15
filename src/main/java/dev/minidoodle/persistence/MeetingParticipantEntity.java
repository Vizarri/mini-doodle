package dev.minidoodle.persistence;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "meeting_participants")
@Getter
@Setter
@NoArgsConstructor
public class MeetingParticipantEntity extends AssignedIdEntity<MeetingParticipantId> {
    @EmbeddedId
    private MeetingParticipantId id;
}
