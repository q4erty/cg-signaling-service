package com.cloudgaming.signalingservice.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

class RoomTest {

    private fun createRoom(
        workerIp: String,
        webrtcPort: Int = 9090,
        ownerId: String = "user-42",
    ): Room {
        return Room(
            roomId = RoomId.of("test-room").getOrThrow(),
            workerIp = workerIp,
            webrtcPort = webrtcPort,
            gameId = "xonotic",
            status = RoomStatus.ACTIVE,
            createdAt = Instant.now(),
            ownerId = ownerId,
        )
    }

    @Test
    fun `workerBaseUrl with IPv4`() {
        val room = createRoom(workerIp = "192.168.1.100", webrtcPort = 9090)
        assertEquals("http://192.168.1.100:9090", room.workerBaseUrl())
    }

    @Test
    fun `workerBaseUrl with IPv6`() {
        val room = createRoom(workerIp = "::1", webrtcPort = 8080)
        assertEquals("http://[::1]:8080", room.workerBaseUrl())
    }

    @Test
    fun `workerBaseUrl with already-bracketed IPv6`() {
        val room = createRoom(workerIp = "[::1]", webrtcPort = 8080)
        assertEquals("http://[::1]:8080", room.workerBaseUrl())
    }

    @Test
    fun `workerBaseUrl with hostname`() {
        val room = createRoom(workerIp = "worker-1.game.local", webrtcPort = 7777)
        assertEquals("http://worker-1.game.local:7777", room.workerBaseUrl())
    }

    @Test
    fun `validateWorkerIp accepts valid IPv4`() {
        assertTrue(Room.validateWorkerIp("192.168.1.1"))
        assertTrue(Room.validateWorkerIp("0.0.0.0"))
        assertTrue(Room.validateWorkerIp("255.255.255.255"))
    }

    @Test
    fun `validateWorkerIp rejects invalid IPv4`() {
        assertFalse(Room.validateWorkerIp("256.1.1.1"))
        assertFalse(Room.validateWorkerIp("192.168.1"))      // ← теперь корректно reject
        assertFalse(Room.validateWorkerIp("192.168.1.1.1"))
        assertFalse(Room.validateWorkerIp("01.02.03.04"))    // leading zeros
    }

    @Test
    fun `validateWorkerIp accepts valid IPv6 in brackets`() {
        assertTrue(Room.validateWorkerIp("[::1]"))
        assertTrue(Room.validateWorkerIp("[2001:db8::1]"))
    }

    @Test
    fun `validateWorkerIp accepts valid hostname`() {
        assertTrue(Room.validateWorkerIp("localhost"))
        assertTrue(Room.validateWorkerIp("worker-1"))
        assertTrue(Room.validateWorkerIp("game.worker.local"))
    }

    @Test
    fun `validateWorkerIp rejects invalid hostname`() {
        assertFalse(Room.validateWorkerIp("-worker"))
        assertFalse(Room.validateWorkerIp("worker-"))
        assertFalse(Room.validateWorkerIp("worker..local"))
    }

    @Test
    fun `validatePort accepts valid ports`() {
        assertTrue(Room.validatePort(1))
        assertTrue(Room.validatePort(80))
        assertTrue(Room.validatePort(8080))
        assertTrue(Room.validatePort(65535))
    }

    @Test
    fun `validatePort rejects invalid ports`() {
        assertFalse(Room.validatePort(0))
        assertFalse(Room.validatePort(-1))
        assertFalse(Room.validatePort(65536))
        assertFalse(Room.validatePort(70000))
    }

    @Test
    fun `RoomStatus fromString returns correct enum`() {
        assertEquals(RoomStatus.ACTIVE, RoomStatus.fromString("ACTIVE"))
        assertEquals(RoomStatus.STOPPED, RoomStatus.fromString("STOPPED"))
        assertEquals(RoomStatus.ERROR, RoomStatus.fromString("ERROR"))
        assertNull(RoomStatus.fromString("UNKNOWN"))
        assertNull(RoomStatus.fromString(""))
    }
}