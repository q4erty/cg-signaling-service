package com.cloudgaming.signalingservice.repository

import com.cloudgaming.signalingservice.model.Room
import com.cloudgaming.signalingservice.model.RoomId
import com.cloudgaming.signalingservice.model.RoomResolutionResult
import com.cloudgaming.signalingservice.model.RoomStatus
import org.slf4j.LoggerFactory
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.format.DateTimeParseException

@Component
class RedisRoomRegistry(
    private val redisTemplate: StringRedisTemplate,
) : RoomRegistry {

    private val log = LoggerFactory.getLogger(RedisRoomRegistry::class.java)

    companion object {
        private const val KEY_PREFIX = "room:"
        private const val FIELD_WORKER_IP = "worker_ip"
        private const val FIELD_WEBRTC_PORT = "webrtc_port"
        private const val FIELD_GAME_ID = "game_id"
        private const val FIELD_STATUS = "status"
        private const val FIELD_CREATED_AT = "created_at"
    }

    override fun resolve(roomId: RoomId): RoomResolutionResult {
        val key = "$KEY_PREFIX${roomId.value}"

        val entries = try {
            redisTemplate.opsForHash<String, String>().entries(key)
        } catch (e: RedisConnectionFailureException) {
            log.error("Redis connection failure while resolving room {}", roomId.value, e)
            return RoomResolutionResult.Unavailable(e)
        } catch (e: Exception) {
            log.error("Unexpected error accessing Redis for room {}", roomId.value, e)
            return RoomResolutionResult.Unavailable(e)
        }

        if (entries.isEmpty()) {
            return RoomResolutionResult.NotFound
        }

        return try {
            val room = parseRoom(roomId, entries)
            RoomResolutionResult.Found(room)
        } catch (e: MalformedRoomException) {
            log.warn("Room {} is malformed: {}", roomId.value, e.message)
            RoomResolutionResult.Malformed(e.message ?: "unknown reason")
        }
    }

    private fun parseRoom(roomId: RoomId, entries: Map<String, String>): Room {
        val workerIp = entries[FIELD_WORKER_IP]
            ?: throw MalformedRoomException("missing field: $FIELD_WORKER_IP")

        if (!Room.validateWorkerIp(workerIp)) {
            throw MalformedRoomException("invalid worker_ip: '$workerIp'")
        }

        val webrtcPortStr = entries[FIELD_WEBRTC_PORT]
            ?: throw MalformedRoomException("missing field: $FIELD_WEBRTC_PORT")

        val webrtcPort = webrtcPortStr.toIntOrNull()
            ?: throw MalformedRoomException("webrtc_port is not a number: '$webrtcPortStr'")

        if (!Room.validatePort(webrtcPort)) {
            throw MalformedRoomException("webrtc_port out of range: $webrtcPort")
        }

        val gameId = entries[FIELD_GAME_ID]
            ?: throw MalformedRoomException("missing field: $FIELD_GAME_ID")

        if (gameId.isBlank()) {
            throw MalformedRoomException("game_id is blank")
        }

        val statusStr = entries[FIELD_STATUS]
            ?: throw MalformedRoomException("missing field: $FIELD_STATUS")

        val status = RoomStatus.fromString(statusStr)
            ?: throw MalformedRoomException("unknown status: '$statusStr'")

        val createdAtStr = entries[FIELD_CREATED_AT]
            ?: throw MalformedRoomException("missing field: $FIELD_CREATED_AT")

        val createdAt = try {
            Instant.parse(createdAtStr)
        } catch (e: DateTimeParseException) {
            throw MalformedRoomException("invalid created_at format: '$createdAtStr'")
        }

        return Room(
            roomId = roomId,
            workerIp = workerIp,
            webrtcPort = webrtcPort,
            gameId = gameId,
            status = status,
            createdAt = createdAt,
        )
    }

    private class MalformedRoomException(message: String) : RuntimeException(message)
}