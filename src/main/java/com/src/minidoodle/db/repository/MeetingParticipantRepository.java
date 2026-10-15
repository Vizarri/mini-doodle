package com.src.minidoodle.db.repository;

import com.src.minidoodle.db.model.MeetingParticipant;
import com.src.minidoodle.db.model.MeetingParticipantId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/**
 * Stores immutable meeting membership, which survives cancellation.
 */
public interface MeetingParticipantRepository extends JpaRepository<MeetingParticipant, MeetingParticipantId> {

    /**
     * Reads all retained members of a meeting.
     *
     * @param meetingId meeting identifier
     * @return membership rows in unspecified order, or an empty list when none exist
     */
    List<MeetingParticipant> findByIdMeetingId(UUID meetingId);

    /**
     * Inserts a known-new composite key without a merge existence query.
     * The meeting and user must already exist; this write joins the booking transaction.
     *
     * @param meetingId persisted meeting identifier
     * @param userId participant identifier, including the organizer
     */
    @Modifying(flushAutomatically = true)
    @Query(value = """
            insert into meeting_participants (meeting_id, user_id)
            values (:meetingId, :userId)
            """, nativeQuery = true)
    void insert(@Param("meetingId") UUID meetingId, @Param("userId") UUID userId);
}
