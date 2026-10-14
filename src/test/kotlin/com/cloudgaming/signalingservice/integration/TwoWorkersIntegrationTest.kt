package com.cloudgaming.signalingservice.integration

import com.cloudgaming.signalingservice.dto.ClientMessage
import com.cloudgaming.signalingservice.dto.ServerMessage
import com.cloudgaming.signalingservice.model.Room
import com.cloudgaming.signalingservice.model.RoomId
import com.cloudgaming.signalingservice.model.RoomResolutionResult
import com.cloudgaming.signalingservice.model.RoomStatus
import com.cloudgaming.signalingservice.repository.RoomRegistry
import com.cloudgaming.signalingservice.security.AuthenticationResult
import com.cloudgaming.signalingservice.security.HandshakeAuthenticator
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.mockk.every
import io.mockk.mockk
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.bean.override.convention.TestBean
import org.springframework.web.client.RestClient
import org.springframework.web.socket.TextMessage
import org.springframework.web.socket.client.standard.StandardWebSocketClient
import org.springframework.web.socket.handler.TextWebSocketHandler
import java.time.Instant
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TwoWorkersIntegrationTest {

    companion object {
        @JvmStatic
        fun roomRegistry(): RoomRegistry = mockk(relaxed = true)

        @JvmStatic
        fun handshakeAuthenticator(): HandshakeAuthenticator {
            val mock = mockk<HandshakeAuthenticator>()
            // По умолчанию возвращаем аутентифицированного user-A
            every { mock.authenticate(any()) } returns AuthenticationResult.Authenticated("user-A")
            return mock
        }
    }

    @TestBean
    private lateinit var roomRegistry: RoomRegistry

    @TestBean
    private lateinit var handshakeAuthenticator: HandshakeAuthenticator

    @LocalServerPort
    private var port: Int = 0

    private lateinit var restClient: RestClient
    private lateinit var workerA: MockWebServer
    private lateinit var workerB: MockWebServer
    private val objectMapper = jacksonObjectMapper()

    private val userA = "user-A"
    private val userB = "user-B"

    @BeforeEach
    fun setUp() {
        restClient = RestClient.builder()
            .baseUrl("http://localhost:$port")
            .build()

        workerA = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                    "/sdp" -> MockResponse().setBody("""{"sdp":"answer-from-worker-A","type":"answer"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }

        workerB = MockWebServer().apply {
            dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = when (request.path) {
                    "/ready" -> MockResponse().setBody("""{"status":"ready","pipeline":"PLAYING"}""")
                    "/sdp" -> MockResponse().setBody("""{"sdp":"answer-from-worker-B","type":"answer"}""")
                    else -> MockResponse().setResponseCode(404)
                }
            }
            start()
        }
    }

    @AfterEach
    fun tearDown() {
        workerA.shutdown()
        workerB.shutdown()
    }

    @Test
    fun `two different room_ids route to two different workers`() {
        val roomA = Room(
            roomId = RoomId.of("room-A").getOrThrow(),
            workerIp = "localhost",
            webrtcPort = workerA.port,
            gameId = "xonotic",
            status = RoomStatus.ACTIVE,
            createdAt = Instant.now(),
            ownerId = userA,
        )
        every { roomRegistry.resolve(RoomId.of("room-A").getOrThrow()) } returns RoomResolutionResult.Found(roomA)

        val roomB = Room(
            roomId = RoomId.of("room-B").getOrThrow(),
            workerIp = "localhost",
            webrtcPort = workerB.port,
            gameId = "xonotic",
            status = RoomStatus.ACTIVE,
            createdAt = Instant.now(),
            ownerId = userB,
        )
        every { roomRegistry.resolve(RoomId.of("room-B").getOrThrow()) } returns RoomResolutionResult.Found(roomB)

        val userHolder = AtomicReference(userA)
        every { handshakeAuthenticator.authenticate(any()) } answers {
            AuthenticationResult.Authenticated(userHolder.get())
        }

        userHolder.set(userA)
        val answerA = connectAndSendOffer("room-A", "offer-from-client-A", token = "dummy-token-A")

        userHolder.set(userB)
        val answerB = connectAndSendOffer("room-B", "offer-from-client-B", token = "dummy-token-B")

        assertEquals("answer-from-worker-A", answerA, "Room A should get answer from worker A")
        assertEquals("answer-from-worker-B", answerB, "Room B should get answer from worker B")
    }

    @Test
    fun `user B cannot access room A owned by user A`() {
        val roomA = Room(
            roomId = RoomId.of("room-A").getOrThrow(),
            workerIp = "localhost",
            webrtcPort = workerA.port,
            gameId = "xonotic",
            status = RoomStatus.ACTIVE,
            createdAt = Instant.now(),
            ownerId = userA,
        )
        every { roomRegistry.resolve(RoomId.of("room-A").getOrThrow()) } returns RoomResolutionResult.Found(roomA)

        every { handshakeAuthenticator.authenticate(any()) } returns AuthenticationResult.Authenticated(userB)
        val error = connectExpectingError("room-A", token = "dummy-token-B")

        assertTrue(
            error.contains("FORBIDDEN") || error.contains("403"),
            "Expected FORBIDDEN, got: $error",
        )
    }

    private fun connectAndSendOffer(roomId: String, offerSdp: String, token: String): String {
        val latch = CountDownLatch(1)
        val answerRef = AtomicReference<String>()
        val errorRef = AtomicReference<String>()

        val client = StandardWebSocketClient()
        val handler = object : TextWebSocketHandler() {
            override fun handleTextMessage(
                session: org.springframework.web.socket.WebSocketSession,
                message: TextMessage,
            ) {
                try {
                    val serverMessage = objectMapper.readValue(message.payload, ServerMessage::class.java)
                    when (serverMessage) {
                        is ServerMessage.Answer -> {
                            answerRef.set(serverMessage.sdp)
                            latch.countDown()
                        }

                        is ServerMessage.Error -> {
                            errorRef.set("${serverMessage.code}: ${serverMessage.message}")
                            latch.countDown()
                        }
                    }
                } catch (e: Exception) {
                    errorRef.set("Parse error: ${e.message}")
                    latch.countDown()
                }
            }

            override fun handleTransportError(
                session: org.springframework.web.socket.WebSocketSession,
                exception: Throwable,
            ) {
                errorRef.set("Transport error: ${exception.message}")
                latch.countDown()
            }
        }

        val wsUrl = "ws://localhost:$port/api/v1/signaling?room_id=$roomId&token=$token"

        val session = try {
            client.execute(handler, wsUrl).get(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            throw AssertionError("Failed to connect to WebSocket for room $roomId: ${e.message}", e)
        }

        val offer = ClientMessage.Offer(sdp = offerSdp)
        session.sendMessage(TextMessage(objectMapper.writeValueAsString(offer)))

        val received = latch.await(15, TimeUnit.SECONDS)
        session.close()

        if (!received) {
            throw AssertionError("Did not receive answer in time for room $roomId")
        }

        val error = errorRef.get()
        if (error != null) {
            throw AssertionError("Received error instead of answer for room $roomId: $error")
        }

        return answerRef.get() ?: throw AssertionError("No answer received for room $roomId")
    }

    private fun connectExpectingError(roomId: String, token: String): String {
        val latch = CountDownLatch(1)
        val errorRef = AtomicReference<String>()

        val client = StandardWebSocketClient()
        val handler = object : TextWebSocketHandler() {
            override fun handleTextMessage(
                session: org.springframework.web.socket.WebSocketSession,
                message: TextMessage,
            ) {
                try {
                    val serverMessage = objectMapper.readValue(message.payload, ServerMessage::class.java)
                    if (serverMessage is ServerMessage.Error) {
                        errorRef.set("${serverMessage.code}: ${serverMessage.message}")
                    }
                } catch (e: Exception) {
                    errorRef.set("Parse error: ${e.message}")
                }
                latch.countDown()
            }

            override fun handleTransportError(
                session: org.springframework.web.socket.WebSocketSession,
                exception: Throwable,
            ) {
                errorRef.set("Transport error: ${exception.message}")
                latch.countDown()
            }
        }

        val wsUrl = "ws://localhost:$port/api/v1/signaling?room_id=$roomId&token=$token"

        try {
            client.execute(handler, wsUrl).get(5, TimeUnit.SECONDS)
            latch.await(5, TimeUnit.SECONDS)
        } catch (e: Exception) {
            return "Handshake failed: ${e.message}"
        }

        return errorRef.get() ?: "No error received"
    }
}