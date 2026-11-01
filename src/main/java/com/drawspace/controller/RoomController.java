package com.drawspace.controller;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.drawspace.dto.CanvasAuthRequest;
import com.drawspace.dto.CreateRoomRequest;
import com.drawspace.dto.JoinRoomRequest;
import com.drawspace.dto.LeaveRoomRequest;
import com.drawspace.dto.ParticipantResponse;
import com.drawspace.dto.RoomDetailsResponse;
import com.drawspace.dto.RoomJoinResponse;
import com.drawspace.service.RoomService;

import jakarta.validation.Valid;

/**
 * Room REST API (CONTRACT §3 #1–#5, #10). Errors are mapped by
 * {@code GlobalExceptionHandler} to {@code {status, error, message, timestamp}}.
 */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomService roomService;

    public RoomController(RoomService roomService) {
        this.roomService = roomService;
    }

    /** §3 #1 — create room (201 with the creator identity). */
    @PostMapping
    public ResponseEntity<RoomJoinResponse> createRoom(@Valid @RequestBody CreateRoomRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(roomService.createRoom(request));
    }

    /** §3 #2 — join room (200; 404 unknown / 409 full / 410 expired-inactive). */
    @PostMapping("/{roomCode}/join")
    public RoomJoinResponse joinRoom(@PathVariable String roomCode,
                                     @Valid @RequestBody JoinRoomRequest request) {
        return roomService.joinRoom(roomCode, request);
    }

    /** §3 #3 — room details. */
    @GetMapping("/{roomCode}")
    public RoomDetailsResponse getRoomDetails(@PathVariable String roomCode) {
        return roomService.getRoomDetails(roomCode);
    }

    /** §3 #4 — active participants (also the roster broadcast shape). */
    @GetMapping("/{roomCode}/participants")
    public List<ParticipantResponse> getParticipants(@PathVariable String roomCode) {
        return roomService.getActiveParticipants(roomCode);
    }

    /** §3 #5 — leave (204); ownership transfers if the owner leaves. */
    @PostMapping("/{roomCode}/leave")
    public ResponseEntity<Void> leaveRoom(@PathVariable String roomCode,
                                          @Valid @RequestBody LeaveRoomRequest request) {
        roomService.leave(roomCode, request);
        return ResponseEntity.noContent().build();
    }

    /** §3 #10 — owner-only canvas clear, persisted as a CLEAR event (204). */
    @DeleteMapping("/{roomCode}/canvas")
    public ResponseEntity<Void> clearCanvas(@PathVariable String roomCode,
                                            @Valid @RequestBody CanvasAuthRequest request) {
        roomService.clearCanvas(roomCode, request);
        return ResponseEntity.noContent().build();
    }
}
