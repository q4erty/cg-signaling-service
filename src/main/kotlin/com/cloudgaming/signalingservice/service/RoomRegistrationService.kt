package com.cloudgaming.signalingservice.controller

import com.cloudgaming.signalingservice.model.Room
import com.cloudgaming.signalingservice.model.RoomId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class RoomRegistrationService(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${rooms.dev-ttl:}") private val devTtl: String?,
) {

    private val log = LoggerFactory.getLogger(RoomRegistrationService::class.java)

    companion object {
        private const val KEY_PREFIX = "room:"
        private const val FIELD_WORKER_IP = "worker_ip"
        private const val FIELD_WEBRTC_PORT = "webrtc_port"
        private const val FIELD_GAME_ID = "game_id"
        private const val FIELD_STATUS = "status"
        private const val FIELD_CREATED_AT = "created_at"
    }

    fun register(roomId: RoomId, workerIp: String, webrtcPort: Int, gameId: String): Boolean {
        if (!Room.validateWorkerIp(workerIp)) {
            throw IllegalArgumentException("Invalid worker_ip: '$workerIp'")
        }

        if (!Room.validatePort(webrtcPort)) {
            throw IllegalArgumentException("Invalid webrtc_port: $webrtcPort")
        }

        if (gameId.isBlank()) {
            throw IllegalArgumentException("gameId is blank")
        }

        val key = "$KEY_PREFIX${roomId.value}"
        val existingKey = redisTemplate.hasKey(key)

        val entries = mapOf(
            FIELD_WORKER_IP to workerIp,
            FIELD_WEBRTC_PORT to webrtcPort.toString(),
            FIELD_GAME_ID to gameId,
            FIELD_STATUS to "ACTIVE",
            FIELD_CREATED_AT to Instant.now().toString(),
        )

        redisTemplate.opsForHash<String, String>().putAll(key, entries)

        if (!devTtl.isNullOrBlank()) {
            val ttl = Duration.parse(devTtl)
            redisTemplate.expire(key, ttl)
            log.info("Registered room {} with TTL {}", roomId.value, ttl)
        } else {
            log.info("Registered room {}", roomId.value)
        }

        return !existingKey
    }
}