package com.cloudgaming.signalingservice.websocket

import com.cloudgaming.signalingservice.model.Room
import com.cloudgaming.signalingservice.model.RoomId
import com.cloudgaming.signalingservice.model.RoomResolutionResult
import com.cloudgaming.signalingservice.model.RoomStatus
import com.cloudgaming.signalingservice.repository.RoomRegistry
import com.cloudgaming.signalingservice.security.AuthenticationResult
import com.cloudgaming.signalingservice.security.HandshakeAuthenticator
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
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
    private lateinit var authenticator: HandshakeAuthenticator
    private lateinit var interceptor: SignalingHandshakeInterceptor
    private lateinit var request: MockHttpServletRequest
    private lateinit var response: MockHttpServletResponse
    private lateinit var wsHandler: WebSocketHandler

    private val testUserId = "user-42"

    @BeforeEach
    fun setUp() {
        roomRegistry = mockk()
        authenticator = mockk()
        interceptor = SignalingHandshakeInterceptor(roomRegistry, authenticator)
        request = MockHttpServletRequest()
        response = MockHttpServletResponse()
        wsHandler = mockk()
    }

    private fun setupValidAuth() {
        every { authenticator.authenticate(any()) } returns AuthenticationResult.Authenticated(testUserId)
    }

    @Test
    fun `successful handshake with valid token and active room owned by user`() {
        setupValidAuth()
        val roomId = RoomId.of("test-room").getOrThrow()
        val room = createRoom(roomId, RoomStatus.ACTIVE, ownerId = testUserId)

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
        assertEquals(testUserId, attributes[SignalingHandshakeInterceptor.ATTR_USER_ID])
    }

    @Test
    fun `missing room_id returns 400 before authentication`() {
        every { authenticator.authenticate(any()) } throws AssertionError("Should not be called")

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
    fun `missing token returns 401 with WWW-Authenticate header`() {
        every { authenticator.authenticate(any()) } returns AuthenticationResult.Missing

        val roomId = RoomId.of("test-room").getOrThrow()
        request.requestURI = "/api/v1/signaling"
        request.queryString = "room_id=test-room"

        val attributes = mutableMapOf<String, Any>()
        val result = interceptor.beforeHandshake(
            ServletServerHttpRequest(request),
            ServletServerHttpResponse(response),
            wsHandler,
            attributes,
        )

        assertFalse(result)
        assertEquals(401, response.status)
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"))
        assertTrue(attributes.isEmpty())
    }

    @Test
    fun `invalid token returns 401 with WWW-Authenticate header`() {
        every { authenticator.authenticate(any()) } returns AuthenticationResult.Invalid("expired")

        val roomId = RoomId.of("test-room").getOrThrow()
        request.requestURI = "/api/v1/signaling"
        request.queryString = "room_id=test-room"

        val attributes = mutableMapOf<String, Any>()
        val result = interceptor.beforeHandshake(
            ServletServerHttpRequest(request),
            ServletServerHttpResponse(response),
            wsHandler,
            attributes,
        )

        assertFalse(result)
        assertEquals(401, response.status)
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"))
    }

    @Test
    fun `auth unavailable returns 503 not 401`() {
        every { authenticator.authenticate(any()) } returns AuthenticationResult.Unavailable(RuntimeException("JWKS down"))

        val roomId = RoomId.of("test-room").getOrThrow()
        request.requestURI = "/api/v1/signaling"
        request.queryString = "room_id=test-room"

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

    @Test
    fun `room not found returns 404 only after successful auth`() {
        setupValidAuth()
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
    fun `unauthenticated request to non-existent room returns 401 not 404`() {
        every { authenticator.authenticate(any()) } returns AuthenticationResult.Missing

        request.requestURI = "/api/v1/signaling"
        request.queryString = "room_id=non-existent"

        val attributes = mutableMapOf<String, Any>()
        val result = interceptor.beforeHandshake(
            ServletServerHttpRequest(request),
            ServletServerHttpResponse(response),
            wsHandler,
            attributes,
        )

        assertFalse(result)
        assertEquals(401, response.status)
    }

    @Test
    fun `room not active returns 409`() {
        setupValidAuth()
        val roomId = RoomId.of("inactive-room").getOrThrow()
        val room = createRoom(roomId, RoomStatus.STOPPED, ownerId = testUserId)

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
    fun `room owned by different user returns 403`() {
        setupValidAuth()
        val roomId = RoomId.of("other-room").getOrThrow()
        val room = createRoom(roomId, RoomStatus.ACTIVE, ownerId = "other-user-99")

        request.requestURI = "/api/v1/signaling"
        request.queryString = "room_id=other-room"
        every { roomRegistry.resolve(roomId) } returns RoomResolutionResult.Found(room)

        val attributes = mutableMapOf<String, Any>()
        val result = interceptor.beforeHandshake(
            ServletServerHttpRequest(request),
            ServletServerHttpResponse(response),
            wsHandler,
            attributes,
        )

        assertFalse(result)
        assertEquals(403, response.status)
    }

    @Test
    fun `malformed room returns 502`() {
        setupValidAuth()
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
    fun `room without owner_id returns 502`() {
        setupValidAuth()
        val roomId = RoomId.of("malformed-owner").getOrThrow()
        request.requestURI = "/api/v1/signaling"
        request.queryString = "room_id=malformed-owner"
        every { roomRegistry.resolve(roomId) } returns RoomResolutionResult.Malformed("owner_id missing")

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
        setupValidAuth()
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

    private fun createRoom(roomId: RoomId, status: RoomStatus, ownerId: String = testUserId): Room {
        return Room(
            roomId = roomId,
            workerIp = "192.168.1.100",
            webrtcPort = 9090,
            gameId = "xonotic",
            status = status,
            createdAt = Instant.now(),
            ownerId = ownerId,
        )
    }
}