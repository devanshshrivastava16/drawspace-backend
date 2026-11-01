package com.drawspace.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.drawspace.entity.RoomParticipant;

/**
 * Room participant persistence (CONTRACT §10/§11).
 * Active = {@code left_at IS NULL AND last_active_at >= now() - staleThreshold}.
 */
public interface RoomParticipantRepository extends JpaRepository<RoomParticipant, UUID> {

    Optional<RoomParticipant> findByRoomIdAndParticipantId(UUID roomId, UUID participantId);

    boolean existsByRoomIdAndParticipantId(UUID roomId, UUID participantId);

    Optional<RoomParticipant> findByRoomIdAndParticipantIdAndParticipantToken(
            UUID roomId, UUID participantId, UUID participantToken);

    /** Capacity check (§10): left_at IS NULL AND last_active_at >= threshold. */
    @Query("""
            select count(p) from RoomParticipant p
            where p.roomId = :roomId and p.leftAt is null and p.lastActiveAt >= :threshold
            """)
    long countActiveByRoomId(@Param("roomId") UUID roomId, @Param("threshold") Instant threshold);

    /** Active participants of a room, earliest joiner first (owner transfer order, §10). */
    @Query("""
            select p from RoomParticipant p
            where p.roomId = :roomId and p.leftAt is null
            order by p.joinedAt asc
            """)
    List<RoomParticipant> findActiveByRoomId(@Param("roomId") UUID roomId);

    /** Global sweep helper for the 60s scheduler: enrolled participants gone stale. */
    List<RoomParticipant> findAllByLeftAtIsNullAndLastActiveAtBefore(Instant cutoff);

    /** Stale participants (left_at IS NULL AND last_active_at < cutoff), grouped per room. */
    @Query("""
            select p from RoomParticipant p
            where p.leftAt is null and p.lastActiveAt < :cutoff
            order by p.roomId, p.joinedAt
            """)
    List<RoomParticipant> findAllStale(@Param("cutoff") Instant cutoff);
}
