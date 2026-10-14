package com.cloudgaming.signalingservice.controller

import com.cloudgaming.signalingservice.dto.RegisterRoomRequest
import com.cloudgaming.signalingservice.model.RoomId
import com.cloudgaming.signalingservice.service.RoomRegistrationService
import com.fasterxml.jackson.databind.ObjectMapper
import jakarta.validation.Validator
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/internal/rooms")
@ConditionalOnProperty(name = ["internal.secret"], matchIfMissing = false)
class InternalRoomController(
    private val roomRegistrationService: RoomRegistrationService,
    private val objectMapper: ObjectMapper,
    private val validator: Validator,
) {

    private val log = LoggerFactory.getLogger(InternalRoomController::class.java)

    @PostMapping("/{roomId}/register")
    fun registerRoom(
        @PathVariable roomId: String,
        @RequestBody body: String,
    ): ResponseEntity<String> {
        val roomIdResult = RoomId.of(roomId)

        if (roomIdResult.isFailure) {
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body("Invalid room_id: ${roomIdResult.exceptionOrNull()?.message}")
        }

        val request = try {
            objectMapper.readValue(body, RegisterRoomRequest::class.java)
        } catch (e: Exception) {
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body("Invalid request body: ${e.message}")
        }

        val violations = validator.validate(request)
        if (violations.isNotEmpty()) {
            val message = violations.joinToString("; ") { "${it.propertyPath}: ${it.message}" }
            return ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body("Validation failed: $message")
        }

        return try {
            val isNew = roomRegistrationService.register(
                roomId = roomIdResult.getOrThrow(),
                workerIp = request.workerIp,
                webrtcPort = request.webrtcPort,
                gameId = request.gameId,
                ownerId = request.ownerId,
            )

            val status = if (isNew) HttpStatus.CREATED else HttpStatus.OK
            ResponseEntity.status(status).build()
        } catch (e: IllegalArgumentException) {
            ResponseEntity
                .status(HttpStatus.BAD_REQUEST)
                .body(e.message ?: "Invalid request")
        } catch (e: Exception) {
            log.error("Failed to register room {}", roomId, e)
            ResponseEntity
                .status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body("Internal server error")
        }
    }
}
