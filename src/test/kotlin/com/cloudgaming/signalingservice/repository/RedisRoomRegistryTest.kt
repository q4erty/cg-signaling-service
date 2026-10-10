package com.cloudgaming.signalingservice.repository

import com.cloudgaming.signalingservice.model.RoomId
import com.cloudgaming.signalingservice.model.RoomResolutionResult
import com.cloudgaming.signalingservice.model.RoomStatus
import com.cloudgaming.signalingservice.repository.RedisRoomRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate
import java.time.Instant

class RedisRoomRegistryTest {

    private lateinit var redisTemplate: StringRedisTemplate
    private lateinit var hashOps: HashOperations<String, String, String>
    private lateinit var roomRegistry: RedisRoomRegistry

    @BeforeEach
    fun setUp() {
        redisTemplate = mockk(relaxed = true)
        hashOps = mockk(relaxed = true)
        every { redisTemplate.opsForHash<String, String>() } returns hashOps
        roomRegistry = RedisRoomRegistry(redisTemplate)
    }

    private fun stubRoom(roomId: String, entries: Map<String, String>) {
        every { hashOps.entries("room:$roomId") } returns entries
    }

    private fun validEntries(
        workerIp: String = "192.168.1.100",
        port: String = "9090",
        gameId: String = "xonotic",
        status: String = "ACTIVE",
    ): Map<String, String> = mapOf(
        "worker_ip" to workerIp,
        "webrtc_port" to port,
        "game_id" to gameId,
        "status" to status,
        "created_at" to Instant.now().toString(),
    )

    @Test
    fun `resolve existing room returns Found`() {
        stubRoom("test-room", validEntries())

        val result = roomRegistry.resolve(RoomId.of("test-room").getOrThrow())

        assertTrue(result is RoomResolutionResult.Found)
        val room = (result as RoomResolutionResult.Found).room
        assertEquals("192.168.1.100", room.workerIp)
        assertEquals(9090, room.webrtcPort)
        assertEquals("xonotic", room.gameId)
        assertEquals(RoomStatus.ACTIVE, room.status)
    }

    @Test
    fun `resolve non-existent room returns NotFound`() {
        every { hashOps.entries("room:missing") } returns emptyMap()

        val result = roomRegistry.resolve(RoomId.of("missing").getOrThrow())
        assertTrue(result is RoomResolutionResult.NotFound)
    }

    @Test
    fun `resolve malformed room - missing worker_ip returns Malformed`() {
        stubRoom("malformed", validEntries().minus("worker_ip"))

        val result = roomRegistry.resolve(RoomId.of("malformed").getOrThrow())
        assertTrue(result is RoomResolutionResult.Malformed)
        assertTrue((result as RoomResolutionResult.Malformed).reason.contains("worker_ip"))
    }

    @Test
    fun `resolve malformed room - non-numeric port returns Malformed`() {
        stubRoom("bad-port", validEntries(port = "abc"))

        val result = roomRegistry.resolve(RoomId.of("bad-port").getOrThrow())
        assertTrue(result is RoomResolutionResult.Malformed)
        assertTrue((result as RoomResolutionResult.Malformed).reason.contains("webrtc_port"))
    }

    @Test
    fun `resolve malformed room - out-of-range port returns Malformed`() {
        stubRoom("big-port", validEntries(port = "70000"))

        val result = roomRegistry.resolve(RoomId.of("big-port").getOrThrow())
        assertTrue(result is RoomResolutionResult.Malformed)
        assertTrue((result as RoomResolutionResult.Malformed).reason.contains("webrtc_port"))
    }

    @Test
    fun `resolve malformed room - unknown status returns Malformed`() {
        stubRoom("bad-status", validEntries(status = "UNKNOWN"))

        val result = roomRegistry.resolve(RoomId.of("bad-status").getOrThrow())
        assertTrue(result is RoomResolutionResult.Malformed)
        assertTrue((result as RoomResolutionResult.Malformed).reason.contains("status"))
    }

    @Test
    fun `resolve malformed room - invalid IPv6 returns Malformed`() {
        stubRoom("bad-ipv6", validEntries(workerIp = "[::ggg]"))

        val result = roomRegistry.resolve(RoomId.of("bad-ipv6").getOrThrow())
        assertTrue(result is RoomResolutionResult.Malformed)
        assertTrue((result as RoomResolutionResult.Malformed).reason.contains("worker_ip"))
    }

    @Test
    fun `resolve returns Unavailable when Redis is down`() {
        every { hashOps.entries(any()) } throws RedisConnectionFailureException("Redis down", null)

        val result = roomRegistry.resolve(RoomId.of("test").getOrThrow())
        assertTrue(result is RoomResolutionResult.Unavailable)
    }
}