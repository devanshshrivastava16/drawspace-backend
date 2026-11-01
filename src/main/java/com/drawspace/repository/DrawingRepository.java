package com.drawspace.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.drawspace.entity.Drawing;

/**
 * Drawing persistence (CONTRACT §11). One canonical drawing per room.
 * {@code findById} is inherited from {@link JpaRepository}.
 */
public interface DrawingRepository extends JpaRepository<Drawing, UUID> {

    Optional<Drawing> findByRoomId(UUID roomId);
}
