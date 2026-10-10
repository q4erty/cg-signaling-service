package com.cloudgaming.signalingservice.service

import com.cloudgaming.signalingservice.model.Room
import com.cloudgaming.signalingservice.model.RoomId
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class RoomRegistrationService(
    private val redisTemplate: StringRedisTemplate,
    @Value("\${rooms.dev-ttl:}") devTtlRaw: String?,
) {
    private val log = LoggerFactory.getLogger(RoomRegistrationService::class.java)

    private val devTtl: Duration? = if (devTtlRaw.isNullOrBlank()) {
        null
    } else {
        val parsed = try {
            Duration.parse(devTtlRaw)
        } catch (e: Exception) {
            throw IllegalStateException(
                "Invalid rooms.dev-ttl value: '$devTtlRaw'. Must be ISO-8601 duration (e.g. PT1H)",
                e,
            )
        }
        require(!parsed.isNegative && !parsed.isZero) {
            "rooms.dev-ttl must be positive, got: $parsed"
        }
        require(parsed.seconds >= 1) {
            "rooms.dev-ttl must be at least 1 second to avoid immediate key expiration, got: $parsed"
        }
        parsed
    }

    companion object {
        private const val KEY_PREFIX = "room:"
        private const val FIELD_WORKER_IP = "worker_ip"
        private const val FIELD_WEBRTC_PORT = "webrtc_port"
        private const val FIELD_GAME_ID = "game_id"
        private const val FIELD_STATUS = "status"
        private const val FIELD_CREATED_AT = "created_at"

        private const val REGISTER_SCRIPT = """
            local exists = redis.call('EXISTS', KEYS[1])
            redis.call('HSET', KEYS[1],
                'worker_ip', ARGV[1],
                'webrtc_port', ARGV[2],
                'game_id', ARGV[3],
                'status', 'ACTIVE',
                'created_at', ARGV[4])
            if ARGV[5] ~= '' then
                redis.call('EXPIRE', KEYS[1], tonumber(ARGV[5]))
            else
                redis.call('PERSIST', KEYS[1])
            end
            return exists
        """
    }

    private val script = RedisScript.of(REGISTER_SCRIPT.trimIndent(), Long::class.java)

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
        val ttlSeconds = devTtl?.seconds?.toString() ?: ""

        val existed = redisTemplate.execute(
            script,
            listOf(key),
            workerIp,
            webrtcPort.toString(),
            gameId,
            Instant.now().toString(),
            ttlSeconds,
        ) ?: 0L

        val isNew = existed == 0L
        log.info(
            "Registered room {} ({}), ttl={}",
            roomId.value,
            if (isNew) "new" else "overwrite",
            devTtl?.toString() ?: "none",
        )

        return isNew
    }
}