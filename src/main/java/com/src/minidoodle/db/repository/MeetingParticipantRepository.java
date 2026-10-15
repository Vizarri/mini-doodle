package com.src.minidoodle.db.repository;

import com.src.minidoodle.db.model.MeetingParticipant;
import com.src.minidoodle.db.model.MeetingParticipantId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface MeetingParticipantRepository extends JpaRepository<MeetingParticipant, MeetingParticipantId> {
    List<MeetingParticipant> findByIdMeetingId(UUID meetingId);

    /** Inserts a known-new composite key without Spring Data's merge existence query. */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into meeting_participants (meeting_id, user_id)
            values (:meetingId, :userId)
            """, nativeQuery = true)
    void insert(@Param("meetingId") UUID meetingId, @Param("userId") UUID userId);
}
