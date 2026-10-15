package dev.minidoodle.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface MeetingParticipantRepository extends JpaRepository<MeetingParticipantEntity, MeetingParticipantId> {
    List<MeetingParticipantEntity> findByIdMeetingId(UUID meetingId);
}
