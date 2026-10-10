package com.cloudgaming.signalingservice.websocket

import com.cloudgaming.signalingservice.model.*
import com.cloudgaming.signalingservice.repository.RoomRegistry
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.server.ServletServerHttpRequest
import org.springframework.http.server.ServletServerHttpResponse
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.socket.WebSocketHandler
import java.time.Instant

class SignalingHandshakeInterceptorTest {

    private lateinit var roomRegistry: RoomRegistry
    private lateinit var interceptor: SignalingHandshakeInterceptor
    private lateinit var request: MockHttpServletRequest
    private lateinit var response: MockHttpServletResponse
    private lateinit var wsHandler: WebSocketHandler

    @BeforeEach
    fun setUp() {
        roomRegistry = mockk()
        interceptor = SignalingHandshakeInterceptor(roomRegistry)
        request = MockHttpServletRequest()
        response = MockHttpServletResponse()
        wsHandler = mockk()
    }

    @Test
    fun `successful handshake with active room`() {
        val roomId = RoomId.of("test-room").getOrThrow()
        val room = createRoom(roomId, RoomStatus.ACTIVE)

        request.requestURI = "/api/v1/signaling"
        request.queryString = "room_id=test-room"

        every { roomRegistry.resolve(roomId) } returns RoomResolutionResult.Found(room)

        val attributes = mutableMapOf<String, Any>()
        val result = interceptor.beforeHandshake(
            ServletServerHttpRequest(request),
            ServletServerHttpResponse(response),
            wsHandler,
            attributes,
        )

        assertTrue(result)
        assertEquals(200, response.status)
        assertEquals("test-room", attributes[SignalingHandshakeInterceptor.ATTR_ROOM_ID])
        assertNotNull(attributes[SignalingHandshakeInterceptor.ATTR_WORKER_BASE_URL])
    }

    @Test
    fun `missing room_id returns 400`() {
        request.requestURI = "/api/v1/signaling"
        request.queryString = ""

        val attributes = mutableMapOf<String, Any>()
        val result = interceptor.beforeHandshake(
            ServletServerHttpRequest(request),
            ServletServerHttpResponse(response),
            wsHandler,
            attributes,
        )

        assertFalse(result)
        assertEquals(400, response.status)
        assertTrue(attributes.isEmpty())
    }

    @Test
    fun `invalid room_id returns 400`() {
        request.requestURI = "/api/v1/signaling"
        request.queryString = "room_id=invalid@room"

        val attributes = mutableMapOf<String, Any>()
        val result = interceptor.beforeHandshake(
            ServletServerHttpRequest(request),
            ServletServerHttpResponse(response),
            wsHandler,
            attributes,
        )

        assertFalse(result)
        assertEquals(400, response.status)
    }

    @Test
    fun `room not found returns 404`() {
        val roomId = RoomId.of("non-existent").getOrThrow()
        request.requestURI = "/api/v1/signaling"
        request.queryString = "room_id=non-existent"

        every { roomRegistry.resolve(roomId) } returns RoomResolutionResult.NotFound

        val attributes = mutableMapOf<String, Any>()
        val result = interceptor.beforeHandshake(
            ServletServerHttpRequest(request),
            ServletServerHttpResponse(response),
            wsHandler,
            attributes,
        )

        assertFalse(result)
        assertEquals(404, response.status)
    }

    @Test
    fun `room not active returns 409`() {
        val roomId = RoomId.of("inactive-room").getOrThrow()
        val room = createRoom(roomId, RoomStatus.STOPPED)

        request.requestURI = "/api/v1/signaling"
        request.queryString = "room_id=inactive-room"

        every { roomRegistry.resolve(roomId) } returns RoomResolutionResult.Found(room)

        val attributes = mutableMapOf<String, Any>()
        val result = interceptor.beforeHandshake(
            ServletServerHttpRequest(request),
            ServletServerHttpResponse(response),
            wsHandler,
            attributes,
        )

        assertFalse(result)
        assertEquals(409, response.status)
    }

    @Test
    fun `malformed room returns 502`() {
        val roomId = RoomId.of("malformed-room").getOrThrow()
        request.requestURI = "/api/v1/signaling"
        request.queryString = "room_id=malformed-room"

        every { roomRegistry.resolve(roomId) } returns RoomResolutionResult.Malformed("missing field")

        val attributes = mutableMapOf<String, Any>()
        val result = interceptor.beforeHandshake(
            ServletServerHttpRequest(request),
            ServletServerHttpResponse(response),
            wsHandler,
            attributes,
        )

        assertFalse(result)
        assertEquals(502, response.status)
    }

    @Test
    fun `redis unavailable returns 503`() {
        val roomId = RoomId.of("test-room").getOrThrow()
        request.requestURI = "/api/v1/signaling"
        request.queryString = "room_id=test-room"

        every { roomRegistry.resolve(roomId) } returns RoomResolutionResult.Unavailable(RuntimeException("Redis down"))

        val attributes = mutableMapOf<String, Any>()
        val result = interceptor.beforeHandshake(
            ServletServerHttpRequest(request),
            ServletServerHttpResponse(response),
            wsHandler,
            attributes,
        )

        assertFalse(result)
        assertEquals(503, response.status)
    }

    private fun createRoom(roomId: RoomId, status: RoomStatus): Room {
        return Room(
            roomId = roomId,
            workerIp = "192.168.1.100",
            webrtcPort = 9090,
            gameId = "xonotic",
            status = status,
            createdAt = Instant.now(),
        )
    }
}