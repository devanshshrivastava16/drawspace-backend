package com.drawspace.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.drawspace.entity.Room;

import jakarta.persistence.LockModeType;

/**
 * Room persistence (CONTRACT §10/§11). {@link #findByIdForUpdate} takes a
 * PESSIMISTIC_WRITE row lock used by join/save transactions before counting
 * and inserting (concurrency backstop: UNIQUE constraints in the schema).
 */
public interface RoomRepository extends JpaRepository<Room, UUID> {

    Optional<Room> findByRoomCode(String roomCode);

    boolean existsByRoomCode(String roomCode);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from Room r where r.id = :id")
    Optional<Room> findByIdForUpdate(@Param("id") UUID id);
}
